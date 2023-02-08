/*	HubiThings Replica Soundbar Driver
	HubiThings Replica Applications Copyright 2023 by Bloodtick
	Replica Color Bulb Copyright 2023 by Dave Gutheinz

	Licensed under the Apache License, Version 2.0 (the "License"); 
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at:
	      http://www.apache.org/licenses/LICENSE-2.0
	Unless required by applicable law or agreed to in writing, software 
	distributed under the License is distributed on an "AS IS" BASIS, 
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or 
	implied. See the License for the specific language governing 
	permissions and limitations under the License.

Sample Audio Notifications Streams are at the bottom of this driver.

Issues with this driver: Contact davegut via Private Message on the
Hubitat Community site: https://community.hubitat.com/
==========================================================================*/
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
def driverVer() { return "1.0" }

metadata {
	definition (name: "Replica Samsung TV",
				namespace: "replica",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/HubiThingsReplica%20Drivers/Replica_Samsung_TV.groovy"
			   ){
		capability "Actuator"
		capability "Configuration"
		capability "Refresh"
		attribute "healthStatus", "enum", ["offline", "online"]
		capability "Switch"
		capability "SamsungTV"
			command "showMessage", [[name: "Not Implemented"]]
			command "toggleSoundMode"
			command "togglePictureMode"
		command "setLevel", ["number"]
			attribute "level", "NUMBER"
		capability "MediaInputSource"	//	new
			command "toggleInputSource"
			attribute "inputSource", "string"
		capability "MediaTransport"
			command "fastForward"
			command "rewind"
		capability "Configuration"
		//	TV Channel/data
		command "setTvChannel", ["number"]
			attribute "tvChannel", "string"
			attribute "tvChannelName", "string"
			attribute "trackDescription", "string"
			command "channelUp"
			command "channelDown"
			command "channelList"
		//	Websocket Keys
		attribute "wsStatus", "string"
		command "sendKey", ["string"]
		//	Art / Ambient	
		command "artMode"
			attribute "artModeStatus", "string"
		command "ambientMode"
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
		//	Navigation Commands
		command "exit"
		command "Return"
		//	Application Functions
		command "appOpenByName", ["string"]
		command "appOpenByCode", ["string"]
			attribute "currentApp", "string"
		//	Dashboard Support
		capability "PushableButton"
		capability "Variable"
		command "eventHandler", [[name: "For App Use Only"]]
		
		//	===== Audio Notification Function =====
		capability "AudioNotification"
		//	Test Phase Only
		command "testAudioNotify"
	}
	preferences {
		input ("deviceIp", "string", title: "Device IP. For Local Notification.")
		if (deviceIp) {
			input ("tvPwrOnMode", "enum", title: "TV Startup Display", 
				   options: ["ART_MODE", "Ambient", "Tv", "HDMI1", 
							 "HDMI2", "HDMI3", "HDMI4", "none"], defaultValue: "none")
			input ("findAppCodes", "bool", title: "Scan for App Codes (use rarely)", defaultValue: false)
			input ("resetAppCodes", "bool", title: "Delete and Rescan for App Codes (use rarely)", defaultValue: false)
			input ("altTts", "bool", title: "Use Alternate TTS Method", defalutValue: false)
		}
		if (altTts) {
			input ("ttsApiKey", "string", title: "TTS Site Key", defaultValue: null,
				   description: "From http://www.voicerss.org/registration.aspx")
			input ("ttsLang", "enum", title: "TTS Language", options: ttsLanguages(), defaultValue: "en-us")
		}
		input ("logEnable", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false)
		input ("infoLog", "bool", title: "Enable information logging",defaultValue: true)
		input ("traceLog", "bool", title: "Enable trace logging as directed by developer", defaultValue: false)
	}
}

//	===== Installation, setup and update =====
def installed() {
	state.token = "12345678"
	def tokenSupport = "false"
	sendEvent(name: "wsStatus", value: "closed")
	sendEvent(name: "numberOfButtone", value: 45)
	sendEvent(name: "currentApp", value: " ")
	state.appData = [:]
	runIn(1, updated)
}

def updated() {
	unschedule()
	def updStatus = [:]
	initialize()
	if (!deviceIp) {
		logWarn("\n\n\t\t<b>Enter the deviceIp and Save Preferences</b>\n\n")
		updStatus << [status: "ERROR", data: "Device IP not set."]
	} else {
		updStatus << [getDeviceData: configureLan()]
		if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
			updateDataValue("driverVersion", driverVer())
			updStatus << [driverVer: driverVer()]
		}
		if (resetAppCodes) {
			state.appData = [:]
			runIn(5, updateAppCodes)
		} else if (findAppCodes) {
			runIn(5, updateAppCodes)
		}
		clearQueue()
		runEvery15Minutes(kickStartQueue)
		//	set state to gather initial attributes on startup
	}
	if (logEnable) { runIn(1800, debugLogOff) }
	if (traceLog) { runIn(600, traceLogOff) }
	updStatus << [logEnable: logEnable, infoLog: infoLog, traceLog: traceLog]
	
	runIn(10, refresh)
	pauseExecution(5000)
	listAttributes(true)
	logInfo("updated: ${updStatus}")
}

def initialize() {
    updateDataValue("triggers", groovy.json.JsonOutput.toJson(getReplicaTriggers()))
    updateDataValue("commands", groovy.json.JsonOutput.toJson(getReplicaCommands()))
	logInfo("initialize: initialize device-specific data")
}

