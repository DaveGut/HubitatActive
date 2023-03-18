/*	===== HUBITAT INTEGRATION VERSION =====================================================
Hubitat - Samsung TV Remote Driver
		Copyright 2022 Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2022 Version 4.1 ====================================================================
Version 4.1
a.	Moved websocket and tv application methods into shared libaries for maintainability.
b.	Fix websocket status to add close function.  Allows user to update status if the
	function fails.

Known issues:
a.	2022 Frame TVs without connectST enabled: when placed in art mode and motion 
	detection/light detection enabled on the TV, the TV may drop out of artMode status.
b.	Volume reporting zero.  Checks of several issues shows that this value is coming 
	from SmartThings.

===========================================================================================*/
def driverVer() { return "4.1-1" }
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
	definition (name: "Samsung TV Remote",
				namespace: "davegut",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungTvRemote/SamsungTVRemote.groovy"
			   ){
		capability "Refresh"
		capability "Configuration"
		capability "SamsungTV"
		command "showMessage", [[name: "Not Implemented"]]
		capability "Switch"
		command "close"
		attribute "wsStatus", "string"
		
		//	Currently under test/validation (in main code)
		command "artMode"
		attribute "artModeStatus", "string"
		command "ambientMode"
		command "appOpenByName", ["string"]
		command "appOpenByCode", ["string"]
		command "appClose"
		//	Remote Control Keys (samsungTV-Keys)
		command "pause"
		command "play"
		command "stop"
		command "sendKey", ["string"]
		//	Cursor and Entry Control
		command "arrowLeft"
		command "arrowRight"
		command "arrowUp"
		command "arrowDown"
		command "enter"
		command "numericKeyPad"
		//	Menu Access
		command "home"
		command "menu"
		command "guide"
		command "info"
		//	Source Commands
		command "source"
		command "hdmi"
		//	TV Channel
		command "channelList"
		command "channelUp"
		command "channelDown"
		command "previousChannel"
		//	Playing Navigation Commands
		command "exit"
		command "Return"
		command "fastBack"
		command "fastForward"
		command "nextTrack", [[name: "Sets Channel Up"]]
		command "previousTrack", [[name: "Sets Channel Down"]]
		
		//	SmartThings Functions (library samsungTV=ST)
		command "toggleInputSource", [[name: "SmartThings Function"]]
		command "toggleSoundMode", [[name: "SmartThings Function"]]
		command "togglePictureMode", [[name: "SmartThings Function"]]
		command "setTvChannel", ["SmartThings Function"]
		attribute "tvChannel", "string"
		attribute "tvChannelName", "string"
		command "setInputSource", ["SmartThings Function"]
		attribute "inputSource", "string"
		command "setVolume", ["SmartThings Function"]
		command "setPictureMode", ["SmartThings Function"]
		command "setSoundMode", ["SmartThings Function"]
		command "setLevel", ["SmartThings Function"]
		
		//	Smart App Control (library samsungTV-apps)
		attribute "currentApp", "string"
		command "appRunBrowser"
		command "appRunYouTube"
		command "appRunNetflix"
		command "appRunPrimeVideo"
		command "appRunYouTubeTV"
		command "appRunHulu"
		attribute "transportStatus", "string"
		attribute "level", "NUMBER"
		attribute "trackDescription", "string"

		capability "PushableButton"
		capability "Variable"
	}
	preferences {
		input ("deviceIp", "text", title: "Samsung TV Ip", defaultValue: "")
		if (deviceIp) {
			input ("tvPwrOnMode", "enum", title: "TV Startup Display", 
				   options: ["ART_MODE", "Ambient", "none"], defaultValue: "none")
			input ("logEnable", "bool",  
				   title: "Enable debug logging for 30 minutes", defaultValue: false)
			input ("infoLog", "bool", 
				   title: "Enable information logging " + helpLogo(),
				   defaultValue: true)
			input ("traceLog", "bool", title: "Enable trace logging as directed by developer", defaultValue: false)
			input ("connectST", "bool", title: "Connect to SmartThings for added functions", defaultValue: false)
		}
		def onPollOptions = ["local": "Local", "off": "DISABLE"]
		if (connectST) {
			onPollOptions = ["st": "SmartThings", "local": "Local", "off": "DISABLE"]
			input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "")
			if (stApiKey) {
				input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "")
			}
			input ("stPollInterval", "enum", title: "SmartThings Poll Interval (minutes)",
				   options: ["off", "1", "5", "15", "30"], defaultValue: "15")
			input ("stTestData", "bool", title: "Get ST data dump for developer", defaultValue: false)
		}
		input ("pollMethod", "enum", title: "Power Polling Method", defaultValue: "local",
			   options: onPollOptions)
//			   options: ["st": "SmartThings", "local": "Local", "off": "DISABLE"])
		input ("pollInterval","enum", title: "Power Polling Interval (seconds)",
			   options: ["off", "10", "15", "20", "30", "60"], defaultValue: "60")
		input ("findAppCodes", "bool", title: "Scan for App Codes (use rarely)", defaultValue: false)
		input ("resetAppCodes", "bool", title: "Delete and Rescan for App Codes (use rarely)", defaultValue: false)
	}
}

String helpLogo() { // library marker davegut.kasaCommon, line 11
	return """<a href="https://github.com/DaveGut/HubitatActive/blob/master/SamsungTvRemote/README.md">""" +
		"""<div style="position: absolute; top: 10px; right: 10px; height: 80px; font-size: 20px;">Samsung TV Remote Help</div></a>"""
}

//	===== Installation, setup and update =====
def installed() {
	state.token = "12345678"
	def tokenSupport = "false"
	sendEvent(name: "wsStatus", value: "closed")
	sendEvent(name: "wsStatus", value: "45")
	runIn(1, updated)
}

def updated() {
	unschedule()
	close()
	def updStatus = [:]
	if (!deviceIp) {
		logWarn("\n\n\t\t<b>Enter the deviceIp and Save Preferences</b>\n\n")
		updStatus << [status: "ERROR", data: "Device IP not set."]
	} else {
//		updStatus << [getDeviceData: configure()]
		if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
			updateDataValue("driverVersion", driverVer())
			updStatus << [driverVer: driverVer()]
		}
		if (logEnable) { runIn(1800, debugLogOff) }
		if (traceLog) { runIn(600, traceLogOff) }
		updStatus << [logEnable: logEnable, infoLog: infoLog, traceLog: traceLog]
		updStatus << [setOnPollInterval: setOnPollInterval()]
		if (!pollMethod) {
			pollMethod = "local"
			device.updateSetting("pollMethod", [type:"enum", value: "local"])
		}
		updStatus << [pollMethod: newPollMethod]
/*		def newPollMethod = pollMethod
		if (newPollMethod == null) {
			
		if (!pollMethod && connectST) {
			newPollMethod = "st"
		} else if (pollMethod == "st" && !connectST) {
			newPollMethod = "local"
		} else {
			newPollMethod = pollMethod
		}
		device.updateSetting("pollMethod", [type:"enum", value: newPollMethod])
		updStatus << [pollMethod: newPollMethod]*/
		if (resetAppCodes) {
			state.appData = [:]
			runIn(1, updateAppCodes)
		} else if (findAppCodes) {
			runIn(1, updateAppCodes)
		}
		runIn(1, configure)
	}
	sendEvent(name: "numberOfButtons", value: 45)
	sendEvent(name: "wsStatus", value: "closed")
	state.standbyTest = false
	logInfo("updated: ${updStatus}")

//	runIn(1, configure)
	listAttributes(true)
	logTrace("updated: onPollCount = $state.onPollCount")
	state.onPollCount = 0
}

def setOnPollInterval() {
	if (pollMethod == "off") {
		pollInterval = "DISABLED"
		device.updateSetting("pollInterval", [type:"enum", value: "off"])
	} else {
		if (pollInterval == null) {
			pollInterval = "60"
			device.updateSetting("pollInterval", [type:"enum", value: "60"])
		}
		if (pollInterval == "60") {
			runEvery1Minute(onPoll)
		} else if (pollInterval != "off") {
			schedule("0/${pollInterval} * * * * ?",  onPoll)
		}
	}
	return pollInterval
}




def configure() {
	def respData = [:]
	def tvData = [:]
	try{
		httpGet([uri: "http://${deviceIp}:8001/api/v2/", timeout: 5]) { resp ->
			tvData = resp.data
			runIn(1, getArtModeStatus)
		}
	} catch (error) {
		tvData << [status: "error", data: error]
		logError("configure: TV Off during setup or Invalid IP address.\n\t\tTurn TV On and Run CONFIGURE or Save Preferences!")

	}
	if (!tvData.status) {
		def wifiMac = tvData.device.wifiMac
		updateDataValue("deviceMac", wifiMac)
		def alternateWolMac = wifiMac.replaceAll(":", "").toUpperCase()
		updateDataValue("alternateWolMac", alternateWolMac)
		device.setDeviceNetworkId(alternateWolMac)
		def modelYear = "20" + tvData.device.model[0..1]
		updateDataValue("modelYear", modelYear)
		def frameTv = "false"
		if (tvData.device.FrameTVSupport) {
			frameTv = tvData.device.FrameTVSupport
		}
		updateDataValue("frameTv", frameTv)
		if (tvData.device.TokenAuthSupport) {
			tokenSupport = tvData.device.TokenAuthSupport
			updateDataValue("tokenSupport", tokenSupport)
		}
		def uuid = tvData.device.duid.substring(5)
		updateDataValue("uuid", uuid)
		respData << [status: "OK", dni: alternateWolMac, modelYear: modelYear,
					 frameTv: frameTv, tokenSupport: tokenSupport]
		sendEvent(name: "artModeStatus", value: "none")
		def data = [request:"get_artmode_status",
					id: "${getDataValue("uuid")}"]
		data = JsonOutput.toJson(data)
		artModeCmd(data)
		state.configured = true
	} else {
		respData << tvData
	}
	runIn(1, stUpdate)
	logInfo("configure: ${respData}")
	return respData
}

