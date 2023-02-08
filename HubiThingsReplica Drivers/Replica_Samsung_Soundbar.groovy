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
def driverVer() { return "1.0" }

metadata {
	definition (name: "Replica Samsung Soundbar",
				namespace: "replica",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/HubiThingsReplica%20Drivers/Replica_Samsung_Soundbar.groovy"
			   ){
		capability "Switch"
		capability "MediaInputSource"
		command "toggleInputSource"
		capability "MediaTransport"
		capability "AudioVolume"
		attribute "audioTrackData", "JSON_OBJECT"
		attribute "trackDescription", "STRING"
		capability "Refresh"
		capability "Configuration"
		attribute "healthStatus", "enum", ["offline", "online"]
		//	===== Audio Notification Function =====
		capability "AudioNotification"
		//	Test Phase Only
		command "testAudioNotify"
	}
	preferences {
		//	===== Audio Notification Preferences =====
		//	if !deviceIp, SmartThings Notify, else Local Notify
		//	Alt TTS Available under all conditions
		input ("altTts", "bool", title: "Use Alternate TTS Method", defalutValue: false)
		if (altTts) {
			input ("ttsApiKey", "string", title: "TTS Site Key", defaultValue: null,
				   description: "From http://www.voicerss.org/registration.aspx")
			input ("ttsLang", "enum", title: "TTS Language", options: ttsLanguages(), defaultValue: "en-us")
		}
		input ("deviceIp", "string", title: "Device IP. For Local Notification.")
		//	===== End Audio Notification Preferences =====
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
	clearQueue()
	runEvery15Minutes(kickStartQueue)

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

//	===== HubiThings Device Settings =====
Map getReplicaCommands() {
    return ([
		"replicaEvent":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]],
		"replicaStatus":[[name:"parent*",type:"OBJECT"],[name:"event*",type:"JSON_OBJECT"]],
//		"replicaHealth":[[name:"parent*",type:"OBJECT"],[name:"health*",type:"JSON_OBJECT"]],
		"setHealthStatusValue":[[name:"healthStatus*",type:"ENUM"]]])
}

Map getReplicaTriggers() {
	def replicaTriggers = [
		off:[],
		on:[],
		setInputSource: [[name:"inputName*", type: "STRING"]],
		play:[],
		pause:[],
		stop:[],
		volumeUp:[],
		volumeDown:[],
		setVolume: [[name:"volumelevel*", type: "NUMBER"]],
		mute:[],
		unmute:[],
		playTrack:[
			[name:"trackuri*", type: "STRING"],
			[name:"volumelevel", type:"NUMBER", data:"volumelevel"]],
		playTrackAndRestore:[
			[name:"trackuri*", type: "STRING"],
			[name:"volumelevel", type:"NUMBER", data:"volumelevel"]],
		playTrackAndResume:[
			[name:"trackuri*", type: "STRING"],
			[name:"volumelevel", type:"NUMBER", data:"volumelevel"]],
		refresh:[],
		deviceRefresh:[]
	]
	return replicaTriggers
}

def configure() {
    initialize()
	setReplicaRules()
	sendCommand("configure")
	logInfo("configure: configuring default rules")
}

