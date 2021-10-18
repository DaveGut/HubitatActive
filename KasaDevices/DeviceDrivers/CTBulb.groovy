/*	Kasa Device Driver Series

		Copyright Dave Gutheinz

License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md

===== Version 6.4.1 =====
1.  Switched to Library-based development.  Groovy file will have a lot of comments
	related to importing the library methods into the driver for publication.
2.	Added bulb and lightStrip preset capabilities.
3.	Modified LANcommunications timeouts and error handling to account for changes 
	in Hubitat platform.
===================================================================================================*/
def driverVer() { return "6.4.1" }
//def type() { return "Color Bulb" }
def type() { return "CT Bulb" }
//def type() { return "Mono Bulb" }
def file() { return type().replaceAll(" ", "") }

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

def installed() {
	def msg = "installed: "
	if (parent.useKasaCloud) {
		msg += "Installing as CLOUD device. "
		msg += "<b>\n\t\t\tif device is not bound to the cloud, the device may not "
		msg += "work! SEt Preferences 'Use Kasa Cloud for device control'.</b>"
		device.updateSetting("useCloud", [type:"bool", value: true])
		sendEvent(name: "connection", value: "CLOUD")
	} else {
		msg += "Installing as LAN device. "
		sendEvent(name: "connection", value: "LAN")
		device.updateSetting("useCloud", [type:"bool", value: false])
	}
	sendEvent(name: "commsError", value: "false")
	state.errorCount = 0
	state.pollInterval = "30 minutes"
	if (type() == "colorBulb") { state.bulbPresets = [:] }
	updateDataValue("driverVersion", driverVer())
	runIn(2, updated)
	logInfo(msg)
}

def updated() {
	if (rebootDev) {
		logWarn("updated: ${rebootDevice()}")
		return
	}
	if (syncBulbs) {
		logDebug("updated: ${syncBulbPresets()}")
		return
	}
	unschedule()
	def updStatus = [:]
	if (debug) { runIn(1800, debugOff) }
	updStatus << [debug: debug]
	updStatus << [descriptionText: descriptionText]
	updStatus << [transition_Time: "${transition_Time} seconds"]
	state.errorCount = 0
	sendEvent(name: "commsError", value: "false")
	updStatus << [bind: bindUnbind()]
	updStatus << [emFunction: setupEmFunction()]
	updStatus << [pollInterval: setPollInterval()]
	updStatus << [driverVersion: updateDriverData()]
	log.info "[${type()}, ${driverVer()}, ${device.label}]  updated: ${updStatus}"
	runIn(3, refresh)
}

def updateDriverData() {
	if (drvVer == !driverVer()) {
		state.remove("lastLanCmd")
		state.remove("commsErrorText")
		if (!state.bulbPresets) { state.bulbPresets = [:] }
		def commsType = "LAN"
		if (useCloud == true) { commsType = "CLOUD" }
		setCommsData(comType)
		if (!state.bulbPresets) { state.bulbPresets = [:] }
		updateDataValue("driverVersion", driverVer())
	}
	return driverVer()
}

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
	if (transTime == null) { transTime = 0 }
	def transTime = 1000 * transition_Time.toInteger()
	sendCmd("""{"${service()}":""" +
			"""{"${method()}":{"on_off":1,"transition_period":${transTime}}},""" +
			""""smartlife.iot.common.emeter":{"get_realtime":{}}}""")
}

def off() {
	logDebug("off: transition time = ${transition_Time}")
	if (transTime == null) { transTime = 0 }
	def transTime = 1000 * transition_Time.toInteger()
	sendCmd("""{"${service()}":""" +
			"""{"${method()}":{"on_off":0,"transition_period":${transTime}}},""" +
			""""smartlife.iot.common.emeter":{"get_realtime":{}}}""")
}

def setLevel(level, transTime = transition_Time.toInteger()) {
	if (level < 0) { level = 0 }
	else if (level > 100) { level = 100 }

	logDebug("setLevel: ${level} // ${transTime}")
	if (transTime == null) { transTime = 0 }
	transTime = 1000*transTime
	sendCmd("""{"${service()}":""" +
			"""{"${method()}":{"ignore_default":1,"on_off":1,""" +
			""""brightness":${level},"transition_period":${transTime}}},""" +
			""""smartlife.iot.common.emeter":{"get_realtime":{}}}""")
}

def setColorTemperature(colorTemp, level = device.currentValue("level"), transTime = transition_Time.toInteger()) {
	logDebug("setColorTemperature: ${colorTemp} // ${level} // ${transTime}")
	if (transTime == null) { transTime = 0 }
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
			""""hue":0,"saturation":0,"transition_period":${transTime}}},""" +
			""""smartlife.iot.common.emeter":{"get_realtime":{}}}""")
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
	if (transTime == null) { transTime = 0 }
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
	hue = Math.round(0.49 + hue * 3.6).toInteger()
	if (hue < 0 || hue > 360 || saturation < 0 || saturation > 100 || level < 0 || level > 100) {
		logWarn("setColor: Entered hue, saturation, or level out of range! (H:${hue}, S:${saturation}, L:${level}")
        return
    }
	sendCmd("""{"${service()}":{"${method()}":""" +
			"""{"ignore_default":1,"on_off":1,"brightness":${level},"color_temp":0,""" +
			""""hue":${hue},"saturation":${saturation},"transition_period":${transTime}}},""" +
			""""smartlife.iot.common.emeter":{"get_realtime":{}}}""")
}

def refresh() {
	logDebug("refresh")
	poll()
}

def poll() {
	sendCmd("""{"system":{"get_sysinfo":{}}}""")
}

