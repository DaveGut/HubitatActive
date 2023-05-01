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
def driverVer() { return "1.0.0" }
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
	definition (name: "Kasa Camera",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/camera.groovy"
			   ) {
		capability "Switch"
		capability "Refresh"
		capability "Sensor"
        capability "Motion Sensor"
		command "motionDetect", [[
			name: "Motion Detct on/off",
			constraints: ["on", "off"],
			type: "ENUM"]]
		attribute "motionDetect", "string"
		command "motionPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["off", "5", "10", "15", "30"],
			type: "ENUM"]]
		command "motionPoll"
	}
	preferences {
		input ("deviceIp", "text", title: "KasaCam Device Ip", defaultValue: "notSet")
		input ("devicePort", "text", title: "KasaCam Device Port", defaultValue: "9999")
		input("userName", "string", title: "Kasa Account User Name", defaultValue: "notSet")
		input("userPassword", "password", title: "Kasa Account Password")
		input ("motionTimeout", "number", title: "Motion Active Timeout (seconds)", defaultValue: 30)
		if (getDataValue("kasaToken")) {
			input ("motionSens", "enum", title: "Motion Detect Sensitivity",
				   options: ["low", "medium", "high"], defaultValue: "low")
			input ("nightTrigger", "number", title: "Night Motion Detect Trigger Time (msec)",
				   defaultValue: 600)
			input ("dayTrigger", "number", title: "Day Motion Detect Trigger Time (msec)",
				   defaultValue: 600)
			input ("dayNight", "enum", title: "Day-night mode",
				   options: ["day", "night", "auto"], defaultValue: "auto")
			input ("bcDetect", "enum", title: "Baby Cry Detect",
				   options: ["on", "off"], defaultValue: "off")
			input ("pDetect", "enum", title: "Person Detect",
				   options: ["on", "off"], defaultValue: "off")
			input ("soundDetect", "enum", title: "Sound Detection (device dependent)",
				   options: ["on", "off"], defaultValue: "off")
			input ("resolution", "enum", title: "Video Resolution",
				   options: ["1080P", "720P", "360P"],
				   defaultValue: "360P")
			input ("ledOnOff", "enum", title: "LED On / Off",
				   options: ["on", "off"], defaultValue: "on")
		}
		input ("textEnable", "bool", title: "Enable information logging${helpLogo()}",defaultValue: true)
		input ("logEnable", "bool", title: "Enable debug logging", defaultValue: false)
	}
}
def installed() {
	state.pollInterval = "off"
	runIn(2, updated)
}
def updated() {
	unschedule()
	def updStatus = [:]
	if (deviceIp == "notSet" || devicePort == "notSet") {
		logWarn("Updated: Enter the device IP and Port and Save Preferences.")
		return
	} else {
		updStatus << [getDeviceId: getDeviceId()]
		updStatus << [deviceId: getDataValue("deviceId")]
	}
	updStatus << [contFns: contFns]
	
	updStatus << [userName: userName]
	if (userName == "notSet" || userPassword == null) {
		updStatus << [getCloudData: "failed", reason: "user data not set"]
	} else if (!getDataValue("kasaToken")) {
		updStatus << [getKasaToken: getKasaToken()]
		updStatus << [getCloudUrl: getKasaCloudUrl()]
	} else {
		updStatus << [kasaToken: "redacted", cloudUrl: getDataValue("kasaCloudUrl")]
	}
	
	if (getDataValue("kasaToken")) {
		updStatus << [updateCamPrefs: updateCamPrefs()]
		runEvery30Minutes(refresh)
		schedule("0 30 2 ? * MON,WED,SAT", updateKasaToken)
	}

	if (logEnable) { runIn(1800, debugLogOff) } 
	updStatus << [textEnable: textEnable, logEnable: logEnable]
	updStatus << [pollInterval: motionPollInterval(state.pollInterval)]
	logInfo("updated: ${updStatus}")
}
String helpLogo() {
	return """<a href="https://github.com/DaveGut/HubitatActive/blob/master/KasaCAM/README.md">""" +
		"""<div style="position: absolute; top: 20px; right: 150px; height: 80px; font-size: 28px;">Kasa CAM Help</div></a>"""
}

