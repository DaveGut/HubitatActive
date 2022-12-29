/*	===== HUBITAT INTEGRATION VERSION =====================================================
Hubitat - Samsung TV Remote Driver
		Copyright 2022 Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2022 Version 4.0 ====================================================================
Complete documentation is available at:
	https://github.com/DaveGut/HubitatActive/tree/master/SamsungTvRemote/Docs
A Version 4.0 change description is contained at:
	https://github.com/DaveGut/HubitatActive/blob/master/SamsungTvRemote/Docs/V4_0%20Notes.pdf
A list (not comprehensive) of Samsung Smart TV Apps and their codes is contained at:
	https://github.com/DaveGut/HubitatActive/blob/master/SamsungTvRemote/Docs/SamsungAppList.pdf

-1	a.  Fixed issue with smartThings volume not parsing.  (created event on enabling smartThings).
	b.	Modified device data setup to not set data until any potential error has cleared.
		Moved setting values outside the try clause.

-2	a.	Added capability Configuration (command configure()).  Auto Configuration will run
		if an update has occurred then next time the power polling detects "on".
	b.	Added frameArt WebSocket test in method configuration.  Sets artModeStatus to "none"
		then runs artModeStatus() to update and (if successful) set state.artModeWs to true.
	c.	SmartThings: IF state.artModeWs not true, then the SmartThings interface can be
		used to update artModeStatus.
	d.	artMode().  Checks if artModeStatus is "yes" and does not update is yes.  Otherwise,
		sends key POWER to enable websocket.  If artModeStatus is not, includes alert that
		the ST interface is required to assure functionality.
	e.	Increased delay on TV Startup to set up start display.  Having timing issues.

===========================================================================================*/
def driverVer() { return "4.0-2g" }
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
	definition (name: "Samsung TV Remote",
				namespace: "davegut",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungTvRemote/SamsungTVRemote.groovy"
			   ){
		capability "Configuration"
		capability "SamsungTV"
		command "showMessage", [[name: "Not Implemented"]]
		capability "Switch"
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
			input ("connectST", "bool", title: "Connect to SmartThings for added functions", defaultValue: false)
		}
		if (connectST) {
			input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "")
			if (stApiKey) {
				input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "")
			}
			input ("stPollInterval", "enum", title: "SmartThings Poll Interval (minutes)",
				   options: ["off", "1", "5", "15", "30"], defaultValue: "15")
			input ("stTestData", "bool", title: "Get ST data dump for developer", defaultValue: false)
				
		}
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
	def updStatus = [:]
	if (!deviceIp) {
		logWarn("\n\n\t\t<b>Enter the deviceIp and Save Preferences</b>\n\n")
		updStatus << [status: "ERROR", data: "Device IP not set."]
	} else {
		updStatus << [getDeviceData: configure()]
		if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
			updateDataValue("driverVersion", driverVer())
			updStatus << [driverVer: driverVer()]
			def cmds = []
			cmds << state.remove("wsDeviceStatus")
			cmds << state.remove("offCount")
			cmds << state.remove("onCount")
			cmds << state.remove("pollTimeOutCount")
			cmds << state.remove("___2022_Model_Note___")
			cmds << state.remove("configured")
			cmds << state.remove("power")
			cmds << state.remove("retry")
			delayBetween(cmds, 1000)
		}
		if (logEnable) { runIn(1800, debugLogOff) }
		updStatus << [logEnable: logEnable, infoLog: infoLog]
		updStatus << [setOnPollInterval: setOnPollInterval()]
		updStatus << [stUpdate: stUpdate()]
	}
	sendEvent(name: "volume", value: 0)
	sendEvent(name: "level", value: 0)
	sendEvent(name: "numberOfButtons", value: 45)
	sendEvent(name: "wsStatus", value: "closed")
	state.standbyTest = false
	logInfo("updated: ${updStatus}")

	if (resetAppCodes) {
		state.appData = [:]
		runIn(5, updateAppCodes)
	} else if (findAppCodes) {
		runIn(5, updateAppCodes)
	}
	pauseExecution(5000)
	listAttributes(true)
}

