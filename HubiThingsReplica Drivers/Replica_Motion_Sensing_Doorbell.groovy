/*	HubiThings Replica Motion Sensing Doorbell Driver
	HubiThings Replica Applications Copyright 2023 by Bloodtick
	Replica Motion Sensing Doorbell Copyright 2023 by Dave Gutheinz

	Licensed under the Apache License, Version 2.0 (the "License"); 
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at:
	      http://www.apache.org/licenses/LICENSE-2.0
	Unless required by applicable law or agreed to in writing, software 
	distributed under the License is distributed on an "AS IS" BASIS, 
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or 
	implied. See the License for the specific language governing 
	permissions and limitations under the License.

Issues with this driver: Contact davegut on via Private Message on the
Hubitat Community site: https://community.hubitat.com/

Appreciation and thanks to bthrock for his initial version of this
driver and support in the developing the below version.
==========================================================================*/
import groovy.json.JsonOutput
def driverVer() { return "1.0.0" }

metadata {
    definition(name: "Replica Motion-Sensing Doorbell", namespace: "replica", author: "bthrock", importUrl:"https://raw.githubusercontent.com/TheMegamind/Replica-Drivers/main/replicaMotionSensingDoorbell.groovy")
    {
        capability "Actuator"
        capability "Configuration"
        capability "MotionSensor"
		attribute "doorbell", "string"
        capability "Refresh"
		capability "Battery"
		command "testRules"
		attribute "lastMotion", "string"
		attribute "lastRing", "string"
        attribute "healthStatus", "enum", ["offline", "online"]
    }
    preferences {   
		input ("textEnable", "bool", 
			   title: "Enable descriptionText logging",
			   defaultValue: true)
		input ("logEnable", "bool",
			   title: "Enable debug logging",
			   defaultValue: false)
    }
}

def installed() {
	initialize()
}

def updated() {
	initialize()
}

def initialize() {
    updateDataValue("triggers", groovy.json.JsonOutput.toJson(getReplicaTriggers()))
    updateDataValue("commands", groovy.json.JsonOutput.toJson(getReplicaCommands()))
}

//	===== HubiThings Device Settings =====
Map getReplicaCommands() {
    return ([
		"setRing":[[name:"button", type: "ENUM"]],
		"setBattery":[[name:"battery", type:"integer"]],
		"setMotionValue":[[name:"motion*",type:"ENUM"]], 
		"setHealthStatusValue":[[name:"healthStatus*",type:"ENUM"]]
	])
}

Map getReplicaTriggers() {
    return ([ "refresh":[], "deviceRefresh": [] ])
}

def configure() {
	logInfo("configure: configured default rules")
    initialize()
    updateDataValue("rules", getReplicaRules())
    sendCommand("configure")
}

String getReplicaRules() {
	return """{"version":1,"components":[{"trigger":{"title":"IntegerPercent","type":"attribute","properties":{"value":{"type":"integer","minimum":0,"maximum":100},"unit":{"type":"string","enum":["%"],"default":"%"}},"additionalProperties":false,"required":["value"],"capability":"battery","attribute":"battery","label":"attribute: battery.*"},"command":{"name":"setBattery","label":"command: setBattery(battery)","type":"command","parameters":[{"name":"battery","type":"integer"}]},"type":"smartTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"ActivityState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"motionSensor","attribute":"motion","label":"attribute: motion.*"},"command":{"name":"setMotionValue","label":"command: setMotionValue(motion*)","type":"command","parameters":[{"name":"motion*","type":"ENUM"}]},"type":"smartTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"ButtonState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"button","attribute":"button","label":"attribute: button.*"},"command":{"name":"setRing","label":"command: setRing(button)","type":"command","parameters":[{"name":"button","type":"ENUM"}]},"type":"smartTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger"},{"trigger":{"name":"deviceRefresh","label":"command: deviceRefresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger","disableStatus":true}]}"""
}

//	===== HubiThings Send Command and Device Health =====
def setHealthStatusValue(value) {    
    sendEvent(name: "healthStatus", value: value)
}

private def sendCommand(String name, def value=null, String unit=null, data=[:]) {
    parent?.deviceTriggerHandler(device, [name:name, value:value, unit:unit, data:data, now:now])
}

void refresh() {
    sendCommand("refresh")
}

def deviceRefresh() {
	sendCommand("deviceRefresh")
}

//	===== Motion and Ring Detection Methods =====
def testRules() {
	setMotionValue("active")
	pauseExecution(10000)
	setRing("pushed")
	setMotionValue("inactive")
}

def setRing(button) {
	if (device.currentValue("doorbell") != button) {
		sendEvent(name: "doorbell", value: button)
		if (button == "pushed") {
			def ringTime = new Date()
			sendEvent(name: "lastRing", value: ringTime)
			runIn(20, deviceRefresh)
			runIn(30, checkRing)
		}
	}
	logInfo("setRing: [doorbell: ${button}]")
}

def checkRing() {
	if (device.currentValue("doorbell") == "pushed") {
		sendEvent(name: "doorbell", value: "up")
		logInfo("checkRing: [doorbell: up]")
	}
}

def setBattery(battery) {
	sendEvent(name: "battery", value: battery, unit: "%")
	logDebug("setBattery: [battery: ${battery}]")
}

def setMotionValue(value) {
	sendEvent(name: "motion", value: value)
	def motionTime = new Date()
	if (value == "active") {
		sendEvent(name: "lastMotion", value: motionTime)
	}
	logInfo("setMotionValue: [motion: ${value}]")
}

//	===== Data Logging =====
def listAttributes(trace = false) {
	def attrs = device.getSupportedAttributes()
	def attrList = [:]
	attrs.each {
		def val = device.currentValue("${it}")
		attrList << ["${it}": val]
	}
	if (trace == true) {
		logInfo("Attributes: ${attrList}")
	} else {
		logDebug("Attributes: ${attrList}")
	}
}
def logTrace(msg){
	log.trace "${device.displayName}-${driverVer()}: ${msg}"
}
def logInfo(msg) { 
	if (textEnable) {
		log.info "${device.displayName}-${driverVer()}: ${msg}"
	}
}
def debugLogOff() {
	if (logEnable) {
		device.updateSetting("logEnable", [type:"bool", value: false])
	}
	logInfo("debugLogOff")
}
def logDebug(msg) {
	if (logEnable) {
		log.debug "${device.displayName}-${driverVer()}: ${msg}"
	}
}
def logWarn(msg) { log.warn "${device.displayName}-${driverVer()}: ${msg}" }