def stUpdate() {
	def stData = [:]
	if (connectST) {
		stData << [connectST: "true"]
		stData << [connectST: connectST]
		if (!stApiKey || stApiKey == "") {
			logWarn("\n\n\t\t<b>Enter the ST API Key and Save Preferences</b>\n\n")
			stData << [status: "ERROR", date: "no stApiKey"]
		} else if (!stDeviceId || stDeviceId == "") {
			getDeviceList()
			logWarn("\n\n\t\t<b>Enter the deviceId from the Log List and Save Preferences</b>\n\n")
			stData << [status: "ERROR", date: "no stDeviceId"]
		} else {
			if (device.currentValue("volume") == null) {
//				sendEvent(name: "volume", value: 0)
//				sendEvent(name: "level", value: 0)
			}
			def stPollInterval = stPollInterval
			if (stPollInterval == null) { 
				stPollInterval = "15"
				device.updateSetting("stPollInterval", [type:"enum", value: "15"])
			}
			switch(stPollInterval) {
				case "1" : runEvery1Minute(refresh); break
				case "5" : runEvery5Minutes(refresh); break
				case "15" : runEvery15Minutes(refresh); break
				case "30" : runEvery30Minutes(refresh); break
				default: unschedule("refresh")
			}
			deviceSetup()
			stData << [stPollInterval: stPollInterval]
		}
	} else {
		stData << [connectST: "false"]
	}
	logInfo("stUpdate: ${stData}")
}




//	===== Polling/Refresh Capability =====
def onPoll() {
	if (traceLog) { state.onPollCount += 1 }
	if (pollMethod == "st") {
		def sendData = [
			path: "/devices/${stDeviceId.trim()}/status",
			parse: "stPollParse"
			]
		asyncGet(sendData, "stPollParse")
	} else if (pollMethod == "local") {
		def sendCmdParams = [
			uri: "http://${deviceIp}:8001/api/v2/",
			timeout: 6
		]
		asynchttpGet("onPollParse", sendCmdParams)
	} else {
		logWarn("onPoll: Polling is disabled")
	}
	if (getDataValue("driverVersion") != driverVer()) {
		logInfo("Auto Configuring changes to this TV.")
		updated()
		pauseExecution(3000)
	}
}




def stPollParse(resp, data) {
	def respLog = [:]
	if (resp.status == 200) {
		try {
			def respData = new JsonSlurper().parseText(resp.data)
			def onOff = respData.components.main.switch.switch.value
			if (device.currentValue("switch") != onOff) {
				logInfo("stPollParse: [switch: ${onOff}]")
				sendEvent(name: "switch", value: onOff)
				if (onOff == "on") {
					runIn(3, setPowerOnMode)
				} else {
					close()
				}
			}
		} catch (err) {
			respLog << [status: "ERROR",
						errorMsg: err,
						respData: resp.data]
		}
	} else {
		respLog << [status: "ERROR",
					httpCode: resp.status,
					errorMsg: resp.errorMessage]
	}
	if (respLog != [:]) {
		logWarn("stPollParse: ${respLog}")
	}
}




def onPollParse(resp, data) {
	def powerState
	if (resp.status == 200) {
		def tempPower = new JsonSlurper().parseText(resp.data).device.PowerState
		if (tempPower == null) {
			powerState = "on"
		} else { 
			powerState = tempPower
		}
	} else {
		logTrace("onPollParse: [state: error, status: $resp.status]")
		powerState = "NC"
	}
	def onOff = "on"
	if (powerState == "on") {
		state.standbyTest = false
		onOff = "on"
	} else {
		if (device.currentValue("switch") == "on") {
			//	If currently on, will need two non-"on" values to set switch off
			if (!state.standbyTest) {
				state.standbyTest = true
				runIn(5, onPoll)
			} else {
				state.standbyTest = false
				onOff = "off"
			}
		} else {
			//	If powerState goes to standby, this indicates tv screen is off
			//	as the tv powers down (takes 0.5 to 2 minutes to disconnect).
			onOff = "off"
		}
		logTrace("onPollParse: [switch: ${device.currentValue("switch")}, onOff: ${onOff}, powerState: $powerState, stbyTest: $state.standbyTest]")
	}
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff)
		if (onOff == "on") {
			runIn(5, setPowerOnMode)
			refresh()
		} else {
			close()
			refresh()
		}
		logInfo("onPollParse: [switch: ${onOff}, powerState: ${powerState}]")
	}
}




//	===== Capability Switch =====




def on() {
	logInfo("on: [frameTv: ${getDataValue("frameTv")}]")
//	unschedule("onPoll")
//	runIn(60, setOnPollInterval)
	def wolMac = getDataValue("alternateWolMac")
	def cmd = "FFFFFFFFFFFF$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac"
	wol = new hubitat.device.HubAction(
		cmd,
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "255.255.255.255:7",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING])
	sendHubCommand(wol)
//	added
	runIn(5, onPoll)
//	sendEvent(name: "switch", value: "on")
//	runIn(2, getArtModeStatus)
//	runIn(5, setPowerOnMode)
}




def setPowerOnMode() {
	logInfo("setPowerOnMode: [tvPwrOnMode: ${tvPwrOnMode}]")
	if(tvPwrOnMode == "ART_MODE") {
		getArtModeStatus()
		pauseExecution(1000)
		artMode()
	} else if (tvPwrOnMode == "Ambient") {
		ambientMode()
	}
	refresh()
}




def off() {
	logInfo("off: [frameTv: ${getDataValue("frameTv")}]")
//	unschedule("onPoll")
//	runIn(60, setOnPollInterval)
	if (getDataValue("frameTv") == "true") {
		sendKey("POWER", "Press")
		pauseExecution(4000)
		sendKey("POWER", "Release")
	} else {
		sendKey("POWER")
	}
//	added
	runIn(5, onPoll)
//	sendEvent(name: "switch", value: "off")
}




//	===== SMART THINGS INTERFACE =====
//	ST Device Setup
def deviceSetup() {
	if (!stDeviceId || stDeviceId.trim() == "") {
		respData = "[status: FAILED, data: no stDeviceId]"
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]")
	} else {
		def sendData = [
			path: "/devices/${stDeviceId.trim()}/status",
			parse: "distResp"
			]
		asyncGet(sendData, "deviceSetup")
	}
}

def getDeviceList() {
	def sendData = [
		path: "/devices",
		parse: "getDeviceListParse"
		]
	asyncGet(sendData)
}

def getDeviceListParse(resp, data) {
	def respData
	if (resp.status != 200) {
		respData = [status: "ERROR",
					httpCode: resp.status,
					errorMsg: resp.errorMessage]
	} else {
		try {
			respData = new JsonSlurper().parseText(resp.data)
		} catch (err) {
			respData = [status: "ERROR",
						errorMsg: err,
						respData: resp.data]
		}
	}
	if (respData.status == "ERROR") {
		logWarn("getDeviceListParse: ${respData}")
	} else {
		log.info ""
		respData.items.each {
			log.trace "${it.label}:   ${it.deviceId}"
		}
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>"
	}
}

def deviceSetupParse(mainData) {
	def setupData = [:]
	def supportedInputs =  mainData.mediaInputSource.supportedInputSources.value
	state.supportedInputs = supportedInputs
	setupData << [supportedInputs: supportedInputs]
	
	def pictureModes = mainData["custom.picturemode"].supportedPictureModes.value
	state.pictureModes = pictureModes
	setupData << [pictureModes: pictureModes]
	
	def soundModes =  mainData["custom.soundmode"].supportedSoundModes.value
	state.soundModes = soundModes
	setupData << [soundModes: soundModes]
	
	logInfo("deviceSetupParse: ${setupData}")
}

//	== ST Commands
def deviceRefresh() { refresh() }

def refresh() {
	if (connectST && stApiKey!= null) {
		def cmdData = [
			component: "main",
			capability: "refresh",
			command: "refresh",
			arguments: []]
		deviceCommand(cmdData)
	}
}

def poll() {
	if (!stDeviceId || stDeviceId.trim() == "") {
		respData = "[status: FAILED, data: no stDeviceId]"
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]")
	} else {
		def sendData = [
			path: "/devices/${stDeviceId.trim()}/status",
			parse: "distResp"
			]
		asyncGet(sendData, "statusParse")
	}
}

def setLevel(level) { setVolume(level) }

