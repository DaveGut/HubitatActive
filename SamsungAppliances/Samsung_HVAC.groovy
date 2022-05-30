/*	===== HUBITAT Samsung Dryer Using SmartThings ==========================================
		Copyright 2022 Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file
except in compliance with the License. You may obtain a copy of the License at:
		http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software distributed under the
License is distributed on an  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied. See the License for the specific language governing permissions
and limitations under the  License.
===== HISTORY =============================================================================
05.27	PASSED final testing for Beta Release.
===========================================================================================*/
def driverVer() { return "B0.1" }

metadata {
	definition (name: "Samsung HVAC",
				namespace: "davegut",
				author: "David Gutheinz",
				//	import URL will be added later.  Provides short-cut for downloading
				//	updates/new code using the editors IMPORT function (at top).
				importUrl: ""
			   ){
		capability "Refresh"
		capability "Switch"
		capability "TemperatureMeasurement"
		capability "ThermostatCoolingSetpoint"
		capability "ThermostatFanMode"
		command "setThermostatFanMode", [[
			name: "Fan Mode",
			constraints: ["on", "circulate", "auto", "high", "medium", "low"],
			type: "ENUM"]]
		capability "ThermostatMode"
		command "setThermostatMode", [[
			name: "Thermostat Mode",
			constraints: ["auto", "off", "heat", "emergency heat", "cool", "dry", "wind"],
			type: "ENUM"]]
		attribute "autoCleaningMode", "string"
		attribute "dustFilterStatus", "string"
	}
	preferences {
		input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "")
		if (stApiKey) {
			input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "")
		}
		if (stDeviceId) {
			input ("pollInterval", "enum", title: "Poll Interval (minutes)",
				   options: ["1", "5", "10", "30"], defaultValue: "10")
			input ("circSpeed", "enum", title: "Fan Circulate Speed",
				   defaultValue: "medium", options: ["low", "medium", "high"])
			input ("debugLog", "bool",  
				   title: "Enable debug logging for 30 minutes", defaultValue: false)
			input ("infoLog", "bool",  
				   title: "Enable description text logging", defaultValue: true)
		}
	}
}

def installed() { }

def updated() {
	def commonStatus = commonUpdate()
	if (commonStatus.status == "FAILED") {
		logWarn("updated: ${commonStatus}")
	} else {
		logInfo("updated: ${commonStatus}")
	}
}

def on() { setOnOff("on") }
def off() { setOnOff("off") }
def setOnOff(onOff) {
	def cmdData = [
		component: "main",
		capability: "switch",
		command: onOff,
		arguments: []]
	def cmdStatus = deviceCommand(cmdData)
	logInfo("setOnOff: [cmd: ${onOff}, ${cmdStatus}]")
}

def auto() { setThermostatMode("auto") }
def cool() { setThermostatMode("cool") }
def heat() { setThermostatMode("heat") }
def emergencyHeat() {
	logWarn("emergencyHeat: not available. Setting Thermostat to heat")
	setThermostatMode("heat")
}
def setThermostatMode(thermostatMode) {
// Modify to thermostatMode "off".
	if (thermostatMode == "off") {
		off()
	} else {
		def cmdData = [
			component: "main",
			capability: "airConditionerMode",
			command: "setAirConditionerMode",
			arguments: [thermostatMode]]
		def cmdStatus = deviceCommand(cmdData)
		logInfo("setThermostatMode: [cmd: ${thermostatMode}, ${cmdStatus}]")
	}
}

def fanAuto() { setThermostatFanMode("auto") }
def fanCirculate() { setThermostatFanMode("circulate") }
def fanOn() { setThermostatFanMode("on") }
def setThermostatFanMode(fanMode) {
	//	supportedAcFanModes:[value:["auto", "low", "medium", "high"]]
	//	Modified metadata to add above to default on, auto, circulate.
	//	Handle on and circulate in respect to available modes from device.
	if (fanMode == "circulate") {
		fanMode = circSpeed
	} else if (fanMode == "on") {
		fanMode = "auto"
	}
	def cmdData = [
		component: "main",
		capability: "airConditionerFanMode",
		command: "setFanMode",
		arguments: [fanMode]]
	def cmdStatus = deviceCommand(cmdData)
	logInfo("setThermostatFanMode: [cmd: ${fanMode}, ${cmdStatus}]")
}

