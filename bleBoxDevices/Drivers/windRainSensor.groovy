/*
===== Blebox Hubitat Integration Driver 2021 Updates
	Copyright 2021, Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file except in compliance with the
License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.

DISCLAIMER: The author of this integration is not associated with blebox.  This code uses the blebox
open API documentation for development and is intended for integration into the Hubitat Environment.

===== Hiatory =====
7.30.21	New driver for blebox windRainSensor
	a.	Version 2.0 supports only the wind sensor.  Rain sensor support will be provided
		when the rain sensor is selected.
	b.	Provides three winds speeds - now, average, max.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "2.0.0" }
def apiLevel() { return 20200831 }	//	bleBox latest API Level, 7.6.2021

metadata {
	definition (name: "bleBox windRainSensor",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/bleBoxDevices/Drivers/windRainSensor.groovy"
			   ) {
		capability "Refresh"
		attribute "windSpeed", "number"
		attribute "avgWind", "number"
		attribute "maxWind", "number"
//		attribute "raining", "string"
		attribute "commsError", "bool"
	}
	preferences {
		input ("windUnit", "enum",
			   title: "Unit for windSpeed reporting",
			   options: ["mps" : "Meters per Second",
						 "Kph" : "Kilometers per Hour",
						 "fps" : "Feet per Second",
						 "Mph" : "Miles per Hour"],
			   defautValue: "mps")
		input ("statusLed", "bool", 
			   title: "Enable the Status LED", 
			   defaultValue: true)
		input ("nameSync", "enum", 
			   title: "Synchronize Names", 
			   defaultValue: "none",
			   options: ["none": "Don't synchronize",
						 "device" : "bleBox device name master", 
						 "hub" : "Hubitat label master"])
		input ("refreshInterval", "enum", 
			   title: "Device Refresh Interval (minutes)",
			   options: ["1", "5", "15", "30"], 
			   defaultValue: "30")
		input ("debug", "bool", 
			   title: "Enable debug logging", 
			   defaultValue: true)
		input ("descriptionText", "bool", 
			   title: "Enable description text logging", 
			   defaultValue: true)
	}
}

def installed() {
	logInfo("Installing...")
	state.windError = false
	state.avgWindError = false
	state.maxWindError = false
	runIn(2, updated)
}

def updated() {
	logInfo("Updating...")
	unschedule()
	state.errorCount = 0
	updateDataValue("driverVersion", driverVer())
	//	Check apiLevel and provide state warning when old.
	if (apiLevel() > getDataValue("apiLevel").toInteger()) {
		state.apiNote = "<b>Device api software is not the latest available. Consider updating."
	} else {
		state.remove("apiNote")
	}

	switch(windUnit) {
		case "mps":
			state.speedFactor = 1
			state.speedUnit = "m/sec"
			break
		case "Kph":
			state.speedFactor = 3.6
			state.speedUnit = "K/Hr"
			break
		case "fps":
			state.speedFactor = 3.281
			state.speedUnit = "ft/sec"
			break
		case "Mph":
			state.speedFactor = 2.237
			state.speedUnit = "M/Hr"
			break
		default:
			state.speedFactor = 1
			state.speedUnit = "m/sec"
			break
	}

	//	update data based on preferences
	switch(refreshInterval) {
		case "1" : runEvery1Minute(refresh); break
		case "5" : runEvery5Minutes(refresh); break
		case "15" : runEvery15Minutes(refresh); break
		default: runEvery30Minutes(refresh)
	}
	logInfo("Refresh interval set for every ${refreshInterval} minute(s).")
	if (debug) { runIn(1800, debugOff) }
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")

	setDevice()
	runIn(2, refresh)
}

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

def refresh() {
	logDebug("refresh.")
	sendGetCmd("/state", "commandParse")
}

def commandParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("commandParse: cmdResponse = ${cmdResponse}")
	def statusUpdate = [:]
	def sensors = cmdResponse.multiSensor.sensors
	sensors.each {
		def type = it.type
		switch(type) {
			case "wind":
				def windSpeed = it.value.toInteger() * state.speedFactor
				if (device.currentValue("windSpeed") != windSpeed) {
					sendEvent(name: "windSpeed", value: windSpeed, unit: state.speedUnit)
					statusUpdate << ["windSpeed": windSpeed]
				}
				if (it.state.toInteger() == 3) {
					state.windError = true
				} else if (state.windError == true) {
					state.windError = false
				}
				break
			case "windAvg":
				def avgWind = it.value.toInteger() * state.speedFactor
				if (device.currentValue("avgWind") != avgWind) {
					sendEvent(name: "avgWind", value: avgWind, unit: state.speedUnit)
					statusUpdate << ["avgWind": avgWind]
				}
				if (it.state.toInteger() == 3) {
					state.avgwindError = true
				} else if (state.avgwindError == true) {
					state.avgwindError = false
				}
				break
			case "windMax":
				def maxWind = it.value.toInteger() * state.speedFactor
				if (device.currentValue("maxWind") != maxWind) {
					sendEvent(name: "maxWind", value: maxWind, unit: state.speedUnit)
					statusUpdate << ["maxWind": maxWind]
				}
				if (it.state.toInteger() == 3) {
					state.maxwindError = true
				} else if (state.avgwindError == true) {
					state.maxwindError = false
				}
				break
			default:
				break
		}
	}
	
	logInfo("commandParse: ${statusUpdate}")
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
def logTrace(msg) { log.trace "${device.label} ${driverVer()} ${msg}" }

def logInfo(msg) {
	if (descriptionText == true) { log.info "${device.label} ${driverVer()} ${msg}" }
}

def logDebug(msg){
	if(debug == true) { log.debug "${device.label} ${driverVer()} ${msg}" }
}

def debugOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("debugLogOff: Debug logging is off.")
}

def logWarn(msg){ log.warn "${device.label} ${driverVer()} ${msg}" }

//	end-of-file
