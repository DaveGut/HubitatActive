/*	===== HUBITAT INTEGRATION VERSION =====================================================
Hubitat - Samsung TV Remote Driver
		Copyright 2022 Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2022 Version 4.1 ====================================================================
Version 4.1-2
a.	Fix issue of commands sometimes not executing when WS is closed by increasing delay
	between wsOpen and sendCommand.
b.	Added wsOpen and wsClose to user interface per request.
c.	Clarified "conflict" error on SmartThings Command with additional message. If a
	Conflict is detected, the device is off-line in SmartThings.  It can take several
	minutes for ST to detect.  This is a ST issue.
d.	Created libraries to ease code maintenance and reuse.
===========================================================================================*/
def driverVer() { return "4.1-2" }
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
			stPreferences()
		}
		def onPollOptions = ["local": "Local", "off": "DISABLE"]
		input ("pollMethod", "enum", title: "Power Polling Method", defaultValue: "local",
			   options: onPollOptions)
		input ("pollInterval","enum", title: "Power Polling Interval (seconds)",
			   options: ["off", "10", "15", "20", "30", "60"], defaultValue: "60")
		tvAppsPreferences()
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
		if (!state.appData) { state.appData == [:] }
		if (findAppCodes) {
			runIn(1, updateAppCodes)
		}
		runIn(1, configure)
	}
	sendEvent(name: "numberOfButtons", value: 45)
	sendEvent(name: "wsStatus", value: "closed")
	updStatus << [attributes: listAttributes()]
	state.standbyTest = false
	logInfo("updated: ${updStatus}")
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
	def wolMac = getDataValue("alternateWolMac")
	def cmd = "FFFFFFFFFFFF$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac"
	wol = new hubitat.device.HubAction(
		cmd,
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "255.255.255.255:7",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING])
	sendHubCommand(wol)
	runIn(5, onPoll)
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
	sendKey("POWER", "Press")
	pauseExecution(3000)
	sendKey("POWER", "Release")
	runIn(5, onPoll)
}

//	===== BUTTON INTERFACE =====
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

//	===== Libraries =====






// ~~~~~ start include (1367) davegut.samsungTvWebsocket ~~~~~
library ( // library marker davegut.samsungTvWebsocket, line 1
	name: "samsungTvWebsocket", // library marker davegut.samsungTvWebsocket, line 2
	namespace: "davegut", // library marker davegut.samsungTvWebsocket, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTvWebsocket, line 4
	description: "Common Samsung TV Websocket Commands", // library marker davegut.samsungTvWebsocket, line 5
	category: "utilities", // library marker davegut.samsungTvWebsocket, line 6
	documentationLink: "" // library marker davegut.samsungTvWebsocket, line 7
) // library marker davegut.samsungTvWebsocket, line 8

import groovy.json.JsonOutput // library marker davegut.samsungTvWebsocket, line 10

command "webSocketClose" // library marker davegut.samsungTvWebsocket, line 12
command "webSocketOpen" // library marker davegut.samsungTvWebsocket, line 13
command "close" // library marker davegut.samsungTvWebsocket, line 14
attribute "wsStatus", "string" // library marker davegut.samsungTvWebsocket, line 15
command "artMode" // library marker davegut.samsungTvWebsocket, line 16
attribute "artModeStatus", "string" // library marker davegut.samsungTvWebsocket, line 17
command "ambientMode" // library marker davegut.samsungTvWebsocket, line 18
//	Remote Control Keys (samsungTV-Keys) // library marker davegut.samsungTvWebsocket, line 19
command "pause" // library marker davegut.samsungTvWebsocket, line 20
command "play" // library marker davegut.samsungTvWebsocket, line 21
command "stop" // library marker davegut.samsungTvWebsocket, line 22
command "sendKey", ["string"] // library marker davegut.samsungTvWebsocket, line 23
//	Cursor and Entry Control // library marker davegut.samsungTvWebsocket, line 24
command "arrowLeft" // library marker davegut.samsungTvWebsocket, line 25
command "arrowRight" // library marker davegut.samsungTvWebsocket, line 26
command "arrowUp" // library marker davegut.samsungTvWebsocket, line 27
command "arrowDown" // library marker davegut.samsungTvWebsocket, line 28
command "enter" // library marker davegut.samsungTvWebsocket, line 29
command "numericKeyPad" // library marker davegut.samsungTvWebsocket, line 30
//	Menu Access // library marker davegut.samsungTvWebsocket, line 31
command "home" // library marker davegut.samsungTvWebsocket, line 32
command "menu" // library marker davegut.samsungTvWebsocket, line 33
command "guide" // library marker davegut.samsungTvWebsocket, line 34
command "info" // library marker davegut.samsungTvWebsocket, line 35
//	Source Commands // library marker davegut.samsungTvWebsocket, line 36
command "source" // library marker davegut.samsungTvWebsocket, line 37
command "hdmi" // library marker davegut.samsungTvWebsocket, line 38
//	TV Channel // library marker davegut.samsungTvWebsocket, line 39
command "channelList" // library marker davegut.samsungTvWebsocket, line 40
command "channelUp" // library marker davegut.samsungTvWebsocket, line 41
command "channelDown" // library marker davegut.samsungTvWebsocket, line 42
command "previousChannel" // library marker davegut.samsungTvWebsocket, line 43
//	Playing Navigation Commands // library marker davegut.samsungTvWebsocket, line 44
command "exit" // library marker davegut.samsungTvWebsocket, line 45
command "Return" // library marker davegut.samsungTvWebsocket, line 46
command "fastBack" // library marker davegut.samsungTvWebsocket, line 47
command "fastForward" // library marker davegut.samsungTvWebsocket, line 48
command "nextTrack", [[name: "Sets Channel Up"]] // library marker davegut.samsungTvWebsocket, line 49
command "previousTrack", [[name: "Sets Channel Down"]] // library marker davegut.samsungTvWebsocket, line 50

//	== ART/Ambient Mode // library marker davegut.samsungTvWebsocket, line 52
def artMode() { // library marker davegut.samsungTvWebsocket, line 53
	def artModeStatus = device.currentValue("artModeStatus") // library marker davegut.samsungTvWebsocket, line 54
	def logData = [artModeStatus: artModeStatus, artModeWs: state.artModeWs] // library marker davegut.samsungTvWebsocket, line 55
	if (getDataValue("frameTv") != "true") { // library marker davegut.samsungTvWebsocket, line 56
		logData << [status: "Not a Frame TV"] // library marker davegut.samsungTvWebsocket, line 57
	} else if (artModeStatus == "on") { // library marker davegut.samsungTvWebsocket, line 58
		logData << [status: "artMode already set"] // library marker davegut.samsungTvWebsocket, line 59
	} else { // library marker davegut.samsungTvWebsocket, line 60
		if (state.artModeWs) { // library marker davegut.samsungTvWebsocket, line 61
			def data = [value:"on", // library marker davegut.samsungTvWebsocket, line 62
						request:"set_artmode_status", // library marker davegut.samsungTvWebsocket, line 63
						id: "${getDataValue("uuid")}"] // library marker davegut.samsungTvWebsocket, line 64
			data = JsonOutput.toJson(data) // library marker davegut.samsungTvWebsocket, line 65
			artModeCmd(data) // library marker davegut.samsungTvWebsocket, line 66
			logData << [status: "Sending artMode WS Command"] // library marker davegut.samsungTvWebsocket, line 67
		} else { // library marker davegut.samsungTvWebsocket, line 68
			sendKey("POWER") // library marker davegut.samsungTvWebsocket, line 69
			logData << [status: "Sending Power WS Command"] // library marker davegut.samsungTvWebsocket, line 70
			if (artModeStatus == "none") { // library marker davegut.samsungTvWebsocket, line 71
				logData << [NOTE: "SENT BLIND. Enable SmartThings interface!"] // library marker davegut.samsungTvWebsocket, line 72
			} // library marker davegut.samsungTvWebsocket, line 73
		} // library marker davegut.samsungTvWebsocket, line 74
		runIn(10, getArtModeStatus) // library marker davegut.samsungTvWebsocket, line 75
	} // library marker davegut.samsungTvWebsocket, line 76
	logInfo("artMode: ${logData}") // library marker davegut.samsungTvWebsocket, line 77
} // library marker davegut.samsungTvWebsocket, line 78

def getArtModeStatus() { // library marker davegut.samsungTvWebsocket, line 80
	if (getDataValue("frameTv") == "true") { // library marker davegut.samsungTvWebsocket, line 81
		if (state.artModeWs) { // library marker davegut.samsungTvWebsocket, line 82
			def data = [request:"get_artmode_status", // library marker davegut.samsungTvWebsocket, line 83
						id: "${getDataValue("uuid")}"] // library marker davegut.samsungTvWebsocket, line 84
			data = JsonOutput.toJson(data) // library marker davegut.samsungTvWebsocket, line 85
			artModeCmd(data) // library marker davegut.samsungTvWebsocket, line 86
		} else { // library marker davegut.samsungTvWebsocket, line 87
			refresh() // library marker davegut.samsungTvWebsocket, line 88
		} // library marker davegut.samsungTvWebsocket, line 89
	} // library marker davegut.samsungTvWebsocket, line 90
} // library marker davegut.samsungTvWebsocket, line 91

def artModeCmd(data) { // library marker davegut.samsungTvWebsocket, line 93
	def cmdData = [method:"ms.channel.emit", // library marker davegut.samsungTvWebsocket, line 94
				   params:[data:"${data}", // library marker davegut.samsungTvWebsocket, line 95
						   to:"host", // library marker davegut.samsungTvWebsocket, line 96
						   event:"art_app_request"]] // library marker davegut.samsungTvWebsocket, line 97
	cmdData = JsonOutput.toJson(cmdData) // library marker davegut.samsungTvWebsocket, line 98
	sendMessage("frameArt", cmdData) // library marker davegut.samsungTvWebsocket, line 99
} // library marker davegut.samsungTvWebsocket, line 100

def ambientMode() { // library marker davegut.samsungTvWebsocket, line 102
	sendKey("AMBIENT") // library marker davegut.samsungTvWebsocket, line 103
	runIn(10, refresh) // library marker davegut.samsungTvWebsocket, line 104
} // library marker davegut.samsungTvWebsocket, line 105

//	== Remote Commands // library marker davegut.samsungTvWebsocket, line 107
def mute() { sendKeyThenRefresh("MUTE") } // library marker davegut.samsungTvWebsocket, line 108

def unmute() { sendKeyThenRefresh("MUTE") } // library marker davegut.samsungTvWebsocket, line 110

def volumeUp() { sendKeyThenRefresh("VOLUP") } // library marker davegut.samsungTvWebsocket, line 112

def volumeDown() { sendKeyThenRefresh("VOLDOWN") } // library marker davegut.samsungTvWebsocket, line 114

def play() { sendKeyThenRefresh("PLAY") } // library marker davegut.samsungTvWebsocket, line 116

def pause() { sendKeyThenRefresh("PAUSE") } // library marker davegut.samsungTvWebsocket, line 118

def stop() { sendKeyThenRefresh("STOP") } // library marker davegut.samsungTvWebsocket, line 120

def exit() { sendKeyThenRefresh("EXIT") } // library marker davegut.samsungTvWebsocket, line 122

def Return() { sendKey("RETURN") } // library marker davegut.samsungTvWebsocket, line 124

def fastBack() { // library marker davegut.samsungTvWebsocket, line 126
	sendKey("LEFT", "Press") // library marker davegut.samsungTvWebsocket, line 127
	pauseExecution(1000) // library marker davegut.samsungTvWebsocket, line 128
	sendKey("LEFT", "Release") // library marker davegut.samsungTvWebsocket, line 129
} // library marker davegut.samsungTvWebsocket, line 130

def fastForward() { // library marker davegut.samsungTvWebsocket, line 132
	sendKey("RIGHT", "Press") // library marker davegut.samsungTvWebsocket, line 133
	pauseExecution(1000) // library marker davegut.samsungTvWebsocket, line 134
	sendKey("RIGHT", "Release") // library marker davegut.samsungTvWebsocket, line 135
} // library marker davegut.samsungTvWebsocket, line 136

def arrowLeft() { sendKey("LEFT") } // library marker davegut.samsungTvWebsocket, line 138

def arrowRight() { sendKey("RIGHT") } // library marker davegut.samsungTvWebsocket, line 140

def arrowUp() { sendKey("UP") } // library marker davegut.samsungTvWebsocket, line 142

def arrowDown() { sendKey("DOWN") } // library marker davegut.samsungTvWebsocket, line 144

def enter() { sendKey("ENTER") } // library marker davegut.samsungTvWebsocket, line 146

def numericKeyPad() { sendKey("MORE") } // library marker davegut.samsungTvWebsocket, line 148

def home() { sendKey("HOME") } // library marker davegut.samsungTvWebsocket, line 150

def menu() { sendKey("MENU") } // library marker davegut.samsungTvWebsocket, line 152

def guide() { sendKey("GUIDE") } // library marker davegut.samsungTvWebsocket, line 154

def info() { sendKey("INFO") } // library marker davegut.samsungTvWebsocket, line 156

def source() { sendKeyThenRefresh("SOURCE") } // library marker davegut.samsungTvWebsocket, line 158

def hdmi() { sendKeyThenRefresh("HDMI") } // library marker davegut.samsungTvWebsocket, line 160

def channelList() { sendKey("CH_LIST") } // library marker davegut.samsungTvWebsocket, line 162

