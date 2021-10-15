/*	===== HUBITAT INTEGRATION VERSION =====================================================
Samsung WiFi Speaker Hubitat Driver
		Copyright 2018 Dave Gutheinz

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file
except in compliance with the License. You may obtain a copy of the License at:
		http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the
License is distributed on an  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied. See the License for the specific language governing permissions
and limitations under the  License.
===== DISCLAIMERS==========================================================================
		THE AUTHOR OF THIS INTEGRATION IS NOT ASSOCIATED WITH SAMSUNG. THIS CODE USES
		TECHNICAL DATA DERIVED FROM GITHUB SOURCES AND AS PERSONAL INVESTIGATION.
===== History =============================================================================
04.30.21	3.3.6	a.	Change sendSyncCommand timeout to 5 seconds.
					b.	Added on-line check / attribute.  if off-line, will not buffer
						TTS messages.
			3.3.7	a.	On now starts a check-connect that verifies connection for TTS.
						Off turns off this function and sets to not-connected.
					b.	Added check for all sendEvents to not send the event unless there
						is a change in the attribute.
					b.	Fixed null error in scheduling trace description.
			3.3.8	Added DLNA (playlist) playback support
					a.	Create using presetCreate with added parameter "Name"
					b.	Play using presetPlay with added parameter "ShuffleMode"
					c.	Recovers to beginning of current track after TTS messages.
===== HUBITAT INTEGRATION VERSION =======================================================*/
import org.json.JSONObject
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
def driverVer() { return "3.3.8" }

metadata {
	definition (name: "Samsung Wifi Speaker",
				namespace: "davegut",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungMultiroom/MultiRoomDriver.groovy"
			   ){
		capability "Switch"
		capability "Switch Level"
		capability "MusicPlayer"
		capability "AudioVolume"
		capability "SpeechSynthesis"
		capability "AudioNotification"
		capability "Refresh"
		capability "PushableButton"
		command "push", ["NUMBER"]
		//	=====	Samsung-specific Speaker Control Capability =====
		attribute "eqPreset", "string"
		attribute "inputSource", "string"
		attribute "repeat", "string"
		attribute "shuffle", "string"
		attribute "subMode", "string"
		command "equalPreset"
		command "inputSource"
		command "repeat"
		command "shuffle"
		command "stopAllActivity"
		attribute "isConnected", "string"
		//	===== Samsung Player Preset Capability =====
		attribute "Preset_1", "string"
		attribute "Preset_2", "string"
		attribute "Preset_3", "string"
		attribute "Preset_4", "string"
		attribute "Preset_5", "string"
		attribute "Preset_6", "string"
		attribute "Preset_7", "string"
		attribute "Preset_8", "string"
		command "presetCreate", [[name: "Preset Number", type: "NUMBER"],
								 [name: "Preset Name", type: "STRING"]]
		command "presetPlay", [[name: "Preset Number", type: "NUMBER"],
							   [name: "ShuffleMode",
								constraints: ["on", "off"], type: "ENUM"]]
		command "presetDelete", [[name: "Preset Number", type: "NUMBER"]]
		//	===== URL Play Preset Capability =====
		attribute "urlPreset_1", "string"
		attribute "urlPreset_2", "string"
		attribute "urlPreset_3", "string"
		attribute "urlPreset_4", "string"
		attribute "urlPreset_5", "string"
		attribute "urlPreset_6", "string"
		attribute "urlPreset_7", "string"
		attribute "urlPreset_8", "string"
		command "urlPresetCreate", [[name: "Url Preset Number", type: "NUMBER"],[name: "Preset Name", type: "STRING"]]
		command "urlPresetPlay", [[name: "Url Preset Number", type: "NUMBER"]]
		command "urlPresetDelete", [[name: "Url Preset Number", type: "NUMBER"]]
		//	===== Samsung Group Spealer Capability =====
		attribute "Group_1", "string"
		attribute "Group_2", "string"
		attribute "Group_3", "string"
		attribute "activeGroup", "string"
		command "groupCreate", [[name: "Group Number", type: "NUMBER"]]
		command "groupStart", [[name: "Group Number", type: "NUMBER"]]
		command "groupStop"
		command "groupDelete", [[name: "Group Number", type: "NUMBER"]]
		//	===== Queue Restart =====
		command "kickStartQueue"
		command "clearQueue"
	}
	preferences {
		def refreshRate = ["1" : "Refresh every 1 minute",
						   "5" : "Refresh every 5 minutes",
						   "15" : "Refresh every 15 minutes",
						   "30" : "Refresh every 30 minutes - RECOMMENDED"]
		def positions = ["fl": "stereo left",
						 "fr": "stereo right",
						 "front": "surround soundbar",  
						 "rl": "surround left",
						 "rr": "surround right"]
		input ("notificationVolume", "num", title: "Notification volume increase in percent", defaultValue: 10)
		input ("refresh_Rate","enum", title: "Device Refresh Interval", options: refreshRate, defaultValue: "30")
		input ("debug", "bool",  title: "Enable debug logging for 30 minutes", defaultValue: false)
		input ("descriptionText", "bool",  title: "Enable description text logging", defaultValue: true)
		input ("spkGroupLoc", "enum", title: "Surround/Stereo Speaker Location", options: positions)
		if (getDataValue("hwType") == "Soundbar") {
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
		}
	}
}

def installed() {
	log.info "Installing .."
	updated()
}

def updated() {
	log.info "Updating .."
	unschedule()
	sendEvent(name: "numberOfButtons", value: "29")
	state.triggered = false
	state.updateTrackDescription = true
	if(!state.urlPresetData) { state.urlPresetData = [:] }
	state.urlPlayback = false
	state.playingNotification = false
	state.spkType = getDataValue("spkType")
	state.groupType = getDataValue("groupType")
	state.trackIcon = ""
	state.remove("playQueue")
	state.playQueue = []
	if (state.recoveryData) { state.remove("recoveryData") }

	if (debug == true) { runIn(1800, debugLogOff) }
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
	clearQueue()
	sendEvent(name: "isConnected", value: "yes")
	checkConnected()
	runEvery1Minute(checkConnected)
	switch(rate) {
		case "1" :
			runEvery1Minute(refresh)
			break
		case "5" :
			runEvery5Minutes(refresh)
			break
		case "15" :
			runEvery15Minutes(refresh)
			break
		default:
			runEvery30Minutes(refresh)
	}
	refresh()
}

def checkConnected() {
	logDebug("checkConnected")
	def isConnected
	try{
		httpGet([uri: "http://${getDataValue("deviceIP")}:8001/api/v2/", timeout: 1]) { resp ->
			isConnected = "yes"
		}
	} catch (error) {
			isConnected = "no"
	}
	setEvent("isConnected", isConnected)
}

//	===== Capability Switch for Amazon Integration =====
def on() {
	//	Requires Rule Machine to be of use.
	setEvent("switch", "on")
	sendEvent(name: "isConnected", value: "yes")
	runEvery1Minute(checkConnected)
	checkConnected()
}
			 
def off() {
	//	Requires Rule Machine to be of use.
	setEvent("switch", "off")
	unschedule(checkConnected)
	sendEvent(name: "isConnected", value: "off")
}

//	========== Capability Music Player ==========
def setLevel(level) { setVolume(level) }

def play() {
	if (state.urlPlayback == true) {
		def trackData = parseJson(device.currentValue("trackData"))
		restoreTrack(trackData)
	} else {
		playbackControl("play")
		runIn(3, setTrackDescription)
	}
}

def pause() {
	unschedule(setTrackDescription)
	playbackControl("pause")
}

def stop() {
	unschedule(setTrackDescription)
	state.urlPlayback = false
	playbackControl("stop")
}

def playbackControl(cmd) {
	logDebug("playbackControl: command = ${cmd}, source = ${device.currentValue("inputSource")}, submode = ${device.currentValue("subMode")}")
	if (device.currentValue("subMode") == "cp") {
		sendCmd("/CPM?cmd=%3Cname%3ESetPlaybackControl%3C/name%3E" +
				"%3Cp%20type=%22str%22%20name=%22playbackcontrol%22%20val=%22${cmd}%22/%3E")
	} else {
		if (cmd == "play") { cmd = "resume" }
		sendCmd("/UIC?cmd=%3Cname%3ESetPlaybackControl%3C/name%3E" +
				"%3Cp%20type=%22str%22%20name=%22playbackcontrol%22%20val=%22${cmd}%22/%3E")
	}
}

