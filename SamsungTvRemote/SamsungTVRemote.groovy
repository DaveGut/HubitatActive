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
def driverVer() { return "4.0-2a" }
import groovy.json.JsonOutput

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
		command "artMode"				//	Toggles artMode
		attribute "artModeStatus", "string"
		command "ambientMode"
		command "appOpenByName", ["string"]
		command "appOpenByCode", ["string"]
		command "appClose"
		//	Remote Control Keys (samsungTV-Keys)
		command "pause"				//	Only work on TV Players
		command "play"					//	Only work on TV Players
		command "stop"					//	Only work on TV Players
		command "sendKey", ["string"]	//	Send entered key. eg: HDMI
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
		capability "PushableButton"
		
		//	SmartThings Functions (library samsungTV=ST)
		command "toggleInputSource", [[name: "SmartThings Function"]]
		command "toggleSoundMode", [[name: "SmartThings Function"]]
		command "togglePictureMode", [[name: "SmartThings Function"]]
		command "setTvChannel", ["SmartThings Function"]
		attribute "tvChannel", "string"
		attribute "tvChannelName", "string"
		command "setInputSource", ["SmartThings Function"]
		attribute "inputSource", "string"
		attribute "inputSources", "string"
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
		
		//	for media player tile
		attribute "transportStatus", "string"
		attribute "level", "NUMBER"
		attribute "trackDescription", "string"
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
			delayBetween(cmds, 500)
		}
		if (logEnable) { runIn(1800, debugLogOff) }
		updStatus << [logEnable: logEnable, infoLog: infoLog]
		updStatus << [setOnPollInterval: setOnPollInterval()]
		updStatus << [stUpdate: stUpdate()]
	}
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
		}
		def pollInterval = stPollInterval
		if (pollInterval == null) { 
			pollInterval = "15"
			device.updateSetting("pollInterval", [type:"enum", value: "15"])
		}
		switch(pollInterval) {
			case "1" : runEvery1Minute(stRefresh); break
			case "5" : runEvery5Minutes(stRefresh); break
			case "15" : runEvery15Minutes(stRefresh); break
			case "30" : runEvery30Minutes(stRefresh); break
			default: runEvery15Minutes(stRefresh)
		}
		runIn(1, deviceSetup)
		stData << [stRefreshInterval: pollInterval]
	}
	return stData
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
		if (!state.configured) {
			logInfo("Auto Configuring changes to this TV.")
			updated()
			pauseExecution(2000)
		}
		getArtModeStatus()
	} else {
		if (device.currentValue("switch") == "on") {
			if (!state.standbyTest) {
				state.standbyTest = true
				close()
				runIn(5, onPoll)
			} else {
				state.standbyTest = false
				onOff = "off"
			}
		} else {
			onOff = "off"
		}
	}
		
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff)
		if (onOff == "on") {
			runIn(4, setPowerOnMode)
		}
		logInfo("onPollParse: [switch: ${onOff}, powerState: ${powerState}]")
	}
}
def stRefresh() {
	if (connectST && device.currentValue("switch") == "on") {
		refresh()
	}
}

//	===== Switch Commands =====
def on() {
	unschedule("onPoll")
	runIn(60, setOnPollInterval)
	def wolMac = getDataValue("alternateWolMac")
	def cmd = "FFFFFFFFFFFF$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac"
	wol = new hubitat.device.HubAction(cmd,
									   hubitat.device.Protocol.LAN,
									   [type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
										destinationAddress: "255.255.255.255:7",
										encoding: hubitat.device.HubAction.Encoding.HEX_STRING])
	sendHubCommand(wol)
	if (device.currentValue("switch") == "off") {
		runIn(2, getArtModeStatus)
		runIn(5, setPowerOnMode)
	}
	sendEvent(name: "switch", value: "on")
	logInfo("on")
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
	unschedule("onPoll")
	runIn(60, setOnPollInterval)
	sendKey("POWER", "Press")
	pauseExecution(5000)
	sendKey("POWER", "Release")
	sendEvent(name: "switch", value: "off")
	runIn(1, close)
	logInfo("off")
}

