/*	Kasa Device Driver Series

		Copyright Dave Gutheinz

License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md

Changes since version 6:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Version%206%20Change%20Log.md

===== Version 6.4.0) =====
1.  New driver for Light Strips.  Includes new functions:
	a.	Effect Presets - Save current strip effect.  Delete saved effect (by name).
		Set a save effect to the strip's active effect.
	b.	Bulb Presets.  Works on current color attributes.  Save, Delete, and Set
	c.	Preference Sync Effect Presets.  Sets the effect presets for other strips
		to match current strip.
	d.	Preference Sync Bulb Preset Data. Sets the bulb presets for other strips 
		to match current strip.
	e.	Limitation: Light Strips and the driver do not support Color Temperature.
2.	Updated Color Bulb driver.  Added Bulb Presets and preference Sync Bulb Data.
3.	General update: Clean up installation and save preferences process.
===================================================================================================*/
def driverVer() { return "6.4.0" }
//def type() { return "Color Bulb" }
//def type() { return "CT Bulb" }
def type() { return "Mono Bulb" }
def file() { return type().replaceAll(" ", "") }
import groovy.json.JsonSlurper

metadata {
	definition (name: "Kasa ${type()}",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/${file()}.groovy"
			   ) {
        capability "Light"
		capability "Switch"
		capability "Switch Level"
		capability "Change Level"
 		capability "Refresh"
		capability "Actuator"
		if (type() != "Mono Bulb") {
			capability "Color Temperature"
			command "setCircadian"
			attribute "circadianState", "string"
		}
		if (type() == "Color Bulb") {
			capability "Color Mode"
			capability "Color Control"
		}
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["default", "5 seconds", "10 seconds", "15 seconds",
						  "30 seconds", "1 minute", "5 minutes",  "10 minutes",
						  "30 minutes"],
			type: "ENUM"]]
		//	EM Functions
		capability "Power Meter"
		capability "Energy Meter"
		attribute "currMonthTotal", "number"
		attribute "currMonthAvg", "number"
		attribute "lastMonthTotal", "number"
		attribute "lastMonthAvg", "number"
		//	Communications
		attribute "connection", "string"
		attribute "commsError", "string"
		//	Psuedo capability Light Presets
		if (type() == "Color Bulb") {
			command "bulbPresetCreate", [[
				name: "Name for preset.", 
				type: "STRING"]]
			command "bulbPresetDelete", [[
				name: "Name for preset.", 
				type: "STRING"]]
			command "bulbPresetSet", [[
				name: "Name for preset.", 
				type: "STRING"],[
				name: "Transition Time (seconds).", 
				type: "STRING"]]
		}
	}
	preferences {
		input ("emFunction", "bool", 
			   title: "Enable Energy Monitor", 
			   defaultValue: false)
		input ("transition_Time", "num",
			   title: "Default Transition time (seconds)",
			   defaultValue: 0)
		if (type() == "Color Bulb") {
			input ("highRes", "bool", 
				   title: "(Color Bulb) High Resolution Hue Scale", 
				   defaultValue: false)
			input ("syncBulbs", "bool",
				   title: "Sync Bulb Preset Data",
				   defaultValue: false)
		}
		input ("debug", "bool",
			   title: "30 minutes of debug logging", 
			   defaultValue: false)
		input ("descriptionText", "bool", 
			   title: "Enable description text logging", 
			   defaultValue: true)
		input ("bind", "bool",
			   title: "Kasa Cloud Binding",
			   defalutValue: true)
		if (bind && parent.useKasaCloud) {
			input ("useCloud", "bool",
				   title: "Use Kasa Cloud for device control",
				   defaultValue: false)
		}
		input ("rebootDev", "bool",
			   title: "Reboot device <b>[Caution]</b>",
			   defaultValue: false)
	}
}

//	======================================
//	===== Code common to all drivers =====
//	======================================
def installed() {
	logInfo("Installing Device...")
	if (parent.useKasaCloud) {
		logInfo("install: Installing as CLOUD device.")
		device.updateSetting("useCloud", [type:"bool", value: true])
		sendEvent(name: "connection", value: "CLOUD")
	} else {
		logInfo("install: Installing as LAN device")
		sendEvent(name: "connection", value: "LAN")
		device.updateSetting("useCloud", [type:"bool", value: false])
	}
	state.errorCount = 0
	state.pollInterval = "30 minutes"
	state.bulbPresets = [:]
	updateDataValue("driverVersion", driverVer())
	if (type() == "colorBulb") { state.bulbPresets = [:] }
	runIn(2, updated)
}