def bulbPresetSet(psName, transTime = transition_Time) {
	psName = psName.trim()
	if (transTime == null) { transTime = 0 }
	transTime = 1000 * transTime.toInteger()
	if (state.bulbPresets."${psName}") {
		def psData = state.bulbPresets."${psName}"
		logDebug("bulbPresetSet: ${psData}, transTime = ${transTime}")
		def hue = psData.hue
		hue = Math.round(0.49 + hue * 3.6).toInteger()
		sendCmd("""{"${service()}":{"${method()}":{"ignore_default":1,"on_off":1,""" +
				""""brightness":${psData.level},"color_temp":${psData.colTemp},""" +
				""""hue":${hue},"saturation":${psData.saturation},"transition_period":${transTime}}},""" +
				""""smartlife.iot.common.emeter":{"get_realtime":{}}}""")
} else {
		logWarn("bulbPresetSet: ${psName} is not a valid name.")
	}
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
		def level = status.brightness
		if (level != device.currentValue("level")) {
			deviceStatus << ["level" : status.brightness]
			sendEvent(name: "level", value: status.brightness, unit: "%")
			isChange = true
		}
		if (device.currentValue("circadianState") != status.mode) {
			deviceStatus << ["mode" : status.mode]
			sendEvent(name: "circadianState", value: status.mode)
			isChange = true
		}
		def ct = status.color_temp.toInteger()
		def hue = status.hue.toInteger()
		hubHue = (hue / 3.6).toInteger()
		def colorMode
		def colorName
		def color = "{:}"
		if (ct == 0) {
			colorMode = "RGB"
			colorName = getColorName(hue)
			color = "{hue: ${hubHue},saturation:${status.saturation},level: ${status.brightness}}"
		} else {
			colorMode = "CT"
			colorName = getCtName(ct)
		}
		
		if (device.currentValue("colorTemperature") != ct) {
			isChange = true
			deviceStatus << ["colorTemp" : ct]
			sendEvent(name: "colorTemperature", value: ct)
		}
		if (color != device.currentValue("color")) {
			isChange = true
			deviceStatus << ["color" : color]
			sendEvent(name: "hue", value: hubHue)
			sendEvent(name: "saturation", value: status.saturation)
			sendEvent(name: "color", value: color)
		}
		if (device.currentValue("colorName") != colorName) {
			deviceStatus << ["colorName" : colorName]
			deviceStatus << ["colorMode" : colorMode]
			sendEvent(name: "colorMode", value: colorMode)
		    sendEvent(name: "colorName", value: colorName)
		}
	}
	if (isChange == true) {
		logInfo("updateBulbData: Status = ${deviceStatus}")
	}
}

//	========================================================
//	===== Communications ===================================

//	========================================================
//	===== Energy Monitor ===================================

//	========================================================
//	===== Preferences and Update ===========================

//	========================================================
//	===== Bulb and Light Strip Tools =======================


// ~~~~~ start include (97) davegut.kasaCommunications ~~~~~
import groovy.json.JsonSlurper // library marker davegut.kasaCommunications, line 1
library ( // library marker davegut.kasaCommunications, line 2
	name: "kasaCommunications", // library marker davegut.kasaCommunications, line 3
	namespace: "davegut", // library marker davegut.kasaCommunications, line 4
	author: "Dave Gutheinz", // library marker davegut.kasaCommunications, line 5
	description: "Kasa Communications Methods", // library marker davegut.kasaCommunications, line 6
	category: "communications", // library marker davegut.kasaCommunications, line 7
	documentationLink: "" // library marker davegut.kasaCommunications, line 8
) // library marker davegut.kasaCommunications, line 9
def sendCmd(command) { // library marker davegut.kasaCommunications, line 10
	if (device.currentValue("connection") == "LAN") { // library marker davegut.kasaCommunications, line 11
		sendLanCmd(command) // library marker davegut.kasaCommunications, line 12
	} else if (device.currentValue("connection") == "CLOUD"){ // library marker davegut.kasaCommunications, line 13
		sendKasaCmd(command) // library marker davegut.kasaCommunications, line 14
	} else { // library marker davegut.kasaCommunications, line 15
		logWarn("sendCmd: attribute connection not set.") // library marker davegut.kasaCommunications, line 16
	} // library marker davegut.kasaCommunications, line 17
} // library marker davegut.kasaCommunications, line 18

def sendLanCmd(command) { // library marker davegut.kasaCommunications, line 20
	logDebug("sendLanCmd: command = ${command}") // library marker davegut.kasaCommunications, line 21
logTrace("sendLanCmd: command = ${command}") // library marker davegut.kasaCommunications, line 22
	state.lastCommand = command // library marker davegut.kasaCommunications, line 23
	def myHubAction = new hubitat.device.HubAction( // library marker davegut.kasaCommunications, line 24
		outputXOR(command), // library marker davegut.kasaCommunications, line 25
		hubitat.device.Protocol.LAN, // library marker davegut.kasaCommunications, line 26
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT, // library marker davegut.kasaCommunications, line 27
		 destinationAddress: "${getDataValue("deviceIP")}:9999", // library marker davegut.kasaCommunications, line 28
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING, // library marker davegut.kasaCommunications, line 29
		 parseWarning: true, // library marker davegut.kasaCommunications, line 30
		 timeout: 5]) // library marker davegut.kasaCommunications, line 31
	try { // library marker davegut.kasaCommunications, line 32
		sendHubCommand(myHubAction) // library marker davegut.kasaCommunications, line 33
	} catch (e) { // library marker davegut.kasaCommunications, line 34
		logWarn("sendLanCmd: LAN Error = ${e}") // library marker davegut.kasaCommunications, line 35
		handleCommsError() // library marker davegut.kasaCommunications, line 36
	} // library marker davegut.kasaCommunications, line 37
} // library marker davegut.kasaCommunications, line 38

def parse(message) { // library marker davegut.kasaCommunications, line 40
	def resp = parseLanMessage(message) // library marker davegut.kasaCommunications, line 41
log.trace resp.type // library marker davegut.kasaCommunications, line 42
	if (resp.type == "LAN_TYPE_UDPCLIENT") { // library marker davegut.kasaCommunications, line 43
		def clearResp = inputXOR(resp.payload) // library marker davegut.kasaCommunications, line 44
		if (clearResp.length() > 1022) { // library marker davegut.kasaCommunications, line 45
			clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}" // library marker davegut.kasaCommunications, line 46
		} // library marker davegut.kasaCommunications, line 47
		def cmdResp = new JsonSlurper().parseText(clearResp) // library marker davegut.kasaCommunications, line 48
		distResp(cmdResp) // library marker davegut.kasaCommunications, line 49
	} else { // library marker davegut.kasaCommunications, line 50
		logWarn("parse: LAN Error = ${resp.type}") // library marker davegut.kasaCommunications, line 51
		handleCommsError() // library marker davegut.kasaCommunications, line 52
	} // library marker davegut.kasaCommunications, line 53
} // library marker davegut.kasaCommunications, line 54