def setCoolingSetpoint(setpoint) {
	def cmdData = [
		component: "main",
		capability: "thermostatCoolingSetpoint",
		command: "setCoolingSetpoint",
		arguments: [setpoint]]
	def cmdStatus = deviceCommand(cmdData)
	logInfo("setCoolingSetpoint: [cmd: ${setpoint}, ${cmdStatus}]")
}

def validateResp(resp, data) {
	//	Fixed to generate logWarn as specified in design.
	def respLog = [:]
	if (resp.status == 200) {
		try {
			def respData = new JsonSlurper().parseText(resp.data)
			statusParse(respData.components.main)
		} catch (err) {
			respLog << [status: "ERROR",
						errorMsg: err,
						respData: resp.data]
		}
	} else {
		respLog << [status: "ERROR",
					httpCode: resp.status,
					errorMsg: resp.errorMessage]
	}
	if (respLog != [:]) {
		logWarn("validateResp: ${respLog}")
	}
}

def statusParse(parseData) {
	def logData = [:]
	
	def onOff = parseData.switch.switch.value
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff)
		logData << [switch: onOff]
	}
	
	def tempUnit = parseData.temperatureMeasurement.temperature.unit
	def temperature = parseData.temperatureMeasurement.temperature.value
	if (device.currentValue("temperature") != temperature) {
		sendEvent(name: "temperature", value: temperature, unit: tempUnit)
		logData << [temperature: temperature]
	}
	
	def thermostatMode = parseData.airConditionerMode.airConditionerMode.value
	if (device.currentValue("thermostatMode") != thermostatMode) {
		sendEvent(name: "thermostatMode", value: thermostatMode)
		logData << [thermostatMode: thermostatMode]
	}

	def coolingSetpoint = parseData.thermostatCoolingSetpoint.coolingSetpoint.value
	if (device.currentValue("coolingSetpoint") != coolingSetpoint) {
		sendEvent(name: "coolingSetpoint", value: coolingSetpoint, unit: tempUnit)
		logData << [coolingSetpoint: coolingSetpoint]
	}

	def thermostatFanMode = parseData.airConditionerFanMode.fanMode.value
	if (device.currentValue("thermostatFanMode") != thermostatFanMode) {
		sendEvent(name: "thermostatFanMode", value: thermostatFanMode)
		logData << [thermostatFanMode: thermostatFanMode]
	}

	def dustFilterStatus = parseData["custom.dustFilter"].dustFilterStatus.value
	if (device.currentValue("dustFilterStatus") != dustFilterStatus) {
		sendEvent(name: "dustFilterStatus", value: dustFilterStatus)
		logData << [dustFilterStatus: dustFilterStatus]
	}

	def autoCleaningMode = parseData["custom.autoCleaningMode"].autoCleaningMode.value
	if (device.currentValue("autoCleaningMode") != autoCleaningMode) {
		sendEvent(name: "autoCleaningMode", value: autoCleaningMode)
		logData << [autoCleaningMode: autoCleaningMode]
	}

	if (logData != [:]) {
		logInfo("statusParse: ${logData}")
}
	runIn(1, listAttributes)
}

//	===== Library Integration =====




// ~~~~~ start include (611) davegut.Logging ~~~~~
library ( // library marker davegut.Logging, line 1
	name: "Logging", // library marker davegut.Logging, line 2
	namespace: "davegut", // library marker davegut.Logging, line 3
	author: "Dave Gutheinz", // library marker davegut.Logging, line 4
	description: "Common Logging Methods", // library marker davegut.Logging, line 5
	category: "utilities", // library marker davegut.Logging, line 6
	documentationLink: "" // library marker davegut.Logging, line 7
) // library marker davegut.Logging, line 8

//	Logging during development // library marker davegut.Logging, line 10
def listAttributes() { // library marker davegut.Logging, line 11
	def attrs = device.getSupportedAttributes() // library marker davegut.Logging, line 12
	def attrList = [:] // library marker davegut.Logging, line 13
	attrs.each { // library marker davegut.Logging, line 14
		def val = device.currentValue("${it}") // library marker davegut.Logging, line 15
		attrList << ["${it}": val] // library marker davegut.Logging, line 16
	} // library marker davegut.Logging, line 17
	logDebug("Attributes: ${attrList}") // library marker davegut.Logging, line 18
} // library marker davegut.Logging, line 19

