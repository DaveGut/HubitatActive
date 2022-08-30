/*	===== HUBITAT INTEGRATION VERSION =====================================================
Hubitat - Samsung TV Remote Driver
		Copyright 2022 Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file
except in compliance with the License. You may obtain a copy of the License at:
		http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software distributed under the
License is distributed on an  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied. See the License for the specific language governing permissions
and limitations under the  License.
===== DISCLAIMERS =========================================================================
	THE AUTHOR OF THIS INTEGRATION IS NOT ASSOCIATED WITH SAMSUNG. THIS CODE USES
	TECHNICAL DATA DERIVED FROM GITHUB SOURCES AND AS PERSONAL INVESTIGATION.
===== APPRECIATION ========================================================================
	Hubitat user Cal for technical, test, and emotional support.
	GitHub user Toxblh for exlempary code for numerous commands
	Hubitat users who supported validation of 2016 - 2020 models.
===== 2022 Version 3.1 ====================================================================
Preferences
a.	Added preference wolMethod to allow user to select on method between three WOL formats
	or using the SmartThing ON command.  ST ON will only work is ST is enabled.
b.	Removed Refresh Interval as no longer necessary (superceded by poll).
Methods:
a.	onPoll: Modified as follows:
	1.	If ST enabled, will use the ST poll command.
	2.	If ST not enabled, modifies previous method to update switch attribute for
		off detection to require three detections prior to setting switch attribute.
Known Issue:	for newer TV's, Samsung has removed the remote Key to control artMode.
				Expect art mode functions to be intermittent until I find a true fix.
===========================================================================================*/
def driverVer() { return "3.1.1" }
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
	definition (name: "Samsung TV Remote",
				namespace: "davegut",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungTvRemote/SamsungTVRemote.groovy"
			   ){
		capability "SamsungTV"			//	cmds: on/off, volume, mute. attrs: switch, volume, mute
		command "showMessage", [[name: "NOT IMPLEMENTED"]]
		command "setVolume", ["SmartThings Function"]	//	SmartThings
		command "setPictureMode", ["SmartThings Function"]	//	SmartThings
		command "setSoundMode", ["SmartThings Function"]	//	SmartThings
		capability "Switch"
		//	===== UPnP Augmentation =====
		command "pause"				//	Only work on TV Players
		command "play"					//	Only work on TV Players
		command "stop"					//	Only work on TV Players
		//	===== Remote Control Interface =====
		command "sendKey", ["string"]	//	Send entered key. eg: HDMI
		command "artMode"				//	Toggles artModeStatus
		attribute "artModeStatus", "string"	//	on/off/notFrame
		command "ambientMode"			//	non-Frame TVs
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
		command "info"					//	Pops up Info banner
		//	Source Commands
		command "source"				//	Pops up source window
		command "hdmi"					//	Direct progression through available sources
		command "setInputSource", ["SmartThings Function"]	//	SmartThings
		attribute "inputSource", "string"					//	SmartThings
		attribute "inputSources", "string"					//	SmartThings
		//	TV Channel
		command "channelList"
		command "channelUp"
		command "channelDown"
		command "previousChannel"
		command "nextChannel"
		command "setTvChannel", ["SmartThings Function"]	//	SmartThings
		attribute "tvChannel", "string"						//	SmartThings
		attribute "tvChannelName", "string"					//	SmartThings
		//	Playing Navigation Commands
		command "exit"
		command "Return"
		command "fastBack"
		command "fastForward"
		
		command "toggleInputSource", [[name: "SmartThings Function"]]	//	SmartThings
		command "toggleSoundMode", [[name: "SmartThings Function"]]	//	SmartThings
		command "togglePictureMode", [[name: "SmartThings Function"]]	//	SmartThings
		
		//	Application Access/Control
		command "appOpenByName", ["string"]
		command "appCloseNamedApp"
		command "appInstallByCode", ["string"]
		command "appOpenByCode", ["string"]
		command "appRunBrowser"
		command "appRunYouTube"
		command "appRunNetflix"
		command "appRunPrimeVideo"
		command "appRunYouTubeTV"
		command "appRunHulu"
		//	===== Button Interface =====
		capability "PushableButton"
		//	for media player tile
		command "setLevel", ["SmartThings Function"]	//	SmartThings
		attribute "transportStatus", "string"
		attribute "level", "NUMBER"
		attribute "trackDescription", "string"
		command "nextTrack", [[name: "Sets Channel Up"]]
		command "previousTrack", [[name: "Sets Channel Down"]]
	}
	preferences {
		input ("deviceIp", "text", title: "Samsung TV Ip", defaultValue: "")
		if (deviceIp) {
			input ("pollInterval","enum", title: "Power Polling Interval (seconds)",
				   options: ["5", "10", "15", "20", "30", "60"], defaultValue: "60")
			input ("tvPwrOnMode", "enum", title: "TV Startup Display", 
				   options: ["ART_MODE", "Ambient", "none"], defaultValue: "none")
			input ("debugLog", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false)
			input ("infoLog", "bool", 
				   title: "Enable information logging " + helpLogo(),
				   defaultValue: true)
			input ("connectST", "bool", title: "Connect to SmartThings for added functions", defaultValue: false)
		}
		if (connectST) {
			input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "")
			if (stApiKey) {
				input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "")
			}
			input ("wolMethod", "enum", title: "Wol (ON) Method", 
				   options: ["1": "Hubitat Magic Packet", "2": "UDP Message", 
							 "3": "Use Alternate Wol MAC", "4": "Use Smart Things"], defaultValue: "2")
		} else {
			input ("wolMethod", "enum", title: "Wol (ON) Method", 
				   options: ["1": "Hubitat Magic Packet", "2": "UDP Message", 
							 "3": "Use Alternate Wol MAC"], defaultValue: "2")
		}
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
	runIn(1, updated)
}