def previousTrack() {
	logDebug("previousTrack: source = ${device.currentValue("inputSource")}, submode = ${device.currentValue("subMode")}")
	if (device.currentValue("inputSource") != "wifi" && device.currentValue("inputSource") != "bt") {
		logWarn("previousTrack does not work for input source")
		return
	}
	if (device.currentValue("subMode") == "cp") {
		sendSyncCmd("/CPM?cmd=%3Cname%3ESetPreviousTrack%3C/name%3E")
		sendSyncCmd("/CPM?cmd=%3Cname%3ESetPreviousTrack%3C/name%3E")
	} else if (state.urlPlayback == true) {
		logInfo("nextTrack command does not work during URL Playback.")
		return
	} else {
		sendSyncCmd("/UIC?cmd=%3Cname%3ESetTrickMode%3C/name%3E" +
				"%3Cp%20type=%22str%22%20name=%22trickmode%22%20val=%22previous%22/%3E")
		sendSyncCmd("/UIC?cmd=%3Cname%3ESetTrickMode%3C/name%3E" +
				"%3Cp%20type=%22str%22%20name=%22trickmode%22%20val=%22previous%22/%3E")
	}
	runIn(1, getPlayStatus)
	runIn(4, setTrackDescription)
}

def nextTrack() {
	logDebug("nextTrack: source = ${device.currentValue("inputSource")}, submode = ${device.currentValue("subMode")}")
	if (device.currentValue("inputSource") != "wifi" && device.currentValue("inputSource") != "bt") {
		logWarn("nextTrack does not work for input source")
		return
	}
	if (device.currentValue("subMode") == "cp") {
		sendCmd("/CPM?cmd=%3Cname%3ESetSkipCurrentTrack%3C/name%3E")
	} else if (state.urlPlayback == true) {
		logInfo("previousTrack command does not work during URL Playback.")
		return
	} else {
		sendCmd("/UIC?cmd=%3Cname%3ESetTrickMode%3C/name%3E" +
				"%3Cp%20type=%22str%22%20name=%22trickmode%22%20val=%22next%22/%3E")
	}
	runIn(2, getPlayStatus)
	runIn(6, setTrackDescription)
}

def setTrack(trackUri) { logWarn("restoreTrack: Not implemented.") }

def restoreTrack(trackUri) {
	resumeTrack(trackUri)
}

def resumeTrack(trackUri) {
	logDebug("resumeTrack: Restarting Stream ${trackUri}")
	playTrack(trackUri)
}

def playText(text, volume=null) {
	logDebug("playText: Text = ${text}, Volume = ${volume}")
	playTextAndResume(text, volume)
}

def playTrack(trackData, volume = null) {
	logDebug("playTrack: trackData = ${trackData}, Volume = ${volume}")
	if (volume == null) { volume = device.currentValue("volume") }

	if (trackData.toString()[0] != "[") {
		trackData = [url: trackData, name: trackData]
	}
	setEvent("trackDescription", trackData.name)
	setEvent("trackData", trackData)
	setEvent("status", "playing")
	state.urlPlayback = true
	logDebug("playTrack: attempting to start play of ${trackData.url}")
	execPlay(trackData.url, 0)
}

def getPlayStatus() {
	logDebug("getPlayStatus: source = ${device.currentValue("inputSource")}, submode = ${device.currentValue("subMode")}")
	if (device.currentValue("subMode") == "cp") {
		sendSyncCmd("/CPM?cmd=%3Cname%3EGetPlayStatus%3C/name%3E")
	} else {
		sendSyncCmd("/UIC?cmd=%3Cname%3EGetPlayStatus%3C/name%3E")
	}
}

def setTrackDescription() {
	unschedule("schedSetTrackDescription")
	if (state.urlPlayback == true) {
		logDebug("setTrackDescription command does not work during URL Playback.")
		trackData = "urlPlayback active"
		return
	}
	def source = getSource()
	def inputSource = source.inputSource
	def subMode = source.subMode
	logDebug("setTrackDescription: source = ${inputSource}, subMode = ${subMode}")
	state.updateTrackDescription = true
	def trackData
	if (subMode == "cp") {
		def respData = sendSyncCmd("/CPM?cmd=%3Cname%3EGetRadioInfo%3C/name%3E")
		trackData = parseRadioInfo(respData)
	} else {
		def respData = sendSyncCmd("/UIC?cmd=%3Cname%3EGetMusicInfo%3C/name%3E")
		trackData = parseMusicInfo(respData)
	}
	try{
		trackData = new JSONObject(trackData)
	} catch (error) {
		trackData = new JSONObject("{title: unknown, album: unknown, artist: unknown, type: unknown, error: data parse}")
	}
	trackDescription = "${trackData.artist}: ${trackData.title}"
	setEvent("trackDescription", trackDescription)
	setEvent("trackData", trackData)
	schedSetTrackDescription()
	return trackData
}

def schedSetTrackDescription() {
	logDebug("schedSetTrackDescription: update = ${state.updateTrackDescription}")
	if (device.currentValue("subMode") != "dlna" && device.currentValue("subMode") != "cp") {
		return
	}
	if(state.updateTrackDescription == false) { return }
	def trackData = parseJson(device.currentValue("trackData"))
	def respData = sendSyncCmd("/UIC?cmd=%3Cname%3EGetCurrentPlayTime%3C/name%3E")
	def timelength
	if (trackData.title == "Commercial") {
		timelength = trackData.trackLength.toInteger()
	} else if (respData.timelength) {
		timelength = respData.timelength.toInteger()
	} else {
		timelength = 60
	}
	def playtime = respData.playtime.toInteger()
	if (timelength == 1 && playtime == 0) { timelength = 0 }
	if (timelength == null || timelength == 0) {
		state.updateTrackDescription = false
		return
	} else {
		def nextUpdate = timelength - playtime + 10
		runIn(nextUpdate, setTrackDescription)
	}
}

def parseMusicInfo(respData) {
	def trackData
	if (respData.@result == "ng") {
		trackData = "{title: unknown, album: unknown, artist: unknown, "
		trackData += "type: unknown, trackLength: 0}"
	} else {
		def album = respData.album.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[]", "")
		if (album == "") { album = "unknown" }
		def artist = respData.artist.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[]", "")
		if (artist == "") { artist = "unknown" }
		def title = respData.title.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[]", "")
		if (title == "") { title = "unknown" }
		def deviceUdn = respData.device_udn.toString().replace("uuid:","")
		def timeLength = respData.timelength
		if (timeLength == "" || timeLength == null) {
			timeLength = 0
		} else {
			def timeSplit = timeLength.toString().split(":")
			timeLength = 60 * timeSplit[1].toInteger() + timeSplit[2].toInteger()
		}
		def folderData = getFolderData(respData.objectid.toString(), deviceUdn)
		def parentId = folderData[0]
		def source = getSource()
		def subMode = source.subMode
		trackData = "{"
		trackData += "title: ${title}, album: ${album}, artist: ${artist}, "
		trackData += "playerType: ${respData.playertype}, "
		trackData += "parentId: ${folderData[0]}, "
		trackData += "folderName: ${folderData[1]}, "
		trackData += "deviceUdn: ${deviceUdn}, "
		trackData += "playIndex: ${respData.playindex}, "
		trackData += "objectId: ${respData.objectid}, "
		trackData += "trackLength: ${timeLength}, "
		trackData += "playTime: ${respData.playtime}, "
		trackData += "type: ${subMode}"
		trackData += "}"
	}
	state.trackTimeLength = "${respData.timelength}"
	state.trackThumbnail = "${respData.thumbnail}"
	state.trackData = trackData
	return trackData
}

