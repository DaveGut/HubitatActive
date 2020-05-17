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
06.01	5.2.0.1	Update to date handling to avoid using state.currDate
=======================================================================================================*/
def driverVer() { return "5.2.0.1" }
metadata {
	definition (name: "Kasa EM Multi Plug",
    			namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/EM-Multi-Plug.groovy"
			   ) {
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		command "setPollFreq", [[name: "Set polling frequency in seconds", type: "NUMBER"]]
		capability "Power Meter"
		capability "Energy Meter"
		attribute "currMonthTotal", "number"
		attribute "currMonthAvg", "number"
		attribute "lastMonthTotal", "number"
		attribute "lastMonthAvg", "number"
	}
    preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP", defaultValue: getDataValue("deviceIP"))
			input ("plug_No", "enum", title: "Plug Number",
				options: ["00", "01", "02", "03", "04", "05"])
		}
		input ("emFunction", "bool", title: "Enable Energy Monitor Function", defaultValue: false)
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
	if (state.currDate) { state.remove("currDate") }
	if (device.currentValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
	}
	if (!getDataValue("applicationVersion")) {
		if (!device_IP || !plug_No) {
			logWarn("updated: Device IP or Plug Number is not set.")
			return
		}
		if (getDataValue("deviceIP") != device_IP.trim()) {
			updateDataValue("deviceIP", device_IP.trim())
			logInfo("updated: Device IP set to ${device_IP.trim()}")
		}
		if (plug_No  != getDataValue("plugNo")) {
			updateDataValue("plugNo", plug_No)
			logInfo("updated: Plug Number set to ${plug_No}")
			sendCmd("""{"system" :{"get_sysinfo" :{}}}""", "getMultiPlugData")
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
	if (emFunction) {
		schedule("0 01 0 * * ?", updateStats)
		runIn(1, updateStats)
		logInfo("updated: Scheduled nightly energy statistics update.")
	}
	runIn(3, refresh)
}


//	Common to Kasa Multi-Plugs
def getMultiPlugData(response) {
	logDebug("getMultiPlugData: plugNo = ${plug_No}")
	def cmdResponse = parseInput(response)
	def plugId = "${cmdResponse.system.get_sysinfo.deviceId}${plug_No}"
	updateDataValue("plugId", plugId)
	logInfo("getMultiPlugData: Plug ID = ${plugId}")
}

def on() {
	logDebug("on")
	sendCmd(outputXOR("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
					  """"system":{"set_relay_state":{"state": 1}},""" +
					  """"system" :{"get_sysinfo" :{}}}"""),
			"commandResponse")
}

def off() {
	logDebug("off")
	sendCmd(outputXOR("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
					  """"system":{"set_relay_state":{"state": 0}},""" +
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


//	Unique to Kasa EM Multiplug
def commandResponse(response) {
	def resp = parseLanMessage(response)
	if(resp.type == "LAN_TYPE_UDPCLIENT") {
		state.errorCount = 0
		def status = parseJson(inputXOR(resp.payload)).system.get_sysinfo.children.find { it.id == getDataValue("plugNo") }
		logDebug("commandResponse: status = ${status}")
		def onOff = "on"
		if (status.state == 0) { onOff = "off" }
		if (onOff != device.currentValue("switch")) {
			sendEvent(name: "switch", value: onOff, type: "digital")
		}
		logInfo("commandResponse: switch: ${onOff}")
		if (!emFunction) {
			if (state.pollFreq > 0) {
				runIn(state.pollFreq, quickPoll)
			}
		} else {
			if (state.pollFreq > 0) {
				runIn(state.pollFreq, powerPoll)
			} else {
				sendCmd(outputXOR("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
								  """"emeter":{"get_realtime":{}}}"""),
						"powerResponse")
			}
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
		def status = parseJson(inputXOR(resp.payload)).system.get_sysinfo.children.find { it.id == getDataValue("plugNo") }
		def onOff = "on"
		if (status.state == 0) { onOff = "off" }
		if (onOff != device.currentValue("switch")) {
			sendEvent(name: "switch", value: onOff, type: "physical")
			logInfo("quickPollResponse: switch: ${onOff}")
		}
		if (state.pollFreq > 0) {
			runIn(state.pollFreq, quickPoll)
		}
	} else {
		setCommsError()
	}
}
	
def powerPoll() {
	sendCmd(outputXOR("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
					  """"emeter":{"get_realtime":{}}}"""),
			"powerPollResponse")
}

def powerPollResponse(response) {
	def resp = parseLanMessage(response)
	if(resp.type == "LAN_TYPE_UDPCLIENT") {
		state.errorCount = 0
		def status = parseJson(inputXOR(resp.payload)).emeter.get_realtime
		def power = status.power
		if (power == null) { power = status.power_mw / 1000 }
		power = (0.5 + Math.round(100*power)/100).toInteger()
		def curPwr = device.currentValue("power").toInteger()
		if (power > curPwr + 5 || power < curPwr - 5) { 
			sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W")
			logInfo("powerPollResponse: power = ${power}")
		}
		if (emFunction && state.pollFreq > 0) {
			runIn(state.pollFreq, powerPoll)
		}
	} else {
		setCommsError()
	}
}

def powerResponse(response) {
	def resp = parseLanMessage(response)
	if(resp.type == "LAN_TYPE_UDPCLIENT") {
		state.errorCount = 0
		def status = parseJson(inputXOR(resp.payload)).emeter.get_realtime
		logDebug("powerResponse: status = ${status}")
		def power = status.power
		if (power == null) { power = status.power_mw / 1000 }
		power = (0.5 + Math.round(100*power)/100).toInteger()
		if (power != device.currentValue("power")) {
			sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W")
			logInfo("setEngrToday: [power: ${power}]")
		}
		def year = new Date().format("yyyy").toInteger()
		sendCmd(outputXOR("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
						  """"emeter":{"get_monthstat":{"year": ${year}}}}"""),
				"setEngrToday")
	} else {
		setCommsError()
	}
}

def setEngrToday(response) {
	def resp = parseLanMessage(response)
	if(resp.type == "LAN_TYPE_UDPCLIENT") {
		state.errorCount = 0
		def cmdResponse = parseJson(inputXOR(resp.payload))
		logDebug("setEngrToday: ${cmdResponse}")
		def month = new Date().format("M").toInteger()
		def data = cmdResponse.emeter.get_monthstat.month_list.find { it.month == month }
		def energyData = data.energy
		if (energyData == null) { energyData = data.energy_wh/1000 }
		energyData -= device.currentValue("currMonthTotal")
		energyData = Math.round(100*energyData)/100
		if (energyData != device.currentValue("energy")) {
			sendEvent(name: "energy", value: energyData, descriptionText: "KiloWatt Hours", unit: "KWH")
			logInfo("setEngrToday: [energy: ${energyData}]")
		}
	} else {
		setCommsError()
	}
}

def updateStats() {
	logDebug("updateStats")
	def year = new Date().format("yyyy").toInteger()
	sendCmd(outputXOR("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
					  """"emeter":{"get_monthstat":{"year": ${year}}}}"""),
			"setThisMonth")
}

def setThisMonth(response) {
	def resp = parseLanMessage(response)
	if(resp.type == "LAN_TYPE_UDPCLIENT") {
		state.errorCount = 0
		def cmdResponse = parseJson(inputXOR(resp.payload))
		logDebug("setThisMonth: cmdResponse = ${cmdResponse}")
		def year = new Date().format("yyyy").toInteger()
		def month = new Date().format("M").toInteger()
		def day = new Date().format("d").toInteger()
		def data = cmdResponse.emeter.get_monthstat.month_list.find { it.month == month }
		def scale = "energy"
		def energyData
		if (data == null) { energyData = 0 }
		else {
			if (data.energy == null) { scale = "energy_wh" }
			energyData = data."${scale}"
		}
		def avgEnergy = 0
		if (day !=1) { avgEnergy = energyData/(day - 1) }
		if (scale == "energy_wh") {
			energyData = energyData/1000
			avgEnergy = avgEnergy/1000
		}
		energyData = Math.round(100*energyData)/100
		avgEnergy = Math.round(100*avgEnergy)/100
		sendEvent(name: "currMonthTotal", value: energyData, descriptionText: "KiloWatt Hours", unit: "KWH")
		sendEvent(name: "currMonthAvg", value: avgEnergy, descriptionText: "KiloWatt Hours per Day", unit: "KWH/D")
		logInfo("This month's energy stats set to ${energyData} // ${avgEnergy}")
		if (month == 1) { year = year -1 }
		sendCmd(outputXOR("""{"context":{"child_ids":["${getDataValue("plugId")}"]},"emeter":""" +
						  """{"get_monthstat":{"year": ${year}}}}"""),
				"setLastMonth")
	} else {
		setCommsError()
	}
}

def setLastMonth(response) {
	def resp = parseLanMessage(response)
	if(resp.type == "LAN_TYPE_UDPCLIENT") {
		state.errorCount = 0
		def cmdResponse = parseJson(inputXOR(resp.payload))
		logDebug("setLastMonth: cmdResponse = ${cmdResponse}")
		def year = new Date().format("yyyy").toInteger()
		def month = new Date().format("M").toInteger()
		def lastMonth = month - 1
		if (lastMonth == 0) { lastMonth = 12 }
		def monthLength
		switch(lastMonth) {
			case 4:
			case 6:
			case 9:
			case 11:
				monthLength = 30
				break
			case 2:
				monthLength = 28
				if (year == 2020 || year == 2024 || year == 2028) { monthLength = 29 }
				break
			default:
				monthLength = 31
		}
		def data = cmdResponse.emeter.get_monthstat.month_list.find { it.month == lastMonth }
		def scale = "energy"
		def energyData
		if (data == null) { energyData = 0 }
		else {
			if (data.energy == null) { scale = "energy_wh" }
			energyData = data."${scale}"
		}
		def avgEnergy = energyData/monthLength
		if (scale == "energy_wh") {
			energyData = energyData/1000
			avgEnergy = avgEnergy/1000
		}
		energyData = Math.round(100*energyData)/100
		avgEnergy = Math.round(100*avgEnergy)/100
		sendEvent(name: "lastMonthTotal", value: energyData, descriptionText: "KiloWatt Hours", unit: "KWH")
		sendEvent(name: "lastMonthAvg", value: avgEnergy, descriptionText: "KiloWatt Hoursper Day", unit: "KWH/D")
		logInfo("Last month's energy stats set to ${energyData} // ${avgEnergy}")
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