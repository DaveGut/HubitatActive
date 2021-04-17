/*	Kasa Device Driver Series
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Changes from 6.1 =====
1.	General cleanup of installed and updated methods.
2.	Updated scheduling to disperse methods better and reduce device collissions.
3.	Clean-up of new reboot, setCommsType (LAN/Cloud), bind, and ledOn/Off methods and information.
4.	Added attribute for communications error.
5.	EM Devices:  Update on and off to delay power request to get a valid power attribute.
6.	Multi-Plugs:
	a.	Coordinate methods/attributes for setCommsType, Bind, pollInterval (on/off).  Includes
		new method in driver and app for coordination.
	b.	Coordinate of polling command so that only one Hubitat device does an on/off poll
		of the Multi-Plug device. Includes new method in driver and app for coordination.
7.	Hubitat 2.2.6:
	a.	Fixed zero length response in parse method string causing error message.
	b.	Bulbs: Accommodate changes in Capability Color Temperature
	c.	Bulbs: Temporary fix for above for when entering data from Device's edit page causing error.
3/26	6.2.1	Further fix to null return error.
3/27	6.2.2	Update state.errorCount location to fix cuunt issue.
3/28	6.2.3	Added descriptionText preference back into code.
===================================================================================================*/
def driverVer() { return "6.2.3" }
def type() { return "Multi Plug" }
//def type() { return "EM Multi Plug" }
//	Multi Plug
def file() { return type().replaceAll(" ", "-") }

//	Plug Switch & Multi Plug
metadata {
	definition (name: "Kasa ${type()}",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/${file()}.groovy"
			   ) {
		capability "Switch"
		if (type() == "Dimming Switch") {
			capability "Switch Level"
			command "presetLevel",  ["NUMBER"]
		}
		capability "Actuator"
		capability "Refresh"
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["default", "5 seconds", "10 seconds", "15 seconds",
						  "30 seconds", "1 minute", "5 minutes",  "10 minutes",
						  "30 minutes"],
			type: "ENUM"]]
		if (type().contains("EM")) {
			command "setPowerPoll", [[
				name: "Power poll Interval",
				constraints: ["default", "5 seconds", "10 seconds", "15 seconds",
						  "30 seconds", "1 minute", "5 minutes",  "10 minutes",
						  "30 minutes"],
				type: "ENUM"]]
			capability "Power Meter"
			capability "Energy Meter"
			attribute "currMonthTotal", "number"
			attribute "currMonthAvg", "number"
			attribute "lastMonthTotal", "number"
			attribute "lastMonthAvg", "number"
		}
		attribute "connection", "string"
		attribute "commsError", "bool"
	}

	preferences {
		if (type().contains("EM")) {
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
		input ("bind", "bool",
			   title: "Kasa Cloud Binding")
		if (bind && parent.kasaCloudUrl) {
			input ("useCloud", "bool",
				   title: "Use Kasa Cloud for device control",
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
}

def installed() {
	logInfo("Installing device and defining initial attributes and states.")
	state.initInstall = true
	sendEvent(name: "connection", value: "LAN")
	sendEvent(name: "commsError", value: false)
	socketStatus("closed")
	state.pollInterval = "30 minutes"
	runIn(30, getBoundState)
	state.respLength = 0
	state.response = ""
	state.errorCount = 0
	updated()
}

def updated() {
	if (rebootDev) {
		logInfo("updated: ${rebootDevice()}")
	}
	logInfo("updated: Updating device preferences and settings.")
	unschedule()

	logInfo("updated: Debug logging is ${debug}")
	logInfo("updated: ${updateDriverData()}")
	state.errorCount = 0
	if(type().contains("Bulb")) {
		if (transition_Time == null) {
			device.updateSetting("transition_Time", [type:"num", value: 0])
		}
	}
	logInfo("updated: ${setPollInterval(state.pollInterval)}")
	if (!state.initInstall) {
		logInfo("updated: ${setCommsType()}")
		logInfo("updated: ${bindUnbind()}")
		if(!type().contains("Bulb")) {
			logInfo("updated: ${ledOnOff()}")
		}
	} else {
		state.remove("initInstall")
	}
	if (type().contains("EM") || type().contains("Bulb")) {
		logInfo("updated: ${setupEmFunction()}")
	}
	runIn(10, refresh)
}

def updateDriverData() {
	if (getDataValue("driverVersion") != driverVer()) {
		if (useCloud) {
			sendEvent(name: "connection", value: "CLOUD")
		} else {
			sendEvent(name: "connection", value: "LAN")
			socketStatus("closed")
		}
		sendEvent(name: "commsError", value: false)
		if (emFunction) {
			state.powerPollInterval = "default"
		}
		if (!state.pollInterval) { state.pollInterval = "default" }
		updateDataValue("driverVersion", driverVer())
		state.remove("currentBind")
		state.remove("socketTimeout")
		state.remove("currentCloud")
		state.remove("lastConnect")
		return "Driver data updated to latest values."
	} else {
		return "Driver version and data already correct."
	}
}

//	================================================
//	===== Multi Plug Command and Parse Methods =====
//	================================================
def on() {
	logDebug("on")
	sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
			""""system":{"set_relay_state":{"state":1},""" +
			""""get_sysinfo":{}}}""")
	if (emFunction) {
		runIn(10, getPower)
	}
}

def off() {
	logDebug("off")
	sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
			""""system":{"set_relay_state":{"state":0},""" +
			""""get_sysinfo":{}}}""")
	if (emFunction) {
		runIn(2, getPower)
	}
}

def refresh() {
	logDebug("refresh")
	poll()
	if (emFunction) {
		runIn(1, getPower)
	}
}

def poll() {
	sendCmd("""{"system":{"get_sysinfo":{}}}""")
}

//	Multi Plug
def setSysInfo(status) {
	status = status.children.find { it.id == getDataValue("plugId") }
	def relayState = status.state
	def onOff = "on"
	if (relayState == 0) { onOff = "off" }
	if (onOff != device.currentValue("switch")) {
		sendEvent(name: "switch", value: onOff, type: "digital")
		logInfo("setSysInfo: switch: ${onOff}")
	}
}

//	==================================
//	===== Energy Monitor Methods =====
//	==================================
//	Multi Plug
def getPower() {
	sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
			""""emeter":{"get_realtime":{}}}""")
}