def getFolderData(objectId, deviceUdn) {
	logDebug("getFolderData: objectId = ${objectId}, deviceUdn = ${deviceUdn}")
	def nextObjId
	def folderName

//	1.  Get object ID for top folder "Music"
	def dirData = dirCmd(deviceUdn, "0")
	dirData.musiclist.music.each{
		if (it.type == "CONTAINER") {
			def object_id = it.@object_id.toString()
			def objIdLen = object_id.length()
			if (objectId.substring(0,object_id.length()) == object_id) {
				folderName = it.title
				nextObjId = it.@object_id
			}
		}
	}

//	2.  get ID for next folder level if music ID is not container
	dirData = dirCmd(deviceUdn, nextObjId)
	dirData.musiclist.music.each{
		if (it.type == "CONTAINER") {
			def object_id = it.@object_id.toString()
			def objIdLen = object_id.length()
			if (objectId.substring(0,object_id.length()) == object_id) {
				folderName = it.title
				nextObjId = it.@object_id
			}
		}
	}
	
//	3.  get ID for next folder level if music ID is not container
	dirData = dirCmd(deviceUdn, nextObjId)
	dirData.musiclist.music.each{
		if (it.type == "CONTAINER") {
			def object_id = it.@object_id.toString()
			def objIdLen = object_id.length()
			if (objectId.substring(0,object_id.length()) == object_id) {
				folderName = it.title
				nextObjId = it.@object_id
			}
		}
	}
	
return [nextObjId, folderName]
}

def dirCmd(deviceUdn, objId) {
	def dirData = sendSyncCmd("/UIC?cmd=%3Cname%3EGetMusicListByID%3C/name%3E" +
							  "%3Cp%20type=%22str%22%20name=%22device_udn%22%20val=%22uuid:${deviceUdn}%22/%3E" +
							  "%3Cp%20type=%22str%22%20name=%22filter%22%20val=%22folder%22/%3E" +
							  "%3Cp%20type=%22str%22%20name=%22parentid%22%20val=%22${objId}%22/%3E" +
							  "%3Cp%20type=%22dec%22%20name=%22liststartindex%22%20val=%220%22/%3E" +
							  "%3Cp%20type=%22dec%22%20name=%22listcount%22%20val=%2230%22/%3E")
	return dirData
}

def parseRadioInfo(respData) {
	def player = respData.cpname
	def artist = "ukn"
	def title = "ukn"
	def playerNo = 0
	def station = " "
	def path = " "
	def trackLength = "0"
	def album = " "
	if (player != null && player != "Unknown") {
		def cpChannels = cpChannels()
		playerNo = cpChannels."${player}"
		if (respData.station != "") {
			station = respData.station.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[;]", "")
		} else if (respData.root != "") {
			station = respData.root.toString()
		}
		path = respData.mediaid
		artist = respData.artist.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[;]", "")
		album = respData.album.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[;]", "")
		title = respData.title.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[;]", "")
		trackLength = respData.tracklength.toString()
		switch(player) {
			case "iHeartRadio":
				artist = "iHeartRadio"
				path = "l${path.toString().take(4)}"
				break
			case "Pandora":
				if (trackLength == "0") {
					artist = "Pandora"
					title = "Commercial"
					trackLength = "30"
					path = "na"
					album = "none"
				}
				break
			case "8tracks":
				if (respData.mixname == "") { station = "${player} - ${path}" }
				else { station = "${respData.mixname}" }
				break
			case "TuneIn":
				station = title
				artist = "TuneIn"
				album = "none"
				trackLength = "0"
				break
			default:
				break
		}
	}
	def source = getSource()
	def subMode = source.subMode
	def trackData = "{title: ${title}, artist: ${artist}, album: ${album}, " +
		"station: ${station}, player: ${player}, playerNo: ${playerNo}, " +
		"path: ${path}, trackLength: ${trackLength}, type: ${subMode}}"
	state.trackIcon = "${respData.thumbnail}"
	return trackData
}

//	========== Capability Audio Volume ==========
def setVolume(volumelevel) {
	logDebug("setVolume: volumelevel = ${volumelevel}, spkType = ${state.spkType}")
	def curVol = device.currentValue("volume")
	def volScale = 30
	if (getDataValue("hwType") == "Soundbar") { volScale = 100 }
	if (volumelevel < 1 || volumelevel > 100) { return }
	def deviceVolume = Math.round(volScale*volumelevel/100).toInteger()
	def volume = sendSyncCmd("/UIC?cmd=%3Cname%3ESetVolume%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22volume%22%20val=%22${deviceVolume}%22/%3E")

	if (state.spkType == "Main") { groupVolume(volumelevel, curVol) }	//Grouped Speakers
	return volume
}

def getVolume() {
	logDebug("getVolume")
	sendCmd("/UIC?cmd=%3Cname%3EGetVolume%3C/name%3E")
}

def muteUnmute() {
	if (device.currentValue("mute") == "unmuted") {
		mute()
	} else {
		unmute()
	}
}

def mute() {
	logDebug("mute")
	sendCmd("/UIC?cmd=%3Cname%3ESetMute%3C/name%3E" +
			"%3Cp%20type=%22str%22%20name=%22mute%22%20val=%22on%22/%3E")
}

def unmute() {
	logDebug("unmute")
	sendCmd("/UIC?cmd=%3Cname%3ESetMute%3C/name%3E" +
			"%3Cp%20type=%22str%22%20name=%22mute%22%20val=%22off%22/%3E")
}

def volumeUp() {
	def curVol = device.currentValue("volume").toInteger()
	logDebug("volumeUp: curVol = ${curVol}")
	def volIncrement = 3
	if (getDataValue("hwType") == "Soundbar") { volIncrement = 1 }

	def newVolume = curVol + volIncrement
	if (newVolume > 100) { newVolume = 100 }
	setVolume(newVolume)
}

def volumeDown() {
	def curVol = device.currentValue("volume").toInteger()
	logDebug("volumeUp: curVol = ${curVol}")
	def volIncrement = 3
	if (getDataValue("hwType") == "Soundbar") { volIncrement = 1 }

	def newVolume = curVol - volIncrement
	if (newVolume > 100) { newVolume = 100 }
	setVolume(newVolume)
}

//	========== Capability Speech Synthesis ==========
def speak(text) {
	logDebug("speak: text = ${text}")
	playTextAndResume(text)
}

//	========== Capability Audio Notification ==========
def playTextAndRestore(text, volume=null) {
	if (state.spkType == "Sub") {
		logDebug("playTextAndRestore: sending command to ${state.mainSpkDNI}.")
		parent.sendCmdToSpeaker(state.mainSpkDNI, "playTextAndRestore", text, volume)
		return
	}
	logDebug("playTextAndRestore: Text = ${text}, Volume = ${volume}")
	def track = convertToTrack(text)
	addToQueue(track.uri, track.duration, volume, false)
}

def playTextAndResume(text, volume=null) {
	if (state.spkType == "Sub") {
		logDebug ("playTextAndResume: sending command to ${state.mainSpkDNI}.")
		parent.sendCmdToSpeaker(state.mainSpkDNI, "playTextAndResume", text, volume)
		return
	}
	logInfo("playTextAndResume: Text = ${text}, Volume = ${volume}")
	def track = convertToTrack(text)
	addToQueue(track.uri, track.duration, volume, true)
}