def setVolume(volume) {
	def cmdData = [
		component: "main",
		capability: "audioVolume",
		command: "setVolume",
		arguments: [volume.toInteger()]]
	deviceCommand(cmdData)
}

def togglePictureMode() {
	//	requires state.pictureModes
	def pictureModes = state.pictureModes
	def totalModes = pictureModes.size()
	def currentMode = device.currentValue("pictureMode")
	def modeNo = pictureModes.indexOf(currentMode)
	def newModeNo = modeNo + 1
	if (newModeNo == totalModes) { newModeNo = 0 }
	def newPictureMode = pictureModes[newModeNo]
	setPictureMode(newPictureMode)
}

def setPictureMode(pictureMode) {
	def cmdData = [
		component: "main",
		capability: "custom.picturemode",
		command: "setPictureMode",
		arguments: [pictureMode]]
	deviceCommand(cmdData)
}

def toggleSoundMode() {
	def soundModes = state.soundModes
	def totalModes = soundModes.size()
	def currentMode = device.currentValue("soundMode")
	def modeNo = soundModes.indexOf(currentMode)
	def newModeNo = modeNo + 1
	if (newModeNo == totalModes) { newModeNo = 0 }
	def soundMode = soundModes[newModeNo]
	setSoundMode(soundMode)
}

def setSoundMode(soundMode) { 
	def cmdData = [
		component: "main",
		capability: "custom.soundmode",
		command: "setSoundMode",
		arguments: [soundMode]]
	deviceCommand(cmdData)
}

def toggleInputSource() {
	def inputSources = state.supportedInputs
	def totalSources = inputSources.size()
	def currentSource = device.currentValue("mediaInputSource")
	def sourceNo = inputSources.indexOf(currentSource)
	def newSourceNo = sourceNo + 1
	if (newSourceNo == totalSources) { newSourceNo = 0 }
	def inputSource = inputSources[newSourceNo]
	setInputSource(inputSource)
}

def setInputSource(inputSource) {
	def cmdData = [
		component: "main",
		capability: "mediaInputSource",
		command: "setInputSource",
		arguments: [inputSource]]
	deviceCommand(cmdData)
}

def setTvChannel(newChannel) {
	def cmdData = [
		component: "main",
		capability: "tvChannel",
		command: "setTvChannel",
		arguments: [newChannel]]
	deviceCommand(cmdData)
}

def deviceCommand(cmdData) {
	logTrace("deviceCommand: $cmdData")
	def respData = [:]
	if (!stDeviceId || stDeviceId.trim() == "") {
		respData << [status: "FAILED", data: "no stDeviceId"]
	} else {
		def sendData = [
			path: "/devices/${stDeviceId.trim()}/commands",
			cmdData: cmdData
		]
		respData = syncPost(sendData)
	}
	if (respData.status == "OK") {
		if (respData.results[0].status == "COMPLETED") {
			if (cmdData.capability && cmdData.capability != "refresh") {
				refresh()
			} else {
				poll()
			}
		}
	}else {
		logWarn("deviceCommand: [status: ${respData.status}, data: ${respData}]")
	}
}




def statusParse(mainData) {
	if (stTestData) {
		device.updateSetting("stTestData", [type:"bool", value: false])
		log.warn mainData
	}
	def stData = [:]
	if (logEnable || traceLog) {
		def quickLog = [:]
		try {
			quickLog << [
				switch: [device.currentValue("switch"), mainData.switch.switch.value],
				volume: [device.currentValue("volume"), mainData.audioVolume.volume.value.toInteger()],
				mute: [device.currentValue("mute"), mainData.audioMute.mute.value],
				input: [device.currentValue("inputSource"), mainData.mediaInputSource.inputSource.value],
				channel: [device.currentValue("tvChannel"), mainData.tvChannel.tvChannel.value.toString()],
				channelName: [device.currentValue("tvChannelName"), mainData.tvChannel.tvChannelName.value],
				pictureMode: [device.currentValue("pictureMode"), mainData["custom.picturemode"].pictureMode.value],
				soundMode: [device.currentValue("soundMode"), mainData["custom.soundmode"].soundMode.value],
				transportStatus: [device.currentValue("transportStatus"), mainData.mediaPlayback.playbackStatus.value]]
		} catch (err) {
			quickLog << [error: ${err}, data: mainData]
		}
		logDebug("statusParse: [quickLog: ${quickLog}]")
		logTrace("statusParse: [quickLog: ${quickLog}]")
	}

	if (device.currentValue("switch") == "on") {
		Integer volume = mainData.audioVolume.volume.value.toInteger()
		if (device.currentValue("volume") != volume) {
			sendEvent(name: "volume", value: volume)
			sendEvent(name: "level", value: volume)
			stData << [volume: volume]
		}

		String mute = mainData.audioMute.mute.value
		if (device.currentValue("mute") != mute) {
			sendEvent(name: "mute", value: mute)
			stData << [mute: mute]
		}

		String inputSource = mainData.mediaInputSource.inputSource.value
		if (device.currentValue("inputSource") != inputSource) {
			sendEvent(name: "inputSource", value: inputSource)		
			stData << [inputSource: inputSource]
		}

		String tvChannel = mainData.tvChannel.tvChannel.value.toString()
		if (tvChannel == "" || tvChannel == null) {
			tvChannel = " "
		}
		String tvChannelName = mainData.tvChannel.tvChannelName.value
		if (tvChannelName == "") {
			tvChannelName = " "
		}
		if (device.currentValue("tvChannelName") != tvChannelName) {
			sendEvent(name: "tvChannel", value: tvChannel)
			sendEvent(name: "tvChannelName", value: tvChannelName)
			if (tvChannelName.contains(".")) {
				getAppData(tvChannelName)
			} else {
				sendEvent(name: "currentApp", value: " ")
			}
			stData << [tvChannel: tvChannel, tvChannelName: tvChannelName]
			if (getDataValue("frameTv") == "true" && !state.artModeWs) {
				String artMode = "off"
				if (tvChannelName == "art") { artMode = "on" }
				sendEvent(name: "artModeStatus", value: artMode)
			}
		}

		String trackDesc = inputSource
		if (tvChannelName != " ") { trackDesc = tvChannelName }
		if (device.currentValue("trackDescription") != trackDesc) {
			sendEvent(name: "trackDescription", value:trackDesc)
			stData << [trackDescription: trackDesc]
		}

		String pictureMode = mainData["custom.picturemode"].pictureMode.value
		if (device.currentValue("pictureMode") != pictureMode) {
			sendEvent(name: "pictureMode",value: pictureMode)
			stData << [pictureMode: pictureMode]
		}

		String soundMode = mainData["custom.soundmode"].soundMode.value
		if (device.currentValue("soundMode") != soundMode) {
			sendEvent(name: "soundMode",value: soundMode)
			stData << [soundMode: soundMode]
		}

		String transportStatus = mainData.mediaPlayback.playbackStatus.value
		if (transportStatus == null || transportStatus == "") {
			transportStatus = "n/a"
		}
		if (device.currentValue("transportStatus") != transportStatus) {
			sendEvent(name: "transportStatus", value: transportStatus)
			stData << [transportStatus: transportStatus]
		}
	}
	
	if (stData != [:]) {
		logInfo("statusParse: ${stData}")
	}
}




//	== ST Communications
private asyncGet(sendData, passData = "none") {
	if (!stApiKey || stApiKey.trim() == "") {
		logWarn("asyncGet: [status: ERROR, errorMsg: no stApiKey]")
	} else {
		logDebug("asyncGet: ${sendData}, ${passData}")
		def sendCmdParams = [
			uri: "https://api.smartthings.com/v1",
			path: sendData.path,
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()]]
		try {
			asynchttpGet(sendData.parse, sendCmdParams, [reason: passData])
		} catch (error) {
			logWarn("asyncGet: [status: FAILED, errorMsg: ${error}]")
		}
	}
}

private syncGet(path){
	def respData = [:]
	if (!stApiKey || stApiKey.trim() == "") {
		respData << [status: "FAILED",
					 errorMsg: "No stApiKey"]
	} else {
		logDebug("syncGet: ${sendData}")
		def sendCmdParams = [
			uri: "https://api.smartthings.com/v1",
			path: path,
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()]
		]
		try {
			httpGet(sendCmdParams) {resp ->
				if (resp.status == 200 && resp.data != null) {
					respData << [status: "OK", results: resp.data]
				} else {
					respData << [status: "FAILED",
								 httpCode: resp.status,
								 errorMsg: resp.errorMessage]
				}
			}
		} catch (error) {
			respData << [status: "FAILED",
						 errorMsg: error]
		}
	}
	return respData
}

private syncPost(sendData){
	def respData = [:]
	if (!stApiKey || stApiKey.trim() == "") {
		respData << [status: "FAILED",
					 errorMsg: "No stApiKey"]
	} else {
		logDebug("syncPost: ${sendData}")
		def cmdBody = [commands: [sendData.cmdData]]
		def sendCmdParams = [
			uri: "https://api.smartthings.com/v1",
			path: sendData.path,
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()],
			body : new groovy.json.JsonBuilder(cmdBody).toString()
		]
		try {
			httpPost(sendCmdParams) {resp ->
				if (resp.status == 200 && resp.data != null) {
					respData << [status: "OK", results: resp.data.results]
				} else {
					respData << [status: "FAILED",
								 httpCode: resp.status,
								 errorMsg: resp.errorMessage]
				}
			}
		} catch (error) {
			respData << [status: "FAILED",
						 errorMsg: error]
		}
	}
	return respData
}