def setPower(status) {
	def power = status.power
	if (power == null) { power = status.power_mw / 1000 }
	power = Math.round(10*(power))/10
	def curPwr = device.currentValue("power")
	if (power > curPwr + 1 || power < curPwr - 1) { 
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W")
		logInfo("pollResp: power = ${power}")
	}
}

def getEnergyToday() {
	def year = new Date().format("yyyy").toInteger()
	def month = new Date().format("M").toInteger()
	if (type().contains("Bulb")) {
		sendCmd("""{"smartlife.iot.common.emeter":{"get_daystat":{"month": ${month}, "year": ${year}}}}""")
	} else if (type().contains("Multi")) {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""emeter":{"get_daystat":{"month": ${month}, "year": ${year}}}}""")
	} else {
		sendCmd("""{"emeter":{"get_daystat":{"month": ${month}, "year": ${year}}}}""")
	}
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
		sendEvent(name: "energy", value: energyData, descriptionText: "KiloWatt Hours", unit: "kWh")
		logInfo("setEngrToday: [energy: ${energyData}]")
	}
}

def updateEmStats() {
	logDebug("updateEmStats: Updating daily energy monitor data.")
	def year = new Date().format("yyyy").toInteger()
	if (type().contains("Bulb")) {
		sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""")
	} else if (type().contains("Multi")) {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""emeter":{"get_monthstat":{"year": ${year}}}}""")
	} else {
		sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""")
	}
	
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
			  descriptionText: "KiloWatt Hours", unit: "kWh")
	sendEvent(name: "currMonthAvg", value: avgEnergy, 
			  descriptionText: "KiloWatt Hours per Day", unit: "kWh/D")
	logInfo("setThisMonth: Energy stats set to ${energyData} // ${avgEnergy}")
	if (month != 1) {
		setLastMonth(resp)
	} else {
		if (type().contains("Bulb")) {
			sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year-1}}}}""")	//	bulbs
		} else if (type().contains("Multi")) {
			sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +	//	multi plugs
					""""emeter":{"get_monthstat":{"year": ${year-1}}}}""")
		} else {
		sendCmd("""{"emeter":{"get_monthstat":{"year": ${year-1}}}}""")
		}
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
	sendEvent(name: "lastMonthTotal", value: energyData, descriptionText: "KiloWatt Hours", unit: "kWh")
	sendEvent(name: "lastMonthAvg", value: avgEnergy, descriptionText: "KiloWatt Hoursper Day", unit: "kWh/D")
	logInfo("setLastMonth: Energy stats set to ${energyData} // ${avgEnergy}")
}