def channelUp() { sendKeyThenRefresh("CHUP") } // library marker davegut.samsungTvWebsocket, line 164

def nextTrack() { channelUp() } // library marker davegut.samsungTvWebsocket, line 166

def channelDown() { sendKeyThenRefresh("CHDOWN") } // library marker davegut.samsungTvWebsocket, line 168

def previousTrack() { channelDown() } // library marker davegut.samsungTvWebsocket, line 170

def previousChannel() { sendKeyThenRefresh("PRECH") } // library marker davegut.samsungTvWebsocket, line 172

def showMessage() { logWarn("showMessage: not implemented") } // library marker davegut.samsungTvWebsocket, line 174

//	== WebSocket Communications / Parse // library marker davegut.samsungTvWebsocket, line 176
def sendKeyThenRefresh(key) { // library marker davegut.samsungTvWebsocket, line 177
	sendKey(key) // library marker davegut.samsungTvWebsocket, line 178
	if (connectST) { runIn(5, deviceRefresh) } // library marker davegut.samsungTvWebsocket, line 179
} // library marker davegut.samsungTvWebsocket, line 180

def sendKey(key, cmd = "Click") { // library marker davegut.samsungTvWebsocket, line 182
	key = "KEY_${key.toUpperCase()}" // library marker davegut.samsungTvWebsocket, line 183
	def data = [method:"ms.remote.control", // library marker davegut.samsungTvWebsocket, line 184
				params:[Cmd:"${cmd}", // library marker davegut.samsungTvWebsocket, line 185
						DataOfCmd:"${key}", // library marker davegut.samsungTvWebsocket, line 186
						TypeOfRemote:"SendRemoteKey"]] // library marker davegut.samsungTvWebsocket, line 187
	sendMessage("remote", JsonOutput.toJson(data) ) // library marker davegut.samsungTvWebsocket, line 188
} // library marker davegut.samsungTvWebsocket, line 189

def sendMessage(funct, data) { // library marker davegut.samsungTvWebsocket, line 191
	def wsStat = device.currentValue("wsStatus") // library marker davegut.samsungTvWebsocket, line 192
	logDebug("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${state.currentFunction}") // library marker davegut.samsungTvWebsocket, line 193
	logTrace("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${state.currentFunction}") // library marker davegut.samsungTvWebsocket, line 194
	if (wsStat != "open" || state.currentFunction != funct) { // library marker davegut.samsungTvWebsocket, line 195
		connect(funct) // library marker davegut.samsungTvWebsocket, line 196
		pauseExecution(1200) // library marker davegut.samsungTvWebsocket, line 197
	} // library marker davegut.samsungTvWebsocket, line 198
	interfaces.webSocket.sendMessage(data) // library marker davegut.samsungTvWebsocket, line 199
	runIn(60, close) // library marker davegut.samsungTvWebsocket, line 200
} // library marker davegut.samsungTvWebsocket, line 201

def webSocketOpen() { connect("remote") } // library marker davegut.samsungTvWebsocket, line 203
def webSocketClose() { close() } // library marker davegut.samsungTvWebsocket, line 204

def connect(funct) { // library marker davegut.samsungTvWebsocket, line 206
	logDebug("connect: function = ${funct}") // library marker davegut.samsungTvWebsocket, line 207
	def url // library marker davegut.samsungTvWebsocket, line 208
	def name = "SHViaXRhdCBTYW1zdW5nIFJlbW90ZQ==" // library marker davegut.samsungTvWebsocket, line 209
	if (getDataValue("tokenSupport") == "true") { // library marker davegut.samsungTvWebsocket, line 210
		if (funct == "remote") { // library marker davegut.samsungTvWebsocket, line 211
			url = "wss://${deviceIp}:8002/api/v2/channels/samsung.remote.control?name=${name}&token=${state.token}" // library marker davegut.samsungTvWebsocket, line 212
		} else if (funct == "frameArt") { // library marker davegut.samsungTvWebsocket, line 213
			url = "wss://${deviceIp}:8002/api/v2/channels/com.samsung.art-app?name=${name}&token=${state.token}" // library marker davegut.samsungTvWebsocket, line 214
		} else { // library marker davegut.samsungTvWebsocket, line 215
			logWarn("sendMessage: Invalid Function = ${funct}, tokenSupport = true") // library marker davegut.samsungTvWebsocket, line 216
		} // library marker davegut.samsungTvWebsocket, line 217
	} else { // library marker davegut.samsungTvWebsocket, line 218
		if (funct == "remote") { // library marker davegut.samsungTvWebsocket, line 219
			url = "ws://${deviceIp}:8001/api/v2/channels/samsung.remote.control?name=${name}" // library marker davegut.samsungTvWebsocket, line 220
		} else if (funct == "frameArt") { // library marker davegut.samsungTvWebsocket, line 221
			url = "ws://${deviceIp}:8001/api/v2/channels/com.samsung.art-app?name=${name}" // library marker davegut.samsungTvWebsocket, line 222
		} else { // library marker davegut.samsungTvWebsocket, line 223
			logWarn("sendMessage: Invalid Function = ${funct}, tokenSupport = false") // library marker davegut.samsungTvWebsocket, line 224
		} // library marker davegut.samsungTvWebsocket, line 225
	} // library marker davegut.samsungTvWebsocket, line 226
	state.currentFunction = funct // library marker davegut.samsungTvWebsocket, line 227
	interfaces.webSocket.connect(url, ignoreSSLIssues: true) // library marker davegut.samsungTvWebsocket, line 228
} // library marker davegut.samsungTvWebsocket, line 229

def close() { // library marker davegut.samsungTvWebsocket, line 231
	logDebug("close") // library marker davegut.samsungTvWebsocket, line 232
	interfaces.webSocket.close() // library marker davegut.samsungTvWebsocket, line 233
	sendEvent(name: "wsStatus", value: "closed") // library marker davegut.samsungTvWebsocket, line 234
} // library marker davegut.samsungTvWebsocket, line 235

def webSocketStatus(message) { // library marker davegut.samsungTvWebsocket, line 237
	def status // library marker davegut.samsungTvWebsocket, line 238
	if (message == "status: open") { // library marker davegut.samsungTvWebsocket, line 239
		status = "open" // library marker davegut.samsungTvWebsocket, line 240
	} else if (message == "status: closing") { // library marker davegut.samsungTvWebsocket, line 241
		status = "closed" // library marker davegut.samsungTvWebsocket, line 242
		state.currentFunction = "close" // library marker davegut.samsungTvWebsocket, line 243
	} else if (message.substring(0,7) == "failure") { // library marker davegut.samsungTvWebsocket, line 244
		status = "closed-failure" // library marker davegut.samsungTvWebsocket, line 245
		state.currentFunction = "close" // library marker davegut.samsungTvWebsocket, line 246
		close() // library marker davegut.samsungTvWebsocket, line 247
	} // library marker davegut.samsungTvWebsocket, line 248
	sendEvent(name: "wsStatus", value: status) // library marker davegut.samsungTvWebsocket, line 249
	logDebug("webSocketStatus: [status: ${status}, message: ${message}]") // library marker davegut.samsungTvWebsocket, line 250
} // library marker davegut.samsungTvWebsocket, line 251

def parse(resp) { // library marker davegut.samsungTvWebsocket, line 253
	def logData = [:] // library marker davegut.samsungTvWebsocket, line 254
	try { // library marker davegut.samsungTvWebsocket, line 255
		resp = parseJson(resp) // library marker davegut.samsungTvWebsocket, line 256
		def event = resp.event // library marker davegut.samsungTvWebsocket, line 257
		logData << [EVENT: event] // library marker davegut.samsungTvWebsocket, line 258
		switch(event) { // library marker davegut.samsungTvWebsocket, line 259
			case "ms.channel.connect": // library marker davegut.samsungTvWebsocket, line 260
				def newToken = resp.data.token // library marker davegut.samsungTvWebsocket, line 261
				if (newToken != null && newToken != state.token) { // library marker davegut.samsungTvWebsocket, line 262
					state.token = newToken // library marker davegut.samsungTvWebsocket, line 263
					logData << [TOKEN: "updated"] // library marker davegut.samsungTvWebsocket, line 264
				} else { // library marker davegut.samsungTvWebsocket, line 265
					logData << [TOKEN: "noChange"] // library marker davegut.samsungTvWebsocket, line 266
				} // library marker davegut.samsungTvWebsocket, line 267
				break // library marker davegut.samsungTvWebsocket, line 268
			case "d2d_service_message": // library marker davegut.samsungTvWebsocket, line 269
				def data = parseJson(resp.data) // library marker davegut.samsungTvWebsocket, line 270
				if (data.event == "artmode_status" || // library marker davegut.samsungTvWebsocket, line 271
					data.event == "art_mode_changed") { // library marker davegut.samsungTvWebsocket, line 272
					def status = data.value // library marker davegut.samsungTvWebsocket, line 273
					if (status == null) { status = data.status } // library marker davegut.samsungTvWebsocket, line 274
					sendEvent(name: "artModeStatus", value: status) // library marker davegut.samsungTvWebsocket, line 275
					logData << [artModeStatus: status] // library marker davegut.samsungTvWebsocket, line 276
					state.artModeWs = true // library marker davegut.samsungTvWebsocket, line 277
				} // library marker davegut.samsungTvWebsocket, line 278
				break // library marker davegut.samsungTvWebsocket, line 279
			case "ms.error": // library marker davegut.samsungTvWebsocket, line 280
				logData << [STATUS: "Error, Closing WS",DATA: resp.data] // library marker davegut.samsungTvWebsocket, line 281
				close() // library marker davegut.samsungTvWebsocket, line 282
				break // library marker davegut.samsungTvWebsocket, line 283
			case "ms.channel.ready": // library marker davegut.samsungTvWebsocket, line 284
			case "ms.channel.clientConnect": // library marker davegut.samsungTvWebsocket, line 285
			case "ms.channel.clientDisconnect": // library marker davegut.samsungTvWebsocket, line 286
			case "ms.remote.touchEnable": // library marker davegut.samsungTvWebsocket, line 287
			case "ms.remote.touchDisable": // library marker davegut.samsungTvWebsocket, line 288
				break // library marker davegut.samsungTvWebsocket, line 289
			default: // library marker davegut.samsungTvWebsocket, line 290
				logData << [STATUS: "Not Parsed", DATA: resp.data] // library marker davegut.samsungTvWebsocket, line 291
				break // library marker davegut.samsungTvWebsocket, line 292
		} // library marker davegut.samsungTvWebsocket, line 293
		logDebug("parse: ${logData}") // library marker davegut.samsungTvWebsocket, line 294
	} catch (e) { // library marker davegut.samsungTvWebsocket, line 295
		logData << [STATUS: "unhandled", ERROR: e] // library marker davegut.samsungTvWebsocket, line 296
		logWarn("parse: ${logData}") // library marker davegut.samsungTvWebsocket, line 297
	} // library marker davegut.samsungTvWebsocket, line 298
} // library marker davegut.samsungTvWebsocket, line 299

// ~~~~~ end include (1367) davegut.samsungTvWebsocket ~~~~~

// ~~~~~ start include (1366) davegut.samsungTvApps ~~~~~
library ( // library marker davegut.samsungTvApps, line 1
	name: "samsungTvApps", // library marker davegut.samsungTvApps, line 2
	namespace: "davegut", // library marker davegut.samsungTvApps, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTvApps, line 4
	description: "Samsung TV Applications", // library marker davegut.samsungTvApps, line 5
	category: "utilities", // library marker davegut.samsungTvApps, line 6
	documentationLink: "" // library marker davegut.samsungTvApps, line 7
) // library marker davegut.samsungTvApps, line 8

command "appOpenByName", ["string"] // library marker davegut.samsungTvApps, line 10
command "appOpenByCode", ["string"] // library marker davegut.samsungTvApps, line 11
command "appClose" // library marker davegut.samsungTvApps, line 12
attribute "currentApp", "string" // library marker davegut.samsungTvApps, line 13
command "appRunBrowser" // library marker davegut.samsungTvApps, line 14
command "appRunYouTube" // library marker davegut.samsungTvApps, line 15
command "appRunNetflix" // library marker davegut.samsungTvApps, line 16
command "appRunPrimeVideo" // library marker davegut.samsungTvApps, line 17
command "appRunYouTubeTV" // library marker davegut.samsungTvApps, line 18
command "appRunHulu" // library marker davegut.samsungTvApps, line 19

def tvAppsPreferences() { // library marker davegut.samsungTvApps, line 21
	input ("findAppCodes", "bool", title: "Scan for App Codes (takes 10 minutes)", defaultValue: false) // library marker davegut.samsungTvApps, line 22
} // library marker davegut.samsungTvApps, line 23

import groovy.json.JsonSlurper // library marker davegut.samsungTvApps, line 25

