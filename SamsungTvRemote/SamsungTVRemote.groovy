/*	===== HUBITAT INTEGRATION VERSION =====================================================
Hubitat - Samsung TV Remote Driver
		Copyright 2022 Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2024 Version 2.3.9 ====================================================================
a.  Created preset functions (create, execute, trigger). Function work with and without
	SmartThings.  SEE DOCUMENTATION.
b.	Added buttons to support preset functions in dashboards.
c.	Added app codes to built-in app search list.
d.  Created methods to support adding running app automatically to state.appData
	if the SmartThings interface is enabled.
===========================================================================================*/
def driverVer() { return version() }
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
	definition (name: "Samsung TV Remote",
				namespace: "davegut",
				author: "David Gutheinz",
				singleThreaded: true,
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
			List modeOptions = ["ART_MODE", "Ambient", "none"]
			if (getDataValue("frameTv") == "false") {
				modeOptions = ["Ambient", "none"]
			}
			input ("tvPwrOnMode", "enum", title: "TV Startup Display", 
				   options: modeOptions, defaultValue: "none")
			input ("logEnable", "bool",  
				   title: "Enable debug logging for 30 minutes", defaultValue: false)
			input ("infoLog", "bool", 
				   title: "Enable information logging " + helpLogo(),
				   defaultValue: true)
			stPreferences()
		}
		input ("pollInterval","enum", title: "Power Polling Interval (seconds)",
			   options: ["off", "10", "20", "30", "60"], defaultValue: "60")
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
//	sendEvent(name: "numberOfButtons", value: "60")
	runIn(1, updated)
}

def updated() {
	sendEvent(name: "numberOfButtons", value: "60")
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
		updStatus << [logEnable: logEnable, infoLog: infoLog]
		updStatus << [setOnPollInterval: setOnPollInterval()]
		sendEvent(name: "numberOfButtons", value: 60)
		sendEvent(name: "wsStatus", value: "closed")
		def action = configure()
		if (!state.appData) { state.appData == [:] }
		updStatus << [updApps: updateAppCodes()]
	}
	logInfo("updated: ${updStatus}")
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
	} else {
		respData << tvData
	}
	runIn(1, stUpdate)
	logInfo("configure: ${respData}")
	return respData
}

//	===== Polling/Refresh Capability =====
def onPoll() {
	def sendCmdParams = [
		uri: "http://${deviceIp}:8001/api/v2/",
		timeout: 6
	]
	asynchttpGet("onPollParse", sendCmdParams)
	if (getDataValue("driverVersion") != driverVer()) {
		logInfo("Auto Configuring changes to this TV.")
		updateDriver()
		pauseExecution(3000)
	}
}

def updateDriver() {
}

def onPollParse(resp, data) {
	def powerState
	if (resp.status == 200) {
		powerState = new JsonSlurper().parseText(resp.data).device.PowerState
	} else {
		powerState = "NC"
	}
	def onOff = "off"
	if (powerState == "on") { onOff = "on" }
	Map logData = [method: "onPollParse", httpStatus: resp.status, 
				   powerState: powerState, onOff: onOff]
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff)
		logData << [switch: onOff]
		if (onOff == "on") {
			runIn(5, setPowerOnMode)
		} else {
			setPowerOffMode()
		}
	}
	logDebug(logData)
}

//	===== Capability Switch =====
def on() {
	logInfo("on: [frameTv: ${getDataValue("frameTv")}]")
	if (device.currentValue("wsStatus") == "open") {
		sendKey("POWER", "Press")
		pauseExecution(3000)
		sendKey("POWER", "Release")
	}
	def wolMac = getDataValue("alternateWolMac")
	def cmd = "FFFFFFFFFFFF$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac"
	wol = new hubitat.device.HubAction(
		cmd,
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "255.255.255.255:7",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING])
	sendHubCommand(wol)
	runIn(1, onPoll)
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
	runIn(5, refresh)
}

def off() {
	logInfo("off: [frameTv: ${getDataValue("frameTv")}]")
	sendKey("POWER", "Press")
	pauseExecution(3000)
	sendKey("POWER", "Release")
	runIn(1, onPoll)
}

def setPowerOffMode() {
	logInfo("setPowerOfMode")
	sendEvent(name: "appId", value: " ")
	sendEvent(name: "appName", value: " ")
	sendEvent(name: "tvChannel", value: " ")
	sendEvent(name: "tvChannelName", value: " ")
	runIn(5, refresh)
}

def setVariable(valueString) {
	sendEvent(name: "variable", value: valueString)
}

def refresh() {
	if (connectST) {
		deviceRefresh()
	}
	runIn(4, updateTitle)
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
		case 2 : mute(); break			// toggles mute/unmute
		case 3 : numericKeyPad(); break
		case 4 : Return(); break		//	Goes back one level on apps. I.e., if playing, goes to
										//	app main screen
		case 6 : artMode(); break
		case 7 : ambientMode(); break
		case 8 : arrowLeft(); break
		case 9 : arrowRight(); break
		case 10: arrowUp(); break
		case 11: arrowDown(); break
		case 12: enter(); break			//	Executes selected OSD item (both apps and tv menus.
		case 13: exit(); break			//	Exits last selected item (if playing in app, will exit
										//	and return to app main screen.  If on main screen, exits app.
		case 14: home(); break			//	toggles on/off the TV's smart control page.
		case 18: channelUp(); break
		case 19: channelDown(); break
		case 20: guide(); break
		case 21: volumeUp(); break
		case 22: volumeDown(); break
		//	===== Direct Access Functions
		case 23: menu(); break			//	Calls upd OSD setting menu for TV.
		case 26: channelList(); break
		case 27: play(); break			//	Player control (playing app media that is not streaming
		case 28: pause(); break			//	(i.e., a recording in app).
		case 29: stop(); break			//	Exits the playing recording, returns to menu
		//	===== Other Commands =====
		case 34: previousChannel(); break	//	aka back
		case 35: sourceToggle(); break		//	Changed from hdmi().  Goes to next active source.
		case 36: fastBack(); break		//	player control
		case 37: fastForward(); break	//	player control
		//	===== Application Interface =====
		case 42: toggleSoundMode(); break		//	ST function
		case 43: togglePictureMode(); break		//	ST function
		case 44: 
			// Allows opening an app by name.  Enter using dashboard tile variable
			//	which creates the attribute variable.
			def variable = device.currentValue("variable")			
			if (variable == null || variable == " ") {
				logWarn("{button44: error: null variable, action: use setVariable to enter the variable]")
			} else {
				appOpenByName(variable)
			}
			sendEvent(name: "variable", value: " ")
			break
		
		case 45:
			//	TV Channel Preset Function.  Allows adding TV channels without the
			//	SmartThing interface.
			def variable = device.currentValue("variable")			
			if (variable == null || variable == " ") {
				logWarn("{button45: error: null variable, action: use setVariable to enter the variable]")
				logWarn("{button45: variable must be in format PresetNo, TVCHANNEL, TVCHANNELTITLE]]")
			} else {
				def arr = variable.split(",")
				if (arr.size() == 3) {
					try {
						arr[0].trim().toInteger()
						arr[1].trim().toInteger()
						presetCreateTV(arr[0].trim(), arr[1].trim(), arr[2].trim())
					} catch (err) {
						logWarn("{button45: [variable: ${variable}, error: must be in format PresetNo, TVCHANNEL, TVCHANNELTITLE]]")
					}
				} else {
					logWarn("{button45: [variable: ${variable}, error: must be in format PresetNo, TVCHANNEL, TVCHANNELTITLE]]")
				}
			}
			sendEvent(name: "variable", value: " ")
			break
		case 46:
			//	TV Channel set.  Must first enter variable.
			def variable = device.currentValue("variable")			
			if (variable == null || variable == " ") {
				logWarn("{button46: error: null variable, action: use setVariable to enter the variable]")
			} else {
				channelSet(variable)
			}
			sendEvent(name: "variable", value: " ")
			break
		//	Trigger function.  Once pressed, the preset buttons become ADD for 5 seconds.
		case 50: presetUpdateNext(); break
		case 51: presetExecute("1"); break
		case 52: presetExecute("2"); break
		case 53: presetExecute("3"); break
		case 54: presetExecute("4"); break
		case 55: presetExecute("5"); break
		case 56: presetExecute("6"); break
		case 57: presetExecute("7"); break
		case 58: presetExecute("8"); break
		case 59: presetExecute("9"); break
		case 60: presetExecute("10"); break
		default:
			logDebug("push: Invalid Button Number!")
			break
	}
}

//	===== Libraries =====
//#include davegut.samsungTvTEST








// ~~~~~ start include (89) davegut.samsungTvWebsocket ~~~~~
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
if (getDataValue("frameTv") == "true") { // library marker davegut.samsungTvWebsocket, line 16
	command "artMode" // library marker davegut.samsungTvWebsocket, line 17
	attribute "artModeStatus", "string" // library marker davegut.samsungTvWebsocket, line 18
} // library marker davegut.samsungTvWebsocket, line 19
command "ambientMode" // library marker davegut.samsungTvWebsocket, line 20
//	Remote Control Keys (samsungTV-Keys) // library marker davegut.samsungTvWebsocket, line 21
command "pause" // library marker davegut.samsungTvWebsocket, line 22
command "play" // library marker davegut.samsungTvWebsocket, line 23
command "stop" // library marker davegut.samsungTvWebsocket, line 24
command "sendKey", ["string"] // library marker davegut.samsungTvWebsocket, line 25
//	Cursor and Entry Control // library marker davegut.samsungTvWebsocket, line 26
command "arrowLeft" // library marker davegut.samsungTvWebsocket, line 27
command "arrowRight" // library marker davegut.samsungTvWebsocket, line 28
command "arrowUp" // library marker davegut.samsungTvWebsocket, line 29
command "arrowDown" // library marker davegut.samsungTvWebsocket, line 30
command "enter" // library marker davegut.samsungTvWebsocket, line 31
command "numericKeyPad" // library marker davegut.samsungTvWebsocket, line 32
//	Menu Access // library marker davegut.samsungTvWebsocket, line 33
command "home" // library marker davegut.samsungTvWebsocket, line 34
command "menu" // library marker davegut.samsungTvWebsocket, line 35
command "guide" // library marker davegut.samsungTvWebsocket, line 36
//command "info"	//  enter // library marker davegut.samsungTvWebsocket, line 37
//	Source Commands // library marker davegut.samsungTvWebsocket, line 38
command "sourceSetOSD" // library marker davegut.samsungTvWebsocket, line 39
command "sourceToggle" // library marker davegut.samsungTvWebsocket, line 40
//command "hdmi" // library marker davegut.samsungTvWebsocket, line 41
//	TV Channel // library marker davegut.samsungTvWebsocket, line 42
command "channelList" // library marker davegut.samsungTvWebsocket, line 43
command "channelUp" // library marker davegut.samsungTvWebsocket, line 44
command "channelDown" // library marker davegut.samsungTvWebsocket, line 45
command "channelSet", ["string"] // library marker davegut.samsungTvWebsocket, line 46
command "previousChannel" // library marker davegut.samsungTvWebsocket, line 47
//	Playing Navigation Commands // library marker davegut.samsungTvWebsocket, line 48
command "exit" // library marker davegut.samsungTvWebsocket, line 49
command "Return" // library marker davegut.samsungTvWebsocket, line 50
command "fastBack" // library marker davegut.samsungTvWebsocket, line 51
command "fastForward" // library marker davegut.samsungTvWebsocket, line 52

