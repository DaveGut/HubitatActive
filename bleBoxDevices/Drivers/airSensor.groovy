/*
===== Blebox Hubitat Integration Driver

	Copyright 2019, Dave Gutheinz

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file except in compliance with the
License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.

DISCLAIMER: The author of this integration is not associated with blebox.  This code uses the blebox
open API documentation for development and is intended for integration into the Hubitat Environment.
===== Hiatory =====
08.14.19	Various edits.
08.14.19	Added Capability Sensor to provide hook for applications.
08.15.19	1.1.01. Integrated design notes at bottom and updated implementation per notes.
09.20.19	1.2.01.	Added link to Application that will check/update IPs if the communications fail.
10.01.19	1.3.01. Updated error handling.  Updated attributes and commands for better match with ST implementation.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "1.3.01" }

metadata {
	definition (name: "bleBox airSensor",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Hubitat/master/Drivers/airSensor.groovy"
			   ) {
		capability "Sensor"
		attribute "PM_1_Measurement", "string"
		attribute "PM_1_Trend", "string"
		attribute "PM_2_5_Measurement", "string"
		attribute "PM_2_5_Trend", "string"
		attribute "pm2_5Quality", "number"
		attribute "PM_10_Measurement", "string"
		attribute "PM_10_Trend", "string"
		attribute "pm10Quality", "number"
		attribute "airQuality", "string"
		attribute "airQualityLevel", "string"
		attribute "measurementTime", "string"
		command "forceMeasurement"
		attribute "updatingData", "bool"
		capability "Refresh"
		attribute "commsError", "bool"
	}
	preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP (Current = ${getDataValue("deviceIP")})")
		}
		input ("nameSync", "enum", title: "Synchronize Names", defaultValue: "none",
			   options: ["none": "Don't synchronize",
						 "device" : "bleBox device name master", 
						 "hub" : "Hubitat label master"])
		input ("statusLed", "bool", title: "Enable the Status LED", defaultValue: true)
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}

def installed() {
	logInfo("Installing...")
	runIn(2, updated)
}

def updated() {
	logInfo("Updating...")
	unschedule()
	
	if (!getDataValue("applicationVersion")) {
		if (!device_IP) {
			logWarn("updated:  deviceIP  is not set.")
			return
		}
		updateDataValue("deviceIP", device_IP)
		logInfo("Device IP set to ${getDataValue("deviceIP")}")
		//	Update device name on manual installation to standard name
		sendGetCmd("/api/device/state", "setDeviceName")
		pauseExecution(1000)
	}

	runEvery5Minutes(refresh)
	runIn(1, setLed)
	state.errorCount = 0
	updateDataValue("driverVersion", driverVer())

	logInfo("Refresh interval set for every 5 minutes.")
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")

	if (nameSync == "device" || nameSync == "hub") { syncName() }
	refresh()
}

def setDeviceName(response) {
	def cmdResponse = parseInput(response)
	logDebug("setDeviceData: ${cmdResponse}")
	device.setName(cmdResponse.device.type)
	logInfo("setDeviceData: Device Name updated to ${cmdResponse.device.type}")
}


//	===== Commands and updating state =====
def forceMeasurement() {
	logDebug("forceMeasurment")
	sendGetCmd("/api/air/kick", "kickParse")
}

def kickParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("kickParse.  Measurement has started and will take about 1 minute for results to show.")
	sendEvent(name: "updatingData", value: true)
	runIn(50, postKick)
}

def postKick() {
	logDebug("postKick.  Retrieving air quality data.")
	sendEvent(name: "kickActive", value: false)
	refresh()
}

def refresh() {
	logDebug("refesh.")
	sendGetCmd("/api/air/state", "commandParse")
}

def commandParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("commandParse: cmdResp = ${cmdResponse}")
    
	def pm1Data = cmdResponse.air.sensors.find{ it.type == "pm1" }
    def pm1Value = pm1Data.value.toInteger()
    def pm1Trend = getTrendText(pm1Data.trend)
    
	def pm2_5Data = cmdResponse.air.sensors.find{ it.type == "pm2.5" }
    def pm25Value = pm2_5Data.value.toInteger()
    def pm25Trend = getTrendText(pm2_5Data.trend)
    
	def pm10Data = cmdResponse.air.sensors.find{ it.type == "pm10" }
    def pm10Value = pm10Data.value.toInteger()
    def pm10Trend = getTrendText(pm10Data.trend)
//	===== SIMULATION DATA TO CHECK ALGORITHM =====
//	pm1Value = 10
//	pm25Value = 66
//	pm10Value = 133

//	Create Air Quality Index using EU standard for measurement. Reference:
//	"http://www.airqualitynow.eu/about_indices_definition.php", utilizing the 
//	Background Index for 1 hour against the sensor provided data.  Values are
//	0 to 500 with 100 per the grid values on the index.
	def pm25Quality
	switch(pm25Value) {
    	case 0..30: pm25Quality = (50 * pm25Value / 30).toInteger(); break
        case 31..55: pm25Quality = 20 + pm25Value - 30; break
        case 56..110: pm25Quality = 75 + (25 * (pm25Value - 55) / 55).toInteger(); break
        default: pm25Quality = (0.5 + (100 *pm25Value/110) ).toInteger()
    }

	def pm10Quality
	switch(pm10Value) {
    	case 0..30: pm10Quality = (50 * pm10Value / 50).toInteger(); break
        case 31..55: pm10Quality = 50 + (25 * (pm10Value - 50) / 40).toInteger(); break
        case 56..180: pm10Quality = 75 + (25 * (pm10Value - 90) / 90).toInteger(); break
        default: pm10Quality = (0.5 + (100*pm10Value /180)).toInteger()
    }

	def airQuality = Math.max(pm25Quality, pm10Quality)
    def airQualityLevel
    switch(airQuality) {
    	case 0..25: airQualityLevel = "Very Low" ; break
        case 26..50: airQualityLevel = "Low" ; break
        case 51..75: airQualityLevel = "Moderate" ; break
        case 75..100: airQualityLevel = "High" ; break
        default: airQualityLevel = "Very High"
    }

	sendEvent(name: "PM_1_Measurement", value: pm1Value, unit: 'µg/m³')
	sendEvent(name: "PM_1_Trend", value: pm1Trend)
	sendEvent(name: "PM_2_5_Measurement", value: pm25Value, unit: 'µg/m³')
	sendEvent(name: "PM_2_5_Trend", value: pm25Trend)
    sendEvent(name: "pm2_5Quality", value: pm25Quality)
	sendEvent(name: "PM_10_Measurement", value: pm10Value, unit: 'µg/m³')
	sendEvent(name: "PM_10_Trend", value: pm10Trend)
    sendEvent(name: "pm10Quality", value: pm10Quality)
   	sendEvent(name: "airQuality", value: airQuality, unit: "CAQI")
    sendEvent(name: "airQualityLevel", value: airQualityLevel)
	def now = new Date(now()).format("h:mm:ss a '\non' M/d/yyyy", location.timeZone).toLowerCase()
    sendEvent(name: "measurementTime", value: now)
	logInfo("commandParse: Air Quality Data, Index and Category Updated")
}

def getTrendText(trend) {
	def trendText
	switch(trend) {
		case 1: trendText = "Even"; break
		case 2: trendText = "Down"; break
		case 3: trendText = "Up"; break
		default: trendText = "no data"
	}
	return trendText
}


//	===== Set Status LED =====
def setLed() {
	logDebug("setLed")
	def enable = 0
	if (statusLed == true) { enable = 1 }
	sendPostCmd("/api/settings/set",
				"""{"settings":{"statusLed":{"enabled":${enable}}}}""",
				"ledStatusParse")
}

def ledStatusParse(response) {
	def cmdResponse = parseInput(response)
	state.statusLed = cmdResponse.settings.statusLed.enabled
	logDebug("ledStatusParse: ${cmdResponse}")
}


//	===== Name Sync Capability =====
def syncName() {
	logDebug("syncName: Synchronizing device name and label with master = ${nameSync}")
	if (nameSync == "hub") {
		sendPostCmd("/api/settings/set",
					"""{"settings":{"deviceName":"${device.label}","statusLed":{"enabled":1}}}""",
					"nameSyncHub")
	} else if (nameSync == "device") {
		sendGetCmd("/api/device/state", "nameSyncDevice")
	}
}
def nameSyncHub(response) {
	def cmdResponse = parseInput(response)
	logDebug("nameSyncHub: ${cmdResponse}")
	logInfo("Setting bleBox device label to that of the Hubitat device.")
}
def nameSyncDevice(response) {
	def cmdResponse = parseInput(response)
	logDebug("nameSyncDevice: ${cmdResponse}")
	def deviceName = cmdResponse.deviceName
	device.setLabel(deviceName)
	logInfo("Hubit name for device changed to ${deviceName}.")
}


//	===== Communications =====
private sendGetCmd(command, action){
	logDebug("sendGetCmd: ${command} / ${action} / ${getDataValue("deviceIP")}")
	state.lastCommand = [type: "get", command: "${command}", body: "n/a", action: "${action}"]
	runIn(3, setCommsError)
	sendHubCommand(new hubitat.device.HubAction("GET ${command} HTTP/1.1\r\nHost: ${getDataValue("deviceIP")}\r\n\r\n",
				   hubitat.device.Protocol.LAN, null,[callback: action]))
}
private sendPostCmd(command, body, action){
	logDebug("sendGetCmd: ${command} / ${body} / ${action} / ${getDataValue("deviceIP")}")
	state.lastCommand = [type: "post", command: "${command}", body: "${body}", action: "${action}"]
	runIn(3, setCommsError)
	def parameters = [ method: "POST",
					  path: command,
					  protocol: "hubitat.device.Protocol.LAN",
					  body: body,
					  headers: [
						  Host: getDataValue("deviceIP")
					  ]]
	sendHubCommand(new hubitat.device.HubAction(parameters, null, [callback: action]))
}
def parseInput(response) {
	unschedule(setCommsError)
	state.errorCount = 0
	sendEvent(name: "commsError", value: false)
	try {
		def jsonSlurper = new groovy.json.JsonSlurper()
		return jsonSlurper.parseText(response.body)
	} catch (error) {
		if (response.status == 204) {
			logDebug("parseInput: valid 204 return to kick command")
		} else {
			logWarn "CommsError: ${error}."
		}
	}
}
def setCommsError() {
	logDebug("setCommsError")
	if (state.errorCount < 3) {
		state.errorCount+= 1
		repeatCommand()
		logWarn("Attempt ${state.errorCount} to recover communications")
	} else if (state.errorCount == 3) {
		state.errorCount += 1
		if (getDataValue("applicationVersion")) {
			logWarn("setCommsError: Parent commanded to poll for devices to correct error.")
			parent.updateDeviceIps()
			runIn(90, repeatCommand)
		}
	} else {
		sendEvent(name: "commsError", value: true)
		logWarn "setCommsError: No response from device.  Refresh.  If off line " +
				"persists, check IP address of device."
	}
}
def repeatCommand() { 
	logDebug("repeatCommand: ${state.lastCommand}")
	if (state.lastCommand.type == "post") {
		sendPostCmd(state.lastCommand.command, state.lastCommand.body, state.lastCommand.action)
	} else {
		sendGetCmd(state.lastCommand.command, state.lastCommand.action)
	}
}


//	===== Utility Methods =====
def logInfo(msg) {
	if (descriptionText == true) { log.info "<b>${device.label} ${driverVer()}</b> ${msg}" }
}
def logDebug(msg){
	if(debug == true) { log.debug "<b>${device.label} ${driverVer()}</b> ${msg}" }
}
def logWarn(msg){ log.warn "<b>${device.label} ${driverVer()}</b> ${msg}" }

//	end-of-file