//	==========================
//	===== Communications =====
//	==========================
def sendCmd(command) {
	if (device.currentValue("connection") == "LAN") { sendLanCmd(command) }
	else { sendKasaCmd(command) }
}

//	Multi PLug && Bulb
def sendLanCmd(command) {
	logDebug("sendLanCmd: ${command}, socketStatus = ${state.socketStatus}")
	if (state.socketStatus != "open") {
		try {
			interfaces.rawSocket.connect("${getDataValue("deviceIP")}", 
										 9999, byteInterface: true)
		} catch (error) {
			logDebug("SendCmd: Unable to connect to device at ${getDataValue("deviceIP")}. " +
					 "Error = ${error}")
			handleCommsError([command, "Socket Connect Fail"])
			return
		}
	}
	interfaces.rawSocket.sendMessage(outputXOR(command))
	runIn(3, handleCommsError, [data: [command, "Socket Comms Timeout"]])
	socketStatus("open")
	runIn(35, socketStatus, [data: "closed"])
}

def socketStatus(message) {
	logDebug("socketStatus: ${message}")
	state.socketStatus = message
	if (message != "open") {
		interfaces.rawSocket.close()
	}
}

def parse(message) {
	if (message == null || message == "") { return }
	def respLength
	def msgLen = message.length()
	if (msgLen > 8 && message.substring(0,4) == "0000") {
		def hexBytes = message.substring(0,8)
		respLength = 8 + 2 * hubitat.helper.HexUtils.hexStringToInt(hexBytes)
		if (msgLen == respLength) {
			prepResponse(message)
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
			prepResponse(resp)
		} else {
			state.response = resp
		}
	}
}

def prepResponse(response) {
	def resp
	try {
		resp = parseJson(inputXOR(response))
	} catch (e) {
		return
	}
	distResp(resp)
	unschedule(handleCommsError)
}

def sendKasaCmd(command) {
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
				distResp(jsonSlurper.parseText(resp.data.result.responseData))
				if (state.communicationsError) {
					resetCommsError()
				}
			} else {
				logDebug("sendKasaCmd: Error returned from Kasa Cloud: [error: ${resp.data}]")
				handleCommsError([command, "Cloud Command Failure"])
			}
		}
	} catch (e) {
		logDebug("sendKasaCmd: Failed to communicate with Kasa Cloud. [error: ${e}]")
		handleCommsError([command, "Cloud Comms Timeout"])
	}
}

def handleCommsError(command) {
	def count = state.errorCount + 1
	state.errorCount = count
	def errType = command[1]
	def message = "handleCommsError: ${count} consecutive communications errors. Type = ${errType}"
	switch(errType) {
		case "Socket Connect Fail" :
			if (count == 1) {
				socketStatus("closed")
				message += "\n\tParent: ${parent.updateIpData()}"
				message += "\n\t<b>Retry ${count} for command.</b>"
				runIn(10, sendLanCmd, [data: command[0]])
			}
			break
		case "Cloud Comms Timeout" :
			if (count == 1) {
				message += "Parent: ${parent.getToken()}"
				message += "\n\tRetry ${count} for command."
				runIn(2, sendKasaCmd, [data: command[0]])
			} else if (count == 4) { setCommsError() }
			break
		case "Socket Comms Timeout" :
			if (count <= 3) {
				message += "\n\tRetry ${count} for command."
				socketStatus("closed")
				runIn(1, sendLanCmd, [data: command[0]])
			} else if (count == 4) { setCommsError() }
			break
		default:
			break
	}
	logWarn(message)
}

