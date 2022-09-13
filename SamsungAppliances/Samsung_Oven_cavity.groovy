/*	Samsung Oven Cavity using SmartThings Interface
		Copyright Dave Gutheinz
License Information:
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Description
This is a child driver to Samsung Oven and will not work indepenently of same.
===== Version 1.1 ==============================================================================*/
def driverVer() { return "1.2" }
def nameSpace() { return "davegut" }

metadata {
	definition (name: "Samsung Oven cavity",
				namespace: nameSpace(),
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Oven_cavity.groovy"
			   ){
		capability "Thermostat Setpoint"
		capability "Temperature Measurement"
		command "pauseOven"
		command "runOven"
		command "stopOven"
		command "setOvenSetpoint", ["NUMBER"]
		attribute "cavityStatus", "string"
		attribute "timeRemaining", "string"
		attribute "completionTime", "NUMBER"
		attribute "ovenState", "string"
		attribute "jobState", "string"
		attribute "ovenMode", "string"
	}
	preferences {
		input ("infoLog", "bool",  
			   title: "Info logging", defaultValue: true)
		input ("debugLog", "bool",  
			   title: "Enable debug logging for 30 minutes", defaultValue: false)
	}
}

def installed() {
	runIn(1, updated)
}

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

def pauseOven() {
	parent.setMachineState("paused", "cavity-01")
}
def runOven() {
	parent.setMachineState("running", "cavity-01")
}

def stopOven() { 
	parent.stopOven("cavity-01")
}

def setOvenSetpoint(setpoint) {
	parent.setOvenSetpoint(setpoint, "cavity-01")
}

def statusParse(respData) {
	try {
		respData = respData.components["cavity-01"]
	} catch (err) {
		logWarn("statusParse: [respData: ${respData}, error: ${error}]")
		return
	}
	
	def logData = [:]
	def tempUnit = respData.temperatureMeasurement.temperature.unit
	def temperature = 0
	def thermostatSetpoint = 0
	def completionTime = 0
	def timeRemaining = 0
	def ovenState = "notPresent"
	def jobState = "notPresent"
	def ovenMode = "notPresent"

	def cavityStatus = "not present"
	def status = respData["custom.ovenCavityStatus"].ovenCavityStatus.value
	if (status == "on") { cavityStatus = "present" }
	
	if (cavityStatus == "present") {
		temperature = respData.temperatureMeasurement.temperature.value
		thermostatSetpoint = respData.ovenSetpoint.ovenSetpoint.value
		completionTime = respData.ovenOperatingState.completionTime.value
		if (completionTime != null) {
			timeRemaining = parent.calcTimeRemaining(completionTime)
		}
		ovenState = respData.ovenOperatingState.machineState.value
		jobState = respData.ovenOperatingState.ovenJobState.value
		ovenMode = respData.ovenMode.ovenMode.value
	}
	
	sendEvent(name: "cavityStatus", value: cavityStatus)
	sendEvent(name: "temperature", value: temperature, unit: tempUnit)
	sendEvent(name: "thermostatSetpoint", value: thermostatSetpoint, unit: tempUnit)
	sendEvent(name: "completionTime", value: completionTime)
	sendEvent(name: "timeRemaining", value:timeRemaining)
	sendEvent(name: "ovenState", value: ovenState)
	sendEvent(name: "jobState", value: jobState)
	sendEvent(name: "ovenMode", value: ovenMode)
	
	if (parent.simulate() == true) {
		runIn(1, listAttributes, [data: true])
	} else {
		runIn(1, listAttributes)
	}
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
