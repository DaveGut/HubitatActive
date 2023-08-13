/*	Kasa Device Driver Series
		Copyright Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Link to list of changes =====
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Changes.pdf
===== Link to Documentation =====
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Documentation.pdf

Version 2.3.5-1
1.	Added ability to set energy today poll interval (1, 5, 30 minutes).
2.	Added ability to manually enter IP address and Port.
===================================================================================================*/
def driverVer() { return "2.3.5-1" }

//def type() { return "Plug Switch" }
//def type() { return "EM Plug" }
def type() { return "Multi Plug" }
//def type() { return "EM Multi Plug" }
def file() { return type().replaceAll(" ", "-") }

metadata {
	definition (name: "Kasa ${type()}",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/${file()}.groovy"
			   ) {
		capability "Switch"
		capability "Actuator"
		capability "Configuration"
		capability "Refresh"
		command "ledOn"
		command "ledOff"
		attribute "led", "string"
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
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
		attribute "connection", "string"
		attribute "commsError", "string"
	}
//	6.7.2 Change B.  change logging names and titles to match other built-in drivers.
	preferences {
		input ("textEnable", "bool", 
			   title: "Enable descriptionText logging",
			   defaultValue: true)
		input ("logEnable", "bool",
			   title: "Enable debug logging",
			   defaultValue: false)
		if (getDataValue("feature") == "TIM:ENE") {
			input ("emFunction", "bool", 
				   title: "Enable Energy Monitor", 
				   defaultValue: false)
			if (emFunction) {
				input ("energyPollInt", "enum",
					   title: "Energy Poll Interval (minutes)",
					   options: ["1 minute", "5 minutes", "30 minutes"],
					   defaultValue: "30 minutes")
			}
		}
		if (getDataValue("deviceIP") != "CLOUD" && getDataValue("model") == "HS200") {
			input ("altLan", "bool",
			   	title: "Alternate LAN Comms (for comms problems only)",
			   	defaultValue: false)
		}
		input ("bind", "bool",
			   title: "Kasa Cloud Binding",
			   defalutValue: true)
		input ("useCloud", "bool",
		 	  title: "Use Kasa Cloud for device control",
		 	  defaultValue: false)
		input ("nameSync", "enum", title: "Synchronize Names",
			   options: ["none": "Don't synchronize",
						 "device" : "Kasa device name master", 
						 "Hubitat" : "Hubitat label master"],
			   defaultValue: "none")
		input ("manualIp", "string",
			   title: "Manual IP Update <b>[Caution]</b>",
			   defaultValue: getDataValue("deviceIP"))
		input ("manualPort", "string",
			   title: "Manual Port Update <b>[Caution]</b>",
			   defaultValue: getDataValue("devicePort"))
		input ("rebootDev", "bool",
			   title: "Reboot device <b>[Caution]</b>",
			   defaultValue: false)
	}
}

def installed() {
		device.updateSetting("nameSync",[type:"enum", value:"device"])
	def instStatus = installCommon()
	logInfo("installed: ${instStatus}")
}

def updated() {
	def updStatus = updateCommon()
	if (getDataValue("feature") == "TIM:ENE") {
		updStatus << [emFunction: setupEmFunction()]
	}
	logInfo("updated: ${updStatus}")
	
	if (getDataValue("model") == "HS300") {
		updateDataValue("altComms", "false")
		state.remove("response")
	}
	
	refresh()
}

def setSysInfo(status) {
	def switchStatus = status.relay_state
	def ledStatus = status.led_off
	def logData = [:]
	if (getDataValue("plugNo") != null) {
		def childStatus = status.children.find { it.id == getDataValue("plugNo") }
		if (childStatus == null) {
			childStatus = status.children.find { it.id == getDataValue("plugId") }
		}
		status = childStatus
		switchStatus = status.state
	}
	
	def onOff = "on"
	if (switchStatus == 0) { onOff = "off" }
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff, type: "digital")
		logData << [switch: onOff]
	}
	def ledOnOff = "on"
	if (ledStatus == 1) { ledOnOff = "off" }
	if (device.currentValue("led") != ledOnOff) {
		sendEvent(name: "led", value: ledOnOff)
		logData << [led: ledOnOff]
	}
	
	if (logData != [:]) {
		logInfo("setSysinfo: ${logData}")
	}
	if (nameSync == "device" || nameSync == "Hubitat") {
		updateName(status)
	}
	getPower()
}

def coordUpdate(cType, coordData) {
	def msg = "coordinateUpdate: "
	if (cType == "commsData") {
		device.updateSetting("bind", [type:"bool", value: coordData.bind])
		device.updateSetting("useCloud", [type:"bool", value: coordData.useCloud])
		sendEvent(name: "connection", value: coordData.connection)
		device.updateSetting("altLan", [type:"bool", value: coordData.altLan])
		msg += "[commsData: ${coordData}]"
	} else {
		msg += "Not updated."
	}
	logInfo(msg)
}







// ~~~~~ start include (1281) davegut.kasaCommon ~~~~~
library ( // library marker davegut.kasaCommon, line 1
	name: "kasaCommon", // library marker davegut.kasaCommon, line 2
	namespace: "davegut", // library marker davegut.kasaCommon, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaCommon, line 4
	description: "Kasa Device Common Methods", // library marker davegut.kasaCommon, line 5
	category: "utilities", // library marker davegut.kasaCommon, line 6
	documentationLink: "" // library marker davegut.kasaCommon, line 7
) // library marker davegut.kasaCommon, line 8

def nameSpace() { return "davegut" } // library marker davegut.kasaCommon, line 10

def installCommon() { // library marker davegut.kasaCommon, line 12
	pauseExecution(3000) // library marker davegut.kasaCommon, line 13
	def instStatus = [:] // library marker davegut.kasaCommon, line 14
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 15
		sendEvent(name: "connection", value: "CLOUD") // library marker davegut.kasaCommon, line 16
		device.updateSetting("useCloud", [type:"bool", value: true]) // library marker davegut.kasaCommon, line 17
		instStatus << [useCloud: true, connection: "CLOUD"] // library marker davegut.kasaCommon, line 18
	} else { // library marker davegut.kasaCommon, line 19
		sendEvent(name: "connection", value: "LAN") // library marker davegut.kasaCommon, line 20
		device.updateSetting("useCloud", [type:"bool", value: false]) // library marker davegut.kasaCommon, line 21
		instStatus << [useCloud: false, connection: "LAN"] // library marker davegut.kasaCommon, line 22
	} // library marker davegut.kasaCommon, line 23

	sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommon, line 25
	state.errorCount = 0 // library marker davegut.kasaCommon, line 26
	state.pollInterval = "30 minutes" // library marker davegut.kasaCommon, line 27
	runIn(1, updated) // library marker davegut.kasaCommon, line 28
	return instStatus // library marker davegut.kasaCommon, line 29
} // library marker davegut.kasaCommon, line 30

def updateCommon() { // library marker davegut.kasaCommon, line 32
	def updStatus = [:] // library marker davegut.kasaCommon, line 33
	if (rebootDev) { // library marker davegut.kasaCommon, line 34
		updStatus << [rebootDev: rebootDevice()] // library marker davegut.kasaCommon, line 35
		return updStatus // library marker davegut.kasaCommon, line 36
	} // library marker davegut.kasaCommon, line 37
	unschedule() // library marker davegut.kasaCommon, line 38
	updStatus << [bind: bindUnbind()] // library marker davegut.kasaCommon, line 39
	if (nameSync != "none") { // library marker davegut.kasaCommon, line 40
		updStatus << [nameSync: syncName()] // library marker davegut.kasaCommon, line 41
	} // library marker davegut.kasaCommon, line 42
	if (logEnable) { runIn(1800, debugLogOff) } // library marker davegut.kasaCommon, line 43
	updStatus << [textEnable: textEnable, logEnable: logEnable] // library marker davegut.kasaCommon, line 44
	if (manualIp != getDataValue("deviceIP")) { // library marker davegut.kasaCommon, line 45
		updateDataValue("deviceIP", manualIp) // library marker davegut.kasaCommon, line 46
		updStatus << [ipUpdate: manualIp] // library marker davegut.kasaCommon, line 47
	} // library marker davegut.kasaCommon, line 48
	if (manualPort != getDataValue("devicePort")) { // library marker davegut.kasaCommon, line 49
		updateDataValue("devicePort", manualPort) // library marker davegut.kasaCommon, line 50
		updStatus << [portUpdate: manualPort] // library marker davegut.kasaCommon, line 51
	} // library marker davegut.kasaCommon, line 52
	state.errorCount = 0 // library marker davegut.kasaCommon, line 53
	sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommon, line 54
	def pollInterval = state.pollInterval // library marker davegut.kasaCommon, line 55
	if (pollInterval == null) { pollInterval = "30 minutes" } // library marker davegut.kasaCommon, line 56
	updStatus << [pollInterval: setPollInterval(pollInterval)] // library marker davegut.kasaCommon, line 57
	state.remove("UPDATE_AVAILABLE") // library marker davegut.kasaCommon, line 58
	state.remove("releaseNotes") // library marker davegut.kasaCommon, line 59
	removeDataValue("driverVersion") // library marker davegut.kasaCommon, line 60
	if (emFunction) { // library marker davegut.kasaCommon, line 61
		scheduleEnergyAttrs() // library marker davegut.kasaCommon, line 62
		state.getEnergy = "This Month" // library marker davegut.kasaCommon, line 63
		updStatus << [emFunction: "scheduled"] // library marker davegut.kasaCommon, line 64
	} // library marker davegut.kasaCommon, line 65
	runIn(5, listAttributes) // library marker davegut.kasaCommon, line 66
	return updStatus // library marker davegut.kasaCommon, line 67
} // library marker davegut.kasaCommon, line 68

