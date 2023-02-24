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
import org.json.JSONObject
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
def driverVer() { return "0.9TEST" }

metadata {
	definition (name: "Replica Samsung Oven",
				namespace: "replica",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/HubiThingsReplica%20Drivers/Replica_Samsung_Oven.groovy"
			   ){
		capability "Configuration"
		capability "Refresh"
		attribute "healthStatus", "enum", ["offline", "online"]
	//	ovenSetpoint
		command "setOvenSetpoint", [[name: "oven temperature", type: "NUMBER"]]
		attribute "ovenSetpoint", "number"
	//	temperatureMeasurement
		attribute "ovenTemperature", "number"	//	attr.temperature
	//	ovenMode and samsungce.ovenMode
		command "setOvenMode", [[name: "from state.supported OvenModes", type:"STRING"]]
		attribute "ovenMode", "string"
	//	ovenOperatingState & samsungce.ovenOperatingState
		command "stop"
		command "start", [[name: "mode", type: "STRING"],
						  [name: "time (hh:mm:ss OR secs)", type: "STRING"],
						  [name: "setpoint", type: "NUMBER"]]
		attribute "completionTime", "string"	//	time string
		attribute "progress", "number"			//	percent
		attribute "operatingState", "string"	//	attr.machineState
		attribute "ovenJobState", "string"
		attribute "operationTime", "string"
		command "pause"
		command "setOperationTime", [[name: "time (hh:mm:ss OR secs)", type: "STRING"]]

	//	samsungce.kidslock
		attribute "lockState", "string"
	//	samsungce.lamp
		command "setOvenLight", [[name: "from state.supported BrightnessLevels", type: "STRING"]]
		attribute "brightnessLevel", "string"
	//	remoteControlStatus
		attribute "remoteControlEnabled", "boolean"
	//	samsungce.doorState
		attribute "doorState", "string"
	//	custom.cooktopOperatingState
		attribute "cooktopOperatingState", "string"
		command "setProbeSetpoint", [[name: "probe alert temperature", type: "NUMBER"]]
		attribute "probeSetpoint", "number"
		attribute "probeStatus", "string"
		attribute "probeTemperature", "number"
	}
	preferences {
		input ("logEnable", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false)
		input ("infoLog", "bool", title: "Enable information logging",defaultValue: true)
		input ("traceLog", "bool", title: "Enable trace logging as directed by developer", defaultValue: false)
	}
}

//	===== Installation, setup and update =====
def installed() {
	updateDataValue("component", "main")
	initialize()
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

	runIn(5, configure)
	pauseExecution(2000)
	listAttributes(true)
	logInfo("updated: ${updStatus}")
	
	def children = getChildDevices()
	if (children == []) {
		installChildren()
	} else {
		children.each {
			it.updated()
		}
	}
}

def initialize() {
    updateDataValue("triggers", groovy.json.JsonOutput.toJson(getReplicaTriggers()))
    updateDataValue("commands", groovy.json.JsonOutput.toJson(getReplicaCommands()))
	logInfo("initialize: initialize device-specific data")
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

def configure() {
    initialize()
	setReplicaRules()
	state.checkCapabilities = true
	sendCommand("configure")
	logInfo("configure: configuring default rules")
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
		runIn(5, checkCapabilities, [data: status.components])
	} else if (state.refreshAttributes) {
		runIn(1, refreshAttributes, [data: status.components])
	}
	logDebug("replicaStatus: ${logData}")
}

def checkCapabilities(status) {
	state.checkCapabilities = false
	def disabledCapabilities = designDisabled()
	try {
		disabledCapabilities << status.main["custom.disabledCapabilities"].disabledCapabilities.value
	} catch (e) { }
	
	state.deviceCapabilities = []
	def enabledCapabilities = []
	Map description = new JsonSlurper().parseText(getDataValue("description"))
	def component = description.components.find { it.id == "main" }
	component.capabilities.each { cap ->
		if (!disabledCapabilities.contains(cap.id)) {
			enabledCapabilities << cap.id
		}
	}
	state.deviceCapabilities = enabledCapabilities

	refreshAttributes(status)
	logInfo("checkCapabilities: [disabled: ${disabledCapabilities}, enabled: ${enabledCapabilities}]")

	getChildDevices().each {
		it.checkCapabilities(status, description)
	}
}

