/**
*  Copyright 2023 David Gutheinz
*
*  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License. You may obtain a copy of the License at:
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
*  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
*  for the specific language governing permissions and limitations under the License.
*
*/
@SuppressWarnings('unused')	//
import org.json.JSONObject	//
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic //
import groovy.transform.Field	//
@Field volatile static Map<String,Long> g_mEventSendTime = [:]	//
public static String driverVer() { return "0.1.0" }

metadata {
	definition (name: "Replica Samsung TV",
				namespace: "replica",
				author: "David Gutheinz",
				importUrl: ""
			   ){
		capability "Actuator"	//	new
		capability "Configuration"
		capability "Refresh"	//	new
		attribute "healthStatus", "enum", ["offline", "online"]
		capability "Switch"
		capability "SamsungTV"
			command "showMessage", [[name: "Not Implemented"]]
			command "toggleSoundMode"
			command "togglePictureMode"
		command "setLevel", ["number"]	//	Support music player on dashboard
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
		command "appClose"
			attribute "currentApp", "string"		//	Use title??????
		//	Dashboard Support
		capability "PushableButton"
		capability "Variable"		//	Used for dashboard to create an app open tile
		
		command "eventHandler", [[name: "For App Use Only"]]
	}
	preferences {
		input ("deviceIp", "text", title: "Enter Samsung TV Ip for Local Controls", defaultValue: "")
		if (deviceIp) {
			input ("tvPwrOnMode", "enum", title: "TV Startup Display", 
				   options: ["ART_MODE", "Ambient", "none"], defaultValue: "none")
			input ("logEnable", "bool",  
				   title: "Enable debug logging for 30 minutes", defaultValue: false)
			input ("infoLog", "bool", 
				   title: "Enable information logging",
				   defaultValue: true)
			input ("traceLog", "bool", title: "Enable trace logging as directed by developer", defaultValue: false)
			input ("findAppCodes", "bool", title: "Scan for App Codes (use rarely)", defaultValue: false)
			input ("resetAppCodes", "bool", title: "Delete and Rescan for App Codes (use rarely)", defaultValue: false)
		}
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
	initialize()
	runIn(5, updated)
}

def updated() {
	unschedule()
	def updStatus = [:]
	initialize()
	
	if (!deviceIp) {
		logWarn("\n\n\t\t<b>Enter the deviceIp and Save Preferences</b>\n\n")
		updStatus << [status: "ERROR", data: "Device IP not set."]
	} else {
		logInfo("updated: ${updStatus}")
		updStatus << [getDeviceData: configureLan()]
		if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
			updateDataValue("driverVersion", driverVer())
			updStatus << [driverVer: driverVer()]
		}
		if (logEnable) { runIn(1800, debugLogOff) }
		if (traceLog) { runIn(600, traceLogOff) }
		updStatus << [logEnable: logEnable, infoLog: infoLog, traceLog: traceLog]

		if (resetAppCodes) {
			state.appData = [:]
			runIn(5, updateAppCodes)
		} else if (findAppCodes) {
			runIn(5, updateAppCodes)
		}
		runEvery5Minutes(poll)
		pauseExecution(5000)
		listAttributes(true)
		//	set state to gather initial attributes on startup
	}
	if (!state.soundModes) {
		state.updateAvailableModes = true
		refresh()
	}
}

def initialize() {
	setAutoAttributes()
    updateDataValue("triggers", groovy.json.JsonOutput.toJson(getReplicaTriggers()))
    updateDataValue("commands", groovy.json.JsonOutput.toJson(getReplicaCommands()))
}

Map getReplicaCommands() {
    return (["replicaEvent":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]], 
			 "replicaStatus":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]], 
			 "replicaHealth":[[name:"parent*",type:"OBJECT"],[name:"health*",type:"JSON_OBJECT"]],
			 "setHealthStatusValue":[[name:"healthStatus*",type:"ENUM"]]])
}

Map getReplicaTriggers() {
	Map triggers = [ 
		refresh:[], 
		setVolume: [[name:"volume*", type: "NUMBER"]], 
		setMute: [[name:"state*", type: "string"]],
		setPictureMode:[[name:"mode*", type:"string"]], 
		setInputSource:[[name:"inputSource*", type: "string"]],
		setSoundMode:[[name:"mode*", type:"string"]],
		setTvChannel:[[name: "tvChannel", type: "string"]],
		fastForward:[], 
		rewind:[], 
		setTvChannel: [[name:"tvChannel*", type: "integer"]]
	]
	return triggers
}