//	===== Art Mode / Ambient Mode (under evaluation) =====
def artMode() {
	def artModeStatus = device.currentValue("artModeStatus")
	def logData = [artModeStatus: artModeStatus, artModeWs: state.artModeWs]
	if (getDataValue("frameTv") != "true") {
		logData << [status: "Not a Frame TV"]
	} else if (artModeStatus == "on") {
		logData << [status: "artMode already set"]
	} else {
		if (state.artModeWs) {
			def data = [value:"${onOff}",
						request:"set_artmode_status",
						id: "${getDataValue("uuid")}"]
			data = JsonOutput.toJson(data)
			artModeCmd(data)
			logData << [status: "Sending artMode WS Command"]
		} else {
			sendKey("POWER")
			logData << [status: "Sending Power WS Command"]
			if (artModeStatus == "none") {
				logData << [NOTE: "SENT BLING. Enable SmartThings interface!"]
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
			stRefresh()
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
	runIn(10, stRefresh)
}

//	===== Smart App Control (under evaluation) =====
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
			runIn(5, stRefresh)
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
			runIn(5, stRefresh)
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
		"kk8MbItQ0H.VUDU",
		"vYmY3ACVaa.emby",
		"ZmmGjO6VKO.slingtv",
		"MCmYXNxgcu.DisneyPlus",
		"PvWgqxV3Xa.YouTubeTV",
		"LBUAQX1exg.Hulu",
		"AQKO41xyKP.AmazonAlexa",
		"3KA0pm7a7V.TubiTV",
		"cj37Ni3qXM.HBONow",
		"gzcc4LRFBF.Peacock",
		"9Ur5IzDKqV.TizenYouTube",
		"BjyffU0l9h.Stream",
		"3202203026841",
		"3202103023232",
		"3202103023185",
		"3202012022468",
		"3202012022421",
		"3202011022316",
		"3202011022131",
		"3202010022098",
		"3202009021877",
		"3202008021577",
		"3202008021462",
		"3202008021439",
		"3202007021336",
		"3202004020674",
		"3202004020626",
		"3202003020365",
		"3201910019457",
		"3201910019449",
		"3201910019420",
		"3201910019378",
		"3201910019365",
		"3201910019354",
		"3201909019271",
		"3201909019175",
		"3201908019041",
		"3201908019022",
		"3201907018807",
		"3201907018786",
		"3201907018784",
		"3201906018693",
		"3201901017768",
		"3201901017640",
		"3201812017479",
		"3201810017091",
		"3201810017074",
		"3201807016597",
		"3201806016432",
		"3201806016390",
		"3201806016381",
		"3201805016367",
		"3201803015944",
		"3201803015934",
		"3201803015869",
		"3201711015226",
		"3201710015067",
		"3201710015037",
		"3201710015016",
		"3201710014874",
		"3201710014866",
		"3201707014489",
		"3201706014250",
		"3201706012478",
		"3201704012212",
		"3201704012147",
		"3201703012079",
		"3201703012065",
		"3201703012029",
		"3201702011851",
		"3201612011418",
		"3201611011210",
		"3201611011005",
		"3201611010983",
		"3201608010385",
		"3201608010191",
		"3201607010031",
		"3201606009910",
		"3201606009798",
		"3201606009684",
		"3201604009182",
		"3201603008746",
		"3201603008210",
		"3201602007865",
		"3201601007670",
		"3201601007625",
		"3201601007230",
		"3201512006963",
		"3201512006785",
		"3201511006428",
		"3201510005981",
		"3201506003488",
		"3201506003486",
		"3201506003175",
		"3201504001965",
		"121299000612",
		"121299000101",
		"121299000089",
		"111399002220",
		"111399002034",
		"111399000741",
		"111299002148",
		"111299001912",
		"111299000769",
		"111012010001",
		"11101200001",
		"11101000407",
		"11091000000"
	]
	return appList
}

//	===== WebSocket Implementation =====
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

//	===== Library Integration =====






def simulate() { return false }

// ~~~~~ start include (1216) davegut.samsungTV-Apps ~~~~~
library ( // library marker davegut.samsungTV-Apps, line 1
	name: "samsungTV-Apps", // library marker davegut.samsungTV-Apps, line 2
	namespace: "davegut", // library marker davegut.samsungTV-Apps, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTV-Apps, line 4
	description: "Samsung TV Smart App Interface", // library marker davegut.samsungTV-Apps, line 5
	category: "utilities", // library marker davegut.samsungTV-Apps, line 6
	documentationLink: "" // library marker davegut.samsungTV-Apps, line 7
) // library marker davegut.samsungTV-Apps, line 8

def appRunBrowser() { appOpenByName("Browser") } // library marker davegut.samsungTV-Apps, line 10
def appRunYouTube() { appOpenByName("YouTube") } // library marker davegut.samsungTV-Apps, line 11
def appRunNetflix() { appOpenByName("Netflix") } // library marker davegut.samsungTV-Apps, line 12
def appRunPrimeVideo() { appOpenByName("Prime Video") } // library marker davegut.samsungTV-Apps, line 13
def appRunYouTubeTV() { appOpenByName("YouTubeTV") } // library marker davegut.samsungTV-Apps, line 14
def appRunHulu() { appOpenByName("Hulu") } // library marker davegut.samsungTV-Apps, line 15

// ~~~~~ end include (1216) davegut.samsungTV-Apps ~~~~~

// ~~~~~ start include (1215) davegut.samsungTV-ST ~~~~~
library ( // library marker davegut.samsungTV-ST, line 1
	name: "samsungTV-ST", // library marker davegut.samsungTV-ST, line 2
	namespace: "davegut", // library marker davegut.samsungTV-ST, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTV-ST, line 4
	description: "Samsung TV SmartThings Interface", // library marker davegut.samsungTV-ST, line 5
	category: "utilities", // library marker davegut.samsungTV-ST, line 6
	documentationLink: "" // library marker davegut.samsungTV-ST, line 7
) // library marker davegut.samsungTV-ST, line 8

//	===== SmartThings Implementation ===== // library marker davegut.samsungTV-ST, line 10
def setLevel(level) { setVolume(level) } // library marker davegut.samsungTV-ST, line 11
def setVolume(volume) { // library marker davegut.samsungTV-ST, line 12
	def cmdData = [ // library marker davegut.samsungTV-ST, line 13
		component: "main", // library marker davegut.samsungTV-ST, line 14
		capability: "audioVolume", // library marker davegut.samsungTV-ST, line 15
		command: "setVolume", // library marker davegut.samsungTV-ST, line 16
		arguments: [volume.toInteger()]] // library marker davegut.samsungTV-ST, line 17
	deviceCommand(cmdData) // library marker davegut.samsungTV-ST, line 18
} // library marker davegut.samsungTV-ST, line 19
def togglePictureMode() { // library marker davegut.samsungTV-ST, line 20
	//	requires state.pictureModes // library marker davegut.samsungTV-ST, line 21
	def pictureModes = state.pictureModes // library marker davegut.samsungTV-ST, line 22
	def totalModes = pictureModes.size() // library marker davegut.samsungTV-ST, line 23
	def currentMode = device.currentValue("pictureMode") // library marker davegut.samsungTV-ST, line 24
	def modeNo = pictureModes.indexOf(currentMode) // library marker davegut.samsungTV-ST, line 25
	def newModeNo = modeNo + 1 // library marker davegut.samsungTV-ST, line 26
	if (newModeNo == totalModes) { newModeNo = 0 } // library marker davegut.samsungTV-ST, line 27
	def newPictureMode = pictureModes[newModeNo] // library marker davegut.samsungTV-ST, line 28
	setPictureMode(newPictureMode) // library marker davegut.samsungTV-ST, line 29
} // library marker davegut.samsungTV-ST, line 30
def setPictureMode(pictureMode) { // library marker davegut.samsungTV-ST, line 31
	def cmdData = [ // library marker davegut.samsungTV-ST, line 32
		component: "main", // library marker davegut.samsungTV-ST, line 33
		capability: "custom.picturemode", // library marker davegut.samsungTV-ST, line 34
		command: "setPictureMode", // library marker davegut.samsungTV-ST, line 35
		arguments: [pictureMode]] // library marker davegut.samsungTV-ST, line 36
	deviceCommand(cmdData) // library marker davegut.samsungTV-ST, line 37
} // library marker davegut.samsungTV-ST, line 38
def toggleSoundMode() { // library marker davegut.samsungTV-ST, line 39
	def soundModes = state.soundModes // library marker davegut.samsungTV-ST, line 40
	def totalModes = soundModes.size() // library marker davegut.samsungTV-ST, line 41
	def currentMode = device.currentValue("soundMode") // library marker davegut.samsungTV-ST, line 42
	def modeNo = soundModes.indexOf(currentMode) // library marker davegut.samsungTV-ST, line 43
	def newModeNo = modeNo + 1 // library marker davegut.samsungTV-ST, line 44
	if (newModeNo == totalModes) { newModeNo = 0 } // library marker davegut.samsungTV-ST, line 45
	def soundMode = soundModes[newModeNo] // library marker davegut.samsungTV-ST, line 46
	setSoundMode(soundMode) // library marker davegut.samsungTV-ST, line 47
} // library marker davegut.samsungTV-ST, line 48
def setSoundMode(soundMode) {  // library marker davegut.samsungTV-ST, line 49
	def cmdData = [ // library marker davegut.samsungTV-ST, line 50
		component: "main", // library marker davegut.samsungTV-ST, line 51
		capability: "custom.soundmode", // library marker davegut.samsungTV-ST, line 52
		command: "setSoundMode", // library marker davegut.samsungTV-ST, line 53
		arguments: [soundMode]] // library marker davegut.samsungTV-ST, line 54
	deviceCommand(cmdData) // library marker davegut.samsungTV-ST, line 55
} // library marker davegut.samsungTV-ST, line 56
def toggleInputSource() { // library marker davegut.samsungTV-ST, line 57
	def inputSources = state.supportedInputs // library marker davegut.samsungTV-ST, line 58
	def totalSources = inputSources.size() // library marker davegut.samsungTV-ST, line 59
	def currentSource = device.currentValue("mediaInputSource") // library marker davegut.samsungTV-ST, line 60
	def sourceNo = inputSources.indexOf(currentSource) // library marker davegut.samsungTV-ST, line 61
	def newSourceNo = sourceNo + 1 // library marker davegut.samsungTV-ST, line 62
	if (newSourceNo == totalSources) { newSourceNo = 0 } // library marker davegut.samsungTV-ST, line 63
	def inputSource = inputSources[newSourceNo] // library marker davegut.samsungTV-ST, line 64
	setInputSource(inputSource) // library marker davegut.samsungTV-ST, line 65
} // library marker davegut.samsungTV-ST, line 66
def setInputSource(inputSource) { // library marker davegut.samsungTV-ST, line 67
	def cmdData = [ // library marker davegut.samsungTV-ST, line 68
		component: "main", // library marker davegut.samsungTV-ST, line 69
		capability: "mediaInputSource", // library marker davegut.samsungTV-ST, line 70
		command: "setInputSource", // library marker davegut.samsungTV-ST, line 71
		arguments: [inputSource]] // library marker davegut.samsungTV-ST, line 72
	deviceCommand(cmdData) // library marker davegut.samsungTV-ST, line 73
} // library marker davegut.samsungTV-ST, line 74
def setTvChannel(newChannel) { // library marker davegut.samsungTV-ST, line 75
	def cmdData = [ // library marker davegut.samsungTV-ST, line 76
		component: "main", // library marker davegut.samsungTV-ST, line 77
		capability: "tvChannel", // library marker davegut.samsungTV-ST, line 78
		command: "setTvChannel", // library marker davegut.samsungTV-ST, line 79
		arguments: [newChannel]] // library marker davegut.samsungTV-ST, line 80
	deviceCommand(cmdData) // library marker davegut.samsungTV-ST, line 81
} // library marker davegut.samsungTV-ST, line 82

//	===== Parse and Update TV SmartThings Data ===== // library marker davegut.samsungTV-ST, line 84
def distResp(resp, data) { // library marker davegut.samsungTV-ST, line 85
	def respLog = [:] // library marker davegut.samsungTV-ST, line 86
	if (resp.status == 200) { // library marker davegut.samsungTV-ST, line 87
		try { // library marker davegut.samsungTV-ST, line 88
			def respData = new JsonSlurper().parseText(resp.data) // library marker davegut.samsungTV-ST, line 89
			if (data.reason == "deviceSetup") { // library marker davegut.samsungTV-ST, line 90
				deviceSetupParse(respData.components.main) // library marker davegut.samsungTV-ST, line 91
			} // library marker davegut.samsungTV-ST, line 92
			statusParse(respData.components.main) // library marker davegut.samsungTV-ST, line 93
		} catch (err) { // library marker davegut.samsungTV-ST, line 94
			respLog << [status: "ERROR", // library marker davegut.samsungTV-ST, line 95
						errorMsg: err, // library marker davegut.samsungTV-ST, line 96
						respData: resp.data] // library marker davegut.samsungTV-ST, line 97
		} // library marker davegut.samsungTV-ST, line 98
	} else { // library marker davegut.samsungTV-ST, line 99
		respLog << [status: "ERROR", // library marker davegut.samsungTV-ST, line 100
					httpCode: resp.status, // library marker davegut.samsungTV-ST, line 101
					errorMsg: resp.errorMessage] // library marker davegut.samsungTV-ST, line 102
	} // library marker davegut.samsungTV-ST, line 103
	if (respLog != [:]) { // library marker davegut.samsungTV-ST, line 104
		logWarn("distResp: ${respLog}") // library marker davegut.samsungTV-ST, line 105
	} // library marker davegut.samsungTV-ST, line 106
} // library marker davegut.samsungTV-ST, line 107

def deviceSetupParse(mainData) { // library marker davegut.samsungTV-ST, line 109
	def setupData = [:] // library marker davegut.samsungTV-ST, line 110
	def supportedInputs =  mainData.mediaInputSource.supportedInputSources.value // library marker davegut.samsungTV-ST, line 111
	state.supportedInputs = supportedInputs // library marker davegut.samsungTV-ST, line 112
	setupData << [supportedInputs: supportedInputs] // library marker davegut.samsungTV-ST, line 113

	def pictureModes = mainData["custom.picturemode"].supportedPictureModes.value // library marker davegut.samsungTV-ST, line 115
	state.pictureModes = pictureModes // library marker davegut.samsungTV-ST, line 116
	setupData << [pictureModes: pictureModes] // library marker davegut.samsungTV-ST, line 117

	def soundModes =  mainData["custom.soundmode"].supportedSoundModes.value // library marker davegut.samsungTV-ST, line 119
	state.soundModes = soundModes // library marker davegut.samsungTV-ST, line 120
	setupData << [soundModes: soundModes] // library marker davegut.samsungTV-ST, line 121

	logInfo("deviceSetupParse: ${setupData}") // library marker davegut.samsungTV-ST, line 123
} // library marker davegut.samsungTV-ST, line 124

def statusParse(mainData) { // library marker davegut.samsungTV-ST, line 126
	if (stTestData) { // library marker davegut.samsungTV-ST, line 127
		log.error mainData // library marker davegut.samsungTV-ST, line 128
		device.updateSetting("stTestData", [type:"bool", value: false]) // library marker davegut.samsungTV-ST, line 129
	} // library marker davegut.samsungTV-ST, line 130
	def stData = [:] // library marker davegut.samsungTV-ST, line 131
	if (logEnable) { // library marker davegut.samsungTV-ST, line 132
		def quickLog = [:] // library marker davegut.samsungTV-ST, line 133
		try { // library marker davegut.samsungTV-ST, line 134
			quickLog << [ // library marker davegut.samsungTV-ST, line 135
				switch: device.currentValue("switch"), // library marker davegut.samsungTV-ST, line 136
				volume: mainData.audioVolume.volume.value.toInteger(), // library marker davegut.samsungTV-ST, line 137
				mute: mainData.audioMute.mute.value, // library marker davegut.samsungTV-ST, line 138
				input: mainData.mediaInputSource.inputSource.value, // library marker davegut.samsungTV-ST, line 139
				channel: mainData.tvChannel.tvChannel.value, // library marker davegut.samsungTV-ST, line 140
				channelName: mainData.tvChannel.tvChannelName.value, // library marker davegut.samsungTV-ST, line 141
				pictureMode: mainData["custom.picturemode"].pictureMode.value, // library marker davegut.samsungTV-ST, line 142
				soundMode: mainData["custom.soundmode"].soundMode.value, // library marker davegut.samsungTV-ST, line 143
				transportStatus: mainData.mediaPlayback.playbackStatus.value] // library marker davegut.samsungTV-ST, line 144
		} catch (err) { // library marker davegut.samsungTV-ST, line 145
			quickLog << [error: ${err}, data: mainData] // library marker davegut.samsungTV-ST, line 146
		} // library marker davegut.samsungTV-ST, line 147
		logDebug("statusParse: [quickLog: ${quickLog}]") // library marker davegut.samsungTV-ST, line 148
	} // library marker davegut.samsungTV-ST, line 149
	if (device.currentValue("switch") == "on") { // library marker davegut.samsungTV-ST, line 150
		def volume = mainData.audioVolume.volume.value.toInteger() // library marker davegut.samsungTV-ST, line 151
		if (device.currentValue("volume").toInteger() != volume) { // library marker davegut.samsungTV-ST, line 152
			sendEvent(name: "volume", value: volume) // library marker davegut.samsungTV-ST, line 153
			stData << [volume: volume] // library marker davegut.samsungTV-ST, line 154
		} // library marker davegut.samsungTV-ST, line 155

		def mute = mainData.audioMute.mute.value // library marker davegut.samsungTV-ST, line 157
		if (device.currentValue("mute") != mute) { // library marker davegut.samsungTV-ST, line 158
			sendEvent(name: "mute", value: mute) // library marker davegut.samsungTV-ST, line 159
			stData << [mute: mute] // library marker davegut.samsungTV-ST, line 160
		} // library marker davegut.samsungTV-ST, line 161

		def inputSource = mainData.mediaInputSource.inputSource.value // library marker davegut.samsungTV-ST, line 163
		if (device.currentValue("inputSource") != inputSource) { // library marker davegut.samsungTV-ST, line 164
			sendEvent(name: "inputSource", value: inputSource)		 // library marker davegut.samsungTV-ST, line 165
			stData << [inputSource: inputSource] // library marker davegut.samsungTV-ST, line 166
		} // library marker davegut.samsungTV-ST, line 167

		def tvChannel = mainData.tvChannel.tvChannel.value // library marker davegut.samsungTV-ST, line 169
		if (tvChannel == "") { tvChannel = " " } // library marker davegut.samsungTV-ST, line 170
		def tvChannelName = mainData.tvChannel.tvChannelName.value // library marker davegut.samsungTV-ST, line 171
		if (tvChannelName == "") { tvChannelName = " " } // library marker davegut.samsungTV-ST, line 172
		if (device.currentValue("tvChannelName") != tvChannelName) { // library marker davegut.samsungTV-ST, line 173
			sendEvent(name: "tvChannel", value: tvChannel) // library marker davegut.samsungTV-ST, line 174
			sendEvent(name: "tvChannelName", value: tvChannelName) // library marker davegut.samsungTV-ST, line 175
			if (tvChannelName.contains(".")) { // library marker davegut.samsungTV-ST, line 176
				getAppData(tvChannelName) // library marker davegut.samsungTV-ST, line 177
			} else { // library marker davegut.samsungTV-ST, line 178
				sendEvent(name: "currentApp", value: " ") // library marker davegut.samsungTV-ST, line 179
			} // library marker davegut.samsungTV-ST, line 180
			stData << [tvChannel: tvChannel, tvChannelName: tvChannelName] // library marker davegut.samsungTV-ST, line 181
			if (getDataValue("frameTv") == "true" && !state.artModeWs) { // library marker davegut.samsungTV-ST, line 182
				def artMode = "off" // library marker davegut.samsungTV-ST, line 183
				if (tvChannelName == "art") { artMode = "on" } // library marker davegut.samsungTV-ST, line 184
				sendEvent(name: "artModeStatus", value: artMode) // library marker davegut.samsungTV-ST, line 185
			} // library marker davegut.samsungTV-ST, line 186
		} // library marker davegut.samsungTV-ST, line 187

		def trackDesc = inputSource // library marker davegut.samsungTV-ST, line 189
		if (tvChannelName != " ") { trackDesc = tvChannelName } // library marker davegut.samsungTV-ST, line 190
		if (device.currentValue("trackDescription") != trackDesc) { // library marker davegut.samsungTV-ST, line 191
			sendEvent(name: "trackDescription", value:trackDesc) // library marker davegut.samsungTV-ST, line 192
			stData << [trackDescription: trackDesc] // library marker davegut.samsungTV-ST, line 193
		} // library marker davegut.samsungTV-ST, line 194

		def pictureMode = mainData["custom.picturemode"].pictureMode.value // library marker davegut.samsungTV-ST, line 196
		if (device.currentValue("pictureMode") != pictureMode) { // library marker davegut.samsungTV-ST, line 197
			sendEvent(name: "pictureMode",value: pictureMode) // library marker davegut.samsungTV-ST, line 198
			stData << [pictureMode: pictureMode] // library marker davegut.samsungTV-ST, line 199
		} // library marker davegut.samsungTV-ST, line 200

		def soundMode = mainData["custom.soundmode"].soundMode.value // library marker davegut.samsungTV-ST, line 202
		if (device.currentValue("soundMode") != soundMode) { // library marker davegut.samsungTV-ST, line 203
			sendEvent(name: "soundMode",value: soundMode) // library marker davegut.samsungTV-ST, line 204
			stData << [soundMode: soundMode] // library marker davegut.samsungTV-ST, line 205
		} // library marker davegut.samsungTV-ST, line 206

		def transportStatus = mainData.mediaPlayback.playbackStatus.value // library marker davegut.samsungTV-ST, line 208
		if (transportStatus == null || transportStatus == "") { // library marker davegut.samsungTV-ST, line 209
			transportStatus = "stopped" // library marker davegut.samsungTV-ST, line 210
		} // library marker davegut.samsungTV-ST, line 211
		if (device.currentValue("transportStatus") != transportStatus) { // library marker davegut.samsungTV-ST, line 212
			sendEvent(name: "transportStatus", value: transportStatus) // library marker davegut.samsungTV-ST, line 213
			stData << [transportStatus: transportStatus] // library marker davegut.samsungTV-ST, line 214
		} // library marker davegut.samsungTV-ST, line 215
	} // library marker davegut.samsungTV-ST, line 216

	if (stData != [:]) { // library marker davegut.samsungTV-ST, line 218
		logInfo("statusParse: ${stData}") // library marker davegut.samsungTV-ST, line 219
	} // library marker davegut.samsungTV-ST, line 220
} // library marker davegut.samsungTV-ST, line 221

// ~~~~~ end include (1215) davegut.samsungTV-ST ~~~~~

// ~~~~~ start include (1217) davegut.samsungTV-Keys ~~~~~
library ( // library marker davegut.samsungTV-Keys, line 1
	name: "samsungTV-Keys", // library marker davegut.samsungTV-Keys, line 2
	namespace: "davegut", // library marker davegut.samsungTV-Keys, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTV-Keys, line 4
	description: "Samsung TV Keys", // library marker davegut.samsungTV-Keys, line 5
	category: "utilities", // library marker davegut.samsungTV-Keys, line 6
	documentationLink: "" // library marker davegut.samsungTV-Keys, line 7
) // library marker davegut.samsungTV-Keys, line 8

//	===== Web Socket Remote Commands ===== // library marker davegut.samsungTV-Keys, line 10
def mute() { // library marker davegut.samsungTV-Keys, line 11
	sendKey("MUTE") // library marker davegut.samsungTV-Keys, line 12
	runIn(5, stRefresh) // library marker davegut.samsungTV-Keys, line 13
} // library marker davegut.samsungTV-Keys, line 14
def unmute() { // library marker davegut.samsungTV-Keys, line 15
	sendKey("MUTE") // library marker davegut.samsungTV-Keys, line 16
	runIn(5, stRefresh) // library marker davegut.samsungTV-Keys, line 17
} // library marker davegut.samsungTV-Keys, line 18
def volumeUp() {  // library marker davegut.samsungTV-Keys, line 19
	sendKey("VOLUP")  // library marker davegut.samsungTV-Keys, line 20
	runIn(5, stRefresh) // library marker davegut.samsungTV-Keys, line 21
} // library marker davegut.samsungTV-Keys, line 22
def volumeDown() {  // library marker davegut.samsungTV-Keys, line 23
	sendKey("VOLDOWN") // library marker davegut.samsungTV-Keys, line 24
	runIn(5, stRefresh) // library marker davegut.samsungTV-Keys, line 25
} // library marker davegut.samsungTV-Keys, line 26

def play() { sendKey("PLAY") } // library marker davegut.samsungTV-Keys, line 28
def pause() { sendKey("PAUSE") } // library marker davegut.samsungTV-Keys, line 29
def stop() { sendKey("STOP") } // library marker davegut.samsungTV-Keys, line 30

def exit() { // library marker davegut.samsungTV-Keys, line 32
	sendKey("EXIT") // library marker davegut.samsungTV-Keys, line 33
	runIn(5, stRefresh) // library marker davegut.samsungTV-Keys, line 34
} // library marker davegut.samsungTV-Keys, line 35
def Return() { sendKey("RETURN") } // library marker davegut.samsungTV-Keys, line 36

def fastBack() { // library marker davegut.samsungTV-Keys, line 38
	sendKey("LEFT", "Press") // library marker davegut.samsungTV-Keys, line 39
	pauseExecution(1000) // library marker davegut.samsungTV-Keys, line 40
	sendKey("LEFT", "Release") // library marker davegut.samsungTV-Keys, line 41
} // library marker davegut.samsungTV-Keys, line 42
def fastForward() { // library marker davegut.samsungTV-Keys, line 43
	sendKey("RIGHT", "Press") // library marker davegut.samsungTV-Keys, line 44
	pauseExecution(1000) // library marker davegut.samsungTV-Keys, line 45
	sendKey("RIGHT", "Release") // library marker davegut.samsungTV-Keys, line 46
} // library marker davegut.samsungTV-Keys, line 47

def arrowLeft() { sendKey("LEFT") } // library marker davegut.samsungTV-Keys, line 49
def arrowRight() { sendKey("RIGHT") } // library marker davegut.samsungTV-Keys, line 50
def arrowUp() { sendKey("UP") } // library marker davegut.samsungTV-Keys, line 51
def arrowDown() { sendKey("DOWN") } // library marker davegut.samsungTV-Keys, line 52
def enter() { sendKey("ENTER") } // library marker davegut.samsungTV-Keys, line 53

def numericKeyPad() { sendKey("MORE") } // library marker davegut.samsungTV-Keys, line 55

def home() { sendKey("HOME") } // library marker davegut.samsungTV-Keys, line 57
def menu() { sendKey("MENU") } // library marker davegut.samsungTV-Keys, line 58
def guide() { sendKey("GUIDE") } // library marker davegut.samsungTV-Keys, line 59
def info() { sendKey("INFO") } // library marker davegut.samsungTV-Keys, line 60

def source() {  // library marker davegut.samsungTV-Keys, line 62
	sendKey("SOURCE") // library marker davegut.samsungTV-Keys, line 63
	runIn(5, stRefresh) // library marker davegut.samsungTV-Keys, line 64
} // library marker davegut.samsungTV-Keys, line 65
def hdmi() { // library marker davegut.samsungTV-Keys, line 66
	sendKey("HDMI") // library marker davegut.samsungTV-Keys, line 67
	runIn(5, stRefresh) // library marker davegut.samsungTV-Keys, line 68
} // library marker davegut.samsungTV-Keys, line 69

def channelList() { sendKey("CH_LIST") } // library marker davegut.samsungTV-Keys, line 71
def channelUp() {  // library marker davegut.samsungTV-Keys, line 72
	sendKey("CHUP")  // library marker davegut.samsungTV-Keys, line 73
	runIn(5, stRefresh) // library marker davegut.samsungTV-Keys, line 74
} // library marker davegut.samsungTV-Keys, line 75
def nextTrack() { channelUp() } // library marker davegut.samsungTV-Keys, line 76
def channelDown() {  // library marker davegut.samsungTV-Keys, line 77
	sendKey("CHDOWN")  // library marker davegut.samsungTV-Keys, line 78
	runIn(5, stRefresh) // library marker davegut.samsungTV-Keys, line 79
} // library marker davegut.samsungTV-Keys, line 80
def previousTrack() { channelDown() } // library marker davegut.samsungTV-Keys, line 81
def previousChannel() {  // library marker davegut.samsungTV-Keys, line 82
	sendKey("PRECH")  // library marker davegut.samsungTV-Keys, line 83
	runIn(5, stRefresh) // library marker davegut.samsungTV-Keys, line 84
} // library marker davegut.samsungTV-Keys, line 85

def showMessage() { logWarn("showMessage: not implemented") } // library marker davegut.samsungTV-Keys, line 87

//	===== Button Interface (facilitates dashboard integration) ===== // library marker davegut.samsungTV-Keys, line 89
def push(pushed) { // library marker davegut.samsungTV-Keys, line 90
	logDebug("push: button = ${pushed}, trigger = ${state.triggered}") // library marker davegut.samsungTV-Keys, line 91
	if (pushed == null) { // library marker davegut.samsungTV-Keys, line 92
		logWarn("push: pushed is null.  Input ignored") // library marker davegut.samsungTV-Keys, line 93
		return // library marker davegut.samsungTV-Keys, line 94
	} // library marker davegut.samsungTV-Keys, line 95
	pushed = pushed.toInteger() // library marker davegut.samsungTV-Keys, line 96
	switch(pushed) { // library marker davegut.samsungTV-Keys, line 97
		//	===== Physical Remote Commands ===== // library marker davegut.samsungTV-Keys, line 98
		case 2 : mute(); break // library marker davegut.samsungTV-Keys, line 99
		case 3 : numericKeyPad(); break // library marker davegut.samsungTV-Keys, line 100
		case 4 : Return(); break // library marker davegut.samsungTV-Keys, line 101
		case 6 : artMode(); break			//	New command.  Toggles art mode // library marker davegut.samsungTV-Keys, line 102
		case 7 : ambientMode(); break // library marker davegut.samsungTV-Keys, line 103
		case 45: ambientmodeExit(); break // library marker davegut.samsungTV-Keys, line 104
		case 8 : arrowLeft(); break // library marker davegut.samsungTV-Keys, line 105
		case 9 : arrowRight(); break // library marker davegut.samsungTV-Keys, line 106
		case 10: arrowUp(); break // library marker davegut.samsungTV-Keys, line 107
		case 11: arrowDown(); break // library marker davegut.samsungTV-Keys, line 108
		case 12: enter(); break // library marker davegut.samsungTV-Keys, line 109
		case 13: exit(); break // library marker davegut.samsungTV-Keys, line 110
		case 14: home(); break // library marker davegut.samsungTV-Keys, line 111
		case 18: channelUp(); break // library marker davegut.samsungTV-Keys, line 112
		case 19: channelDown(); break // library marker davegut.samsungTV-Keys, line 113
		case 20: guide(); break // library marker davegut.samsungTV-Keys, line 114
		case 21: volumeUp(); break // library marker davegut.samsungTV-Keys, line 115
		case 22: volumeDown(); break // library marker davegut.samsungTV-Keys, line 116
		//	===== Direct Access Functions // library marker davegut.samsungTV-Keys, line 117
		case 23: menu(); break			//	Main menu with access to system settings. // library marker davegut.samsungTV-Keys, line 118
		case 24: source(); break		//	Pops up home with cursor at source.  Use left/right/enter to select. // library marker davegut.samsungTV-Keys, line 119
		case 25: info(); break			//	Pops up upper display of currently playing channel // library marker davegut.samsungTV-Keys, line 120
		case 26: channelList(); break	//	Pops up short channel-list. // library marker davegut.samsungTV-Keys, line 121
		//	===== Other Commands ===== // library marker davegut.samsungTV-Keys, line 122
		case 34: previousChannel(); break // library marker davegut.samsungTV-Keys, line 123
		case 35: hdmi(); break			//	Brings up next available source // library marker davegut.samsungTV-Keys, line 124
		case 36: fastBack(); break		//	causes fast forward // library marker davegut.samsungTV-Keys, line 125
		case 37: fastForward(); break	//	causes fast rewind // library marker davegut.samsungTV-Keys, line 126
		case 38: appRunBrowser(); break		//	Direct to source 1 (ofour right of TV on menu) // library marker davegut.samsungTV-Keys, line 127
		case 39: appRunYouTube(); break // library marker davegut.samsungTV-Keys, line 128
		case 40: appRunNetflix(); break // library marker davegut.samsungTV-Keys, line 129
		case 42: toggleSoundMode(); break // library marker davegut.samsungTV-Keys, line 130
		case 43: togglePictureMode(); break // library marker davegut.samsungTV-Keys, line 131
		case 44: setPictureMode("Dynamic"); break // library marker davegut.samsungTV-Keys, line 132
		default: // library marker davegut.samsungTV-Keys, line 133
			logDebug("push: Invalid Button Number!") // library marker davegut.samsungTV-Keys, line 134
			break // library marker davegut.samsungTV-Keys, line 135
	} // library marker davegut.samsungTV-Keys, line 136
} // library marker davegut.samsungTV-Keys, line 137


// ~~~~~ end include (1217) davegut.samsungTV-Keys ~~~~~

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
						 errorMsg: error] // library marker davegut.ST-Communications, line 52
		} // library marker davegut.ST-Communications, line 53
	} // library marker davegut.ST-Communications, line 54
	return respData // library marker davegut.ST-Communications, line 55
} // library marker davegut.ST-Communications, line 56