//	== ART/Ambient Mode // library marker davegut.samsungTvWebsocket, line 54
def artMode() { // library marker davegut.samsungTvWebsocket, line 55
	def artModeStatus = device.currentValue("artModeStatus") // library marker davegut.samsungTvWebsocket, line 56
	def logData = [artModeStatus: artModeStatus, artModeWs: state.artModeWs] // library marker davegut.samsungTvWebsocket, line 57
	if (getDataValue("frameTv") != "true") { // library marker davegut.samsungTvWebsocket, line 58
		logData << [status: "Not a Frame TV"] // library marker davegut.samsungTvWebsocket, line 59
	} else if (artModeStatus == "on") { // library marker davegut.samsungTvWebsocket, line 60
		logData << [status: "artMode already set"] // library marker davegut.samsungTvWebsocket, line 61
	} else { // library marker davegut.samsungTvWebsocket, line 62
		if (state.artModeWs) { // library marker davegut.samsungTvWebsocket, line 63
			def data = [value:"on", // library marker davegut.samsungTvWebsocket, line 64
						request:"set_artmode_status", // library marker davegut.samsungTvWebsocket, line 65
						id: "${getDataValue("uuid")}"] // library marker davegut.samsungTvWebsocket, line 66
			data = JsonOutput.toJson(data) // library marker davegut.samsungTvWebsocket, line 67
			artModeCmd(data) // library marker davegut.samsungTvWebsocket, line 68
			logData << [status: "Sending artMode WS Command"] // library marker davegut.samsungTvWebsocket, line 69
		} else { // library marker davegut.samsungTvWebsocket, line 70
			sendKey("POWER") // library marker davegut.samsungTvWebsocket, line 71
			logData << [status: "Sending Power WS Command"] // library marker davegut.samsungTvWebsocket, line 72
			if (artModeStatus == "none") { // library marker davegut.samsungTvWebsocket, line 73
				logData << [NOTE: "SENT BLIND. Enable SmartThings interface!"] // library marker davegut.samsungTvWebsocket, line 74
			} // library marker davegut.samsungTvWebsocket, line 75
		} // library marker davegut.samsungTvWebsocket, line 76
		runIn(10, getArtModeStatus) // library marker davegut.samsungTvWebsocket, line 77
	} // library marker davegut.samsungTvWebsocket, line 78
	logInfo("artMode: ${logData}") // library marker davegut.samsungTvWebsocket, line 79
} // library marker davegut.samsungTvWebsocket, line 80

def getArtModeStatus() { // library marker davegut.samsungTvWebsocket, line 82
	if (getDataValue("frameTv") == "true") { // library marker davegut.samsungTvWebsocket, line 83
		if (state.artModeWs) { // library marker davegut.samsungTvWebsocket, line 84
			def data = [request:"get_artmode_status", // library marker davegut.samsungTvWebsocket, line 85
						id: "${getDataValue("uuid")}"] // library marker davegut.samsungTvWebsocket, line 86
			data = JsonOutput.toJson(data) // library marker davegut.samsungTvWebsocket, line 87
			artModeCmd(data) // library marker davegut.samsungTvWebsocket, line 88
		} else { // library marker davegut.samsungTvWebsocket, line 89
			refresh() // library marker davegut.samsungTvWebsocket, line 90
		} // library marker davegut.samsungTvWebsocket, line 91
	} // library marker davegut.samsungTvWebsocket, line 92
} // library marker davegut.samsungTvWebsocket, line 93

def artModeCmd(data) { // library marker davegut.samsungTvWebsocket, line 95
	def cmdData = [method:"ms.channel.emit", // library marker davegut.samsungTvWebsocket, line 96
				   params:[data:"${data}", // library marker davegut.samsungTvWebsocket, line 97
						   to:"host", // library marker davegut.samsungTvWebsocket, line 98
						   event:"art_app_request"]] // library marker davegut.samsungTvWebsocket, line 99
	cmdData = JsonOutput.toJson(cmdData) // library marker davegut.samsungTvWebsocket, line 100
	sendMessage("frameArt", cmdData) // library marker davegut.samsungTvWebsocket, line 101
} // library marker davegut.samsungTvWebsocket, line 102

def ambientMode() { // library marker davegut.samsungTvWebsocket, line 104
	sendKey("AMBIENT") // library marker davegut.samsungTvWebsocket, line 105
	runIn(10, refresh) // library marker davegut.samsungTvWebsocket, line 106
} // library marker davegut.samsungTvWebsocket, line 107

//	== Remote Commands // library marker davegut.samsungTvWebsocket, line 109
def mute() { sendKeyThenRefresh("MUTE") } // library marker davegut.samsungTvWebsocket, line 110

def unmute() { mute() } // library marker davegut.samsungTvWebsocket, line 112

def volumeUp() { sendKeyThenRefresh("VOLUP") } // library marker davegut.samsungTvWebsocket, line 114

def volumeDown() { sendKeyThenRefresh("VOLDOWN") } // library marker davegut.samsungTvWebsocket, line 116

def play() { sendKeyThenRefresh("PLAY") } // library marker davegut.samsungTvWebsocket, line 118

def pause() { sendKeyThenRefresh("PAUSE") } // library marker davegut.samsungTvWebsocket, line 120

def stop() { sendKeyThenRefresh("STOP") } // library marker davegut.samsungTvWebsocket, line 122

def exit() { sendKeyThenRefresh("EXIT") } // library marker davegut.samsungTvWebsocket, line 124

def Return() { sendKeyThenRefresh("RETURN") } // library marker davegut.samsungTvWebsocket, line 126

def fastBack() { // library marker davegut.samsungTvWebsocket, line 128
	sendKey("LEFT", "Press") // library marker davegut.samsungTvWebsocket, line 129
	pauseExecution(1000) // library marker davegut.samsungTvWebsocket, line 130
	sendKey("LEFT", "Release") // library marker davegut.samsungTvWebsocket, line 131
} // library marker davegut.samsungTvWebsocket, line 132

def fastForward() { // library marker davegut.samsungTvWebsocket, line 134
	sendKey("RIGHT", "Press") // library marker davegut.samsungTvWebsocket, line 135
	pauseExecution(1000) // library marker davegut.samsungTvWebsocket, line 136
	sendKey("RIGHT", "Release") // library marker davegut.samsungTvWebsocket, line 137
} // library marker davegut.samsungTvWebsocket, line 138

def arrowLeft() { sendKey("LEFT") } // library marker davegut.samsungTvWebsocket, line 140

def arrowRight() { sendKey("RIGHT") } // library marker davegut.samsungTvWebsocket, line 142

def arrowUp() { sendKey("UP") } // library marker davegut.samsungTvWebsocket, line 144

def arrowDown() { sendKey("DOWN") } // library marker davegut.samsungTvWebsocket, line 146

def enter() { sendKeyThenRefresh("ENTER") } // library marker davegut.samsungTvWebsocket, line 148

def numericKeyPad() { sendKey("MORE") } // library marker davegut.samsungTvWebsocket, line 150

def home() { sendKey("HOME") } // library marker davegut.samsungTvWebsocket, line 152

def menu() { sendKey("MENU") } // library marker davegut.samsungTvWebsocket, line 154

def guide() { sendKey("GUIDE") } // library marker davegut.samsungTvWebsocket, line 156

def info() { enter() } // library marker davegut.samsungTvWebsocket, line 158

def source() { sourceSetOSD() } // library marker davegut.samsungTvWebsocket, line 160
def sourceSetOSD() { sendKey("SOURCE") } // library marker davegut.samsungTvWebsocket, line 161

def hdmi() { sourceToggle() } // library marker davegut.samsungTvWebsocket, line 163
def sourceToggle() { sendKeyThenRefresh("HDMI") } // library marker davegut.samsungTvWebsocket, line 164

def channelList() { sendKey("CH_LIST") } // library marker davegut.samsungTvWebsocket, line 166

def channelUp() { sendKeyThenRefresh("CHUP") } // library marker davegut.samsungTvWebsocket, line 168
def nextTrack() { channelUp() } // library marker davegut.samsungTvWebsocket, line 169

def channelDown() { sendKeyThenRefresh("CHDOWN") } // library marker davegut.samsungTvWebsocket, line 171
def previousTrack() { channelDown() } // library marker davegut.samsungTvWebsocket, line 172

//	Uses ST interface if available. // library marker davegut.samsungTvWebsocket, line 174
def channelSet(channel) { // library marker davegut.samsungTvWebsocket, line 175
	if (connectST) { // library marker davegut.samsungTvWebsocket, line 176
		setTvChannel(channel) // library marker davegut.samsungTvWebsocket, line 177
	} else { // library marker davegut.samsungTvWebsocket, line 178
		for (int i = 0; i < channel.length(); i++) { // library marker davegut.samsungTvWebsocket, line 179
			sendKey(channel[i]) // library marker davegut.samsungTvWebsocket, line 180
		} // library marker davegut.samsungTvWebsocket, line 181
		enter() // library marker davegut.samsungTvWebsocket, line 182
		sendEvent(name: "tvChannel", value: channel) // library marker davegut.samsungTvWebsocket, line 183
	} // library marker davegut.samsungTvWebsocket, line 184
} // library marker davegut.samsungTvWebsocket, line 185

def previousChannel() { sendKeyThenRefresh("PRECH") } // library marker davegut.samsungTvWebsocket, line 187

def showMessage() { logWarn("showMessage: not implemented") } // library marker davegut.samsungTvWebsocket, line 189

//	== WebSocket Communications / Parse // library marker davegut.samsungTvWebsocket, line 191
def sendKeyThenRefresh(key) { // library marker davegut.samsungTvWebsocket, line 192
	sendKey(key) // library marker davegut.samsungTvWebsocket, line 193
	if (connectST) { runIn(3, deviceRefresh) } // library marker davegut.samsungTvWebsocket, line 194
} // library marker davegut.samsungTvWebsocket, line 195

def sendKey(key, cmd = "Click") { // library marker davegut.samsungTvWebsocket, line 197
	key = "KEY_${key.toUpperCase()}" // library marker davegut.samsungTvWebsocket, line 198
	def data = [method:"ms.remote.control", // library marker davegut.samsungTvWebsocket, line 199
				params:[Cmd:"${cmd}", // library marker davegut.samsungTvWebsocket, line 200
						DataOfCmd:"${key}", // library marker davegut.samsungTvWebsocket, line 201
						TypeOfRemote:"SendRemoteKey"]] // library marker davegut.samsungTvWebsocket, line 202
	sendMessage("remote", JsonOutput.toJson(data).toString() ) // library marker davegut.samsungTvWebsocket, line 203
} // library marker davegut.samsungTvWebsocket, line 204

def sendMessage(funct, data) { // library marker davegut.samsungTvWebsocket, line 206
	def wsStat = device.currentValue("wsStatus") // library marker davegut.samsungTvWebsocket, line 207
	logDebug("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${state.currentFunction}") // library marker davegut.samsungTvWebsocket, line 208
	if (wsStat != "open" || state.currentFunction != funct) { // library marker davegut.samsungTvWebsocket, line 209
		connect(funct) // library marker davegut.samsungTvWebsocket, line 210
		pauseExecution(2000) // library marker davegut.samsungTvWebsocket, line 211
	} // library marker davegut.samsungTvWebsocket, line 212
	interfaces.webSocket.sendMessage(data) // library marker davegut.samsungTvWebsocket, line 213
	runIn(600, close) // library marker davegut.samsungTvWebsocket, line 214
} // library marker davegut.samsungTvWebsocket, line 215

def webSocketOpen() { connect("remote") } // library marker davegut.samsungTvWebsocket, line 217
def webSocketClose() { close() } // library marker davegut.samsungTvWebsocket, line 218

def connect(funct) { // library marker davegut.samsungTvWebsocket, line 220
	logDebug("connect: function = ${funct}") // library marker davegut.samsungTvWebsocket, line 221
	def url // library marker davegut.samsungTvWebsocket, line 222
	def name = "SHViaXRhdCBTYW1zdW5nIFJlbW90ZQ==" // library marker davegut.samsungTvWebsocket, line 223
	if (getDataValue("tokenSupport") == "true") { // library marker davegut.samsungTvWebsocket, line 224
		if (funct == "remote") { // library marker davegut.samsungTvWebsocket, line 225
			url = "wss://${deviceIp}:8002/api/v2/channels/samsung.remote.control?name=${name}&token=${state.token}" // library marker davegut.samsungTvWebsocket, line 226
		} else if (funct == "frameArt") { // library marker davegut.samsungTvWebsocket, line 227
			url = "wss://${deviceIp}:8002/api/v2/channels/com.samsung.art-app?name=${name}&token=${state.token}" // library marker davegut.samsungTvWebsocket, line 228
		} else { // library marker davegut.samsungTvWebsocket, line 229
			logWarn("sendMessage: Invalid Function = ${funct}, tokenSupport = true") // library marker davegut.samsungTvWebsocket, line 230
		} // library marker davegut.samsungTvWebsocket, line 231
	} else { // library marker davegut.samsungTvWebsocket, line 232
		if (funct == "remote") { // library marker davegut.samsungTvWebsocket, line 233
			url = "ws://${deviceIp}:8001/api/v2/channels/samsung.remote.control?name=${name}" // library marker davegut.samsungTvWebsocket, line 234
		} else if (funct == "frameArt") { // library marker davegut.samsungTvWebsocket, line 235
			url = "ws://${deviceIp}:8001/api/v2/channels/com.samsung.art-app?name=${name}" // library marker davegut.samsungTvWebsocket, line 236
		} else { // library marker davegut.samsungTvWebsocket, line 237
			logWarn("sendMessage: Invalid Function = ${funct}, tokenSupport = false") // library marker davegut.samsungTvWebsocket, line 238
		} // library marker davegut.samsungTvWebsocket, line 239
	} // library marker davegut.samsungTvWebsocket, line 240
	state.currentFunction = funct // library marker davegut.samsungTvWebsocket, line 241
	interfaces.webSocket.connect(url, ignoreSSLIssues: true) // library marker davegut.samsungTvWebsocket, line 242
} // library marker davegut.samsungTvWebsocket, line 243

