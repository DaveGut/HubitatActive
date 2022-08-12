/*	===== HUBITAT INTEGRATION VERSION =====================================================
Hubitat - Smart Things Data Collection for Hubitat Environment driver development
		Copyright 2022 Dave Gutheinz
===========================================================================================*/
def driverVer() { return "1.1" }
metadata {
	definition (name: "ST Data Collect",
				namespace: "davegut",
				author: "David Gutheinz",
				importUrl: ""
			   ){
	}
	preferences {
		input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "")
		if (stApiKey) {
			input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "")
		}
	}
}

def installed() { updated() }

def updated() {
	def status = "OK"
	def reason = "Success"
	def updateData = [:]
	if (!stApiKey || stApiKey == "") {
		updateData << [status: "FAILED", reason: "No stApiKey"]
	} else if (!stDeviceId || stDeviceId == "") {
		getDeviceList()
		updateData << [status: "FAILED", reason: "No stDeviceId"]
	} else {
		unschedule()
		updateData << [status: "OK"]
		updateData << [stDeviceId: stDeviceId]
		if (!getDataValue("driverVersion") || 
			getDataValue("driverVersion") != driverVer()) {
			updateDataValue("driverVersion", driverVer())
			updateData << [driverVer: driverVer()]
		}
		getDevDesc()
		runIn(3, getDeviceStatus)
	}
	if (updateData.status == "FAILED") {
		logWarn("updated: ${updateData}")
	} else {
		logInfo("updated: ${updateData}")
	}
}			  
		
def getDevDesc() {
	def sendData = [
		path: "/devices/${stDeviceId.trim()}",
		parse: "devDescParse"
		]
	asyncGet(sendData)
}

def devDescParse(resp, data) {
	if (resp.status != 200) {
		logWarn("devDescParse: [error: InvalidResponse, respStatus: ${resp.status}]")
	} else {
		try {
			def respData = new JsonSlurper().parseText(resp.data)
			log.trace "deviceDescription: ${respData}"
		} catch (err) {
			logWarn("devDescParse(: [noDataError: ${err}]")
		}
	}
}

def getDeviceStatus() {
	def sendData = [
		path: "/devices/${stDeviceId.trim()}/status",
		parse: "devParse"
	]
	asyncGet(sendData)
}

def devParse(resp, data) {
	if (resp.status != 200) {
		logWarn("devParse: [error: InvalidResponse, respStatus: ${resp.status}]")
	} else {
		try {
			def respData = new JsonSlurper().parseText(resp.data)
			log.trace "deviceStatus: ${respData}"
		} catch (err) {
			logWarn("devParse: [noDataError: ${err}]")
		}
	}
}

//	===== Library Integration =====




// ~~~~~ start include (993) davegut.Logging ~~~~~
library ( // library marker davegut.Logging, line 1
	name: "Logging", // library marker davegut.Logging, line 2
	namespace: "davegut", // library marker davegut.Logging, line 3
	author: "Dave Gutheinz", // library marker davegut.Logging, line 4
	description: "Common Logging Methods", // library marker davegut.Logging, line 5
	category: "utilities", // library marker davegut.Logging, line 6
	documentationLink: "" // library marker davegut.Logging, line 7
) // library marker davegut.Logging, line 8

def listAttributes(trace = false) { // library marker davegut.Logging, line 10
	def attrs = device.getSupportedAttributes() // library marker davegut.Logging, line 11
	def attrList = [:] // library marker davegut.Logging, line 12
	attrs.each { // library marker davegut.Logging, line 13
		def val = device.currentValue("${it}") // library marker davegut.Logging, line 14
		attrList << ["${it}": val] // library marker davegut.Logging, line 15
	} // library marker davegut.Logging, line 16
	if (trace == true) { // library marker davegut.Logging, line 17
		logTrace("Attributes: ${attrList}") // library marker davegut.Logging, line 18
	} else { // library marker davegut.Logging, line 19
		logDebug("Attributes: ${attrList}") // library marker davegut.Logging, line 20
	} // library marker davegut.Logging, line 21
} // library marker davegut.Logging, line 22

def logTrace(msg){ // library marker davegut.Logging, line 24
	log.trace "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" // library marker davegut.Logging, line 25
} // library marker davegut.Logging, line 26

def logInfo(msg) {  // library marker davegut.Logging, line 28
	log.info "${device.displayName} ${getDataValue("driverVersion")}: ${msg}"  // library marker davegut.Logging, line 29
} // library marker davegut.Logging, line 30