def logTrace(msg){ // library marker davegut.Logging, line 21
	log.trace "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" // library marker davegut.Logging, line 22
} // library marker davegut.Logging, line 23

def logInfo(msg) {  // library marker davegut.Logging, line 25
	if (infoLog == true) { // library marker davegut.Logging, line 26
		log.info "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" // library marker davegut.Logging, line 27
	} // library marker davegut.Logging, line 28
} // library marker davegut.Logging, line 29

def debugLogOff() { // library marker davegut.Logging, line 31
	device.updateSetting("debugLog", [type:"bool", value: false]) // library marker davegut.Logging, line 32
	logInfo("Debug logging is false.") // library marker davegut.Logging, line 33
} // library marker davegut.Logging, line 34

def logDebug(msg) { // library marker davegut.Logging, line 36
	if (debugLog == true) { // library marker davegut.Logging, line 37
		log.debug "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" // library marker davegut.Logging, line 38
	} // library marker davegut.Logging, line 39
} // library marker davegut.Logging, line 40

def logWarn(msg) { log.warn "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" } // library marker davegut.Logging, line 42

// ~~~~~ end include (611) davegut.Logging ~~~~~

// ~~~~~ start include (387) davegut.ST-Communications ~~~~~
library ( // library marker davegut.ST-Communications, line 1
	name: "ST-Communications", // library marker davegut.ST-Communications, line 2
	namespace: "davegut", // library marker davegut.ST-Communications, line 3
	author: "Dave Gutheinz", // library marker davegut.ST-Communications, line 4
	description: "ST Communications Methods", // library marker davegut.ST-Communications, line 5
	category: "utilities", // library marker davegut.ST-Communications, line 6
	documentationLink: "" // library marker davegut.ST-Communications, line 7
) // library marker davegut.ST-Communications, line 8
import groovy.json.JsonSlurper // library marker davegut.ST-Communications, line 9

private asyncGet(sendData) { // library marker davegut.ST-Communications, line 11
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 12
		logWarn("asyncGet: [status: ERROR, errorMsg: no stApiKey]") // library marker davegut.ST-Communications, line 13
	} else { // library marker davegut.ST-Communications, line 14
		logDebug("asyncGet: ${sendData}") // library marker davegut.ST-Communications, line 15
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 16
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 17
			path: sendData.path, // library marker davegut.ST-Communications, line 18
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()]] // library marker davegut.ST-Communications, line 19
		try { // library marker davegut.ST-Communications, line 20
			asynchttpGet(sendData.parse, sendCmdParams) // library marker davegut.ST-Communications, line 21
		} catch (error) { // library marker davegut.ST-Communications, line 22
			logWarn("asyncGet: [status: error, statusReason: ${error}]") // library marker davegut.ST-Communications, line 23
		} // library marker davegut.ST-Communications, line 24
	} // library marker davegut.ST-Communications, line 25
} // library marker davegut.ST-Communications, line 26

private xxxsyncGet(path = "/devices/${stDeviceId.trim()}/commands"){ // library marker davegut.ST-Communications, line 28
	if (!stApiKey || stApiKey == "") { // library marker davegut.ST-Communications, line 29
		logWarn("asyncPost: [status: ${error}, statusReason: no stApiKey]") // library marker davegut.ST-Communications, line 30
		return // library marker davegut.ST-Communications, line 31
	} // library marker davegut.ST-Communications, line 32
	logDebug("syncGet: [cmdBody: ${cmdData}, path: ${path}]") // library marker davegut.ST-Communications, line 33
	def cmdBody = [commands: [cmdData]] // library marker davegut.ST-Communications, line 34
	def sendCmdParams = [ // library marker davegut.ST-Communications, line 35
		uri: "https://api.smartthings.com/v1${path}", // library marker davegut.ST-Communications, line 36
		headers: ['Authorization': 'Bearer ' + stApiKey.trim()] // library marker davegut.ST-Communications, line 37
	] // library marker davegut.ST-Communications, line 38
	def respData = [:] // library marker davegut.ST-Communications, line 39
	try { // library marker davegut.ST-Communications, line 40
		httpGet(sendCmdParams) {resp -> // library marker davegut.ST-Communications, line 41
			if (resp.status == 200 && resp.data != null) { // library marker davegut.ST-Communications, line 42
				respData << [status: "OK", results: resp.data.results] // library marker davegut.ST-Communications, line 43
			} else { // library marker davegut.ST-Communications, line 44
				respData << [status:"ERROR", // library marker davegut.ST-Communications, line 45
							 cmdBody: cmdBody, // library marker davegut.ST-Communications, line 46
							 httpCode: resp.status, // library marker davegut.ST-Communications, line 47
							 errorMsg: resp.errorMessage] // library marker davegut.ST-Communications, line 48
			} // library marker davegut.ST-Communications, line 49
		} // library marker davegut.ST-Communications, line 50
	} catch (error) { // library marker davegut.ST-Communications, line 51
		respData << [status: "ERROR", // library marker davegut.ST-Communications, line 52
					 cmdBody: cmdBody, // library marker davegut.ST-Communications, line 53
					 httpCode: "No Response", // library marker davegut.ST-Communications, line 54
					 errorMsg: error] // library marker davegut.ST-Communications, line 55
	} // library marker davegut.ST-Communications, line 56
	return respData // library marker davegut.ST-Communications, line 57
} // library marker davegut.ST-Communications, line 58

