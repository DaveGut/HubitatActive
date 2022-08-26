/*	Samsung Refrigerator Cavity using SmartThings Interface
		Copyright Dave Gutheinz
License Information:
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Description
This is a child driver to Samsung Oven and will not work indepenently of same.
==============================================================================*/
def driverVer() { return "B0.5" }

metadata {
	definition (name: "Samsung Refrig cavity",
				namespace: "davegut",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Refrig_cavity.groovy"
			   ){
		capability "Contact Sensor"
		capability "Temperature Measurement"
		capability "Thermostat Cooling Setpoint"
	}
	preferences {
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

def setCoolingSetpoint(setpoint) {
	def cmdData = [
		component: getDataValue("component"),
		capability: "thermostatCoolingSetpoint",
		command: "setCoolingSetpoint",
		arguments: [setpoint]]
	def cmdStatus = parent.deviceCommand(cmdData)
	logInfo("setCoolingSetpoint: [cmd: ${setpoint}, ${cmdStatus}]")
}

def statusParse(respData) {
	def parseData
	def componentId = getDataValue("component")
	try {
		parseData = respData.components[componentId]
	} catch (error) {
		logWarn("statusParse: [respData: ${respData}, error: ${error}]")
	}
		
	def logData = [:]
	def contact = parseData.contactSensor.contact.value
	if (device.currentValue("contact") != contact) {
		sendEvent(name: "contact", value: contact)
		logData << [contact: contact]
	}
	
	def tempUnit = parseData.thermostatCoolingSetpoint.coolingSetpoint.unit
	def coolingSetpoint = parseData.thermostatCoolingSetpoint.coolingSetpoint.value
	if (device.currentValue("coolingSetpoint") != coolingSetpoint) {
		sendEvent(name: "coolingSetpoint", value: coolingSetpoint, unit: tempUnit)
		logData << [coolingSetpoint: coolingSetpoint, unit: tempUnit]
	}
	
	if (parent.getDataValue("dongle") == "false") {
		def temperature = parseData.temperatureMeasurement.temperature.value
		if (device.currentValue("temperature") != temperature) {
			sendEvent(name: "temperature", value: temperature, unit: tempUnit)
			logData << [temperature: temperature]
		}
	}
	
	if (logData != [:]) {
		logInfo("getDeviceStatus: ${logData}")
	}
	
	runIn(1, listAttributes, [data: true])
//	runIn(1, listAttributes)
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
