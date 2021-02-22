/*	Kasa Device Driver Series
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2021 History =====
01-25	6.0.0 Update.  Combine Cloud and LAN Driver code to one driver/app set.
02-01	6.1.0	a.	Combined driver files for ease maintenance.
				b.	Recreated setPollInterval (erroneously left out of 6.0).
				c.	Moved cloud comms to within driver / device.
02-12	6.1.0.1	Quick fix for ledOnOff missing in updated.
02-13	6.1.0.2	Quick fix for file name
===================================================================================================*/
def driverVer() { return "6.1.0.2" }
def type() { return "Multi Plug" }
//def type() { return "EM Multi Plug" }
def file = type().replaceAll(" ", "-")

metadata {
	definition (name: "Kasa ${type()}",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/${file}.groovy"
			   ) {
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["off", "5", "10", "15", "20", "30"],
			type: "ENUM"]]
		if (type() == "EM Multi Plug") {
			capability "Power Meter"
			capability "Energy Meter"
			attribute "currMonthTotal", "number"
			attribute "currMonthAvg", "number"
			attribute "lastMonthTotal", "number"
			attribute "lastMonthAvg", "number"
		}
	}

	preferences {
		if (getDataValue("appServerUrl")) {
			input ("useCloud", "bool",
				   title: "Use Kasa Cloud for device control",
				   defaultValue: false)
		}
		def refreshIntervals = ["60": "1 minute", "300": "5 minutes", 
								"900": "15 minutes", "1800": "30 minutes"]
		input ("refreshInterval", "enum",
			   title: "Refresh / Poll Interval",
			   options: refreshIntervals,
			   defaultValue: "1800")
		if (type() == "EM Multi Plug") {
			input ("emFunction", "bool", 
				   title: "Enable Energy Monitor", 
				   defaultValue: false)
		}
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
}	//	plug version
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
	if (!state.pollInterval) { state.pollInterval = "off" }
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
	ledOnOff()
	pauseExecution(2000)
	def interval = "1800"
	if (refreshInterval) {
		interval = refreshInterval
	} else {
		device.updateSetting("refreshInterval", [type:"enum", value: "1800"])
	}
	logInfo("updated: ${setInterval(interval)}")
	//	Energy Monitor startup
	if (emFunction) {
		pauseExecution(1000)
		sendEvent(name: "power", value: 0, descriptionText: "Watts", unit: "W")
		schedule("0 01 0 * * ?", updateEmStats)
		runEvery30Minutes(getEnergyToday)
		runIn(1, getEnergyToday)
		runIn(2, updateEmStats)
		logInfo("updated: Energy Monitor Function enabled.")
	}
	refresh()
}	//	plug version
def updateDriverData() {
	if (getDataValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
		removeDataValue("emSysInfo")
		pauseExecution(1000)
		removeDataValue("getPwr")
		pauseExecution(1000)
		state.remove("warning")
		return "Driver data updated to latest values."
	} else {
		return "Driver version and data already correct."
	}
}	//	plug version
def setPollInterval(interval) {
	if (interval == "off") {
		state.remove("WARNING")
	} else {
		logWarn("setPollInterval: polling interval set to ${interval} seconds.\n" +
				"Quick Polling can have negative impact on the Hubitat Hub performance. " +
			    "If you encounter performance problems, try turning off quick polling.")
		state.WARNING = "<b>Quick Polling can have negative impact on the Hubitat " +
			"Hub performance. If you encounter performance problems, try turning " +
			"off quick polling."
	}
	state.pollInterval = interval
	setInterval(interval)
}	//	plug only
def setInterval(interval) {
	if (state.pollInterval != "off") {
		interval = state.pollInterval
	} else if (state.pollInterval == "off") {
		interval = refreshInterval
	}
	interval = interval.toInteger()
	def message = "Setting poll interval."
	if (useCloud) {
		if (interval < 60) {
			interval = 60
			message += "\n\t\t\t\tuseCloud is true, refreshInterval set to 1 minute minimum."
			state.pollInterval = "off"
		}
	}
	if (interval < 60) {
		schedule("*/${interval} * * * * ?", refresh)
		message += "\n\t\t\t\tPoll interval set to ${interval} seconds."
	} else {
		def minInterval = (	interval/60).toInteger()
		schedule("0 */${minInterval} * * * ?", refresh)
		message += "\n\t\t\t\tPoll interval set to ${minInterval} minutes."
	}
	logDebug("setInterval: ${message}")
	return message

}	//	plug version