private syncPost(sendData){ // library marker davegut.ST-Communications, line 60
	def respData = [:] // library marker davegut.ST-Communications, line 61
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 62
		respData << [status: "ERROR", errorMsg: "no stApiKey"] // library marker davegut.ST-Communications, line 63
		logWarn("syncPost: [status: ERROR, errorMsg: no stApiKey]") // library marker davegut.ST-Communications, line 64
	} else { // library marker davegut.ST-Communications, line 65
		logDebug("syncPost: ${sendData}") // library marker davegut.ST-Communications, line 66
		def cmdBody = [commands: [sendData.cmdData]] // library marker davegut.ST-Communications, line 67
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 68
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 69
			path: sendData.path, // library marker davegut.ST-Communications, line 70
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()], // library marker davegut.ST-Communications, line 71
			body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.ST-Communications, line 72
		] // library marker davegut.ST-Communications, line 73
		try { // library marker davegut.ST-Communications, line 74
			httpPost(sendCmdParams) {resp -> // library marker davegut.ST-Communications, line 75
				if (resp.status == 200 && resp.data != null) { // library marker davegut.ST-Communications, line 76
					respData << [status: "OK", results: resp.data.results] // library marker davegut.ST-Communications, line 77
				} else { // library marker davegut.ST-Communications, line 78
					respData << [status: "FAILED", errorMsg: "httpCode: ${resp.status}"] // library marker davegut.ST-Communications, line 79
					def warnData = [status:"ERROR", // library marker davegut.ST-Communications, line 80
									cmdData: sendData.cmdData, // library marker davegut.ST-Communications, line 81
									httpCode: resp.status, // library marker davegut.ST-Communications, line 82
									errorMsg: resp.errorMessage] // library marker davegut.ST-Communications, line 83
					logWarn("syncPost: ${warnData}") // library marker davegut.ST-Communications, line 84
				} // library marker davegut.ST-Communications, line 85
			} // library marker davegut.ST-Communications, line 86
		} catch (error) { // library marker davegut.ST-Communications, line 87
			respData << [status: "FAILED", errorMsg: "non-HTTP Error"] // library marker davegut.ST-Communications, line 88
			def warnData = [status: "ERROR", // library marker davegut.ST-Communications, line 89
							cmdData: sendData.cmdData, // library marker davegut.ST-Communications, line 90
							httpCode: "No Response", // library marker davegut.ST-Communications, line 91
							errorMsg: error] // library marker davegut.ST-Communications, line 92
			logWarn("syncPost: ${warnData}") // library marker davegut.ST-Communications, line 93
		} // library marker davegut.ST-Communications, line 94
	} // library marker davegut.ST-Communications, line 95
	return respData // library marker davegut.ST-Communications, line 96
} // library marker davegut.ST-Communications, line 97

// ~~~~~ end include (387) davegut.ST-Communications ~~~~~

