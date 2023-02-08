/*	HubiThings Replica Color Temperature Bulb Driver
	HubiThings Replica Applications Copyright 2023 by Bloodtick
	Replica Color Temperature Bulb Copyright 2023 by Dave Gutheinz

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
def driverVer() { return "1.0" }

metadata {
	definition (name: "Replica CT Bulb",
				namespace: "replica",
				author: "Dave Gutheinz",
				importUrl: ""
			   ) {
		capability "Light"
		capability "Switch"
		capability "Switch Level"
		capability "Change Level"
		capability "Color Temperature"
		command "setColorTemperature", [[
			name: "Color Temperature",
			type: "NUMBER"]]
		capability "Refresh"
		capability "Actuator"
		capability "Configuration"
		
		attribute "healthStatus", "enum", ["offline", "online"]
	}
	preferences {
		input ("textEnable", "bool", 
			   title: "Enable descriptionText logging",
			   defaultValue: true)
		input ("logEnable", "bool",
			   title: "Enable debug logging",
			   defaultValue: false)
		input ("transTime", "number",
			   title: "Default Transition time (seconds)",
			   defaultValue: 1)
		input ("ctLow", "number", title: "lowerLimit of Color Temp", defaultValue: 2000)
		input ("ctHigh", "number", title: "UpperLimit of Color Temp", defaultValue: 9000)
	}
}

def installed() {
	initialize()	
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
	updStatus << [logEnable: logEnable, infoLog: infoLog]
	runIn(5, refresh)
	pauseExecution(5000)
	listAttributes(true)
	logInfo("updated: ${updStatus}")
}

def initialize() {
    updateDataValue("triggers", groovy.json.JsonOutput.toJson(getReplicaTriggers()))
    updateDataValue("commands", groovy.json.JsonOutput.toJson(getReplicaCommands()))
}

//	===== HubiThings Device Settings =====
Map getReplicaCommands() {
	Map replicaCommands = [ 
		setSwitchValue:[[name:"switch*",type:"ENUM"]], 
		setLevelValue:[[name:"level*",type:"NUMBER"]] ,
		setColorTemperatureValue:[[name: "colorTemperature", type: "NUMBER"]],
		setHealthStatusValue:[[name:"healthStatus*",type:"ENUM"]]]
	return replicaCommands
}

Map getReplicaTriggers() {
	def replicaTriggers = [
		off:[],
		on:[],
		setLevel: [
			[name:"level*", type: "NUMBER"],
			[name:"rate", type:"NUMBER",data:"rate"]],
		setColorTemperature: [
			[name:"colorTemperature", type: "NUMBER"]],
		refresh:[]]
	return replicaTriggers
}

def configure() {
    logInfo "configure: configured default rules"
    initialize()
    updateDataValue("rules", getReplicaRules())
	sendCommand("configure")
}

String getReplicaRules() {
	return """{"version":1,"components":[{"trigger":{"name":"off","label":"command: off()","type":"command"},"command":{"name":"off","type":"command","capability":"switch","label":"command: off()"},"type":"hubitatTrigger"},{"trigger":{"name":"on","label":"command: on()","type":"command"},"command":{"name":"on","type":"command","capability":"switch","label":"command: on()"},"type":"hubitatTrigger"},{"trigger":{"name":"refresh","label":"command: refresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},{"trigger":{"name":"setColorTemperature","label":"command: setColorTemperature(colorTemperature)","type":"command","parameters":[{"name":"colorTemperature","type":"NUMBER"}]},"command":{"name":"setColorTemperature","arguments":[{"name":"temperature","optional":false,"schema":{"type":"integer","minimum":1,"maximum":30000}}],"type":"command","capability":"colorTemperature","label":"command: setColorTemperature(temperature*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setLevel","label":"command: setLevel(level*, rate)","type":"command","parameters":[{"name":"level*","type":"NUMBER"},{"name":"rate","type":"NUMBER","data":"rate"}]},"command":{"name":"setLevel","arguments":[{"name":"level","optional":false,"schema":{"type":"integer","minimum":0,"maximum":100}},{"name":"rate","optional":true,"schema":{"title":"PositiveInteger","type":"integer","minimum":0}}],"type":"command","capability":"switchLevel","label":"command: setLevel(level*, rate)"},"type":"hubitatTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"type":"integer","minimum":1,"maximum":30000},"unit":{"type":"string","enum":["K"],"default":"K"}},"additionalProperties":false,"required":["value"],"capability":"colorTemperature","attribute":"colorTemperature","label":"attribute: colorTemperature.*"},"command":{"name":"setColorTemperatureValue","label":"command: setColorTemperatureValue(colorTemperature)","type":"command","parameters":[{"name":"colorTemperature","type":"NUMBER"}]},"type":"smartTrigger"},{"trigger":{"title":"IntegerPercent","type":"attribute","properties":{"value":{"type":"integer","minimum":0,"maximum":100},"unit":{"type":"string","enum":["%"],"default":"%"}},"additionalProperties":false,"required":["value"],"capability":"switchLevel","attribute":"level","label":"attribute: level.*"},"command":{"name":"setLevelValue","label":"command: setLevelValue(level*)","type":"command","parameters":[{"name":"level*","type":"NUMBER"}]},"type":"smartTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"SwitchState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"switch","attribute":"switch","label":"attribute: switch.*"},"command":{"name":"setSwitchValue","label":"command: setSwitchValue(switch*)","type":"command","parameters":[{"name":"switch*","type":"ENUM"}]},"type":"smartTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger"}]}"""
}

//	===== HubiThings Send Command and Device Health =====
def setHealthStatusValue(value) {    
    sendEvent(name: "healthStatus", value: value, descriptionText: "${device.displayName} healthStatus set to $value")
}

private def sendCommand(String name, def value=null, String unit=null, data=[:]) {
    parent?.deviceTriggerHandler(device, [name:name, value:value, unit:unit, data:data, now:now])
}

void refresh() {
	sendCommand("refresh")
}

//	===== Mono, CT, Color Bulb commands =====
def on() {
	sendCommand("on")
}

def off() {
	sendCommand("off")
}

def setSwitchValue(onOff) {
    sendEvent(name: "switch", value: onOff)
	logDebug("setSwitchValue: [switch: ${onOff}]")
}

def setLevel(level, transTime = transTime) {
	if (level == null || level < 0) {
		level = 0
	} else if (level > 100) {
		level = 100
	}
	if (transTime == null || transTime < 0) {
		transTime = 0
	} else if (transTime > 8) {
		transTime = 8
	}
	if (level == 0) {
		off()
	} else {
		sendCommand("setLevel", level, null, [rate:transTime])
	}
}

def startLevelChange(direction) {
	unschedule(levelUp)
	unschedule(levelDown)
	if (direction == "up") { levelUp() }
	else { levelDown() }
}

def stopLevelChange() {
	unschedule(levelUp)
	unschedule(levelDown)
}

def levelUp() {
	def curLevel = device.currentValue("level").toInteger()
	if (curLevel == 100) { return }
	def newLevel = curLevel + 4
	if (newLevel > 100) { newLevel = 100 }
	setLevel(newLevel, 0)
	runIn(1, levelUp)
}

def levelDown() {
	def curLevel = device.currentValue("level").toInteger()
	if (curLevel == 0 || device.currentValue("switch") == "off") { return }
	def newLevel = curLevel - 4
	if (newLevel < 0) { off() }
	else {
		setLevel(newLevel, 0)
		runIn(1, levelDown)
	}
}

def setLevelValue(level) {
	sendEvent(name: "level", value: level, unit: "%")
	//	Update attribute color if in color mode
	if (device.currentValue("colorMode") == "COLOR") {
		runIn(3, setHslValue)
	}
	logDebug("setLevelValue: [level: ${level}%]")
}

//	===== CT and Color Bulb Commands =====
//	CT May not work with light strips
def setColorTemperature(colorTemp) {
	if (colorTemp > ctHigh) { colorTemp = ctHigh}
	else if (colorTemp < ctLow) { colorTemp = ctLow}
	sendEvent(name: "colorMode", value: "CT")
	sendCommand("setColorTemperature", colorTemp)
	if(device.currentValue("colorTemperature") == colorTemp) {
		setColorTemperatureValue(colorTemp)
	}
}

def setColorTemperatureValue(colorTemp) {
	def logData = [colorTemperature: "${colorTemp}°K"]
	sendEvent(name: "colorTemperature", value: colorTemp, unit: "°K")
	if (device.currentValue("colorMode") == "CT") {
		def colorName = convertTemperatureToGenericColorName(colorTemp.toInteger())
		sendEvent(name: "colorName", value: colorName, isChange: true)
		logData << [colorName: colorName]
	}
	logDebug("setColorTemperatureValue: ${logData}")
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
