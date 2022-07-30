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
===== 2022 Version 3.0 ====================================================================
a.	Updated SmartThings Communications and logging to use my existing library code.
b.	Enabled setSoundMode() and setPictureMode() from capablity SamsungTV.
c.	Added command toggleSoundMode.  This will use Button command push(42).
d.	Added command togglePictureMode.  This will use Button command push(43).
e.	Converted setVolume() from UPNP to SmartThings command.
f.	Removed UPNP communications.
g.	Reworked method updated() to incorporate UPNP and SmartThings Changes.
NOTE: User can add other buttons by adding lines to Method push.  
Example setPictureMode("Dynamic"): addline: case 44: setPictureMode("Dynamic"); break
(This has been added to base code).
===========================================================================================*/
def driverVer() { return "3.0.1" }
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
		capability "Refresh"
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
			input ("refreshInterval", "enum", title: "Device Refresh Interval (minutes)", 
				   options: ["1", "5", "10", "15", "30"], defaultValue: "15")
			input ("tvPwrOnMode", "enum", title: "TV Startup Display", 
				   options: ["ART_MODE", "Ambient", "none"], defaultValue: "none")
			input ("debugLog", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false)
			input ("infoLog", "bool", 
				   title: "Enable information logging " + helpLogo(),
				   defaultValue: true)
			input ("altWolMac", "bool", title: "Use alternate WOL MAC", defaultValue: false)
			input ("connectST", "bool", title: "Connect to SmartThings for added functions", defaultValue: false)
		}
		if (connectST) {
			input ("stApiKey", "string", title: "SmartThings API Key - See HELP", defaultValue: "")
			if (stApiKey) {
				input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "")
			}
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
		def onOff = checkOn()
		updStatus << [switch: onOff]
		if (onOff.switch == "off") {
			updStatus << [status: "ERROR", data: "TV not on or incorrect IP Address."]
		} else {
			if (!getDataValue("deviceMac")) {
				def devData = getDeviceData()
				if (devData.status == "ERROR") {
					updStatus << [status: "ERROR", getDeviceData: devData]
				} else {
					updStatus << [status: "OK", getDeviceData: devData]
				}
			}
			if(getDataValue("frameTv") == "false") {
				sendEvent(name: "artModeStatus", value: "notFrameTV")
				updStatus << [artModeStatus: "notFrameTV"]
			}
		}
	}
	//	Check if update if so, update data due to version change
	if (!getDataValue("driverVersion") || 
		getDataValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
		updStatus << [driverVer: driverVer()]
		sendEvent(name: "volume", value: volume)
		//	2.x to 3.0 data updates
		state.remove("driverError")
		state.remove("WARNING")
		if (pollInterval == "off") {
			device.updateSetting("pollInterval", [type:"enum", value: "60"])
//		} else if (pollInterval == "5") {
//			device.updateSetting("pollInterval", [type:"enum", value: "10"])
		}
	}
	if (debugLog) { runIn(1800, debugLogOff) }
	updStatus << [debugLog: debugLog, infoLog: infoLog]
	if (pollInterval == "60" || pollInterval == "off" || pollInterval == null) {
		runEvery1Minute(onPoll)
	} else {
		schedule("0/${pollInterval} * * * * ?",  onPoll)
	}
	updStatus << [pollInterval: pollInterval]
	switch(refreshInterval) {
		case "1" : runEvery1Minute(refresh); break
		case "5" : runEvery5Minutes(refresh); break
		case "10" : runEvery10Minutes("refresh"); break
		case "15" : runEvery15Minutes(refresh); break
		case "30" : runEvery30Minutes(refresh); break
		default:
			runEvery5Minutes(refresh); break
	}
	updStatus << [refreshInterval: refreshInterval]
	if (updStatus.status == "ERROR") {
		logWarn("updated: ${updStatus}")
	} else {
		logInfo("updated: ${updStatus}")
	}
	if (connectST) {
		connectToSt()
	} else {
		refresh()
	}
}