def setCommsError() {
	def message = "setCommsError:"
	message += "\n\t\t4th consecutive error.  Setting communications to ERROR."
	message += "\n\t\tSetting poll interval to 30 minutes until corrected."
	message += "\n\t\t<b>Check device IP (LAN) or Kasa Token (CLOUD)</b>."
	unschedule(getPower)
	setPollInterval("30 minutes")
	state.communicationsError = "Six consecutive comms error."
	sendEvent(name: "commsError", value: true)
	logWarn message
}

def resetCommsError() {
	state.remove("communicationsError")
	sendEvent(name: "commsError", value: false)
	if (state.errorCount >= 6) {
		setPollInterval(state.pollInterval)
		if(emFunction) {
			setPowerPoll(state.powerPollInterval)
		}
	}
}

//	Multi Plug
def distResp(response) {
	if (response.system) {
		if (response.system.get_sysinfo) {
			parent.coordPoll(getDataValue("deviceId"), response.system.get_sysinfo)
		} else if (response.system.set_relay_state) {
			refresh()
		} else if (response.system.reboot) {
			logInfo("distResp: Rebooting device.")
		} else if (response.system.set_led_off) {
			logInfo("distResp: Led On/Off response = ${response.system.set_led_off}")
		} else {
			logWarn("distResp: Unhandled response = ${response}")
		}
	} else if (emFunction && response.emeter) {
		def month = new Date().format("M").toInteger()
		if (response.emeter.get_realtime) {
			setPower(response.emeter.get_realtime)
		} else if (response.emeter.get_daystat) {
			setEnergyToday(response.emeter.get_daystat)
		} else if (response.emeter.get_monthstat.month_list.find { it.month == month }) {
			setThisMonth(response.emeter.get_monthstat)
		} else if (response.emeter.get_monthstat.month_list.find { it.month == month - 1 }) {
			setLastMonth(response.emeter.get_monthstat)
		} else {
			logWarn("distResp: Unhandled response = ${response}")
		}
	} else if (response.cnCloud) {
		setBindUnbind(response.cnCloud)
	} else {
		logWarn("distResp: Unhandled response = ${response}")
	}
	state.errorCount = 0
	if (state.communicationsError) {
		resetCommsError()
	}
}

//	==============================
//	===== Preference Methods =====
//	==============================
def setCommsType() {
	def commsType = device.currentValue("connection")
	logDebug("setCommsType: ${commsType}")
	def message = ""
	
	def currentCloud = false
	if (commsType == "CLOUD") {
		currentCloud = true
	}
	if (currentCloud == useCloud) {
		message = "device already set use ${commsType} communications."
		return message
	} else if (useCloud) {
		if (!parent.useKasaCloud || !bind || 
			!parent.kasaToken || !parent.kasaCloudUrl) {
			//	Not available due to app setting or device binding to cloud.
			logWarn("setCommsType: <b>Can't set to Kasa cloud communications.</b> Check items:" +
				    "\n\t1.\tKasa Integration Appication" +
				    "\n\t\t* set Interface to Kasa Cloud in the app and validate userName and password" +
					"\n\t\t* the token must not be null." +
				    "\n\t2.\tDevice must be bound to Kasa Cloud.")
			message = "ERROR: device reset use ${commsType} communications."
			commsType = "LAN"
		} else {
			message = "device set use ${commsType} communications."
			commsType = "CLOUD"
		}
	} else if (!useCloud) {
		if (!getDataValue("deviceIP")) {
			//	No IP set - may not be able to use device locally.
			logWarn("setCommsType: <b>Device IP is not available.</b>  Device IP not set. " +
				    "Go to Kasa Integration app and run Update Installed Devices.")
			message = "ERROR: device reset use ${commsType} communications."
			commsType = "CLOUD"
		} else {
			message = "device set use ${commsType} communications."
			commsType = "LAN"
		}
	} else {
		message = "useCloud not set to valid value."
		return
	}

	if (!type().contains("Multi")) {
		if (commsType == "CLOUD") {
			device.updateSetting("useCloud", [type:"bool", value: true])
			sendEvent(name: "connection", value: "CLOUD")
		} else {
			state.respLength = 0
			state.response = ""
			device.updateSetting("useCloud", [type:"bool", value: false])
			sendEvent(name: "connection", value: "LAN")
			socketStatus("closed")
		}
	} else {
		parent.coordinate(getDataValue("deviceId"), getDataValue("plugNo"), 
						  "lanCloud", commsType)
	}
	return message
}

