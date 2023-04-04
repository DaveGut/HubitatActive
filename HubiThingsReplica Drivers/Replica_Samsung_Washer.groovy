/*	HubiThings Replica RangeOven Driver
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
def driverVer() { return "1.0" }
def appliance() { return "Samsung Washer" }

metadata {
	definition (name: "Replica ${appliance()}",
				namespace: "replica",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/HubiThingsReplica%20Drivers/Replica_Samsung_Washer.groovy"
			   ){
		capability "Configuration"
		capability "Refresh"
		attribute "healthStatus", "enum", ["offline", "online"]
		capability "Refresh"
		attribute "switch", "string"
		command "start"
		command "pause"
		command "stop"
		attribute "machineState", "string"
		attribute "lockState", "string"
		attribute "remoteControlEnabled", "boolean"
		attribute "completionTime", "string"
		attribute "timeRemaining", "number"
		attribute "washerWaterTemperature", "string"
		attribute "washerJobState", "string"
		attribute "washerSoilLevel", "string"
		attribute "washerSpinLevel", "string"
		attribute "jobBeginningStatus", "string"
	}
	preferences {
		input ("logEnable", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false)
		input ("infoLog", "bool", title: "Enable information logging${helpLogo()}",defaultValue: true)
		input ("traceLog", "bool", title: "Enable trace logging as directed by developer", defaultValue: false)
	}
}

String helpLogo() {
	return """<a href="https://github.com/DaveGut/HubitatActive/blob/master/HubiThingsReplica%20Drivers/Docs/SamsungWasherReadme.md">""" +
		"""<div style="position: absolute; top: 20px; right: 150px; height: 80px; font-size: 28px;">Washer Help</div></a>"""
}

//	===== Installation, setup and update =====
def installed() {
	updateDataValue("componentId", "main")
	runIn(1, updated)
}

def updated() {
	unschedule()
	pauseExecution(2000)
	def updStatus = [:]
	if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
		updStatus << [driverVer: driverVer()]
	}
	if (logEnable) { runIn(1800, debugLogOff) }
	if (traceLog) { runIn(600, traceLogOff) }
	updStatus << [logEnable: logEnable, infoLog: infoLog, traceLog: traceLog]
	runIn(3, configure)
	logInfo("updated: ${updStatus}")
}

def designCapabilities() {
	return ["refresh", "remoteControlStatus", "samsungce.kidsLock", 
			"switch", "washerOperatingState", "custom.washerWaterTemperature",
		    "custom.washerSoilLevel",  "custom.washerSpinLevel",  
			"custom.jobBeginningStatus"]
}

Map designChildren() { return [:] }

def sendRawCommand(component, capability, command, arguments = []) {
	Map status = [:]
	def rcEnabled = device.currentValue("remoteControlEnabled")
	if (rcEnabled) {
		def deviceId = new JSONObject(getDataValue("description")).deviceId
		def cmdStatus = parent.setSmartDeviceCommand(deviceId, component, capability, command, arguments)
		def cmdData = [component, capability, command, arguments, cmdStatus]
		status << [cmdData: cmdData]
	} else {
		status << [FAILED: [rcEnabled: rcEnabled]]
	}
	return status
}

//	===== Device Commands =====
//	Common parent/child Oven commands are in library replica.samsungReplicaOvenCommon
def start() { setMachineState("run") }
def pause() { setMachineState("pause") }
def stop() { setMachineState("stop") }
def setMachineState(machState) {
	def oldState = device.currentValue("machineState")
	Map cmdStatus = [oldState: oldState, newState: machState]
	if (oldState != machState) {
		cmdStatus << sendRawCommand(getDataValue("componentId"), "washerOperatingState", 
									"setMachineState", [machState])
	} else {
		cmdStatus << [FAILED: "no change in state"]
		runIn(10, checkAttribute, [data: ["setMachineState", "machineState", machState]])
	}
	logInfo("setMachineState: ${cmdStatus}")
}

def checkAttribute(setCommand, attrName, attrValue) {
	def checkValue = device.currentValue(attrName).toString()
	if (checkValue != attrValue.toString()) {
		Map warnTxt = [command: setCommand,
					   attribute: attrName,
					   checkValue: checkValue,
					   attrValue: attrValue,
					   failed: "Function not accepted by the device."]
		logWarn("checkAttribute: ${warnTxt}")
	}
}

def parseEvent(event) {
	logDebug("parseEvent: <b>${event}</b>")
	if (state.deviceCapabilities.contains(event.capability)) {
		logTrace("parseEvent: <b>${event}</b>")
		if (event.value != null) {
			switch(event.attribute) {
				case "completionTime":
					setEvent(event)
					def timeRemaining = calcTimeRemaining(event.value)
					setEvent([attribute: "timeRemaining", value: timeRemaining, unit: null])
					break
				case "supportedMachineStates":
				case "supportedWasherWaterTemperature":
				case "supportedWasherSoilLevel":
				case "supportedWasherSpinLevel":
					break				
				default:
					setEvent(event)
					break
			}
		}
	}
}

def setState(event) {
	def attribute = event.attribute
	if (state."${attribute}" != event.value) {
		state."${event.attribute}" = event.value
		logInfo("setState: [event: ${event}]")
	}
}

def setEvent(event) {
	logTrace("<b>setEvent</b>: ${event}")
	sendEvent(name: event.attribute, value: event.value, unit: event.unit)
	if (device.currentValue(event.attribute).toString() != event.value.toString()) {
		logInfo("setEvent: [event: ${event}]")
	}
}

def calcTimeRemaining(completionTime) {
	Integer currTime = now()
	Integer compTime
	try {
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime()
	} catch (e) {
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime()
	}
	Integer timeRemaining = ((compTime-currTime) /1000).toInteger()
	if (timeRemaining < 0) { timeRemaining = 0 }
	return timeRemaining
}

//	===== Libraries =====



// ~~~~~ start include (1251) replica.samsungReplicaCommon ~~~~~
library ( // library marker replica.samsungReplicaCommon, line 1
	name: "samsungReplicaCommon", // library marker replica.samsungReplicaCommon, line 2
	namespace: "replica", // library marker replica.samsungReplicaCommon, line 3
	author: "Dave Gutheinz", // library marker replica.samsungReplicaCommon, line 4
	description: "Common Methods for replica Samsung Appliances", // library marker replica.samsungReplicaCommon, line 5
	category: "utilities", // library marker replica.samsungReplicaCommon, line 6
	documentationLink: "" // library marker replica.samsungReplicaCommon, line 7
) // library marker replica.samsungReplicaCommon, line 8
//	version 1.0 // library marker replica.samsungReplicaCommon, line 9

import org.json.JSONObject // library marker replica.samsungReplicaCommon, line 11
import groovy.json.JsonOutput // library marker replica.samsungReplicaCommon, line 12
import groovy.json.JsonSlurper // library marker replica.samsungReplicaCommon, line 13

def configure() { // library marker replica.samsungReplicaCommon, line 15
	Map logData = [:] // library marker replica.samsungReplicaCommon, line 16
    updateDataValue("triggers", groovy.json.JsonOutput.toJson(getReplicaTriggers())) // library marker replica.samsungReplicaCommon, line 17
    updateDataValue("commands", groovy.json.JsonOutput.toJson(getReplicaCommands())) // library marker replica.samsungReplicaCommon, line 18
	updateDataValue("rules", getReplicaRules()) // library marker replica.samsungReplicaCommon, line 19
//	setReplicaRules() // library marker replica.samsungReplicaCommon, line 20
	logData << [triggers: "initialized", commands: "initialized", rules: "initialized"] // library marker replica.samsungReplicaCommon, line 21
	logData << [replicaRules: "initialized"] // library marker replica.samsungReplicaCommon, line 22
	state.checkCapabilities = true // library marker replica.samsungReplicaCommon, line 23
	sendCommand("configure") // library marker replica.samsungReplicaCommon, line 24
	logData: [device: "configuring HubiThings"] // library marker replica.samsungReplicaCommon, line 25
//	refresh() // library marker replica.samsungReplicaCommon, line 26
	runIn(5, listAttributes,[data:true]) // library marker replica.samsungReplicaCommon, line 27
	logInfo("configure: ${logData}") // library marker replica.samsungReplicaCommon, line 28
} // library marker replica.samsungReplicaCommon, line 29

Map getReplicaCommands() { // library marker replica.samsungReplicaCommon, line 31
    return (["replicaEvent":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]],  // library marker replica.samsungReplicaCommon, line 32
			 "replicaStatus":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]],  // library marker replica.samsungReplicaCommon, line 33
			 "replicaHealth":[[name:"parent*",type:"OBJECT"],[name:"health*",type:"JSON_OBJECT"]], // library marker replica.samsungReplicaCommon, line 34
			 "setHealthStatusValue":[[name:"healthStatus*",type:"ENUM"]]]) // library marker replica.samsungReplicaCommon, line 35
} // library marker replica.samsungReplicaCommon, line 36

Map getReplicaTriggers() { // library marker replica.samsungReplicaCommon, line 38
	return [refresh:[], deviceRefresh: []] // library marker replica.samsungReplicaCommon, line 39
} // library marker replica.samsungReplicaCommon, line 40

String getReplicaRules() { // library marker replica.samsungReplicaCommon, line 42
	return """{"version":1,"components":[{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger","mute":true},{"trigger":{"name":"refresh","label":"command: refresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},{"trigger":{"name":"deviceRefresh","label":"command: deviceRefresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"}]}""" // library marker replica.samsungReplicaCommon, line 43
} // library marker replica.samsungReplicaCommon, line 44

//	===== Event Parse Interface s===== // library marker replica.samsungReplicaCommon, line 46
void replicaStatus(def parent=null, Map status=null) { // library marker replica.samsungReplicaCommon, line 47
	def logData = [parent: parent, status: status] // library marker replica.samsungReplicaCommon, line 48
	if (state.checkCapabilities) { // library marker replica.samsungReplicaCommon, line 49
		runIn(10, checkCapabilities, [data: status.components]) // library marker replica.samsungReplicaCommon, line 50
	} else if (state.refreshAttributes) { // library marker replica.samsungReplicaCommon, line 51
		refreshAttributes(status.components) // library marker replica.samsungReplicaCommon, line 52
	} // library marker replica.samsungReplicaCommon, line 53
	logDebug("replicaStatus: ${logData}") // library marker replica.samsungReplicaCommon, line 54
} // library marker replica.samsungReplicaCommon, line 55

def checkCapabilities(components) { // library marker replica.samsungReplicaCommon, line 57
	state.checkCapabilities = false // library marker replica.samsungReplicaCommon, line 58
	def componentId = getDataValue("componentId") // library marker replica.samsungReplicaCommon, line 59
	def disabledCapabilities = [] // library marker replica.samsungReplicaCommon, line 60
	try { // library marker replica.samsungReplicaCommon, line 61
		disabledCapabilities << components[componentId]["custom.disabledCapabilities"].disabledCapabilities.value // library marker replica.samsungReplicaCommon, line 62
	} catch (e) { } // library marker replica.samsungReplicaCommon, line 63

	def enabledCapabilities = [] // library marker replica.samsungReplicaCommon, line 65
	Map description // library marker replica.samsungReplicaCommon, line 66
	try { // library marker replica.samsungReplicaCommon, line 67
		description = new JsonSlurper().parseText(getDataValue("description")) // library marker replica.samsungReplicaCommon, line 68
	} catch (error) { // library marker replica.samsungReplicaCommon, line 69
		logWarn("checkCapabilities.  Data element Description not loaded. Run Configure") // library marker replica.samsungReplicaCommon, line 70
	} // library marker replica.samsungReplicaCommon, line 71
	def thisComponent = description.components.find { it.id == componentId } // library marker replica.samsungReplicaCommon, line 72
	thisComponent.capabilities.each { capability -> // library marker replica.samsungReplicaCommon, line 73
		if (designCapabilities().contains(capability.id) && // library marker replica.samsungReplicaCommon, line 74
			!disabledCapabilities.contains(capability.id)) { // library marker replica.samsungReplicaCommon, line 75
			enabledCapabilities << capability.id // library marker replica.samsungReplicaCommon, line 76
		} // library marker replica.samsungReplicaCommon, line 77
	} // library marker replica.samsungReplicaCommon, line 78
	state.deviceCapabilities = enabledCapabilities // library marker replica.samsungReplicaCommon, line 79
	runIn(1, configureChildren, [data: components]) // library marker replica.samsungReplicaCommon, line 80
	runIn(5, refreshAttributes, [data: components]) // library marker replica.samsungReplicaCommon, line 81
	logInfo("checkCapabilities: [design: ${designCapabilities()}, disabled: ${disabledCapabilities}, enabled: ${enabledCapabilities}]") // library marker replica.samsungReplicaCommon, line 82
} // library marker replica.samsungReplicaCommon, line 83

//	===== Child Configure / Install ===== // library marker replica.samsungReplicaCommon, line 85
def configureChildren(components) { // library marker replica.samsungReplicaCommon, line 86
	def logData = [:] // library marker replica.samsungReplicaCommon, line 87
	def componentId = getDataValue("componentId") // library marker replica.samsungReplicaCommon, line 88
	def disabledComponents = [] // library marker replica.samsungReplicaCommon, line 89
	try { // library marker replica.samsungReplicaCommon, line 90
		disabledComponents << components[componentId]["custom.disabledComponents"].disabledComponents.value // library marker replica.samsungReplicaCommon, line 91
	} catch (e) { } // library marker replica.samsungReplicaCommon, line 92
	designChildren().each { designChild -> // library marker replica.samsungReplicaCommon, line 93
		if (disabledComponents.contains(designChild.key)) { // library marker replica.samsungReplicaCommon, line 94
			logData << ["${designChild.key}": [status: "SmartThingsDisabled"]] // library marker replica.samsungReplicaCommon, line 95
		} else { // library marker replica.samsungReplicaCommon, line 96
			def dni = device.getDeviceNetworkId() // library marker replica.samsungReplicaCommon, line 97
			def childDni = "dni-${designChild.key}" // library marker replica.samsungReplicaCommon, line 98
			def child = getChildDevice(childDni) // library marker replica.samsungReplicaCommon, line 99
			def name = "${device.displayName} ${designChild.key}" // library marker replica.samsungReplicaCommon, line 100
			if (child == null) { // library marker replica.samsungReplicaCommon, line 101
				def type = "Replica ${appliance()} ${designChild.value}" // library marker replica.samsungReplicaCommon, line 102
				try { // library marker replica.samsungReplicaCommon, line 103
					addChildDevice("replicaChild", "${type}", "${childDni}", [ // library marker replica.samsungReplicaCommon, line 104
						name: type,  // library marker replica.samsungReplicaCommon, line 105
						label: name, // library marker replica.samsungReplicaCommon, line 106
						componentId: designChild.key // library marker replica.samsungReplicaCommon, line 107
					]) // library marker replica.samsungReplicaCommon, line 108
					logData << ["${name}": [status: "installed"]] // library marker replica.samsungReplicaCommon, line 109
				} catch (error) { // library marker replica.samsungReplicaCommon, line 110
					logData << ["${name}": [status: "FAILED", reason: error]] // library marker replica.samsungReplicaCommon, line 111
				} // library marker replica.samsungReplicaCommon, line 112
			} else { // library marker replica.samsungReplicaCommon, line 113
				child.checkCapabilities(components) // library marker replica.samsungReplicaCommon, line 114
				logData << ["${name}": [status: "already installed"]] // library marker replica.samsungReplicaCommon, line 115
			} // library marker replica.samsungReplicaCommon, line 116
		} // library marker replica.samsungReplicaCommon, line 117
	} // library marker replica.samsungReplicaCommon, line 118
	runIn(1, checkChildren, [data: components]) // library marker replica.samsungReplicaCommon, line 119
	runIn(3, refreshAttributes, [data: components]) // library marker replica.samsungReplicaCommon, line 120
	logInfo("configureChildren: ${logData}") // library marker replica.samsungReplicaCommon, line 121
} // library marker replica.samsungReplicaCommon, line 122

def checkChildren(components) { // library marker replica.samsungReplicaCommon, line 124
	getChildDevices().each { // library marker replica.samsungReplicaCommon, line 125
		it.checkCapabilities(components) // library marker replica.samsungReplicaCommon, line 126
	} // library marker replica.samsungReplicaCommon, line 127
} // library marker replica.samsungReplicaCommon, line 128

//	===== Attributes // library marker replica.samsungReplicaCommon, line 130
def refreshAttributes(components) { // library marker replica.samsungReplicaCommon, line 131
	state.refreshAttributes = false // library marker replica.samsungReplicaCommon, line 132
	def component = components."${getDataValue("componentId")}" // library marker replica.samsungReplicaCommon, line 133
	logDebug("refreshAttributes: ${component}") // library marker replica.samsungReplicaCommon, line 134
	component.each { capability -> // library marker replica.samsungReplicaCommon, line 135
		capability.value.each { attribute -> // library marker replica.samsungReplicaCommon, line 136
			parseEvent([capability: capability.key, // library marker replica.samsungReplicaCommon, line 137
						attribute: attribute.key, // library marker replica.samsungReplicaCommon, line 138
						value: attribute.value.value, // library marker replica.samsungReplicaCommon, line 139
						unit: attribute.value.unit]) // library marker replica.samsungReplicaCommon, line 140
			pauseExecution(50) // library marker replica.samsungReplicaCommon, line 141
		} // library marker replica.samsungReplicaCommon, line 142
	} // library marker replica.samsungReplicaCommon, line 143
	getChildDevices().each { // library marker replica.samsungReplicaCommon, line 144
		it.refreshAttributes(components) // library marker replica.samsungReplicaCommon, line 145
	} // library marker replica.samsungReplicaCommon, line 146
} // library marker replica.samsungReplicaCommon, line 147

void replicaHealth(def parent=null, Map health=null) { // library marker replica.samsungReplicaCommon, line 149
	if(parent) { logInfo("replicaHealth: ${parent?.getLabel()}") } // library marker replica.samsungReplicaCommon, line 150
	if(health) { logInfo("replicaHealth: ${health}") } // library marker replica.samsungReplicaCommon, line 151
} // library marker replica.samsungReplicaCommon, line 152

def setHealthStatusValue(value) {     // library marker replica.samsungReplicaCommon, line 154
    sendEvent(name: "healthStatus", value: value, descriptionText: "${device.displayName} healthStatus set to $value") // library marker replica.samsungReplicaCommon, line 155
} // library marker replica.samsungReplicaCommon, line 156

void replicaEvent(def parent=null, Map event=null) { // library marker replica.samsungReplicaCommon, line 158
	if (event && event.deviceEvent.componentId == getDataValue("componentId")) { // library marker replica.samsungReplicaCommon, line 159
		try { // library marker replica.samsungReplicaCommon, line 160
			parseEvent(event.deviceEvent) // library marker replica.samsungReplicaCommon, line 161
		} catch (err) { // library marker replica.samsungReplicaCommon, line 162
			logWarn("replicaEvent: [event = ${event}, error: ${err}") // library marker replica.samsungReplicaCommon, line 163
		} // library marker replica.samsungReplicaCommon, line 164
	} else { // library marker replica.samsungReplicaCommon, line 165
		getChildDevices().each {  // library marker replica.samsungReplicaCommon, line 166
			it.parentEvent(event) // library marker replica.samsungReplicaCommon, line 167
		} // library marker replica.samsungReplicaCommon, line 168
	} // library marker replica.samsungReplicaCommon, line 169
} // library marker replica.samsungReplicaCommon, line 170

def sendCommand(String name, def value=null, String unit=null, data=[:]) { // library marker replica.samsungReplicaCommon, line 172
    parent?.deviceTriggerHandler(device, [name:name, value:value, unit:unit, data:data, now:now]) // library marker replica.samsungReplicaCommon, line 173
} // library marker replica.samsungReplicaCommon, line 174

//	===== Refresh Commands ===== // library marker replica.samsungReplicaCommon, line 176
def refresh() { // library marker replica.samsungReplicaCommon, line 177
	logDebug("refresh") // library marker replica.samsungReplicaCommon, line 178
	state.refreshAttributes = true // library marker replica.samsungReplicaCommon, line 179
	sendCommand("deviceRefresh") // library marker replica.samsungReplicaCommon, line 180
	runIn(1, sendCommand, [data: ["refresh"]]) // library marker replica.samsungReplicaCommon, line 181
} // library marker replica.samsungReplicaCommon, line 182

def deviceRefresh() { // library marker replica.samsungReplicaCommon, line 184
	sendCommand("deviceRefresh") // library marker replica.samsungReplicaCommon, line 185
} // library marker replica.samsungReplicaCommon, line 186

// ~~~~~ end include (1251) replica.samsungReplicaCommon ~~~~~

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