def close() { // library marker davegut.samsungTvWebsocket, line 245
	logDebug("close") // library marker davegut.samsungTvWebsocket, line 246
	interfaces.webSocket.close() // library marker davegut.samsungTvWebsocket, line 247
	sendEvent(name: "wsStatus", value: "closed") // library marker davegut.samsungTvWebsocket, line 248
} // library marker davegut.samsungTvWebsocket, line 249

def webSocketStatus(message) { // library marker davegut.samsungTvWebsocket, line 251
	def status // library marker davegut.samsungTvWebsocket, line 252
	if (message == "status: open") { // library marker davegut.samsungTvWebsocket, line 253
		status = "open" // library marker davegut.samsungTvWebsocket, line 254
	} else if (message == "status: closing") { // library marker davegut.samsungTvWebsocket, line 255
		status = "closed" // library marker davegut.samsungTvWebsocket, line 256
		state.currentFunction = "close" // library marker davegut.samsungTvWebsocket, line 257
	} else if (message.substring(0,7) == "failure") { // library marker davegut.samsungTvWebsocket, line 258
		status = "closed-failure" // library marker davegut.samsungTvWebsocket, line 259
		state.currentFunction = "close" // library marker davegut.samsungTvWebsocket, line 260
		close() // library marker davegut.samsungTvWebsocket, line 261
	} // library marker davegut.samsungTvWebsocket, line 262
	sendEvent(name: "wsStatus", value: status) // library marker davegut.samsungTvWebsocket, line 263
	logDebug("webSocketStatus: [status: ${status}, message: ${message}]") // library marker davegut.samsungTvWebsocket, line 264
} // library marker davegut.samsungTvWebsocket, line 265

def parse(resp) { // library marker davegut.samsungTvWebsocket, line 267
	def logData = [:] // library marker davegut.samsungTvWebsocket, line 268
	try { // library marker davegut.samsungTvWebsocket, line 269
		resp = parseJson(resp) // library marker davegut.samsungTvWebsocket, line 270
		def event = resp.event // library marker davegut.samsungTvWebsocket, line 271
		logData << [EVENT: event] // library marker davegut.samsungTvWebsocket, line 272
		switch(event) { // library marker davegut.samsungTvWebsocket, line 273
			case "ms.channel.connect": // library marker davegut.samsungTvWebsocket, line 274
				def newToken = resp.data.token // library marker davegut.samsungTvWebsocket, line 275
				if (newToken != null && newToken != state.token) { // library marker davegut.samsungTvWebsocket, line 276
					state.token = newToken // library marker davegut.samsungTvWebsocket, line 277
					logData << [TOKEN: "updated"] // library marker davegut.samsungTvWebsocket, line 278
				} else { // library marker davegut.samsungTvWebsocket, line 279
					logData << [TOKEN: "noChange"] // library marker davegut.samsungTvWebsocket, line 280
				} // library marker davegut.samsungTvWebsocket, line 281
				break // library marker davegut.samsungTvWebsocket, line 282
			case "d2d_service_message": // library marker davegut.samsungTvWebsocket, line 283
				def data = parseJson(resp.data) // library marker davegut.samsungTvWebsocket, line 284
				if (data.event == "artmode_status" || // library marker davegut.samsungTvWebsocket, line 285
					data.event == "art_mode_changed") { // library marker davegut.samsungTvWebsocket, line 286
					def status = data.value // library marker davegut.samsungTvWebsocket, line 287
					if (status == null) { status = data.status } // library marker davegut.samsungTvWebsocket, line 288
					sendEvent(name: "artModeStatus", value: status) // library marker davegut.samsungTvWebsocket, line 289
					logData << [artModeStatus: status] // library marker davegut.samsungTvWebsocket, line 290
					state.artModeWs = true // library marker davegut.samsungTvWebsocket, line 291
				} // library marker davegut.samsungTvWebsocket, line 292
				break // library marker davegut.samsungTvWebsocket, line 293
			case "ms.error": // library marker davegut.samsungTvWebsocket, line 294
			case "ms.channel.ready": // library marker davegut.samsungTvWebsocket, line 295
			case "ms.channel.clientConnect": // library marker davegut.samsungTvWebsocket, line 296
			case "ms.channel.clientDisconnect": // library marker davegut.samsungTvWebsocket, line 297
			case "ms.remote.touchEnable": // library marker davegut.samsungTvWebsocket, line 298
			case "ms.remote.touchDisable": // library marker davegut.samsungTvWebsocket, line 299
				break // library marker davegut.samsungTvWebsocket, line 300
			default: // library marker davegut.samsungTvWebsocket, line 301
				logData << [STATUS: "Not Parsed", DATA: resp.data] // library marker davegut.samsungTvWebsocket, line 302
				break // library marker davegut.samsungTvWebsocket, line 303
		} // library marker davegut.samsungTvWebsocket, line 304
		logDebug("parse: ${logData}") // library marker davegut.samsungTvWebsocket, line 305
	} catch (e) { // library marker davegut.samsungTvWebsocket, line 306
		logData << [STATUS: "unhandled", ERROR: e] // library marker davegut.samsungTvWebsocket, line 307
		logWarn("parse: ${logData}") // library marker davegut.samsungTvWebsocket, line 308
	} // library marker davegut.samsungTvWebsocket, line 309
} // library marker davegut.samsungTvWebsocket, line 310

// ~~~~~ end include (89) davegut.samsungTvWebsocket ~~~~~

// ~~~~~ start include (88) davegut.samsungTvApps ~~~~~
library ( // library marker davegut.samsungTvApps, line 1
	name: "samsungTvApps", // library marker davegut.samsungTvApps, line 2
	namespace: "davegut", // library marker davegut.samsungTvApps, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTvApps, line 4
	description: "Samsung TV Applications", // library marker davegut.samsungTvApps, line 5
	category: "utilities", // library marker davegut.samsungTvApps, line 6
	documentationLink: "" // library marker davegut.samsungTvApps, line 7
) // library marker davegut.samsungTvApps, line 8

import groovy.json.JsonSlurper // library marker davegut.samsungTvApps, line 10

command "appOpenByName", ["string"] // library marker davegut.samsungTvApps, line 12
command "appClose" // library marker davegut.samsungTvApps, line 13
attribute "nowPlaying", "string" // library marker davegut.samsungTvApps, line 14
attribute "appName", "string" // library marker davegut.samsungTvApps, line 15
attribute "appId", "string" // library marker davegut.samsungTvApps, line 16
attribute "tvChannel", "string" // library marker davegut.samsungTvApps, line 17
attribute "tvChannelName", "string" // library marker davegut.samsungTvApps, line 18

def tvAppsPreferences() { // library marker davegut.samsungTvApps, line 20
	input ("findAppCodes", "enum", title: "Scan for App Codes (takes 10 minutes)",  // library marker davegut.samsungTvApps, line 21
		   options: ["off", "startOver", "find"], defaultValue: "off") // library marker davegut.samsungTvApps, line 22
} // library marker davegut.samsungTvApps, line 23

def appOpenByName(appName) { // library marker davegut.samsungTvApps, line 25
	def logData = [method: "appOpenByName"] // library marker davegut.samsungTvApps, line 26
	def thisApp = state.appData.find { it.key.toLowerCase().contains(appName.toLowerCase()) } // library marker davegut.samsungTvApps, line 27
	if (thisApp != null) { // library marker davegut.samsungTvApps, line 28
		def appId = thisApp.value // library marker davegut.samsungTvApps, line 29
		appName = thisApp.key // library marker davegut.samsungTvApps, line 30
		logData << [appName: appName, appId: appId] // library marker davegut.samsungTvApps, line 31
		def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}" // library marker davegut.samsungTvApps, line 32
		try { // library marker davegut.samsungTvApps, line 33
			httpPost(uri, body) { resp -> // library marker davegut.samsungTvApps, line 34
				logData << [status: resp.statusLine, data: resp.data, success: resp.success] // library marker davegut.samsungTvApps, line 35
				if (resp.status == 200) { // library marker davegut.samsungTvApps, line 36
					if (connectST) { runIn(10, deviceRefresh) } // library marker davegut.samsungTvApps, line 37
					sendEvent(name: "appName", value: appName) // library marker davegut.samsungTvApps, line 38
					sendEvent(name: "appId", value: appId) // library marker davegut.samsungTvApps, line 39
					logDebug(logData) // library marker davegut.samsungTvApps, line 40
				} else { // library marker davegut.samsungTvApps, line 41
					logWarn(logData) // library marker davegut.samsungTvApps, line 42
				} // library marker davegut.samsungTvApps, line 43
			} // library marker davegut.samsungTvApps, line 44
			logDebug(logData) // library marker davegut.samsungTvApps, line 45
		} catch (err) { // library marker davegut.samsungTvApps, line 46
			logData << [status: "httpPost error", data: err] // library marker davegut.samsungTvApps, line 47
			logWarn(logData) // library marker davegut.samsungTvApps, line 48
		} // library marker davegut.samsungTvApps, line 49
	} else { // library marker davegut.samsungTvApps, line 50
		logData << [error: "appId is null"] // library marker davegut.samsungTvApps, line 51
		logWarn(logData) // library marker davegut.samsungTvApps, line 52
	} // library marker davegut.samsungTvApps, line 53
} // library marker davegut.samsungTvApps, line 54

def appClose(appId = device.currentValue("appId")) { // library marker davegut.samsungTvApps, line 56
	def logData = [method: "appClose", appId: appId] // library marker davegut.samsungTvApps, line 57
	if (appId == null || appId == " ") { // library marker davegut.samsungTvApps, line 58
		logData << [status: "appId is null", action: "try exit()"] // library marker davegut.samsungTvApps, line 59
		sendEvent(name: "appName", value: " ") // library marker davegut.samsungTvApps, line 60
		sendEvent(name: "appId", value: " ") // library marker davegut.samsungTvApps, line 61
		if (connectST) { runIn(5, deviceRefresh) } // library marker davegut.samsungTvApps, line 62
	} else { // library marker davegut.samsungTvApps, line 63
		logData << [status: "sending appClose"] // library marker davegut.samsungTvApps, line 64
		Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${appId}", // library marker davegut.samsungTvApps, line 65
					  timeout: 3] // library marker davegut.samsungTvApps, line 66
		asynchttpDelete("appCloseParse", params, [appId: appId]) // library marker davegut.samsungTvApps, line 67
	} // library marker davegut.samsungTvApps, line 68
	logDebug(logData) // library marker davegut.samsungTvApps, line 69
} // library marker davegut.samsungTvApps, line 70

def appCloseParse(resp, data) { // library marker davegut.samsungTvApps, line 72
	Map logData = [method: "appCloseParse", data: data] // library marker davegut.samsungTvApps, line 73
	if (resp.status == 200 && resp.json == true) { // library marker davegut.samsungTvApps, line 74
		logData << [status: resp.status, success: resp.json] // library marker davegut.samsungTvApps, line 75
		logDebug(logData) // library marker davegut.samsungTvApps, line 76
	} else { // library marker davegut.samsungTvApps, line 77
		logData << [status: resp.status, success: "false", action: "exit()"] // library marker davegut.samsungTvApps, line 78
		exit() // library marker davegut.samsungTvApps, line 79
		exit() // library marker davegut.samsungTvApps, line 80
		logWarn(logData) // library marker davegut.samsungTvApps, line 81
	} // library marker davegut.samsungTvApps, line 82
	if (connectST) { runIn(5, deviceRefresh) } // library marker davegut.samsungTvApps, line 83
	sendEvent(name: "appName", value: " ") // library marker davegut.samsungTvApps, line 84
	sendEvent(name: "appId", value: " ") // library marker davegut.samsungTvApps, line 85
} // library marker davegut.samsungTvApps, line 86

