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
	definition (name: "bleBox wLightBox Mono",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Hubitat/master/Drivers/wLightBoxMono.groovy"
			   ) {
		capability "Light"
		capability "Switch"
		capability "Actuator"
		capability "Switch Level"
		capability "Refresh"
	}
	preferences {
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}

def installed() {
	logInfo("Installing...")
	state.savedLevel = "00"
	runIn(2, updated)
}

def updated() {
	logInfo("Updating...")
	unschedule()
	updateDataValue("driverVersion", driverVer())
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	state.transTime = 2
	refresh()
}


//	===== Commands and Parse Returns =====
def on() {
	logDebug("on: ${state.savedLevel}")
	parent.childCommand(getDataValue("channel"), state.savedLevel)
}

def off() {
	logDebug("off")
	parent.childCommand(getDataValue("channel"), "00")
}

def setLevel(level, fadeSpeed = state.transTime) {
	logDebug("setLevel: level = ${level})")
	level = (level * 2.55).toInteger()
	level = hubitat.helper.HexUtils.integerToHexString(level, 1)
	parent.childCommand(getDataValue("channel"), level, fadeSpeed)
}

def refresh() {
	logDebug("refresh.")
	parent.refresh()
}

def parseReturnData(hexDesired) {
	logDebug("parseReturnData: ${hexDesired}")
	def hexLevel
	switch(getDataValue("channel")) {
		case "ch1":
			hexLevel = hexDesired[0..1]
			break
		case "ch2":
			hexLevel = hexDesired[2..3]
			break
		case "ch3":
			hexLevel = hexDesired[4..5]
			break
		case "ch4":
			hexLevel = hexDesired[6..7]
			break
		default: return
	}
	if (hexLevel == "00") {
		sendEvent(name: "switch", value: "off")
		sendEvent(name: "level", value: 0)
		logInfo("parseReturnData: Devic is off")
	} else {
		sendEvent(name: "switch", value: "on")
		state.savedLevel = hexLevel
		def level = hubitat.helper.HexUtils.hexStringToInt(hexLevel)
		level = (0.5 + (level / 2.55)).toInteger()
		sendEvent(name: "level", value: level)
		logInfo("parseReturnData: On, level: ${level}")
	}
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