private syncPost(sendData){ // library marker davegut.ST-Communications, line 58
	def respData = [:] // library marker davegut.ST-Communications, line 59
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 60
		respData << [status: "FAILED", // library marker davegut.ST-Communications, line 61
					 errorMsg: "No stApiKey"] // library marker davegut.ST-Communications, line 62
	} else { // library marker davegut.ST-Communications, line 63
		logDebug("syncPost: ${sendData}") // library marker davegut.ST-Communications, line 64
		def cmdBody = [commands: [sendData.cmdData]] // library marker davegut.ST-Communications, line 65
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 66
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 67
			path: sendData.path, // library marker davegut.ST-Communications, line 68
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()], // library marker davegut.ST-Communications, line 69
			body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.ST-Communications, line 70
		] // library marker davegut.ST-Communications, line 71
		try { // library marker davegut.ST-Communications, line 72
			httpPost(sendCmdParams) {resp -> // library marker davegut.ST-Communications, line 73
				if (resp.status == 200 && resp.data != null) { // library marker davegut.ST-Communications, line 74
					respData << [status: "OK", results: resp.data.results] // library marker davegut.ST-Communications, line 75
				} else { // library marker davegut.ST-Communications, line 76
					respData << [status: "FAILED", // library marker davegut.ST-Communications, line 77
								 httpCode: resp.status, // library marker davegut.ST-Communications, line 78
								 errorMsg: resp.errorMessage] // library marker davegut.ST-Communications, line 79
				} // library marker davegut.ST-Communications, line 80
			} // library marker davegut.ST-Communications, line 81
		} catch (error) { // library marker davegut.ST-Communications, line 82
			respData << [status: "FAILED", // library marker davegut.ST-Communications, line 83
						 errorMsg: error] // library marker davegut.ST-Communications, line 84
		} // library marker davegut.ST-Communications, line 85
	} // library marker davegut.ST-Communications, line 86
	return respData // library marker davegut.ST-Communications, line 87
} // library marker davegut.ST-Communications, line 88

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
	updateData << [textEnable: textEnable, logEnable: logEnable] // library marker davegut.ST-Common, line 24
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
		case "10sec":  // library marker davegut.ST-Common, line 41
			schedule("*/10 * * * * ?", "poll")		 // library marker davegut.ST-Common, line 42
			break // library marker davegut.ST-Common, line 43
		case "20sec": // library marker davegut.ST-Common, line 44
			schedule("*/20 * * * * ?", "poll")		 // library marker davegut.ST-Common, line 45
			break // library marker davegut.ST-Common, line 46
		case "30sec": // library marker davegut.ST-Common, line 47
			schedule("*/30 * * * * ?", "poll")		 // library marker davegut.ST-Common, line 48
			break // library marker davegut.ST-Common, line 49
		case "1" : // library marker davegut.ST-Common, line 50
		case "1min": runEvery1Minute(poll); break // library marker davegut.ST-Common, line 51
		case "5" : runEvery5Minutes(poll); break // library marker davegut.ST-Common, line 52
		case "5min" : runEvery5Minutes(poll); break // library marker davegut.ST-Common, line 53
		case "10" : runEvery10Minutes(poll); break // library marker davegut.ST-Common, line 54
		case "30" : runEvery30Minutes(poll); break // library marker davegut.ST-Common, line 55
		default: runEvery10Minutes(poll) // library marker davegut.ST-Common, line 56
	} // library marker davegut.ST-Common, line 57
} // library marker davegut.ST-Common, line 58

