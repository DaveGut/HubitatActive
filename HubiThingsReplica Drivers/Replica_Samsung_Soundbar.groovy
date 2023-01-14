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
//	Sample Audio Notifications
	"Bell 1": [uri: "http://s3.amazonaws.com/smartapp-media/sonos/bell1.mp3", duration: "10"]
	"Bell 2": [uri: "http://s3.amazonaws.com/smartapp-media/sonos/bell2.mp3", duration: "10"]
	"Dogs Barking": [uri: "http://s3.amazonaws.com/smartapp-media/sonos/dogs.mp3", duration: "10"]
	"Fire Alarm": [uri: "http://s3.amazonaws.com/smartapp-media/sonos/alarm.mp3", duration: "17"]
	"The mail has arrived": [uri: "http://s3.amazonaws.com/smartapp-media/sonos/the+mail+has+arrived.mp3", duration: "1"]
	"A door opened": [uri: "http://s3.amazonaws.com/smartapp-media/sonos/a+door+opened.mp3", duration: "1"]
	"There is motion": [uri: "http://s3.amazonaws.com/smartapp-media/sonos/there+is+motion.mp3", duration: "1"]
	"Someone is arriving": [uri: "http://s3.amazonaws.com/smartapp-media/sonos/someone+is+arriving.mp3", duration: "1"]
	"Piano": [uri: "http://s3.amazonaws.com/smartapp-media/sonos/piano2.mp3", duration: "10"]
	"Lightsaber": [uri: "http://s3.amazonaws.com/smartapp-media/sonos/lightsaber.mp3", duration: "10"]
*/
//@SuppressWarnings('unused')
import org.json.JSONObject
import groovy.json.JsonOutput
//import groovy.json.JsonSlurper
//import groovy.transform.CompileStatic
//import groovy.transform.Field
//@Field volatile static Map<String,Long> g_mEventSendTime = [:]
def driverVer() { return "0.1.1" }

metadata {
	definition (name: "Replica Samsung Soundbar",
				namespace: "replica",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Soundbar.groovy"
			   ){
		capability "Switch"
		capability "MediaInputSource"
		command "toggleInputSource"
		capability "MediaTransport"
		capability "AudioVolume"
		capability "AudioNotification"
		attribute "audioTrackData", "JSON_OBJECT"
		capability "Refresh"
		capability "Configuration"
//		command "eventHandler", [[name: "For App Use Only"]]
		attribute "healthStatus", "enum", ["offline", "online"]
		command "poll"
	}
	preferences {
		input ("logEnable", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false)
		input ("infoLog", "bool", title: "Enable information logging",defaultValue: true)
		input ("traceLog", "bool", title: "Enable trace logging as directed by developer", defaultValue: false)
	}
}

def installed() {
	runIn(1, updated)
}

def updated() {
	unschedule()
	def updStatus = [:]
	initialize()
	if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
		updStatus << [driverVer: driverVer()]
	}
	if (logEnable) { runIn(1800, debugLogOff) }
	if (traceLog) { runIn(600, traceLogOff) }
	updStatus << [logEnable: logEnable, infoLog: infoLog, traceLog: traceLog]
	runEvery5Minutes(poll)
	updStatus << [pollInterval: "5 Minutes"]

	runIn(10, refresh)
	pauseExecution(5000)
	listAttributes(true)
	logInfo("updated: ${updStatus}")
}

def initialize() {
	setAutoAttributes()
    updateDataValue("triggers", groovy.json.JsonOutput.toJson(getReplicaTriggers()))
    updateDataValue("commands", groovy.json.JsonOutput.toJson(getReplicaCommands()))
	logInfo("initialize: initialize device-specific data")
}

def setAutoAttributes() {
	state.autoAttributes = ["switch", "volume", "mute", "audioTrackData"]
}

Map getReplicaCommands() {
    return (["replicaEvent":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]], 
			 "replicaStatus":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]], 
			 "replicaHealth":[[name:"parent*",type:"OBJECT"],[name:"health*",type:"JSON_OBJECT"]],
			 "setHealthStatusValue":[[name:"healthStatus*",type:"ENUM"]]])
}

