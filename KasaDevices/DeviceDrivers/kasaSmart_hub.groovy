/*	TP-Link SMART API / PROTOCOL DRIVER SERIES for plugs, switches, bulbs, hubs and Hub-connected devices.
		Copyright Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
=================================================================================================*/
//def type() { return "tpLink_hub" }
//def gitPath() { return "DaveGut/tpLink_Hubitat/main/Drivers/" }
def type() {return "kasaSmart_hub" }
def gitPath() { return "DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/" }

metadata {
	definition (name: "kasaSmart_hub", namespace: nameSpace(), author: "Dave Gutheinz", 
				importUrl: "https://raw.githubusercontent.com/${gitPath()}${type()}.groovy")
	{
		capability "Switch"		//	Turns on or off.  easier Alexa/google integration
		command "configureAlarm", [
			[name: "Alarm Type", constraints: alarmTypes(), type: "ENUM"],
			[name: "Volume", constraints: ["low", "normal", "high"], type: "ENUM"],
			[name: "Duration", type: "NUMBER"]
		]
		command "playAlarmConfig", [
			[name: "Alarm Type", constraints: alarmTypes(), type: "ENUM"],
			[name: "Volume", constraints: ["low", "normal", "high"], type: "ENUM"],
			[name: "Duration", type: "NUMBER"]
		]
		attribute "alarmConfig", "JSON_OBJECT"
		attribute "commsError", "string"
	}
	preferences {
		input ("childPollInt", "enum", title: "CHILD DEVICE Poll interval (seconds)",
			   options: ["5 sec", "10 sec", "15 sec", "30 sec", "1 min", "5 min"], 
			   defaultValue: "30 sec")
		commonPreferences()
		securityPreferences()
	}
}

def installed() { 
	if (type().contains("kasaSmart")) {
		updateDataValue("deviceIp", getDataValue("deviceIP"))
		removeDataValue("deviceIP")
	}
	runIn(5, updated)
}

def updated() { commonUpdated() }
def delayedUpdates() {
	Map logData = [alarmConfig: getAlarmConfig()]
	logData << [childPoll: setChildPoll()]
	logData << [common: commonDelayedUpdates()]
	logInfo("delayedUpdates: ${logData}")
	runIn(5, installChildDevices)
}

def getAlarmConfig() {
	def cmdResp = syncPassthrough([method: "get_alarm_configure"])
	def alarmData = cmdResp.result
	updateAttr("alarmConfig", alarmData)
	return alarmData
}

def on() {
	logDebug("on: play default alarm configuraiton")
	List requests = [[method: "play_alarm"]]
	requests << [method: "get_alarm_configure"]
	requests << [method: "get_device_info"]
	asyncPassthrough(createMultiCmd(requests), "on", "alarmParse")
}

def off() {
	logDebug("off: stop alarm")
	List requests =[[method: "stop_alarm"]]
	requests << [method: "get_device_info"]
	asyncPassthrough(createMultiCmd(requests), "off", "deviceParse")
}

def configureAlarm(alarmType, volume, duration=30) {
	logDebug("configureAlarm: [alarmType: ${alarmType}, volume: ${volume}, duration: ${duration}]")
	if (duration < 0) { duration = -duration }
	else if (duration == 0) { duration = 30 }
	List requests = [
		[method: "set_alarm_configure",
		 params: [custom: 0,
				  type: "${alarmType}",
				  volume: "${volume}",
				  duration: duration
				 ]]]
	requests << [method: "get_alarm_configure"]
	requests << [method: "get_device_info"]
	asyncPassthrough(createMultiCmd(requests), "configureAlarm", "alarmParse")
}

def playAlarmConfig(alarmType, volume, duration=30) {
	logDebug("playAlarmConfig: [alarmType: ${alarmType}, volume: ${volume}, duration: ${duration}]")
	if (duration < 0) { duration = -duration }
	else if (duration == 0) { duration = 30 }
	List requests = [
		[method: "set_alarm_configure",
		 params: [custom: 0,
				  type: "${alarmType}",
				  volume: "${volume}",
				  duration: duration
				 ]]]
	requests << [method: "get_alarm_configure"]
	requests << [method: "play_alarm"]
	requests << [method: "get_device_info"]
	asyncPassthrough(createMultiCmd(requests), "playAlarmConfig", "alarmParse")
}

def alarmParse(resp, data) {
	def  devData = parseData(resp).cmdResp.result
	logDebug("alarmParse: ${devData}")
	if (devData && devData.responses) {
		def alarmData = devData.responses.find{it.method == "get_alarm_configure"}.result
		updateAttr("alarmConfig", alarmData)
		def devInfo = devData.responses.find{it.method == "get_device_info"}
		if (devInfo) {
			def inAlarm = devInfo.result.in_alarm
			def onOff = "off"
			if (inAlarm == true) {
				onOff = "on"
				runIn(alarmData.duration + 1, refresh)
			}
			updateAttr("switch", onOff)
		}
	} else {
		updateAttr("alarmConfig", devData)
	}
}

def alarmTypes() {
	 return [
		 "Doorbell Ring 1", "Doorbell Ring 2", "Doorbell Ring 3", "Doorbell Ring 4",
		 "Doorbell Ring 5", "Doorbell Ring 6", "Doorbell Ring 7", "Doorbell Ring 8",
		 "Doorbell Ring 9", "Doorbell Ring 10", "Phone Ring", "Alarm 1", "Alarm 2",
		 "Alarm 3", "Alarm 4", "Alarm 5", "Dripping Tap", "Connection 1", "Connection 2"
	 ] 
}

def deviceParse(resp, data=null) {
	def cmdResp = parseData(resp)
	if (cmdResp.status == "OK") {
		def devData = cmdResp.cmdResp.result
		if (devData.responses) {
			devData = devData.responses.find{it.method == "get_device_info"}.result
		}
		logDebug("deviceParse: ${devData}")
		def onOff = "off"
		if (devData.in_alarm == true) { 
			onOff = "on" 
			runIn(30, refresh)
		}
		updateAttr("switch", onOff)
	}
}

//	===== Install Methods Unique to Hub =====
def getDriverId(category, model) {
	def driver = "tapoHub-NewType"
	switch(category) {
		case "subg.trigger.contact-sensor":
			driver = "tpLink_hub_contact"
			break
		case "subg.trigger.motion-sensor":
			driver = "tpLink_hub_motion"
			break
		case "subg.trigger.button":
			if (model == "S200B") {
				driver = "tpLink_hub_button"
			}
			//	Note: Installing only sensor version for now.  Need data to install D version.
			break
		case "subg.trigger.temp-hmdt-sensor":
			driver = "tpLink_hub_tempHumidity"
			break
		case "subg.trigger":
		case "subg.trigger.water-leak-sensor":
		case "subg.plugswitch":
		case "subg.plugswitch.plug":
		case "subg.plugswitch.switch":
		case "subg.trv":
		default:
			driver = "tapoHub-NewType"
	}
	return driver
}







// ~~~~~ start include (1335) davegut.lib_tpLink_common ~~~~~
library ( // library marker davegut.lib_tpLink_common, line 1
	name: "lib_tpLink_common", // library marker davegut.lib_tpLink_common, line 2
	namespace: "davegut", // library marker davegut.lib_tpLink_common, line 3
	author: "Compied by Dave Gutheinz", // library marker davegut.lib_tpLink_common, line 4
	description: "Method common to tpLink device DRIVERS", // library marker davegut.lib_tpLink_common, line 5
	category: "utilities", // library marker davegut.lib_tpLink_common, line 6
	documentationLink: "" // library marker davegut.lib_tpLink_common, line 7
) // library marker davegut.lib_tpLink_common, line 8
def driverVer() {  // library marker davegut.lib_tpLink_common, line 9
	if (type().contains("kasaSmart")) { return "2.3.6"} // library marker davegut.lib_tpLink_common, line 10
	else { return "1.1" } // library marker davegut.lib_tpLink_common, line 11
} // library marker davegut.lib_tpLink_common, line 12

def nameSpace() { return "davegut" } // library marker davegut.lib_tpLink_common, line 14

capability "Refresh" // library marker davegut.lib_tpLink_common, line 16

def commonPreferences() { // library marker davegut.lib_tpLink_common, line 18
	input ("nameSync", "enum", title: "Synchronize Names", // library marker davegut.lib_tpLink_common, line 19
		   options: ["none": "Don't synchronize", // library marker davegut.lib_tpLink_common, line 20
					 "device" : "TP-Link device name master", // library marker davegut.lib_tpLink_common, line 21
					 "Hubitat" : "Hubitat label master"], // library marker davegut.lib_tpLink_common, line 22
		   defaultValue: "none") // library marker davegut.lib_tpLink_common, line 23
	input ("pollInterval", "enum", title: "Poll Interval (< 1 min can cause issues)", // library marker davegut.lib_tpLink_common, line 24
		   options: ["5 sec", "10 sec", "30 sec", "1 min", "10 min"], // library marker davegut.lib_tpLink_common, line 25
		   defaultValue: "10 min") // library marker davegut.lib_tpLink_common, line 26
	input ("developerData", "bool", title: "Get Data for Developer", defaultValue: false) // library marker davegut.lib_tpLink_common, line 27
	input ("rebootDev", "bool", title: "Reboot Device then run Save Preferences", defaultValue: false) // library marker davegut.lib_tpLink_common, line 28
} // library marker davegut.lib_tpLink_common, line 29

def commonUpdated() { // library marker davegut.lib_tpLink_common, line 31
	unschedule() // library marker davegut.lib_tpLink_common, line 32
	Map logData = [:] // library marker davegut.lib_tpLink_common, line 33
	if (rebootDev == true) { // library marker davegut.lib_tpLink_common, line 34
		runInMillis(50, rebootDevice) // library marker davegut.lib_tpLink_common, line 35
		device.updateSetting("rebootDev",[type:"bool", value: false]) // library marker davegut.lib_tpLink_common, line 36
		pauseExecution(5000) // library marker davegut.lib_tpLink_common, line 37
	} // library marker davegut.lib_tpLink_common, line 38
	updateAttr("commsError", false) // library marker davegut.lib_tpLink_common, line 39
	state.errorCount = 0 // library marker davegut.lib_tpLink_common, line 40
	state.lastCmd = "" // library marker davegut.lib_tpLink_common, line 41
	logData << [login: setLoginInterval()] // library marker davegut.lib_tpLink_common, line 42
	logData << setLogsOff() // library marker davegut.lib_tpLink_common, line 43
	logData << deviceLogin() // library marker davegut.lib_tpLink_common, line 44
	pauseExecution(5000) // library marker davegut.lib_tpLink_common, line 45
	if (logData.status == "ERROR") { // library marker davegut.lib_tpLink_common, line 46
		logError("updated: ${logData}") // library marker davegut.lib_tpLink_common, line 47
	} else { // library marker davegut.lib_tpLink_common, line 48
		logInfo("updated: ${logData}") // library marker davegut.lib_tpLink_common, line 49
	} // library marker davegut.lib_tpLink_common, line 50
	runIn(3, delayedUpdates) // library marker davegut.lib_tpLink_common, line 51
	pauseExecution(10000) // library marker davegut.lib_tpLink_common, line 52
} // library marker davegut.lib_tpLink_common, line 53

