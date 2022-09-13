/*	Samsung Soundbar using SmartThings Interface
		Copyright Dave Gutheinz
License Information:
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Description
This driver is for SmartThings-installed Samsung Soundbars for import of control
and status of defined functions into Hubitat Environment.
===== Installation Instructions Link =====
https://github.com/DaveGut/HubitatActive/blob/master/SamsungAppliances/Install_Samsung_Appliance.pdf
===== Version 1.1 ==============================================================================*/
def driverVer() { return "1.2" }
def nameSpace() { return "davegut" }

metadata {
	definition (name: "Samsung Oven",
				namespace: nameSpace(),
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Oven.groovy"
			   ){
		capability "Refresh"
		capability "Contact Sensor"
		capability "Thermostat Setpoint"
		capability "Temperature Measurement"
		command "pauseOven"
		command "runOven"
		command "stopOven"
		command "setOvenSetpoint", ["NUMBER"]
		attribute "kidsLock", "string"
		attribute "cooktopState", "string"
		attribute "remoteControl", "string"
		attribute "completionTime", "string"
		attribute "timeRemaining", "string"
		attribute "ovenState", "string"
		attribute "jobState", "string"
		attribute "ovenMode", "string"
	}

	preferences {
		input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "")
		if (stApiKey) {
			input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "")
		}
		if (stDeviceId) {
			input ("pollInterval", "enum", title: "Poll Interval (minutes)",
				   options: ["10sec", "20sec", "30sec", "1", "5", "10", "30"], defaultValue: "10")
			input ("infoLog", "bool",  
				   title: "Info logging", defaultValue: true)
			input ("debugLog", "bool",  
				   title: "Enable debug logging for 30 minutes", defaultValue: false)
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
		def children = getChildDevices()
		if (children == []) {
			installChildren()
		} else {
			children.each {
				it.updated()
			}
		}
	}
}

def pauseOven() {
	setMachineState("paused", "main")
}
def runOven() {
	setMachineState("running", "main")
}
def setMachineState(machineState, component) {
	def cmdData = [
		component: component,
		capability: "ovenOperatingState",
		command: "setMachineState",
		arguments: [machineState]]
	def cmdStatus = deviceCommand(cmdData)
	logInfo("setMachineState: [comp: ${component}, state: ${machineState}, status: ${cmdStatus}]")
}

def stopOven(component = "main") {
	def cmdData = [
		component: component,
		capability: "ovenOperatingState",
		command: "stop",
		arguments: []]
	def cmdStatus = deviceCommand(cmdData)
	logInfo("stopOven: [comp: ${component}, status: ${cmdStatus}]")
}

def setOvenSetpoint(setpoint, component = "main") {
	def cmdData = [
		component: component,
		capability: "ovenSetpoint",
		command: "setOvenSetpoint",
		arguments: [setpoint]]
	def cmdStatus = deviceCommand(cmdData)
	logInfo("setOvenSetpoint: [comp: ${component}, setpoint: ${setpoint}, status: ${cmdStatus}]")
}

def distResp(resp, data) {
	def respLog = "OK"
	def respData
	if (resp.status == 200) {
		try {
			respData = new JsonSlurper().parseText(resp.data)
		} catch (err) {
			respLog = [status: "ERROR", errorMsg: err, respData: resp.data]
		}
	} else {
		respLog = [status: "ERROR", httpCode: resp.status, errorMsg: resp.errorMessage]
	}
	if (respLog == "OK") {
		def children = getChildDevices()
		children.each {
			it.statusParse(respData)
		}
		statusParse(respData)
	} else {
		logWarn("validateResp: ${respLog}")
	}
}