def debugLogOff() { // library marker davegut.Logging, line 32
	if (debug == true) { // library marker davegut.Logging, line 33
		device.updateSetting("debug", [type:"bool", value: false]) // library marker davegut.Logging, line 34
	} else if (debugLog == true) { // library marker davegut.Logging, line 35
		device.updateSetting("debugLog", [type:"bool", value: false]) // library marker davegut.Logging, line 36
	} // library marker davegut.Logging, line 37
	logInfo("Debug logging is false.") // library marker davegut.Logging, line 38
} // library marker davegut.Logging, line 39

def logDebug(msg) { // library marker davegut.Logging, line 41
	if (debug == true || debugLog == true) { // library marker davegut.Logging, line 42
		log.debug "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" // library marker davegut.Logging, line 43
	} // library marker davegut.Logging, line 44
} // library marker davegut.Logging, line 45

def logWarn(msg) { log.warn "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" } // library marker davegut.Logging, line 47

// ~~~~~ end include (993) davegut.Logging ~~~~~

// ~~~~~ start include (1001) davegut.ST-Communications ~~~~~
library ( // library marker davegut.ST-Communications, line 1
	name: "ST-Communications", // library marker davegut.ST-Communications, line 2
	namespace: "davegut", // library marker davegut.ST-Communications, line 3
	author: "Dave Gutheinz", // library marker davegut.ST-Communications, line 4
	description: "ST Communications Methods", // library marker davegut.ST-Communications, line 5
	category: "utilities", // library marker davegut.ST-Communications, line 6
	documentationLink: "" // library marker davegut.ST-Communications, line 7
) // library marker davegut.ST-Communications, line 8
import groovy.json.JsonSlurper // library marker davegut.ST-Communications, line 9

private asyncGet(sendData, passData = "none") { // library marker davegut.ST-Communications, line 11
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 12
		logWarn("asyncGet: [status: ERROR, errorMsg: no stApiKey]") // library marker davegut.ST-Communications, line 13
	} else { // library marker davegut.ST-Communications, line 14
		logDebug("asyncGet: ${sendData}, ${passData}") // library marker davegut.ST-Communications, line 15
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 16
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 17
			path: sendData.path, // library marker davegut.ST-Communications, line 18
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()]] // library marker davegut.ST-Communications, line 19
		try { // library marker davegut.ST-Communications, line 20
			asynchttpGet(sendData.parse, sendCmdParams, [reason: passData]) // library marker davegut.ST-Communications, line 21
		} catch (error) { // library marker davegut.ST-Communications, line 22
			logWarn("asyncGet: [status: FAILED, errorMsg: ${error}]") // library marker davegut.ST-Communications, line 23
		} // library marker davegut.ST-Communications, line 24
	} // library marker davegut.ST-Communications, line 25
} // library marker davegut.ST-Communications, line 26

private syncGet(path){ // library marker davegut.ST-Communications, line 28
	def respData = [:] // library marker davegut.ST-Communications, line 29
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 30
		respData << [status: "FAILED", // library marker davegut.ST-Communications, line 31
					 errorMsg: "No stApiKey"] // library marker davegut.ST-Communications, line 32
	} else { // library marker davegut.ST-Communications, line 33
		logDebug("syncGet: ${sendData}") // library marker davegut.ST-Communications, line 34
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 35
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 36
			path: path, // library marker davegut.ST-Communications, line 37
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()] // library marker davegut.ST-Communications, line 38
		] // library marker davegut.ST-Communications, line 39
		try { // library marker davegut.ST-Communications, line 40
			httpGet(sendCmdParams) {resp -> // library marker davegut.ST-Communications, line 41
				if (resp.status == 200 && resp.data != null) { // library marker davegut.ST-Communications, line 42
					respData << [status: "OK", results: resp.data] // library marker davegut.ST-Communications, line 43
				} else { // library marker davegut.ST-Communications, line 44
					respData << [status: "FAILED", // library marker davegut.ST-Communications, line 45
								 httpCode: resp.status, // library marker davegut.ST-Communications, line 46
								 errorMsg: resp.errorMessage] // library marker davegut.ST-Communications, line 47
				} // library marker davegut.ST-Communications, line 48
			} // library marker davegut.ST-Communications, line 49
		} catch (error) { // library marker davegut.ST-Communications, line 50
			respData << [status: "FAILED", // library marker davegut.ST-Communications, line 51
						 httpCode: "Timeout", // library marker davegut.ST-Communications, line 52
						 errorMsg: error] // library marker davegut.ST-Communications, line 53
		} // library marker davegut.ST-Communications, line 54
	} // library marker davegut.ST-Communications, line 55
	return respData // library marker davegut.ST-Communications, line 56
} // library marker davegut.ST-Communications, line 57