def commonDelayedUpdates() { // library marker davegut.lib_tpLink_common, line 55
	Map logData = [syncName: syncName()] // library marker davegut.lib_tpLink_common, line 56
	logData << [pollInterval: setPollInterval()] // library marker davegut.lib_tpLink_common, line 57
	if (developerData) { getDeveloperData() } // library marker davegut.lib_tpLink_common, line 58
	runEvery10Minutes(refresh) // library marker davegut.lib_tpLink_common, line 59
	logData << [refresh: "15 mins"] // library marker davegut.lib_tpLink_common, line 60
	refresh() // library marker davegut.lib_tpLink_common, line 61
	return logData // library marker davegut.lib_tpLink_common, line 62
} // library marker davegut.lib_tpLink_common, line 63

def rebootDevice() { // library marker davegut.lib_tpLink_common, line 65
	logWarn("rebootDevice: Rebooting device per preference request") // library marker davegut.lib_tpLink_common, line 66
	def result = syncPassthrough([method: "device_reboot"]) // library marker davegut.lib_tpLink_common, line 67
	logWarn("rebootDevice: ${result}") // library marker davegut.lib_tpLink_common, line 68
} // library marker davegut.lib_tpLink_common, line 69

def setPollInterval() { // library marker davegut.lib_tpLink_common, line 71
	def method = "poll" // library marker davegut.lib_tpLink_common, line 72
	if (getDataValue("capability") == "plug_em") { // library marker davegut.lib_tpLink_common, line 73
		method = "emPoll" // library marker davegut.lib_tpLink_common, line 74
	} // library marker davegut.lib_tpLink_common, line 75
	if (pollInterval.contains("sec")) { // library marker davegut.lib_tpLink_common, line 76
		def interval= pollInterval.replace(" sec", "").toInteger() // library marker davegut.lib_tpLink_common, line 77
		def start = Math.round((interval-1) * Math.random()).toInteger() // library marker davegut.lib_tpLink_common, line 78
		schedule("${start}/${interval} * * * * ?", method) // library marker davegut.lib_tpLink_common, line 79
		logWarn("setPollInterval: Polling intervals of less than one minute " + // library marker davegut.lib_tpLink_common, line 80
				"can take high resources and may impact hub performance.") // library marker davegut.lib_tpLink_common, line 81
	} else { // library marker davegut.lib_tpLink_common, line 82
		def interval= pollInterval.replace(" min", "").toInteger() // library marker davegut.lib_tpLink_common, line 83
		def start = Math.round(59 * Math.random()).toInteger() // library marker davegut.lib_tpLink_common, line 84
		schedule("${start} */${interval} * * * ?", method) // library marker davegut.lib_tpLink_common, line 85
	} // library marker davegut.lib_tpLink_common, line 86
	return pollInterval // library marker davegut.lib_tpLink_common, line 87
} // library marker davegut.lib_tpLink_common, line 88

def setLoginInterval() { // library marker davegut.lib_tpLink_common, line 90
	def startS = Math.round((59) * Math.random()).toInteger() // library marker davegut.lib_tpLink_common, line 91
	def startM = Math.round((59) * Math.random()).toInteger() // library marker davegut.lib_tpLink_common, line 92
	def startH = Math.round((11) * Math.random()).toInteger() // library marker davegut.lib_tpLink_common, line 93
	schedule("${startS} ${startM} ${startH}/12 * * ?", "deviceLogin") // library marker davegut.lib_tpLink_common, line 94
	return "12 hrs" // library marker davegut.lib_tpLink_common, line 95
} // library marker davegut.lib_tpLink_common, line 96

def syncName() { // library marker davegut.lib_tpLink_common, line 98
	def logData = [syncName: nameSync] // library marker davegut.lib_tpLink_common, line 99
	if (nameSync == "none") { // library marker davegut.lib_tpLink_common, line 100
		logData << [status: "Label Not Updated"] // library marker davegut.lib_tpLink_common, line 101
	} else { // library marker davegut.lib_tpLink_common, line 102
		def cmdResp // library marker davegut.lib_tpLink_common, line 103
		String nickname // library marker davegut.lib_tpLink_common, line 104
		if (nameSync == "device") { // library marker davegut.lib_tpLink_common, line 105
			cmdResp = syncPassthrough([method: "get_device_info"]) // library marker davegut.lib_tpLink_common, line 106
			nickname = cmdResp.result.nickname // library marker davegut.lib_tpLink_common, line 107
		} else if (nameSync == "Hubitat") { // library marker davegut.lib_tpLink_common, line 108
			nickname = device.getLabel().bytes.encodeBase64().toString() // library marker davegut.lib_tpLink_common, line 109
			List requests = [[method: "set_device_info",params: [nickname: nickname]]] // library marker davegut.lib_tpLink_common, line 110
			requests << [method: "get_device_info"] // library marker davegut.lib_tpLink_common, line 111
			cmdResp = syncPassthrough(createMultiCmd(requests)) // library marker davegut.lib_tpLink_common, line 112
			cmdResp = cmdResp.result.responses.find { it.method == "get_device_info" } // library marker davegut.lib_tpLink_common, line 113
			nickname = cmdResp.result.nickname // library marker davegut.lib_tpLink_common, line 114
		} // library marker davegut.lib_tpLink_common, line 115
		byte[] plainBytes = nickname.decodeBase64() // library marker davegut.lib_tpLink_common, line 116
		String label = new String(plainBytes) // library marker davegut.lib_tpLink_common, line 117
		device.setLabel(label) // library marker davegut.lib_tpLink_common, line 118
		logData << [nickname: nickname, label: label, status: "Label Updated"] // library marker davegut.lib_tpLink_common, line 119
	} // library marker davegut.lib_tpLink_common, line 120
	device.updateSetting("nameSync",[type: "enum", value: "none"]) // library marker davegut.lib_tpLink_common, line 121
	return logData // library marker davegut.lib_tpLink_common, line 122
} // library marker davegut.lib_tpLink_common, line 123

def getDeveloperData() { // library marker davegut.lib_tpLink_common, line 125
	device.updateSetting("developerData",[type:"bool", value: false]) // library marker davegut.lib_tpLink_common, line 126
	def attrs = listAttributes() // library marker davegut.lib_tpLink_common, line 127
	Date date = new Date() // library marker davegut.lib_tpLink_common, line 128
	Map devData = [ // library marker davegut.lib_tpLink_common, line 129
		currentTime: date.toString(), // library marker davegut.lib_tpLink_common, line 130
		lastLogin: state.lastSuccessfulLogin, // library marker davegut.lib_tpLink_common, line 131
		name: device.getName(), // library marker davegut.lib_tpLink_common, line 132
		status: device.getStatus(), // library marker davegut.lib_tpLink_common, line 133
		aesKey: aesKey, // library marker davegut.lib_tpLink_common, line 134
		cookie: getDataValue("deviceCookie"), // library marker davegut.lib_tpLink_common, line 135
		tokenLen: getDataValue("deviceToken"), // library marker davegut.lib_tpLink_common, line 136
		dataValues: device.getData(), // library marker davegut.lib_tpLink_common, line 137
		attributes: attrs, // library marker davegut.lib_tpLink_common, line 138
		cmdResp: syncPassthrough([method: "get_device_info"]), // library marker davegut.lib_tpLink_common, line 139
		childData: getChildDevData() // library marker davegut.lib_tpLink_common, line 140
	] // library marker davegut.lib_tpLink_common, line 141
	logWarn("DEVELOPER DATA: ${devData}") // library marker davegut.lib_tpLink_common, line 142
} // library marker davegut.lib_tpLink_common, line 143

def getChildDevData(){ // library marker davegut.lib_tpLink_common, line 145
	Map cmdBody = [ // library marker davegut.lib_tpLink_common, line 146
		method: "get_child_device_list" // library marker davegut.lib_tpLink_common, line 147
	] // library marker davegut.lib_tpLink_common, line 148
	def childData = syncPassthrough(cmdBody) // library marker davegut.lib_tpLink_common, line 149
	if (childData.error_code == 0) { // library marker davegut.lib_tpLink_common, line 150
		return childData.result.child_device_list // library marker davegut.lib_tpLink_common, line 151
	} else { // library marker davegut.lib_tpLink_common, line 152
		return "noChildren" // library marker davegut.lib_tpLink_common, line 153
	} // library marker davegut.lib_tpLink_common, line 154
} // library marker davegut.lib_tpLink_common, line 155

def deviceLogin() { // library marker davegut.lib_tpLink_common, line 157
	Map logData = [:] // library marker davegut.lib_tpLink_common, line 158
	def handshakeData = handshake(getDataValue("deviceIP")) // library marker davegut.lib_tpLink_common, line 159
	if (handshakeData.respStatus == "OK") { // library marker davegut.lib_tpLink_common, line 160
		Map credentials = [encUsername: getDataValue("encUsername"),  // library marker davegut.lib_tpLink_common, line 161
						   encPassword: getDataValue("encPassword")] // library marker davegut.lib_tpLink_common, line 162
		def tokenData = loginDevice(handshakeData.cookie, handshakeData.aesKey,  // library marker davegut.lib_tpLink_common, line 163
									credentials, getDataValue("deviceIP")) // library marker davegut.lib_tpLink_common, line 164
		if (tokenData.respStatus == "OK") { // library marker davegut.lib_tpLink_common, line 165
			logData << [rsaKeys: handshakeData.rsaKeys, // library marker davegut.lib_tpLink_common, line 166
						cookie: handshakeData.cookie, // library marker davegut.lib_tpLink_common, line 167
						aesKey: handshakeData.aesKey, // library marker davegut.lib_tpLink_common, line 168
						token: tokenData.token] // library marker davegut.lib_tpLink_common, line 169

			device.updateSetting("aesKey", [type:"password", value: handshakeData.aesKey]) // library marker davegut.lib_tpLink_common, line 171
			updateDataValue("deviceCookie", handshakeData.cookie) // library marker davegut.lib_tpLink_common, line 172
			updateDataValue("deviceToken", tokenData.token) // library marker davegut.lib_tpLink_common, line 173
			logData << [status: "OK"] // library marker davegut.lib_tpLink_common, line 174
		} else { // library marker davegut.lib_tpLink_common, line 175
			logData << [status: "ERROR.",tokenData: tokenData] // library marker davegut.lib_tpLink_common, line 176
		} // library marker davegut.lib_tpLink_common, line 177
	} else { // library marker davegut.lib_tpLink_common, line 178
		logData << [status: "ERROR",handshakeData: handshakeData] // library marker davegut.lib_tpLink_common, line 179
	} // library marker davegut.lib_tpLink_common, line 180
	Map logStatus = [:] // library marker davegut.lib_tpLink_common, line 181
	if (logData.status == "OK") { // library marker davegut.lib_tpLink_common, line 182
		logInfo("deviceLogin: ${logData}") // library marker davegut.lib_tpLink_common, line 183
		logStatus << [logStatus: "SUCCESS"] // library marker davegut.lib_tpLink_common, line 184
	} else { // library marker davegut.lib_tpLink_common, line 185
		logWarn("deviceLogin: ${logData}") // library marker davegut.lib_tpLink_common, line 186
		logStatus << [logStatus: "FAILURE"] // library marker davegut.lib_tpLink_common, line 187
	} // library marker davegut.lib_tpLink_common, line 188
	return logStatus // library marker davegut.lib_tpLink_common, line 189
} // library marker davegut.lib_tpLink_common, line 190