def checkOn() {
	def respData = [:]
	def onOff = "off"
	try{
		httpGet([uri: "http://${deviceIp}:8001/api/v2/", timeout: 5]) { resp ->
			if (resp.status == 200) { onOff = "on" }
		}
	} catch (e) {}
	if (onOff == "on" && device.currentValue("switch") != "on") {
		sendEvent(name: "switch", value: "on")
		respData << [switch: "on"]
		if(tvPwrOnMode == "ART_MODE" && getDataValue("frameTv") == "true") {
			artMode("on") }
		else if(tvPwrOnMode == "Ambient") {
			ambientMode()
		} 
		connect("remote")
	}
	else if (onOff == "off" && device.currentValue("switch") != "off") {
		sendEvent(name: "switch", value: "off")
		respData << [switch: "off"]
	}
	return respData
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
				def newDni = getMACFromIP(deviceIp)
				if (device.deviceNetworkId != newDni) {
					device.setDeviceNetworkId(newDni)
				}
				def modelYear = "20" + resp.data.device.model[0..1]
				updateDataValue("modelYear", modelYear)
				def frameTv = "false"
				if (resp.data.device.FrameTVSupport) {
					frameTv = resp.data.device.FrameTVSupport
				}
				updateDataValue("frameTv", frameTv)
				if (resp.data.device.TokenAuthSupport) {
					tokenSupport = resp.data.device.TokenAuthSupport
				}
				def uuid = resp.data.device.duid.substring(5)
				updateDataValue("uuid", uuid)
				updateDataValue("tokenSupport", tokenSupport)
				respData << [status: "OK", dni: newDni, modelYear: modelYear,
							 frameTv: frameTv, tokenSupport: tokenSupport]
			}
		} catch (error) {
			respData << [status: "ERROR", reason: error]
		}
	}
	return respData
}

def connectToSt() {
	if (!stApiKey || stApiKey == "") {
		logWarn("\n\n\t\t<b>Enter the ST API Key and Save Preferences</b>\n\n")
	} else if (!stDeviceId || stDeviceId == "") {
		getDeviceList()
		logWarn("\n\n\t\t<b>Enter the deviceId from the Log List and Save Preferences</b>\n\n")
	} else {
		sendEvent(name: "volume", value: 0)

		runIn(1, deviceSetup)
		logInfo("connectToSt: [status: OK, data: running deviceSetup]")
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
		def devList = "\n\n<b>Copy your device's deviceId from the list below"
		devList += "\n\t\talias: \t\t\tdeviceId</b>"
		respData.items.each {
			devList += "\n\t\t${it.label}:\t${it.deviceId}"
		}
		devList += "\n\n"
		logInfo devList
	}
}

