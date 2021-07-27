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
def apiLevel() { return 20200831 }

metadata {
	definition (name: "bleBox switchBoxD",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/bleBoxDevices/Drivers/switchBoxD.groovy"
			   ) {
		capability "Switch"
        capability "Actuator"
		capability "Refresh"
		attribute "commsError", "bool"
	}
	preferences {
		input ("shortPoll", "number",
			   title: "Fast Polling Interval ('0' = DISABLED)",
			   defaultValue: 0)
		if (getDataValue("apiLevel") != "00000000") {
			input ("statusLed", "bool", 
				   title: "Enable the Status LED", 
				   defaultValue: true)
		}
		input ("nameSync", "enum", title: "Synchronize Names",
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
def relayNo() { return getDataValue("relayNumber").toInteger() }

def installed() {
	logInfo("Installing...")
	if (deviceApi() >= 20190808) {
		sendGetCmd("/api/settings/state", "updateDeviceSettings")
	} else {
		sendGetCmd("/api/device/state", "updateDeviceSettings")
	}
	runIn(5, updated)
}

def updated() {
	logInfo("Updating...")
	unschedule()
	state.errorCount = 0
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
	
	//	Fast Polling
	if (shortPoll == null) { device.updateSetting("shortPoll",[type:"number", value:0]) }
	logInfo("fastPoll interval set to ${shortPoll}")

	setDevice()
	runIn(2, refresh)
}

def setDevice() {
	logDebug("setDevice: statusLed: ${statusLed}, nameSync = ${nameSync}")
	//	Relay string
	def relayString = """{"name":"${device.label}"}, {}"""
	if (getDataValue("relayNumber") == "1") {
		relayString = """{}, {"name":"${device.label}"}"""
	}
	def command = "/api/settings/set"
	def cmdText = """{"settings":{"""
	if (deviceApi() >= 20190808) {
		//	statusLED and Relay Name
		def ledEnabled = 1
		if (statusLed == false) { ledEnabled = 0 }
		cmdText = cmdText + """"statusLed":{"enabled":${ledEnabled}}"""
		//	Relay Name
		if (nameSync == "hub") {
			cmdText = cmdText + ""","relays":[${relayString}]"""
		}
		cmdText = cmdText + """}}"""
	} else {
		command = "/api/relays/set"
		//	relay name only
		if (nameSync == "hub") {
			cmdText = """,{"relays":[${relayString}]}"""
		}
	}
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
	def ledEnabled = 99
	def deviceName
	if (cmdResponse.settings) {
		ledEnabled = cmdResponse.settings.statusLed.enabled
		deviceName = cmdResponse.settings.relays[getDataValue("relayNumber").toInteger()].name
	} else if (cmdResponse.relays){
		deviceName = cmdResponse.relays[getDataValue("relayNumber").toInteger()].name
	} else {
		logWarn("updateSettings: Setting data not read properly. Check apiLevel.")
		return
	}
	def settingsUpdate = [:]
	//	Led Status
	if (ledEnabled != 99) {
		def statusLed = true
		if (ledEnabled == 0) {
			statusLed = false
		}
		device.updateSetting("statusLed",[type:"bool", value: statusLed])
		settingsUpdate << ["statusLed": statusLed]
	}
	//	Name - only update if syncing name
	if (nameSync != "none") {
		device.setLabel(deviceName)
		settingsUpdate << ["HubitatName": deviceName]
		device.updateSetting("nameSync",[type:"enum", value:"none"])
	}
	
	logInfo("updateDeviceSettings: ${settingsUpdate}")
}

//	===== Commands and Refresh with Response =====
def on() {
	logDebug("on")
	sendGetCmd("/s/${relayNo()}/1", "commandParse")
	runIn(3, refresh)
}

def off() {
	logDebug("off")
	sendGetCmd("/s/${relayNo()}/0", "commandParse")
}

def refresh() {
	logDebug("refresh")
	if (deviceApi() > 20190808) {
		sendGetCmd("/state", "commandParse")
	} else {
		sendGetCmd("/api/relay/state", "commandParse")
	}
}

def commandParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("commandParse: response = ${cmdResponse}")

	def thisRelay = cmdResponse.relays.find{ it.relay == relayNo() }
	def onOff = "off"
	if (thisRelay.state == 1) { onOff = "on" }
	if (onOff != device.currentValue("switch")) {
		sendEvent(name: "switch", value: onOff)
		logInfo("cmdResponse: switch = ${onOff}")
	}
	if (shortPoll.toInteger() > 0) { runIn(shortPoll.toInteger(), refresh) }
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