//	===== Device Command Methods =====
def on() { setSwitch("on") }
def off() { setSwitch("off") }
def setSwitch(onOff) {
	if (getDataValue("kasaToken")) {
		def cmdData = [uri: getDataValue("kasaCloudUrl")]
		def cmd = """{"smartlife.cam.ipcamera.switch":{"set_is_enable":{"value":"${onOff}"},"get_is_enable":{}}}"""
		def execResp = sendKasaCmd(cmd, "setSwitchStatus")
		if (execResp == "OK") {
			logDebug("setSwitch")
			pauseExecution(2000)
		} else {
			logWarn("setSwitch: ${execResp}")
		}
	} else {
		logWarn("setSwitch: Not available.  Not kasaToken.")
	}
}
def setSwitchStatus(resp, data) {
	def updData = [:]
	resp = cloudPrepResp(resp)
	if (resp.cloudError) {
		updData << resp
	} else {
		if (resp["smartlife.cam.ipcamera.switch"]) {
			def onOff = resp["smartlife.cam.ipcamera.switch"].get_is_enable.value
			sendEvent(name: "switch", value: status)
			updData << [switch: status]
		} else {
			updData << [switchUpdate: "failed", data: resp]
		}
	}
	logInfo("setSwitchStatus: ${updData}")
}

def motionDetect(onOff) {
	def data = [
		"smartlife.cam.ipcamera.motionDetect":[
			set_is_enable:[value: onOff],
			get_is_enable:[]]]	
	def cmd = JsonOutput.toJson(data)
	def execResp = sendKasaCmd(cmd, "setMotionDetectStatus")
	logDebug("motionDetect:${execResp}")
}
def setMotionDetectStatus(resp, data) {
	def updData = [:]
	resp = cloudPrepResp(resp)
	if (resp.cloudError) {
		updData << resp
	} else {
		if (resp["smartlife.cam.ipcamera.motionDetect"]) {
			def motDet = resp["smartlife.cam.ipcamera.motionDetect"].get_is_enable.value
			sendEvent(name: "motionDetect", value: motDet)
			updData << [motionDetect: motDet]
		} else {
			updData << [motionDetectUpdate: "failed", data: resp]
		}
	}
	logInfo("setMotionDetectStatus: ${updData}")
}

