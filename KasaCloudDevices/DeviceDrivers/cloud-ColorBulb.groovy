/*	Kasa Cloud Device Driver Series
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md

This is a special cloud version for devices that have had firmware updates that disabled port 9999.
===== 2020 History =====
11.27	1.0.0	Initial release of cloud version.

===================================================================================================*/
def driverVer() { return "1.0.0" }
metadata {
	definition (name: "Kasa Cloud Color Bulb",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaCloudDevices/DeviceDrivers/cloud-ColorBulb.groovy"
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
		input ("transition_Time", "num", 
			   title: "Default Transition time (seconds)", 
			   defaultValue: 0)
		input ("highRes", "bool", 
			   title: "(Color Bulb) High Resolution Hue Scale", 
			   defaultValue: false)
		input ("refresh_Rate", "enum", 
			   title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "10", "15", "30", "60", "180"], 
			   defaultValue: "60")
		input ("debug", "bool", 
			   title: "Enable debug logging", 
			   defaultValue: false)
		input ("descriptionText", "bool", 
			   title: "Enable description text logging", 
			   defaultValue: true)
	}
}
def installed() {
	logInfo("Installing Device....")
	runIn(2, updated)
}
//	===== Updated and associated methods =====
def updated() {
	logInfo("Updating device preferences....")
	unschedule()
	//	Update various preferences.
	if (debug == true) { 
		runIn(1800, debugLogOff)
		logInfo("updated: Debug logging enabled for 30 minutes.")
	} else {
		unschedule(debugLogOff)
		logInfo("updated: Debug logging is off.")
	}
	logInfo("updated: Description text logging is ${descriptionText}.")
	state.transTime = 1000*transition_Time.toInteger()
	logInfo("updated: Light transition time set to ${transition_Time} seconds.")
	updateDataValue("driverVersion", driverVer())
	switch(refresh_Rate) {
		case "1" : runEvery1Minute(refresh); break
		case "5" : runEvery5Minutes(refresh); break
		case "10" : runEvery10Minutes("refresh"); break
		case "15" : runEvery15Minutes(refresh); break
		case "30" : runEvery30Minutes(refresh); break
		case "180": runEvery3Hours(refresh); break
		default:
			runEvery1Hour(refresh); break
	}

	refresh()
}

//	===== Command Methods =====
def on() {
	logDebug("on: transition time = ${state.transTime}")
	def command = """{"smartlife.iot.smartbulb.lightingservice":""" +
		"""{"transition_light_state":{"on_off":1,"transition_period":${state.transTime}}}}"""
	sendCmd(command, "setBulbData")
}

def off() {
	logDebug("off: transition time = ${state.transTime}")
	def command = """{"smartlife.iot.smartbulb.lightingservice":""" +
		"""{"transition_light_state":{"on_off":0,"transition_period":${state.transTime}}}}"""
	sendCmd(command, "setBulbData")
}

def setLevel(percentage, rate = null) {
	logDebug("setLevel(x,x): rate = ${rate} // percentage = ${percentage}")
	if (percentage < 0) { percentage = 0 }
	else if (percentage > 100) { percentage = 100 }
	if (rate == null) {
		rate = state.transTime.toInteger()
	} else {
		rate = 1000*rate.toInteger()
	}
	def command = """{"smartlife.iot.smartbulb.lightingservice":""" +
		"""{"transition_light_state":{"ignore_default":1,"on_off":1,""" +
		""""brightness":${percentage},"transition_period":${rate}}}}"""
	sendCmd(command, "setBulbData")
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
	def curLevel = device.currentValue("level").toInteger()
	if (curLevel == 100) { return }
	def newLevel = curLevel + 4
	if (newLevel > 100) { newLevel = 100 }
	setLevel(newLevel, 0)
	runIn(1, levelUp)
}

def levelDown() {
	def curLevel = device.currentValue("level").toInteger()
	if (curLevel == 0) { return }
	def newLevel = curLevel - 4
	if (newLevel < 0) { newLevel = 0 }
	setLevel(newLevel, 0)
	if (newLevel == 0) { off() }
	runIn(1, levelDown)
}