def setAutoAttributes() {
	state.autoAttributes = [
		"switch", "mute", "pictureMode", "soundMode",
		"playbackStatus", "tvChannel"]
}

def configure() {
    logInfo "configure (default device data)"
    initialize()
    updateDataValue("rules", getReplicaRules())
    sendCommand("configure")
}

String getReplicaRules() {
return """{"version":1,"components":[{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger","mute":true},{"trigger":{"name":"setVolume","label":"command: setVolume(volume*)","type":"command","parameters":[{"name":"volume*","type":"NUMBER"}]},"command":{"name":"setVolume","arguments":[{"name":"volume","optional":false,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioVolume","label":"command: setVolume(volume*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setMute","label":"command: setMute(state*)","type":"command","parameters":[{"name":"state*","type":"string"}]},"command":{"name":"setMute","arguments":[{"name":"state","optional":false,"schema":{"title":"MuteState","type":"string","enum":["muted","unmuted"]}}],"type":"command","capability":"audioMute","label":"command: setMute(state*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setPictureMode","label":"command: setPictureMode(mode*)","type":"command","parameters":[{"name":"mode*","type":"string"}]},"command":{"name":"setPictureMode","arguments":[{"name":"mode","optional":false,"schema":{"type":"string"}}],"type":"command","capability":"custom.picturemode","label":"command: setPictureMode(mode*)"},"type":"hubitatTrigger"},{"trigger":{"name":"setSoundMode","label":"command: setSoundMode(mode*)","type":"command","parameters":[{"name":"mode*","type":"string"}]},"command":{"name":"setSoundMode","arguments":[{"name":"mode","optional":false,"schema":{"type":"string"}}],"type":"command","capability":"custom.soundmode","label":"command: setSoundMode(mode*)"},"type":"hubitatTrigger"},{"trigger":{"name":"refresh","label":"command: refresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},{"trigger":{"name":"fastForward","label":"command: fastForward()","type":"command"},"command":{"name":"fastForward","type":"command","capability":"mediaPlayback","label":"command: fastForward()"},"type":"hubitatTrigger"},{"trigger":{"name":"rewind","label":"command: rewind()","type":"command"},"command":{"name":"rewind","type":"command","capability":"mediaPlayback","label":"command: rewind()"},"type":"hubitatTrigger"},{"trigger":{"name":"setInputSource","label":"command: setInputSource(inputSource*)","type":"command","parameters":[{"name":"inputSource*","type":"string"}]},"command":{"name":"setInputSource","arguments":[{"name":"mode","optional":false,"schema":{"title":"MediaSource","enum":["AM","CD","FM","HDMI","HDMI1","HDMI2","HDMI3","HDMI4","HDMI5","HDMI6","digitalTv","USB","YouTube","aux","bluetooth","digital","melon","wifi"],"type":"string"}}],"type":"command","capability":"mediaInputSource","label":"command: setInputSource(mode*)"},"type":"hubitatTrigger"}]}"""

//	DEFAULT FOR DEVELOPMENT    return """{"version":1,"components":[{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger","mute":true}]}"""
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
		respData << [status: "OK", dni: alternateWolMac, modelYear: modelYear,
					 frameTv: frameTv, tokenSupport: tokenSupport]
		sendEvent(name: "artModeStatus", value: "none")
		def data = [request:"get_artmode_status",
					id: uuid]
		data = JsonOutput.toJson(data)
		artModeCmd(data)
	} else {
		respData << tvData
	}
	return respData
}

//	===== Event Parse Interface s=====
void replicaStatus(def parent=null, Map status=null) {
	def logData = [:]
	if (status != null && state.updateAvailableModes == true) {
		setInitialAttributes(status.components.main)
		pauseExecution(1000)
		state.updateAvailableModes = false
	}
}