def updated() {
	unschedule()
	def updStatus = [:]
	if (!deviceIp) {
		logWarn("\n\n\t\t<b>Enter the deviceIp and Save Preferences</b>\n\n")
		updStatus << [status: "ERROR", data: "Device IP not set."]
	} else {
		updStatus << [getDeviceData: getDeviceData()]
		if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
			updateDataValue("driverVersion", driverVer())
			updStatus << [driverVer: driverVer()]
		}

		if (debugLog) { runIn(1800, debugLogOff) }
		updStatus << [debugLog: debugLog, infoLog: infoLog]
		if (pollInterval == "60" || pollInterval == "off" || pollInterval == null) {
			runEvery1Minute(onPoll)
		} else {
			schedule("0/${pollInterval} * * * * ?",  onPoll)
		}
		updStatus << [pollInterval: pollInterval]

		if (getDataValue("frameTv") == "true" && getDataValue("modelYear").toInteger() >= 2022) {
			state.___2022_Model_Note___ = "artMode keys and functions may not work on this device. Changes in Tizen OS."
			updStatus << [artMode: "May not work"]
		} else {
			state.remove("___2022_Model_Note___")
		}
		updStatus << [stUpdate: stUpdate()]
	}

	//	if (updStatus.status == "ERROR") {
	if (updStatus.toString().contains("ERROR")) {
		logWarn("updated: ${updStatus}")
	} else {
		logInfo("updated: ${updStatus}")
	}
}

def getDeviceData() {
	def respData = [:]
	if (getDataValue("uuid")) {
		respData << [status: "already run"]
	} else {
		try{
			httpGet([uri: "http://${deviceIp}:8001/api/v2/", timeout: 5]) { resp ->
				def wifiMac = resp.data.device.wifiMac
				updateDataValue("deviceMac", wifiMac)
				def alternateWolMac = wifiMac.replaceAll(":", "").toUpperCase()
				updateDataValue("alternateWolMac", alternateWolMac)
				device.setDeviceNetworkId(alternateWolMac)
				
				def modelYear = "20" + resp.data.device.model[0..1]
				updateDataValue("modelYear", modelYear)
				def frameTv = "false"
				if (resp.data.device.FrameTVSupport) {
					frameTv = resp.data.device.FrameTVSupport
					sendEvent(name: "artModeStatus", value: "notFrameTV")
					respData << [artModeStatus: "notFrameTV"]
				}
				updateDataValue("frameTv", frameTv)
				if (resp.data.device.TokenAuthSupport) {
					tokenSupport = resp.data.device.TokenAuthSupport
					updateDataValue("tokenSupport", tokenSupport)
				}
				def uuid = resp.data.device.duid.substring(5)
				updateDataValue("uuid", uuid)
				respData << [status: "OK", dni: alternateWolMac, modelYear: modelYear,
							 frameTv: frameTv, tokenSupport: tokenSupport]
			}
		} catch (error) {
			respData << [status: "ERROR", reason: error]
		}
	}
	return respData
}

def stUpdate() {
	def stData = [:]
	if (!connectST) {
		stData << [status: "Preference connectST not true"]
	} else if (!stApiKey || stApiKey == "") {
		logWarn("\n\n\t\t<b>Enter the ST API Key and Save Preferences</b>\n\n")
		stData << [status: "ERROR", date: "no stApiKey"]
	} else if (!stDeviceId || stDeviceId == "") {
		getDeviceList()
		logWarn("\n\n\t\t<b>Enter the deviceId from the Log List and Save Preferences</b>\n\n")
		stData << [status: "ERROR", date: "no stDeviceId"]
	} else {
		runEvery1Minute(stRefresh)
		if (device.currentValue("volume") == null) {
			sendEvent(name: "volume", value: 0)
		}
		runIn(5, stRefresh)
		stData << [stRefreshInterval: "1 minute"]
	}
	return stData
}

def stRefresh() {
	if (connectST && device.currentValue("switch") == "on") {
		refresh()
	}
}


//	===== Polling/Refresh Capability =====
def onPoll() {
		def sendCmdParams = [
			uri: "http://${deviceIp}:8001/api/v2/",
			timeout: 5]
		asynchttpGet("onParse", sendCmdParams, [reason: "none"])
}

def onParse(resp, data) {
	if (resp.status != 408) {
		state.offCount = 0
		if (device.currentValue("switch") != "on") {
			sendEvent(name: "switch", value: "on")
			logInfo("onParse: [switch: on]")S
			setPowerOnMode()
		}
	} else {
		if (state.offCount > 2) {
			if (device.currentValue("switch") != "off") {
				sendEvent(name: "switch", value: "off")
				logInfo("onPoll: [switch: off]")
			}
		} else {
			state.offCount += 1
			runIn(1, onPoll)
		}
	}
}

def setPowerOnMode() {
	if(tvPwrOnMode == "ART_MODE" && getDataValue("frameTv") == "true") {
		artMode("on")
	} else if (tvPwrOnMode == "Ambient") {
		ambientMode()
	}
	connect("remote")
}		