Map getReplicaCommands() {
    return (["replicaEvent":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]], 
			 "replicaStatus":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]], 
			 "replicaHealth":[[name:"parent*",type:"OBJECT"],[name:"health*",type:"JSON_OBJECT"]],
			 "setHealthStatusValue":[[name:"healthStatus*",type:"ENUM"]]])
}

Map getReplicaTriggers() {
	Map triggers = [ 
		refresh:[], deviceRefresh: [],
		setVolume: [[name:"volume*", type: "NUMBER"]], 
		setMute: [[name:"state*", type: "string"]],
		setPictureMode:[[name:"mode*", type:"string"]], 
		setInputSource:[[name:"inputName*", type: "string"]],
		setSoundMode:[[name:"mode*", type:"string"]],
		setTvChannel: [[name:"tvChannel*", type: "number"]]
	]
	return triggers
}

def configure() {
    initialize()
    updateDataValue("rules", getReplicaRules())
    sendCommand("configure")
	logInfo "configure (default device data)"
}

String getReplicaRules() {
	return """ {"version":1,"components":[{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger","mute":true},{"trigger":{"name":"setVolume","label":"command: setVolume(volume*)","type":"command","parameters":[{"name":"volume*","type":"NUMBER"}]},"command":{"name":"setVolume","arguments":[{"name":"volume","optional":false,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioVolume","label":"command: setVolume(volume*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setMute","label":"command: setMute(state*)","type":"command","parameters":[{"name":"state*","type":"string"}]},"command":{"name":"setMute","arguments":[{"name":"state","optional":false,"schema":{"title":"MuteState","type":"string","enum":["muted","unmuted"]}}],"type":"command","capability":"audioMute","label":"command: setMute(state*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setPictureMode","label":"command: setPictureMode(mode*)","type":"command","parameters":[{"name":"mode*","type":"string"}]},"command":{"name":"setPictureMode","arguments":[{"name":"mode","optional":false,"schema":{"type":"string"}}],"type":"command","capability":"custom.picturemode","label":"command: setPictureMode(mode*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setSoundMode","label":"command: setSoundMode(mode*)","type":"command","parameters":[{"name":"mode*","type":"string"}]},"command":{"name":"setSoundMode","arguments":[{"name":"mode","optional":false,"schema":{"type":"string"}}],"type":"command","capability":"custom.soundmode","label":"command: setSoundMode(mode*)"},"type":"hubitatTrigger"},{"trigger":{"name":"refresh","label":"command: refresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},{"trigger":{"name":"setInputSource","label":"command: setInputSource(inputName*)","type":"command","parameters":[{"name":"inputName*","type":"string"}]},"command":{"name":"setInputSource","arguments":[{"name":"id","optional":false,"schema":{"type":"string"}}],"type":"command","capability":"samsungvd.mediaInputSource","label":"command: setInputSource(id*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setTvChannel","label":"command: setTvChannel(tvChannel*)","type":"command","parameters":[{"name":"tvChannel*","type":"number"}]},"command":{"name":"setTvChannel","arguments":[{"name":"tvChannel","optional":false,"schema":{"title":"String","type":"string","maxLength":255}}],"type":"command","capability":"tvChannel","label":"command: setTvChannel(tvChannel*)"},"type":"hubitatTrigger"},{"trigger":{"name":"deviceRefresh","label":"command: deviceRefresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"}]}"""
}

def configureLan() {
	def respData = [:]
	def tvData = [:]
	try{
		httpGet([uri: "http://${deviceIp}:8001/api/v2/", timeout: 5]) { resp ->
			tvData = resp.data
		}
	} catch (error) {
		tvData << [status: "error", data: error]
	}
	if (!tvData.status) {
		def wolMac = tvData.device.wifiMac.replaceAll(":", "").toUpperCase()
		updateDataValue("wolMac", wolMac)
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
		respData << [status: "OK", wolMac: wolMac, frameTv: frameTv, 
					 tokenSupport: tokenSupport, uuid: uuid]
		sendEvent(name: "artModeStatus", value: "none")
		if (frameTv == "true") {
			def data = [request:"get_artmode_status",
						id: uuid]
			data = JsonOutput.toJson(data)
			artModeCmd(data)
		}
	} else {
		respData << tvData
	}
	return respData
}

//	===== Event Parse Interface s=====
void replicaStatus(def parent=null, Map status=null) {
	def logData = [parent: parent, status: status]
	if (state.refreshAttributes) {
		refreshAttributes(status.components.main)
	}
	logTrace("replicaStatus: ${logData}")
}