def designDisabled() {
	return [               
		"ocf", "execute", "custom.disabledCapabilities", "samsungce.driverVersion",
		"samsungce.kitchenDeviceIdentification", "samsungce.kitchenDeviceDefaults",
		"samsungce.customRecipe", "samsungce.kitchenModeSpecification"
	]
}

def refreshAttributes(status) {
	state.refreshAttributes = false
	def compStatus = status.main
	logDebug("refreshAttributes: ${compStatus}")
	compStatus.each { capability ->
		capability.value.each { attribute ->
			parseEvent([capability: capability.key,
						attribute: attribute.key,
						value: attribute.value.value,
						unit: attribute.value.unit])
			pauseExecution(50)
		}
	}
	runIn(5, sendRefreshToChildren, [data: status])
}

def sendRefreshToChildren(status) {
	getChildDevices().each {
		it.refreshAttributes(status)
	}
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
				case "machineState":
					parseCorrect([capability: event.capability,
								  attribute: "operatingState",
								  value: event.value,
								  unit: event.unit])
					break
				case "operationTime":
					if (event.capability == "ovenOperatingState") {
						def opTime = convertIntToHhMmSs(event.value.toInteger())
						parseCorrect([capability: event.capability,
									  attribute: event.attribute,
									  value: opTime,
									  unit: event.unit])
					}
					break
				case "temperature":
					def attr = "ovenTemperature"
					if (event.capability == "samsungce.meatProbe") {
						attr = "probeTemperature"
					}
					parseCorrect([capability: event.capability,
								  attribute: attr,
								  value: event.value,
								  unit: event.unit])
					break
				case "temperatureSetpoint":
					parseCorrect([capability: event.capability,
								  attribute: "probeSetpoint",
								  value: event.value,
								  unit: event.unit])
					break
				case "status":
					parseCorrect([capability: event.capability,
								  attribute: "probeStatus",
								  value: event.value,
								  unit: event.unit])
					break
				case "supportedOvenModes":
				case "supportedBrightnessLevel":
				case "supportedCooktopOperatingState":
				case "supportedMachineStates":
					setState(event)
					break
				default:
					if (device.currentValue(event.attribute).toString() != event.value.toString()) {
						sendEvent(name: event.attribute, value: event.value, unit: event.unit)
						logInfo("parseEvent: [event: ${event}]")
					}
			}
		}
	}
}

def parseCorrect(event) {
	logTrace("parseCorrect: <b>${event}</b>")
	if (device.currentValue(event.attribute).toString() != event.value.toString()) {
		sendEvent(name: event.attribute, value: event.value, unit: event.unit)
		logInfo("parseCorrect: [event: ${event}]")
	}
}

def setState(event) {
	def attribute = event.attribute
	if (state."${attribute}" != event.value) {
		if (attribute == "supportedOvenModes" &&
			!state.deviceCapabilities.contains("samsungce.ovenMode")) {
			logWarn("setState: [attribute: ${attribute}, status: FAILED, reason: not samsungce device]")
			return
		}
		state."${event.attribute}" = event.value
		logInfo("setState: [event: ${event}]")
	}
}

//	Used for any rule-based commands
private def sendCommand(String name, def value=null, String unit=null, data=[:]) {
    parent?.deviceTriggerHandler(device, [name:name, value:value, unit:unit, data:data, now:now])
}

def sendRawCommand(component, capability, command, arguments = []) {
	if (device.currentValue("remoteControlEnabled")) {
		def deviceId = new JSONObject(getDataValue("description")).deviceId
		parent.setSmartDeviceCommand(deviceId, component, capability, command, arguments)
		logDebug("sendRawCommand: [${deviceId}, ${component}, ${capability}, ${command}, ${arguments}]")
	} else {
		logWarn("sendRawCommand: Command Aborted.  The Remote Control must be enabled at the stove prior to entering control commands.")
	}
}