private syncPost(sendData){ // library marker davegut.ST-Communications, line 59
	def respData = [:] // library marker davegut.ST-Communications, line 60
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 61
		respData << [status: "FAILED", // library marker davegut.ST-Communications, line 62
					 errorMsg: "No stApiKey"] // library marker davegut.ST-Communications, line 63
	} else { // library marker davegut.ST-Communications, line 64
		logDebug("syncPost: ${sendData}") // library marker davegut.ST-Communications, line 65

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
					respData << [status: "FAILED", // library marker davegut.ST-Communications, line 79
								 httpCode: resp.status, // library marker davegut.ST-Communications, line 80
								 errorMsg: resp.errorMessage] // library marker davegut.ST-Communications, line 81
				} // library marker davegut.ST-Communications, line 82
			} // library marker davegut.ST-Communications, line 83
		} catch (error) { // library marker davegut.ST-Communications, line 84
			respData << [status: "FAILED", // library marker davegut.ST-Communications, line 85
						 httpCode: "Timeout", // library marker davegut.ST-Communications, line 86
						 errorMsg: error] // library marker davegut.ST-Communications, line 87
		} // library marker davegut.ST-Communications, line 88
	} // library marker davegut.ST-Communications, line 89
	return respData // library marker davegut.ST-Communications, line 90
} // library marker davegut.ST-Communications, line 91

// ~~~~~ end include (1001) davegut.ST-Communications ~~~~~

// ~~~~~ start include (1000) davegut.ST-Common ~~~~~
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
	def respData = [:] // library marker davegut.ST-Common, line 50
	if (simulate() == true) { // library marker davegut.ST-Common, line 51
		respData = testResp(cmdData) // library marker davegut.ST-Common, line 52
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 53
		respData << "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 54
	} else { // library marker davegut.ST-Common, line 55
		def sendData = [ // library marker davegut.ST-Common, line 56
			path: "/devices/${stDeviceId.trim()}/commands", // library marker davegut.ST-Common, line 57
			cmdData: cmdData // library marker davegut.ST-Common, line 58
		] // library marker davegut.ST-Common, line 59
		respData = syncPost(sendData) // library marker davegut.ST-Common, line 60
	} // library marker davegut.ST-Common, line 61
	if (cmdData.capability && cmdData.capability != "refresh") { // library marker davegut.ST-Common, line 62
		refresh() // library marker davegut.ST-Common, line 63
	} else { // library marker davegut.ST-Common, line 64
		poll() // library marker davegut.ST-Common, line 65
	} // library marker davegut.ST-Common, line 66
	return respData // library marker davegut.ST-Common, line 67
} // library marker davegut.ST-Common, line 68

def refresh() {  // library marker davegut.ST-Common, line 70
	def cmdData = [ // library marker davegut.ST-Common, line 71
		component: "main", // library marker davegut.ST-Common, line 72
		capability: "refresh", // library marker davegut.ST-Common, line 73
		command: "refresh", // library marker davegut.ST-Common, line 74
		arguments: []] // library marker davegut.ST-Common, line 75
	deviceCommand(cmdData) // library marker davegut.ST-Common, line 76
} // library marker davegut.ST-Common, line 77

def poll() { // library marker davegut.ST-Common, line 79
	if (simulate() == true) { // library marker davegut.ST-Common, line 80
		def children = getChildDevices() // library marker davegut.ST-Common, line 81
		if (children) { // library marker davegut.ST-Common, line 82
			children.each { // library marker davegut.ST-Common, line 83
				it.statusParse(testData()) // library marker davegut.ST-Common, line 84
			} // library marker davegut.ST-Common, line 85
		} // library marker davegut.ST-Common, line 86
		statusParse(testData()) // library marker davegut.ST-Common, line 87
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 88
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 89
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 90
	} else { // library marker davegut.ST-Common, line 91
		def sendData = [ // library marker davegut.ST-Common, line 92
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.ST-Common, line 93
			parse: "distResp" // library marker davegut.ST-Common, line 94
			] // library marker davegut.ST-Common, line 95
		asyncGet(sendData, "statusParse") // library marker davegut.ST-Common, line 96
	} // library marker davegut.ST-Common, line 97
} // library marker davegut.ST-Common, line 98