def distResp(resp, data) {
	def respLog = [:]
	if (resp.status == 200) {
		try {
			def respData = new JsonSlurper().parseText(resp.data)
			if (data.reason == "deviceSetup") {
				deviceSetupParse(respData.components.main)
				runIn(1, statusParse, [data: respData.components.main])
			} else {
				statusParse(respData.components.main)
			}
		} catch (err) {
			respLog << [status: "ERROR",
						errorMsg: err,
						respData: resp.data]
		}
	} else {
		respLog << [status: "ERROR",
					httpCode: resp.status,
					errorMsg: resp.errorMessage]
	}
	if (respLog != [:]) {
		logWarn("distResp: ${respLog}")
	}
}

//	===== BUTTON INTERFACE =====
def setVariable(appName) {
	sendEvent(name: "variable", value: appName)
	appOpenByName(appName)
}

def push(pushed) {
	logDebug("push: button = ${pushed}, trigger = ${state.triggered}")
	if (pushed == null) {
		logWarn("push: pushed is null.  Input ignored")
		return
	}
	pushed = pushed.toInteger()
	switch(pushed) {
		//	===== Physical Remote Commands =====
		case 2 : mute(); break
		case 3 : numericKeyPad(); break
		case 4 : Return(); break
		case 6 : artMode(); break
		case 7 : ambientMode(); break
		case 45: ambientmodeExit(); break
		case 8 : arrowLeft(); break
		case 9 : arrowRight(); break
		case 10: arrowUp(); break
		case 11: arrowDown(); break
		case 12: enter(); break
		case 13: exit(); break
		case 14: home(); break
		case 18: channelUp(); break
		case 19: channelDown(); break
		case 20: guide(); break
		case 21: volumeUp(); break
		case 22: volumeDown(); break
		//	===== Direct Access Functions
		case 23: menu(); break
		case 24: source(); break
		case 25: info(); break
		case 26: channelList(); break
		//	===== Other Commands =====
		case 34: previousChannel(); break
		case 35: hdmi(); break
		case 36: fastBack(); break
		case 37: fastForward(); break
		//	===== Application Interface =====
		case 38: appOpenByName("Browser"); break
		case 39: appOpenByName("YouTube"); break
		case 40: appOpenByName("RunNetflix"); break
		case 41: close()
		case 42: toggleSoundMode(); break
		case 43: togglePictureMode(); break
		case 44: appOpenByName(device.currentValue("variable")); break
		default:
			logDebug("push: Invalid Button Number!")
			break
	}
}

def parse(resp) {
	if (resp.toString().contains("mac:")) {
		upnpParse(resp)
	} else {
		parseWs(resp)
	}
}

//	===== Libraries =====




// ~~~~~ start include (1245) davegut.samsungTvWebsocket ~~~~~
library ( // library marker davegut.samsungTvWebsocket, line 1
	name: "samsungTvWebsocket", // library marker davegut.samsungTvWebsocket, line 2
	namespace: "davegut", // library marker davegut.samsungTvWebsocket, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTvWebsocket, line 4
	description: "Common Samsung TV Websocket Commands", // library marker davegut.samsungTvWebsocket, line 5
	category: "utilities", // library marker davegut.samsungTvWebsocket, line 6
	documentationLink: "" // library marker davegut.samsungTvWebsocket, line 7
) // library marker davegut.samsungTvWebsocket, line 8

import groovy.json.JsonOutput // library marker davegut.samsungTvWebsocket, line 10

//	== ART/Ambient Mode // library marker davegut.samsungTvWebsocket, line 12
def artMode() { // library marker davegut.samsungTvWebsocket, line 13
	def artModeStatus = device.currentValue("artModeStatus") // library marker davegut.samsungTvWebsocket, line 14
	def logData = [artModeStatus: artModeStatus, artModeWs: state.artModeWs] // library marker davegut.samsungTvWebsocket, line 15
	if (getDataValue("frameTv") != "true") { // library marker davegut.samsungTvWebsocket, line 16
		logData << [status: "Not a Frame TV"] // library marker davegut.samsungTvWebsocket, line 17
	} else if (artModeStatus == "on") { // library marker davegut.samsungTvWebsocket, line 18
		logData << [status: "artMode already set"] // library marker davegut.samsungTvWebsocket, line 19
	} else { // library marker davegut.samsungTvWebsocket, line 20
		if (state.artModeWs) { // library marker davegut.samsungTvWebsocket, line 21
			def data = [value:"on", // library marker davegut.samsungTvWebsocket, line 22
						request:"set_artmode_status", // library marker davegut.samsungTvWebsocket, line 23
						id: "${getDataValue("uuid")}"] // library marker davegut.samsungTvWebsocket, line 24
			data = JsonOutput.toJson(data) // library marker davegut.samsungTvWebsocket, line 25
			artModeCmd(data) // library marker davegut.samsungTvWebsocket, line 26
			logData << [status: "Sending artMode WS Command"] // library marker davegut.samsungTvWebsocket, line 27
		} else { // library marker davegut.samsungTvWebsocket, line 28
			sendKey("POWER") // library marker davegut.samsungTvWebsocket, line 29
			logData << [status: "Sending Power WS Command"] // library marker davegut.samsungTvWebsocket, line 30
			if (artModeStatus == "none") { // library marker davegut.samsungTvWebsocket, line 31
				logData << [NOTE: "SENT BLIND. Enable SmartThings interface!"] // library marker davegut.samsungTvWebsocket, line 32
			} // library marker davegut.samsungTvWebsocket, line 33
		} // library marker davegut.samsungTvWebsocket, line 34
		runIn(10, getArtModeStatus) // library marker davegut.samsungTvWebsocket, line 35
	} // library marker davegut.samsungTvWebsocket, line 36
	logInfo("artMode: ${logData}") // library marker davegut.samsungTvWebsocket, line 37
} // library marker davegut.samsungTvWebsocket, line 38

def getArtModeStatus() { // library marker davegut.samsungTvWebsocket, line 40
	if (getDataValue("frameTv") == "true") { // library marker davegut.samsungTvWebsocket, line 41
		if (state.artModeWs) { // library marker davegut.samsungTvWebsocket, line 42
			def data = [request:"get_artmode_status", // library marker davegut.samsungTvWebsocket, line 43
						id: "${getDataValue("uuid")}"] // library marker davegut.samsungTvWebsocket, line 44
			data = JsonOutput.toJson(data) // library marker davegut.samsungTvWebsocket, line 45
			artModeCmd(data) // library marker davegut.samsungTvWebsocket, line 46
		} else { // library marker davegut.samsungTvWebsocket, line 47
			refresh() // library marker davegut.samsungTvWebsocket, line 48
		} // library marker davegut.samsungTvWebsocket, line 49
	} // library marker davegut.samsungTvWebsocket, line 50
} // library marker davegut.samsungTvWebsocket, line 51

def artModeCmd(data) { // library marker davegut.samsungTvWebsocket, line 53
	def cmdData = [method:"ms.channel.emit", // library marker davegut.samsungTvWebsocket, line 54
				   params:[data:"${data}", // library marker davegut.samsungTvWebsocket, line 55
						   to:"host", // library marker davegut.samsungTvWebsocket, line 56
						   event:"art_app_request"]] // library marker davegut.samsungTvWebsocket, line 57
	cmdData = JsonOutput.toJson(cmdData) // library marker davegut.samsungTvWebsocket, line 58
	sendMessage("frameArt", cmdData) // library marker davegut.samsungTvWebsocket, line 59
} // library marker davegut.samsungTvWebsocket, line 60

def ambientMode() { // library marker davegut.samsungTvWebsocket, line 62
	sendKey("AMBIENT") // library marker davegut.samsungTvWebsocket, line 63
	runIn(10, refresh) // library marker davegut.samsungTvWebsocket, line 64
} // library marker davegut.samsungTvWebsocket, line 65

//	== Remote Commands // library marker davegut.samsungTvWebsocket, line 67
def mute() { // library marker davegut.samsungTvWebsocket, line 68
	sendKey("MUTE") // library marker davegut.samsungTvWebsocket, line 69
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 70
} // library marker davegut.samsungTvWebsocket, line 71

def unmute() { // library marker davegut.samsungTvWebsocket, line 73
	sendKey("MUTE") // library marker davegut.samsungTvWebsocket, line 74
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 75
} // library marker davegut.samsungTvWebsocket, line 76

def volumeUp() {  // library marker davegut.samsungTvWebsocket, line 78
	sendKey("VOLUP")  // library marker davegut.samsungTvWebsocket, line 79
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 80
} // library marker davegut.samsungTvWebsocket, line 81

def volumeDown() {  // library marker davegut.samsungTvWebsocket, line 83
	sendKey("VOLDOWN") // library marker davegut.samsungTvWebsocket, line 84
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 85
} // library marker davegut.samsungTvWebsocket, line 86