def setOnPollInterval() {
	if (pollInterval == null) {
		pollInterval = "60"
		device.updateSetting("pollInterval", [type:"enum", value: "60"])
	}
	if (pollInterval == "60") {
		runEvery1Minute(onPoll)
	} else if (pollInterval != "off") {
		schedule("0/${pollInterval} * * * * ?",  onPoll)
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
	return respData
}

//	===== Polling/Refresh Capability =====
def onPoll() {
	def sendCmdParams = [
		uri: "http://${deviceIp}:8001/api/v2/",
		timeout: 3
	]
	asynchttpGet("onPollParse", sendCmdParams)
}

def onPollParse(resp, data) {
	def powerState
	def onOff = "on"
	if (resp.status == 200) {
		powerState = new JsonSlurper().parseText(resp.data).device.PowerState
	} else {
		powerState = "notConnected"
	}
	if (powerState == "on") {
		state.standbyTest = false
		if (getDataValue("driverVersion") != driverVer()) {
			logInfo("Auto Configuring changes to this TV.")
			updated()
			pauseExecution(3000)
		}
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
	}
		
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff)
		if (onOff == "on") {
			getArtModeStatus()
			runIn(4, setPowerOnMode)
		}
		logInfo("onPollParse: [switch: ${onOff}, powerState: ${powerState}]")
	}
}

//	===== Capability Switch =====
def on() {
	def powerState = getPowerState()
	logInfo("on: [powerState = ${powerState}]")
	unschedule("onPoll")
	runIn(60, setOnPollInterval)
	if (powerState == "standby") {
		//	if power state is standby, WoL will not work, but power key will usually.
		sendKey("POWER")
	} else if (powerState == "notConnected") {
		//	wolMac is that typically ised for Hubitat.
		def wolMac = getDataValue("alternateWolMac")
		def cmd = "FFFFFFFFFFFF$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac"
		wol = new hubitat.device.HubAction(cmd,
										   hubitat.device.Protocol.LAN,
										   [type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
											destinationAddress: "255.255.255.255:7",
											encoding: hubitat.device.HubAction.Encoding.HEX_STRING])
		sendHubCommand(wol)
	} else {
		//	If powerState is on, exit.  It is already on.
		return
	}
	if (device.currentValue("switch") == "off") {
		sendEvent(name: "switch", value: "on")
	}
	runIn(2, getArtModeStatus)
	runIn(5, setPowerOnMode)
}

def getPowerState() {
	def powerState
	try {
		httpGet([uri: "http://${deviceIp}:8001/api/v2/", timeout: 3]) { resp ->
			powerState = resp.data.device.PowerState
		}
	} catch (error) {
		powerState = "notConnected"
	}
	return powerState
}

def setPowerOnMode() {
	logInfo("setPowerOnMode: [tvPwrOnMode: ${tvPwrOnMode}]")
	if(tvPwrOnMode == "ART_MODE") {
		artMode()
	} else if (tvPwrOnMode == "Ambient") {
		ambientMode()
	}
}

def off() {
	logInfo("off: [frameTv: ${getDataValue("frameTv")}]")
	unschedule("onPoll")
	runIn(60, setOnPollInterval)
	if (getDataValue("frameTv") == "true") {
		sendKey("POWER", "Press")
		pauseExecution(4000)
		sendKey("POWER", "Release")
	} else {
		sendKey("POWER")
	}
	sendEvent(name: "switch", value: "off")
	runIn(1, close)
}

