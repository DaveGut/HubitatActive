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
		e.	Added preferences for ledState and Reboot Device.
		f.	Removed option for a manual installation.  With segment selection in App,
			no longer necessary.
	6.0.0.1	Quick fix for not properly creating state.lastCommand in sendLanCmd.
===================================================================================================*/
def driverVer() { return "6.0.0.1" }
metadata {
	definition (name: "Kasa Dimming Switch",
    			namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/DimmingSwitch.groovy"
			   ) {
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		capability "Switch Level"
		command "presetLevel",  ["NUMBER"]
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
		input ("debug", "bool",
			   title: "Enable debug logging", 
			   defaultValue: false)
		input ("descriptionText", "bool", 
			   title: "Enable description text logging", 
			   defaultValue: true)
		input ("bind", "enum",
			   options: ["0": "Unbound from Cloud", "1": "Bound to Cloud"],
			   title: "Kasa Cloud Binding <b>[Caution]</b>")
		input ("ledStatus", "enum",
			   options: ["0": "on", "1": "off"],
			   title: "Led On/Off",
			   defaultValue: "0")
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
	if (debug == true) {
		runIn(1800, debugLogOff)
		logInfo("updated: Debug logging enabled for 30 minutes.")
	}
	logInfo("updated: Description text logging is ${descriptionText}.")

	logInfo("updated: ${getBindState()}.")
	pauseExecution(2000)
	logInfo("updated: LED Off Status is ${ledOnOff()}.")
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

//	===== Device Command Methods =====
def on() {
	logDebug("on")
	sendCmd("""{"system":{"set_relay_state":{"state":1},""" +
			""""get_sysinfo":{}}}""")
}
def off() {
	logDebug("off")
	sendCmd("""{"system":{"set_relay_state":{"state":0},""" +
			""""get_sysinfo":{}}}""")
}
def setLevel(percentage, transition = null) {
	logDebug("setLevel: level = ${percentage}")
	percentage = percentage.toInteger()
	if (percentage < 0) { percentage = 0 }
	if (percentage > 100) { percentage = 100 }
	percentage = percentage.toInteger()
	sendCmd("""{"smartlife.iot.dimmer":{"set_brightness":{"brightness":${percentage}}},""" +
			""""system":{"set_relay_state":{"state":1},"get_sysinfo":{}}}""")
}
def presetLevel(percentage) {
	logDebug("presetLevel: level = ${percentage}")
	percentage = percentage.toInteger()
	if (percentage < 0) { percentage = 0 }
	if (percentage > 100) { percentage = 100 }
	percentage = percentage.toInteger()
	sendCmd("""{"smartlife.iot.dimmer":{"set_brightness":{"brightness":${percentage}}},""" +
			""""system" :{"get_sysinfo" :{}}}""")
}
def refresh() {
	logDebug("refresh")
	sendCmd("""{"system":{"get_sysinfo":{}}}""")
}
def setSysInfo(resp) {
	def status = resp.system.get_sysinfo
	logDebug("setSysInfo: status = ${status}")
	def onOff = "on"
	if (status.relay_state == 0) { onOff = "off" }
	if (onOff != device.currentValue("switch")) {
		sendEvent(name: "switch", value: onOff, type: "digital")
		logInfo("setSysInfo: switch: ${onOff}")
	}
	if (status.brightness != device.currentValue("level")) {
		sendEvent(name: "level", value: status.brightness, type: "digital")
		logInfo("setSysInfo: level: ${status.brightness}")
	}
}

//	===== Kasa Utility Commands =====
def getBindState() {
	sendCmd("""{"cnCloud":{"get_info":{}}}""")
	return "Getting and Updating Bind State"
}
def bindUnbind(bind) {
	logInfo("bindUnbind: updating to ${bind}")
	if (bind == "1") {
		if (!parent || !parent.useKasaCloud) {
			logWarn("bindUnbind: Application must be set to useKasaCloud for binding to work.")
			device.updateSetting("bind", [type:"enum", value: "0"])
		} else {
			sendCmd("""{"cnCloud":{"bind":{"username":"${parent.userName}",""" +
					""""password":"${parent.userPassword}"}},"cnCloud":{"get_info":{}}}""")
		}
	} else {
		if (useCloud) {
			logWarn("bindUnbind: Can't unbind when device is set to useCloud")
			device.updateSetting("bind", [type:"enum", value: "1"])
		} else {
			sendCmd("""{"cnCloud":{"unbind":""},"cnCloud":{"get_info":{}}}""")
		}
	}
}
def setBindUnbind(cmdResp) {
	def binded = cmdResp.cnCloud.get_info.binded.toString()
	if (bind && binded != bind) {
		bindUnbind(bind)
	} else {
		device.updateSetting("bind", [type:"enum", value: binded])
		logInfo("setBindUnbind: Bind status set to ${binded}")
	}
}
def ledOnOff() {
	sendCmd("""{"system":{"set_led_off":{"off":${ledStatus}}}}""")
	return ledStatus
}
def rebootDevice() {
	logInfo("rebootDevice: User Commanded Reboot Device!")
	device.updateSetting("rebootDev", [type:"bool", value: false])
	sendCmd("""{"system":{"reboot":{"delay":1}}}""")
	pauseExecution(10000)
	return "Attempted to reboot device"
}

//	===== distribute responses =====
def distResp(response) {
	if (response.system) {
		if (response.system.get_sysinfo) {
			setSysInfo(response)
		} else if (response.system.reboot) {
			logInfo("distResp: Rebooting device")
		}
	} else if (response.cnCloud) {
		setBindUnbind(response)
	} else if (response.error) {
		logWarn("distResponse: Error = ${response.error}")
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
	runIn(4, rawSocketTimeout, [data: command])
	command = outputXOR(command)
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