def on() {
	logDebug("on: [dni: ${device.deviceNetworkId}, wolMethod: ${wolMethod}]")
	def wol
	if (wolMethod == "2") {
		def wolMac = getDataValue("alternateWolMac")
		def cmd = "FFFFFFFFFFFF$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac"
		wol = new hubitat.device.HubAction(cmd,
											   hubitat.device.Protocol.LAN,
											   [type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
												destinationAddress: "255.255.255.255:7",
												encoding: hubitat.device.HubAction.Encoding.HEX_STRING])
		sendHubCommand(wol)
	} else if (wolMethod == "3") {
		wol = new hubitat.device.HubAction("wake on lan ${getDataValue("alternateWolMac")}",
											   hubitat.device.Protocol.LAN,
											   null)
		sendHubCommand(wol)
	} else if (wolMethod == "4") {
		setSwitch("on")
	} else {
		wol = new hubitat.device.HubAction("wake on lan ${device.deviceNetworkId}",
											   hubitat.device.Protocol.LAN,
											   null)
		sendHubCommand(wol)
	}
	if (pollInterval.toInteger() > 10) {
		runIn(5, onPoll)
	}
}

def off() {
	logDebug("off: frameTv = ${getDataValue("frameTV")}")
	if (getDataValue("frameTv") == "false") { sendKey("POWER") }
	else {
		sendKey("POWER", "Press")
		pauseExecution(3000)
		sendKey("POWER", "Release")
	}
	sendEvent(name: "switch", value: "off")
}



//	===== Unimplemented Commands from Capabilities =====
def showMessage() { logWarn("showMessage: not implemented") }



//	===== LAN Websocket Interface =====
def mute() {
	sendKey("MUTE")
	runIn(5, stRefresh)
}
def unmute() {
	sendKey("MUTE")
	runIn(5, stRefresh)
}
def volumeUp() { 
	sendKey("VOLUP") 
	runIn(5, stRefresh)
}
def volumeDown() { 
	sendKey("VOLDOWN")
	runIn(5, stRefresh)
}

def play() { sendKey("PLAY") }
def pause() { sendKey("PAUSE") }
def stop() { sendKey("STOP") }
def exit() { sendKey("EXIT") }
def Return() { sendKey("RETURN") }
def fastBack() {
	sendKey("LEFT", "Press")
	pauseExecution(1000)
	sendKey("LEFT", "Release")
}
def fastForward() {
	sendKey("RIGHT", "Press")
	pauseExecution(1000)
	sendKey("RIGHT", "Release")
}

def arrowLeft() { sendKey("LEFT") }
def arrowRight() { sendKey("RIGHT") }
def arrowUp() { sendKey("UP") }
def arrowDown() { sendKey("DOWN") }
def enter() { sendKey("ENTER") }
def numericKeyPad() { sendKey("MORE") }

def home() { sendKey("HOME") }
def menu() { sendKey("MENU") }
def guide() { sendKey("GUIDE") }
def info() { sendKey("INFO") }

def source() { 
	sendKey("SOURCE")
	runIn(5, stRefresh)
}
def hdmi() {
	sendKey("HDMI")
	runIn(5, stRefresh)
}

def channelList() { sendKey("CH_LIST") }
def channelUp() { 
	sendKey("CHUP") 
	runIn(5, stRefresh)
}
def nextTrack() { channelUp() }
def channelDown() { 
	sendKey("CHDOWN") 
	runIn(5, stRefresh)
}
def previousTrack() { channelDown() }
def previousChannel() { 
	sendKey("PRECH") 
	runIn(5, stRefresh)
}

def artMode() {
	if (getDataValue("modelYear").toInteger() >= 2022) {
		logWarn("artMode: Art Mode may not work on 2022 and later model years")
	}
	if (getDataValue("frameTv") == "false") {
		logInfo("artMode: Command not executed. Not a frameTv.")
		return
	}
	getArtModeStatus()
	def onOff = "on"
	if (device.currentValue("artModeStatus") == "on") {
		onOff = "off"
	}
	logDebug("artMode: setting artMode to ${onOff}.")
	def data = [value:"${onOff}",
				request:"set_artmode_status",
				id: "${getDataValue("uuid")}"]
	data = JsonOutput.toJson(data)
	artModeCmd(data)
}
def getArtModeStatus() {
	def data = [request:"get_artmode_status",
				id: "${getDataValue("uuid")}"]
	data = JsonOutput.toJson(data)
	artModeCmd(data)
}
def artModeCmd(data) {
	def cmdData = [method:"ms.channel.emit",
				   params:[data:"${data}",
						   to:"host",
						   event:"art_app_request"]]
	cmdData = JsonOutput.toJson(cmdData)
	sendMessage("frameArt", cmdData)	//	send command, connect is automatic.
}
def ambientMode() {
	if (getDataValue("modelYear").toInteger() >= 2022) {
		logWarn("artMode: Ambient Mode may not work on 2022 and later model years")
	}
	logDebug("ambientMode: frameTv = ${getDataValue("frameTv")}")
	if (getDataValue("frameTv") == "true") { return }
	sendKey("AMBIENT")
}

def sendKey(key, cmd = "Click") {
	key = "KEY_${key.toUpperCase()}"
	def data = [method:"ms.remote.control",
				params:[Cmd:"${cmd}",
						DataOfCmd:"${key}",
						TypeOfRemote:"SendRemoteKey"]]
	sendMessage("remote", JsonOutput.toJson(data) )
}

