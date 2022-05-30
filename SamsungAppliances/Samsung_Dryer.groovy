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
===== Installation Instructions Link =====
https://github.com/DaveGut/HubitatActive/blob/master/SamsungAppliances/Install_Samsung_Appliance.pdf
===== Update Instructions =====
a.	Use Browser import feature and select the default file.
b.	Save the update to the driver.
c.	Open a logging window and the device's edit page
d.	Run a Save Preferences noting no ERRORS on the log page
e.	If errors, contact developer.
===========================================================================================*/
def driverVer() { return "B0.1" }

metadata {
	definition (name: "Samsung Dryer",
				namespace: "davegut",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Dryer.groovy"
			   ){
		capability "Refresh"
		attribute "switch", "string"
		command "start"
		command "pause"
		command "stop"
		attribute "jobState", "string"
		attribute "machineState", "string"
		attribute "kidsLock", "string"
		attribute "remoteControlEnabled", "string"
		attribute "completionTime", "string"
		attribute "timeRemaining", "number"
	}
	
	preferences {
		input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "")
		if (stApiKey) {
			input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "")
		}
		if (stDeviceId) {
			input ("pollInterval", "enum", title: "Poll Interval (minutes)",
				   options: ["1", "5", "10", "30"], defaultValue: "10")
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

def start() { setMachineState("run") }
def pause() { setMachineState("pause") }
def stop() { setMachineState("stop") }
def setMachineState(machState) {
	def cmdData = [
		component: "main",
		capability: "dryerOperatingState",
		command: "setMachineState",
		arguments: [machState]]
	def cmdStatus = deviceCommand(cmdData)
	logInfo("setMachineState: [cmd: ${machState}, status: ${cmdStatus}]")
}

def validateResp(resp, data) {
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

def statusParse(mainData) {
	def logData = [:]

	def onOff = mainData.switch.switch.value
	if (device.currentValue("switch") != onOff) {
		if (onOff == "off") {
			setPollInterval(state.pollInterval)
		} else {
			runEvery1Minute(poll)
		}
		sendEvent(name: "switch", value: onOff)
		logData << [switch: onOff]
	}
	
	def kidsLock = mainData["samsungce.kidsLock"].lockState.value
	if (device.currentValue("kidsLock") != kidsLock) {
		sendEvent(name: "kidsLock", value: kidsLock)
		logData << [kidsLock: kidsLock]
	}

	def machineState = mainData.dryerOperatingState.machineState.value
	if (device.currentValue("machineState") != machineState) {
		sendEvent(name: "machineState", value: machineState)
		logData << [machineState: machineState]
	}
	
	def jobState = mainData.dryerOperatingState.dryerJobState.value
	if (device.currentValue("jobState") != jobState) {
		sendEvent(name: "jobState", value: jobState)
		logData << [jobState: jobState]
	}

	def remoteControlEnabled = mainData.remoteControlStatus.remoteControlEnabled.value
	if (device.currentValue("remoteControlEnabled") != remoteControlEnabled) {
		sendEvent(name: "remoteControlEnabled", value: remoteControlEnabled)
		logData << [remoteControlEnabled: remoteControlEnabled]
	}

	def completionTime = mainData.dryerOperatingState.completionTime.value
	if (completionTime != null) {
		def timeRemaining = calcTimeRemaining(completionTime)
		if (device.currentValue("timeRemaining") != timeRemaining) {
			sendEvent(name: "completionTime", value: completionTime)
			sendEvent(name: "timeRemaining", value: timeRemaining)
			logData << [completionTime: completionTime, timeRemaining: timeRemaining]
		}
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

// ~~~~~ start include (673) davegut.Samsung-Dryer-Sim ~~~~~
library ( // library marker davegut.Samsung-Dryer-Sim, line 1
	name: "Samsung-Dryer-Sim", // library marker davegut.Samsung-Dryer-Sim, line 2
	namespace: "davegut", // library marker davegut.Samsung-Dryer-Sim, line 3
	author: "Dave Gutheinz", // library marker davegut.Samsung-Dryer-Sim, line 4
	description: "Simulator - Samsung Dryer", // library marker davegut.Samsung-Dryer-Sim, line 5
	category: "utilities", // library marker davegut.Samsung-Dryer-Sim, line 6
	documentationLink: "" // library marker davegut.Samsung-Dryer-Sim, line 7
) // library marker davegut.Samsung-Dryer-Sim, line 8

def testData() { // library marker davegut.Samsung-Dryer-Sim, line 10
	def wrinklePrevent = "off" // library marker davegut.Samsung-Dryer-Sim, line 11
	def dryingTemp = "high" // library marker davegut.Samsung-Dryer-Sim, line 12
	def completionTime = "2022-05-27T17:56:26Z" // library marker davegut.Samsung-Dryer-Sim, line 13
	def machineState = "run" // library marker davegut.Samsung-Dryer-Sim, line 14
	def jobState = "wash" // library marker davegut.Samsung-Dryer-Sim, line 15
	def onOff = "off" // library marker davegut.Samsung-Dryer-Sim, line 16
	def kidsLock = "locked" // library marker davegut.Samsung-Dryer-Sim, line 17
	def remoteControl = "false" // library marker davegut.Samsung-Dryer-Sim, line 18

	return  [components:[ // library marker davegut.Samsung-Dryer-Sim, line 20
		main:[ // library marker davegut.Samsung-Dryer-Sim, line 21
			"custom.dryerWrinklePrevent":[dryerWrinklePrevent:[value:wrinklePrevent]],  // library marker davegut.Samsung-Dryer-Sim, line 22
			"samsungce.dryerDryingTemperature":[dryingTemperature:[value:dryingTemp]],  // library marker davegut.Samsung-Dryer-Sim, line 23
			switch:[switch:[value:onOff]], // library marker davegut.Samsung-Dryer-Sim, line 24
			"samsungce.kidsLock":[lockState:[value:kidsLock]],  // library marker davegut.Samsung-Dryer-Sim, line 25
			dryerOperatingState:[ // library marker davegut.Samsung-Dryer-Sim, line 26
				completionTime:[value:completionTime],  // library marker davegut.Samsung-Dryer-Sim, line 27
				machineState:[value:machineState],  // library marker davegut.Samsung-Dryer-Sim, line 28
				dryerJobState:[value:jobState]],  // library marker davegut.Samsung-Dryer-Sim, line 29
			remoteControlStatus:[remoteControlEnabled:[value:remoteControl]],  // library marker davegut.Samsung-Dryer-Sim, line 30
		]]] // library marker davegut.Samsung-Dryer-Sim, line 31
} // library marker davegut.Samsung-Dryer-Sim, line 32

def testResp(cmdData) { // library marker davegut.Samsung-Dryer-Sim, line 34
	return [ // library marker davegut.Samsung-Dryer-Sim, line 35
		cmdData: cmdData, // library marker davegut.Samsung-Dryer-Sim, line 36
		status: [status: "OK", // library marker davegut.Samsung-Dryer-Sim, line 37
				 results:[[id: "e9585885-3848-4fea-b0db-ece30ff1701e", status: "ACCEPTED"]]]] // library marker davegut.Samsung-Dryer-Sim, line 38
} // library marker davegut.Samsung-Dryer-Sim, line 39

// ~~~~~ end include (673) davegut.Samsung-Dryer-Sim ~~~~~
