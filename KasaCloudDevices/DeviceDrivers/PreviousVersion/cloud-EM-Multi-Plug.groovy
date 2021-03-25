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
02-22	6.1.1	a.	Update to access kasaServerUrl and kasaToken from app.  Had problems with
					updating the data when enabling cloud access.
				b.	Reworked logic for bind/unbind and Lccal/Cloud due to problems with transition.
					Beefed up error message for these functions.
===================================================================================================*/
def driverVer() { return "6.1.1" }
//def type() { return "Multi Plug" }
def type() { return "EM Multi Plug" }
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
		def refreshIntervals = ["60": "1 minute", "300": "5 minutes", 
								"900": "15 minutes", "1800": "30 minutes"]
		input ("refreshInterval", "enum",
			   title: "Refresh Interval",
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
			   title: "Kasa Cloud Binding <b>[Caution]</b>",
			   defaultValue: "1")
		if (bind == "1") {
			input ("useCloud", "bool",
				   title: "Use Kasa Cloud for device control (must already be bound to cloud)",
				   defaultValue: false)
		}
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
	if (state.currentBind == null) { state.currentBind = bind }
	if (state.currentCloud == null) { state.currentCloud = useCloud }
	if (rebootDev) {
		logInfo("updated: ${rebootDevice()}")
	}
	logInfo("updated: ${updateDriverData()}")
	if (debug == true) {
		runIn(1800, debugLogOff)
		logInfo("updated: Debug logging enabled for 30 minutes.")
	}
	logInfo("updated: Description text logging is ${descriptionText}.")

	logInfo("updated: ${setCommsType()}")		//	set actual comms type after checking data
	logInfo("updated: ${bindUnbind()}.")
	ledOnOff()
	def interval = "1800"
	if (!state.pollInterval) { state.pollInterval = "off" }
	if (refreshInterval) {
		interval = refreshInterval
	} else {
		device.updateSetting("refreshInterval", [type:"enum", value: "1800"])
	}
	logInfo("updated: ${setInterval(interval)}")

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
}
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
}
def setCommsType() {
	def commsType = "local"
	if (state.currentCloud) { commsType = "Kasa cloud" }
	def commsParams = [:]
	commsParams["useKasaCloud"] = parent.useKasaCloud
	commsParams["kasaToken"] = parent.kasaToken
	commsParams["kasaCloudUrl"] = parent.kasaCloudUrl
	commsParams["deviceIP"] = getDataValue("deviceIP")	
	commsParams["useCloud"] = useCloud
	commsParams["currentCloud"] = state.currentCloud
	commsParams["bind"] = bind
	commsParams["currentBind"] = state.currentBind
	commsParams["commsType"] = commsType
	logDebug("setCommsType: ${commsParams}")

	def message
	if (state.currentCloud == useCloud) {
		message = "device already set use ${commsType} communications."
	} else if (useCloud) {
		if (!parent.useKasaCloud || state.currentBind == "0" || 
			!parent.kasaToken || !parent.kasaCloudUrl) {
			//	Not available due to app setting or device binding to cloud.
			logWarn("setCommsType: <b>Can't set to Kasa cloud communications.</b> Check items:" +
				    "\n1.  Kasa Integration app must be set to Interface to Kasa Cloud." +
				    "\n    * set Interface to Kasa Cloud in the app." +
				    "\n2.  Device must be bound to Kasa Cloud." +
				    "\n    * open the Kasa phone app and set device to remote control, or" +
				    "\n    * use device page and attempt updating w/o changing cloud parameters." +
				    "\n3.  The token is not set in the Kasa Integration app." +
				    "\n    * run Kasa Login and Token Update in the app." +
				    "\n4.  The kasaCloudUrl is not set int the Kasa Integration app." +
				    "\n    * run Update Installed Devices in the app.")
			commsType = "local"
			device.updateSetting("useCloud", [type:"bool", value: false])
			state.currentCloud = false
			message = "ERROR: device reset use ${commsType} communications."
		} else {
			commsType = "Kasa cloud"
			state.currentCloud = true
			message = "device set use ${commsType} communications."
		}
	} else if (!useCloud) {
		if (!getDataValue("deviceIP")) {
			//	No IP set - may not be able to use device locally.
			logWarn("setCommsType: <b>Device IP is not available.</b>  Check items:" +
				    "\n1.  Device IP not set." +
				    "\n    * Go to Kasa Integration app and run Update Installed Devices." +
				    "\n2.  Device firmware updated to not allow local comms." +
				    "\n    *Use cloud only.")
			commsType = "Kasa cloud"
			device.updateSetting("useCloud", [type:"bool", value: true])
			state.currentCloud = true
			message = "ERROR: device reset use ${commsType} communications."
		} else {
			commsType = "local"
			state.currentCloud = false
			message = "device set use ${commsType} communications."
		}
	} else { message = "useCloud not set to valid value." }

	if (commsType == "Kasa cloud") {
		state.remove("respLength")
		state.remove("response")
		state.remove("lastConnect")
		state.remove("errorCount")
	} else {
		state.respLength = 0
		state.response = ""
		state.lastConnect = 0
		state.errorCount = 0
	}
	return message
}
def bindUnbind() {
	def bindParams = [:]
	bindParams["currentCloud"] = state.currentCloud
	bindParams["bind"] = bind
	bindParams["currentBind"] = state.currentBind
	bindParams["password"] = parent.userPassword
	bindParams["name"] = parent.userName
	logDebug("bindUnbind: ${bindParams}")

	if (state.currentBind == bind) {
		message = "No change in bind state."
		sendCmd("""{"cnCloud":{"get_info":{}}}""")	//	get Bind state
	} else if (bind == "1") {
		if (!parent.userName || !parent.userPassword) {
			//	Username or password not set, can not bind.
			logWarn("bindUnbind: <b>Username or Password not set</b>  Check items:" +
				    "\n1.  userName in Kasa Integration app." +
				    "\n2.  userPassword in Kasa Integration app." +
				    "\nRun Kasa Login and Token Update in Kasa Integration app.")
			device.updateSetting("bind", [type:"enum", value: "0"])
			state.currentBind = "0"
			message = "ERROR: Username or Password not set."
			sendCmd("""{"cnCloud":{"get_info":{}}}""")	//	get Bind state
		} else {
			state.currentBind = "1"
			message = "Binding device to Kasa Cloud."
			sendCmd("""{"cnCloud":{"bind":{"username":"${parent.userName}",""" +
					""""password":"${parent.userPassword}"}},"cnCloud":{"get_info":{}}}""")
		}
	} else if (bind == "0") {
		if (state.currentCloud) {
			//	Can not unbind if current comms is cloud
			logWarn("bindUnbind: <b>Unable to unbind while using cloud.</b>  Try " +
				    "updating using cloud to local first.")
			device.updateSetting("bind", [type:"enum", value: "1"])
			state.currentBind = "1"
			message = "ERROR: Can't set to unbind while useCloud is true."
			sendCmd("""{"cnCloud":{"get_info":{}}}""")	//	get Bind state
		} else {
			state.currentBind = "0"
			message = "Unbinding device from Kasa Cloud."
			sendCmd("""{"cnCloud":{"unbind":""},"cnCloud":{"get_info":{}}}""")
		}
	} else { message = "ERROR.  Bind value not properly set." }
	pauseExecution 1000
	return message
}
def setBindUnbind(cmdResp) {
	def binded = cmdResp.cnCloud.get_info.binded.toString()
	device.updateSetting("bind", [type:"enum", value: binded])
	pauseExecution(1000)
	state.currentBind = binded
	logInfo("setBindUnbind: Bind status set to ${binded}")
}
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
}
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
			message += "\n\t\t\tuseCloud is true, refreshInterval set to 1 minute minimum."
			state.pollInterval = "off"
		}
	}
	if (interval < 60) {
		schedule("*/${interval} * * * * ?", refresh)
		message += "\n\t\t\tPoll interval set to ${interval} seconds."
	} else {
		def minInterval = (	interval/60).toInteger()
		schedule("0 */${minInterval} * * * ?", refresh)
		message += "\n\t\t\tPoll interval set to ${minInterval} minutes."
	}
	return message

}
def ledOnOff() {
	sendCmd("""{"system":{"set_led_off":{"off":${ledStatus}}}}""")
	return ledStatus
}
def rebootDevice() {
	logWarn("rebootDevice: User Commanded Reboot Device!")
	device.updateSetting("rebootDev", [type:"bool", value: false])
	sendCmd("""{"system":{"reboot":{"delay":1}}}""")
	pauseExecution(10000)
	return "REBOOTING DEVICE"
}