//	===== WEBSOCKET =====
//	== ART/Ambient Mode
def artMode() {
	def artModeStatus = device.currentValue("artModeStatus")
	def logData = [artModeStatus: artModeStatus, artModeWs: state.artModeWs]
	if (getDataValue("frameTv") != "true") {
		logData << [status: "Not a Frame TV"]
	} else if (artModeStatus == "on") {
		logData << [status: "artMode already set"]
	} else {
		if (state.artModeWs) {
			def data = [value:"on",
						request:"set_artmode_status",
						id: "${getDataValue("uuid")}"]
			data = JsonOutput.toJson(data)
			artModeCmd(data)
			logData << [status: "Sending artMode WS Command"]
		} else {
			sendKey("POWER")
			logData << [status: "Sending Power WS Command"]
			if (artModeStatus == "none") {
				logData << [NOTE: "SENT BLIND. Enable SmartThings interface!"]
			}
		}
		runIn(10, getArtModeStatus)
	}
	logInfo("artMode: ${logData}")
}

def getArtModeStatus() {
	if (getDataValue("frameTv") == "true") {
		if (state.artModeWs) {
			def data = [request:"get_artmode_status",
						id: "${getDataValue("uuid")}"]
			data = JsonOutput.toJson(data)
			artModeCmd(data)
		} else {
			refresh()
		}
	}
}

def artModeCmd(data) {
	def cmdData = [method:"ms.channel.emit",
				   params:[data:"${data}",
						   to:"host",
						   event:"art_app_request"]]
	cmdData = JsonOutput.toJson(cmdData)
	sendMessage("frameArt", cmdData)
}

def ambientMode() {
	sendKey("AMBIENT")
	runIn(10, refresh)
}

//	== Remote Commands
def mute() {
	sendKey("MUTE")
	runIn(5, refresh)
}

def unmute() {
	sendKey("MUTE")
	runIn(5, refresh)
}

def volumeUp() { 
	sendKey("VOLUP") 
	runIn(5, refresh)
}

def volumeDown() { 
	sendKey("VOLDOWN")
	runIn(5, refresh)
}

def play() { sendKey("PLAY") }

def pause() { sendKey("PAUSE") }

def stop() { sendKey("STOP") }

def exit() {
	sendKey("EXIT")
	runIn(5, refresh)
}

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
	runIn(5, refresh)
}

def hdmi() {
	sendKey("HDMI")
	runIn(5, refresh)
}

def channelList() { sendKey("CH_LIST") }

def channelUp() { 
	sendKey("CHUP") 
	runIn(5, refresh)
}

def nextTrack() { channelUp() }

def channelDown() { 
	sendKey("CHDOWN") 
	runIn(5, refresh)
}

def previousTrack() { channelDown() }

def previousChannel() { 
	sendKey("PRECH") 
	runIn(5, refresh)
}

def showMessage() { logWarn("showMessage: not implemented") }

//	== WebSocket Communications / Parse
def sendKey(key, cmd = "Click") {
	key = "KEY_${key.toUpperCase()}"
	def data = [method:"ms.remote.control",
				params:[Cmd:"${cmd}",
						DataOfCmd:"${key}",
						TypeOfRemote:"SendRemoteKey"]]
	sendMessage("remote", JsonOutput.toJson(data) )
}

def sendMessage(funct, data) {
	def wsStat = device.currentValue("wsStatus")
	logDebug("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${state.currentFunction}")
	if (wsStat != "open" || state.currentFunction != funct) {
		connect(funct)
		pauseExecution(600)
	}
	interfaces.webSocket.sendMessage(data)
	runIn(30, close)
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
		} else {
			logWarn("sendMessage: Invalid Function = ${funct}, tokenSupport = true")
		}
	} else {
		if (funct == "remote") {
			url = "ws://${deviceIp}:8001/api/v2/channels/samsung.remote.control?name=${name}"
		} else if (funct == "frameArt") {
			url = "ws://${deviceIp}:8001/api/v2/channels/com.samsung.art-app?name=${name}"
		} else {
			logWarn("sendMessage: Invalid Function = ${funct}, tokenSupport = false")
		}
	}
	state.currentFunction = funct
	interfaces.webSocket.connect(url, ignoreSSLIssues: true)
}