Map getReplicaTriggers() {
	def replicaTriggers = [
		off:[],
		on:[],
		setInputSource: [[name:"mode*", type: "ENUM"]],//////////
		play:[],
		pause:[],
		stop:[],
		volumeUp:[],
		volumeDown:[],
		setVolume: [[name:"volume*", type: "NUMBER"]],
		mute:[],
		unmute:[],
		playTrack:[
			[name:"uri*", type: "STRING"],
			[name:"level", type:"NUMBER"]],
		playTrackAndRestore:[
			[name:"uri*", type: "STRING"],
			[name:"level", type:"NUMBER"]],
		playTrackAndResume:[
			[name:"uri*", type: "STRING"],
			[name:"level", type:"NUMBER"]],
		refresh:[]]
	return replicaTriggers
}

def configure() {
    initialize()
	setReplicaRules()
	sendCommand("configure")
	logTrace("configure: configuring default rules")
}

String setReplicaRules() {
	def rules = """{"version":1,"components":[
{"trigger":{"name":"mute","label":"command: mute()","type":"command"},"command":{"name":"mute","type":"command","capability":"audioMute","label":"command: mute()"},"type":"hubitatTrigger"},
{"trigger":{"name":"off","label":"command: off()","type":"command"},"command":{"name":"off","type":"command","capability":"switch","label":"command: off()"},"type":"hubitatTrigger"},
{"trigger":{"name":"on","label":"command: on()","type":"command"},"command":{"name":"on","type":"command","capability":"switch","label":"command: on()"},"type":"hubitatTrigger"},
{"trigger":{"name":"pause","label":"command: pause()","type":"command"},"command":{"name":"pause","type":"command","capability":"mediaPlayback","label":"command: pause()"},"type":"hubitatTrigger"},
{"trigger":{"name":"play","label":"command: play()","type":"command"},"command":{"name":"play","type":"command","capability":"mediaPlayback","label":"command: play()"},"type":"hubitatTrigger"},
{"trigger":{"name":"playTrack","label":"command: playTrack(uri*, level)","type":"command","parameters":[{"name":"uri*","type":"STRING"},{"name":"level","type":"NUMBER"}]},"command":{"name":"playTrack","arguments":[{"name":"uri","optional":false,"schema":{"title":"URI","type":"string","format":"uri"}},{"name":"level","optional":true,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioNotification","label":"command: playTrack(uri*, level)"},"type":"hubitatTrigger"},
{"trigger":{"name":"playTrackAndRestore","label":"command: playTrackAndRestore(uri*, level)","type":"command","parameters":[{"name":"uri*","type":"STRING"},{"name":"level","type":"NUMBER"}]},"command":{"name":"playTrackAndRestore","arguments":[{"name":"uri","optional":false,"schema":{"title":"URI","type":"string","format":"uri"}},{"name":"level","optional":true,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioNotification","label":"command: playTrackAndRestore(uri*, level)"},"type":"hubitatTrigger"},
{"trigger":{"name":"playTrackAndResume","label":"command: playTrackAndResume(uri*, level)","type":"command","parameters":[{"name":"uri*","type":"STRING"},{"name":"level","type":"NUMBER"}]},"command":{"name":"playTrackAndResume","arguments":[{"name":"uri","optional":false,"schema":{"title":"URI","type":"string","format":"uri"}},{"name":"level","optional":true,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioNotification","label":"command: playTrackAndResume(uri*, level)"},"type":"hubitatTrigger"},
{"trigger":{"name":"refresh","label":"command: refresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},
{"trigger":{"name":"setVolume","label":"command: setVolume(volume*)","type":"command","parameters":[{"name":"volume*","type":"NUMBER"}]},"command":{"name":"setVolume","arguments":[{"name":"volume","optional":false,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioVolume","label":"command: setVolume(volume*)"},"type":"hubitatTrigger"},
{"trigger":{"name":"stop","label":"command: stop()","type":"command"},"command":{"name":"stop","type":"command","capability":"mediaPlayback","label":"command: stop()"},"type":"hubitatTrigger"},
{"trigger":{"name":"unmute","label":"command: unmute()","type":"command"},"command":{"name":"unmute","type":"command","capability":"audioMute","label":"command: unmute()"},"type":"hubitatTrigger"},
{"trigger":{"name":"volumeDown","label":"command: volumeDown()","type":"command"},"command":{"name":"volumeDown","type":"command","capability":"audioVolume","label":"command: volumeDown()"},"type":"hubitatTrigger"},
{"trigger":{"name":"volumeUp","label":"command: volumeUp()","type":"command"},"command":{"name":"volumeUp","type":"command","capability":"audioVolume","label":"command: volumeUp()"},"type":"hubitatTrigger"},
{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger"},
{"trigger":{"name":"setInputSource","label":"command: setInputSource(mode*)","type":"command","parameters":[{"name":"inputSource*","type":"string"}]},"command":{"name":"setInputSource","arguments":[{"name":"mode","optional":false,"schema":{"title":"MediaSource","enum":["AM","CD","FM","HDMI","HDMI1","HDMI2","HDMI3","HDMI4","HDMI5","HDMI6","digitalTv","USB","YouTube","aux","bluetooth","digital","melon","wifi"],"type":"string"}}],"type":"command","capability":"mediaInputSource","label":"command: setInputSource(mode*)"},"type":"hubitatTrigger"}]}"""
	updateDataValue("rules", rules)
//	def rules = """{"version":1,"components":[{"trigger":{"name":"mute","label":"command: mute()","type":"command"},"command":{"name":"mute","type":"command","capability":"audioMute","label":"command: mute()"},"type":"hubitatTrigger"},{"trigger":{"name":"off","label":"command: off()","type":"command"},"command":{"name":"off","type":"command","capability":"switch","label":"command: off()"},"type":"hubitatTrigger"},{"trigger":{"name":"on","label":"command: on()","type":"command"},"command":{"name":"on","type":"command","capability":"switch","label":"command: on()"},"type":"hubitatTrigger"},{"trigger":{"name":"pause","label":"command: pause()","type":"command"},"command":{"name":"pause","type":"command","capability":"mediaPlayback","label":"command: pause()"},"type":"hubitatTrigger"},{"trigger":{"name":"play","label":"command: play()","type":"command"},"command":{"name":"play","type":"command","capability":"mediaPlayback","label":"command: play()"},"type":"hubitatTrigger"},{"trigger":{"name":"playTrack","label":"command: playTrack(uri*, level)","type":"command","parameters":[{"name":"uri*","type":"STRING"},{"name":"level","type":"NUMBER"}]},"command":{"name":"playTrack","arguments":[{"name":"uri","optional":false,"schema":{"title":"URI","type":"string","format":"uri"}},{"name":"level","optional":true,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioNotification","label":"command: playTrack(uri*, level)"},"type":"hubitatTrigger"},{"trigger":{"name":"playTrackAndRestore","label":"command: playTrackAndRestore(uri*, level)","type":"command","parameters":[{"name":"uri*","type":"STRING"},{"name":"level","type":"NUMBER"}]},"command":{"name":"playTrackAndRestore","arguments":[{"name":"uri","optional":false,"schema":{"title":"URI","type":"string","format":"uri"}},{"name":"level","optional":true,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioNotification","label":"command: playTrackAndRestore(uri*, level)"},"type":"hubitatTrigger"},{"trigger":{"name":"playTrackAndResume","label":"command: playTrackAndResume(uri*, level)","type":"command","parameters":[{"name":"uri*","type":"STRING"},{"name":"level","type":"NUMBER"}]},"command":{"name":"playTrackAndResume","arguments":[{"name":"uri","optional":false,"schema":{"title":"URI","type":"string","format":"uri"}},{"name":"level","optional":true,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioNotification","label":"command: playTrackAndResume(uri*, level)"},"type":"hubitatTrigger"},{"trigger":{"name":"refresh","label":"command: refresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},{"trigger":{"name":"setVolume","label":"command: setVolume(volume*)","type":"command","parameters":[{"name":"volume*","type":"NUMBER"}]},"command":{"name":"setVolume","arguments":[{"name":"volume","optional":false,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioVolume","label":"command: setVolume(volume*)"},"type":"hubitatTrigger"},{"trigger":{"name":"stop","label":"command: stop()","type":"command"},"command":{"name":"stop","type":"command","capability":"mediaPlayback","label":"command: stop()"},"type":"hubitatTrigger"},{"trigger":{"name":"unmute","label":"command: unmute()","type":"command"},"command":{"name":"unmute","type":"command","capability":"audioMute","label":"command: unmute()"},"type":"hubitatTrigger"},{"trigger":{"name":"volumeDown","label":"command: volumeDown()","type":"command"},"command":{"name":"volumeDown","type":"command","capability":"audioVolume","label":"command: volumeDown()"},"type":"hubitatTrigger"},{"trigger":{"name":"volumeUp","label":"command: volumeUp()","type":"command"},"command":{"name":"volumeUp","type":"command","capability":"audioVolume","label":"command: volumeUp()"},"type":"hubitatTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"MuteState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"audioMute","attribute":"mute","label":"attribute: mute.*"},"command":{"name":"setMuteValue","label":"command: setMuteValue(mute)","type":"command","parameters":[{"name":"mute","type":"ENUM"}]},"type":"smartTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"title":"SwitchState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"switch","attribute":"switch","label":"attribute: switch.*"},"command":{"name":"setSwitchValue","label":"command: setSwitchValue(switch*)","type":"command","parameters":[{"name":"switch*","type":"ENUM"}]},"type":"smartTrigger"},{"trigger":{"type":"attribute","properties":{"value":{"type":"string"}},"additionalProperties":false,"required":[],"capability":"mediaPlayback","attribute":"playbackStatus","label":"attribute: playbackStatus.*"},"command":{"name":"setTransportStatusValue","label":"command: setTransportStatusValue(playbackStatus*)","type":"command","parameters":[{"name":"playbackStatus*","type":"ENUM"}]},"type":"smartTrigger"},{"trigger":{"title":"IntegerPercent","type":"attribute","properties":{"value":{"type":"integer","minimum":0,"maximum":100},"unit":{"type":"string","enum":["%"],"default":"%"}},"additionalProperties":false,"required":["value"],"capability":"audioVolume","attribute":"volume","label":"attribute: volume.*"},"command":{"name":"setVolumeValue","label":"command: setVolumeValue(volume*)","type":"command","parameters":[{"name":"volume*","type":"NUMBER"}]},"type":"smartTrigger"}]}"""
}

