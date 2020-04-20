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
09.20.19	1.2.01.	Initial Parent - Child driver release.
					Added link to Application that will check/update IPs if the communications fail.
10.01.19	1.3.01. Updated error handling.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "1.3.01" }

metadata {
	definition (name: "bleBox wLightBox",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Hubitat/master/Drivers/wLightBox.groovy"
			) {
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		attribute "commsError", "bool"
	}
	preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP (Current = ${getDataValue("deviceIP")})")
		}
		input ("transTime", "number", title: "Default Transition time (seconds)", defaultValue: 2)
		input ("refreshInterval", "enum", title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "15", "30"], defaultValue: "30")
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
	state.savedLevel = "00000000"
	runIn(1, updated)
}

def updated() {
	logInfo("Updating...")
	logInfo("Default fade speed set to ${state.defFadeSpeed}")
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	unschedule()

	if (!getDataValue("applicationVersion")) {
		if (!device_IP) {
			logWarn("updated:  deviceIP  is not set.")
			return
		}
		updateDataValue("deviceIP", device_IP)
		logInfo("Device IP set to ${getDataValue("deviceIP")}")
	}

	if(!getDataValue("driverVersion")) {
		sendGetCmd("/api/device/state", "setDeviceName")
		pauseExecution(1000)
		sendGetCmd("/api/rgbw/state", "addChildren")
		pauseExecution(5000)
		logInfo("updated: <b>successfully added children ${getChildDevices()}")
	}
	
	switch(refreshInterval) {
		case "1" : runEvery1Minute(refresh); break
		case "5" : runEvery5Minutes(refresh); break
		case "15" : runEvery15Minutes(refresh); break
		default: runEvery30Minutes(refresh)
	}
	logInfo("Refresh interval set for every ${refreshInterval} minute(s).")

	state.errorCount = 0
	state.fadeSpeed = transTime
	updateDataValue("driverVersion", driverVer())
	if (nameSync == "device" || nameSync == "hub") { syncName() }
	runIn(1, refresh)
}

def setDeviceName(response) {
	def cmdResponse = parseInput(response)
	logDebug("setDeviceData: ${cmdResponse}")
	device.setName(cmdResponse.device.type)
	logInfo("setDeviceData: Device Name updated to ${cmdResponse.device.type}")
}

def addChildren(response) {
	def cmdResponse = parseInput(response)
	logDebug("addChildren: Adding children for mode = ${mode}")

	def dni = device.getDeviceNetworkId()
	def channel
	def child
	switch(cmdResponse.rgbw.colorMode) {
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

def setRgbw(rgbw, transTime = state.fadeSpeed) {
	//	Common method to send new rgbw to device.
	logDebug("setRgbw: ${rgbw} / ${transTime}")
	fadeSpeed = 1000 * transTime.toInteger()
	sendPostCmd("/api/rgbw/set",
				"""{"rgbw":{"desiredColor":"${rgbw}","durationsMs":{"colorFade":${fadeSpeed}}}}""",
				"commandParse")
	state.fadeSpeed = transTime
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


//	===== Name Sync Capability =====
def syncName() {
	logDebug("syncName. Synchronizing device name and label with master = ${nameSync}")
	if (nameSync == "hub") {
		sendPostCmd("/api/settings/set",
					"""{"settings":{"deviceName":"${device.label}"}}""",
					"nameSyncHub")
	} else if (nameSync == "device") {
		sendGetCmd("/api/device/state", "nameSyncDevice")
	}
	device.updateSetting("nameSync",[type:"enum", value:"none"])
}
def nameSyncHub(response) {
	def cmdResponse = parseInput(response)
	logDebug("nameSyncHub: ${cmdResponse}")
	logInfo("Set bleBox device label to that of the Hubitat device.")
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