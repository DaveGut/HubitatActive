/*	HubiThings Replica Refrigerator cavity Driver
	HubiThings Replica Applications Copyright 2023 by Bloodtick
	Replica RangeOven Copyright 2023 by Dave Gutheinz

	Licensed under the Apache License, Version 2.0 (the "License"); 
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at:
	      http://www.apache.org/licenses/LICENSE-2.0
	Unless required by applicable law or agreed to in writing, software 
	distributed under the License is distributed on an "AS IS" BASIS, 
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or 
	implied. See the License for the specific language governing 
	permissions and limitations under the License.

Issues with this driver: Contact davegut via Private Message on the
Hubitat Community site: https://community.hubitat.com/
==========================================================================*/
import groovy.json.JsonSlurper
def driverVer() { return "0.9T" }

metadata {
	definition (name: "Replica Samsung Refrigerator cavity",
				namespace: "replicaChild",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/HubiThingsReplica%20Drivers/Replica_Samsung_Refrigerator_cavity.groovy"
			   ){
		capability "Refresh"
		capability "Contact Sensor"
		//	temperatureMeasurement, thermostatCoolingSetpoint, xxcustom.fridgeMode
		capability "Temperature Measurement"
		capability "Thermostat Cooling Setpoint"
		command "raiseSetpoint"
		command "lowerSetpoint"
		attribute "fridgeMode", "string"
	}
	preferences {
		input ("logEnable", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false)
		input ("infoLog", "bool", title: "Enable information logging",defaultValue: true)
		input ("traceLog", "bool", title: "Enable trace logging as directed by developer", defaultValue: false)
	}
}

//	===== Installation, setup and update =====
def installed() {
	runIn(1, updated)
}

def updated() {
	unschedule()
	def updStatus = [:]
	if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
		updStatus << [driverVer: driverVer()]
	}
	if (logEnable) { runIn(1800, debugLogOff) }
	if (traceLog) { runIn(600, traceLogOff) }
	updStatus << [logEnable: logEnable, infoLog: infoLog, traceLog: traceLog]
	listAttributes(true)
	logInfo("updated: ${updStatus}")
}

//	===== Event Parse Interface s=====
def checkCapabilities(components) {
	def disabledCapabilities = []
	try {
		disabledCapabilities << components.main["custom.disabledCapabilities"].disabledCapabilities.value
	} catch (e) { }

	def enabledCapabilities = []
	Map description = new JsonSlurper().parseText(parent.getDataValue("description"))
	def descComponent = description.components.find { it.id == getDataValue("componentId") }
	descComponent.capabilities.each { capability ->
		if (designCapabilities().contains(capability.id) &&
			!disabledCapabilities.contains(capability.id)) {
			enabledCapabilities << capability.id
		}
	}
	state.deviceCapabilities = enabledCapabilities
	runIn(1, refreshAttributes, [data: components])
	logInfo("checkCapabilities: [disabled: ${disabledCapabilities}, enabled: ${enabledCapabilities}]")
}

def designCapabilities() {
	return [
		"contactSensor", "temperatureMeasurement", "thermostatCoolingSetpoint",
		"custom.fridgeMode", "custom.thermostatSetpointControl",
		"samsungce.temperatureSetting"
	]
}

def refreshAttributes(components) {
	def component = components."${getDataValue("componentId")}"
	logDebug("refreshAttributes: ${component}")
	component.each { capability ->
		capability.value.each { attribute ->
			parseEvent([capability: capability.key,
						attribute: attribute.key,
						value: attribute.value.value,
						unit: attribute.value.unit])
			pauseExecution(50)
		}
	}
	listAttributes(false)
}

void parentEvent(Map event) {
	if (event.deviceEvent.componentId == getDataValue("componentId")) {
		try {
			parseEvent(event.deviceEvent)
		} catch (err) {
			logWarn("replicaEvent: [event = ${event}, error: ${err}")
		}
	}
}

def parseEvent(event) {
	logDebug("parseEvent: <b>${event}</b>")
	//	some attribute names changed for clarity.  list attributes stored as states.
	if (state.deviceCapabilities.contains(event.capability)) {
		if (event.value != null) {
			switch(event.attribute) {
				case "desiredTemperature":
					parseCorrect([capability: "coolingSetpoint", attribute: event.attribute, 
								  value: event.value, unit: event.unit])
					break
				case "minimumSetpoint":
				case "maximumSetpoint":
				case "supportedDesiredTemperatures":
				case "fridgeModeValue":
					logDebug("parseEvent: [ignoredEvent: ${event}]")
					break
				default:
					if (device.currentValue(event.attribute).toString() != event.value.toString()) {
						sendEvent(name: event.attribute, value: event.value, unit: event.unit)
						logInfo("parseEvent: [event: ${event}]")
					}
			}
		}
	} else {
		logDebug("parseEvent: [filteredEvent: ${event}]")
	}
}

