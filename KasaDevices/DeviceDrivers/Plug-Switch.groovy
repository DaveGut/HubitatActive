/*	Kasa Device Driver Series
		Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
6.5.1	Hot fix for loop in EM Month Stat Processing due to month = 1
===================================================================================================*/
def type() { return "Plug Switch" }
//def type() { return "EM Plug" }
//def type() { return "Multi Plug" }
//def type() { return "EM Multi Plug" }
def file() { return type().replaceAll(" ", "-") }
def driverVer() { return "6.5.1" }

metadata {
	definition (name: "Kasa ${type()}",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/${file()}.groovy"
			   ) {
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["default", "5 seconds", "10 seconds", "15 seconds",
						  "30 seconds", "1 minute", "5 minutes",  "10 minutes",
						  "30 minutes"],
			type: "ENUM"]]
		command "ledOn"
		command "ledOff"
		attribute "led", "string"
		attribute "connection", "string"
		attribute "commsError", "string"
		capability "Power Meter"
		capability "Energy Meter"
		attribute "currMonthTotal", "number"
		attribute "currMonthAvg", "number"
		attribute "lastMonthTotal", "number"
		attribute "lastMonthAvg", "number"
	}
	preferences {
		input ("debug", "bool",
			   title: "30 minutes of debug logging", 
			   defaultValue: false)
		if (getDataValue("feature") == "TIM:ENE") {
			input ("emFunction", "bool", 
				   title: "Enable Energy Monitor", 
				   defaultValue: false)
		}
		if (getDataValue("model") == "HS200" && getDataValue("deviceIP") != "CLOUD") {
			input ("altLan", "bool",
				   title: "Alternate LAN Comms (for comms problems only)",
				   defaultValue: false)
		}
		input ("bind", "bool",
			   title: "Kasa Cloud Binding",
			   defalutValue: true)
		if (bind && parent.kasaToken) {
			input ("useCloud", "bool",
				   title: "Use Kasa Cloud for device control",
				   defaultValue: false)
		}
		input ("nameSync", "enum", title: "Synchronize Names",
			   defaultValue: "none",
			   options: ["none": "Don't synchronize",
						 "device" : "Kasa device name master", 
						 "Hubitat" : "Hubitat label master"])
		input ("rebootDev", "bool",
			   title: "Reboot device <b>[Caution]</b>",
			   defaultValue: false)
	}
}

def installed() {
	def instStatus= installCommon()
	logInfo("installed: ${instStatus}")
	runIn(2, updated)
}

def updated() {
	def updStatus = updateCommon()
	logInfo("updated: ${updStatus}")
	runIn(3, refresh)
}

def on() {
	if (getDataValue("plugNo") == null) {
		sendCmd("""{"system":{"set_relay_state":{"state":1},"get_sysinfo":{}}}""")
	} else {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""system":{"set_relay_state":{"state":1},"get_sysinfo":{}}}""")
	}
}

def off() {
	if (getDataValue("plugNo") == null) {
		sendCmd("""{"system":{"set_relay_state":{"state":0},"get_sysinfo":{}}}""")
	} else {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""system":{"set_relay_state":{"state":0},"get_sysinfo":{}}}""")
	}
}

def ledOn() {
	logDebug("ledOn: Setting LED to on")
	sendCmd("""{"system":{"set_led_off":{"off":0},""" +
			""""get_sysinfo":{}}}""")
}

def ledOff() {
	logDebug("ledOff: Setting LED to off")
	sendCmd("""{"system":{"set_led_off":{"off":1},""" +
			""""get_sysinfo":{}}}""")
}

def refresh() { poll() }

def poll() {
	sendCmd("""{"system":{"get_sysinfo":{}}}""")
}

def distResp(response) {
	if (response.system) {
		if (response.system.get_sysinfo) {
			setSysInfo(response.system.get_sysinfo)
		} else if (response.system.set_relay_state) {
			poll()
		} else if (response.system.reboot) {
			logWarn("distResp: Rebooting device.")
		} else if (response.system.set_dev_alias) {
			device.updateSetting("nameSync",[type:"enum", value:"none"])
			if (response.system.set_dev_alias.err_code != 0) {
				def msg = "distResp: Name Sync from Hubitat to Device returned an error."
				msg+= "Note: <b>some devices do not support syncing name from the hub.</b>\n\r"
				logWarn(msg)
			}
		} else {
			logWarn("distResp: Unhandled response = ${response}")
		}
	} else if (response.emeter) {
		distEmeter(response.emeter)
	} else if (response.cnCloud) {
		setBindUnbind(response.cnCloud)
	} else {
		logWarn("distResp: Unhandled response = ${response}")
	}
	resetCommsError()
}

def setSysInfo(status) {
	logDebug("setSysInfo: ${status}")
	def updates = [:]
	def switchStatus = status.relay_state
	def ledStatus = status.led_off
	if (getDataValue("plugNo") != null) {
		if (device.currentValue("connection") != "CLOUD") {
			status = status.children.find { it.id == getDataValue("plugNo") }
		} else {
			status = status.children.find { it.id == getDataValue("plugId") }
		}
		switchStatus = status.state
	}
	def onOff = "on"
	if (switchStatus == 0) { onOff = "off" }
	if (onOff != device.currentValue("switch")) {
		updates << [switch: onOff]
		sendEvent(name: "switch", value: onOff, type: "digital")
	}
	def ledOnOff = "on"
	if (ledStatus == 1) { ledOnOff = "off" }
	if (ledOnOff != device.currentValue("led")) {
		updates << [led: ledOnOff]
		sendEvent(name: "led", value: ledOnOff)
	}
	if (nameSync == "device") {
		device.setLabel(status.alias)
		device.updateSetting("nameSync",[type:"enum", value:"none"])
		updates << [label: status.alias]
	}
	if (updates != [:]) { logInfo("setSysinfo: ${updates}") }
	if (emFunction) { getPower() }
}

def coordUpdate(cType, coordData) {
	logDebug("coordUpdate: ${cType}, ${coordData}")
	if (cType == "commsData") {
		device.updateSetting("bind", [type:"bool", value: coordData.bind])
		device.updateSetting("useCloud", [type:"bool", value: coordData.useCloud])
		sendEvent(name: "connection", value: coordData.connection)
	} else {
		logWarn("coordUpdate: Unhandled Update: ${cType}, ${coordData}")
	}
}

//	===== includes =====




// ~~~~~ start include (258) davegut.kasaCommon ~~~~~
library ( // library marker davegut.kasaCommon, line 1
	name: "kasaCommon", // library marker davegut.kasaCommon, line 2
	namespace: "davegut", // library marker davegut.kasaCommon, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaCommon, line 4
	description: "Kasa updated and preferences routines", // library marker davegut.kasaCommon, line 5
	category: "energyMonitor", // library marker davegut.kasaCommon, line 6
	documentationLink: "" // library marker davegut.kasaCommon, line 7
) // library marker davegut.kasaCommon, line 8