def deviceSetup() {
	if (!stDeviceId || stDeviceId.trim() == "") {
		logWarn("deviceSetup: ST Device ID not set.")
	} else {
		def sendData = [
			path: "/devices/${stDeviceId.trim()}/status",
			parse: "distResp"
			]
		asyncGet(sendData, "deviceSetup")
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
	refresh()
}

//	===== Polling/Refresh Capability =====
def onPoll() {
	def sendCmdParams = [
		uri: "http://${deviceIp}:9197",
		timeout: 5]
	asynchttpGet("onParse", sendCmdParams, [reason: "none"])
}
def onParse(resp, data) {
	if (resp.status == 400) {
		if (device.currentValue("switch") != "on") {
			sendEvent(name: "switch", value: "on")
			logInfo("onParse: [switch: on]")
			if(tvPwrOnMode == "ART_MODE" && getDataValue("frameTv") == "true") {
				artMode("on") }
			else if(tvPwrOnMode == "Ambient") {
				ambientMode()
			} 
			connect("remote")
		}
	} else {
		if (device.currentValue("switch") != "off") {
			sendEvent(name: "switch", value: "off")
			logInfo("onParse: [switch: off]")
		}
	}
}

def refresh() {
	if (device.currentValue("switch") == "off") {
		logDebug("refresh: TV is off.  Refresh not run.")
	} else {
		getArtModeStatus()
		stRefresh()
	}
}

def stRefresh() {
	if (connectST) {
		def cmdData = [
			component: "main",
			capability: "refresh",
			command: "refresh",
			arguments: []]
		deviceCommand(cmdData)
	}
}

//	===== On/Off =====
def on() {
	def wolMac = device.deviceNetworkId
	if (altWolMac) {
		wolMac = getDataValue("alternateWolMac")
	}
	logDebug("on: wolMac = ${wolMac}")
	def wol = new hubitat.device.HubAction ("wake on lan ${wolMac}",
											hubitat.device.Protocol.LAN,
											null)
	sendHubCommand(wol)
}

def off() {
	logDebug("off: frameTv = ${getDataValue("frameTV")}")
	if (getDataValue("frameTv") == "false") { sendKey("POWER") }
	else {
		sendKey("POWER", "Press")
		pauseExecution(3000)
		sendKey("POWER", "Release")
	}
}

//	===== Unimplemented Commands from Capabilities =====
def showMessage() { logWarn("showMessage: not implemented") }

//	===== WS TV WS Commands =====
//	audio control
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

//	track control (works with TV Apps)
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

//	Cursor and Entry Control
def arrowLeft() { sendKey("LEFT") }
def arrowRight() { sendKey("RIGHT") }
def arrowUp() { sendKey("UP") }
def arrowDown() { sendKey("DOWN") }
def enter() { sendKey("ENTER") }
def numericKeyPad() { sendKey("MORE") }

//	Menu Access
def home() { sendKey("HOME") }
def menu() { sendKey("MENU") }
def guide() { sendKey("GUIDE") }
def info() { sendKey("INFO") }

//	Source Commands
def source() { 
	sendKey("SOURCE")
	runIn(5, stRefresh)
}
def hdmi() {
	sendKey("HDMI")
	runIn(5, stRefresh)
}

//	TV Channel
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

//	ArtMode / Ambient Mode
def artMode() {
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
	logDebug("ambientMode: frameTv = ${getDataValue("frameTv")}")
	if (getDataValue("frameTv") == "true") { return }
	sendKey("AMBIENT")
}

//	Application Control
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
def appCloseNamedApp() {
	def appId = state.currentAppId
	logDebug("appClose: appId = ${appId}")
	def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}"
	try { httpDelete([uri: uri]) { resp -> }
	} catch (e) {}
	state.currentAppId = null
}
def appInstallByCode(appId) {
	logDebug("appInstall: appId = ${appId}")
	def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}"
	try {
		httpPut(uri, "") { resp ->
			if (resp.data == true) {
				logDebug("appOpen: Success.")
			}
		}
	} catch (e) {
		logWarn("appInstall: appId = ${appId}, FAILED: ${e}")
		return
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
def appGetData(appId) {
	def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}"
	try {
		httpGet(uri) { resp -> 
			state.currentAppId = resp.data.id
			logDebug("appGetData: [appId: ${resp.data.id}]")
		}
	} catch (e) {
		state.latestAppData = [id: appId]
		logWarn("appGetData: [appId: ${appId}, status: FAILED, data: ${e}]")
	}
}

//	Hardcoded Applications
def appRunBrowser() { appOpenByCode("org.tizen.browser") }
def appRunYouTube() { appOpenByName("YouTube") }
def appRunNetflix() { appOpenByName("Netflix") }
def appRunPrimeVideo() { appOpenByName("AmazonInstantVideo") }
def appRunYouTubeTV() { appOpenByName("YouTubeTV") }
def appRunHulu() { appOpenByCode("3201601007625") }

//	common KEY WS interface
def sendKey(key, cmd = "Click") {
	key = "KEY_${key.toUpperCase()}"
	def data = [method:"ms.remote.control",
				params:[Cmd:"${cmd}",
						DataOfCmd:"${key}",
						TypeOfRemote:"SendRemoteKey"]]
	sendMessage("remote", JsonOutput.toJson(data) )
}

//	===== TV WebSocket Communications =====
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

//	===== SmartThings Commands =====
def setLevel(level) { setVolume(level) }
def setVolume(volume) {
	def cmdData = [
		component: "main",
		capability: "audioVolume",
		command: "setVolume",
		arguments: [volume.toInteger()]]
	deviceCommand(cmdData)
}

