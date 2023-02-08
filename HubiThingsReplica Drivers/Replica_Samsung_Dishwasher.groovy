/*	HubiThings Replica Samsung Disawasher
		HubiThings Replica Applications Copyright 2023 by Bloodtick
		Replica Color Bulb Copyright 2023 by Dave Gutheinz

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
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
def driverVer() { return "1.0" }

metadata {
	definition (name: "Replica Samsung Dishwasher",
				namespace: "replica",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Washer.groovy"
			   ){
		capability "Configuration"
		//	refresh, switch, kidsLock, remoteControlStatus
		capability "Refresh"
		capability "Switch"
		attribute "lockState", "string"
		attribute "remoteControlEnabled", "bool"

		//	Washing Course (samsungce.)
		command "setWashingCourse", ["string"]
		command "toggleWashingCourse"
		command "startWashingCourse", ["string"]
		attribute "washingCourse", "string"
		//	Operation Control
			//	dishwasherOperatingState: pause, run (start/resume), stop (cancel)
			//	custom_dishwasherDelayStartTime: setDishwasherDelayStartTime  (startLater)
			//	samsungce.dishwasherOperation: resume, cancel, start, pause, startLater
			//	If samsungce version is enabled, use it.  Create state.samsungce =  t/f to
				//	control commands and attribute handling.
		command "resume"
		command "start", ["string"]
		command "pause"
		command "cancel", ["bool"]
		command "startLater", ["number"]
		attribute "operatingState", "string"	//	samsungce: operatingState, std: machineState
		//	samsungce true attributes
		attribute "remainingTime", "string"
		attribute "operationTime", "string"
		attribute "timeLeftToStart", "string"
		//	standard attributes
		attribute "completionTime", "string"
		//	Job Status
			//	custom.dishwasherOperatingProgress
			//	samsungce.dishwasherJobState
		attribute "jobState", "string"
	}
	preferences {
		input ("logEnable", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false)
		input ("infoLog", "bool", title: "Enable information logging",defaultValue: true)
		input ("traceLog", "bool", title: "Enable trace logging as directed by developer", defaultValue: false)
	}
}

def installed() {
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
}

def initialize() {
    updateDataValue("triggers", groovy.json.JsonOutput.toJson(getReplicaTriggers()))
    updateDataValue("commands", groovy.json.JsonOutput.toJson(getReplicaCommands()))
	logInfo("initialize: initialize device-specific data")
}

Map getReplicaCommands() {
    return (["replicaEvent":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]], 
			 "replicaStatus":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]], 
//			 "replicaHealth":[[name:"parent*",type:"OBJECT"],[name:"health*",type:"JSON_OBJECT"]],
			 "setHealthStatusValue":[[name:"healthStatus*",type:"ENUM"]]])
}

Map getReplicaTriggers() {
	def replicaTriggers = [
		off:[], on:[], refresh:[], deviceRefresh:[],
		//	samsungce = true
		setWashingCourse: [[name:"course*", type: "ENUM"]],
		startWashingCourse: [[name:"course*", type: "ENUM"]],
		start:[[name: "option", type: "string"]], 
		pause:[], resume:[], 
		cancel:[[name:"drain", type:"bool"]],
		startLater: [[name:"delay*", type:"number"]],
		//	samsungce = false
		setMachineState:[[name:"state*", type:"string"]],
		setDishwasherDelayStartTime:[[name:"dishwasherDelayStartTime*", type:"string"]]
		]
	return replicaTriggers
}

def configure() {
    initialize()
	setReplicaRules()
	state.checkCapabilities = true
	sendCommand("configure")
	logInfo("configure: configuring default rules")
}

String setReplicaRules() {
	def rules = """{"version":1,"components":[{"trigger":{"name":"off","label":"command: off()","type":"command"},"command":{"name":"off","type":"command","capability":"switch","label":"command: off()"},"type":"hubitatTrigger"},{"trigger":{"name":"on","label":"command: on()","type":"command"},"command":{"name":"on","type":"command","capability":"switch","label":"command: on()"},"type":"hubitatTrigger"},{"trigger":{"name":"refresh","label":"command: refresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger"},{"trigger":{"name":"pause","label":"command: pause()","type":"command"},"command":{"name":"pause","type":"command","capability":"samsungce.dishwasherOperation","label":"command: pause()"},"type":"hubitatTrigger"},{"trigger":{"name":"resume","label":"command: resume()","type":"command"},"command":{"name":"resume","type":"command","capability":"samsungce.dishwasherOperation","label":"command: resume()"},"type":"hubitatTrigger"},{"trigger":{"name":"start","label":"command: start(option)","type":"command","parameters":[{"name":"option","type":"string"}]},"command":{"name":"start","arguments":[{"name":"option","optional":true,"schema":{"type":"object"}}],"type":"command","capability":"samsungce.dishwasherOperation","label":"command: start(option)"},"type":"hubitatTrigger"},{"trigger":{"name":"startLater","label":"command: startLater(delay*)","type":"command","parameters":[{"name":"delay*","type":"number"}]},"command":{"name":"startLater","arguments":[{"name":"delay","optional":false,"schema":{"type":"number"}}],"type":"command","capability":"samsungce.dishwasherOperation","label":"command: startLater(delay*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setWashingCourse","label":"command: setWashingCourse(course*)","type":"command","parameters":[{"name":"course*","type":"ENUM"}]},"command":{"name":"setWashingCourse","arguments":[{"name":"course","optional":false,"schema":{"type":"string","enum":["auto","eco","intensive","delicate","express","preWash","selfClean","extraSilence","rinseOnly","plastics","potsAndPans","babycare","normal","selfSanitize","dryOnly","upperExpress","night","babyBottle","coldRinse","glasses","quick","heavy","daily","chef","preBlast","steamSoak","rinseDry","machineCare","AI","nightSilence","express_0C","daily_09","eco_08","eco_10"]}}],"type":"command","capability":"samsungce.dishwasherWashingCourse","label":"command: setWashingCourse(course*)"},"type":"hubitatTrigger"},{"trigger":{"name":"startWashingCourse","label":"command: startWashingCourse(course*)","type":"command","parameters":[{"name":"course*","type":"ENUM"}]},"command":{"name":"startWashingCourse","arguments":[{"name":"course","optional":false,"schema":{"type":"string","enum":["auto","eco","intensive","delicate","express","preWash","selfClean","extraSilence","rinseOnly","plastics","potsAndPans","babycare","normal","selfSanitize","dryOnly","upperExpress","night","babyBottle","coldRinse","glasses","quick","heavy","daily","chef","preBlast","steamSoak","rinseDry","machineCare","AI","nightSilence","express_0C","daily_09","eco_08","eco_10"]}}],"type":"command","capability":"samsungce.dishwasherWashingCourse","label":"command: startWashingCourse(course*)"},"type":"hubitatTrigger"},{"trigger":{"name":"deviceRefresh","label":"command: deviceRefresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},{"trigger":{"name":"cancel","label":"command: cancel(drain)","type":"command","parameters":[{"name":"drain","type":"bool"}]},"command":{"name":"cancel","arguments":[{"name":"drain","optional":true,"schema":{"type":"boolean"}}],"type":"command","capability":"samsungce.dishwasherOperation","label":"command: cancel(drain)"},"type":"hubitatTrigger"},{"trigger":{"name":"setMachineState","label":"command: setMachineState(state*)","type":"command","parameters":[{"name":"state*","type":"string"}]},"command":{"name":"setMachineState","arguments":[{"name":"state","optional":false,"schema":{"title":"MachineState","type":"string","enum":["pause","run","stop"]}}],"type":"command","capability":"dishwasherOperatingState","label":"command: setMachineState(state*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setDishwasherDelayStartTime","label":"command: setDishwasherDelayStartTime(dishwasherDelayStartTime*)","type":"command","parameters":[{"name":"dishwasherDelayStartTime*","type":"string"}]},"command":{"name":"setDishwasherDelayStartTime","arguments":[{"name":"dishwasherDelayStartTime","optional":false,"schema":{"type":"string"}}],"type":"command","capability":"custom.dishwasherDelayStartTime","label":"command: setDishwasherDelayStartTime(dishwasherDelayStartTime*)"},"type":"hubitatTrigger"}]}"""
	updateDataValue("rules", rules)
}

//	===== Event Parse Interface s=====
void replicaStatus(def parent=null, Map status=null) {
	def logData = [parent: parent, status: status]
	if (state.checkCapabilities) {
		runIn(10, checkCapabilities, [data: status.components.main])
	} else if (state.refreshAttributes) {
		refreshAttributes(status.components.main)
	}
	logTrace("replicaStatus: ${logData}")
}

def checkCapabilities(status) {
	def disabledCapabilities = ["n/a"]
	try {
		disabledCapabilities = status["custom.disabledCapabilities"].disabledCapabilities.value
	} catch (e) {}
	def enabledCapabilities = []
	def description = new JsonSlurper().parseText(getDataValue("description"))
	description.components.each {
		it.capabilities.each { cap ->
			if (!disabledCapabilities.contains(cap.id)) {
				enabledCapabilities << cap.id
			}
		}
	}
	state.deviceCapabilities = enabledCapabilities
	state.checkCapabilities = false
	logInfo("checkCapabilities: [disabled: ${disabledCapabilities}, enabled: ${enabledCapabilities}]")
	runIn(2, refreshAttributes, [data: status])
}

def refreshAttributes(mainData) {
	logDebug("refreshAttributes: ${mainData}")
	if (state.deviceCapabilities.contains("samsungce.dishwasherWashingCourse")) {
		parse_main([attribute: "supportedCourses", value: mainData["samsungce.dishwasherWashingCourse"].supportedCourses.value])
		pauseExecution(100)
		parse_main([attribute: "washingCourse", value: mainData["samsungce.dishwasherWashingCourse"].washingCourse.value])
		pauseExecution(100)
	}
	
	if (state.deviceCapabilities.contains("switch")) {
		parse_main([attribute: "switch", value: mainData.switch.switch.value])
		pauseExecution(100)
	}
	
	if (state.deviceCapabilities.contains("remoteControlStatus")) {
		parse_main([attribute: "remoteControlEnabled", value: mainData.remoteControlStatus.remoteControlEnabled.value])
		pauseExecution(100)
	}
	
	if (state.deviceCapabilities.contains("dishwasherOperatingState")) {
		parse_main([attribute: "machineState", value: mainData.dishwasherOperatingState.machineState.value])
		pauseExecution(100)
		parse_main([attribute: "completionTime", value: mainData.dishwasherOperatingState.completionTime.value])
		pauseExecution(100)
	}
	
	if (state.deviceCapabilities.contains("samsungce.dishwasherOperation")) {
		parse_main([attribute: "operatingState", value: mainData["samsungce.dishwasherOperation"].operatingState.value])
		pauseExecution(100)
		parse_main([attribute: "operationTime", value: mainData["samsungce.dishwasherOperation"].operationTime.value, unit: "min"])
		pauseExecution(100)
		parse_main([attribute: "remainingTime", value: mainData["samsungce.dishwasherOperation"].remainingTime.value, unit: "min"])
		pauseExecution(100)
		parse_main([attribute: "timeLeftToStart", value: mainData["samsungce.dishwasherOperation"].timeLeftToStart.value, unit: "min"])
		pauseExecution(100)
	}
	
	if (state.deviceCapabilities.contains("samsungce.dishwasherJobState")) {
		parse_main([attribute: "dishwasherJobState", value: mainData["samsungce.dishwasherJobState"].dishwasherJobState.value])
		pauseExecution(100)
	}
	
	if (state.deviceCapabilities.contains("custom.dishwasherOperatingProgress")) {
		parse_main([attribute: "dishwasherOperatingProgress", value: mainData["custom.dishwasherOperatingProgress"].dishwasherOperatingProgress.value])
	}
	state.refreshAttributes	= false
}

void replicaHealth(def parent=null, Map health=null) {
	if(parent) { logInfo("replicaHealth: ${parent?.getLabel()}") }
	if(health) { logInfo("replicaHealth: ${health}") }
}

void replicaEvent(def parent=null, Map event=null) {
	logTrace("replicaEvent: [parent: ${parent}, event: ${event}]")
	def eventData = event.deviceEvent
	try {
		"parse_${event.deviceEvent.componentId}"(event.deviceEvent)
	} catch (err) {
		logWarn("replicaEvent: [event = ${event}, error: ${err}")
	}
}

def parse_main(event) {
	logInfo("parse_main: <b>[attribute: ${event.attribute}, value: ${event.value}, unit: ${event.unit}]</b>")
	switch(event.attribute) {
		case "operatingState":
		case "machineState":
			sendEvent(name: "operatingState", value: event.value)
			break
		case "dishwasherJobState":
		case "dishwasherOperatingProgress":
			sendEvent(name: "jobState", value: event.value)
			break
		case "supportedCourses":
			state.supportedDishwasherCourses = event.value
			break
		default:
//			sendEvent(name: event.attribute, value: event.value, unit: event.unit)
			sendEvent(name: event.attribute, value: event.value)
		break
	}
}

//	===== HubiThings Send Command and Device Health =====
def setHealthStatusValue(value) {    
    sendEvent(name: "healthStatus", value: value)
}

def sendCommand(String name, def value=null, String unit=null, data=[:]) {
    parent?.deviceTriggerHandler(device, [name:name, value:value, unit:unit, data:data, now:now])
}

def refresh() {
	state.refreshAttributes = true
	sendCommand("deviceRefresh")
	pauseExecution(500)
	sendCommand("refresh")
}

def deviceRefresh() {
	sendCommand("deviceRefresh")
}

//	===== Samsung Dishwasher Commands =====
def on() {
	if (state.deviceCapabilities.contains("switch")){
		sendCommand("on")
		logDebug("on")
	} else {
		logWarn("on: NOT SUPPORTED BY YOUR DEVICE")
	}
}

def off() {
	if (state.deviceCapabilities.contains("switch")){
		sendCommand("off")
		logDebug("off")
	} else {
		logWarn("off: NOT SUPPORTED BY YOUR DEVICE")
	}
}

def toggleWashingCourse() {
	if (state.supportedDishwasherCourses) {
		def courses = state.supportedDishwasherCourses
		def totalCourses = courses.size()
		def currentCourse = device.currentValue("washingCourse")
		def courseIndex = courses.indexOf(currentMode)
		def newCourseIndex = courseIndex + 1
		if (newCourseIndex == totalCourses) { newCourseIndex = 0 }
		setWashingCourse(courses[newCourseIndex])
		runIn(3, deviceRefresh)
	} else { logWarn("toggleWasherCourse: NOT SUPPORTED") }
}

def setWashingCourse(washingCourse) {
	if (state.deviceCapabilities.contains("samsungce.dishwasherWashingCourse")) {
		sendCommand("setWashingCourse", washingCourse)
		logDebug("setWashingCourse: ${washingCourse}")
	} else {
		logWarn("setWashingCourse: NOT SUPPORTED BY YOUR DEVICE")
	}
}

def startWashingCourse(washingCourse = device.currentValue("washingCourse")) {
	if (state.deviceCapabilities.contains("samsungce.dishwasherWashingCourse")) {
		sendCommand("startWashingCourse", washingCourse)
		logDebug("startWashingCourse: ${washingCourse}")
	} else {
		logWarn("startWashingCourse: NOT SUPPORTED BY YOUR DEVICE")
	}
}

def resume() {
	if (state.deviceCapabilities.contains("samsungce.dishwasherOperation")) {
		sendCommand("resume")
		logInfo("resume: samsungce.dishwasherOperation")
	} else {
		sendCommand("setMachineState", "run")
		logInfo("resume: dishwasherOperatingState")
	}
}

def start(option = null) {
	if (state.deviceCapabilities.contains("samsungce.dishwasherOperation")) {
		if (option == null) {
			sendCommand("start")
		} else {
			sendCommand("start", option)
		}
		logInfo("start: [samsungce.dishwasherOperation, option: ${option}]")
	} else {
		sendCommand("setMachineState", "run")
		logInfo("start: dishwasherOperatingState")
	}
}

def pause() {
	if (state.deviceCapabilities.contains("samsungce.dishwasherOperation")) {
		sendCommand("pause")
		logInfo("pause: samsungce.dishwasherOperation")
	} else {
		sendCommand("setMachineState", "pause")
		logInfo("pause: dishwasherOperatingState")
	}
}

def cancel(drain = null) {
	if (state.deviceCapabilities.contains("samsungce.dishwasherOperation")) {
		if (drain == null) {
			sendCommand("cancel")
		} else {
			sendCommand("cancel", drain)
		}
		logInfo("cancel: [samsungce.dishwasherOperation, drain: ${drain}]")
	} else {
		sendCommand("setMachineState", "stop")
		logInfo("cancel: dishwasherOperatingState")
	}
}

def startLater(delay) {
	if (state.deviceCapabilities.contains("samsungce.dishwasherOperation")) {
		sendCommand("startLater", delay)
		logInfo("startLater: [samsungce.dishwasherOperation, delay: ${delay}]")
	} else if (state.deviceCapabilities.contains("custom_dishwasherDelayStartTime")) {
		sendCommand("setDishwasherDelayStartTime", delay)
		logInfo("startLater: [dishwasherOperatingState, startTime: ${delay}]")
	} else {
		logWarn("startLater: not supported by device")
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