//	====== Common Install / Update Elements ===== // library marker davegut.kasaCommon, line 10
def installCommon() { // library marker davegut.kasaCommon, line 11
	pauseExecution(3000) // library marker davegut.kasaCommon, line 12
	def instStatus = [:] // library marker davegut.kasaCommon, line 13
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 14
		sendEvent(name: "connection", value: "CLOUD") // library marker davegut.kasaCommon, line 15
		device.updateSetting("useCloud", [type:"bool", value: true]) // library marker davegut.kasaCommon, line 16
		instStatus << [useCloud: true, connection: "CLOUD"] // library marker davegut.kasaCommon, line 17
	} else { // library marker davegut.kasaCommon, line 18
		sendEvent(name: "connection", value: "LAN") // library marker davegut.kasaCommon, line 19
		device.updateSetting("useCloud", [type:"bool", value: false]) // library marker davegut.kasaCommon, line 20
		instStatus << [useCloud: false, connection: "LAN"] // library marker davegut.kasaCommon, line 21
	} // library marker davegut.kasaCommon, line 22
	sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommon, line 23
	state.errorCount = 0 // library marker davegut.kasaCommon, line 24
	state.pollInterval = "30 minutes" // library marker davegut.kasaCommon, line 25
	updateDataValue("driverVersion", driverVer()) // library marker davegut.kasaCommon, line 26
	instStatus << [driverVersion: driverVer()] // library marker davegut.kasaCommon, line 27
	return instStatus // library marker davegut.kasaCommon, line 28
} // library marker davegut.kasaCommon, line 29

def updateCommon() { // library marker davegut.kasaCommon, line 31
	unschedule() // library marker davegut.kasaCommon, line 32
	def updStatus = [:] // library marker davegut.kasaCommon, line 33
	if (rebootDev) { // library marker davegut.kasaCommon, line 34
		updStatus << [rebootDev: rebootDevice()] // library marker davegut.kasaCommon, line 35
		return updStatus // library marker davegut.kasaCommon, line 36
	} // library marker davegut.kasaCommon, line 37
	updStatus << [bind: bindUnbind()] // library marker davegut.kasaCommon, line 38
	if (nameSync != "none") { // library marker davegut.kasaCommon, line 39
		updStatus << [nameSync: syncName()] // library marker davegut.kasaCommon, line 40
	} // library marker davegut.kasaCommon, line 41
	if (debug) { runIn(1800, debugOff) } // library marker davegut.kasaCommon, line 42
	updStatus << [debug: debug] // library marker davegut.kasaCommon, line 43
	state.errorCount = 0 // library marker davegut.kasaCommon, line 44
	sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommon, line 45
	updStatus << [emFunction: setupEmFunction()] // library marker davegut.kasaCommon, line 46
	updStatus << [pollInterval: setPollInterval()] // library marker davegut.kasaCommon, line 47
	state.remove("ISSUE") // library marker davegut.kasaCommon, line 48
	if(getDataValue("driverVersion") != driverVer()){ // library marker davegut.kasaCommon, line 49
		updStatus << updateDriverData() // library marker davegut.kasaCommon, line 50
	} // library marker davegut.kasaCommon, line 51
	return updStatus // library marker davegut.kasaCommon, line 52
} // library marker davegut.kasaCommon, line 53

def updateDriverData() { // library marker davegut.kasaCommon, line 55
	def drvVer = getDataValue("driverVersion") // library marker davegut.kasaCommon, line 56
	state.remove("lastLanCmd") // library marker davegut.kasaCommon, line 57
	state.remove("commsErrorText") // library marker davegut.kasaCommon, line 58
	if (!state.pollInterval) { state.pollInterval = "30 minutes" } // library marker davegut.kasaCommon, line 59
	updateDataValue("driverVersion", driverVer()) // library marker davegut.kasaCommon, line 60
	return [driverVersion: driverVer()] // library marker davegut.kasaCommon, line 61
} // library marker davegut.kasaCommon, line 62

//	===== Preference Methods ===== // library marker davegut.kasaCommon, line 64
def setPollInterval(interval = state.pollInterval) { // library marker davegut.kasaCommon, line 65
	if (interval == "default" || interval == "off") { // library marker davegut.kasaCommon, line 66
		interval = "30 minutes" // library marker davegut.kasaCommon, line 67
	} else if (useCloud && interval.contains("sec")) { // library marker davegut.kasaCommon, line 68
		interval = "1 minute" // library marker davegut.kasaCommon, line 69
	} // library marker davegut.kasaCommon, line 70
	state.pollInterval = interval // library marker davegut.kasaCommon, line 71
	def pollInterval = interval.substring(0,2).toInteger() // library marker davegut.kasaCommon, line 72
	if (interval.contains("sec")) { // library marker davegut.kasaCommon, line 73
		def start = Math.round((pollInterval-1) * Math.random()).toInteger() // library marker davegut.kasaCommon, line 74
		schedule("${start}/${pollInterval} * * * * ?", "poll") // library marker davegut.kasaCommon, line 75
		state.pollWarning = "Polling intervals of less than one minute can take high " + // library marker davegut.kasaCommon, line 76
			"resources and may impact hub performance." // library marker davegut.kasaCommon, line 77
	} else { // library marker davegut.kasaCommon, line 78
		def start = Math.round(59 * Math.random()).toInteger() // library marker davegut.kasaCommon, line 79
		schedule("${start} */${pollInterval} * * * ?", "poll") // library marker davegut.kasaCommon, line 80
		state.remove("pollWarning") // library marker davegut.kasaCommon, line 81
	} // library marker davegut.kasaCommon, line 82
	logDebug("setPollInterval: interval = ${interval}.") // library marker davegut.kasaCommon, line 83
	return interval // library marker davegut.kasaCommon, line 84
} // library marker davegut.kasaCommon, line 85

def rebootDevice() { // library marker davegut.kasaCommon, line 87
	logWarn("rebootDevice: User Commanded Reboot Device!") // library marker davegut.kasaCommon, line 88
	device.updateSetting("rebootDev", [type:"bool", value: false]) // library marker davegut.kasaCommon, line 89
	if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaCommon, line 90
		sendCmd("""{"smartlife.iot.common.system":{"reboot":{"delay":1}}}""") // library marker davegut.kasaCommon, line 91
	} else { // library marker davegut.kasaCommon, line 92
		sendCmd("""{"system":{"reboot":{"delay":1}}}""") // library marker davegut.kasaCommon, line 93
	} // library marker davegut.kasaCommon, line 94
	pauseExecution(10000) // library marker davegut.kasaCommon, line 95
	return "REBOOTING DEVICE" // library marker davegut.kasaCommon, line 96
} // library marker davegut.kasaCommon, line 97