def statusParse(respData) {
	try {
		respData = respData.components.main
	} catch (error) {
		logWarn("statusParse: [respData: ${respData}, error: ${error}]")
		return
	}
	def tempUnit = respData.temperatureMeasurement.temperature.unit
	def temperature = respData.temperatureMeasurement.temperature.value
	sendEvent(name: "temperature", value: temperature, unit: tempUnit)
	
	def thermostatSetpoint = respData.ovenSetpoint.ovenSetpoint.value
	sendEvent(name: "thermostatSetpoint", value: thermostatSetpoint, unit: tempUnit)
	
	def completionTime = respData.ovenOperatingState.completionTime.value
	if (completionTime != null) {
		sendEvent(name: "completionTime", value: completionTime)
		def timeRemaining = calcTimeRemaining(completionTime)
		sendEvent(name: "timeRemaining", value: timeRemaining)
		if (timeRemaining == 0 && state.pollInterval == "1") {
			setPollInterval("10")
		} else if (timeRemaining > 0 && state.pollInterval == "10") {
			setPollInterval("1")
		}
	}
	
	def kidsLock = respData["samsungce.kidsLock"].lockState.value
	sendEvent(name: "kidsLock", value: kidsLock)

	def cooktopState = respData["custom.cooktopOperatingState"].cooktopOperatingState.value
	sendEvent(name: "cooktopState", value: cooktopState)

	def remoteControl = respData.remoteControlStatus.remoteControlEnabled.value
	sendEvent(name: "remoteControl", value: remoteControl)

	def contact = respData["samsungce.doorState"].doorState.value
	sendEvent(name: "contact", value: contact)

	def ovenState = respData.ovenOperatingState.machineState.value
	sendEvent(name: "ovenState", value: ovenState)

	def jobState = respData.ovenOperatingState.ovenJobState.value
	sendEvent(name: "jobState", value: jobState)

	def ovenMode = respData.ovenMode.ovenMode.value
	sendEvent(name: "ovenMode", value: ovenMode)
	
	if (simulate() == true) {
		runIn(1, listAttributes, [data: true])
	} else {
		runIn(1, listAttributes)
	}
}

//	===== Common Oven Parent/Child API Commands =====
//	===== Child Installation =====
def installChildren() {
	addChild("probe")
	pauseExecution(500)
	addChild("cavity")
	device.updateSetting("childInstall", [type:"bool", value: false])
}

def addChild(component) {
	def dni = device.getDeviceNetworkId()
	def childDni = dni + "-${component}"
	def isChild = getChildDevice(childDni)
	if (isChild) {
		logInfo("installChildren: [component: ${component}, status: already installed]")
	} else {
		def type = "Samsung Oven ${component}"
		try {
			addChildDevice("davegut", "${type}", "${childDni}", [
				"name": type, "label": component, component: component])
			logInfo("addChild: [status: ADDED, label: ${component}, type: ${type}]")
		} catch (error) {
			logWarn("addChild: [status: FAILED, type: ${type}, dni: ${childDni}, component: ${component}, error: ${error}]")
		}
	}
}

//	===== Library Integration =====



def simulate() { return false }
//#include davegut.Samsung-Oven-Sim

// ~~~~~ start include (1072) davegut.Logging ~~~~~
library ( // library marker davegut.Logging, line 1
	name: "Logging", // library marker davegut.Logging, line 2
	namespace: "davegut", // library marker davegut.Logging, line 3
	author: "Dave Gutheinz", // library marker davegut.Logging, line 4
	description: "Common Logging Methods", // library marker davegut.Logging, line 5
	category: "utilities", // library marker davegut.Logging, line 6
	documentationLink: "" // library marker davegut.Logging, line 7
) // library marker davegut.Logging, line 8

//	Logging during development // library marker davegut.Logging, line 10
def listAttributes(trace = false) { // library marker davegut.Logging, line 11
	def attrs = device.getSupportedAttributes() // library marker davegut.Logging, line 12
	def attrList = [:] // library marker davegut.Logging, line 13
	attrs.each { // library marker davegut.Logging, line 14
		def val = device.currentValue("${it}") // library marker davegut.Logging, line 15
		attrList << ["${it}": val] // library marker davegut.Logging, line 16
	} // library marker davegut.Logging, line 17
	if (trace == true) { // library marker davegut.Logging, line 18
		logTrace("Attributes: ${attrList}") // library marker davegut.Logging, line 19
	} else { // library marker davegut.Logging, line 20
		logDebug("Attributes: ${attrList}") // library marker davegut.Logging, line 21
	} // library marker davegut.Logging, line 22
} // library marker davegut.Logging, line 23

//	6.7.2 Change B.  Remove driverVer() // library marker davegut.Logging, line 25
def logTrace(msg){ // library marker davegut.Logging, line 26
	log.trace "${device.displayName}: ${msg}" // library marker davegut.Logging, line 27
} // library marker davegut.Logging, line 28

def logInfo(msg) {  // library marker davegut.Logging, line 30
	if (!infoLog || infoLog == true) { // library marker davegut.Logging, line 31
		log.info "${device.displayName}: ${msg}" // library marker davegut.Logging, line 32
	} // library marker davegut.Logging, line 33
} // library marker davegut.Logging, line 34