def configure() { // library marker davegut.kasaCommon, line 70
	if (parent == null) { // library marker davegut.kasaCommon, line 71
		logWarn("configure: No Parent Detected.  Configure function ABORTED.  Use Save Preferences instead.") // library marker davegut.kasaCommon, line 72
	} else { // library marker davegut.kasaCommon, line 73
		def confStatus = parent.updateConfigurations() // library marker davegut.kasaCommon, line 74
		logInfo("configure: ${confStatus}") // library marker davegut.kasaCommon, line 75
	} // library marker davegut.kasaCommon, line 76
} // library marker davegut.kasaCommon, line 77

def refresh() { poll() } // library marker davegut.kasaCommon, line 79

def poll() { getSysinfo() } // library marker davegut.kasaCommon, line 81

def setPollInterval(interval = state.pollInterval) { // library marker davegut.kasaCommon, line 83
	if (interval == "default" || interval == "off" || interval == null) { // library marker davegut.kasaCommon, line 84
		interval = "30 minutes" // library marker davegut.kasaCommon, line 85
	} else if (useCloud || altLan || getDataValue("altComms") == "true") { // library marker davegut.kasaCommon, line 86
		if (interval.contains("sec")) { // library marker davegut.kasaCommon, line 87
			interval = "1 minute" // library marker davegut.kasaCommon, line 88
			logWarn("setPollInterval: Device using Cloud or rawSocket.  Poll interval reset to minimum value of 1 minute.") // library marker davegut.kasaCommon, line 89
		} // library marker davegut.kasaCommon, line 90
	} // library marker davegut.kasaCommon, line 91
	state.pollInterval = interval // library marker davegut.kasaCommon, line 92
	def pollInterval = interval.substring(0,2).toInteger() // library marker davegut.kasaCommon, line 93
	if (interval.contains("sec")) { // library marker davegut.kasaCommon, line 94
		def start = Math.round((pollInterval-1) * Math.random()).toInteger() // library marker davegut.kasaCommon, line 95
		schedule("${start}/${pollInterval} * * * * ?", "poll") // library marker davegut.kasaCommon, line 96
		logWarn("setPollInterval: Polling intervals of less than one minute " + // library marker davegut.kasaCommon, line 97
				"can take high resources and may impact hub performance.") // library marker davegut.kasaCommon, line 98
	} else { // library marker davegut.kasaCommon, line 99
		def start = Math.round(59 * Math.random()).toInteger() // library marker davegut.kasaCommon, line 100
		schedule("${start} */${pollInterval} * * * ?", "poll") // library marker davegut.kasaCommon, line 101
	} // library marker davegut.kasaCommon, line 102
	logDebug("setPollInterval: interval = ${interval}.") // library marker davegut.kasaCommon, line 103
	return interval // library marker davegut.kasaCommon, line 104
} // library marker davegut.kasaCommon, line 105

def rebootDevice() { // library marker davegut.kasaCommon, line 107
	device.updateSetting("rebootDev", [type:"bool", value: false]) // library marker davegut.kasaCommon, line 108
	reboot() // library marker davegut.kasaCommon, line 109
	pauseExecution(10000) // library marker davegut.kasaCommon, line 110
	return "REBOOTING DEVICE" // library marker davegut.kasaCommon, line 111
} // library marker davegut.kasaCommon, line 112

def bindUnbind() { // library marker davegut.kasaCommon, line 114
	def message // library marker davegut.kasaCommon, line 115
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 116
		device.updateSetting("bind", [type:"bool", value: true]) // library marker davegut.kasaCommon, line 117
		device.updateSetting("useCloud", [type:"bool", value: true]) // library marker davegut.kasaCommon, line 118
		message = "No deviceIp.  Bind not modified." // library marker davegut.kasaCommon, line 119
	} else if (bind == null ||  getDataValue("feature") == "lightStrip") { // library marker davegut.kasaCommon, line 120
		message = "Getting current bind state" // library marker davegut.kasaCommon, line 121
		getBind() // library marker davegut.kasaCommon, line 122
	} else if (bind == true) { // library marker davegut.kasaCommon, line 123
		if (!parent.kasaToken || parent.userName == null || parent.userPassword == null) { // library marker davegut.kasaCommon, line 124
			message = "Username/pwd not set." // library marker davegut.kasaCommon, line 125
			getBind() // library marker davegut.kasaCommon, line 126
		} else { // library marker davegut.kasaCommon, line 127
			message = "Binding device to the Kasa Cloud." // library marker davegut.kasaCommon, line 128
			setBind(parent.userName, parent.userPassword) // library marker davegut.kasaCommon, line 129
		} // library marker davegut.kasaCommon, line 130
	} else if (bind == false) { // library marker davegut.kasaCommon, line 131
		message = "Unbinding device from the Kasa Cloud." // library marker davegut.kasaCommon, line 132
		setUnbind() // library marker davegut.kasaCommon, line 133
	} // library marker davegut.kasaCommon, line 134
	pauseExecution(5000) // library marker davegut.kasaCommon, line 135
	return message // library marker davegut.kasaCommon, line 136
} // library marker davegut.kasaCommon, line 137

def setBindUnbind(cmdResp) { // library marker davegut.kasaCommon, line 139
	def bindState = true // library marker davegut.kasaCommon, line 140
	if (cmdResp.get_info) { // library marker davegut.kasaCommon, line 141
		if (cmdResp.get_info.binded == 0) { bindState = false } // library marker davegut.kasaCommon, line 142
		logInfo("setBindUnbind: Bind status set to ${bindState}") // library marker davegut.kasaCommon, line 143
		setCommsType(bindState) // library marker davegut.kasaCommon, line 144
	} else if (cmdResp.bind.err_code == 0){ // library marker davegut.kasaCommon, line 145
		getBind() // library marker davegut.kasaCommon, line 146
	} else { // library marker davegut.kasaCommon, line 147
		logWarn("setBindUnbind: Unhandled response: ${cmdResp}") // library marker davegut.kasaCommon, line 148
	} // library marker davegut.kasaCommon, line 149
} // library marker davegut.kasaCommon, line 150

def setCommsType(bindState) { // library marker davegut.kasaCommon, line 152
	def commsType = "LAN" // library marker davegut.kasaCommon, line 153
	def cloudCtrl = false // library marker davegut.kasaCommon, line 154
	if (bindState == false && useCloud == true) { // library marker davegut.kasaCommon, line 155
		logWarn("setCommsType: Can not use cloud.  Device is not bound to Kasa cloud.") // library marker davegut.kasaCommon, line 156
	} else if (bindState == true && useCloud == true && parent.kasaToken) { // library marker davegut.kasaCommon, line 157
		commsType = "CLOUD" // library marker davegut.kasaCommon, line 158
		cloudCtrl = true // library marker davegut.kasaCommon, line 159
	} else if (altLan == true) { // library marker davegut.kasaCommon, line 160
		commsType = "AltLAN" // library marker davegut.kasaCommon, line 161
		state.response = "" // library marker davegut.kasaCommon, line 162
	} // library marker davegut.kasaCommon, line 163
	def commsSettings = [bind: bindState, useCloud: cloudCtrl, commsType: commsType] // library marker davegut.kasaCommon, line 164
	device.updateSetting("bind", [type:"bool", value: bindState]) // library marker davegut.kasaCommon, line 165
	device.updateSetting("useCloud", [type:"bool", value: cloudCtrl]) // library marker davegut.kasaCommon, line 166
	sendEvent(name: "connection", value: "${commsType}") // library marker davegut.kasaCommon, line 167
	logInfo("setCommsType: ${commsSettings}") // library marker davegut.kasaCommon, line 168
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaCommon, line 169
		def coordData = [:] // library marker davegut.kasaCommon, line 170
		coordData << [bind: bindState] // library marker davegut.kasaCommon, line 171
		coordData << [useCloud: cloudCtrl] // library marker davegut.kasaCommon, line 172
		coordData << [connection: commsType] // library marker davegut.kasaCommon, line 173
		coordData << [altLan: altLan] // library marker davegut.kasaCommon, line 174
		parent.coordinate("commsData", coordData, getDataValue("deviceId"), getDataValue("plugNo")) // library marker davegut.kasaCommon, line 175
	} // library marker davegut.kasaCommon, line 176
	pauseExecution(1000) // library marker davegut.kasaCommon, line 177
} // library marker davegut.kasaCommon, line 178