def updateAppCodes() { // library marker davegut.samsungTvApps, line 88
	Map logData = [method: "updateAppCodes", findAppCodes: findAppCodes] // library marker davegut.samsungTvApps, line 89
	if (findAppCodes != "off" &&  // library marker davegut.samsungTvApps, line 90
		device.currentValue("switch") == "on") { // library marker davegut.samsungTvApps, line 91
		device.updateSetting("findAppCodes", [type:"enum", value: "off"]) // library marker davegut.samsungTvApps, line 92
		if (findAppCodes == "startOver") {  // library marker davegut.samsungTvApps, line 93
			state.appData = [:] // library marker davegut.samsungTvApps, line 94
			logData << [appData: "reset"] // library marker davegut.samsungTvApps, line 95
		} // library marker davegut.samsungTvApps, line 96
		unschedule("onPoll") // library marker davegut.samsungTvApps, line 97
		state.appsInstalled = [] // library marker davegut.samsungTvApps, line 98
		def appIds = appIdList() // library marker davegut.samsungTvApps, line 99
		def appId = 0 // library marker davegut.samsungTvApps, line 100
		logData << [codesToCheck: appIds.size(), status: "OK"] // library marker davegut.samsungTvApps, line 101
		runIn(5, getAppData) // library marker davegut.samsungTvApps, line 102
		logInfo(logData) // library marker davegut.samsungTvApps, line 103
	} else if (device.currentValue("switch") == "off") { // library marker davegut.samsungTvApps, line 104
		logData << [status: "FAILED", reason: "tv off"] // library marker davegut.samsungTvApps, line 105
		logWarn(logData) // library marker davegut.samsungTvApps, line 106
	} // library marker davegut.samsungTvApps, line 107
	return logData // library marker davegut.samsungTvApps, line 108
} // library marker davegut.samsungTvApps, line 109

def getAppData(appId = 0) { // library marker davegut.samsungTvApps, line 111
	Map logData = [method: "getAppData", appId: appId] // library marker davegut.samsungTvApps, line 112
	def appIds = appIdList() // library marker davegut.samsungTvApps, line 113
	if (appId < appIds.size()) { // library marker davegut.samsungTvApps, line 114
		def appCode = appIds[appId] // library marker davegut.samsungTvApps, line 115
		logData << [appCode: appCode] // library marker davegut.samsungTvApps, line 116
		def thisDevice = state.appData.find { it.value == appCode } // library marker davegut.samsungTvApps, line 117
		if (thisDevice != null) { // library marker davegut.samsungTvApps, line 118
			logData << [thisDevice: thisDevice, status: "Already in appData"] // library marker davegut.samsungTvApps, line 119
			appId = appId + 1 // library marker davegut.samsungTvApps, line 120
			runInMillis(100, getAppData, [data: appId]) // library marker davegut.samsungTvApps, line 121
		} else { // library marker davegut.samsungTvApps, line 122
			logData << [status: "looking for App"] // library marker davegut.samsungTvApps, line 123
			Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${appCode}", // library marker davegut.samsungTvApps, line 124
						  timeout: 10] // library marker davegut.samsungTvApps, line 125
			asynchttpGet("parseGetAppData", params, [appId:appId, appCode: appCode]) // library marker davegut.samsungTvApps, line 126
		} // library marker davegut.samsungTvApps, line 127
		logDebug(logData) // library marker davegut.samsungTvApps, line 128
	} else { // library marker davegut.samsungTvApps, line 129
		runIn(5, setOnPollInterval) // library marker davegut.samsungTvApps, line 130
		logData << [status: "Done finding", totalApps: state.appData.size(), appsInstalled: state.appsInstalled] // library marker davegut.samsungTvApps, line 131
		state.remove("appsInstalled") // library marker davegut.samsungTvApps, line 132
		state.remove("retry") // library marker davegut.samsungTvApps, line 133
		logInfo(logData) // library marker davegut.samsungTvApps, line 134
	} // library marker davegut.samsungTvApps, line 135
} // library marker davegut.samsungTvApps, line 136

def parseGetAppData(resp, data) { // library marker davegut.samsungTvApps, line 138
	Map logData = [method: "parseGetAppData", data: data, status: resp.status] // library marker davegut.samsungTvApps, line 139
	if (resp.status == 200) { // library marker davegut.samsungTvApps, line 140
		def respData = new JsonSlurper().parseText(resp.data) // library marker davegut.samsungTvApps, line 141
		String name = shortenName(respData.name) // library marker davegut.samsungTvApps, line 142
		logData << [name: name, status: "appAdded"] // library marker davegut.samsungTvApps, line 143
		state.appData << ["${name}": respData.id] // library marker davegut.samsungTvApps, line 144
		state.appsInstalled << name // library marker davegut.samsungTvApps, line 145
		logDebug(logData) // library marker davegut.samsungTvApps, line 146
		state.retry = false // library marker davegut.samsungTvApps, line 147
		runIn(1, getAppData, [data: data.appId + 1]) // library marker davegut.samsungTvApps, line 148
	} else if (resp.status == 404) { // library marker davegut.samsungTvApps, line 149
		logData << [status: "appNotAdded", reason: "not installed in TV"] // library marker davegut.samsungTvApps, line 150
		logDebug(logData) // library marker davegut.samsungTvApps, line 151
		state.retry = false // library marker davegut.samsungTvApps, line 152
		runIn(1, getAppData, [data: data.appId + 1]) // library marker davegut.samsungTvApps, line 153
	} else { // library marker davegut.samsungTvApps, line 154
		logData << [retry: state.retry, status: "appNotAdded", // library marker davegut.samsungTvApps, line 155
					reason: "invalid response from device"] // library marker davegut.samsungTvApps, line 156
		if (state.retry == false) { // library marker davegut.samsungTvApps, line 157
			logData << [action: "<b>RETRYING</b>"] // library marker davegut.samsungTvApps, line 158
			state.retry = true // library marker davegut.samsungTvApps, line 159
			runIn(5, getAppData, [data: data.appId]) // library marker davegut.samsungTvApps, line 160
		} else { // library marker davegut.samsungTvApps, line 161
			runIn(1, getAppData, [data: data.appId + 1]) // library marker davegut.samsungTvApps, line 162
		} // library marker davegut.samsungTvApps, line 163
		logWarn(logData) // library marker davegut.samsungTvApps, line 164
	} // library marker davegut.samsungTvApps, line 165
} // library marker davegut.samsungTvApps, line 166

def shortenName(name) { // library marker davegut.samsungTvApps, line 168
	if (name.contains(" - ")) { // library marker davegut.samsungTvApps, line 169
		name = name.substring(0, name.indexOf(" - ")) // library marker davegut.samsungTvApps, line 170
	} else if (name.contains(" by ")) { // library marker davegut.samsungTvApps, line 171
		name = name.substring(0, name.indexOf(" by ")) // library marker davegut.samsungTvApps, line 172
	} else if (name.contains(": ")) { // library marker davegut.samsungTvApps, line 173
		name = name.substring(0, name.indexOf(": ")) // library marker davegut.samsungTvApps, line 174
	} else if (name.contains(" | ")) { // library marker davegut.samsungTvApps, line 175
		name = name.substring(0, name.indexOf(" | ")) // library marker davegut.samsungTvApps, line 176
	} // library marker davegut.samsungTvApps, line 177
	return name // library marker davegut.samsungTvApps, line 178
} // library marker davegut.samsungTvApps, line 179

def appIdList() { // library marker davegut.samsungTvApps, line 181
	def appList = [ // library marker davegut.samsungTvApps, line 182
		"Nuvyyo0002.tablo", "5b8c3eb16b.BeamCTVDev", "kk8MbItQ0H.VUDU", "vYmY3ACVaa.emby",  // library marker davegut.samsungTvApps, line 183
		"ZmmGjO6VKO.slingtv", "PvWgqxV3Xa.YouTubeTV", "LBUAQX1exg.Hulu",  // library marker davegut.samsungTvApps, line 184
		"AQKO41xyKP.AmazonAlexa", "3KA0pm7a7V.TubiTV", "cj37Ni3qXM.HBONow", "gzcc4LRFBF.Peacock",  // library marker davegut.samsungTvApps, line 185
		"9Ur5IzDKqV.TizenYouTube", "BjyffU0l9h.Stream", "3201907018807", "3201910019365",  // library marker davegut.samsungTvApps, line 186
		"3201907018784", "kIciSQlYEM.plex", "ckfgqqzvt0.dplus", "H7DIeAitkn.DisneyNOW", // library marker davegut.samsungTvApps, line 187
		"MCmYXNxgcu.DisneyPlus", "tCyZuSsCVw.Britbox", "tzo5Zi4mCPv.fuboTV", "3HYANqBDJD.DFW", // library marker davegut.samsungTvApps, line 188
		"EYm8vc1St4.Philo", "N4St7cQBPD.SiriusXM", "sNUyBbfvHf.SpectrumTV", "rJeHak5zRg.Spotify", // library marker davegut.samsungTvApps, line 189
		"3KA0pm7a7V.TubiTV", "r1mzFxGfYe.E","3201606009684", "3201910019365", "3201807016597",  // library marker davegut.samsungTvApps, line 190
		"3201601007625", "3201710015037", "3201908019041", "3201504001965", "3201907018784",  // library marker davegut.samsungTvApps, line 191
		"org.tizen.browser", "org.tizen.primevideo", "org.tizen.netflix-app",  // library marker davegut.samsungTvApps, line 192
		"com.samsung.tv.aria-video", "com.samsung.tv.gallery", "org.tizen.apple.apple-music", // library marker davegut.samsungTvApps, line 193
		"com.samsung.tv.store", // library marker davegut.samsungTvApps, line 194

		"3202203026841", "3202103023232", "3202103023185", "3202012022468", "3202012022421", // library marker davegut.samsungTvApps, line 196
		"3202011022316", "3202011022131", "3202010022098", "3202009021877", "3202008021577", // library marker davegut.samsungTvApps, line 197
		"3202008021462", "3202008021439", "3202007021336", "3202004020674", "3202004020626", // library marker davegut.samsungTvApps, line 198
		"3202003020365", "3201910019457", "3201910019449", "3201910019420", "3201910019378", // library marker davegut.samsungTvApps, line 199
		"3201910019354", "3201909019271", "3201909019175", "3201908019041", "3201908019022",  // library marker davegut.samsungTvApps, line 200
		"3201907018786", "3201906018693", // library marker davegut.samsungTvApps, line 201
		"3201901017768", "3201901017640", "3201812017479", "3201810017091", "3201810017074", // library marker davegut.samsungTvApps, line 202
		"3201807016597", "3201806016432", "3201806016390", "3201806016381", "3201805016367", // library marker davegut.samsungTvApps, line 203
		"3201803015944", "3201803015934", "3201803015869", "3201711015226", "3201710015067", // library marker davegut.samsungTvApps, line 204
		"3201710015037", "3201710015016", "3201710014874", "3201710014866", "3201707014489", // library marker davegut.samsungTvApps, line 205
		"3201706014250", "3201706012478", "3201704012212", "3201704012147", "3201703012079", // library marker davegut.samsungTvApps, line 206
		"3201703012065", "3201703012029", "3201702011851", "3201612011418", "3201611011210", // library marker davegut.samsungTvApps, line 207
		"3201611011005", "3201611010983", "3201608010385", "3201608010191", "3201607010031", // library marker davegut.samsungTvApps, line 208
		"3201606009910", "3201606009798", "3201606009684", "3201604009182", "3201603008746", // library marker davegut.samsungTvApps, line 209
		"3201603008210", "3201602007865", "3201601007670", "3201601007625", "3201601007230", // library marker davegut.samsungTvApps, line 210
		"3201512006963", "3201512006785", "3201511006428", "3201510005981", "3201506003488", // library marker davegut.samsungTvApps, line 211
		"3201506003486", "3201506003175", "3201504001965", "121299000612", "121299000101", // library marker davegut.samsungTvApps, line 212
		"121299000089", "111399002220", "111399002034", "111399000741", "111299002148", // library marker davegut.samsungTvApps, line 213
		"111299001912", "111299000769", "111012010001", "11101200001", "11101000407", // library marker davegut.samsungTvApps, line 214
		"11091000000" // library marker davegut.samsungTvApps, line 215
	] // library marker davegut.samsungTvApps, line 216
	return appList // library marker davegut.samsungTvApps, line 217
} // library marker davegut.samsungTvApps, line 218