// ~~~~~ start include (642) davegut.ST-Common ~~~~~
library ( // library marker davegut.ST-Common, line 1
	name: "ST-Common", // library marker davegut.ST-Common, line 2
	namespace: "davegut", // library marker davegut.ST-Common, line 3
	author: "Dave Gutheinz", // library marker davegut.ST-Common, line 4
	description: "ST Wash/Dryer Common Methods", // library marker davegut.ST-Common, line 5
	category: "utilities", // library marker davegut.ST-Common, line 6
	documentationLink: "" // library marker davegut.ST-Common, line 7
) // library marker davegut.ST-Common, line 8

def commonUpdate() { // library marker davegut.ST-Common, line 10
	if (!stApiKey || stApiKey == "") { // library marker davegut.ST-Common, line 11
		return [status: "FAILED", reason: "No stApiKey"] // library marker davegut.ST-Common, line 12
	} // library marker davegut.ST-Common, line 13
	if (!stDeviceId || stDeviceId == "") { // library marker davegut.ST-Common, line 14
		getDeviceList() // library marker davegut.ST-Common, line 15
		return [status: "FAILED", reason: "No stDeviceId"] // library marker davegut.ST-Common, line 16
	} // library marker davegut.ST-Common, line 17

	unschedule() // library marker davegut.ST-Common, line 19
	def updateData = [:] // library marker davegut.ST-Common, line 20
	updateData << [status: "OK"] // library marker davegut.ST-Common, line 21
	if (debugLog) { runIn(1800, debugLogOff) } // library marker davegut.ST-Common, line 22
	updateData << [stDeviceId: stDeviceId] // library marker davegut.ST-Common, line 23
	updateData << [debugLog: debugLog, infoLog: infoLog] // library marker davegut.ST-Common, line 24
	if (!getDataValue("driverVersion") ||  // library marker davegut.ST-Common, line 25
		getDataValue("driverVersion") != driverVer()) { // library marker davegut.ST-Common, line 26
		updateDataValue("driverVersion", driverVer()) // library marker davegut.ST-Common, line 27
		updateData << [driverVer: driverVer()] // library marker davegut.ST-Common, line 28
	} // library marker davegut.ST-Common, line 29
	setPollInterval(pollInterval) // library marker davegut.ST-Common, line 30
	updateData << [pollInterval: pollInterval] // library marker davegut.ST-Common, line 31

	runIn(5, refresh) // library marker davegut.ST-Common, line 33
	return updateData // library marker davegut.ST-Common, line 34
} // library marker davegut.ST-Common, line 35

def setPollInterval(pollInterval) { // library marker davegut.ST-Common, line 37
	logDebug("setPollInterval: ${pollInterval}") // library marker davegut.ST-Common, line 38
	state.pollInterval = pollInterval // library marker davegut.ST-Common, line 39
	switch(pollInterval) { // library marker davegut.ST-Common, line 40
		case "1" : runEvery1Minute(poll); break // library marker davegut.ST-Common, line 41
		case "5" : runEvery5Minutes(poll); break // library marker davegut.ST-Common, line 42
		case "10" : runEvery10Minutes(poll); break // library marker davegut.ST-Common, line 43
		case "30" : runEvery30Minutes(poll); break // library marker davegut.ST-Common, line 44
		default: runEvery10Minutes(poll) // library marker davegut.ST-Common, line 45
	} // library marker davegut.ST-Common, line 46
} // library marker davegut.ST-Common, line 47