def syncName() { // library marker davegut.kasaCommon, line 180
	def message // library marker davegut.kasaCommon, line 181
	if (nameSync == "Hubitat") { // library marker davegut.kasaCommon, line 182
		message = "Hubitat Label Sync" // library marker davegut.kasaCommon, line 183
		setDeviceAlias(device.getLabel()) // library marker davegut.kasaCommon, line 184
	} else if (nameSync == "device") { // library marker davegut.kasaCommon, line 185
		message = "Device Alias Sync" // library marker davegut.kasaCommon, line 186
	} else { // library marker davegut.kasaCommon, line 187
		message = "Not Syncing" // library marker davegut.kasaCommon, line 188
	} // library marker davegut.kasaCommon, line 189
	return message // library marker davegut.kasaCommon, line 190
} // library marker davegut.kasaCommon, line 191

def updateName(response) { // library marker davegut.kasaCommon, line 193
	device.updateSetting("nameSync",[type:"enum", value:"none"]) // library marker davegut.kasaCommon, line 194
	def name = device.getLabel() // library marker davegut.kasaCommon, line 195
	if (response.alias) { // library marker davegut.kasaCommon, line 196
		name = response.alias // library marker davegut.kasaCommon, line 197
		device.setLabel(name) // library marker davegut.kasaCommon, line 198
		parent.updateAlias(device.deviceNetworkId, name) // library marker davegut.kasaCommon, line 199
	} else if (response.err_code != 0) { // library marker davegut.kasaCommon, line 200
		def msg = "updateName: Name Sync from Hubitat to Device returned an error." // library marker davegut.kasaCommon, line 201
		msg+= "\n\rNote: <b>Some devices do not support syncing name from the hub.</b>\n\r" // library marker davegut.kasaCommon, line 202
		logWarn(msg) // library marker davegut.kasaCommon, line 203
		return // library marker davegut.kasaCommon, line 204
	} // library marker davegut.kasaCommon, line 205
	logInfo("updateName: Hubitat and Kasa device name synchronized to ${name}") // library marker davegut.kasaCommon, line 206
} // library marker davegut.kasaCommon, line 207

def getSysinfo() { // library marker davegut.kasaCommon, line 209
	if (getDataValue("altComms") == "true") { // library marker davegut.kasaCommon, line 210
		sendTcpCmd("""{"system":{"get_sysinfo":{}}}""") // library marker davegut.kasaCommon, line 211
	} else { // library marker davegut.kasaCommon, line 212
		sendCmd("""{"system":{"get_sysinfo":{}}}""") // library marker davegut.kasaCommon, line 213
	} // library marker davegut.kasaCommon, line 214
} // library marker davegut.kasaCommon, line 215

def bindService() { // library marker davegut.kasaCommon, line 217
	def service = "cnCloud" // library marker davegut.kasaCommon, line 218
	def feature = getDataValue("feature") // library marker davegut.kasaCommon, line 219
	if (feature.contains("Bulb") || feature == "lightStrip") { // library marker davegut.kasaCommon, line 220
		service = "smartlife.iot.common.cloud" // library marker davegut.kasaCommon, line 221
	} // library marker davegut.kasaCommon, line 222
	return service // library marker davegut.kasaCommon, line 223
} // library marker davegut.kasaCommon, line 224

