/*	Kasa Device Driver Series
		Copyright Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Link to list of changes =====
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Changes.pdf
===== Link to Documentation =====
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Documentation.pdf
===================================================================================================*/
metadata {
	definition (name: "Kasa Mono Bulb",
				namespace: nameSpace(),
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/WhiteBulb.groovy"
			   ) {
		capability "Light"
		capability "Switch"
		capability "Switch Level"
		capability "Change Level"
		capability "Refresh"
		capability "Actuator"
		capability "Configuration"
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["default", "1 minute", "5 minutes",  "10 minutes",
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
		input ("emFunction", "bool", 
			   title: "Enable Energy Monitor", 
			   defaultValue: false)
		input ("bind", "bool",
			   title: "Kasa Cloud Binding",
			   defalutValue: true)
		input ("useCloud", "bool",
		 	  title: "Use Kasa Cloud for device control",
		 	  defaultValue: false)
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
}

def updated() {
	def updStatus = updateCommon()
	updStatus << [emFunction: setupEmFunction()]
	def transTime = transition_Time
	if (transTime == null) {
		transTime = 1
		device.updateSetting("transition_Time", [type:"number", value: 1])
	}
	updStatus << [transition_Time: transTime]
	logInfo("updated: ${updStatus}")
	refresh()
}

def distResp(response) {
	if (response.system) {
		if (response.system.get_sysinfo) {
			setSysInfo(response.system.get_sysinfo)
			if (nameSync == "device") {
				updateName(response.system.get_sysinfo)
			}
		} else if (response.system.set_dev_alias) {
			updateName(response.system.set_dev_alias)
		} else {
			logWarn("distResp: Unhandled response = ${response}")
		}
	} else if (response["smartlife.iot.smartbulb.lightingservice"]) {
		setSysInfo([light_state:response["smartlife.iot.smartbulb.lightingservice"].transition_light_state])
	} else if (response["smartlife.iot.common.emeter"]) {
		distEmeter(response["smartlife.iot.common.emeter"])
	} else if (response["smartlife.iot.common.cloud"]) {
		setBindUnbind(response["smartlife.iot.common.cloud"])
	} else if (response["smartlife.iot.common.system"]) {
		if (response["smartlife.iot.common.system"].reboot) {
			logWarn("distResp: Rebooting device")
		} else {
			logDebug("distResp: Unhandled reboot response: ${response}")
		}
	} else {
		logWarn("distResp: Unhandled response = ${response}")
	}
}

def setSysInfo(status) {
	def lightStatus = status.light_state
	if (state.lastStatus != lightStatus) {
		state.lastStatus = lightStatus
		logInfo("setSysinfo: [status: ${lightStatus}]")
		def onOff
		int level
		if (lightStatus.on_off == 0) {
			onOff = "off"
		} else {
			onOff = "on"
			level = lightStatus.brightness
		}
		sendEvent(name: "switch", value: onOff, type: "digital")
		if (device.currentValue("level") != level) {
			sendEvent(name: "level", value: level)
		}
	}
	runIn(1, getPower)
}







// ~~~~~ start include (1178) davegut.kasaCommon ~~~~~
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
	if (debug) { runIn(1800, debugLogOff) } // library marker davegut.kasaCommon, line 43
	updStatus << [debug: debug] // library marker davegut.kasaCommon, line 44
	state.errorCount = 0 // library marker davegut.kasaCommon, line 45
	sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommon, line 46
	def pollInterval = state.pollInterval // library marker davegut.kasaCommon, line 47
	if (pollInterval == null) { pollInterval = "30 minutes" } // library marker davegut.kasaCommon, line 48
	updStatus << [pollInterval: setPollInterval(pollInterval)] // library marker davegut.kasaCommon, line 49
	state.remove("UPDATE_AVAILABLE") // library marker davegut.kasaCommon, line 50
	state.remove("releaseNotes") // library marker davegut.kasaCommon, line 51
	removeDataValue("driverVersion") // library marker davegut.kasaCommon, line 52
	if (emFunction) { // library marker davegut.kasaCommon, line 53
		schedule("10 0 0 * * ?", getEnergyThisMonth) // library marker davegut.kasaCommon, line 54
		schedule("15 2 0 1 * ?", getEnergyLastMonth) // library marker davegut.kasaCommon, line 55
		state.getEnergy = "This Month" // library marker davegut.kasaCommon, line 56
		updStatus << [emFunction: "scheduled"] // library marker davegut.kasaCommon, line 57
	} // library marker davegut.kasaCommon, line 58
	runIn(5, listAttributes) // library marker davegut.kasaCommon, line 59
	return updStatus // library marker davegut.kasaCommon, line 60
} // library marker davegut.kasaCommon, line 61

def configure() { // library marker davegut.kasaCommon, line 63
	if (parent == null) { // library marker davegut.kasaCommon, line 64
		logWarn("configure: No Parent Detected.  Configure function ABORTED.  Use Save Preferences instead.") // library marker davegut.kasaCommon, line 65
	} else { // library marker davegut.kasaCommon, line 66
		def confStatus = parent.updateConfigurations() // library marker davegut.kasaCommon, line 67
		logInfo("configure: ${confStatus}") // library marker davegut.kasaCommon, line 68
	} // library marker davegut.kasaCommon, line 69
} // library marker davegut.kasaCommon, line 70

def refresh() { poll() } // library marker davegut.kasaCommon, line 72

def poll() { getSysinfo() } // library marker davegut.kasaCommon, line 74

def setPollInterval(interval = state.pollInterval) { // library marker davegut.kasaCommon, line 76
	if (interval == "default" || interval == "off" || interval == null) { // library marker davegut.kasaCommon, line 77
		interval = "30 minutes" // library marker davegut.kasaCommon, line 78
	} else if (useCloud || altLan || getDataValue("altComms") == "true") { // library marker davegut.kasaCommon, line 79
		if (interval.contains("sec")) { // library marker davegut.kasaCommon, line 80
			interval = "1 minute" // library marker davegut.kasaCommon, line 81
			logWarn("setPollInterval: Device using Cloud or rawSocket.  Poll interval reset to minimum value of 1 minute.") // library marker davegut.kasaCommon, line 82
		} // library marker davegut.kasaCommon, line 83
	} // library marker davegut.kasaCommon, line 84
	state.pollInterval = interval // library marker davegut.kasaCommon, line 85
	def pollInterval = interval.substring(0,2).toInteger() // library marker davegut.kasaCommon, line 86
	if (interval.contains("sec")) { // library marker davegut.kasaCommon, line 87
		def start = Math.round((pollInterval-1) * Math.random()).toInteger() // library marker davegut.kasaCommon, line 88
		schedule("${start}/${pollInterval} * * * * ?", "poll") // library marker davegut.kasaCommon, line 89
		logWarn("setPollInterval: Polling intervals of less than one minute " + // library marker davegut.kasaCommon, line 90
				"can take high resources and may impact hub performance.") // library marker davegut.kasaCommon, line 91
	} else { // library marker davegut.kasaCommon, line 92
		def start = Math.round(59 * Math.random()).toInteger() // library marker davegut.kasaCommon, line 93
		schedule("${start} */${pollInterval} * * * ?", "poll") // library marker davegut.kasaCommon, line 94
	} // library marker davegut.kasaCommon, line 95
	logDebug("setPollInterval: interval = ${interval}.") // library marker davegut.kasaCommon, line 96
	return interval // library marker davegut.kasaCommon, line 97
} // library marker davegut.kasaCommon, line 98

def rebootDevice() { // library marker davegut.kasaCommon, line 100
	device.updateSetting("rebootDev", [type:"bool", value: false]) // library marker davegut.kasaCommon, line 101
	reboot() // library marker davegut.kasaCommon, line 102
	pauseExecution(10000) // library marker davegut.kasaCommon, line 103
	return "REBOOTING DEVICE" // library marker davegut.kasaCommon, line 104
} // library marker davegut.kasaCommon, line 105

def bindUnbind() { // library marker davegut.kasaCommon, line 107
	def message // library marker davegut.kasaCommon, line 108
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 109
		device.updateSetting("bind", [type:"bool", value: true]) // library marker davegut.kasaCommon, line 110
		device.updateSetting("useCloud", [type:"bool", value: true]) // library marker davegut.kasaCommon, line 111
		message = "No deviceIp.  Bind not modified." // library marker davegut.kasaCommon, line 112
	} else if (bind == null ||  getDataValue("feature") == "lightStrip") { // library marker davegut.kasaCommon, line 113
		message = "Getting current bind state" // library marker davegut.kasaCommon, line 114
		getBind() // library marker davegut.kasaCommon, line 115
	} else if (bind == true) { // library marker davegut.kasaCommon, line 116
		if (!parent.kasaToken || parent.userName == null || parent.userPassword == null) { // library marker davegut.kasaCommon, line 117
			message = "Username/pwd not set." // library marker davegut.kasaCommon, line 118
			getBind() // library marker davegut.kasaCommon, line 119
		} else { // library marker davegut.kasaCommon, line 120
			message = "Binding device to the Kasa Cloud." // library marker davegut.kasaCommon, line 121
			setBind(parent.userName, parent.userPassword) // library marker davegut.kasaCommon, line 122
		} // library marker davegut.kasaCommon, line 123
	} else if (bind == false) { // library marker davegut.kasaCommon, line 124
		message = "Unbinding device from the Kasa Cloud." // library marker davegut.kasaCommon, line 125
		setUnbind() // library marker davegut.kasaCommon, line 126
	} // library marker davegut.kasaCommon, line 127
	pauseExecution(5000) // library marker davegut.kasaCommon, line 128
	return message // library marker davegut.kasaCommon, line 129
} // library marker davegut.kasaCommon, line 130

