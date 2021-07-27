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
7.30.21	Various edits to update to latest bleBox API Levels.
	a.	Create check for API Level of device.
		1)	Add STATE to recommend updating to user if out-of-sync.
		2)	Code to support all apiLevel up to the level defined in apiLevel().
	b.	Removed manual installation.
	c.	Air Sensor:
		1)	incorporated data element airQuality in latest apiLevels.
		2)	determine air quality based on sensor qualityLevel element.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "2.0.0" }
def apiLevel() { return 20200831 }	//	bleBox latest API Level, 6.16.2021

metadata {
	definition (name: "bleBox airSensor",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/bleBoxDevices/Drivers/airSensor.groovy"
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
		attribute "measurementTime", "string"
		command "forceMeasurement"
		capability "Refresh"
		attribute "commsError", "bool"
	}
	preferences {
		input ("statusLed", "bool", 
			   title: "Enable the Status LED", 
			   defaultValue: true)
		input ("nameSync", "enum", 
			   title: "Synchronize Names", 
			   defaultValue: "none",
			   options: ["none": "Don't synchronize",
						 "device" : "bleBox device name master", 
						 "hub" : "Hubitat label master"])
		input ("debug", "bool", 
			   title: "Enable debug logging", 
			   defaultValue: true)
		input ("descriptionText", "bool", 
			   title: "Enable description text logging", 
			   defaultValue: true)
	}
}

def deviceApi() { return getDataValue("apiLevel").toInteger() }

def installed() {
	logInfo("Installing...")
	sendGetCmd("/api/settings/state", "updateDeviceSettings")
	runIn(5, updated)
}

def updated() {
	logInfo("Updating...")
	unschedule()
	state.errorCount = 0
	
	//	Check apiLevel and provide state warning when old.
	if (apiLevel() > deviceApi()) {
		state.apiNote = "<b>Device api software is not the latest available. Consider updating."
	} else {
		state.remove("apiNote")
	}
	updateDataValue("driverVersion", driverVer())
	runEvery5Minutes(refresh)
	logInfo("Refresh interval set for every 5 minutes.")

	//	update data based on preferences
	if (debug) { runIn(1800, debugOff) }
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")

	setDevice()
	runIn(2, refresh)
}

//	===== Set Status LED =====
def setDevice() {
	logDebug("setDevice: statusLed: ${statusLed}, nameSync = ${nameSync}")
	//	Led
	def command = "/api/settings/set"
	def cmdText = """{"settings":{"""
	def ledEnabled = 1
	if (statusLed == false) { ledEnabled = 0 }
		cmdText = cmdText + """"statusLed":{"enabled":${ledEnabled}}"""
	//	Name
	if (nameSync == "hub") {
		cmdText = cmdText + ""","deviceName":"${device.label}"}}"""
	}
	cmdText = cmdText + """}}"""
	sendPostCmd(command, cmdText, "updateDeviceSettings")
}

def updateDeviceSettings(response) {
	def cmdResponse = parseInput(response)
	logDebug("updateDeviceSettings: ${cmdResponse}")
	//	Work around for null response due to shutter box returning null.
	if (cmdResponse == null) {
		if (state.nullResp == true) { return }
		state.nullResp = true
		pauseExecution(1000)
		sendGetCmd("/api/settings/state", "updateDeviceSettings")
		return
	}
	state.nullResp = false
	
	//	Capture Data
	def ledEnabled
	def deviceName
	if (cmdResponse.settings) {
		ledEnabled = cmdResponse.settings.statusLed.enabled
		deviceName = cmdResponse.settings.deviceName
	} else {
		logWarn("updateSettings: Setting data not read properly. Check apiLevel.")
		return
	}
	def settingsUpdate = [:]
	//	Led Status
	//	Led Status
	def statusLed = true
	if (ledEnabled == 0) {
		statusLed = false
	}
	device.updateSetting("statusLed",[type:"bool", value: statusLed])
	settingsUpdate << ["statusLed": statusLed]
	//	Name - only update if syncing name
	if (nameSync != "none") {
		device.setLabel(deviceName)
		settingsUpdate << ["HubitatName": deviceName]
		device.updateSetting("nameSync",[type:"enum", value:"none"])
	}

	logInfo("updateDeviceSettings: ${settingsUpdate}")
}

//	===== Commands and updating state =====
def forceMeasurement() {
	logDebug("forceMeasurment")
	if (deviceApi() >= 20191112) {
		sendGetCmd("/s/kick", "commandParse")
	} else {
		sendGetCmd("/api/air/kick", "commandParse")
		runIn(30, refresh)
	}
}

