/*	===== HUBITAT INTEGRATION VERSION =====================================================
Hubitat - Samsung TV Remote Driver Switch Only
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
===== 2022 History
02.22	Created Switch Only Version.

===========================================================================================*/
def driverVer() { return "1.0.0" }
//	Poll Timeout in seconds for user changes
import groovy.json.JsonOutput

metadata {
	definition (name: "Samsung TV Switch",
				namespace: "davegut",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungTvSwitch/SamsungTVSwitch.groovy"
			   ){
		capability "Switch"				//	On/Off
		capability "Polling"			//	Poll for on/off state of device / connected via wifi
	}
	preferences {
		input ("deviceIp", "text", title: "Samsung TV Ip", defaultValue: "")
		input ("tvWsToken", "text", 
			   title: "The WS Token for your TV (from previous Installation)",
			   defaultValue: state.token)
		input ("altWolMac", "bool", title: "Use alternate WOL MAC", defaultValue: false)
		input ("debugLog", "bool",  
			   title: "Enable debug logging for 30 minutes", defaultValue: false)
		input ("infoLog", "bool",  
			   title: "Enable description text logging", defaultValue: true)
	}
}

//	===== Installation, setup and update =====
def installed() {
	state.token = ""
	runIn(1, updated)
}

def updated() {
	logInfo("updated")
	unschedule()
	def updateData = [:]
	def status = "OK"
	def statusReason
	def deviceData
	if (deviceIp) {
		//	Get onOff status for use in setup
		updateData << [deviceIp: "deviceIp"]
		if (deviceIp != deviceIp.trim()) {
			deviceIp = deviceIp.trim()
			device.updateSetting("deviceIp", [type:"text", value: deviceIp])	
		}
		deviceData = getDeviceData()
	} else {
		logInfo("updated: [status: failed, statusReason: No device IP]")
		return
	}
	if (deviceData.status == "failed") {
		logInfo("updated: [status: failed, statusReason: Can not connect to TV]")
		sendEvent(name: "switch", value: "off")
		return
	} else { sendEvent(name: "switch", value: "on") }

	
	state.token = tvWsToken
	updateData << [tvToken: tvWsToken]
	if (debug) { runIn(1800, debugOff) }
	updateData << [debugLog: debugLog, infoLog: infoLog]
	updateData << [driver: versionUpdate()]
	def updateStatus = [:]
	updateStatus << [status: status]
	if (statusReason != "") {
		updateStatus << [statusReason: statusReason]
	}
	updateStatus << [updateData: updateData, deviceData: deviceData]
	logInfo("updated: ${updateStatus}")
}

def getDeviceData() {
	def deviceData = [:]
	try {
		httpGet([uri: "http://${deviceIp}:8001/api/v2/", timeout: 5]) { resp ->
			deviceData << [status: "OK"]
			def wifiMac = resp.data.device.wifiMac
			updateDataValue("deviceMac", wifiMac)
			deviceData << [mac: wifiMac]
			def alternateWolMac = wifiMac.replaceAll(":", "").toUpperCase()
			updateDataValue("alternateWolMac", alternateWolMac)
			deviceData << [alternateWolMac: alternateWolMac]
			def dni = getMACFromIP(deviceIp)
			device.setDeviceNetworkId(dni)
			deviceData << [dni: dni]
			def modelYear = "20" + resp.data.device.model[0..1]
			updateDataValue("modelYear", modelYear)
			deviceData << [modelYear: modelYear]
			def frameTv = "false"
			if (resp.data.device.FrameTVSupport) {
				frameTv = resp.data.device.FrameTVSupport
			}
			updateDataValue("frameTv", frameTv)
			deviceData << [frameTv: frameTv]
			
			def tokenSupport = false
			if (resp.data.device.TokenAuthSupport) {
				tokenSupport = resp.data.device.TokenAuthSupport
			}
			updateDataValue("tokenSupport", tokenSupport)
			deviceData << [tokenSupport: tokenSupport]
			def uuid = resp.data.device.duid.substring(5)
			updateDataValue("uuid", uuid)
			deviceData << [uuid: uuid]
		}
	} catch (error) {
		deviceData << [status: "failed", statusReason: [error: error]]
	}
	return deviceData
}