def bindUnbind() { // library marker davegut.kasaCommon, line 99
	def meth = "cnCloud" // library marker davegut.kasaCommon, line 100
	if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaCommon, line 101
		meth = "smartlife.iot.common.cloud" // library marker davegut.kasaCommon, line 102
	} // library marker davegut.kasaCommon, line 103
	def message // library marker davegut.kasaCommon, line 104
	if (bind == null) { // library marker davegut.kasaCommon, line 105
		message = "Getting bind state" // library marker davegut.kasaCommon, line 106
		sendCmd("""{"${meth}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 107
	} else if (getDataValue("deviceIP") == "CLOUD" || // library marker davegut.kasaCommon, line 108
			   type() == "Light Strip") { // library marker davegut.kasaCommon, line 109
		message = "Bind Only Device" // library marker davegut.kasaCommon, line 110
		sendLanCmd("""{"${meth}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 111
	} else if (bind == true) { // library marker davegut.kasaCommon, line 112
		if (!parent.kasaToken || parent.userName == null || parent.userPassword == null) { // library marker davegut.kasaCommon, line 113
			message = "Username/pwd not set" // library marker davegut.kasaCommon, line 114
			sendCmd("""{"${meth}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 115
		} else { // library marker davegut.kasaCommon, line 116
			message = "Binding" // library marker davegut.kasaCommon, line 117
			sendLanCmd("""{"${meth}":{"bind":{"username":"${parent.userName}",""" + // library marker davegut.kasaCommon, line 118
					   """"password":"${parent.userPassword}"}},""" + // library marker davegut.kasaCommon, line 119
					   """"${meth}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 120
		} // library marker davegut.kasaCommon, line 121
	} else if (bind == false) { // library marker davegut.kasaCommon, line 122
		message = "Unbinding" // library marker davegut.kasaCommon, line 123
		sendLanCmd("""{"${meth}":{"unbind":""},"${meth}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 124
	} // library marker davegut.kasaCommon, line 125
	pauseExecution(5000) // library marker davegut.kasaCommon, line 126
	return message // library marker davegut.kasaCommon, line 127
} // library marker davegut.kasaCommon, line 128

def setBindUnbind(cmdResp) { // library marker davegut.kasaCommon, line 130
	def bindState = true // library marker davegut.kasaCommon, line 131
	if (cmdResp.get_info) { // library marker davegut.kasaCommon, line 132
		if (cmdResp.get_info.binded == 0) { bindState = false } // library marker davegut.kasaCommon, line 133
		logInfo("setBindUnbind: Bind status set to ${bindState}") // library marker davegut.kasaCommon, line 134
		setCommsType(bindState) // library marker davegut.kasaCommon, line 135
	} else if (cmdResp.bind.err_code == 0){ // library marker davegut.kasaCommon, line 136
		def meth = "cnCloud" // library marker davegut.kasaCommon, line 137
		if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaCommon, line 138
			meth = "smartlife.iot.common.cloud" // library marker davegut.kasaCommon, line 139
		} // library marker davegut.kasaCommon, line 140
		sendLanCmd("""{"${meth}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 141
	} else { // library marker davegut.kasaCommon, line 142
		logWarn("setBindUnbind: Unhandled response: ${cmdResp}") // library marker davegut.kasaCommon, line 143
	} // library marker davegut.kasaCommon, line 144
} // library marker davegut.kasaCommon, line 145

def setCommsType(bindState) { // library marker davegut.kasaCommon, line 147
	def commsType = "LAN" // library marker davegut.kasaCommon, line 148
	def cloudCtrl = false // library marker davegut.kasaCommon, line 149
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 150
		commsType = "CLOUD" // library marker davegut.kasaCommon, line 151
		cloudCtrl = true // library marker davegut.kasaCommon, line 152
	} else if (bindState == true && useCloud == true && parent.kasaToken) { // library marker davegut.kasaCommon, line 153
		commsType = "CLOUD" // library marker davegut.kasaCommon, line 154
		cloudCtrl = true // library marker davegut.kasaCommon, line 155
	} else if (altLan == true) { // library marker davegut.kasaCommon, line 156
		commsType = "AltLAN" // library marker davegut.kasaCommon, line 157
	} // library marker davegut.kasaCommon, line 158
	def commsSettings = [bind: bindState, useCloud: cloudCtrl, commsType: commsType] // library marker davegut.kasaCommon, line 159
	device.updateSetting("bind", [type:"bool", value: bindState]) // library marker davegut.kasaCommon, line 160
	device.updateSetting("useCloud", [type:"bool", value: cloudCtrl]) // library marker davegut.kasaCommon, line 161
	sendEvent(name: "connection", value: "${commsType}") // library marker davegut.kasaCommon, line 162
	logInfo("setCommsType: ${commsSettings}") // library marker davegut.kasaCommon, line 163
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaCommon, line 164
		def coordData = [:] // library marker davegut.kasaCommon, line 165
		coordData << [bind: bindState] // library marker davegut.kasaCommon, line 166
		coordData << [useCloud: cloudCtrl] // library marker davegut.kasaCommon, line 167
		coordData << [connection: commsType] // library marker davegut.kasaCommon, line 168
		parent.coordinate("commsData", coordData, getDataValue("deviceId"), getDataValue("plugNo")) // library marker davegut.kasaCommon, line 169
	} // library marker davegut.kasaCommon, line 170
	pauseExecution(1000) // library marker davegut.kasaCommon, line 171
} // library marker davegut.kasaCommon, line 172

def syncName() { // library marker davegut.kasaCommon, line 174
	def message // library marker davegut.kasaCommon, line 175
	if (nameSync == "Hubitat") { // library marker davegut.kasaCommon, line 176
		message = "Hubitat Label Sync" // library marker davegut.kasaCommon, line 177
		if (getDataValue("plugNo") != null) { // library marker davegut.kasaCommon, line 178
			sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaCommon, line 179
					""""system":{"set_dev_alias":{"alias":"${device.label}"}}}""") // library marker davegut.kasaCommon, line 180
		} else { // library marker davegut.kasaCommon, line 181
			sendCmd("""{"system":{"set_dev_alias":{"alias":"${device.label}"}}}""") // library marker davegut.kasaCommon, line 182
		} // library marker davegut.kasaCommon, line 183
	} else if (nameSync == "device") { // library marker davegut.kasaCommon, line 184
		message = "Device Alias Sync" // library marker davegut.kasaCommon, line 185
	} else { // library marker davegut.kasaCommon, line 186
		message = "Not Syncing" // library marker davegut.kasaCommon, line 187
	} // library marker davegut.kasaCommon, line 188
	return message // library marker davegut.kasaCommon, line 189
} // library marker davegut.kasaCommon, line 190

// ~~~~~ end include (258) davegut.kasaCommon ~~~~~

// ~~~~~ start include (259) davegut.kasaCommunications ~~~~~
import groovy.json.JsonSlurper // library marker davegut.kasaCommunications, line 1
library ( // library marker davegut.kasaCommunications, line 2
	name: "kasaCommunications", // library marker davegut.kasaCommunications, line 3
	namespace: "davegut", // library marker davegut.kasaCommunications, line 4
	author: "Dave Gutheinz", // library marker davegut.kasaCommunications, line 5
	description: "Kasa Communications Methods", // library marker davegut.kasaCommunications, line 6
	category: "communications", // library marker davegut.kasaCommunications, line 7
	documentationLink: "" // library marker davegut.kasaCommunications, line 8
) // library marker davegut.kasaCommunications, line 9

def getPort() { // library marker davegut.kasaCommunications, line 11
	def port = 9999 // library marker davegut.kasaCommunications, line 12
	if (getDataValue("devicePort")) { // library marker davegut.kasaCommunications, line 13
		port = getDataValue("devicePort") // library marker davegut.kasaCommunications, line 14
	} // library marker davegut.kasaCommunications, line 15
	return port // library marker davegut.kasaCommunications, line 16
} // library marker davegut.kasaCommunications, line 17

def sendCmd(command) { // library marker davegut.kasaCommunications, line 19
	if (device.currentValue("connection") == "LAN") { // library marker davegut.kasaCommunications, line 20
		sendLanCmd(command) // library marker davegut.kasaCommunications, line 21
	} else if (device.currentValue("connection") == "CLOUD"){ // library marker davegut.kasaCommunications, line 22
		sendKasaCmd(command) // library marker davegut.kasaCommunications, line 23
	} else if (device.currentValue("connection") == "AltLAN") { // library marker davegut.kasaCommunications, line 24
		sendTcpCmd(command) // library marker davegut.kasaCommunications, line 25
	} else { // library marker davegut.kasaCommunications, line 26
		logWarn("sendCmd: attribute connection is not set.") // library marker davegut.kasaCommunications, line 27
	} // library marker davegut.kasaCommunications, line 28
} // library marker davegut.kasaCommunications, line 29

def sendLanCmd(command) { // library marker davegut.kasaCommunications, line 31
	logDebug("sendLanCmd: command = ${command}") // library marker davegut.kasaCommunications, line 32
	if (!command.contains("password")) { // library marker davegut.kasaCommunications, line 33
		state.lastCommand = command // library marker davegut.kasaCommunications, line 34
	} // library marker davegut.kasaCommunications, line 35
	def myHubAction = new hubitat.device.HubAction( // library marker davegut.kasaCommunications, line 36
		outputXOR(command), // library marker davegut.kasaCommunications, line 37
		hubitat.device.Protocol.LAN, // library marker davegut.kasaCommunications, line 38
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT, // library marker davegut.kasaCommunications, line 39
		 destinationAddress: "${getDataValue("deviceIP")}:${getPort()}", // library marker davegut.kasaCommunications, line 40
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING, // library marker davegut.kasaCommunications, line 41
		 parseWarning: true, // library marker davegut.kasaCommunications, line 42
		 timeout: 10, // library marker davegut.kasaCommunications, line 43
		 callback: parseUdp]) // library marker davegut.kasaCommunications, line 44
	try { // library marker davegut.kasaCommunications, line 45
		sendHubCommand(myHubAction) // library marker davegut.kasaCommunications, line 46
	} catch (e) { // library marker davegut.kasaCommunications, line 47
		logWarn("sendLanCmd: LAN Error = ${e}") // library marker davegut.kasaCommunications, line 48
		handleCommsError() // library marker davegut.kasaCommunications, line 49
	} // library marker davegut.kasaCommunications, line 50
} // library marker davegut.kasaCommunications, line 51

def parseUdp(message) { // library marker davegut.kasaCommunications, line 53
	def resp = parseLanMessage(message) // library marker davegut.kasaCommunications, line 54
	if (resp.type == "LAN_TYPE_UDPCLIENT") { // library marker davegut.kasaCommunications, line 55
		def clearResp = inputXOR(resp.payload) // library marker davegut.kasaCommunications, line 56
		if (clearResp.length() > 1022) { // library marker davegut.kasaCommunications, line 57
			if (clearResp.contains("preferred")) { // library marker davegut.kasaCommunications, line 58
				clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}" // library marker davegut.kasaCommunications, line 59
			} else { // library marker davegut.kasaCommunications, line 60
				def msg = "parseUdp: Response is too long for Hubitat UDP implementation." // library marker davegut.kasaCommunications, line 61
				msg += "\n\t<b>Device attributes have not been updated.</b>" // library marker davegut.kasaCommunications, line 62
				if(device.getName().contains("Multi")) { // library marker davegut.kasaCommunications, line 63
					msg += "\n\t<b>HS300:</b>\tCheck your device names. The total Kasa App names of all " // library marker davegut.kasaCommunications, line 64
					msg += "\n\t\t\tdevice names can't exceed 96 charactrs (16 per device).\n\r" // library marker davegut.kasaCommunications, line 65
				} // library marker davegut.kasaCommunications, line 66
				logWarn(msg) // library marker davegut.kasaCommunications, line 67
				return // library marker davegut.kasaCommunications, line 68
			} // library marker davegut.kasaCommunications, line 69
		} // library marker davegut.kasaCommunications, line 70
		def cmdResp = new JsonSlurper().parseText(clearResp) // library marker davegut.kasaCommunications, line 71
		distResp(cmdResp) // library marker davegut.kasaCommunications, line 72
	} else { // library marker davegut.kasaCommunications, line 73
		logDebug("parse: LAN Error = ${resp.type}") // library marker davegut.kasaCommunications, line 74
		handleCommsError() // library marker davegut.kasaCommunications, line 75
	} // library marker davegut.kasaCommunications, line 76
} // library marker davegut.kasaCommunications, line 77