def sendKasaCmd(command) { // library marker davegut.kasaCommunications, line 56
	logDebug("sendKasaCmd: ${command}") // library marker davegut.kasaCommunications, line 57
	state.lastCommand = command // library marker davegut.kasaCommunications, line 58
	runIn(5, handleCommsError) // library marker davegut.kasaCommunications, line 59
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 60
	def cmdBody = [ // library marker davegut.kasaCommunications, line 61
		method: "passthrough", // library marker davegut.kasaCommunications, line 62
		params: [ // library marker davegut.kasaCommunications, line 63
			deviceId: getDataValue("deviceId"), // library marker davegut.kasaCommunications, line 64
			requestData: "${command}" // library marker davegut.kasaCommunications, line 65
		] // library marker davegut.kasaCommunications, line 66
	] // library marker davegut.kasaCommunications, line 67
	def sendCloudCmdParams = [ // library marker davegut.kasaCommunications, line 68
		uri: "${parent.kasaCloudUrl}/?token=${parent.kasaToken}", // library marker davegut.kasaCommunications, line 69
		requestContentType: 'application/json', // library marker davegut.kasaCommunications, line 70
		contentType: 'application/json', // library marker davegut.kasaCommunications, line 71
		headers: ['Accept':'application/json; version=1, */*; q=0.01'], // library marker davegut.kasaCommunications, line 72
		timeout: 5, // library marker davegut.kasaCommunications, line 73
		body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.kasaCommunications, line 74
	] // library marker davegut.kasaCommunications, line 75
	try { // library marker davegut.kasaCommunications, line 76
		httpPostJson(sendCloudCmdParams) {resp -> // library marker davegut.kasaCommunications, line 77
			if (resp.status == 200 && resp.data.error_code == 0) { // library marker davegut.kasaCommunications, line 78
				def jsonSlurper = new groovy.json.JsonSlurper() // library marker davegut.kasaCommunications, line 79
				distResp(jsonSlurper.parseText(resp.data.result.responseData)) // library marker davegut.kasaCommunications, line 80
			} else { // library marker davegut.kasaCommunications, line 81
				def errMsg = "CLOUD Error = ${resp.data}" // library marker davegut.kasaCommunications, line 82
				logWarn("sendKasaCmd: ${errMsg}]") // library marker davegut.kasaCommunications, line 83
			} // library marker davegut.kasaCommunications, line 84
		} // library marker davegut.kasaCommunications, line 85
	} catch (e) { // library marker davegut.kasaCommunications, line 86
		def errMsg = "CLOUD Error = ${e}" // library marker davegut.kasaCommunications, line 87
		logWarn("sendKasaCmd: ${errMsg}]") // library marker davegut.kasaCommunications, line 88
	} // library marker davegut.kasaCommunications, line 89
} // library marker davegut.kasaCommunications, line 90

def handleCommsError() { // library marker davegut.kasaCommunications, line 92
	def count = state.errorCount + 1 // library marker davegut.kasaCommunications, line 93
	state.errorCount = count // library marker davegut.kasaCommunications, line 94
	def message = "handleCommsError: Count: ${count}." // library marker davegut.kasaCommunications, line 95
	if (count <= 3) { // library marker davegut.kasaCommunications, line 96
		message += "\n\t\t\t Retransmitting command, try = ${count}" // library marker davegut.kasaCommunications, line 97
		runIn(1, sendCmd, [data: state.lastCommand]) // library marker davegut.kasaCommunications, line 98
	} else if (count == 4) { // library marker davegut.kasaCommunications, line 99
		setCommsError() // library marker davegut.kasaCommunications, line 100
		message += "\n\t\t\t Setting Comms Error." // library marker davegut.kasaCommunications, line 101
	} // library marker davegut.kasaCommunications, line 102
	logDebug(message) // library marker davegut.kasaCommunications, line 103
} // library marker davegut.kasaCommunications, line 104

def setCommsError() { // library marker davegut.kasaCommunications, line 106
	def message = "setCommsError: Four consecutive errors.  Setting commsError to true." // library marker davegut.kasaCommunications, line 107
	message += "\n\t\t<b>ErrorData = ${ErrorData}</b>." // library marker davegut.kasaCommunications, line 108
	if (device.currentValue("commsError") == "false") { // library marker davegut.kasaCommunications, line 109
		sendEvent(name: "commsError", value: "true") // library marker davegut.kasaCommunications, line 110
		message += "\n\t\t${parent.fixConnection(device.currentValue("connection"))}" // library marker davegut.kasaCommunications, line 111
		logWarn message // library marker davegut.kasaCommunications, line 112
	} // library marker davegut.kasaCommunications, line 113
} // library marker davegut.kasaCommunications, line 114

def resetCommsError() { // library marker davegut.kasaCommunications, line 116
	unschedule(handleCommsError) // library marker davegut.kasaCommunications, line 117
	sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommunications, line 118
	state.errorCount = 0 // library marker davegut.kasaCommunications, line 119
} // library marker davegut.kasaCommunications, line 120

private outputXOR(command) { // library marker davegut.kasaCommunications, line 122
	def str = "" // library marker davegut.kasaCommunications, line 123
	def encrCmd = "" // library marker davegut.kasaCommunications, line 124
 	def key = 0xAB // library marker davegut.kasaCommunications, line 125
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 126
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 127
		key = str // library marker davegut.kasaCommunications, line 128
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 129
	} // library marker davegut.kasaCommunications, line 130
   	return encrCmd // library marker davegut.kasaCommunications, line 131
} // library marker davegut.kasaCommunications, line 132

private inputXOR(encrResponse) { // library marker davegut.kasaCommunications, line 134
	String[] strBytes = encrResponse.split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 135
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 136
	def key = 0xAB // library marker davegut.kasaCommunications, line 137
	def nextKey // library marker davegut.kasaCommunications, line 138
	byte[] XORtemp // library marker davegut.kasaCommunications, line 139
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 140
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 141
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 142
		key = nextKey // library marker davegut.kasaCommunications, line 143
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 144
	} // library marker davegut.kasaCommunications, line 145
	return cmdResponse // library marker davegut.kasaCommunications, line 146
} // library marker davegut.kasaCommunications, line 147

def logTrace(msg){ // library marker davegut.kasaCommunications, line 149
	log.trace "[${type()} / ${driverVer()} / ${device.label}]| ${msg}" // library marker davegut.kasaCommunications, line 150
} // library marker davegut.kasaCommunications, line 151

def logInfo(msg) { // library marker davegut.kasaCommunications, line 153
	if (descriptionText == true) {  // library marker davegut.kasaCommunications, line 154
		log.info "[${type()} / ${driverVer()} / ${device.label}]| ${msg}" // library marker davegut.kasaCommunications, line 155
	} // library marker davegut.kasaCommunications, line 156
} // library marker davegut.kasaCommunications, line 157

def logDebug(msg){ // library marker davegut.kasaCommunications, line 159
	if(debug == true) { // library marker davegut.kasaCommunications, line 160
		log.debug "[${type()} / ${driverVer()} / ${device.label}]| ${msg}" // library marker davegut.kasaCommunications, line 161
	} // library marker davegut.kasaCommunications, line 162
} // library marker davegut.kasaCommunications, line 163

def debugOff() { // library marker davegut.kasaCommunications, line 165
	device.updateSetting("debug", [type:"bool", value: false]) // library marker davegut.kasaCommunications, line 166
	logInfo("debugLogOff: Debug logging is off.") // library marker davegut.kasaCommunications, line 167
} // library marker davegut.kasaCommunications, line 168

def logWarn(msg){ // library marker davegut.kasaCommunications, line 170
	log.warn "[${type()} / ${driverVer()} / ${device.label}]| ${msg}" // library marker davegut.kasaCommunications, line 171
} // library marker davegut.kasaCommunications, line 172

// ~~~~~ end include (97) davegut.kasaCommunications ~~~~~

