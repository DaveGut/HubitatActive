/*
===== Blebox Hubitat Integration Driver

	Copyright 2019, Dave Gutheinz

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file except in compliance with the
License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.

DISCLAIMER: The author of this integration is not associated with blebox.  This code uses the blebox
open API documentation for development and is intended for integration into the Hubitat Environment.

===== Hiatory =====
09.20.19	1.2.01.	Initial Parent-Child release.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "1.2.01" }
metadata {
	definition (name: "bleBox wLightBox Rgb",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Hubitat/master/Drivers/wLightBoxRgb.groovy"
			   ) {
		capability "Light"
		capability "Switch"
		capability "Actuator"
		capability "Switch Level"
		capability "Refresh"
		capability "Color Control"
		capability "Color Mode"
	}
	preferences {
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}

def installed() {
	logInfo("Installing...")
 	sendEvent(name: "colorMode", value: "RGB")
	state.savedLevel = "000000"
	runIn(2, updated)
}

def updated() {
	logInfo("Updating...")
	unschedule()
	updateDataValue("driverVersion", driverVer())
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	state.fadeSpeed = 2
	refresh()
}


//	===== Commands and Parse Returns =====
def on() {
	logDebug("on: ${state.savedLevel}")
	parent.childCommand(getDataValue("channel"), state.savedLevel)
}

def off() {
	logDebug("off")
	parent.childCommand(getDataValue("channel"), "000000")
}

def setLevel(level, fadeSpeed = state.fadeSpeed) {
	logDebug("setLevel: level = ${level})")
	state.fadeSpeed = fadeSpeed
	setColor([hue: device.currentValue("hue"),
			  saturation: device.currentValue("saturation"),
			  level: level.toInteger()])
}

def setHue(hue) {
	logDebug("setHue:  hue = ${hue}")
	setColor([hue: hue.toInteger(),
			  saturation: device.currentValue("saturation"),
			  level: device.currentValue("level")])
}

def setSaturation(saturation) {
	logDebug("setSaturation: saturation = ${saturation}")
	setColor([hue: device.currentValue("hue"),
			  saturation: saturation.toInteger(),
			  level: device.currentValue("level")])
}

def setColor(color) {
	logDebug("setColor:  color = ${color}")
	def hue = color.hue
	if (hue == 0) { hue = 1 }
	def saturation = color.saturation
	if (saturation == 0) { saturation = hue }
	def level = color.level
	if (level < 1) { level = hue }
	def rgbData = hubitat.helper.ColorUtils.hsvToRGB([hue, saturation, level])
	def rgb = hubitat.helper.HexUtils.integerToHexString(rgbData[0], 1)
	rgb += hubitat.helper.HexUtils.integerToHexString(rgbData[1], 1)
	rgb += hubitat.helper.HexUtils.integerToHexString(rgbData[2], 1)
	parent.childCommand(getDataValue("channel"), rgb, state.fadeSpeed)
	state.FadeSpeed = 2
}

def refresh() {
	logDebug("refresh.")
	parent.refresh()
}

def parseReturnData(hexDesired) {
	logDebug("parseReturnData: ${hexDesired}")
	def hexLevel = hexDesired[0..5]
	if (hexLevel == "000000") {
		sendEvent(name: "switch", value: "off")
		sendEvent(name: "hue", value: 0)
		sendEvent(name: "saturation", value: 0)
		sendEvent(name: "level", value: 0)
		def color = ["hue": 0, "saturation": 0, "level": 0]
		sendEvent(name: "color", value: color)
		sendEvent(name: "RGB", value: "000000")
		logInfo("parseReturnData: Device is Off")
	} else {
		sendEvent(name: "switch", value: "on")
		state.savedLevel = hexDesired[0..5]
		def red255 = Integer.parseInt(hexLevel[0..1],16)
		def green255 = Integer.parseInt(hexLevel[2..3],16)
		def blue255 = Integer.parseInt(hexLevel[4..5],16)
		def hsvData = hubitat.helper.ColorUtils.rgbToHSV([red255, green255, blue255])
		def hue = (0.5 + hsvData[0]).toInteger()
		def saturation = (0.5 + hsvData[1]).toInteger()
		def level = (0.5 + hsvData[2]).toInteger()
		sendEvent(name: "hue", value: hue)
		sendEvent(name: "saturation", value: saturation)
		sendEvent(name: "level", value: level)
		def color = ["hue": hue, "saturation": saturation, "level": level]
		sendEvent(name: "color", value: color)
		setColorName(hue)
		sendEvent(name: "RGB", value: hexLevel)
		logInfo("parseReturnData: On, hue: ${hue}, saturation: ${saturation}, level: ${level}")
	}
}

def setColorName(hue){
	logDebug("setRgbData: hue = ${hue}")
    def colorName
	switch (hue){
		case 0..4: colorName = "Red"
			break
		case 5..12: colorName = "Orange"
			break
		case 13..20: colorName = "Yellow"
			break
		case 21..29: colorName = "Chartreuse"
			break
		case 30..37: colorName = "Green"
			break
		case 38..45: colorName = "Spring"
			break
		case 46..54: colorName = "Cyan"
			break
		case 55..62: colorName = "Azure"
			break
		case 63..255: colorName = "Blue"
			break
		case 256..70: colorName = "Violet"
			break
		case 71..87: colorName = "Magenta"
			break
		case 88..95: colorName = "Rose"
			break
		case 96..100: colorName = "Red"
			break
		deafult: colorName = "Unknown"
	}
	logDebug("setRgbData: Color is ${colorName}.")
	sendEvent(name: "colorName", value: colorName)
}


//	===== Utility Methods =====
def logInfo(msg) {
	if (descriptionText == true) { log.info "<b>${device.label} ${driverVer()}</b> ${msg}" }
}
def logDebug(msg){
	if(debug == true) { log.debug "<b>${device.label} ${driverVer()}</b> ${msg}" }
}
def logWarn(msg){ log.warn "<b>${device.label} ${driverVer()}</b> ${msg}" }

//	end-of-file