def refresh() { // library marker davegut.lib_tpLink_common, line 192
	logDebug("refresh") // library marker davegut.lib_tpLink_common, line 193
	asyncPassthrough([method: "get_device_info"], "refresh", "deviceParse") // library marker davegut.lib_tpLink_common, line 194
} // library marker davegut.lib_tpLink_common, line 195

def poll() { // library marker davegut.lib_tpLink_common, line 197
	logDebug("poll") // library marker davegut.lib_tpLink_common, line 198
	asyncPassthrough([method: "get_device_running_info"], "poll", "pollParse") // library marker davegut.lib_tpLink_common, line 199
} // library marker davegut.lib_tpLink_common, line 200

def pollParse(resp, data=null) { // library marker davegut.lib_tpLink_common, line 202
	def cmdResp = parseData(resp) // library marker davegut.lib_tpLink_common, line 203
	if (cmdResp.status == "OK") { // library marker davegut.lib_tpLink_common, line 204
		def devData = cmdResp.cmdResp.result // library marker davegut.lib_tpLink_common, line 205
		def onOff = "off" // library marker davegut.lib_tpLink_common, line 206
		if (devData.device_on == true) { onOff ="on" } // library marker davegut.lib_tpLink_common, line 207
		updateAttr("switch", onOff) // library marker davegut.lib_tpLink_common, line 208
	} // library marker davegut.lib_tpLink_common, line 209
} // library marker davegut.lib_tpLink_common, line 210

def emPoll() { // library marker davegut.lib_tpLink_common, line 212
	logDebug("poll") // library marker davegut.lib_tpLink_common, line 213
	List requests = [[method: "get_device_running_info"]] // library marker davegut.lib_tpLink_common, line 214
	requests << [method: "get_energy_usage"] // library marker davegut.lib_tpLink_common, line 215
	asyncPassthrough(createMultiCmd(requests), "emPoll", "emPollParse") // library marker davegut.lib_tpLink_common, line 216
} // library marker davegut.lib_tpLink_common, line 217

def emPollParse(resp, data=null) { // library marker davegut.lib_tpLink_common, line 219
	def cmdResp = parseData(resp) // library marker davegut.lib_tpLink_common, line 220
	if (cmdResp.status == "OK") { // library marker davegut.lib_tpLink_common, line 221
		def devData = cmdResp.cmdResp.result.responses.find{it.method == "get_device_running_info"}.result // library marker davegut.lib_tpLink_common, line 222
		def onOff = "off" // library marker davegut.lib_tpLink_common, line 223
		if (devData.device_on == true) { onOff ="on" } // library marker davegut.lib_tpLink_common, line 224
		updateAttr("switch", onOff) // library marker davegut.lib_tpLink_common, line 225
		def emData = cmdResp.cmdResp.result.responses.find{it.method == "get_energy_usage"} // library marker davegut.lib_tpLink_common, line 226
		if (emData.error_code == 0) { // library marker davegut.lib_tpLink_common, line 227
			emData = emData.result // library marker davegut.lib_tpLink_common, line 228
			updateAttr("power", emData.current_power) // library marker davegut.lib_tpLink_common, line 229
		} // library marker davegut.lib_tpLink_common, line 230
	} // library marker davegut.lib_tpLink_common, line 231
} // library marker davegut.lib_tpLink_common, line 232

def updateAttr(attr, value) { // library marker davegut.lib_tpLink_common, line 234
	if (device.currentValue(attr) != value) { // library marker davegut.lib_tpLink_common, line 235
		sendEvent(name: attr, value: value) // library marker davegut.lib_tpLink_common, line 236
	} // library marker davegut.lib_tpLink_common, line 237
} // library marker davegut.lib_tpLink_common, line 238

// ~~~~~ end include (1335) davegut.lib_tpLink_common ~~~~~

// ~~~~~ start include (1327) davegut.lib_tpLink_comms ~~~~~
library ( // library marker davegut.lib_tpLink_comms, line 1
	name: "lib_tpLink_comms", // library marker davegut.lib_tpLink_comms, line 2
	namespace: "davegut", // library marker davegut.lib_tpLink_comms, line 3
	author: "Dave Gutheinz", // library marker davegut.lib_tpLink_comms, line 4
	description: "Tapo Communications", // library marker davegut.lib_tpLink_comms, line 5
	category: "utilities", // library marker davegut.lib_tpLink_comms, line 6
	documentationLink: "" // library marker davegut.lib_tpLink_comms, line 7
) // library marker davegut.lib_tpLink_comms, line 8
import org.json.JSONObject // library marker davegut.lib_tpLink_comms, line 9
import groovy.json.JsonOutput // library marker davegut.lib_tpLink_comms, line 10
import groovy.json.JsonBuilder // library marker davegut.lib_tpLink_comms, line 11
import groovy.json.JsonSlurper // library marker davegut.lib_tpLink_comms, line 12

def createMultiCmd(requests) { // library marker davegut.lib_tpLink_comms, line 14
	Map cmdBody = [ // library marker davegut.lib_tpLink_comms, line 15
		method: "multipleRequest", // library marker davegut.lib_tpLink_comms, line 16
		params: [requests: requests]] // library marker davegut.lib_tpLink_comms, line 17
	return cmdBody // library marker davegut.lib_tpLink_comms, line 18
} // library marker davegut.lib_tpLink_comms, line 19

def asyncPassthrough(cmdBody, method, action) { // library marker davegut.lib_tpLink_comms, line 21
	if (devIp == null) { devIp = getDataValue("deviceIP") }	//	used for Kasa Compatibility // library marker davegut.lib_tpLink_comms, line 22
	Map cmdData = [cmdBody: cmdBody, method: method, action: action] // library marker davegut.lib_tpLink_comms, line 23
	state.lastCmd = cmdData // library marker davegut.lib_tpLink_comms, line 24
	logDebug("asyncPassthrough: ${cmdData}") // library marker davegut.lib_tpLink_comms, line 25
	def uri = "http://${getDataValue("deviceIP")}/app?token=${getDataValue("deviceToken")}" // library marker davegut.lib_tpLink_comms, line 26
	Map reqBody = createReqBody(cmdBody) // library marker davegut.lib_tpLink_comms, line 27
	asyncPost(uri, reqBody, action, getDataValue("deviceCookie"), method) // library marker davegut.lib_tpLink_comms, line 28
} // library marker davegut.lib_tpLink_comms, line 29

def syncPassthrough(cmdBody) { // library marker davegut.lib_tpLink_comms, line 31
	if (devIp == null) { devIp = getDataValue("deviceIP") }	//	used for Kasa Compatibility // library marker davegut.lib_tpLink_comms, line 32
	Map logData = [cmdBody: cmdBody] // library marker davegut.lib_tpLink_comms, line 33
	def uri = "http://${getDataValue("deviceIP")}/app?token=${getDataValue("deviceToken")}" // library marker davegut.lib_tpLink_comms, line 34
	Map reqBody = createReqBody(cmdBody) // library marker davegut.lib_tpLink_comms, line 35
	def resp = syncPost(uri, reqBody, getDataValue("deviceCookie")) // library marker davegut.lib_tpLink_comms, line 36
	def cmdResp = "ERROR" // library marker davegut.lib_tpLink_comms, line 37
	if (resp.status == "OK") { // library marker davegut.lib_tpLink_comms, line 38
		try { // library marker davegut.lib_tpLink_comms, line 39
			cmdResp = new JsonSlurper().parseText(decrypt(resp.resp.data.result.response)) // library marker davegut.lib_tpLink_comms, line 40
			logData << [status: "OK"] // library marker davegut.lib_tpLink_comms, line 41
		} catch (err) { // library marker davegut.lib_tpLink_comms, line 42
			logData << [status: "cryptoError", error: "Error decrypting response", data: err] // library marker davegut.lib_tpLink_comms, line 43
		} // library marker davegut.lib_tpLink_comms, line 44
	} else { // library marker davegut.lib_tpLink_comms, line 45
		logData << [status: "postJsonError", postJsonData: resp] // library marker davegut.lib_tpLink_comms, line 46
	} // library marker davegut.lib_tpLink_comms, line 47
	if (logData.status == "OK") { // library marker davegut.lib_tpLink_comms, line 48
		logDebug("syncPassthrough: ${logData}") // library marker davegut.lib_tpLink_comms, line 49
	} else { // library marker davegut.lib_tpLink_comms, line 50
		logWarn("syncPassthrough: ${logData}") // library marker davegut.lib_tpLink_comms, line 51
	} // library marker davegut.lib_tpLink_comms, line 52
	return cmdResp // library marker davegut.lib_tpLink_comms, line 53
} // library marker davegut.lib_tpLink_comms, line 54

def createReqBody(cmdBody) { // library marker davegut.lib_tpLink_comms, line 56
	def cmdStr = JsonOutput.toJson(cmdBody).toString() // library marker davegut.lib_tpLink_comms, line 57
	Map reqBody = [method: "securePassthrough", // library marker davegut.lib_tpLink_comms, line 58
				   params: [request: encrypt(cmdStr)]] // library marker davegut.lib_tpLink_comms, line 59
	return reqBody // library marker davegut.lib_tpLink_comms, line 60
} // library marker davegut.lib_tpLink_comms, line 61

//	===== Sync comms for device update ===== // library marker davegut.lib_tpLink_comms, line 63
def syncPost(uri, reqBody, cookie=null) { // library marker davegut.lib_tpLink_comms, line 64
	def reqParams = [ // library marker davegut.lib_tpLink_comms, line 65
		uri: uri, // library marker davegut.lib_tpLink_comms, line 66
		headers: [ // library marker davegut.lib_tpLink_comms, line 67
			Cookie: cookie // library marker davegut.lib_tpLink_comms, line 68
		], // library marker davegut.lib_tpLink_comms, line 69
		body : new JsonBuilder(reqBody).toString() // library marker davegut.lib_tpLink_comms, line 70
	] // library marker davegut.lib_tpLink_comms, line 71
	logDebug("syncPost: [cmdParams: ${reqParams}]") // library marker davegut.lib_tpLink_comms, line 72
	Map respData = [:] // library marker davegut.lib_tpLink_comms, line 73
	try { // library marker davegut.lib_tpLink_comms, line 74
		httpPostJson(reqParams) {resp -> // library marker davegut.lib_tpLink_comms, line 75
			if (resp.status == 200 && resp.data.error_code == 0) { // library marker davegut.lib_tpLink_comms, line 76
				respData << [status: "OK", resp: resp] // library marker davegut.lib_tpLink_comms, line 77
			} else { // library marker davegut.lib_tpLink_comms, line 78
				respData << [status: "lanDataError", respStatus: resp.status,  // library marker davegut.lib_tpLink_comms, line 79
					errorCode: resp.data.error_code] // library marker davegut.lib_tpLink_comms, line 80
			} // library marker davegut.lib_tpLink_comms, line 81
		} // library marker davegut.lib_tpLink_comms, line 82
	} catch (err) { // library marker davegut.lib_tpLink_comms, line 83
		respData << [status: "HTTP Failed", data: err] // library marker davegut.lib_tpLink_comms, line 84
	} // library marker davegut.lib_tpLink_comms, line 85
	return respData // library marker davegut.lib_tpLink_comms, line 86
} // library marker davegut.lib_tpLink_comms, line 87

