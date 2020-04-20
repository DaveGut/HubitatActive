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
8.14.19	Various edits.
08.15.19	1.1.01. Integrated design notes at bottom and updated implementation per notes.
09.20.19	1.2.01.	a.	Added link to Application that will check/update IPs if the communications fail.
					b.	Added configure method that sets as dimmable or undimmable.
					c.	Combined two dimmerBox drivers into one.
10.01.19	1.3.01. Updated error handling.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "1.3.01" }

metadata {
	definition (name: "bleBox dimmerBox",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Hubitat/master/Drivers/dimmerBox.groovy"
			   ) {
		capability "Light"
		capability "Switch Level"
		capability "Actuator"
		capability "Refresh"
		attribute "commsError", "bool"
	}
	preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP (Current = ${getDataValue("deviceIP")})")
		}
		input ("transTime", "num", title: "Default Transition time (0 - 60 seconds maximum)",
			   defaultValue: 1)
		input ("refreshInterval", "enum", title: "Device Refresh Interval (minutes)",
			   options: ["1", "5", "15", "30"], defaultValue: "30")
		input ("shortPoll", "number",title: "Fast Polling Interval ('0' = DISABLED)",
			   defaultValue: 0)
		input ("nameSync", "enum", title: "Synchronize Names", defaultValue: "none",
			   options: ["none": "Don't synchronize",
						 "device" : "bleBox device name master", 
						 "hub" : "Hubitat label master"])
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}

def installed() {
	logInfo("Installing...")
	state.savedLevel = 255
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

	if (!getDataValue("mode")) {
		sendGetCmd("/api/dimmer/state", "setDimmerMode")
	}

	switch(refreshInterval) {
		case "1" : runEvery1Minute(refresh); break
		case "5" : runEvery5Minutes(refresh); break
		case "15" : runEvery15Minutes(refresh); break
		default: runEvery30Minutes(refresh)
	}
	if (shortPoll == null) { device.updateSetting("shortPoll",[type:"number", value:0]) }
	state.errorCount = 0
	state.defFadeSpeed = getFadeSpeed(transTime)
	updateDataValue("driverVersion", driverVer())

	logInfo("fastPoll interval set to ${shortPoll}")
	logInfo("Default Fade Speed set to ${state.defFadeSpeed} seconds")
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	logInfo("Refresh interval set for every ${refreshInterval} minute(s).")

	if (nameSync == "device" || nameSync == "hub") { runIn(5, syncName) }
	runIn(2, refresh)
}

def setDeviceName(response) {
	def cmdResponse = parseInput(response)
	logDebug("setDeviceData: ${cmdResponse}")
	device.setName(cmdResponse.device.type)
	logInfo("setDeviceData: Device Name updated to ${cmdResponse.device.type}")
}

def setDimmerMode(response) {
	def cmdResponse = parseInput(response)
	logDebug("setDimmerMode: ${cmdResponse}")
	def mode = "dimmable"
	if (cmdResponse.dimmer.loadType == "2") {
		mode = "undimmable"
		sendEvent(name: "level", value: null)
	}
	updateDataValue("mode", mode)
}


//	===== Device Commands and Parse	=====
def on() {
	logDebug("on")
	sendPostCmd("/api/dimmer/set",
				"""{"dimmer":{"desiredBrightness":${state.savedLevel},"fadeSpeed":${state.defFadeSpeed}}}""",
				"commandParse")
}

def off() {
	logDebug("off")
	sendPostCmd("/api/dimmer/set",
				"""{"dimmer":{"desiredBrightness":0,"fadeSpeed":${state.defFadeSpeed}}}""",
				"commandParse")
}

def setLevel(level, transitionTime = null) {
	if (getDataValue("mode") == "dimmable") {
		def fadeSpeed = state.defFadeSpeed
		if (transitionTime != null) { fadeSpeed = getFadeSpeed(transitionTime) }
		logDebug("setLevel: level = ${level} // ${fadeSpeed}")
		level255 = (2.55 * level + 0.5).toInteger()
		state.savedLevel = level255
		sendPostCmd("/api/dimmer/set",
					"""{"dimmer":{"desiredBrightness":${level255},"fadeSpeed":${fadeSpeed}}}""",
					"commandParse")
	}
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
	sendGetCmd("/api/dimmer/state", "commandParse")
}

def commandParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("commandParse: response = ${cmdResponse}")

	def level = cmdResponse.dimmer.desiredBrightness
	level = (0.5 + level/ 2.55).toInteger()
	def onOff = "off"
	if (level > 0) { onOff = "on" }
	sendEvent(name: "switch", value: onOff)
	if (getDataValue("mode") == "dimmable") {
		sendEvent(name: "level", value: level)
	}
	logInfo "commandParse: switch = ${onOff}, level = ${level}"
	if (state.pollInterval != "0") {
		runIn(state.pollInterval.toInteger(), quickPoll)
	}
}


//	===== Name Sync Capability =====
def syncName() {
	logDebug("syncName. Synchronizing device name and label with master = ${nameSync}")
	if (nameSync == "hub") {
		sendPostCmd("/api/device/set",
					"""{"device":{"deviceName":"${device.label}"}}""",
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
	def deviceName = cmdResponse.device.deviceName
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
		logWarn "CommsError: ${error}."
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