def close() {
	logDebug("close")
	interfaces.webSocket.close()
}

def webSocketStatus(message) {
	def status
	if (message == "status: open") {
		status = "open"
	} else if (message == "status: closing") {
		status = "closed"
		state.currentFunction = "close"
	} else if (message.substring(0,7) == "failure") {
		status = "closed-failure"
		state.currentFunction = "close"
		close()
	}
	sendEvent(name: "wsStatus", value: status)
	logDebug("webSocketStatus: [status: ${status}, message: ${message}]")
}

def parse(resp) {
	def logData = [:]
	try {
		resp = parseJson(resp)
		def event = resp.event
		logData << [EVENT: event]
		switch(event) {
			case "ms.channel.connect":
				def newToken = resp.data.token
				if (newToken != null && newToken != state.token) {
					state.token = newToken
					logData << [TOKEN: "updated"]
				} else {
					logData << [TOKEN: "noChange"]
				}
				break
			case "d2d_service_message":
				def data = parseJson(resp.data)
				if (data.event == "artmode_status" ||
					data.event == "art_mode_changed") {
					def status = data.value
					if (status == null) { status = data.status }
					sendEvent(name: "artModeStatus", value: status)
					logData << [artModeStatus: status]
					state.artModeWs = true
				}
				break
			case "ms.error":
				logData << [STATUS: "Error, Closing WS",DATA: resp.data]
				close()
				break
			case "ms.channel.ready":
			case "ms.channel.clientConnect":
			case "ms.channel.clientDisconnect":
			case "ms.remote.touchEnable":
			case "ms.remote.touchDisable":
				break
			default:
				logData << [STATUS: "Not Parsed", DATA: resp.data]
				break
		}
		logDebug("parse: ${logData}")
	} catch (e) {
		logData << [STATUS: "unhandled", ERROR: e]
		logWarn("parse: ${logData}")
	}
}

//	===== SMART TV APP CONTROL =====
def appOpenByName(appName) {
	def thisApp = findThisApp(appName)
	def logData = [appName: thisApp[0], appId: thisApp[1]]
	if (thisApp[1] != "none") {
		[status: "execute appOpenByCode"]
		appOpenByCode(thisApp[1])
	} else {
		def url = "http://${deviceIp}:8080/ws/apps/${appName}"
		try {
			httpPost(url, "") { resp ->
				sendEvent(name: "currentApp", value: respData.name)
				logData << [status: "OK", currentApp: respData.name]
			}
			runIn(5, refresh)
		} catch (err) {
			logData << [status: "appName Not Found", data: err]
			logWarn("appOpenByName: ${logData}")
		}
	}
	logDebug("appOpenByName: ${logData}")
}

def appOpenByCode(appId) {
	def appName = state.appData.find { it.value == appId }
	if (appName != null) {
		appName = appName.key
	}
	def logData = [appId: appId, appName: appName]
	def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}"
	try {
		httpPost(uri, body) { resp ->
			if (appName == null) {
				runIn(3, getAppData, [data: appId])
			} else {
				sendEvent(name: "currentApp", value: appName)
				logData << [currentApp: appName]
			}
			runIn(5, refresh)
			logData << [status: "OK", data: resp.data]
		}
	} catch (err) {
		logData << [status: "appId Not Found", data: err]
		logWarn("appOpenByCode: ${logData}")
	}
	logDebug("appOpenByCode: ${logData}")
}

def appClose() {
	def appId
	def appName = device.currentValue("currentApp")
	if (appName == " " || appName == null) {
		logWarn("appClose: [status: FAILED, reason: appName not set.]")
		return
	}
	def thisApp = findThisApp(appName)
	appId = thisApp[1]
	def logData = [appName: appName, appId: appId]
	Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${appId}",
				  timeout: 3]
	try {
		asynchttpDelete("appCloseParse", params, [appId: appId])
		logData: [status: "OK"]
		exit()
	} catch (err) {
		logData: [status: "FAILED", data: err]
		logWarn("appClose: ${logData}")
	}
	logDebug("appClose: ${logData}")
}