//	===== Event Parse Interface s=====
void replicaStatus(def parent=null, Map status=null) {
	def logData = [parent: parent, status: status]
	if (status != null && !state.inputSources) {
		def initialAttributes = setInitialAttributes(status.components.main)
		logData << [initialAttributes: initialAttributes]
	}
	if (logTrace) {
		logTrace("replicaStatus: ${logData}")
	} else {
		logDebug("replicaStatus: ${logData}")
	}
}

def setInitialAttributes(mainData) {
	def logData = [:]
	try {
		inputSources = mainData.mediaInputSource.supportedInputSources.value
	} catch(e) {
		inputSources = ["n/a"]
		pauseExecution(200)
	}
	state.inputSources = inputSources
	logData << ["state.inputSources": inputSources]

	sendEvent(name: "switch", value: mainData.switch.switch.value)
	logData << [switch: mainData.switch.switch.value]

	sendEvent(name: "volume", value: mainData.audioVolume.volume.value.toInteger())
	logData << [volume: mainData.audioVolume.volume.value.toInteger()]

	sendEvent(name: "mute", value: mainData.audioMute.mute.value)
	logData<< [mute: mainData.audioMute.mute.value]
	
	sendEvent(name: "transportStatus", value: mainData.mediaPlayback.playbackStatus.value)
	logData<< [mute: mainData.audioMute.mute.value]

	def inputSource
	try {
		inputSource = mainData.mediaInputSource.inputSource.value
	} catch(e) {
		inputSource = "n/a"
	}
	sendEvent(name: "mediaInputSource", value: inputSource)
	logData<< [mediaInputSource: inputSource]

	def audioTrackData
	try {
		audioTrackData = mainData.audioTrackData.audioTrackData.value
	} catch(e) {
		audioTrackData = "n/a"
	}
	sendEvent(name: "audioTrackData", value: audioTrackData)
	logData<< [audioTrackData: audioTrackData]

	return logData
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
	logTrace("replicaEvent: [parent: ${parent}, event: ${event}]")
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
			case "inputSource":
				if (event.capability == "mediaInputSource") {
					sendEvent(name: "mediaInputSource", value: event.value)
				}
				break
			case "playbackStatus":
				sendEvent(name: "transportStatus", value: event.value)
				break
			default:
				logDebug("parse_main: [unhandledEvent: ${event}]")
			break
		}
	}
	if (traceLog) {
		logTrace("parse_main: [event: ${event}]")
	} else {
		logDebug("parse_main: [event: ${event}]")
	}
}