def setPollInterval(interval) {
	logDebug("setPollInterval: interval = ${interval}.")
	if (emFunction && interval.contains("sec") 
		&& state.powerPollInterval.contains("sec")) {
		logWarn("setPollInterval: Not set.  Power Poll set to less than one minute." +
				" Reset Power Poll to greater than 1 minute.")
		return
	}
	if (interval == "default" || interval == "off") {
		interval = "30 minutes"
	} else if (useCloud && interval.contains("sec")) {
		interval = "1 minute"
	}
	if (type().contains("Multi")) {
		parent.coordinate(getDataValue("deviceId"), getDataValue("plugNo"), "poll", interval)
	} else {
		state.pollInterval = interval
	}
	schedInterval("poll", interval)
}

//	Plug/Switch && Multi Plug
def setPowerPoll(interval) {
	if (!emFunction) { return }
	logDebug("setPowerPoll: interval = ${interval}.")
	if (interval.contains("sec") && state.pollInterval.contains("sec")) {
		logWarn("setpowerPoll: Not set.  On/off Poll set to less than one minute." +
				" Reset on/off Poll to greater than 1 minute.")
		return
	}
	if (interval == "default" || interval == "off") {
		interval = "30 minutes"
	} else if (useCloud && interval.contains("sec")) {
		interval = "1 minute"
	}
	state.powerPollInterval = interval
	schedInterval("getPower", interval)
}

def schedInterval(type, interval) {
	logDebug("schedInterval: type = ${type}, interval = ${interval}.")
	def message = ""
	def pollInterval = interval.substring(0,2).toInteger()
	if (interval.contains("sec")) {
		def start = Math.round((pollInterval-1) * Math.random()).toInteger()
		schedule("${start}/${pollInterval} * * * * ?", type)
		message += "${type} Interval set to ${interval} seconds."
	} else {
		def start = Math.round(59 * Math.random()).toInteger()
		schedule("${start} */${pollInterval} * * * ?", type)
		message += "${type} Interval set to ${interval} minutes."
	}
	logDebug("schedInterval: ${message}")
	return message
}

def setupEmFunction() {
	if (!emFunction) {
		if (state.powerPollInterval) {
			state.remove("powerPollInterval")
			sendEvent(name: "power", value: 0)
			sendEvent(name: "energy", value: 0)
			sendEvent(name: "currMonthTotal", value: 0)
			sendEvent(name: "currMonthAvg", value: 0)
			sendEvent(name: "lastMonthTotal", value: 0)
			sendEvent(name: "lastMonthAvg", value: 0l)
		}
		return "Not in Energy Monitor mode."
	} else {
		sendEvent(name: "power", value: 0, descriptionText: "Watts", unit: "W")
		if (type().contains("EM")) {
			logInfo("setupEmFunction: ${setPowerPoll("default")}")
		}
		def start = Math.round(59 * Math.random()).toInteger()
		schedule("${start} 01 0 * * ?", updateEmStats)
		start = Math.round(59 * Math.random()).toInteger()
		schedule("${start} */30 * * * ?", getEnergyToday)
		getEnergyToday()
		pauseExecution(500)
		updateEmStats()
		pauseExecution(2000)
		return "Energy Monitor Function initialized."
	}
}