def refreshAttributes(mainData) {
	logDebug("setInitialAttributes: ${mainData}")
	def value
	
	value = mainData["custom.picturemode"].supportedPictureModes.value
	parse_main([attribute: "supportedPictureModes", value: value])
	pauseExecution(200)
	
	value = mainData["custom.soundmode"].supportedSoundModes.value
	parse_main([attribute: "supportedSoundModes", value: value])
	pauseExecution(200)

	value = mainData.mediaInputSource.supportedInputSources.value
	parse_main([attribute: "supportedInputSources", value: value])
	pauseExecution(200)
	
	parse_main([attribute: "switch", value: mainData.switch.switch.value])
	pauseExecution(200)

	value = mainData.audioVolume.volume.value.toInteger()
	parse_main([attribute: "volume", value: value, unit: "%"])
	pauseExecution(200)
	
	parse_main([attribute: "mute", value: mainData.audioMute.mute.value])
	pauseExecution(200)

	parse_main([attribute: "pictureMode", value: mainData["custom.picturemode"].pictureMode.value])
	pauseExecution(200)
		
	parse_main([attribute: "soundMode", value: mainData["custom.soundmode"].soundMode.value])
	pauseExecution(200)
		
	parse_main([attribute: "mediaInputSource", value: mainData.mediaInputSource.inputSource.value])
}

void replicaHealth(def parent=null, Map health=null) {
	if(parent) { logInfo("replicaHealth: ${parent?.getLabel()}") }
	if(health) { logInfo("replicaHealth: ${health}") }
}

def setHealthStatusValue(value) {    
    sendEvent(name: "healthStatus", value: value, descriptionText: "${device.displayName} healthStatus set to $value")
}

void replicaEvent(def parent=null, Map event=null) {
	def eventData = event.deviceEvent
	try {
		"parse_${event.deviceEvent.componentId}"(event.deviceEvent)
	} catch (err) {
		logWarn("replicaEvent: [event = ${event}, error: ${err}")
	}
}

def parse_main(event) {
	logInfo("parse_main: <b>[attribute: ${event.attribute}, value: ${event.value}, unit: ${event.unit}]</b>")
	switch(event.attribute) {
		case "mute":
		case "pictureMode":
		case "soundMode":
		case "tvChannel":
			sendEvent(name: event.attribute, value: event.value)
			break
		case "switch":
			sendEvent(name: "switch", value: event.value)
			if (event.value == "on") {
				runIn(3, setPowerOnMode)
			}
			break
		case "volume":
			sendEvent(name: "volume", value: event.value, unit: "%")
			sendEvent(name: "level", value: event.value, unit: "%")
			break
		case "inputSource":
			if (event.capability == "mediaInputSource") {
				sendEvent(name: "mediaInputSource", value: event.value)
			}
			break
		case "tvChannelName":
			sendEvent(name: "tvChannelName", value: event.value)
			sendEvent(name: "trackDescription", value: event.value)
			if (event.value.contains(".")) {
				getAppData(event.value)
			}
			break
		case "playbackStatus":
			sendEvent(name: "transportStatus", value: event.value)
			break
		case "supportedSoundModes":
			state.soundModes = event.value
			break
		case "supportedInputSources":
			state.inputSources = event.value
			break
		case "supportedPictureModes":
			state.pictureModes = event.value
			break
		case "supportedSoundModesMap":
		case "supportedInputSourcesMap":
		case "supportedPictureModes":
			break
		default:
			logInfo("parse_main: [unhandledEvent: ${event}]")
			break
	}
	logTrace("parse_main: [event: ${event}]")
}

//	Used for any rule-based commands
private def sendCommand(String name, def value=null, String unit=null, data=[:]) {
    parent?.deviceTriggerHandler(device, [name:name, value:value, unit:unit, data:data, now:now])
}

//	===== Samsung TV Commands =====
def refresh() {
	state.refreshAttributes = true
	sendCommand("deviceRefresh")
	pauseExecution(500)
	sendCommand("refresh")
}

def deviceRefresh() {
	sendCommand("deviceRefresh")
}

def on() {
	def wolMac = getDataValue("wolMac")
	def cmd = "FFFFFFFFFFFF$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac"
	wol = new hubitat.device.HubAction(
		cmd,
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "255.255.255.255:7",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING])
	sendHubCommand(wol)
}

def off() {
	logInfo("off: [frameTv: ${getDataValue("frameTv")}]")
	if (getDataValue("frameTv") == "true") {
		sendKey("POWER", "Press")
		pauseExecution(4000)
		sendKey("POWER", "Release")
	} else {
		sendKey("POWER")
	}
}

def setPowerOnMode() {
	logDebug("setPowerOnMode: [tvPwrOnMode: ${tvPwrOnMode}]")
	switch (tvPwrOnMode) {
		case "ART_MODE":
			setInputSource("Tv")
			pauseExecution(1000)
			artMode()
			break
		case "Ambient":
			setInputSource("Tv")
			pauseExecution(1000)
			ambientMode()
			break
		case "Tv":
		case "HDMI1":
		case "HDMI2":
		case "HDMI3":
		case "HDMI4":
			setInputSource(tvPwrOnMode)
			break
		default:
			break
	}
}

//	===== capability "SamsungTV"===== 
def setLevel(level) { setVolume(level) }

def volumeUp() {
	sendKey("VOLUP")
	runIn(5, deviceRefresh)
}

def volumeDown() {
	sendKey("VOLDOWN")
	runIn(5, deviceRefresh)
}

def setVolume(volume) {
	if (volume == null) { volume = device.currentValue("volume").toInteger() }
	if (volume < 0) { volume = 0 }
	else if (volume > 100) { volume = 100 }
	sendCommand("setVolume", volume)
	runIn(5, deviceRefresh)
}

def mute() {
	sendKey("MUTE")
	runIn(5, deviceRefresh)
}

def unmute() {
	sendKey("MUTE")
	runIn(5, deviceRefresh)
}