def appOpenByName(appName) { // library marker davegut.samsungTvApps, line 27
	def thisApp = findThisApp(appName) // library marker davegut.samsungTvApps, line 28
	def logData = [appName: thisApp[0], appId: thisApp[1]] // library marker davegut.samsungTvApps, line 29
	if (thisApp[1] != "none") { // library marker davegut.samsungTvApps, line 30
		[status: "execute appOpenByCode"] // library marker davegut.samsungTvApps, line 31
		appOpenByCode(thisApp[1]) // library marker davegut.samsungTvApps, line 32
	} else { // library marker davegut.samsungTvApps, line 33
		def url = "http://${deviceIp}:8080/ws/apps/${appName}" // library marker davegut.samsungTvApps, line 34
		try { // library marker davegut.samsungTvApps, line 35
			httpPost(url, "") { resp -> // library marker davegut.samsungTvApps, line 36
				sendEvent(name: "currentApp", value: respData.name) // library marker davegut.samsungTvApps, line 37
				logData << [status: "OK", currentApp: respData.name] // library marker davegut.samsungTvApps, line 38
			} // library marker davegut.samsungTvApps, line 39
			runIn(5, refresh) // library marker davegut.samsungTvApps, line 40
		} catch (err) { // library marker davegut.samsungTvApps, line 41
			logData << [status: "appName Not Found", data: err] // library marker davegut.samsungTvApps, line 42
			logWarn("appOpenByName: ${logData}") // library marker davegut.samsungTvApps, line 43
		} // library marker davegut.samsungTvApps, line 44
	} // library marker davegut.samsungTvApps, line 45
	logDebug("appOpenByName: ${logData}") // library marker davegut.samsungTvApps, line 46
} // library marker davegut.samsungTvApps, line 47

def appOpenByCode(appId) { // library marker davegut.samsungTvApps, line 49
	def appName = state.appData.find { it.value == appId } // library marker davegut.samsungTvApps, line 50
	if (appName != null) { // library marker davegut.samsungTvApps, line 51
		appName = appName.key // library marker davegut.samsungTvApps, line 52
	} // library marker davegut.samsungTvApps, line 53
	def logData = [appId: appId, appName: appName] // library marker davegut.samsungTvApps, line 54
	def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}" // library marker davegut.samsungTvApps, line 55
	try { // library marker davegut.samsungTvApps, line 56
		httpPost(uri, body) { resp -> // library marker davegut.samsungTvApps, line 57
			if (appName == null) { // library marker davegut.samsungTvApps, line 58
				runIn(3, getAppData, [data: appId]) // library marker davegut.samsungTvApps, line 59
			} else { // library marker davegut.samsungTvApps, line 60
				sendEvent(name: "currentApp", value: appName) // library marker davegut.samsungTvApps, line 61
				logData << [currentApp: appName] // library marker davegut.samsungTvApps, line 62
			} // library marker davegut.samsungTvApps, line 63
			runIn(5, refresh) // library marker davegut.samsungTvApps, line 64
			logData << [status: "OK", data: resp.data] // library marker davegut.samsungTvApps, line 65
		} // library marker davegut.samsungTvApps, line 66
	} catch (err) { // library marker davegut.samsungTvApps, line 67
		logData << [status: "appId Not Found", data: err] // library marker davegut.samsungTvApps, line 68
		logWarn("appOpenByCode: ${logData}") // library marker davegut.samsungTvApps, line 69
	} // library marker davegut.samsungTvApps, line 70
	logDebug("appOpenByCode: ${logData}") // library marker davegut.samsungTvApps, line 71
} // library marker davegut.samsungTvApps, line 72

def appClose() { // library marker davegut.samsungTvApps, line 74
	def appId // library marker davegut.samsungTvApps, line 75
	def appName = device.currentValue("currentApp") // library marker davegut.samsungTvApps, line 76
	if (appName == " " || appName == null) { // library marker davegut.samsungTvApps, line 77
		logWarn("appClose: [status: FAILED, reason: appName not set.]") // library marker davegut.samsungTvApps, line 78
		return // library marker davegut.samsungTvApps, line 79
	} // library marker davegut.samsungTvApps, line 80
	def thisApp = findThisApp(appName) // library marker davegut.samsungTvApps, line 81
	appId = thisApp[1] // library marker davegut.samsungTvApps, line 82
	def logData = [appName: appName, appId: appId] // library marker davegut.samsungTvApps, line 83
	Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${appId}", // library marker davegut.samsungTvApps, line 84
				  timeout: 3] // library marker davegut.samsungTvApps, line 85
	try { // library marker davegut.samsungTvApps, line 86
		asynchttpDelete("appCloseParse", params, [appId: appId]) // library marker davegut.samsungTvApps, line 87
		logData: [status: "OK"] // library marker davegut.samsungTvApps, line 88
		exit() // library marker davegut.samsungTvApps, line 89
	} catch (err) { // library marker davegut.samsungTvApps, line 90
		logData: [status: "FAILED", data: err] // library marker davegut.samsungTvApps, line 91
		logWarn("appClose: ${logData}") // library marker davegut.samsungTvApps, line 92
	} // library marker davegut.samsungTvApps, line 93
	logDebug("appClose: ${logData}") // library marker davegut.samsungTvApps, line 94
} // library marker davegut.samsungTvApps, line 95

def appCloseParse(resp, data) { // library marker davegut.samsungTvApps, line 97
	def logData = [appId: data.appId] // library marker davegut.samsungTvApps, line 98
	if (resp.status == 200) { // library marker davegut.samsungTvApps, line 99
		sendEvent(name: "currentApp", value: " ") // library marker davegut.samsungTvApps, line 100
		logData << [status: "OK"] // library marker davegut.samsungTvApps, line 101
	} else { // library marker davegut.samsungTvApps, line 102
		logData << [status: "FAILED", status: resp.status] // library marker davegut.samsungTvApps, line 103
		logWarn("appCloseParse: ${logData}") // library marker davegut.samsungTvApps, line 104
	} // library marker davegut.samsungTvApps, line 105
	logDebug("appCloseParse: ${logData}") // library marker davegut.samsungTvApps, line 106
} // library marker davegut.samsungTvApps, line 107

def findThisApp(appName) { // library marker davegut.samsungTvApps, line 109
	def thisApp = state.appData.find { it.key.toLowerCase().contains(appName.toLowerCase()) } // library marker davegut.samsungTvApps, line 110
	def appId = "none" // library marker davegut.samsungTvApps, line 111
	if (thisApp != null) { // library marker davegut.samsungTvApps, line 112
		appName = thisApp.key // library marker davegut.samsungTvApps, line 113
		appId = thisApp.value // library marker davegut.samsungTvApps, line 114
	} else { // library marker davegut.samsungTvApps, line 115
		//	Handle special case for browser (using switch to add other cases. // library marker davegut.samsungTvApps, line 116
		switch(appName.toLowerCase()) { // library marker davegut.samsungTvApps, line 117
			case "browser": // library marker davegut.samsungTvApps, line 118
				appId = "org.tizen.browser" // library marker davegut.samsungTvApps, line 119
				appName = "Browser" // library marker davegut.samsungTvApps, line 120
				break // library marker davegut.samsungTvApps, line 121
			case "youtubetv": // library marker davegut.samsungTvApps, line 122
				appId = "PvWgqxV3Xa.YouTubeTV" // library marker davegut.samsungTvApps, line 123
				appName = "YouTube TV" // library marker davegut.samsungTvApps, line 124
				break // library marker davegut.samsungTvApps, line 125
			case "netflix": // library marker davegut.samsungTvApps, line 126
				appId = "3201907018807" // library marker davegut.samsungTvApps, line 127
				appName = "Netflix" // library marker davegut.samsungTvApps, line 128
				break // library marker davegut.samsungTvApps, line 129
			case "youtube": // library marker davegut.samsungTvApps, line 130
				appId = "9Ur5IzDKqV.TizenYouTube" // library marker davegut.samsungTvApps, line 131
				appName = "YouTube" // library marker davegut.samsungTvApps, line 132
				break // library marker davegut.samsungTvApps, line 133
			case "amazoninstantvideo": // library marker davegut.samsungTvApps, line 134
				appId = "3201910019365" // library marker davegut.samsungTvApps, line 135
				appName = "Prime Video" // library marker davegut.samsungTvApps, line 136
				break // library marker davegut.samsungTvApps, line 137
			default: // library marker davegut.samsungTvApps, line 138
				logWarn("findThisApp: ${appName} not found in appData") // library marker davegut.samsungTvApps, line 139
		} // library marker davegut.samsungTvApps, line 140
	} // library marker davegut.samsungTvApps, line 141
	return [appName, appId] // library marker davegut.samsungTvApps, line 142
} // library marker davegut.samsungTvApps, line 143

def getAppData(appId) { // library marker davegut.samsungTvApps, line 145
	def logData = [appId: appId] // library marker davegut.samsungTvApps, line 146
	def thisApp = state.appData.find { it.value == appId } // library marker davegut.samsungTvApps, line 147
	if (thisApp && !state.appIdIndex) { // library marker davegut.samsungTvApps, line 148
		sendEvent(name: "currentApp", value: thisApp.key) // library marker davegut.samsungTvApps, line 149
		logData << [currentApp: thisApp.key] // library marker davegut.samsungTvApps, line 150
	} else { // library marker davegut.samsungTvApps, line 151
		Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${appId}", // library marker davegut.samsungTvApps, line 152
					  timeout: 3] // library marker davegut.samsungTvApps, line 153
		try { // library marker davegut.samsungTvApps, line 154
			asynchttpGet("getAppDataParse", params, [appId: appId]) // library marker davegut.samsungTvApps, line 155
		} catch (err) { // library marker davegut.samsungTvApps, line 156
			logData: [status: "FAILED", data: err] // library marker davegut.samsungTvApps, line 157
		} // library marker davegut.samsungTvApps, line 158
	} // library marker davegut.samsungTvApps, line 159
	logDebug("getAppData: ${logData}") // library marker davegut.samsungTvApps, line 160
} // library marker davegut.samsungTvApps, line 161

def getAppDataParse(resp, data) { // library marker davegut.samsungTvApps, line 163
	def logData = [appId: data.appId] // library marker davegut.samsungTvApps, line 164
	if (resp.status == 200) { // library marker davegut.samsungTvApps, line 165
		def respData = new JsonSlurper().parseText(resp.data) // library marker davegut.samsungTvApps, line 166
		logData << [resp: respData] // library marker davegut.samsungTvApps, line 167
		state.appData << ["${respData.name}": respData.id] // library marker davegut.samsungTvApps, line 168
		if(!state.appIdIndex && device.currentValue("currentApp") != currApp) { // library marker davegut.samsungTvApps, line 169
			sendEvent(name: "currentApp", value: respData.name) // library marker davegut.samsungTvApps, line 170
			logData << [currentApp: respData.name] // library marker davegut.samsungTvApps, line 171
		} // library marker davegut.samsungTvApps, line 172
	} else if (resp.status == 404) { // library marker davegut.samsungTvApps, line 173
		logData << [status: "appNotPresent", code: resp.status] // library marker davegut.samsungTvApps, line 174
	} else { // library marker davegut.samsungTvApps, line 175
		logData << [status: "FAILED", reason: "${resp.status} response from TV"] // library marker davegut.samsungTvApps, line 176
	} // library marker davegut.samsungTvApps, line 177
	if (logData.status == "FAO:ED") { // library marker davegut.samsungTvApps, line 178
		logWarn("getAppDataParse: ${logData}") // library marker davegut.samsungTvApps, line 179
	} else { // library marker davegut.samsungTvApps, line 180
		logInfo("getAppDataParse: ${logData}") // library marker davegut.samsungTvApps, line 181
	} // library marker davegut.samsungTvApps, line 182
} // library marker davegut.samsungTvApps, line 183

def updateAppCodes() { // library marker davegut.samsungTvApps, line 185
	state.appData = [:] // library marker davegut.samsungTvApps, line 186
	if (device.currentValue("switch") == "on") { // library marker davegut.samsungTvApps, line 187
		logInfo("updateAppCodes: [currentDbSize: ${state.appData.size()}, availableCodes: ${appIdList().size()}]") // library marker davegut.samsungTvApps, line 188
		unschedule("onPoll") // library marker davegut.samsungTvApps, line 189
		runIn(900, setOnPollInterval) // library marker davegut.samsungTvApps, line 190
		state.appIdIndex = 0 // library marker davegut.samsungTvApps, line 191
		findNextApp() // library marker davegut.samsungTvApps, line 192
	} else { // library marker davegut.samsungTvApps, line 193
		logWarn("getAppList: [status: FAILED, reason: tvOff]") // library marker davegut.samsungTvApps, line 194
	} // library marker davegut.samsungTvApps, line 195
	device.updateSetting("findAppCodes", [type:"bool", value: false]) // library marker davegut.samsungTvApps, line 196
} // library marker davegut.samsungTvApps, line 197

def findNextApp() { // library marker davegut.samsungTvApps, line 199
	def appIds = appIdList() // library marker davegut.samsungTvApps, line 200
	def logData = [:] // library marker davegut.samsungTvApps, line 201
	if (state.appIdIndex < appIds.size()) { // library marker davegut.samsungTvApps, line 202
		def nextApp = appIds[state.appIdIndex] // library marker davegut.samsungTvApps, line 203
		state.appIdIndex += 1 // library marker davegut.samsungTvApps, line 204
		getAppData(nextApp) // library marker davegut.samsungTvApps, line 205
		runIn(6, findNextApp) // library marker davegut.samsungTvApps, line 206
	} else { // library marker davegut.samsungTvApps, line 207
		runIn(20, setOnPollInterval) // library marker davegut.samsungTvApps, line 208
		logData << [status: "Complete", appIdsScanned: state.appIdIndex] // library marker davegut.samsungTvApps, line 209
		logData << [totalApps: state.appData.size(), appData: state.appData] // library marker davegut.samsungTvApps, line 210
		state.remove("appIdIndex") // library marker davegut.samsungTvApps, line 211
		logInfo("findNextApp: ${logData}") // library marker davegut.samsungTvApps, line 212
	} // library marker davegut.samsungTvApps, line 213
} // library marker davegut.samsungTvApps, line 214