def refresh(){
	logDebug("refresh")
	def command = """{"system":{"get_sysinfo":{}}}"""
	sendCmd(command, "setBulbData")
}

def setColorTemperature(kelvin) {
	logDebug("setColorTemperature: colorTemp = ${kelvin}")
	if (kelvin < 2500) { kelvin = 2500 }
	if (kelvin > 9000) { kelvin = 9000 }
	def command = """{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":""" +
		"""{"ignore_default":1,"on_off":1,"color_temp": ${kelvin},"hue":0,"saturation":0}}}"""
	sendCmd(command, "setBulbData")
}

def setCircadian() {
	logDebug("setCircadian")
	def command = """{"smartlife.iot.smartbulb.lightingservice":""" +
		"""{"transition_light_state":{"mode":"circadian"}}}"""
	sendCmd(command, "setBulbData")
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
		hue = Math.round(0.49 + hue * 3.6).toInteger()
	}
	if (hue < 0 || hue > 360 || saturation < 0 || saturation > 100 || level < 0 || level > 100) {
		logWarn("setColor: Entered hue, saturation, or level out of range! (H:${hue}, S:${saturation}, L:${level}")
        return
    }
	def command = """{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":""" +
		"""{"ignore_default":1,"on_off":1,"brightness":${level},"color_temp":0,""" +
		""""hue":${hue},"saturation":${saturation}}}}"""
	sendCmd(command, "setBulbData")
}

def setBulbData(resp) {
	def status
	if (resp["smartlife.iot.smartbulb.lightingservice"]) {
		status = resp["smartlife.iot.smartbulb.lightingservice"].transition_light_state
	} else {
		status = resp.system.get_sysinfo.light_state
	}
	logDebug("setBulbData: ${status}")
	def deviceStatus = [:]
	def type = getDataValue("devType")
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
		if (status.color_temp.toInteger() == 0) { 
			setRgbData(hue, status.saturation) }
		else { 
			setColorTempData(status.color_temp) 
		}
	}
	logInfo("setBulbData: Status = ${deviceStatus}")
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
	//	Color Bulb Only.
	if (device.currentValue("colorMode") != "CT") {
 		sendEvent(name: "colorMode", value: "CT")
		logInfo("setColorTempData: Color Mode is CT")
	}
	if (device.currentValue("colorName") != colorName) {
	    sendEvent(name: "colorName", value: colorName)
		logInfo("setColorTempData: Color name is ${colorName}.")
	}
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
	if (device.currentValue("colorMode") != "RGB") {
 		sendEvent(name: "colorMode", value: "RGB")
		logInfo("setRgbData: Color Mode is RGB")
	}
	if (device.currentValue("colorName") != colorName) {
	    sendEvent(name: "colorName", value: colorName)
		logInfo("setRgbData: Color name is ${colorName}.")
	}
}

//	===== Common Kasa Driver code =====
private sendCmd(command, action) {
	logDebug("sendCmd")
	def appServerUrl = getDataValue("appServerUrl")
	def deviceId = getDataValue("deviceId")
	def cmdResponse = parent.sendDeviceCmd(appServerUrl, deviceId, command)
	String cmdResp = cmdResponse.toString()
	if (cmdResp.substring(0,5) == "ERROR"){
		def errMsg = cmdResp.substring(7,cmdResp.length())
		log.error "${device.label}: ${errMsg}"
		sendEvent(name: "switch", value: "unavailable", descriptionText: errMsg)
		sendEvent(name: "deviceError", value: errMsg)
		action = ""
	} else {
		sendEvent(name: "deviceError", value: "OK")
	}
	if (action == "setBulbData") { setBulbData(cmdResponse) }
	else { logError("sendCmd: Error.  Invalid action") }
}

//	 ===== Logging =====
//	 ===== Logging =====
def logTrace(msg){ log.trace "Cloud ${device.label} ${msg}" }
def logInfo(msg) {
	if (descriptionText == true) { log.info "Cloud ${device.label} ${msg}" }
}
def debugLogOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("debugLogOff: Debug logging is off.")
}
def logDebug(msg){
	if(debug == true) { log.debug "Cloud ${device.label} ${msg}" }
}
def logWarn(msg){ log.warn "Cloud ${device.label} ${msg}" }