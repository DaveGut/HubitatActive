/**
*  Copyright 2023 David Gutheinz
*
*  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License. You may obtain a copy of the License at:
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
*  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
*  for the specific language governing permissions and limitations under the License.
*
*/
@SuppressWarnings('unused')
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.transform.Field
@Field volatile static Map<String,Long> g_mEventSendTime = [:]
public static String driverVer() { return "0.1.1" }

//	Beta Status:  Tested on Sengled and Kasa Bulbs integrated in SmartThings.

metadata {
	definition (name: "Replica Color Bulb",
				namespace: "replica",
				author: "Dave Gutheinz",
				importUrl: ""
			   ) {
		capability "Light"
		capability "Switch"
		capability "Switch Level"
		capability "Change Level"
		capability "Color Temperature"
		//	ST setColorTemp does not support transition time
		//	Hard code command setColorTemperature.
		command "setColorTemperature", [[
			name: "Color Temperature",
			type: "NUMBER"]]
		capability "Color Mode"
		capability "Color Control"
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
	initialize()	
}

def initialize() {
    updateDataValue("triggers", groovy.json.JsonOutput.toJson(getReplicaTriggers()))
    updateDataValue("commands", groovy.json.JsonOutput.toJson(getReplicaCommands()))
}

def configure() {
    logInfo "configure: configured default rules"
    initialize()
    updateDataValue("rules", getReplicaRules())
	sendCommand("configure")
}

void refresh() {
	sendCommand("refresh")
}

//	Capability Switch
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


//	Capability SwitchLevel
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
		runIn(5, setColorValue)
	}
	logDebug("setLevelValue: [level: ${level}%]")
}


//	Capability Color Temperature
def setColorTemperature(colorTemp) {
	if (colorTemp > ctHigh) { colorTemp = ctHigh}
	else if (colorTemp < ctLow) { colorTemp = ctLow}
	sendEvent(name: "colorMode", value: "CT")
	sendCommand("setColorTemperature", colorTemp)
}

def setColorTemperatureValue(colorTemp) {
	sendEvent(name: "colorTemperature", value: colorTemp, unit: "°K")
	if (device.currentValue("colorMode") == "CT") {
		def colorName = convertTemperatureToGenericColorName(colorTemp)
		sendEvent(name: "colorName", value: colorName)
		logDebug("setColorTemperatureValue: [colorTemperature: ${colorTemp}°K, colorName: ${colorName}]")
	}
}


//	Capability Color Control
def setHue(hue) { 
	hue = (hue + 0.5).toInteger()
	if (hue < 0) { hue = 0 }
	else if (hue > 100) { hue = 100 }
	setColor([hue: hue])
}

def setSaturation(saturation) {
	saturation = (saturation +0.5).toInteger()
	if (saturation < 0) { saturation = 0 }
	else if (saturation > 100) { saturation = 100 }
	setColor([saturation: saturation])
}

def setColor(Map color) {
	//	Design note.  SmartThings device do not set level via the setColor command
	//	Added setLevel command if the color command has defined a new level.
	if (color == null) {
		LogWarn("setColor: Color map is null. Command not executed.")
	} else {
		def level = device.currentValue("level")
		if (color.level) { level = color.level }
		//	ST setColor does not always implement "level".  For Hubitat
		//	compatibility reasons, do a separate setLevel (rate = 0).
		setLevel(level, 0)
		pauseExecution(200)
		def hue = device.currentValue("hue")
		if (color.hue) { hue = color.hue }
		def saturation = device.currentValue("saturation")
		if (color.saturation) { saturation = color.saturation }
		def newColor = """{"hue":${hue}, "saturation":${saturation}, "level":${level}}"""
		sendEvent(name: "colorMode", value: "COLOR")
		sendCommand("setColor", newColor)
		logDebug("setColor: [color: ${color}, level: ${level}, setColor: ${newColor}]")
	}
}

def setHueValue(hue) {
	hue = (hue + 0.5).toInteger()
	sendEvent(name: "hue", value: hue, unit: "%")
	runIn(3, setColorValue)
	logDebug("setHueValue: [hue: ${hue}%]")
}

def setSaturationValue(saturation) {
	saturation = (saturation + 0.5).toInteger()
	sendEvent(name: "saturation", value: saturation, unit: "%")
	runIn(3, setColorValue)
	logInfo("setSaturationValue: [saturation: ${saturation}%]")
}

def setColorValue() {
	//	ST always returns null of color.  Recreate using
	//	Hubitat attributes hue, saturation, level.
	Map color = [hue: device.currentValue("hue"),
				 saturation: device.currentValue("saturation"),
				 level: device.currentValue("level")
				 ]
	sendEvent(name: "color", value: color)
	if (device.currentValue("colorMode") == "COLOR") {
		def colorName = convertHueToGenericColorName(color.hue)
		sendEvent(name: "colorName", value: colorName)
		logDebug("setColorValue: [color: ${color}, colorName: ${colorName}]")
	}
}