def setPictureMode(pictureMode) {
	def cmdData = [
		component: "main",
		capability: "custom.picturemode",
		command: "setPictureMode",
		arguments: [pictureMode]]
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

def setSoundMode(soundMode) { 
	def cmdData = [
		component: "main",
		capability: "custom.soundmode",
		command: "setSoundMode",
		arguments: [soundMode]]
	deviceCommand(cmdData)
}

def toggleSoundMode() {
	//	requires state.soundModes
	def soundModes = state.soundModes
	def totalModes = soundModes.size()
	def currentMode = device.currentValue("soundMode")
	def modeNo = soundModes.indexOf(currentMode)
	def newModeNo = modeNo + 1
	if (newModeNo == totalModes) { newModeNo = 0 }
	def soundMode = soundModes[newModeNo]
	setSoundMode(soundMode)
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

//	===== SmartThings Communicaions / Parse =====
def deviceCommand(cmdData) {
	def cmdResp = [cmdData: cmdData]
	def respData
	if (connectST) {
		if (!stDeviceId || stDeviceId.trim() == "") {
			respData = [status: "FAILED", data: "no stDeviceId"]
		} else {
			def sendData = [
				path: "/devices/${stDeviceId.trim()}/commands",
				cmdData: cmdData
			]
			respData = syncPost(sendData)
			if(respData.status == "OK") {
				respData << [status: "OK"]
			}
		}
		cmdResp << [respData: respData]
		if (respData.status == "FAILED") {
			logWarn("deviceCommand: ${cmdResp}")
		} else {
			logDebug("deviceCommand: ${cmdResp}")
			if (cmdData.capability != "refresh") {
				refresh()
			} else {
				def sendData = [
					path: "/devices/${stDeviceId.trim()}/status",
					parse: "distResp"
					]
				asyncGet(sendData, "statusParse")
			}
		}
	}
}

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
			logWarn("asyncGet: [status: error, statusReason: ${error}]")
		}
	}
}

private syncPost(sendData){
	def respData = [:]
	if (!stApiKey || stApiKey.trim() == "") {
		respData << [status: "ERROR", errorMsg: "no stApiKey"]
		logWarn("syncPost: [status: ERROR, errorMsg: no stApiKey]")
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
					respData << [status: "FAILED", errorMsg: "httpCode: ${resp.status}"]
					def warnData = [status:"ERROR",
									cmdData: sendData.cmdData,
									httpCode: resp.status,
									errorMsg: resp.errorMessage]
					logWarn("syncPost: ${warnData}")
				}
			}
		} catch (error) {
			respData << [status: "FAILED", errorMsg: "non-HTTP Error"]
			def warnData = [status: "ERROR",
							cmdData: sendData.cmdData,
							httpCode: "No Response",
							errorMsg: error]
			logWarn("syncPost: ${warnData}")
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

def statusParse(mainData) {
	def stData = [:]
	def volume = mainData.audioVolume.volume.value.toInteger()
	if (device.currentValue("volume").toInteger() != volume) {
		sendEvent(name: "volume", value: volume)
		sendEvent(name: "level", value: volume)
		stData << [volume: volume, level: volume]
	}
	
	def inputSource = mainData.mediaInputSource.inputSource.value
	if (device.currentValue("inputSource") != inputSource) {
		sendEvent(name: "inputSource", value: inputSource)		
		stData << [inputSource: inputSource]
	}
	
	def tvChannel = mainData.tvChannel.tvChannel.value
	def tvChannelName = mainData.tvChannel.tvChannelName.value
	if (tvChannel == "") { tvChannel = " " }
	if (tvChannelName == "") { tvChannelName = " " }
	if (device.currentValue("tvChannel") != tvChannel) {
		sendEvent(name: "tvChannel", value: tvChannel)	
		sendEvent(name: "tvChannelName", value: tvChannelName)			
		stData << [tvChannel: tvChannel, tvChannelName: tvChannelName]
		def trackDescription = "${tvChannel}: ${tvChannelName}"
		sendEvent(name: "trackDescription", value: trackDescription)
		stData << [trackDescription: trackDescription]
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
	
	def mute = mainData.audioMute.mute.value
	if (device.currentValue("mute") != mute) {
		sendEvent(name: "mute",value: mute)
		stData << [soundMode: mute]
	}
	
	if (stData != [:]) {
		logInfo("statusParse: ${stData}")
	}			   
}

def setEvent(evtName, newValue) {
	if (device.currentValue(evtName) != newValue) {
		sendEvent(name: evtName, value: newValue)
	}
}

//	===== Logging=====
def logTrace(msg){
	log.trace "${device.displayName} ${getDataValue("driverVersion")}: ${msg}"
}
def logInfo(msg) { 
	if (infoLog == true) {
		log.info "${device.displayName} ${getDataValue("driverVersion")}: ${msg}"
	}
}
def debugLogOff() {
	device.updateSetting("debugLog", [type:"bool", value: false])
	logInfo("Debug logging is false.")
}
def logDebug(msg) {
	if (debugLog == true) {
		log.debug "${device.displayName} ${getDataValue("driverVersion")}: ${msg}"
	}
}
def logWarn(msg) { log.warn "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" }

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