def asyncPost(uri, reqBody, parseMethod, cookie=null, reqData=null) { // library marker davegut.lib_tpLink_comms, line 89
	Map logData = [:] // library marker davegut.lib_tpLink_comms, line 90
	def reqParams = [ // library marker davegut.lib_tpLink_comms, line 91
		uri: uri, // library marker davegut.lib_tpLink_comms, line 92
		requestContentType: 'application/json', // library marker davegut.lib_tpLink_comms, line 93
		contentType: 'application/json', // library marker davegut.lib_tpLink_comms, line 94
		headers: [ // library marker davegut.lib_tpLink_comms, line 95
			Cookie: cookie // library marker davegut.lib_tpLink_comms, line 96
		], // library marker davegut.lib_tpLink_comms, line 97
		timeout: 4, // library marker davegut.lib_tpLink_comms, line 98
		body : new groovy.json.JsonBuilder(reqBody).toString() // library marker davegut.lib_tpLink_comms, line 99
	] // library marker davegut.lib_tpLink_comms, line 100
	try { // library marker davegut.lib_tpLink_comms, line 101
		asynchttpPost(parseMethod, reqParams, [data: reqData]) // library marker davegut.lib_tpLink_comms, line 102
		logData << [status: "OK"] // library marker davegut.lib_tpLink_comms, line 103
	} catch (e) { // library marker davegut.lib_tpLink_comms, line 104
		logData << [status: e, reqParams: reqParams] // library marker davegut.lib_tpLink_comms, line 105
	} // library marker davegut.lib_tpLink_comms, line 106
	if (logData.status == "OK") { // library marker davegut.lib_tpLink_comms, line 107
		logDebug("asyncPost: ${logData}") // library marker davegut.lib_tpLink_comms, line 108
	} else { // library marker davegut.lib_tpLink_comms, line 109
		logWarn("asyncPost: ${logData}") // library marker davegut.lib_tpLink_comms, line 110
		handleCommsError() // library marker davegut.lib_tpLink_comms, line 111
	} // library marker davegut.lib_tpLink_comms, line 112
} // library marker davegut.lib_tpLink_comms, line 113

def parseData(resp) { // library marker davegut.lib_tpLink_comms, line 115
	def logData = [:] // library marker davegut.lib_tpLink_comms, line 116
	if (resp.status == 200 && resp.json.error_code == 0) { // library marker davegut.lib_tpLink_comms, line 117
		def cmdResp // library marker davegut.lib_tpLink_comms, line 118
		try { // library marker davegut.lib_tpLink_comms, line 119
			cmdResp = new JsonSlurper().parseText(decrypt(resp.json.result.response)) // library marker davegut.lib_tpLink_comms, line 120
			setCommsError(false) // library marker davegut.lib_tpLink_comms, line 121
		} catch (err) { // library marker davegut.lib_tpLink_comms, line 122
			logData << [status: "cryptoError", error: "Error decrypting response", data: err] // library marker davegut.lib_tpLink_comms, line 123
		} // library marker davegut.lib_tpLink_comms, line 124
		if (cmdResp != null && cmdResp.error_code == 0) { // library marker davegut.lib_tpLink_comms, line 125
			logData << [status: "OK", cmdResp: cmdResp] // library marker davegut.lib_tpLink_comms, line 126
		} else { // library marker davegut.lib_tpLink_comms, line 127
			logData << [status: "deviceDataError", cmdResp: cmdResp] // library marker davegut.lib_tpLink_comms, line 128
		} // library marker davegut.lib_tpLink_comms, line 129
	} else { // library marker davegut.lib_tpLink_comms, line 130
		logData << [status: "lanDataError"] // library marker davegut.lib_tpLink_comms, line 131
	} // library marker davegut.lib_tpLink_comms, line 132
	if (logData.status == "OK") { // library marker davegut.lib_tpLink_comms, line 133
		logDebug("parseData: ${logData}") // library marker davegut.lib_tpLink_comms, line 134
	} else { // library marker davegut.lib_tpLink_comms, line 135
		logWarn("parseData: ${logData}") // library marker davegut.lib_tpLink_comms, line 136
		handleCommsError() // library marker davegut.lib_tpLink_comms, line 137
	} // library marker davegut.lib_tpLink_comms, line 138
	return logData // library marker davegut.lib_tpLink_comms, line 139
} // library marker davegut.lib_tpLink_comms, line 140

def handleCommsError() { // library marker davegut.lib_tpLink_comms, line 142
	Map logData = [:] // library marker davegut.lib_tpLink_comms, line 143
	if (state.lastCommand != "") { // library marker davegut.lib_tpLink_comms, line 144
		def count = state.errorCount + 1 // library marker davegut.lib_tpLink_comms, line 145
		state.errorCount = count // library marker davegut.lib_tpLink_comms, line 146
		def cmdData = new JSONObject(state.lastCmd) // library marker davegut.lib_tpLink_comms, line 147
		def cmdBody = parseJson(cmdData.cmdBody.toString()) // library marker davegut.lib_tpLink_comms, line 148
		logData << [count: count, command: cmdData] // library marker davegut.lib_tpLink_comms, line 149
		switch (count) { // library marker davegut.lib_tpLink_comms, line 150
			case 1: // library marker davegut.lib_tpLink_comms, line 151
				asyncPassthrough(cmdBody, cmdData.method, cmdData.action) // library marker davegut.lib_tpLink_comms, line 152
				logData << [status: "commandRetry"] // library marker davegut.lib_tpLink_comms, line 153
				logDebug("handleCommsError: ${logData}") // library marker davegut.lib_tpLink_comms, line 154
				break // library marker davegut.lib_tpLink_comms, line 155
			case 2: // library marker davegut.lib_tpLink_comms, line 156
				logData << [deviceLogin: deviceLogin()] // library marker davegut.lib_tpLink_comms, line 157
				Map data = [cmdBody: cmdBody, method: cmdData.method, action:cmdData.action] // library marker davegut.lib_tpLink_comms, line 158
				runIn(2, delayedPassThrough, [data:data]) // library marker davegut.lib_tpLink_comms, line 159
				logData << [status: "newLogin and commandRetry"] // library marker davegut.lib_tpLink_comms, line 160
				logWarn("handleCommsError: ${logData}") // library marker davegut.lib_tpLink_comms, line 161
				break // library marker davegut.lib_tpLink_comms, line 162
			case 3: // library marker davegut.lib_tpLink_comms, line 163
				logData << [setCommsError: setCommsError(true), status: "retriesDisabled"] // library marker davegut.lib_tpLink_comms, line 164
				logError("handleCommsError: ${logData}") // library marker davegut.lib_tpLink_comms, line 165
				break // library marker davegut.lib_tpLink_comms, line 166
			default: // library marker davegut.lib_tpLink_comms, line 167
				break // library marker davegut.lib_tpLink_comms, line 168
		} // library marker davegut.lib_tpLink_comms, line 169
	} // library marker davegut.lib_tpLink_comms, line 170
} // library marker davegut.lib_tpLink_comms, line 171

def delayedPassThrough(data) { // library marker davegut.lib_tpLink_comms, line 173
	asyncPassthrough(data.cmdBody, data.method, data.action) // library marker davegut.lib_tpLink_comms, line 174
} // library marker davegut.lib_tpLink_comms, line 175

def setCommsError(status) { // library marker davegut.lib_tpLink_comms, line 177
	if (!status) { // library marker davegut.lib_tpLink_comms, line 178
		updateAttr("commsError", false) // library marker davegut.lib_tpLink_comms, line 179
		state.errorCount = 0 // library marker davegut.lib_tpLink_comms, line 180
	} else { // library marker davegut.lib_tpLink_comms, line 181
		updateAttr("commsError", true) // library marker davegut.lib_tpLink_comms, line 182
		return "commsErrorSet" // library marker davegut.lib_tpLink_comms, line 183
	} // library marker davegut.lib_tpLink_comms, line 184
} // library marker davegut.lib_tpLink_comms, line 185

// ~~~~~ end include (1327) davegut.lib_tpLink_comms ~~~~~

// ~~~~~ start include (1337) davegut.lib_tpLink_security ~~~~~
library ( // library marker davegut.lib_tpLink_security, line 1
	name: "lib_tpLink_security", // library marker davegut.lib_tpLink_security, line 2
	namespace: "davegut", // library marker davegut.lib_tpLink_security, line 3
	author: "Dave Gutheinz", // library marker davegut.lib_tpLink_security, line 4
	description: "tpLink RSA and AES security measures", // library marker davegut.lib_tpLink_security, line 5
	category: "utilities", // library marker davegut.lib_tpLink_security, line 6
	documentationLink: "" // library marker davegut.lib_tpLink_security, line 7
) // library marker davegut.lib_tpLink_security, line 8
import groovy.json.JsonSlurper // library marker davegut.lib_tpLink_security, line 9
import java.security.spec.PKCS8EncodedKeySpec // library marker davegut.lib_tpLink_security, line 10
import javax.crypto.spec.SecretKeySpec // library marker davegut.lib_tpLink_security, line 11
import javax.crypto.spec.IvParameterSpec // library marker davegut.lib_tpLink_security, line 12
import javax.crypto.Cipher // library marker davegut.lib_tpLink_security, line 13
import java.security.KeyFactory // library marker davegut.lib_tpLink_security, line 14

def securityPreferences() { // library marker davegut.lib_tpLink_security, line 16
	input ("aesKey", "password", title: "Storage for the AES Key") // library marker davegut.lib_tpLink_security, line 17
} // library marker davegut.lib_tpLink_security, line 18