def sendKasaCmd(command) { // library marker davegut.kasaCommunications, line 79
	logDebug("sendKasaCmd: ${command}") // library marker davegut.kasaCommunications, line 80
	state.lastCommand = command // library marker davegut.kasaCommunications, line 81
	runIn(5, handleCommsError) // library marker davegut.kasaCommunications, line 82
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 83
	def cmdBody = [ // library marker davegut.kasaCommunications, line 84
		method: "passthrough", // library marker davegut.kasaCommunications, line 85
		params: [ // library marker davegut.kasaCommunications, line 86
			deviceId: getDataValue("deviceId"), // library marker davegut.kasaCommunications, line 87
			requestData: "${command}" // library marker davegut.kasaCommunications, line 88
		] // library marker davegut.kasaCommunications, line 89
	] // library marker davegut.kasaCommunications, line 90
	def sendCloudCmdParams = [ // library marker davegut.kasaCommunications, line 91
		uri: "${parent.kasaCloudUrl}/?token=${parent.kasaToken}", // library marker davegut.kasaCommunications, line 92
		requestContentType: 'application/json', // library marker davegut.kasaCommunications, line 93
		contentType: 'application/json', // library marker davegut.kasaCommunications, line 94
		headers: ['Accept':'application/json; version=1, */*; q=0.01'], // library marker davegut.kasaCommunications, line 95
		timeout: 5, // library marker davegut.kasaCommunications, line 96
		body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.kasaCommunications, line 97
	] // library marker davegut.kasaCommunications, line 98
	try { // library marker davegut.kasaCommunications, line 99
		asynchttpPost("cloudParse", sendCloudCmdParams) // library marker davegut.kasaCommunications, line 100
	} catch (e) { // library marker davegut.kasaCommunications, line 101
		def msg = "sendKasaCmd: <b>Error in Cloud Communications.</b> The Kasa Cloud is unreachable." // library marker davegut.kasaCommunications, line 102
		msg += "\nAdditional Data: Error = ${e}\n\n" // library marker davegut.kasaCommunications, line 103
		logWarn(msg) // library marker davegut.kasaCommunications, line 104
	} // library marker davegut.kasaCommunications, line 105
} // library marker davegut.kasaCommunications, line 106