def appIdList() { // library marker davegut.samsungTvApps, line 216
	def appList = [ // library marker davegut.samsungTvApps, line 217
		"kk8MbItQ0H.VUDU", "vYmY3ACVaa.emby", "ZmmGjO6VKO.slingtv", "MCmYXNxgcu.DisneyPlus", // library marker davegut.samsungTvApps, line 218
		"PvWgqxV3Xa.YouTubeTV", "LBUAQX1exg.Hulu", "AQKO41xyKP.AmazonAlexa", "3KA0pm7a7V.TubiTV", // library marker davegut.samsungTvApps, line 219
		"cj37Ni3qXM.HBONow", "gzcc4LRFBF.Peacock", "9Ur5IzDKqV.TizenYouTube", "BjyffU0l9h.Stream", // library marker davegut.samsungTvApps, line 220
		"3202203026841", "3202103023232", "3202103023185", "3202012022468", "3202012022421", // library marker davegut.samsungTvApps, line 221
		"3202011022316", "3202011022131", "3202010022098", "3202009021877", "3202008021577", // library marker davegut.samsungTvApps, line 222
		"3202008021462", "3202008021439", "3202007021336", "3202004020674", "3202004020626", // library marker davegut.samsungTvApps, line 223
		"3202003020365", "3201910019457", "3201910019449", "3201910019420", "3201910019378", // library marker davegut.samsungTvApps, line 224
		"3201910019365", "3201910019354", "3201909019271", "3201909019175", "3201908019041", // library marker davegut.samsungTvApps, line 225
		"3201908019022", "3201907018807", "3201907018786", "3201907018784", "3201906018693", // library marker davegut.samsungTvApps, line 226
		"3201901017768", "3201901017640", "3201812017479", "3201810017091", "3201810017074", // library marker davegut.samsungTvApps, line 227
		"3201807016597", "3201806016432", "3201806016390", "3201806016381", "3201805016367", // library marker davegut.samsungTvApps, line 228
		"3201803015944", "3201803015934", "3201803015869", "3201711015226", "3201710015067", // library marker davegut.samsungTvApps, line 229
		"3201710015037", "3201710015016", "3201710014874", "3201710014866", "3201707014489", // library marker davegut.samsungTvApps, line 230
		"3201706014250", "3201706012478", "3201704012212", "3201704012147", "3201703012079", // library marker davegut.samsungTvApps, line 231
		"3201703012065", "3201703012029", "3201702011851", "3201612011418", "3201611011210", // library marker davegut.samsungTvApps, line 232
		"3201611011005", "3201611010983", "3201608010385", "3201608010191", "3201607010031", // library marker davegut.samsungTvApps, line 233
		"3201606009910", "3201606009798", "3201606009684", "3201604009182", "3201603008746", // library marker davegut.samsungTvApps, line 234
		"3201603008210", "3201602007865", "3201601007670", "3201601007625", "3201601007230", // library marker davegut.samsungTvApps, line 235
		"3201512006963", "3201512006785", "3201511006428", "3201510005981", "3201506003488", // library marker davegut.samsungTvApps, line 236
		"3201506003486", "3201506003175", "3201504001965", "121299000612", "121299000101", // library marker davegut.samsungTvApps, line 237
		"121299000089", "111399002220", "111399002034", "111399000741", "111299002148", // library marker davegut.samsungTvApps, line 238
		"111299001912", "111299000769", "111012010001", "11101200001", "11101000407", // library marker davegut.samsungTvApps, line 239
		"11091000000" // library marker davegut.samsungTvApps, line 240
	] // library marker davegut.samsungTvApps, line 241
	return appList // library marker davegut.samsungTvApps, line 242
} // library marker davegut.samsungTvApps, line 243

def appRunBrowser() { appOpenByName("Browser") } // library marker davegut.samsungTvApps, line 245

def appRunYouTube() { appOpenByName("YouTube") } // library marker davegut.samsungTvApps, line 247

def appRunNetflix() { appOpenByName("Netflix") } // library marker davegut.samsungTvApps, line 249

def appRunPrimeVideo() { appOpenByName("Prime Video") } // library marker davegut.samsungTvApps, line 251

def appRunYouTubeTV() { appOpenByName("YouTubeTV") } // library marker davegut.samsungTvApps, line 253

def appRunHulu() { appOpenByName("Hulu") } // library marker davegut.samsungTvApps, line 255

// ~~~~~ end include (1366) davegut.samsungTvApps ~~~~~

// ~~~~~ start include (1369) davegut.SmartThingsInterface ~~~~~
library ( // library marker davegut.SmartThingsInterface, line 1
	name: "SmartThingsInterface", // library marker davegut.SmartThingsInterface, line 2
	namespace: "davegut", // library marker davegut.SmartThingsInterface, line 3
	author: "Dave Gutheinz", // library marker davegut.SmartThingsInterface, line 4
	description: "Samsung TV SmartThings Capabilities", // library marker davegut.SmartThingsInterface, line 5
	category: "utilities", // library marker davegut.SmartThingsInterface, line 6
	documentationLink: "" // library marker davegut.SmartThingsInterface, line 7
) // library marker davegut.SmartThingsInterface, line 8

def stPreferences() { // library marker davegut.SmartThingsInterface, line 10
	input ("connectST", "bool", title: "Connect to SmartThings for added functions", defaultValue: false) // library marker davegut.SmartThingsInterface, line 11
	if (connectST) { // library marker davegut.SmartThingsInterface, line 12
		onPollOptions = ["st": "SmartThings", "local": "Local", "off": "DISABLE"] // library marker davegut.SmartThingsInterface, line 13
		input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "") // library marker davegut.SmartThingsInterface, line 14
		if (stApiKey) { // library marker davegut.SmartThingsInterface, line 15
			input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "") // library marker davegut.SmartThingsInterface, line 16
		} // library marker davegut.SmartThingsInterface, line 17
		input ("stPollInterval", "enum", title: "SmartThings Poll Interval (minutes)", // library marker davegut.SmartThingsInterface, line 18
			   options: ["off", "1", "5", "15", "30"], defaultValue: "15") // library marker davegut.SmartThingsInterface, line 19
		input ("stTestData", "bool", title: "Get ST data dump for developer", defaultValue: false) // library marker davegut.SmartThingsInterface, line 20
	} // library marker davegut.SmartThingsInterface, line 21
} // library marker davegut.SmartThingsInterface, line 22

def stUpdate() { // library marker davegut.SmartThingsInterface, line 24
	def stData = [:] // library marker davegut.SmartThingsInterface, line 25
	if (connectST) { // library marker davegut.SmartThingsInterface, line 26
		stData << [connectST: "true"] // library marker davegut.SmartThingsInterface, line 27
		stData << [connectST: connectST] // library marker davegut.SmartThingsInterface, line 28
		if (!stApiKey || stApiKey == "") { // library marker davegut.SmartThingsInterface, line 29
			logWarn("\n\n\t\t<b>Enter the ST API Key and Save Preferences</b>\n\n") // library marker davegut.SmartThingsInterface, line 30
			stData << [status: "ERROR", date: "no stApiKey"] // library marker davegut.SmartThingsInterface, line 31
		} else if (!stDeviceId || stDeviceId == "") { // library marker davegut.SmartThingsInterface, line 32
			getDeviceList() // library marker davegut.SmartThingsInterface, line 33
			logWarn("\n\n\t\t<b>Enter the deviceId from the Log List and Save Preferences</b>\n\n") // library marker davegut.SmartThingsInterface, line 34
			stData << [status: "ERROR", date: "no stDeviceId"] // library marker davegut.SmartThingsInterface, line 35
		} else { // library marker davegut.SmartThingsInterface, line 36
			def stPollInterval = stPollInterval // library marker davegut.SmartThingsInterface, line 37
			if (stPollInterval == null) {  // library marker davegut.SmartThingsInterface, line 38
				stPollInterval = "15" // library marker davegut.SmartThingsInterface, line 39
				device.updateSetting("stPollInterval", [type:"enum", value: "15"]) // library marker davegut.SmartThingsInterface, line 40
			} // library marker davegut.SmartThingsInterface, line 41
			switch(stPollInterval) { // library marker davegut.SmartThingsInterface, line 42
				case "1" : runEvery1Minute(refresh); break // library marker davegut.SmartThingsInterface, line 43
				case "5" : runEvery5Minutes(refresh); break // library marker davegut.SmartThingsInterface, line 44
				case "15" : runEvery15Minutes(refresh); break // library marker davegut.SmartThingsInterface, line 45
				case "30" : runEvery30Minutes(refresh); break // library marker davegut.SmartThingsInterface, line 46
				default: unschedule("refresh") // library marker davegut.SmartThingsInterface, line 47
			} // library marker davegut.SmartThingsInterface, line 48
			deviceSetup() // library marker davegut.SmartThingsInterface, line 49
			stData << [stPollInterval: stPollInterval] // library marker davegut.SmartThingsInterface, line 50
		} // library marker davegut.SmartThingsInterface, line 51
	} else { // library marker davegut.SmartThingsInterface, line 52
		stData << [connectST: "false"] // library marker davegut.SmartThingsInterface, line 53
	} // library marker davegut.SmartThingsInterface, line 54
	logInfo("stUpdate: ${stData}") // library marker davegut.SmartThingsInterface, line 55
} // library marker davegut.SmartThingsInterface, line 56

def deviceSetup() { // library marker davegut.SmartThingsInterface, line 58
	if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.SmartThingsInterface, line 59
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.SmartThingsInterface, line 60
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.SmartThingsInterface, line 61
	} else { // library marker davegut.SmartThingsInterface, line 62
		def sendData = [ // library marker davegut.SmartThingsInterface, line 63
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.SmartThingsInterface, line 64
			parse: "distResp" // library marker davegut.SmartThingsInterface, line 65
			] // library marker davegut.SmartThingsInterface, line 66
		asyncGet(sendData, "deviceSetup") // library marker davegut.SmartThingsInterface, line 67
	} // library marker davegut.SmartThingsInterface, line 68
} // library marker davegut.SmartThingsInterface, line 69

def getDeviceList() { // library marker davegut.SmartThingsInterface, line 71
	def sendData = [ // library marker davegut.SmartThingsInterface, line 72
		path: "/devices", // library marker davegut.SmartThingsInterface, line 73
		parse: "getDeviceListParse" // library marker davegut.SmartThingsInterface, line 74
		] // library marker davegut.SmartThingsInterface, line 75
	asyncGet(sendData) // library marker davegut.SmartThingsInterface, line 76
} // library marker davegut.SmartThingsInterface, line 77

def getDeviceListParse(resp, data) { // library marker davegut.SmartThingsInterface, line 79
	def respData // library marker davegut.SmartThingsInterface, line 80
	if (resp.status != 200) { // library marker davegut.SmartThingsInterface, line 81
		respData = [status: "ERROR", // library marker davegut.SmartThingsInterface, line 82
					httpCode: resp.status, // library marker davegut.SmartThingsInterface, line 83
					errorMsg: resp.errorMessage] // library marker davegut.SmartThingsInterface, line 84
	} else { // library marker davegut.SmartThingsInterface, line 85
		try { // library marker davegut.SmartThingsInterface, line 86
			respData = new JsonSlurper().parseText(resp.data) // library marker davegut.SmartThingsInterface, line 87
		} catch (err) { // library marker davegut.SmartThingsInterface, line 88
			respData = [status: "ERROR", // library marker davegut.SmartThingsInterface, line 89
						errorMsg: err, // library marker davegut.SmartThingsInterface, line 90
						respData: resp.data] // library marker davegut.SmartThingsInterface, line 91
		} // library marker davegut.SmartThingsInterface, line 92
	} // library marker davegut.SmartThingsInterface, line 93
	if (respData.status == "ERROR") { // library marker davegut.SmartThingsInterface, line 94
		logWarn("getDeviceListParse: ${respData}") // library marker davegut.SmartThingsInterface, line 95
	} else { // library marker davegut.SmartThingsInterface, line 96
		log.info "" // library marker davegut.SmartThingsInterface, line 97
		respData.items.each { // library marker davegut.SmartThingsInterface, line 98
			log.trace "${it.label}:   ${it.deviceId}" // library marker davegut.SmartThingsInterface, line 99
		} // library marker davegut.SmartThingsInterface, line 100
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>" // library marker davegut.SmartThingsInterface, line 101
	} // library marker davegut.SmartThingsInterface, line 102
} // library marker davegut.SmartThingsInterface, line 103

def deviceSetupParse(mainData) { // library marker davegut.SmartThingsInterface, line 105
	def setupData = [:] // library marker davegut.SmartThingsInterface, line 106
	def supportedInputs =  mainData.mediaInputSource.supportedInputSources.value // library marker davegut.SmartThingsInterface, line 107
	state.supportedInputs = supportedInputs // library marker davegut.SmartThingsInterface, line 108
	setupData << [supportedInputs: supportedInputs] // library marker davegut.SmartThingsInterface, line 109

	def pictureModes = mainData["custom.picturemode"].supportedPictureModes.value // library marker davegut.SmartThingsInterface, line 111
	state.pictureModes = pictureModes // library marker davegut.SmartThingsInterface, line 112
	setupData << [pictureModes: pictureModes] // library marker davegut.SmartThingsInterface, line 113

	def soundModes =  mainData["custom.soundmode"].supportedSoundModes.value // library marker davegut.SmartThingsInterface, line 115
	state.soundModes = soundModes // library marker davegut.SmartThingsInterface, line 116
	setupData << [soundModes: soundModes] // library marker davegut.SmartThingsInterface, line 117

	logInfo("deviceSetupParse: ${setupData}") // library marker davegut.SmartThingsInterface, line 119
} // library marker davegut.SmartThingsInterface, line 120