def showMessage() { logWarn("showMessage: not implemented") }

def togglePictureMode() {
	def pictureModes = state.pictureModes
	if (pictureModes != null) {
		def totalModes = pictureModes.size()
		def currentMode = device.currentValue("pictureMode")
		def modeNo = pictureModes.indexOf(currentMode)
		def newModeNo = modeNo + 1
		if (newModeNo == totalModes) { newModeNo = 0 }
		def newPictureMode = pictureModes[newModeNo]
		setPictureMode(newPictureMode)
	}
}

def setPictureMode(pictureMode) {
	sendCommand("setPictureMode", pictureMode)
	runIn(5, deviceRefresh)
}

def toggleSoundMode() {
	def soundModes = state.soundModes
	if (soundModes != null) {
		def totalModes = soundModes.size()
		def currentMode = device.currentValue("soundMode")
		def modeNo = soundModes.indexOf(currentMode)
		def newModeNo = modeNo + 1
		if (newModeNo == totalModes) { newModeNo = 0 }
		def soundMode = soundModes[newModeNo]
		setSoundMode(soundMode)
	}
}

def setSoundMode(soundMode) { 
	sendCommand("setSoundMode", soundMode)
	runIn(5, deviceRefresh)
}

//	===== capability "MediaInputSource" =====
def toggleInputSource() {
	sendKey("HDMI")
}

def setInputSource(inputSource) {
	if (inputSource.contains(inputSource)) {
		inputSource = state.inputSources.find { it.contains(inputSource) }
	}
	sendCommand("setInputSource", inputSource)
}

//	===== capability "MediaTransport" =====
def play() {
	sendKey("PLAY")
	runIn(5, deviceRefresh)
}

def pause() {
	sendKey("PAUSE")
	runIn(5, deviceRefresh)
}

def stop() {
	sendKey("STOP")
	runIn(5, deviceRefresh)
}

def rewind() {
	sendKey("REWIND")
	runIn(5, deviceRefresh)
}

def fastForward() {
	sendKey("REWIND")
	runIn(5, deviceRefresh)
}

//	===== TV Channel =====
def setTvChannel(tvChannel) {
	sendCommand("setTvChannel", tvChannel.toString())
	runIn(5, deviceRefresh)
}

def channelList() { sendKey("CH_LIST") }

def channelUp() {
	sendKey("CHUP") 
	runIn(5, deviceRefresh)
}

def channelDown() {
	sendKey("CHDOWN") 
	runIn(5, deviceRefresh)
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
		logInfo("artMode: ${logData}")
	}
}

def artModeCmd(data) {
	def cmdData = [method:"ms.channel.emit",
				   params:[data:"${data}",
						   to:"host",
						   event:"art_app_request"]]
	cmdData = JsonOutput.toJson(cmdData)
	sendMessage("frameArt", cmdData)
	runIn(5, deviceRefresh)
}

def ambientMode() {
	sendKey("AMBIENT")
	runIn(5, deviceRefresh)
}

//	== Remote Commands
def exit() {
	sendKey("EXIT")
	runIn(5, deviceRefresh)
}

def Return() { sendKey("RETURN") }

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

//	===== WebSocket Communications / Parse =====
def sendKey(key, cmd = "Click") {
	key = "KEY_${key.toUpperCase()}"
	def data = [method:"ms.remote.control",
				params:[Cmd:"${cmd}",
						DataOfCmd:"${key}",
						TypeOfRemote:"SendRemoteKey"]]
	sendMessage("remote", JsonOutput.toJson(data) )
}

def sendMessage(funct, data) {
	if (!deviceIp || deviceIp == "") {
		logWarn("sendMessage: [status: no deviceIp]")
	} else {
		def wsStat = device.currentValue("wsStatus")
		logDebug("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${state.currentFunction}")
		logTrace("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${state.currentFunction}")
		if (wsStat != "open" || state.currentFunction != funct) {
			connect(funct)
			pauseExecution(600)
		}
		interfaces.webSocket.sendMessage(data)
	}
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

def parseWs(resp) {
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
		upnpParse(resp)
//		logData << [STATUS: "unhandled", ERROR: e]
//		logWarn("parse: ${logData}")
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
			runIn(5, deviceRefresh)
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
			runIn(5, deviceRefresh)
			logData << [status: "OK", data: resp.data]
		}
	} catch (err) {
		logData << [status: "appId Not Found", data: err]
		logWarn("appOpenByCode: ${logData}")
	}
	logDebug("appOpenByCode: ${logData}")
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

//	===== DASHBOARD SUPPORT INTERFACE =====
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



// ~~~~~ start include (1234) davegut.samsungAudioNotify ~~~~~
library ( // library marker davegut.samsungAudioNotify, line 1
	name: "samsungAudioNotify", // library marker davegut.samsungAudioNotify, line 2
	namespace: "davegut", // library marker davegut.samsungAudioNotify, line 3
	author: "Dave Gutheinz", // library marker davegut.samsungAudioNotify, line 4
	description: "Samsung Audio Notify Functions", // library marker davegut.samsungAudioNotify, line 5
	category: "utilities", // library marker davegut.samsungAudioNotify, line 6
	documentationLink: "" // library marker davegut.samsungAudioNotify, line 7
) // library marker davegut.samsungAudioNotify, line 8
import org.json.JSONObject // library marker davegut.samsungAudioNotify, line 9

