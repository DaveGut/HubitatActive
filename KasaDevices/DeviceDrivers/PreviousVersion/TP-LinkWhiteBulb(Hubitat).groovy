/*	Kasa Device Driver Series
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2020 History =====
02.28	New version 5.0
		a.	Changed version number to Ln.n.n format where the L refers to LOCAL installation.
		b.	Moved Quick Polling from preferences to a command with number (seconds) input value.  A value of
			blank or 0 is disabled.  A value below 5 is read as 5.
		c.	Upaded all drivers to eight individual divers.
03.03	Manual install and functional testing complete.  Auto Installation testing complete.
04.08	L5.0.2.  Initial development started for next version:
		a.	Add type to attribute "switch",
		b.	Add 60 and 180 minute refresh rates.  Change default to 60 minutes.
04.20	5.1.0	Update for Hubitat Program Manager
04.23	5.1.1	Update for Hub version 2.2.0, specifically the parseLanMessage = true option.
06.01	5.2.0	Pre-encrypt on, off, and refresh commands to reduce per-commnand processing.
=======================================================================================================*/
def driverVer() { return "A5.2.0" }
metadata {
	definition (name: "Kasa Mono Bulb",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/WhiteBulb.groovy"
			   ) {
        capability "Light"
		capability "Switch"
		capability "Switch Level"
		capability "Change Level"
 		capability "Refresh"
		capability "Actuator"
	}
	preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP", defaultValue: getDataValue("deviceIP"))
		}
		input ("transition_Time", "num", title: "Default Transition time (seconds)", defaultValue: 0)
		input ("refresh_Rate", "enum", title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "10", "15", "30", "60", "180"], defaultValue: "60")
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}


//	Common to all Kasa Bulbs
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

def on() {
	logDebug("On: transition time = ${state.transTime}")
	sendCmd(outputXOR("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":""" +
					  """{"on_off":1,"transition_period":${state.transTime}}}}"""),
			"commandResponse")
}

def off() {
	logDebug("Off: transition time = ${state.transTime}")
	sendCmd(outputXOR("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":""" +
					  """{"on_off":0,"transition_period":${state.transTime}}}}"""),
			"commandResponse")
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
	sendCmd(outputXOR("""{"smartlife.iot.smartbulb.lightingservice":{"transition_light_state":""" +
					  """{"ignore_default":1,"on_off":1,"brightness":${percentage},"transition_period":${rate}}}}"""),
			"commandResponse")
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

def refresh(){
	logDebug("refresh")
		sendCmd("d0f281f88bff9af7d5ef94b6d1b4c09fec95e68fe187e8caf08bf68bf6",
				"statusResponse")
}

def commandResponse(response) {
	def resp = parseInput(response)
	if (resp == "commsError") { return }
	logDebug("commandResponse: cmdResponse = ${resp}")
	updateBulbData(resp["smartlife.iot.smartbulb.lightingservice"].transition_light_state)
}

def statusResponse(response) {
	def resp = parseInput(response)
	if (resp == "commsError") { return }
	logDebug("statusResponse: cmdResponse = ${resp}")
	updateBulbData(resp.system.get_sysinfo.light_state)
}


//	Unique to Kasa Mono Bulb
def updateBulbData(status) {
	logDebug("updateBulbData: ${status}")
	def deviceStatus = [:]
	if (status.on_off == 0) { 
		sendEvent(name: "switch", value: "off", type: "physical")
		deviceStatus << ["power" : "off"]
	} else {
		sendEvent(name: "switch", value: "on", type: "physical")
		deviceStatus << ["power" : "on"]
		sendEvent(name: "level", value: status.brightness, unit: "%")
		deviceStatus << ["level" : status.brightness]
	}
	logInfo("Status: ${deviceStatus}")
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
	logDebug("sendCmd: action = ${action}")
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

def parseInput(response) {
	def resp = parseLanMessage(response)
	if(resp.type != "LAN_TYPE_UDPCLIENT") {
		def errorString = new String(resp.payload.decodeBase64())
		logWarn("parseInput: Response error: ${errorString}. Check device physical status and IP Address.")
		setCommsError()
		return "commsError"
	} else {
		state.errorCount = 0
		try {
			return parseJson(inputXOR(resp.payload))
		} catch (e) {
			logWarn("parseInput: JsonParse failed. Likely fragmented return from device. error = ${e}.")
		}
	}
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
			logWarn("setCommsError: Attempting to update Kasa Device IPs.")
			parent.requestDataUpdate()
			runIn(30, repeatCommand)
		} else {
			runIn(3, repeatCommand)
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