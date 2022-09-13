/*	Samsung Washer Flex Compartment using SmartThings Interface
		Copyright Dave Gutheinz
License Information:
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Description
This is a child driver to Samsung Dryer and will not work indepenently of same.
===== Version 1.1 ==============================================================================*/
def driverVer() { return "1.1" }
def nameSpace() { return "davegut" }

metadata {
	definition (name: "Samsung Washer flex",
				namespace: nameSpace(),
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Washer_flex.groovy"
			   ){
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
		attribute "waterTemperature", "string"
	}
	preferences {
		input ("debugLog", "bool",
			   title: "Enable debug logging for 30 minutes", defaultValue: false)
	}
}

def installed() { runIn(1, updated) }

def updated() {
	def logData = [:]
	if (driverVer() != parent.driverVer()) {
		logWarn("updated: Child driver version does not match parent.")
	}
	if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
		logData << [driverVersion: driverVer()]
	}
	if (logData != [:]) {
		logInfo("updated: ${logData}")
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
	def cmdStatus = parent.deviceCommand(cmdData)
	logInfo("setMachineState: [cmd: ${machState}, status: ${cmdStatus}]")
}

def statusParse(respData) {
	def parseData
	def componentId = getDataValue("component")
	try {
		parseData = respData.components[componentId]
	} catch (error) {
		logWarn("statusParse: [parseData: ${respData}, error: ${error}]")
		return
	}

	def onOff = parseData.switch.switch.value
	sendEvent(name: "switch", value: onOff)
	
	if (parseData["samsungce.kidsLock"]) {
		def kidsLock = parseData["samsungce.kidsLock"].lockState.value
		sendEvent(name: "kidsLock", value: kidsLock)
	}

	def machineState = parseData.washerOperatingState.machineState.value
	sendEvent(name: "machineState", value: machineState)
	
	def jobState = parseData.washerOperatingState.washerJobState.value
	sendEvent(name: "jobState", value: jobState)
	
	def remoteControlEnabled = parseData.remoteControlStatus.remoteControlEnabled.value
	sendEvent(name: "remoteControlEnabled", value: remoteControlEnabled)
	
	def completionTime = parseData.washerOperatingState.completionTime.value
	if (completionTime != null) {
		def timeRemaining = parent.calcTimeRemaining(completionTime)
		sendEvent(name: "completionTime", value: completionTime)
		sendEvent(name: "timeRemaining", value: timeRemaining)
	}

	def waterTemperature = parseData["custom.washerWaterTemperature"].washerWaterTemperature.value
	sendEvent(name: "waterTemperature", value: waterTemperature)

//	runIn(1, listAttributes, [data: true])
	runIn(1, listAttributes)
}

//	===== Library Integration =====


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

def logTrace(msg){ // library marker davegut.Logging, line 25
	log.trace "${device.displayName} ${driverVer()}: ${msg}" // library marker davegut.Logging, line 26
} // library marker davegut.Logging, line 27

def logInfo(msg) {  // library marker davegut.Logging, line 29
	if (infoLog == true) { // library marker davegut.Logging, line 30
		log.info "${device.displayName} ${driverVer()}: ${msg}" // library marker davegut.Logging, line 31
	} // library marker davegut.Logging, line 32
} // library marker davegut.Logging, line 33

def debugLogOff() { // library marker davegut.Logging, line 35
	if (debug == true) { // library marker davegut.Logging, line 36
		device.updateSetting("debug", [type:"bool", value: false]) // library marker davegut.Logging, line 37
	} else if (debugLog == true) { // library marker davegut.Logging, line 38
		device.updateSetting("debugLog", [type:"bool", value: false]) // library marker davegut.Logging, line 39
	} // library marker davegut.Logging, line 40
	logInfo("Debug logging is false.") // library marker davegut.Logging, line 41
} // library marker davegut.Logging, line 42

def logDebug(msg) { // library marker davegut.Logging, line 44
	if (debug == true || debugLog == true) { // library marker davegut.Logging, line 45
		log.debug "${device.displayName} ${driverVer()}: ${msg}" // library marker davegut.Logging, line 46
	} // library marker davegut.Logging, line 47
} // library marker davegut.Logging, line 48

def logWarn(msg) { log.warn "${device.displayName} ${driverVer()}: ${msg}" } // library marker davegut.Logging, line 50

// ~~~~~ end include (1072) davegut.Logging ~~~~~
