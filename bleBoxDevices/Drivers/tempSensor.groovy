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
7.30.21	Various edits to update to latest bleBox API Levels.
	a.	Create check for API Level of device.
		1)	Add STATE to recommend updating to user if out-of-sync.
		2)	Code to send correct commands and properly parse.
	b.	Removed manual installation.
	c.	Temp Sensor:  Added capability to insert Temp Offset in degrees C if
		apiLevel = 20210118
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "2.0.0" }
def apiLevel() { return 20210118 }	//	bleBox latest API Level, 6.16.2021

metadata {
	definition (name: "bleBox tempSensor",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/bleBoxDevices/Drivers/tempSensor.groovy"
			   ) {
		capability "Temperature Measurement"
		attribute "trend", "string"
		attribute "sensorHealth", "string"
		capability "Refresh"
		attribute "commsError", "bool"
	}
	preferences {
		input ("tOffset", "integer",
			   title: "temperature offset in 10 times degrees C [-120 -> +120]")
		input ("tempScale", "enum", 
			   title: "Temperature Scale", 
			   options: ["C", "F"], 
			   defaultValue: "C")
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
	runIn(2, updated)
}

def updated() {
	logInfo("Updating...")
	unschedule()
	state.errorCount = 0
	//	Capture settings statusLed and tempOffset when version has changed.
	if (driverVer() != getDataValue("driverVersion")) {
		sendGetCmd("/api/settings/state", "updateDeviceSettings")
		pauseExecution(4000)
		updateDataValue("driverVersion", driverVer())
	}
	
	//	Check apiLevel and provide state warning when old.
	if (apiLevel() > deviceApi()) {
		state.apiNote = "<b>Device api software is not the latest available. Consider updating."
	} else {
		state.remove("apiNote")
	}
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
	def command = "/api/settings/set"
	def cmdText = """{"settings":{"""
	//	tempOffset
	if (tOffset != 0 && deviceApi() < 20180118) {
		logWarn("setDevice: tempOffset available only to apiLevel above 20180118.")
	} else {
		cmdText = cmdText + """"tempSensor":{"userTempOffset":{"0":${tOffset}}}"""
	}
	//	Led
	def ledEnabled = 1
	if (statusLed == false) { ledEnabled = 0 }
	cmdText = cmdText + ""","statusLed":{"enabled":${ledEnabled}}"""
	
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
	//	When setting ledStatus, immediate return is null.
	if (cmdResponse == null) {
		if (state.nullResp == true) { return }
		state.nullResp = true
		pauseExecution(1000)
		sendGetCmd("/api/settings/state", "updateDeviceSettings")
		return
	}
	state.nullResp = false
	
	//	Capture Data
	def tempOffset = 0
	def ledEnabled
	def deviceName
	if (cmdResponse.settings) {
		if (deviceApi() >= 20210118) {
			tempOffset = cmdResponse.settings.tempSensor.userTempOffset."0"
		}
		ledEnabled = cmdResponse.settings.statusLed.enabled
		deviceName = cmdResponse.settings.deviceName
	} else {
		logWarn("updateSettings: Setting data not read properly. Check apiLevel.")
		return
	}
	def settingsUpdate = [:]

	//	Temp Offset
	device.updateSetting("tOffset",[type:"number", value:tempOffset])
	settingsUpdate << ["tempOffset": tempOffset]

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
	if (deviceApi() >= 20200229) {
		sendGetCmd("/state", "commandParse")
	} else {
		sendGetCmd("/api/tempsensor/state", "commandParse")
	}
}

def commandParse(response) {
	def cmdResponse = parseInput(response)
	logDebug("commandParse: cmdResponse = ${cmdResponse}")

	def respData = cmdResponse.tempSensor.sensors[0]
	def temperature = Math.round(respData.value.toInteger() / 10) / 10
	if (tempScale == "F") {
		temperature = Math.round((3200 + 9*respData.value.toInteger() / 5) / 10) / 10
	}
	def trend
	switch(respData.trend) {
		case "1": trend = "even"; break
		case "2": trend = "down"; break
		case "3": trend = "up"; break
		default: trend = "No Data"
	}
	def sensorHealth = "normal"
	if (respData.state == "3") {
		sensorHealth = "sensor error"
		logWarn("Sensor Error")
	}

	def statusUpdate = [:]
	def isChange = false
	if (temperature != device.currentValue("temperature")) {
		sendEvent(name: "temperature", value: temperature, unit: tempScale)
		statusUpdate << ["temperature": temperature]
		isChange = true
	}
	if (trend != device.currentValue("trend")) {
		sendEvent(name: "trend", value: trend)
		statusUpdate << ["trend": trend]
		isChange = true
	}
	if (sensorHealth != device.currentValue("sensorHealth")) {
		sendEvent(name: "sensorHealth", value: sensorHealth)
		statusUpdate << ["sensorHealth": sensorHealth]
		isChange = true
	}
	if (isChange) { logInfo("commandParse: ${statusUpdate}") }
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