def debugLogOff() { // library marker davegut.Logging, line 36
	if (debug == true) { // library marker davegut.Logging, line 37
		device.updateSetting("debug", [type:"bool", value: false]) // library marker davegut.Logging, line 38
	} else if (debugLog == true) { // library marker davegut.Logging, line 39
		device.updateSetting("debugLog", [type:"bool", value: false]) // library marker davegut.Logging, line 40
	} // library marker davegut.Logging, line 41
	logInfo("Debug logging is false.") // library marker davegut.Logging, line 42
} // library marker davegut.Logging, line 43

def logDebug(msg) { // library marker davegut.Logging, line 45
	if (debug == true || debugLog == true) { // library marker davegut.Logging, line 46
		log.debug "${device.displayName}: ${msg}" // library marker davegut.Logging, line 47
	} // library marker davegut.Logging, line 48
} // library marker davegut.Logging, line 49

def logWarn(msg) { log.warn "${device.displayName}: ${msg}" } // library marker davegut.Logging, line 51

// ~~~~~ end include (1072) davegut.Logging ~~~~~

// ~~~~~ start include (1091) davegut.ST-Communications ~~~~~
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

// ~~~~~ end include (1091) davegut.ST-Communications ~~~~~

// ~~~~~ start include (1090) davegut.ST-Common ~~~~~
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
		case "10sec":  // library marker davegut.ST-Common, line 41
			schedule("*/10 * * * * ?", "poll")		 // library marker davegut.ST-Common, line 42
			break // library marker davegut.ST-Common, line 43
		case "20sec": // library marker davegut.ST-Common, line 44
			schedule("*/20 * * * * ?", "poll")		 // library marker davegut.ST-Common, line 45
			break // library marker davegut.ST-Common, line 46
		case "30sec": // library marker davegut.ST-Common, line 47
			schedule("*/30 * * * * ?", "poll")		 // library marker davegut.ST-Common, line 48
			break // library marker davegut.ST-Common, line 49
		case "1" : runEvery1Minute(poll); break // library marker davegut.ST-Common, line 50
		case "5" : runEvery5Minutes(poll); break // library marker davegut.ST-Common, line 51
		case "10" : runEvery10Minutes(poll); break // library marker davegut.ST-Common, line 52
		case "30" : runEvery30Minutes(poll); break // library marker davegut.ST-Common, line 53
		default: runEvery10Minutes(poll) // library marker davegut.ST-Common, line 54
	} // library marker davegut.ST-Common, line 55
} // library marker davegut.ST-Common, line 56

def deviceCommand(cmdData) { // library marker davegut.ST-Common, line 58
	def respData = [:] // library marker davegut.ST-Common, line 59
	if (simulate() == true) { // library marker davegut.ST-Common, line 60
		respData = testResp(cmdData) // library marker davegut.ST-Common, line 61
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 62
		respData << [status: "FAILED", data: "no stDeviceId"] // library marker davegut.ST-Common, line 63
	} else { // library marker davegut.ST-Common, line 64
		def sendData = [ // library marker davegut.ST-Common, line 65
			path: "/devices/${stDeviceId.trim()}/commands", // library marker davegut.ST-Common, line 66
			cmdData: cmdData // library marker davegut.ST-Common, line 67
		] // library marker davegut.ST-Common, line 68
		respData = syncPost(sendData) // library marker davegut.ST-Common, line 69
	} // library marker davegut.ST-Common, line 70
	if (cmdData.capability && cmdData.capability != "refresh") { // library marker davegut.ST-Common, line 71
		refresh() // library marker davegut.ST-Common, line 72
	} else { // library marker davegut.ST-Common, line 73
		poll() // library marker davegut.ST-Common, line 74
	} // library marker davegut.ST-Common, line 75
	return respData // library marker davegut.ST-Common, line 76
} // library marker davegut.ST-Common, line 77

def refresh() { // library marker davegut.ST-Common, line 79
	if (stApiKey!= null) { // library marker davegut.ST-Common, line 80
		def cmdData = [ // library marker davegut.ST-Common, line 81
			component: "main", // library marker davegut.ST-Common, line 82
			capability: "refresh", // library marker davegut.ST-Common, line 83
			command: "refresh", // library marker davegut.ST-Common, line 84
			arguments: []] // library marker davegut.ST-Common, line 85
		deviceCommand(cmdData) // library marker davegut.ST-Common, line 86
	} // library marker davegut.ST-Common, line 87
} // library marker davegut.ST-Common, line 88