def appCloseParse(resp, data) {
	def logData = [appId: data.appId]
	if (resp.status == 200) {
		sendEvent(name: "currentApp", value: " ")
		logData << [status: "OK"]
	} else {
		logData << [status: "FAILED", status: resp.status]
		logWarn("appCloseParse: ${logData}")
	}
	logDebug("appCloseParse: ${logData}")
}

def findThisApp(appName) {
	def thisApp = state.appData.find { it.key.toLowerCase().contains(appName.toLowerCase()) }
	def appId = "none"
	if (thisApp != null) {
		appName = thisApp.key
		appId = thisApp.value
	} else {
		//	Handle special case for browser (using switch to add other cases.
		switch(appName.toLowerCase()) {
			case "browser":
				appId = "org.tizen.browser"
				appName = "Browser"
				break
			case "youtubetv":
				appId = "PvWgqxV3Xa.YouTubeTV"
				appName = "YouTube TV"
				break
			case "netflix":
				appId = "3201907018807"
				appName = "Netflix"
				break
			case "youtube":
				appId = "9Ur5IzDKqV.TizenYouTube"
				appName = "YouTube"
				break
			case "amazoninstantvideo":
				appId = "3201910019365"
				appName = "Prime Video"
				break
			default:
				logWarn("findThisApp: ${appName} not found in appData")
		}
	}
	return [appName, appId]
}

def getAppData(appId) {
	def logData = [appId: appId]
	def thisApp = state.appData.find { it.value == appId }
	if (thisApp && !state.appIdIndex) {
		sendEvent(name: "currentApp", value: thisApp.key)
		logData << [currentApp: thisApp.key]
	} else {
		Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${appId}",
					  timeout: 3]
		try {
			asynchttpGet("getAppDataParse", params, [appId: appId])
		} catch (err) {
			logData: [status: "FAILED", data: err]
		}
	}
	logDebug("getAppData: ${logData}")
}

def getAppDataParse(resp, data) {
	def logData = [appId: data.appId]
	if (resp.status == 200) {
		def respData = new JsonSlurper().parseText(resp.data)
		logData << [resp: respData]
		state.appData << ["${respData.name}": respData.id]
		if(!state.appIdIndex && device.currentValue("currentApp") != currApp) {
			sendEvent(name: "currentApp", value: respData.name)
			logData << [currentApp: respData.name]
		}
	} else {
		logData << [status: "FAILED", reason: "${resp.status} response from TV"]
	}
	logDebug("getAppDataParse: ${logData}")
}

def updateAppCodes() {
	if (!state.appData) { state.appData = [:] }
	if (device.currentValue("switch") == "on") {
		logInfo("updateAppCodes: [currentDbSize: ${state.appData.size()}, availableCodes: ${appIdList().size()}]")
		unschedule("onPoll")
		runIn(900, setOnPollInterval)
		state.appIdIndex = 0
		findNextApp()
	} else {
		logWarn("getAppList: [status: FAILED, reason: tvOff]")
	}
	device.updateSetting("resetAppCodes", [type:"bool", value: false])
	device.updateSetting("findAppCodes", [type:"bool", value: false])
}

def findNextApp() {
	def appIds = appIdList()
	def logData = [:]
	if (state.appIdIndex < appIds.size()) {
		def nextApp = appIds[state.appIdIndex]
		state.appIdIndex += 1
		getAppData(nextApp)
		runIn(6, findNextApp)
	} else {
		runIn(20, setOnPollInterval)
		logData << [status: "Complete", appIdsScanned: state.appIdIndex]
		logData << [totalApps: state.appData.size(), appData: state.appData]
		state.remove("appIdIndex")
		logInfo("findNextApp: ${logData}")
	}
}