def refresh() { refreshCam() }
def refreshCam() {
	def data = [
		"smartlife.cam.ipcamera.motionDetect":[
			get_is_enable:[],
			get_sensitivity:[],
			get_min_trigger_time:[]
		],
		"smartlife.cam.ipcamera.switch":[
			get_is_enable:[]],
		"smartlife.cam.ipcamera.led":[
			get_status:[]],
		"smartlife.cam.ipcamera.videoControl":[
			get_resolution:[]],
		"smartlife.cam.ipcamera.soundDetect":[
			get_sensitivity:[],
			get_is_enable:[]],
		"smartlife.cam.ipcamera.dayNight":[
			get_mode:[]],
		"smartlife.cam.ipcamera.intelligence":[
			get_bcd_enable:[],
			get_pd_enable:[]],
		"smartlife.cam.ipcamera.relay":[
			get_preview_snapshot:[]]
	]

	def cmd = JsonOutput.toJson(data)
	def cmdResp = sendKasaCmd(cmd, "setCamPrefs")
	logDebug("refreshCam: ${cmdResp}")
}
def setCamPrefs(resp, data) {
	def updData = [:]
	resp = cloudPrepResp(resp)
	if (resp.cloudError) {
		updData << resp
	} else {
		if (resp["smartlife.cam.ipcamera.led"]) {
			def ledOnOff = resp["smartlife.cam.ipcamera.led"].get_status.value
			device.updateSetting("ledOnOff", [type:"enum", value: ledOnOff])
			updData << [ledOnOff: ledOnOff]
		}
		if (resp["smartlife.cam.ipcamera.switch"]) {
			def onOff = resp["smartlife.cam.ipcamera.switch"].get_is_enable.value
			sendEvent(name: "switch", value: onOff, type: digital)
			updData << [switch: onOff]
		}
		if (resp["smartlife.cam.ipcamera.videoControl"]) {
			def resolution = resp["smartlife.cam.ipcamera.videoControl"].get_resolution.value[0].resolution
			device.updateSetting("resolution", [type:"enum", value: resolution])
			updData << [resolution: resolution]
		}
		if (resp["smartlife.cam.ipcamera.soundDetect"]) {
			def sdEnable = resp["smartlife.cam.ipcamera.soundDetect"].get_is_enable.value
			device.updateSetting("soundDetect", [type:"enum", value: sdEnable])
			updData << [soundDetect: sdEnable]
		}
		if (resp["smartlife.cam.ipcamera.motionDetect"]) {
			def motDet = resp["smartlife.cam.ipcamera.motionDetect"].get_is_enable.value
			def sens = resp["smartlife.cam.ipcamera.motionDetect"].get_sensitivity.value
			def dayTrig = resp["smartlife.cam.ipcamera.motionDetect"].get_min_trigger_time.day_mode_value
			def nightTrig = resp["smartlife.cam.ipcamera.motionDetect"].get_min_trigger_time.night_mode_value
			sendEvent(name: "motionDetect", value: motDet)
			device.updateSetting("motionSens", [type:"enum", value: sens])
			device.updateSetting("dayTrigger", [type:"number", value: dayTrigger])
			device.updateSetting("nightTrigger", [type:"number", value: nightTrigger])
			updData << [motionDetect: motDet, motionSens: sens,
						dayTrigger: dayTrigger, nightTrigger: nightTrigger]
		}
		if (resp["smartlife.cam.ipcamera.dayNight"]) {
			def dayNight = resp["smartlife.cam.ipcamera.dayNight"].get_mode.value
			device.updateSetting("dayNight", [type:"enum", value: dayNight])
			updData << [dayNight: dayNight]
		}
		if (resp["smartlife.cam.ipcamera.intelligence"]) {
			def bcDet = resp["smartlife.cam.ipcamera.intelligence"].get_bcd_enable.value
			def pDet = resp["smartlife.cam.ipcamera.intelligence"].get_pd_enable.value
			device.updateSetting("bcDetect", [type:"enum", value: bcDet])
			device.updateSetting("pDetect", [type:"enum", value: pDet])
			updData << [bcDetect: bcDet, pDetect: pDet]
		}
	}
	logDebug("setCamPrefs: ${updData}")
}

def motionPollInterval(interval) {
	state.pollInterval = interval
	if (interval != "off") {
		schedule("3/${interval} * * * * ?", "motionPoll")
	}
	logDebug("motionPollInterval: [pollInterval: ${interval}]")
	return interval
}
def motionPoll() {
	def cmd = """{"system":{"get_sysinfo":{}}}"""
	sendLanCmd(cmd, "motionParse")
}
def motionParse(resp) {
	def updData = [:]
	resp = lanPrepResp(resp)
	if (resp.lanError) {
		updData << resp
		logWarn("motionParse: ${resp}")
	} else {
		def status = resp.system.get_sysinfo.system
		def lastActTime = status.last_activity_timestamp
		def sysTime = status.system_time
		def deltaTime = sysTime - lastActTime
		def a_type = status.a_type
		if (deltaTime < 300 && lastActTime > state.lastActiveTime) {
	   	 	sendEvent(name: "motion", value: "active")
			state.lastActiveTime = lastActTime
			updData << [motion: "active", motionTime: lastActTime, type: a_type]
		} else if (device.currentValue("motion") == "active" &&
				   motionTimeout.toInteger() < sysTime - lastActTime) {
			sendEvent(name: "motion", value: "inactive")
			updData << [motion: "inactive"]
		}
		def onOff = status.camera_switch
		if (device.currentValue("switch") != onOff) {
			sendEvent(name: "switch", value: onOff)
			updData << [switch: onOff]
		}
	}
	if (updData != [:]) {
		logInfo("motionParse: ${updData}")
	}
}