def deviceCommand(cmdData) { // library marker davegut.SmartThingsInterface, line 122
	logTrace("deviceCommand: $cmdData") // library marker davegut.SmartThingsInterface, line 123
	def respData = [:] // library marker davegut.SmartThingsInterface, line 124
	if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.SmartThingsInterface, line 125
		respData << [status: "FAILED", data: "no stDeviceId"] // library marker davegut.SmartThingsInterface, line 126
	} else { // library marker davegut.SmartThingsInterface, line 127
		def sendData = [ // library marker davegut.SmartThingsInterface, line 128
			path: "/devices/${stDeviceId.trim()}/commands", // library marker davegut.SmartThingsInterface, line 129
			cmdData: cmdData // library marker davegut.SmartThingsInterface, line 130
		] // library marker davegut.SmartThingsInterface, line 131
		respData = syncPost(sendData) // library marker davegut.SmartThingsInterface, line 132
	} // library marker davegut.SmartThingsInterface, line 133
	if (respData.status == "OK") { // library marker davegut.SmartThingsInterface, line 134
		if (respData.results[0].status == "COMPLETED") { // library marker davegut.SmartThingsInterface, line 135
			if (cmdData.capability && cmdData.capability != "refresh") { // library marker davegut.SmartThingsInterface, line 136
				refresh() // library marker davegut.SmartThingsInterface, line 137
			} else { // library marker davegut.SmartThingsInterface, line 138
				poll() // library marker davegut.SmartThingsInterface, line 139
			} // library marker davegut.SmartThingsInterface, line 140
		} // library marker davegut.SmartThingsInterface, line 141
	}else { // library marker davegut.SmartThingsInterface, line 142
		logWarn("deviceCommand: [status: ${respData.status}, data: ${respData}]") // library marker davegut.SmartThingsInterface, line 143
		if (respData.toString().contains("Conflict")) { // library marker davegut.SmartThingsInterface, line 144
			logWarn("<b>Conflict internal to SmartThings.  Device may be offline in SmartThings</b>") // library marker davegut.SmartThingsInterface, line 145
		} // library marker davegut.SmartThingsInterface, line 146
	} // library marker davegut.SmartThingsInterface, line 147
} // library marker davegut.SmartThingsInterface, line 148

def statusParse(mainData) { // library marker davegut.SmartThingsInterface, line 150
	if (stTestData) { // library marker davegut.SmartThingsInterface, line 151
		device.updateSetting("stTestData", [type:"bool", value: false]) // library marker davegut.SmartThingsInterface, line 152
		log.warn mainData // library marker davegut.SmartThingsInterface, line 153
	} // library marker davegut.SmartThingsInterface, line 154
	def stData = [:] // library marker davegut.SmartThingsInterface, line 155
	if (logEnable || traceLog) { // library marker davegut.SmartThingsInterface, line 156
		def quickLog = [:] // library marker davegut.SmartThingsInterface, line 157
		try { // library marker davegut.SmartThingsInterface, line 158
			quickLog << [ // library marker davegut.SmartThingsInterface, line 159
				switch: [device.currentValue("switch"), mainData.switch.switch.value], // library marker davegut.SmartThingsInterface, line 160
				volume: [device.currentValue("volume"), mainData.audioVolume.volume.value.toInteger()], // library marker davegut.SmartThingsInterface, line 161
				mute: [device.currentValue("mute"), mainData.audioMute.mute.value], // library marker davegut.SmartThingsInterface, line 162
				input: [device.currentValue("inputSource"), mainData.mediaInputSource.inputSource.value], // library marker davegut.SmartThingsInterface, line 163
				channel: [device.currentValue("tvChannel"), mainData.tvChannel.tvChannel.value.toString()], // library marker davegut.SmartThingsInterface, line 164
				channelName: [device.currentValue("tvChannelName"), mainData.tvChannel.tvChannelName.value], // library marker davegut.SmartThingsInterface, line 165
				pictureMode: [device.currentValue("pictureMode"), mainData["custom.picturemode"].pictureMode.value], // library marker davegut.SmartThingsInterface, line 166
				soundMode: [device.currentValue("soundMode"), mainData["custom.soundmode"].soundMode.value], // library marker davegut.SmartThingsInterface, line 167
				transportStatus: [device.currentValue("transportStatus"), mainData.mediaPlayback.playbackStatus.value]] // library marker davegut.SmartThingsInterface, line 168
		} catch (err) { // library marker davegut.SmartThingsInterface, line 169
			quickLog << [error: ${err}, data: mainData] // library marker davegut.SmartThingsInterface, line 170
		} // library marker davegut.SmartThingsInterface, line 171
		logDebug("statusParse: [quickLog: ${quickLog}]") // library marker davegut.SmartThingsInterface, line 172
		logTrace("statusParse: [quickLog: ${quickLog}]") // library marker davegut.SmartThingsInterface, line 173
	} // library marker davegut.SmartThingsInterface, line 174

	if (device.currentValue("switch") == "on") { // library marker davegut.SmartThingsInterface, line 176
		Integer volume = mainData.audioVolume.volume.value.toInteger() // library marker davegut.SmartThingsInterface, line 177
		if (device.currentValue("volume") != volume) { // library marker davegut.SmartThingsInterface, line 178
			sendEvent(name: "volume", value: volume) // library marker davegut.SmartThingsInterface, line 179
			sendEvent(name: "level", value: volume) // library marker davegut.SmartThingsInterface, line 180
			stData << [volume: volume] // library marker davegut.SmartThingsInterface, line 181
		} // library marker davegut.SmartThingsInterface, line 182

		String mute = mainData.audioMute.mute.value // library marker davegut.SmartThingsInterface, line 184
		if (device.currentValue("mute") != mute) { // library marker davegut.SmartThingsInterface, line 185
			sendEvent(name: "mute", value: mute) // library marker davegut.SmartThingsInterface, line 186
			stData << [mute: mute] // library marker davegut.SmartThingsInterface, line 187
		} // library marker davegut.SmartThingsInterface, line 188

		String inputSource = mainData.mediaInputSource.inputSource.value // library marker davegut.SmartThingsInterface, line 190
		if (device.currentValue("inputSource") != inputSource) { // library marker davegut.SmartThingsInterface, line 191
			sendEvent(name: "inputSource", value: inputSource)		 // library marker davegut.SmartThingsInterface, line 192
			stData << [inputSource: inputSource] // library marker davegut.SmartThingsInterface, line 193
		} // library marker davegut.SmartThingsInterface, line 194

		String tvChannel = mainData.tvChannel.tvChannel.value.toString() // library marker davegut.SmartThingsInterface, line 196
		if (tvChannel == "" || tvChannel == null) { // library marker davegut.SmartThingsInterface, line 197
			tvChannel = " " // library marker davegut.SmartThingsInterface, line 198
		} // library marker davegut.SmartThingsInterface, line 199
		String tvChannelName = mainData.tvChannel.tvChannelName.value // library marker davegut.SmartThingsInterface, line 200
		if (tvChannelName == "") { // library marker davegut.SmartThingsInterface, line 201
			tvChannelName = " " // library marker davegut.SmartThingsInterface, line 202
		} // library marker davegut.SmartThingsInterface, line 203
		if (device.currentValue("tvChannelName") != tvChannelName) { // library marker davegut.SmartThingsInterface, line 204
			sendEvent(name: "tvChannel", value: tvChannel) // library marker davegut.SmartThingsInterface, line 205
			sendEvent(name: "tvChannelName", value: tvChannelName) // library marker davegut.SmartThingsInterface, line 206
			if (tvChannelName.contains(".")) { // library marker davegut.SmartThingsInterface, line 207
				getAppData(tvChannelName) // library marker davegut.SmartThingsInterface, line 208
			} else { // library marker davegut.SmartThingsInterface, line 209
				sendEvent(name: "currentApp", value: " ") // library marker davegut.SmartThingsInterface, line 210
			} // library marker davegut.SmartThingsInterface, line 211
			stData << [tvChannel: tvChannel, tvChannelName: tvChannelName] // library marker davegut.SmartThingsInterface, line 212
			if (getDataValue("frameTv") == "true" && !state.artModeWs) { // library marker davegut.SmartThingsInterface, line 213
				String artMode = "off" // library marker davegut.SmartThingsInterface, line 214
				if (tvChannelName == "art") { artMode = "on" } // library marker davegut.SmartThingsInterface, line 215
				sendEvent(name: "artModeStatus", value: artMode) // library marker davegut.SmartThingsInterface, line 216
			} // library marker davegut.SmartThingsInterface, line 217
		} // library marker davegut.SmartThingsInterface, line 218

		String trackDesc = inputSource // library marker davegut.SmartThingsInterface, line 220
		if (tvChannelName != " ") { trackDesc = tvChannelName } // library marker davegut.SmartThingsInterface, line 221
		if (device.currentValue("trackDescription") != trackDesc) { // library marker davegut.SmartThingsInterface, line 222
			sendEvent(name: "trackDescription", value:trackDesc) // library marker davegut.SmartThingsInterface, line 223
			stData << [trackDescription: trackDesc] // library marker davegut.SmartThingsInterface, line 224
		} // library marker davegut.SmartThingsInterface, line 225

		String pictureMode = mainData["custom.picturemode"].pictureMode.value // library marker davegut.SmartThingsInterface, line 227
		if (device.currentValue("pictureMode") != pictureMode) { // library marker davegut.SmartThingsInterface, line 228
			sendEvent(name: "pictureMode",value: pictureMode) // library marker davegut.SmartThingsInterface, line 229
			stData << [pictureMode: pictureMode] // library marker davegut.SmartThingsInterface, line 230
		} // library marker davegut.SmartThingsInterface, line 231

		String soundMode = mainData["custom.soundmode"].soundMode.value // library marker davegut.SmartThingsInterface, line 233
		if (device.currentValue("soundMode") != soundMode) { // library marker davegut.SmartThingsInterface, line 234
			sendEvent(name: "soundMode",value: soundMode) // library marker davegut.SmartThingsInterface, line 235
			stData << [soundMode: soundMode] // library marker davegut.SmartThingsInterface, line 236
		} // library marker davegut.SmartThingsInterface, line 237

		String transportStatus = mainData.mediaPlayback.playbackStatus.value // library marker davegut.SmartThingsInterface, line 239
		if (transportStatus == null || transportStatus == "") { // library marker davegut.SmartThingsInterface, line 240
			transportStatus = "n/a" // library marker davegut.SmartThingsInterface, line 241
		} // library marker davegut.SmartThingsInterface, line 242
		if (device.currentValue("transportStatus") != transportStatus) { // library marker davegut.SmartThingsInterface, line 243
			sendEvent(name: "transportStatus", value: transportStatus) // library marker davegut.SmartThingsInterface, line 244
			stData << [transportStatus: transportStatus] // library marker davegut.SmartThingsInterface, line 245
		} // library marker davegut.SmartThingsInterface, line 246
	} // library marker davegut.SmartThingsInterface, line 247

	if (stData != [:]) { // library marker davegut.SmartThingsInterface, line 249
		logInfo("statusParse: ${stData}") // library marker davegut.SmartThingsInterface, line 250
	} // library marker davegut.SmartThingsInterface, line 251
} // library marker davegut.SmartThingsInterface, line 252

private asyncGet(sendData, passData = "none") { // library marker davegut.SmartThingsInterface, line 254
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.SmartThingsInterface, line 255
		logWarn("asyncGet: [status: ERROR, errorMsg: no stApiKey]") // library marker davegut.SmartThingsInterface, line 256
	} else { // library marker davegut.SmartThingsInterface, line 257
		logDebug("asyncGet: ${sendData}, ${passData}") // library marker davegut.SmartThingsInterface, line 258
		def sendCmdParams = [ // library marker davegut.SmartThingsInterface, line 259
			uri: "https://api.smartthings.com/v1", // library marker davegut.SmartThingsInterface, line 260
			path: sendData.path, // library marker davegut.SmartThingsInterface, line 261
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()]] // library marker davegut.SmartThingsInterface, line 262
		try { // library marker davegut.SmartThingsInterface, line 263
			asynchttpGet(sendData.parse, sendCmdParams, [reason: passData]) // library marker davegut.SmartThingsInterface, line 264
		} catch (error) { // library marker davegut.SmartThingsInterface, line 265
			logWarn("asyncGet: [status: FAILED, errorMsg: ${error}]") // library marker davegut.SmartThingsInterface, line 266
		} // library marker davegut.SmartThingsInterface, line 267
	} // library marker davegut.SmartThingsInterface, line 268
} // library marker davegut.SmartThingsInterface, line 269

