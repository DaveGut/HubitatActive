/*	Kasa Device Driver Series
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2020 History =====
02.28	New version 5.0.  Moved Quick Polling from preferences to a command with number (seconds)
		input value.  A value of blank or 0 is disabled.  A value below 5 is read as 5.
04.20	5.1.0	Update for Hubitat Program Manager
04.23	5.1.1	Update for Hub version 2.2.0, specifically the parseLanMessage = true option.
06.01	5.2.0	a.	Pre-encrypt refresh / quickPoll commands to reduce per-commnand processing
				b.	Integrated method parseInput into responses and deleted
=======================================================================================================*/
def driverVer() { return "5.2.0" }
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
		command "setPollFreq", ["NUMBER"]
		command "presetLevel",  ["NUMBER"]
	}
    preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP", defaultValue: getDataValue("deviceIP"))
		}
		input ("refresh_Rate", "enum", title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "10", "15", "30", "60", "180"], defaultValue: "60")
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}

def installed() {
	log.info "Installing .."
	state.pollFreq = 0
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
	if (debug == true) { runIn(1800, debugLogOff) }
	logInfo("updated: Debug logging is: ${debug} for 30 minutes.")
	logInfo("updated: Description text logging is ${descriptionText}.")
	refresh()
}


//	Common to all Kasa single Plugs
def on() {
	logDebug("on")
	sendCmd(outputXOR("""{"system":{"set_relay_state":{"state":1}},""" +
					  """"system" :{"get_sysinfo" :{}}}"""),
			"commandResponse")
}

def off() {
	logDebug("off")
	sendCmd(outputXOR("""{"system":{"set_relay_state":{"state":0}},""" +
					  """"system" :{"get_sysinfo" :{}}}"""),
			"commandResponse")
}

def refresh() {
	logDebug("refresh")
	sendCmd("d0f281f88bff9af7d5ef94b6d1b4c09fec95e68fe187e8caf08bf68bf6",
			"commandResponse")
}

def setPollFreq(interval = 0) {
	interval = interval.toInteger()
	if (interval !=0 && interval < 5) { interval = 5 }
	if (interval != state.pollFreq) {
		state.pollFreq = interval
		refresh()
		logInfo("setPollFreq: interval set to ${interval}")
	} else {
		logWarn("setPollFreq: No change in interval from command.")
	}
}


//	Unique to Kasa Dimming Switch
def setLevel(percentage, transition = null) {
	logDebug("setLevel: level = ${percentage}")
	percentage = percentage.toInteger()
	if (percentage < 0) { percentage = 0 }
	if (percentage > 100) { percentage = 100 }
	percentage = percentage.toInteger()
	sendCmd(outputXOR("""{"system":{"set_relay_state":{"state":1}},""" +
			""""smartlife.iot.dimmer":{"set_brightness":{"brightness":${percentage}}},""" +
			""""system" :{"get_sysinfo" :{}}}"""),
			"commandResponse")
}

def presetLevel(percentage) {
	logDebug("presetLevel: level = ${percentage}")
	percentage = percentage.toInteger()
	if (percentage < 0) { percentage = 0 }
	if (percentage > 100) { percentage = 100 }
	percentage = percentage.toInteger()
	sendCmd(outputXOR("""{"smartlife.iot.dimmer":{"set_brightness":{"brightness":${percentage}}},""" +
			""""system" :{"get_sysinfo" :{}}}"""),
			"commandResponse")
}

def commandResponse(response) {
	def resp = parseLanMessage(response)
	if(resp.type == "LAN_TYPE_UDPCLIENT") {
		state.errorCount = 0
		def status = parseJson(inputXOR(resp.payload)).system.get_sysinfo
		logDebug("commandResponse: status = ${status}")
		def onOff = "on"
		if (status.relay_state == 0 || status.state == 0) { onOff = "off" }
		if (onOff != device.currentValue("switch")) {
			sendEvent(name: "switch", value: onOff, type: "digital")
		}
		if(status.brightness != device.currentValue("level")) {
			sendEvent(name: "level", value: status.brightness)
		}
		logInfo("commandResponse: switch: ${onOff}, level: ${status.brightness}]")
		if (state.pollFreq > 0) {
			runIn(state.pollFreq, quickPoll)
		}
	} else {
		setCommsError()
	}
}

def quickPoll() {
	sendCmd("d0f281f88bff9af7d5ef94b6d1b4c09fec95e68fe187e8caf08bf68bf6",
			"quickPollResponse")
}

def quickPollResponse(response) {
	def resp = parseLanMessage(response)
	if(resp.type == "LAN_TYPE_UDPCLIENT") {
		state.errorCount = 0
		def status = parseJson(inputXOR(resp.payload)).system.get_sysinfo
		def onOff = "on"
		if (status.relay_state == 0 || status.state == 0) { onOff = "off" }
		if (onOff != device.currentValue("switch")) {
			sendEvent(name: "switch", value: onOff, type: "digital")
		}
		if(status.brightness != device.currentValue("level")) {
			sendEvent(name: "level", value: status.brightness)
		}
		logInfo("quickPollResponse: switch: ${onOff}, level: ${status.brightness}]")
		if (state.pollFreq > 0) {
			runIn(state.pollFreq, quickPoll)
		}
	} else {
		setCommsError()
	}
}


//	Common to all Kasa Drivers
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

private sendCmd(command, action) {
	logDebug("sendCmd: command = ${command} // action = ${action}")
	state.lastCommand = [command: "${command}", action: "${action}"]
	sendHubCommand(new hubitat.device.HubAction(
		command,
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${getDataValue("deviceIP")}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 parseWarning: true,
		 timeout: 2,
		 callback: action]
	))
}

def setCommsError() {
	logWarn("setCommsError")
	state.errorCount += 1
	if (state.errorCount > 4) {
		return
	} else if (state.errorCount < 3) {
		repeatCommand()
	} else if (state.errorCount == 3) {
		if (getDataValue("applicationVersion")) {
			logWarn("setCommsError: Commanding parent to check for IP changes.")
			parent.requestDataUpdate()
			runIn(30, repeatCommand)
		} else {
			runIn(3, repeatCommand)
		}
	} else if (state.errorCount == 4) {	
		def warnText = "setCommsError: \n<b>Your device is not reachable. Potential corrective Actions:\r" +
			"a.\tDisable the device if it is no longer powered on.\n" +
			"b.\tRun the Kasa Integration Application and see if the device is on the list.\n" +
			"c.\tIf not on the list of devices, troubleshoot the device using the Kasa App."
		logWarn(warnText)
	}
}

def repeatCommand() { 
	logWarn("repeatCommand: ${state.lastCommand}")
	sendCmd(state.lastCommand.command, state.lastCommand.action)
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