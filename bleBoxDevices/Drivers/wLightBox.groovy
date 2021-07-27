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
def apiLevel() { return 20200229 }	//	bleBox latest API Level, 6.16.2021

metadata {
	definition (name: "bleBox wLightBox",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/bleBoxDevices/Drivers/wLightBox.groovy"
			) {
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		attribute "commsError", "bool"
	}
	preferences {
		input ("transTime", "integer", 
			   title: "Default Transition time (seconds)", 
			   defaultValue: 2)
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
	state.savedLevel = "00000000"
	sendGetCmd("/api/rgbw/state", "addChildren")
	runIn(5, updated)
}

def addChildren(response) {
	def cmdResponse = parseInput(response)
	def colorMode = cmdResponse.rgbw.colorMode
	logDebug("addChildren: Adding children for mode = ${colorMode}")

	def dni = device.getDeviceNetworkId()
	def channel
	def child
	switch(colorMode) {
		case "1":
			channel = "rgbw"
			addChild("wLightBox Rgbw", "${dni}-1", "${device.displayName} Rgbw", channel)
			break
		case "2":
			channel = "rgb"
			addChild("wLightBox Rgb", "${dni}-1", "${device.displayName} Rgb", channel)
			break
		case "3":
			channel = "ch1"
			addChild("wLightBox Mono", "${dni}-1", "${device.displayName} Ch1", channel)
			pauseExecution(1000)
			channel = "ch2"
			addChild("wLightBox Mono", "${dni}-2", "${device.displayName} Ch2", channel)
			pauseExecution(1000)
			channel = "ch3"
			addChild("wLightBox Mono", "${dni}-3", "${device.displayName} Ch3", channel)
			pauseExecution(1000)
			channel = "ch4"
			addChild("wLightBox Mono", "${dni}-4", "${device.displayName} Ch4", channel)
			break
		case "4":
			channel = "rgb"
			addChild("wLightBox Rgb", "${dni}-1", "${device.displayName} Rgb", channel)
			pauseExecution(1000)
			channel = "ch4"
			addChild("wLightBox Mono", "${dni}-2", "${device.displayName} White", channel)
			break
		case "5":
			channel = "ct1"
			addChild("wLightBox Ct", "${dni}-1", "${device.displayName} Ct1", channel)
			break
		case "6":
			channel = "ct1"
			addChild("wLightBox Ct", "${dni}-1", "${device.displayName} Ct1", channel)
			pauseExecution(1000)
			channel = "ct2"
			addChild("wLightBox Ct", "${dni}-2", "${device.displayName} Ct2", channel)
			break
		default: 
			logWarn("addChildren: No channel detected in message from device: ${cmdResponse}")
			break
	}
	return
}

def addChild(type, dni, label, channel) {
	logDebug("addChild: ${type} / ${dni} / ${label} / ${channel}")
	try {
		addChildDevice("davegut", "bleBox ${type}", "${dni}", [
			"name": type, "label": label, "channel": channel, isComponent: false])
	} catch (error) {
		logWarn("addChild: failed. Error = ${error}")
		return
	}
	logInfo("addChild: Added child ${type} / ${dni} / ${label} / ${channel}")
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
	logInfo("Refresh interval set for every ${refreshInterval} minute(s).")
	if (debug) { runIn(1800, debugOff) }
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")

	setDevice()
	runIn(2, refresh)
}

def setDevice() {
	logDebug("setDevice: statusLed: ${statusLed}, nameSync = ${nameSync}")
	def command = "/api/settings/set"
	def cmdText = """{"settings":{"""
	if (deviceApi() < 20180718) {
		command = "/api/device/set"
		cmdText = """{"device":{"""
	}
	//	Led
	if (deviceApi() >= 20180718) {
		def ledEnabled = 1
		if (statusLed == false) { ledEnabled = 0 }
		cmdText = cmdText + """"statusLed":{"enabled":${ledEnabled}}"""
	}
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
	def ledEnabled = 1
	def deviceName
	if (cmdResponse.settings) {
		ledEnabled = cmdResponse.settings.statusLed.enabled
		deviceName = cmdResponse.settings.deviceName
	} else if (cmdResponse.device) {
		deviceName = cmdResponse.device.deviceName
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

//	===== Commands and Parse Returns =====
def on() {
	logDebug("on")
	setRgbw(state.savedLevel)
}

def off() {
	logDebug("off")
	setRgbw("00000000")
}

def childCommand(channel, level, transTime=state.fadeSpeed) {
	logDebug("parseChildInput: ${channel}, ${level}, ${transTime}")
	if(transTime == null){ transTime = state.fadeSpeed }
	def rgbwNow = state.savedLevel
	switch (channel) {
		case "rgbw":
			setRgbw(level, transTime)
			break
		case "rgb":
			setRgbw(level + rgbwNow[6..7], transTime)
			break
		case "ch1":
			setRgbw(level + rgbwNow[2..7], transTime)
			break
		case "ch2":
			setRgbw(rgbwNow[0..1] + level + rgbwNow[4..7], transTime)
			break
		case "ch3":
			setRgbw(rgbwNow[0..3] + level + rgbwNow[6..7], transTime)
			break
		case "ch4":
			setRgbw(rgbwNow[0..5] + level, transTime)
			break
		case "ct1":
			setRgbw(level + rgbwNow[4..7], transTime)
			break
		case "ct2":
			setRgbw(rgbwNow[0..3] + level, transTime)
			break
		default:
			setRgbw(rgbwNow)
	}
}

def setRgbw(rgbw, fadeSpeed = transTime) {
	//	Common method to send new rgbw to device.
	logDebug("setRgbw: ${rgbw} / ${fadeSpeed}")
	fadeSpeed = 1000 * fadeSpeed.toInteger()
	sendPostCmd("/api/rgbw/set",
				"""{"rgbw":{"desiredColor":"${rgbw}","durationsMs":{"colorFade":${fadeSpeed}}}}""",
				"commandParse")
}

def refresh() {
	logDebug("refresh.")
	sendGetCmd("/api/rgbw/state", "commandParse")
}

def commandParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("commandParse: ${cmdResponse}")
	def hexDesired = cmdResponse.rgbw.desiredColor.toUpperCase()
	if (hexDesired == "00000000") {
		sendEvent(name: "switch", value: "off")
	} else {
		sendEvent(name: "switch", value: "on")
		state.savedLevel = hexDesired
	}
	//	Return data to the children
	def children = getChildDevices()
	children.each { it.parseReturnData(hexDesired) }
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

