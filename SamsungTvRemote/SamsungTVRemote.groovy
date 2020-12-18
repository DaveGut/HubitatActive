/*	===== HUBITAT INTEGRATION VERSION =====================================================
Hubitat - Samsung TV Remote Driver
		Copyright 2020 Dave Gutheinz

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
	The GitHub WebSockets personnel for node.js code "ws" used in the external server
	GitHub user Toxblh for exlempary code for numerous commands
	Hubitat users who supported validation of 2016 - 2020 models.
===== REQUIREMENTS ========================================================================
a.	For model years 2017 and later, a stand-alone node.js server installed IAW provided
	instructions and running.
b.	This driver installed and configured IAW provided instructions.
===== RELEASE NOTES =======================================================================
Beta 1.0	Initial release
Beta 1.1	Updated to work WITHOUT an external NodeJs Server (2017 + models
			1.	Changed youTube launch to Home Page.
			2.	Added Netflix key
Beta 1.2	1.	Added AppOpen, Install, and Close
			2.	Added return key as Return
			3.	Reworked comms area to fix problem with closing port all the time.
Beta 1.2.1	Fixed Art Mode.  Correct typo in debug mode.
Beta 1.3.0	1.  Added UPnP commands to set level, mute, playStatus, and notifications.
			2.  Added artMode (toggle) command and removed access to ArtModeOn,
				ArtModeOff, ArtModeStatus
			3.  Removed Source1, Source1, Source3, Source4, and TV commands.
				Use HDMI or Source instead.
			4.	Removed following Button Interface:  on, off, artModeOn, artModeOff,
				artModeStatus, volumeUp, volumeDown, mute
			ToDo List: Find a means to poll status frequently (looking for non-obtrusive method.)
Beta 1.3.2	a.	Fixed art mode function.
			b.	Modified play/pause to work as Enter if no media is present (this will 
				pause/play external (HDMI) media, if available) by passing command on HDMI/CEC
				interface.  Also enables play/pause interface on Media Player dashboard tile.
			c.	Added mute toggle to allow single tile mute in addition to use of Media Play
				dashboard tile.
Beta 1.3.3	a.	Created quick poll routine using port 9197 and path /dmr (hard coded response).
			b.	Created command "setQuickPoll" with enumerated values in seconds to turn on
				and off quick polling.
			c.	Modified Refresh to use quick poll to determine on/off state and then update
				data only if the device is on.
			d.	Fixed art mode status to attain correct value (requires testing)
Beta 1.3.4	a.	Added capability Switch
			b.	Updated save preferences processing to re-acquire settings data on each
				update.  Test to capture new MAC after changing wired to/from wifi connect.
			c.	Still working on Art Mode Status.  Next fix attempt (problem parsing data).
Beta 1.3.5	a.	Added ST Intergation and functions setInputSource and setTvChannel.
			b.	Next attempt at fixing artMode
1.3.6		a.	Fixed error in refresh causing failure to request important satus data.
			b.	Fixed mute functions to properly operate and report state.
			c.	Fixed websocket.  Now auto-closes in 3 minutes after opening.
				I was unable to reliable capture the close status when closed by tv.
			d.	Converted ArtMode into artModeOn and artModeOff.  Changed buttons
				to 5 for artModeOn and 6 for artModeOff
			e.	Fixed HDMI and Source methods to call ST status 5 - 10 seconds
				after the update is activated.
*/
def driverVer() { return "1.3.6" }
import groovy.json.JsonOutput
metadata {
	definition (name: "Samsung TV Remote",
				namespace: "davegut",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungTvRemote/SamsungTVRemote.groovy"
			   ){
		capability "SamsungTV"			//	cmds: on/off, volume, mute. attrs: switch, volume, mute
		capability "Switch"
		//	===== UPnP Augmentation =====
		command "pause"					//	Only work on TV Players
		command "play"					//	Only work on TV Players
		command "stop"					//	Only work on TV Players
		attribute "status", "string"	//	Play Status
		capability "SpeechSynthesis"	//	TTS Speak. cmd: speak
		capability "Notification"		//	TTS Notification. cmd: deviceNotification
		command "kickStartQueue"		//	Force Queue to start when stalled
		capability "Refresh"
		//	===== WebSocketInterface =====
		command "close"					//	Force socket close.
		attribute "wsDeviceStatus", "string"	//	Socket status open/closed
		//	===== Remote Control Interface =====
		command "sendKey", ["string"]	//	Send entered key. eg: HDMI
		//	TV Art Display
		command "artModeOn"				//	Turns on Art Mode
		command "artModeOff"			//	Turns off Art Mode
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
		command "setInputSource", [[	//	Requires SmartThings integration
			name: "Input Source",
			constraints: ["digitalTv", "HDMI1", "HDMI2", "HDMI3", "HDMI4", "COMPONENT"],
			type: "ENUM"]]
		attribute "inputSource", "string"		//	Requires SmartThings integration
		attribute "inputSources", "string"		//	Requires SmartThings integration
		//	TV Channel
		command "channelList"
		command "channelUp"
		command "channelDown"
		command "previousChannel"
		command "setTvChannel", ["string"]		//	Requires SmartThings integration
		attribute "tvChannel", "string"			//	Requires SmartThings integration
		attribute "tvChannelName", "string"		//	Requires SmartThings integration
		//	Playing Navigation Commands
		command "exit"
		command "Return"
		command "fastBack"
		command "fastForward"
		//	Application Access/Control
		command "browser"
		command "youTube"
		command "netflix"
		command "appInstall", ["string"]
		command "appOpen", ["string"]
		command "appClose"
		//	===== Button Interface =====
		capability "PushableButton"
		command "push", ["NUMBER"]
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["off", "5", "10", "15", "20", "25", "30"],
			type: "ENUM"]]
		command "listStDevices"		//	Used only during ST Integration setup.
	}
	preferences {
		input ("deviceIp", "text", title: "Samsung TV Ip")
		input ("connectST", "bool", title: "Connect to SmartThings for added functions", defaultValue: false)
		if (connectST) {
			input ("stApiKey", "text", title: "SmartThings API Key")
			input ("stDeviceId", "text", title: "SmartThings TV Device ID")
		}
		input ("refreshInterval", "enum",  
			   title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "10", "15", "30", "60", "180"])
		input ("tvPwrOnMode", "enum", title: "TV Startup Display", 
			   options: ["ART_MODE", "Ambient", "none"], defalutValue: "none")
		def ttsLanguages = ["en-au":"English (Australia)","en-ca":"English (Canada)", "en-gb":"English (Great Britain)",
							"en-us":"English (United States)", "en-in":"English (India)","ca-es":"Catalan",
							"zh-cn":"Chinese (China)", "zh-hk":"Chinese (Hong Kong)","zh-tw":"Chinese (Taiwan)",
							"da-dk":"Danish", "nl-nl":"Dutch","fi-fi":"Finnish","fr-ca":"French (Canada)",
							"fr-fr":"French (France)","de-de":"German","it-it":"Italian","ja-jp":"Japanese",
							"ko-kr":"Korean","nb-no":"Norwegian","pl-pl":"Polish","pt-br":"Portuguese (Brazil)",
							"pt-pt":"Portuguese (Portugal)","ru-ru":"Russian","es-mx":"Spanish (Mexico)",
							"es-es":"Spanish (Spain)","sv-se":"Swedish (Sweden)"]
		input ("ttsApiKey", "string", title: "TTS Site Key", description: "From http://www.voicerss.org/registration.aspx")
		input ("ttsLang", "enum", title: "TTS Language", options: ttsLanguages, defaultValue: "en-us")
		input ("debugLog", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: true)
		input ("infoLog", "bool",  title: "Enable description text logging", defaultValue: true)
	}
}