def connect(funct) {
	logDebug("connect: function = ${funct}")
	def url
	def name = "SHViaXRhdCBTYW1zdW5nIFJlbW90ZQ=="
	if (getDataValue("tokenSupport") == "true") {
		if (funct == "remote") {
			url = "wss://${deviceIp}:8002/api/v2/channels/samsung.remote.control?name=${name}&token=${state.token}"
		} else if (funct == "frameArt") {
			url = "wss://${deviceIp}:8002/api/v2/channels/com.samsung.art-app?name=${name}&token=${state.token}"
		} else if (funct == "application") {
			url = "ws://${deviceIp}:8001/api/v2/applications?name=${name}"
		} else {
			logWarn("sendMessage: Invalid Function = ${funct}, tokenSupport = true")
		}
	} else {
		if (funct == "remote") {
			url = "ws://${deviceIp}:8001/api/v2/channels/samsung.remote.control?name=${name}"
		} else if (funct == "frameArt") {
			url = "ws://${deviceIp}:8001/api/v2/channels/com.samsung.art-app?name=${name}"
		} else if (funct == "application") {
			url = "ws://${deviceIp}:8001/api/v2?name=${name}"
		} else {
			logWarn("sendMessage: Invalid Function = ${funct}, tokenSupport = false")
		}
	}
	state.currentFunction = funct
	interfaces.webSocket.connect(url, ignoreSSLIssues: true)
}

def sendMessage(funct, data) {
	logDebug("sendMessage: function = ${funct} | data = ${data} | connectType = ${state.currentFunction}")
	if (state.wsDeviceStatus != "open" || state.currentFunction != funct) {
		connect(funct)
		pauseExecution(300)
	}
	interfaces.webSocket.sendMessage(data)
}

def webSocketStatus(message) {
	if (message == "status: open") {
		state.wsDeviceStatus = "open"
		logDebug("webSocketStatus: wsDeviceStatus = open")
	} else if (message == "status: closing") {
		state.wsDeviceStatus = "closed"
		state.currentFunction = "close"
		logDebug("webSocketStatus: wsDeviceStatus = closed")
	} else if (message.substring(0,7) == "failure") {
		logDebug("webSocketStatus: Failure.  Closing Socket.")
		state.wsDeviceStatus = "closed"
		state.currentFunction = "close"
		interfaces.webSocket.close()
	}
}

def parse(resp) {
	try {
		resp = parseJson(resp)
	} catch (e) {
		logWarn("parse: Unhandled websocket return. resp =\n${resp}")
	}
	logDebug("parse: ${resp}")
	def event = resp.event
	def logMsg = "parse: event = ${event}"
	if (event == "ms.channel.connect") {
		logMsg += ", webSocket open"
		def newToken = resp.data.token
		if (newToken != null && newToken != state.token) {
			logMsg += ", token updated to ${newToken}"
			state.token = newToken
		}
	} else if (event == "d2d_service_message") {
		def data = parseJson(resp.data)
		if (data.event == "artmode_status" ||
			data.event == "art_mode_changed") {
			def status = data.value
			if (status == null) { status = data.status }
			sendEvent(name: "artModeStatus", value: status)
			logMsg += ", artMode status = ${data.value}"
		}
	} else if (event == "ms.channel.ready") {
		logMsg += ", webSocket connected"
	} else if (event == "ms.error") {
		logMsg += "Error Event.  Closing webSocket"
		close{}
	} else {
		logMsg += ", message = ${resp}"
	}
	logDebug(logMsg)
}



//	===== LAN HTTP Implementation =====
def appRunBrowser() { appOpenByCode("org.tizen.browser") }
def appRunYouTube() { appOpenByName("YouTube") }
def appRunNetflix() { appOpenByName("Netflix") }
def appRunPrimeVideo() { appOpenByName("AmazonInstantVideo") }
def appRunYouTubeTV() { appOpenByName("YouTubeTV") }
def appRunHulu() { appOpenByCode("3201601007625") }
def appOpenByName(appName) {
	def url = "http://${deviceIp}:8080/ws/apps/${appName}"
	try {
		httpPost(url, "") { resp ->
			logDebug("appOpenByName: [name: ${appName}, status: ${resp.status}, data: ${resp.data}]")
		}
	} catch (e) {
		logWarn("appOpenByName: [name: ${appName}, status: FAILED, data: ${e}]")
	}
}
def appOpenByCode(appId) {
	def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}"
	try {
		httpPost(uri, body) { resp ->
			logDebug("appOpenByCode: [code: ${appId}, status: ${resp.status}, data: ${resp.data}]")
		}
		runIn(5, appGetData, [data: appId]) 
	} catch (e) {
		logWarn("appOpenByCode: [code: ${appId}, status: FAILED, data: ${e}]")
	}
}