//	Used for any rule-based commands
private def sendCommand(String name, def value=null, String unit=null, data=[:]) {
    parent?.deviceTriggerHandler(device, [name:name, value:value, unit:unit, data:data, now:now])
}

//	===== Samsung TV Commands =====
def poll() {
	deviceRefresh()
}

def refresh() {
	sendCommand("refresh")
}

def deviceRefresh() {
	def deviceId = new JSONObject(getDataValue("replica")).deviceId
	parent.setSmartDeviceCommand(deviceId, "main", "refresh", "refresh")
	sendCommand("deviceRefresh")
}

def on() { sendCommand("on") }

def off() { sendCommand("off") }

//	===== Media Input Source =====
def toggleInputSource() {
	if (state.inputSources) {
		def inputSources = state.inputSources
		def totalSources = inputSources.size()
		def currentSource = device.currentValue("mediaInputSource")
		def sourceNo = inputSources.indexOf(currentSource)
		def newSourceNo = sourceNo + 1
		if (newSourceNo == totalSources) { newSourceNo = 0 }
		def inputSource = inputSources[newSourceNo]
		setInputSource(inputSource)
	} else { 
		logWarn("toggleInputSource: [status: FAILED, reason: no state.inputSources, <b>correction: try running refresh</b>]")
	}
}