def deviceCommand(cmdData) { // library marker davegut.ST-Common, line 60
	def respData = [:] // library marker davegut.ST-Common, line 61
	if (simulate() == true) { // library marker davegut.ST-Common, line 62
		respData = testResp(cmdData) // library marker davegut.ST-Common, line 63
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 64
		respData << [status: "FAILED", data: "no stDeviceId"] // library marker davegut.ST-Common, line 65
	} else { // library marker davegut.ST-Common, line 66
		def sendData = [ // library marker davegut.ST-Common, line 67
			path: "/devices/${stDeviceId.trim()}/commands", // library marker davegut.ST-Common, line 68
			cmdData: cmdData // library marker davegut.ST-Common, line 69
		] // library marker davegut.ST-Common, line 70
		respData = syncPost(sendData) // library marker davegut.ST-Common, line 71
	} // library marker davegut.ST-Common, line 72
	if (respData.results.status[0] != "FAILED") { // library marker davegut.ST-Common, line 73
		if (cmdData.capability && cmdData.capability != "refresh") { // library marker davegut.ST-Common, line 74
			refresh() // library marker davegut.ST-Common, line 75
		} else { // library marker davegut.ST-Common, line 76
			poll() // library marker davegut.ST-Common, line 77
		} // library marker davegut.ST-Common, line 78
	} // library marker davegut.ST-Common, line 79
	return respData // library marker davegut.ST-Common, line 80
} // library marker davegut.ST-Common, line 81