def setInitialAttributes(mainData) {
	def logData = [:]
	
	List pictureModes = mainData["custom.picturemode"].supportedPictureModes.value
	state.pictureModes = pictureModes
	logData << [pictureModes: mainData["custom.picturemode"].supportedPictureModes.value]

	pauseExecution(200)
	state.soundModes = mainData["custom.soundmode"].supportedSoundModes.value
	logData << [soundModes: mainData["custom.soundmode"].supportedSoundModes.value]

	pauseExecution(200)
	state.inputSources = mainData.mediaInputSource.supportedInputSources.value
	logData << [inputSources: mainData.mediaInputSource.supportedInputSources.value]
	
	pauseExecution(200)
	sendEvent(name: "switch", value: mainData.switch.switch.value)
	logData << [switch: mainData.switch.switch.value]
		
	pauseExecution(200)
	sendEvent(name: "volume", value: mainData.audioVolume.volume.value.toInteger())
	sendEvent(name: "level", value: mainData.audioVolume.volume.value.toInteger())
	logData << [volume: mainData.audioVolume.volume.value.toInteger()]
	
	pauseExecution(200)
	sendEvent(name: "mute", value: mainData.audioMute.mute.value)
	logData<< [mute: mainData.audioMute.mute.value]
		
	pauseExecution(200)
	sendEvent(name: "pictureMode", value: mainData["custom.picturemode"].pictureMode.value)
	logData<< [pictureMode: mainData["custom.picturemode"].pictureMode.value]
		
	pauseExecution(200)
	sendEvent(name: "soundMode", value: mainData["custom.soundmode"].soundMode.value)
	logData<< [soundMode: mainData["custom.soundmode"].soundMode.value]
		
	pauseExecution(200)
	sendEvent(name: "mediaInputSource", value: mainData.mediaInputSource.inputSource.value)
	logData<< [mediaInputSource: mainData.mediaInputSource.inputSource.value]
		
	pauseExecution(200)
	sendEvent(name: "mediaInputSource", value: mainData.mediaInputSource.inputSource.value)
	logData<< [mediaInputSource: mainData.mediaInputSource.inputSource.value]
		
	logInfo("setInitialAttributes: ${logData}")
}

void replicaHealth(def parent=null, Map health=null) {
	if(parent) { logInfo("replicaHealth: ${parent?.getLabel()}") }
	if(health) { logInfo("replicaHealth: ${health}") }
}

def setHealthStatusValue(value) {    
    sendEvent(name: "healthStatus", value: value, descriptionText: "${device.displayName} healthStatus set to $value")
}

//	===== Device Event Handler Methods
void replicaEvent(def parent=null, Map event=null) {
	def eventData = event.deviceEvent
	try {
	"parse_${event.deviceEvent.componentId}"(event.deviceEvent)
	} catch (err) {
		logWarn("replicaEvent: [event = ${event}, error: ${err}")
	}
}

def parse_main(event) {
	if (state.autoAttributes.contains(event.attribute)) {
		sendEvent(name: event.attribute, value: event.value, unit: event.unit)
	} else {
		switch(event.attribute) {
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
				if (event.value.contains(".")) {
					getAppData(event.value)
				} else {
					sendEvent(name: "currentApp", value: " ")
				}
				break
			default:
				logDebug("parse_main: [unhandledEvent: ${event}]")
			break
		}
	}
}

//	Used for any rule-based commands
private def sendCommand(String name, def value=null, String unit=null, data=[:]) {
    parent?.deviceTriggerHandler(device, [name:name, value:value, unit:unit, data:data, now:now])
}

//	===== Samsung TV Commands =====
def poll() {
	if (device.currentValue("switch") == "on") {
		deviceRefresh()
	}
}

def refresh() {
	sendCommand("refresh")
}

def deviceRefresh() {
	def deviceId = new JSONObject(getDataValue("replica")).deviceId
	log.trace deviceId
	parent.setSmartDeviceCommand(deviceId, "main", "refresh", "refresh")
}

def on() {
	if (deviceIp) {
		sendKey("POWER")
		def wolMac = getDataValue("wolMac")
		def cmd = "FFFFFFFFFFFF$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac$wolMac"
		wol = new hubitat.device.HubAction(
			cmd,
			hubitat.device.Protocol.LAN,
			[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
			 destinationAddress: "255.255.255.255:7",
			 encoding: hubitat.device.HubAction.Encoding.HEX_STRING])
		sendHubCommand(wol)
	} else {
		sendCommand("on")
	}
	runIn(5, setPowerOnMode)
}