def rebootDevice() {
	logWarn("rebootDevice: User Commanded Reboot Device!")
	device.updateSetting("rebootDev", [type:"bool", value: false])
	if (type().contains("Bulb")) {
		sendCmd("""{"smartlife.iot.common.system":{"reboot":{"delay":1}}}""")
	} else {
		sendCmd("""{"system":{"reboot":{"delay":1}}}""")
	}
	pauseExecution(10000)
	return "REBOOTING DEVICE"
}

def bindUnbind() {
	def commsError = device.currentValue("commsError")
	logDebug("bindUnbind: ${bind} // ${commsError}")
	def message = ""
	def meth = "cnCloud"
	if (type().contains("Bulb")) {
		meth = "smartlife.iot.common.cloud"
	}
	if (commsError == "true") {
		message += "Bind. Error in connection.  Aborting update"
		return message
	} else if (!bind) {
		message = "Attempting to Unbind the Device from the Kasa Cloud."
		sendCmd("""{"${meth}":{"unbind":""},""" +
				""""${meth}":{"get_info":{}}}""")
	} else if (bind) {
		message += "Attempting to Bind the Device to the Kasa Cloud"
		sendCmd("""{"${meth}":{"bind":{"username":"${parent.userName}",""" +
				""""password":"${parent.userPassword}"}},""" +
				""""${meth}":{"get_info":{}}}""")
	}
	pauseExecution(1000)
	return message
}

def getBoundState() {
	def meth = "cnCloud"
	if (type().contains("Bulb")) {
		meth = "smartlife.iot.common.cloud"
	}
	sendCmd("""{"${meth}":{"get_info":{}}}""")
}

def setBindUnbind(cmdResp) {
	if (cmdResp.get_info) {
		def bindState = true
		if (cmdResp.get_info.binded == 0) {
			bindState = false
		}
		if (type().contains("Multi")) {
			parent.coordinate(getDataValue("deviceId"), getDataValue("plugNo"),
							  "bind", bindState)
		} else {
			device.updateSetting("bind", [type:"bool", value: bindState])
		}
		logInfo("setBindUnbind: Bind status set to ${bindState}")
	} else {
		logWarn("setBindUnbind: Unhandled response: ${cmdResp}")
	}
}

//	Plug/Switch & Multi Plug
def ledOnOff() {
	sendCmd("""{"system":{"set_led_off":{"off":${ledStatus}}}}""")
	pauseExecution(2000)
	return "Setting Led off to ${ledStatus}."
}

//	Multi Plug
def coordPoll(data) { setSysInfo(data) }

def coord(type, data, plugNo) {
	switch(type) {
		case "sysInfo" :
			setSysInfo(data)
			break
		case "poll" :
			if (plugNo != getDataValue("plugNo")) {
				unschedule("poll")
			}
			state.pollInterval = data
			break
		case "bind" :
			device.updateSetting("bind", [type: "bool", value: data])
			break
		case "lanCloud" :
			if (data == "LAN") {
				device.updateSetting("useCloud", [type: "bool", value: false])
				state.respLength = 0
				state.response = ""
				sendEvent(name: "connection", value: "LAN")
				socketStatus("closed")
			} else {
				device.updateSetting("useCloud", [type: "bool", value: true])
				state.remove("respLength")
				state.remove("response")
				state.remove("socketStatus")
				sendEvent(name: "connection", value: "CLOUD")
			}
			break
		default:
			logWarn("coord: Invalid type.  ${type} / ${data}")
			return
	}
	logDebug("coord: success.  type = ${type}")
}

//	===========================
//	===== Utility Methods =====
//	===========================
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

def logTrace(msg){
	log.trace "[${type()} / ${driverVer()} / ${device.label}]| ${msg}"
}

def logInfo(msg) {
	if (descriptionText == true) { 
		log.info "[${type()} / ${driverVer()} / ${device.label}]| ${msg}"
	}
}

def logDebug(msg){
	if(debug == true) {
		log.debug "[${type()} / ${driverVer()} / ${device.label}]| ${msg}"
	}
}

def logWarn(msg){
	log.warn "[${type()} / ${driverVer()} / ${device.label}]| ${msg}"
}

//	End of File