def setBindUnbind(cmdResp) { // library marker davegut.kasaCommon, line 132
	def bindState = true // library marker davegut.kasaCommon, line 133
	if (cmdResp.get_info) { // library marker davegut.kasaCommon, line 134
		if (cmdResp.get_info.binded == 0) { bindState = false } // library marker davegut.kasaCommon, line 135
		logInfo("setBindUnbind: Bind status set to ${bindState}") // library marker davegut.kasaCommon, line 136
		setCommsType(bindState) // library marker davegut.kasaCommon, line 137
	} else if (cmdResp.bind.err_code == 0){ // library marker davegut.kasaCommon, line 138
		getBind() // library marker davegut.kasaCommon, line 139
	} else { // library marker davegut.kasaCommon, line 140
		logWarn("setBindUnbind: Unhandled response: ${cmdResp}") // library marker davegut.kasaCommon, line 141
	} // library marker davegut.kasaCommon, line 142
} // library marker davegut.kasaCommon, line 143

def setCommsType(bindState) { // library marker davegut.kasaCommon, line 145
	def commsType = "LAN" // library marker davegut.kasaCommon, line 146
	def cloudCtrl = false // library marker davegut.kasaCommon, line 147
	if (bindState == false && useCloud == true) { // library marker davegut.kasaCommon, line 148
		logWarn("setCommsType: Can not use cloud.  Device is not bound to Kasa cloud.") // library marker davegut.kasaCommon, line 149
	} else if (bindState == true && useCloud == true && parent.kasaToken) { // library marker davegut.kasaCommon, line 150
		commsType = "CLOUD" // library marker davegut.kasaCommon, line 151
		cloudCtrl = true // library marker davegut.kasaCommon, line 152
	} else if (altLan == true) { // library marker davegut.kasaCommon, line 153
		commsType = "AltLAN" // library marker davegut.kasaCommon, line 154
		state.response = "" // library marker davegut.kasaCommon, line 155
	} // library marker davegut.kasaCommon, line 156
	def commsSettings = [bind: bindState, useCloud: cloudCtrl, commsType: commsType] // library marker davegut.kasaCommon, line 157
	device.updateSetting("bind", [type:"bool", value: bindState]) // library marker davegut.kasaCommon, line 158
	device.updateSetting("useCloud", [type:"bool", value: cloudCtrl]) // library marker davegut.kasaCommon, line 159
	sendEvent(name: "connection", value: "${commsType}") // library marker davegut.kasaCommon, line 160
	logInfo("setCommsType: ${commsSettings}") // library marker davegut.kasaCommon, line 161
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaCommon, line 162
		def coordData = [:] // library marker davegut.kasaCommon, line 163
		coordData << [bind: bindState] // library marker davegut.kasaCommon, line 164
		coordData << [useCloud: cloudCtrl] // library marker davegut.kasaCommon, line 165
		coordData << [connection: commsType] // library marker davegut.kasaCommon, line 166
		coordData << [altLan: altLan] // library marker davegut.kasaCommon, line 167
		parent.coordinate("commsData", coordData, getDataValue("deviceId"), getDataValue("plugNo")) // library marker davegut.kasaCommon, line 168
	} // library marker davegut.kasaCommon, line 169
	pauseExecution(1000) // library marker davegut.kasaCommon, line 170
} // library marker davegut.kasaCommon, line 171

def syncName() { // library marker davegut.kasaCommon, line 173
	def message // library marker davegut.kasaCommon, line 174
	if (nameSync == "Hubitat") { // library marker davegut.kasaCommon, line 175
		message = "Hubitat Label Sync" // library marker davegut.kasaCommon, line 176
		setDeviceAlias(device.getLabel()) // library marker davegut.kasaCommon, line 177
	} else if (nameSync == "device") { // library marker davegut.kasaCommon, line 178
		message = "Device Alias Sync" // library marker davegut.kasaCommon, line 179
	} else { // library marker davegut.kasaCommon, line 180
		message = "Not Syncing" // library marker davegut.kasaCommon, line 181
	} // library marker davegut.kasaCommon, line 182
	return message // library marker davegut.kasaCommon, line 183
} // library marker davegut.kasaCommon, line 184

def updateName(response) { // library marker davegut.kasaCommon, line 186
	device.updateSetting("nameSync",[type:"enum", value:"none"]) // library marker davegut.kasaCommon, line 187
	def name = device.getLabel() // library marker davegut.kasaCommon, line 188
	if (response.alias) { // library marker davegut.kasaCommon, line 189
		name = response.alias // library marker davegut.kasaCommon, line 190
		device.setLabel(name) // library marker davegut.kasaCommon, line 191
		parent.updateAlias(device.deviceNetworkId, name) // library marker davegut.kasaCommon, line 192
	} else if (response.err_code != 0) { // library marker davegut.kasaCommon, line 193
		def msg = "updateName: Name Sync from Hubitat to Device returned an error." // library marker davegut.kasaCommon, line 194
		msg+= "\n\rNote: <b>Some devices do not support syncing name from the hub.</b>\n\r" // library marker davegut.kasaCommon, line 195
		logWarn(msg) // library marker davegut.kasaCommon, line 196
		return // library marker davegut.kasaCommon, line 197
	} // library marker davegut.kasaCommon, line 198
	logInfo("updateName: Hubitat and Kasa device name synchronized to ${name}") // library marker davegut.kasaCommon, line 199
} // library marker davegut.kasaCommon, line 200