def versionUpdate() {
	if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
	}
	return driverVer()
}

def poll() {
	def onOff
	try {
		httpGet([uri: "http://${deviceIp}:8001/api/v2/", timeout: 5]) { resp ->
			onOff = "on"
		}
	} catch (error) {
		onOff = "off"
	}
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff)
		logDebug("poll: [switch: ${onOff}]")
	}
}

//	===== Commands =====
//	Switch
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
	sendEvent(name: "switch", value: "on")
	runIn(5, poll)
}

def off() {
	logDebug("off: frameTv = ${getDataValue("frameTv")}")
	if (getDataValue("frameTv") == "false") {
		sendKey("POWER")
	} else {
		sendKey("POWER", "Press")
		pauseExecution(3000)
		sendKey("POWER", "Release")
	}
	sendEvent(name: "switch", value: "off")
	runIn(30, poll)
}

//	===== WebSocket Interace
def sendKey(key, cmd = "Click") {
	key = "KEY_${key.toUpperCase()}"
	def data = [method:"ms.remote.control",
				params:[Cmd:"${cmd}",
						DataOfCmd:"${key}",
						Option: false,
						TypeOfRemote:"SendRemoteKey"]]
	sendMessage("remote", JsonOutput.toJson(data) )
}

def connect(funct) {
	logDebug("connect: function = ${funct}")
	def samsungMeth = "samsung.remote.control"
	if (funct == "frameArt") {
		samsungMeth = "com.samsung.art-app"
	}
	def url
	def name = "SHViaXRhdCBTYW1zdW5nIFJlbW90ZQ=="
	if (getDataValue("tokenSupport") == "true") {
		url = "wss://${deviceIp}:8002/api/v2/channels/${samsungMeth}?name=${name}&token=${state.token}"
	} else {
		url = "ws://${deviceIp}:8001/api/v2/channels/${samsungMeth}?name=${name}"
	}
	state.currentFunction = funct
	interfaces.webSocket.connect(url, ignoreSSLIssues: true, pingInterval: 60)
}

def sendMessage(funct, data) {
	logDebug("sendMessage: [function: ${funct}, connectType: ${state.currentFunction}, " +
			 "data: ${data}]")
	connect(funct)
	pauseExecution(400)
	interfaces.webSocket.sendMessage(data)
	runIn(30, close)
}

def close() {
	if (device.currentValue("switch") == "on") {
		interfaces.webSocket.close()
	} else {
		logDebug("close: Not executed. Device is off")
	}
}

def webSocketStatus(message) {
	logDebug("webSocketStatus: [message: ${message}]")
}

def parse(resp) {
	resp = parseJson(resp)
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
	} else if (event == "ms.channel.ready") {
		logMsg += ", webSocket connected"
	} else if (event == "ms.error") {
		logMsg += "Error Event.  Closing webSocket"
	} else {
		logMsg += ", message = ${resp}"
	}
	logDebug(logMsg)
}

//	===== Logging=====
def logTrace(msg){
	log.trace "[${device.label}, ${driverVer()}]:: ${msg}"
}

def logInfo(msg) { 
	if (infoLog == true) {
		log.info "[${device.label}, ${driverVer()}]:: ${msg}"
	}
}

def debugOff() {
	device.updateSetting("debugLog", [type:"bool", value: false])
	logInfo("Debug logging is false.")
}

def logDebug(msg) {
	if (debugLog == true) {
		log.debug "[${device.label}, ${driverVer()}]:: ${msg}"
	}
}

def logWarn(msg) { log.warn "[${device.label}, ${driverVer()}]:: ${msg}" }

//	End-of-File