def off() {
	logInfo("off: [frameTv: ${getDataValue("frameTv")}]")
	if (deviceIp) {
		if (getDataValue("frameTv") == "true") {
			sendKey("POWER", "Press")
			pauseExecution(4000)
			sendKey("POWER", "Release")
		} else {
			sendKey("POWER")
		}
	} else {
		sendCommand("off")
	}
}

def setPowerOnMode() {
	logDebug("setPowerOnMode: [tvPwrOnMode: ${tvPwrOnMode}]")
	if(tvPwrOnMode == "ART_MODE") {
		artMode()
	} else if (tvPwrOnMode == "Ambient") {
		ambientMode()
	}
}

//	===== capability "SamsungTV"===== 
def setLevel(level) { setVolume(level) }

def volumeUp() {
	sendKey("VOLUP")
	runIn(5, poll)
}
	
def volumeDown() {
	sendKey("VOLDOWN")
	runIn(5, poll)
}
	
def setVolume(volume) {
	if (volume == null) { volume = device.currentValue("volume").toInteger() }
	if (volume < 0) { volume = 0 }
	else if (volume > 100) { volume = 100 }
	sendCommand("setVolume", volume)
	runIn(5, poll)
}

def mute() {
	sendKey("MUTE")
	runIn(5, poll)
}

def unmute() {
	sendKey("MUTE")
	runIn(5, poll)
}

def setMuteValue(mute) {
	sendEvent(name: "mute", value: mute)
	logDebug("setMuteValue: [mute: ${mute}]")
	runIn(5, poll)
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
	runIn(5, poll)
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
	runIn(5, poll)
}

//	===== capability "MediaInputSource" =====
def toggleInputSource() {
	def inputSources = state.inputSources
	if (inputSources != null) {
		def totalSources = inputSources.size()
		def currentSource = device.currentValue("mediaInputSource")
		def sourceNo = inputSources.indexOf(currentSource)
		def newSourceNo = sourceNo + 1
		if (newSourceNo == totalSources) { newSourceNo = 0 }
		def inputSource = inputSources[newSourceNo]
		setInputSource(inputSource)
	} else {
		sendKey("HDMI")
	}
}

def setInputSource(inputSource) {
	sendCommand("setInputSource", inputSource)
}

//	===== capability "MediaTransport" =====
def play() { sendKey("PLAY") }

def pause() { sendKey("PAUSE") }

def stop() { sendKey("STOP") }

def rewind() { sendCommand("rewind") }

def fastForward() { sendCommand("fastForward") }

//	===== TV Channel =====
def setTvChannel(tvChannel) {
	sendCommand("setTvChannel", tvChannel.toString())
}

def channelList() { sendKey("CH_LIST") }

def channelUp() { sendKey("CHUP") }

def channelDown() { sendKey("CHDOWN") }

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
}

def ambientMode() { sendKey("AMBIENT") }

//	== Remote Commands
def exit() { sendKey("EXIT") }

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
		logWarn("sendMessage: [status: notAvailable, reason: deviceIp not set]")
	} else {
		def wsStat = device.currentValue("wsStatus")
		logDebug("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${state.currentFunction}")
		logTrace("sendMessage: [wsStatus: ${wsStat}, function: ${funct}, data: ${data}, connectType: ${state.currentFunction}")
		if (wsStat != "open" || state.currentFunction != funct) {
			connect(funct)
			pauseExecution(600)
		}
		interfaces.webSocket.sendMessage(data)
		runIn(30, close)
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
			runIn(5, poll)
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
			runIn(5, poll)
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

//	===== LOGGING INTERFACE =====
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
	if (traceLog) {
		log.trace "${device.displayName}-${driverVer()}: ${msg}"
	}
}

def traceLogOff() {
	if (traceLog) {
		device.updateSetting("traceLog", [type:"bool", value: false])
		logInfo("traceLog off")
	}
}

def logInfo(msg) {
	if (textEnable || infoLog) {
		log.info "${device.displayName}-${driverVer()}: ${msg}"
	}
}

def debugLogOff() {
	if (logEnable) {
		device.updateSetting("logEnable", [type:"bool", value: false])
		logInfo("debugLogOff")
	}
}

def logDebug(msg) {
	if (logEnable) {
		log.debug "${device.displayName}-${driverVer()}: ${msg}"
	}
}

def logWarn(msg) { log.warn "${device.displayName}-${driverVer()}: ${msg}" }