// ~~~~~ start include (98) davegut.kasaEnergyMonitor ~~~~~
library ( // library marker davegut.kasaEnergyMonitor, line 1
	name: "kasaEnergyMonitor", // library marker davegut.kasaEnergyMonitor, line 2
	namespace: "davegut", // library marker davegut.kasaEnergyMonitor, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaEnergyMonitor, line 4
	description: "Kasa energy monitor routines", // library marker davegut.kasaEnergyMonitor, line 5
	category: "energyMonitor", // library marker davegut.kasaEnergyMonitor, line 6
	documentationLink: "" // library marker davegut.kasaEnergyMonitor, line 7
) // library marker davegut.kasaEnergyMonitor, line 8

def setupEmFunction() { // library marker davegut.kasaEnergyMonitor, line 10
	if (emFunction) { // library marker davegut.kasaEnergyMonitor, line 11
		sendEvent(name: "power", value: 0) // library marker davegut.kasaEnergyMonitor, line 12
		sendEvent(name: "energy", value: 0) // library marker davegut.kasaEnergyMonitor, line 13
		sendEvent(name: "currMonthTotal", value: 0) // library marker davegut.kasaEnergyMonitor, line 14
		sendEvent(name: "currMonthAvg", value: 0) // library marker davegut.kasaEnergyMonitor, line 15
		sendEvent(name: "lastMonthTotal", value: 0) // library marker davegut.kasaEnergyMonitor, line 16
		sendEvent(name: "lastMonthAvg", value: 0) // library marker davegut.kasaEnergyMonitor, line 17
		def start = Math.round(30 * Math.random()).toInteger() // library marker davegut.kasaEnergyMonitor, line 18
		schedule("${start} */30 * * * ?", getEnergyToday) // library marker davegut.kasaEnergyMonitor, line 19
		runIn(1, getEnergyToday) // library marker davegut.kasaEnergyMonitor, line 20
		return "Initialized" // library marker davegut.kasaEnergyMonitor, line 21
	} else if (device.currentValue("power") != null) { // library marker davegut.kasaEnergyMonitor, line 22
		sendEvent(name: "power", value: 0) // library marker davegut.kasaEnergyMonitor, line 23
		sendEvent(name: "energy", value: 0) // library marker davegut.kasaEnergyMonitor, line 24
		sendEvent(name: "currMonthTotal", value: 0) // library marker davegut.kasaEnergyMonitor, line 25
		sendEvent(name: "currMonthAvg", value: 0) // library marker davegut.kasaEnergyMonitor, line 26
		sendEvent(name: "lastMonthTotal", value: 0) // library marker davegut.kasaEnergyMonitor, line 27
		sendEvent(name: "lastMonthAvg", value: 0) // library marker davegut.kasaEnergyMonitor, line 28
		if (type().contains("Multi")) { // library marker davegut.kasaEnergyMonitor, line 29
			state.remove("powerPollInterval") // library marker davegut.kasaEnergyMonitor, line 30
		} // library marker davegut.kasaEnergyMonitor, line 31
		return "Disabled" // library marker davegut.kasaEnergyMonitor, line 32
	} else { // library marker davegut.kasaEnergyMonitor, line 33
		return "Disabled" // library marker davegut.kasaEnergyMonitor, line 34
	} // library marker davegut.kasaEnergyMonitor, line 35
} // library marker davegut.kasaEnergyMonitor, line 36

