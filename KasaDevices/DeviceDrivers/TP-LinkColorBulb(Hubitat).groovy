/*
Kasa Local Device Driver
		Copyright Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file except in compliance with the
License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.

DISCLAIMER:  This Applicaion and the associated Device Drivers are in no way sanctioned or supported by TP-Link.  
All  development is based upon open-source data on the TP-Link devices; primarily various users on GitHub.com.

===== 2020 History =====
02.28	New version 5.0
		a.	Changed version number to Ln.n.n format where the L refers to LOCAL installation.
		b.	Moved Quick Polling from preferences to a command with number (seconds) input value.  A value of
			blank or 0 is disabled.  A value below 5 is read as 5.
		c.	Upaded all drivers to eight individual divers.
03.03	Manual install and functional testing complete.  Auto Installation testing complete.
04.08	L5.0.4.  Initial development started for next version:
		a.	Add type to attribute "switch",
		b.	Add 60 and 180 minute refresh rates.  Change default to 60 minutes.
=======================================================================================================*/
def driverVer() { return "L5.0.4" }

metadata {
	definition (name: "Kasa Color Bulb",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/Hubitat-TP-Link-Integration/master/DeviceDrivers/TP-LinkColorBulb(Hubitat).groovy"
			   ) {
        capability "Light"
		capability "Switch"
		capability "Switch Level"
		capability "Change Level"
 		capability "Refresh"
		capability "Actuator"
		capability "Color Temperature"
		command "setCircadian"
		attribute "circadianState", "string"
		capability "Color Mode"
		capability "Color Control"
	}
	preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP", defaultValue: getDataValue("deviceIP"))
		}
		input ("transition_Time", "num", title: "Default Transition time (seconds)", defaultValue: 0)
		input ("highRes", "bool", title: "High Resolution Hue Scale", defaultValue: false)
		input ("refresh_Rate", "enum", title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "10", "15", "30", "60", "180"], defaultValue: "60")
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}

def installed() {
	log.info "Installing .."
	updated()
}

def updated() {
	log.info "Updating .."
	unschedule()
	state.errorCount = 0
	if (device.currentValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
	}
	if (!getDataValue("applicationVersion")) {
		if (!device_IP) {
			logWarn("updated: Device IP is not set.")
			return
		}
		if (getDataValue("deviceIP") != device_IP.trim()) {
			updateDataValue("deviceIP", device_IP.trim())
			logInfo("updated: Device IP set to ${device_IP.trim()}")
		}
	}
	switch(refresh_Rate) {
		case "1" : runEvery1Minute(refresh); break
		case "5" : runEvery5Minutes(refresh); break
		case "10" : runEvery10Minutes(refresh); break
		case "15" : runEvery15Minutes(refresh); break
		case "30" : runEvery30Minutes(refresh); break
		case "180": runEvery3Hours(refresh); break
		default: runEvery1Hour(refresh)
	}
	logInfo("updated: Refresh set for every ${refresh_Rate} minute(s).")
	state.transTime = 1000*transition_Time.toInteger()
	logInfo("updated: Light transition time set to ${transition_Time} seconds.")
	if (debug == true) { runIn(1800, debugLogOff) }
	logInfo("updated: Debug logging is: ${debug} for 30 minutes.")
	logInfo("updated: Description text logging is ${descriptionText}.")
	refresh()
}

//	Device Cloud and Local Common Methods
def on() {
	logDebug("On: transition time = ${state.transTime}")
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":1,"transition_period":${state.transTime}}}}""", "commandResponse")
}

def off() {
	logDebug("Off: transition time = ${state.transTime}")
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"on_off":0,"transition_period":${state.transTime}}}}""", "commandResponse")
}

def setLevel(level, rate = null) {
	logDebug("setLevel(x,x): rate = ${rate} // percentage = ${level}")
	level = level.toInteger()
	if (level < 0 || level > 100) {
		logWarn("setLevel: Entered level out of range!")
        return
    }
	if (rate == null) {
		rate = state.transTime.toInteger()
	} else {
		rate = 1000*rate.toInteger()
	}
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"brightness":${level},"transition_period":${rate}}}}""", "commandResponse")
}

def startLevelChange(direction) {
	logDebug("startLevelChange: direction = ${direction}")
	if (direction == "up") { levelUp() }
	else { levelDown() }
}

def stopLevelChange() {
	logDebug("stopLevelChange")
	unschedule(levelUp)
	unschedule(levelDown)
}

def levelUp() {
	def newLevel = device.currentValue("level").toInteger() + 4
	if (newLevel > 101) { return }
	if (newLevel > 100) { newLevel = 100 }
	setLevel(newLevel, 0)
	runIn(1	,levelUp)
}