//	===== LAN Setup Methods =====
def getDeviceId() {
	def cmd = """{"system":{"get_sysinfo":{}}}"""
	def status = sendLanCmd(cmd, "setDeviceId")
	pauseExecution(5000)
	return status
}
def setDeviceId(message) {
	def resp = lanPrepResp(message)
	if (resp.lanError) {
		logWarn("setDeviceId: [failed: ${resp}]")
	} else {
		def deviceId = resp.system.get_sysinfo.system.deviceId
		updateDataValue("deviceId", deviceId)
		logInfo("setDeviceId: [deviceId: ${deviceId}]")
	}
}

//	===== Cloud Setup Methods =====
def getKasaToken() {
	def respData = [:]
	def cmdResp
	def termId = java.util.UUID.randomUUID()
	def cmdBody = [
		method: "login",
		params: [
			appType: "Kasa_Android",
			cloudUserName: "${userName}",
			cloudPassword: "${userPassword.replaceAll('&gt;', '>').replaceAll('&lt;','<')}",
			terminalUUID: "${termId}"]]
	cmdData = [uri: "https://wap.tplinkcloud.com",
			   cmdBody: cmdBody]
	cmdResp = sendCloudCmd(cmdData)
	if (cmdResp.error_code == 0) {
		updateDataValue("kasaToken", cmdResp.result.token)
		respData << [kasaToken: "created/updated"]
	} else {
		respData << [kasaToken: "updateFailed", data: cmdResp]
	}
	pauseExecution(5000)
	return respData
}
def updateKasaToken() {
	def respData = [updateKasaToken: getKasaToken()]
	logInfo("updateKasaToken: ${respData}")
}
def getKasaCloudUrl() {
	def respData = [:]
	def cmdData = [uri: "https://wap.tplinkcloud.com?token=${getDataValue("kasaToken")}",
				   cmdBody: [method: "getDeviceList"]]
	def cmdResp = sendCloudCmd(cmdData)
	if (cmdResp.error_code == 0) {
		def cloudDevices = cmdResp.result.deviceList
		updateDataValue("kasaCloudUrl", cloudDevices[0].appServerUrl)
		respData << [kasaCloudUrl: cloudDevices[0].appServerUrl]
	} else {
		respData << [kasaCloudUrl: "updateFailed", data: cmdResp]
	}
	pauseExecution(5000)
	return respData
}

def updateCamPrefs() {
	def data = [
		"smartlife.cam.ipcamera.motionDetect":[
			set_is_enable:[value:"${motionDetect}"],
			set_sensitivity:[value:"${motionSens}"],
			set_min_trigger_time:[
				day_mode_value: dayTrigger,
				night_mode_value: nightTrigger
				]
		],
		"smartlife.cam.ipcamera.led":[
			set_status:[value:"${ledOnOff}"]
		],
		"smartlife.cam.ipcamera.videoControl":[
			set_resolution:[
				value:[
					[channel:1, resolution:"${resolution}"]]]
		],		
		"smartlife.cam.ipcamera.soundDetect":[
			set_is_enable:[value:"${soundDetect}"]
		],
		"smartlife.cam.ipcamera.dayNight":[
			set_mode:[value:"${dayNight}"]
		],
		"smartlife.cam.ipcamera.intelligence":[
			set_bcd_enable:[value:"${bcDetect}"],
			set_pd_enable:[value:"${pDetect}"]
		]]
	def cmd = JsonOutput.toJson(data)
	def execResp = sendKasaCmd(cmd, "checkCamPrefsUpdate")
	return execResp
}
def checkCamPrefsUpdate(resp, data) {
	def updData = [:]
	resp = cloudPrepResp(resp)
	if (resp.cloudError) {
		updData << resp
	} else {
		updData << [status: "prefsUpdated", cmdResp: resp]
	}
	logInfo("checkCamPrefsUpdate: ${updData}")
	refresh()
}