def setInputSource(inputSource) {
	if (inputSource == "n/a") {
		logWarn("setInputSource: [status: FAILED, reason: ST Device does not support input source]")
	} else {
		def inputSources = state.inputSources
		if (inputSources == null) {
			logWarn("setInputSource: [status: FAILED, reason: no state.inputSources, <b>correction: try running refresh</b>]")
		} else if (state.inputSources.contains(inputSource)) {
			sendCommand("setInputSource", inputSource)
		} else {
			logWarn("setInputSource: [status: FAILED, inputSource: ${inputSource}, inputSources: ${inputSources}]")
		}
	}
}

//	===== Media Transport =====
def play() {
	sendCommand("play")
	runIn(5, poll)
}

def pause() { 
	sendCommand("pause") 
	runIn(5, poll)
}

def stop() {
	sendCommand("stop")
	runIn(5, poll)
}

//	===== Audio Volume =====
def volumeUp() { sendCommand("volumeUp") }

def volumeDown() {sendCommand("volumeDown") }

def setVolume(volume) {
	if (volume == null) { volume = device.currentValue("volume").toInteger() }
	if (volume < 0) { volume = 0 }
	else if (volume > 100) { volume = 100 }
	sendCommand("setVolume", volume)
}

def mute() { sendCommand("mute") }

def unmute() { sendCommand("unmute") }

def playText(text, volume = null) {
	playTrack(textToSpeech(text), volume)
}

def playTrack(uri, volume = null) {
	if (volume == null) { volume = device.currentValue("volume") }
	def level = volume
	def test = sendCommand("playTrack", uri, null, [level:level])
	log.trace test
	logDebug("playTrack: [uri: ${uri}, Volume: ${volume}]")
}

def playTextAndRestore(text, volume = null) {
	playTrackAndRestore(textToSpeech(text), volume)
}

def playTrackAndRestore(uri, volume=null) {
	if (volume == null) { volume = device.currentValue("volume") }
	sendCommand("playTrackAndRestore", uri, null, [level:volume])
	logDebug("playTrackAndRestore: [uri: ${uri}, Volume: ${volume}]")
}

def playTextAndResume(text, volume = null) {
	playTrackAndRestore(textToSpeech(text), volume)
}

def playTrackAndResume(uri, volume=null) {
	if (volume == null) { volume = device.currentValue("volume") }
	sendCommand("playTrackAndResume", uri, null, [level:volume])
	logDebug("playTrackAndResume: [uri: ${uri}, Volume: ${volume}]")
}

//	===== Logging Data =====
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
	}
	logInfo("debugLogOff")
}
def logInfo(msg) { 
	if (infoLog) {
		log.info "${device.displayName}-${driverVer()}: ${msg}"
	}
}
def debugLogOff() {
	if (logEnable) {
		device.updateSetting("logEnable", [type:"bool", value: false])
	}
	logInfo("debugLogOff")
}
def logDebug(msg) {
	if (logEnable) {
		log.debug "${device.displayName}-${driverVer()}: ${msg}"
	}
}
def logWarn(msg) { log.warn "${device.displayName}-${driverVer()}: ${msg}" }