def levelDown() {
	def newLevel = device.currentValue("level").toInteger() - 4
	if (newLevel < -1) { return }
	else if (newLevel <= 0) { off() }
	else {
		setLevel(newLevel, 0)
		runIn(1, levelDown)
	}
}

def setColorTemperature(kelvin) {
	logDebug("setColorTemperature: colorTemp = ${kelvin}")
	if (kelvin < 2500) kelvin = 2500
	if (kelvin > 9000) kelvin = 9000
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"color_temp": ${kelvin},"hue":0,"saturation":0}}}""", "commandResponse")
}

def setCircadian() {
	logDebug("setCircadian")
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"mode":"circadian"}}}""", "commandResponse")
}

def setHue(hue) {
	logDebug("setHue:  hue = ${hue}")
	setColor([hue: hue])
}

def setSaturation(saturation) {
	logDebug("setSaturation: saturation = ${saturation}")
	setColor([saturation: saturation])
}

def setColor(Map color) {
	logDebug("setColor:  color = ${color}")
	if (color == null) {
		LogWarn("setColor: Color map is null. Command not executed.")
		return
	}
	def level = device.currentValue("level")
	if (color.level) { level = color.level }
	def hue = device.currentValue("hue")
	if (color.hue || color.hue == 0) { hue = color.hue.toInteger() }
	def saturation = device.currentValue("saturation")
	if (color.saturation || color.saturation == 0) { saturation = color.saturation }
	if (highRes != true) {
		hue = Math.round(0.5 + hue * 3.6).toInteger()
	}
	if (hue < 0 || hue > 360 || saturation < 0 || saturation > 100 || level < 0 || level > 100) {
		logWarn("setColor: Entered hue, saturation, or level out of range! (H:${hue}, S:${saturation}, L:${level}")
        return
    }
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":{"ignore_default":1,"on_off":1,"brightness":${level},"color_temp":0,"hue":${hue},"saturation":${saturation}}}}""", "commandResponse")
}

def refresh(){
	logDebug("refresh")
		sendCmd("""{"system":{"get_sysinfo":{}}}""", "statusResponse")
}

def commandResponse(response) {
	def cmdResponse = parseInput(response)
	logDebug("commandResponse: cmdResponse = ${cmdResponse}")
	updateBulbData(cmdResponse["smartlife.iot.smartbulb.lightingservice"].transition_light_state)
}

def statusResponse(response) {
	def cmdResponse = parseInput(response)
	logDebug("statusResponse: cmdResponse = ${cmdResponse}")
	updateBulbData(cmdResponse.system.get_sysinfo.light_state)
}

def updateBulbData(status) {
	logDebug("updateBulbData: ${status}")
	def deviceStatus = [:]
	if (status.on_off == 0) { 
		sendEvent(name: "switch", value: "off", type: "digital")
		sendEvent(name: "circadianState", value: "normal")
		deviceStatus << ["power" : "off"]
	} else {
		sendEvent(name: "switch", value: "on", type: "digital")
		deviceStatus << ["power" : "on"]
		sendEvent(name: "level", value: status.brightness, unit: "%")
		deviceStatus << ["level" : status.brightness]
		sendEvent(name: "circadianState", value: status.mode)
		deviceStatus << ["mode" : status.mode]
		sendEvent(name: "colorTemperature", value: status.color_temp, unit: " K")
		deviceStatus << ["colorTemp" : status.color_temp]
		def hue = status.hue.toInteger()
		if (highRes != true) { hue = (hue / 3.6).toInteger() }
		sendEvent(name: "hue", value: hue)
		deviceStatus << ["hue" : hue]
		sendEvent(name: "saturation", value: status.saturation)
		deviceStatus << ["sat" : status.saturation]
		def color = [:]
		color << ["hue" : hue]
		color << ["saturation" : status.saturation]
		if (status.color_temp.toInteger() > 2000) {
			color << ["level" : 0]
		} else {
			color << ["level" : status.brightness]
		}
		sendEvent(name: "color", value: color)
		if (status.color_temp.toInteger() == 0) { setRgbData(hue, status.saturation) }
		else { setColorTempData(status.color_temp) }
	}
	logInfo("updateBulbData: Status = ${deviceStatus}")
}

