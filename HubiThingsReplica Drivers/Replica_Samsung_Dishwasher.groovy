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
def driverVer() { return "0.5T" }

metadata {
	definition (name: "Replica Samsung Dishwasher",
				namespace: "replica",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Washer.groovy"
			   ){
		capability "Configuration"
		capability "Refresh"
		capability "Switch"
		//	samsungce.kidslock
		attribute "lockState", "string"
		//	samsungce.dishwasherWashingCourxe
		command "setWashingCourse", ["string"]
		command "toggleWashingCourse"
		command "startWashingCourse", ["string"]
		attribute "washingCourse", "string"
		//	samsungce.dishwasherOperation
		command "resume"
		command "start"
		command "pause"
		command "startLater", ["number"]
		attribute "operatingState", "string"
		attribute "operationTime", "string"
		attribute "remainingTime", "string"
		attribute "timeLeftToStart", "string"
		//	samsungce.dishwasherJobState
		attribute "dishwasherJobState", "string"
	}
	preferences {
		input ("logEnable", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false)
		input ("infoLog", "bool", title: "Enable information logging",defaultValue: true)
		input ("traceLog", "bool", title: "Enable trace logging as directed by developer", defaultValue: false)
	}
}

def installed() {
	runIn(1, updated)
}

def updated() {
	unschedule()
	def updStatus = [:]
	initialize()
	if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
		updStatus << [driverVer: driverVer()]
	}
	if (logEnable) { runIn(1800, debugLogOff) }
	if (traceLog) { runIn(600, traceLogOff) }
	updStatus << [logEnable: logEnable, infoLog: infoLog, traceLog: traceLog]

	runIn(10, refresh)
	pauseExecution(5000)
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
		off:[], on:[], refresh:[], 
		setWashingCourse: [[name:"course*", type: "ENUM"]],
		startWashingCourse: [[name:"course*", type: "ENUM"]],
		start:[[name: "option", type: "string"]], 
		pause:[], resume:[],
		startLater: [[name:"delay*", type:"number"]],
		deviceRefresh:[]]
	return replicaTriggers
}

def configure() {
    initialize()
	setReplicaRules()
	sendCommand("configure")
	logInfo("configure: configuring default rules")
}

String setReplicaRules() {
	def rules = """{"version":1,"components":[{"trigger":{"name":"off","label":"command: off()","type":"command"},"command":{"name":"off","type":"command","capability":"switch","label":"command: off()"},"type":"hubitatTrigger"},{"trigger":{"name":"on","label":"command: on()","type":"command"},"command":{"name":"on","type":"command","capability":"switch","label":"command: on()"},"type":"hubitatTrigger"},{"trigger":{"name":"refresh","label":"command: refresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger"},{"trigger":{"name":"pause","label":"command: pause()","type":"command"},"command":{"name":"pause","type":"command","capability":"samsungce.dishwasherOperation","label":"command: pause()"},"type":"hubitatTrigger"},{"trigger":{"name":"resume","label":"command: resume()","type":"command"},"command":{"name":"resume","type":"command","capability":"samsungce.dishwasherOperation","label":"command: resume()"},"type":"hubitatTrigger"},{"trigger":{"name":"start","label":"command: start(option)","type":"command","parameters":[{"name":"option","type":"string"}]},"command":{"name":"start","arguments":[{"name":"option","optional":true,"schema":{"type":"object"}}],"type":"command","capability":"samsungce.dishwasherOperation","label":"command: start(option)"},"type":"hubitatTrigger"},{"trigger":{"name":"startLater","label":"command: startLater(delay*)","type":"command","parameters":[{"name":"delay*","type":"number"}]},"command":{"name":"startLater","arguments":[{"name":"delay","optional":false,"schema":{"type":"number"}}],"type":"command","capability":"samsungce.dishwasherOperation","label":"command: startLater(delay*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setWashingCourse","label":"command: setWashingCourse(course*)","type":"command","parameters":[{"name":"course*","type":"ENUM"}]},"command":{"name":"setWashingCourse","arguments":[{"name":"course","optional":false,"schema":{"type":"string","enum":["auto","eco","intensive","delicate","express","preWash","selfClean","extraSilence","rinseOnly","plastics","potsAndPans","babycare","normal","selfSanitize","dryOnly","upperExpress","night","babyBottle","coldRinse","glasses","quick","heavy","daily","chef","preBlast","steamSoak","rinseDry","machineCare","AI","nightSilence","express_0C","daily_09","eco_08","eco_10"]}}],"type":"command","capability":"samsungce.dishwasherWashingCourse","label":"command: setWashingCourse(course*)"},"type":"hubitatTrigger"},{"trigger":{"name":"startWashingCourse","label":"command: startWashingCourse(course*)","type":"command","parameters":[{"name":"course*","type":"ENUM"}]},"command":{"name":"startWashingCourse","arguments":[{"name":"course","optional":false,"schema":{"type":"string","enum":["auto","eco","intensive","delicate","express","preWash","selfClean","extraSilence","rinseOnly","plastics","potsAndPans","babycare","normal","selfSanitize","dryOnly","upperExpress","night","babyBottle","coldRinse","glasses","quick","heavy","daily","chef","preBlast","steamSoak","rinseDry","machineCare","AI","nightSilence","express_0C","daily_09","eco_08","eco_10"]}}],"type":"command","capability":"samsungce.dishwasherWashingCourse","label":"command: startWashingCourse(course*)"},"type":"hubitatTrigger"}]}"""

	updateDataValue("rules", rules)
}

//	===== Event Parse Interface s=====
void replicaStatus(def parent=null, Map status=null) {
	def logData = [parent: parent, status: status]
	if (state.refreshAttributes) {
		refreshAttributes(status.components.main)
	}
	logTrace("replicaStatus: ${logData}")
}

def refreshAttributes(mainData) {
	logDebug("refreshAttributes: ${mainData}")
	def value
	
	parse_main([attribute: "switch", value: mainData.switch.switch.value])
	pauseExecution(200)

	try {
		value = mainData["samsungce.kidsLock"].lockState.value
	} catch (e) {
		value = "n/a"
	}
	parse_main([attribute: "lockState", value: value])
	pauseExecution(200)
	
	try {
		value = mainData["samsungce.dishwasherWashingCourse"].supportedCourses.value
	} catch(e) {
		value = ["n/a"]
	}
	parse_main([attribute: "supportedCourses", value: value])
	pauseExecution(200)

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
		case "switch":
		case "lockState":
		case "washingCourse":
		case "operatingState":
		case "operationTime":
		case "remainingTime":
		case "timeLeftToStart":
		case "dishwasherJobState":
			sendEvent(name: event.attribute, value: event.value)
			break
		case "supportedCourses":
			state.supportedDishwasherCourses = event.value
			break
		default:
			logDebug("parse_main: [unhandledEvent: ${event}]")
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
	sendCommand("on")
	logDebug("on")
}

def off() {
	sendCommand("off")
	logDebug("off")
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
	sendCommand("setWashingCourse", washingCourse)
	logDebug("setWashingCourse: ${washingCourse}")
}

def startWashingCourse(washingCourse = device.currentValue("washingCourse")) {
	sendCommand("startWashingCourse", washingCourse)
	logDebug("startWashingCourse: ${washingCourse}")
}

def resume() {
	sendCommand("resume")
	logDebug("resume")
}

def start() {
	sendCommand("start")
	logDebug("start")
}

def pause() {
	sendCommand("pause")
	logDebug("pause")
}

def startLater(delay) {
	sendCommand("startLater", delay, "number")
	logDebug("startLater")
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