//	===== CLOUD SmartThings Implementation =====
def setSwitch(onOff) {
	def cmdData = [
		component: "main",
		capability: "switch",
		command: onOff,
		arguments: []]
	deviceCommand(cmdData)
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

def distResp(resp, data) {
	def respLog = [:]
	if (resp.status == 200) {
		try {
			def respData = new JsonSlurper().parseText(resp.data)
			if (data.reason == "deviceSetup") {
				deviceSetupParse(respData.components.main)
			}
			statusParse(respData.components.main)
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

def deviceSetupParse(mainData) {
	def setupData = [:]
	def supportedInputs =  mainData.mediaInputSource.supportedInputSources.value
	sendEvent(name: "supportedInputs", value: supportedInputs)	
	state.supportedInputs = supportedInputs
	setupData << [supportedInputs: supportedInputs]
	
	def pictureModes = mainData["custom.picturemode"].supportedPictureModes.value
	sendEvent(name: "pictureModes",value: pictureModes)
	state.pictureModes = pictureModes
	setupData << [pictureModes: pictureModes]
	
	def soundModes =  mainData["custom.soundmode"].supportedSoundModes.value
	sendEvent(name: "soundModes",value: soundModes)
	state.soundModes = soundModes
	setupData << [soundModes: soundModes]
	
	logInfo("deviceSetupParse: ${setupData}")
}

def statusParse(mainData) {
	def stData = [:]
	
	def onOff = mainData.switch.switch.value
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff)
		stData << [switch: onOff]
		setPowerOnMode()
	}
	
	if (onOff == "on") {
		def volume = mainData.audioVolume.volume.value.toInteger()
		if (device.currentValue("volume").toInteger() != volume) {
			sendEvent(name: "volume", value: volume)
			stData << [volume: volume]
		}
	
		def mute = mainData.audioMute.mute.value
		if (device.currentValue("mute") != mute) {
			sendEvent(name: "mute", value: mute)
			stData << [mute: mute]
		}
	
		def inputSource = mainData.mediaInputSource.inputSource.value
		if (device.currentValue("inputSource") != inputSource) {
			sendEvent(name: "inputSource", value: inputSource)		
			stData << [inputSource: inputSource]
		}
		
		def tvChannel = mainData.tvChannel.tvChannel.value
		if (tvChannel == "") { tvChannel = " " }
		def tvChannelName = mainData.tvChannel.tvChannelName.value
		if (tvChannelName == "") { tvChannelName = " " }
		if (device.currentValue("tvChannel") != tvChannel) {
			sendEvent(name: "tvChannel", value: tvChannel)
			sendEvent(name: "tvChannelName", value: tvChannelName)
			stData << [tvChannel: tvChannel, tvChannelName: tvChannelName]
		}
		
		def trackDesc = inputSource
		if (tvChannelName != " ") { trackDesc = tvChannelName }
		if (device.currentValue("trackDescription") != trackDesc) {
			sendEvent(name: "trackDescription", value:trackDesc)
			stData << [trackDescription: trackDesc]
		}
	
		def pictureMode = mainData["custom.picturemode"].pictureMode.value
		if (device.currentValue("pictureMode") != pictureMode) {
			sendEvent(name: "pictureMode",value: pictureMode)
			stData << [pictureMode: pictureMode]
		}
	
		def soundMode = mainData["custom.soundmode"].soundMode.value
		if (device.currentValue("soundMode") != soundMode) {
			sendEvent(name: "soundMode",value: soundMode)
			stData << [soundMode: soundMode]
		}
	
		def transportStatus = mainData.mediaPlayback.playbackStatus.value
		if (transportStatus == null || transportStatus == "") {
			transportStatus = "stopped"
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


//	===== Button Interface (facilitates dashboard integration) =====
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
		case 6 : artMode(); break			//	New command.  Toggles art mode
		case 7 : ambientMode(); break
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
		case 23: menu(); break			//	Main menu with access to system settings.
		case 24: source(); break		//	Pops up home with cursor at source.  Use left/right/enter to select.
		case 25: info(); break			//	Pops up upper display of currently playing channel
		case 26: channelList(); break	//	Pops up short channel-list.
		case 27: source0(); break		//	Direct to source TV
		case 28: source1(); break		//	Direct to source 1 (one right of TV on menu)
		case 29: source2(); break		//	Direct to source 1 (two right of TV on menu)
		case 30: source3(); break		//	Direct to source 1 (three right of TV on menu)
		case 31: source4(); break		//	Direct to source 1 (ofour right of TV on menu)
		//	===== Other Commands =====
		case 34: previousChannel(); break
		case 35: hdmi(); break			//	Brings up next available source
		case 36: fastBack(); break		//	causes fast forward
		case 37: fastForward(); break	//	causes fast rewind
		case 38: appRunBrowser(); break		//	Direct to source 1 (ofour right of TV on menu)
		case 39: appRunYouTube(); break
		case 40: appRunNetflix(); break
		case 42: toggleSoundMode(); break
		case 43: togglePictureMode(); break
		case 44: setPictureMode("Dynamic"); break
		default:
			logDebug("push: Invalid Button Number!")
			break
	}
}





def simulate() { return false }
			 
				 
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
		logTrace("Attributes: ${attrList}") // library marker davegut.Logging, line 19
	} else { // library marker davegut.Logging, line 20
		logDebug("Attributes: ${attrList}") // library marker davegut.Logging, line 21
	} // library marker davegut.Logging, line 22
} // library marker davegut.Logging, line 23

def logTrace(msg){ // library marker davegut.Logging, line 25
	log.trace "${device.displayName} ${driverVer()}: ${msg}" // library marker davegut.Logging, line 26
} // library marker davegut.Logging, line 27

def logInfo(msg) {  // library marker davegut.Logging, line 29
	if (infoLog == true) { // library marker davegut.Logging, line 30
		log.info "${device.displayName} ${driverVer()}: ${msg}" // library marker davegut.Logging, line 31
	} // library marker davegut.Logging, line 32
} // library marker davegut.Logging, line 33

def debugLogOff() { // library marker davegut.Logging, line 35
	if (debug == true) { // library marker davegut.Logging, line 36
		device.updateSetting("debug", [type:"bool", value: false]) // library marker davegut.Logging, line 37
	} else if (debugLog == true) { // library marker davegut.Logging, line 38
		device.updateSetting("debugLog", [type:"bool", value: false]) // library marker davegut.Logging, line 39
	} // library marker davegut.Logging, line 40
	logInfo("Debug logging is false.") // library marker davegut.Logging, line 41
} // library marker davegut.Logging, line 42

def logDebug(msg) { // library marker davegut.Logging, line 44
	if (debug == true || debugLog == true) { // library marker davegut.Logging, line 45
		log.debug "${device.displayName} ${driverVer()}: ${msg}" // library marker davegut.Logging, line 46
	} // library marker davegut.Logging, line 47
} // library marker davegut.Logging, line 48

def logWarn(msg) { log.warn "${device.displayName} ${driverVer()}: ${msg}" } // library marker davegut.Logging, line 50

// ~~~~~ end include (1072) davegut.Logging ~~~~~

// ~~~~~ start include (1091) davegut.ST-Communications ~~~~~
library ( // library marker davegut.ST-Communications, line 1
	name: "ST-Communications", // library marker davegut.ST-Communications, line 2
	namespace: "davegut", // library marker davegut.ST-Communications, line 3
	author: "Dave Gutheinz", // library marker davegut.ST-Communications, line 4
	description: "ST Communications Methods", // library marker davegut.ST-Communications, line 5
	category: "utilities", // library marker davegut.ST-Communications, line 6
	documentationLink: "" // library marker davegut.ST-Communications, line 7
) // library marker davegut.ST-Communications, line 8
import groovy.json.JsonSlurper // library marker davegut.ST-Communications, line 9

private asyncGet(sendData, passData = "none") { // library marker davegut.ST-Communications, line 11
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 12
		logWarn("asyncGet: [status: ERROR, errorMsg: no stApiKey]") // library marker davegut.ST-Communications, line 13
	} else { // library marker davegut.ST-Communications, line 14
		logDebug("asyncGet: ${sendData}, ${passData}") // library marker davegut.ST-Communications, line 15
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 16
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 17
			path: sendData.path, // library marker davegut.ST-Communications, line 18
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()]] // library marker davegut.ST-Communications, line 19
		try { // library marker davegut.ST-Communications, line 20
			asynchttpGet(sendData.parse, sendCmdParams, [reason: passData]) // library marker davegut.ST-Communications, line 21
		} catch (error) { // library marker davegut.ST-Communications, line 22
			logWarn("asyncGet: [status: FAILED, errorMsg: ${error}]") // library marker davegut.ST-Communications, line 23
		} // library marker davegut.ST-Communications, line 24
	} // library marker davegut.ST-Communications, line 25
} // library marker davegut.ST-Communications, line 26