def setColorTempData(temp){
	logDebug("setColorTempData: color temperature = ${temp}")
    def value = temp.toInteger()
	state.lastColorTemp = value
    def colorName
	if (value <= 2800) { colorName = "Incandescent" }
	else if (value <= 3300) { colorName = "Soft White" }
	else if (value <= 3500) { colorName = "Warm White" }
	else if (value <= 4150) { colorName = "Moonlight" }
	else if (value <= 5000) { colorName = "Horizon" }
	else if (value <= 5500) { colorName = "Daylight" }
	else if (value <= 6000) { colorName = "Electronic" }
	else if (value <= 6500) { colorName = "Skylight" }
	else { colorName = "Polar" }
	if (device.currentValue("colorMode") == "CT" && device.currentValue("colorName") == colorName) {
		return
	}
	logInfo "${device.getDisplayName()} Color Mode is CT.  Color is ${colorName}."
 	sendEvent(name: "colorMode", value: "CT")
    sendEvent(name: "colorName", value: colorName)
}

def setRgbData(hue, saturation){
	logDebug("setRgbData: hue = ${hue} // highRes = ${highRes}")
	state.lastHue = hue
	state.lastSaturation = saturation
	if (highRes != true) { hue = (hue * 3.6).toInteger() }
    def colorName
	switch (hue){
		case 0..15: colorName = "Red"
            break
		case 16..45: colorName = "Orange"
            break
		case 46..75: colorName = "Yellow"
            break
		case 76..105: colorName = "Chartreuse"
            break
		case 106..135: colorName = "Green"
            break
		case 136..165: colorName = "Spring"
            break
		case 166..195: colorName = "Cyan"
            break
		case 196..225: colorName = "Azure"
            break
		case 226..255: colorName = "Blue"
            break
		case 256..285: colorName = "Violet"
            break
		case 286..315: colorName = "Magenta"
            break
		case 316..345: colorName = "Rose"
            break
		case 346..360: colorName = "Red"
            break
		default:
			logWarn("setRgbData: Unknown.")
			colorName = "Unknown"
    }
	logInfo "${device.getDisplayName()} Color Mode is RGB.  Color is ${colorName}."
 	sendEvent(name: "colorMode", value: "RGB")
    sendEvent(name: "colorName", value: colorName)
}

//	Cloud and Local Common Methods
def setCommsError() {
	logWarn("setCommsError")
	state.errorCount += 1
	if (state.errorCount > 4) {
		return
	} else if (state.errorCount < 3) {
		repeatCommand()
		logInfo("Executing attempt ${state.errorCount} to recover communications")
	} else if (state.errorCount == 3) {
		if (getDataValue("applicationVersion")) {
			logWarn("setCommsError: Attempting to update Kasa Device IPs.")
			parent.requestDataUpdate()
			runIn(30, repeatCommand)
		} else {
			runIn(3, repeatCommand)
			logInfo("Executing attempt ${state.errorCount} to recover communications")
		}
	} else if (state.errorCount == 4) {	
		def warnText = "<b>setCommsError</b>: Your device is not reachable.\r" +
						"Complete corrective action then execute any command to continue"
		logWarn(warnText)
	}
}

def repeatCommand() { 
	logDebug("repeatCommand: ${state.lastCommand}")
	sendCmd(state.lastCommand.command, state.lastCommand.action)
}

def logInfo(msg) {
	if (descriptionText == true) { log.info "${device.label} ${driverVer()} ${msg}" }
}

def debugLogOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("Debug logging is false.")
}

def logDebug(msg){
	if(debug == true) { log.debug "${device.label} ${driverVer()} ${msg}" }
}

def logWarn(msg){ log.warn "${device.label} ${driverVer()} ${msg}" }

//	Local Communications Methods
private sendCmd(command, action) {
	logDebug("sendCmd: command = ${command} // device IP = ${getDataValue("deviceIP")}, action = ${action}")
	state.lastCommand = [command: "${command}", action: "${action}"]
	runIn(3, setCommsError)
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${getDataValue("deviceIP")}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 timeout: 2,
		 callback: action])
	sendHubCommand(myHubAction)
}

def parseInput(response) {
	unschedule(setCommsError)
	state.errorCount = 0
	try {
		return parseJson(inputXOR(parseLanMessage(response).payload))
	} catch (e) {
		logWarn("parseInput: JsonParse failed. Response = ${inputXOR(parseLanMessage(response).payload)}.")
	}
}

private outputXOR(command) {
	def str = ""
	def encrCmd = ""
 	def key = 0xAB
	for (int i = 0; i < command.length(); i++) {
		str = (command.charAt(i) as byte) ^ key
		key = str
		encrCmd += Integer.toHexString(str)
	}
   	return encrCmd
}

private inputXOR(encrResponse) {
	String[] strBytes = encrResponse.split("(?<=\\G.{2})")
	def cmdResponse = ""
	def key = 0xAB
	def nextKey
	byte[] XORtemp
	for(int i = 0; i < strBytes.length; i++) {
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative
		XORtemp = nextKey ^ key
		key = nextKey
		cmdResponse += new String(XORtemp)
	}
	return cmdResponse
}
		
//	end-of-file