String setReplicaRules() {
	def rules = """{"version":1,"components":[{"trigger":{"type":"attribute","properties":{"value":{"title":"HealthState","type":"string"}},"additionalProperties":false,"required":["value"],"capability":"healthCheck","attribute":"healthStatus","label":"attribute: healthStatus.*"},"command":{"name":"setHealthStatusValue","label":"command: setHealthStatusValue(healthStatus*)","type":"command","parameters":[{"name":"healthStatus*","type":"ENUM"}]},"type":"smartTrigger"},
{"trigger":{"name":"deviceRefresh","label":"command: deviceRefresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},
{"trigger":{"name":"mute","label":"command: mute()","type":"command"},"command":{"name":"mute","type":"command","capability":"audioMute","label":"command: mute()"},"type":"hubitatTrigger"},
{"trigger":{"name":"off","label":"command: off()","type":"command"},"command":{"name":"off","type":"command","capability":"switch","label":"command: off()"},"type":"hubitatTrigger"},
{"trigger":{"name":"on","label":"command: on()","type":"command"},"command":{"name":"on","type":"command","capability":"switch","label":"command: on()"},"type":"hubitatTrigger"},
{"trigger":{"name":"pause","label":"command: pause()","type":"command"},"command":{"name":"pause","type":"command","capability":"mediaPlayback","label":"command: pause()"},"type":"hubitatTrigger"},
{"trigger":{"name":"play","label":"command: play()","type":"command"},"command":{"name":"play","type":"command","capability":"mediaPlayback","label":"command: play()"},"type":"hubitatTrigger"},
{"trigger":{"name":"playTrack","label":"command: playTrack(trackuri*, volumelevel)","type":"command","parameters":[{"name":"trackuri*","type":"STRING"},{"name":"volumelevel","type":"NUMBER","data":"volumelevel"}]},"command":{"name":"playTrack","arguments":[{"name":"uri","optional":false,"schema":{"title":"URI","type":"string","format":"uri"}},{"name":"level","optional":true,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioNotification","label":"command: playTrack(uri*, level)"},"type":"hubitatTrigger"},
{"trigger":{"name":"playTrackAndRestore","label":"command: playTrackAndRestore(trackuri*, volumelevel)","type":"command","parameters":[{"name":"trackuri*","type":"STRING"},{"name":"volumelevel","type":"NUMBER","data":"volumelevel"}]},"command":{"name":"playTrackAndRestore","arguments":[{"name":"uri","optional":false,"schema":{"title":"URI","type":"string","format":"uri"}},{"name":"level","optional":true,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioNotification","label":"command: playTrackAndRestore(uri*, level)"},"type":"hubitatTrigger"},
{"trigger":{"name":"playTrackAndResume","label":"command: playTrackAndResume(trackuri*, volumelevel)","type":"command","parameters":[{"name":"trackuri*","type":"STRING"},{"name":"volumelevel","type":"NUMBER","data":"volumelevel"}]},"command":{"name":"playTrackAndResume","arguments":[{"name":"uri","optional":false,"schema":{"title":"URI","type":"string","format":"uri"}},{"name":"level","optional":true,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioNotification","label":"command: playTrackAndResume(uri*, level)"},"type":"hubitatTrigger"},
{"trigger":{"name":"refresh","label":"command: refresh()","type":"command"},"command":{"name":"refresh","type":"command","capability":"refresh","label":"command: refresh()"},"type":"hubitatTrigger"},
{"trigger":{"name":"setVolume","label":"command: setVolume(volumelevel*)","type":"command","parameters":[{"name":"volumelevel*","type":"NUMBER"}]},"command":{"name":"setVolume","arguments":[{"name":"volume","optional":false,"schema":{"type":"integer","minimum":0,"maximum":100}}],"type":"command","capability":"audioVolume","label":"command: setVolume(volume*)"},"type":"hubitatTrigger"},
{"trigger":{"name":"setInputSource","label":"command: setInputSource(inputSource*)","type":"command","parameters":[{"name":"inputName*","type":"string"}]},"command":{"name":"setInputSource","arguments":[{"name":"mode","optional":false,"schema":{"title":"MediaSource","enum":["AM","CD","FM","HDMI","HDMI1","HDMI2","HDMI3","HDMI4","HDMI5","HDMI6","digitalTv","USB","YouTube","aux","bluetooth","digital","melon","wifi"],"type":"string"}}],"type":"command","capability":"mediaInputSource","label":"command: setInputSource(mode*)"},"type":"hubitatTrigger"},
{"trigger":{"name":"stop","label":"command: stop()","type":"command"},"command":{"name":"stop","type":"command","capability":"mediaPlayback","label":"command: stop()"},"type":"hubitatTrigger"},
{"trigger":{"name":"unmute","label":"command: unmute()","type":"command"},"command":{"name":"unmute","type":"command","capability":"audioMute","label":"command: unmute()"},"type":"hubitatTrigger"},
{"trigger":{"name":"volumeDown","label":"command: volumeDown()","type":"command"},"command":{"name":"volumeDown","type":"command","capability":"audioVolume","label":"command: volumeDown()"},"type":"hubitatTrigger"},
{"trigger":{"name":"volumeUp","label":"command: volumeUp()","type":"command"},"command":{"name":"volumeUp","type":"command","capability":"audioVolume","label":"command: volumeUp()"},"type":"hubitatTrigger"}]}"""

	updateDataValue("rules", rules)
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
	logDebug("refreshAttributes: ${mainData}")
	def value
	try {
		value = mainData.mediaInputSource.supportedInputSources.value
	} catch(e) {
		value = ["n/a"]
		pauseExecution(200)
	}
	parse_main([attribute: "supportedInputSources", value: value])
	pauseExecution(200)
	
	parse_main([attribute: "switch", value: mainData.switch.switch.value])
	pauseExecution(200)

	parse_main([attribute: "volume", value: mainData.audioVolume.volume.value.toInteger(), unit: "%"])
	pauseExecution(200)

	parse_main([attribute: "mute", value: mainData.audioMute.mute.value])
	pauseExecution(200)

	parse_main([attribute: "playbackStatus", value: mainData.mediaPlayback.playbackStatus.value])
	pauseExecution(200)

	try {
		value = mainData.mediaInputSource.inputSource.value
	} catch(e) {
		value = "n/a"
	}
	parse_main([attribute: "inputSource", value: value])
	pauseExecution(200)

	try {
		value = mainData.audioTrackData.audioTrackData.value
	} catch(e) {
		value = "n/a"
	}
	parse_main([attribute: "audioTrackData", value: value])
	
	state.refreshAttributes	= false
}