private syncGet(path){ // library marker davegut.ST-Communications, line 28
	def respData = [:] // library marker davegut.ST-Communications, line 29
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 30
		respData << [status: "FAILED", // library marker davegut.ST-Communications, line 31
					 errorMsg: "No stApiKey"] // library marker davegut.ST-Communications, line 32
	} else { // library marker davegut.ST-Communications, line 33
		logDebug("syncGet: ${sendData}") // library marker davegut.ST-Communications, line 34
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 35
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 36
			path: path, // library marker davegut.ST-Communications, line 37
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()] // library marker davegut.ST-Communications, line 38
		] // library marker davegut.ST-Communications, line 39
		try { // library marker davegut.ST-Communications, line 40
			httpGet(sendCmdParams) {resp -> // library marker davegut.ST-Communications, line 41
				if (resp.status == 200 && resp.data != null) { // library marker davegut.ST-Communications, line 42
					respData << [status: "OK", results: resp.data] // library marker davegut.ST-Communications, line 43
				} else { // library marker davegut.ST-Communications, line 44
					respData << [status: "FAILED", // library marker davegut.ST-Communications, line 45
								 httpCode: resp.status, // library marker davegut.ST-Communications, line 46
								 errorMsg: resp.errorMessage] // library marker davegut.ST-Communications, line 47
				} // library marker davegut.ST-Communications, line 48
			} // library marker davegut.ST-Communications, line 49
		} catch (error) { // library marker davegut.ST-Communications, line 50
			respData << [status: "FAILED", // library marker davegut.ST-Communications, line 51
						 httpCode: "Timeout", // library marker davegut.ST-Communications, line 52
						 errorMsg: error] // library marker davegut.ST-Communications, line 53
		} // library marker davegut.ST-Communications, line 54
	} // library marker davegut.ST-Communications, line 55
	return respData // library marker davegut.ST-Communications, line 56
} // library marker davegut.ST-Communications, line 57

private syncPost(sendData){ // library marker davegut.ST-Communications, line 59
	def respData = [:] // library marker davegut.ST-Communications, line 60
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 61
		respData << [status: "FAILED", // library marker davegut.ST-Communications, line 62
					 errorMsg: "No stApiKey"] // library marker davegut.ST-Communications, line 63
	} else { // library marker davegut.ST-Communications, line 64
		logDebug("syncPost: ${sendData}") // library marker davegut.ST-Communications, line 65

		def cmdBody = [commands: [sendData.cmdData]] // library marker davegut.ST-Communications, line 67
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 68
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 69
			path: sendData.path, // library marker davegut.ST-Communications, line 70
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()], // library marker davegut.ST-Communications, line 71
			body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.ST-Communications, line 72
		] // library marker davegut.ST-Communications, line 73
		try { // library marker davegut.ST-Communications, line 74
			httpPost(sendCmdParams) {resp -> // library marker davegut.ST-Communications, line 75
				if (resp.status == 200 && resp.data != null) { // library marker davegut.ST-Communications, line 76
					respData << [status: "OK", results: resp.data.results] // library marker davegut.ST-Communications, line 77
				} else { // library marker davegut.ST-Communications, line 78
					respData << [status: "FAILED", // library marker davegut.ST-Communications, line 79
								 httpCode: resp.status, // library marker davegut.ST-Communications, line 80
								 errorMsg: resp.errorMessage] // library marker davegut.ST-Communications, line 81
				} // library marker davegut.ST-Communications, line 82
			} // library marker davegut.ST-Communications, line 83
		} catch (error) { // library marker davegut.ST-Communications, line 84
			respData << [status: "FAILED", // library marker davegut.ST-Communications, line 85
						 httpCode: "Timeout", // library marker davegut.ST-Communications, line 86
						 errorMsg: error] // library marker davegut.ST-Communications, line 87
		} // library marker davegut.ST-Communications, line 88
	} // library marker davegut.ST-Communications, line 89
	return respData // library marker davegut.ST-Communications, line 90
} // library marker davegut.ST-Communications, line 91