def poll() { // library marker davegut.ST-Common, line 90
	if (simulate() == true) { // library marker davegut.ST-Common, line 91
		def children = getChildDevices() // library marker davegut.ST-Common, line 92
		if (children) { // library marker davegut.ST-Common, line 93
			children.each { // library marker davegut.ST-Common, line 94
				it.statusParse(testData()) // library marker davegut.ST-Common, line 95
			} // library marker davegut.ST-Common, line 96
		} // library marker davegut.ST-Common, line 97
		statusParse(testData()) // library marker davegut.ST-Common, line 98
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 99
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 100
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 101
	} else { // library marker davegut.ST-Common, line 102
		def sendData = [ // library marker davegut.ST-Common, line 103
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.ST-Common, line 104
			parse: "distResp" // library marker davegut.ST-Common, line 105
			] // library marker davegut.ST-Common, line 106
		asyncGet(sendData, "statusParse") // library marker davegut.ST-Common, line 107
	} // library marker davegut.ST-Common, line 108
} // library marker davegut.ST-Common, line 109

def deviceSetup() { // library marker davegut.ST-Common, line 111
	if (simulate() == true) { // library marker davegut.ST-Common, line 112
		def children = getChildDevices() // library marker davegut.ST-Common, line 113
		deviceSetupParse(testData()) // library marker davegut.ST-Common, line 114
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 115
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 116
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 117
	} else { // library marker davegut.ST-Common, line 118
		def sendData = [ // library marker davegut.ST-Common, line 119
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.ST-Common, line 120
			parse: "distResp" // library marker davegut.ST-Common, line 121
			] // library marker davegut.ST-Common, line 122
		asyncGet(sendData, "deviceSetup") // library marker davegut.ST-Common, line 123
	} // library marker davegut.ST-Common, line 124
} // library marker davegut.ST-Common, line 125

def getDeviceList() { // library marker davegut.ST-Common, line 127
	def sendData = [ // library marker davegut.ST-Common, line 128
		path: "/devices", // library marker davegut.ST-Common, line 129
		parse: "getDeviceListParse" // library marker davegut.ST-Common, line 130
		] // library marker davegut.ST-Common, line 131
	asyncGet(sendData) // library marker davegut.ST-Common, line 132
} // library marker davegut.ST-Common, line 133

def getDeviceListParse(resp, data) { // library marker davegut.ST-Common, line 135
	def respData // library marker davegut.ST-Common, line 136
	if (resp.status != 200) { // library marker davegut.ST-Common, line 137
		respData = [status: "ERROR", // library marker davegut.ST-Common, line 138
					httpCode: resp.status, // library marker davegut.ST-Common, line 139
					errorMsg: resp.errorMessage] // library marker davegut.ST-Common, line 140
	} else { // library marker davegut.ST-Common, line 141
		try { // library marker davegut.ST-Common, line 142
			respData = new JsonSlurper().parseText(resp.data) // library marker davegut.ST-Common, line 143
		} catch (err) { // library marker davegut.ST-Common, line 144
			respData = [status: "ERROR", // library marker davegut.ST-Common, line 145
						errorMsg: err, // library marker davegut.ST-Common, line 146
						respData: resp.data] // library marker davegut.ST-Common, line 147
		} // library marker davegut.ST-Common, line 148
	} // library marker davegut.ST-Common, line 149
	if (respData.status == "ERROR") { // library marker davegut.ST-Common, line 150
		logWarn("getDeviceListParse: ${respData}") // library marker davegut.ST-Common, line 151
	} else { // library marker davegut.ST-Common, line 152
		log.info "" // library marker davegut.ST-Common, line 153
		respData.items.each { // library marker davegut.ST-Common, line 154
			log.trace "${it.label}:   ${it.deviceId}" // library marker davegut.ST-Common, line 155
		} // library marker davegut.ST-Common, line 156
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>" // library marker davegut.ST-Common, line 157
	} // library marker davegut.ST-Common, line 158
} // library marker davegut.ST-Common, line 159

def calcTimeRemaining(completionTime) { // library marker davegut.ST-Common, line 161
	Integer currTime = now() // library marker davegut.ST-Common, line 162
	Integer compTime // library marker davegut.ST-Common, line 163
	try { // library marker davegut.ST-Common, line 164
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 165
	} catch (e) { // library marker davegut.ST-Common, line 166
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 167
	} // library marker davegut.ST-Common, line 168
	Integer timeRemaining = ((compTime-currTime) /1000).toInteger() // library marker davegut.ST-Common, line 169
	if (timeRemaining < 0) { timeRemaining = 0 } // library marker davegut.ST-Common, line 170
	return timeRemaining // library marker davegut.ST-Common, line 171
} // library marker davegut.ST-Common, line 172

// ~~~~~ end include (1090) davegut.ST-Common ~~~~~