def updated() {
	if (rebootDev) {
		//	First to run with  10 second wait to continue.
		logWarn("updated: ${rebootDevice()}")
	}
	unschedule()
	if (syncBulbs) {
		logDebug("updated: ${syncBulbPresets()}")
		return
	}
	if (debug) { runIn(1800, debugOff) }
	logDebug("updated: Debug logging is ${debug}. Info logging is ${descriptionText}.")
	logDebug("updated: Default Transition Time = ${transition_Time} seconds.")
	logDebug("updated: High Resolution Color is ${highRes}")
	state.errorCount = 0
	sendEvent(name: "commsError", value: "false")
	if (type() == "Color Bulb" && !state.bulbPresets) { state.bulbPresets = [:] }
	logDebug("updated: ${bindUnbind()}")
	logDebug("updated: ${setupEmFunction()}")
	logDebug("updated: ${setPolling()}")
	logDebug("updated: ${updateDriverData()}")
	updateDataValue("driverVersion", driverVer())
	runIn(3, refresh)
}

def updateDriverData() {
	def drvVer = getDataValue("driverVersion")
	if (drvVer == driverVer()) {
		return "Driver Data already updated."
	}
	def message = "<b>Updating data from driver version ${drvVer}."
	state.remove("lastSaturation")
	pauseExecution(100)
	state.remove("lastHue")
	def comType = "LAN"
	def usingCloud = false
	def binding = true
	def interval = "30 minutes"
	if (drvVer.contains("5.3")) {
		if (state.pollInterval && state.pollInterval != "off") {
			interval = "${state.pollInterval} seconds"
		}
	} else if (drvVer.contains("6.0")) {
		if (refreshInterval) {
			def inter = refreshInterval.toInteger()
			if (inter < 60) {
				interval = "${inter} seconds"
			}
		}
		if (useCloud) { commType = "CLOUD" }
		if (bind == "0") { binding = false }
	} else if (drvVer.contains("6.1")) {
		if (state.pollInterval && state.pollInterval != "off") {
			interval = "${state.pollInterval} seconds"
		}
		if (useCloud) { commType = "Cloud" }
	} else if (drvVer.contains("6.2")) {
		interval = state.pollInterval
		if (useCloud) { commType = "Cloud" }
		binding = bind
	}
	message += "\n\t\t\t Connection = ${comType}."
	message += "\n\t\t\t bind = ${binding}."
	message += "\n\t\t\t pollInterval = ${interval}."
	setCommsData(comType)
	pauseExecution(200)
	device.updateSetting("bind", [type:"bool", value: binding])
	pauseExecution(200)
	state.pollInterval = interval
	state.remove("WARNING")
	pauseExecution(100)
	updateDataValue("driverVersion", driverVer())
	message += "\n\t\t\tNew Version: ${driverVer()}.</b>"
	return message
}

//	===== Energy Monitor Methods =====
def getPower() {
	if (type().contains("Multi")) {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""emeter":{"get_realtime":{}}}""")
	} else if (type().contains("Bulb")) {
		sendCmd("""{"smartlife.iot.common.emeter":{"get_realtime":{}}}""")
	} else {
		sendCmd("""{"emeter":{"get_realtime":{}}}""")
	}
}

def setPower(response) {
	def power = response.power
	if (power == null) { power = response.power_mw / 1000 }
	power = Math.round(10*(power))/10
	def curPwr = device.currentValue("power")
	if (curPwr < 5 && (power > curPwr + 0.3 || power < curPwr - 0.3)) {
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital")
		logDebug("polResp: power = ${power}")
	} else if (power > curPwr + 5 || power < curPwr - 5) {
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital")
		logDebug("polResp: power = ${power}")
	}
}

def getEnergyToday() {
	logDebug("getEnergyToday")
	def year = new Date().format("yyyy").toInteger()
	if (type().contains("Multi")) {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""emeter":{"get_monthstat":{"year": ${year}}}}""")
	} else if (type().contains("Bulb")) {
		sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""")
	} else {
		sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""")
	}
}