def cloudParse(resp, data = null) { // library marker davegut.kasaCommunications, line 108
	def jsonSlurper = new groovy.json.JsonSlurper() // library marker davegut.kasaCommunications, line 109
	def response = jsonSlurper.parseText(resp.data) // library marker davegut.kasaCommunications, line 110
	if (resp.status == 200 && response.error_code == 0) { // library marker davegut.kasaCommunications, line 111
		distResp(jsonSlurper.parseText(response.result.responseData)) // library marker davegut.kasaCommunications, line 112
	} else { // library marker davegut.kasaCommunications, line 113
		def msg = "sendKasaCmd:\n<b>Error from the Kasa Cloud.</b> Most common cause is " // library marker davegut.kasaCommunications, line 114
		msg += "your Kasa Token has expired.  Run Kasa Login and Token update and try again." // library marker davegut.kasaCommunications, line 115
		msg += "\nAdditional Data: Error ${resp.data.error_code} = ${resp.data.msg}\n\n" // library marker davegut.kasaCommunications, line 116
		logWarn(msg) // library marker davegut.kasaCommunications, line 117
	} // library marker davegut.kasaCommunications, line 118
} // library marker davegut.kasaCommunications, line 119

///////////////////////////////////////////////////////////////// // library marker davegut.kasaCommunications, line 121
def xxxsendKasaCmd(command) { // library marker davegut.kasaCommunications, line 122
	logDebug("sendKasaCmd: ${command}") // library marker davegut.kasaCommunications, line 123
	state.lastCommand = command // library marker davegut.kasaCommunications, line 124
	runIn(5, handleCommsError) // library marker davegut.kasaCommunications, line 125
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 126
	def cmdBody = [ // library marker davegut.kasaCommunications, line 127
		method: "passthrough", // library marker davegut.kasaCommunications, line 128
		params: [ // library marker davegut.kasaCommunications, line 129
			deviceId: getDataValue("deviceId"), // library marker davegut.kasaCommunications, line 130
			requestData: "${command}" // library marker davegut.kasaCommunications, line 131
		] // library marker davegut.kasaCommunications, line 132
	] // library marker davegut.kasaCommunications, line 133
	def sendCloudCmdParams = [ // library marker davegut.kasaCommunications, line 134
		uri: "${parent.kasaCloudUrl}/?token=${parent.kasaToken}", // library marker davegut.kasaCommunications, line 135
		requestContentType: 'application/json', // library marker davegut.kasaCommunications, line 136
		contentType: 'application/json', // library marker davegut.kasaCommunications, line 137
		headers: ['Accept':'application/json; version=1, */*; q=0.01'], // library marker davegut.kasaCommunications, line 138
		timeout: 5, // library marker davegut.kasaCommunications, line 139
		body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.kasaCommunications, line 140
	] // library marker davegut.kasaCommunications, line 141
	try { // library marker davegut.kasaCommunications, line 142
		httpPostJson(sendCloudCmdParams) {resp -> // library marker davegut.kasaCommunications, line 143
			if (resp.status == 200 && resp.data.error_code == 0) { // library marker davegut.kasaCommunications, line 144
				def jsonSlurper = new groovy.json.JsonSlurper() // library marker davegut.kasaCommunications, line 145
				distResp(jsonSlurper.parseText(resp.data.result.responseData)) // library marker davegut.kasaCommunications, line 146
			} else { // library marker davegut.kasaCommunications, line 147
				def msg = "sendKasaCmd:\n<b>Error from the Kasa Cloud.</b> Most common cause is " // library marker davegut.kasaCommunications, line 148
				msg += "your Kasa Token has expired.  Run Kasa Login and Token update and try again." // library marker davegut.kasaCommunications, line 149
				msg += "\nAdditional Data: Error ${resp.data.error_code} = ${resp.data.msg}\n\n" // library marker davegut.kasaCommunications, line 150
				logWarn(msg) // library marker davegut.kasaCommunications, line 151
			} // library marker davegut.kasaCommunications, line 152
		} // library marker davegut.kasaCommunications, line 153
	} catch (e) { // library marker davegut.kasaCommunications, line 154
		def msg = "sendKasaCmd:\n<b>Error in Cloud Communications.</b> The Kasa Cloud is unreachable." // library marker davegut.kasaCommunications, line 155
		msg += "\nAdditional Data: Error = ${e}\n\n" // library marker davegut.kasaCommunications, line 156
		logWarn(msg) // library marker davegut.kasaCommunications, line 157
	} // library marker davegut.kasaCommunications, line 158
} // library marker davegut.kasaCommunications, line 159

private sendTcpCmd(command) { // library marker davegut.kasaCommunications, line 161
	logDebug("sendTcpCmd: ${command}") // library marker davegut.kasaCommunications, line 162
	try { // library marker davegut.kasaCommunications, line 163
		interfaces.rawSocket.connect("${getDataValue("deviceIP")}", // library marker davegut.kasaCommunications, line 164
									 getPort().toInteger(), byteInterface: true) // library marker davegut.kasaCommunications, line 165
	} catch (error) { // library marker davegut.kasaCommunications, line 166
		logDebug("SendTcpCmd: Unable to connect to device at ${getDataValue("deviceIP")}:${getDataValue("devicePort")}. " + // library marker davegut.kasaCommunications, line 167
				 "Error = ${error}") // library marker davegut.kasaCommunications, line 168
	} // library marker davegut.kasaCommunications, line 169
	runIn(5, handleCommsError) // library marker davegut.kasaCommunications, line 170
	state.lastCommand = command // library marker davegut.kasaCommunications, line 171
	interfaces.rawSocket.sendMessage(outputXorTcp(command)) // library marker davegut.kasaCommunications, line 172
} // library marker davegut.kasaCommunications, line 173

def socketStatus(message) { // library marker davegut.kasaCommunications, line 175
	if (message != "receive error: Stream closed.") { // library marker davegut.kasaCommunications, line 176
		logDebug("socketStatus: Socket Established") // library marker davegut.kasaCommunications, line 177
	} else { // library marker davegut.kasaCommunications, line 178
		logWarn("socketStatus = ${message}") // library marker davegut.kasaCommunications, line 179
	} // library marker davegut.kasaCommunications, line 180
} // library marker davegut.kasaCommunications, line 181