// ~~~~~ end include (1091) davegut.ST-Communications ~~~~~

// ~~~~~ start include (1090) davegut.ST-Common ~~~~~
library ( // library marker davegut.ST-Common, line 1
	name: "ST-Common", // library marker davegut.ST-Common, line 2
	namespace: "davegut", // library marker davegut.ST-Common, line 3
	author: "Dave Gutheinz", // library marker davegut.ST-Common, line 4
	description: "ST Wash/Dryer Common Methods", // library marker davegut.ST-Common, line 5
	category: "utilities", // library marker davegut.ST-Common, line 6
	documentationLink: "" // library marker davegut.ST-Common, line 7
) // library marker davegut.ST-Common, line 8

def commonUpdate() { // library marker davegut.ST-Common, line 10
	if (!stApiKey || stApiKey == "") { // library marker davegut.ST-Common, line 11
		return [status: "FAILED", reason: "No stApiKey"] // library marker davegut.ST-Common, line 12
	} // library marker davegut.ST-Common, line 13
	if (!stDeviceId || stDeviceId == "") { // library marker davegut.ST-Common, line 14
		getDeviceList() // library marker davegut.ST-Common, line 15
		return [status: "FAILED", reason: "No stDeviceId"] // library marker davegut.ST-Common, line 16
	} // library marker davegut.ST-Common, line 17

	unschedule() // library marker davegut.ST-Common, line 19
	def updateData = [:] // library marker davegut.ST-Common, line 20
	updateData << [status: "OK"] // library marker davegut.ST-Common, line 21
	if (debugLog) { runIn(1800, debugLogOff) } // library marker davegut.ST-Common, line 22
	updateData << [stDeviceId: stDeviceId] // library marker davegut.ST-Common, line 23
	updateData << [debugLog: debugLog, infoLog: infoLog] // library marker davegut.ST-Common, line 24
	if (!getDataValue("driverVersion") ||  // library marker davegut.ST-Common, line 25
		getDataValue("driverVersion") != driverVer()) { // library marker davegut.ST-Common, line 26
		updateDataValue("driverVersion", driverVer()) // library marker davegut.ST-Common, line 27
		updateData << [driverVer: driverVer()] // library marker davegut.ST-Common, line 28
	} // library marker davegut.ST-Common, line 29
	setPollInterval(pollInterval) // library marker davegut.ST-Common, line 30
	updateData << [pollInterval: pollInterval] // library marker davegut.ST-Common, line 31

	runIn(5, refresh) // library marker davegut.ST-Common, line 33
	return updateData // library marker davegut.ST-Common, line 34
} // library marker davegut.ST-Common, line 35

def setPollInterval(pollInterval) { // library marker davegut.ST-Common, line 37
	logDebug("setPollInterval: ${pollInterval}") // library marker davegut.ST-Common, line 38
	state.pollInterval = pollInterval // library marker davegut.ST-Common, line 39
	switch(pollInterval) { // library marker davegut.ST-Common, line 40
		case "1" : runEvery1Minute(poll); break // library marker davegut.ST-Common, line 41
		case "5" : runEvery5Minutes(poll); break // library marker davegut.ST-Common, line 42
		case "10" : runEvery10Minutes(poll); break // library marker davegut.ST-Common, line 43
		case "30" : runEvery30Minutes(poll); break // library marker davegut.ST-Common, line 44
		default: runEvery10Minutes(poll) // library marker davegut.ST-Common, line 45
	} // library marker davegut.ST-Common, line 46
} // library marker davegut.ST-Common, line 47

def deviceCommand(cmdData) { // library marker davegut.ST-Common, line 49
	def respData = [:] // library marker davegut.ST-Common, line 50
	if (simulate() == true) { // library marker davegut.ST-Common, line 51
		respData = testResp(cmdData) // library marker davegut.ST-Common, line 52
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 53
		respData << [status: "FAILED", data: "no stDeviceId"] // library marker davegut.ST-Common, line 54
	} else { // library marker davegut.ST-Common, line 55
		def sendData = [ // library marker davegut.ST-Common, line 56
			path: "/devices/${stDeviceId.trim()}/commands", // library marker davegut.ST-Common, line 57
			cmdData: cmdData // library marker davegut.ST-Common, line 58
		] // library marker davegut.ST-Common, line 59
		respData = syncPost(sendData) // library marker davegut.ST-Common, line 60
	} // library marker davegut.ST-Common, line 61
	if (cmdData.capability && cmdData.capability != "refresh") { // library marker davegut.ST-Common, line 62
		refresh() // library marker davegut.ST-Common, line 63
	} else { // library marker davegut.ST-Common, line 64
		poll() // library marker davegut.ST-Common, line 65
	} // library marker davegut.ST-Common, line 66
	return respData // library marker davegut.ST-Common, line 67
} // library marker davegut.ST-Common, line 68