//	===== Installation, setup and update =====
def installed() {
	state.token = "12345678"
	def tokenSupport = "false"
	updateDataValue("name", "Hubitat Samsung Remote")
	updateDataValue("name64", "Hubitat Samsung Remote".encodeAsBase64().toString())
}
def updated() {
	logInfo("updated")
	sendEvent(name: "numberOfButtons", value: "50")
	close()
	state.playQueue = []
	setUpnpData()
	if (debugLog) { runIn(1800, debugLogOff) }
		def tokenSupport = getDeviceData()
		logInfo("Performing test using tokenSupport = ${tokenSupport}")
	if (stApiKey && stDeviceId) { getStDeviceData("setup") }
	pauseExecution(2000)
	if(getDataValue("frameTv") == "false") {
		sendEvent(name: "artModeStatus", value: "notFrameTV")
	} else { getArtModeStatus() }
	switch(refreshInterval) {
		case "1" : runEvery1Minute(refresh); break
		case "5" : runEvery5Minutes(refresh); break
		case "10" : runEvery10Minutes("refresh"); break
		case "15" : runEvery15Minutes(refresh); break
		case "30" : runEvery30Minutes(refresh); break
		case "180": runEvery3Hours(refresh); break
		default:
			runEvery1Hour(refresh); break
	}
	runIn(2, refresh)
}
def getDeviceData() {
	logInfo("getDeviceData: Updating Device Data.")
	try{
		httpGet([uri: "http://${deviceIp}:8001/api/v2/", timeout: 5]) { resp ->
			updateDataValue("deviceMac", resp.data.device.wifiMac)
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
			logInfo("getDeviceData: year = $modelYear, frameTv = $frameTv, tokenSupport = $tokenSupport")
		} 
	} catch (error) {
		logWarn("getDeviceData: Failed.  TV may be powered off.  Error = ${error}")
	}
		
	return tokenSupport
}
def setUpnpData() {
	logInfo("setUpnpData")
	updateDataValue("rcUrn", "urn:schemas-upnp-org:service:RenderingControl:1")
	updateDataValue("rcPath", "/upnp/control/RenderingControl1")
	updateDataValue("rcPort", "9197")
	updateDataValue("avUrn", "urn:schemas-upnp-org:service:AVTransport:1")
	updateDataValue("avPath", "/upnp/control/AVTransport1")
	updateDataValue("avPort", "9197")
}