//	===== Voices.rss TTS Languages ===== // library marker davegut.samsungAudioNotify, line 11
def ttsLanguages() { // library marker davegut.samsungAudioNotify, line 12
	def languages = [ // library marker davegut.samsungAudioNotify, line 13
		"en-au":"English (Australia)","en-ca":"English (Canada)", // library marker davegut.samsungAudioNotify, line 14
		"en-gb":"English (Great Britain)","en-us":"English (United States)", // library marker davegut.samsungAudioNotify, line 15
		"en-in":"English (India)","ca-es":"Catalan","zh-cn":"Chinese (China)",  // library marker davegut.samsungAudioNotify, line 16
		"zh-hk":"Chinese (Hong Kong)","zh-tw":"Chinese (Taiwan)", // library marker davegut.samsungAudioNotify, line 17
		"da-dk":"Danish", "nl-nl":"Dutch","fi-fi":"Finnish", // library marker davegut.samsungAudioNotify, line 18
		"fr-ca":"French (Canada)","fr-fr":"French (France)","de-de":"German", // library marker davegut.samsungAudioNotify, line 19
		"it-it":"Italian","ja-jp":"Japanese","ko-kr":"Korean", // library marker davegut.samsungAudioNotify, line 20
		"nb-no":"Norwegian","pl-pl":"Polish","pt-br":"Portuguese (Brazil)", // library marker davegut.samsungAudioNotify, line 21
		"pt-pt":"Portuguese (Portugal)","ru-ru":"Russian", // library marker davegut.samsungAudioNotify, line 22
		"es-mx":"Spanish (Mexico)","es-es":"Spanish (Spain)", // library marker davegut.samsungAudioNotify, line 23
		"sv-se":"Swedish (Sweden)"] // library marker davegut.samsungAudioNotify, line 24
	return languages // library marker davegut.samsungAudioNotify, line 25
} // library marker davegut.samsungAudioNotify, line 26

//	===== Audio Notification / URL-URI Playback functions // library marker davegut.samsungAudioNotify, line 28
def testAudioNotify() { // library marker davegut.samsungAudioNotify, line 29
	logInfo("testAudioNotify: Testing audio notification interfaces") // library marker davegut.samsungAudioNotify, line 30
	runIn(3, testTextNotify) // library marker davegut.samsungAudioNotify, line 31
	playTrack("""http://s3.amazonaws.com/smartapp-media/sonos/dogs.mp3""", 8) // library marker davegut.samsungAudioNotify, line 32
} // library marker davegut.samsungAudioNotify, line 33
def testTextNotify() { // library marker davegut.samsungAudioNotify, line 34
	playText("This is a test of the Text-to-speech function", 9) // library marker davegut.samsungAudioNotify, line 35
} // library marker davegut.samsungAudioNotify, line 36

def playText(text, volume = null) { // library marker davegut.samsungAudioNotify, line 38
	createTextData(text, volume, true, "playText") // library marker davegut.samsungAudioNotify, line 39
} // library marker davegut.samsungAudioNotify, line 40

def playTextAndRestore(text, volume = null) { // library marker davegut.samsungAudioNotify, line 42
	createTextData(text, volume, false, "playTextAndRestore") // library marker davegut.samsungAudioNotify, line 43
} // library marker davegut.samsungAudioNotify, line 44

def playTextAndResume(text, volume = null) { // library marker davegut.samsungAudioNotify, line 46
	createTextData(text, volume, true, "playTextAndResume") // library marker davegut.samsungAudioNotify, line 47
} // library marker davegut.samsungAudioNotify, line 48

def createTextData(text, volume, resume, method) { // library marker davegut.samsungAudioNotify, line 50
	if (volume == null) { volume = device.currentValue("volume") } // library marker davegut.samsungAudioNotify, line 51
	def logData = [method: method, text: text, volume: volume, resume: resume] // library marker davegut.samsungAudioNotify, line 52
	def trackUri // library marker davegut.samsungAudioNotify, line 53
	def duration // library marker davegut.samsungAudioNotify, line 54
	text = "<s>     <s>${text}." // library marker davegut.samsungAudioNotify, line 55
	if (altTts) { // library marker davegut.samsungAudioNotify, line 56
		if (ttsApiKey == null) { // library marker davegut.samsungAudioNotify, line 57
			logWarn("convertToTrack: [FAILED: No ttsApiKey]") // library marker davegut.samsungAudioNotify, line 58
		} else { // library marker davegut.samsungAudioNotify, line 59
			def uriText = URLEncoder.encode(text, "UTF-8").replaceAll(/\+/, "%20") // library marker davegut.samsungAudioNotify, line 60
			trackUri = "http://api.voicerss.org/?" + // library marker davegut.samsungAudioNotify, line 61
				"key=${ttsApiKey.trim()}" + // library marker davegut.samsungAudioNotify, line 62
				"&c=MP3" + // library marker davegut.samsungAudioNotify, line 63
				"&hl=${ttsLang}" + // library marker davegut.samsungAudioNotify, line 64
				"&src=${uriText}" // library marker davegut.samsungAudioNotify, line 65
			duration = (2 + text.length() / 10).toInteger() // library marker davegut.samsungAudioNotify, line 66
			track =  [uri: trackUri, duration: duration] // library marker davegut.samsungAudioNotify, line 67
		} // library marker davegut.samsungAudioNotify, line 68
	} else { // library marker davegut.samsungAudioNotify, line 69
		def track = textToSpeech(text, voice) // library marker davegut.samsungAudioNotify, line 70
		trackUri = track.uri // library marker davegut.samsungAudioNotify, line 71
		duration = track.duration // library marker davegut.samsungAudioNotify, line 72
	} // library marker davegut.samsungAudioNotify, line 73
	logData << [trackUri: trackUri, duration: duration] // library marker davegut.samsungAudioNotify, line 74
//	addToQueue(trackUri, duration, volume, resume) // library marker davegut.samsungAudioNotify, line 75
	addToQueue(trackUri, duration, volume) // library marker davegut.samsungAudioNotify, line 76
	logInfo("createTextData: ${logData}") // library marker davegut.samsungAudioNotify, line 77
} // library marker davegut.samsungAudioNotify, line 78