def setEnergyToday(response) {
	logDebug("setEnergyToday: response = ${response}")
	def month = new Date().format("M").toInteger()
	def data = response.month_list.find { it.month == month }
	def energy = data.energy
	if (energy == null) { energy = data.energy_wh/1000 }
	energy -= device.currentValue("currMonthTotal")
	energy = Math.round(100*energy)/100
	def currEnergy = device.currentValue("energy")
	if (currEnergy < energy + 0.05) {
		sendEvent(name: "energy", value: energy, descriptionText: "KiloWatt Hours", unit: "KWH")
		logDebug("setEngrToday: [energy: ${energy}]")
	}
	setThisMonth(response)
}

def setThisMonth(response) {
	logDebug("setThisMonth: response = ${response}")
	def month = new Date().format("M").toInteger()
	def day = new Date().format("d").toInteger()
	def data = response.month_list.find { it.month == month }
	def totEnergy = data.energy
	if (totEnergy == null) { 
		totEnergy = data.energy_wh/1000
	}
	totEnergy = Math.round(100*totEnergy)/100
	def avgEnergy = 0
	if (day != 1) { 
		avgEnergy = totEnergy /(day - 1) 
	}
	avgEnergy = Math.round(100*avgEnergy)/100

	sendEvent(name: "currMonthTotal", value: totEnergy, 
			  descriptionText: "KiloWatt Hours", unit: "kWh")
	sendEvent(name: "currMonthAvg", value: avgEnergy, 
			  descriptionText: "KiloWatt Hours per Day", unit: "kWh/D")
	logDebug("setThisMonth: Energy stats set to ${totEnergy} // ${avgEnergy}")
	if (month != 1) {
		setLastMonth(response)
	} else {
		def year = new Date().format("yyyy").toInteger()
		if (type().contains("Multi")) {
			sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
					""""emeter":{"get_monthstat":{"year": ${year}}}}""")
		} else if (type().contains("Bulb")) {
			sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""")
		} else {
			sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""")
		}
	}
}

def setLastMonth(response) {
	logDebug("setLastMonth: response = ${response}")
	def year = new Date().format("yyyy").toInteger()
	def month = new Date().format("M").toInteger()
	def day = new Date().format("d").toInteger()
	def lastMonth
	if (month == 1) {
		lastMonth = 12
	} else {
		lastMonth = month - 1
	}
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
	def data = response.month_list.find { it.month == lastMonth }
	def totEnergy
	if (data == null) {
		totEnergy = 0
	} else {
		totEnergy = data.energy
		if (totEnergy == null) { 
			totEnergy = data.energy_wh/1000
		}
		totEnergy = Math.round(100*totEnergy)/100
	}
	def avgEnergy = 0
	if (day !=1) {
		avgEnergy = totEnergy /(day - 1)
	}
	avgEnergy = Math.round(100*avgEnergy)/100
	sendEvent(name: "lastMonthTotal", value: totEnergy, 
			  descriptionText: "KiloWatt Hours", unit: "kWh")
	sendEvent(name: "lastMonthAvg", value: avgEnergy, 
			  descriptionText: "KiloWatt Hoursper Day", unit: "kWh/D")
	logDebug("setLastMonth: Energy stats set to ${totEnergy} // ${avgEnergy}")
}

//	===== Communications =====
def sendCmd(command) {
	if (device.currentValue("connection") == "LAN") {
		sendLanCmd(command)
	} else if (device.currentValue("connection") == "CLOUD"){
		sendKasaCmd(command)
	} else {
		logWarn("sendCmd: attribute connection not set.")
	}
}

def sendLanCmd(command) {
	logDebug("sendLanCmd: command = ${command}")
	state.lastLanCmd = command
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${getDataValue("deviceIP")}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 parseWarning: true,
		 timeout: 2])
	sendHubCommand(myHubAction)
}