def updateAppName(tvName = device.currentValue("tvChannelName")) { // library marker davegut.samsungTvApps, line 220
	//	If the tvChannel is blank, the the name may reflect the appId // library marker davegut.samsungTvApps, line 221
	//	that is used by the device.  Thanks SmartThings. // library marker davegut.samsungTvApps, line 222
	String appId = " " // library marker davegut.samsungTvApps, line 223
	String appName = " " // library marker davegut.samsungTvApps, line 224
	Map logData = [method: "updateAppName", tvName: tvName] // library marker davegut.samsungTvApps, line 225
	//	There are some names that need translation based on known // library marker davegut.samsungTvApps, line 226
	//	idiosyncracies with the SmartThings implementation. // library marker davegut.samsungTvApps, line 227
	//	Go to translation table and if the translation exists, // library marker davegut.samsungTvApps, line 228
	//	set the appName to that value. // library marker davegut.samsungTvApps, line 229
	def tempName = transTable().find { it.key == tvName } // library marker davegut.samsungTvApps, line 230
	if (tempName != null) {  // library marker davegut.samsungTvApps, line 231
		appName = tempName.value // library marker davegut.samsungTvApps, line 232
		logData << [tempName: tempName] // library marker davegut.samsungTvApps, line 233
	} // library marker davegut.samsungTvApps, line 234
	//	See if the name is in the app list.  If so, update here // library marker davegut.samsungTvApps, line 235
	//	and in states. // library marker davegut.samsungTvApps, line 236
	def thisApp = state.appData.find { it.key == appName } // library marker davegut.samsungTvApps, line 237
	if (thisApp) { // library marker davegut.samsungTvApps, line 238
		logData << [thisApp: thisApp] // library marker davegut.samsungTvApps, line 239
		appId = thisApp.value // library marker davegut.samsungTvApps, line 240
	} else { // library marker davegut.samsungTvApps, line 241
		Map params = [uri: "http://${deviceIp}:8001/api/v2/applications/${tvName}", // library marker davegut.samsungTvApps, line 242
				  	timeout: 10] // library marker davegut.samsungTvApps, line 243
		try { // library marker davegut.samsungTvApps, line 244
			httpGet(params) { resp -> // library marker davegut.samsungTvApps, line 245
				appId = resp.data.id // library marker davegut.samsungTvApps, line 246
				appName = shortenName(resp.data.name) // library marker davegut.samsungTvApps, line 247
				logData << [appId: appId, appName: appName] // library marker davegut.samsungTvApps, line 248
			} // library marker davegut.samsungTvApps, line 249
		}catch (err) { // library marker davegut.samsungTvApps, line 250
			logData << [error: err] // library marker davegut.samsungTvApps, line 251
		} // library marker davegut.samsungTvApps, line 252
		if (appId != "") { // library marker davegut.samsungTvApps, line 253
			state.appData << ["${appName}": appId] // library marker davegut.samsungTvApps, line 254
			logData << [appData: ["${appName}": appId]] // library marker davegut.samsungTvApps, line 255
		} // library marker davegut.samsungTvApps, line 256
	} // library marker davegut.samsungTvApps, line 257
	sendEvent(name: "appName", value: appName) // library marker davegut.samsungTvApps, line 258
	sendEvent(name: "appId", value: appId) // library marker davegut.samsungTvApps, line 259
	logDebug(logData) // library marker davegut.samsungTvApps, line 260
} // library marker davegut.samsungTvApps, line 261

def updateTitle() { // library marker davegut.samsungTvApps, line 263
	String tvChannel = device.currentValue("tvChannel") // library marker davegut.samsungTvApps, line 264
	String title = "${tvChannel}: ${device.currentValue("tvChannelName")}" // library marker davegut.samsungTvApps, line 265
	if (tvChannel == " ") { // library marker davegut.samsungTvApps, line 266
		title = "app: ${device.currentValue("appName")}" // library marker davegut.samsungTvApps, line 267
	} // library marker davegut.samsungTvApps, line 268
	sendEvent(name: "nowPlaying", value: title) // library marker davegut.samsungTvApps, line 269
} // library marker davegut.samsungTvApps, line 270

def transTable() { // library marker davegut.samsungTvApps, line 272
	def translations = [ // library marker davegut.samsungTvApps, line 273
		"org.tizen.primevideo": "Prime Video", // library marker davegut.samsungTvApps, line 274
		"org.tizen.netflix-app": "Netflix", // library marker davegut.samsungTvApps, line 275
		"org.tizen.browser": "Internet", // library marker davegut.samsungTvApps, line 276
		"com.samsung.tv.aria-video": "Apple TV", // library marker davegut.samsungTvApps, line 277
		"com.samsung.tv.gallery": "Gallery", // library marker davegut.samsungTvApps, line 278
		"org.tizen.apple.apple-music": "Apple Music" // library marker davegut.samsungTvApps, line 279
		] // library marker davegut.samsungTvApps, line 280
	return translations // library marker davegut.samsungTvApps, line 281
} // library marker davegut.samsungTvApps, line 282

// ~~~~~ end include (88) davegut.samsungTvApps ~~~~~

// ~~~~~ start include (93) davegut.samsungTvPresets ~~~~~
library ( // library marker davegut.samsungTvPresets, line 1
	name: "samsungTvPresets", // library marker davegut.samsungTvPresets, line 2
	namespace: "davegut", // library marker davegut.samsungTvPresets, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTvPresets, line 4
	description: "Samsung TV Preset Implementation", // library marker davegut.samsungTvPresets, line 5
	category: "utilities", // library marker davegut.samsungTvPresets, line 6
	documentationLink: "" // library marker davegut.samsungTvPresets, line 7
) // library marker davegut.samsungTvPresets, line 8