//	===== Device Login Core ===== // library marker davegut.lib_tpLink_security, line 20
def handshake(devIp) { // library marker davegut.lib_tpLink_security, line 21
	def rsaKeys = getRsaKeys() // library marker davegut.lib_tpLink_security, line 22
	Map handshakeData = [method: "handshakeData", rsaKeys: rsaKeys.keyNo] // library marker davegut.lib_tpLink_security, line 23
	def pubPem = "-----BEGIN PUBLIC KEY-----\n${rsaKeys.public}-----END PUBLIC KEY-----\n" // library marker davegut.lib_tpLink_security, line 24
	Map cmdBody = [ method: "handshake", params: [ key: pubPem]] // library marker davegut.lib_tpLink_security, line 25
	def uri = "http://${devIp}/app" // library marker davegut.lib_tpLink_security, line 26
	def respData = syncPost(uri, cmdBody) // library marker davegut.lib_tpLink_security, line 27
	if (respData.status == "OK") { // library marker davegut.lib_tpLink_security, line 28
		String deviceKey = respData.resp.data.result.key // library marker davegut.lib_tpLink_security, line 29
		try { // library marker davegut.lib_tpLink_security, line 30
			def cookieHeader = respData.resp.headers["set-cookie"].toString() // library marker davegut.lib_tpLink_security, line 31
			def cookie = cookieHeader.substring(cookieHeader.indexOf(":") +1, cookieHeader.indexOf(";")) // library marker davegut.lib_tpLink_security, line 32
			handshakeData << [cookie: cookie] // library marker davegut.lib_tpLink_security, line 33
		} catch (err) { // library marker davegut.lib_tpLink_security, line 34
			handshakeData << [respStatus: "FAILED", check: "respData.headers", error: err] // library marker davegut.lib_tpLink_security, line 35
		} // library marker davegut.lib_tpLink_security, line 36
		def aesArray = readDeviceKey(deviceKey, rsaKeys.private) // library marker davegut.lib_tpLink_security, line 37
		handshakeData << [aesKey: aesArray] // library marker davegut.lib_tpLink_security, line 38
		if (aesArray == "ERROR") { // library marker davegut.lib_tpLink_security, line 39
			handshakeData << [respStatus: "FAILED", check: "privateKey"] // library marker davegut.lib_tpLink_security, line 40
		} else { // library marker davegut.lib_tpLink_security, line 41
			handshakeData << [respStatus: "OK"] // library marker davegut.lib_tpLink_security, line 42
		} // library marker davegut.lib_tpLink_security, line 43
	} else { // library marker davegut.lib_tpLink_security, line 44
		handshakeData << [respStatus: "FAILED", check: "pubPem. devIp", respData: respData] // library marker davegut.lib_tpLink_security, line 45
	} // library marker davegut.lib_tpLink_security, line 46
	if (handshakeData.respStatus == "OK") { // library marker davegut.lib_tpLink_security, line 47
		logDebug("handshake: ${handshakeData}") // library marker davegut.lib_tpLink_security, line 48
	} else { // library marker davegut.lib_tpLink_security, line 49
		logWarn("handshake: ${handshakeData}") // library marker davegut.lib_tpLink_security, line 50
	} // library marker davegut.lib_tpLink_security, line 51
	return handshakeData // library marker davegut.lib_tpLink_security, line 52
} // library marker davegut.lib_tpLink_security, line 53

def readDeviceKey(deviceKey, privateKey) { // library marker davegut.lib_tpLink_security, line 55
	def response = "ERROR" // library marker davegut.lib_tpLink_security, line 56
	def logData = [:] // library marker davegut.lib_tpLink_security, line 57
	try { // library marker davegut.lib_tpLink_security, line 58
		byte[] privateKeyBytes = privateKey.decodeBase64() // library marker davegut.lib_tpLink_security, line 59
		byte[] deviceKeyBytes = deviceKey.getBytes("UTF-8").decodeBase64() // library marker davegut.lib_tpLink_security, line 60
    	Cipher instance = Cipher.getInstance("RSA/ECB/PKCS1Padding") // library marker davegut.lib_tpLink_security, line 61
		instance.init(2, KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes))) // library marker davegut.lib_tpLink_security, line 62
		byte[] cryptoArray = instance.doFinal(deviceKeyBytes) // library marker davegut.lib_tpLink_security, line 63
		response = cryptoArray // library marker davegut.lib_tpLink_security, line 64
		logData << [cryptoArray: "REDACTED for logs", status: "OK"] // library marker davegut.lib_tpLink_security, line 65
		logDebug("readDeviceKey: ${logData}") // library marker davegut.lib_tpLink_security, line 66
	} catch (err) { // library marker davegut.lib_tpLink_security, line 67
		logData << [status: "READ ERROR", data: err] // library marker davegut.lib_tpLink_security, line 68
		logWarn("readDeviceKey: ${logData}") // library marker davegut.lib_tpLink_security, line 69
	} // library marker davegut.lib_tpLink_security, line 70
	return response // library marker davegut.lib_tpLink_security, line 71
} // library marker davegut.lib_tpLink_security, line 72

def loginDevice(cookie, cryptoArray, credentials, devIp) { // library marker davegut.lib_tpLink_security, line 74
	Map tokenData = [method: "loginDevice"] // library marker davegut.lib_tpLink_security, line 75
	def uri = "http://${devIp}/app" // library marker davegut.lib_tpLink_security, line 76
	Map cmdBody = [method: "login_device", // library marker davegut.lib_tpLink_security, line 77
				   params: [password: credentials.encPassword, // library marker davegut.lib_tpLink_security, line 78
							username: credentials.encUsername], // library marker davegut.lib_tpLink_security, line 79
				   requestTimeMils: 0] // library marker davegut.lib_tpLink_security, line 80
	def cmdStr = JsonOutput.toJson(cmdBody).toString() // library marker davegut.lib_tpLink_security, line 81
	Map reqBody = [method: "securePassthrough", params: [request: encrypt(cmdStr, cryptoArray)]] // library marker davegut.lib_tpLink_security, line 82
	def respData = syncPost(uri, reqBody, cookie) // library marker davegut.lib_tpLink_security, line 83
	if (respData.status == "OK") { // library marker davegut.lib_tpLink_security, line 84
		if (respData.resp.data.error_code == 0) { // library marker davegut.lib_tpLink_security, line 85
			try { // library marker davegut.lib_tpLink_security, line 86
				def cmdResp = decrypt(respData.resp.data.result.response, cryptoArray) // library marker davegut.lib_tpLink_security, line 87
				cmdResp = new JsonSlurper().parseText(cmdResp) // library marker davegut.lib_tpLink_security, line 88
				if (cmdResp.error_code == 0) { // library marker davegut.lib_tpLink_security, line 89
					tokenData << [respStatus: "OK", token: cmdResp.result.token] // library marker davegut.lib_tpLink_security, line 90
				} else { // library marker davegut.lib_tpLink_security, line 91
					tokenData << [respStatus: "Error from device",  // library marker davegut.lib_tpLink_security, line 92
								  check: "cryptoArray, credentials", data: cmdResp] // library marker davegut.lib_tpLink_security, line 93
				} // library marker davegut.lib_tpLink_security, line 94
			} catch (err) { // library marker davegut.lib_tpLink_security, line 95
				tokenData << [respStatus: "Error parsing", error: err] // library marker davegut.lib_tpLink_security, line 96
			} // library marker davegut.lib_tpLink_security, line 97
		} else { // library marker davegut.lib_tpLink_security, line 98
			tokenData << [respStatus: "Error in respData.data", data: respData.data] // library marker davegut.lib_tpLink_security, line 99
		} // library marker davegut.lib_tpLink_security, line 100
	} else { // library marker davegut.lib_tpLink_security, line 101
		tokenData << [respStatus: "Error in respData", data: respData] // library marker davegut.lib_tpLink_security, line 102
	} // library marker davegut.lib_tpLink_security, line 103
	if (tokenData.respStatus == "OK") { // library marker davegut.lib_tpLink_security, line 104
		logDebug("handshake: ${tokenData}") // library marker davegut.lib_tpLink_security, line 105
	} else { // library marker davegut.lib_tpLink_security, line 106
		logWarn("handshake: ${tokenData}") // library marker davegut.lib_tpLink_security, line 107
	} // library marker davegut.lib_tpLink_security, line 108
	return tokenData // library marker davegut.lib_tpLink_security, line 109
} // library marker davegut.lib_tpLink_security, line 110

//	===== AES Methods ===== // library marker davegut.lib_tpLink_security, line 112
//def encrypt(plainText, keyData) { // library marker davegut.lib_tpLink_security, line 113
def encrypt(plainText, keyData = null) { // library marker davegut.lib_tpLink_security, line 114
	if (keyData == null) { // library marker davegut.lib_tpLink_security, line 115
		keyData = new JsonSlurper().parseText(aesKey) // library marker davegut.lib_tpLink_security, line 116
	} // library marker davegut.lib_tpLink_security, line 117
	byte[] keyenc = keyData[0..15] // library marker davegut.lib_tpLink_security, line 118
	byte[] ivenc = keyData[16..31] // library marker davegut.lib_tpLink_security, line 119

	def cipher = Cipher.getInstance("AES/CBC/PKCS5Padding") // library marker davegut.lib_tpLink_security, line 121
	SecretKeySpec key = new SecretKeySpec(keyenc, "AES") // library marker davegut.lib_tpLink_security, line 122
	IvParameterSpec iv = new IvParameterSpec(ivenc) // library marker davegut.lib_tpLink_security, line 123
	cipher.init(Cipher.ENCRYPT_MODE, key, iv) // library marker davegut.lib_tpLink_security, line 124
	String result = cipher.doFinal(plainText.getBytes("UTF-8")).encodeBase64().toString() // library marker davegut.lib_tpLink_security, line 125
	return result.replace("\r\n","") // library marker davegut.lib_tpLink_security, line 126
} // library marker davegut.lib_tpLink_security, line 127

def decrypt(cypherText, keyData = null) { // library marker davegut.lib_tpLink_security, line 129
	if (keyData == null) { // library marker davegut.lib_tpLink_security, line 130
		keyData = new JsonSlurper().parseText(aesKey) // library marker davegut.lib_tpLink_security, line 131
	} // library marker davegut.lib_tpLink_security, line 132
	byte[] keyenc = keyData[0..15] // library marker davegut.lib_tpLink_security, line 133
	byte[] ivenc = keyData[16..31] // library marker davegut.lib_tpLink_security, line 134

    byte[] decodedBytes = cypherText.decodeBase64() // library marker davegut.lib_tpLink_security, line 136
    def cipher = Cipher.getInstance("AES/CBC/PKCS5Padding") // library marker davegut.lib_tpLink_security, line 137
    SecretKeySpec key = new SecretKeySpec(keyenc, "AES") // library marker davegut.lib_tpLink_security, line 138
    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(ivenc)) // library marker davegut.lib_tpLink_security, line 139
	String result = new String(cipher.doFinal(decodedBytes), "UTF-8") // library marker davegut.lib_tpLink_security, line 140
	return result // library marker davegut.lib_tpLink_security, line 141
} // library marker davegut.lib_tpLink_security, line 142

//	===== RSA Key Methods ===== // library marker davegut.lib_tpLink_security, line 144
def getRsaKeys() { // library marker davegut.lib_tpLink_security, line 145
	def keyNo = Math.round(5 * Math.random()).toInteger() // library marker davegut.lib_tpLink_security, line 146
	def keyData = keyData() // library marker davegut.lib_tpLink_security, line 147
	def RSAKeys = keyData.find { it.keyNo == keyNo } // library marker davegut.lib_tpLink_security, line 148
	return RSAKeys // library marker davegut.lib_tpLink_security, line 149
} // library marker davegut.lib_tpLink_security, line 150