def parse(message) {
	def resp = parseLanMessage(message)
	if (resp.type != "LAN_TYPE_UDPCLIENT") {
		def errMsg = "LAN Error = ${resp.type}"
		logDebug("parse: ${errMsg}]")
		handleCommsError([state.lastLanCmd, errMsg])
		return
	}
	def clearResp = inputXOR(resp.payload)
	if (clearResp.length() > 1022) {
		clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}"
	}
	def cmdResp = new JsonSlurper().parseText(clearResp)
	distResp(cmdResp)
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
			} else {
				def errMsg = "CLOUD Error = ${resp.data}"
				logDebug("sendKasaCmd: ${errMsg}]")
				handleCommsError([command, errMsg])
			}
		}
	} catch (e) {
				def errMsg = "CLOUD Error = ${e}"
				logDebug("sendKasaCmd: ${errMsg}]")
				handleCommsError([command, errMsg])
	}
}

def handleCommsError(errorData) {
	def count = state.errorCount + 1
	state.errorCount = count
	def errData = errorData[1]
	def command = errorData[0]
	def message = "handleCommsError: Count: ${count}."
	if (count <= 3) {
		message += "\n\t\t\t Retransmitting command, try = ${count}"
		runIn(2, sendCmd, [data: command])
	} else if (count == 4) {
		setCommsError(errData)
		message += "\n\t\t\t Setting Comms Error."
	}
	logDebug(message)
		
}

def setCommsError(errorData) {
	def message = "setCommsError: Four consecutive errors.  Setting commsError to true."
	message += "\n\t\t<b>ErrorData = ${ErrorData}</b>."
	sendEvent(name: "commsError", value: "true")
	state.commsErrorText = "<b>${errorData}</b>"
	message += "\n\t\t${parent.fixConnection(device.currentValue("connection"))}"
	logWarn message
	runIn(2, refresh)
}

def resetCommsError() {
	if (state.errorCount >= 4) {
		sendEvent(name: "commsError", value: "false")
		state.remove("commsErrorText")
	}
	state.errorCount = 0
}

def distResp(response) {
	if (response["${service()}"]) {
		updateBulbData(response["${service()}"]."${method()}")
		if(emFunction) { getPower() }
	} else if (response.system) {
		updateBulbData(response.system.get_sysinfo.light_state)
		if(emFunction) { getPower() }
	} else if (emFunction && response["smartlife.iot.common.emeter"]) {
		def month = new Date().format("M").toInteger()
		def emeterResp = response["smartlife.iot.common.emeter"]
		if (emeterResp.get_realtime) {
			setPower(emeterResp.get_realtime)
		} else if (emeterResp.get_monthstat.month_list.find { it.month == month }) {
			setEnergyToday(emeterResp.get_monthstat)
		} else if (emeterResp.get_monthstat.month_list.find { it.month == month - 1 }) {
			setLastMonth(emeterResp.get_monthstat)
		} else {
			logWarn("distResp: Unhandled response = ${response}")
		}
	} else if (response["smartlife.iot.common.cloud"]) {
		setBindUnbind(response["smartlife.iot.common.cloud"])
	} else if (response["smartlife.iot.common.system"]) {
		logWarn("distResp: Rebooting device")
	} else {
		logWarn("distResp: Unhandled response = ${response}")
	}
	resetCommsError()
}

//	===== Preference Methods =====
def setPolling() {
	def message = "Setting Poll Intervals."
	def interval = "30 minutes"
	if (state.pollInterval) {
		interval = state.pollInterval
	}
	message += "\n\t\t\t OnOff Polling set to ${interval}."
	setPollInterval(interval)
	state.remove("powerPollInterval")
	state.remove("powerPollWarning")
	return message
}

def setPollInterval(interval) {
	logDebug("setPollInterval: interval = ${interval}.")
	if (interval == "default" || interval == "off") {
		interval = "30 minutes"
	} else if (useCloud && interval.contains("sec")) {
		interval = "1 minute"
	}
	if (interval.contains("sec")) {
		state.pollWarning = "<b>Polling intervals of less than one minute can take high " +
								 "resources and impact hub performance.</b>"
	} else {
		state.remove("pollWarning")
	}
	state.pollInterval = interval
	schedInterval("poll", interval)
}