//	===== Command and Parse Methods =====
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
}
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

//	===== Communications =====
private sendCmd(command) {
	if (!useCloud) { sendLanCmd(command) }
	else { sendKasaCmd(command) }
}
//	LAN
private sendLanCmd(command) {
	logDebug("sendLanCmd: ${command}")
	runIn(2, rawSocketTimeout, [data: command])
	command = outputXOR(command)
	if (now() - state.lastConnect > 35000) {
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
//	Cloud
private sendKasaCmd(command) {
	logDebug("sendKasaCmd: ${command}")
	def cmdResponse = ""
	if (!parent.kasaCloudUrl || !parent.kasaToken) {
			cmdResponse = ["error": "kasaCloudUrl or kasaToken not set in Kasa Integration app."]
			logWarn("sendKasaCmd: <b>Failed to communicate with Kasa Cloud.</b> Check items:" +
				    "\n1.  kasaCloudUrl must be set in Kasa Integration app." +
				    "\n2.  kasaToken must be set in the Kasa Integration app.")
		return
	}

	def cmdBody = [
		method: "passthrough",
		params: [
			deviceId: getDataValue("deviceId"),
			requestData: "${command}"
		]
	]
	def sendCloudCmdParams = [
		uri: "${parent.kasaCloudUrl}/?token=${parent.kasaToken}",
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		timeout: 5,
		body : new groovy.json.JsonBuilder(cmdBody).toString()
	]
	try {
		httpPostJson(sendCloudCmdParams) {resp ->
			if (resp.status == 200 && resp.data.error_code == 0) {
				def jsonSlurper = new groovy.json.JsonSlurper()
				cmdResponse = jsonSlurper.parseText(resp.data.result.responseData)
			} else {
				logWarn("sendKasaCmd: Error returned from Kasa Cloud")
				cmdResponse = ["error": "${resp.data.error_code} = ${resp.data.msg}"]
			}
		}
	} catch (e) {
		logWarn("sendKasaCmd: <b>Failed to communicate with Kasa Cloud.</b> Check items:" +
				"\n1.  kasaCloudUrl must be set in Kasa Integration app." +
				"\n2.  useKasaCloud must be set in Kasa Integration app." +
				"\n3.  kasaToken must be set in the Kasa Integration app.")
		cmdResponse = ["error": "Protocol Error = ${e}"]
	}
	distResp(cmdResponse)
}
//	Distribute to parsing methods
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
}

//	===== Encryption / Decryption =====
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