def keyData() { // library marker davegut.lib_tpLink_security, line 152
/*	User Note.  You can update these keys at you will using the site: // library marker davegut.lib_tpLink_security, line 153
		https://www.devglan.com/online-tools/rsa-encryption-decryption // library marker davegut.lib_tpLink_security, line 154
	with an RSA Key Size: 1024 bit // library marker davegut.lib_tpLink_security, line 155
	This is at your risk.*/ // library marker davegut.lib_tpLink_security, line 156
	return [ // library marker davegut.lib_tpLink_security, line 157
		[ // library marker davegut.lib_tpLink_security, line 158
			keyNo: 0, // library marker davegut.lib_tpLink_security, line 159
			public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDGr/mHBK8aqx7UAS+g+TuAvE3J2DdwsqRn9MmAkjPGNon1ZlwM6nLQHfJHebdohyVqkNWaCECGXnftnlC8CM2c/RujvCrStRA0lVD+jixO9QJ9PcYTa07Z1FuEze7Q5OIa6pEoPxomrjxzVlUWLDXt901qCdn3/zRZpBdpXzVZtQIDAQAB", // library marker davegut.lib_tpLink_security, line 160
			private: "MIICeAIBADANBgkqhkiG9w0BAQEFAASCAmIwggJeAgEAAoGBAMav+YcErxqrHtQBL6D5O4C8TcnYN3CypGf0yYCSM8Y2ifVmXAzqctAd8kd5t2iHJWqQ1ZoIQIZed+2eULwIzZz9G6O8KtK1EDSVUP6OLE71An09xhNrTtnUW4TN7tDk4hrqkSg/GiauPHNWVRYsNe33TWoJ2ff/NFmkF2lfNVm1AgMBAAECgYEAocxCHmKBGe2KAEkq+SKdAxvVGO77TsobOhDMWug0Q1C8jduaUGZHsxT/7JbA9d1AagSh/XqE2Sdq8FUBF+7vSFzozBHyGkrX1iKURpQFEQM2j9JgUCucEavnxvCqDYpscyNRAgqz9jdh+BjEMcKAG7o68bOw41ZC+JyYR41xSe0CQQD1os71NcZiMVqYcBud6fTYFHZz3HBNcbzOk+RpIHyi8aF3zIqPKIAh2pO4s7vJgrMZTc2wkIe0ZnUrm0oaC//jAkEAzxIPW1mWd3+KE3gpgyX0cFkZsDmlIbWojUIbyz8NgeUglr+BczARG4ITrTV4fxkGwNI4EZxBT8vXDSIXJ8NDhwJBAIiKndx0rfg7Uw7VkqRvPqk2hrnU2aBTDw8N6rP9WQsCoi0DyCnX65Hl/KN5VXOocYIpW6NAVA8VvSAmTES6Ut0CQQCX20jD13mPfUsHaDIZafZPhiheoofFpvFLVtYHQeBoCF7T7vHCRdfl8oj3l6UcoH/hXMmdsJf9KyI1EXElyf91AkAvLfmAS2UvUnhX4qyFioitjxwWawSnf+CewN8LDbH7m5JVXJEh3hqp+aLHg1EaW4wJtkoKLCF+DeVIgbSvOLJw" // library marker davegut.lib_tpLink_security, line 161
		],[ // library marker davegut.lib_tpLink_security, line 162
			keyNo: 1, // library marker davegut.lib_tpLink_security, line 163
			public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCshy+qBKbJNefcyJUZ/3i+3KyLji6XaWEWvebUCC2r9/0jE6hc89AufO41a13E3gJ2es732vaxwZ1BZKLy468NnL+tg6vlQXaPkDcdunQwjxbTLNL/yzDZs9HRju2lJnupcksdJWBZmjtztMWQkzBrQVeSKzSTrKYK0s24EEXmtQIDAQAB", // library marker davegut.lib_tpLink_security, line 164
			private: "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAKyHL6oEpsk159zIlRn/eL7crIuOLpdpYRa95tQILav3/SMTqFzz0C587jVrXcTeAnZ6zvfa9rHBnUFkovLjrw2cv62Dq+VBdo+QNx26dDCPFtMs0v/LMNmz0dGO7aUme6lySx0lYFmaO3O0xZCTMGtBV5IrNJOspgrSzbgQRea1AgMBAAECgYBSeiX9H1AkbJK1Z2ZwEUNF6vTJmmUHmScC2jHZNzeuOFVZSXJ5TU0+jBbMjtE65e9DeJ4suw6oF6j3tAZ6GwJ5tHoIy+qHRV6AjA8GEXjhSwwVCyP8jXYZ7UZyHzjLQAK+L0PvwJY1lAtns/Xmk5GH+zpNnhEmKSZAw23f7wpj2QJBANVPQGYT7TsMTDEEl2jq/ZgOX5Djf2VnKpPZYZGsUmg1hMwcpN/4XQ7XOaclR5TO/CJBJl3UCUEVjdrR1zdD8g8CQQDPDoa5Y5UfhLz4Ja2/gs2UKwO4fkTqqR6Ad8fQlaUZ55HINHWFd8FeERBFgNJzszrzd9BBJ7NnZM5nf2OPqU77AkBLuQuScSZ5HL97czbQvwLxVMDmLWyPMdVykOvLC9JhPgZ7cvuwqnlWiF7mEBzeHbBx9JDLJDd4zE8ETBPLgapPAkAHhCR52FaSdVQSwfNjr1DdHw6chODlj8wOp8p2FOiQXyqYlObrOGSpkH8BtuJs1sW+DsxdgR5vE2a2tRYdIe0/AkEAoQ5MzLcETQrmabdVCyB9pQAiHe4yY9e1w7cimsLJOrH7LMM0hqvBqFOIbSPrZyTp7Ie8awn4nTKoZQtvBfwzHw==" // library marker davegut.lib_tpLink_security, line 165
		],[ // library marker davegut.lib_tpLink_security, line 166
			keyNo: 2, // library marker davegut.lib_tpLink_security, line 167
			public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCBeqRy4zAOs63Sc5yc0DtlFXG1stmdD6sEfUiGjlsy0S8aS8X+Qcjcu5AK3uBBrkVNIa8djXht1bd+pUof5/txzWIMJw9SNtNYqzSdeO7cCtRLzuQnQWP7Am64OBvYkXn2sUqoaqDE50LbSQWbuvZw0Vi9QihfBYGQdlrqjCPUsQIDAQAB", // library marker davegut.lib_tpLink_security, line 168
			private: "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAIF6pHLjMA6zrdJznJzQO2UVcbWy2Z0PqwR9SIaOWzLRLxpLxf5ByNy7kAre4EGuRU0hrx2NeG3Vt36lSh/n+3HNYgwnD1I201irNJ147twK1EvO5CdBY/sCbrg4G9iRefaxSqhqoMTnQttJBZu69nDRWL1CKF8FgZB2WuqMI9SxAgMBAAECgYBBi2wkHI3/Y0Xi+1OUrnTivvBJIri2oW/ZXfKQ6w+PsgU+Mo2QII0l8G0Ck8DCfw3l9d9H/o2wTDgPjGzxqeXHAbxET1dS0QBTjR1zLZlFyfAs7WO8tDKmHVroUgqRkJgoQNQlBSe1E3e7pTgSKElzLuALkRS6p1jhzT2wu9U04QJBAOFr/G36PbQ6NmDYtVyEEr3vWn46JHeZISdJOsordR7Wzbt6xk6/zUDHq0OGM9rYrpBy7PNrbc0JuQrhfbIyaHMCQQCTCvETjXCMkwyUrQT6TpxVzKEVRf1rCitnNQCh1TLnDKcCEAnqZT2RRS3yNXTWFoJrtuEHMGmwUrtog9+ZJBlLAkEA2qxdkPY621XJIIO404mPgM7rMx4F+DsE7U5diHdFw2fO5brBGu13GAtZuUQ7k2W1WY0TDUO+nTN8XPDHdZDuvwJABu7TIwreLaKZS0FFJNAkCt+VEL22Dx/xn/Idz4OP3Nj53t0Guqh/WKQcYHkowxdYmt+KiJ49vXSJJYpiNoQ/NQJAM1HCl8hBznLZLQlxrCTdMvUimG3kJmA0bUNVncgUBq7ptqjk7lp5iNrle5aml99foYnzZeEUW6jrCC7Lj9tg+w==" // library marker davegut.lib_tpLink_security, line 169
		],[ // library marker davegut.lib_tpLink_security, line 170
			keyNo: 3, // library marker davegut.lib_tpLink_security, line 171
			public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCFYaoMvv5kBxUUbp4PQyd7RoZlPompsupXP2La0qGGxacF98/88W4KNUqLbF4X5BPqxoEA+VeZy75qqyfuYbGQ4fxT6usE/LnzW8zDY/PjhVBht8FBRyAUsoYAt3Ip6sDyjd9YzRzUL1Q/OxCgxz5CNETYxcNr7zfMshBHDmZXMQIDAQAB", // library marker davegut.lib_tpLink_security, line 172
			private: "MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbAgEAAoGBAIVhqgy+/mQHFRRung9DJ3tGhmU+iamy6lc/YtrSoYbFpwX3z/zxbgo1SotsXhfkE+rGgQD5V5nLvmqrJ+5hsZDh/FPq6wT8ufNbzMNj8+OFUGG3wUFHIBSyhgC3cinqwPKN31jNHNQvVD87EKDHPkI0RNjFw2vvN8yyEEcOZlcxAgMBAAECgYA3NxjoMeCpk+z8ClbQRqJ/e9CC9QKUB4bPG2RW5b8MRaJA7DdjpKZC/5CeavwAs+Ay3n3k41OKTTfEfJoJKtQQZnCrqnZfq9IVZI26xfYo0cgSYbi8wCie6nqIBdu9k54nqhePPshi22VcFuOh97xxPvY7kiUaRbbKqxn9PFwrYQJBAMsO3uOnYSJxN/FuxksKLqhtNei2GUC/0l7uIE8rbRdtN3QOpcC5suj7id03/IMn2Ks+Vsrmi0lV4VV/c8xyo9UCQQCoKDlObjbYeYYdW7/NvI6cEntgHygENi7b6WFk+dbRhJQgrFH8Z/Idj9a2E3BkfLCTUM1Z/Z3e7D0iqPDKBn/tAkBAHI3bKvnMOhsDq4oIH0rj+rdOplAK1YXCW0TwOjHTd7ROfGFxHDCUxvacVhTwBCCw0JnuriPEH81phTg2kOuRAkAEPR9UrsqLImUTEGEBWqNto7mgbqifko4T1QozdWjI10K0oCNg7W3Y+Os8o7jNj6cTz5GdlxsHp4TS/tczAH7xAkBY6KPIlF1FfiyJAnBC8+jJr2h4TSPQD7sbJJmYw7mvR+f1T4tsWY0aGux69hVm8BoaLStBVPdkaENBMdP+a07u" // library marker davegut.lib_tpLink_security, line 173
		],[ // library marker davegut.lib_tpLink_security, line 174
			keyNo: 4, // library marker davegut.lib_tpLink_security, line 175
			public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQClF0yuCpo3r1ZpYlGcyI5wy5nnvZdOZmxqz5U2rklt2b8+9uWhmsGdpbTv5+qJXlZmvUKbpoaPxpJluBFDJH2GSpq3I0whh0gNq9Arzpp/TDYaZLb6iIqDMF6wm8yjGOtcSkB7qLQWkXpEN9T2NsEzlfTc+GTKc07QXHnzxoLmwQIDAQAB", // library marker davegut.lib_tpLink_security, line 176
			private: "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAKUXTK4KmjevVmliUZzIjnDLmee9l05mbGrPlTauSW3Zvz725aGawZ2ltO/n6oleVma9Qpumho/GkmW4EUMkfYZKmrcjTCGHSA2r0CvOmn9MNhpktvqIioMwXrCbzKMY61xKQHuotBaRekQ31PY2wTOV9Nz4ZMpzTtBcefPGgubBAgMBAAECgYB4wCz+05RvDFk45YfqFCtTRyg//0UvO+0qxsBN6Xad2XlvlWjqJeZd53kLTGcYqJ6rsNyKOmgLu2MS8Wn24TbJmPUAwZU+9cvSPxxQ5k6bwjg1RifieIcbTPC5wHDqVy0/Ur7dt+JVMOHFseR/pElDw471LCdwWSuFHAKuiHsaUQJBANHiPdSU3s1bbJYTLaS1tW0UXo7aqgeXuJgqZ2sKsoIEheEAROJ5rW/f2KrFVtvg0ITSM8mgXNlhNBS5OE4nSD0CQQDJXYJxKvdodeRoj+RGTCZGZanAE1naUzSdfcNWx2IMnYUD/3/2eB7ZIyQPBG5fWjc3bGOJKI+gy/14bCwXU7zVAkAdnsE9HBlpf+qOL3y0jxRgpYxGuuNeGPJrPyjDOYpBwSOnwmL2V1e7vyqTxy/f7hVfeU7nuKMB5q7z8cPZe7+9AkEAl7A6aDe+wlE069OhWZdZqeRBmLC7Gi1d0FoBwahW4zvyDM32vltEmbvQGQP0hR33xGeBH7yPXcjtOz75g+UPtQJBAL4gknJ/p+yQm9RJB0oq/g+HriErpIMHwrhNoRY1aOBMJVl4ari1Ch2RQNL9KQW7yrFDv7XiP3z5NwNDKsp/QeU=" // library marker davegut.lib_tpLink_security, line 177
		],[ // library marker davegut.lib_tpLink_security, line 178
			keyNo: 5, // library marker davegut.lib_tpLink_security, line 179
			public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQChN8Xc+gsSuhcLVM1W1E+e1o+celvKlOmuV6sJEkJecknKFujx9+T4xvyapzyePpTBn0lA9EYbaF7UDYBsDgqSwgt0El3gV+49O56nt1ELbLUJtkYEQPK+6Pu8665UG17leCiaMiFQyoZhD80PXhpjehqDu2900uU/4DzKZ/eywwIDAQAB", // library marker davegut.lib_tpLink_security, line 180
			private: "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAKE3xdz6CxK6FwtUzVbUT57Wj5x6W8qU6a5XqwkSQl5yScoW6PH35PjG/JqnPJ4+lMGfSUD0RhtoXtQNgGwOCpLCC3QSXeBX7j07nqe3UQtstQm2RgRA8r7o+7zrrlQbXuV4KJoyIVDKhmEPzQ9eGmN6GoO7b3TS5T/gPMpn97LDAgMBAAECgYAy+uQCwL8HqPjoiGR2dKTI4aiAHuEv6m8KxoY7VB7QputWkHARNAaf9KykawXsNHXt1GThuV0CBbsW6z4U7UvCJEZEpv7qJiGX8UWgEs1ISatqXmiIMVosIJJvoFw/rAoScadCYyicskjwDFBVNU53EAUD3WzwEq+dRYDn52lqQQJBAMu30FEReAHTAKE/hvjAeBUyWjg7E4/lnYvb/i9Wuc+MTH0q3JxFGGMb3n6APT9+kbGE0rinM/GEXtpny+5y3asCQQDKl7eNq0NdIEBGAdKerX4O+nVDZ7PXz1kQ2ca0r1tXtY/9sBDDoKHP2fQAH/xlOLIhLaH1rabSEJYNUM0ohHdJAkBYZqhwNWtlJ0ITtvSEB0lUsWfzFLe1bseCBHH16uVwygn7GtlmupkNkO9o548seWkRpnimhnAE8xMSJY6aJ6BHAkEAuSFLKrqGJGOEWHTx8u63cxiMb7wkK+HekfdwDUzxO4U+v6RUrW/sbfPNdQ/FpPnaTVdV2RuGhg+CD0j3MT9bgQJARH86hfxp1bkyc7f1iJQT8sofdqqVz5grCV5XeGY77BNmCvTOGLfL5pOJdgALuOoP4t3e94nRYdlW6LqIVugRBQ==" // library marker davegut.lib_tpLink_security, line 181
		] // library marker davegut.lib_tpLink_security, line 182
	] // library marker davegut.lib_tpLink_security, line 183
} // library marker davegut.lib_tpLink_security, line 184