//	===== Device Command Methods =====
def on() {
	logDebug("on")
	if (emFunction) {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""system":{"set_relay_state":{"state":1},""" +
				""""get_sysinfo":{}},""" +
				""""emeter":{"get_realtime":{}}}""")
	} else {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""system":{"set_relay_state":{"state":1},""" +
				""""get_sysinfo":{}}}""")
	}
}
def off() {
	logDebug("off")
	if (emFunction) {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""system":{"set_relay_state":{"state":0},""" +
				""""get_sysinfo":{}},""" +
				""""emeter":{"get_realtime":{}}}""")
	} else {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""system":{"set_relay_state":{"state":0},""" +
				""""get_sysinfo":{}}}""")
	}
}
def refresh() {
	logDebug("refresh")
	sendCmd("""{"system":{"get_sysinfo":{}}}""")
	if (emFunction) { getPower() }
}
def setSysInfo(resp) {
	def status = resp.system.get_sysinfo
	status = status.children.find { it.id == getDataValue("plugId") }
	logDebug("setSysInfo: status = ${status}")
	def onOff = "on"
	if (status.state == 0) { onOff = "off" }
	if (onOff != device.currentValue("switch")) {
		sendEvent(name: "switch", value: onOff, type: "digital")
		logInfo("setSysInfo: switch: ${onOff}")
	}
	if (resp.emeter) { setPower(resp.emeter.get_realtime) }
}

//	===== Device Energy Monitor Methods =====
def getPower() {
	logDebug("getPower")
	sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
			""""emeter":{"get_realtime":{}}}""")
}
def setPower(resp) {
	logDebug("setPower: status = ${resp}")
	def power = resp.power
	if (power == null) { power = resp.power_mw / 1000 }
	power = (0.5 + Math.round(100*power)/100).toInteger()
	def curPwr = device.currentValue("power").toInteger()
	if (power > curPwr + 1 || power < curPwr - 1) { 
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W")
		logInfo("pollResp: power = ${power}")
	}
}

def getEnergyToday() {
	def year = new Date().format("yyyy").toInteger()
	def month = new Date().format("M").toInteger()
	sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
			""""emeter":{"get_daystat":{"month": ${month}, "year": ${year}}}}""")
}
def setEnergyToday(resp) {
	logDebug("setEnergyToday: ${resp}")
	def day = new Date().format("d").toInteger()
	def data = resp.day_list.find { it.day == day }
	def energyData
	if (data == null) {
		energyData = 0
	} else {
		energyData = data.energy
		if (energyData == null) { energyData = data.energy_wh/1000 }
	}
	energyData = Math.round(100*energyData)/100
	if (energyData != device.currentValue("energy")) {
		sendEvent(name: "energy", value: energyData, descriptionText: "KiloWatt Hours", unit: "kWH")
		logInfo("setEngrToday: [energy: ${energyData}]")
	}
}

def updateEmStats() {
	logDebug("updateEmStats: Updating daily energy monitor data.")
	def year = new Date().format("yyyy").toInteger()
	sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
			""""emeter":{"get_monthstat":{"year": ${year}}}}""")
}	//	plug only
def setThisMonth(resp) {
	logDebug("setThisMonth: ${resp}")
	def year = new Date().format("yyyy").toInteger()
	def month = new Date().format("M").toInteger()
	def day = new Date().format("d").toInteger()
	def data = resp.month_list.find { it.month == month }
	def scale = "energy"
	def energyData
	if (data == null) {
		energyData = 0
	} else {
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
	sendEvent(name: "currMonthTotal", value: energyData, 
			  descriptionText: "KiloWatt Hours", unit: "KWH")
	sendEvent(name: "currMonthAvg", value: avgEnergy, 
			  descriptionText: "KiloWatt Hours per Day", unit: "KWH/D")
	logInfo("setThisMonth: Energy stats set to ${energyData} // ${avgEnergy}")
	if (month != 1) {
		setLastMonth(resp)
	} else {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""emeter":{"get_monthstat":{"year": ${year-1}}}}""")
	}
}
def setLastMonth(resp) {
	logDebug("setLastMonth: cmdResponse = ${resp}")
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
	def data = resp.month_list.find { it.month == lastMonth }
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
	logInfo("setLastMonth: Energy stats set to ${energyData} // ${avgEnergy}")
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
	if (bind && bind != binded) {
		bindUnbind(bind)
	} else {
		device.updateSetting("bind", [type:"enum", value: binded])
		logInfo("setBindUnbind: Bind status set to ${binded}")
	}
}
def ledOnOff() {
	sendCmd("""{"system":{"set_led_off":{"off":${ledStatus}}}}""")
	return ledStatus
}	//	plug only
def rebootDevice() {
	logInfo("rebootDevice: User Commanded Reboot Device!")
	device.updateSetting("rebootDev", [type:"bool", value: false])
	sendCmd("""{"system":{"reboot":{"delay":1}}}""")
	pauseExecution(10000)
	return "REBOOTING DEVICE"
}	//	plug version