def play() { // library marker davegut.samsungTvWebsocket, line 88
	sendKey("PLAY") // library marker davegut.samsungTvWebsocket, line 89
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 90
} // library marker davegut.samsungTvWebsocket, line 91

def pause() { // library marker davegut.samsungTvWebsocket, line 93
	sendKey("PAUSE") // library marker davegut.samsungTvWebsocket, line 94
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 95
} // library marker davegut.samsungTvWebsocket, line 96

def stop() { // library marker davegut.samsungTvWebsocket, line 98
	sendKey("STOP") // library marker davegut.samsungTvWebsocket, line 99
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 100
} // library marker davegut.samsungTvWebsocket, line 101

def exit() { // library marker davegut.samsungTvWebsocket, line 103
	sendKey("EXIT") // library marker davegut.samsungTvWebsocket, line 104
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 105
} // library marker davegut.samsungTvWebsocket, line 106

def Return() { sendKey("RETURN") } // library marker davegut.samsungTvWebsocket, line 108

def fastBack() { // library marker davegut.samsungTvWebsocket, line 110
	sendKey("LEFT", "Press") // library marker davegut.samsungTvWebsocket, line 111
	pauseExecution(1000) // library marker davegut.samsungTvWebsocket, line 112
	sendKey("LEFT", "Release") // library marker davegut.samsungTvWebsocket, line 113
} // library marker davegut.samsungTvWebsocket, line 114

def fastForward() { // library marker davegut.samsungTvWebsocket, line 116
	sendKey("RIGHT", "Press") // library marker davegut.samsungTvWebsocket, line 117
	pauseExecution(1000) // library marker davegut.samsungTvWebsocket, line 118
	sendKey("RIGHT", "Release") // library marker davegut.samsungTvWebsocket, line 119
} // library marker davegut.samsungTvWebsocket, line 120

def arrowLeft() { sendKey("LEFT") } // library marker davegut.samsungTvWebsocket, line 122

def arrowRight() { sendKey("RIGHT") } // library marker davegut.samsungTvWebsocket, line 124

def arrowUp() { sendKey("UP") } // library marker davegut.samsungTvWebsocket, line 126

def arrowDown() { sendKey("DOWN") } // library marker davegut.samsungTvWebsocket, line 128

def enter() { sendKey("ENTER") } // library marker davegut.samsungTvWebsocket, line 130

def numericKeyPad() { sendKey("MORE") } // library marker davegut.samsungTvWebsocket, line 132

def home() { sendKey("HOME") } // library marker davegut.samsungTvWebsocket, line 134

def menu() { sendKey("MENU") } // library marker davegut.samsungTvWebsocket, line 136

def guide() { sendKey("GUIDE") } // library marker davegut.samsungTvWebsocket, line 138

def info() { sendKey("INFO") } // library marker davegut.samsungTvWebsocket, line 140

def source() {  // library marker davegut.samsungTvWebsocket, line 142
	sendKey("SOURCE") // library marker davegut.samsungTvWebsocket, line 143
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 144
} // library marker davegut.samsungTvWebsocket, line 145

def hdmi() { // library marker davegut.samsungTvWebsocket, line 147
	sendKey("HDMI") // library marker davegut.samsungTvWebsocket, line 148
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 149
} // library marker davegut.samsungTvWebsocket, line 150

def channelList() { sendKey("CH_LIST") } // library marker davegut.samsungTvWebsocket, line 152

def channelUp() {  // library marker davegut.samsungTvWebsocket, line 154
	sendKey("CHUP")  // library marker davegut.samsungTvWebsocket, line 155
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 156
} // library marker davegut.samsungTvWebsocket, line 157

def nextTrack() { channelUp() } // library marker davegut.samsungTvWebsocket, line 159

def channelDown() {  // library marker davegut.samsungTvWebsocket, line 161
	sendKey("CHDOWN")  // library marker davegut.samsungTvWebsocket, line 162
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 163
} // library marker davegut.samsungTvWebsocket, line 164

def previousTrack() { channelDown() } // library marker davegut.samsungTvWebsocket, line 166

def previousChannel() {  // library marker davegut.samsungTvWebsocket, line 168
	sendKey("PRECH")  // library marker davegut.samsungTvWebsocket, line 169
	runIn(5, deviceRefresh) // library marker davegut.samsungTvWebsocket, line 170
} // library marker davegut.samsungTvWebsocket, line 171

def showMessage() { logWarn("showMessage: not implemented") } // library marker davegut.samsungTvWebsocket, line 173

//	== WebSocket Communications / Parse // library marker davegut.samsungTvWebsocket, line 175
def sendKey(key, cmd = "Click") { // library marker davegut.samsungTvWebsocket, line 176
	key = "KEY_${key.toUpperCase()}" // library marker davegut.samsungTvWebsocket, line 177
	def data = [method:"ms.remote.control", // library marker davegut.samsungTvWebsocket, line 178
				params:[Cmd:"${cmd}", // library marker davegut.samsungTvWebsocket, line 179
						DataOfCmd:"${key}", // library marker davegut.samsungTvWebsocket, line 180
						TypeOfRemote:"SendRemoteKey"]] // library marker davegut.samsungTvWebsocket, line 181
	sendMessage("remote", JsonOutput.toJson(data) ) // library marker davegut.samsungTvWebsocket, line 182
} // library marker davegut.samsungTvWebsocket, line 183

def sendMessage(funct, data) { // library marker davegut.samsungTvWebsocket, line 185
	def wsStat = device.currentValue("wsStatus") // library marker davegut.samsungTvWebsocket, line 186
	logDebug("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${state.currentFunction}") // library marker davegut.samsungTvWebsocket, line 187
	logTrace("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${state.currentFunction}") // library marker davegut.samsungTvWebsocket, line 188
	if (wsStat != "open" || state.currentFunction != funct) { // library marker davegut.samsungTvWebsocket, line 189
		connect(funct) // library marker davegut.samsungTvWebsocket, line 190
		pauseExecution(600) // library marker davegut.samsungTvWebsocket, line 191
	} // library marker davegut.samsungTvWebsocket, line 192
	interfaces.webSocket.sendMessage(data) // library marker davegut.samsungTvWebsocket, line 193
	runIn(60, close) // library marker davegut.samsungTvWebsocket, line 194
} // library marker davegut.samsungTvWebsocket, line 195

def connect(funct) { // library marker davegut.samsungTvWebsocket, line 197
	logDebug("connect: function = ${funct}") // library marker davegut.samsungTvWebsocket, line 198
	def url // library marker davegut.samsungTvWebsocket, line 199
	def name = "SHViaXRhdCBTYW1zdW5nIFJlbW90ZQ==" // library marker davegut.samsungTvWebsocket, line 200
	if (getDataValue("tokenSupport") == "true") { // library marker davegut.samsungTvWebsocket, line 201
		if (funct == "remote") { // library marker davegut.samsungTvWebsocket, line 202
			url = "wss://${deviceIp}:8002/api/v2/channels/samsung.remote.control?name=${name}&token=${state.token}" // library marker davegut.samsungTvWebsocket, line 203
		} else if (funct == "frameArt") { // library marker davegut.samsungTvWebsocket, line 204
			url = "wss://${deviceIp}:8002/api/v2/channels/com.samsung.art-app?name=${name}&token=${state.token}" // library marker davegut.samsungTvWebsocket, line 205
		} else { // library marker davegut.samsungTvWebsocket, line 206
			logWarn("sendMessage: Invalid Function = ${funct}, tokenSupport = true") // library marker davegut.samsungTvWebsocket, line 207
		} // library marker davegut.samsungTvWebsocket, line 208
	} else { // library marker davegut.samsungTvWebsocket, line 209
		if (funct == "remote") { // library marker davegut.samsungTvWebsocket, line 210
			url = "ws://${deviceIp}:8001/api/v2/channels/samsung.remote.control?name=${name}" // library marker davegut.samsungTvWebsocket, line 211
		} else if (funct == "frameArt") { // library marker davegut.samsungTvWebsocket, line 212
			url = "ws://${deviceIp}:8001/api/v2/channels/com.samsung.art-app?name=${name}" // library marker davegut.samsungTvWebsocket, line 213
		} else { // library marker davegut.samsungTvWebsocket, line 214
			logWarn("sendMessage: Invalid Function = ${funct}, tokenSupport = false") // library marker davegut.samsungTvWebsocket, line 215
		} // library marker davegut.samsungTvWebsocket, line 216
	} // library marker davegut.samsungTvWebsocket, line 217
	state.currentFunction = funct // library marker davegut.samsungTvWebsocket, line 218
	interfaces.webSocket.connect(url, ignoreSSLIssues: true) // library marker davegut.samsungTvWebsocket, line 219
} // library marker davegut.samsungTvWebsocket, line 220

def close() { // library marker davegut.samsungTvWebsocket, line 222
	logDebug("close") // library marker davegut.samsungTvWebsocket, line 223
	interfaces.webSocket.close() // library marker davegut.samsungTvWebsocket, line 224
	sendEvent(name: "wsStatus", value: "closed") // library marker davegut.samsungTvWebsocket, line 225
} // library marker davegut.samsungTvWebsocket, line 226