def deviceCommand(cmdData) { // library marker davegut.ST-Common, line 49
	def respData // library marker davegut.ST-Common, line 50
	if (simulate == true) { // library marker davegut.ST-Common, line 51
		respData = testResp(cmdData) // library marker davegut.ST-Common, line 52
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 53
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 54
		logWarn("deviceCommand: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 55
	} else { // library marker davegut.ST-Common, line 56
		def sendData = [ // library marker davegut.ST-Common, line 57
			path: "/devices/${stDeviceId.trim()}/commands", // library marker davegut.ST-Common, line 58
			cmdData: cmdData // library marker davegut.ST-Common, line 59
		] // library marker davegut.ST-Common, line 60
		respData = syncPost(sendData) // library marker davegut.ST-Common, line 61
		if(respData.status == "OK") { // library marker davegut.ST-Common, line 62
			respData = [status: "OK"] // library marker davegut.ST-Common, line 63
		} // library marker davegut.ST-Common, line 64
	} // library marker davegut.ST-Common, line 65
	runIn(1, poll) // library marker davegut.ST-Common, line 66
	return respData // library marker davegut.ST-Common, line 67
} // library marker davegut.ST-Common, line 68

def refresh() {  // library marker davegut.ST-Common, line 70
	def cmdData = [ // library marker davegut.ST-Common, line 71
		component: "main", // library marker davegut.ST-Common, line 72
		capability: "refresh", // library marker davegut.ST-Common, line 73
		command: "refresh", // library marker davegut.ST-Common, line 74
		arguments: []] // library marker davegut.ST-Common, line 75
	def cmdStatus = deviceCommand(cmdData) // library marker davegut.ST-Common, line 76
	logInfo("refresh: ${cmdStatus}") // library marker davegut.ST-Common, line 77
} // library marker davegut.ST-Common, line 78

def poll() { // library marker davegut.ST-Common, line 80
	if (simulate == true) { // library marker davegut.ST-Common, line 81
		statusParse(testData().components.main) // library marker davegut.ST-Common, line 82
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 83
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 84
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 85
	} else { // library marker davegut.ST-Common, line 86
		def sendData = [ // library marker davegut.ST-Common, line 87
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.ST-Common, line 88
			parse: "validateResp" // library marker davegut.ST-Common, line 89
			] // library marker davegut.ST-Common, line 90
		asyncGet(sendData) // library marker davegut.ST-Common, line 91
	} // library marker davegut.ST-Common, line 92
} // library marker davegut.ST-Common, line 93

def getDeviceList() { // library marker davegut.ST-Common, line 95
	def sendData = [ // library marker davegut.ST-Common, line 96
		path: "/devices", // library marker davegut.ST-Common, line 97
		parse: "getDeviceListParse" // library marker davegut.ST-Common, line 98
		] // library marker davegut.ST-Common, line 99
	asyncGet(sendData) // library marker davegut.ST-Common, line 100
} // library marker davegut.ST-Common, line 101

def getDeviceListParse(resp, data) { // library marker davegut.ST-Common, line 103
	def respData // library marker davegut.ST-Common, line 104
	if (resp.status != 200) { // library marker davegut.ST-Common, line 105
		respData = [status: "ERROR", // library marker davegut.ST-Common, line 106
					httpCode: resp.status, // library marker davegut.ST-Common, line 107
					errorMsg: resp.errorMessage] // library marker davegut.ST-Common, line 108
	} else { // library marker davegut.ST-Common, line 109
		try { // library marker davegut.ST-Common, line 110
			respData = new JsonSlurper().parseText(resp.data) // library marker davegut.ST-Common, line 111
		} catch (err) { // library marker davegut.ST-Common, line 112
			respData = [status: "ERROR", // library marker davegut.ST-Common, line 113
						errorMsg: err, // library marker davegut.ST-Common, line 114
						respData: resp.data] // library marker davegut.ST-Common, line 115
		} // library marker davegut.ST-Common, line 116
	} // library marker davegut.ST-Common, line 117
	if (respData.status == "ERROR") { // library marker davegut.ST-Common, line 118
		logWarn("getDeviceListParse: ${respData}") // library marker davegut.ST-Common, line 119
	} else { // library marker davegut.ST-Common, line 120
		log.info "" // library marker davegut.ST-Common, line 121
		respData.items.each { // library marker davegut.ST-Common, line 122
			log.trace "${it.label}:   ${it.deviceId}" // library marker davegut.ST-Common, line 123
		} // library marker davegut.ST-Common, line 124
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>" // library marker davegut.ST-Common, line 125
	} // library marker davegut.ST-Common, line 126
} // library marker davegut.ST-Common, line 127

def calcTimeRemaining(completionTime) { // library marker davegut.ST-Common, line 129
	Integer currTime = now() // library marker davegut.ST-Common, line 130
	Integer compTime // library marker davegut.ST-Common, line 131
	try { // library marker davegut.ST-Common, line 132
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 133
	} catch (e) { // library marker davegut.ST-Common, line 134
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 135
	} // library marker davegut.ST-Common, line 136
	Integer timeRemaining = ((compTime-currTime) /1000).toInteger() // library marker davegut.ST-Common, line 137
	if (timeRemaining < 0) { timeRemaining = 0 } // library marker davegut.ST-Common, line 138
	return timeRemaining // library marker davegut.ST-Common, line 139
} // library marker davegut.ST-Common, line 140

// ~~~~~ end include (642) davegut.ST-Common ~~~~~