//	===== distribute responses =====
def distResp(response) {
	def month = new Date().format("M").toInteger()
	if (response.system) {
		if (response.system.get_sysinfo) {
			setSysInfo(response)
		} else if (response.system.reboot) {
			logInfo("distResp: Rebooting device")
		}
	} else if (response.emeter) {
		def emeterResp = response.emeter
		if (emeterResp.get_realtime) {
			setPower(emeterResp.get_realtime)
		} else if (emeterResp.get_daystat) {
			setEnergyToday(emeterResp.get_daystat)
		} else if (emeterResp.get_monthstat.month_list.find { it.month == month }) {
			setThisMonth(emeterResp.get_monthstat)
		} else if (emeterResp.get_monthstat.month_list.find { it.month == month - 1 }) {
			setLastMonth(emeterResp.get_monthstat)
		}
	} else if (response.cnCloud) {
		setBindUnbind(response)
	} else if (response.error) {
		logWarn("distResponse: Error = ${response.error}")
	} else {
		logWarn("distResp: Unhandled response = ${response}")
	}
}	//	plug version

private sendCmd(command) {
	if (!useCloud) { sendLanCmd(command) }
	else { sendKasaCmd(command) }
}
 
//	===== LAN Communications Code =====
private sendLanCmd(command) {
	logDebug("sendLanCmd: ${command}")
	runIn(3, rawSocketTimeout, [data: command])
	command = outputXOR(command)
	if (now() - state.lastConnect > 35000 ||
	   getDataValue("model") == "HS100" || getDataValue("model") == "HS200") {
		logDebug("sendLanCmd: Attempting to connect.....")
		try {
			interfaces.rawSocket.connect("${getDataValue("deviceIP")}", 
										 9999, byteInterface: true)
		} catch (error) {
			logDebug("SendCmd: Unable to connect to device at ${getDataValue("deviceIP")}. " +
					 "Error = ${error}")
			def pollEnabled = parent.pollForIps()
			if (pollEnabled == true) {
				logWarn("SendCmd: Attempting to update IP address via the Application.")
				runIn(10, rawSocketTimeout, [data: command])
			} else {
				logWarn("SendCmd: IP address update attempted within last hour./n" + 
					    "Check your device. Disable if not longer in use.")
			}
			return
		}
	}
	interfaces.rawSocket.sendMessage(command)
}
def rawSocketTimeout(command) {
	state.errorCount += 1
	if (state.errorCount <= 2) {
		logDebug("rawSocketTimeout: attempt = ${state.errorCount}")
		state.lastConnect = 0
		sendLanCmd(command)
	} else {
		logWarn("rawSocketTimeout: Retry on error limit exceeded. Error " +
				"count = ${state.errorCount}.  Run Application to update IP.")
		if (state.errorCount > 10) {
			unschedule(quickPoll)
			unschedule(refresh)
			logWarn("rawSocketTimeout: Quick Poll and Refresh Disabled.")
		}
	}
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

//	===== Cloud Communications Code =====
private sendKasaCmd(command) {
	logDebug("sendKasaCmd: ${command}")
	def cmdResponse = ""
	def cmdBody = [
		method: "passthrough",
		params: [
			deviceId: getDataValue("deviceId"),
			requestData: "${command}"
		]
	]
	def sendCloudCmdParams = [
		uri: "${getDataValue("appServerUrl")}/?token=${getDataValue("token")}",
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		timeout: 5,
		body : new groovy.json.JsonBuilder(cmdBody).toString()
	]
	httpPostJson(sendCloudCmdParams) {resp ->
		if (resp.status == 200 && resp.data.error_code == 0) {
			def jsonSlurper = new groovy.json.JsonSlurper()
			cmdResponse = jsonSlurper.parseText(resp.data.result.responseData)
		} else {
			logWarn("sendKasaCmd: Error returned from Kasa Cloud")
			cmdResponse = ["error": "${resp.data.error_code} = ${resp.data.msg}"]
		}
	}
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
def logTrace(msg){ 
	log.trace "[${type()}/${driverVer()}] ${device.label} ${msg}"
}
def logInfo(msg) {
	if (descriptionText == true) { 
		log.info "[${type()}/${driverVer()}] ${device.label} ${msg}"
	}
}
def logDebug(msg){
	if(debug == true) {
		log.debug "[${type()}/${driverVer()}] ${device.label} ${msg}"
	}
}
def debugLogOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("debugLogOff: Debug logging is off.")
}
def logWarn(msg){ 
	log.warn "[${type()}/${driverVer()}] ${device.label} ${msg}"
}

//	End of File