def schedInterval(pollType, interval) {
	logDebug("schedInterval: type = ${pollType}, interval = ${interval}.")
	def message = ""
	def pollInterval = interval.substring(0,2).toInteger()
	if (interval.contains("sec")) {
		def start = Math.round((pollInterval-1) * Math.random()).toInteger()
		schedule("${start}/${pollInterval} * * * * ?", pollType)
		message += "${pollType} Interval set to ${interval} seconds."
	} else {
		def start = Math.round(59 * Math.random()).toInteger()
		schedule("${start} */${pollInterval} * * * ?", pollType)
		message += "${pollType} Interval set to ${interval} minutes."
	}
}

def setupEmFunction() {
	if (emFunction) {
		sendEvent(name: "power", value: 0)
		sendEvent(name: "energy", value: 0)
		sendEvent(name: "currMonthTotal", value: 0)
		sendEvent(name: "currMonthAvg", value: 0)
		sendEvent(name: "lastMonthTotal", value: 0)
		sendEvent(name: "lastMonthAvg", value: 0)
		def start = Math.round(30 * Math.random()).toInteger()
		schedule("${start} */30 * * * ?", getEnergyToday)
		runIn(1, getEnergyToday)
		return "Energy Monitor Function initialized."
	} else if (device.currentValue("power") != null) {
		sendEvent(name: "power", value: 0)
		sendEvent(name: "energy", value: 0)
		sendEvent(name: "currMonthTotal", value: 0)
		sendEvent(name: "currMonthAvg", value: 0)
		sendEvent(name: "lastMonthTotal", value: 0)
		sendEvent(name: "lastMonthAvg", value: 0)
		if (type().contains("Multi")) {
			state.remove("powerPollInterval")
		}
		return "Energy Monitor Function not enabled. Data element set to 0."
	} else {
		return "energy Monitor Function not enabled.  Data elements already null."
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
	logDebug("bindUnbind: ${bind}")
	def message = ""
	def meth = "cnCloud"
	if (type().contains("Bulb")) {
		meth = "smartlife.iot.common.cloud"
	}
	if (bind == null) {
		//	Set bind to true.  Attempt to get.
		//	If update fails, device is considered cloud only and true is correct.
		message += "Bind value null.  Set to true and attempt to get actual."
		device.updateSetting("bind", [type:"bool", value: true])
		sendCmd("""{"${meth}":{"get_info":{}}}""")
	} else if (bind) {
		message += "Attempting to Bind the Device to the Kasa Cloud."
		sendCmd("""{"${meth}":{"bind":{"username":"${parent.userName}",""" +
				""""password":"${parent.userPassword}"}},""" +
				""""${meth}":{"get_info":{}}}""")
	} else if (!bind) {
		message = "Attempting to Unbind the Device from the Kasa Cloud."
		sendCmd("""{"${meth}":{"unbind":""},""" +
				""""${meth}":{"get_info":{}}}""")
	}
	pauseExecution(5000)
	return message
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
		logDebug("setBindUnbind: Bind status set to ${bindState}")
	} else {
		logWarn("setBindUnbind: Unhandled response: ${cmdResp}")
	}
	runIn(1, setCommsType)
}

def setCommsType() {
	def commsType = device.currentValue("connection")
	logDebug("setCommsType: Ctype = ${commsType}, useCloud = ${useCloud}, bind = ${bind}")
	def message = ""
	if (useCloud) {
		if (parent.useKasaCloud && bind) {
			message = "Device set to use CLOUD communications."
			commsType = "CLOUD"
		} else {
			//	Not available due to app setting or device binding to cloud.
			logWarn("setCommsType: <b>Can't set to Kasa cloud communications.</b> Check items:" +
				    "\n\t1.\tKasa Integration Appication" +
				    "\n\t\t* set Interface to Kasa Cloud in the app and validate userName and password" +
					"\n\t\t* the token must not be null.")
			message = "ERROR: Can not use Cloud. Device set to use LAN communications."
			commsType = "LAN"
		}
	} else if (!useCloud) {
		if (!getDataValue("deviceIP")) {
			//	No IP set - may not be able to use device locally.
			logWarn("setCommsType: <b>Device IP is not available.</b>  Device IP not set. " +
				    "Go to Kasa Integration app and run Update Installed Devices.")
			message = "ERROR: No deviceIP.  Device set to use CLOUD communications."
			commsType = "CLOUD"
		} else {
			message = "Device set to use LAN communications."
			commsType = "LAN"
		}
	} else {
		logWarn("setCommsType: useCloud not set to valid value.")
		return
	}

	if (type().contains("Multi")) {
		parent.coordinate(getDataValue("deviceId"), getDataValue("plugNo"), 
						  "lanCloud", commsType)
	} else {
		setCommsData(commsType)
	}
}