private syncGet(path){ // library marker davegut.SmartThingsInterface, line 271
	def respData = [:] // library marker davegut.SmartThingsInterface, line 272
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.SmartThingsInterface, line 273
		respData << [status: "FAILED", // library marker davegut.SmartThingsInterface, line 274
					 errorMsg: "No stApiKey"] // library marker davegut.SmartThingsInterface, line 275
	} else { // library marker davegut.SmartThingsInterface, line 276
		logDebug("syncGet: ${sendData}") // library marker davegut.SmartThingsInterface, line 277
		def sendCmdParams = [ // library marker davegut.SmartThingsInterface, line 278
			uri: "https://api.smartthings.com/v1", // library marker davegut.SmartThingsInterface, line 279
			path: path, // library marker davegut.SmartThingsInterface, line 280
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()] // library marker davegut.SmartThingsInterface, line 281
		] // library marker davegut.SmartThingsInterface, line 282
		try { // library marker davegut.SmartThingsInterface, line 283
			httpGet(sendCmdParams) {resp -> // library marker davegut.SmartThingsInterface, line 284
				if (resp.status == 200 && resp.data != null) { // library marker davegut.SmartThingsInterface, line 285
					respData << [status: "OK", results: resp.data] // library marker davegut.SmartThingsInterface, line 286
				} else { // library marker davegut.SmartThingsInterface, line 287
					respData << [status: "FAILED", // library marker davegut.SmartThingsInterface, line 288
								 httpCode: resp.status, // library marker davegut.SmartThingsInterface, line 289
								 errorMsg: resp.errorMessage] // library marker davegut.SmartThingsInterface, line 290
				} // library marker davegut.SmartThingsInterface, line 291
			} // library marker davegut.SmartThingsInterface, line 292
		} catch (error) { // library marker davegut.SmartThingsInterface, line 293
			respData << [status: "FAILED", // library marker davegut.SmartThingsInterface, line 294
						 errorMsg: error] // library marker davegut.SmartThingsInterface, line 295
		} // library marker davegut.SmartThingsInterface, line 296
	} // library marker davegut.SmartThingsInterface, line 297
	return respData // library marker davegut.SmartThingsInterface, line 298
} // library marker davegut.SmartThingsInterface, line 299

private syncPost(sendData){ // library marker davegut.SmartThingsInterface, line 301
	def respData = [:] // library marker davegut.SmartThingsInterface, line 302
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.SmartThingsInterface, line 303
		respData << [status: "FAILED", // library marker davegut.SmartThingsInterface, line 304
					 errorMsg: "No stApiKey"] // library marker davegut.SmartThingsInterface, line 305
	} else { // library marker davegut.SmartThingsInterface, line 306
		logDebug("syncPost: ${sendData}") // library marker davegut.SmartThingsInterface, line 307
		def cmdBody = [commands: [sendData.cmdData]] // library marker davegut.SmartThingsInterface, line 308
		def sendCmdParams = [ // library marker davegut.SmartThingsInterface, line 309
			uri: "https://api.smartthings.com/v1", // library marker davegut.SmartThingsInterface, line 310
			path: sendData.path, // library marker davegut.SmartThingsInterface, line 311
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()], // library marker davegut.SmartThingsInterface, line 312
			body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.SmartThingsInterface, line 313
		] // library marker davegut.SmartThingsInterface, line 314
		try { // library marker davegut.SmartThingsInterface, line 315
			httpPost(sendCmdParams) {resp -> // library marker davegut.SmartThingsInterface, line 316
				if (resp.status == 200 && resp.data != null) { // library marker davegut.SmartThingsInterface, line 317
					respData << [status: "OK", results: resp.data.results] // library marker davegut.SmartThingsInterface, line 318
				} else { // library marker davegut.SmartThingsInterface, line 319
					respData << [status: "FAILED", // library marker davegut.SmartThingsInterface, line 320
								 httpCode: resp.status, // library marker davegut.SmartThingsInterface, line 321
								 errorMsg: resp.errorMessage] // library marker davegut.SmartThingsInterface, line 322
				} // library marker davegut.SmartThingsInterface, line 323
			} // library marker davegut.SmartThingsInterface, line 324
		} catch (error) { // library marker davegut.SmartThingsInterface, line 325
			respData << [status: "FAILED", // library marker davegut.SmartThingsInterface, line 326
						 errorMsg: error] // library marker davegut.SmartThingsInterface, line 327
		} // library marker davegut.SmartThingsInterface, line 328
	} // library marker davegut.SmartThingsInterface, line 329
	return respData // library marker davegut.SmartThingsInterface, line 330
} // library marker davegut.SmartThingsInterface, line 331

def distResp(resp, data) { // library marker davegut.SmartThingsInterface, line 333
	def respLog = [:] // library marker davegut.SmartThingsInterface, line 334
	if (resp.status == 200) { // library marker davegut.SmartThingsInterface, line 335
		try { // library marker davegut.SmartThingsInterface, line 336
			def respData = new JsonSlurper().parseText(resp.data) // library marker davegut.SmartThingsInterface, line 337
			if (data.reason == "deviceSetup") { // library marker davegut.SmartThingsInterface, line 338
				deviceSetupParse(respData.components.main) // library marker davegut.SmartThingsInterface, line 339
				runIn(1, statusParse, [data: respData.components.main]) // library marker davegut.SmartThingsInterface, line 340
			} else { // library marker davegut.SmartThingsInterface, line 341
				statusParse(respData.components.main) // library marker davegut.SmartThingsInterface, line 342
			} // library marker davegut.SmartThingsInterface, line 343
		} catch (err) { // library marker davegut.SmartThingsInterface, line 344
			respLog << [status: "ERROR", // library marker davegut.SmartThingsInterface, line 345
						errorMsg: err, // library marker davegut.SmartThingsInterface, line 346
						respData: resp.data] // library marker davegut.SmartThingsInterface, line 347
		} // library marker davegut.SmartThingsInterface, line 348
	} else { // library marker davegut.SmartThingsInterface, line 349
		respLog << [status: "ERROR", // library marker davegut.SmartThingsInterface, line 350
					httpCode: resp.status, // library marker davegut.SmartThingsInterface, line 351
					errorMsg: resp.errorMessage] // library marker davegut.SmartThingsInterface, line 352
	} // library marker davegut.SmartThingsInterface, line 353
	if (respLog != [:]) { // library marker davegut.SmartThingsInterface, line 354
		logWarn("distResp: ${respLog}") // library marker davegut.SmartThingsInterface, line 355
	} // library marker davegut.SmartThingsInterface, line 356
} // library marker davegut.SmartThingsInterface, line 357

// ~~~~~ end include (1369) davegut.SmartThingsInterface ~~~~~

// ~~~~~ start include (1368) davegut.samsungTvST ~~~~~
library ( // library marker davegut.samsungTvST, line 1
	name: "samsungTvST", // library marker davegut.samsungTvST, line 2
	namespace: "davegut", // library marker davegut.samsungTvST, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTvST, line 4
	description: "Samsung TV SmartThings Capabilities", // library marker davegut.samsungTvST, line 5
	category: "utilities", // library marker davegut.samsungTvST, line 6
	documentationLink: "" // library marker davegut.samsungTvST, line 7
) // library marker davegut.samsungTvST, line 8

command "toggleInputSource", [[name: "SmartThings Function"]] // library marker davegut.samsungTvST, line 10
command "toggleSoundMode", [[name: "SmartThings Function"]] // library marker davegut.samsungTvST, line 11
command "togglePictureMode", [[name: "SmartThings Function"]] // library marker davegut.samsungTvST, line 12
command "setTvChannel", ["SmartThings Function"] // library marker davegut.samsungTvST, line 13
attribute "tvChannel", "string" // library marker davegut.samsungTvST, line 14
attribute "tvChannelName", "string" // library marker davegut.samsungTvST, line 15
command "setInputSource", ["SmartThings Function"] // library marker davegut.samsungTvST, line 16
attribute "inputSource", "string" // library marker davegut.samsungTvST, line 17
command "setVolume", ["SmartThings Function"] // library marker davegut.samsungTvST, line 18
command "setPictureMode", ["SmartThings Function"] // library marker davegut.samsungTvST, line 19
command "setSoundMode", ["SmartThings Function"] // library marker davegut.samsungTvST, line 20
command "setLevel", ["SmartThings Function"] // library marker davegut.samsungTvST, line 21
attribute "transportStatus", "string" // library marker davegut.samsungTvST, line 22
attribute "level", "NUMBER" // library marker davegut.samsungTvST, line 23
attribute "trackDescription", "string" // library marker davegut.samsungTvST, line 24


/* // library marker davegut.samsungTvST, line 27
def stPreferences() { // library marker davegut.samsungTvST, line 28
	input ("connectST", "bool", title: "Connect to SmartThings for added functions", defaultValue: false) // library marker davegut.samsungTvST, line 29
	if (connectST) { // library marker davegut.samsungTvST, line 30
		onPollOptions = ["st": "SmartThings", "local": "Local", "off": "DISABLE"] // library marker davegut.samsungTvST, line 31
		input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "") // library marker davegut.samsungTvST, line 32
		if (stApiKey) { // library marker davegut.samsungTvST, line 33
			input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "") // library marker davegut.samsungTvST, line 34
		} // library marker davegut.samsungTvST, line 35
		input ("stPollInterval", "enum", title: "SmartThings Poll Interval (minutes)", // library marker davegut.samsungTvST, line 36
			   options: ["off", "1", "5", "15", "30"], defaultValue: "15") // library marker davegut.samsungTvST, line 37
		input ("stTestData", "bool", title: "Get ST data dump for developer", defaultValue: false) // library marker davegut.samsungTvST, line 38
	} // library marker davegut.samsungTvST, line 39


} // library marker davegut.samsungTvST, line 42

def stUpdate() { // library marker davegut.samsungTvST, line 44
	def stData = [:] // library marker davegut.samsungTvST, line 45
	if (connectST) { // library marker davegut.samsungTvST, line 46
		stData << [connectST: "true"] // library marker davegut.samsungTvST, line 47
		stData << [connectST: connectST] // library marker davegut.samsungTvST, line 48
		if (!stApiKey || stApiKey == "") { // library marker davegut.samsungTvST, line 49
			logWarn("\n\n\t\t<b>Enter the ST API Key and Save Preferences</b>\n\n") // library marker davegut.samsungTvST, line 50
			stData << [status: "ERROR", date: "no stApiKey"] // library marker davegut.samsungTvST, line 51
		} else if (!stDeviceId || stDeviceId == "") { // library marker davegut.samsungTvST, line 52
			getDeviceList() // library marker davegut.samsungTvST, line 53
			logWarn("\n\n\t\t<b>Enter the deviceId from the Log List and Save Preferences</b>\n\n") // library marker davegut.samsungTvST, line 54
			stData << [status: "ERROR", date: "no stDeviceId"] // library marker davegut.samsungTvST, line 55
		} else { // library marker davegut.samsungTvST, line 56
			if (device.currentValue("volume") == null) { // library marker davegut.samsungTvST, line 57
//				sendEvent(name: "volume", value: 0) // library marker davegut.samsungTvST, line 58
//				sendEvent(name: "level", value: 0) // library marker davegut.samsungTvST, line 59
			} // library marker davegut.samsungTvST, line 60
			def stPollInterval = stPollInterval // library marker davegut.samsungTvST, line 61
			if (stPollInterval == null) {  // library marker davegut.samsungTvST, line 62
				stPollInterval = "15" // library marker davegut.samsungTvST, line 63
				device.updateSetting("stPollInterval", [type:"enum", value: "15"]) // library marker davegut.samsungTvST, line 64
			} // library marker davegut.samsungTvST, line 65
			switch(stPollInterval) { // library marker davegut.samsungTvST, line 66
				case "1" : runEvery1Minute(refresh); break // library marker davegut.samsungTvST, line 67
				case "5" : runEvery5Minutes(refresh); break // library marker davegut.samsungTvST, line 68
				case "15" : runEvery15Minutes(refresh); break // library marker davegut.samsungTvST, line 69
				case "30" : runEvery30Minutes(refresh); break // library marker davegut.samsungTvST, line 70
				default: unschedule("refresh") // library marker davegut.samsungTvST, line 71
			} // library marker davegut.samsungTvST, line 72
			deviceSetup() // library marker davegut.samsungTvST, line 73
			stData << [stPollInterval: stPollInterval] // library marker davegut.samsungTvST, line 74
		} // library marker davegut.samsungTvST, line 75
	} else { // library marker davegut.samsungTvST, line 76
		stData << [connectST: "false"] // library marker davegut.samsungTvST, line 77
	} // library marker davegut.samsungTvST, line 78
	logInfo("stUpdate: ${stData}") // library marker davegut.samsungTvST, line 79
} // library marker davegut.samsungTvST, line 80

def deviceSetup() { // library marker davegut.samsungTvST, line 82
	if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.samsungTvST, line 83
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.samsungTvST, line 84
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.samsungTvST, line 85
	} else { // library marker davegut.samsungTvST, line 86
		def sendData = [ // library marker davegut.samsungTvST, line 87
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.samsungTvST, line 88
			parse: "distResp" // library marker davegut.samsungTvST, line 89
			] // library marker davegut.samsungTvST, line 90
		asyncGet(sendData, "deviceSetup") // library marker davegut.samsungTvST, line 91
	} // library marker davegut.samsungTvST, line 92
} // library marker davegut.samsungTvST, line 93

def getDeviceList() { // library marker davegut.samsungTvST, line 95
	def sendData = [ // library marker davegut.samsungTvST, line 96
		path: "/devices", // library marker davegut.samsungTvST, line 97
		parse: "getDeviceListParse" // library marker davegut.samsungTvST, line 98
		] // library marker davegut.samsungTvST, line 99
	asyncGet(sendData) // library marker davegut.samsungTvST, line 100
} // library marker davegut.samsungTvST, line 101

def getDeviceListParse(resp, data) { // library marker davegut.samsungTvST, line 103
	def respData // library marker davegut.samsungTvST, line 104
	if (resp.status != 200) { // library marker davegut.samsungTvST, line 105
		respData = [status: "ERROR", // library marker davegut.samsungTvST, line 106
					httpCode: resp.status, // library marker davegut.samsungTvST, line 107
					errorMsg: resp.errorMessage] // library marker davegut.samsungTvST, line 108
	} else { // library marker davegut.samsungTvST, line 109
		try { // library marker davegut.samsungTvST, line 110
			respData = new JsonSlurper().parseText(resp.data) // library marker davegut.samsungTvST, line 111
		} catch (err) { // library marker davegut.samsungTvST, line 112
			respData = [status: "ERROR", // library marker davegut.samsungTvST, line 113
						errorMsg: err, // library marker davegut.samsungTvST, line 114
						respData: resp.data] // library marker davegut.samsungTvST, line 115
		} // library marker davegut.samsungTvST, line 116
	} // library marker davegut.samsungTvST, line 117
	if (respData.status == "ERROR") { // library marker davegut.samsungTvST, line 118
		logWarn("getDeviceListParse: ${respData}") // library marker davegut.samsungTvST, line 119
	} else { // library marker davegut.samsungTvST, line 120
		log.info "" // library marker davegut.samsungTvST, line 121
		respData.items.each { // library marker davegut.samsungTvST, line 122
			log.trace "${it.label}:   ${it.deviceId}" // library marker davegut.samsungTvST, line 123
		} // library marker davegut.samsungTvST, line 124
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>" // library marker davegut.samsungTvST, line 125
	} // library marker davegut.samsungTvST, line 126
} // library marker davegut.samsungTvST, line 127

def deviceSetupParse(mainData) { // library marker davegut.samsungTvST, line 129
	def setupData = [:] // library marker davegut.samsungTvST, line 130
	def supportedInputs =  mainData.mediaInputSource.supportedInputSources.value // library marker davegut.samsungTvST, line 131
	state.supportedInputs = supportedInputs // library marker davegut.samsungTvST, line 132
	setupData << [supportedInputs: supportedInputs] // library marker davegut.samsungTvST, line 133

	def pictureModes = mainData["custom.picturemode"].supportedPictureModes.value // library marker davegut.samsungTvST, line 135
	state.pictureModes = pictureModes // library marker davegut.samsungTvST, line 136
	setupData << [pictureModes: pictureModes] // library marker davegut.samsungTvST, line 137

	def soundModes =  mainData["custom.soundmode"].supportedSoundModes.value // library marker davegut.samsungTvST, line 139
	state.soundModes = soundModes // library marker davegut.samsungTvST, line 140
	setupData << [soundModes: soundModes] // library marker davegut.samsungTvST, line 141

	logInfo("deviceSetupParse: ${setupData}") // library marker davegut.samsungTvST, line 143
} // library marker davegut.samsungTvST, line 144
*/ // library marker davegut.samsungTvST, line 145



