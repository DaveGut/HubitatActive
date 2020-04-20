/*
Bulb Buttons Driver, Version 1.0

	Copyright 2019 Dave Gutheinz

Licensed under the Apache License, Version 2.0 (the "License"); you may not use
this  file except in compliance with the License. You may obtain a copy of the
License at:

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the  License.
===== History ====================================================================
01.19.19	Initial Release
06.05.19	Update to correct failures due to data type rule
			changes in interpreter
//	===== Device Type Identifier ===============================================*/

	def driverVer() { return "1.1.01" }
metadata {
	definition (name: "Bulb Buttons",
				namespace: "davegut",
				author: "djgutheinz",
			   	importUrl: "https://github.com/DaveGut/Hubitat-Blub-Buttons/blob/master/Driver/Bulb%20Buttons%20Driver.groovy") {
		capability "Pushable Button"
		command "push", ["NUMBER"]
		attribute "Color_1", "string"
		attribute "Color_2", "string"
		attribute "Color_3", "string"
		attribute "Color_4", "string"
		attribute "Color_5", "string"
		attribute "Color_6", "string"
		attribute "Color_7", "string"
		attribute "Color_8", "string"
		attribute "Color_9", "string"
		attribute "Color_10", "string"
		attribute "Color_11", "string"
		attribute "Color_12", "string"
		attribute "SetCircadian", "string"
		attribute "CTemp_1", "string"
		attribute "CTemp_2", "string"
		attribute "CTemp_3", "string"
		attribute "CTemp_4", "string"
		attribute "CTemp_5", "string"
		attribute "CTemp_6", "string"
		attribute "CTemp_7", "string"
		attribute "CTemp_8", "string"
    }
	preferences {
	input name: "traceLog", type: "bool", title: "Display trace messages?", required: false
	}
}

def installed() {
	log.info "${device.label} Installed"
	if (getDataValue("color") == "true") {
		sendEvent(name: "Color_1", value: "Red")
		sendEvent(name: "Color_2", value: "Orange")
		sendEvent(name: "Color_3", value: "Yellow")
		sendEvent(name: "Color_4", value: "Chartreuse")
		sendEvent(name: "Color_5", value: "Green")
		sendEvent(name: "Color_6", value: "Spring")
		sendEvent(name: "Color_7", value: "Cyan")
		sendEvent(name: "Color_8", value: "Azure")
		sendEvent(name: "Color_9", value: "Blue")
		sendEvent(name: "Color_10", value: "Violet")
		sendEvent(name: "Color_11", value: "Magenta")
		sendEvent(name: "Color_12", value: "Rose")
	}
	sendEvent(name: "SetCircadian", value: "Set Circadian")
	sendEvent(name: "CTemp_1", value: "Incandescent")
	sendEvent(name: "CTemp_2", value: "Soft White")
	sendEvent(name: "CTemp_3", value: "Warm White")
	sendEvent(name: "CTemp_4", value: "Moonlight")
	sendEvent(name: "CTemp_5", value: "Horizon")
	sendEvent(name: "CTemp_6", value: "Daylight")
	sendEvent(name: "CTemp_7", value: "Electronic")
	sendEvent(name: "CTemp_8", value: "Skylight")
	updated()
}

def updated() {
	log.info "${device.label} Updating...."
	updateDataValue("driverVersion", driverVer())
	if (traceLog == true) { runIn(1800, stopTraceLogging) }
	if (getDataValue("color") == "true") { state.colorNo = 1 }
	state.colorTempNo = 21
}

def stopTraceLogging() {
	logTrace("stopTraceLogging")
	device.updateSetting("traceLog", [type:"bool", value: false])
}

def push(pushed) {
	pushed = pushed.toInteger()
	def bulb = getDataValue("bulb")
	if (!getDataValue("color") && pushed < 15) {
		log.error "${device.label}: Push ignored.  Not a color bulb!"
		return
	}
	logTrace("push: button = ${pushed}, bulb = ${bulb}")
	def dni = device.deviceNetworkId

	switch(pushed) {
		//	===== Color Bulbs =====
		case 1 :
			parent.setColor(bulb, [hue: 1, saturation: 100])
			state.colorNo = pushed
			break
		
		case 2 :
			parent.setColor(bulb, [hue: 9, saturation: 100])
			state.colorNo = pushed
			break
		
		case 3 :
			parent.setColor(bulb, [hue: 17, saturation: 100])
			state.colorNo = pushed
			break
		
		case 4 :
			parent.setColor(bulb, [hue: 26, saturation: 100])
			state.colorNo = pushed
			break
		
		case 5 :
			parent.setColor(bulb, [hue: 34, saturation: 100])
			state.colorNo = pushed
			break
		
		case 6 :
			parent.setColor(bulb, [hue: 42, saturation: 100])
			state.colorNo = pushed
			break
		
		case 7 :
			parent.setColor(bulb, [hue: 51, saturation: 100])
			state.colorNo = pushed
			break
		
		case 8 :
			parent.setColor(bulb, [hue: 59, saturation: 100])
			state.colorNo = pushed
			break
		
		case 9 :
			parent.setColor(bulb, [hue: 67, saturation: 100])
			state.colorNo = pushed
			break
		
		case 10 :
			parent.setColor(bulb, [hue: 76, saturation: 100])
			state.colorNo = pushed
			break
		
		case 11 :
			parent.setColor(bulb, [hue: 84, saturation: 100])
			state.colorNo = pushed
			break
		
		case 12 :
			parent.setColor(bulb, [hue: 92, saturation: 100])
			state.colorNo = pushed
			break
		
		case 13 :
			def toggle = state.colorNo + 1
			if (toggle > 12) { toggle = 1 }
			push(toggle)
			break
		
		case 14 :
			def hue = Math.abs(new Random().nextInt() % 100) + 1
			def saturation = 100 - Math.abs(new Random().nextInt() % 100)
			parent.setColor(bulb, [hue: hue, saturation: saturation])
			break
		
		//	===== Color Temperature Functions =====
		case 20 :
			parent.setCircadian(bulb)
			break
		case 21 :
			parent.setColorTemperature(bulb, 2700)
			state.colorTempNo = pushed
			break
		
		case 22 :
			parent.setColorTemperature(bulb, 3150)
			state.colorTempNo = pushed
			break
		
		case 23 :
			parent.setColorTemperature(bulb, 3400)
			state.colorTempNo = pushed
			break
		
		case 24 :
			parent.setColorTemperature(bulb, 3825)
			state.colorTempNo = pushed
			break
		
		case 25 :
			parent.setColorTemperature(bulb, 4425)
			state.colorTempNo = pushed
			break
		
		case 26 :
			parent.setColorTemperature(bulb, 5250)
			state.colorTempNo = pushed
			break

		case 27 :
			parent.setColorTemperature(bulb, 5750)
			state.colorTempNo = pushed
			break

		case 28 :
			parent.setColorTemperature(bulb, 6450)
			state.colorTempNo = pushed
			break

		case 29 :
			def toggle = state.colorTempNo + 1
			if (toggle > 28) { toggle = 21 }
			push(toggle)
			break

		default :
			log.error "${device.label}: button number out of range!"
	}
}

def logTrace(msg) {
	if (traceLog == true) { log.trace msg }
}

//	end-of-file