def getPower() { // library marker davegut.kasaEnergyMonitor, line 38
	logDebug("getPower") // library marker davegut.kasaEnergyMonitor, line 39
	if (type().contains("Multi")) { // library marker davegut.kasaEnergyMonitor, line 40
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 41
				""""emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 42
	} else if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaEnergyMonitor, line 43
		sendCmd("""{"smartlife.iot.common.emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 44
	} else { // library marker davegut.kasaEnergyMonitor, line 45
		sendCmd("""{"emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 46
	} // library marker davegut.kasaEnergyMonitor, line 47
} // library marker davegut.kasaEnergyMonitor, line 48

def setPower(response) { // library marker davegut.kasaEnergyMonitor, line 50
log.trace response // library marker davegut.kasaEnergyMonitor, line 51
	def power = response.power // library marker davegut.kasaEnergyMonitor, line 52
	if (power == null) { power = response.power_mw / 1000 } // library marker davegut.kasaEnergyMonitor, line 53
	power = Math.round(10*(power))/10 // library marker davegut.kasaEnergyMonitor, line 54
	def curPwr = device.currentValue("power") // library marker davegut.kasaEnergyMonitor, line 55
	if (curPwr < 5 && (power > curPwr + 0.3 || power < curPwr - 0.3)) { // library marker davegut.kasaEnergyMonitor, line 56
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital") // library marker davegut.kasaEnergyMonitor, line 57
		logDebug("polResp: power = ${power}") // library marker davegut.kasaEnergyMonitor, line 58
	} else if (power > curPwr + 5 || power < curPwr - 5) { // library marker davegut.kasaEnergyMonitor, line 59
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital") // library marker davegut.kasaEnergyMonitor, line 60
		logDebug("polResp: power = ${power}") // library marker davegut.kasaEnergyMonitor, line 61
	} // library marker davegut.kasaEnergyMonitor, line 62
} // library marker davegut.kasaEnergyMonitor, line 63

def getEnergyToday() { // library marker davegut.kasaEnergyMonitor, line 65
	logDebug("getEnergyToday") // library marker davegut.kasaEnergyMonitor, line 66
	def year = new Date().format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 67
	if (type().contains("Multi")) { // library marker davegut.kasaEnergyMonitor, line 68
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 69
				""""emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 70
	} else if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaEnergyMonitor, line 71
		sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 72
	} else { // library marker davegut.kasaEnergyMonitor, line 73
		sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 74
	} // library marker davegut.kasaEnergyMonitor, line 75
} // library marker davegut.kasaEnergyMonitor, line 76

def setEnergyToday(response) { // library marker davegut.kasaEnergyMonitor, line 78
	logDebug("setEnergyToday: response = ${response}") // library marker davegut.kasaEnergyMonitor, line 79
	def month = new Date().format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 80
	def data = response.month_list.find { it.month == month } // library marker davegut.kasaEnergyMonitor, line 81
	def energy = data.energy // library marker davegut.kasaEnergyMonitor, line 82
	if (energy == null) { energy = data.energy_wh/1000 } // library marker davegut.kasaEnergyMonitor, line 83
	energy -= device.currentValue("currMonthTotal") // library marker davegut.kasaEnergyMonitor, line 84
	energy = Math.round(100*energy)/100 // library marker davegut.kasaEnergyMonitor, line 85
	def currEnergy = device.currentValue("energy") // library marker davegut.kasaEnergyMonitor, line 86
	if (currEnergy < energy + 0.05) { // library marker davegut.kasaEnergyMonitor, line 87
		sendEvent(name: "energy", value: energy, descriptionText: "KiloWatt Hours", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 88
		logDebug("setEngrToday: [energy: ${energy}]") // library marker davegut.kasaEnergyMonitor, line 89
	} // library marker davegut.kasaEnergyMonitor, line 90
	setThisMonth(response) // library marker davegut.kasaEnergyMonitor, line 91
} // library marker davegut.kasaEnergyMonitor, line 92

def setThisMonth(response) { // library marker davegut.kasaEnergyMonitor, line 94
	logDebug("setThisMonth: response = ${response}") // library marker davegut.kasaEnergyMonitor, line 95
	def month = new Date().format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 96
	def day = new Date().format("d").toInteger() // library marker davegut.kasaEnergyMonitor, line 97
	def data = response.month_list.find { it.month == month } // library marker davegut.kasaEnergyMonitor, line 98
	def totEnergy = data.energy // library marker davegut.kasaEnergyMonitor, line 99
	if (totEnergy == null) {  // library marker davegut.kasaEnergyMonitor, line 100
		totEnergy = data.energy_wh/1000 // library marker davegut.kasaEnergyMonitor, line 101
	} // library marker davegut.kasaEnergyMonitor, line 102
	totEnergy = Math.round(100*totEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 103
	def avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 104
	if (day != 1) {  // library marker davegut.kasaEnergyMonitor, line 105
		avgEnergy = totEnergy /(day - 1)  // library marker davegut.kasaEnergyMonitor, line 106
	} // library marker davegut.kasaEnergyMonitor, line 107
	avgEnergy = Math.round(100*avgEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 108

	sendEvent(name: "currMonthTotal", value: totEnergy,  // library marker davegut.kasaEnergyMonitor, line 110
			  descriptionText: "KiloWatt Hours", unit: "kWh") // library marker davegut.kasaEnergyMonitor, line 111
	sendEvent(name: "currMonthAvg", value: avgEnergy,  // library marker davegut.kasaEnergyMonitor, line 112
			  descriptionText: "KiloWatt Hours per Day", unit: "kWh/D") // library marker davegut.kasaEnergyMonitor, line 113
	logDebug("setThisMonth: Energy stats set to ${totEnergy} // ${avgEnergy}") // library marker davegut.kasaEnergyMonitor, line 114
	if (month != 1) { // library marker davegut.kasaEnergyMonitor, line 115
		setLastMonth(response) // library marker davegut.kasaEnergyMonitor, line 116
	} else { // library marker davegut.kasaEnergyMonitor, line 117
		def year = new Date().format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 118
		if (type().contains("Multi")) { // library marker davegut.kasaEnergyMonitor, line 119
			sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 120
					""""emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 121
		} else if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaEnergyMonitor, line 122
			sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 123
		} else { // library marker davegut.kasaEnergyMonitor, line 124
			sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 125
		} // library marker davegut.kasaEnergyMonitor, line 126
	} // library marker davegut.kasaEnergyMonitor, line 127
} // library marker davegut.kasaEnergyMonitor, line 128

def setLastMonth(response) { // library marker davegut.kasaEnergyMonitor, line 130
	logDebug("setLastMonth: response = ${response}") // library marker davegut.kasaEnergyMonitor, line 131
	def year = new Date().format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 132
	def month = new Date().format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 133
	def day = new Date().format("d").toInteger() // library marker davegut.kasaEnergyMonitor, line 134
	def lastMonth // library marker davegut.kasaEnergyMonitor, line 135
	if (month == 1) { // library marker davegut.kasaEnergyMonitor, line 136
		lastMonth = 12 // library marker davegut.kasaEnergyMonitor, line 137
	} else { // library marker davegut.kasaEnergyMonitor, line 138
		lastMonth = month - 1 // library marker davegut.kasaEnergyMonitor, line 139
	} // library marker davegut.kasaEnergyMonitor, line 140
	def monthLength // library marker davegut.kasaEnergyMonitor, line 141
	switch(lastMonth) { // library marker davegut.kasaEnergyMonitor, line 142
		case 4: // library marker davegut.kasaEnergyMonitor, line 143
		case 6: // library marker davegut.kasaEnergyMonitor, line 144
		case 9: // library marker davegut.kasaEnergyMonitor, line 145
		case 11: // library marker davegut.kasaEnergyMonitor, line 146
			monthLength = 30 // library marker davegut.kasaEnergyMonitor, line 147
			break // library marker davegut.kasaEnergyMonitor, line 148
		case 2: // library marker davegut.kasaEnergyMonitor, line 149
			monthLength = 28 // library marker davegut.kasaEnergyMonitor, line 150
			if (year == 2020 || year == 2024 || year == 2028) { monthLength = 29 } // library marker davegut.kasaEnergyMonitor, line 151
			break // library marker davegut.kasaEnergyMonitor, line 152
		default: // library marker davegut.kasaEnergyMonitor, line 153
			monthLength = 31 // library marker davegut.kasaEnergyMonitor, line 154
	} // library marker davegut.kasaEnergyMonitor, line 155
	def data = response.month_list.find { it.month == lastMonth } // library marker davegut.kasaEnergyMonitor, line 156
	def totEnergy // library marker davegut.kasaEnergyMonitor, line 157
	if (data == null) { // library marker davegut.kasaEnergyMonitor, line 158
		totEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 159
	} else { // library marker davegut.kasaEnergyMonitor, line 160
		totEnergy = data.energy // library marker davegut.kasaEnergyMonitor, line 161
		if (totEnergy == null) {  // library marker davegut.kasaEnergyMonitor, line 162
			totEnergy = data.energy_wh/1000 // library marker davegut.kasaEnergyMonitor, line 163
		} // library marker davegut.kasaEnergyMonitor, line 164
		totEnergy = Math.round(100*totEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 165
	} // library marker davegut.kasaEnergyMonitor, line 166
	def avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 167
	if (day !=1) { // library marker davegut.kasaEnergyMonitor, line 168
		avgEnergy = totEnergy /(day - 1) // library marker davegut.kasaEnergyMonitor, line 169
	} // library marker davegut.kasaEnergyMonitor, line 170
	avgEnergy = Math.round(100*avgEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 171
	sendEvent(name: "lastMonthTotal", value: totEnergy,  // library marker davegut.kasaEnergyMonitor, line 172
			  descriptionText: "KiloWatt Hours", unit: "kWh") // library marker davegut.kasaEnergyMonitor, line 173
	sendEvent(name: "lastMonthAvg", value: avgEnergy,  // library marker davegut.kasaEnergyMonitor, line 174
			  descriptionText: "KiloWatt Hoursper Day", unit: "kWh/D") // library marker davegut.kasaEnergyMonitor, line 175
	logDebug("setLastMonth: Energy stats set to ${totEnergy} // ${avgEnergy}") // library marker davegut.kasaEnergyMonitor, line 176
} // library marker davegut.kasaEnergyMonitor, line 177

// ~~~~~ end include (98) davegut.kasaEnergyMonitor ~~~~~

// ~~~~~ start include (1) davegut.kasaPreferences ~~~~~
library ( // library marker davegut.kasaPreferences, line 1
	name: "kasaPreferences", // library marker davegut.kasaPreferences, line 2
	namespace: "davegut", // library marker davegut.kasaPreferences, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaPreferences, line 4
	description: "Kasa updated and preferences routines", // library marker davegut.kasaPreferences, line 5
	category: "energyMonitor", // library marker davegut.kasaPreferences, line 6
	documentationLink: "" // library marker davegut.kasaPreferences, line 7
) // library marker davegut.kasaPreferences, line 8

//	===== Preference Methods ===== // library marker davegut.kasaPreferences, line 10
def setPollInterval(interval = state.pollInterval) { // library marker davegut.kasaPreferences, line 11
	if (interval == "default" || interval == "off") { // library marker davegut.kasaPreferences, line 12
		interval = "30 minutes" // library marker davegut.kasaPreferences, line 13
	} else if (useCloud && interval.contains("sec")) { // library marker davegut.kasaPreferences, line 14
		interval = "1 minute" // library marker davegut.kasaPreferences, line 15
	} // library marker davegut.kasaPreferences, line 16
	state.pollInterval = interval // library marker davegut.kasaPreferences, line 17
	def pollInterval = interval.substring(0,2).toInteger() // library marker davegut.kasaPreferences, line 18
	if (interval.contains("sec")) { // library marker davegut.kasaPreferences, line 19
		def start = Math.round((pollInterval-1) * Math.random()).toInteger() // library marker davegut.kasaPreferences, line 20
		schedule("${start}/${pollInterval} * * * * ?", "poll") // library marker davegut.kasaPreferences, line 21
		state.pollWarning = "Polling intervals of less than one minute can take high " + // library marker davegut.kasaPreferences, line 22
			"resources and may impact hub performance." // library marker davegut.kasaPreferences, line 23
	} else { // library marker davegut.kasaPreferences, line 24
		def start = Math.round(59 * Math.random()).toInteger() // library marker davegut.kasaPreferences, line 25
		schedule("${start} */${pollInterval} * * * ?", "poll") // library marker davegut.kasaPreferences, line 26
		state.remove("pollWarning") // library marker davegut.kasaPreferences, line 27
	} // library marker davegut.kasaPreferences, line 28
	logDebug("setPollInterval: interval = ${interval}.") // library marker davegut.kasaPreferences, line 29
	return interval // library marker davegut.kasaPreferences, line 30
} // library marker davegut.kasaPreferences, line 31

def rebootDevice() { // library marker davegut.kasaPreferences, line 33
	logWarn("rebootDevice: User Commanded Reboot Device!") // library marker davegut.kasaPreferences, line 34
	device.updateSetting("rebootDev", [type:"bool", value: false]) // library marker davegut.kasaPreferences, line 35
	if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaPreferences, line 36
		sendCmd("""{"smartlife.iot.common.system":{"reboot":{"delay":1}}}""") // library marker davegut.kasaPreferences, line 37
	} else { // library marker davegut.kasaPreferences, line 38
		sendCmd("""{"system":{"reboot":{"delay":1}}}""") // library marker davegut.kasaPreferences, line 39
	} // library marker davegut.kasaPreferences, line 40
	pauseExecution(10000) // library marker davegut.kasaPreferences, line 41
	return "REBOOTING DEVICE" // library marker davegut.kasaPreferences, line 42
} // library marker davegut.kasaPreferences, line 43

def bindUnbind() { // library marker davegut.kasaPreferences, line 45
	def meth = "cnCloud" // library marker davegut.kasaPreferences, line 46
	if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaPreferences, line 47
		meth = "smartlife.iot.common.cloud" // library marker davegut.kasaPreferences, line 48
	} // library marker davegut.kasaPreferences, line 49
	def message // library marker davegut.kasaPreferences, line 50
	if (bind == null) { // library marker davegut.kasaPreferences, line 51
		sendLanCmd("""{"${meth}":{"get_info":{}}}""") // library marker davegut.kasaPreferences, line 52
		message = "Updating to current device value" // library marker davegut.kasaPreferences, line 53
	} else if (bind) { // library marker davegut.kasaPreferences, line 54
		if (!parent.useKasaCloud || parent.userName == null || parent.userPassword == null) { // library marker davegut.kasaPreferences, line 55
			message = "useKasaCtr, userName or userPassword not set" // library marker davegut.kasaPreferences, line 56
			sendLanCmd("""{"${meth}":{"get_info":{}}}""") // library marker davegut.kasaPreferences, line 57
		} else { // library marker davegut.kasaPreferences, line 58
			message = "Sending bind command" // library marker davegut.kasaPreferences, line 59
			sendLanCmd("""{"${meth}":{"bind":{"username":"${parent.userName}",""" + // library marker davegut.kasaPreferences, line 60
					""""password":"${parent.userPassword}"}},""" + // library marker davegut.kasaPreferences, line 61
					""""${meth}":{"get_info":{}}}""") // library marker davegut.kasaPreferences, line 62
		} // library marker davegut.kasaPreferences, line 63
	} else if (!bind) { // library marker davegut.kasaPreferences, line 64
		if (!getDataValue("deviceIP")) { // library marker davegut.kasaPreferences, line 65
			message = "Not set. No deviceIP" // library marker davegut.kasaPreferences, line 66
			setCommsType(true) // library marker davegut.kasaPreferences, line 67
		} else if (type() == "Light Strip") { // library marker davegut.kasaPreferences, line 68
			message = "Not set. Light Strip" // library marker davegut.kasaPreferences, line 69
			setCommsType(true) // library marker davegut.kasaPreferences, line 70
		} else { // library marker davegut.kasaPreferences, line 71
			message = "Sending unbind command" // library marker davegut.kasaPreferences, line 72
			sendLanCmd("""{"${meth}":{"unbind":""},"${meth}":{"get_info":{}}}""") // library marker davegut.kasaPreferences, line 73
		} // library marker davegut.kasaPreferences, line 74
	} // library marker davegut.kasaPreferences, line 75
	pauseExecution(5000) // library marker davegut.kasaPreferences, line 76
	return message // library marker davegut.kasaPreferences, line 77
} // library marker davegut.kasaPreferences, line 78

def setBindUnbind(cmdResp) { // library marker davegut.kasaPreferences, line 80
	def bindState = true // library marker davegut.kasaPreferences, line 81
	if (cmdResp.get_info) { // library marker davegut.kasaPreferences, line 82
		if (cmdResp.get_info.binded == 0) { bindState = false } // library marker davegut.kasaPreferences, line 83
		logInfo("setBindUnbind: Bind status set to ${bindState}") // library marker davegut.kasaPreferences, line 84
		setCommsType(bindState) // library marker davegut.kasaPreferences, line 85
	} else if (cmdResp.bind.err_code == 0){ // library marker davegut.kasaPreferences, line 86
		def meth = "cnCloud" // library marker davegut.kasaPreferences, line 87
		if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaPreferences, line 88
			meth = "smartlife.iot.common.cloud" // library marker davegut.kasaPreferences, line 89
		} // library marker davegut.kasaPreferences, line 90
		if (!device.contains("Multi") || getDataValue("plugNo") == "00") { // library marker davegut.kasaPreferences, line 91
			sendLanCmd("""{"${meth}":{"get_info":{}}}""") // library marker davegut.kasaPreferences, line 92
		} else { // library marker davegut.kasaPreferences, line 93
			logWarn("setBindUnbind: Multiplug Plug 00 not installed.") // library marker davegut.kasaPreferences, line 94
		} // library marker davegut.kasaPreferences, line 95
	} else { // library marker davegut.kasaPreferences, line 96
		logWarn("setBindUnbind: Unhandled response: ${cmdResp}") // library marker davegut.kasaPreferences, line 97
	} // library marker davegut.kasaPreferences, line 98
} // library marker davegut.kasaPreferences, line 99

def setCommsType(bindState) { // library marker davegut.kasaPreferences, line 101
	def commsType = "LAN" // library marker davegut.kasaPreferences, line 102
	def cloudCtrl = false // library marker davegut.kasaPreferences, line 103
	state.lastCommand = """{"system":{"get_sysinfo":{}}}""" // library marker davegut.kasaPreferences, line 104

	if (bindState == true && useCloud == true && parent.useKasaCloud &&  // library marker davegut.kasaPreferences, line 106
		parent.userName && parent.userPassword) { // library marker davegut.kasaPreferences, line 107
		state.remove("lastLanCmd") // library marker davegut.kasaPreferences, line 108
		commsType = "CLOUD" // library marker davegut.kasaPreferences, line 109
		cloudCtrl = true // library marker davegut.kasaPreferences, line 110
	} // library marker davegut.kasaPreferences, line 111
	def commsSettings = [bind: bindState, useCloud: cloudCtrl, commsType: commsType] // library marker davegut.kasaPreferences, line 112
		device.updateSetting("bind", [type:"bool", value: bindState]) // library marker davegut.kasaPreferences, line 113
		device.updateSetting("useCloud", [type:"bool", value: cloudCtrl]) // library marker davegut.kasaPreferences, line 114
		sendEvent(name: "connection", value: "${commsType}") // library marker davegut.kasaPreferences, line 115
	log.info "[${type()}, ${driverVer()}, ${device.label}]  setCommsType: ${commsSettings}" // library marker davegut.kasaPreferences, line 116
	if (type().contains("Multi")) { // library marker davegut.kasaPreferences, line 117
		def coordData = [:] // library marker davegut.kasaPreferences, line 118
		coordData << [bind: bindState] // library marker davegut.kasaPreferences, line 119
		coordData << [useCloud: cloudCtrl] // library marker davegut.kasaPreferences, line 120
		coordData << [connection: commsType] // library marker davegut.kasaPreferences, line 121
		parent.coordinate("commsData", coordData, getDataValue("deviceId"), getDataValue("plugNo")) // library marker davegut.kasaPreferences, line 122
	} // library marker davegut.kasaPreferences, line 123
	pauseExecution(1000) // library marker davegut.kasaPreferences, line 124
} // library marker davegut.kasaPreferences, line 125

def ledOn() { // library marker davegut.kasaPreferences, line 127
	logDebug("ledOn: Setting LED to on") // library marker davegut.kasaPreferences, line 128
	sendCmd("""{"system":{"set_led_off":{"off":0},""" + // library marker davegut.kasaPreferences, line 129
			""""get_sysinfo":{}}}""") // library marker davegut.kasaPreferences, line 130
} // library marker davegut.kasaPreferences, line 131

def ledOff() { // library marker davegut.kasaPreferences, line 133
	logDebug("ledOff: Setting LED to off") // library marker davegut.kasaPreferences, line 134
	sendCmd("""{"system":{"set_led_off":{"off":1},""" + // library marker davegut.kasaPreferences, line 135
			""""get_sysinfo":{}}}""") // library marker davegut.kasaPreferences, line 136
} // library marker davegut.kasaPreferences, line 137

// ~~~~~ end include (1) davegut.kasaPreferences ~~~~~

// ~~~~~ start include (33) davegut.bulbTools ~~~~~
/*	bulb tools // library marker davegut.bulbTools, line 1

		Copyright Dave Gutheinz // library marker davegut.bulbTools, line 3

License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md // library marker davegut.bulbTools, line 5

This library contains tools that can be useful to bulb developers in the future. // library marker davegut.bulbTools, line 7
It is designed to be hardware and communications agnostic.  Each method, when  // library marker davegut.bulbTools, line 8
called, returns the data within the specifications below. // library marker davegut.bulbTools, line 9


===================================================================================================*/ // library marker davegut.bulbTools, line 12
library ( // library marker davegut.bulbTools, line 13
	name: "bulbTools", // library marker davegut.bulbTools, line 14
	namespace: "davegut", // library marker davegut.bulbTools, line 15
	author: "Dave Gutheinz", // library marker davegut.bulbTools, line 16
	description: "Bulb and Light Strip Tools", // library marker davegut.bulbTools, line 17
	category: "utility", // library marker davegut.bulbTools, line 18
	documentationLink: "" // library marker davegut.bulbTools, line 19
) // library marker davegut.bulbTools, line 20

def startLevelChange(direction) { // library marker davegut.bulbTools, line 22
	if (direction == "up") { levelUp() } // library marker davegut.bulbTools, line 23
	else { levelDown() } // library marker davegut.bulbTools, line 24
} // library marker davegut.bulbTools, line 25

def stopLevelChange() { // library marker davegut.bulbTools, line 27
	unschedule(levelUp) // library marker davegut.bulbTools, line 28
	unschedule(levelDown) // library marker davegut.bulbTools, line 29
} // library marker davegut.bulbTools, line 30

def levelUp() { // library marker davegut.bulbTools, line 32
	def curLevel = device.currentValue("level").toInteger() // library marker davegut.bulbTools, line 33
	if (curLevel == 100) { return } // library marker davegut.bulbTools, line 34
	def newLevel = curLevel + 4 // library marker davegut.bulbTools, line 35
	if (newLevel > 100) { newLevel = 100 } // library marker davegut.bulbTools, line 36
	setLevel(newLevel, 0) // library marker davegut.bulbTools, line 37
	runIn(1, levelUp) // library marker davegut.bulbTools, line 38
} // library marker davegut.bulbTools, line 39

def levelDown() { // library marker davegut.bulbTools, line 41
	def curLevel = device.currentValue("level").toInteger() // library marker davegut.bulbTools, line 42
	if (curLevel == 0) { return } // library marker davegut.bulbTools, line 43
	def newLevel = curLevel - 4 // library marker davegut.bulbTools, line 44
	if (newLevel < 0) { newLevel = 0 } // library marker davegut.bulbTools, line 45
	setLevel(newLevel, 0) // library marker davegut.bulbTools, line 46
	if (newLevel == 0) { off() } // library marker davegut.bulbTools, line 47
	runIn(1, levelDown) // library marker davegut.bulbTools, line 48
} // library marker davegut.bulbTools, line 49

def getCtName(temp){ // library marker davegut.bulbTools, line 51
    def value = temp.toInteger() // library marker davegut.bulbTools, line 52
    def colorName // library marker davegut.bulbTools, line 53
	if (value <= 2800) { colorName = "Incandescent" } // library marker davegut.bulbTools, line 54
	else if (value <= 3300) { colorName = "Soft White" } // library marker davegut.bulbTools, line 55
	else if (value <= 3500) { colorName = "Warm White" } // library marker davegut.bulbTools, line 56
	else if (value <= 4150) { colorName = "Moonlight" } // library marker davegut.bulbTools, line 57
	else if (value <= 5000) { colorName = "Horizon" } // library marker davegut.bulbTools, line 58
	else if (value <= 5500) { colorName = "Daylight" } // library marker davegut.bulbTools, line 59
	else if (value <= 6000) { colorName = "Electronic" } // library marker davegut.bulbTools, line 60
	else if (value <= 6500) { colorName = "Skylight" } // library marker davegut.bulbTools, line 61
	else { colorName = "Polar" } // library marker davegut.bulbTools, line 62
	return colorName // library marker davegut.bulbTools, line 63
} // library marker davegut.bulbTools, line 64

def getColorName(hue){ // library marker davegut.bulbTools, line 66
    def colorName // library marker davegut.bulbTools, line 67
	switch (hue){ // library marker davegut.bulbTools, line 68
		case 0..15: colorName = "Red" // library marker davegut.bulbTools, line 69
            break // library marker davegut.bulbTools, line 70
		case 16..45: colorName = "Orange" // library marker davegut.bulbTools, line 71
            break // library marker davegut.bulbTools, line 72
		case 46..75: colorName = "Yellow" // library marker davegut.bulbTools, line 73
            break // library marker davegut.bulbTools, line 74
		case 76..105: colorName = "Chartreuse" // library marker davegut.bulbTools, line 75
            break // library marker davegut.bulbTools, line 76
		case 106..135: colorName = "Green" // library marker davegut.bulbTools, line 77
            break // library marker davegut.bulbTools, line 78
		case 136..165: colorName = "Spring" // library marker davegut.bulbTools, line 79
            break // library marker davegut.bulbTools, line 80
		case 166..195: colorName = "Cyan" // library marker davegut.bulbTools, line 81
            break // library marker davegut.bulbTools, line 82
		case 196..225: colorName = "Azure" // library marker davegut.bulbTools, line 83
            break // library marker davegut.bulbTools, line 84
		case 226..255: colorName = "Blue" // library marker davegut.bulbTools, line 85
            break // library marker davegut.bulbTools, line 86
		case 256..285: colorName = "Violet" // library marker davegut.bulbTools, line 87
            break // library marker davegut.bulbTools, line 88
		case 286..315: colorName = "Magenta" // library marker davegut.bulbTools, line 89
            break // library marker davegut.bulbTools, line 90
		case 316..345: colorName = "Rose" // library marker davegut.bulbTools, line 91
            break // library marker davegut.bulbTools, line 92
		case 346..360: colorName = "Red" // library marker davegut.bulbTools, line 93
            break // library marker davegut.bulbTools, line 94
		default: // library marker davegut.bulbTools, line 95
			logWarn("setRgbData: Unknown.") // library marker davegut.bulbTools, line 96
			colorName = "Unknown" // library marker davegut.bulbTools, line 97
    } // library marker davegut.bulbTools, line 98
	return colorName // library marker davegut.bulbTools, line 99
} // library marker davegut.bulbTools, line 100

def bulbPresetCreate(psName) { // library marker davegut.bulbTools, line 102
	if (!state.bulbPresets) { state.bulbPresets = [:] } // library marker davegut.bulbTools, line 103
	psName = psName.trim() // library marker davegut.bulbTools, line 104
	logDebug("bulbPresetCreate: ${psName}") // library marker davegut.bulbTools, line 105
	def psData = [:] // library marker davegut.bulbTools, line 106
	psData["hue"] = device.currentValue("hue") // library marker davegut.bulbTools, line 107
	psData["saturation"] = device.currentValue("saturation") // library marker davegut.bulbTools, line 108
	psData["level"] = device.currentValue("level") // library marker davegut.bulbTools, line 109
	def colorTemp = device.currentValue("colorTemperature") // library marker davegut.bulbTools, line 110
	if (colorTemp == null) { colorTemp = 0 } // library marker davegut.bulbTools, line 111
	psData["colTemp"] = colorTemp // library marker davegut.bulbTools, line 112
	state.bulbPresets << ["${psName}": psData] // library marker davegut.bulbTools, line 113
} // library marker davegut.bulbTools, line 114

def bulbPresetDelete(psName) { // library marker davegut.bulbTools, line 116
	psName = psName.trim() // library marker davegut.bulbTools, line 117
	logDebug("bulbPresetDelete: ${psName}") // library marker davegut.bulbTools, line 118
	def presets = state.bulbPresets // library marker davegut.bulbTools, line 119
	if (presets.toString().contains(psName)) { // library marker davegut.bulbTools, line 120
		presets.remove(psName) // library marker davegut.bulbTools, line 121
	} else { // library marker davegut.bulbTools, line 122
		logWarn("bulbPresetDelete: ${psName} is not a valid name.") // library marker davegut.bulbTools, line 123
	} // library marker davegut.bulbTools, line 124
} // library marker davegut.bulbTools, line 125

def syncBulbPresets() { // library marker davegut.bulbTools, line 127
	device.updateSetting("syncBulbs", [type:"bool", value: false]) // library marker davegut.bulbTools, line 128
	parent.syncBulbPresets(state.bulbPresets, type()) // library marker davegut.bulbTools, line 129
	return "Synching Bulb Presets with all Kasa Bulbs." // library marker davegut.bulbTools, line 130
} // library marker davegut.bulbTools, line 131

def updatePresets(bulbPresets) { // library marker davegut.bulbTools, line 133
	logDebug("updatePresets: Preset Bulb Data: ${bulbPresets}.") // library marker davegut.bulbTools, line 134
	state.bulbPresets = bulbPresets // library marker davegut.bulbTools, line 135
} // library marker davegut.bulbTools, line 136

// ~~~~~ end include (33) davegut.bulbTools ~~~~~