def playTrack(trackData, volume = null) { // library marker davegut.samsungAudioNotify, line 80
	createPlayData(trackData, volume, true, "playTrack") // library marker davegut.samsungAudioNotify, line 81
} // library marker davegut.samsungAudioNotify, line 82

def playTrackAndRestore(trackData, volume=null) { // library marker davegut.samsungAudioNotify, line 84
	createPlayData(trackData, volume, false, "playTrackAndRestore") // library marker davegut.samsungAudioNotify, line 85
} // library marker davegut.samsungAudioNotify, line 86

def playTrackAndResume(trackData, volume=null) { // library marker davegut.samsungAudioNotify, line 88
	createPlayData(trackData, volume, true, "playTrackAndResume") // library marker davegut.samsungAudioNotify, line 89
} // library marker davegut.samsungAudioNotify, line 90

def createPlayData(trackData, volume, resume, method) { // library marker davegut.samsungAudioNotify, line 92
	if (volume == null) { volume = device.currentValue("volume") } // library marker davegut.samsungAudioNotify, line 93
	def logData = [method: method, trackData: text, volume: volume, resume: resume] // library marker davegut.samsungAudioNotify, line 94
	def trackUri // library marker davegut.samsungAudioNotify, line 95
	def duration // library marker davegut.samsungAudioNotify, line 96
	if (trackData[0] == "[") { // library marker davegut.samsungAudioNotify, line 97
		logData << [status: "aborted", reason: "trackData not formated as {uri: , duration: }"] // library marker davegut.samsungAudioNotify, line 98
	} else { // library marker davegut.samsungAudioNotify, line 99
		if (trackData[0] == "{") { // library marker davegut.samsungAudioNotify, line 100
			trackData = new JSONObject(trackData) // library marker davegut.samsungAudioNotify, line 101
			trackUri = trackData.uri // library marker davegut.samsungAudioNotify, line 102
			duration = trackData.duration // library marker davegut.samsungAudioNotify, line 103
		} else { // library marker davegut.samsungAudioNotify, line 104
			trackUri = trackData // library marker davegut.samsungAudioNotify, line 105
			duration = 15 // library marker davegut.samsungAudioNotify, line 106
		} // library marker davegut.samsungAudioNotify, line 107
		logData << [status: "addToQueue", trackData: trackData, volume: volume, resume: resume] // library marker davegut.samsungAudioNotify, line 108
//		addToQueue(trackUri, duration, volume, resume) // library marker davegut.samsungAudioNotify, line 109
		addToQueue(trackUri, duration, volume) // library marker davegut.samsungAudioNotify, line 110
	} // library marker davegut.samsungAudioNotify, line 111
	logDebug("createPlayData: ${logData}") // library marker davegut.samsungAudioNotify, line 112
} // library marker davegut.samsungAudioNotify, line 113

//	========== Play Queue Execution ========== // library marker davegut.samsungAudioNotify, line 115
def addToQueue(trackUri, duration, volume){ // library marker davegut.samsungAudioNotify, line 116
	if(device.currentValue("switch") == "on") { // library marker davegut.samsungAudioNotify, line 117
		def logData = [:] // library marker davegut.samsungAudioNotify, line 118
		duration = duration + 3 // library marker davegut.samsungAudioNotify, line 119
		playData = ["trackUri": trackUri,  // library marker davegut.samsungAudioNotify, line 120
					"duration": duration, // library marker davegut.samsungAudioNotify, line 121
					"requestVolume": volume] // library marker davegut.samsungAudioNotify, line 122
		state.playQueue.add(playData)	 // library marker davegut.samsungAudioNotify, line 123
		logData << [addedToQueue: [uri: trackUri, duration: duration, volume: volume]]	 // library marker davegut.samsungAudioNotify, line 124

		if (state.playingNotification == false) { // library marker davegut.samsungAudioNotify, line 126
			runInMillis(100, startPlayViaQueue) // library marker davegut.samsungAudioNotify, line 127
		} // library marker davegut.samsungAudioNotify, line 128
		logDebug("addToQueue: ${logData}") // library marker davegut.samsungAudioNotify, line 129
	} // library marker davegut.samsungAudioNotify, line 130
} // library marker davegut.samsungAudioNotify, line 131