def refresh() {
	logDebug("refesh.")
	if (deviceApi() >= 20200229) {
		sendGetCmd("/state", "commandParse")
	} else {
		sendGetCmd("/api/air/state", "commandParse")
	}
}

def commandParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("commandParse: cmdResp = ${cmdResponse}")
	if(cmdResponse == null) { return }
	
	def pm1Data = cmdResponse.air.sensors.find{ it.type == "pm1" }
    def pm1Value = pm1Data.value.toInteger()
    def pm1Trend = getTrendText(pm1Data.trend)

	def pm2_5Data = cmdResponse.air.sensors.find{ it.type == "pm2.5" }
    def pm25Value = pm2_5Data.value.toInteger()
    def pm25Trend = getTrendText(pm2_5Data.trend)
	def pm25Quality = pm2_5Data.qualityLevel.toInteger()

	def pm10Data = cmdResponse.air.sensors.find{ it.type == "pm10" }
    def pm10Value = pm10Data.value.toInteger()
    def pm10Trend = getTrendText(pm10Data.trend)
	def pm10Quality = pm10Data.qualityLevel.toInteger()

	def airQualityLevel = Math.max(pm25Quality, pm10Quality)
	if (deviceApi() >= 20200229) {
		airQualityLevel = cmdResponse.air.airQualityLevel.toInteger()
	}
	def airQuality
    switch(airQualityLevel) {
    	case 1: airQuality = "Very Good" ; break
        case 2: airQuality = "Good" ; break
        case 3: airQuality = "Moderate" ; break
        case 4: airQuality = "Sufficient" ; break
        case 5: airQuality = "Bad" ; break
        case 6: airQuality = "Very Bad" ; break
        default: airQuality = "Not Measured"
    }

	sendEvent(name: "PM_1_Measurement", value: pm1Value, unit: 'µg/m³')
	sendEvent(name: "PM_1_Trend", value: pm1Trend)
	sendEvent(name: "PM_2_5_Measurement", value: pm25Value, unit: 'µg/m³')
	sendEvent(name: "PM_2_5_Trend", value: pm25Trend)
    sendEvent(name: "pm2_5Quality", value: pm25Quality)
	sendEvent(name: "PM_10_Measurement", value: pm10Value, unit: 'µg/m³')
	sendEvent(name: "PM_10_Trend", value: pm10Trend)
    sendEvent(name: "pm10Quality", value: pm10Quality)
    sendEvent(name: "airQuality", value: airQuality)
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

//	===== Communications =====
private sendGetCmd(command, action){
	logDebug("sendGetCmd: ${command} / ${action} / ${getDataValue("deviceIP")}")
	runIn(3, setCommsError)
	sendHubCommand(new hubitat.device.HubAction("GET ${command} HTTP/1.1\r\nHost: ${getDataValue("deviceIP")}\r\n\r\n",
				   hubitat.device.Protocol.LAN, null,[callback: action]))
}

private sendPostCmd(command, body, action){
	logDebug("sendPostCmd: ${command} / ${body} / ${action} / ${getDataValue("deviceIP")}")
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
	if (device.currentValue("commsError") == "true") {
		sendEvent(name: "commsError", value: false)
	}
	try {
		if(response.body == null) { return }
		def jsonSlurper = new groovy.json.JsonSlurper()
		return jsonSlurper.parseText(response.body)
	} catch (error) {
		logWarn "parseInput: Error attempting to parse: ${error}."
	}
}

def setCommsError() {
	logDebug("setCommsError")
	if (state.errorCount < 3) {
		state.errorCount+= 1
	} else if (state.errorCount == 3) {
		state.errorCount += 1
		sendEvent(name: "commsError", value: true)
		logWarn "setCommsError: Three consecutive communications errors."
	}
}

//	===== Utility Methods =====
def logTrace(msg) { log.trace "<b>${device.label} ${driverVer()}</b> ${msg}" }

def logInfo(msg) {
	if (descriptionText == true) { log.info "<b>${device.label} ${driverVer()}</b> ${msg}" }
}

def logDebug(msg){
	if(debug == true) { log.debug "<b>${device.label} ${driverVer()}</b> ${msg}" }
}

def debugOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("debugLogOff: Debug logging is off.")
}

def logWarn(msg){ log.warn "<b>${device.label} ${driverVer()}</b> ${msg}" }

//	end-of-file