def deviceRefresh() { refresh() } // library marker davegut.samsungTvST, line 149

def refresh() { // library marker davegut.samsungTvST, line 151
	if (connectST && stApiKey!= null) { // library marker davegut.samsungTvST, line 152
		def cmdData = [ // library marker davegut.samsungTvST, line 153
			component: "main", // library marker davegut.samsungTvST, line 154
			capability: "refresh", // library marker davegut.samsungTvST, line 155
			command: "refresh", // library marker davegut.samsungTvST, line 156
			arguments: []] // library marker davegut.samsungTvST, line 157
		deviceCommand(cmdData) // library marker davegut.samsungTvST, line 158
	} // library marker davegut.samsungTvST, line 159
} // library marker davegut.samsungTvST, line 160

def poll() { // library marker davegut.samsungTvST, line 162
	if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.samsungTvST, line 163
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.samsungTvST, line 164
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.samsungTvST, line 165
	} else { // library marker davegut.samsungTvST, line 166
		def sendData = [ // library marker davegut.samsungTvST, line 167
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.samsungTvST, line 168
			parse: "distResp" // library marker davegut.samsungTvST, line 169
			] // library marker davegut.samsungTvST, line 170
		asyncGet(sendData, "statusParse") // library marker davegut.samsungTvST, line 171
	} // library marker davegut.samsungTvST, line 172
} // library marker davegut.samsungTvST, line 173

def setLevel(level) { setVolume(level) } // library marker davegut.samsungTvST, line 175

def setVolume(volume) { // library marker davegut.samsungTvST, line 177
	def cmdData = [ // library marker davegut.samsungTvST, line 178
		component: "main", // library marker davegut.samsungTvST, line 179
		capability: "audioVolume", // library marker davegut.samsungTvST, line 180
		command: "setVolume", // library marker davegut.samsungTvST, line 181
		arguments: [volume.toInteger()]] // library marker davegut.samsungTvST, line 182
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 183
} // library marker davegut.samsungTvST, line 184

def togglePictureMode() { // library marker davegut.samsungTvST, line 186
	//	requires state.pictureModes // library marker davegut.samsungTvST, line 187
	def pictureModes = state.pictureModes // library marker davegut.samsungTvST, line 188
	def totalModes = pictureModes.size() // library marker davegut.samsungTvST, line 189
	def currentMode = device.currentValue("pictureMode") // library marker davegut.samsungTvST, line 190
	def modeNo = pictureModes.indexOf(currentMode) // library marker davegut.samsungTvST, line 191
	def newModeNo = modeNo + 1 // library marker davegut.samsungTvST, line 192
	if (newModeNo == totalModes) { newModeNo = 0 } // library marker davegut.samsungTvST, line 193
	def newPictureMode = pictureModes[newModeNo] // library marker davegut.samsungTvST, line 194
	setPictureMode(newPictureMode) // library marker davegut.samsungTvST, line 195
} // library marker davegut.samsungTvST, line 196

def setPictureMode(pictureMode) { // library marker davegut.samsungTvST, line 198
	def cmdData = [ // library marker davegut.samsungTvST, line 199
		component: "main", // library marker davegut.samsungTvST, line 200
		capability: "custom.picturemode", // library marker davegut.samsungTvST, line 201
		command: "setPictureMode", // library marker davegut.samsungTvST, line 202
		arguments: [pictureMode]] // library marker davegut.samsungTvST, line 203
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 204
} // library marker davegut.samsungTvST, line 205

def toggleSoundMode() { // library marker davegut.samsungTvST, line 207
	def soundModes = state.soundModes // library marker davegut.samsungTvST, line 208
	def totalModes = soundModes.size() // library marker davegut.samsungTvST, line 209
	def currentMode = device.currentValue("soundMode") // library marker davegut.samsungTvST, line 210
	def modeNo = soundModes.indexOf(currentMode) // library marker davegut.samsungTvST, line 211
	def newModeNo = modeNo + 1 // library marker davegut.samsungTvST, line 212
	if (newModeNo == totalModes) { newModeNo = 0 } // library marker davegut.samsungTvST, line 213
	def soundMode = soundModes[newModeNo] // library marker davegut.samsungTvST, line 214
	setSoundMode(soundMode) // library marker davegut.samsungTvST, line 215
} // library marker davegut.samsungTvST, line 216

def setSoundMode(soundMode) {  // library marker davegut.samsungTvST, line 218
	def cmdData = [ // library marker davegut.samsungTvST, line 219
		component: "main", // library marker davegut.samsungTvST, line 220
		capability: "custom.soundmode", // library marker davegut.samsungTvST, line 221
		command: "setSoundMode", // library marker davegut.samsungTvST, line 222
		arguments: [soundMode]] // library marker davegut.samsungTvST, line 223
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 224
} // library marker davegut.samsungTvST, line 225

def toggleInputSource() { // library marker davegut.samsungTvST, line 227
	def inputSources = state.supportedInputs // library marker davegut.samsungTvST, line 228
	def totalSources = inputSources.size() // library marker davegut.samsungTvST, line 229
	def currentSource = device.currentValue("inputSource") // library marker davegut.samsungTvST, line 230
	def sourceNo = inputSources.indexOf(currentSource) // library marker davegut.samsungTvST, line 231
	def newSourceNo = sourceNo + 1 // library marker davegut.samsungTvST, line 232
	if (newSourceNo == totalSources) { newSourceNo = 0 } // library marker davegut.samsungTvST, line 233
	def inputSource = inputSources[newSourceNo] // library marker davegut.samsungTvST, line 234
	setInputSource(inputSource) // library marker davegut.samsungTvST, line 235
} // library marker davegut.samsungTvST, line 236

def setInputSource(inputSource) { // library marker davegut.samsungTvST, line 238
	def cmdData = [ // library marker davegut.samsungTvST, line 239
		component: "main", // library marker davegut.samsungTvST, line 240
		capability: "mediaInputSource", // library marker davegut.samsungTvST, line 241
		command: "setInputSource", // library marker davegut.samsungTvST, line 242
		arguments: [inputSource]] // library marker davegut.samsungTvST, line 243
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 244
} // library marker davegut.samsungTvST, line 245

def setTvChannel(newChannel) { // library marker davegut.samsungTvST, line 247
	def cmdData = [ // library marker davegut.samsungTvST, line 248
		component: "main", // library marker davegut.samsungTvST, line 249
		capability: "tvChannel", // library marker davegut.samsungTvST, line 250
		command: "setTvChannel", // library marker davegut.samsungTvST, line 251
		arguments: [newChannel]] // library marker davegut.samsungTvST, line 252
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 253
} // library marker davegut.samsungTvST, line 254