def startPlayViaQueue() { // library marker davegut.samsungAudioNotify, line 133
	logDebug("startPlayViaQueue: [queueSize: ${state.playQueue.size()}]") // library marker davegut.samsungAudioNotify, line 134
	if (state.playQueue.size() == 0) { return } // library marker davegut.samsungAudioNotify, line 135
	state.recoveryVolume = device.currentValue("volume") // library marker davegut.samsungAudioNotify, line 136
	state.recoverySource = device.currentValue("mediaInputSource") // library marker davegut.samsungAudioNotify, line 137
	state.playingNotification = true // library marker davegut.samsungAudioNotify, line 138
	playViaQueue() // library marker davegut.samsungAudioNotify, line 139
} // library marker davegut.samsungAudioNotify, line 140

def playViaQueue() { // library marker davegut.samsungAudioNotify, line 142
	def logData = [:] // library marker davegut.samsungAudioNotify, line 143
	if (state.playQueue.size() == 0) { // library marker davegut.samsungAudioNotify, line 144
		resumePlayer() // library marker davegut.samsungAudioNotify, line 145
		logData << [status: "resumingPlayer", reason: "Zero Queue"] // library marker davegut.samsungAudioNotify, line 146
	} else { // library marker davegut.samsungAudioNotify, line 147
		def playData = state.playQueue.get(0) // library marker davegut.samsungAudioNotify, line 148
		state.playQueue.remove(0) // library marker davegut.samsungAudioNotify, line 149

		runInMillis(100, setVolume, [data:playData.requestVolume]) // library marker davegut.samsungAudioNotify, line 151

		runInMillis(400, execPlay, [data: playData]) // library marker davegut.samsungAudioNotify, line 153
		runIn(playData.duration, resumePlayer) // library marker davegut.samsungAudioNotify, line 154
		runIn(30, kickStartQueue) // library marker davegut.samsungAudioNotify, line 155
		logData << [playData: playData, recoveryVolume: recVolume] // library marker davegut.samsungAudioNotify, line 156
	} // library marker davegut.samsungAudioNotify, line 157
	logDebug("playViaQueue: ${logData}") // library marker davegut.samsungAudioNotify, line 158
} // library marker davegut.samsungAudioNotify, line 159

def execPlay(data) { // library marker davegut.samsungAudioNotify, line 161
	if (deviceIp) { // library marker davegut.samsungAudioNotify, line 162
		sendUpnpCmd("SetAVTransportURI", // library marker davegut.samsungAudioNotify, line 163
					 [InstanceID: 0, // library marker davegut.samsungAudioNotify, line 164
					  CurrentURI: data.trackUri, // library marker davegut.samsungAudioNotify, line 165
					  CurrentURIMetaData: ""]) // library marker davegut.samsungAudioNotify, line 166
		pauseExecution(300) // library marker davegut.samsungAudioNotify, line 167
		sendUpnpCmd("Play", // library marker davegut.samsungAudioNotify, line 168
					 ["InstanceID" :0, // library marker davegut.samsungAudioNotify, line 169
					  "Speed": "1"]) // library marker davegut.samsungAudioNotify, line 170
	} else { // library marker davegut.samsungAudioNotify, line 171
		sendCommand("playTrack", data.trackUri) // library marker davegut.samsungAudioNotify, line 172
	}		 // library marker davegut.samsungAudioNotify, line 173
} // library marker davegut.samsungAudioNotify, line 174

def resumePlayer() { // library marker davegut.samsungAudioNotify, line 176
	//	should be able to recover data here.  At least, recover the current value of the inputSource // library marker davegut.samsungAudioNotify, line 177
	def logData = [:] // library marker davegut.samsungAudioNotify, line 178
	if (state.playQueue.size() > 0) { // library marker davegut.samsungAudioNotify, line 179
		logData << [status: "aborted", reason: "playQueue not 0"] // library marker davegut.samsungAudioNotify, line 180
		playViaQueue() // library marker davegut.samsungAudioNotify, line 181
	} else { // library marker davegut.samsungAudioNotify, line 182
		state.playingNotification = false // library marker davegut.samsungAudioNotify, line 183
		setVolume(state.recoveryVolume) // library marker davegut.samsungAudioNotify, line 184
		def source = state.recoverySource // library marker davegut.samsungAudioNotify, line 185
		if (source != "n/a") { // library marker davegut.samsungAudioNotify, line 186
			setInputSource(source) // library marker davegut.samsungAudioNotify, line 187
		} // library marker davegut.samsungAudioNotify, line 188
	} // library marker davegut.samsungAudioNotify, line 189
	logDebug("resumePlayer: ${logData}") // library marker davegut.samsungAudioNotify, line 190
} // library marker davegut.samsungAudioNotify, line 191

def kickStartQueue() { // library marker davegut.samsungAudioNotify, line 193
	logInfo("kickStartQueue: [size: ${state.playQueue.size()}]") // library marker davegut.samsungAudioNotify, line 194
	if (state.playQueue.size() > 0) { // library marker davegut.samsungAudioNotify, line 195
		resumePlayer() // library marker davegut.samsungAudioNotify, line 196
	} else { // library marker davegut.samsungAudioNotify, line 197
		state.playingNotification = false // library marker davegut.samsungAudioNotify, line 198
	} // library marker davegut.samsungAudioNotify, line 199
} // library marker davegut.samsungAudioNotify, line 200