def webSocketStatus(message) { // library marker davegut.samsungTvWebsocket, line 228
	def status // library marker davegut.samsungTvWebsocket, line 229
	if (message == "status: open") { // library marker davegut.samsungTvWebsocket, line 230
		status = "open" // library marker davegut.samsungTvWebsocket, line 231
	} else if (message == "status: closing") { // library marker davegut.samsungTvWebsocket, line 232
		status = "closed" // library marker davegut.samsungTvWebsocket, line 233
		state.currentFunction = "close" // library marker davegut.samsungTvWebsocket, line 234
	} else if (message.substring(0,7) == "failure") { // library marker davegut.samsungTvWebsocket, line 235
		status = "closed-failure" // library marker davegut.samsungTvWebsocket, line 236
		state.currentFunction = "close" // library marker davegut.samsungTvWebsocket, line 237
		close() // library marker davegut.samsungTvWebsocket, line 238
	} // library marker davegut.samsungTvWebsocket, line 239
	sendEvent(name: "wsStatus", value: status) // library marker davegut.samsungTvWebsocket, line 240
	logDebug("webSocketStatus: [status: ${status}, message: ${message}]") // library marker davegut.samsungTvWebsocket, line 241
} // library marker davegut.samsungTvWebsocket, line 242

def parseWs(resp) { // library marker davegut.samsungTvWebsocket, line 244
	def logData = [:] // library marker davegut.samsungTvWebsocket, line 245
	try { // library marker davegut.samsungTvWebsocket, line 246
		resp = parseJson(resp) // library marker davegut.samsungTvWebsocket, line 247
		def event = resp.event // library marker davegut.samsungTvWebsocket, line 248
		logData << [EVENT: event] // library marker davegut.samsungTvWebsocket, line 249
		switch(event) { // library marker davegut.samsungTvWebsocket, line 250
			case "ms.channel.connect": // library marker davegut.samsungTvWebsocket, line 251
				def newToken = resp.data.token // library marker davegut.samsungTvWebsocket, line 252
				if (newToken != null && newToken != state.token) { // library marker davegut.samsungTvWebsocket, line 253
					state.token = newToken // library marker davegut.samsungTvWebsocket, line 254
					logData << [TOKEN: "updated"] // library marker davegut.samsungTvWebsocket, line 255
				} else { // library marker davegut.samsungTvWebsocket, line 256
					logData << [TOKEN: "noChange"] // library marker davegut.samsungTvWebsocket, line 257
				} // library marker davegut.samsungTvWebsocket, line 258
				break // library marker davegut.samsungTvWebsocket, line 259
			case "d2d_service_message": // library marker davegut.samsungTvWebsocket, line 260
				def data = parseJson(resp.data) // library marker davegut.samsungTvWebsocket, line 261
				if (data.event == "artmode_status" || // library marker davegut.samsungTvWebsocket, line 262
					data.event == "art_mode_changed") { // library marker davegut.samsungTvWebsocket, line 263
					def status = data.value // library marker davegut.samsungTvWebsocket, line 264
					if (status == null) { status = data.status } // library marker davegut.samsungTvWebsocket, line 265
					sendEvent(name: "artModeStatus", value: status) // library marker davegut.samsungTvWebsocket, line 266
					logData << [artModeStatus: status] // library marker davegut.samsungTvWebsocket, line 267
					state.artModeWs = true // library marker davegut.samsungTvWebsocket, line 268
				} // library marker davegut.samsungTvWebsocket, line 269
				break // library marker davegut.samsungTvWebsocket, line 270
			case "ms.error": // library marker davegut.samsungTvWebsocket, line 271
				logData << [STATUS: "Error, Closing WS",DATA: resp.data] // library marker davegut.samsungTvWebsocket, line 272
				close() // library marker davegut.samsungTvWebsocket, line 273
				break // library marker davegut.samsungTvWebsocket, line 274
			case "ms.channel.ready": // library marker davegut.samsungTvWebsocket, line 275
			case "ms.channel.clientConnect": // library marker davegut.samsungTvWebsocket, line 276
			case "ms.channel.clientDisconnect": // library marker davegut.samsungTvWebsocket, line 277
			case "ms.remote.touchEnable": // library marker davegut.samsungTvWebsocket, line 278
			case "ms.remote.touchDisable": // library marker davegut.samsungTvWebsocket, line 279
				break // library marker davegut.samsungTvWebsocket, line 280
			default: // library marker davegut.samsungTvWebsocket, line 281
				logData << [STATUS: "Not Parsed", DATA: resp.data] // library marker davegut.samsungTvWebsocket, line 282
				break // library marker davegut.samsungTvWebsocket, line 283
		} // library marker davegut.samsungTvWebsocket, line 284
		logDebug("parse: ${logData}") // library marker davegut.samsungTvWebsocket, line 285
	} catch (e) { // library marker davegut.samsungTvWebsocket, line 286
		logData << [STATUS: "unhandled", ERROR: e] // library marker davegut.samsungTvWebsocket, line 287
		logWarn("parse: ${logData}") // library marker davegut.samsungTvWebsocket, line 288
	} // library marker davegut.samsungTvWebsocket, line 289
} // library marker davegut.samsungTvWebsocket, line 290

// ~~~~~ end include (1245) davegut.samsungTvWebsocket ~~~~~

// ~~~~~ start include (1244) davegut.samsungTvApps ~~~~~
library ( // library marker davegut.samsungTvApps, line 1
	name: "samsungTvApps", // library marker davegut.samsungTvApps, line 2
	namespace: "davegut", // library marker davegut.samsungTvApps, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTvApps, line 4
	description: "Samsung TV Applications", // library marker davegut.samsungTvApps, line 5
	category: "utilities", // library marker davegut.samsungTvApps, line 6
	documentationLink: "" // library marker davegut.samsungTvApps, line 7
) // library marker davegut.samsungTvApps, line 8

import groovy.json.JsonSlurper // library marker davegut.samsungTvApps, line 10

def appOpenByName(appName) { // library marker davegut.samsungTvApps, line 12
	def thisApp = findThisApp(appName) // library marker davegut.samsungTvApps, line 13
	def logData = [appName: thisApp[0], appId: thisApp[1]] // library marker davegut.samsungTvApps, line 14
	if (thisApp[1] != "none") { // library marker davegut.samsungTvApps, line 15
		[status: "execute appOpenByCode"] // library marker davegut.samsungTvApps, line 16
		appOpenByCode(thisApp[1]) // library marker davegut.samsungTvApps, line 17
	} else { // library marker davegut.samsungTvApps, line 18
		def url = "http://${deviceIp}:8080/ws/apps/${appName}" // library marker davegut.samsungTvApps, line 19
		try { // library marker davegut.samsungTvApps, line 20
			httpPost(url, "") { resp -> // library marker davegut.samsungTvApps, line 21
				sendEvent(name: "currentApp", value: respData.name) // library marker davegut.samsungTvApps, line 22
				logData << [status: "OK", currentApp: respData.name] // library marker davegut.samsungTvApps, line 23
			} // library marker davegut.samsungTvApps, line 24
			runIn(5, refresh) // library marker davegut.samsungTvApps, line 25
		} catch (err) { // library marker davegut.samsungTvApps, line 26
			logData << [status: "appName Not Found", data: err] // library marker davegut.samsungTvApps, line 27
			logWarn("appOpenByName: ${logData}") // library marker davegut.samsungTvApps, line 28
		} // library marker davegut.samsungTvApps, line 29
	} // library marker davegut.samsungTvApps, line 30
	logDebug("appOpenByName: ${logData}") // library marker davegut.samsungTvApps, line 31
} // library marker davegut.samsungTvApps, line 32

def appOpenByCode(appId) { // library marker davegut.samsungTvApps, line 34
	def appName = state.appData.find { it.value == appId } // library marker davegut.samsungTvApps, line 35
	if (appName != null) { // library marker davegut.samsungTvApps, line 36
		appName = appName.key // library marker davegut.samsungTvApps, line 37
	} // library marker davegut.samsungTvApps, line 38
	def logData = [appId: appId, appName: appName] // library marker davegut.samsungTvApps, line 39
	def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}" // library marker davegut.samsungTvApps, line 40
	try { // library marker davegut.samsungTvApps, line 41
		httpPost(uri, body) { resp -> // library marker davegut.samsungTvApps, line 42
			if (appName == null) { // library marker davegut.samsungTvApps, line 43
				runIn(3, getAppData, [data: appId]) // library marker davegut.samsungTvApps, line 44
			} else { // library marker davegut.samsungTvApps, line 45
				sendEvent(name: "currentApp", value: appName) // library marker davegut.samsungTvApps, line 46
				logData << [currentApp: appName] // library marker davegut.samsungTvApps, line 47
			} // library marker davegut.samsungTvApps, line 48
			runIn(5, refresh) // library marker davegut.samsungTvApps, line 49
			logData << [status: "OK", data: resp.data] // library marker davegut.samsungTvApps, line 50
		} // library marker davegut.samsungTvApps, line 51
	} catch (err) { // library marker davegut.samsungTvApps, line 52
		logData << [status: "appId Not Found", data: err] // library marker davegut.samsungTvApps, line 53
		logWarn("appOpenByCode: ${logData}") // library marker davegut.samsungTvApps, line 54
	} // library marker davegut.samsungTvApps, line 55
	logDebug("appOpenByCode: ${logData}") // library marker davegut.samsungTvApps, line 56
} // library marker davegut.samsungTvApps, line 57