def parse(message) { // library marker davegut.kasaCommunications, line 183
	def respLength // library marker davegut.kasaCommunications, line 184
	if (message.length() > 8 && message.substring(0,4) == "0000") { // library marker davegut.kasaCommunications, line 185
		def hexBytes = message.substring(0,8) // library marker davegut.kasaCommunications, line 186
		respLength = 8 + 2 * hubitat.helper.HexUtils.hexStringToInt(hexBytes) // library marker davegut.kasaCommunications, line 187
		if (message.length() == respLength) { // library marker davegut.kasaCommunications, line 188
			extractResp(message) // library marker davegut.kasaCommunications, line 189
		} else { // library marker davegut.kasaCommunications, line 190
			state.response = message // library marker davegut.kasaCommunications, line 191
			state.respLength = respLength // library marker davegut.kasaCommunications, line 192
		} // library marker davegut.kasaCommunications, line 193
	} else if (message.length() == 0 || message == null) { // library marker davegut.kasaCommunications, line 194
		return // library marker davegut.kasaCommunications, line 195
	} else { // library marker davegut.kasaCommunications, line 196
		def resp = state.response // library marker davegut.kasaCommunications, line 197
		resp = resp.concat(message) // library marker davegut.kasaCommunications, line 198
		if (resp.length() == state.respLength) { // library marker davegut.kasaCommunications, line 199
			state.response = "" // library marker davegut.kasaCommunications, line 200
			state.respLength = 0 // library marker davegut.kasaCommunications, line 201
			extractResp(message) // library marker davegut.kasaCommunications, line 202
		} else { // library marker davegut.kasaCommunications, line 203
			state.response = resp // library marker davegut.kasaCommunications, line 204
		} // library marker davegut.kasaCommunications, line 205
	} // library marker davegut.kasaCommunications, line 206
} // library marker davegut.kasaCommunications, line 207

def extractResp(message) { // library marker davegut.kasaCommunications, line 209
	if (message.length() == null) { // library marker davegut.kasaCommunications, line 210
		logDebug("extractResp: null return rejected.") // library marker davegut.kasaCommunications, line 211
		return  // library marker davegut.kasaCommunications, line 212
	} // library marker davegut.kasaCommunications, line 213
	logDebug("extractResp: ${message}") // library marker davegut.kasaCommunications, line 214
	try { // library marker davegut.kasaCommunications, line 215
		distResp(parseJson(inputXorTcp(message))) // library marker davegut.kasaCommunications, line 216
	} catch (e) { // library marker davegut.kasaCommunications, line 217
		logWarn("extractResp: Invalid or incomplete return.\nerror = ${e}") // library marker davegut.kasaCommunications, line 218
		handleCommsError() // library marker davegut.kasaCommunications, line 219
	} // library marker davegut.kasaCommunications, line 220
} // library marker davegut.kasaCommunications, line 221

def handleCommsError() { // library marker davegut.kasaCommunications, line 223
	def count = state.errorCount + 1 // library marker davegut.kasaCommunications, line 224
	state.errorCount = count // library marker davegut.kasaCommunications, line 225
	def message = "handleCommsError: Count: ${count}." // library marker davegut.kasaCommunications, line 226
	if (count <= 3) { // library marker davegut.kasaCommunications, line 227
		message += "\n\t\t\t Retransmitting command, try = ${count}" // library marker davegut.kasaCommunications, line 228
		runIn(1, sendCmd, [data: state.lastCommand]) // library marker davegut.kasaCommunications, line 229
	} else if (count == 4) { // library marker davegut.kasaCommunications, line 230
		setCommsError() // library marker davegut.kasaCommunications, line 231
		message += "\n\t\t\t Setting Comms Error." // library marker davegut.kasaCommunications, line 232
	} // library marker davegut.kasaCommunications, line 233
	logDebug(message) // library marker davegut.kasaCommunications, line 234
} // library marker davegut.kasaCommunications, line 235

def setCommsError() { // library marker davegut.kasaCommunications, line 237
	def message = "setCommsError: Four consecutive errors.  Setting commsError to true." // library marker davegut.kasaCommunications, line 238
	if (device.currentValue("commsError") == "false") { // library marker davegut.kasaCommunications, line 239
		sendEvent(name: "commsError", value: "true") // library marker davegut.kasaCommunications, line 240
		message += "\n\t\tFix attempt ${parent.fixConnection(device.currentValue("connection"))}" // library marker davegut.kasaCommunications, line 241
		logWarn message // library marker davegut.kasaCommunications, line 242
	} // library marker davegut.kasaCommunications, line 243
} // library marker davegut.kasaCommunications, line 244

def resetCommsError() { // library marker davegut.kasaCommunications, line 246
	unschedule(handleCommsError) // library marker davegut.kasaCommunications, line 247
	sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommunications, line 248
	state.errorCount = 0 // library marker davegut.kasaCommunications, line 249
} // library marker davegut.kasaCommunications, line 250

private outputXOR(command) { // library marker davegut.kasaCommunications, line 252
	def str = "" // library marker davegut.kasaCommunications, line 253
	def encrCmd = "" // library marker davegut.kasaCommunications, line 254
 	def key = 0xAB // library marker davegut.kasaCommunications, line 255
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 256
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 257
		key = str // library marker davegut.kasaCommunications, line 258
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 259
	} // library marker davegut.kasaCommunications, line 260
   	return encrCmd // library marker davegut.kasaCommunications, line 261
} // library marker davegut.kasaCommunications, line 262

private inputXOR(encrResponse) { // library marker davegut.kasaCommunications, line 264
	String[] strBytes = encrResponse.split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 265
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 266
	def key = 0xAB // library marker davegut.kasaCommunications, line 267
	def nextKey // library marker davegut.kasaCommunications, line 268
	byte[] XORtemp // library marker davegut.kasaCommunications, line 269
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 270
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 271
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 272
		key = nextKey // library marker davegut.kasaCommunications, line 273
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 274
	} // library marker davegut.kasaCommunications, line 275
	return cmdResponse // library marker davegut.kasaCommunications, line 276
} // library marker davegut.kasaCommunications, line 277

private outputXorTcp(command) { // library marker davegut.kasaCommunications, line 279
	def str = "" // library marker davegut.kasaCommunications, line 280
	def encrCmd = "000000" + Integer.toHexString(command.length())  // library marker davegut.kasaCommunications, line 281
 	def key = 0xAB // library marker davegut.kasaCommunications, line 282
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 283
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 284
		key = str // library marker davegut.kasaCommunications, line 285
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 286
	} // library marker davegut.kasaCommunications, line 287
   	return encrCmd // library marker davegut.kasaCommunications, line 288
} // library marker davegut.kasaCommunications, line 289

private inputXorTcp(resp) { // library marker davegut.kasaCommunications, line 291
	String[] strBytes = resp.substring(8).split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 292
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 293
	def key = 0xAB // library marker davegut.kasaCommunications, line 294
	def nextKey // library marker davegut.kasaCommunications, line 295
	byte[] XORtemp // library marker davegut.kasaCommunications, line 296
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 297
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 298
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 299
		key = nextKey // library marker davegut.kasaCommunications, line 300
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 301
	} // library marker davegut.kasaCommunications, line 302
	return cmdResponse // library marker davegut.kasaCommunications, line 303
} // library marker davegut.kasaCommunications, line 304

def logTrace(msg){ // library marker davegut.kasaCommunications, line 306
	log.trace "[${type()} / ${driverVer()} / ${device.label}]| ${msg}" // library marker davegut.kasaCommunications, line 307
} // library marker davegut.kasaCommunications, line 308

def logInfo(msg) { // library marker davegut.kasaCommunications, line 310
	log.info "[${type()} / ${driverVer()} / ${device.label}]| ${msg}" // library marker davegut.kasaCommunications, line 311
} // library marker davegut.kasaCommunications, line 312