def clearQueue() { // library marker davegut.samsungAudioNotify, line 202
	logDebug("clearQueue") // library marker davegut.samsungAudioNotify, line 203
	state.playQueue = [] // library marker davegut.samsungAudioNotify, line 204
	state.playingNotification = false // library marker davegut.samsungAudioNotify, line 205
} // library marker davegut.samsungAudioNotify, line 206

private sendUpnpCmd(String action, Map body){ // library marker davegut.samsungAudioNotify, line 208
	logDebug("sendUpnpCmd: upnpAction = ${action}, upnpBody = ${body}") // library marker davegut.samsungAudioNotify, line 209
	def host = "${deviceIp}:9197" // library marker davegut.samsungAudioNotify, line 210
	def hubCmd = new hubitat.device.HubSoapAction( // library marker davegut.samsungAudioNotify, line 211
		path:	"/upnp/control/AVTransport1", // library marker davegut.samsungAudioNotify, line 212
		urn:	 "urn:schemas-upnp-org:service:AVTransport:1", // library marker davegut.samsungAudioNotify, line 213
		action:  action, // library marker davegut.samsungAudioNotify, line 214
		body:	body, // library marker davegut.samsungAudioNotify, line 215
		headers: [Host: host, // library marker davegut.samsungAudioNotify, line 216
				  CONNECTION: "close"] // library marker davegut.samsungAudioNotify, line 217
	) // library marker davegut.samsungAudioNotify, line 218
	sendHubCommand(hubCmd) // library marker davegut.samsungAudioNotify, line 219
} // library marker davegut.samsungAudioNotify, line 220

def upnpParse(resp) { // library marker davegut.samsungAudioNotify, line 222
	resp = parseLanMessage(resp) // library marker davegut.samsungAudioNotify, line 223
	if (resp.status != 200) { // library marker davegut.samsungAudioNotify, line 224
		logWarn("upnpParse: [status: failed, data: ${resp}]") // library marker davegut.samsungAudioNotify, line 225
	} // library marker davegut.samsungAudioNotify, line 226
} // library marker davegut.samsungAudioNotify, line 227

//	Send parse to appropriate method for UPNP or WS! // library marker davegut.samsungAudioNotify, line 229
def parse(resp) { // library marker davegut.samsungAudioNotify, line 230
	if (resp.toString().contains("mac:")) { // library marker davegut.samsungAudioNotify, line 231
		upnpParse(resp) // library marker davegut.samsungAudioNotify, line 232
	} else { // library marker davegut.samsungAudioNotify, line 233
		parseWs(resp) // library marker davegut.samsungAudioNotify, line 234
	} // library marker davegut.samsungAudioNotify, line 235
} // library marker davegut.samsungAudioNotify, line 236


/*	===== Sample Audio Notification URIs ===== // library marker davegut.samsungAudioNotify, line 239
[title: "Bell 1", uri: "http://s3.amazonaws.com/smartapp-media/sonos/bell1.mp3", duration: "10"] // library marker davegut.samsungAudioNotify, line 240
[title: "Dogs Barking", uri: "http://s3.amazonaws.com/smartapp-media/sonos/dogs.mp3", duration: "10"] // library marker davegut.samsungAudioNotify, line 241
[title: "Fire Alarm", uri: "http://s3.amazonaws.com/smartapp-media/sonos/alarm.mp3", duration: "17"] // library marker davegut.samsungAudioNotify, line 242
[title: "The mail has arrived",uri: "http://s3.amazonaws.com/smartapp-media/sonos/the+mail+has+arrived.mp3", duration: "1"] // library marker davegut.samsungAudioNotify, line 243
[title: "A door opened", uri: "http://s3.amazonaws.com/smartapp-media/sonos/a+door+opened.mp3", duration: "1"] // library marker davegut.samsungAudioNotify, line 244
[title: "There is motion", uri: "http://s3.amazonaws.com/smartapp-media/sonos/there+is+motion.mp3", duration: "1"] // library marker davegut.samsungAudioNotify, line 245
[title: "Someone is arriving", uri: "http://s3.amazonaws.com/smartapp-media/sonos/someone+is+arriving.mp3", duration: "1"] // library marker davegut.samsungAudioNotify, line 246
=====	Some working Streaming Stations ===== // library marker davegut.samsungAudioNotify, line 247
[title:"Cafe del Mar", uri:"https://streams.radio.co/se1a320b47/listen", duration: 0] // library marker davegut.samsungAudioNotify, line 248
[title:"UT-KUTX", uri: "https://kut.streamguys1.com/kutx-web", duration: 0] // library marker davegut.samsungAudioNotify, line 249
[title:"89.7 FM Perth", uri: "https://ice8.securenetsystems.net/897FM", duration: 0] // library marker davegut.samsungAudioNotify, line 250
[title:"Euro1", uri:"https://streams.radio.co/se1a320b47/listen", duration: 0] // library marker davegut.samsungAudioNotify, line 251
[title:"Easy Hits Florida", uri:"http://airspectrum.cdnstream1.com:8114/1648_128", duration: 0] // library marker davegut.samsungAudioNotify, line 252
[title:"Austin Blues", uri:"http://158.69.131.71:8036/stream/1/", duration: 0] // library marker davegut.samsungAudioNotify, line 253
*/ // library marker davegut.samsungAudioNotify, line 254


// ~~~~~ end include (1234) davegut.samsungAudioNotify ~~~~~

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

// ~~~~~ end include (1072) davegut.Logging ~~~~~