//	===== Refresh Commands =====
def refresh() {
	//	refreshes st data then gets st data
	//	will update all events in status mssg.
	logDebug("refresh")
	state.refreshAttributes = true
	sendCommand("deviceRefresh")
	pauseExecution(500)
	sendCommand("refresh")
}

def deviceRefresh() {
	//	update st data = force st to send new single event.
	sendCommand("deviceRefresh")
}

//	===== Device Commands =====
//	===== Oven Mode (Bake, Broil, etc) =====
def setOvenSetpoint(temperature) {
	temperature = temperature.toInteger()
	if (temperature > 0) {
		sendRawCommand("main", "ovenSetpoint", "setOvenSetpoint", [temperature])
		logInfo("setOvenSetpoint: [temperature: ${temperature}]")
		return "OK"
	} else {
		logWarn("setProbeSetpoint: Not set.  Temperature ${temperature} <= 0")
		return "FAILED"
	}
}

def setOvenMode(mode) {
	mode = state.supportedOvenModes.find { it.toLowerCase() == mode.toLowerCase() }
	if (mode == null) {
		def msg = "Mode ${mode} not supported.\n\r\t\t"
		msg += "Mode must be in state.supportedOvenModes.\n\r\t\t"
		msg += "If you have the divider in the oven, the mode list is different from without the devider."
		logWarn("setOvenMode: ${msg}")
		return "FAILED"
	} else {
		def capability = "samsungce.ovenMode"
		if (!state.deviceCapabilities.contains(capability)) {
			capability = "ovenMode"
		}
		sendRawCommand("main", capability, "setOvenMode", [mode])
		logInfo("setOvenMode: [mode: ${mode}]")
		return "OK"
	}
}

//	===== Oven State (on, off, pause, time, etc) =====
def pause() {
	if (state.deviceCapabilities.contains("samsungce.ovenOperatingState")) {
		sendRawCommand("main", "samsungce.ovenOperatingState", "pause")
		logInfo("pause")
	} else {
		logWarn("pause: Not supported on your device")
	}
}

def stop() {
	def capability = "samsungce.ovenOperatingState"
	if (!state.deviceCapabilities.contains(capability)) {
		capability = "ovenOperatingState"
	}
	sendRawCommand("main", capability, "stop")
	logInfo("stop")
}

def start(mode = null, time = null, setpoint = null) {
	def logData = [:]
	def abort = false
	if (mode == null || time == null || setpoint == null) {
		sendRawCommand("main", "ovenOperatingState", "start")
		logData << [command: "startOnly"]
	} else if (state.deviceCapabilities.contains("ovenOperatingState")) {
		logData << [capability: "ovenOperatingState"]
		mode = state.supportedOvenModes.find { it.toLowerCase() == mode.toLowerCase() }
		if (mode == null) {
			mode = "failedLookup"
			abort = true
		}
		try {
			if (time != null && time.toString().contains(":")) {
				time = convertHhMmSsToInt(time)
			}
		} catch (e) {
			time = "failedConvert"
			abort = true
		}
		if (setpoint <=0) {
			setpoint = [setpoint: "notPositiveInteger"]
			abort = true
		}
		logData 
		if (!abort) {
			sendRawCommand("main", "ovenOperatingState", "start", [mode: mode, time: time, setpoint: setpoint])
			logData << [command: "startWithParameters"]
		}
	} else {
		logData << [capability: "samsungce.ovenOperatingState"]
		def status = setOvenMode()
		logData << [modeLookup: status]
		if (status == "FAILED") { abort = true }
		pauseExecution(200)
		status = setOperationTime(time)
		logData << [setOperationTime: status]
		if (status == "FAILED") { abort = true }
		pauseExecution(200)
		status = setOvenSetpoint(setpoint)
		logData << [setOvenSetpoint: status]
		if (status == "FAILED") { abort = true }
		if (!abort) {
			sendRawCommand("main", "samsungce.ovenOperatingState", "start")
			logData << [command: "individualCommands"]
		}
	}
	logData << [mode: mode, time: time, setpoint: setpoint]
	if (!abort) {
		logInfo("start: [aborted: ${abort}, data: ${logData}")
	} else {
		logWarn("start: [aborted: ${abort}, data: ${logData}")
		logWarn("start: start must have no parameters parameters must be populated for all parameters.")
	}
}

