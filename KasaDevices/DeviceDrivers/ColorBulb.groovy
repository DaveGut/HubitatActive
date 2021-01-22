/*	Kasa Device Driver Series
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2021 History =====
01-25	Version 6.0.0 Update.  Combine Cloud and LAN Driver code to one driver/app set.
		a.	Add Preferece useCloud.  When true, all communications will be via the Kasa
			cloud, using methods embedded in the application.
			1.	Will not be availalbe based on the following application-set dataValues
				a.	"appServerUrl" is not in the data section
				b.	"boundToKasa" is false
			2.	When true, refresh/polling interval is limited to a minimum of 1 minute.
		b.	Removed quick poll command and merged functions into preference refresh interval.
		c.	Removed preference pollTest.
		d.	Modified options for preference refreshInterval.
		e.	Added preferences for Reboot Device.
		f.	Removed option for a manual installation.  With segment selection in App,
			no longer necessary.
===================================================================================================*/
def driverVer() { return "6.0.0" }
metadata {
	definition (name: "Kasa Color Bulb",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/ColorBulb.groovy"
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
		if (getDataValue("appServerUrl")) {
			input ("useCloud", "bool",
			   title: "Use Kasa Cloud for device control",
			   description: "(Must be bound to Kasa Cloud)",
			   defaultValue: false)
		}
		def refreshIntervals = ["5": "5 seconds", "10": "10 seconds", "15": "15 seconds",
								"20": "20 seconds", "25": "25 seconds",
								"30": "30 seconds", "60": "1 minute", "300": "5 minutes"]
		input ("refreshInterval", "enum",
			   title: "Refresh / Poll Interval",
			   options: refreshIntervals,
			   defaultValue: "300")
		input ("transition_Time", "num", 
			   title: "Default Transition time (seconds)", 
			   defaultValue: 0)
		input ("highRes", "bool", 
			   title: "(Color Bulb) High Resolution Hue Scale", 
			   defaultValue: false)
		input ("debug", "bool", 
			   title: "Enable debug logging", 
			   defaultValue: false)
		input ("descriptionText", "bool", 
			   title: "Enable description text logging", 
			   defaultValue: true)
		input ("bind", "enum",
			   options: ["0": "Unbound from Cloud", "1": "Bound to Cloud"],
			   title: "Kasa Cloud Binding <b>[Caution]</b>")
		input ("rebootDev", "bool",
			   title: "Reboot device <b>[Caution]</b>",
			   defaultValue: false)
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
	if (rebootDev) {
		logInfo("updated: ${rebootDevice()}")
	}
	
	//	Set cloud to false if no appServerUrl, true if no deviceIp
	if (!getDataValue("appServerUrl")) {
		device.updateSetting("useCloud", [type:"bool", value: false])
	} else if (!getDataValue("deviceIP")){
		device.updateSetting("useCloud", [type:"bool", value: true])
	}
	logInfo("updated: useCloud set to ${useCloud}")
	if (useCloud && bind == "1") {
		state.remove("respLength")
		state.remove("response")
		state.remove("lastConnect")
		state.remove("errorCount")
	} else if (useCloud && bind == "0") {
		logWarn("updated: useCloud not available if not bound to Kasa Cloud.")
		device.updateSetting("useCloud", [type:"bool", value: "false"])
		state.respLength = 0
		state.response = ""
		state.lastConnect = 0
		state.errorCount = 0
	} else {
		state.respLength = 0
		state.response = ""
		state.lastConnect = 0
		state.errorCount = 0
	}

	logInfo("updated: ${updateDriverData()}")
	state.transTime = 1000*transition_Time.toInteger()
	logInfo("updated: transition time set to ${transition_Time} seconds.")
	if (debug == true) { 
		runIn(1800, debugLogOff)
		logInfo("updated: Debug logging enabled for 30 minutes.")
	}
	logInfo("updated: Description text logging is ${descriptionText}.")

	logInfo("updated: ${getBindState()}.")
	pauseExecution(2000)
	logInfo("updated: ${setPollInterval()}")

	refresh()
}
def updateDriverData() {
	if (getDataValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
		state.remove("WARNING")
		if (state.pollInterval && state.pollInterval != "off") {
			interval = state.pollInterval.toInteger()
			message += "\n\t\t\t\tCapturing existing poll interval of ${interval}."
			device.updateSetting("refreshInterval", 
								 [type:"enum", 
								  value: interval.toString()])
			state.remove("pollInterval")	
		}
		return "Driver data updated to latest values."
	} else {
		return "Driver version and data already correct."
	}
}
def setPollInterval() {
	def message = "Setting poll interval."
	def interval = refreshInterval.toInteger()
	if (useCloud) {
		if (interval < 60) {
			interval = 60
			device.updateSetting("refreshInterval", [type:"enum", value: "60"])
			message += "\n\t\t\t\tuseCloud is true, refreshInterval set to 1 minute minimum."
		}
	}
	if (interval < 60) {
		schedule("*/${interval} * * * * ?", refresh)
		message += "\n\t\t\t\tPoll interval set to ${interval} seconds."
	} else {
		interval = (interval/60).toInteger()
		schedule("0 */${interval} * * * ?", refresh)
		message += "\n\t\t\t\tPoll interval set to ${interval} minutes."
	}
	return message
}

//	===== Command Methods =====
def on() {
	logDebug("on: transition time = ${state.transTime}")
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":""" +
			"""{"transition_light_state":{"on_off":1,"transition_period":${state.transTime}}}}""")
}
def off() {
	logDebug("off: transition time = ${state.transTime}")
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":""" +
			"""{"transition_light_state":{"on_off":0,"transition_period":${state.transTime}}}}""")
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
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":""" +
			"""{"transition_light_state":{"ignore_default":1,"on_off":1,""" +
			""""brightness":${percentage},"transition_period":${rate}}}}""")
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
def refresh() {
	logDebug("refresh")
	sendCmd("""{"system":{"get_sysinfo":{}}}""")
}
def setColorTemperature(kelvin) {
	logDebug("setColorTemperature: colorTemp = ${kelvin}")
	if (kelvin < 2500) { kelvin = 2500 }
	if (kelvin > 9000) { kelvin = 9000 }
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":""" +
			"""{"ignore_default":1,"on_off":1,"color_temp": ${kelvin},"hue":0,"saturation":0}}}""")
}
def setCircadian() {
	logDebug("setCircadian")
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":""" +
			"""{"transition_light_state":{"mode":"circadian"}}}""")
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
	sendCmd("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":""" +
			"""{"ignore_default":1,"on_off":1,"brightness":${level},"color_temp":0,""" +
			""""hue":${hue},"saturation":${saturation}}}}""")
}
def updateBulbData(status) {
	logDebug("updateBulbData: ${status}")
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

//	===== Kasa Utility Commands =====
def getBindState() {
	sendCmd("""{"smartlife.iot.common.cloud":{"get_info":{}}}""")
	return "Getting and Updating Bind State"
}
def bindUnbind(bind) {
	logInfo("bindUnbind: updating to ${bind}")
	if (bind == "1") {
		if (!parent || !parent.useKasaCloud) {
			logWarn("bindUnbind: Application must be set to useKasaCloud for binding to work.")
			device.updateSetting("bind", [type:"enum", value: "0"])
		} else {
			sendCmd("""{"smartlife.iot.common.cloud":{"bind":{"username":"${parent.userName}",""" +
					""""password":"${parent.userPassword}"}},""" +
					""""smartlife.iot.common.cloud":{"get_info":{}}}""")
		}
	} else {
		if (useCloud) {
			logWarn("bindUnbind: Can't unbind when device is set to useCloud")
			device.updateSetting("bind", [type:"enum", value: "1"])
		} else {
			sendCmd("""{"smartlife.iot.common.cloud":{"unbind":""},""" +
					""""smartlife.iot.common.cloud":{"get_info":{}}}""")
		}
	}
}
def setBindUnbind(cmdResp) {
	def binded = cmdResp["smartlife.iot.common.cloud"].get_info.binded.toString()
	if (bind && binded != bind) {
		bindUnbind(bind)
	} else {
		device.updateSetting("bind", [type:"enum", value: binded])
		logInfo("setBindUnbind: Bind status set to ${binded}")
	}
}
def rebootDevice() {
	logInfo("rebootDevice: User Commanded Reboot Device!")
	device.updateSetting("rebootDev", [type:"bool", value: false])
	sendCmd("""{"smartlife.iot.common.system":{"reboot":{"delay":1}}}""")
	pauseExecution(10000)
	return "Attempted to reboot device"
}

//	===== distribute responses =====
def distResp(response) {
	if (response["smartlife.iot.smartbulb.lightingservice"]) {
		updateBulbData(response["smartlife.iot.smartbulb.lightingservice"].transition_light_state)
	} else if (response.system) {
		updateBulbData(response.system.get_sysinfo.light_state)
	} else if (response["smartlife.iot.common.cloud"]) {
		setBindUnbind(response)
	} else if (response["smartlife.iot.common.system"]) {
		logInfo("distResp: Rebooting device")
	} else if (response.error) {
		logWarn("distResp: Error = ${response.error}")
	} else {
		logWarn("distResp: Unhandled response = ${response}")
	}
}

private sendCmd(command) {
	if (!useCloud) { sendLanCmd(command) }
	else { sendKasaCmd(command) }
}

//	===== LAN Communications Code =====
private sendLanCmd(command) {
	logDebug("sendLanCmd: ${command}")
	command = outputXOR(command)
	runIn(4, rawSocketTimeout, [data: command])
	if (now() - state.lastConnect > 35000 ||
	   device.name == "HS100" || device.name == "HS200") {
		logDebug("sendLanCmd: Attempting to connect.....")
		try {
			interfaces.rawSocket.connect("${getDataValue("deviceIP")}", 
										 9999, byteInterface: true)
		} catch (error) {
			logDebug("SendCmd: Unable to connect to device at ${getDataValue("deviceIP")}. " +
					 "Error = ${error}")
			def pollEnabled = parent.pollForIps()
			if (pollEnabled == true) {
				logDebug("SendCmd: Attempting to update IP address.")
				runIn(10, rawSocketTimeout, [data: command])
			} else {
				logWarn("SendCmd: IP address updat attempted within last hour./n" + 
					    "Check your device. Disable if not longer in use.")
			}
			return
		}
	}
	interfaces.rawSocket.sendMessage(command)
}
def socketStatus(message) {
	if (message == "receive error: Stream closed.") {
		logDebug("socketStatus: Socket Established")
	} else {
		logWarn("socketStatus = ${message}")
		logWarn("Check: Device Name must be first 5 characters of Model (i.e., HS200).")
	}
}
def parse(message) {
	def respLength
	if (message.length() > 8 && message.substring(0,4) == "0000") {
		def hexBytes = message.substring(0,8)
		respLength = 8 + 2 * hubitat.helper.HexUtils.hexStringToInt(hexBytes)
		if (message.length() == respLength) {
			prepResponse(message)
			state.lastConnect = now()
		} else {
			state.response = message
			state.respLength = respLength
		}
	} else {
		def resp = state.response
		resp = resp.concat(message)
		if (resp.length() == state.respLength) {
			state.response = ""
			state.respLength = 0
			state.lastConnect = now()
			prepResponse(resp)
		} else {
			state.response = resp
		}
	}
}
def prepResponse(response) {
	logDebug("prepResponse: response length = ${response.length()}")
	if (response.length() == null) {
		logDebug("distResp: null return rejected.")
		return 
	}
	def resp
	try {
		resp = parseJson(inputXOR(response))
	} catch (e) {
		resp = ["error": "Invalid or incomplete return. Error = ${e}"]
	}
	state.errorCount = 0
	unschedule(rawSocketTimeout)
	distResp(resp)
}
def rawSocketTimeout(command) {
	state.errorCount += 1
	if (state.errorCount <= 2) {
		logDebug("rawSocketTimeout: attempt = ${state.errorCount}")
		state.lastConnect = 0
		sendLanCmd(command)
	} else {
		logWarn("rawSocketTimeout: Retry on error limit exceeded. Error " +
				"count = ${state.errorCount}.  If persistant try SavePreferences.")
		if (state.errorCount > 10) {
			unschedule(quickPoll)
			unschedule(refresh)
			logWarn("rawSocketTimeout: Quick Poll and Refresh Disabled.")
		}
	}
}

//	===== Cloud Communications Code =====
private sendKasaCmd(command) {
	logDebug("sendKasaCmd: ${command}")
	def appServerUrl = getDataValue("appServerUrl")
	def deviceId = getDataValue("deviceId")
	def cmdResponse = parent.sendKasaCmd(appServerUrl, deviceId, command)
	distResp(cmdResponse)
}

//	-- Encryption / Decryption
private outputXOR(command) {
	def str = ""
	def encrCmd = "000000" + Integer.toHexString(command.length()) 
 	def key = 0xAB
	for (int i = 0; i < command.length(); i++) {
		str = (command.charAt(i) as byte) ^ key
		key = str
		encrCmd += Integer.toHexString(str)
	}
   	return encrCmd
}
private inputXOR(resp) {
	String[] strBytes = resp.substring(8).split("(?<=\\G.{2})")
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
//	 ===== Logging =====
def logTrace(msg){ log.trace "${device.label} ${msg}" }
def logInfo(msg) {
	if (descriptionText == true) { log.info "${device.label} ${msg}" }
}
def logDebug(msg){
	if(debug == true) { log.debug "${device.label} ${msg}" }
}
def debugLogOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("debugLogOff: Debug logging is off.")
}
def logWarn(msg){ log.warn "${device.label} ${msg}" }