/* // library marker davegut.samsungTvST, line 257
def deviceCommand(cmdData) { // library marker davegut.samsungTvST, line 258
	logTrace("deviceCommand: $cmdData") // library marker davegut.samsungTvST, line 259
	def respData = [:] // library marker davegut.samsungTvST, line 260
	if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.samsungTvST, line 261
		respData << [status: "FAILED", data: "no stDeviceId"] // library marker davegut.samsungTvST, line 262
	} else { // library marker davegut.samsungTvST, line 263
		def sendData = [ // library marker davegut.samsungTvST, line 264
			path: "/devices/${stDeviceId.trim()}/commands", // library marker davegut.samsungTvST, line 265
			cmdData: cmdData // library marker davegut.samsungTvST, line 266
		] // library marker davegut.samsungTvST, line 267
		respData = syncPost(sendData) // library marker davegut.samsungTvST, line 268
	} // library marker davegut.samsungTvST, line 269
	if (respData.status == "OK") { // library marker davegut.samsungTvST, line 270
		if (respData.results[0].status == "COMPLETED") { // library marker davegut.samsungTvST, line 271
			if (cmdData.capability && cmdData.capability != "refresh") { // library marker davegut.samsungTvST, line 272
				refresh() // library marker davegut.samsungTvST, line 273
			} else { // library marker davegut.samsungTvST, line 274
				poll() // library marker davegut.samsungTvST, line 275
			} // library marker davegut.samsungTvST, line 276
		} // library marker davegut.samsungTvST, line 277
	}else { // library marker davegut.samsungTvST, line 278
		logWarn("deviceCommand: [status: ${respData.status}, data: ${respData}]") // library marker davegut.samsungTvST, line 279
		if (respData.toString().contains("Conflict")) { // library marker davegut.samsungTvST, line 280
			logWarn("<b>Conflict internal to SmartThings.  Device may be offline in SmartThings</b>") // library marker davegut.samsungTvST, line 281
		} // library marker davegut.samsungTvST, line 282
	} // library marker davegut.samsungTvST, line 283
} // library marker davegut.samsungTvST, line 284

def statusParse(mainData) { // library marker davegut.samsungTvST, line 286
	if (stTestData) { // library marker davegut.samsungTvST, line 287
		device.updateSetting("stTestData", [type:"bool", value: false]) // library marker davegut.samsungTvST, line 288
		log.warn mainData // library marker davegut.samsungTvST, line 289
	} // library marker davegut.samsungTvST, line 290
	def stData = [:] // library marker davegut.samsungTvST, line 291
	if (logEnable || traceLog) { // library marker davegut.samsungTvST, line 292
		def quickLog = [:] // library marker davegut.samsungTvST, line 293
		try { // library marker davegut.samsungTvST, line 294
			quickLog << [ // library marker davegut.samsungTvST, line 295
				switch: [device.currentValue("switch"), mainData.switch.switch.value], // library marker davegut.samsungTvST, line 296
				volume: [device.currentValue("volume"), mainData.audioVolume.volume.value.toInteger()], // library marker davegut.samsungTvST, line 297
				mute: [device.currentValue("mute"), mainData.audioMute.mute.value], // library marker davegut.samsungTvST, line 298
				input: [device.currentValue("inputSource"), mainData.mediaInputSource.inputSource.value], // library marker davegut.samsungTvST, line 299
				channel: [device.currentValue("tvChannel"), mainData.tvChannel.tvChannel.value.toString()], // library marker davegut.samsungTvST, line 300
				channelName: [device.currentValue("tvChannelName"), mainData.tvChannel.tvChannelName.value], // library marker davegut.samsungTvST, line 301
				pictureMode: [device.currentValue("pictureMode"), mainData["custom.picturemode"].pictureMode.value], // library marker davegut.samsungTvST, line 302
				soundMode: [device.currentValue("soundMode"), mainData["custom.soundmode"].soundMode.value], // library marker davegut.samsungTvST, line 303
				transportStatus: [device.currentValue("transportStatus"), mainData.mediaPlayback.playbackStatus.value]] // library marker davegut.samsungTvST, line 304
		} catch (err) { // library marker davegut.samsungTvST, line 305
			quickLog << [error: ${err}, data: mainData] // library marker davegut.samsungTvST, line 306
		} // library marker davegut.samsungTvST, line 307
		logDebug("statusParse: [quickLog: ${quickLog}]") // library marker davegut.samsungTvST, line 308
		logTrace("statusParse: [quickLog: ${quickLog}]") // library marker davegut.samsungTvST, line 309
	} // library marker davegut.samsungTvST, line 310

	if (device.currentValue("switch") == "on") { // library marker davegut.samsungTvST, line 312
		Integer volume = mainData.audioVolume.volume.value.toInteger() // library marker davegut.samsungTvST, line 313
		if (device.currentValue("volume") != volume) { // library marker davegut.samsungTvST, line 314
			sendEvent(name: "volume", value: volume) // library marker davegut.samsungTvST, line 315
			sendEvent(name: "level", value: volume) // library marker davegut.samsungTvST, line 316
			stData << [volume: volume] // library marker davegut.samsungTvST, line 317
		} // library marker davegut.samsungTvST, line 318

		String mute = mainData.audioMute.mute.value // library marker davegut.samsungTvST, line 320
		if (device.currentValue("mute") != mute) { // library marker davegut.samsungTvST, line 321
			sendEvent(name: "mute", value: mute) // library marker davegut.samsungTvST, line 322
			stData << [mute: mute] // library marker davegut.samsungTvST, line 323
		} // library marker davegut.samsungTvST, line 324

		String inputSource = mainData.mediaInputSource.inputSource.value // library marker davegut.samsungTvST, line 326
		if (device.currentValue("inputSource") != inputSource) { // library marker davegut.samsungTvST, line 327
			sendEvent(name: "inputSource", value: inputSource)		 // library marker davegut.samsungTvST, line 328
			stData << [inputSource: inputSource] // library marker davegut.samsungTvST, line 329
		} // library marker davegut.samsungTvST, line 330

		String tvChannel = mainData.tvChannel.tvChannel.value.toString() // library marker davegut.samsungTvST, line 332
		if (tvChannel == "" || tvChannel == null) { // library marker davegut.samsungTvST, line 333
			tvChannel = " " // library marker davegut.samsungTvST, line 334
		} // library marker davegut.samsungTvST, line 335
		String tvChannelName = mainData.tvChannel.tvChannelName.value // library marker davegut.samsungTvST, line 336
		if (tvChannelName == "") { // library marker davegut.samsungTvST, line 337
			tvChannelName = " " // library marker davegut.samsungTvST, line 338
		} // library marker davegut.samsungTvST, line 339
		if (device.currentValue("tvChannelName") != tvChannelName) { // library marker davegut.samsungTvST, line 340
			sendEvent(name: "tvChannel", value: tvChannel) // library marker davegut.samsungTvST, line 341
			sendEvent(name: "tvChannelName", value: tvChannelName) // library marker davegut.samsungTvST, line 342
			if (tvChannelName.contains(".")) { // library marker davegut.samsungTvST, line 343
				getAppData(tvChannelName) // library marker davegut.samsungTvST, line 344
			} else { // library marker davegut.samsungTvST, line 345
				sendEvent(name: "currentApp", value: " ") // library marker davegut.samsungTvST, line 346
			} // library marker davegut.samsungTvST, line 347
			stData << [tvChannel: tvChannel, tvChannelName: tvChannelName] // library marker davegut.samsungTvST, line 348
			if (getDataValue("frameTv") == "true" && !state.artModeWs) { // library marker davegut.samsungTvST, line 349
				String artMode = "off" // library marker davegut.samsungTvST, line 350
				if (tvChannelName == "art") { artMode = "on" } // library marker davegut.samsungTvST, line 351
				sendEvent(name: "artModeStatus", value: artMode) // library marker davegut.samsungTvST, line 352
			} // library marker davegut.samsungTvST, line 353
		} // library marker davegut.samsungTvST, line 354

		String trackDesc = inputSource // library marker davegut.samsungTvST, line 356
		if (tvChannelName != " ") { trackDesc = tvChannelName } // library marker davegut.samsungTvST, line 357
		if (device.currentValue("trackDescription") != trackDesc) { // library marker davegut.samsungTvST, line 358
			sendEvent(name: "trackDescription", value:trackDesc) // library marker davegut.samsungTvST, line 359
			stData << [trackDescription: trackDesc] // library marker davegut.samsungTvST, line 360
		} // library marker davegut.samsungTvST, line 361

		String pictureMode = mainData["custom.picturemode"].pictureMode.value // library marker davegut.samsungTvST, line 363
		if (device.currentValue("pictureMode") != pictureMode) { // library marker davegut.samsungTvST, line 364
			sendEvent(name: "pictureMode",value: pictureMode) // library marker davegut.samsungTvST, line 365
			stData << [pictureMode: pictureMode] // library marker davegut.samsungTvST, line 366
		} // library marker davegut.samsungTvST, line 367

		String soundMode = mainData["custom.soundmode"].soundMode.value // library marker davegut.samsungTvST, line 369
		if (device.currentValue("soundMode") != soundMode) { // library marker davegut.samsungTvST, line 370
			sendEvent(name: "soundMode",value: soundMode) // library marker davegut.samsungTvST, line 371
			stData << [soundMode: soundMode] // library marker davegut.samsungTvST, line 372
		} // library marker davegut.samsungTvST, line 373

		String transportStatus = mainData.mediaPlayback.playbackStatus.value // library marker davegut.samsungTvST, line 375
		if (transportStatus == null || transportStatus == "") { // library marker davegut.samsungTvST, line 376
			transportStatus = "n/a" // library marker davegut.samsungTvST, line 377
		} // library marker davegut.samsungTvST, line 378
		if (device.currentValue("transportStatus") != transportStatus) { // library marker davegut.samsungTvST, line 379
			sendEvent(name: "transportStatus", value: transportStatus) // library marker davegut.samsungTvST, line 380
			stData << [transportStatus: transportStatus] // library marker davegut.samsungTvST, line 381
		} // library marker davegut.samsungTvST, line 382
	} // library marker davegut.samsungTvST, line 383

	if (stData != [:]) { // library marker davegut.samsungTvST, line 385
		logInfo("statusParse: ${stData}") // library marker davegut.samsungTvST, line 386
	} // library marker davegut.samsungTvST, line 387
} // library marker davegut.samsungTvST, line 388

private asyncGet(sendData, passData = "none") { // library marker davegut.samsungTvST, line 390
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.samsungTvST, line 391
		logWarn("asyncGet: [status: ERROR, errorMsg: no stApiKey]") // library marker davegut.samsungTvST, line 392
	} else { // library marker davegut.samsungTvST, line 393
		logDebug("asyncGet: ${sendData}, ${passData}") // library marker davegut.samsungTvST, line 394
		def sendCmdParams = [ // library marker davegut.samsungTvST, line 395
			uri: "https://api.smartthings.com/v1", // library marker davegut.samsungTvST, line 396
			path: sendData.path, // library marker davegut.samsungTvST, line 397
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()]] // library marker davegut.samsungTvST, line 398
		try { // library marker davegut.samsungTvST, line 399
			asynchttpGet(sendData.parse, sendCmdParams, [reason: passData]) // library marker davegut.samsungTvST, line 400
		} catch (error) { // library marker davegut.samsungTvST, line 401
			logWarn("asyncGet: [status: FAILED, errorMsg: ${error}]") // library marker davegut.samsungTvST, line 402
		} // library marker davegut.samsungTvST, line 403
	} // library marker davegut.samsungTvST, line 404
} // library marker davegut.samsungTvST, line 405

private syncGet(path){ // library marker davegut.samsungTvST, line 407
	def respData = [:] // library marker davegut.samsungTvST, line 408
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.samsungTvST, line 409
		respData << [status: "FAILED", // library marker davegut.samsungTvST, line 410
					 errorMsg: "No stApiKey"] // library marker davegut.samsungTvST, line 411
	} else { // library marker davegut.samsungTvST, line 412
		logDebug("syncGet: ${sendData}") // library marker davegut.samsungTvST, line 413
		def sendCmdParams = [ // library marker davegut.samsungTvST, line 414
			uri: "https://api.smartthings.com/v1", // library marker davegut.samsungTvST, line 415
			path: path, // library marker davegut.samsungTvST, line 416
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()] // library marker davegut.samsungTvST, line 417
		] // library marker davegut.samsungTvST, line 418
		try { // library marker davegut.samsungTvST, line 419
			httpGet(sendCmdParams) {resp -> // library marker davegut.samsungTvST, line 420
				if (resp.status == 200 && resp.data != null) { // library marker davegut.samsungTvST, line 421
					respData << [status: "OK", results: resp.data] // library marker davegut.samsungTvST, line 422
				} else { // library marker davegut.samsungTvST, line 423
					respData << [status: "FAILED", // library marker davegut.samsungTvST, line 424
								 httpCode: resp.status, // library marker davegut.samsungTvST, line 425
								 errorMsg: resp.errorMessage] // library marker davegut.samsungTvST, line 426
				} // library marker davegut.samsungTvST, line 427
			} // library marker davegut.samsungTvST, line 428
		} catch (error) { // library marker davegut.samsungTvST, line 429
			respData << [status: "FAILED", // library marker davegut.samsungTvST, line 430
						 errorMsg: error] // library marker davegut.samsungTvST, line 431
		} // library marker davegut.samsungTvST, line 432
	} // library marker davegut.samsungTvST, line 433
	return respData // library marker davegut.samsungTvST, line 434
} // library marker davegut.samsungTvST, line 435

private syncPost(sendData){ // library marker davegut.samsungTvST, line 437
	def respData = [:] // library marker davegut.samsungTvST, line 438
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.samsungTvST, line 439
		respData << [status: "FAILED", // library marker davegut.samsungTvST, line 440
					 errorMsg: "No stApiKey"] // library marker davegut.samsungTvST, line 441
	} else { // library marker davegut.samsungTvST, line 442
		logDebug("syncPost: ${sendData}") // library marker davegut.samsungTvST, line 443
		def cmdBody = [commands: [sendData.cmdData]] // library marker davegut.samsungTvST, line 444
		def sendCmdParams = [ // library marker davegut.samsungTvST, line 445
			uri: "https://api.smartthings.com/v1", // library marker davegut.samsungTvST, line 446
			path: sendData.path, // library marker davegut.samsungTvST, line 447
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()], // library marker davegut.samsungTvST, line 448
			body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.samsungTvST, line 449
		] // library marker davegut.samsungTvST, line 450
		try { // library marker davegut.samsungTvST, line 451
			httpPost(sendCmdParams) {resp -> // library marker davegut.samsungTvST, line 452
				if (resp.status == 200 && resp.data != null) { // library marker davegut.samsungTvST, line 453
					respData << [status: "OK", results: resp.data.results] // library marker davegut.samsungTvST, line 454
				} else { // library marker davegut.samsungTvST, line 455
					respData << [status: "FAILED", // library marker davegut.samsungTvST, line 456
								 httpCode: resp.status, // library marker davegut.samsungTvST, line 457
								 errorMsg: resp.errorMessage] // library marker davegut.samsungTvST, line 458
				} // library marker davegut.samsungTvST, line 459
			} // library marker davegut.samsungTvST, line 460
		} catch (error) { // library marker davegut.samsungTvST, line 461
			respData << [status: "FAILED", // library marker davegut.samsungTvST, line 462
						 errorMsg: error] // library marker davegut.samsungTvST, line 463
		} // library marker davegut.samsungTvST, line 464
	} // library marker davegut.samsungTvST, line 465
	return respData // library marker davegut.samsungTvST, line 466
} // library marker davegut.samsungTvST, line 467

def distResp(resp, data) { // library marker davegut.samsungTvST, line 469
	def respLog = [:] // library marker davegut.samsungTvST, line 470
	if (resp.status == 200) { // library marker davegut.samsungTvST, line 471
		try { // library marker davegut.samsungTvST, line 472
			def respData = new JsonSlurper().parseText(resp.data) // library marker davegut.samsungTvST, line 473
			if (data.reason == "deviceSetup") { // library marker davegut.samsungTvST, line 474
				deviceSetupParse(respData.components.main) // library marker davegut.samsungTvST, line 475
				runIn(1, statusParse, [data: respData.components.main]) // library marker davegut.samsungTvST, line 476
			} else { // library marker davegut.samsungTvST, line 477
				statusParse(respData.components.main) // library marker davegut.samsungTvST, line 478
			} // library marker davegut.samsungTvST, line 479
		} catch (err) { // library marker davegut.samsungTvST, line 480
			respLog << [status: "ERROR", // library marker davegut.samsungTvST, line 481
						errorMsg: err, // library marker davegut.samsungTvST, line 482
						respData: resp.data] // library marker davegut.samsungTvST, line 483
		} // library marker davegut.samsungTvST, line 484
	} else { // library marker davegut.samsungTvST, line 485
		respLog << [status: "ERROR", // library marker davegut.samsungTvST, line 486
					httpCode: resp.status, // library marker davegut.samsungTvST, line 487
					errorMsg: resp.errorMessage] // library marker davegut.samsungTvST, line 488
	} // library marker davegut.samsungTvST, line 489
	if (respLog != [:]) { // library marker davegut.samsungTvST, line 490
		logWarn("distResp: ${respLog}") // library marker davegut.samsungTvST, line 491
	} // library marker davegut.samsungTvST, line 492
} // library marker davegut.samsungTvST, line 493
*/ // library marker davegut.samsungTvST, line 494

// ~~~~~ end include (1368) davegut.samsungTvST ~~~~~

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