def parseCorrect(event) {
	logInfo("parseCorrect: <b>${event}</b>")
	if (device.currentValue(event.attribute).toString() != event.value.toString()) {
		sendEvent(name: event.attribute, value: event.value, unit: event.unit)
		logInfo("parseCorrect: [event: ${event}]")
	}
}

//	===== Device Commands =====
def sendCommandToParent(capability, command, arguments = []) {
	parent.sendRawCommand(getDataValue("componentId"), capability, command, arguments)
	logDebug("sendCommandToParent: [${getDataValue("componentId")}, ${capability}, ${command}, ${arguments}]")
}

def refresh() { parent.refresh() }

//	===== Thermostat Cooling Setpoint
def lowerSetpoint() {
	if (state.deviceCapabilities.contains("custom.thermostatSetpointControl")) {
		sendCommandToParent("custom.thermostatSetpointControl", "lowerSetpoint", [])
		logInfo("lowerSetpoint: sent via lowerSetpoint command")
	} else {
		def newSetpoint = device.currentValue("coolingSetpoint").toInteger() - 1
		sendEvent(name: "coolingSetpoint", value: newSetpoint)
		logDebug("coolingSetpoint: sent to setCoolingSetpoint method")
		runIn(5, setCoolingSetpoint)
	}
}
	
def raiseSetpoint() {
	if (state.deviceCapabilities.contains("custom.thermostatSetpointControl")) {
		sendCommandToParent("custom.thermostatSetpointControl", "raiseSetpoint", [])
		logInfo("raiseSetpoint: sent via raiseSetpoint command")
	} else {
		def newSetpoint = device.currentValue("coolingSetpoint").toInteger() + 1
		sendEvent(name: "coolingSetpoint", value: newSetpoint)
		logDebug("raiseSetpoint: sent to setCoolingSetpoint method")
		runIn(5, setCoolingSetpoint)
	}
}
	
def setCoolingSetpoint(setpoint = device.currentValue("coolingSetpoint")) {
	if (state.deviceCapabilities.contains("samsungce.temperatureSetting")) {
		sendCommandToParent("samsungce.temperatureSetting", "desiredTemperature", [setpoint])
	} else if (state.deviceCapabilities.contains("thermostatCoolingSetpoint")) {
		sendCommandToParent("thermostatCoolingSetpoint", "setCoolingSetpoint", [setpoint])
	} else {
		logWarn("setRefrigeration: Device does not support setCoolingSetpoint")
	}
	logInfo("setCoolingSetpoint: [setpoint: ${setpoint}]")
}

//	===== Libraries =====


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
		logInfo("Attributes: ${attrList}") // library marker davegut.Logging, line 19
	} else { // library marker davegut.Logging, line 20
		logDebug("Attributes: ${attrList}") // library marker davegut.Logging, line 21
	} // library marker davegut.Logging, line 22
} // library marker davegut.Logging, line 23

def logTrace(msg){ // library marker davegut.Logging, line 25
	if (traceLog == true) { // library marker davegut.Logging, line 26
		log.trace "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.Logging, line 27
	} // library marker davegut.Logging, line 28
} // library marker davegut.Logging, line 29

def traceLogOff() { // library marker davegut.Logging, line 31
	device.updateSetting("traceLog", [type:"bool", value: false]) // library marker davegut.Logging, line 32
	logInfo("traceLogOff") // library marker davegut.Logging, line 33
} // library marker davegut.Logging, line 34

def logInfo(msg) {  // library marker davegut.Logging, line 36
	if (textEnable || infoLog) { // library marker davegut.Logging, line 37
		log.info "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.Logging, line 38
	} // library marker davegut.Logging, line 39
} // library marker davegut.Logging, line 40

def debugLogOff() { // library marker davegut.Logging, line 42
	device.updateSetting("logEnable", [type:"bool", value: false]) // library marker davegut.Logging, line 43
	logInfo("debugLogOff") // library marker davegut.Logging, line 44
} // library marker davegut.Logging, line 45

def logDebug(msg) { // library marker davegut.Logging, line 47
	if (logEnable || debugLog) { // library marker davegut.Logging, line 48
		log.debug "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.Logging, line 49
	} // library marker davegut.Logging, line 50
} // library marker davegut.Logging, line 51

def logWarn(msg) { log.warn "${device.displayName}-${driverVer()}: ${msg}" } // library marker davegut.Logging, line 53

// ~~~~~ end include (1072) davegut.Logging ~~~~~