command "presetUpdateNext" // library marker davegut.samsungTvPresets, line 10
command "presetCreate", [ // library marker davegut.samsungTvPresets, line 11
	[name: "Preset Number", type: "ENUM",  // library marker davegut.samsungTvPresets, line 12
	 constraints: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"]]] // library marker davegut.samsungTvPresets, line 13
command "presetExecute", [ // library marker davegut.samsungTvPresets, line 14
	[name: "Preset Number", type: "ENUM", // library marker davegut.samsungTvPresets, line 15
	 constraints: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"]]] // library marker davegut.samsungTvPresets, line 16
command "presetCreateTv", [ // library marker davegut.samsungTvPresets, line 17
	[name: "Preset Number", type: "ENUM", // library marker davegut.samsungTvPresets, line 18
	 constraints: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"]], // library marker davegut.samsungTvPresets, line 19
	[name: "tvChannel", type: "STRING"], // library marker davegut.samsungTvPresets, line 20
	[name: "tvChannelName", type: "STRING"]] // library marker davegut.samsungTvPresets, line 21
attribute "presetUpdateNext", "string" // library marker davegut.samsungTvPresets, line 22

def presetUpdateNext() { // library marker davegut.samsungTvPresets, line 24
	//	Sets up next presetExecute to update the preset selected // library marker davegut.samsungTvPresets, line 25
	//	Has a 10 second timer to select preset to reset.  Will then // library marker davegut.samsungTvPresets, line 26
	//	revert back to false // library marker davegut.samsungTvPresets, line 27
	sendEvent(name: "presetUpdateNext", value: "true") // library marker davegut.samsungTvPresets, line 28
	runIn(5, undoUpdate) // library marker davegut.samsungTvPresets, line 29
} // library marker davegut.samsungTvPresets, line 30
def undoUpdate() { sendEvent(name: "presetUpdateNext", value: "false") } // library marker davegut.samsungTvPresets, line 31

def presetCreate(presetNumber) { // library marker davegut.samsungTvPresets, line 33
	//	Called from Hubitat Device's page for TV or from presetExecute // library marker davegut.samsungTvPresets, line 34
	//	when state.updateNextPreset is true // library marker davegut.samsungTvPresets, line 35
	refresh() // library marker davegut.samsungTvPresets, line 36
	pauseExecution(2000) // library marker davegut.samsungTvPresets, line 37
	Map logData = [method: "presetCreate", presetNumber: presetNumber] // library marker davegut.samsungTvPresets, line 38
	String appName = device.currentValue("appName") // library marker davegut.samsungTvPresets, line 39
	String tvChannel = device.currentValue("tvChannel") // library marker davegut.samsungTvPresets, line 40
	if (appName != " ") { // library marker davegut.samsungTvPresets, line 41
		String appId = device.currentValue("appId") // library marker davegut.samsungTvPresets, line 42
		presetCreateApp(presetNumber, appName, appId) // library marker davegut.samsungTvPresets, line 43
		logData << [action: "appPresetCreate"] // library marker davegut.samsungTvPresets, line 44
	} else if (tvChannel != " ") { // library marker davegut.samsungTvPresets, line 45
		String tvChannelName = device.currentValue("tvChannelName") // library marker davegut.samsungTvPresets, line 46
		presetCreateTv(presetNumber, tvChannel, tvChannelName) // library marker davegut.samsungTvPresets, line 47
		logData << [action: "tvPresetCreate"] // library marker davegut.samsungTvPresets, line 48
	} // library marker davegut.samsungTvPresets, line 49
	logInfo(logData) // library marker davegut.samsungTvPresets, line 50
} // library marker davegut.samsungTvPresets, line 51

def presetCreateApp(presetNumber, appName, appId) { // library marker davegut.samsungTvPresets, line 53
	Map logData = [method: "appPresetCreate", presetNumber: presetNumber, // library marker davegut.samsungTvPresets, line 54
				   appName: appName, appId: appId] // library marker davegut.samsungTvPresets, line 55
	Map thisPresetData = [type: "application", execute: appName, appId: appId] // library marker davegut.samsungTvPresets, line 56
	logData << [thisPresetData: thisPresetData, status: "updating, check state to confirm"] // library marker davegut.samsungTvPresets, line 57
	presetDataUpdate(presetNumber, thisPresetData) // library marker davegut.samsungTvPresets, line 58
	logInfo(logData) // library marker davegut.samsungTvPresets, line 59
} // library marker davegut.samsungTvPresets, line 60

def presetCreateTv(presetNumber, tvChannel, tvChannelName) { // library marker davegut.samsungTvPresets, line 62
	Map logData = [method: "resetCreateTv", presetNumber: presetNumber,  // library marker davegut.samsungTvPresets, line 63
				   tvChannel: tvChannel, tvChannelName: tvChannelName] // library marker davegut.samsungTvPresets, line 64
	Map thisPresetData = [type: "tvChannel", execute: tvChannel, tvChannelName: tvChannelName] // library marker davegut.samsungTvPresets, line 65
	logData << [thisPresetData: thisPresetData, status: "updating, check state to confirm"] // library marker davegut.samsungTvPresets, line 66
	presetDataUpdate(presetNumber, thisPresetData) // library marker davegut.samsungTvPresets, line 67
	logInfo(logData) // library marker davegut.samsungTvPresets, line 68
} // library marker davegut.samsungTvPresets, line 69

def presetDataUpdate(presetNumber, thisPresetData) { // library marker davegut.samsungTvPresets, line 71
	Map presetData = state.presetData // library marker davegut.samsungTvPresets, line 72
	state.remove("presetData") // library marker davegut.samsungTvPresets, line 73
	if (presetData == null) { presetData = [:] } // library marker davegut.samsungTvPresets, line 74
	if (presetData.find{it.key == presetNumber}) { // library marker davegut.samsungTvPresets, line 75
		presetData.remove(presetNumber) // library marker davegut.samsungTvPresets, line 76
	} // library marker davegut.samsungTvPresets, line 77
	presetData << ["${presetNumber}": thisPresetData] // library marker davegut.samsungTvPresets, line 78
	state.presetData = presetData // library marker davegut.samsungTvPresets, line 79
} // library marker davegut.samsungTvPresets, line 80

def presetExecute(presetNumber) { // library marker davegut.samsungTvPresets, line 82
	Map logData = [method: "presetExecute", presetNumber: presetNumber] // library marker davegut.samsungTvPresets, line 83
	if (device.currentValue("presetUpdateNext") == "true") { // library marker davegut.samsungTvPresets, line 84
		sendEvent(name: "presetUpdateNext", value: "false") // library marker davegut.samsungTvPresets, line 85
		logData << [action: "presetCreate"] // library marker davegut.samsungTvPresets, line 86
		presetCreate(presetNumber) // library marker davegut.samsungTvPresets, line 87
	} else { // library marker davegut.samsungTvPresets, line 88
		def thisPreset = state.presetData.find { it.key == presetNumber } // library marker davegut.samsungTvPresets, line 89
		if (thisPreset == null) { // library marker davegut.samsungTvPresets, line 90
			logData << [error: "presetNotSet"] // library marker davegut.samsungTvPresets, line 91
			logWarn(logData) // library marker davegut.samsungTvPresets, line 92
		} else { // library marker davegut.samsungTvPresets, line 93
			def execute = thisPreset.value.execute // library marker davegut.samsungTvPresets, line 94
			def presetType = thisPreset.value.type // library marker davegut.samsungTvPresets, line 95
			if (presetType == "application") { // library marker davegut.samsungTvPresets, line 96
				//	Simply open the app. // library marker davegut.samsungTvPresets, line 97
				appOpenByName(execute) // library marker davegut.samsungTvPresets, line 98
				sendEvent(name: "appId", value: thisPreset.value.appId) // library marker davegut.samsungTvPresets, line 99
				sendEvent(name: "appName", value: execute) // library marker davegut.samsungTvPresets, line 100
				sendEvent(name: "tvChannel", value: " ") // library marker davegut.samsungTvPresets, line 101
				sendEvent(name: "tvChannelName", value: " ") // library marker davegut.samsungTvPresets, line 102
				logData << [appName: execute, appId: thisPreset.value.appId] // library marker davegut.samsungTvPresets, line 103
			} else if (presetType == "tvChannel") { // library marker davegut.samsungTvPresets, line 104
				//	Close running app the update channel // library marker davegut.samsungTvPresets, line 105
				if (!ST && device.currentValue("appId") != " ") { // library marker davegut.samsungTvPresets, line 106
					appClose() // library marker davegut.samsungTvPresets, line 107
					pauseExecution(7000) // library marker davegut.samsungTvPresets, line 108
				} // library marker davegut.samsungTvPresets, line 109
				channelSet(execute) // library marker davegut.samsungTvPresets, line 110
				sendEvent(name: "appId", value: " ") // library marker davegut.samsungTvPresets, line 111
				sendEvent(name: "appName", value: " ") // library marker davegut.samsungTvPresets, line 112
				sendEvent(name: "tvChannel", value: execute) // library marker davegut.samsungTvPresets, line 113
				sendEvent(name: "tvChannelName", value: thisPreset.value.tvChannelName) // library marker davegut.samsungTvPresets, line 114
				logData << [tvChannel: tvChannel, tvChannelName: thisPreset.value.tvChannelName] // library marker davegut.samsungTvPresets, line 115
			} else { // library marker davegut.samsungTvPresets, line 116
				logData << [error: "invalid preset type"] // library marker davegut.samsungTvPresets, line 117
				logWarn(logData) // library marker davegut.samsungTvPresets, line 118
			} // library marker davegut.samsungTvPresets, line 119
		} // library marker davegut.samsungTvPresets, line 120
		runIn(2, updateTitle) // library marker davegut.samsungTvPresets, line 121
	} // library marker davegut.samsungTvPresets, line 122
	logDebug(logData) // library marker davegut.samsungTvPresets, line 123
} // library marker davegut.samsungTvPresets, line 124


// ~~~~~ end include (93) davegut.samsungTvPresets ~~~~~

// ~~~~~ start include (91) davegut.SmartThingsInterface ~~~~~
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
		input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "") // library marker davegut.SmartThingsInterface, line 13
		if (stApiKey) { // library marker davegut.SmartThingsInterface, line 14
			input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "") // library marker davegut.SmartThingsInterface, line 15
		} // library marker davegut.SmartThingsInterface, line 16
		input ("stPollInterval", "enum", title: "SmartThings Poll Interval (minutes)", // library marker davegut.SmartThingsInterface, line 17
			   options: ["off", "1", "5", "15", "30"], defaultValue: "15") // library marker davegut.SmartThingsInterface, line 18
		input ("stTestData", "bool", title: "Get ST data dump for developer", defaultValue: false) // library marker davegut.SmartThingsInterface, line 19
	} // library marker davegut.SmartThingsInterface, line 20
} // library marker davegut.SmartThingsInterface, line 21

def stUpdate() { // library marker davegut.SmartThingsInterface, line 23
	def stData = [:] // library marker davegut.SmartThingsInterface, line 24
	if (connectST) { // library marker davegut.SmartThingsInterface, line 25
		stData << [connectST: "true"] // library marker davegut.SmartThingsInterface, line 26
		stData << [connectST: connectST] // library marker davegut.SmartThingsInterface, line 27
		if (!stApiKey || stApiKey == "") { // library marker davegut.SmartThingsInterface, line 28
			logWarn("\n\n\t\t<b>Enter the ST API Key and Save Preferences</b>\n\n") // library marker davegut.SmartThingsInterface, line 29
			stData << [status: "ERROR", date: "no stApiKey"] // library marker davegut.SmartThingsInterface, line 30
		} else if (!stDeviceId || stDeviceId == "") { // library marker davegut.SmartThingsInterface, line 31
			getDeviceList() // library marker davegut.SmartThingsInterface, line 32
			logWarn("\n\n\t\t<b>Enter the deviceId from the log List and Save Preferences</b>\n\n") // library marker davegut.SmartThingsInterface, line 33
			stData << [status: "ERROR", date: "no stDeviceId"] // library marker davegut.SmartThingsInterface, line 34
		} else { // library marker davegut.SmartThingsInterface, line 35
			def stPollInterval = stPollInterval // library marker davegut.SmartThingsInterface, line 36
			if (stPollInterval == null) {  // library marker davegut.SmartThingsInterface, line 37
				stPollInterval = "15" // library marker davegut.SmartThingsInterface, line 38
				device.updateSetting("stPollInterval", [type:"enum", value: "15"]) // library marker davegut.SmartThingsInterface, line 39
			} // library marker davegut.SmartThingsInterface, line 40
			switch(stPollInterval) { // library marker davegut.SmartThingsInterface, line 41
				case "1" : runEvery1Minute(refresh); break // library marker davegut.SmartThingsInterface, line 42
				case "5" : runEvery5Minutes(refresh); break // library marker davegut.SmartThingsInterface, line 43
				case "15" : runEvery15Minutes(refresh); break // library marker davegut.SmartThingsInterface, line 44
				case "30" : runEvery30Minutes(refresh); break // library marker davegut.SmartThingsInterface, line 45
				default: unschedule("refresh") // library marker davegut.SmartThingsInterface, line 46
			} // library marker davegut.SmartThingsInterface, line 47
			deviceSetup() // library marker davegut.SmartThingsInterface, line 48
			stData << [stPollInterval: stPollInterval] // library marker davegut.SmartThingsInterface, line 49
		} // library marker davegut.SmartThingsInterface, line 50
	} else { // library marker davegut.SmartThingsInterface, line 51
		stData << [connectST: "false"] // library marker davegut.SmartThingsInterface, line 52
	} // library marker davegut.SmartThingsInterface, line 53
	logInfo("stUpdate: ${stData}") // library marker davegut.SmartThingsInterface, line 54
} // library marker davegut.SmartThingsInterface, line 55

def deviceSetup() { // library marker davegut.SmartThingsInterface, line 57
	if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.SmartThingsInterface, line 58
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.SmartThingsInterface, line 59
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.SmartThingsInterface, line 60
	} else { // library marker davegut.SmartThingsInterface, line 61
		def sendData = [ // library marker davegut.SmartThingsInterface, line 62
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.SmartThingsInterface, line 63
			parse: "distResp" // library marker davegut.SmartThingsInterface, line 64
			] // library marker davegut.SmartThingsInterface, line 65
		asyncGet(sendData, "deviceSetup") // library marker davegut.SmartThingsInterface, line 66
	} // library marker davegut.SmartThingsInterface, line 67
} // library marker davegut.SmartThingsInterface, line 68

def getDeviceList() { // library marker davegut.SmartThingsInterface, line 70
	def sendData = [ // library marker davegut.SmartThingsInterface, line 71
		path: "/devices", // library marker davegut.SmartThingsInterface, line 72
		parse: "getDeviceListParse" // library marker davegut.SmartThingsInterface, line 73
		] // library marker davegut.SmartThingsInterface, line 74
	asyncGet(sendData) // library marker davegut.SmartThingsInterface, line 75
} // library marker davegut.SmartThingsInterface, line 76

def getDeviceListParse(resp, data) { // library marker davegut.SmartThingsInterface, line 78
	def respData // library marker davegut.SmartThingsInterface, line 79
	if (resp.status != 200) { // library marker davegut.SmartThingsInterface, line 80
		respData = [status: "ERROR", // library marker davegut.SmartThingsInterface, line 81
					httpCode: resp.status, // library marker davegut.SmartThingsInterface, line 82
					errorMsg: resp.errorMessage] // library marker davegut.SmartThingsInterface, line 83
	} else { // library marker davegut.SmartThingsInterface, line 84
		try { // library marker davegut.SmartThingsInterface, line 85
			respData = new JsonSlurper().parseText(resp.data) // library marker davegut.SmartThingsInterface, line 86
		} catch (err) { // library marker davegut.SmartThingsInterface, line 87
			respData = [status: "ERROR", // library marker davegut.SmartThingsInterface, line 88
						errorMsg: err, // library marker davegut.SmartThingsInterface, line 89
						respData: resp.data] // library marker davegut.SmartThingsInterface, line 90
		} // library marker davegut.SmartThingsInterface, line 91
	} // library marker davegut.SmartThingsInterface, line 92
	if (respData.status == "ERROR") { // library marker davegut.SmartThingsInterface, line 93
		logWarn("getDeviceListParse: ${respData}") // library marker davegut.SmartThingsInterface, line 94
	} else { // library marker davegut.SmartThingsInterface, line 95
		log.info "" // library marker davegut.SmartThingsInterface, line 96
		respData.items.each { // library marker davegut.SmartThingsInterface, line 97
			log.trace "${it.label}:   ${it.deviceId}" // library marker davegut.SmartThingsInterface, line 98
		} // library marker davegut.SmartThingsInterface, line 99
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>" // library marker davegut.SmartThingsInterface, line 100
	} // library marker davegut.SmartThingsInterface, line 101
} // library marker davegut.SmartThingsInterface, line 102

def deviceSetupParse(mainData) { // library marker davegut.SmartThingsInterface, line 104
	def setupData = [:] // library marker davegut.SmartThingsInterface, line 105

	def pictureModes = mainData["custom.picturemode"].supportedPictureModes.value // library marker davegut.SmartThingsInterface, line 107
	state.pictureModes = pictureModes // library marker davegut.SmartThingsInterface, line 108
	setupData << [pictureModes: pictureModes] // library marker davegut.SmartThingsInterface, line 109

	def soundModes =  mainData["custom.soundmode"].supportedSoundModes.value // library marker davegut.SmartThingsInterface, line 111
	state.soundModes = soundModes // library marker davegut.SmartThingsInterface, line 112
	setupData << [soundModes: soundModes] // library marker davegut.SmartThingsInterface, line 113

	logInfo("deviceSetupParse: ${setupData}") // library marker davegut.SmartThingsInterface, line 115
} // library marker davegut.SmartThingsInterface, line 116