def appIdList() {
	def appList = [
		"kk8MbItQ0H.VUDU", "vYmY3ACVaa.emby", "ZmmGjO6VKO.slingtv", "MCmYXNxgcu.DisneyPlus",
		"PvWgqxV3Xa.YouTubeTV", "LBUAQX1exg.Hulu", "AQKO41xyKP.AmazonAlexa", "3KA0pm7a7V.TubiTV",
		"cj37Ni3qXM.HBONow", "gzcc4LRFBF.Peacock", "9Ur5IzDKqV.TizenYouTube", "BjyffU0l9h.Stream",
		"3202203026841", "3202103023232", "3202103023185", "3202012022468", "3202012022421",
		"3202011022316", "3202011022131", "3202010022098", "3202009021877", "3202008021577",
		"3202008021462", "3202008021439", "3202007021336", "3202004020674", "3202004020626",
		"3202003020365", "3201910019457", "3201910019449", "3201910019420", "3201910019378",
		"3201910019365", "3201910019354", "3201909019271", "3201909019175", "3201908019041",
		"3201908019022", "3201907018807", "3201907018786", "3201907018784", "3201906018693",
		"3201901017768", "3201901017640", "3201812017479", "3201810017091", "3201810017074",
		"3201807016597", "3201806016432", "3201806016390", "3201806016381", "3201805016367",
		"3201803015944", "3201803015934", "3201803015869", "3201711015226", "3201710015067",
		"3201710015037", "3201710015016", "3201710014874", "3201710014866", "3201707014489",
		"3201706014250", "3201706012478", "3201704012212", "3201704012147", "3201703012079",
		"3201703012065", "3201703012029", "3201702011851", "3201612011418", "3201611011210",
		"3201611011005", "3201611010983", "3201608010385", "3201608010191", "3201607010031",
		"3201606009910", "3201606009798", "3201606009684", "3201604009182", "3201603008746",
		"3201603008210", "3201602007865", "3201601007670", "3201601007625", "3201601007230",
		"3201512006963", "3201512006785", "3201511006428", "3201510005981", "3201506003488",
		"3201506003486", "3201506003175", "3201504001965", "121299000612", "121299000101",
		"121299000089", "111399002220", "111399002034", "111399000741", "111299002148",
		"111299001912", "111299000769", "111012010001", "11101200001", "11101000407",
		"11091000000"
	]
	return appList
}

def appRunBrowser() { appOpenByName("Browser") }

def appRunYouTube() { appOpenByName("YouTube") }

def appRunNetflix() { appOpenByName("Netflix") }

def appRunPrimeVideo() { appOpenByName("Prime Video") }

def appRunYouTubeTV() { appOpenByName("YouTubeTV") }

def appRunHulu() { appOpenByName("Hulu") }

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

def stUpdate() {
	def stData = [:]
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
			sendEvent(name: "volume", value: 0)
			sendEvent(name: "level", value: 0)
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
		runIn(1, deviceSetup)
		stData << [stPollInterval: stPollInterval]
	}
	return stData
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
	if (logEnable) {
		def quickLog = [:]
		try {
			quickLog << [
				switch: device.currentValue("switch"),
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
	}

	if (device.currentValue("switch") == "on") {
		Integer volume = mainData.audioVolume.volume.value.toInteger()
		if (device.currentValue("volume").toInteger() != volume) {
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
		if (tvChannel == "" || tvChannel == null) { tvChannel = " " }
		String tvChannelName = mainData.tvChannel.tvChannelName.value
		if (tvChannelName == "") { tvChannelName = " " }
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

//	===== Library Integration =====


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
	if (logEnable) { // library marker davegut.commonLogging, line 44
		log.debug "${device.displayName}-${driverVer()}: ${msg}" // library marker davegut.commonLogging, line 45
	} // library marker davegut.commonLogging, line 46
} // library marker davegut.commonLogging, line 47

def logWarn(msg) { log.warn "${device.displayName}-${driverVer()}: ${msg}" } // library marker davegut.commonLogging, line 49

// ~~~~~ end include (1170) davegut.commonLogging ~~~~~