// ~~~~~ end include (1337) davegut.lib_tpLink_security ~~~~~

// ~~~~~ start include (1353) davegut.lib_tpLink_parents ~~~~~
library ( // library marker davegut.lib_tpLink_parents, line 1
	name: "lib_tpLink_parents", // library marker davegut.lib_tpLink_parents, line 2
	namespace: "davegut", // library marker davegut.lib_tpLink_parents, line 3
	author: "Compied by Dave Gutheinz", // library marker davegut.lib_tpLink_parents, line 4
	description: "Method common to Parent Device Drivers", // library marker davegut.lib_tpLink_parents, line 5
	category: "utilities", // library marker davegut.lib_tpLink_parents, line 6
	documentationLink: "" // library marker davegut.lib_tpLink_parents, line 7
) // library marker davegut.lib_tpLink_parents, line 8

def setChildPoll() { // library marker davegut.lib_tpLink_parents, line 10
	if (childPollInt.contains("sec")) { // library marker davegut.lib_tpLink_parents, line 11
		def pollInterval = childPollInt.replace(" sec", "").toInteger() // library marker davegut.lib_tpLink_parents, line 12
		schedule("3/${pollInterval} * * * * ?", "pollChildren") // library marker davegut.lib_tpLink_parents, line 13
	} else if (childPollInt == "1 min") { // library marker davegut.lib_tpLink_parents, line 14
		runEvery1Minute(pollChildren) // library marker davegut.lib_tpLink_parents, line 15
	} else { // library marker davegut.lib_tpLink_parents, line 16
		runEvery5Minutes(pollChildren) // library marker davegut.lib_tpLink_parents, line 17
	} // library marker davegut.lib_tpLink_parents, line 18
	return childPollInt // library marker davegut.lib_tpLink_parents, line 19
} // library marker davegut.lib_tpLink_parents, line 20

def pollChildren() { // library marker davegut.lib_tpLink_parents, line 22
	Map cmdBody = [ // library marker davegut.lib_tpLink_parents, line 23
		method: "get_child_device_list" // library marker davegut.lib_tpLink_parents, line 24
	] // library marker davegut.lib_tpLink_parents, line 25
	asyncPassthrough(cmdBody, "pollChildren", "childPollParse") // library marker davegut.lib_tpLink_parents, line 26
} // library marker davegut.lib_tpLink_parents, line 27
def childPollParse(resp, data) { // library marker davegut.lib_tpLink_parents, line 28
	def childData = parseData(resp).cmdResp.result.child_device_list // library marker davegut.lib_tpLink_parents, line 29
	def children = getChildDevices() // library marker davegut.lib_tpLink_parents, line 30
	children.each { child -> // library marker davegut.lib_tpLink_parents, line 31
		child.devicePollParse(childData) // library marker davegut.lib_tpLink_parents, line 32
	} // library marker davegut.lib_tpLink_parents, line 33
} // library marker davegut.lib_tpLink_parents, line 34

def distTriggerLog(resp, data) { // library marker davegut.lib_tpLink_parents, line 36
	def triggerData = parseData(resp) // library marker davegut.lib_tpLink_parents, line 37
	def child = getChildDevice(data.data) // library marker davegut.lib_tpLink_parents, line 38
	child.parseTriggerLog(triggerData) // library marker davegut.lib_tpLink_parents, line 39
} // library marker davegut.lib_tpLink_parents, line 40

def installChildDevices() { // library marker davegut.lib_tpLink_parents, line 42
	Map logData = [:] // library marker davegut.lib_tpLink_parents, line 43
	def respData = syncPassthrough([method: "get_child_device_list"]) // library marker davegut.lib_tpLink_parents, line 44
	def children = respData.result.child_device_list // library marker davegut.lib_tpLink_parents, line 45
	children.each { // library marker davegut.lib_tpLink_parents, line 46
		String childDni = it.mac // library marker davegut.lib_tpLink_parents, line 47
		logData << [childDni: childDni] // library marker davegut.lib_tpLink_parents, line 48
		def isChild = getChildDevice(childDni) // library marker davegut.lib_tpLink_parents, line 49
		byte[] plainBytes = it.nickname.decodeBase64() // library marker davegut.lib_tpLink_parents, line 50
		String alias = new String(plainBytes) // library marker davegut.lib_tpLink_parents, line 51
		if (isChild) { // library marker davegut.lib_tpLink_parents, line 52
			logDebug("installChildDevices: [${alias}: device already installed]") // library marker davegut.lib_tpLink_parents, line 53
		} else { // library marker davegut.lib_tpLink_parents, line 54
			String model = it.model // library marker davegut.lib_tpLink_parents, line 55
			String category = it.category // library marker davegut.lib_tpLink_parents, line 56
			String driver = getDriverId(category, model) // library marker davegut.lib_tpLink_parents, line 57
			String deviceId = it.device_id // library marker davegut.lib_tpLink_parents, line 58
			Map instData = [model: model, category: category, driver: driver]  // library marker davegut.lib_tpLink_parents, line 59
			try { // library marker davegut.lib_tpLink_parents, line 60
				addChildDevice("davegut", driver, childDni,  // library marker davegut.lib_tpLink_parents, line 61
							   [label: alias, name: model, deviceId : deviceId]) // library marker davegut.lib_tpLink_parents, line 62
				logInfo("installChildDevices: [${alias}: Installed, data: ${instData}]") // library marker davegut.lib_tpLink_parents, line 63
			} catch (e) { // library marker davegut.lib_tpLink_parents, line 64
				logWarn("installChildDevices: [${alias}: FAILED, data ${instData}, error: ${e}]") // library marker davegut.lib_tpLink_parents, line 65
			} // library marker davegut.lib_tpLink_parents, line 66
		} // library marker davegut.lib_tpLink_parents, line 67
	} // library marker davegut.lib_tpLink_parents, line 68
} // library marker davegut.lib_tpLink_parents, line 69





//command "TEST" // library marker davegut.lib_tpLink_parents, line 75
def xxTEST() {createPollList()} // library marker davegut.lib_tpLink_parents, line 76
def createPollList() { // library marker davegut.lib_tpLink_parents, line 77
	def children = getChildDevices() // library marker davegut.lib_tpLink_parents, line 78
	log.info children // library marker davegut.lib_tpLink_parents, line 79
	List requests = [] // library marker davegut.lib_tpLink_parents, line 80
	children.each { child -> // library marker davegut.lib_tpLink_parents, line 81
		Map cmdBody = [ // library marker davegut.lib_tpLink_parents, line 82
		method: "control_child", // library marker davegut.lib_tpLink_parents, line 83
		params: [ // library marker davegut.lib_tpLink_parents, line 84
			device_id: child.getDataValue("deviceId"), // library marker davegut.lib_tpLink_parents, line 85
			requestData: [ // library marker davegut.lib_tpLink_parents, line 86
				method: "get_device_info" // library marker davegut.lib_tpLink_parents, line 87
			]]] // library marker davegut.lib_tpLink_parents, line 88
//log.warn syncPassthrough(cmdBody) // library marker davegut.lib_tpLink_parents, line 89
		requests << cmdBody // library marker davegut.lib_tpLink_parents, line 90
	} // library marker davegut.lib_tpLink_parents, line 91
	log.debug createMultiCmd(requests) // library marker davegut.lib_tpLink_parents, line 92
//	asyncPassthrough(createMultiCmd(requests), "TEST", "testParse") // library marker davegut.lib_tpLink_parents, line 93
	log.trace syncPassthrough(createMultiCmd(requests)) // library marker davegut.lib_tpLink_parents, line 94
} // library marker davegut.lib_tpLink_parents, line 95