def deviceCommand(cmdData) { // library marker davegut.SmartThingsInterface, line 118
	logDebug("deviceCommand: $cmdData") // library marker davegut.SmartThingsInterface, line 119
	def respData = [:] // library marker davegut.SmartThingsInterface, line 120
	if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.SmartThingsInterface, line 121
		respData << [status: "FAILED", data: "no stDeviceId"] // library marker davegut.SmartThingsInterface, line 122
	} else { // library marker davegut.SmartThingsInterface, line 123
		def sendData = [ // library marker davegut.SmartThingsInterface, line 124
			path: "/devices/${stDeviceId.trim()}/commands", // library marker davegut.SmartThingsInterface, line 125
			cmdData: cmdData // library marker davegut.SmartThingsInterface, line 126
		] // library marker davegut.SmartThingsInterface, line 127
		respData = syncPost(sendData) // library marker davegut.SmartThingsInterface, line 128
	} // library marker davegut.SmartThingsInterface, line 129
	if (respData.status == "OK") { // library marker davegut.SmartThingsInterface, line 130
		if (cmdData.capability && cmdData.capability != "refresh") { // library marker davegut.SmartThingsInterface, line 131
			deviceRefresh() // library marker davegut.SmartThingsInterface, line 132
		} else { // library marker davegut.SmartThingsInterface, line 133
			poll() // library marker davegut.SmartThingsInterface, line 134
		} // library marker davegut.SmartThingsInterface, line 135
	}else { // library marker davegut.SmartThingsInterface, line 136
		logWarn("deviceCommand: [status: ${respData.status}, data: ${respData}]") // library marker davegut.SmartThingsInterface, line 137
		if (respData.toString().contains("Conflict")) { // library marker davegut.SmartThingsInterface, line 138
			logWarn("<b>Conflict internal to SmartThings.  Device may be offline in SmartThings</b>") // library marker davegut.SmartThingsInterface, line 139
		} // library marker davegut.SmartThingsInterface, line 140
	} // library marker davegut.SmartThingsInterface, line 141
} // library marker davegut.SmartThingsInterface, line 142

def statusParse(mainData) { // library marker davegut.SmartThingsInterface, line 144
	Map logData = [method: "statusParse"] // library marker davegut.SmartThingsInterface, line 145
	if (stTestData) { // library marker davegut.SmartThingsInterface, line 146
		device.updateSetting("stTestData", [type:"bool", value: false]) // library marker davegut.SmartThingsInterface, line 147
		Map testData = [stTestData: mainData] // library marker davegut.SmartThingsInterface, line 148
	} // library marker davegut.SmartThingsInterface, line 149
	String onOff = mainData.switch.switch.value // library marker davegut.SmartThingsInterface, line 150
	Map parseResults = [:] // library marker davegut.SmartThingsInterface, line 151
	if (onOff == "on") { // library marker davegut.SmartThingsInterface, line 152
		Integer volume = mainData.audioVolume.volume.value.toInteger() // library marker davegut.SmartThingsInterface, line 153
		sendEvent(name: "volume", value: volume) // library marker davegut.SmartThingsInterface, line 154
		sendEvent(name: "level", value: volume) // library marker davegut.SmartThingsInterface, line 155
		parseResults << [volume: volume] // library marker davegut.SmartThingsInterface, line 156

		String mute = mainData.audioMute.mute.value // library marker davegut.SmartThingsInterface, line 158
		sendEvent(name: "mute", value: mute) // library marker davegut.SmartThingsInterface, line 159
		parseResults << [mute: mute] // library marker davegut.SmartThingsInterface, line 160

		String inputSource = mainData.mediaInputSource.inputSource.value // library marker davegut.SmartThingsInterface, line 162
		sendEvent(name: "inputSource", value: inputSource)		 // library marker davegut.SmartThingsInterface, line 163
		parseResults << [inputSource: inputSource] // library marker davegut.SmartThingsInterface, line 164

		String tvChannel = mainData.tvChannel.tvChannel.value // library marker davegut.SmartThingsInterface, line 166
		if (tvChannel == null) { tvChannel = " " } // library marker davegut.SmartThingsInterface, line 167
		String tvChannelName = mainData.tvChannel.tvChannelName.value // library marker davegut.SmartThingsInterface, line 168
		parseResults << [tvChannel: tvChannel, tvChannelName: tvChannelName] // library marker davegut.SmartThingsInterface, line 169
		if (tvChannel == " " && tvChannelName != device.currentValue("tvChannelName")) { // library marker davegut.SmartThingsInterface, line 170
			//	tvChannel indicates app, tvChannelName is thrn spp code (ST Version) // library marker davegut.SmartThingsInterface, line 171
			if (tvChannelName.contains(".")) { // library marker davegut.SmartThingsInterface, line 172
				runIn(2, updateAppName) // library marker davegut.SmartThingsInterface, line 173
			} // library marker davegut.SmartThingsInterface, line 174
		} // library marker davegut.SmartThingsInterface, line 175
		sendEvent(name: "tvChannel", value: tvChannel) // library marker davegut.SmartThingsInterface, line 176
		sendEvent(name: "tvChannelName", value: tvChannelName) // library marker davegut.SmartThingsInterface, line 177

		String pictureMode = mainData["custom.picturemode"].pictureMode.value // library marker davegut.SmartThingsInterface, line 179
		sendEvent(name: "pictureMode",value: pictureMode) // library marker davegut.SmartThingsInterface, line 180
		parseResults << [pictureMode: pictureMode] // library marker davegut.SmartThingsInterface, line 181

		String soundMode = mainData["custom.soundmode"].soundMode.value // library marker davegut.SmartThingsInterface, line 183
		sendEvent(name: "soundMode",value: soundMode) // library marker davegut.SmartThingsInterface, line 184
		parseResults << [soundMode: soundMode] // library marker davegut.SmartThingsInterface, line 185
	} // library marker davegut.SmartThingsInterface, line 186
	logDebug(logData) // library marker davegut.SmartThingsInterface, line 187
} // library marker davegut.SmartThingsInterface, line 188

private asyncGet(sendData, passData = "none") { // library marker davegut.SmartThingsInterface, line 190
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.SmartThingsInterface, line 191
		logWarn("asyncGet: [status: ERROR, errorMsg: no stApiKey]") // library marker davegut.SmartThingsInterface, line 192
	} else { // library marker davegut.SmartThingsInterface, line 193
		logDebug("asyncGet: ${sendData}, ${passData}") // library marker davegut.SmartThingsInterface, line 194
		def sendCmdParams = [ // library marker davegut.SmartThingsInterface, line 195
			uri: "https://api.smartthings.com/v1", // library marker davegut.SmartThingsInterface, line 196
			path: sendData.path, // library marker davegut.SmartThingsInterface, line 197
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()]] // library marker davegut.SmartThingsInterface, line 198
		try { // library marker davegut.SmartThingsInterface, line 199
			asynchttpGet(sendData.parse, sendCmdParams, [reason: passData]) // library marker davegut.SmartThingsInterface, line 200
		} catch (error) { // library marker davegut.SmartThingsInterface, line 201
			logWarn("asyncGet: [status: FAILED, errorMsg: ${error}]") // library marker davegut.SmartThingsInterface, line 202
		} // library marker davegut.SmartThingsInterface, line 203
	} // library marker davegut.SmartThingsInterface, line 204
} // library marker davegut.SmartThingsInterface, line 205

private syncGet(path){ // library marker davegut.SmartThingsInterface, line 207
	def respData = [:] // library marker davegut.SmartThingsInterface, line 208
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.SmartThingsInterface, line 209
		respData << [status: "FAILED", // library marker davegut.SmartThingsInterface, line 210
					 errorMsg: "No stApiKey"] // library marker davegut.SmartThingsInterface, line 211
	} else { // library marker davegut.SmartThingsInterface, line 212
		logDebug("syncGet: ${sendData}") // library marker davegut.SmartThingsInterface, line 213
		def sendCmdParams = [ // library marker davegut.SmartThingsInterface, line 214
			uri: "https://api.smartthings.com/v1", // library marker davegut.SmartThingsInterface, line 215
			path: path, // library marker davegut.SmartThingsInterface, line 216
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()] // library marker davegut.SmartThingsInterface, line 217
		] // library marker davegut.SmartThingsInterface, line 218
		try { // library marker davegut.SmartThingsInterface, line 219
			httpGet(sendCmdParams) {resp -> // library marker davegut.SmartThingsInterface, line 220
				if (resp.status == 200 && resp.data != null) { // library marker davegut.SmartThingsInterface, line 221
					respData << [status: "OK", results: resp.data] // library marker davegut.SmartThingsInterface, line 222
				} else { // library marker davegut.SmartThingsInterface, line 223
					respData << [status: "FAILED", // library marker davegut.SmartThingsInterface, line 224
								 httpCode: resp.status, // library marker davegut.SmartThingsInterface, line 225
								 errorMsg: resp.errorMessage] // library marker davegut.SmartThingsInterface, line 226
				} // library marker davegut.SmartThingsInterface, line 227
			} // library marker davegut.SmartThingsInterface, line 228
		} catch (error) { // library marker davegut.SmartThingsInterface, line 229
			respData << [status: "FAILED", // library marker davegut.SmartThingsInterface, line 230
						 errorMsg: error] // library marker davegut.SmartThingsInterface, line 231
		} // library marker davegut.SmartThingsInterface, line 232
	} // library marker davegut.SmartThingsInterface, line 233
	return respData // library marker davegut.SmartThingsInterface, line 234
} // library marker davegut.SmartThingsInterface, line 235

private syncPost(sendData){ // library marker davegut.SmartThingsInterface, line 237
	def respData = [:] // library marker davegut.SmartThingsInterface, line 238
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.SmartThingsInterface, line 239
		respData << [status: "FAILED", // library marker davegut.SmartThingsInterface, line 240
					 errorMsg: "No stApiKey"] // library marker davegut.SmartThingsInterface, line 241
	} else { // library marker davegut.SmartThingsInterface, line 242
		logDebug("syncPost: ${sendData}") // library marker davegut.SmartThingsInterface, line 243
		def cmdBody = [commands: [sendData.cmdData]] // library marker davegut.SmartThingsInterface, line 244
		def sendCmdParams = [ // library marker davegut.SmartThingsInterface, line 245
			uri: "https://api.smartthings.com/v1", // library marker davegut.SmartThingsInterface, line 246
			path: sendData.path, // library marker davegut.SmartThingsInterface, line 247
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()], // library marker davegut.SmartThingsInterface, line 248
			body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.SmartThingsInterface, line 249
		] // library marker davegut.SmartThingsInterface, line 250
		try { // library marker davegut.SmartThingsInterface, line 251
			httpPost(sendCmdParams) {resp -> // library marker davegut.SmartThingsInterface, line 252
				if (resp.status == 200 && resp.data != null) { // library marker davegut.SmartThingsInterface, line 253
					respData << [status: "OK", results: resp.data.results] // library marker davegut.SmartThingsInterface, line 254
				} else { // library marker davegut.SmartThingsInterface, line 255
					respData << [status: "FAILED", // library marker davegut.SmartThingsInterface, line 256
								 httpCode: resp.status, // library marker davegut.SmartThingsInterface, line 257
								 errorMsg: resp.errorMessage] // library marker davegut.SmartThingsInterface, line 258
				} // library marker davegut.SmartThingsInterface, line 259
			} // library marker davegut.SmartThingsInterface, line 260
		} catch (error) { // library marker davegut.SmartThingsInterface, line 261
			respData << [status: "FAILED", // library marker davegut.SmartThingsInterface, line 262
						 errorMsg: error] // library marker davegut.SmartThingsInterface, line 263
		} // library marker davegut.SmartThingsInterface, line 264
	} // library marker davegut.SmartThingsInterface, line 265
	return respData // library marker davegut.SmartThingsInterface, line 266
} // library marker davegut.SmartThingsInterface, line 267