def setOperationTime(opTime) {
	if (state.deviceCapabilities.contains("samsungce.ovenOperatingState")) {
		if (!opTime.toString().contains(":")) {
			try {
				opTime = convertIntToHhMmSs(opTime.toInteger())
			} catch (e) {
				def msg = "Time conversion failed.  Time must be of the format "
				msg += "<b>hh:mm:ss<\b> or integer seconds."
				logWarn("setOperationTime: ${msg}")
				return "FAILED"
			}
		}
		sendRawCommand("main", "samsungce.ovenOperatingState", "setOperationTime", [opTime])
		logInfo("setOperationTime: ${opTime}")
		return "OK"
	} else {
		logWarn("setOperationTime: Device does not support setOperationTime")
		return "FAILED"
	}
}

//	===== samsungce.meatProbe =====
def setProbeSetpoint(temperature) {
	if (device.currentValue("probeStatus") != "disconnected") {
		temperature = temperature.toInteger()
		if (temperature > 0) {
			sendRawCommand("main", "samsungce.meatProbe", "setTemperatureSetpoint", [temperature])
			logInfo("setProbeSetpoint: ${temperature}")
		} else {
			logWarn("setProbeSetpoint: Not set.  Temperature ${temperature} < 0")
		}
	} else {
		logWarn("setProbeSetpoint: Not set.  Probe is disconnected")
	}
}

//	===== samsungce.lamp =====
def setOvenLight(lightLevel) {
	 lightLevel = state.supportedBrightnessLevel.find { it.toLowerCase() == lightLevel.toLowerCase() }
	if (lightLevel == null) {
		logWarn("setOvenLight:  Level ${lightLevel} not supported")
	} else {
		sendRawCommand("main", "samsungce.lamp", "setBrightnessLevel", [lightLevel])
		logInfo("setOvenLight: ${lightLevel}")
	}
}

//	===== Utility Methods =====
def convertIntToHhMmSs(seconds) {
	def hours = (seconds/3600).toInteger()
	seconds = seconds - hours*3600
	if (hours < 10) { hours = "0${hours}" }
	def minutes = (seconds/60).toInteger()
	seconds = seconds - minutes*60
	if (minutes < 10) { minutes = "0${minutes}" }
	if (seconds < 10) { seconds = "0${seconds}" }
	return "${hours}:${minutes}:${seconds}"
}
		
def convertHhMmSsToInt(time) {
	def timeArray = time.split(":")
	def seconds = timeArray[0].toInteger() * 3600 +
		timeArray[1].toInteger() * 60 + timeArray[2].toInteger()
	return seconds
}

//	===== Common Oven Parent/Child API Commands =====
//	===== Child Installation =====
def installChildren() {
	//	This will be expanded for other device installations to include all.
	//	format is addChild(driverId, name, componentId)
	addChild("cavity", "${device.displayName} cavity", "cavity-01")
}

def addChild(driverId, name, componentId) {
	def dni = device.getDeviceNetworkId()
	def childDni = dni + "-${componentId}"
	def isChild = getChildDevice(childDni)
	if (isChild) {
		logWarn("installChildren: [label: ${name}, type: ${type}, component: ${componentId}, status: already installed]")
	} else {
		def type = "RepChild Samsung Oven ${driverId}"
		try {
			addChildDevice("replicaChild", "${type}", "${childDni}", [
				name: type, label: name, component: componentId])
			logInfo("addChild: [status: ADDED, label: ${name}, type: ${type}, component: ${componentId}]")
		} catch (error) {
			logWarn("addChild: [status: FAILED, label: ${name}, type: ${type}, component: ${componentId}, error: ${error}]")
		}
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