def setHealthStatusValue(value) {    
    sendEvent(name: "healthStatus", value: value, descriptionText: "${device.displayName} healthStatus set to $value")
}


private def sendCommand(String name, def value=null, String unit=null, data=[:]) {
    parent?.deviceTriggerHandler(device, [name:name, value:value, unit:unit, data:data, now:now])
}


Map getReplicaCommands() {
	Map replicaCommands = [ 
		setSwitchValue:[[name:"switch*",type:"ENUM"]], 
		setLevelValue:[[name:"level*",type:"NUMBER"]] ,
		setColorTemperatureValue:[[name: "colorTemperature", type: "NUMBER"]],
		setHueValue:[[name: "hue", type: "NUMBER"]],
		setSaturationValue:[[name: "saturation", type: "NUMBER"]],
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
		setColor: [
			[name: "color", type: "OBJECT"]],
		refresh:[]]
	return replicaTriggers
}

String getReplicaRules() {
	return """{"version":1,"components":[{"trigger":{"name":"off","label":"command: off()","type":"command"},"command":{"name":"off","type":"command","capability":"switch","label":"command: off()"},"type":"hubitatTrigger"},{"trigger":{"name":"on","label":"command: on()","type":"command"},"command":{"name":"on","type":"command","capability":"switch","label":"command: on()"},"type":"hubitatTrigger"},{"trigger":{"name":"refresh","label":"command: refresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},{"trigger":{"name":"setColor","label":"command: setColor(color)","type":"command","parameters":[{"name":"color","type":"object"}]},"command":{"name":"setColor","arguments":[{"name":"color","optional":false,"schema":{"title":"COLOR_MAP","type":"object","additionalProperties":false,"properties":{"hue":{"type":"number"},"saturation":{"type":"number"},"hex":{"type":"string","maxLength":7},"level":{"type":"integer"},"switch":{"type":"string","maxLength":3}}}}],"type":"command","capability":"colorControl","label":"command: setColor(color*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setColorTemperature","label":"command: setColorTemperature(colorTemperature)","type":"command","parameters":[{"name":"colorTemperature","type":"NUMBER"}]},"command":{"name":"setColorTemperature","arguments":[{"name":"temperature","optional":false,"schema":{"type":"integer","minimum":1,"maximum":30000}}],"type":"command","capability":"colorTemperature","label":"command: setColorTemperature(temperature*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setLevel","label":"command: setLevel(level*, rate)","type":"command","parameters":[{"name":"level*","type":"NUMBER"},{"name":"rate","type":"NUMBER","data":"rate"}]},"command":{"name":"setLevel","arguments":[{"name":"level","optional":false,"schema":{"type":"integer","minimum":0,"maximum":100}},{"name":"rate","optional":true,"schema":{"title":"PositiveInteger","type":"integer","minimum":0}}],"type":"command","capability":"switchLevel","label":"command: setLevel(level*, rate)"},"type":"hubitatTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"type":"integer","minimum":1,"maximum":30000},"unit":{"type":"string","enum":["K"],"default":"K"}},"additionalProperties":false,"required":["value"],"capability":"colorTemperature","attribute":"colorTemperature","label":"attribute: colorTemperature.*"},"command":{"name":"setColorTemperatureValue","label":"command: setColorTemperatureValue(colorTemperature)","type":"command","parameters":[{"name":"colorTemperature","type":"NUMBER"}]},"type":"smartTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"PositiveNumber","type":"number","minimum":0}},"additionalProperties":false,"required":[],"capability":"colorControl","attribute":"hue","label":"attribute: hue.*"},"command":{"name":"setHueValue","label":"command: setHueValue(hue)","type":"command","parameters":[{"name":"hue","type":"NUMBER"}]},"type":"smartTrigger"},{"trigger":{"title":"IntegerPercent","type":"attribute","properties":{"value":{"type":"integer","minimum":0,"maximum":100},"unit":{"type":"string","enum":["%"],"default":"%"}},"additionalProperties":false,"required":["value"],"capability":"switchLevel","attribute":"level","label":"attribute: level.*"},"command":{"name":"setLevelValue","label":"command: setLevelValue(level*)","type":"command","parameters":[{"name":"level*","type":"NUMBER"}]},"type":"smartTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"PositiveNumber","type":"number","minimum":0}},"additionalProperties":false,"required":[],"capability":"colorControl","attribute":"saturation","label":"attribute: saturation.*"},"command":{"name":"setSaturationValue","label":"command: setSaturationValue(saturation)","type":"command","parameters":[{"name":"saturation","type":"NUMBER"}]},"type":"smartTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"SwitchState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"switch","attribute":"switch","label":"attribute: switch.*"},"command":{"name":"setSwitchValue","label":"command: setSwitchValue(switch*)","type":"command","parameters":[{"name":"switch*","type":"ENUM"}]},"type":"smartTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger"}]}"""
}


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