/*	HubiThings Replica Refrigerator Driver
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
import org.json.JSONObject
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
def driverVer() { return "0.9T" }

metadata {
	definition (name: "Replica Samsung Refrigerator",
				namespace: "replica",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/HubiThingsReplica%20Drivers/Replica_Samsung_Refrigerator.groovy"
			   ){
		capability "Configuration"
		capability "Refresh"
		attribute "healthStatus", "enum", ["offline", "online"]
		capability "Contact Sensor"
		//	Refrigeration
		command "setRapidCooling", [[ name: "Rapid Cooling", constraints: ["on", "off"], type: "ENUM"]]
		attribute "rapidCooling", "string"
		command "setRapidFreezing", [[ name: "Rapid Freezing", constraints: ["on", "off"], type: "ENUM"]]
		attribute "rapidFreezing", "string"
		command "defrost", [[ name: "Defrost", constraints: ["on", "off"], type: "ENUM"]]
		attribute "defrost", "string"
		//	samsungce_powerCool, samsungce_powerFreeze
		command "setPowerCool", [[name: "powerCool", constraints: ["on", "off"], type: "ENUM"]]
		attribute "powerCooling", "bool"
		command "setPowerFreeze", [[name: "powerFreeze", constraints: ["on", "off"], type: "ENUM"]]
		attribute "powerFreezing", "bool"
		//	temperatureMeasurement, thermostatCoolingSetpoint, xxcustom.fridgeMode
		capability "Temperature Measurement"
		capability "Thermostat Cooling Setpoint"
		command "raiseSetpoint"			//	development command
		command "lowerSetpoint"			//	development command
		attribute "fridgeMode", "string"
		//	custom.deodorFilter
		attribute "deodorFilterStatus", "string"
		attribute "deodorFilterUsage", "string"
		command "resetDeodorFilter"
		//	custom.waterFilter
		attribute "waterFilterStatus", "string"
		attribute "waterFilterUsage", "string"
		command "resetWaterFilter"
	}
	preferences {
		input ("logEnable", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false)
		input ("infoLog", "bool", title: "Enable information logging",defaultValue: true)
		input ("traceLog", "bool", title: "Enable trace logging as directed by developer", defaultValue: false)
	}
}

//	===== Installation, setup and update =====
def installed() {
	updateDataValue("componentId", "main")
	configure()
	runIn(5, updated)
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
	refresh()
	runIn(10, listAttributes,[data:true])
	logInfo("updated: ${updStatus}")
}

def configure() {
	Map logData = [:]
    updateDataValue("triggers", groovy.json.JsonOutput.toJson(getReplicaTriggers()))
    updateDataValue("commands", groovy.json.JsonOutput.toJson(getReplicaCommands()))
	logData << [triggers: "initialized", commands: "initialized"]
	setReplicaRules()
	logData << [replicaRules: "initialized"]
	state.checkCapabilities = true
	sendCommand("configure")
	logData: [device: "configuring"]
	logInfo("configure: ${logData}")
}

Map getReplicaCommands() {
    return (["replicaEvent":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]], 
			 "replicaStatus":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]], 
			 "replicaHealth":[[name:"parent*",type:"OBJECT"],[name:"health*",type:"JSON_OBJECT"]],
			 "setHealthStatusValue":[[name:"healthStatus*",type:"ENUM"]]])
}

Map getReplicaTriggers() {
	Map triggers = [ 
		refresh:[], deviceRefresh: []
	]
	return triggers
}

String setReplicaRules() {
	def rules = """{"version":1,"components":[
{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger","mute":true},
{"trigger":{"name":"refresh","label":"command: refresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},
{"trigger":{"name":"deviceRefresh","label":"command: deviceRefresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"}]}"""

	updateDataValue("rules", rules)
}

//	===== Event Parse Interface s=====
void replicaStatus(def parent=null, Map status=null) {
	def logData = [parent: parent, status: status]
	if (state.checkCapabilities) {
		runIn(2, checkCapabilities, [data: status.components])
	} else if (state.refreshAttributes) {
		runIn(1, refreshComponents, [data: status.components])
	}
	logDebug("replicaStatus: ${logData}")
}

def refreshComponents(components) {
	refreshAttributes(components)
	getChildDevices().each {
		it.refreshAttributes(components)
	}
}

def checkCapabilities(components) {
	state.checkCapabilities = false
	def disabledCapabilities = []
	try {
		disabledCapabilities << components.main["custom.disabledCapabilities"].disabledCapabilities.value
	} catch (e) { }

	def enabledCapabilities = []
	Map description = new JsonSlurper().parseText(getDataValue("description"))
	def descComponent = description.components.find { it.id == getDataValue("componentId") }
	descComponent.capabilities.each { capability ->
		if (designCapabilities().contains(capability.id) &&
			!disabledCapabilities.contains(capability.id)) {
			enabledCapabilities << capability.id
		}
	}
	state.deviceCapabilities = enabledCapabilities
//	runIn(5, refreshAttributes, [data: components])
	runIn(1, continueCapabilities, [data: components])
	logInfo("checkCapabilities: [disabled: ${disabledCapabilities}, enabled: ${enabledCapabilities}]")
}

def designCapabilities() {
	return [
		"contactSensor", "refresh", "refrigeration", "temperatureMeasurement",
		"thermostatCoolingSetpoint", "custom.deodorFilter", "custom.fridgeMode",
		"custom.waterFilter", "samsungce.powerCool", "samsungce.powerFreeze"
	]
}

def continueCapabilities(components) {
	refreshAttributes(components)
	configureChildren(components)
}

//	===== Child Configure / Install =====
def configureChildren(components) {
log.warn "at configureChildren"
	def logData = [:]
	def disabledComponents = components.main["custom.disabledComponents"].disabledComponents.value
	designChildren().each { designChild ->
		if (disabledComponents.contains(designChild.key)) {
			logData << ["${designChild.key}": [status: "SmartThingsDisabled"]]
		} else {
			def dni = device.getDeviceNetworkId()
			def childDni = "dni-${designChild.key}"
			def child = getChildDevice(childDni)
			if (child == null) {
				def type = "Replica Samsung Refrigerator ${designChild.value}"
				def name = "${device.displayName} ${designChild.key}"
				try {
					addChildDevice("replicaChild", "${type}", "${childDni}", [
						name: type, 
						label: name, 
						componentId: designChild.key
					])
					logData << ["${name}": [status: "installed"]]
				} catch (error) {
					logData << ["${name}": [status: "FAILED", reason: error]]
				}
			} else {
				child.checkCapabilities(components)
				logData << ["${name}": [status: "already installed"]]
			}
		}
	}
	runIn(1, checkChildren, [data: components])
	logInfo("configureChildren: ${logData}")
}

Map designChildren() {
	return [freezer: "cavity", cooler: "cavity", cvroom: "cavity", 
			onedoor: "cavity", icemaker: "icemaker", "icemaker-02": "icemaker"
		   ]
}

def checkChildren(components) {
	getChildDevices().each {
		it.checkCapabilities(components)
	}
}

//	===== Attributes
def refreshAttributes(components) {
	state.refreshAttributes = false
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

void replicaHealth(def parent=null, Map health=null) {
	if(parent) { logInfo("replicaHealth: ${parent?.getLabel()}") }
	if(health) { logInfo("replicaHealth: ${health}") }
}

def setHealthStatusValue(value) {    
    sendEvent(name: "healthStatus", value: value, descriptionText: "${device.displayName} healthStatus set to $value")
}

void replicaEvent(def parent=null, Map event=null) {
	if (event.deviceEvent.componentId == "main") {
		try {
			parseEvent(event.deviceEvent)
		} catch (err) {
			logWarn("replicaEvent: [event = ${event}, error: ${err}")
		}
	} else {
		getChildDevices().each { 
			it.parentEvent(event)
		}
	}
}

def parseEvent(event) {
	logDebug("parseEvent: <b>${event}</b>")
	//	some attribute names changed for clarity.  list attributes stored as states.
	if (state.deviceCapabilities.contains(event.capability)) {
		if (event.value != null) {
			switch(event.attribute) {
				case "activated":
					def attribute = "powerCooling"
					if (event.capability == "samsungce.powerFreeze") {
						attribute = "powerFreezing"
					}
					parseCorrect([capability: event.capability, attribute: attribute, 
								  value: event.value, unit: event.unit])
					break
				case "deodorFilterLastResetDate":
				case "deodorFilterCapacity":
				case "deodorFilterResetType":
				case "deodorFilterUsageStep":
				case "waterFilterUsageStep":
				case "waterFilterResetType":
				case "waterFilterCapacity":
				case "waterFilterLastResetDate":
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
	logTrace("parseCorrect: <b>${event}</b>")
	if (device.currentValue(event.attribute).toString() != event.value.toString()) {
		sendEvent(name: event.attribute, value: event.value, unit: event.unit)
		logInfo("parseCorrect: [event: ${event}]")
	}
}

//	Used for any rule-based commands
private def sendCommand(String name, def value=null, String unit=null, data=[:]) {
    parent?.deviceTriggerHandler(device, [name:name, value:value, unit:unit, data:data, now:now])
}

def sendRawCommand(component, capability, command, arguments = []) {
	def deviceId = new JSONObject(getDataValue("description")).deviceId
	parent.setSmartDeviceCommand(deviceId, component, capability, command, arguments)
	logDebug("sendRawCommand: [${deviceId}, ${component}, ${capability}, ${command}, ${arguments}]")
}

//	===== Refresh Commands =====
def refresh() {
	logDebug("refresh")
	state.refreshAttributes = true
	runInMillis(100, sendRefresh)
}

def sendRefresh() {
	sendCommand("deviceRefresh")
	pauseExecution(200)
	sendCommand("refresh")
}

//	===== Device Commands =====
//	===== Refrigeration
def setRapidCooling(onOff) {
	setRefrigeration("setRapidCooling", onOff)
}

def setRapidFreezing(onOff) {
	setRefrigeration("setRapidFreezing", onOff)
}

def defrost(onOff) {
	setRefrigeration("setDefrost", onOff)
}

def setRefrigeration(command, onOff) {
	if (state.deviceCapabilities.contains("refrigeration")) {
		sendRawCommand("main", "refrigeration", command, [onOff])
		logInfo("setRefrigeration: [cmd ${command}, onOff: ${onOff}]")
	} else {
		logWarn("setRefrigeration: Device does not support setRefrigeration")
	}
}

def setPowerCool(onOff) {
	if (state.deviceCapabilities.contains("samsungce.powerCool")) {
		def cmd = "deactivate"
		if (onOff == "on") { cmd = "activate" }
		sendRawCommand("main", "samsungce.powerCool", cmd)
		logInfo("setPowerCool: ${cmd}")
	}
}
	
def setPowerFreeze(onOff) {
	if (state.deviceCapabilities.contains("samsungce.powerFreeze")) {
		def cmd = "deactivate"
		if (onOff == "on") { cmd = "activate" }
		sendRawCommand("main", "samsungce.powerFreeze", cmd)
		logInfo("setPowerFreeze: ${cmd}")
	}
}

//	===== Thermostat Cooling Setpoint
def lowerSetpoint() {
	def newSetpoint = device.currentValue("coolingSetpoint").toInteger() - 1
	sendEvent(name: "coolingSetpoint", value: newSetpoint)
	runIn(5, setCoolingSetpoint)
}
	
def raiseSetpoint() {
	def newSetpoint = device.currentValue("coolingSetpoint").toInteger() + 1
	sendEvent(name: "coolingSetpoint", value: newSetpoint)
	runIn(5, setCoolingSetpoint)
}
	
def setCoolingSetpoint(setpoint = device.currentValue("coolingSetpoint")) {
	if (state.deviceCapabilities.contains("thermostatCoolingSetpoint")) {
		sendRawCommand("main", "thermostatCoolingSetpoint", "setCoolingSetpoint", [setpoint])
		logInfo("setCoolingSetpoint: [setpoint: ${setpoint}]")
	} else {
		logWarn("setRefrigeration: Device does not support setCoolingSetpoint")
	}
}

//	===== Filters (odor/water)
def resetDeodorFilter() {
	if (state.deviceCapabilities.contains("custom.deodorFilter")) {
		sendRawCommand("main", "custom.deodorFilter", "resetDeodorFilter")
		logInfo("resetDeodorFilter")
	} else {
		logWarn("resetDeodorFilter: Device does not support resetDeodorFilter")
	}
}
	
def resetWaterFilter() {
	if (state.deviceCapabilities.contains("custom.deodorFilter")) {
		sendRawCommand("main", "custom.waterFilter", "resetWaterFilter")
		logInfo("resetWaterFilter")
	} else {
		logWarn("resetWaterFilter: Device does not support resetWaterFilter")
	}
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