def getSysinfo() { // library marker davegut.kasaCommon, line 202
	if (!getDataValue("altComms")) { // library marker davegut.kasaCommon, line 203
		sendCmd("""{"system":{"get_sysinfo":{}}}""") // library marker davegut.kasaCommon, line 204
	} else { // library marker davegut.kasaCommon, line 205
		sendTcpCmd("""{"system":{"get_sysinfo":{}}}""") // library marker davegut.kasaCommon, line 206
	} // library marker davegut.kasaCommon, line 207
} // library marker davegut.kasaCommon, line 208

def bindService() { // library marker davegut.kasaCommon, line 210
	def service = "cnCloud" // library marker davegut.kasaCommon, line 211
	def feature = getDataValue("feature") // library marker davegut.kasaCommon, line 212
	if (feature.contains("Bulb") || feature == "lightStrip") { // library marker davegut.kasaCommon, line 213
		service = "smartlife.iot.common.cloud" // library marker davegut.kasaCommon, line 214
	} // library marker davegut.kasaCommon, line 215
	return service // library marker davegut.kasaCommon, line 216
} // library marker davegut.kasaCommon, line 217

def getBind() { // library marker davegut.kasaCommon, line 219
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 220
		logDebug("getBind: [status: notRun, reason: [deviceIP: CLOUD]]") // library marker davegut.kasaCommon, line 221
	} else { // library marker davegut.kasaCommon, line 222
		sendLanCmd("""{"${bindService()}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 223
	} // library marker davegut.kasaCommon, line 224
} // library marker davegut.kasaCommon, line 225

def setBind(userName, password) { // library marker davegut.kasaCommon, line 227
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 228
		logDebug("setBind: [status: notRun, reason: [deviceIP: CLOUD]]") // library marker davegut.kasaCommon, line 229
	} else { // library marker davegut.kasaCommon, line 230
		sendLanCmd("""{"${bindService()}":{"bind":{"username":"${userName}",""" + // library marker davegut.kasaCommon, line 231
				   """"password":"${password}"}},""" + // library marker davegut.kasaCommon, line 232
				   """"${bindService()}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 233
	} // library marker davegut.kasaCommon, line 234
} // library marker davegut.kasaCommon, line 235

def setUnbind() { // library marker davegut.kasaCommon, line 237
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 238
		logDebug("setUnbind: [status: notRun, reason: [deviceIP: CLOUD]]") // library marker davegut.kasaCommon, line 239
	} else { // library marker davegut.kasaCommon, line 240
		sendLanCmd("""{"${bindService()}":{"unbind":""},""" + // library marker davegut.kasaCommon, line 241
				   """"${bindService()}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 242
	} // library marker davegut.kasaCommon, line 243
} // library marker davegut.kasaCommon, line 244

def sysService() { // library marker davegut.kasaCommon, line 246
	def service = "system" // library marker davegut.kasaCommon, line 247
	def feature = getDataValue("feature") // library marker davegut.kasaCommon, line 248
	if (feature.contains("Bulb") || feature == "lightStrip") { // library marker davegut.kasaCommon, line 249
		service = "smartlife.iot.common.system" // library marker davegut.kasaCommon, line 250
	} // library marker davegut.kasaCommon, line 251
	return service // library marker davegut.kasaCommon, line 252
} // library marker davegut.kasaCommon, line 253

def reboot() { // library marker davegut.kasaCommon, line 255
	sendCmd("""{"${sysService()}":{"reboot":{"delay":1}}}""") // library marker davegut.kasaCommon, line 256
} // library marker davegut.kasaCommon, line 257

def setDeviceAlias(newAlias) { // library marker davegut.kasaCommon, line 259
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaCommon, line 260
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaCommon, line 261
				""""system":{"set_dev_alias":{"alias":"${device.getLabel()}"}}}""") // library marker davegut.kasaCommon, line 262
	} else { // library marker davegut.kasaCommon, line 263
		sendCmd("""{"${sysService()}":{"set_dev_alias":{"alias":"${device.getLabel()}"}}}""") // library marker davegut.kasaCommon, line 264
	} // library marker davegut.kasaCommon, line 265
} // library marker davegut.kasaCommon, line 266

// ~~~~~ end include (1178) davegut.kasaCommon ~~~~~

// ~~~~~ start include (1179) davegut.kasaCommunications ~~~~~
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
	def connection = device.currentValue("connection") // library marker davegut.kasaCommunications, line 21
	if (connection == "LAN") { // library marker davegut.kasaCommunications, line 22
		sendLanCmd(command) // library marker davegut.kasaCommunications, line 23
	} else if (connection == "CLOUD") { // library marker davegut.kasaCommunications, line 24
		sendKasaCmd(command) // library marker davegut.kasaCommunications, line 25
	} else if (connection == "AltLAN") { // library marker davegut.kasaCommunications, line 26
		sendTcpCmd(command) // library marker davegut.kasaCommunications, line 27
	} else { // library marker davegut.kasaCommunications, line 28
		logWarn("sendCmd: attribute connection is not set.") // library marker davegut.kasaCommunications, line 29
	} // library marker davegut.kasaCommunications, line 30
} // library marker davegut.kasaCommunications, line 31

def sendLanCmd(command) { // library marker davegut.kasaCommunications, line 33
	logDebug("sendLanCmd: [ip: ${getDataValue("deviceIP")}, cmd: ${command}]") // library marker davegut.kasaCommunications, line 34
	state.lastCommand = command // library marker davegut.kasaCommunications, line 35
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
		if (clearResp.length() > 1022) { // library marker davegut.kasaCommunications, line 56
			if (clearResp.contains("preferred")) { // library marker davegut.kasaCommunications, line 57
				clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}" // library marker davegut.kasaCommunications, line 58
			} else if (clearResp.contains("child_num")) { // library marker davegut.kasaCommunications, line 59
				clearResp = clearResp.substring(0,clearResp.indexOf("child_num") -2) + "}}}" // library marker davegut.kasaCommunications, line 60
			} else { // library marker davegut.kasaCommunications, line 61
				logWarn("parseUdp: [error: msg too long, data: ${clearResp}]") // library marker davegut.kasaCommunications, line 62
				updateDataValue("altComms", "true") // library marker davegut.kasaCommunications, line 63
				sendTcpCmd(state.lastCommand) // library marker davegut.kasaCommunications, line 64
				return // library marker davegut.kasaCommunications, line 65
			} // library marker davegut.kasaCommunications, line 66
		} // library marker davegut.kasaCommunications, line 67
		def cmdResp = new JsonSlurper().parseText(clearResp) // library marker davegut.kasaCommunications, line 68
		logDebug("parseUdp: ${cmdResp}") // library marker davegut.kasaCommunications, line 69
		distResp(cmdResp) // library marker davegut.kasaCommunications, line 70
		state.lastCommand = "" // library marker davegut.kasaCommunications, line 71
		resetCommsError() // library marker davegut.kasaCommunications, line 72
	} else { // library marker davegut.kasaCommunications, line 73
		logDebug("parseUdp: [error: error, reason: not LAN_TYPE_UDPCLIENT, respType: ${resp.type}]") // library marker davegut.kasaCommunications, line 74
		handleCommsError() // library marker davegut.kasaCommunications, line 75
	} // library marker davegut.kasaCommunications, line 76
} // library marker davegut.kasaCommunications, line 77

def sendKasaCmd(command) { // library marker davegut.kasaCommunications, line 79
	logDebug("sendKasaCmd: ${command}") // library marker davegut.kasaCommunications, line 80
	state.lastCommand = command // library marker davegut.kasaCommunications, line 81
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
		state.lastCommand = "" // library marker davegut.kasaCommunications, line 120
		resetCommsError() // library marker davegut.kasaCommunications, line 121
	} else { // library marker davegut.kasaCommunications, line 122
		handleCommsError() // library marker davegut.kasaCommunications, line 123
		def msg = "cloudParse:\n<b>Error from the Kasa Cloud.</b> Most common cause is " // library marker davegut.kasaCommunications, line 124
		msg += "your Kasa Token has expired.  Run Kasa Login and Token update and try again." // library marker davegut.kasaCommunications, line 125
		msg += "\nAdditional Data: Error = ${resp.data}\n\n" // library marker davegut.kasaCommunications, line 126
		logDebug(msg) // library marker davegut.kasaCommunications, line 127
	} // library marker davegut.kasaCommunications, line 128
} // library marker davegut.kasaCommunications, line 129

def sendTcpCmd(command) { // library marker davegut.kasaCommunications, line 131
	logDebug("sendTcpCmd: ${command}") // library marker davegut.kasaCommunications, line 132
	state.lastCommand = command // library marker davegut.kasaCommunications, line 133
	try { // library marker davegut.kasaCommunications, line 134
		interfaces.rawSocket.connect("${getDataValue("deviceIP")}", // library marker davegut.kasaCommunications, line 135
									 getPort().toInteger(), byteInterface: true) // library marker davegut.kasaCommunications, line 136
	} catch (error) { // library marker davegut.kasaCommunications, line 137
		logDebug("SendTcpCmd: [connectFailed: [ip: ${getDataValue("deviceIP")}, Error = ${error}]]") // library marker davegut.kasaCommunications, line 138
	} // library marker davegut.kasaCommunications, line 139
	state.response = "" // library marker davegut.kasaCommunications, line 140
	interfaces.rawSocket.sendMessage(outputXorTcp(command)) // library marker davegut.kasaCommunications, line 141
} // library marker davegut.kasaCommunications, line 142
def close() { interfaces.rawSocket.close() } // library marker davegut.kasaCommunications, line 143
def socketStatus(message) { // library marker davegut.kasaCommunications, line 144
	if (message != "receive error: Stream closed.") { // library marker davegut.kasaCommunications, line 145
		logDebug("socketStatus: Socket Established") // library marker davegut.kasaCommunications, line 146
	} else { // library marker davegut.kasaCommunications, line 147
		logWarn("socketStatus = ${message}") // library marker davegut.kasaCommunications, line 148
	} // library marker davegut.kasaCommunications, line 149
} // library marker davegut.kasaCommunications, line 150
def parse(message) { // library marker davegut.kasaCommunications, line 151
	if (message != null || message != "") { // library marker davegut.kasaCommunications, line 152
		def response = state.response.concat(message) // library marker davegut.kasaCommunications, line 153
		state.response = response // library marker davegut.kasaCommunications, line 154
		extractTcpResp(response) // library marker davegut.kasaCommunications, line 155
	} // library marker davegut.kasaCommunications, line 156
} // library marker davegut.kasaCommunications, line 157
def extractTcpResp(response) { // library marker davegut.kasaCommunications, line 158
	def cmdResp // library marker davegut.kasaCommunications, line 159
	def clearResp = inputXorTcp(response) // library marker davegut.kasaCommunications, line 160
	if (clearResp.endsWith("}}}")) { // library marker davegut.kasaCommunications, line 161
		interfaces.rawSocket.close() // library marker davegut.kasaCommunications, line 162
		state.lastCommand = "" // library marker davegut.kasaCommunications, line 163
		resetCommsError() // library marker davegut.kasaCommunications, line 164
		try { // library marker davegut.kasaCommunications, line 165
			cmdResp = parseJson(clearResp) // library marker davegut.kasaCommunications, line 166
			distResp(cmdResp) // library marker davegut.kasaCommunications, line 167
		} catch (e) { // library marker davegut.kasaCommunications, line 168
			logWarn("extractTcpResp: [length: ${clearResp.length()}, clearResp: ${clearResp}, comms error: ${e}]") // library marker davegut.kasaCommunications, line 169
			handleCommsError() // library marker davegut.kasaCommunications, line 170
		} // library marker davegut.kasaCommunications, line 171
	} else if (clearResp.length() > 2000) { // library marker davegut.kasaCommunications, line 172
		interfaces.rawSocket.close() // library marker davegut.kasaCommunications, line 173
		handleCommsError() // library marker davegut.kasaCommunications, line 174
	} // library marker davegut.kasaCommunications, line 175
} // library marker davegut.kasaCommunications, line 176

def handleCommsError() { // library marker davegut.kasaCommunications, line 178
	if (state.lastCommand == "") { return } // library marker davegut.kasaCommunications, line 179
	def count = state.errorCount + 1 // library marker davegut.kasaCommunications, line 180
	state.errorCount = count // library marker davegut.kasaCommunications, line 181
	def retry = true // library marker davegut.kasaCommunications, line 182
	def status = [count: count, command: state.lastCommand] // library marker davegut.kasaCommunications, line 183
	if (count == 3) { // library marker davegut.kasaCommunications, line 184
		def attemptFix = parent.fixConnection() // library marker davegut.kasaCommunications, line 185
		status << [attemptFixResult: [attemptFix]] // library marker davegut.kasaCommunications, line 186
	} else if (count >= 4) { // library marker davegut.kasaCommunications, line 187
		retry = false // library marker davegut.kasaCommunications, line 188
	} // library marker davegut.kasaCommunications, line 189
	if (retry == true) { // library marker davegut.kasaCommunications, line 190
		if (state.lastCommand != null) {  // library marker davegut.kasaCommunications, line 191
			if (getDataValue("altComms") == "true") { // library marker davegut.kasaCommunications, line 192
				sendTcpCmd(state.lastCommand) // library marker davegut.kasaCommunications, line 193
			} else { // library marker davegut.kasaCommunications, line 194
				sendCmd(state.lastCommand) // library marker davegut.kasaCommunications, line 195
			} // library marker davegut.kasaCommunications, line 196
		} // library marker davegut.kasaCommunications, line 197
	} else { // library marker davegut.kasaCommunications, line 198
		setCommsError() // library marker davegut.kasaCommunications, line 199
	} // library marker davegut.kasaCommunications, line 200
	status << [retry: retry] // library marker davegut.kasaCommunications, line 201
	if (status.count > 2) { // library marker davegut.kasaCommunications, line 202
		logWarn("handleCommsError: ${status}") // library marker davegut.kasaCommunications, line 203
	} else { // library marker davegut.kasaCommunications, line 204
		logDebug("handleCommsError: ${status}") // library marker davegut.kasaCommunications, line 205
	} // library marker davegut.kasaCommunications, line 206
} // library marker davegut.kasaCommunications, line 207

def setCommsError() { // library marker davegut.kasaCommunications, line 209
	if (device.currentValue("commsError") == "false") { // library marker davegut.kasaCommunications, line 210
		def message = "Can't connect to your device at ${getDataValue("deviceIP")}:${getPort()}. " // library marker davegut.kasaCommunications, line 211
		message += "Refer to troubleshooting guide commsError section." // library marker davegut.kasaCommunications, line 212
		sendEvent(name: "commsError", value: "true") // library marker davegut.kasaCommunications, line 213
		state.COMMS_ERROR = message			 // library marker davegut.kasaCommunications, line 214
		logWarn("setCommsError: <b>${message}</b>") // library marker davegut.kasaCommunications, line 215
		runIn(15, limitPollInterval) // library marker davegut.kasaCommunications, line 216
	} // library marker davegut.kasaCommunications, line 217
} // library marker davegut.kasaCommunications, line 218

def limitPollInterval() { // library marker davegut.kasaCommunications, line 220
	state.nonErrorPollInterval = state.pollInterval // library marker davegut.kasaCommunications, line 221
	setPollInterval("30 minutes") // library marker davegut.kasaCommunications, line 222
} // library marker davegut.kasaCommunications, line 223

def resetCommsError() { // library marker davegut.kasaCommunications, line 225
	state.errorCount = 0 // library marker davegut.kasaCommunications, line 226
	if (device.currentValue("commsError") == "true") { // library marker davegut.kasaCommunications, line 227
		sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommunications, line 228
		setPollInterval(state.nonErrorPollInterval) // library marker davegut.kasaCommunications, line 229
		state.remove("nonErrorPollInterval") // library marker davegut.kasaCommunications, line 230
		state.remove("COMMS_ERROR") // library marker davegut.kasaCommunications, line 231
		logInfo("resetCommsError: Comms error cleared!") // library marker davegut.kasaCommunications, line 232
	} // library marker davegut.kasaCommunications, line 233
} // library marker davegut.kasaCommunications, line 234

private outputXOR(command) { // library marker davegut.kasaCommunications, line 236
	def str = "" // library marker davegut.kasaCommunications, line 237
	def encrCmd = "" // library marker davegut.kasaCommunications, line 238
 	def key = 0xAB // library marker davegut.kasaCommunications, line 239
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 240
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 241
		key = str // library marker davegut.kasaCommunications, line 242
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 243
	} // library marker davegut.kasaCommunications, line 244
   	return encrCmd // library marker davegut.kasaCommunications, line 245
} // library marker davegut.kasaCommunications, line 246

private inputXOR(encrResponse) { // library marker davegut.kasaCommunications, line 248
	String[] strBytes = encrResponse.split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 249
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 250
	def key = 0xAB // library marker davegut.kasaCommunications, line 251
	def nextKey // library marker davegut.kasaCommunications, line 252
	byte[] XORtemp // library marker davegut.kasaCommunications, line 253
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 254
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 255
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 256
		key = nextKey // library marker davegut.kasaCommunications, line 257
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 258
	} // library marker davegut.kasaCommunications, line 259
	return cmdResponse // library marker davegut.kasaCommunications, line 260
} // library marker davegut.kasaCommunications, line 261

private outputXorTcp(command) { // library marker davegut.kasaCommunications, line 263
	def str = "" // library marker davegut.kasaCommunications, line 264
	def encrCmd = "000000" + Integer.toHexString(command.length())  // library marker davegut.kasaCommunications, line 265
 	def key = 0xAB // library marker davegut.kasaCommunications, line 266
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 267
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 268
		key = str // library marker davegut.kasaCommunications, line 269
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 270
	} // library marker davegut.kasaCommunications, line 271
   	return encrCmd // library marker davegut.kasaCommunications, line 272
} // library marker davegut.kasaCommunications, line 273

private inputXorTcp(resp) { // library marker davegut.kasaCommunications, line 275
	String[] strBytes = resp.substring(8).split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 276
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 277
	def key = 0xAB // library marker davegut.kasaCommunications, line 278
	def nextKey // library marker davegut.kasaCommunications, line 279
	byte[] XORtemp // library marker davegut.kasaCommunications, line 280
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 281
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 282
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 283
		key = nextKey // library marker davegut.kasaCommunications, line 284
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 285
	} // library marker davegut.kasaCommunications, line 286
	return cmdResponse // library marker davegut.kasaCommunications, line 287
} // library marker davegut.kasaCommunications, line 288

// ~~~~~ end include (1179) davegut.kasaCommunications ~~~~~

// ~~~~~ start include (1170) davegut.commonLogging ~~~~~
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
	log.trace "${device.displayName}: ${msg}" // library marker davegut.commonLogging, line 27
} // library marker davegut.commonLogging, line 28

def logInfo(msg) {  // library marker davegut.commonLogging, line 30
	if (textEnable || infoLog) { // library marker davegut.commonLogging, line 31
		log.info "${device.displayName}: ${msg}" // library marker davegut.commonLogging, line 32
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
		log.debug "${device.displayName}: ${msg}" // library marker davegut.commonLogging, line 45
	} // library marker davegut.commonLogging, line 46
} // library marker davegut.commonLogging, line 47

def logWarn(msg) { log.warn "${device.displayName}: ${msg}" } // library marker davegut.commonLogging, line 49

// ~~~~~ end include (1170) davegut.commonLogging ~~~~~

// ~~~~~ start include (1181) davegut.kasaLights ~~~~~
library ( // library marker davegut.kasaLights, line 1
	name: "kasaLights", // library marker davegut.kasaLights, line 2
	namespace: "davegut", // library marker davegut.kasaLights, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaLights, line 4
	description: "Kasa Bulb and Light Common Methods", // library marker davegut.kasaLights, line 5
	category: "utilities", // library marker davegut.kasaLights, line 6
	documentationLink: "" // library marker davegut.kasaLights, line 7
) // library marker davegut.kasaLights, line 8

def on() { setLightOnOff(1, transition_Time) } // library marker davegut.kasaLights, line 10

def off() { setLightOnOff(0, transition_Time) } // library marker davegut.kasaLights, line 12

def setLevel(level, transTime = transition_Time) { // library marker davegut.kasaLights, line 14
	setLightLevel(level, transTime) // library marker davegut.kasaLights, line 15
} // library marker davegut.kasaLights, line 16

def startLevelChange(direction) { // library marker davegut.kasaLights, line 18
	unschedule(levelUp) // library marker davegut.kasaLights, line 19
	unschedule(levelDown) // library marker davegut.kasaLights, line 20
	if (direction == "up") { levelUp() } // library marker davegut.kasaLights, line 21
	else { levelDown() } // library marker davegut.kasaLights, line 22
} // library marker davegut.kasaLights, line 23

def stopLevelChange() { // library marker davegut.kasaLights, line 25
	unschedule(levelUp) // library marker davegut.kasaLights, line 26
	unschedule(levelDown) // library marker davegut.kasaLights, line 27
} // library marker davegut.kasaLights, line 28

def levelUp() { // library marker davegut.kasaLights, line 30
	def curLevel = device.currentValue("level").toInteger() // library marker davegut.kasaLights, line 31
	if (curLevel == 100) { return } // library marker davegut.kasaLights, line 32
	def newLevel = curLevel + 4 // library marker davegut.kasaLights, line 33
	if (newLevel > 100) { newLevel = 100 } // library marker davegut.kasaLights, line 34
	setLevel(newLevel, 0) // library marker davegut.kasaLights, line 35
	runIn(1, levelUp) // library marker davegut.kasaLights, line 36
} // library marker davegut.kasaLights, line 37

def levelDown() { // library marker davegut.kasaLights, line 39
	def curLevel = device.currentValue("level").toInteger() // library marker davegut.kasaLights, line 40
	if (curLevel == 0 || device.currentValue("switch") == "off") { return } // library marker davegut.kasaLights, line 41
	def newLevel = curLevel - 4 // library marker davegut.kasaLights, line 42
	if (newLevel < 0) { off() } // library marker davegut.kasaLights, line 43
	else { // library marker davegut.kasaLights, line 44
		setLevel(newLevel, 0) // library marker davegut.kasaLights, line 45
		runIn(1, levelDown) // library marker davegut.kasaLights, line 46
	} // library marker davegut.kasaLights, line 47
} // library marker davegut.kasaLights, line 48

def service() { // library marker davegut.kasaLights, line 50
	def service = "smartlife.iot.smartbulb.lightingservice" // library marker davegut.kasaLights, line 51
	if (getDataValue("feature") == "lightStrip") { service = "smartlife.iot.lightStrip" } // library marker davegut.kasaLights, line 52
	return service // library marker davegut.kasaLights, line 53
} // library marker davegut.kasaLights, line 54

def method() { // library marker davegut.kasaLights, line 56
	def method = "transition_light_state" // library marker davegut.kasaLights, line 57
	if (getDataValue("feature") == "lightStrip") { method = "set_light_state" } // library marker davegut.kasaLights, line 58
	return method // library marker davegut.kasaLights, line 59
} // library marker davegut.kasaLights, line 60

def checkTransTime(transTime) { // library marker davegut.kasaLights, line 62
	if (transTime == null || transTime < 0) { transTime = 0 } // library marker davegut.kasaLights, line 63
	transTime = 1000 * transTime.toInteger() // library marker davegut.kasaLights, line 64
	if (transTime > 8000) { transTime = 8000 } // library marker davegut.kasaLights, line 65
	return transTime // library marker davegut.kasaLights, line 66
} // library marker davegut.kasaLights, line 67

def checkLevel(level) { // library marker davegut.kasaLights, line 69
	if (level == null || level < 0) { // library marker davegut.kasaLights, line 70
		level = device.currentValue("level") // library marker davegut.kasaLights, line 71
		logWarn("checkLevel: Entered level null or negative. Level set to ${level}") // library marker davegut.kasaLights, line 72
	} else if (level > 100) { // library marker davegut.kasaLights, line 73
		level = 100 // library marker davegut.kasaLights, line 74
		logWarn("checkLevel: Entered level > 100.  Level set to ${level}") // library marker davegut.kasaLights, line 75
	} // library marker davegut.kasaLights, line 76
	return level // library marker davegut.kasaLights, line 77
} // library marker davegut.kasaLights, line 78

def setLightOnOff(onOff, transTime = 0) { // library marker davegut.kasaLights, line 80
	transTime = checkTransTime(transTime) // library marker davegut.kasaLights, line 81
	sendCmd("""{"${service()}":{"${method()}":{"on_off":${onOff},""" + // library marker davegut.kasaLights, line 82
			""""transition_period":${transTime}}}}""") // library marker davegut.kasaLights, line 83
} // library marker davegut.kasaLights, line 84

def setLightLevel(level, transTime = 0) { // library marker davegut.kasaLights, line 86
	level = checkLevel(level) // library marker davegut.kasaLights, line 87
	if (level == 0) { // library marker davegut.kasaLights, line 88
		setLightOnOff(0, transTime) // library marker davegut.kasaLights, line 89
	} else { // library marker davegut.kasaLights, line 90
		transTime = checkTransTime(transTime) // library marker davegut.kasaLights, line 91
		sendCmd("""{"${service()}":{"${method()}":{"ignore_default":1,"on_off":1,""" + // library marker davegut.kasaLights, line 92
				""""brightness":${level},"transition_period":${transTime}}}}""") // library marker davegut.kasaLights, line 93
	} // library marker davegut.kasaLights, line 94
} // library marker davegut.kasaLights, line 95

// ~~~~~ end include (1181) davegut.kasaLights ~~~~~

// ~~~~~ start include (1180) davegut.kasaEnergyMonitor ~~~~~
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
		runEvery30Minutes(getEnergyToday) // library marker davegut.kasaEnergyMonitor, line 12
		schedule("10 0 0 * * ?", getEnergyThisMonth) // library marker davegut.kasaEnergyMonitor, line 13
		schedule("15 2 0 1 * ?", getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 14
		state.getEnergy = "This Month" // library marker davegut.kasaEnergyMonitor, line 15
		return "Continuing EM Function" // library marker davegut.kasaEnergyMonitor, line 16
	} else if (emFunction) { // library marker davegut.kasaEnergyMonitor, line 17
		sendEvent(name: "power", value: 0, unit: "W") // library marker davegut.kasaEnergyMonitor, line 18
		sendEvent(name: "energy", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 19
		sendEvent(name: "currMonthTotal", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 20
		sendEvent(name: "currMonthAvg", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 21
		sendEvent(name: "lastMonthTotal", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 22
		sendEvent(name: "lastMonthAvg", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 23
		state.response = "" // library marker davegut.kasaEnergyMonitor, line 24
		runEvery30Minutes(getEnergyToday) // library marker davegut.kasaEnergyMonitor, line 25
		schedule("10 0 0 * * ?", getEnergyThisMonth) // library marker davegut.kasaEnergyMonitor, line 26
		schedule("15 2 0 1 * ?", getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 27
		state.getEnergy = "This Month" // library marker davegut.kasaEnergyMonitor, line 28
		//	Run order / delay is critical for successful operation. // library marker davegut.kasaEnergyMonitor, line 29
		getEnergyThisMonth() // library marker davegut.kasaEnergyMonitor, line 30
		runIn(10, getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 31
		return "Initialized" // library marker davegut.kasaEnergyMonitor, line 32
	} else if (emFunction && device.currentValue("power") != null) { // library marker davegut.kasaEnergyMonitor, line 33
		//	for power != null, EM had to be enabled at one time.  Set values to 0. // library marker davegut.kasaEnergyMonitor, line 34
		sendEvent(name: "power", value: 0) // library marker davegut.kasaEnergyMonitor, line 35
		sendEvent(name: "energy", value: 0) // library marker davegut.kasaEnergyMonitor, line 36
		sendEvent(name: "currMonthTotal", value: 0) // library marker davegut.kasaEnergyMonitor, line 37
		sendEvent(name: "currMonthAvg", value: 0) // library marker davegut.kasaEnergyMonitor, line 38
		sendEvent(name: "lastMonthTotal", value: 0) // library marker davegut.kasaEnergyMonitor, line 39
		sendEvent(name: "lastMonthAvg", value: 0) // library marker davegut.kasaEnergyMonitor, line 40
		state.remove("getEnergy") // library marker davegut.kasaEnergyMonitor, line 41
		return "Disabled" // library marker davegut.kasaEnergyMonitor, line 42
	} else { // library marker davegut.kasaEnergyMonitor, line 43
		return "Not initialized" // library marker davegut.kasaEnergyMonitor, line 44
	} // library marker davegut.kasaEnergyMonitor, line 45
} // library marker davegut.kasaEnergyMonitor, line 46

def getDate() { // library marker davegut.kasaEnergyMonitor, line 48
	def currDate = new Date() // library marker davegut.kasaEnergyMonitor, line 49
	int year = currDate.format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 50
	int month = currDate.format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 51
	int day = currDate.format("d").toInteger() // library marker davegut.kasaEnergyMonitor, line 52
	return [year: year, month: month, day: day] // library marker davegut.kasaEnergyMonitor, line 53
} // library marker davegut.kasaEnergyMonitor, line 54

def distEmeter(emeterResp) { // library marker davegut.kasaEnergyMonitor, line 56
	def date = getDate() // library marker davegut.kasaEnergyMonitor, line 57
	logDebug("distEmeter: ${emeterResp}, ${date}, ${state.getEnergy}") // library marker davegut.kasaEnergyMonitor, line 58
	def lastYear = date.year - 1 // library marker davegut.kasaEnergyMonitor, line 59
	if (emeterResp.get_realtime) { // library marker davegut.kasaEnergyMonitor, line 60
		setPower(emeterResp.get_realtime) // library marker davegut.kasaEnergyMonitor, line 61
	} else if (emeterResp.get_monthstat) { // library marker davegut.kasaEnergyMonitor, line 62
		def monthList = emeterResp.get_monthstat.month_list // library marker davegut.kasaEnergyMonitor, line 63
		if (state.getEnergy == "Today") { // library marker davegut.kasaEnergyMonitor, line 64
			setEnergyToday(monthList, date) // library marker davegut.kasaEnergyMonitor, line 65
		} else if (state.getEnergy == "This Month") { // library marker davegut.kasaEnergyMonitor, line 66
			setThisMonth(monthList, date) // library marker davegut.kasaEnergyMonitor, line 67
		} else if (state.getEnergy == "Last Month") { // library marker davegut.kasaEnergyMonitor, line 68
			setLastMonth(monthList, date) // library marker davegut.kasaEnergyMonitor, line 69
		} else if (monthList == []) { // library marker davegut.kasaEnergyMonitor, line 70
			logDebug("distEmeter: monthList Empty. No data for year.") // library marker davegut.kasaEnergyMonitor, line 71
		} // library marker davegut.kasaEnergyMonitor, line 72
	} else { // library marker davegut.kasaEnergyMonitor, line 73
		logWarn("distEmeter: Unhandled response = ${emeterResp}") // library marker davegut.kasaEnergyMonitor, line 74
	} // library marker davegut.kasaEnergyMonitor, line 75
} // library marker davegut.kasaEnergyMonitor, line 76

def getPower() { // library marker davegut.kasaEnergyMonitor, line 78
	if (emFunction) { // library marker davegut.kasaEnergyMonitor, line 79
		if (device.currentValue("switch") == "on") { // library marker davegut.kasaEnergyMonitor, line 80
			getRealtime() // library marker davegut.kasaEnergyMonitor, line 81
		} else if (device.currentValue("power") != 0) { // library marker davegut.kasaEnergyMonitor, line 82
			sendEvent(name: "power", value: 0, descriptionText: "Watts", unit: "W", type: "digital") // library marker davegut.kasaEnergyMonitor, line 83
		} // library marker davegut.kasaEnergyMonitor, line 84
	} // library marker davegut.kasaEnergyMonitor, line 85
} // library marker davegut.kasaEnergyMonitor, line 86

def setPower(response) { // library marker davegut.kasaEnergyMonitor, line 88
	logDebug("setPower: ${response}") // library marker davegut.kasaEnergyMonitor, line 89
	def power = response.power // library marker davegut.kasaEnergyMonitor, line 90
	if (power == null) { power = response.power_mw / 1000 } // library marker davegut.kasaEnergyMonitor, line 91
	power = (power + 0.5).toInteger() // library marker davegut.kasaEnergyMonitor, line 92
	def curPwr = device.currentValue("power") // library marker davegut.kasaEnergyMonitor, line 93
	def pwrChange = false // library marker davegut.kasaEnergyMonitor, line 94
	if (curPwr != power) { // library marker davegut.kasaEnergyMonitor, line 95
		if (curPwr == null || (curPwr == 0 && power > 0)) { // library marker davegut.kasaEnergyMonitor, line 96
			pwrChange = true // library marker davegut.kasaEnergyMonitor, line 97
		} else { // library marker davegut.kasaEnergyMonitor, line 98
			def changeRatio = Math.abs((power - curPwr) / curPwr) // library marker davegut.kasaEnergyMonitor, line 99
			if (changeRatio > 0.03) { // library marker davegut.kasaEnergyMonitor, line 100
				pwrChange = true // library marker davegut.kasaEnergyMonitor, line 101
			} // library marker davegut.kasaEnergyMonitor, line 102
		} // library marker davegut.kasaEnergyMonitor, line 103
	} // library marker davegut.kasaEnergyMonitor, line 104
	if (pwrChange == true) { // library marker davegut.kasaEnergyMonitor, line 105
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital") // library marker davegut.kasaEnergyMonitor, line 106
	} // library marker davegut.kasaEnergyMonitor, line 107
} // library marker davegut.kasaEnergyMonitor, line 108

def getEnergyToday() { // library marker davegut.kasaEnergyMonitor, line 110
	if (device.currentValue("switch") == "on") { // library marker davegut.kasaEnergyMonitor, line 111
		state.getEnergy = "Today" // library marker davegut.kasaEnergyMonitor, line 112
		def year = getDate().year // library marker davegut.kasaEnergyMonitor, line 113
		logDebug("getEnergyToday: ${year}") // library marker davegut.kasaEnergyMonitor, line 114
		runIn(5, getMonthstat, [data: year]) // library marker davegut.kasaEnergyMonitor, line 115
	} // library marker davegut.kasaEnergyMonitor, line 116
} // library marker davegut.kasaEnergyMonitor, line 117

def setEnergyToday(monthList, date) { // library marker davegut.kasaEnergyMonitor, line 119
	logDebug("setEnergyToday: ${date}, ${monthList}") // library marker davegut.kasaEnergyMonitor, line 120
	def data = monthList.find { it.month == date.month && it.year == date.year} // library marker davegut.kasaEnergyMonitor, line 121
	def status = [:] // library marker davegut.kasaEnergyMonitor, line 122
	def energy = 0 // library marker davegut.kasaEnergyMonitor, line 123
	if (data == null) { // library marker davegut.kasaEnergyMonitor, line 124
		status << [msgError: "Return Data Null"] // library marker davegut.kasaEnergyMonitor, line 125
	} else { // library marker davegut.kasaEnergyMonitor, line 126
		energy = data.energy // library marker davegut.kasaEnergyMonitor, line 127
		if (energy == null) { energy = data.energy_wh/1000 } // library marker davegut.kasaEnergyMonitor, line 128
		energy = Math.round(100*energy)/100 - device.currentValue("currMonthTotal") // library marker davegut.kasaEnergyMonitor, line 129
	} // library marker davegut.kasaEnergyMonitor, line 130
	if (device.currentValue("energy") != energy) { // library marker davegut.kasaEnergyMonitor, line 131
		sendEvent(name: "energy", value: energy, descriptionText: "KiloWatt Hours", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 132
		status << [energy: energy] // library marker davegut.kasaEnergyMonitor, line 133
	} // library marker davegut.kasaEnergyMonitor, line 134
	if (status != [:]) { logInfo("setEnergyToday: ${status}") } // library marker davegut.kasaEnergyMonitor, line 135
	if (!state.getEnergy) { // library marker davegut.kasaEnergyMonitor, line 136
		schedule("10 0 0 * * ?", getEnergyThisMonth) // library marker davegut.kasaEnergyMonitor, line 137
		schedule("15 2 0 1 * ?", getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 138
		state.getEnergy = "This Month" // library marker davegut.kasaEnergyMonitor, line 139
		getEnergyThisMonth() // library marker davegut.kasaEnergyMonitor, line 140
		runIn(10, getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 141
	} // library marker davegut.kasaEnergyMonitor, line 142
} // library marker davegut.kasaEnergyMonitor, line 143

def getEnergyThisMonth() { // library marker davegut.kasaEnergyMonitor, line 145
	state.getEnergy = "This Month" // library marker davegut.kasaEnergyMonitor, line 146
	def year = getDate().year // library marker davegut.kasaEnergyMonitor, line 147
	logDebug("getEnergyThisMonth: ${year}") // library marker davegut.kasaEnergyMonitor, line 148
	runIn(5, getMonthstat, [data: year]) // library marker davegut.kasaEnergyMonitor, line 149
} // library marker davegut.kasaEnergyMonitor, line 150

def setThisMonth(monthList, date) { // library marker davegut.kasaEnergyMonitor, line 152
	logDebug("setThisMonth: ${date} // ${monthList}") // library marker davegut.kasaEnergyMonitor, line 153
	def data = monthList.find { it.month == date.month && it.year == date.year} // library marker davegut.kasaEnergyMonitor, line 154
	def status = [:] // library marker davegut.kasaEnergyMonitor, line 155
	def totEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 156
	def avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 157
	if (data == null) { // library marker davegut.kasaEnergyMonitor, line 158
		status << [msgError: "Return Data Null"] // library marker davegut.kasaEnergyMonitor, line 159
	} else { // library marker davegut.kasaEnergyMonitor, line 160
		status << [msgError: "OK"] // library marker davegut.kasaEnergyMonitor, line 161
		totEnergy = data.energy // library marker davegut.kasaEnergyMonitor, line 162
		if (totEnergy == null) { totEnergy = data.energy_wh/1000 } // library marker davegut.kasaEnergyMonitor, line 163
		if (date.day == 1) { // library marker davegut.kasaEnergyMonitor, line 164
			avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 165
		} else { // library marker davegut.kasaEnergyMonitor, line 166
			avgEnergy = totEnergy /(date.day - 1) // library marker davegut.kasaEnergyMonitor, line 167
		} // library marker davegut.kasaEnergyMonitor, line 168
	} // library marker davegut.kasaEnergyMonitor, line 169
	totEnergy = Math.round(100*totEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 170
	avgEnergy = Math.round(100*avgEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 171
	sendEvent(name: "currMonthTotal", value: totEnergy,  // library marker davegut.kasaEnergyMonitor, line 172
			  descriptionText: "KiloWatt Hours", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 173
	status << [currMonthTotal: totEnergy] // library marker davegut.kasaEnergyMonitor, line 174
	sendEvent(name: "currMonthAvg", value: avgEnergy,  // library marker davegut.kasaEnergyMonitor, line 175
		 	 descriptionText: "KiloWatt Hours per Day", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 176
	status << [currMonthAvg: avgEnergy] // library marker davegut.kasaEnergyMonitor, line 177
	getEnergyToday() // library marker davegut.kasaEnergyMonitor, line 178
	logInfo("setThisMonth: ${status}") // library marker davegut.kasaEnergyMonitor, line 179
} // library marker davegut.kasaEnergyMonitor, line 180

def getEnergyLastMonth() { // library marker davegut.kasaEnergyMonitor, line 182
	state.getEnergy = "Last Month" // library marker davegut.kasaEnergyMonitor, line 183
	def date = getDate() // library marker davegut.kasaEnergyMonitor, line 184
	def year = date.year // library marker davegut.kasaEnergyMonitor, line 185
	if (date.month == 1) { // library marker davegut.kasaEnergyMonitor, line 186
		year = year - 1 // library marker davegut.kasaEnergyMonitor, line 187
	} // library marker davegut.kasaEnergyMonitor, line 188
	logDebug("getEnergyLastMonth: ${year}") // library marker davegut.kasaEnergyMonitor, line 189
	runIn(5, getMonthstat, [data: year]) // library marker davegut.kasaEnergyMonitor, line 190
} // library marker davegut.kasaEnergyMonitor, line 191

def setLastMonth(monthList, date) { // library marker davegut.kasaEnergyMonitor, line 193
	logDebug("setLastMonth: ${date} // ${monthList}") // library marker davegut.kasaEnergyMonitor, line 194
	def lastMonthYear = date.year // library marker davegut.kasaEnergyMonitor, line 195
	def lastMonth = date.month - 1 // library marker davegut.kasaEnergyMonitor, line 196
	if (date.month == 1) { // library marker davegut.kasaEnergyMonitor, line 197
		lastMonthYear -+ 1 // library marker davegut.kasaEnergyMonitor, line 198
		lastMonth = 12 // library marker davegut.kasaEnergyMonitor, line 199
	} // library marker davegut.kasaEnergyMonitor, line 200
	def data = monthList.find { it.month == lastMonth } // library marker davegut.kasaEnergyMonitor, line 201
	def status = [:] // library marker davegut.kasaEnergyMonitor, line 202
	def totEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 203
	def avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 204
	if (data == null) { // library marker davegut.kasaEnergyMonitor, line 205
		status << [msgError: "Return Data Null"] // library marker davegut.kasaEnergyMonitor, line 206
	} else { // library marker davegut.kasaEnergyMonitor, line 207
		status << [msgError: "OK"] // library marker davegut.kasaEnergyMonitor, line 208
		def monthLength // library marker davegut.kasaEnergyMonitor, line 209
		switch(lastMonth) { // library marker davegut.kasaEnergyMonitor, line 210
			case 4: // library marker davegut.kasaEnergyMonitor, line 211
			case 6: // library marker davegut.kasaEnergyMonitor, line 212
			case 9: // library marker davegut.kasaEnergyMonitor, line 213
			case 11: // library marker davegut.kasaEnergyMonitor, line 214
				monthLength = 30 // library marker davegut.kasaEnergyMonitor, line 215
				break // library marker davegut.kasaEnergyMonitor, line 216
			case 2: // library marker davegut.kasaEnergyMonitor, line 217
				monthLength = 28 // library marker davegut.kasaEnergyMonitor, line 218
				if (lastMonthYear == 2020 || lastMonthYear == 2024 || lastMonthYear == 2028) {  // library marker davegut.kasaEnergyMonitor, line 219
					monthLength = 29 // library marker davegut.kasaEnergyMonitor, line 220
				} // library marker davegut.kasaEnergyMonitor, line 221
				break // library marker davegut.kasaEnergyMonitor, line 222
			default: // library marker davegut.kasaEnergyMonitor, line 223
				monthLength = 31 // library marker davegut.kasaEnergyMonitor, line 224
		} // library marker davegut.kasaEnergyMonitor, line 225
		totEnergy = data.energy // library marker davegut.kasaEnergyMonitor, line 226
		if (totEnergy == null) { totEnergy = data.energy_wh/1000 } // library marker davegut.kasaEnergyMonitor, line 227
		avgEnergy = totEnergy / monthLength // library marker davegut.kasaEnergyMonitor, line 228
	} // library marker davegut.kasaEnergyMonitor, line 229
	totEnergy = Math.round(100*totEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 230
	avgEnergy = Math.round(100*avgEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 231
	sendEvent(name: "lastMonthTotal", value: totEnergy,  // library marker davegut.kasaEnergyMonitor, line 232
			  descriptionText: "KiloWatt Hours", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 233
	status << [lastMonthTotal: totEnergy] // library marker davegut.kasaEnergyMonitor, line 234
	sendEvent(name: "lastMonthAvg", value: avgEnergy,  // library marker davegut.kasaEnergyMonitor, line 235
			  descriptionText: "KiloWatt Hoursper Day", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 236
	status << [lastMonthAvg: avgEnergy] // library marker davegut.kasaEnergyMonitor, line 237
	logInfo("setLastMonth: ${status}") // library marker davegut.kasaEnergyMonitor, line 238
} // library marker davegut.kasaEnergyMonitor, line 239

def getRealtime() { // library marker davegut.kasaEnergyMonitor, line 241
	def feature = getDataValue("feature") // library marker davegut.kasaEnergyMonitor, line 242
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaEnergyMonitor, line 243
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 244
				""""emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 245
	} else if (feature.contains("Bulb") || feature == "lightStrip") { // library marker davegut.kasaEnergyMonitor, line 246
		sendCmd("""{"smartlife.iot.common.emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 247
	} else { // library marker davegut.kasaEnergyMonitor, line 248
		sendCmd("""{"emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 249
	} // library marker davegut.kasaEnergyMonitor, line 250
} // library marker davegut.kasaEnergyMonitor, line 251

def getMonthstat(year) { // library marker davegut.kasaEnergyMonitor, line 253
	def feature = getDataValue("feature") // library marker davegut.kasaEnergyMonitor, line 254
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaEnergyMonitor, line 255
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 256
				""""emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 257
	} else if (feature.contains("Bulb") || feature == "lightStrip") { // library marker davegut.kasaEnergyMonitor, line 258
		sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 259
	} else { // library marker davegut.kasaEnergyMonitor, line 260
		sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 261
	} // library marker davegut.kasaEnergyMonitor, line 262
} // library marker davegut.kasaEnergyMonitor, line 263

// ~~~~~ end include (1180) davegut.kasaEnergyMonitor ~~~~~
