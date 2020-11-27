/*	Kasa Device Driver Series
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2020 History =====
02.28	New version 5.0.  Deprecated with this version
04.20	5.1.0	Update for Hubitat Program Manager
05,17	5.2.0	UDP Comms Update.  Deprecated with this version.
08.01	5.3.0	Major rewrite of LAN communications using rawSocket.  Other edit improvements.
				a.	implemented rawSocket for communications to address UPD errors and
					the issue that Hubitat UDP not supporting Kasa return lengths > 1024.
				b.	Use encrypted version of refresh / quickPoll commands
08.25	5.3.1	Update Error Process to check for IPs on comms error.  Limited to once ever 15 min.
11/27	5.3.3	Fixed error handling to properly cancel quick polling and refresh after 10 errors.
===================================================================================================*/
def driverVer() { return "5.3.3" }

metadata {
	definition (name: "Kasa Plug Switch",
    			namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/Plug-Switch.groovy"
			   ) {
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["off", "5", "10", "15", "20", "25", "30"],
			type: "ENUM"]]
	}

	preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text",
				   title: "Device IP",
				   defaultValue: getDataValue("deviceIP"))
		}
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
		input ("pollTest", "bool", 
			   title: "Enable 5 minute quick poll trace logging", 
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
	state.respLength = 0
	state.response = ""
	state.lastConnect = 0
	state.errorCount = 0
	if (!state.pollInterval) { state.pollInterval = "off" }

	//	Manual installation support.  Get IP and Plug Number
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
	
	//	Update various preferences.
	if (debug == true) { 
		runIn(1800, debugLogOff)
		logInfo("updated: Debug logging enabled for 30 minutes.")
	} else {
		unschedule(debugLogOff)
		logInfo("updated: Debug logging is off.")
	}
	if (pollTest) { 
		runIn(300, pollTestOff)
		logInfo("updated: Poll Testing enabled for 5 minutes")
	} else {
		unschedule(pollTestOff)
		logInfo("updated: Poll Testing is off")
	}
	logInfo("updated: Description text logging is ${descriptionText}.")
	logInfo("updated: ${updateDriverData()}")
	setRefresh()

	refresh()
}

def updateDriverData() {
	//	Version 5.2 to 5.3 updates
	if (getDataValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
		if (state.pollFreq) {
			def interval = state.pollFreq
			if (interval == 0) {
				interval = "off"
			} else if (interval < 5) {
				interval = 5
			} else if (interval > 30) {
				interval = 30
			}
			setPollInterval(interval.toString())
		}
		state.remove("pollFreq")
		pauseExecution(1000)
		state.remove("lastCommand")
		return "Driver data updated to latest values."
	} else {
		return "Driver version and data already correct."
	}
}

def setRefresh() {
	logDebug("setRefresh: pollInterval = ${state.pollInterval}, refreshRate = ${refresh_Rate}")
	if (state.pollInterval != "off") {
		setPollInterval(state.pollInterval)
		return "Preference Refresh is disabled.  Using quickPoll"
	} else {
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
		return "Preference Refresh set for every ${refresh_Rate} minute(s)."
	}
}

def debugLogOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("debugLogOff: Debug logging is off.")
}

def pollTestOff() {
	device.updateSetting("pollTest", [type:"bool", value: false])
	logInfo("pollTestOff: poll testing is off")
}


//	===== Device Command Methods =====
def on() {
	logDebug("on")
	def command = outputXOR("""{"system":{"set_relay_state":{"state":1},""" +
							""""get_sysinfo":{}}}""")
	sendCmd(command)
}

def off() {
	logDebug("off")
	def command = outputXOR("""{"system":{"set_relay_state":{"state":0},""" +
							""""get_sysinfo":{}}}""")
	sendCmd(command)
}

def refresh() {
	logDebug("refresh")
	if (pollTest) { logTrace("Poll Test.  Time = ${now()}") }
	def command = "0000001dd0f281f88bff9af7d5ef94b6d1b4c09" +
		"fec95e68fe187e8caf08bf68bf6"
	sendCmd(command)
}

def setPollInterval(interval) {
	logDebug("setPollInterval: interval = ${interval}")
	if (interval == "off") {
		logInfo("setPollInterval: polling is off")
		state.pollInterval = "off"
		state.remove("WARNING")
		logInfo("setPollInterval: ${setRefresh()}")
	} else {
		interval = interval.toInteger()
		state.pollInterval = interval
		schedule("*/${interval} * * * * ?", refresh)
		logWarn("setPollInterval: polling interval set to ${interval} seconds.\n" +
				"Quick Polling can have negative impact on the Hubitat Hub performance. " +
			    "If you encounter performance problems, try turning off quick polling.")
		state.WARNING = "<b>Quick Polling can have negative impact on the Hubitat " +
						"Hub and network performance.</b>  If you encounter performance " +
				    	"problems, <b>before contacting Hubitat support</b>, turn off quick " +
				    	"polling and check your sysem out."
	}
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
}


//	===== distribute responses =====
def distResp(response) {
	logDebug("distResp: response length = ${response.length()}")
	if (response.length() == null) {
		logDebug("distResp: null return rejected.")
		return 
	}
	
	def resp
	try {
		resp = parseJson(inputXOR(response))
	} catch (e) {
		logWarn("distResp: Invalid or incomplete return.\nerror = ${e}")
		return
	}
	state.errorCount = 0
	unschedule(rawSocketTimeout)
	setSysInfo(resp)
}


//	===== Common Kasa Driver code =====
private sendCmd(command) {
	logDebug("sendCmd")
	runIn(4, rawSocketTimeout, [data: command])
	if (now() - state.lastConnect > 35000 ||
	   device.name == "HS100" || device.name == "HS200") {
		logDebug("sendCmd: Attempting to connect.....")
		try {
			interfaces.rawSocket.connect("${getDataValue("deviceIP")}", 
										 9999, byteInterface: true)
		} catch (error) {
			logDebug("SendCmd: Unable to connect to device at ${getDataValue("deviceIP")}. " +
					 "Error = ${error}")
			if (!getDataValue("applicationVersion")) {
				logWarn("sendCmd:  Check your IP address and device power.")
				return
			}
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
	}
}

def parse(message) {
	def respLength
	if (message.length() > 8 && message.substring(0,4) == "0000") {
		def hexBytes = message.substring(0,8)
		respLength = 8 + 2 * hubitat.helper.HexUtils.hexStringToInt(hexBytes)
		if (message.length() == respLength) {
			distResp(message)
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
			distResp(resp)
		} else {
			state.response = resp
		}
	}
}

def rawSocketTimeout(command) {
	state.errorCount += 1
	if (state.errorCount <= 2) {
		logDebug("rawSocketTimeout: attempt = ${state.errorCount}")
		state.lastConnect = 0
		sendCmd(command)
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

def logWarn(msg){ log.warn "${device.label} ${msg}" }