void replicaHealth(def parent=null, Map health=null) {
	if(parent) { logInfo("replicaHealth: ${parent?.getLabel()}") }
	if(health) { logInfo("replicaHealth: ${health}") }
}

void replicaEvent(def parent=null, Map event=null) {
	logDebug("replicaEvent: [parent: ${parent}, event: ${event}]")
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
		case "switch":
		case "mute":
			sendEvent(name: event.attribute, value: event.value)
			break
		case "audioTrackData":
			sendEvent(name: event.attribute, value: event.value)
			def title = " "
			if (event.value != "n/a" && event.value.title != null) {
				title = event.value.title
			}
			sendEvent(name: "trackDescription", value: title)
			break
		case "volume":
			sendEvent(name: event.attribute, value: event.value)
			sendEvent(name: "level", value: event.value)
			break
		case "inputSource":
			if (event.capability == "mediaInputSource") {
				sendEvent(name: "mediaInputSource", value: event.value)
			}
			break
		case "playbackStatus":
			sendEvent(name: "transportStatus", value: event.value)
			break
		case "supportedInputSources":
			state.inputSources = event.value
			break
		default:
			logDebug("parse_main: [unhandledEvent: ${event}]")
		break
	}
	logTrace("parse_main: [event: ${event}]")
}

//	===== HubiThings Send Command and Device Health =====
def setHealthStatusValue(value) {    
    sendEvent(name: "healthStatus", value: value)
}

private def sendCommand(String name, def value=null, String unit=null, data=[:]) {
	parent?.deviceTriggerHandler(device, [name:name, value:value, unit:unit, data:data, now:now])
}

def refresh() {
	state.refreshAttributes = true
	sendCommand("deviceRefresh")
	pauseExecution(500)
	sendCommand("refresh")
}

def deviceRefresh() {
	sendCommand("deviceRefresh")
}

//	===== Samsung Soundbar Commands =====
def on() {
	sendCommand("on")
}

def off() {
	sendCommand("off")
}

def setAttrSwitch(onOff) {
	sendEvent(name: "switch", value: onOff)
}

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
	runIn(5, deviceRefresh)
}

def pause() { 
	sendCommand("pause") 
	runIn(5, deviceRefresh)
}

def stop() {
	sendCommand("stop")
	runIn(5, deviceRefresh)
}

//	===== Audio Volume =====
def volumeUp() { sendCommand("volumeUp") }

def volumeDown() { sendCommand("volumeDown") }

def setVolume(volume) {
	if (volume == null) { volume = device.currentValue("volume").toInteger() }
	if (volume < 0) { volume = 0 }
	else if (volume > 100) { volume = 100 }
	sendCommand("setVolume", volume)
}

def mute() { sendCommand("mute") }

def unmute() { sendCommand("unmute") }

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