def xyTEST() { // library marker davegut.lib_tpLink_parents, line 97
	def command = [ // library marker davegut.lib_tpLink_parents, line 98
		method:"multipleRequest",  // library marker davegut.lib_tpLink_parents, line 99
		params:[ // library marker davegut.lib_tpLink_parents, line 100
			requests:[ // library marker davegut.lib_tpLink_parents, line 101
				[method:"control_child",  // library marker davegut.lib_tpLink_parents, line 102
				 params:[ // library marker davegut.lib_tpLink_parents, line 103
					 device_id:"802E2AA5F05058477DAE4F4F76CF2D9020C234A6", // library marker davegut.lib_tpLink_parents, line 104
					 requestData:[method:"get_device_info"]]],  // library marker davegut.lib_tpLink_parents, line 105
				[method:"control_child",  // library marker davegut.lib_tpLink_parents, line 106
				 params:[ // library marker davegut.lib_tpLink_parents, line 107
					 device_id:"802ECD75CFC5EF371DCB6CB117CD38E420C25A00",  // library marker davegut.lib_tpLink_parents, line 108
					 requestData:[method:get_device_info]]]]]] // library marker davegut.lib_tpLink_parents, line 109

	log.trace syncPassthrough(command) // library marker davegut.lib_tpLink_parents, line 111
} // library marker davegut.lib_tpLink_parents, line 112

def TEST() { // library marker davegut.lib_tpLink_parents, line 114
	def command = [ // library marker davegut.lib_tpLink_parents, line 115
		method: "control_child", // library marker davegut.lib_tpLink_parents, line 116
//		method: "multipleRequests", // library marker davegut.lib_tpLink_parents, line 117
		params:[ // library marker davegut.lib_tpLink_parents, line 118
			method: "multipleRequests", // library marker davegut.lib_tpLink_parents, line 119
//			method: "control_child", // library marker davegut.lib_tpLink_parents, line 120
			params: [requests: [ // library marker davegut.lib_tpLink_parents, line 121
				[params:[device_id:"802E2AA5F05058477DAE4F4F76CF2D9020C234A6", // library marker davegut.lib_tpLink_parents, line 122
				 requestData:[method:"get_device_info"]]], // library marker davegut.lib_tpLink_parents, line 123
				[params:[device_id:"802ECD75CFC5EF371DCB6CB117CD38E420C25A00", // library marker davegut.lib_tpLink_parents, line 124
				 requestData:[method:"get_device_info"]]]]]]] // library marker davegut.lib_tpLink_parents, line 125

	log.trace syncPassthrough(command) // library marker davegut.lib_tpLink_parents, line 127
} // library marker davegut.lib_tpLink_parents, line 128





def testParse(resp, data) { // library marker davegut.lib_tpLink_parents, line 134
	log.warn parseData(resp) // library marker davegut.lib_tpLink_parents, line 135
} // library marker davegut.lib_tpLink_parents, line 136



def childDeviceInfo() { // library marker davegut.lib_tpLink_parents, line 140
	List requests = [ // library marker davegut.lib_tpLink_parents, line 141
		[method: "set_alarm_configure", // library marker davegut.lib_tpLink_parents, line 142
		 params: [custom: 0, // library marker davegut.lib_tpLink_parents, line 143
				  type: "${alarmType}", // library marker davegut.lib_tpLink_parents, line 144
				  volume: "${volume}", // library marker davegut.lib_tpLink_parents, line 145
				  duration: duration // library marker davegut.lib_tpLink_parents, line 146
				 ]]] // library marker davegut.lib_tpLink_parents, line 147
	requests << [method: "get_alarm_configure"] // library marker davegut.lib_tpLink_parents, line 148
	requests << [method: "play_alarm"] // library marker davegut.lib_tpLink_parents, line 149
	requests << [method: "get_device_info"] // library marker davegut.lib_tpLink_parents, line 150
	asyncPassthrough(createMultiCmd(requests), "playAlarmConfig", "alarmParse") // library marker davegut.lib_tpLink_parents, line 151






/*	 // library marker davegut.lib_tpLink_parents, line 158

		Map cmdBody = [ // library marker davegut.lib_tpLink_parents, line 160
		method: "multipleRequest", // library marker davegut.lib_tpLink_parents, line 161
		params: [requests: requests]] // library marker davegut.lib_tpLink_parents, line 162

	List requests = [ // library marker davegut.lib_tpLink_parents, line 164
		[method: "set_alarm_configure", // library marker davegut.lib_tpLink_parents, line 165
		 params: [custom: 0, // library marker davegut.lib_tpLink_parents, line 166
				  type: "${alarmType}", // library marker davegut.lib_tpLink_parents, line 167
				  volume: "${volume}", // library marker davegut.lib_tpLink_parents, line 168
				  duration: duration // library marker davegut.lib_tpLink_parents, line 169
				 ]]] // library marker davegut.lib_tpLink_parents, line 170
	requests << [method: "get_alarm_configure"] // library marker davegut.lib_tpLink_parents, line 171
	requests << [method: "play_alarm"] // library marker davegut.lib_tpLink_parents, line 172
	requests << [method: "get_device_info"] // library marker davegut.lib_tpLink_parents, line 173
	asyncPassthrough(createMultiCmd(requests), "playAlarmConfig", "alarmParse") // library marker davegut.lib_tpLink_parents, line 174

	Map cmdBody = [ // library marker davegut.lib_tpLink_parents, line 176
		method: "multipleRequest", // library marker davegut.lib_tpLink_parents, line 177
		params: [requests: requests]] // library marker davegut.lib_tpLink_parents, line 178



		method: "control_child", // library marker davegut.lib_tpLink_parents, line 182
		params: [ // library marker davegut.lib_tpLink_parents, line 183
			device_id: getDataValue("deviceId"), // library marker davegut.lib_tpLink_parents, line 184
			requestData: [ // library marker davegut.lib_tpLink_parents, line 185
				method: "get_device_running_info" // library marker davegut.lib_tpLink_parents, line 186
//				method: "get_trigger_logs", // library marker davegut.lib_tpLink_parents, line 187
//				params: [page_size: 5,"start_id": 0] // library marker davegut.lib_tpLink_parents, line 188
			] // library marker davegut.lib_tpLink_parents, line 189
		] // library marker davegut.lib_tpLink_parents, line 190
*/	 // library marker davegut.lib_tpLink_parents, line 191
} // library marker davegut.lib_tpLink_parents, line 192



// ~~~~~ end include (1353) davegut.lib_tpLink_parents ~~~~~

// ~~~~~ start include (1339) davegut.Logging ~~~~~
library ( // library marker davegut.Logging, line 1
	name: "Logging", // library marker davegut.Logging, line 2
	namespace: "davegut", // library marker davegut.Logging, line 3
	author: "Dave Gutheinz", // library marker davegut.Logging, line 4
	description: "Common Logging and info gathering Methods", // library marker davegut.Logging, line 5
	category: "utilities", // library marker davegut.Logging, line 6
	documentationLink: "" // library marker davegut.Logging, line 7
) // library marker davegut.Logging, line 8

preferences { // library marker davegut.Logging, line 10
	input ("logEnable", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false) // library marker davegut.Logging, line 11
	input ("infoLog", "bool", title: "Enable information logging",defaultValue: true) // library marker davegut.Logging, line 12
	input ("traceLog", "bool", title: "Enable trace logging as directed by developer", defaultValue: false) // library marker davegut.Logging, line 13
} // library marker davegut.Logging, line 14

def listAttributes() { // library marker davegut.Logging, line 16
	def attrData = device.getCurrentStates() // library marker davegut.Logging, line 17
	Map attrs = [:] // library marker davegut.Logging, line 18
	attrData.each { // library marker davegut.Logging, line 19
		attrs << ["${it.name}": it.value] // library marker davegut.Logging, line 20
	} // library marker davegut.Logging, line 21
	return attrs // library marker davegut.Logging, line 22
} // library marker davegut.Logging, line 23

def setLogsOff() { // library marker davegut.Logging, line 25
	def logData = [logEnagle: logEnable, infoLog: infoLog, traceLog:traceLog] // library marker davegut.Logging, line 26
	if (logEnable) { // library marker davegut.Logging, line 27
		runIn(1800, debugLogOff) // library marker davegut.Logging, line 28
		logData << [debugLogOff: "scheduled"] // library marker davegut.Logging, line 29
	} // library marker davegut.Logging, line 30
	if (traceLog) { // library marker davegut.Logging, line 31
		runIn(1800, traceLogOff) // library marker davegut.Logging, line 32
		logData << [traceLogOff: "scheduled"] // library marker davegut.Logging, line 33
	} // library marker davegut.Logging, line 34
	return logData // library marker davegut.Logging, line 35
} // library marker davegut.Logging, line 36

def logTrace(msg){ // library marker davegut.Logging, line 38
	if (traceLog == true) { // library marker davegut.Logging, line 39
		log.trace "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.Logging, line 40
	} // library marker davegut.Logging, line 41
} // library marker davegut.Logging, line 42

def traceLogOff() { // library marker davegut.Logging, line 44
	device.updateSetting("traceLog", [type:"bool", value: false]) // library marker davegut.Logging, line 45
	logInfo("traceLogOff") // library marker davegut.Logging, line 46
} // library marker davegut.Logging, line 47

def logInfo(msg) {  // library marker davegut.Logging, line 49
	if (textEnable || infoLog) { // library marker davegut.Logging, line 50
		log.info "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.Logging, line 51
	} // library marker davegut.Logging, line 52
} // library marker davegut.Logging, line 53

def debugLogOff() { // library marker davegut.Logging, line 55
	device.updateSetting("logEnable", [type:"bool", value: false]) // library marker davegut.Logging, line 56
	logInfo("debugLogOff") // library marker davegut.Logging, line 57
} // library marker davegut.Logging, line 58

def logDebug(msg) { // library marker davegut.Logging, line 60
	if (logEnable || debugLog) { // library marker davegut.Logging, line 61
		log.debug "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.Logging, line 62
	} // library marker davegut.Logging, line 63
} // library marker davegut.Logging, line 64

def logWarn(msg) { log.warn "${device.displayName}-${driverVer()}: ${msg}" } // library marker davegut.Logging, line 66

def logError(msg) { log.error "${device.displayName}-${driverVer()}: ${msg}" } // library marker davegut.Logging, line 68

// ~~~~~ end include (1339) davegut.Logging ~~~~~