def setCommsData(commsType) {
	logDebug("setCommsData: ${commsType}.")
	if (commsType == "CLOUD") {
		state.remove("lastLanCmd")
		device.updateSetting("useCloud", [type:"bool", value: true])
		sendEvent(name: "connection", value: "CLOUD")
	} else {
		state.lastLanCmd = """{"system":{"get_sysinfo":{}}}"""
		device.updateSetting("useCloud", [type:"bool", value: false])
		sendEvent(name: "connection", value: "LAN")
	}
	pauseExecution(1000)
}

//	===== Preset Sync Functions =====
def syncBulbPresets() {
	device.updateSetting("syncBulbs", [type:"bool", value: false])
//	runIn(1, sendBulbPresets)
	parent.syncBulbPresets(state.bulbPresets, type())
	return "Synching Bulb Presets with all Kasa Bulbs."
}

def xxsendBulbPresets() {
	parent.syncBulbPresets(state.bulbPresets, type())
}

def updatePresets(bulbPresets) {
	logDebug("updatePresets: Preset Bulb Data: ${bulbPresets}.")
	state.bulbPresets = bulbPresets
}

//	===== Utility Methods =====
private outputXOR(command) {
//	UDP Version
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

def debugOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("debugLogOff: Debug logging is off.")
}

def logWarn(msg){
	log.warn "[${type()} / ${driverVer()} / ${device.label}]| ${msg}"
}

//	===============================
//	===== Bulb Unique Methods =====
//	===============================
def service() {
	def service = "smartlife.iot.smartbulb.lightingservice"
	if (getDataValue("feature") == "lightStrip") { service = "smartlife.iot.lightStrip" }
	return service
}

def method() {
	def method = "transition_light_state"
	if (getDataValue("feature") == "lightStrip") { method = "set_light_state" }
	return method
}

def on() {
	logDebug("on: transition time = ${transition_Time}")
	def transTime = 1000 * transition_Time.toInteger()
	sendCmd("""{"${service()}":""" +
			"""{"${method()}":{"on_off":1,"transition_period":${transTime}}}}""")
}

def off() {
	logDebug("off: transition time = ${transition_Time}")
	def transTime = 1000 * transition_Time.toInteger()
	sendCmd("""{"${service()}":""" +
			"""{"${method()}":{"on_off":0,"transition_period":${transTime}}}}""")
}

