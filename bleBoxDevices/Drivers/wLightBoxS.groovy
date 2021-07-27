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
def driverVer() { return "D2.0.0" }
def apiLevel() { return 20150206 }	//	bleBox latest API Level, 6.16.2021

metadata {
	definition (name: "bleBox wLightBoxS",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/bleBoxDevices/Drivers/wLightBoxS.groovy"
			   ) {
		capability "Light"
		capability "Switch"
		capability "Switch Level"
		capability "Actuator"
		capability "Refresh"
		attribute "commsError", "bool"
	}
	preferences {
		input ("transTime", "num", 
			   title: "Default Transition time (0 - 60 seconds maximum)", 
			   defaultValue: 1)
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
	state.savedLevel = "FF"
	runIn(2, updated)
}

def updated() {
	logInfo("Updating...")
	unschedule()
	state.errorCount = 0
	state.nullResp = false
	state.defFadeSpeed = getFadeSpeed(transTime)
	updateDataValue("driverVersion", driverVer())
	
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
	def command = "/api/device/set"
	def cmdText = """{"device":{"""
	//	Name
	if (nameSync == "hub") {
		cmdText = cmdText + """"deviceName":"${device.label}"}}"""
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
	def deviceName
	if (cmdResponse.device) {
		deviceName = cmdResponse.device.deviceName
	} else {
		logWarn("updateSettings: Setting data not read properly. Check apiLevel.")
		return
	}
	def settingsUpdate = [:]
	//	Name - only update if syncing name
	if (nameSync != "none") {
		device.setLabel(deviceName)
		settingsUpdate << ["HubitatName": deviceName]
		device.updateSetting("nameSync",[type:"enum", value:"none"])
	}

	logInfo("updateDeviceSettings: ${settingsUpdate}")
}

//	===== Commands and Parse Returns =====
def on() {
	logDebug("on")
	sendPostCmd("/api/light/set",
				"""{"light":{"desiredColor":"${state.savedLevel}","fadeSpeed":${state.defFadeSpeed}}}""",
				"commandParse")
}

def off() {
	logDebug("off")
	sendPostCmd("/api/light/set",
				"""{"light":{"desiredColor":"00","fadeSpeed":${state.defFadeSpeed}}}""",
				"commandParse")
}

def setLevel(level, transitionTime = null) {
	def fadeSpeed = state.defFadeSpeed
	if (transitionTime != null) { fadeSpeed = getFadeSpeed(transitionTime) }
	logDebug("setLevel: level = ${level} // ${fadeSpeed}")
	level = (2.55 * level + 0.5).toInteger()
	def hexLevel = hubitat.helper.HexUtils.integerToHexString(level, 1)
	state.savedLevel = "${hexLevel}"
	sendPostCmd("/api/light/set",
				"""{"light":{"desiredColor":"${hexLevel}","fadeSpeed":${fadeSpeed}}}""",
				"commandParse")
}

def getFadeSpeed(transitionTime) {
	logDebug("getFadeSpeed: ${transitionTime}")
	def timeIndex = (10* transitionTime.toFloat()).toInteger()
	def fadeSpeed
	switch (timeIndex) {
		case 0: fadeSpeed = 255; break
		case 1..7 :		fadeSpeed = 234; break
		case 8..15 :	fadeSpeed = 229; break
		case 16..25 :	fadeSpeed = 219; break
		case 26..35 : 	fadeSpeed = 215; break
		case 36..45 : 	fadeSpeed = 213; break
		case 46..55 : 	fadeSpeed = 212; break
		case 56..65 :	fadeSpeed = 211; break
		case 66..90 : 	fadeSpeed = 209; break
		case 91..125 : 	fadeSpeed = 207; break
		case 126..175 : fadeSpeed = 202; break
		case 176..225 : fadeSpeed = 199; break
		case 226..275 : fadeSpeed = 197; break
		case 276..350 :	fadeSpeed = 194; break
		case 351..450 : fadeSpeed = 189; break
		case 451..550 : fadeSpeed = 185; break
		default: fadeSpeed = 179
	}
	return fadeSpeed
}

def refresh() {
	logDebug("refresh")
	sendGetCmd("/api/light/state", "commandParse")
}

def commandParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("commandParse: response = ${cmdResponse}")
	def hexLevel = cmdResponse.light.desiredColor.toUpperCase()
	if (hexLevel == "00") {
		sendEvent(name: "switch", value: "off")
		sendEvent(name: "level", value: 0)
		logInfo "commandParse: switch = off"
	} else {
		sendEvent(name: "switch", value: "on")
		def brightness = hubitat.helper.HexUtils.hexStringToInt(hexLevel)
		def level = (0.5 + brightness/ 2.55).toInteger()
		sendEvent(name: "level", value: level)
		logInfo "commandParse: switch = on, level = ${level}"
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