def convertToTrack(text) {
	if (getDataValue("hwType") == "Speaker") {		//	Speaker
		def track = textToSpeech(text)
		return track
	} else {										//	Soundbar
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
}

def playTrackAndRestore(trackData, volume=null) {
	if (state.spkType == "Sub") {
		logDebug("playTrackAndRestore: sending command to ${state.mainSpkDNI}.")
		parent.sendCmdToSpeaker(state.mainSpkDNI, "playTrackAndRestore", trackData, volume)
		return
	}
	logDebug("playTrackAndResore: Volume = ${volume}, trackData = ${trackData}")
	def trackUri
	def duration
	if (trackData[0] == "[") {
		logWarn("playTrackAndResume: Track data must be formated as {uri: , duration: }")
	} else if (trackData[0] == "{") {
		trackData = new JSONObject(trackData)
		trackUri = trackData.uri
		duration = trackData.duration
	} else {
		trackUri = trackData
		duration = 15
	}
	addToQueue(trackUri, duration, volume, false)
}

def playTrackAndResume(trackData, volume=null) {
	if (state.spkType == "Sub") {
		parent.sendCmdToSpeaker(state.mainSpkDNI, "playTrackAndResume", trackData, volume)
		return
	}
	logDebug("playTrackAndResume: Volume = ${volume}, trackData = ${trackData}")
	def trackUri
	def duration
	if (trackData[0] == "[") {
		logWarn("playTrackAndResume: Track data must be formated as {uri: , duration: }")
	} else if (trackData[0] == "{") {
		trackData = new JSONObject(trackData)
		trackUri = trackData.uri
		duration = trackData.duration
	} else {
		trackUri = trackData
		duration = 15
	}
	addToQueue(trackUri, duration, volume, true)
}

//	========== Play Queue Execution ==========
def addToQueue(trackUri, duration, volume, resumePlay){
	logDebug("addToQueue: ${trackUri},${duration},${volume},${resumePlay}")
	if (device.currentValue("isConnected") == "no") { return }
	duration = duration + 3
	playData = ["trackUri": trackUri, 
				"duration": duration,
				"requestVolume": volume]
	state.playQueue.add(playData)

	if (state.playingNotification == false) {
		state.playingNotification = true
		runInMillis(100, startPlayViaQueue, [data: resumePlay])
	}
}

def startPlayViaQueue(resumePlay) {
	logDebug("startPlayViaQueue: queueSize = ${state.playQueue.size()}, resumePlay = ${resumePlay}")
	if (state.playQueue.size() == 0) { return }
	getSource()
	setTrackDescription()
	pauseExecution(1000)
	unschedule(setTrackDescription)
	state.recoveryVolume = device.currentValue("volume")
	if (getDataValue("hwType") == "Speaker") {
		def blankTrack = convertToTrack("     ")
		execPlay(blankTrack.uri, true)
	}
	pause()
	runIn(1, playViaQueue, [data: resumePlay])
}

def playViaQueue(resumePlay) {
	logDebug("playViaQueue: queueSize = ${state.playQueue.size()}, resumePlay = ${resumePlay}")
	if (state.playQueue.size() == 0) {
		resumePlayer(resumePlay)
		return
	}
	def playData = state.playQueue.get(0)
	state.playQueue.remove(0)

	logDebug("playViaQueue: playData = ${playData}, recoveryVolume = ${state.recoveryVolume}")

	recVolume = state.recoveryVolume.toInteger()
	def playVolume = playData.requestVolume
	if (!playVolume) {
		def multFactor = 1 + notificationVolume.toInteger()/100
		playVolume = (multFactor * recVolume).toInteger()
	}
	if (playVolume > 100) { playVolume = 100 }

	def vol = setVolume(playVolume)
	execPlay(playData.trackUri, resumePlay)
	runIn(playData.duration, resumePlayer, [data: resumePlay])
	runIn(30, kickStartQueue, [data: resumePlay])
}

def execPlay(trackUri, resumePlay) {
	if (getDataValue("hwType") == "Speaker") {
	//	Speaker Play
		def playResume = 1
		if (resumePlay == false) { playResume = "0" }
		sendCmd("/UIC?cmd=%3Cname%3ESetUrlPlayback%3C/name%3E" +
		"%3Cp%20type=%22cdata%22%20name=%22url%22%20val=%22empty%22%3E" +
		"%3C![CDATA[${trackUri}]]%3E%3C/p%3E" +
		"%3Cp%20type=%22dec%22%20name=%22buffersize%22%20val=%220%22/%3E" +
		"%3Cp%20type=%22dec%22%20name=%22seektime%22%20val=%220%22/%3E" +
		"%3Cp%20type=%22dec%22%20name=%22resume%22%20val=%22${playResume}%22/%3E")
	}	else {
	//	Soundbar Play
		sendSpeakCmd("SetAVTransportURI",
					 [InstanceID: 0,
					  CurrentURI: trackUri,
					  CurrentURIMetaData: ""])
		pauseExecution(200)
		sendSpeakCmd("Play",
					 ["InstanceID" :0,
					  "Speed": "1"])
	}
}

def resumePlayer(resumePlay) {
	if (state.playQueue.size() > 0) {
		playViaQueue(resumePlay)
		return
	}
	logDebug("resumePlayer: resumePlay = ${resumePlay}")
	state.playingNotification = false
	setVolume(state.recoveryVolume)
	if (resumePlay == false) {return}

	def trackData = new JSONObject(device.currentValue("trackData"))
	def subMode = device.currentValue("subMode")
	logDebug("resumePlayer: restoring play, track data = ${trackData}")

	if (state.urlPlayback == true) {
		playTrack([url: trackData.url, name: trackData.name])
	} else if (subMode == "cp" && trackData.player != "Unknown") {
		switch(trackData.player) {
			case "Amazon":
			case "AmazonPrime":
				nextTrack()
				break
			default:
				def id = sendSyncCmd("/CPM?cmd=%3Cname%3EPlayById%3C/name%3E" +
					"%3Cp%20type=%22str%22%20name=%22cpname%22%20val=%22${trackData.player}%22/%3E" +
					"%3Cp%20type=%22str%22%20name=%22mediaid%22%20val=%22${trackData.path}%22/%3E")
		}
		pauseExecution(1000)
		play()
	} else if (subMode == "dlna") {
		playDlna(trackData)
		runIn(10, refresh)
	}
}

def kickStartQueue(resumePlay = true) {
	logInfo("kickStartQueue: resumePlay = ${resumePlay}.")
	if (state.playQueue.size() > 0) {
		resumePlayer(resumePlay)
	} else {
		state.playingNotification = false
	}
}

def clearQueue() {
	logDebug("clearQueue")
	state.remove("playQueue")
	pauseExecution(5000)
	state.playQueue = []
	state.playingNotification = false
}

//	========== Capability Pushable Button ==========
def push(pushed) {
	logDebug("push: button = ${pushed}, trigger = ${state.triggered}")
	if (pushed == null) {
		logWarn("push: pushed is null.  Input ignored")
		return
	}
	sendEvent(name: "pushed", value: pushed)
	pushed = pushed.toInteger()
	switch(pushed) {
		case 0 :
			if (state.triggered == true) {
				state.triggered = false
				logDebug("push: Trigger is NOT ARMED")
			} else {
				state.triggered = true
				logDebug("push: Trigger is ARMED")
				runIn(15, unTrigger)
			}
			break
		case 1 :		//	Preset 1
		case 2 :		//	Preset 2
		case 3 :		//	Preset 3
		case 4 :		//	Preset 4
		case 5 :		//	Preset 5
		case 6 :		//	Preset 6
		case 7 :		//	Preset 7
		case 8 :		//	Preset 8
			if (state.triggered == false) {
				presetPlay(pushed)
			} else {
				presetCreate(pushed)
				sendEvent(name: "Trigger", value: "notArmed")
			}
			break
		case 9 :		//	Group 1
		case 10:		//	Group 2
		case 11:		//	Group 3
			if (state.triggered == false) {
				if (state.activeGroupNo == "") {
					groupStart(pushed-8)
				} else if (state.activeGroupNo.toInteger() == pushed - 8) {
					groupStop()
				}
			} else {
				groupCreate(pushed-8)
				sendEvent(name: "Trigger", value: "notArmed")
			}
			break
		case 12: inputSource(); break
		case 13: equalPreset(); break
		case 14: repeat(); break
		case 15: shuffle(); break
		case 16: groupStop(); break
		case 17: volumeUp(); break
		case 18: volumeDown(); break
		case 19: stopAllActivity(); break
		case 20: refresh(); break
		case 21 :		//	Preset 1
		case 22 :		//	Preset 2
		case 23 :		//	Preset 3
		case 24 :		//	Preset 4
		case 25 :		//	Preset 5
		case 26 :		//	Preset 6
		case 27 :		//	Preset 7
		case 28 :		//	Preset 8
			if (state.triggered == false) {
				urlPresetPlay(pushed-20)
			} else {
				logWarn("Auto urlPresetCreate is not available")
				sendEvent(name: "Trigger", value: "notArmed")
			}
			break
		case 29 : muteUnmute(); break
		default:
			logWarn("${device.label}: Invalid Preset Number (must be 0 thru 29)!")
			break
	}
}

def unTrigger() { state.triggered = false }

//	========== Capability Refresh ==========
def refresh() {
	if (device.currentValue("isConnected") == "no") { return }
	if (state.playingNotification == true) {
		logDebug("refresh: ignored while playing notifications")
		return
	}
	logDebug("refresh")
	getVolume()
	def status = getPlayStatus()
	setTrackDescription()
	pauseExecution(2000)
	sendCmd("/UIC?cmd=%3Cname%3EGetAcmMode%3C/name%3E")
	if (state.activeGroupNo == "" && device.currentValue("activeGroup")) {
		sendEvent(name: "activeGroup", value: "none")
	}
}

//	========== Samsung-specific Speaker Control ==========
def stopAllActivity() {
	logInfo("stopAllActivity: Disconect any streaming service.")
	if (state.spkType == "Main") {
		groupStop()
		pauseExecution(1000)
	}
	stop()
	if (getDataValue("hwType") == "Soundbar") {
		pauseExecution(3000)
		sendSyncCmd("/UIC?cmd=%3Cname%3ESetPowerStatus%3C/name%3E" +
					"%3Cp%20type=%22dec%22%20name=%22powerstatus%22%20val=%220%22/%3E")
		return
	}
	inputSource("bt")
	pauseExecution(3000)
	inputSource("wifi")
}

def repeat() {
	logDebug("repeat: source = ${device.currentValue("inputSource")}, submode = ${device.currentValue("subMode")}")
	if (device.currentValue("inputSource") != "wifi" && device.currentValue("inputSource") != "bt") {
		logWarn("repeat does not work for input source")
		return
	} else if (state.urlPlayback == false) {
		logInfo("repeat commands do not work during URL Playback.")
		return
	}
	def repeat = device.currentValue("repeat")
	if (device.currentValue("subMode") == "cp") {
		def repeatMode = "0"
		if (repeat == "0") { repeatMode = "1" }
		else if (repeat == "1") { repeatMode = "2" }
	 	sendSyncCmd("/CPM?cmd=%3Cname%3ESetRepeatMode%3C/name%3E" +
					"%3Cp%20type=%22dec%22%20name=%22mode%22%20val=%22${repeatMode}%22/%3E")
	} else {
		def repeatMode = "off"
		if (repeat == "0") { repeatMode = "on" }
		sendSyncCmd("/UIC?cmd=%3Cname%3ESetRepeatMode%3C/name%3E" +
					"%3Cp%20type=%22str%22%20name=%22repeatmode%22%20val=%22${repeatMode}%22/%3E")
	}
}

def shuffle() {
	logDebug("shuffle: source = ${device.currentValue("inputSource")}, submode = ${device.currentValue("subMode")}")
	if (device.currentValue("inputSource") != "wifi" && device.currentValue("inputSource") != "bt") {
		logWarn("shuffle does not work for input source")
		return
	} else if (state.urlPlayback == true) {
		logInfo("shuffle commands do not work during URL Playback.")
		return
	}
	shuffleMode = "off"
	if (device.currentValue("shuffle") == "off") { shuffleMode = "on" }
	setShuffle(shuffleMode)
}

def setShuffle(shuffleMode) {
	logDebug("setShuffle: shuffleMode = ${shuffleMode}")
	def source = getSource()
	def subMode = source.subMode
	if (subMode == "cp") {
		def shuffleNo = "1"
		if (shuffleMode == "off") { shuffleNo = "0" }
		sendCmd("/CPM?cmd=%3Cname%3ESetToggleShuffle%3C/name%3E" +
				"%3Cp%20type=%22dec%22%20name=%22mode%22%20val=%22${shuffleNo}%22/%3E")
	} else {
		sendCmd("/UIC?cmd=%3Cname%3ESetShuffleMode%3C/name%3E" +
					"%3Cp%20type=%22str%22%20name=%22shufflemode%22%20val=%22${shuffleMode}%22/%3E")
	}
}

def equalPreset() {
	logDebug("equalPreset")
	def newEqPreset = ""
	def respData = sendSyncCmd("/UIC?cmd=%3Cname%3EGet7BandEQList%3C/name%3E")
	def totalPresets = respData.listcount.toInteger() - 1
	respData = sendSyncCmd("/UIC?cmd=%3Cname%3EGetCurrentEQMode%3C/name%3E")
	def currentEqPreset = respData.presetindex.toInteger()

	if(currentEqPreset >= totalPresets) {
		newEqPreset = 0
	} else {
		newEqPreset = currentEqPreset + 1
	}
	sendSyncCmd("/UIC?cmd=%3Cname%3ESet7bandEQMode%3C/name%3E" +
				"%3Cp%20type=%22dec%22%20name=%22presetindex%22%20val=%22${newEqPreset}%22/%3E")
	sendSyncCmd("/UIC?cmd=%3Cname%3EGetCurrentEQMode%3C/name%3E")
}

def inputSource(source = null) {
	logDebug("inputSource: source = ${source}")
	if (source == null) {
		def sources = new JSONObject(getDataValue("inputSources"))
		def totalSources = sources.length().toInteger()
		def currentSource = device.currentValue("inputSource")
		for (int i = 1; i < totalSources + 1; i++) {
			if (sources."${i}" == currentSource) {
				def sourceNo = i
				if (sourceNo >= totalSources) {
					sourceNo = 1
				} else {
					sourceNo = sourceNo + 1
				}
				source = sources."${sourceNo}"
			}
		}
	}
	state.urlPlayback = false
	sendSyncCmd("/UIC?cmd=%3Cname%3ESetFunc%3C/name%3E" +
				"%3Cp%20type=%22str%22%20name=%22function%22%20val=%22${source}%22/%3E")
	runIn(4, getSource)
	runIn(8, setTrackDescription)
}

def getSource() {
	logDebug("getSource")
	def source = sendSyncCmd("/UIC?cmd=%3Cname%3EGetFunc%3C/name%3E")
	return source
}

//	========== Samsung Player Preset Capability ==========
def presetCreate(preset, name = "NotSet") {
	if (preset < 1 || preset > 8) {
		logWarn("presetCreate: Preset Number ${preset}out of range (1-8)!")
		return
	}
	logDebug("presetCreate: name = ${name}, preset = ${preset}")
	state.urlPlayback = false
	def trackData = setTrackDescription()
	def presetData = [:]
	if (trackData.type == "dlna") {
		if (name == "NotSet") { name = trackData.album }
		parentId = trackData.parentId
		def resp = sendSyncCmd("/UIC?cmd=%3Cname%3EGetMusicListByID%3C/name%3E" +
					"%3Cp%20type=%22str%22%20name=%22device_udn%22%20val=%22uuid:${trackData.deviceUdn}%22/%3E" +
					"%3Cp%20type=%22str%22%20name=%22filter%22%20val=%22folder%22/%3E" +
					"%3Cp%20type=%22str%22%20name=%22parentid%22%20val=%22${trackData.parentId}%22/%3E" +
					"%3Cp%20type=%22dec%22%20name=%22liststartindex%22%20val=%220%22/%3E" +
					"%3Cp%20type=%22dec%22%20name=%22listcount%22%20val=%221%22/%3E")
		if (!resp.musiclist) {
			logWarn("createPreset: Not created.  No Music List from device")
		} else {
			presetData["type"] = trackData.type
			presetData["name"] = name
			presetData["deviceUdn"] = trackData.deviceUdn
//			presetData["playerType"] = trackData.playerType
			presetData["parentId"] = trackData.parentId
			presetData["objectId"] = resp.musiclist.music.@object_id.toString()
			presetData["playTime"] = "0"
			presetData["playIndex"] = "0"
			state."Preset_${preset}_Data" = presetData
		}
	} else if (trackData.type == "cp") {
		if (name == "NotSet") { name = trackData.station }
		presetData["type"] = trackData.type
		presetData["name"] = name
		presetData["player"] = trackData.player
		presetData["playerNo"] = trackData.playerNo
		presetData["station"] = trackData.station
		presetData["path"] = trackData.path
		state."Preset_${preset}_Data" = presetData
	} else {
		logWarn("presetCreate: can't create preset from trackData = ${trackData}")
		return
	}
	sendEvent(name: "Preset_${preset}", value: name)
	logInfo("presetCreate: create preset ${preset}, data = ${presetData}")
}

def presetPlay(preset, shuffle = "on") {
	def psName = device.currentValue("Preset_${preset}")
	def psData = state."Preset_${preset}_Data"
	logDebug("presetPlay: preset = ${preset}, psName = ${psName}, " +
			 "psData = ${psData}, shuffle = ${shuffle}")
	if (preset < 1 || preset > 8) {
		logWarn("presetPlay: Preset Number out of range (1-8)!")
		return
	} else if (psName == "preset${preset}") {
		logWarn("presetPlay: Preset Not Set!")
		return
	}
	if (psData.type == "cp") {
		stop()
		if (psData.playerNo != "99") {
			def service = sendSyncCmd("/CPM?cmd=%3Cname%3ESetCpService%3C/name%3E" +
					"%3Cp%20type=%22dec%22%20name=%22cpservice_id%22%20val=%22${psData.playerNo}%22/%3E")
		}
		def id = sendSyncCmd("/CPM?cmd=%3Cname%3EPlayById%3C/name%3E" +
				"%3Cp%20type=%22str%22%20name=%22cpname%22%20val=%22${psData.player}%22/%3E" +
				"%3Cp%20type=%22str%22%20name=%22mediaid%22%20val=%22${psData.path}%22/%3E")
		pauseExecution(500)		
		play()
	} else if (psData.type == "dlna") {
		def startDlna = playDlna(psData)
		pauseExecution(2000)
		setShuffle(shuffle)
	} else {
		logWarn("presetPlay: can't play preset. trackData = ${trackData}")
		return
	}
	runIn(20, refresh)
	logInfo("presetPlay: Playing ${psName}")
}

def playDlna(trackData) {
	logDebug("playDlna: trackData = ${trackData}")
	def playTime = trackData.playTime
	if (playTime == null) { playTime = "0" }
	if (playTime != "0") { 
		playTime = (playTime.toInteger() / 1000).toInteger()
	}
	sendCmd("/UIC?cmd=%3Cname%3ESetFolderPlaybackControl%3C/name%3E" +
			"%3Cp%20type=%22str%22%20name=%22device_udn%22%20val=%22uuid:${trackData.deviceUdn}%22/%3E" +
			"%3Cp%20type=%22str%22%20name=%22playbackcontol%22%20val=%22play%22/%3E" +
			"%3Cp%20type=%22str%22%20name=%22playertype%22%20val=%22${trackData.playerType}%22/%3E" +
			"%3Cp%20type=%22cdata%22%20name=%22sourcename%22%20val=%22empty%22%3E" +
			"%3C![CDATA[]]%3E%3C/p%3E" +
			"%3Cp%20type=%22str%22%20name=%22parentid%22%20val=%22${trackData.parentId}%22/%3E%" +
			"3Cp%20type=%22dec%22%20name=%22playindex%22%20val=%22${trackData.playIndex}%22/%3E" +
			"%3Cp%20type=%22dec%22%20name=%22playtime%22%20val=%22${playTime}%22/%3E" +
			"%3Cp%20type=%22str%22%20name=%22objectid%22%20val=%22${trackData.objectId}%22/%3E" +
			"")
	return
}

def presetDelete(preset) {
	logDebug("presetDelete: preset = ${preset}")
	if (preset < 1 || preset > 8) {
		logWarn("presetDelete: Preset Number out of range (1-8)!")
		return
	}
	state."Preset_${preset}_Data" = ""
	sendEvent(name: "Preset_${preset}", value: "Preset_${preset}")
	logInfo("presetDeleted: preset = ${preset}")
}

//	========== urlStation Preset Capability ==========
def urlPresetCreate(preset, name = "NotSet") {
	if (preset < 1 || preset > 8) {
		logWarn("urlPresetCreate: Preset Number out of range (1-8)!")
		return
	}
	def trackData = parseJson(device.currentValue("trackData"))
	logDebug("urlPresetCreate: preset = ${preset} // name = ${name} // trackData = ${trackData}")
	def urlData = [:]
	urlData["name"] = name
	urlData["url"] = trackData.url
	state.urlPresetData << ["PS_${preset}":[urlData]]
	sendEvent(name: "urlPreset_${preset}", value: urlData.name)
	logInfo("urlPresetCreate: created preset ${preset}, data = ${urlData}")
}

def urlPresetPlay(preset) {
	if (preset < 1 || preset > 8) {
		logWarn("urlPresetPlay: Preset Number out of range (1-8)!")
		return
	} 
	def urlData = state.urlPresetData."PS_${preset}"
	if (urlData == null || urlData == [:]) {
		logWarn("urlPresetPlay: Preset Not Set!")
		return
	}
	playTrack(urlData[0])
	logInfo("urlPresetPlay: Playing ${urlData}")
}

def urlPresetDelete(preset) {
	def urlPresetData = state.urlPresetData
	if (preset < 1 || preset > 8) {
		logWarn("urlPresetPlay: Preset Number out of range (1-8)!")
		return
	} else if (urlPresetData."PS_${preset}" == null || urlPresetData."PS_${preset}" == [:]) {
		logWarn("urlPresetPlay: Preset Not Set!")
		return
	}
	urlPresetData << ["PS_${preset}":[]]
	sendEvent(name: "urlPreset_${preset}", value: "urlPreset${preset}")
	logInfo("urlPresetDelete: preset = ${preset}")
}

//	========== Samsung Group Speaker Capability ==========
def groupCreate(groupNo) {
	logDebug("groupCreate: groupNo = ${groupNo}")
	if (groupNo < 1 || groupNo > 3) {
		logWarn("groupCreate: Group Number out of range (1-3)!")
		return
	}
	def acmData = sendSyncCmd("/UIC?cmd=%3Cname%3EGetAcmMode%3C/name%3E")
	if (state.spkType != "Main") {
		logWarn("groupCreate: Not currently a Main Speaker")
		return
	}

	def groupName = sendSyncCmd("/UIC?cmd=%3Cname%3EGetGroupName%3C/name%3E")
	def mainSpeakerData = generateSpeakerData()
	def groupData = [:]
	groupData["groupName"] = "${groupName}"
	groupData["groupType"] = "${state.groupType}"
	groupData["Main"] = mainSpeakerData

	def groupSpeakerData = parent.requestSubSpeakerData(groupData, device.deviceNetworkId)
	logDebug("groupCreate: returned speakerData = #{speakerData}")
	state.activeGroupNo = groupNo
	state.mainSpkDNI = "${device.deviceNetworkId}"
	state.spkType = "Main"
	sendEvent(name: "Group_${groupNo}", value: "${groupName}")
	sendEvent(name: "activeGroup", value: "${groupName}")
	state."group_${groupNo}_Data" = groupSpeakerData
	logInfo("groupCreated: groupNo = ${groupNo}")
}

def generateSpeakerData() {
	logDebug("generateSpeakerData")
	sendSyncCmd("/UIC?cmd=%3Cname%3EGetChVolMultich%3C/name%3E")
	sendSyncCmd("/UIC?cmd=%3Cname%3EGetVolume%3C/name%3E")
	def speakerData = [:]
	speakerData["spkName"] = "${device.label}"
	speakerData["spkDNI"] = "${device.deviceNetworkId}"
	speakerData["spkMAC"] = "${getDataValue("deviceMac")}"
	speakerData["spkChVol"] = "${device.currentValue("multiChVol")}"
	speakerData["spkDefVol"] = "${device.currentValue("volume")}"
	speakerData["spkLoc"] = "${spkGroupLoc}"
	return speakerData
}

def getSubSpeakerData() {
	logDebug("getSubSpeakerData")
	def acmData = sendSyncCmd("/UIC?cmd=%3Cname%3EGetAcmMode%3C/name%3E")
	def subSpkData = "not Sub"
	if (acmData == "commsError") {
		subSpkData = "commsError"
	} else if (state.spkType == "Sub") {
		subSpkData = generateSpeakerData()
	}
	return subSpkData
}

def groupStart(groupNo) {
	sendSyncCmd("/UIC?cmd=%3Cname%3EGetAcmMode%3C/name%3E")
	def groupData = state."group_${groupNo}_Data"
	logDebug("groupStart: groupNo = ${groupNo}, groupData = ${groupData}")
	if (groupNo < 1 || groupNo > 3) {
		logWarn "groupStart: Group Number out of range (1-3)!"
		return
	} else if (device.currentValue("Group_${groupNo}") == "group${groupNo}") {
		logWarn "groupStart: Group is not defined!"
		return
	} else if (state.spkType != "Solo") {
		logWarn("groupStart: The speaker is already in a group.")
		return
	}

//	Create command string group and main speaker string
	def groupName = groupData.groupName.replaceAll(' ','%20')
	def mainSpkName = groupData.Main.spkName.replaceAll(' ','%20')
	def spksInGrp = groupData.noSubSpks.toInteger() + 1
	def spkMethod = "SetMultispkGroup"
	if (groupData.groupType == "Surround") { spkMethod = "SetMultichGroup" }
	def groupCmd = "/UIC?cmd=%3Cname%3E${spkMethod}%3C/name%3E"
		groupCmd += "%3Cp%20type=%20%22cdata%22%20name=%20%22name%22%20val=%20%22empty%22%3E%3C![CDATA[${groupName}]]%3E%3C/p%3E"
		groupCmd += "%3Cp%20type=%20%22dec%22%20name=%20%22index%22%20val=%20%221%22/%3E"
		groupCmd += "%3Cp%20type=%20%22str%22%20name=%20%22type%22%20val=%20%22main%22/%3E"
		groupCmd += "%3Cp%20type=%20%22dec%22%20name=%20%22spknum%22%20val=%20%22${spksInGrp}%22/%3E"
		groupCmd += "%3Cp%20type=%20%22str%22%20name=%20%22audiosourcemacaddr%22%20val=%20%22${groupData.Main.spkMAC}%22/%3E"
		groupCmd += "%3Cp%20type=%20%22cdata%22%20name=%20%22audiosourcename%22%20val=%20%22empty%22%3E%3C![CDATA[${mainSpkName}]]%3E"
		groupCmd += "%3C/p%3E%3Cp%20type=%20%22str%22%20name=%20%22audiosourcetype%22%20val=%20%22speaker%22/%3E"
	if (groupData.groupType == "Surround") {
		groupCmd += "%3Cp%20type=%22str%22%20name=%22channeltype%22%20val=%22${groupData.Main.spkLoc}%22/%3E"
		groupCmd += "%3Cp%20type=%22dec%22%20name=%22channelvolume%22%20val=%22${groupData.Main.spkChVol}%22/%3E"
	}

//	Add subspeaker strings and update subspeaker data
	def i = 1
	while (i < groupData.noSubSpks.toInteger() + 1) {
		def spkData = groupData."Sub_${i}"
		def subSpkIP = parent.getIP(spkData.spkDNI)
		groupCmd += "%3Cp%20type=%20%22str%22%20name=%20%22subspkip%22%20val=%20%22${subSpkIP}%22/%3E"
		groupCmd += "%3Cp%20type=%20%22str%22%20name=%20%22subspkmacaddr%22%20val=%20%22${spkData.spkMAC}%22/%3E"
		if (groupData.groupType == "Surround") {
			groupCmd += "%3Cp%20type=%22str%22%20name=%22subchanneltype%22%20val=%22${spkData.spkLoc}%22/%3E"
		}
		parent.sendCmdToSpeaker(spkData.spkDNI, "startSubSpeaker", spkData.spkDefVol)
		i = i + 1
	}

//	Send group command and update main speaker data
	setVolume(groupData.Main.spkDefVol.toInteger())
	if (groupData.groupType == "Surround") {
		sendSyncCmd("/UIC?cmd=%3Cname%3ESetEqualizeVolMultich%3C/name%3E" +
				"%3Cp%20type=%22str%22%20name=%22groupspkvol%22%20val=%22equalize%22/%3E")
	}
	sendSyncCmd(groupCmd)
	state.activeGroupNo = groupNo
	state.spkType = "Main"
	sendEvent(name: "activeGroup", value: "${groupData.groupName}")
	sendSyncCmd("/UIC?cmd=%3Cname%3EGetAcmMode%3C/name%3E")
	logInfo("groupStart: Group ${groupData.groupName} started.")
}

def startSubSpeaker(spkVolume) {
	logDebug("startSubSpeaker: speaker volume = ${spkVolume}, mainSpkDni = %{mainSpkDNI}")
	pauseExecution(10000)
	setVolume(spkVolume.toInteger())
	sendSyncCmd("/UIC?cmd=%3Cname%3EGetAcmMode%3C/name%3E")
}

def groupVolume(groupVolume, curVol) {
	logDebug("groupVolume: speakerType = ${state.spkType}, groupVolume = ${groupVolume}, curVol = ${curVol}")
	if (state.spkType != "Main") { return }
	def groupData = state."group_${state.activeGroupNo}_Data"
	def spksInGroup = groupData.noSubSpks.toInteger()
	def volChange = (groupVolume - curVol)/curVol
	def i = 1
	while (i <= spksInGroup) {
		def spkData = groupData."Sub_${i}"
		def subSpkDNI = spkData.spkDNI
		parent.sendCmdToSpeaker(subSpkDNI, "setSubSpkVolume", volChange)
		i = i + 1
	}
}

def setSubSpkVolume(volChange) {
	logDebug("setSubSpkVolume: volume change = ${volChange}")
	def curVol = device.currentValue("volume").toInteger()
	logDebug("setSubSpkVolume: volChange = ${volChange}, curVol = ${curVol}")
	def newVolume = (curVol*(1 + volChange)).toInteger()
	setVolume(newVolume)
}

def groupStop() {
	def groupNo = state.activeGroupNo
	if (state.spkType == "Sub") {
		logDebug("groupStop: Group Stop comand being sent to main speaker.")
		parent.sendCmdToSpeaker(state.mainSpkDNI, "groupStop")
		return
	} else if (groupNo == "" || state.spkType == "Solo") {
		logWarn("groupStop: Not a Grouped speaker.")
		return
	}
	def groupData = state."group_${groupNo}_Data"
	logDebug("groupStop: groupNo = ${groupNo}, groupData = ${groupData}")

	sendSyncCmd("/UIC?cmd=%3Cname%3ESetUngroup%3C/name%3E")
	sendEvent(name: "activeGroup", value: "none")
	sendCmd("/UIC?cmd=%3Cname%3ESetChVolMultich%3C%2Fname%3E" +
			"%3Cp%20type%3D%22dec%22%20name%3D%22chvol%22%20val%3D%220%22%2F%3E")
	pauseExecution(1000)
	setVolume(groupData.Main.spkDefVol.toInteger())

	def spksInGroup = groupData.noSubSpks.toInteger() + 1
	def i = 1
	while (i < spksInGroup) {
		def spkData = groupData."Sub_${i}"
		i = i + 1
		def subSpkDNI = spkData.spkDNI
		parent.sendCmdToSpeaker(subSpkDNI, "stopSubSpeaker", spkData.spkDefVol)
	}

	sendSyncCmd("/UIC?cmd=%3Cname%3EGetAcmMode%3C/name%3E")
	logInfo("groupStop: Active Group Stopped.")
}

def stopSubSpeaker(defVol) {
	logDebug("stopSubSpeaker: default volume = ${defVol}")
	sendSyncCmd("/UIC?cmd=%3Cname%3ESetUngroup%3C/name%3E")
	setVolume(defVol.toInteger())
	sendSyncCmd("/UIC?cmd=%3Cname%3ESetChVolMultich%3C%2Fname%3E" +
			"%3Cp%20type%3D%22dec%22%20name%3D%22chvol%22%20val%3D%220%22%2F%3E")
	sendSyncCmd("/UIC?cmd=%3Cname%3EGetAcmMode%3C/name%3E")
	sendSyncCmd("/UIC?cmd=%3Cname%3EGetFunc%3C/name%3E")
	getPlayStatus()
	runIn(3, setTrackDescription)
}

def groupDelete(groupNo) {
	logInfo("groupDelete: groupNo = ${groupNo}")
	if (groupNo < 1 || groupNo > 3) {
		logWarn "groupStart: Group Number out of range (1-3)!"
		return
	}
	sendEvent(name: "Group_${groupNo}", value: "group${groupNo}")
	state."group_${groupNo}_Data" = {}
	if (groupNo == state.activeGroupNo) {
		state.activeGroupNo = ""
		state.spkType = "Solo"
		state.mainSpkDNI = ""
		sendEvent(name: "activeGroup", value: "")
	}
}

//	========== Utility Functions ==========
def execAppCommand(command, param1, param2, param3) {
	logDebug("execAppCommand: command = ${command}, params = ${param1} // ${param2}")
	switch(command) {
		case "playTextAndRestore":
			playTextAndRestore(param1, param2)
			break
		case "playTextAndResume":
			playTextAndResume(param1, param2)
			break
		case "playTrackAndResume":
			playTrackAndResume(param1, param2)
			break
		case "playTrackAndRestore":
			playTrackAndRestore(param1, param2)
			break
		case "setSubSpkVolume":
		  	setSubSpkVolume(param1)
			break
		case "groupStop":
			groupStop()
			break
		case "startSubSpeaker":
			startSubSpeaker(param1)
			break
		case "stopSubSpeaker":
			stopSubSpeaker(param1)
			break
		default:
			logWarn("execAppCommand: command ${command} not executed")
			break
	}
}

def cpChannels() {
	return ["Pandora": "0", "Spotify": "1","Deezer": "2", "Napster": "3", 
			"8tracks": "4","iHeartRadio": "5", "Rdio": "6", "BugsMusic": "7",
			"JUKE": "8", "7digital": "9", "Murfie": "10","JB HI-FI Now": "11", 
			"Rhapsody": "12","Qobuz": "13", "Stitcher": "15", "MTV Music": "16",
			"Milk Music": "17", "Milk Music Radio": "18","MelOn": "19", 
			"Tidal HiFi": "21","SiriusXM": "22", "Anghami": "23",
			"AmazonPrime": "24", "Amazon": "98", "TuneIn": "99"]
}

def convertMac(dni) {
	dni = dni.toString()
	def mac = "${dni.substring(0,2)}${dni.substring(3,5)}${dni.substring(6,8)}${dni.substring(9,11)}${dni.substring(12,14)}${dni.substring(15,17)}"
	mac = mac.toUpperCase()
	return mac
}

//	========== SEND Commands to Devices ==========
private sendSpeakCmd(String action, Map body){
	logDebug("sendSpeakCmd: upnpAction = ${action}, upnpBody = ${body}")
	def deviceIP = getDataValue("deviceIP")
	def host = "${deviceIP}:9197"
	def hubCmd = new hubitat.device.HubSoapAction(
		path:	"/upnp/control/AVTransport1",
		urn:	 "urn:schemas-upnp-org:service:AVTransport:1",
		action:  action,
		body:	body,
		headers: [Host: host,
				  CONNECTION: "close"]
	)
	sendHubCommand(hubCmd)
}

private sendCmd(command){
	def host = "${getDataValue("deviceIP")}:55001"
	logDebug("sendCmd: Command= ${command}, host = ${host}")
	try {
		sendHubCommand(new hubitat.device.HubAction("""GET ${command} HTTP/1.1\r\nHOST: ${host}\r\n\r\n""",
													hubitat.device.Protocol.LAN, host))
	} catch (error) {
		logWarn("sendCmd, Command ${command}: No response received.  Error = ${error}")
		return "commsError"
	}
}

def parse(resp) {
	resp = parseLanMessage(resp)
	if (resp.port == "23ed") {
		logDebug("parse: Response received from UPNP port.")
		return
	} else if(resp.status != 200) {
		logDebug("parse: Error return: ${resp}")
		return
	} else if (resp.body == null){
		logDebug("parse: No data in command response.")
		return
	}
	def response = new XmlSlurper().parseText(resp.body)
	def respMethod = response.method
	def respData = response.response
	extractData(respMethod, respData)
}

private sendSyncCmd(command){
	def host = "http://${getDataValue("deviceIP")}:55001"
	logDebug("sendSyncCmd: Command= ${command}, host = ${host}")
	try {
		httpGet([uri: "${host}${command}", contentType: "text/xml", timeout: 5]) { resp ->
			if(resp.status != 200) {
				logDebug("sendSyncCmd, Command ${command}: Error return: ${resp.status}")
				return
			} else if (resp.data == null){
				logDebug("sendSyncCmd, Command ${command}: No data in command response.")
				return
			}
			def respMethod = resp.data.method
			def respData = resp.data.response
			extractData(respMethod, respData)
		}
	} catch (error) {
		if (command == "/UIC?cmd=%3Cname%3EGetPlayStatus%3C/name%3E") { return }
		logWarn("sendSyncCmd, Command ${command}: No response received.  Error = ${error}")
		return "commsError"
	}
}

def extractData(respMethod, respData) {
	logDebug("extractData: method = ${respMethod}, data = ${respData}")
	switch(respMethod) {
		case "SkipInfo":
			logWarn("respParse_${respMethod}: Function Failed. ${respData.errcode} / ${respData.errmessage}")
			break
		case "ErrorEvent":
			logWarn("respParse_${respMethod}: ${respData}")
			break
//	Music Player Response Methods
		case "MediaBufferStartEvent":
		case "StartPlaybackEvent":
			setEvent("status", "playing")
			state.updateTrackDescription = true
			runIn(2, setTrackDescription)
			break
		case "EndPlaybackEvent":
		case "MediaBufferEndEvent":
 		case "PausePlaybackEvent":
		case "StopPlaybackEvent":
			if (state.urlPlayback == true) { return }
			setEvent("status", "paused")
			state.updateTrackDescription = false
			break
		case "PlayStatus":
		case "PlaybackStatus":
			if (respData.playstatus == "playing") {  setEvent("status", "paused") }
			else if (respData.playstatus == "pause") { setEvent("status", "paused") }
			else if (respData.playstatus == "stop") { setEvent("status", "stopped") }
			return respData.playstatus
			break
		case "MusicInfo":
			return respData
			break
		case "RadioInfo":
			return respData
			break
		case "MusicPlayTime":
			return respData
			break
//	Audio Volume Response Methods
		case "VolumeLevel":
			def volume = respData.volume.toInteger()
			def volScale = 30
			if (getDataValue("hwType") == "Soundbar") { volScale = 100 }
			volume = Math.round(100*volume/volScale).toInteger()
			setEvent("level", volume)
			setEvent("volume", volume)
			return volume
			break
		case "MuteStatus":
			if (respData.mute == "on") { setEvent("mute", "muted")
			} else { setEvent("mute", "unmuted") }
			break
//	Speaker Control Response Methods
		case "CurrentFunc":
			def inputSource = respData.function
			def subMode = respData.submode
			if (inputSource != "wifi") { subMode = "none" }
			setEvent("inputSource", inputSource)
			setEvent("subMode", subMode)
			return [inputSource: inputSource, subMode: subMode]
			break
		case "7BandEQList":
			return respData
			break
		case "7bandEQMode":
		case "CurrentEQMode":
			setEvent("eqPreset", "${respData.presetname}")
			return respData
			break
		case "RepeatMode":
			def subMode = device.currentValue("subMode")
			def repeatMode = 0
			if (subMode == "dlna") {
				if (respData.repeat == "one") { repeatMode = "1" }
			} else if (subMode == "cp") {
				repeatMode = respData.repeatmode
			}
			setEvent("repeat", "${repeatMode}")
			break
		case "ShuffleMode":
			setEvent("shuffle", "${respData.shuffle}")
			break
		case "ToggleShuffle":
			def shuffleMode = "off"
			if (respData.shufflemode == "1") { shuffleMode = "on" }
			setEvent("shuffle", "${shuffleMode}")
			break
//	Group Speaker Response Methods
		case "ChVolMultich":
			setEvent("multiChVol", "${respData.channelvolume}")
			break
		case "AcmMode":
			def sourceMac = respData.audiosourcemacaddr
			def mainSpkDNI = convertMac(sourceMac)
			def spkType
			def groupType
			if (sourceMac == "00:00:00:00:00:00") {
				spkType = "Solo"
				groupType = ""
				mainSpkDNI = ""
			} else if (sourceMac == getDataValue("deviceMac")) {
				spkType = "Main"
				if (respData.acmmode == "aasync") { groupType = "Group" }
				else { groupType = "Surround" }
			} else {
				spkType = "Sub"
				groupType = "subSpeaker"
			}
			if (spkType != "Main") { state.activeGroupNo = "" }
			state.spkType = spkType
			state.mainSpkDNI = mainSpkDNI
			state.groupType = groupType
			break
		case "GroupName":
			return respData.groupname
			break
		case "MusicList":
			return respData
			break
		//	Ignored Response Methods
		case "QueueList":
		case "MultiQueueList":
		case "CpChanged":
		case "PowerStatus":
		case "EQDrc":
		case "EQMode":
		case "SongInfo":
		case "MultispkGroupStartEvent":
		case "MusicPlayTime":
		case "RadioList":
		case "Ungroup":
		case "UrlPlayback":
		case "RadioPlayList":
		case "DmsList":
		case "SpeakerStatus":
			break
		default:
			logWarn("extractData_${respMethod}: Method ignored. Data = ${respData}")
			break
	}
	return respMethod
}

def setEvent(evtName, newValue) {
	if (device.currentValue(evtName) != newValue) {
		sendEvent(name: evtName, value: newValue)
	}
}

//	===== Utility Methods =====
def logTrace(msg) {
	if (descriptionText == true) { log.trace "${device.label} ${driverVer()} ${msg}" }
}

def logInfo(msg) {
	if (descriptionText == true) { log.info "${device.label} ${driverVer()} ${msg}" }
}

def debugLogOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("Debug logging is false.")
}

def logDebug(msg){
	if(debug == true) { log.debug "${device.label} ${driverVer()} ${msg}" }
}

def logWarn(msg){ log.warn "${device.label} ${driverVer()} ${msg}" }

//	End-of-File