def getBind() { // library marker davegut.kasaCommon, line 226
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 227
		logDebug("getBind: [status: notRun, reason: [deviceIP: CLOUD]]") // library marker davegut.kasaCommon, line 228
	} else { // library marker davegut.kasaCommon, line 229
		sendLanCmd("""{"${bindService()}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 230
	} // library marker davegut.kasaCommon, line 231
} // library marker davegut.kasaCommon, line 232

def setBind(userName, password) { // library marker davegut.kasaCommon, line 234
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 235
		logDebug("setBind: [status: notRun, reason: [deviceIP: CLOUD]]") // library marker davegut.kasaCommon, line 236
	} else { // library marker davegut.kasaCommon, line 237
		sendLanCmd("""{"${bindService()}":{"bind":{"username":"${userName}",""" + // library marker davegut.kasaCommon, line 238
				   """"password":"${password}"}},""" + // library marker davegut.kasaCommon, line 239
				   """"${bindService()}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 240
	} // library marker davegut.kasaCommon, line 241
} // library marker davegut.kasaCommon, line 242

def setUnbind() { // library marker davegut.kasaCommon, line 244
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 245
		logDebug("setUnbind: [status: notRun, reason: [deviceIP: CLOUD]]") // library marker davegut.kasaCommon, line 246
	} else { // library marker davegut.kasaCommon, line 247
		sendLanCmd("""{"${bindService()}":{"unbind":""},""" + // library marker davegut.kasaCommon, line 248
				   """"${bindService()}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 249
	} // library marker davegut.kasaCommon, line 250
} // library marker davegut.kasaCommon, line 251

def sysService() { // library marker davegut.kasaCommon, line 253
	def service = "system" // library marker davegut.kasaCommon, line 254
	def feature = getDataValue("feature") // library marker davegut.kasaCommon, line 255
	if (feature.contains("Bulb") || feature == "lightStrip") { // library marker davegut.kasaCommon, line 256
		service = "smartlife.iot.common.system" // library marker davegut.kasaCommon, line 257
	} // library marker davegut.kasaCommon, line 258
	return service // library marker davegut.kasaCommon, line 259
} // library marker davegut.kasaCommon, line 260

def reboot() { // library marker davegut.kasaCommon, line 262
	sendCmd("""{"${sysService()}":{"reboot":{"delay":1}}}""") // library marker davegut.kasaCommon, line 263
} // library marker davegut.kasaCommon, line 264

def setDeviceAlias(newAlias) { // library marker davegut.kasaCommon, line 266
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaCommon, line 267
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaCommon, line 268
				""""system":{"set_dev_alias":{"alias":"${device.getLabel()}"}}}""") // library marker davegut.kasaCommon, line 269
	} else { // library marker davegut.kasaCommon, line 270
		sendCmd("""{"${sysService()}":{"set_dev_alias":{"alias":"${device.getLabel()}"}}}""") // library marker davegut.kasaCommon, line 271
	} // library marker davegut.kasaCommon, line 272
} // library marker davegut.kasaCommon, line 273

// ~~~~~ end include (1281) davegut.kasaCommon ~~~~~

// ~~~~~ start include (1282) davegut.kasaCommunications ~~~~~
library ( // library marker davegut.kasaCommunications, line 1
	name: "kasaCommunications", // library marker davegut.kasaCommunications, line 2
	namespace: "davegut", // library marker davegut.kasaCommunications, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaCommunications, line 4
	description: "Kasa Communications Methods", // library marker davegut.kasaCommunications, line 5
	category: "communications", // library marker davegut.kasaCommunications, line 6
	documentationLink: "" // library marker davegut.kasaCommunications, line 7
) // library marker davegut.kasaCommunications, line 8

import groovy.json.JsonSlurper // library marker davegut.kasaCommunications, line 10

def getPort() { // library marker davegut.kasaCommunications, line 12
	def port = 9999 // library marker davegut.kasaCommunications, line 13
	if (getDataValue("devicePort")) { // library marker davegut.kasaCommunications, line 14
		port = getDataValue("devicePort") // library marker davegut.kasaCommunications, line 15
	} // library marker davegut.kasaCommunications, line 16
	return port // library marker davegut.kasaCommunications, line 17
} // library marker davegut.kasaCommunications, line 18

def sendCmd(command) { // library marker davegut.kasaCommunications, line 20
	state.lastCommand = command // library marker davegut.kasaCommunications, line 21
	def connection = device.currentValue("connection") // library marker davegut.kasaCommunications, line 22
	if (connection == "LAN") { // library marker davegut.kasaCommunications, line 23
		sendLanCmd(command) // library marker davegut.kasaCommunications, line 24
	} else if (connection == "CLOUD") { // library marker davegut.kasaCommunications, line 25
		sendKasaCmd(command) // library marker davegut.kasaCommunications, line 26
	} else if (connection == "AltLAN") { // library marker davegut.kasaCommunications, line 27
		sendTcpCmd(command) // library marker davegut.kasaCommunications, line 28
	} else { // library marker davegut.kasaCommunications, line 29
		logWarn("sendCmd: attribute connection is not set.") // library marker davegut.kasaCommunications, line 30
	} // library marker davegut.kasaCommunications, line 31
} // library marker davegut.kasaCommunications, line 32

def sendLanCmd(command) { // library marker davegut.kasaCommunications, line 34
	logDebug("sendLanCmd: [ip: ${getDataValue("deviceIP")}, cmd: ${command}]") // library marker davegut.kasaCommunications, line 35
	def myHubAction = new hubitat.device.HubAction( // library marker davegut.kasaCommunications, line 36
		outputXOR(command), // library marker davegut.kasaCommunications, line 37
		hubitat.device.Protocol.LAN, // library marker davegut.kasaCommunications, line 38
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT, // library marker davegut.kasaCommunications, line 39
		 destinationAddress: "${getDataValue("deviceIP")}:${getPort()}", // library marker davegut.kasaCommunications, line 40
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING, // library marker davegut.kasaCommunications, line 41
		 parseWarning: true, // library marker davegut.kasaCommunications, line 42
		 timeout: 9, // library marker davegut.kasaCommunications, line 43
		 ignoreResponse: false, // library marker davegut.kasaCommunications, line 44
		 callback: "parseUdp"]) // library marker davegut.kasaCommunications, line 45
	try { // library marker davegut.kasaCommunications, line 46
		sendHubCommand(myHubAction) // library marker davegut.kasaCommunications, line 47
	} catch (e) { // library marker davegut.kasaCommunications, line 48
		logWarn("sendLanCmd: LAN Error = ${e}.\n\rNo retry on this error.") // library marker davegut.kasaCommunications, line 49
	} // library marker davegut.kasaCommunications, line 50
} // library marker davegut.kasaCommunications, line 51
def parseUdp(message) { // library marker davegut.kasaCommunications, line 52
	def resp = parseLanMessage(message) // library marker davegut.kasaCommunications, line 53
	if (resp.type == "LAN_TYPE_UDPCLIENT") { // library marker davegut.kasaCommunications, line 54
		def clearResp = inputXOR(resp.payload) // library marker davegut.kasaCommunications, line 55
		if (clearResp.length() > 1023) { // library marker davegut.kasaCommunications, line 56
			if (clearResp.contains("preferred")) { // library marker davegut.kasaCommunications, line 57
				clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}" // library marker davegut.kasaCommunications, line 58
			} else if (clearResp.contains("child_num")) { // library marker davegut.kasaCommunications, line 59
				clearResp = clearResp.substring(0,clearResp.indexOf("child_num") -2) + "}}}" // library marker davegut.kasaCommunications, line 60
			} else { // library marker davegut.kasaCommunications, line 61
				logWarn("parseUdp: [status: converting to altComms, error: udp msg can not be parsed]") // library marker davegut.kasaCommunications, line 62
				logDebug("parseUdp: [messageData: ${clearResp}]") // library marker davegut.kasaCommunications, line 63
				updateDataValue("altComms", "true") // library marker davegut.kasaCommunications, line 64
				sendTcpCmd(state.lastCommand) // library marker davegut.kasaCommunications, line 65
				return // library marker davegut.kasaCommunications, line 66
			} // library marker davegut.kasaCommunications, line 67
		} // library marker davegut.kasaCommunications, line 68
		def cmdResp = new JsonSlurper().parseText(clearResp) // library marker davegut.kasaCommunications, line 69
		logDebug("parseUdp: ${cmdResp}") // library marker davegut.kasaCommunications, line 70
		distResp(cmdResp) // library marker davegut.kasaCommunications, line 71
//		state.lastCommand = "" // library marker davegut.kasaCommunications, line 72
//		resetCommsError() // library marker davegut.kasaCommunications, line 73
	} else { // library marker davegut.kasaCommunications, line 74
		logDebug("parseUdp: [error: error, reason: not LAN_TYPE_UDPCLIENT, respType: ${resp.type}]") // library marker davegut.kasaCommunications, line 75
//		handleCommsError() // library marker davegut.kasaCommunications, line 76
	} // library marker davegut.kasaCommunications, line 77
} // library marker davegut.kasaCommunications, line 78

def sendKasaCmd(command) { // library marker davegut.kasaCommunications, line 80
	logDebug("sendKasaCmd: ${command}") // library marker davegut.kasaCommunications, line 81
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 82
	def cmdBody = [ // library marker davegut.kasaCommunications, line 83
		method: "passthrough", // library marker davegut.kasaCommunications, line 84
		params: [ // library marker davegut.kasaCommunications, line 85
			deviceId: getDataValue("deviceId"), // library marker davegut.kasaCommunications, line 86
			requestData: "${command}" // library marker davegut.kasaCommunications, line 87
		] // library marker davegut.kasaCommunications, line 88
	] // library marker davegut.kasaCommunications, line 89
	if (!parent.kasaCloudUrl || !parent.kasaToken) { // library marker davegut.kasaCommunications, line 90
		logWarn("sendKasaCmd: Cloud interface not properly set up.") // library marker davegut.kasaCommunications, line 91
		return // library marker davegut.kasaCommunications, line 92
	} // library marker davegut.kasaCommunications, line 93
	def sendCloudCmdParams = [ // library marker davegut.kasaCommunications, line 94
		uri: "${parent.kasaCloudUrl}/?token=${parent.kasaToken}", // library marker davegut.kasaCommunications, line 95
		requestContentType: 'application/json', // library marker davegut.kasaCommunications, line 96
		contentType: 'application/json', // library marker davegut.kasaCommunications, line 97
		headers: ['Accept':'application/json; version=1, */*; q=0.01'], // library marker davegut.kasaCommunications, line 98
		timeout: 10, // library marker davegut.kasaCommunications, line 99
		body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.kasaCommunications, line 100
	] // library marker davegut.kasaCommunications, line 101
	try { // library marker davegut.kasaCommunications, line 102
		asynchttpPost("cloudParse", sendCloudCmdParams) // library marker davegut.kasaCommunications, line 103
	} catch (e) { // library marker davegut.kasaCommunications, line 104
		def msg = "sendKasaCmd: <b>Error in Cloud Communications.</b> The Kasa Cloud is unreachable." // library marker davegut.kasaCommunications, line 105
		msg += "\nAdditional Data: Error = ${e}\n\n" // library marker davegut.kasaCommunications, line 106
		logWarn(msg) // library marker davegut.kasaCommunications, line 107
	} // library marker davegut.kasaCommunications, line 108
} // library marker davegut.kasaCommunications, line 109
def cloudParse(resp, data = null) { // library marker davegut.kasaCommunications, line 110
	try { // library marker davegut.kasaCommunications, line 111
		response = new JsonSlurper().parseText(resp.data) // library marker davegut.kasaCommunications, line 112
	} catch (e) { // library marker davegut.kasaCommunications, line 113
		response = [error_code: 9999, data: e] // library marker davegut.kasaCommunications, line 114
	} // library marker davegut.kasaCommunications, line 115
	if (resp.status == 200 && response.error_code == 0 && resp != []) { // library marker davegut.kasaCommunications, line 116
		def cmdResp = new JsonSlurper().parseText(response.result.responseData) // library marker davegut.kasaCommunications, line 117
		logDebug("cloudParse: ${cmdResp}") // library marker davegut.kasaCommunications, line 118
		distResp(cmdResp) // library marker davegut.kasaCommunications, line 119
	} else { // library marker davegut.kasaCommunications, line 120
		def msg = "cloudParse:\n<b>Error from the Kasa Cloud.</b> Most common cause is " // library marker davegut.kasaCommunications, line 121
		msg += "your Kasa Token has expired.  Run Kasa Login and Token update and try again." // library marker davegut.kasaCommunications, line 122
		msg += "\nAdditional Data: Error = ${resp.data}\n\n" // library marker davegut.kasaCommunications, line 123
		logDebug(msg) // library marker davegut.kasaCommunications, line 124
	} // library marker davegut.kasaCommunications, line 125
} // library marker davegut.kasaCommunications, line 126

def sendTcpCmd(command) { // library marker davegut.kasaCommunications, line 128
	logDebug("sendTcpCmd: ${command}") // library marker davegut.kasaCommunications, line 129
	try { // library marker davegut.kasaCommunications, line 130
		interfaces.rawSocket.connect("${getDataValue("deviceIP")}", // library marker davegut.kasaCommunications, line 131
									 getPort().toInteger(), byteInterface: true) // library marker davegut.kasaCommunications, line 132
	} catch (error) { // library marker davegut.kasaCommunications, line 133
		logDebug("SendTcpCmd: [connectFailed: [ip: ${getDataValue("deviceIP")}, Error = ${error}]]") // library marker davegut.kasaCommunications, line 134
	} // library marker davegut.kasaCommunications, line 135
	state.response = "" // library marker davegut.kasaCommunications, line 136
	interfaces.rawSocket.sendMessage(outputXorTcp(command)) // library marker davegut.kasaCommunications, line 137
} // library marker davegut.kasaCommunications, line 138
def close() { interfaces.rawSocket.close() } // library marker davegut.kasaCommunications, line 139
def socketStatus(message) { // library marker davegut.kasaCommunications, line 140
	if (message != "receive error: Stream closed.") { // library marker davegut.kasaCommunications, line 141
		logDebug("socketStatus: Socket Established") // library marker davegut.kasaCommunications, line 142
	} else { // library marker davegut.kasaCommunications, line 143
		logWarn("socketStatus = ${message}") // library marker davegut.kasaCommunications, line 144
	} // library marker davegut.kasaCommunications, line 145
} // library marker davegut.kasaCommunications, line 146
def parse(message) { // library marker davegut.kasaCommunications, line 147
	if (message != null || message != "") { // library marker davegut.kasaCommunications, line 148
		def response = state.response.concat(message) // library marker davegut.kasaCommunications, line 149
		state.response = response // library marker davegut.kasaCommunications, line 150
		extractTcpResp(response) // library marker davegut.kasaCommunications, line 151
	} // library marker davegut.kasaCommunications, line 152
} // library marker davegut.kasaCommunications, line 153
def extractTcpResp(response) { // library marker davegut.kasaCommunications, line 154
	def cmdResp // library marker davegut.kasaCommunications, line 155
	def clearResp = inputXorTcp(response) // library marker davegut.kasaCommunications, line 156
	if (clearResp.endsWith("}}}")) { // library marker davegut.kasaCommunications, line 157
		interfaces.rawSocket.close() // library marker davegut.kasaCommunications, line 158
		try { // library marker davegut.kasaCommunications, line 159
			cmdResp = parseJson(clearResp) // library marker davegut.kasaCommunications, line 160
			distResp(cmdResp) // library marker davegut.kasaCommunications, line 161
		} catch (e) { // library marker davegut.kasaCommunications, line 162
			logWarn("extractTcpResp: [length: ${clearResp.length()}, clearResp: ${clearResp}, comms error: ${e}]") // library marker davegut.kasaCommunications, line 163
		} // library marker davegut.kasaCommunications, line 164
	} else if (clearResp.length() > 2000) { // library marker davegut.kasaCommunications, line 165
		interfaces.rawSocket.close() // library marker davegut.kasaCommunications, line 166
	} // library marker davegut.kasaCommunications, line 167
} // library marker davegut.kasaCommunications, line 168

def handleCommsError() { // library marker davegut.kasaCommunications, line 170
	if (state.lastCommand == "") { return } // library marker davegut.kasaCommunications, line 171
	def count = state.errorCount + 1 // library marker davegut.kasaCommunications, line 172
	state.errorCount = count // library marker davegut.kasaCommunications, line 173
	def retry = true // library marker davegut.kasaCommunications, line 174
	def status = [count: count, command: state.lastCommand] // library marker davegut.kasaCommunications, line 175
	if (count == 3) { // library marker davegut.kasaCommunications, line 176
		def attemptFix = parent.fixConnection() // library marker davegut.kasaCommunications, line 177
		status << [attemptFixResult: [attemptFix]] // library marker davegut.kasaCommunications, line 178
	} else if (count >= 4) { // library marker davegut.kasaCommunications, line 179
		retry = false // library marker davegut.kasaCommunications, line 180
	} // library marker davegut.kasaCommunications, line 181
	if (retry == true) { // library marker davegut.kasaCommunications, line 182
		if (state.lastCommand != null) {  // library marker davegut.kasaCommunications, line 183
			if (getDataValue("altComms") == "true") { // library marker davegut.kasaCommunications, line 184
				sendTcpCmd(state.lastCommand) // library marker davegut.kasaCommunications, line 185
			} else { // library marker davegut.kasaCommunications, line 186
				sendCmd(state.lastCommand) // library marker davegut.kasaCommunications, line 187
			} // library marker davegut.kasaCommunications, line 188
		} // library marker davegut.kasaCommunications, line 189
	} else { // library marker davegut.kasaCommunications, line 190
		setCommsError() // library marker davegut.kasaCommunications, line 191
	} // library marker davegut.kasaCommunications, line 192
	status << [retry: retry] // library marker davegut.kasaCommunications, line 193
	if (status.count > 2) { // library marker davegut.kasaCommunications, line 194
		logWarn("handleCommsError: ${status}") // library marker davegut.kasaCommunications, line 195
	} else { // library marker davegut.kasaCommunications, line 196
		logDebug("handleCommsError: ${status}") // library marker davegut.kasaCommunications, line 197
	} // library marker davegut.kasaCommunications, line 198
} // library marker davegut.kasaCommunications, line 199

def setCommsError() { // library marker davegut.kasaCommunications, line 201
	if (device.currentValue("commsError") == "false") { // library marker davegut.kasaCommunications, line 202
		def message = "Can't connect to your device at ${getDataValue("deviceIP")}:${getPort()}. " // library marker davegut.kasaCommunications, line 203
		message += "Refer to troubleshooting guide commsError section." // library marker davegut.kasaCommunications, line 204
		sendEvent(name: "commsError", value: "true") // library marker davegut.kasaCommunications, line 205
		state.COMMS_ERROR = message			 // library marker davegut.kasaCommunications, line 206
		logWarn("setCommsError: <b>${message}</b>") // library marker davegut.kasaCommunications, line 207
		runIn(15, limitPollInterval) // library marker davegut.kasaCommunications, line 208
	} // library marker davegut.kasaCommunications, line 209
} // library marker davegut.kasaCommunications, line 210

def limitPollInterval() { // library marker davegut.kasaCommunications, line 212
	state.nonErrorPollInterval = state.pollInterval // library marker davegut.kasaCommunications, line 213
	setPollInterval("30 minutes") // library marker davegut.kasaCommunications, line 214
} // library marker davegut.kasaCommunications, line 215

def resetCommsError() { // library marker davegut.kasaCommunications, line 217
	state.errorCount = 0 // library marker davegut.kasaCommunications, line 218
	if (device.currentValue("commsError") == "true") { // library marker davegut.kasaCommunications, line 219
		sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommunications, line 220
		setPollInterval(state.nonErrorPollInterval) // library marker davegut.kasaCommunications, line 221
		state.remove("nonErrorPollInterval") // library marker davegut.kasaCommunications, line 222
		state.remove("COMMS_ERROR") // library marker davegut.kasaCommunications, line 223
		logInfo("resetCommsError: Comms error cleared!") // library marker davegut.kasaCommunications, line 224
	} // library marker davegut.kasaCommunications, line 225
} // library marker davegut.kasaCommunications, line 226

private outputXOR(command) { // library marker davegut.kasaCommunications, line 228
	def str = "" // library marker davegut.kasaCommunications, line 229
	def encrCmd = "" // library marker davegut.kasaCommunications, line 230
 	def key = 0xAB // library marker davegut.kasaCommunications, line 231
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 232
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 233
		key = str // library marker davegut.kasaCommunications, line 234
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 235
	} // library marker davegut.kasaCommunications, line 236
   	return encrCmd // library marker davegut.kasaCommunications, line 237
} // library marker davegut.kasaCommunications, line 238

private inputXOR(encrResponse) { // library marker davegut.kasaCommunications, line 240
	String[] strBytes = encrResponse.split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 241
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 242
	def key = 0xAB // library marker davegut.kasaCommunications, line 243
	def nextKey // library marker davegut.kasaCommunications, line 244
	byte[] XORtemp // library marker davegut.kasaCommunications, line 245
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 246
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 247
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 248
		key = nextKey // library marker davegut.kasaCommunications, line 249
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 250
	} // library marker davegut.kasaCommunications, line 251
	return cmdResponse // library marker davegut.kasaCommunications, line 252
} // library marker davegut.kasaCommunications, line 253

private outputXorTcp(command) { // library marker davegut.kasaCommunications, line 255
	def str = "" // library marker davegut.kasaCommunications, line 256
	def encrCmd = "000000" + Integer.toHexString(command.length())  // library marker davegut.kasaCommunications, line 257
 	def key = 0xAB // library marker davegut.kasaCommunications, line 258
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 259
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 260
		key = str // library marker davegut.kasaCommunications, line 261
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 262
	} // library marker davegut.kasaCommunications, line 263
   	return encrCmd // library marker davegut.kasaCommunications, line 264
} // library marker davegut.kasaCommunications, line 265

private inputXorTcp(resp) { // library marker davegut.kasaCommunications, line 267
	String[] strBytes = resp.substring(8).split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 268
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 269
	def key = 0xAB // library marker davegut.kasaCommunications, line 270
	def nextKey // library marker davegut.kasaCommunications, line 271
	byte[] XORtemp // library marker davegut.kasaCommunications, line 272
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 273
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 274
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 275
		key = nextKey // library marker davegut.kasaCommunications, line 276
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 277
	} // library marker davegut.kasaCommunications, line 278
	return cmdResponse // library marker davegut.kasaCommunications, line 279
} // library marker davegut.kasaCommunications, line 280

// ~~~~~ end include (1282) davegut.kasaCommunications ~~~~~

// ~~~~~ start include (1279) davegut.commonLogging ~~~~~
library ( // library marker davegut.commonLogging, line 1
	name: "commonLogging", // library marker davegut.commonLogging, line 2
	namespace: "davegut", // library marker davegut.commonLogging, line 3
	author: "Dave Gutheinz", // library marker davegut.commonLogging, line 4
	description: "Common Logging Methods", // library marker davegut.commonLogging, line 5
	category: "utilities", // library marker davegut.commonLogging, line 6
	documentationLink: "" // library marker davegut.commonLogging, line 7
) // library marker davegut.commonLogging, line 8

//	Logging during development // library marker davegut.commonLogging, line 10
def listAttributes(trace = false) { // library marker davegut.commonLogging, line 11
	def attrs = device.getSupportedAttributes() // library marker davegut.commonLogging, line 12
	def attrList = [:] // library marker davegut.commonLogging, line 13
	attrs.each { // library marker davegut.commonLogging, line 14
		def val = device.currentValue("${it}") // library marker davegut.commonLogging, line 15
		attrList << ["${it}": val] // library marker davegut.commonLogging, line 16
	} // library marker davegut.commonLogging, line 17
	if (trace == true) { // library marker davegut.commonLogging, line 18
		logInfo("Attributes: ${attrList}") // library marker davegut.commonLogging, line 19
	} else { // library marker davegut.commonLogging, line 20
		logDebug("Attributes: ${attrList}") // library marker davegut.commonLogging, line 21
	} // library marker davegut.commonLogging, line 22
} // library marker davegut.commonLogging, line 23

//	6.7.2 Change B.  Remove driverVer() // library marker davegut.commonLogging, line 25
def logTrace(msg){ // library marker davegut.commonLogging, line 26
	log.trace "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.commonLogging, line 27
} // library marker davegut.commonLogging, line 28

def logInfo(msg) {  // library marker davegut.commonLogging, line 30
	if (textEnable || infoLog) { // library marker davegut.commonLogging, line 31
		log.info "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.commonLogging, line 32
	} // library marker davegut.commonLogging, line 33
} // library marker davegut.commonLogging, line 34

def debugLogOff() { // library marker davegut.commonLogging, line 36
	if (logEnable) { // library marker davegut.commonLogging, line 37
		device.updateSetting("logEnable", [type:"bool", value: false]) // library marker davegut.commonLogging, line 38
	} // library marker davegut.commonLogging, line 39
	logInfo("debugLogOff") // library marker davegut.commonLogging, line 40
} // library marker davegut.commonLogging, line 41

def logDebug(msg) { // library marker davegut.commonLogging, line 43
	if (logEnable || debugLog) { // library marker davegut.commonLogging, line 44
		log.debug "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.commonLogging, line 45
	} // library marker davegut.commonLogging, line 46
} // library marker davegut.commonLogging, line 47

def logWarn(msg) { log.warn "${device.displayName}-${driverVer()}: ${msg}" } // library marker davegut.commonLogging, line 49

// ~~~~~ end include (1279) davegut.commonLogging ~~~~~

// ~~~~~ start include (1285) davegut.kasaPlugs ~~~~~
library ( // library marker davegut.kasaPlugs, line 1
	name: "kasaPlugs", // library marker davegut.kasaPlugs, line 2
	namespace: "davegut", // library marker davegut.kasaPlugs, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaPlugs, line 4
	description: "Kasa Plug and Switches Common Methods", // library marker davegut.kasaPlugs, line 5
	category: "utilities", // library marker davegut.kasaPlugs, line 6
	documentationLink: "" // library marker davegut.kasaPlugs, line 7
) // library marker davegut.kasaPlugs, line 8

def on() { setRelayState(1) } // library marker davegut.kasaPlugs, line 10

def off() { setRelayState(0) } // library marker davegut.kasaPlugs, line 12

def ledOn() { setLedOff(0) } // library marker davegut.kasaPlugs, line 14

def ledOff() { setLedOff(1) } // library marker davegut.kasaPlugs, line 16

def distResp(response) { // library marker davegut.kasaPlugs, line 18
	if (response.system) { // library marker davegut.kasaPlugs, line 19
		if (response.system.get_sysinfo) { // library marker davegut.kasaPlugs, line 20
			setSysInfo(response.system.get_sysinfo) // library marker davegut.kasaPlugs, line 21
		} else if (response.system.set_relay_state || // library marker davegut.kasaPlugs, line 22
				   response.system.set_led_off) { // library marker davegut.kasaPlugs, line 23
			if (getDataValue("model") == "HS210") { // library marker davegut.kasaPlugs, line 24
				runIn(2, getSysinfo) // library marker davegut.kasaPlugs, line 25
			} else { // library marker davegut.kasaPlugs, line 26
				getSysinfo() // library marker davegut.kasaPlugs, line 27
			} // library marker davegut.kasaPlugs, line 28
		} else if (response.system.reboot) { // library marker davegut.kasaPlugs, line 29
			logWarn("distResp: Rebooting device.") // library marker davegut.kasaPlugs, line 30
		} else if (response.system.set_dev_alias) { // library marker davegut.kasaPlugs, line 31
			updateName(response.system.set_dev_alias) // library marker davegut.kasaPlugs, line 32
		} else { // library marker davegut.kasaPlugs, line 33
			logDebug("distResp: Unhandled response = ${response}") // library marker davegut.kasaPlugs, line 34
		} // library marker davegut.kasaPlugs, line 35
	} else if (response["smartlife.iot.dimmer"]) { // library marker davegut.kasaPlugs, line 36
		if (response["smartlife.iot.dimmer"].get_dimmer_parameters) { // library marker davegut.kasaPlugs, line 37
			setDimmerConfig(response["smartlife.iot.dimmer"]) // library marker davegut.kasaPlugs, line 38
		} else { // library marker davegut.kasaPlugs, line 39
			logDebug("distResp: Unhandled response: ${response["smartlife.iot.dimmer"]}") // library marker davegut.kasaPlugs, line 40
		} // library marker davegut.kasaPlugs, line 41
	} else if (response.emeter) { // library marker davegut.kasaPlugs, line 42
		distEmeter(response.emeter) // library marker davegut.kasaPlugs, line 43
	} else if (response.cnCloud) { // library marker davegut.kasaPlugs, line 44
		setBindUnbind(response.cnCloud) // library marker davegut.kasaPlugs, line 45
	} else { // library marker davegut.kasaPlugs, line 46
		logDebug("distResp: Unhandled response = ${response}") // library marker davegut.kasaPlugs, line 47
	} // library marker davegut.kasaPlugs, line 48
} // library marker davegut.kasaPlugs, line 49

def setRelayState(onOff) { // library marker davegut.kasaPlugs, line 51
	logDebug("setRelayState: [switch: ${onOff}]") // library marker davegut.kasaPlugs, line 52
	if (getDataValue("plugNo") == null) { // library marker davegut.kasaPlugs, line 53
		sendCmd("""{"system":{"set_relay_state":{"state":${onOff}}}}""") // library marker davegut.kasaPlugs, line 54
	} else { // library marker davegut.kasaPlugs, line 55
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaPlugs, line 56
				""""system":{"set_relay_state":{"state":${onOff}}}}""") // library marker davegut.kasaPlugs, line 57
	} // library marker davegut.kasaPlugs, line 58
} // library marker davegut.kasaPlugs, line 59

def setLedOff(onOff) { // library marker davegut.kasaPlugs, line 61
	logDebug("setLedOff: [ledOff: ${onOff}]") // library marker davegut.kasaPlugs, line 62
		sendCmd("""{"system":{"set_led_off":{"off":${onOff}}}}""") // library marker davegut.kasaPlugs, line 63
} // library marker davegut.kasaPlugs, line 64

// ~~~~~ end include (1285) davegut.kasaPlugs ~~~~~

// ~~~~~ start include (1283) davegut.kasaEnergyMonitor ~~~~~
library ( // library marker davegut.kasaEnergyMonitor, line 1
	name: "kasaEnergyMonitor", // library marker davegut.kasaEnergyMonitor, line 2
	namespace: "davegut", // library marker davegut.kasaEnergyMonitor, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaEnergyMonitor, line 4
	description: "Kasa Device Energy Monitor Methods", // library marker davegut.kasaEnergyMonitor, line 5
	category: "energyMonitor", // library marker davegut.kasaEnergyMonitor, line 6
	documentationLink: "" // library marker davegut.kasaEnergyMonitor, line 7
) // library marker davegut.kasaEnergyMonitor, line 8

def setupEmFunction() { // library marker davegut.kasaEnergyMonitor, line 10
	if (emFunction && device.currentValue("currMonthTotal") > 0) { // library marker davegut.kasaEnergyMonitor, line 11
		state.getEnergy = "This Month" // library marker davegut.kasaEnergyMonitor, line 12
		return "Continuing EM Function" // library marker davegut.kasaEnergyMonitor, line 13
	} else if (emFunction) { // library marker davegut.kasaEnergyMonitor, line 14
		zeroizeEnergyAttrs() // library marker davegut.kasaEnergyMonitor, line 15
		state.response = "" // library marker davegut.kasaEnergyMonitor, line 16
		state.getEnergy = "This Month" // library marker davegut.kasaEnergyMonitor, line 17
		//	Run order / delay is critical for successful operation. // library marker davegut.kasaEnergyMonitor, line 18
		getEnergyThisMonth() // library marker davegut.kasaEnergyMonitor, line 19
		runIn(10, getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 20
		return "Initialized" // library marker davegut.kasaEnergyMonitor, line 21
	} else if (emFunction && device.currentValue("power") != null) { // library marker davegut.kasaEnergyMonitor, line 22
		//	for power != null, EM had to be enabled at one time.  Set values to 0. // library marker davegut.kasaEnergyMonitor, line 23
		zeroizeEnergyAttrs() // library marker davegut.kasaEnergyMonitor, line 24
		state.remove("getEnergy") // library marker davegut.kasaEnergyMonitor, line 25
		return "Disabled" // library marker davegut.kasaEnergyMonitor, line 26
	} else { // library marker davegut.kasaEnergyMonitor, line 27
		return "Not initialized" // library marker davegut.kasaEnergyMonitor, line 28
	} // library marker davegut.kasaEnergyMonitor, line 29
} // library marker davegut.kasaEnergyMonitor, line 30

def scheduleEnergyAttrs() { // library marker davegut.kasaEnergyMonitor, line 32
	schedule("10 0 0 * * ?", getEnergyThisMonth) // library marker davegut.kasaEnergyMonitor, line 33
	schedule("15 2 0 1 * ?", getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 34
	switch(energyPollInt) { // library marker davegut.kasaEnergyMonitor, line 35
		case "1 minute": // library marker davegut.kasaEnergyMonitor, line 36
			runEvery1Minute(getEnergyToday) // library marker davegut.kasaEnergyMonitor, line 37
			break // library marker davegut.kasaEnergyMonitor, line 38
		case "5 minutes": // library marker davegut.kasaEnergyMonitor, line 39
			runEvery5Minutes(getEnergyToday) // library marker davegut.kasaEnergyMonitor, line 40
			break // library marker davegut.kasaEnergyMonitor, line 41
		default: // library marker davegut.kasaEnergyMonitor, line 42
			runEvery30Minutes(getEnergyToday) // library marker davegut.kasaEnergyMonitor, line 43
	} // library marker davegut.kasaEnergyMonitor, line 44
} // library marker davegut.kasaEnergyMonitor, line 45

def zeroizeEnergyAttrs() { // library marker davegut.kasaEnergyMonitor, line 47
	sendEvent(name: "power", value: 0, unit: "W") // library marker davegut.kasaEnergyMonitor, line 48
	sendEvent(name: "energy", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 49
	sendEvent(name: "currMonthTotal", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 50
	sendEvent(name: "currMonthAvg", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 51
	sendEvent(name: "lastMonthTotal", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 52
	sendEvent(name: "lastMonthAvg", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 53
} // library marker davegut.kasaEnergyMonitor, line 54

def getDate() { // library marker davegut.kasaEnergyMonitor, line 56
	def currDate = new Date() // library marker davegut.kasaEnergyMonitor, line 57
	int year = currDate.format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 58
	int month = currDate.format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 59
	int day = currDate.format("d").toInteger() // library marker davegut.kasaEnergyMonitor, line 60
	return [year: year, month: month, day: day] // library marker davegut.kasaEnergyMonitor, line 61
} // library marker davegut.kasaEnergyMonitor, line 62

def distEmeter(emeterResp) { // library marker davegut.kasaEnergyMonitor, line 64
	def date = getDate() // library marker davegut.kasaEnergyMonitor, line 65
	logDebug("distEmeter: ${emeterResp}, ${date}, ${state.getEnergy}") // library marker davegut.kasaEnergyMonitor, line 66
	def lastYear = date.year - 1 // library marker davegut.kasaEnergyMonitor, line 67
	if (emeterResp.get_realtime) { // library marker davegut.kasaEnergyMonitor, line 68
		setPower(emeterResp.get_realtime) // library marker davegut.kasaEnergyMonitor, line 69
	} else if (emeterResp.get_monthstat) { // library marker davegut.kasaEnergyMonitor, line 70
		def monthList = emeterResp.get_monthstat.month_list // library marker davegut.kasaEnergyMonitor, line 71
		if (state.getEnergy == "Today") { // library marker davegut.kasaEnergyMonitor, line 72
			setEnergyToday(monthList, date) // library marker davegut.kasaEnergyMonitor, line 73
		} else if (state.getEnergy == "This Month") { // library marker davegut.kasaEnergyMonitor, line 74
			setThisMonth(monthList, date) // library marker davegut.kasaEnergyMonitor, line 75
		} else if (state.getEnergy == "Last Month") { // library marker davegut.kasaEnergyMonitor, line 76
			setLastMonth(monthList, date) // library marker davegut.kasaEnergyMonitor, line 77
		} else if (monthList == []) { // library marker davegut.kasaEnergyMonitor, line 78
			logDebug("distEmeter: monthList Empty. No data for year.") // library marker davegut.kasaEnergyMonitor, line 79
		} // library marker davegut.kasaEnergyMonitor, line 80
	} else { // library marker davegut.kasaEnergyMonitor, line 81
		logWarn("distEmeter: Unhandled response = ${emeterResp}") // library marker davegut.kasaEnergyMonitor, line 82
	} // library marker davegut.kasaEnergyMonitor, line 83
} // library marker davegut.kasaEnergyMonitor, line 84

def getPower() { // library marker davegut.kasaEnergyMonitor, line 86
	if (emFunction) { // library marker davegut.kasaEnergyMonitor, line 87
		if (device.currentValue("switch") == "on") { // library marker davegut.kasaEnergyMonitor, line 88
			getRealtime() // library marker davegut.kasaEnergyMonitor, line 89
		} else if (device.currentValue("power") != 0) { // library marker davegut.kasaEnergyMonitor, line 90
			sendEvent(name: "power", value: 0, descriptionText: "Watts", unit: "W", type: "digital") // library marker davegut.kasaEnergyMonitor, line 91
		} // library marker davegut.kasaEnergyMonitor, line 92
	} // library marker davegut.kasaEnergyMonitor, line 93
} // library marker davegut.kasaEnergyMonitor, line 94

def setPower(response) { // library marker davegut.kasaEnergyMonitor, line 96
	logDebug("setPower: ${response}") // library marker davegut.kasaEnergyMonitor, line 97
	def power = response.power // library marker davegut.kasaEnergyMonitor, line 98
	if (power == null) { power = response.power_mw / 1000 } // library marker davegut.kasaEnergyMonitor, line 99
	power = (power + 0.5).toInteger() // library marker davegut.kasaEnergyMonitor, line 100
	def curPwr = device.currentValue("power") // library marker davegut.kasaEnergyMonitor, line 101
	def pwrChange = false // library marker davegut.kasaEnergyMonitor, line 102
	if (curPwr != power) { // library marker davegut.kasaEnergyMonitor, line 103
		if (curPwr == null || (curPwr == 0 && power > 0)) { // library marker davegut.kasaEnergyMonitor, line 104
			pwrChange = true // library marker davegut.kasaEnergyMonitor, line 105
		} else { // library marker davegut.kasaEnergyMonitor, line 106
			def changeRatio = Math.abs((power - curPwr) / curPwr) // library marker davegut.kasaEnergyMonitor, line 107
			if (changeRatio > 0.03) { // library marker davegut.kasaEnergyMonitor, line 108
				pwrChange = true // library marker davegut.kasaEnergyMonitor, line 109
			} // library marker davegut.kasaEnergyMonitor, line 110
		} // library marker davegut.kasaEnergyMonitor, line 111
	} // library marker davegut.kasaEnergyMonitor, line 112
	if (pwrChange == true) { // library marker davegut.kasaEnergyMonitor, line 113
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital") // library marker davegut.kasaEnergyMonitor, line 114
	} // library marker davegut.kasaEnergyMonitor, line 115
} // library marker davegut.kasaEnergyMonitor, line 116

def getEnergyToday() { // library marker davegut.kasaEnergyMonitor, line 118
	if (device.currentValue("switch") == "on") { // library marker davegut.kasaEnergyMonitor, line 119
		state.getEnergy = "Today" // library marker davegut.kasaEnergyMonitor, line 120
		def year = getDate().year // library marker davegut.kasaEnergyMonitor, line 121
		logDebug("getEnergyToday: ${year}") // library marker davegut.kasaEnergyMonitor, line 122
		runIn(5, getMonthstat, [data: year]) // library marker davegut.kasaEnergyMonitor, line 123
	} // library marker davegut.kasaEnergyMonitor, line 124
} // library marker davegut.kasaEnergyMonitor, line 125

def setEnergyToday(monthList, date) { // library marker davegut.kasaEnergyMonitor, line 127
	logDebug("setEnergyToday: ${date}, ${monthList}") // library marker davegut.kasaEnergyMonitor, line 128
	def data = monthList.find { it.month == date.month && it.year == date.year} // library marker davegut.kasaEnergyMonitor, line 129
	def status = [:] // library marker davegut.kasaEnergyMonitor, line 130
	def energy = 0 // library marker davegut.kasaEnergyMonitor, line 131
	if (data == null) { // library marker davegut.kasaEnergyMonitor, line 132
		status << [msgError: "Return Data Null"] // library marker davegut.kasaEnergyMonitor, line 133
	} else { // library marker davegut.kasaEnergyMonitor, line 134
		energy = data.energy // library marker davegut.kasaEnergyMonitor, line 135
		if (energy == null) { energy = data.energy_wh/1000 } // library marker davegut.kasaEnergyMonitor, line 136
		energy = Math.round(100*energy)/100 - device.currentValue("currMonthTotal") // library marker davegut.kasaEnergyMonitor, line 137
	} // library marker davegut.kasaEnergyMonitor, line 138
	if (device.currentValue("energy") != energy) { // library marker davegut.kasaEnergyMonitor, line 139
		sendEvent(name: "energy", value: energy, descriptionText: "KiloWatt Hours", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 140
		status << [energy: energy] // library marker davegut.kasaEnergyMonitor, line 141
	} // library marker davegut.kasaEnergyMonitor, line 142
	if (status != [:]) { logInfo("setEnergyToday: ${status}") } // library marker davegut.kasaEnergyMonitor, line 143
	if (!state.getEnergy) { // library marker davegut.kasaEnergyMonitor, line 144
		schedule("10 0 0 * * ?", getEnergyThisMonth) // library marker davegut.kasaEnergyMonitor, line 145
		schedule("15 2 0 1 * ?", getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 146
		state.getEnergy = "This Month" // library marker davegut.kasaEnergyMonitor, line 147
		getEnergyThisMonth() // library marker davegut.kasaEnergyMonitor, line 148
		runIn(10, getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 149
	} // library marker davegut.kasaEnergyMonitor, line 150
} // library marker davegut.kasaEnergyMonitor, line 151

def getEnergyThisMonth() { // library marker davegut.kasaEnergyMonitor, line 153
	state.getEnergy = "This Month" // library marker davegut.kasaEnergyMonitor, line 154
	def year = getDate().year // library marker davegut.kasaEnergyMonitor, line 155
	logDebug("getEnergyThisMonth: ${year}") // library marker davegut.kasaEnergyMonitor, line 156
	runIn(5, getMonthstat, [data: year]) // library marker davegut.kasaEnergyMonitor, line 157
} // library marker davegut.kasaEnergyMonitor, line 158

def setThisMonth(monthList, date) { // library marker davegut.kasaEnergyMonitor, line 160
	logDebug("setThisMonth: ${date} // ${monthList}") // library marker davegut.kasaEnergyMonitor, line 161
	def data = monthList.find { it.month == date.month && it.year == date.year} // library marker davegut.kasaEnergyMonitor, line 162
	def status = [:] // library marker davegut.kasaEnergyMonitor, line 163
	def totEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 164
	def avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 165
	if (data == null) { // library marker davegut.kasaEnergyMonitor, line 166
		status << [msgError: "Return Data Null"] // library marker davegut.kasaEnergyMonitor, line 167
	} else { // library marker davegut.kasaEnergyMonitor, line 168
		status << [msgError: "OK"] // library marker davegut.kasaEnergyMonitor, line 169
		totEnergy = data.energy // library marker davegut.kasaEnergyMonitor, line 170
		if (totEnergy == null) { totEnergy = data.energy_wh/1000 } // library marker davegut.kasaEnergyMonitor, line 171
		if (date.day == 1) { // library marker davegut.kasaEnergyMonitor, line 172
			avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 173
		} else { // library marker davegut.kasaEnergyMonitor, line 174
			avgEnergy = totEnergy /(date.day - 1) // library marker davegut.kasaEnergyMonitor, line 175
		} // library marker davegut.kasaEnergyMonitor, line 176
	} // library marker davegut.kasaEnergyMonitor, line 177
	totEnergy = Math.round(100*totEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 178
	avgEnergy = Math.round(100*avgEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 179
	sendEvent(name: "currMonthTotal", value: totEnergy,  // library marker davegut.kasaEnergyMonitor, line 180
			  descriptionText: "KiloWatt Hours", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 181
	status << [currMonthTotal: totEnergy] // library marker davegut.kasaEnergyMonitor, line 182
	sendEvent(name: "currMonthAvg", value: avgEnergy,  // library marker davegut.kasaEnergyMonitor, line 183
		 	 descriptionText: "KiloWatt Hours per Day", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 184
	status << [currMonthAvg: avgEnergy] // library marker davegut.kasaEnergyMonitor, line 185
	getEnergyToday() // library marker davegut.kasaEnergyMonitor, line 186
	logInfo("setThisMonth: ${status}") // library marker davegut.kasaEnergyMonitor, line 187
} // library marker davegut.kasaEnergyMonitor, line 188

def getEnergyLastMonth() { // library marker davegut.kasaEnergyMonitor, line 190
	state.getEnergy = "Last Month" // library marker davegut.kasaEnergyMonitor, line 191
	def date = getDate() // library marker davegut.kasaEnergyMonitor, line 192
	def year = date.year // library marker davegut.kasaEnergyMonitor, line 193
	if (date.month == 1) { // library marker davegut.kasaEnergyMonitor, line 194
		year = year - 1 // library marker davegut.kasaEnergyMonitor, line 195
	} // library marker davegut.kasaEnergyMonitor, line 196
	logDebug("getEnergyLastMonth: ${year}") // library marker davegut.kasaEnergyMonitor, line 197
	runIn(5, getMonthstat, [data: year]) // library marker davegut.kasaEnergyMonitor, line 198
} // library marker davegut.kasaEnergyMonitor, line 199

def setLastMonth(monthList, date) { // library marker davegut.kasaEnergyMonitor, line 201
	logDebug("setLastMonth: ${date} // ${monthList}") // library marker davegut.kasaEnergyMonitor, line 202
	def lastMonthYear = date.year // library marker davegut.kasaEnergyMonitor, line 203
	def lastMonth = date.month - 1 // library marker davegut.kasaEnergyMonitor, line 204
	if (date.month == 1) { // library marker davegut.kasaEnergyMonitor, line 205
		lastMonthYear -+ 1 // library marker davegut.kasaEnergyMonitor, line 206
		lastMonth = 12 // library marker davegut.kasaEnergyMonitor, line 207
	} // library marker davegut.kasaEnergyMonitor, line 208
	def data = monthList.find { it.month == lastMonth } // library marker davegut.kasaEnergyMonitor, line 209
	def status = [:] // library marker davegut.kasaEnergyMonitor, line 210
	def totEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 211
	def avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 212
	if (data == null) { // library marker davegut.kasaEnergyMonitor, line 213
		status << [msgError: "Return Data Null"] // library marker davegut.kasaEnergyMonitor, line 214
	} else { // library marker davegut.kasaEnergyMonitor, line 215
		status << [msgError: "OK"] // library marker davegut.kasaEnergyMonitor, line 216
		def monthLength // library marker davegut.kasaEnergyMonitor, line 217
		switch(lastMonth) { // library marker davegut.kasaEnergyMonitor, line 218
			case 4: // library marker davegut.kasaEnergyMonitor, line 219
			case 6: // library marker davegut.kasaEnergyMonitor, line 220
			case 9: // library marker davegut.kasaEnergyMonitor, line 221
			case 11: // library marker davegut.kasaEnergyMonitor, line 222
				monthLength = 30 // library marker davegut.kasaEnergyMonitor, line 223
				break // library marker davegut.kasaEnergyMonitor, line 224
			case 2: // library marker davegut.kasaEnergyMonitor, line 225
				monthLength = 28 // library marker davegut.kasaEnergyMonitor, line 226
				if (lastMonthYear == 2020 || lastMonthYear == 2024 || lastMonthYear == 2028) {  // library marker davegut.kasaEnergyMonitor, line 227
					monthLength = 29 // library marker davegut.kasaEnergyMonitor, line 228
				} // library marker davegut.kasaEnergyMonitor, line 229
				break // library marker davegut.kasaEnergyMonitor, line 230
			default: // library marker davegut.kasaEnergyMonitor, line 231
				monthLength = 31 // library marker davegut.kasaEnergyMonitor, line 232
		} // library marker davegut.kasaEnergyMonitor, line 233
		totEnergy = data.energy // library marker davegut.kasaEnergyMonitor, line 234
		if (totEnergy == null) { totEnergy = data.energy_wh/1000 } // library marker davegut.kasaEnergyMonitor, line 235
		avgEnergy = totEnergy / monthLength // library marker davegut.kasaEnergyMonitor, line 236
	} // library marker davegut.kasaEnergyMonitor, line 237
	totEnergy = Math.round(100*totEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 238
	avgEnergy = Math.round(100*avgEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 239
	sendEvent(name: "lastMonthTotal", value: totEnergy,  // library marker davegut.kasaEnergyMonitor, line 240
			  descriptionText: "KiloWatt Hours", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 241
	status << [lastMonthTotal: totEnergy] // library marker davegut.kasaEnergyMonitor, line 242
	sendEvent(name: "lastMonthAvg", value: avgEnergy,  // library marker davegut.kasaEnergyMonitor, line 243
			  descriptionText: "KiloWatt Hoursper Day", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 244
	status << [lastMonthAvg: avgEnergy] // library marker davegut.kasaEnergyMonitor, line 245
	logInfo("setLastMonth: ${status}") // library marker davegut.kasaEnergyMonitor, line 246
} // library marker davegut.kasaEnergyMonitor, line 247

def getRealtime() { // library marker davegut.kasaEnergyMonitor, line 249
	def feature = getDataValue("feature") // library marker davegut.kasaEnergyMonitor, line 250
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaEnergyMonitor, line 251
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 252
				""""emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 253
	} else if (feature.contains("Bulb") || feature == "lightStrip") { // library marker davegut.kasaEnergyMonitor, line 254
		sendCmd("""{"smartlife.iot.common.emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 255
	} else { // library marker davegut.kasaEnergyMonitor, line 256
		sendCmd("""{"emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 257
	} // library marker davegut.kasaEnergyMonitor, line 258
} // library marker davegut.kasaEnergyMonitor, line 259

def getMonthstat(year) { // library marker davegut.kasaEnergyMonitor, line 261
	def feature = getDataValue("feature") // library marker davegut.kasaEnergyMonitor, line 262
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaEnergyMonitor, line 263
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 264
				""""emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 265
	} else if (feature.contains("Bulb") || feature == "lightStrip") { // library marker davegut.kasaEnergyMonitor, line 266
		sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 267
	} else { // library marker davegut.kasaEnergyMonitor, line 268
		sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 269
	} // library marker davegut.kasaEnergyMonitor, line 270
} // library marker davegut.kasaEnergyMonitor, line 271

// ~~~~~ end include (1283) davegut.kasaEnergyMonitor ~~~~~