def deviceSetup() { // library marker davegut.ST-Common, line 100
	if (simulate() == true) { // library marker davegut.ST-Common, line 101
		def children = getChildDevices() // library marker davegut.ST-Common, line 102
		if (children) { // library marker davegut.ST-Common, line 103
			children.each { // library marker davegut.ST-Common, line 104
				it.deviceSetupParse(testData()) // library marker davegut.ST-Common, line 105
			} // library marker davegut.ST-Common, line 106
		} // library marker davegut.ST-Common, line 107
		deviceSetupParse(testData()) // library marker davegut.ST-Common, line 108
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 109
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 110
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 111
	} else { // library marker davegut.ST-Common, line 112
		def sendData = [ // library marker davegut.ST-Common, line 113
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.ST-Common, line 114
			parse: "distResp" // library marker davegut.ST-Common, line 115
			] // library marker davegut.ST-Common, line 116
		asyncGet(sendData, "deviceSetup") // library marker davegut.ST-Common, line 117
	} // library marker davegut.ST-Common, line 118
} // library marker davegut.ST-Common, line 119

def getDeviceList() { // library marker davegut.ST-Common, line 121
	def sendData = [ // library marker davegut.ST-Common, line 122
		path: "/devices", // library marker davegut.ST-Common, line 123
		parse: "getDeviceListParse" // library marker davegut.ST-Common, line 124
		] // library marker davegut.ST-Common, line 125
	asyncGet(sendData) // library marker davegut.ST-Common, line 126
} // library marker davegut.ST-Common, line 127

def getDeviceListParse(resp, data) { // library marker davegut.ST-Common, line 129
	def respData // library marker davegut.ST-Common, line 130
	if (resp.status != 200) { // library marker davegut.ST-Common, line 131
		respData = [status: "ERROR", // library marker davegut.ST-Common, line 132
					httpCode: resp.status, // library marker davegut.ST-Common, line 133
					errorMsg: resp.errorMessage] // library marker davegut.ST-Common, line 134
	} else { // library marker davegut.ST-Common, line 135
		try { // library marker davegut.ST-Common, line 136
			respData = new JsonSlurper().parseText(resp.data) // library marker davegut.ST-Common, line 137
		} catch (err) { // library marker davegut.ST-Common, line 138
			respData = [status: "ERROR", // library marker davegut.ST-Common, line 139
						errorMsg: err, // library marker davegut.ST-Common, line 140
						respData: resp.data] // library marker davegut.ST-Common, line 141
		} // library marker davegut.ST-Common, line 142
	} // library marker davegut.ST-Common, line 143
	if (respData.status == "ERROR") { // library marker davegut.ST-Common, line 144
		logWarn("getDeviceListParse: ${respData}") // library marker davegut.ST-Common, line 145
	} else { // library marker davegut.ST-Common, line 146
		log.info "" // library marker davegut.ST-Common, line 147
		respData.items.each { // library marker davegut.ST-Common, line 148
			log.trace "${it.label}:   ${it.deviceId}" // library marker davegut.ST-Common, line 149
		} // library marker davegut.ST-Common, line 150
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>" // library marker davegut.ST-Common, line 151
	} // library marker davegut.ST-Common, line 152
} // library marker davegut.ST-Common, line 153

def calcTimeRemaining(completionTime) { // library marker davegut.ST-Common, line 155
	Integer currTime = now() // library marker davegut.ST-Common, line 156
	Integer compTime // library marker davegut.ST-Common, line 157
	try { // library marker davegut.ST-Common, line 158
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 159
	} catch (e) { // library marker davegut.ST-Common, line 160
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 161
	} // library marker davegut.ST-Common, line 162
	Integer timeRemaining = ((compTime-currTime) /1000).toInteger() // library marker davegut.ST-Common, line 163
	if (timeRemaining < 0) { timeRemaining = 0 } // library marker davegut.ST-Common, line 164
	return timeRemaining // library marker davegut.ST-Common, line 165
} // library marker davegut.ST-Common, line 166

// ~~~~~ end include (1000) davegut.ST-Common ~~~~~