def refresh() { // library marker davegut.ST-Common, line 70
	if (stApiKey!= null) { // library marker davegut.ST-Common, line 71
		def cmdData = [ // library marker davegut.ST-Common, line 72
			component: "main", // library marker davegut.ST-Common, line 73
			capability: "refresh", // library marker davegut.ST-Common, line 74
			command: "refresh", // library marker davegut.ST-Common, line 75
			arguments: []] // library marker davegut.ST-Common, line 76
		deviceCommand(cmdData) // library marker davegut.ST-Common, line 77
	} // library marker davegut.ST-Common, line 78
} // library marker davegut.ST-Common, line 79

def poll() { // library marker davegut.ST-Common, line 81
	if (simulate() == true) { // library marker davegut.ST-Common, line 82
		def children = getChildDevices() // library marker davegut.ST-Common, line 83
		if (children) { // library marker davegut.ST-Common, line 84
			children.each { // library marker davegut.ST-Common, line 85
				it.statusParse(testData()) // library marker davegut.ST-Common, line 86
			} // library marker davegut.ST-Common, line 87
		} // library marker davegut.ST-Common, line 88
		statusParse(testData()) // library marker davegut.ST-Common, line 89
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 90
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 91
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 92
	} else { // library marker davegut.ST-Common, line 93
		def sendData = [ // library marker davegut.ST-Common, line 94
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.ST-Common, line 95
			parse: "distResp" // library marker davegut.ST-Common, line 96
			] // library marker davegut.ST-Common, line 97
		asyncGet(sendData, "statusParse") // library marker davegut.ST-Common, line 98
	} // library marker davegut.ST-Common, line 99
} // library marker davegut.ST-Common, line 100

def deviceSetup() { // library marker davegut.ST-Common, line 102
	if (simulate() == true) { // library marker davegut.ST-Common, line 103
		def children = getChildDevices() // library marker davegut.ST-Common, line 104
		deviceSetupParse(testData()) // library marker davegut.ST-Common, line 105
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 106
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 107
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 108
	} else { // library marker davegut.ST-Common, line 109
		def sendData = [ // library marker davegut.ST-Common, line 110
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.ST-Common, line 111
			parse: "distResp" // library marker davegut.ST-Common, line 112
			] // library marker davegut.ST-Common, line 113
		asyncGet(sendData, "deviceSetup") // library marker davegut.ST-Common, line 114
	} // library marker davegut.ST-Common, line 115
} // library marker davegut.ST-Common, line 116

def getDeviceList() { // library marker davegut.ST-Common, line 118
	def sendData = [ // library marker davegut.ST-Common, line 119
		path: "/devices", // library marker davegut.ST-Common, line 120
		parse: "getDeviceListParse" // library marker davegut.ST-Common, line 121
		] // library marker davegut.ST-Common, line 122
	asyncGet(sendData) // library marker davegut.ST-Common, line 123
} // library marker davegut.ST-Common, line 124

def getDeviceListParse(resp, data) { // library marker davegut.ST-Common, line 126
	def respData // library marker davegut.ST-Common, line 127
	if (resp.status != 200) { // library marker davegut.ST-Common, line 128
		respData = [status: "ERROR", // library marker davegut.ST-Common, line 129
					httpCode: resp.status, // library marker davegut.ST-Common, line 130
					errorMsg: resp.errorMessage] // library marker davegut.ST-Common, line 131
	} else { // library marker davegut.ST-Common, line 132
		try { // library marker davegut.ST-Common, line 133
			respData = new JsonSlurper().parseText(resp.data) // library marker davegut.ST-Common, line 134
		} catch (err) { // library marker davegut.ST-Common, line 135
			respData = [status: "ERROR", // library marker davegut.ST-Common, line 136
						errorMsg: err, // library marker davegut.ST-Common, line 137
						respData: resp.data] // library marker davegut.ST-Common, line 138
		} // library marker davegut.ST-Common, line 139
	} // library marker davegut.ST-Common, line 140
	if (respData.status == "ERROR") { // library marker davegut.ST-Common, line 141
		logWarn("getDeviceListParse: ${respData}") // library marker davegut.ST-Common, line 142
	} else { // library marker davegut.ST-Common, line 143
		log.info "" // library marker davegut.ST-Common, line 144
		respData.items.each { // library marker davegut.ST-Common, line 145
			log.trace "${it.label}:   ${it.deviceId}" // library marker davegut.ST-Common, line 146
		} // library marker davegut.ST-Common, line 147
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>" // library marker davegut.ST-Common, line 148
	} // library marker davegut.ST-Common, line 149
} // library marker davegut.ST-Common, line 150

def calcTimeRemaining(completionTime) { // library marker davegut.ST-Common, line 152
	Integer currTime = now() // library marker davegut.ST-Common, line 153
	Integer compTime // library marker davegut.ST-Common, line 154
	try { // library marker davegut.ST-Common, line 155
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 156
	} catch (e) { // library marker davegut.ST-Common, line 157
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 158
	} // library marker davegut.ST-Common, line 159
	Integer timeRemaining = ((compTime-currTime) /1000).toInteger() // library marker davegut.ST-Common, line 160
	if (timeRemaining < 0) { timeRemaining = 0 } // library marker davegut.ST-Common, line 161
	return timeRemaining // library marker davegut.ST-Common, line 162
} // library marker davegut.ST-Common, line 163

// ~~~~~ end include (1090) davegut.ST-Common ~~~~~
