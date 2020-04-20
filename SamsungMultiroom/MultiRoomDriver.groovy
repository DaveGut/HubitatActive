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
2019
06.15	3.0.01.	Update to reduce code size and better performance.  Additionally:
		a.	Changed preset channel data structure for future expansion.
		b.	Update state and data definitions.  Added update method for use in upgrades.
			Added this to the updated method.  It checks to see if it has run, and if not
			it will exit.
		c.	Added command 'stopAllActivity' that assures steaming music is disconnected.
		d.	Added button interface for 'stopAllActivity' and 'refresh'.
		e.	Added importUrl in 'definition'.  Allows updates editor import button.
		f.	Tested for > 40 hours, correcting any errors as encountered.
07.18	3.0.02	Updated playTrack, playTrackAndRestore, and playTrackAndResume to
		accommodate duration in the track data space.		
2020
04.20	3.1.0	Update for Hubitat Package Manager
===== HUBITAT INTEGRATION VERSION =======================================================*/
import org.json.JSONObject
def driverVer() { return "3.1.0" }

metadata {
	definition (name: "Samsung Wifi Speaker",
				namespace: "davegut",
				author: "David Gutheinz",
				importUrl: "https://github.com/DaveGut/Hubitat_Samsung-WiFi-Audio/blob/master/DeviceDriver/SamsungWifiSpeaker.groovy"
			   ){
		capability "MusicPlayer"
		capability "AudioVolume"
		capability "SpeechSynthesis"
		capability "AudioNotification"
		capability "Refresh"
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
		command "push", ["NUMBER"]
		//	===== Samsung Player Preset Capability =====
		attribute "Preset_1", "string"
		attribute "Preset_2", "string"
		attribute "Preset_3", "string"
		attribute "Preset_4", "string"
		attribute "Preset_5", "string"
		attribute "Preset_6", "string"
		attribute "Preset_7", "string"
		attribute "Preset_8", "string"
		command "presetCreate", ["NUMBER"]
		command "presetPlay", ["NUMBER"]
		command "presetDelete", ["NUMBER"]
		//	===== Samsung Group Spealer Capability =====
		attribute "Group_1", "string"
		attribute "Group_2", "string"
		attribute "Group_3", "string"
		attribute "activeGroup", "string"
		command "groupCreate", ["NUMBER"]
		command "groupStart", ["NUMBER"]
		command "groupStop"
		command "groupDelete", ["NUMBER"]
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
		input ("spkGroupLoc", "enum", title: "Surround/Stereo Speaker Location", options: positions)
		input ("debug", "bool",  title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool",  title: "Enable description text logging", defaultValue: true)
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
	state.triggered = false
	state.playingUrl = false
	state.updateTrackDescription = true
	state.playQueue = []
	state.recoveryData = [:]
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")
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

def updateInstallData() {
//	Use model to determine if update will run.  Will only run if critical updates are still not corrected.
	def model = getDataValue("model")
	if(model == null || model == "") {
		logInfo("updateInstallData: No Install Data Updates Required.")
		return
	}
	unschedule("poll")
	logInfo("updateInstallData: Updating device installation to new formats, if required")
//	Attribute Updates
	sendEvent(name: "repeat", value: "0")
	sendEvent(name: "shuffle", value: "off")
	sendCmd("/UIC?cmd=%3Cname%3EGetCurrentEQMode%3C/name%3E")
	pauseExecution(500)
//	Program Data Updates
	if (!getDataValue("inputSources")) {
		def inputSources = parent.getInputSources(device.getName())
		logInfo("Creating dataValue inputSources = ${inputSources}")
		updateDataValue("inputSources", inputSources)
		pauseExecution(500)
	}
	if (!getDataValue("hwType")) {
		def name = device.name
		def hwType = "Speaker"
		if (name[0..1] == "HW") { hwType = "Soundbar" }
		logInfo("Creating dataValue hwType = ${hwType}")
		updateDataValue("hwType", hwType)
		pauseExecution(500)
	}
//	Update Preset Data to new format
	for (int i = 1; i < 9; i++) {
		def psData = state."Preset_${i}_Data"
		if (psData == [] || psData == "" || psData == null) {
			logInfo("Preset_${i}_Data does not exist or is null")
		} else if (psData[0] == "cp") {
			def presetData = [:]
			presetData["type"] = psData[0]
			presetData["player"] = psData[2]
			presetData["playerNo"] = psData[3]
			presetData["station"] = psData[1]
			presetData["path"] = psData[4]
			logInfo("Updating state.Preset_${i}_Data to ${presetData}")
			state."Preset_${i}_Data" = presetData
			pauseExecution(200)
		} else {
			logInfo("Preset_${i}_Data alread correct.  Data = ${psData}")
		}
	}
//	Create new state variables
	state.groupType = getDataValue("groupType")
	pauseExecution(200)
	state.triggered = false
	pauseExecution(200)
	if (getDataValue("playingUrl") == "yes") { state.playingUrl = true }
	else { state.playingUrl = false }
	pauseExecution(200)
	state.spkType = getDataValue("spkType")
	pauseExecution(200)
	state.trackIcon = ""
	pauseExecution(200)
//	Remove state variables no longer used
	state.remove("unreachableCount")
	pauseExecution(500)
	state.remove("currentEqPreset")
	pauseExecution(500)
	state.remove("resumePlay")
	pauseExecution(500)
	state.remove("trackLength")
	pauseExecution(500)
	state.remove("currentSourceNo")
	pauseExecution(500)
	state.remove("groupNo")
	pauseExecution(500)
	state.remove("currentPresetNo")
	pauseExecution(200)
//	Null out device data no longer used
	updateDataValue("playingUrl", "")
	pauseExecution(200)
	updateDataValue("spkType", "")
	pauseExecution(200)
	updateDataValue("groupType", "")
	updateDataValue("groupName", "")
	pauseExecution(200)
	updateDataValue("noSubSpks", "")
	pauseExecution(200)
//	Key update - used to determin if the update process has already run.
	updateDataValue("model", null)
}

//	========== Capability Music Player ==========
def setLevel(level) { setVolume(level) }

def play() {
	playbackControl("play")
	getVolume()
	getPlayStatus()
	getSource()
	runIn(10, setTrackDescription)
}

def pause() {
	unschedule(setTrackDescription)
	playbackControl("pause")
}

def stop() {
	unschedule(setTrackDescription)
	playbackControl("stop")
}

def playbackControl(cmd) {
	logDebug("playbackControl: command = ${cmd}, source = ${device.currentValue("inputSource")}, submode = ${device.currentValue("subMode")}")
	if (device.currentValue("subMode") == "cp") {
		sendSyncCmd("/CPM?cmd=%3Cname%3ESetPlaybackControl%3C/name%3E" +
				"%3Cp%20type=%22str%22%20name=%22playbackcontrol%22%20val=%22${cmd}%22/%3E")
	} else {
		if (cmd == "play") { cmd = "resume" }
		else { cmd = "pause" }
		sendSyncCmd("/UIC?cmd=%3Cname%3ESetPlaybackControl%3C/name%3E" +
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
	} else {
		sendCmd("/UIC?cmd=%3Cname%3ESetTrickMode%3C/name%3E" +
				"%3Cp%20type=%22str%22%20name=%22trickmode%22%20val=%22next%22/%3E")
	}
	runIn(2, getPlayStatus)
	runIn(6, setTrackDescription)
}

def setTrack(trackUri) { logWarn("setTrack: Not implemented.") }

def restoreTrack(trackuri) { logWarn("restoreTrack: Not implemented.") }

def resumeTrack(trackuri) { logWarn("resumeTrack: Not implemented.") }

def getPlayStatus() {
	logDebug("getPlayStatus: source = ${device.currentValue("inputSource")}, submode = ${device.currentValue("subMode")}")
	if (device.currentValue("subMode") == "cp") {
		sendSyncCmd("/CPM?cmd=%3Cname%3EGetPlayStatus%3C/name%3E")
	} else {
		sendSyncCmd("/UIC?cmd=%3Cname%3EGetPlayStatus%3C/name%3E")
	}
}

def setTrackDescription() {
	def inputSource = device.currentValue("inputSource")
	def subMode = device.currentValue("subMode")
	logDebug("setTrackDescription: source = ${inputSource}, subMode = ${subMode}")
	unschedule("schedSetTrackDescription")
	state.updateTrackDescription = true
	if (device.currentValue("status") != "playing") {
		trackData = new JSONObject("{type: ${inputSource}}")
		sendEvent(name: "trackData", value: trackData)
		sendEvent(name: "trackDescription", value: "${inputSource}")
	} else if (subMode == "dlna") {
		sendSyncCmd("/UIC?cmd=%3Cname%3EGetMusicInfo%3C/name%3E")
	} else if (subMode == "cp") {
		sendSyncCmd("/CPM?cmd=%3Cname%3EGetRadioInfo%3C/name%3E")
	}
	return
}

def schedSetTrackDescription() {
	logDebug("schedSetTrackDescription: update = ${state.updateTrackDescription}")
	if (device.currentValue("subMode") != "dlna" && device.currentValue("subMode") != "cp") { return }
	if(state.updateTrackDescription == false) { return }
	def trackData = parseJson(device.currentValue("trackData"))
	def respData = sendSyncCmd("/UIC?cmd=%3Cname%3EGetCurrentPlayTime%3C/name%3E")

	def timelength
	if (trackData.title == "Commercial") { timelength = trackData.trackLength.toInteger() }
	else { timelength = respData.timelength.toInteger() }
	def playtime = respData.playtime.toInteger()
	if (timelength == 1 && playtime == 0) { timelength = 0 }
	if (timelength == null || timelength == 0) {
		state.updateTrackDescription = false
		return
	} else {
		def nextUpdate = timelength - playtime + 5
		runIn(nextUpdate, setTrackDescription)
	}
}

def parseMusicInfo(respData) {
	def trackData
	def trackDescription
	if (respData.@result == "ng") {
		trackDescription = "WiFi DLNA No Music"
		trackData = "{type: dlna, error: no music}"
	} else {
		def parentId =  respData.parentId
		if (parentId == "") { parentId = "unknown" }
		def parentId2 =  respData.parentid
		if (parentId2 == "") { parentId2 = "unknown" }
		def player =  respData.sourcename.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[]", "")
		if (player == "") { player = "unknown" }
		def album = respData.album.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[]", "")
		if (album == "") { album = "unknown" }
		def artist = respData.artist.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[]", "")
		if (artist == "") { artist = "unknown" }
		def title = respData.title.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[]", "")
		if (title == "") { title = "unknown" }
		def deviceUdn = respData.device_udn.toString().replace("uuid:", "")

		trackDescription = "${artist}: ${title}"
		trackData = "{type: ${device.currentValue("subMode")}, deviceUdn: ${deviceUdn}, "
		trackData += "playbackType: ${respData.playbacktype}, parentId: ${parentId}, parentId2: ${parentId2}, "
		trackData += "playIndex: ${respData.playindex}, album: ${album}, artist: ${artist}, "
		trackData += "title: ${title}, objectId: ${respData.objectid}}"
		logDebug("parseMusicInfo: trackData = ${trackData}")
		runIn(2, schedSetTrackDescription)
	}
	try{
		trackData = new JSONObject(trackData)
	} catch (error) {
		logWarn("setTrackData: ${trackData}")
		trackData = new JSONObject("{type: dlna, error: dataParseError}")
	}
	state.trackIcon = ""
	sendEvent(name: "trackDescription", value: "${trackDescription}")
	sendEvent(name: "trackData", value: trackData)
}

def parseRadioInfo(respData) {
	def player = respData.cpname
	if (player == null) {
		logWarn("parseRadioInfo: CP Name is null. trackData not updated")
		sendEvent(name: "trackDescription", value: "unknown")
		runIn(60, setTrackDescription)
		return "{error: no player id}"
	}
	def station = "unknown"
	if (respData.station != "") {
		station = respData.station.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[;]", "")
	} else if (respData.root != "") {
		station = respData.root.toString()
	}
	def path = respData.mediaid
	def artist = respData.artist.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[;]", "")
	def album = respData.album.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[;]", "")
	def title = respData.title.toString().replaceAll("[\\\\/:*?\"<>|,'\\]\\[;]", "")
	def trackLength = respData.tracklength.toString()
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
	def cpChannels = cpChannels()
	def playerNo  = cpChannels."${player}"
	def trackData = "{type: cp, player: ${player}, playerNo: ${playerNo}, station: ${station}, trackLength: ${trackLength}, "
	trackData += "path: ${path}, album: ${album}, artist: ${artist}, title: ${title}}"

	logDebug("parseRadioInfo: trackData = ${trackData}")
	state.trackIcon = "${respData.thumbnail}"
	sendEvent(name: "trackDescription", value: "${artist}: ${title}")
	try{
		trackData = new JSONObject(trackData)
	} catch (error) {
		logWarn("setTrackData: ${trackData}")
		trackData = new JSONObject("{error: dataParseError}")
	}
	sendEvent(name: "trackData", value: trackData)
	runIn(2, schedSetTrackDescription)
}

//	========== Capability Auido Volume ==========
def setVolume(volumelevel) {
	logDebug("setVolume: volumelevel = ${volumelevel}, spkType = ${state.spkType}")
	def curVol = device.currentValue("volume")
	def volScale = 30
	if (getDataValue("hwType") == "Soundbar") { volScale = 100 }
	if (volumelevel < 1 || volumelevel > 100) { return }
	def deviceVolume = Math.round(volScale*volumelevel/100).toInteger()
	sendSyncCmd("/UIC?cmd=%3Cname%3ESetVolume%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22volume%22%20val=%22${deviceVolume}%22/%3E")
	if (state.spkType == "Main") { groupVolume(volumelevel, curVol) }	//Grouped Speakers
}

def getVolume() {
	logDebug("getVolume")
	sendSyncCmd("/UIC?cmd=%3Cname%3EGetVolume%3C/name%3E")
}

def mute() {
	logDebug("mute")
	sendSyncCmd("/UIC?cmd=%3Cname%3ESetMute%3C/name%3E" +
			"%3Cp%20type=%22str%22%20name=%22mute%22%20val=%22on%22/%3E")
}
	
def unmute() {
	logDebug("unmute")
	sendSyncCmd("/UIC?cmd=%3Cname%3ESetMute%3C/name%3E" +
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
def playText(text, volume=null) {
	logDebug("playText: Text = ${text}, Volume = ${volume}")
	playTextAndResume(text, volume)
}

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

def playTrack(trackData, volume=null) {
	logDebug("playTrack: track = ${trackUri}, Volume = ${volume}")
	playTrackAndResume(trackData, volume)
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

def addToQueue(trackUri, duration, volume, resumePlay){
	if (volume == null) { volume = 0 }
	duration = duration + 3
	playData = ["trackUri": trackUri, 
				"duration": duration.toInteger(),
				"requestVolume": volume.toInteger(),
				"notificationVolume": notificationVolume.toInteger(),
				"resumePlay": resumePlay]
	state.playQueue.add(playData)

	if (state.playingUrl == false) {
		state.playingUrl = true
		runInMillis(100, startPlayViaQueue, [data: [resumePlay: resumePlay]])
	} else {
		runIn(20, startPlayViaQueue, [data: [resumePlay: resumePlay]])
	}
}

def startPlayViaQueue(data) {
	logDebug("startPlayViaQueue: queueSize = ${state.playQueue.size()}")
	if (state.playQueue.size() == 0) { return }
	def src = sendSyncCmd("/UIC?cmd=%3Cname%3EGetFunc%3C/name%3E")
	def vol = sendSyncCmd("/UIC?cmd=%3Cname%3EGetVolume%3C/name%3E")
	createRecoveryData(data.resumePlay)
	pause()
	if (device.currentValue("inputSource") != "wifi") {
		inputSource("wifi")
		pauseExecution(5000)
	}
	runIn(1, playViaQueue)
}

def createRecoveryData(resumePlay) {
	logDebug("createRecoveryData: resumePlay = ${resumePlay}")
	state.recoveryData["inputSource"] = device.currentValue("inputSource")
	state.recoveryData["subMode"] = device.currentValue("subMode")
	state.recoveryData["prevVolume"] = device.currentValue("volume")
	state.recoveryData["resumePlay"] = resumePlay
	if (resumePlay == true && device.currentValue("subMode") == "cp") {
		logDebug("createRecoveryData: trackData = ${device.currentValue("trackData")}")
		def trackData = parseJson(device.currentValue("trackData"))
		state.recoveryData["player"] = trackData.player
		state.recoveryData["path"] = trackData.path
	}
}

def playViaQueue() {
	logDebug("playViaQueue: queueSize = ${state.playQueue.size()}")
	if (state.playQueue.size() == 0) {
		resumePlayer()
		return
	}
	def playData = state.playQueue.get(0)
	state.playQueue.remove(0)
	logDebug("playViaQueue: playData = ${playData}, recoveryData = ${state.recoveryData}")
	
	def recoveryVolume = state.recoveryData.prevVolume.toInteger()
	def playVolume = playData.requestVolume
	if (playVolume == 0) { playVolume = recoveryVolume + playData.notificationVolume }
	if (playVolume > 100) { playVolume = 100 }

	setVolume(playVolume)
	execPlay(playData.trackUri, playData.resumePlay)
	runIn(playData.duration, playViaQueue)
}

def execPlay(trackUri, resumePlay) {
	if (getDataValue("hwType") == "Speaker") {		//	Speaker
		def playResume = 1
		if (resumePlay == false) { playResume = "0" }
		sendSyncCmd("/UIC?cmd=%3Cname%3ESetUrlPlayback%3C/name%3E" +
		"%3Cp%20type=%22cdata%22%20name=%22url%22%20val=%22empty%22%3E" +
		"%3C![CDATA[${trackUri}]]%3E%3C/p%3E" +
		"%3Cp%20type=%22dec%22%20name=%22buffersize%22%20val=%220%22/%3E" +
		"%3Cp%20type=%22dec%22%20name=%22seektime%22%20val=%220%22/%3E" +
		"%3Cp%20type=%22dec%22%20name=%22resume%22%20val=%22${playResume}%22/%3E")

	}	else {										//	Soundbar
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

def resumePlayer() {
	def data = state.recoveryData
	logDebug("resumePlayer: recoveryData = ${data}")
	if (state.playQueue.size() > 0) {
		runIn(1, playViaQueue)
		return
	}
	state.recoveryData = [:]
	state.playingUrl = false
	setVolume(data.prevVolume.toInteger())
	if (data.inputSource != "wifi") {
		inputSource(data.inputSource)
		return
	}

	if (data.resumePlay == false) { return }
	logInfo("resumePlayer: restoring play, data = ${data}")
	if (data.subMode == "cp") {
		switch(data.player) {
			case "Amazon":
			case "AmazonPrime":
				nextTrack()
				break
//			case "8tracks":
			default:
				sendSyncCmd("/CPM?cmd=%3Cname%3EPlayById%3C/name%3E" +
					"%3Cp%20type=%22str%22%20name=%22cpname%22%20val=%22${data.player}%22/%3E" +
					"%3Cp%20type=%22str%22%20name=%22mediaid%22%20val=%22${data.path}%22/%3E")
				break
//			default:
//				break
		}
	}
	play()
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
		case 12:		//	Toggle Input Source
			inputSource()
			break
		case 13:		//	Toggle Programmed Equalizer Presets
			equalPreset()
			break
		case 14:		//	Toggle Repeat
			repeat()
			break
		case 15:		//	Toggle Shuffle
			shuffle()
			break
		case 16:		//	Stop Playing Group of Speakers
			groupStop()
			break
		case 17:		//	Volume Up
			volumeUp()
			break
		case 18:		//	Volume Down
			volumeDown()
			break
		case 19:		//	Stop Player
			stopAllActivity()
			break
		case 20:
			refresh()
			break
		default:
			logWarn("${device.label}: Invalid Preset Number (must be 0 thru 19)!")
			break
	}
}

def unTrigger() { state.triggered = false }

//	========== Capability Refresh ==========
def refresh() {
	logDebug("refresh")
	def check = sendSyncCmd("/UIC?cmd=%3Cname%3EGetVolume%3C/name%3E")
	if (check == "commsError") {
		return
	}
	sendSyncCmd("/UIC?cmd=%3Cname%3EGetAcmMode%3C/name%3E")
	sendSyncCmd("/UIC?cmd=%3Cname%3EGetFunc%3C/name%3E")
	if (state.activeGroupNo == "" && device.currentValue("activeGroup")) {
		sendEvent(name: "activeGroup", value: "none")
	}
	getPlayStatus()
	runIn(4, setTrackDescription)
}

//	========== Samsung-specific Speaker Control ==========
def stopAllActivity() {
	logInfo("stopAllActivity: Disconect any streaming service.")
	if (state.spkType == "Main") {
		groupStop()
		pauseExecution(1000)
	}
	inputSource("bt")
	pauseExecution(3000)
	inputSource("wifi")
	if (getDataValue("hwType") == "Soundbar") {
		pauseExecution(3000)
		sendSyncCmd("/UIC?cmd=%3Cname%3ESetPowerStatus%3C/name%3E" +
					"%3Cp%20type=%22dec%22%20name=%22powerstatus%22%20val=%220%22/%3E")
	}
}

def repeat() {
	logDebug("repeat: source = ${device.currentValue("inputSource")}, submode = ${device.currentValue("subMode")}")
	if (device.currentValue("inputSource") != "wifi" && device.currentValue("inputSource") != "bt") {
		logWarn("repeat does not work for input source")
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
		if (repeat == "0") { repeatMode = "one" }
		sendSyncCmd("/UIC?cmd=%3Cname%3ESetRepeatMode%3C/name%3E" +
					"%3Cp%20type=%22str%22%20name=%22repeatmode%22%20val=%22${repeatMode}%22/%3E")
	}
}

def shuffle() {
	logDebug("shuffle: source = ${device.currentValue("inputSource")}, submode = ${device.currentValue("subMode")}")
	if (device.currentValue("inputSource") != "wifi" && device.currentValue("inputSource") != "bt") {
		logWarn("shuffle does not work for input source")
		return
	}
	if (device.currentValue("subMode") == "cp") {
		def shuffleMode = "1"
		if (device.currentValue("shuffle") == "on") { shuffleMode = "0" }
		sendSyncCmd("/CPM?cmd=%3Cname%3ESetToggleShuffle%3C/name%3E" +
				"%3Cp%20type=%22dec%22%20name=%22mode%22%20val=%22${shuffleMode}%22/%3E")
	} else {
		def shuffleMode = "on"
		if (device.currentValue("shuffle") == "on") { shuffleMode = "off" }
		sendSyncCmd("/UIC?cmd=%3Cname%3ESetShuffleMode%3C/name%3E" +
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
		if (!currentSource) {
			inputSource(defaultSource)
			return
		}
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
	sendSyncCmd("/UIC?cmd=%3Cname%3ESetFunc%3C/name%3E" +
				"%3Cp%20type=%22str%22%20name=%22function%22%20val=%22${source}%22/%3E")
	runIn(4, getSource)
	runIn(8, setTrackDescription)
}

def getSource() {
	logDebug("getSource")
	sendSyncCmd("/UIC?cmd=%3Cname%3EGetFunc%3C/name%3E")
	return
}

//	========== Samsung Player Preset Capability ==========
def presetCreate(preset) {
	logDebug("presetCreate: preset = ${preset}")
	def hold = getSource()
	def subMode = device.currentValue("subMode")
	if (preset < 1 || preset > 8) {
		logWarn("presetCreate: Preset Number out of range (1-8)!")
		return
	} else if (subMode != "cp") {
		logWarn("presetCreate: Can't create from media from source!")
		return
	}
	state."Preset_${preset}_Data" = [:]
	hold = setTrackDescription()
	pauseExecution(1000)
	def trackData = parseJson(device.currentValue("trackData"))
	if (trackData.player == "Amazon" || trackData.player == "AmazonPrime") {
		logWarn("presetCreate: Preset not currently supported for Amazon")
		return
	} else {
		def presetData = state."Preset_${preset}_Data"
		presetData["type"] = trackData.type
		presetData["player"] = trackData.player
		presetData["playerNo"] = trackData.playerNo
		presetData["station"] = trackData.station
		presetData["path"] = trackData.path
		sendEvent(name: "Preset_${preset}", value: "${trackData.station}")
		logInfo("presetCreate: create preset ${preset}, data = ${presetData}")
	}
}

def presetPlay(preset) {
	def psName = device.currentValue("Preset_${preset}")
	if (preset < 1 || preset > 8) {
		logWarn("presetPlay: Preset Number out of range (1-8)!")
		return
	} else if (psName == "preset${preset}") {
		logWarn("presetPlay: Preset Not Set!")
		return
	}
	def psData = state."Preset_${preset}_Data"
	logDebug("presetPlay: preset = ${preset}, psName = ${psName}, psData = ${psData}")
	if (device.currentValue("inputSource") != "wifi") {
		inputSource("wifi")
		pauseExecution(5000)
	}

	if (psData.playerNo != "99") {
		sendSyncCmd("/CPM?cmd=%3Cname%3ESetCpService%3C/name%3E" +
					"%3Cp%20type=%22dec%22%20name=%22cpservice_id%22%20val=%22${psData.playerNo}%22/%3E")
	}
	sendSyncCmd("/CPM?cmd=%3Cname%3EPlayById%3C/name%3E" +
				"%3Cp%20type=%22str%22%20name=%22cpname%22%20val=%22${psData.player}%22/%3E" +
				"%3Cp%20type=%22str%22%20name=%22mediaid%22%20val=%22${psData.path}%22/%3E")
	play()
	logInfo("presetPlay: Playing ${psName}")
}

def presetDelete(preset) {
	logDebug("presetDelete: preset = ${preset}")
	if (preset < 1 || preset > 8) {
		logWarn("presetDelete: Preset Number out of range (1-8)!")
		return
	}
	state."Preset_${preset}_Data" = ""
	sendEvent(name: "Preset_${preset}", value: "preset${preset}")
	logInfo("presetDeleted: preset = ${preset}")
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
	if(resp.status != 200) {
		logWarn("parse: Error return: ${resp}")
		return
	} else if (resp.body == null){
		logWarn("parse: No data in command response.")
		return
	} else if (resp.port == "23ed") {
		logDebug("parse: Response received from UPNP port.")
		return
	}
	def respMethod = (new XmlSlurper().parseText(resp.body)).method
	def respData = (new XmlSlurper().parseText(resp.body)).response
	extractData(respMethod, respData)
}

private sendSyncCmd(command){
	def host = "http://${getDataValue("deviceIP")}:55001"
	logDebug("sendSyncCmd: Command= ${command}, host = ${host}")
	try {
		httpGet([uri: "${host}${command}", contentType: "text/xml", timeout: 5]) { resp ->
		if(resp.status != 200) {
			logWarn("sendSyncCmd, Command ${command}: Error return: ${resp.status}")
			return
		} else if (resp.data == null){
			logWarn("sendSyncCmd, Command ${command}: No data in command response.")
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
	logInfo("extractData: method = ${respMethod}, data = ${respData}")
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
			sendEvent(name: "status", value: "playing")
			state.updateTrackDescription = true
			break
		case "EndPlaybackEvent":
		case "MediaBufferEndEvent":
 		case "PausePlaybackEvent":
		case "StopPlaybackEvent":
			sendEvent(name: "status", value: "paused")
			state.updateTrackDescription = false
			break
		case "PlayStatus":
		case "PlaybackStatus":
			if (respData.playstatus == "play") { sendEvent(name: "status", value: "playing") }
			else if (respData.playstatus == "pause") { sendEvent(name: "status", value: "paused") }
			else if (respData.playstatus == "stop") { sendEvent(name: "status", value: "stopped") }
			break
		case "MusicInfo":
			return parseMusicInfo(respData)
			break
		case "RadioInfo":
			return parseRadioInfo(respData)
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
			sendEvent(name: "level", value: volume)
			sendEvent(name: "volume", value: volume)
			break
		case "MuteStatus":
			if (respData.mute == "on") {
				sendEvent(name: "mute", value: "muted")
			} else {
				sendEvent(name: "mute", value: "unmuted")
			}
				break
//	Speaker Control Response Methods
		case "CurrentFunc":
			def inputSource = respData.function
			def subMode = respData.submode
			sendEvent(name: "inputSource", value: inputSource)
			if (inputSource != "wifi") { subMode = "none" }
			sendEvent(name: "subMode", value: subMode)
			return respMethod
			break
		case "7BandEQList":
			return respData
			break
		case "7bandEQMode":
		case "CurrentEQMode":
			sendEvent(name: "eqPreset", value: "${respData.presetname}")
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
			sendEvent(name: "repeat", value: "${repeatMode}")
			break
		case "ShuffleMode":
			sendEvent(name: "shuffle", value: "${respData.shuffle}")
			break
		case "ToggleShuffle":
			def shuffleMode = "off"
			if (respData.shufflemode == "1") { shuffleMode = "on" }
			sendEvent(name: "shuffle", value: "${shuffleMode}")
			break
//	Group Speaker Response Methods
		case "ChVolMultich":
			sendEvent(name: "multiChVol", value: "${respData.channelvolume}")
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

		
//	Ignored Response Methods
		case "CpChanged":
		case "MusicList":
		case "PowerStatus":
		case "EQDrc":
		case "EQMode":
		case "SongInfo":
		case "MultiQueueList":
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

def logInfo(msg) {
	if (descriptionText == true) { log.info "${device.label} ${driverVer()} ${msg}" }
}

def logDebug(msg){
	if(debug == true) { log.debug "${device.label} ${driverVer()} ${msg}" }
}

def logWarn(msg){ log.warn "${device.label} ${driverVer()} ${msg}" }

//	End-of-File