def distResp(resp, data) { // library marker davegut.SmartThingsInterface, line 269
	def resplog = [:] // library marker davegut.SmartThingsInterface, line 270
	if (resp.status == 200) { // library marker davegut.SmartThingsInterface, line 271
		try { // library marker davegut.SmartThingsInterface, line 272
			def respData = new JsonSlurper().parseText(resp.data) // library marker davegut.SmartThingsInterface, line 273
			if (data.reason == "deviceSetup") { // library marker davegut.SmartThingsInterface, line 274
				deviceSetupParse(respData.components.main) // library marker davegut.SmartThingsInterface, line 275
				runIn(1, statusParse, [data: respData.components.main]) // library marker davegut.SmartThingsInterface, line 276
			} else { // library marker davegut.SmartThingsInterface, line 277
				statusParse(respData.components.main) // library marker davegut.SmartThingsInterface, line 278
			} // library marker davegut.SmartThingsInterface, line 279
		} catch (err) { // library marker davegut.SmartThingsInterface, line 280
			resplog << [status: "ERROR", // library marker davegut.SmartThingsInterface, line 281
						errorMsg: err, // library marker davegut.SmartThingsInterface, line 282
						respData: resp.data] // library marker davegut.SmartThingsInterface, line 283
		} // library marker davegut.SmartThingsInterface, line 284
	} else { // library marker davegut.SmartThingsInterface, line 285
		resplog << [status: "ERROR", // library marker davegut.SmartThingsInterface, line 286
					httpCode: resp.status, // library marker davegut.SmartThingsInterface, line 287
					errorMsg: resp.errorMessage] // library marker davegut.SmartThingsInterface, line 288
	} // library marker davegut.SmartThingsInterface, line 289
	if (resplog != [:]) { // library marker davegut.SmartThingsInterface, line 290
		logWarn("distResp: ${resplog}") // library marker davegut.SmartThingsInterface, line 291
	} // library marker davegut.SmartThingsInterface, line 292
} // library marker davegut.SmartThingsInterface, line 293

// ~~~~~ end include (91) davegut.SmartThingsInterface ~~~~~

// ~~~~~ start include (90) davegut.samsungTvST ~~~~~
library ( // library marker davegut.samsungTvST, line 1
	name: "samsungTvST", // library marker davegut.samsungTvST, line 2
	namespace: "davegut", // library marker davegut.samsungTvST, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungTvST, line 4
	description: "Samsung TV SmartThings Capabilities", // library marker davegut.samsungTvST, line 5
	category: "utilities", // library marker davegut.samsungTvST, line 6
	documentationLink: "" // library marker davegut.samsungTvST, line 7
) // library marker davegut.samsungTvST, line 8

command "toggleSoundMode", [[name: "SmartThings Function"]] // library marker davegut.samsungTvST, line 10
command "togglePictureMode", [[name: "SmartThings Function"]] // library marker davegut.samsungTvST, line 11
command "sourceSetST", ["SmartThings Function"] // library marker davegut.samsungTvST, line 12
attribute "inputSource", "string" // library marker davegut.samsungTvST, line 13
command "setVolume", ["SmartThings Function"] // library marker davegut.samsungTvST, line 14
command "setPictureMode", ["SmartThings Function"] // library marker davegut.samsungTvST, line 15
command "setSoundMode", ["SmartThings Function"] // library marker davegut.samsungTvST, line 16
command "setLevel", ["SmartThings Function"] // library marker davegut.samsungTvST, line 17
attribute "level", "NUMBER" // library marker davegut.samsungTvST, line 18

def deviceRefresh() { // library marker davegut.samsungTvST, line 20
	if (connectST && stApiKey!= null) { // library marker davegut.samsungTvST, line 21
		def cmdData = [ // library marker davegut.samsungTvST, line 22
			component: "main", // library marker davegut.samsungTvST, line 23
			capability: "refresh", // library marker davegut.samsungTvST, line 24
			command: "refresh", // library marker davegut.samsungTvST, line 25
			arguments: []] // library marker davegut.samsungTvST, line 26
		deviceCommand(cmdData) // library marker davegut.samsungTvST, line 27
	} // library marker davegut.samsungTvST, line 28
} // library marker davegut.samsungTvST, line 29

def poll() { // library marker davegut.samsungTvST, line 31
	if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.samsungTvST, line 32
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.samsungTvST, line 33
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.samsungTvST, line 34
	} else { // library marker davegut.samsungTvST, line 35
		def sendData = [ // library marker davegut.samsungTvST, line 36
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.samsungTvST, line 37
			parse: "distResp" // library marker davegut.samsungTvST, line 38
			] // library marker davegut.samsungTvST, line 39
		asyncGet(sendData, "statusParse") // library marker davegut.samsungTvST, line 40
	} // library marker davegut.samsungTvST, line 41
} // library marker davegut.samsungTvST, line 42

def setLevel(level) { setVolume(level) } // library marker davegut.samsungTvST, line 44

def setVolume(volume) { // library marker davegut.samsungTvST, line 46
	def cmdData = [ // library marker davegut.samsungTvST, line 47
		component: "main", // library marker davegut.samsungTvST, line 48
		capability: "audioVolume", // library marker davegut.samsungTvST, line 49
		command: "setVolume", // library marker davegut.samsungTvST, line 50
		arguments: [volume.toInteger()]] // library marker davegut.samsungTvST, line 51
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 52
} // library marker davegut.samsungTvST, line 53

def togglePictureMode() { // library marker davegut.samsungTvST, line 55
	//	requires state.pictureModes // library marker davegut.samsungTvST, line 56
	def pictureModes = state.pictureModes // library marker davegut.samsungTvST, line 57
	def totalModes = pictureModes.size() // library marker davegut.samsungTvST, line 58
	def currentMode = device.currentValue("pictureMode") // library marker davegut.samsungTvST, line 59
	def modeNo = pictureModes.indexOf(currentMode) // library marker davegut.samsungTvST, line 60
	def newModeNo = modeNo + 1 // library marker davegut.samsungTvST, line 61
	if (newModeNo == totalModes) { newModeNo = 0 } // library marker davegut.samsungTvST, line 62
	def newPictureMode = pictureModes[newModeNo] // library marker davegut.samsungTvST, line 63
	setPictureMode(newPictureMode) // library marker davegut.samsungTvST, line 64
} // library marker davegut.samsungTvST, line 65

def setPictureMode(pictureMode) { // library marker davegut.samsungTvST, line 67
	def cmdData = [ // library marker davegut.samsungTvST, line 68
		component: "main", // library marker davegut.samsungTvST, line 69
		capability: "custom.picturemode", // library marker davegut.samsungTvST, line 70
		command: "setPictureMode", // library marker davegut.samsungTvST, line 71
		arguments: [pictureMode]] // library marker davegut.samsungTvST, line 72
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 73
} // library marker davegut.samsungTvST, line 74

def toggleSoundMode() { // library marker davegut.samsungTvST, line 76
	def soundModes = state.soundModes // library marker davegut.samsungTvST, line 77
	def totalModes = soundModes.size() // library marker davegut.samsungTvST, line 78
	def currentMode = device.currentValue("soundMode") // library marker davegut.samsungTvST, line 79
	def modeNo = soundModes.indexOf(currentMode) // library marker davegut.samsungTvST, line 80
	def newModeNo = modeNo + 1 // library marker davegut.samsungTvST, line 81
	if (newModeNo == totalModes) { newModeNo = 0 } // library marker davegut.samsungTvST, line 82
	def soundMode = soundModes[newModeNo] // library marker davegut.samsungTvST, line 83
	setSoundMode(soundMode) // library marker davegut.samsungTvST, line 84
} // library marker davegut.samsungTvST, line 85

def setSoundMode(soundMode) {  // library marker davegut.samsungTvST, line 87
	def cmdData = [ // library marker davegut.samsungTvST, line 88
		component: "main", // library marker davegut.samsungTvST, line 89
		capability: "custom.soundmode", // library marker davegut.samsungTvST, line 90
		command: "setSoundMode", // library marker davegut.samsungTvST, line 91
		arguments: [soundMode]] // library marker davegut.samsungTvST, line 92
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 93
} // library marker davegut.samsungTvST, line 94

def toggleInputSource() { sourceToggle() } // library marker davegut.samsungTvST, line 96

def setInputSource(inputSource) { sourceSetST(inputSource) } // library marker davegut.samsungTvST, line 98
def sourceSetST(inputSource) { // library marker davegut.samsungTvST, line 99
	def cmdData = [ // library marker davegut.samsungTvST, line 100
		component: "main", // library marker davegut.samsungTvST, line 101
		capability: "mediaInputSource", // library marker davegut.samsungTvST, line 102
		command: "setInputSource", // library marker davegut.samsungTvST, line 103
		arguments: [inputSource]] // library marker davegut.samsungTvST, line 104
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 105
} // library marker davegut.samsungTvST, line 106

def setTvChannel(newChannel) { // library marker davegut.samsungTvST, line 108
	def cmdData = [ // library marker davegut.samsungTvST, line 109
		component: "main", // library marker davegut.samsungTvST, line 110
		capability: "tvChannel", // library marker davegut.samsungTvST, line 111
		command: "setTvChannel", // library marker davegut.samsungTvST, line 112
		arguments: [newChannel]] // library marker davegut.samsungTvST, line 113
	deviceCommand(cmdData) // library marker davegut.samsungTvST, line 114
} // library marker davegut.samsungTvST, line 115

// ~~~~~ end include (90) davegut.samsungTvST ~~~~~

// ~~~~~ start include (79) davegut.Logging ~~~~~
library ( // library marker davegut.Logging, line 1
	name: "Logging", // library marker davegut.Logging, line 2
	namespace: "davegut", // library marker davegut.Logging, line 3
	author: "Dave Gutheinz", // library marker davegut.Logging, line 4
	description: "Common Logging and info gathering Methods", // library marker davegut.Logging, line 5
	category: "utilities", // library marker davegut.Logging, line 6
	documentationLink: "" // library marker davegut.Logging, line 7
) // library marker davegut.Logging, line 8

def nameSpace() { return "davegut" } // library marker davegut.Logging, line 10

def version() { return "2.3.9a" } // library marker davegut.Logging, line 12

def label() { // library marker davegut.Logging, line 14
	if (device) {  // library marker davegut.Logging, line 15
		return device.displayName + "-${version()}" // library marker davegut.Logging, line 16
	} else {  // library marker davegut.Logging, line 17
		return app.getLabel() + "-${version()}" // library marker davegut.Logging, line 18
	} // library marker davegut.Logging, line 19
} // library marker davegut.Logging, line 20

def listAttributes() { // library marker davegut.Logging, line 22
	def attrData = device.getCurrentStates() // library marker davegut.Logging, line 23
	Map attrs = [:] // library marker davegut.Logging, line 24
	attrData.each { // library marker davegut.Logging, line 25
		attrs << ["${it.name}": it.value] // library marker davegut.Logging, line 26
	} // library marker davegut.Logging, line 27
	return attrs // library marker davegut.Logging, line 28
} // library marker davegut.Logging, line 29

def setLogsOff() { // library marker davegut.Logging, line 31
	def logData = [logEnable: logEnable] // library marker davegut.Logging, line 32
	if (logEnable) { // library marker davegut.Logging, line 33
		runIn(1800, debugLogOff) // library marker davegut.Logging, line 34
		logData << [debugLogOff: "scheduled"] // library marker davegut.Logging, line 35
	} // library marker davegut.Logging, line 36
	return logData // library marker davegut.Logging, line 37
} // library marker davegut.Logging, line 38

def logTrace(msg){ log.trace "${label()}: ${msg}" } // library marker davegut.Logging, line 40

def logInfo(msg) {  // library marker davegut.Logging, line 42
	if (infoLog) { log.info "${label()}: ${msg}" } // library marker davegut.Logging, line 43
} // library marker davegut.Logging, line 44

def debugLogOff() { // library marker davegut.Logging, line 46
	if (device) { // library marker davegut.Logging, line 47
		device.updateSetting("logEnable", [type:"bool", value: false]) // library marker davegut.Logging, line 48
	} else { // library marker davegut.Logging, line 49
		app.updateSetting("logEnable", false) // library marker davegut.Logging, line 50
	} // library marker davegut.Logging, line 51
	logInfo("debugLogOff") // library marker davegut.Logging, line 52
} // library marker davegut.Logging, line 53

def logDebug(msg) { // library marker davegut.Logging, line 55
	if (logEnable) { log.debug "${label()}: ${msg}" } // library marker davegut.Logging, line 56
} // library marker davegut.Logging, line 57

def logWarn(msg) { log.warn "${label()}: ${msg}" } // library marker davegut.Logging, line 59

def logError(msg) { log.error "${label()}: ${msg}" } // library marker davegut.Logging, line 61

// ~~~~~ end include (79) davegut.Logging ~~~~~
