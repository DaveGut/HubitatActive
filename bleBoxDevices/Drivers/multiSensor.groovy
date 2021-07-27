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
7.30.21	New Parent driver for blebox multiSensor units
	a.	Version 2.0 supports only type = temperature.  Future should support
		types for wind and rain measurement.
	b.	On install, creates child based on type in setting.
	c.	Parent has following functions:
		1.	Set refresh rate (1 minute to 30 minutes).
		2.	Complete a manual reset.
		3.	Allows post-parent-install adding children
		4.	Provides all communications to device for parent and children.
		5.	Communications error detection overall health reporting.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "2.0.0" }
def apiLevel() { return 20210413 }	//	bleBox latest API Level, 7.6.2021

metadata {
	definition (name: "bleBox multiSensor",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/bleBoxDevices/Drivers/multiSensor.groovy"
			   ) {
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
		input ("addChild", "bool", 
			   title: "Add New Child sensors", 
			   defaultValue: false)
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
	sendGetCmd("/api/settings/state", "createChildren")
	runIn(5, updated)
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
	//	Add Children (toggle) plus Refresh
	if (addChild) {
		sendGetCmd("/api/settings/state", "createChildren")
		device.updateSetting("addChild",[type:"bool", value:false])
		pauseExecution(3000)
	}

	setDevice()
	runIn(2, refresh)
}

def createChildren(response) {
	def cmdResponse = parseInput(response)
	logDebug("createChildren: ${cmdResponse}")
	def settingsArrays = cmdResponse.settings.multiSensor
	settingsArrays.each {
		def type = it.type
		def sensorDni = "${device.getDeviceNetworkId()}-${it.id}"
		def isChild = getChildDevice(sensorDni)
		if (!isChild && it.settings.enabled.toInteger() == 1) {
			try {
				addChildDevice("davegut", "bleBox MSChild ${type}", sensorDni, [
					"label":it.settings.name, 
					"name":"tempSensorChild",
					"apiLevel":getDataValue("apiLevel"), 
					"tempOffset":it.settings.userTempOffset, 
					"sensorId":it.id.toString()])
				logInfo("Installed ${it.settings.name}.")
			} catch (error) {
				logWarn("Failed to install device. Device: ${device}, sensorId = ${it.id}.")
				logWarn(error)
			}
		}
	}
	updateDeviceSettings(response)
	logInfo("createChildren: statusLed = ${ledStatus}")
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
	//	Transfer data to children
	def settingsArrays = cmdResponse.settings.multiSensor
	def children = getChildDevices()
	children.each { it.updateDeviceSettings(settingsArrays) }
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

//	===== Commands and Parse Returns =====
def refresh() {
	logDebug("refresh.")
	sendGetCmd("/state", "commandParse")
}

def commandParse(response) {
	def stateArrays
	def cmdResponse = parseInput(response)
	logDebug("commandParse: cmdResponse = ${cmdResponse}")
	stateArrays = cmdResponse.multiSensor.sensors

	def children = getChildDevices()
	children.each { it.commandParse(stateArrays) }
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