def refresh() { // library marker davegut.ST-Common, line 83
	if (stApiKey!= null) { // library marker davegut.ST-Common, line 84
		def cmdData = [ // library marker davegut.ST-Common, line 85
			component: "main", // library marker davegut.ST-Common, line 86
			capability: "refresh", // library marker davegut.ST-Common, line 87
			command: "refresh", // library marker davegut.ST-Common, line 88
			arguments: []] // library marker davegut.ST-Common, line 89
		deviceCommand(cmdData) // library marker davegut.ST-Common, line 90
	} // library marker davegut.ST-Common, line 91
} // library marker davegut.ST-Common, line 92

def poll() { // library marker davegut.ST-Common, line 94
	if (simulate() == true) { // library marker davegut.ST-Common, line 95
		pauseExecution(200) // library marker davegut.ST-Common, line 96
		def children = getChildDevices() // library marker davegut.ST-Common, line 97
		if (children) { // library marker davegut.ST-Common, line 98
			children.each { // library marker davegut.ST-Common, line 99
				it.statusParse(testData()) // library marker davegut.ST-Common, line 100
			} // library marker davegut.ST-Common, line 101
		} // library marker davegut.ST-Common, line 102
		statusParse(testData()) // library marker davegut.ST-Common, line 103
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 104
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 105
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 106
	} else { // library marker davegut.ST-Common, line 107
		def sendData = [ // library marker davegut.ST-Common, line 108
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.ST-Common, line 109
			parse: "distResp" // library marker davegut.ST-Common, line 110
			] // library marker davegut.ST-Common, line 111
		asyncGet(sendData, "statusParse") // library marker davegut.ST-Common, line 112
	} // library marker davegut.ST-Common, line 113
} // library marker davegut.ST-Common, line 114

