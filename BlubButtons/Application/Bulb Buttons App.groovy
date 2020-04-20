/*
Bulb Buttons Application, Version 1.0

	Copyright 2019 Dave Gutheinz

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this  file except in compliance with the
License. You may obtain a copy of the License at:

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, 
software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
either express or implied. See the License for the specific 
language governing permissions and limitations under the 
License.
===== History ================================================
01.19.19	Initial Release
//	===== Device Type Identifier ===========================*/
	def applicationVer() { return "1.0.01" }
//	def debugLog() { return false }
	def debugLog() { return true }

definition(
	name: "Bulb Color Buttons",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Provide Color, Color Temperature, and other additional functions via buttons.",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	iconX3Url: "")
	singleInstance: true

preferences {
	page(name: "mainPage")
	page(name: "selectBulbsPage")
	page(name: "listBulbsPage")
}

//	===== Page Definitions =====
def mainPage() {
	logDebug("mainPage")
	setInitialStates()

	return dynamicPage(name:"mainPage",
		title:"Select Bulbs for Accessory Buttons",
		nextPage:"",
		refresh: false,
        multiple: true,
		uninstall: true,
		install: true) {
        
 		section() {
			paragraph "You must select bulbs in both lists for all functions to work."
			input ("colorBulbs",
				   "capability.colorControl",
				   title: "Select Color Bulbs for Buttons",
                   submitOnChange: false,
				   required: false,
				   multiple: true)
 		
			input ("colorTempBulbs",
				   "capability.colorTemperature",
				   title: "Select Color Temp Bulbs for Buttons",
                   submitOnChange: false,
				   required: false,
				   multiple: true)
		}
	}
}

//	===== Start up Functions =====
def setInitialStates() {
	logDebug("setInitialStates")
	if (!state.devices) { state.devices = [:] }
}

def installed() { initialize() }

def updated() { initialize() }

def initialize() {
	logDebug("initialize")
	unsubscribe()
	unschedule()
	if (colorBulbs || colorTempBulbs) { addDevices() }
}

//	=======================================
//	===== Add Devices to Hubitat ==========
//	=======================================
def addDevices() {
	logDebug("addDevices: colorBulbs = ${colorBulbs} / CT Bulbs = ${colorTempBulbs}")
	state.devices = [:]
	devices = [:]
	if (colorTempBulbs) {
		colorTempBulbs.each {
			def dni = "${it.getDeviceNetworkId()}_BUT"
			def device = [:]
			device["bulb"] = "${it}"
			device["dni"]  = "${dni}"
			device["color"] = false
			devices << ["${dni}" : device]
		}
	}
	
	if (colorBulbs) {
		colorBulbs.each {
			def dni = "${it.getDeviceNetworkId()}_BUT"
			def device = [:]
			device["bulb"] = "${it}"
			device["dni"]  = "${dni}"
			device["color"] = true
			devices << ["${dni}" : device]
		}
	}
	state.devices = devices

	try { 
		hub = location.hubs[0] 
	} catch (error) { 
		log.error "Hub not detected.  You must have a hub to install this app."
		return
	}
	def hubId = hub.id
	devices.each { bulb ->
		def isChild = getChildDevice(bulb.value.dni)
		if (!isChild) {
			logDebug("addDevices: ${bulb.value.dni} / ${hubId} / bulb = ${bulb.value.bulb}")
			addChildDevice(
              	"davegut", 
				"Bulb Buttons",
				bulb.value.dni,
				hubId, [
					"label" : "${bulb.value.bulb} Buttons",
                   	"name" : "Bulb Buttons",
					"data" : [ "bulb": bulb.value.bulb,
							   "color": bulb.value.color]
                ]
            )
			log.info "Installed Button Driver named ${bulb.value.bulb} Buttons"
		}
	}
}

def setColor(bulb, colorMap) {
	logTrace("setColor: bulb = ${bulb} / colorMap = ${colorMap} / list = ${colorBulbs} / DNI = ${dni}")
	def selectedBulb = colorBulbs.find{ it.toString() == bulb }
	selectedBulb.setColor(colorMap)
}

def setColorTemperature(bulb, colorTemp) {
	logTrace("setColorTemp: bulb = ${bulb} / colorTemp = ${colorTemp} / DNI = ${dni} / list = ${colorTempBulbs}")
	def selectedBulb = colorTempBulbs.find{ it.toString() == bulb }
	selectedBulb.setColorTemperature(colorTemp)
}

def setCircadian(bulb) {
	logTrace("setCircadian: bulb = ${bulb} / list = ${colorTempBulbs}")
	def selectedBulb = colorTempBulbs.find{ it.toString() == bulb }
	selectedBulb.setCircadian()
}

def logTrace(msg){
	if(debugLog() == true) { log.trace msg }
}

def logDebug(msg){
	if(debugLog() == true) { log.debug msg }
}

//	end-of-file