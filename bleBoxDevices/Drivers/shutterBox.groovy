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
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "2.0.0" }
def apiLevel() { return 20190911 }	//	bleBox latest API Level, 6.16.2021

metadata {
	definition (name: "bleBox shutterBox",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/bleBoxDevices/Drivers/shutterBox.groovy"
			   ) {
		capability "Window Shade"
		capability "Refresh"
		command "stop"
		command "setTilt", ["NUMBER"]
		attribute "tilt", "number"
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
	state.nullResp = false
	updateDataValue("driverVersion", driverVer())
	
	//	Check apiLevel and provide state warning when old.
	if (apiLevel() > deviceApi()) {
		state.apiNote = "<b>Device api software is not the latest available. Consider updating."
	} else {
		state.remove("apiNote")
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
	def controlType
	if (cmdResponse.settings) {
		ledEnabled = cmdResponse.settings.statusLed.enabled
		deviceName = cmdResponse.settings.deviceName
		controlType = cmdResponse.settings.shutter.controlType
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
	//	Shutter Mode
	def mode
	switch (controlType) {
		case "1": mode = "roller" ; break
		case "2": mode = "withoutPositioning"; break
		case "3": mode = "tilt"; break
		case "4": mode = "windowOpener"; break
		case "5": mode = "material"; break
		case "6": mode = "awning"; break
		case "7": mode = "screen"; break
		default: mode = "notSet"
	}
	updateDataValue("mode", mode)
	if (mode != "tilt") { sendEvent(name: "tilt", value: null) }
	settingsUpdate << ["mode": mode]

	logInfo("updateDeviceSettings: ${settingsUpdate}")
}

//	===== Commands and updating state =====
def open() {
	logDebug("open")
	sendGetCmd("/s/u", "commandParse")
}

def close() {
	logDebug("close")
	sendGetCmd("/s/d", "commandParse")
}

def stop() {
	logDebug("stop")
	sendGetCmd("/api/shutter/state", "stopParse")
}

def stopParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("stopParse: cmdResponse = ${cmdResponse}")
	def stopPosition = cmdResponse.shutter.currentPos.position
	setPosition(stopPosition.toInteger())
}

def startPositionChange(change) {
	logDebug("startPositionChange: ${change}")
	if (change == "open") { 
		sendGetCmd("/s/u", "commandParse") 
	} else { 
		sendGetCmd("/s/d", "commandParse") 
	}
}

def stopPositionChange() {
	logDebug("stopPositionChange:")
	sendGetCmd("/api/shutter/state", "stopParse")
}

def setPosition(percentage) {
	logDebug("setPosition: percentage = ${percentage}")
	runIn(15, refresh)
	sendGetCmd("/s/p/${percentage}", "commandParse")
}

def setTilt(percentage) {
	if (getDataValue("mode") == "tilt") {
		logDebug("setTilt: percentage = ${percentage}")
		runIn(15, refresh)
		sendGetCmd("/s/t/${percentage}", "commandParse")
	} else {
		logWarn("setTilt: Device Mode is not Tilt")
	}
}

def refresh() {
	logDebug("refresh")
	sendGetCmd("/api/shutter/state", "commandParse")
}

def commandParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("commandParse: cmdResponse = ${cmdResponse}")
	def shutter = cmdResponse.shutter
	def currPos = shutter.currentPos.position
	def currTilt = shutter.currentPos.tilt
	if (currPos == -1) {
		state.calibration = "device is not calibrated"
	} else {
		state.remove("calibration")
	}
	def posData = [:]

	def windowShade
	switch (shutter.state) {
		case 0:
			windowShade = "closing"
			break
		case 1:
			windowShade = "opening"
			break
		case 2:
			windowShade = "partially open"
			break
		case 3:
			windowShade = "closed"
			break
		case 4:
			windowShade = "open"
			break
		default:
			windowShade = "unknown"
	}
	if (windowShade != device.currentValue("windowShade")) {
		sendEvent(name: "windowShade", value: windowShade)
		posData << ["WindowShade": windowShade]
	}
	if (device.currentValue("position") != currPos) {
		sendEvent(name: "position", value: currPos)
		posData << ["position": currPos]
	}
	if (getDataValue("mode") == "tilt" && device.currentValue("tilt") != currTilt) {
		sendEvent(name: "tilt", value: currTilt)
		posData << ["tilt": currTilt]
	}
	if (posData != [:]) {
		logInfo("commandParse: ${posData}")
	}
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