//	========== SEND UPnP Commands to Devices ==========
private sendCmd(type, action, body = []){
	logDebug("sendCmd: type = ${type}, upnpAction = ${action}, upnpBody = ${body}")
	def cmdPort
	def cmdPath
	def cmdUrn
	if (type == "AVTransport") {
		cmdPort = getDataValue("avPort")
		cmdUrn = getDataValue("avUrn")
		cmdPath = getDataValue("avPath")
	} else if (type == "RenderingControl") {
		cmdPort = getDataValue("rcPort")
		cmdUrn = getDataValue("rcUrn")
		cmdPath = getDataValue("rcPath")
	} else { logWarn("sendCmd: Invalid UPnP Type = ${type}") }
	
	def host = "${deviceIp}:${cmdPort}"
	Map params = [path:	cmdPath,
				  urn:	 cmdUrn,
				  action:  action,
				  body:	body,
				  headers: [Host: host]]
	new hubitat.device.HubSoapAction(params)
}

//	===== WebSocket Communications =====
def connect(funct) {
	logDebug("connect: function = ${funct}")
	def url
	def name = getDataValue("name64")
	if (getDataValue("tokenSupport") == "true") {
		def token = state.token
		if (funct == "remote") {
			url = "wss://${deviceIp}:8002/api/v2/channels/samsung.remote.control?name=${name}&token=${token}"
		} else if (funct == "frameArt") {
			url = "wss://${deviceIp}:8002/api/v2/channels/com.samsung.art-app?name=${name}&token=${token}"
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
	runIn(180, close)
	interfaces.webSocket.connect(url, ignoreSSLIssues: true)
}
def sendMessage(funct, data) {
	logDebug("sendMessage: function = ${funct} | data = ${data} | connectType = ${state.currentFunction}")
	if(state.currentFunction != funct) {
		close()
	}
	if (device.currentValue("wsDeviceStatus") != "open") {
		connect(funct)
		pauseExecution(1000)
	}
	interfaces.webSocket.sendMessage(data)
}
def close() {
	//	Set to closed in case device is unreachable
	sendEvent(name: "wsDeviceStatus", value: "closed")
	state.currentFunction = "close"
	interfaces.webSocket.close()
}
def webSocketStatus(message) {
	if (message == "status: open") {
		sendEvent(name: "wsDeviceStatus", value: "open")
		if (device.currentValue("switch") != "on") {
			sendEvent(name: "switch", value: "on")
		}
		logInfo("webSocketStatus: wsDeviceStatus = open")
	} else if (message == "status: closing") {
		sendEvent(name: "wsDeviceStatus", value: "closed")
		state.currentFunction = "close"
		logInfo("webSocketStatus: wsDeviceStatus = closed")
	} else if (message.substring(0,7) == "failure") {
		logInfo("webSocketStatus: Failure.  Closing Socket.")
		close()
	}
}

//	===== Parse the responses =====
def parse(resp) {
	if (resp.substring(2,6) == "data") {
		parseWebsocket(resp)
	} else {
		parseUpnp(resp)
	}
}
def parseUpnp(resp) {
	resp = parseLanMessage(resp)
	logDebug("parse: ${groovy.xml.XmlUtil.escapeXml(resp.body)}")
	def body = resp.xml.Body
	if (!body.size()) {
		logWarn("parse: No XML Body in resp: ${resp}")
		return
	}
	else if (body.GetVolumeResponse.size()){ updateVolume(body.GetVolumeResponse) }
	else if (body.GetTransportInfoResponse.size()){ updatePlayStatus(body.GetTransportInfoResponse) }
	else if (body.GetMuteResponse.size()){ updateMuteStatus(body.GetMuteResponse) }
	//	===== Get status after command
	else if (body.SetAVTransportURIResponse.size()){ play() }
	else if (body.PlayResponse.size()){ runIn(5,getPlayStatus) }
	else if (body.PauseResponse.size()){ runIn(5,getPlayStatus) }
	else if (body.StopResponse.size()){ runIn(5,getPlayStatus) }
	else if (body.SetVolumeResponse.size()){ getVolume() }
	else if (body.SetMuteResponse.size()){ getMute() }
	//	===== Fault Code =====
	else if (body.Fault.size()){
		def desc = body.Fault.detail.UPnPError.errorDescription
		logInfo("parse: Fault = ${desc}")
	}
	//	===== Unhandled response =====
	else { logWarn("parse: unhandled response: ${resp}") }
}
def parseWebsocket(resp) {
	resp = parseJson(resp)
	logDebug("parseWebsocket: ${resp}")
	def event = resp.event
	def logMsg = "parseWebsocket: event = ${event}"
	if (event == "ms.channel.connect") {
		logMsg += ", webSocket open"
		def newToken = resp.data.token
		if (newToken != null && newToken != state.token) {
			logMsg += ", token updated to ${newToken}"
			logInfo("parseWebsocket: Token updated to ${newToken}")
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
			logInfo("parseWebsocket: artMode status = ${status}")
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

//	===== SmartThings Communications / Parsing =====
private listStDevices() {
	if (!stApiKey) {
		logWarn("listStDevices: no stApiKey")
		return
	}
	logDebug("listDevices")
	def cmdUri = "https://api.smartthings.com/v1/devices"
	def sendCmdParams = [
		uri: cmdUri,
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01',
				 'Authorization': 'Bearer ' + stApiKey.trim()]
	]
	httpGet(sendCmdParams) {resp ->
		def devicesData = resp.data.items
		devicesData.each {
			log.trace "Name = ${it.name} || DeviceId = ${it.deviceId}"
		}
	}
}
def getStDeviceStatus() { getStDeviceData("status") }
private getStDeviceData(reqType = "status") {
	if (!stDeviceId || !stApiKey) {
		logWarn("getStDeviceData: no stApiKey or stDeviceId")
		return
	}
	def cmdUri = "https://api.smartthings.com/v1/devices/${stDeviceId.trim()}/status"
	def sendCmdParams = [
		uri: cmdUri,
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01',
				 'Authorization': 'Bearer ' + stApiKey.trim()]
	]
	httpGet(sendCmdParams) {resp ->
		def data = resp.data.components.main
		if (resp.status == 200) {
			if (reqType == "status") {
				def inputSource = data.mediaInputSource.inputSource.value
				if (inputSource != device.currentValue("inputSource")) {
					sendEvent(name: "inputSource", value: inputSource)
				}
				def tvChannel = data.tvChannel.tvChannel.value
				def tvChannlName = data.tvChannel.tvChannelName.value
				if (tvChannel != getDataValue("tvChannel")) {
					sendEvent(name: "tvChannel", value: tvChannel)
					sendEvent(name: "tvChannelName", value: tvChannelName)
				}
				logDebug("getStDeviceData: source = ${inputSource}, channel = ${tvChannel}")
			} else if (reqType == "setup") {
				def inputSources = data.mediaInputSource.supportedInputSources.value
				sendEvent(name: "inputSources", value: inputSources)
				logDebug("getStDeviceData: inputSources = ${inputSources}")
			}
		} else { logWarn{"getStDeviceData: Invalid resp status.  Status = ${resp.status}"} }
	}
}
private sendStPost(cap, cmd, args = null){
	if (!stDeviceId || !stApiKey) {
		logWarn("sendStPost: no stApiKey or stDeviceId")
		return
	}
	logDebug("sendStGet: ${comp} / ${cap}/ ${cmd}/ ${args} / ${source}")
	def cmdUri =  "https://api.smartthings.com/v1/devices/${stDeviceId.trim()}/commands"
	def cmd1 = [
		component: "main",
		capability: cap,
		command: cmd,
		arguments: args
	]
	def cmdBody = [commands: [cmd1]]
	def sendCmdParams = [
		uri: cmdUri,
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01',
				 'Authorization': 'Bearer ' + stApiKey.trim()],
		body : new groovy.json.JsonBuilder(cmdBody).toString()
	]
	try {
		httpPost(sendCmdParams) {resp ->
			def data = resp.data
			if (resp.status == 200) {
				log.trace data.results[0].status
				if (data.results[0].status == "ACCEPTED") {
					getStDeviceData()
				} else {
					logWarn("sendStPost: Status = ${data.results.status}")
				}
	
			} else { logWarn{": Invalid resp status.  Status = ${resp.status}"} }
		}
	} catch (e) { logWarn("sendStPost: Invalid Argument. error = ${e}") }
}

//	===== Capability Samsung TV =====
def on() {
	logDebug("on: desired TV Mode = ${tvPwrOnMode}")
	def newMac = getDataValue("deviceMac").replaceAll(":","").replaceAll("-","")
	def wol = new hubitat.device.HubAction ("wake on lan $newMac",
											hubitat.device.Protocol.LAN,
											null)
	sendHubCommand(wol)
	if(tvPwrOnMode == "ART_MODE" && getDataValue("frameTv") == "true") {
		artMode("on") }
	else if(tvPwrOnMode == "Ambient") { ambientMode() }
	else { connect("remote") }
	
	runIn(1, refresh)
}
def off() {
	sendEvent(name: "switch", value: "off")
	if (getDataValue("frameTv") == "false") { sendKey("POWER") }
	else {
		sendKey("POWER", "Press")
		pauseExecution(3000)
		sendKey("POWER", "Release")
	}
	runIn(1, refresh)
	runIn(3, close)
}

def mute() { 
	sendKey("MUTE")
	runIn(5, getMute)
}
def unmute() {
	sendKey("MUTE")
	runIn(5, getMute)
}
def getMute() {
	logDebug("getMute")
	sendCmd("RenderingControl",
			"GetMute",
			["InstanceID" :0,
			 "Channel": "Master"])
}
def updateMuteStatus(body) {
	def status = body.CurrentMute.text()
	def mute = "unmuted"
	if (status == "1") { mute = "muted" }
	sendEvent(name: "mute", value: mute)
	logDebug("updateMuteStatus.GetMuteStatus: ${mute}")
}

def setVolume(volume) {
	logDebug("setVolume: volume = ${volume}")
	volume = volume.toInteger()
	if (volume <= 0 || volume >= 100) { return }
	sendCmd("RenderingControl",
			"SetVolume",
			["InstanceID" :0,
			 "Channel": "Master",
			 "DesiredVolume": volume])
}
def volumeUp() {
	sendKey("VOLUP")
	runIn(3, getVolume)
}
def volumeDown() {
	sendKey("VOLDOWN")
	runIn(3, getVolume)
}
def getVolume() {
	logDebug("getVolume")
	sendCmd("RenderingControl",
			"GetVolume",
			["InstanceID" :0,
			 "Channel": "Master"])
}
def updateVolume(body) {
	def status = body.CurrentVolume.text()
	sendEvent(name: "volume", value: status.toInteger())
	logDebug("updateVolume: volume = ${status}")
}

//	===== Quick Polling Capability =====
def setPollInterval(interval) {
	logDebug("setPollInterval: interval = ${interval}")
	if (interval == "off") {
		state.quickPoll = false
		state.pollInterval = "off"
		state.remove("WARNING")
		unschedule(quickPoll)
	} else {
		state.quickPoll = true
		state.pollInterval = interval
		schedule("*/${interval} * * * * ?",  quickPoll)
		logWarn("setPollInterval: polling interval set to ${interval} seconds.\n" +
				"Quick Polling can have negative impact on the Hubitat Hub performance. " +
			    "If you encounter performance problems, try turning off quick polling.")
		state.WARNING = "<b>Quick Polling can have negative impact on the Hubitat " +
						"Hub and network performance.</b>  If you encounter performance " +
				    	"problems, <b>before contacting Hubitat support</b>, turn off quick " +
				    	"polling and check your sysem out."
	}
}
def quickPoll() {
	try {
		httpGet([uri: "http://${deviceIp}:9197/dmr", timeout: 5]) { resp ->
			if (device.currentValue("switch") != "on") {
				sendEvent(name: "switch", value: "on")
			}
			return "on"
		}
	} catch (error) {
		if (device.currentValue("switch") != "off") {
			sendEvent(name: "switch", value: "off")
			close()
		}
		return "off"
	}
}

def setPictureMode(data) { logDebug("setPictureMode: not implemented") }
def setSoundMode(data) { logDebug("setSoundMode: not implemented") }
def showMessage(d,d1,d2,d3) { logDebug("showMessage: not implemented") }

def play() {
	logDebug("play")
	if (device.currentValue("status") == "no media") {
		sendKey("ENTER")
	} else {
		sendCmd("AVTransport",
				"Play",
				["InstanceID" :0,
				 "Speed": "1"])
	}
}
def pause() {
	logDebug("pause")
	if (device.currentValue("status") == "no media") {
		sendKey("ENTER")
	} else {
		sendCmd("AVTransport",
				"Pause",
				["InstanceID" :0,
				 "Speed": "1"])
	}
}
def stop() {
	logDebug("stop")
	if (device.currentValue("status") == "no media") {
		sendKey("ENTER")
	} else {
		sendCmd("AVTransport",
				"Stop",
				["InstanceID" :0,
				 "Speed": "1"])
	}
}
def getPlayStatus() {
	logDebug("getPlayStatus")
	sendCmd("AVTransport",
			"GetTransportInfo",
			["InstanceID" :0])
}
def updatePlayStatus(body) {
	def status = body.CurrentTransportState.text()
	switch(status) {
		case "PLAYING":
			status = "playing"
			break
		case "PAUSED_PLAYBACK":
			status = "paused"
			break
		case "STOPPED":
			status = "stopped"
			break
		default:
			status = "no media"
	}
	sendEvent(name: "status", value: status)
	logDebug("updatePlayStatus: ${status}")
}

//	===== TTS Notification =====
def speak(text) {
	logDebug("speak: text = ${text}")
	def track = convertToTrack(text)
	addToQueue(track.uri, track.duration)
}
def deviceNotification(text) { speak(text) }
def convertToTrack(text) {
	def uriText = URLEncoder.encode(text, "UTF-8").replaceAll(/\+/, "%20")
	trackUri = "http://api.voicerss.org/?" +
		"key=${ttsApiKey.trim()}" +
		"&f=48khz_16bit_mono" +
		"&c=MP3" +
		"&hl=${ttsLang}" +
		"&src=${uriText}"
	def duration = (1 + text.length() / 10).toInteger()
	return [uri: trackUri, duration: duration]
}

def addToQueue(trackUri, duration){
	logDebug("addToQueue: ${trackUri},${duration}") 
	duration = duration + 1
	playData = ["trackUri": trackUri, 
				"duration": duration]
	state.playQueue.add(playData)

	if (state.speaking == false) {
		state.speaking = true
		runInMillis(100, startPlayViaQueue)
	}
}
def startPlayViaQueue() {
	logDebug("startPlayViaQueue: queueSize = ${state.playQueue.size()}")
	if (state.playQueue.size() == 0) { return }
	playViaQueue()
}
def playViaQueue() {
	logDebug("playViaQueue: queueSize = ${state.playQueue.size()}")
	if (state.playQueue.size() == 0) {
		resumePlayer()
		return
	}
	def playData = state.playQueue.get(0)
	state.playQueue.remove(0)
	logDebug("playViaQueue: playData = ${playData}")

	execPlay(playData.trackUri)
	runIn(playData.duration, resumePlayer)
	runIn(30, kickStartQueue)
}
def execPlay(trackUri) {
	sendCmd("AVTransport",
			"SetAVTransportURI",
			[InstanceID: 0,
			 CurrentURI: trackUri,
			 CurrentURIMetaData: ""])
}
def resumePlayer() {
	if (state.playQueue.size() > 0) {
		playViaQueue()
		return
	}
	logDebug("resumePlayer")
	state.speaking = false
}
def kickStartQueue() {
	logInfo("kickStartQueue")
	if (state.playQueue.size() > 0) {
		resumePlayer()
	} else {
		state.speaking = false
	}
}

//	========== Capability Refresh ==========
def refresh() {
	def onOff = quickPoll()
	logDebug("refresh: device is ${onOff}")
	if (onOff == "on") {
		runInMillis(300, getVolume)
		runInMillis(600, getPlayStatus)
		runInMillis(900, getMute)
		if (stApiKey && stDeviceId) {
			getStDeviceData("status")
		}
	}
}

//	===== Samsung Smart Remote Keys =====
def sendKey(key, cmd = "Click") {
	key = "KEY_${key.toUpperCase()}"
	def data = [method:"ms.remote.control",
				params:[Cmd:"${cmd}",
						DataOfCmd:"${key}",
						TypeOfRemote:"SendRemoteKey"]]
	sendMessage("remote", JsonOutput.toJson(data) )
}
//	TV Art Display
def artModeOn() {
	artMode("on")
}
def artModeOff() {
	artMode("off")
}
def artMode(onOff) {
	logDebug("artMode: ${onOff}")
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
def ambientMode() { sendKey("AMBIENT") }
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
	runIn(10, getStDeviceStatus)
}
def hdmi() {
	sendKey("HDMI")
	runIn(5, getStDeviceStatus)
}
def setInputSource(inputSource) {
	sendStPost("mediaInputSource", "setInputSource", args = [inputSource])
}
//	TV Channel
def channelList() { sendKey("CH_LIST") }
def channelUp() { sendKey("CHUP") }
def channelDown() { sendKey("CHDOWN") }
def previousChannel() { sendKey("PRECH") }
def setTvChannel(tvChannel) {
	sendStPost("tvChannel", "setTvChannel", args = ["11"])
}
//	Playing Navigation Commands
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
//	Application Access/Control
def browser() { sendKey("CONVERGENCE") }
def youTube() {
	def url = "http://${deviceIp}:8080/ws/apps/YouTube"
	httpPost(url, "") { resp ->
		logDebug("youTube:  ${resp.status}  ||  ${resp.data}")
	}
}
def netflix() {
	def url = "http://${deviceIp}:8080/ws/apps/Netflix"
	httpPost(url, "") { resp ->
		logDebug("netflix:  ${resp.status}  ||  ${resp.data}")
	}
}
def appInstall(appId) {
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
def appOpen(appId) {
	def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}"
	try {
		httpPost(uri, body) { resp ->
			logDebug("appOpen: appId = ${appId}, Success.")
		}
	} catch (e) {
		logWarn("appOpen: appId = ${appId}, FAILED: ${e}")
		return
	}
	runIn(5, appGetData, [data: appId]) 
}
def appGetData(appId) {
	def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}"
	try {
		httpGet(uri) { resp -> 
			state.currentAppId = resp.data.id
			logDebug("appGetData: appId = ${resp.data.id}")
		}
	} catch (e) {
		state.latestAppData = [id: appId]
		logWarn("appGetData: appId = ${appId}, FAILED: ${e}")
	}
}
def appClose() {
	def appId = state.currentAppId
	logDebug("appClose: appId = ${appId}")
	def uri = "http://${deviceIp}:8001/api/v2/applications/${appId}"
	try { httpDelete([uri: uri]) { resp -> }
	} catch (e) {}
	state.currentAppId = null
}

//	===== Button Interface (facilitates dashboard integration) =====
def push(pushed) {
	logDebug("push: button = ${pushed}, trigger = ${state.triggered}")
	if (pushed == null) {
		logWarn("push: pushed is null.  Input ignored")
		return
	}
	sendEvent(name: "pushed", value: pushed)
	pushed = pushed.toInteger()
	switch(pushed) {
		case 0 : close(); break
		//	===== Physical Remote Commands =====
		case 3 : numericKeyPad(); break
		case 5 : artModeOn(); break			//	New command.  Toggles art mode
		case 6 : artModeOff(); break			//	New command.  Toggles art mode
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
		case 38: browser(); break		//	Direct to source 1 (ofour right of TV on menu)
		case 39: youTube(); break
		case 40: netflix(); break
		default:
			logDebug("push: Invalid Button Number!")
			break
	}
}
//	===== Logging =====
def logInfo(msg) { 
	if (infoLog == true) {
		log.info "${driverVer()} || ${msg}"
	}
}
def debugLogOff() {
	device.updateSetting("debugLog", [type:"bool", value: false])
	logInfo("Debug logging is false.")
}
def logDebug(msg) {
	if (debugLog == true) {
		log.debug "${driverVer()} || ${msg}"
	}
}
def logWarn(msg) { log.warn "${driverVer()} || ${msg}" }