def appClose() { // library marker davegut.samsungTvApps, line 59
	def appId // library marker davegut.samsungTvApps, line 60
	def appName = device.currentValue("currentApp") // library marker davegut.samsungTvApps, line 61
	if (appName == " " || appName == null) { // library marker davegut.samsungTvApps, line 62
		logWarn("appClose: [status: FAILED, reason: appName not set.]") // library marker davegut.samsungTvApps, line 63
		return // library marker davegut.samsungTvApps, line 64
	} // library marker davegut.samsungTvApps, line 65
	def thisApp = findThisApp(appName) // library marker davegut.samsungTvApps, line 66
	appId = thisApp[1] // library marker davegut.samsungTvApps, line 67
	def logData = [appName: appName, appId: appId] // library marker davegut.samsungTvApps, line 68
	Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${appId}", // library marker davegut.samsungTvApps, line 69
				  timeout: 3] // library marker davegut.samsungTvApps, line 70
	try { // library marker davegut.samsungTvApps, line 71
		asynchttpDelete("appCloseParse", params, [appId: appId]) // library marker davegut.samsungTvApps, line 72
		logData: [status: "OK"] // library marker davegut.samsungTvApps, line 73
		exit() // library marker davegut.samsungTvApps, line 74
	} catch (err) { // library marker davegut.samsungTvApps, line 75
		logData: [status: "FAILED", data: err] // library marker davegut.samsungTvApps, line 76
		logWarn("appClose: ${logData}") // library marker davegut.samsungTvApps, line 77
	} // library marker davegut.samsungTvApps, line 78
	logDebug("appClose: ${logData}") // library marker davegut.samsungTvApps, line 79
} // library marker davegut.samsungTvApps, line 80

def appCloseParse(resp, data) { // library marker davegut.samsungTvApps, line 82
	def logData = [appId: data.appId] // library marker davegut.samsungTvApps, line 83
	if (resp.status == 200) { // library marker davegut.samsungTvApps, line 84
		sendEvent(name: "currentApp", value: " ") // library marker davegut.samsungTvApps, line 85
		logData << [status: "OK"] // library marker davegut.samsungTvApps, line 86
	} else { // library marker davegut.samsungTvApps, line 87
		logData << [status: "FAILED", status: resp.status] // library marker davegut.samsungTvApps, line 88
		logWarn("appCloseParse: ${logData}") // library marker davegut.samsungTvApps, line 89
	} // library marker davegut.samsungTvApps, line 90
	logDebug("appCloseParse: ${logData}") // library marker davegut.samsungTvApps, line 91
} // library marker davegut.samsungTvApps, line 92

def findThisApp(appName) { // library marker davegut.samsungTvApps, line 94
	def thisApp = state.appData.find { it.key.toLowerCase().contains(appName.toLowerCase()) } // library marker davegut.samsungTvApps, line 95
	def appId = "none" // library marker davegut.samsungTvApps, line 96
	if (thisApp != null) { // library marker davegut.samsungTvApps, line 97
		appName = thisApp.key // library marker davegut.samsungTvApps, line 98
		appId = thisApp.value // library marker davegut.samsungTvApps, line 99
	} else { // library marker davegut.samsungTvApps, line 100
		//	Handle special case for browser (using switch to add other cases. // library marker davegut.samsungTvApps, line 101
		switch(appName.toLowerCase()) { // library marker davegut.samsungTvApps, line 102
			case "browser": // library marker davegut.samsungTvApps, line 103
				appId = "org.tizen.browser" // library marker davegut.samsungTvApps, line 104
				appName = "Browser" // library marker davegut.samsungTvApps, line 105
				break // library marker davegut.samsungTvApps, line 106
			case "youtubetv": // library marker davegut.samsungTvApps, line 107
				appId = "PvWgqxV3Xa.YouTubeTV" // library marker davegut.samsungTvApps, line 108
				appName = "YouTube TV" // library marker davegut.samsungTvApps, line 109
				break // library marker davegut.samsungTvApps, line 110
			case "netflix": // library marker davegut.samsungTvApps, line 111
				appId = "3201907018807" // library marker davegut.samsungTvApps, line 112
				appName = "Netflix" // library marker davegut.samsungTvApps, line 113
				break // library marker davegut.samsungTvApps, line 114
			case "youtube": // library marker davegut.samsungTvApps, line 115
				appId = "9Ur5IzDKqV.TizenYouTube" // library marker davegut.samsungTvApps, line 116
				appName = "YouTube" // library marker davegut.samsungTvApps, line 117
				break // library marker davegut.samsungTvApps, line 118
			case "amazoninstantvideo": // library marker davegut.samsungTvApps, line 119
				appId = "3201910019365" // library marker davegut.samsungTvApps, line 120
				appName = "Prime Video" // library marker davegut.samsungTvApps, line 121
				break // library marker davegut.samsungTvApps, line 122
			default: // library marker davegut.samsungTvApps, line 123
				logWarn("findThisApp: ${appName} not found in appData") // library marker davegut.samsungTvApps, line 124
		} // library marker davegut.samsungTvApps, line 125
	} // library marker davegut.samsungTvApps, line 126
	return [appName, appId] // library marker davegut.samsungTvApps, line 127
} // library marker davegut.samsungTvApps, line 128

def getAppData(appId) { // library marker davegut.samsungTvApps, line 130
	def logData = [appId: appId] // library marker davegut.samsungTvApps, line 131
	def thisApp = state.appData.find { it.value == appId } // library marker davegut.samsungTvApps, line 132
	if (thisApp && !state.appIdIndex) { // library marker davegut.samsungTvApps, line 133
		sendEvent(name: "currentApp", value: thisApp.key) // library marker davegut.samsungTvApps, line 134
		logData << [currentApp: thisApp.key] // library marker davegut.samsungTvApps, line 135
	} else { // library marker davegut.samsungTvApps, line 136
		Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${appId}", // library marker davegut.samsungTvApps, line 137
					  timeout: 3] // library marker davegut.samsungTvApps, line 138
		try { // library marker davegut.samsungTvApps, line 139
			asynchttpGet("getAppDataParse", params, [appId: appId]) // library marker davegut.samsungTvApps, line 140
		} catch (err) { // library marker davegut.samsungTvApps, line 141
			logData: [status: "FAILED", data: err] // library marker davegut.samsungTvApps, line 142
		} // library marker davegut.samsungTvApps, line 143
	} // library marker davegut.samsungTvApps, line 144
	logDebug("getAppData: ${logData}") // library marker davegut.samsungTvApps, line 145
} // library marker davegut.samsungTvApps, line 146

def getAppDataParse(resp, data) { // library marker davegut.samsungTvApps, line 148
	def logData = [appId: data.appId] // library marker davegut.samsungTvApps, line 149
	if (resp.status == 200) { // library marker davegut.samsungTvApps, line 150
		def respData = new JsonSlurper().parseText(resp.data) // library marker davegut.samsungTvApps, line 151
		logData << [resp: respData] // library marker davegut.samsungTvApps, line 152
		state.appData << ["${respData.name}": respData.id] // library marker davegut.samsungTvApps, line 153
		if(!state.appIdIndex && device.currentValue("currentApp") != currApp) { // library marker davegut.samsungTvApps, line 154
			sendEvent(name: "currentApp", value: respData.name) // library marker davegut.samsungTvApps, line 155
			logData << [currentApp: respData.name] // library marker davegut.samsungTvApps, line 156
		} // library marker davegut.samsungTvApps, line 157
	} else { // library marker davegut.samsungTvApps, line 158
		logData << [status: "FAILED", reason: "${resp.status} response from TV"] // library marker davegut.samsungTvApps, line 159
	} // library marker davegut.samsungTvApps, line 160
	logDebug("getAppDataParse: ${logData}") // library marker davegut.samsungTvApps, line 161
} // library marker davegut.samsungTvApps, line 162

def updateAppCodes() { // library marker davegut.samsungTvApps, line 164
	if (!state.appData) { state.appData = [:] } // library marker davegut.samsungTvApps, line 165
	if (device.currentValue("switch") == "on") { // library marker davegut.samsungTvApps, line 166
		logInfo("updateAppCodes: [currentDbSize: ${state.appData.size()}, availableCodes: ${appIdList().size()}]") // library marker davegut.samsungTvApps, line 167
		unschedule("onPoll") // library marker davegut.samsungTvApps, line 168
		runIn(900, setOnPollInterval) // library marker davegut.samsungTvApps, line 169
		state.appIdIndex = 0 // library marker davegut.samsungTvApps, line 170
		findNextApp() // library marker davegut.samsungTvApps, line 171
	} else { // library marker davegut.samsungTvApps, line 172
		logWarn("getAppList: [status: FAILED, reason: tvOff]") // library marker davegut.samsungTvApps, line 173
	} // library marker davegut.samsungTvApps, line 174
	device.updateSetting("resetAppCodes", [type:"bool", value: false]) // library marker davegut.samsungTvApps, line 175
	device.updateSetting("findAppCodes", [type:"bool", value: false]) // library marker davegut.samsungTvApps, line 176
} // library marker davegut.samsungTvApps, line 177

