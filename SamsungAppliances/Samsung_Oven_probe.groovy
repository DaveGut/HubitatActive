/*	Samsung Oven Probe using SmartThings Interface
		Copyright Dave Gutheinz
License Information:
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Description
This is a child driver to Samsung Oven and will not work indepenently of same.
===== Version 1.1 ==============================================================================*/
def driverVer() { return "1.2" }
def nameSpace() { return "davegut" }

metadata {
	definition (name: "Samsung Oven probe",
				namespace: nameSpace(),
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Oven_probe.groovy"
			   ){
		capability "Thermostat Setpoint"
		capability "Temperature Measurement"
		command "setProbeSetpoint", ["NUM"]
		attribute "probeStatus", "string"
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

def setProbeSetpoint(setpoint) {
	logDebug("setProbeSetpoint: ${setpoint}")
	def cmdData = [
		component: "main",
		capability: "samsungce.meatProbe",
		command: "setTemperatureSetpoint",
		arguments: [setpoint]]
	def cmdStatus = parent.deviceCommand(cmdData)
	logInfo("setProbeSetpoint: [setpoint: ${setpoint}, status: ${cmdStatus}]")
}

def statusParse(respData) {
	try {
		respData = respData.components.main["samsungce.meatProbe"]
	} catch (err) {
		logWarn("status: [status: ERROR, error: ${err}]")
		return
	}

	def tempUnit = respData.temperature.unit
	def temperature = 0
	def thermostatSetpoint = 0
	def probeStatus = "not present"
	def status = respData.status.value
	if (status == "connected") {probeStatus = "present" }
	if (probeStatus == "present") {
		temperature = respData.temperature.value
		thermostatSetpoint = respData.temperatureSetpoint.value
	}
	sendEvent(name: "probeStatus", value: probeStatus)
	sendEvent(name: "temperature", value: temperature, unit: tempUnit)
	sendEvent(name: "thermostatSetpoint", value: thermostatSetpoint, unit: tempUnit)

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