def deviceSetup() { // library marker davegut.ST-Common, line 116
	if (simulate() == true) { // library marker davegut.ST-Common, line 117
		def children = getChildDevices() // library marker davegut.ST-Common, line 118
		deviceSetupParse(testData()) // library marker davegut.ST-Common, line 119
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 120
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 121
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 122
	} else { // library marker davegut.ST-Common, line 123
		def sendData = [ // library marker davegut.ST-Common, line 124
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.ST-Common, line 125
			parse: "distResp" // library marker davegut.ST-Common, line 126
			] // library marker davegut.ST-Common, line 127
		asyncGet(sendData, "deviceSetup") // library marker davegut.ST-Common, line 128
	} // library marker davegut.ST-Common, line 129
} // library marker davegut.ST-Common, line 130

def getDeviceList() { // library marker davegut.ST-Common, line 132
	def sendData = [ // library marker davegut.ST-Common, line 133
		path: "/devices", // library marker davegut.ST-Common, line 134
		parse: "getDeviceListParse" // library marker davegut.ST-Common, line 135
		] // library marker davegut.ST-Common, line 136
	asyncGet(sendData) // library marker davegut.ST-Common, line 137
} // library marker davegut.ST-Common, line 138

def getDeviceListParse(resp, data) { // library marker davegut.ST-Common, line 140
	def respData // library marker davegut.ST-Common, line 141
	if (resp.status != 200) { // library marker davegut.ST-Common, line 142
		respData = [status: "ERROR", // library marker davegut.ST-Common, line 143
					httpCode: resp.status, // library marker davegut.ST-Common, line 144
					errorMsg: resp.errorMessage] // library marker davegut.ST-Common, line 145
	} else { // library marker davegut.ST-Common, line 146
		try { // library marker davegut.ST-Common, line 147
			respData = new JsonSlurper().parseText(resp.data) // library marker davegut.ST-Common, line 148
		} catch (err) { // library marker davegut.ST-Common, line 149
			respData = [status: "ERROR", // library marker davegut.ST-Common, line 150
						errorMsg: err, // library marker davegut.ST-Common, line 151
						respData: resp.data] // library marker davegut.ST-Common, line 152
		} // library marker davegut.ST-Common, line 153
	} // library marker davegut.ST-Common, line 154
	if (respData.status == "ERROR") { // library marker davegut.ST-Common, line 155
		logWarn("getDeviceListParse: ${respData}") // library marker davegut.ST-Common, line 156
	} else { // library marker davegut.ST-Common, line 157
		log.info "" // library marker davegut.ST-Common, line 158
		respData.items.each { // library marker davegut.ST-Common, line 159
			log.trace "${it.label}:   ${it.deviceId}" // library marker davegut.ST-Common, line 160
		} // library marker davegut.ST-Common, line 161
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>" // library marker davegut.ST-Common, line 162
	} // library marker davegut.ST-Common, line 163
} // library marker davegut.ST-Common, line 164

def calcTimeRemaining(completionTime) { // library marker davegut.ST-Common, line 166
	Integer currTime = now() // library marker davegut.ST-Common, line 167
	Integer compTime // library marker davegut.ST-Common, line 168
	try { // library marker davegut.ST-Common, line 169
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 170
	} catch (e) { // library marker davegut.ST-Common, line 171
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 172
	} // library marker davegut.ST-Common, line 173
	Integer timeRemaining = ((compTime-currTime) /1000).toInteger() // library marker davegut.ST-Common, line 174
	if (timeRemaining < 0) { timeRemaining = 0 } // library marker davegut.ST-Common, line 175
	return timeRemaining // library marker davegut.ST-Common, line 176
} // library marker davegut.ST-Common, line 177

// ~~~~~ end include (1090) davegut.ST-Common ~~~~~

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