def logDebug(msg){ // library marker davegut.kasaCommunications, line 314
	if(debug == true) { // library marker davegut.kasaCommunications, line 315
		log.debug "[${type()} / ${driverVer()} / ${device.label}]| ${msg}" // library marker davegut.kasaCommunications, line 316
	} // library marker davegut.kasaCommunications, line 317
} // library marker davegut.kasaCommunications, line 318

def debugOff() { // library marker davegut.kasaCommunications, line 320
	device.updateSetting("debug", [type:"bool", value: false]) // library marker davegut.kasaCommunications, line 321
	logInfo("debugLogOff: Debug logging is off.") // library marker davegut.kasaCommunications, line 322
} // library marker davegut.kasaCommunications, line 323

def logWarn(msg) { // library marker davegut.kasaCommunications, line 325
	if (getDataValue("driverVersion") != driverVer()) { // library marker davegut.kasaCommunications, line 326
		msg += "\n\t\t<b>Run a Save Preferences and try again before reporting.</b>" // library marker davegut.kasaCommunications, line 327
		state.ISSUE = "<b>Run Save Preferences</b>\n\r" // library marker davegut.kasaCommunications, line 328
	} // library marker davegut.kasaCommunications, line 329
	log.warn "[${type()} / ${driverVer()} / ${device.label}]| ${msg}" // library marker davegut.kasaCommunications, line 330
} // library marker davegut.kasaCommunications, line 331

// ~~~~~ end include (259) davegut.kasaCommunications ~~~~~

// ~~~~~ start include (260) davegut.kasaEnergyMonitor ~~~~~
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
		return "Disabled" // library marker davegut.kasaEnergyMonitor, line 29
	} else { // library marker davegut.kasaEnergyMonitor, line 30
		return "Disabled" // library marker davegut.kasaEnergyMonitor, line 31
	} // library marker davegut.kasaEnergyMonitor, line 32
} // library marker davegut.kasaEnergyMonitor, line 33

def distEmeter(emeterResp) { // library marker davegut.kasaEnergyMonitor, line 35
	def year = new Date().format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 36
	def month = new Date().format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 37
	def lastMonth = month - 1 // library marker davegut.kasaEnergyMonitor, line 38
	if (lastMonth == 0) { lastMonth = 12 } // library marker davegut.kasaEnergyMonitor, line 39
	if (emeterResp.get_realtime) { // library marker davegut.kasaEnergyMonitor, line 40
		setPower(emeterResp.get_realtime) // library marker davegut.kasaEnergyMonitor, line 41
	} else if (emeterResp.get_monthstat.month_list.find { it.month == month && it.year == year}) { // library marker davegut.kasaEnergyMonitor, line 42
		setEnergyToday(emeterResp.get_monthstat) // library marker davegut.kasaEnergyMonitor, line 43
	} else if (emeterResp.get_monthstat.month_list.find { it.month == lastMonth }) { // library marker davegut.kasaEnergyMonitor, line 44
		setLastMonth(emeterResp.get_monthstat) // library marker davegut.kasaEnergyMonitor, line 45
	} else { // library marker davegut.kasaEnergyMonitor, line 46
		logWarn("distEmeter: Unhandled response = ${response}") // library marker davegut.kasaEnergyMonitor, line 47
	} // library marker davegut.kasaEnergyMonitor, line 48
} // library marker davegut.kasaEnergyMonitor, line 49

def getPower() { // library marker davegut.kasaEnergyMonitor, line 51
	logDebug("getPower") // library marker davegut.kasaEnergyMonitor, line 52
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaEnergyMonitor, line 53
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 54
				""""emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 55
	} else if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaEnergyMonitor, line 56
		sendCmd("""{"smartlife.iot.common.emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 57
	} else { // library marker davegut.kasaEnergyMonitor, line 58
		sendCmd("""{"emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 59
	} // library marker davegut.kasaEnergyMonitor, line 60
} // library marker davegut.kasaEnergyMonitor, line 61

def setPower(response) { // library marker davegut.kasaEnergyMonitor, line 63
	logDebug("setPower: ${response}") // library marker davegut.kasaEnergyMonitor, line 64
	def power = response.power // library marker davegut.kasaEnergyMonitor, line 65
	if (power == null) { power = response.power_mw / 1000 } // library marker davegut.kasaEnergyMonitor, line 66
	power = Math.round(10*(power))/10 // library marker davegut.kasaEnergyMonitor, line 67
	def curPwr = device.currentValue("power") // library marker davegut.kasaEnergyMonitor, line 68
	if (curPwr < 5 && (power > curPwr + 0.3 || power < curPwr - 0.3)) { // library marker davegut.kasaEnergyMonitor, line 69
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital") // library marker davegut.kasaEnergyMonitor, line 70
		logDebug("polResp: power = ${power}") // library marker davegut.kasaEnergyMonitor, line 71
	} else if (power > curPwr + 5 || power < curPwr - 5) { // library marker davegut.kasaEnergyMonitor, line 72
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital") // library marker davegut.kasaEnergyMonitor, line 73
		logDebug("polResp: power = ${power}") // library marker davegut.kasaEnergyMonitor, line 74
	} // library marker davegut.kasaEnergyMonitor, line 75
} // library marker davegut.kasaEnergyMonitor, line 76

def getEnergyToday() { // library marker davegut.kasaEnergyMonitor, line 78
	logDebug("getEnergyToday") // library marker davegut.kasaEnergyMonitor, line 79
	def year = new Date().format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 80
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaEnergyMonitor, line 81
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 82
				""""emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 83
	} else if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaEnergyMonitor, line 84
		sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 85
	} else { // library marker davegut.kasaEnergyMonitor, line 86
		sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 87
	} // library marker davegut.kasaEnergyMonitor, line 88
} // library marker davegut.kasaEnergyMonitor, line 89