//	===== LAN Communications =====
def sendLanCmd(command, action) {
	logDebug("sendLanCmd: [ip: ${deviceIp}, cmd: ${command}]")
	def errorData = "OK"
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${deviceIp}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 parseWarning: true,
		 timeout: 9,
		 ignoreResponse: false,
		 callback: action])
	try {
		sendHubCommand(myHubAction)
	} catch (e) {
		logWarn("sendLanCmd: LAN Error = ${e}.\n\rNo retry on this error.")
	}
	return errorData
}
def lanPrepResp(message) {
	def respData
	try {
		def resp = parseLanMessage(message)
		if (resp.type == "LAN_TYPE_UDPCLIENT") {
			def clearResp = inputXOR(resp.payload)
			respData = new JsonSlurper().parseText(clearResp)
		} else {
			respData = [lanError: "lan_01", error: "invalid response type", respType: resp.type]
			logDebug("lanPrepResp: [error: error, reason: not LAN_TYPE_UDPCLIENT, respType: ${resp.type}]")
		}
	} catch (err) {
		respData = [lanError: "lan_02", error: err]
	}
	return respData
}
private outputXOR(command) {
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
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)
		XORtemp = nextKey ^ key
		key = nextKey
		cmdResponse += new String(XORtemp)
	}
	return cmdResponse
}

//	===== Cloud Methods =====
def sendKasaCmd(command, action = "cloudParse") {
	def execResp = "OK"
	def cmdBody = [
		method: "passthrough",
		params: [
			deviceId: getDataValue("deviceId"),
			requestData: "${command}"
		]
	]
	if (!getDataValue("kasaCloudUrl") || !getDataValue("kasaToken")) {
		execResp = [cloudError: "cloud_03", reason: "Cloud Comms not set up."]
	} else {
		def sendCloudCmdParams = [
			uri: "${getDataValue("kasaCloudUrl")}/?token=${getDataValue("kasaToken")}",
			requestContentType: 'application/json',
			contentType: 'application/json',
			headers: ['Accept':'application/json; version=1, */*; q=0.01'],
			timeout: 10,
			body : new groovy.json.JsonBuilder(cmdBody).toString()
		]
		try {
			asynchttpPost(action, sendCloudCmdParams)
		} catch (e) {
			execResp = [cloudError: "cloud_04", date: e]
		}
	}
	return execResp
}
def cloudPrepResp(resp) {
	def respData
	try {
		response = new JsonSlurper().parseText(resp.data)
	} catch (e) {
		respData = [cloudError: "cloud_01", data: e]
	}
	if (resp.status == 200 && response.error_code == 0 && resp != []) {
		respData = new JsonSlurper().parseText(response.result.responseData)
	} else {
		respData = [cloudError: "cloud_02", data: resp.data]
	}
	return respData
}
def sendCloudCmd(cmdData) {
	def commandParams = [
		uri: cmdData.uri,
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		body : new groovy.json.JsonBuilder(cmdData.cmdBody).toString()
	]
	def respData
	try {
		httpPostJson(commandParams) {resp ->
			if (resp.status == 200) {
				respData = resp.data
			} else {
				def msg = "sendKasaCmd: <b>HTTP Status not equal to 200.  Protocol error.  "
				msg += "HTTP Protocol Status = ${resp.status}"
				logWarn(msg)
				respData = [error_code: resp.status, msg: "HTTP Protocol Error"]
			}
		}
	} catch (e) {
		def msg = "sendKasaCmd: <b>Error in Cloud Communications.</b> The Kasa Cloud is unreachable."
		msg += "\nAdditional Data: Error = ${e}\n\n"
		logWarn(msg)
		respData = [error_code: 9999, msg: e]
	}
	return respData
}

//	===== Logging Methods =====
def listAttributes(trace = false) {
	def attrs = device.getSupportedAttributes()
	def attrList = [:]
	attrs.each {
		def val = device.currentValue("${it}")
		attrList << ["${it}": val]
	}
	if (trace == true) {
		logInfo("Attributes: ${attrList}")
	} else {
		logDebug("Attributes: ${attrList}")
	}
}
def logTrace(msg){
	log.trace "${device.displayName}-${driverVer()}: ${msg}"
}
def logInfo(msg) {
	if (textEnable || infoLog) {
		log.info "${device.displayName}-${driverVer()}: ${msg}"
	}
}
def debugLogOff() {
	if (logEnable) {
		device.updateSetting("logEnable", [type:"bool", value: false])
	}
	logInfo("debugLogOff")
}
def logDebug(msg) {
	if (logEnable || debugLog) {
		log.debug "${device.displayName}-${driverVer()}: ${msg}"
	}
}
def logWarn(msg) { log.warn "${device.displayName}-${driverVer()}: ${msg}" }