def findNextApp() { // library marker davegut.samsungTvApps, line 179
	def appIds = appIdList() // library marker davegut.samsungTvApps, line 180
	def logData = [:] // library marker davegut.samsungTvApps, line 181
	if (state.appIdIndex < appIds.size()) { // library marker davegut.samsungTvApps, line 182
		def nextApp = appIds[state.appIdIndex] // library marker davegut.samsungTvApps, line 183
		state.appIdIndex += 1 // library marker davegut.samsungTvApps, line 184
		getAppData(nextApp) // library marker davegut.samsungTvApps, line 185
		runIn(6, findNextApp) // library marker davegut.samsungTvApps, line 186
	} else { // library marker davegut.samsungTvApps, line 187
		runIn(20, setOnPollInterval) // library marker davegut.samsungTvApps, line 188
		logData << [status: "Complete", appIdsScanned: state.appIdIndex] // library marker davegut.samsungTvApps, line 189
		logData << [totalApps: state.appData.size(), appData: state.appData] // library marker davegut.samsungTvApps, line 190
		state.remove("appIdIndex") // library marker davegut.samsungTvApps, line 191
		logInfo("findNextApp: ${logData}") // library marker davegut.samsungTvApps, line 192
	} // library marker davegut.samsungTvApps, line 193
} // library marker davegut.samsungTvApps, line 194

def appIdList() { // library marker davegut.samsungTvApps, line 196
	def appList = [ // library marker davegut.samsungTvApps, line 197
		"kk8MbItQ0H.VUDU", "vYmY3ACVaa.emby", "ZmmGjO6VKO.slingtv", "MCmYXNxgcu.DisneyPlus", // library marker davegut.samsungTvApps, line 198
		"PvWgqxV3Xa.YouTubeTV", "LBUAQX1exg.Hulu", "AQKO41xyKP.AmazonAlexa", "3KA0pm7a7V.TubiTV", // library marker davegut.samsungTvApps, line 199
		"cj37Ni3qXM.HBONow", "gzcc4LRFBF.Peacock", "9Ur5IzDKqV.TizenYouTube", "BjyffU0l9h.Stream", // library marker davegut.samsungTvApps, line 200
		"3202203026841", "3202103023232", "3202103023185", "3202012022468", "3202012022421", // library marker davegut.samsungTvApps, line 201
		"3202011022316", "3202011022131", "3202010022098", "3202009021877", "3202008021577", // library marker davegut.samsungTvApps, line 202
		"3202008021462", "3202008021439", "3202007021336", "3202004020674", "3202004020626", // library marker davegut.samsungTvApps, line 203
		"3202003020365", "3201910019457", "3201910019449", "3201910019420", "3201910019378", // library marker davegut.samsungTvApps, line 204
		"3201910019365", "3201910019354", "3201909019271", "3201909019175", "3201908019041", // library marker davegut.samsungTvApps, line 205
		"3201908019022", "3201907018807", "3201907018786", "3201907018784", "3201906018693", // library marker davegut.samsungTvApps, line 206
		"3201901017768", "3201901017640", "3201812017479", "3201810017091", "3201810017074", // library marker davegut.samsungTvApps, line 207
		"3201807016597", "3201806016432", "3201806016390", "3201806016381", "3201805016367", // library marker davegut.samsungTvApps, line 208
		"3201803015944", "3201803015934", "3201803015869", "3201711015226", "3201710015067", // library marker davegut.samsungTvApps, line 209
		"3201710015037", "3201710015016", "3201710014874", "3201710014866", "3201707014489", // library marker davegut.samsungTvApps, line 210
		"3201706014250", "3201706012478", "3201704012212", "3201704012147", "3201703012079", // library marker davegut.samsungTvApps, line 211
		"3201703012065", "3201703012029", "3201702011851", "3201612011418", "3201611011210", // library marker davegut.samsungTvApps, line 212
		"3201611011005", "3201611010983", "3201608010385", "3201608010191", "3201607010031", // library marker davegut.samsungTvApps, line 213
		"3201606009910", "3201606009798", "3201606009684", "3201604009182", "3201603008746", // library marker davegut.samsungTvApps, line 214
		"3201603008210", "3201602007865", "3201601007670", "3201601007625", "3201601007230", // library marker davegut.samsungTvApps, line 215
		"3201512006963", "3201512006785", "3201511006428", "3201510005981", "3201506003488", // library marker davegut.samsungTvApps, line 216
		"3201506003486", "3201506003175", "3201504001965", "121299000612", "121299000101", // library marker davegut.samsungTvApps, line 217
		"121299000089", "111399002220", "111399002034", "111399000741", "111299002148", // library marker davegut.samsungTvApps, line 218
		"111299001912", "111299000769", "111012010001", "11101200001", "11101000407", // library marker davegut.samsungTvApps, line 219
		"11091000000" // library marker davegut.samsungTvApps, line 220
	] // library marker davegut.samsungTvApps, line 221
	return appList // library marker davegut.samsungTvApps, line 222
} // library marker davegut.samsungTvApps, line 223

def appRunBrowser() { appOpenByName("Browser") } // library marker davegut.samsungTvApps, line 225

def appRunYouTube() { appOpenByName("YouTube") } // library marker davegut.samsungTvApps, line 227

def appRunNetflix() { appOpenByName("Netflix") } // library marker davegut.samsungTvApps, line 229

def appRunPrimeVideo() { appOpenByName("Prime Video") } // library marker davegut.samsungTvApps, line 231

def appRunYouTubeTV() { appOpenByName("YouTubeTV") } // library marker davegut.samsungTvApps, line 233

def appRunHulu() { appOpenByName("Hulu") } // library marker davegut.samsungTvApps, line 235

// ~~~~~ end include (1244) davegut.samsungTvApps ~~~~~

// ~~~~~ start include (1072) davegut.Logging ~~~~~
library ( // library marker davegut.Logging, line 1
	name: "Logging", // library marker davegut.Logging, line 2
	namespace: "davegut", // library marker davegut.Logging, line 3
	author: "Dave Gutheinz", // library marker davegut.Logging, line 4
	description: "Common Logging Methods", // library marker davegut.Logging, line 5
	category: "utilities", // library marker davegut.Logging, line 6
	documentationLink: "" // library marker davegut.Logging, line 7
) // library marker davegut.Logging, line 8

//	Logging during development // library marker davegut.Logging, line 10
def listAttributes(trace = false) { // library marker davegut.Logging, line 11
	def attrs = device.getSupportedAttributes() // library marker davegut.Logging, line 12
	def attrList = [:] // library marker davegut.Logging, line 13
	attrs.each { // library marker davegut.Logging, line 14
		def val = device.currentValue("${it}") // library marker davegut.Logging, line 15
		attrList << ["${it}": val] // library marker davegut.Logging, line 16
	} // library marker davegut.Logging, line 17
	if (trace == true) { // library marker davegut.Logging, line 18
		logInfo("Attributes: ${attrList}") // library marker davegut.Logging, line 19
	} else { // library marker davegut.Logging, line 20
		logDebug("Attributes: ${attrList}") // library marker davegut.Logging, line 21
	} // library marker davegut.Logging, line 22
} // library marker davegut.Logging, line 23

def logTrace(msg){ // library marker davegut.Logging, line 25
	if (traceLog == true) { // library marker davegut.Logging, line 26
		log.trace "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.Logging, line 27
	} // library marker davegut.Logging, line 28
} // library marker davegut.Logging, line 29

def traceLogOff() { // library marker davegut.Logging, line 31
	device.updateSetting("traceLog", [type:"bool", value: false]) // library marker davegut.Logging, line 32
	logInfo("traceLogOff") // library marker davegut.Logging, line 33
} // library marker davegut.Logging, line 34

def logInfo(msg) {  // library marker davegut.Logging, line 36
	if (textEnable || infoLog) { // library marker davegut.Logging, line 37
		log.info "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.Logging, line 38
	} // library marker davegut.Logging, line 39
} // library marker davegut.Logging, line 40

def debugLogOff() { // library marker davegut.Logging, line 42
	device.updateSetting("logEnable", [type:"bool", value: false]) // library marker davegut.Logging, line 43
	logInfo("debugLogOff") // library marker davegut.Logging, line 44
} // library marker davegut.Logging, line 45

def logDebug(msg) { // library marker davegut.Logging, line 47
	if (logEnable || debugLog) { // library marker davegut.Logging, line 48
		log.debug "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.Logging, line 49
	} // library marker davegut.Logging, line 50
} // library marker davegut.Logging, line 51

def logWarn(msg) { log.warn "${device.displayName}-${driverVer()}: ${msg}" } // library marker davegut.Logging, line 53

def logError(msg) { log.error "${device.displayName}-${driverVer()}: ${msg}" } // library marker davegut.Logging, line 55

// ~~~~~ end include (1072) davegut.Logging ~~~~~