def setLevel(level, transTime = transition_Time.toInteger()) {
	if (level < 0) { level = 0 }
	else if (level > 100) { level = 100 }

	logDebug("setLevel: ${level} // ${transTime}")
	transTime = 1000*transTime
	sendCmd("""{"${service()}":""" +
			"""{"${method()}":{"ignore_default":1,"on_off":1,""" +
			""""brightness":${level},"transition_period":${transTime}}}}""")
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

def setColorTemperature(colorTemp, level = device.currentValue("level"), transTime = transition_Time.toInteger()) {
	logDebug("setColorTemperature: ${colorTemp} // ${level} // ${transTime}")
	transTime = 1000 * transTime
	def lowCt = 2500
	def highCt = 9000
	if (type() == "CT Bulb") {
		lowCt = 2700
		highCt = 6500
	}
	if (colorTemp < lowCt) { colorTemp = lowCt }
	else if (colorTemp > highCt) { colorTemp = highCt }
	sendCmd("""{"${service()}":{"${method()}":""" +
			"""{"ignore_default":1,"on_off":1,"brightness":${level},"color_temp":${colorTemp},""" +
			""""hue":0,"saturation":0,"transition_period":${transTime}}}}""")
}

def setCircadian() {
	logDebug("setCircadian")
	sendCmd("""{"${service()}":""" +
			"""{"${method()}":{"mode":"circadian"}}}""")
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
	logDebug("setColor:  ${color} // ${transition_Time}")
	def transTime = 1000 * transition_Time.toInteger()
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
	sendCmd("""{"${service()}":{"${method()}":""" +
			"""{"ignore_default":1,"on_off":1,"brightness":${level},"color_temp":0,""" +
			""""hue":${hue},"saturation":${saturation},"transition_period":${transTime}}}}""")
}

def refresh() {
	logDebug("refresh")
	poll()
}

def poll() {
	sendCmd("""{"system":{"get_sysinfo":{}}}""")
}

//	===== Capability Bulb Presets =====
def bulbPresetCreate(psName) {
	if (!state.bulbPresets) { state.bulbPresets = [:] }
	psName = psName.trim()
	logDebug("bulbPresetCreate: ${psName}")
	def psData = [:]
	psData["hue"] = device.currentValue("hue")
	psData["saturation"] = device.currentValue("saturation")
	psData["level"] = device.currentValue("level")
	def colorTemp = device.currentValue("colorTemperature")
	if (colorTemp == null) { colorTemp = 0 }
	psData["colTemp"] = colorTemp
	state.bulbPresets << ["${psName}": psData]
}

def bulbPresetDelete(psName) {
	psName = psName.trim()
	logDebug("bulbPresetDelete: ${psName}")
	def presets = state.bulbPresets
	if (presets.toString().contains(psName)) {
		presets.remove(psName)
	} else {
		logWarn("bulbPresetDelete: ${psName} is not a valid name.")
	}
}

def bulbPresetSet(psName, transTime = transition_Time) {
	psName = psName.trim()
	transTime = 1000 * transTime.toInteger()
	if (state.bulbPresets."${psName}") {
		def psData = state.bulbPresets."${psName}"
		logDebug("bulbPresetSet: ${psData}, transTime = ${transTime}")
		def hue = psData.hue
		if (highRes != true) {
			hue = Math.round(0.49 + hue * 3.6).toInteger()
		}
		sendCmd("""{"${service()}":{"${method()}":{"ignore_default":1,"on_off":1,""" +
				""""brightness":${psData.level},"color_temp":${psData.colTemp},""" +
				""""hue":${hue},"saturation":${psData.saturation},"transition_period":${transTime}}}}""")
} else {
		logWarn("bulbPresetSet: ${psName} is not a valid name.")
	}
}

//	===== Update Data =====
def updateBulbData(status) {
	logDebug("updateBulbData: ${status}")
	if (status.err_code && status.err_code != 0) {
		logWarn("updateBulbData: ${status.err_msg}")
		return
	}
	def deviceStatus = [:]
	def onOff = "on"
	if (status.on_off == 0) { onOff = "off" }
	deviceStatus << ["power" : onOff]
	def isChange = "false"
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff, type: "digital")
		isChange = true
	}
	if (onOff == "on") {
		deviceStatus << ["level" : status.brightness]
		if (status.brightness != device.currentValue("level")) {
			sendEvent(name: "level", value: status.brightness, unit: "%")
			isChange = true
		}
		if (type() != "Mono Bulb") {
			deviceStatus << ["mode" : status.mode]
			if (device.currentValue("circadianState") != status.mode) {
				sendEvent(name: "circadianState", value: status.mode)
				isChange = true
			}
			def ct = status.color_temp
			deviceStatus << ["colorTemp" : ct]
			if (device.currentValue("colorTemperature") != ct) {
				isChange = true
				sendEvent(name: "colorTemperature", value: ct)
			}
			def hue = status.hue.toInteger()
			if (ct == "0") { hue = 0 }
			if (highRes != true) { hue = (hue / 3.6).toInteger() }
			deviceStatus << ["hue" : hue]
			if (device.currentValue("hue") != hue) {
				sendEvent(name: "hue", value: hue)
				isChange = true
			}
			deviceStatus << ["sat" : status.saturation]
			if (ct == "0") { saturation = 0 }
			if (device.currentValue("saturation") != status.saturation) {
				sendEvent(name: "saturation", value: status.saturation)
				isChange = true
			}
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
				setRgbData(hue) }
			else { 
				setColorTempData(status.color_temp) 
			}
		}
	}
	if (isChange) {
		logInfo("updateBulbData: Status = ${deviceStatus}")
	}
}

def setColorTempData(temp){
	logDebug("setColorTempData: color temperature = ${temp}")
    def value = temp.toInteger()
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

def setRgbData(hue){
	logDebug("setRgbData: hue = ${hue} // highRes = ${highRes}")
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

//	End of File