def setEnergyToday(response) { // library marker davegut.kasaEnergyMonitor, line 91
	logDebug("setEnergyToday: response = ${response}") // library marker davegut.kasaEnergyMonitor, line 92
//logTrace("setEnergyToday: response = ${response}") // library marker davegut.kasaEnergyMonitor, line 93
	def month = new Date().format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 94
	def data = response.month_list.find { it.month == month } // library marker davegut.kasaEnergyMonitor, line 95
	def energy = data.energy // library marker davegut.kasaEnergyMonitor, line 96
	if (energy == null) { energy = data.energy_wh/1000 } // library marker davegut.kasaEnergyMonitor, line 97
	energy -= device.currentValue("currMonthTotal") // library marker davegut.kasaEnergyMonitor, line 98
	energy = Math.round(100*energy)/100 // library marker davegut.kasaEnergyMonitor, line 99
	def currEnergy = device.currentValue("energy") // library marker davegut.kasaEnergyMonitor, line 100
	if (currEnergy < energy + 0.05) { // library marker davegut.kasaEnergyMonitor, line 101
		sendEvent(name: "energy", value: energy, descriptionText: "KiloWatt Hours", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 102
		logDebug("setEngrToday: [energy: ${energy}]") // library marker davegut.kasaEnergyMonitor, line 103
	} // library marker davegut.kasaEnergyMonitor, line 104
	setThisMonth(response) // library marker davegut.kasaEnergyMonitor, line 105
} // library marker davegut.kasaEnergyMonitor, line 106


def setThisMonth(response) { // library marker davegut.kasaEnergyMonitor, line 109
	logDebug("setThisMonth: response = ${response}") // library marker davegut.kasaEnergyMonitor, line 110
	def month = new Date().format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 111
	def day = new Date().format("d").toInteger() // library marker davegut.kasaEnergyMonitor, line 112
	def data = response.month_list.find { it.month == month } // library marker davegut.kasaEnergyMonitor, line 113
	def totEnergy = data.energy // library marker davegut.kasaEnergyMonitor, line 114
	if (totEnergy == null) {  // library marker davegut.kasaEnergyMonitor, line 115
		totEnergy = data.energy_wh/1000 // library marker davegut.kasaEnergyMonitor, line 116
	} // library marker davegut.kasaEnergyMonitor, line 117
	totEnergy = Math.round(100*totEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 118
	def avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 119
//	Change != logic to ==.  Switched processing to get raw avgEnergy // library marker davegut.kasaEnergyMonitor, line 120
	if (day == 1) { // library marker davegut.kasaEnergyMonitor, line 121
		avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 122
	} else { // library marker davegut.kasaEnergyMonitor, line 123
		avgEnergy = totEnergy /(day - 1)  // library marker davegut.kasaEnergyMonitor, line 124
	} // library marker davegut.kasaEnergyMonitor, line 125
	avgEnergy = Math.round(100*avgEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 126
	sendEvent(name: "currMonthTotal", value: totEnergy,  // library marker davegut.kasaEnergyMonitor, line 127
			  descriptionText: "KiloWatt Hours", unit: "kWh") // library marker davegut.kasaEnergyMonitor, line 128
	sendEvent(name: "currMonthAvg", value: avgEnergy,  // library marker davegut.kasaEnergyMonitor, line 129
			  descriptionText: "KiloWatt Hours per Day", unit: "kWh/D") // library marker davegut.kasaEnergyMonitor, line 130
	logDebug("setThisMonth: Energy stats set to ${totEnergy} // ${avgEnergy}") // library marker davegut.kasaEnergyMonitor, line 131
	//	Send data to setLastMonth // library marker davegut.kasaEnergyMonitor, line 132
	if (month == 1) { // library marker davegut.kasaEnergyMonitor, line 133
		def year = new Date().format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 134
		year = year - 1 // library marker davegut.kasaEnergyMonitor, line 135
		if (getDataValue("plugNo") != null) { // library marker davegut.kasaEnergyMonitor, line 136
			sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 137
					""""emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 138
		} else if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaEnergyMonitor, line 139
			sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 140
		} else { // library marker davegut.kasaEnergyMonitor, line 141
			sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 142
		} // library marker davegut.kasaEnergyMonitor, line 143
	} else { // library marker davegut.kasaEnergyMonitor, line 144
		setLastMonth(response) // library marker davegut.kasaEnergyMonitor, line 145
	} // library marker davegut.kasaEnergyMonitor, line 146
} // library marker davegut.kasaEnergyMonitor, line 147

def setLastMonth(response) { // library marker davegut.kasaEnergyMonitor, line 149
	logDebug("setLastMonth: response = ${response}") // library marker davegut.kasaEnergyMonitor, line 150
	def year = new Date().format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 151
	def month = new Date().format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 152
	def day = new Date().format("d").toInteger() // library marker davegut.kasaEnergyMonitor, line 153
	def lastMonth // library marker davegut.kasaEnergyMonitor, line 154
	if (month == 1) { // library marker davegut.kasaEnergyMonitor, line 155
		lastMonth = 12 // library marker davegut.kasaEnergyMonitor, line 156
	} else { // library marker davegut.kasaEnergyMonitor, line 157
		lastMonth = month - 1 // library marker davegut.kasaEnergyMonitor, line 158
	} // library marker davegut.kasaEnergyMonitor, line 159
	def monthLength // library marker davegut.kasaEnergyMonitor, line 160
	switch(lastMonth) { // library marker davegut.kasaEnergyMonitor, line 161
		case 4: // library marker davegut.kasaEnergyMonitor, line 162
		case 6: // library marker davegut.kasaEnergyMonitor, line 163
		case 9: // library marker davegut.kasaEnergyMonitor, line 164
		case 11: // library marker davegut.kasaEnergyMonitor, line 165
			monthLength = 30 // library marker davegut.kasaEnergyMonitor, line 166
			break // library marker davegut.kasaEnergyMonitor, line 167
		case 2: // library marker davegut.kasaEnergyMonitor, line 168
			monthLength = 28 // library marker davegut.kasaEnergyMonitor, line 169
			if (year == 2020 || year == 2024 || year == 2028) { monthLength = 29 } // library marker davegut.kasaEnergyMonitor, line 170
			break // library marker davegut.kasaEnergyMonitor, line 171
		default: // library marker davegut.kasaEnergyMonitor, line 172
			monthLength = 31 // library marker davegut.kasaEnergyMonitor, line 173
	} // library marker davegut.kasaEnergyMonitor, line 174
	def data = response.month_list.find { it.month == lastMonth } // library marker davegut.kasaEnergyMonitor, line 175
	def totEnergy // library marker davegut.kasaEnergyMonitor, line 176
	if (data == null) { // library marker davegut.kasaEnergyMonitor, line 177
		totEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 178
	} else { // library marker davegut.kasaEnergyMonitor, line 179
		totEnergy = data.energy // library marker davegut.kasaEnergyMonitor, line 180
		if (totEnergy == null) {  // library marker davegut.kasaEnergyMonitor, line 181
			totEnergy = data.energy_wh/1000 // library marker davegut.kasaEnergyMonitor, line 182
		} // library marker davegut.kasaEnergyMonitor, line 183
		totEnergy = Math.round(100*totEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 184
	} // library marker davegut.kasaEnergyMonitor, line 185
	def avgEnergy = totEnergy / monthLength // library marker davegut.kasaEnergyMonitor, line 186
	avgEnergy = Math.round(100*avgEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 187
	sendEvent(name: "lastMonthTotal", value: totEnergy,  // library marker davegut.kasaEnergyMonitor, line 188
			  descriptionText: "KiloWatt Hours", unit: "kWh") // library marker davegut.kasaEnergyMonitor, line 189
	sendEvent(name: "lastMonthAvg", value: avgEnergy,  // library marker davegut.kasaEnergyMonitor, line 190
			  descriptionText: "KiloWatt Hoursper Day", unit: "kWh/D") // library marker davegut.kasaEnergyMonitor, line 191
	logDebug("setLastMonth: Energy stats set to ${totEnergy} // ${avgEnergy}") // library marker davegut.kasaEnergyMonitor, line 192
} // library marker davegut.kasaEnergyMonitor, line 193

// ~~~~~ end include (260) davegut.kasaEnergyMonitor ~~~~~
