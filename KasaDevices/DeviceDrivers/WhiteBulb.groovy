/*	Kasa Device Driver Series
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2020 History =====
02.28	New version 5.0.
04.20	5.1.0	Update for Hubitat Program Manager
04.23	5.1.1	Update for Hub version 2.2.0, specifically the parseLanMessage = true option.
05.16	5.2.0	a.	Pre-encrypt refresh commands to reduce per-commnand processing
				b.	Fixed fragmented return failure in method Status Reponse
				c.	Integrated method parseInput into responses and deleted
=======================================================================================================*/
def driverVer() { return "5.2.0" }
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
	def resp = parseLanMessage(response)
	if(resp.type == "LAN_TYPE_UDPCLIENT") {
		state.errorCount = 0
		resp = parseJson(inputXOR(resp.payload))
		logDebug("commandResponse: cmdResponse = ${resp}")
		updateBulbData(resp["smartlife.iot.smartbulb.lightingservice"].transition_light_state)
	} else {
		setCommsError()
	}
}

def statusResponse(response) {
	def resp = parseLanMessage(response)
	if(resp.type == "LAN_TYPE_UDPCLIENT") {
		state.errorCount = 0
		resp = inputXOR(resp.payload)
		if (resp.length() >= 1023) {
			resp = resp.substring(0,resp.indexOf("preferred")-2) + "}}}"
		}
		resp = parseJson(resp)
		logDebug("statusResponse: cmdResponse = ${resp}")
		updateBulbData(resp.system.get_sysinfo.light_state)
	} else {
		setCommsError()
	}
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