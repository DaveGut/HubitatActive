/*===== HUBITAT INTEGRATION VERSION ===========================
Samsung WiFi Audio Hubitat Driver
Copyright 2019 Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this  file except in compliance with the
License. You may obtain a copy of the License at:
		http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
either express or implied. See the License for the specific 
language governing permissions and limitations under the 
License.
This device handler interfaces to Samsung WiFi Soundbars.  
Testing was completed on the HW-MS650 Soundbar and the R1
Speaker using commands derived frominternet data.
===== DISCLAIMERS==============================================
THE AUTHOR OF THIS INTEGRATION IS NOT ASSOCIATED WITH SAMSUNG.
THIS CODE USES TECHNICAL DATA ON THE SPEAKERS DERIVED FROM
GITHUB SOURCES AS WELL AS PERSONAL INVESTIGATION.
===== History =================================================
2019
01.15	2.0.01.  Updates
		a.	Added buttons for dashboard control
		b.	Moved device comms to this driver
		c.	Various clean-ups.
01.19	2.0.02.  Fixes to correct too many log entries
		a.	Added code to stop refresh comms when error with
			device exceeds 3.  Also added reset if comms
			are successful.
		b.	Added preference for refresh rate.
		c.	Enforced debug logging to run for 15 minutes each
			time started.
03.06	2.0.02	Updated to add Capability "Speech Synthesis"
===== HUBITAT INTEGRATION VERSION ===========================*/
	def driverVersion() { return "TEST" }
metadata {
	definition (name: "zzzSamsung WiFi Audio Driver", namespace: "davegut", author: "David Gutheinz") {
		capability "Switch"
		capability "Music Player"
		capability "Audio Notification"
		capability "Refresh"
		capability "Speech Synthesis"
		//	===== Speaker Control Commands / Attributes
		command "repeat"
		attribute "repeat", "string"
		command "shuffle"
		attribute "shuffle", "string"
		command "equalPreset"
		attribute "eqPreset", "string"
		command "inputSource"
		attribute "inputSource", "string"
/////////////////////////////////////////////////////////////////////////
		attribute "subMode", "string"
		//	===== Player Preset Attributes =====
		command "presetPlay", ["NUMBER"]
		command "presetCreate", ["NUMBER"]
		attribute "Trigger", "string"
		attribute "Preset_1", "string"
		attribute "Preset_2", "string"
		attribute "Preset_3", "string"
		attribute "Preset_4", "string"
		attribute "Preset_5", "string"
		attribute "Preset_6", "string"
		attribute "Preset_7", "string"
		attribute "Preset_8", "string"
		//	===== Group Preset Attributes =====
		command "groupStart", ["NUMBER"]
		command "groupCreate", ["NUMBER"]
		command "groupStop"
		attribute "Group_1", "string"
		attribute "Group_2", "string"
		attribute "Group_3", "string"
		attribute "activeGroup", "string"
		//	===== Button Commands / Attributes =====
 		capability "PushableButton"
		command "push", ["NUMBER"]
		attribute "numberOfButtons", "NUMBER"
		attribute "pushed", "NUMBER"
		
		attribute "errorMessage", "string"
	}
}

preferences {
//	===== Set response modes (to display log & debug messages) =====
	input name: "debugMode", type: "bool", title: "Display debug messages?", required: false
	def refreshRate = [:]
	refreshRate << ["5" : "Refresh every 5 minutes"]
	refreshRate << ["10" : "Refresh every 10 minutes"]
	refreshRate << ["15" : "Refresh every 15 minutes"]
	refreshRate << ["30" : "Refresh every 30 minutes"]
	input ("refresh_Rate", "enum", title: "Device Refresh Rate", options: refreshRate)

//	===== Group Preset Preferences (Speaker Location) used only for Stereo and Surround modes =====
	def positions = ["fl": "stereo left", "fr": "stereo right", "front": "surround soundbar",  
					 "rl": "surround left", "rr": "surround right"]
	input name: "spkGroupLoc", type: "enum", title: "Surround/Stereo Speaker Location", 
		options: positions, required: false

//	===== Soundbars with non-wifi rear speakers =====
	if (getDataValue("hwType") == "Soundbar") {
		def rearLevels = ["-6", "-5", "-4", "-3", "-2", "-1", "0", "1", "2", "3", "4", "5", "6"]
		input name: "rearSpeaker", type: "bool", title: "Rear Speaker?", required: false
		input name: "rearLevel", type: "enum", title: "Rear Speaker Level", options: rearLevels, 
			required: false
		def ttsLanguages = ["en-au":"English (Australia)","en-ca":"English (Canada)",
			"en-gb":"English (Great Britain)","en-us":"English (United States)",
			"en-in":"English (India)","ca-es":"Catalan","zh-cn":"Chinese (China)",
			"zh-hk":"Chinese (Hong Kong)","zh-tw":"Chinese (Taiwan)","da-dk":"Danish",
			"nl-nl":"Dutch","fi-fi":"Finnish","fr-ca":"French (Canada)",
			"fr-fr":"French (France)","de-de":"German","it-it":"Italian","ja-jp":"Japanese",
			"ko-kr":"Korean","nb-no":"Norwegian","pl-pl":"Polish","pt-br":"Portuguese (Brazil)",
			"pt-pt":"Portuguese (Portugal)","ru-ru":"Russian","es-mx":"Spanish (Mexico)",
			"es-es":"Spanish (Spain)","sv-se":"Swedish (Sweden)"]
		input name: "ttsApiKey", type: "password", title: "TTS Site Key", 
			description: "From http://www.voicerss.org/registration.aspx", required: false
		input name: "ttsLang", type: "enum", title: "TTS Language", options: ttsLanguages, 
			required: false
	}
}

//	===== Initialization Functions =====
def installed() {
	sendEvent(name: "eqPreset", value: "EqPreset Not Set")
	sendEvent(name: "errorMessage", value: "None")
	getSources()
	Set7bandEQMode(0)
	state.currentEqPreset = 0
	state.currentSourceNo = 0
	state.resumePlay = "1"
	state.updateTrackDescription = "yes"
	device.updateSetting("debugMode", [type: "bool", value: true])
	runIn(900, debugModeOff)

	sendEvent(name: "Preset_1", value: "preset1")
	sendEvent(name: "Preset_2", value: "preset2")
	sendEvent(name: "Preset_3", value: "preset3")
	sendEvent(name: "Preset_4", value: "preset4")
	sendEvent(name: "Preset_5", value: "preset5")
	sendEvent(name: "Preset_6", value: "preset6")
	sendEvent(name: "Preset_7", value: "preset7")
	sendEvent(name: "Preset_8", value: "preset8")
 	sendEvent(name: "Group_1", value: "group1")
 	sendEvent(name: "Group_2", value: "group2")
 	sendEvent(name: "Group_3", value: "group3")
 	sendEvent(name: "activeGroup", value: "No Group Active")

 	state.activeGroupNo = ""
	state.mainSpkDNI = ""
	state.group_1_Data = [:]
	state.group_2_Data = [:]
	state.group_3_Data = [:]
	state.cpChannels = ["Pandora": "0", "Spotify": "1",
		"Deezer": "2", "Napster": "3", "8tracks": "4",
		"iHeartRadio": "5", "Rdio": "6", "BugsMusic": "7",
		"JUKE": "8", "7digital": "9", "Murfie": "10",
		"JB HI-FI Now": "11", "Rhapsody": "12",
		"Qobuz": "13", "Stitcher": "15", "MTV Music": "16",
		"Milk Music": "17", "Milk Music Radio": "18",
		"MelOn": "19", "Tidal HiFi": "21",
		"SiriusXM": "22", "Anghami": "23",
		"AmazonPrime": "24", "Amazon": "98", "TuneIn": "99"]
	
	updated()
}

def updated() {
	log.info "updating ...."
	logDebug("${device.label} updated: refresh = ${refresh_Rate}, debug = ${debugMode}")
	state.unreachableCount = 0
	if (rearLevel){ SetRearLevel(rearLevel) }

	sendEvent(name: "numberOfButtons", value: 17)
	sendEvent(name: "Trigger", value: "notArmed")
	if (debugMode == true) {
		device.updateSetting("debugMode", [type: "bool", value: true])
		runIn(900, debugModeOff)
	}
		
	if (!device.currentValue("shuffle")) {
		sendEvent(name: "shuffle", value: "Shuffle Inactive")
	}
	if (!device.currentValue("repeat")) {
		sendEvent(name: "repeat", value: "Repeat Inactive")
	}
	
	updateDataValue("driverVersion", driverVersion())
	switch(refresh_Rate) {
		case "5" :
			runEvery5Minutes(refresh)
			break
		case "10" :
			runEvery10Minutes(refresh)
			break
		case "15" :
			runEvery15Minutes(refresh)
			break
		default:
			runEvery30Minutes(refresh)
	}

	runIn(2, refresh)
}

def uninstalled() {
	log.info "Uninstalling ${device.label}"
	try{ parent.deleteChildDevice(device.device.NetworkId) }
	catch (e) {}
}

def debugModeOff() {
	log.debug "${device.label} debugModeOff: Debugging logging off.  Goodbye!"
	device.updateSetting("debugMode", [type: "bool", value: off])
}

def push(pushed) {
	logDebug("push: button = ${pushed}, triggered = ${state.triggered}")
	sendEvent(name: "pushed", value: pushed)
	switch(pushed) {
		case 0 :
		if (device.currentValue("Trigger") == "Armed") { sendEvent(name: "Trigger", value: "notArmed") }
		else { sendEvent(name: "Trigger", value: "Armed") }
			break
		case 1 :
		case 2 :
		case 3 :
		case 4 :
		case 5 :
		case 6 :
		case 7 :
		case 8 :
			if (device.currentValue("Trigger") == "notArmed") {
				presetPlay(pushed)
			} else {
				presetCreate(pushed)
				sendEvent(name: "Trigger", value: "notArmed")
			}
			break
		case 9:
		case 10:
		case 11:
			if (device.currentValue("Trigger") == "notArmed") {
				groupStart(pushed-8)
			} else {
				groupCreate(pushed-8)
				sendEvent(name: "Trigger", value: "notArmed")
			}
			break
		case 12:
			inputSource()
			break
		case 13:
			equalPreset()
			break
		case 14:
			repeat()
			break
		case 15:
			shuffle()
			break
		case 16:
			groupStop()
			break
		default:
			log.warn "${device.label}: Invalid Preset Number (must be 0 thru 16)!"
	}
}

def getSources() {
	def model = getDataValue("model")
	logDebug("getSources: model = ${model}")
	def sources = [:]
	switch(model) {
		case "HW-MS650":
		case "HW-MS6500":
			sources = ["wifi","bt","aux","optical","hdmi"]
 			break
		case "HW-MS750":
		case "HW-MS7500":
			sources = ["wifi", "bt", "aux", "optical", "hdmi1", "hdmi2"]
			break
		case "HW-J8500":
		case "HW-J7500":
		case "HW-J650":
		case "HW-H750":
		case "HW-K650":
			sources = ["wifi", "bt", "soundshare", "aux", "optical", "usb", "hdmi"]
			break
		default:
			sources = ["wifi","bt","soundshare"]
			break
	}
	state.sources = sources
}

//	===== Device Control Functions =====
def on() {
	logDebug("on")
	def cmds = [
		SetPowerStatus("1"),
		sendEvent(name: "switch", value: "on"),
		GetFunc(),
		GetMute(),
		GetVolume(),
		pauseExecution(2000),
		setTrackDescription()
	]
	cmds
}

def off() {
	logDebug("off")
	stop()
	SetPowerStatus("0")
	sendEvent(name: "switch", value: "off")
	sendEvent(name: "trackDescription", value: "OFF")
}

def inputSource() {
	def sources = state.sources
	logDebug("inputSource: sources = ${sources}")
	def totSources = sources.size()
	def sourceNo = state.currentSourceNo.toInteger()
	if (sourceNo + 1 >= totSources) {
		sourceNo = 0
	} else {
		sourceNo = sourceNo + 1
	}
	state.currentSourceNo = sourceNo
	sendEvent(name: "inputSource", value: sources[sourceNo])
	SetFunc(sources[sourceNo])
	runIn(5, setTrackDescription)
}

def setLevel(level) {
	logDebug("setLevel: level = ${level}")
	if (level == null) { level = device.currentValue("level") }
	else if (level > 100) { level = 100 }
	def curVol = device.currentValue("level").toInteger()
	def scale = getDataValue("volScale").toInteger()
	def deviceLevel = Math.round(scale*level/100).toInteger()
	SetVolume(deviceLevel)
	groupVolume(level, curVol)
}

def mute() { SetMute("on") }

def unmute() { SetMute("off") }

def equalPreset() {
	Get7BandEQList()
}

def cmdEqPreset(totPresets) {
	logDebug("cmdEqPreset: totPresets = ${totPresets}")
	def newEqPreset = ""
	def totalPresets = totPresets.toInteger() - 1
	def currentEqPreset = state.currentEqPreset
	if(currentEqPreset >= totalPresets) {
		newEqPreset = 0
	} else {
		newEqPreset = currentEqPreset + 1
	}
	Set7bandEQMode(newEqPreset)
}

def getPwr() {
	def hwType = getDataValue("hwType")
	logDebug("getPwr: hwType = ${hwType}")
	if (hwType == "Soundbar") {
		GetPowerStatus()
	} else {
		if (device.currentValue("status") == "playing") {
			sendEvent(name: "switch", value: "on")
		} else {
			sendEvent(name: "switch", value: "off")
		}
	}
}

//	===== Music Control Functions =====
def play() {
	def subMode = device.currentValue("subMode")
	def inputSource = device.currentValue("inputSource")
	logDebug("play: source = ${inputSource}, submode = ${subMode}")
	if (device.currentValue("inputSource") != "wifi") {
		return
	}
	if (subMode == "cp") {
		cpm_SetPlaybackControl("play")
	} else {
		uic_SetPlaybackControl("resume")
	}
}

def pause() {
	def subMode = device.currentValue("subMode")
	def inputSource = device.currentValue("inputSource")
	logDebug("pause: source = ${inputSource}, submode = ${subMode}")
	if (device.currentValue("inputSource") != "wifi") {
		return
	}
	if (subMode == "cp") {
		cpm_SetPlaybackControl("pause")
	} else {
		uic_SetPlaybackControl("pause")
	}
	unschedule("setTrackDesciption")
}

def stop() {
	def subMode = device.currentValue("subMode")
	def inputSource = device.currentValue("inputSource")
	logDebug("stop: source = ${inputSource}, submode = ${subMode}")
	if (device.currentValue("inputSource") != "wifi") {
		return
	}
	if (subMode == "cp") {
		cpm_SetPlaybackControl("stop")
	} else {
		uic_SetPlaybackControl("pause")
	}
	unschedule("setTrackDesciption")
}

def getPlayStatus() {
	def subMode = device.currentValue("subMode")
	def inputSource = device.currentValue("inputSource")
	logDebug("getPlayStatus: source = ${inputSource}, submode = ${subMode}")
	if (device.currentValue("inputSource") != "wifi") {
		return
	}
	if (subMode == "cp") {
		cpm_GetPlayStatus()
	} else {
		uic_GetPlayStatus()
	}
}

def previousTrack() {
	def subMode = device.currentValue("subMode")
	def inputSource = device.currentValue("inputSource")
	logDebug("previousTrack: source = ${inputSource}, submode = ${subMode}")
	if (device.currentValue("inputSource") != "wifi") {
		return
	}
	def cmds = []
	switch(subMode) {
		case "dlna":
			cmds << SetTrickMode("previous")
			cmds << pauseExecution(200)
			cmds << SetTrickMode("previous")
			cmds << pauseExecution(3000)
			cmds << GetMusicInfo()
			break
	   case "cp":
			cmds << SetPreviousTrack()
			cmds << pauseExecution(200)
			cmds << SetPreviousTrack()
			cmds << pauseExecution(3000)
			cmds << GetRadioInfo()
			break
		default:
			log.info "nextTrack: Next track not valid for device or mode"
			break
	}
	cmds
}

def nextTrack() {
	def subMode = device.currentValue("subMode")
	def inputSource = device.currentValue("inputSource")
	logDebug("nextTrack: source = ${inputSource}, submode = ${state.subMode}")
	if (device.currentValue("inputSource") != "wifi") {
		return
	}
	switch(subMode) {
		case "dlna":
			stop()
			SetTrickMode("next")
			runIn(5, GetMusicInfo)
			break
		case "cp":
			SetSkipCurrentTrack()
			runIn(5, GetRadioInfo)
			break
		default:
			log.info "nextTrack: Next track not valid for device or mode"
			break
	}
}

def shuffle() {
	def subMode = device.currentValue("subMode")
	def inputSource = device.currentValue("inputSource")
	logDebug("shuffle: source = ${inputSource}, submode = ${subMode}")
	if (device.currentValue("inputSource") != "wifi") {
		return
	}
	switch(state.subMode) {
		case "dlna":
			if (device.currentValue("shuffle") == "Shuffle 0" || device.currentValue("shuffle") == "Shuffle inactive") {
				SetShuffleMode("on")
			} else {
				SetShuffleMode("off")
			}
			break
		case "cp":
			if (device.currentValue("shuffle") == "Shuffle 0" || device.currentValue("shuffle") == "Shuffle inactive") {
				SetToggleShuffle("1")
			} else {
				SetToggleShuffle("0")
			}
			break
		default:
			log.info "toggleShuffle: ShuffleMode not valid for device or mode"
		 	return
	}
}

def repeat() {
	def subMode = device.currentValue("subMode")
	def inputSource = device.currentValue("inputSource")
	logDebug("repeat: source = ${inputSource}, submode = ${subMode}")
	if (device.currentValue("inputSource") != "wifi") {
		return
	}
	 switch(subMode) {
		case "dlna":
			if (device.currentValue("repeat") == "Repeat 0" || device.currentValue("repeat") == "Repeat inactive") {
				uic_SetRepeatMode("one")
			} else {
				uic_SetRepeatMode("off")
			}
			break
		case "cp":
			if (device.currentValue("repeat") == "Repeat 0" || device.currentValue("repeat") == "Repeat inactive") {
				cpm_SetRepeatMode("1")
			} else {
				cpm_SetRepeatMode("0")
			}
			break
		default:
			log.info "toggleRepeat: Repeat not valid for device or mode"
		 	return
	}
}

//	===== Content Information Methods =====
def setTrackDescription() {
	unschedule("setTrackDesciption")
	state.updateTrackDescription = "yes"
	def subMode = device.currentValue("subMode")
	def source = device.currentValue("inputSource")
	logDebug("setTrackDescription: source = ${source}")
	if (source != "wifi") {
		sendEvent(name: "trackDescription", value: source)
		state.updateTrackDescription = "no"
		sendEvent(name: "shuffle", value: "Shuffle inactive")
		sendEvent(name: "repeat", value: "Repeat inactive")
	} else {
		switch(subMode) {
			case "dlna":
				GetMusicInfo("generalResponse")
				break
			case "cp":
				GetRadioInfo("generalResponse")
				break
			case "device":
			case "dmr":
				sendEvent(name: "trackDescription", value: "WiFi ${submode}")
				state.updateTrackDescription = "no"
				sendEvent(name: "shuffle", value: "Shuffle inactive")
				sendEvent(name: "repeat", value: "Repeat inactive")
				GetAcmMode()	//	Determine what data is here and how to parse and use.
				break
			default:
				sendEvent(name: "trackDescription", value: "WiFi (${submode})")
				state.updateTrackDescription = "no"
				sendEvent(name: "shuffle", value: "Shuffle inactive")
				sendEvent(name: "repeat", value: "Repeat inactive")
		}
	}
	runIn(5, getPlayStatus)
}

def getPlayTime() {
	def update = state.updateTrackDescription
	logDebug("getPlayTime: update = ${update}")
	if(update == "no") {
		log.info "getPlayTime: schedSetTrackDescription turned off"
		return
	} else {
		GetCurrentPlayTime()
	}
}

def schedSetTrackDescription(playtime) {
	logDebug("schedSetTrackDescription: playtime = ${playtime}")
	def nextUpdate
	if (state.trackLength == null || state.trackLength == 0) {
		state.updateTrackDescription = "no"
		return
	} else {
		nextUpdate = state.trackLength - playtime + 7
		state.recoverDlnaTime = nextUpdate
	}
	runIn(nextUpdate, setTrackDescription)
}

//	===== Preset Player Functions =====
def presetPlay(preset) {
	logDebug("presetPlay: preset = ${preset}")
	def psName = device.currentValue("Preset_${preset}")
	if (preset < 1 || preset > 8) {
		log.warn "presetPlay: Preset Number out of range (1-8)!"
		return
	} else if (psName == null) {
		log.info "presetPlay: Preset Not Set!"
		return
	}
	def psData = state."Preset_${preset}_Data"
	logDebug("${device.label} presetPlay: psData = ${psData}")
	def player = psData[2]
	state.currentlyPlaying = psData
	switch(player) {
		case "Amazon":
			SetSelectAmazonCp()
			break
		case "TuneIn":
			SetSelectRadio()
			break
 		default:
			SetCpService(psData[3])
			break
	}
}

def presetCreate(preset) {
	logDebug("preset: preset = ${preset}")
	def subMode = device.currentValue("subMode")
	def psName = device.currentValue("Preset_${preset}")
	if (preset < 1 || preset > 8) {
		log.warn "presetCreate: Preset Number out of range (1-8)!"
		return
	} else if (state != "cp") {
		log.warn "presetCreate: Can't preset media from source!"
	} else {
		GetRadioInfo()
		runIn(3, contPresetCreate, [data: [preset]])
	}
}

def contPresetCreate(data) {
	logDebug("contPresetCreate: preset data = ${data}")
	def subMode = device.currentValue("subMode")
	def preset = data[0]
   	def currentlyPlaying = state.currentlyPlaying
	state."Preset_${preset}_Data" = ["${subMode}", 
									 "${currentlyPlaying[1]}", 
								   	 "${currentlyPlaying[2]}", 
									 "${currentlyPlaying[3]}", 
								   	 "${currentlyPlaying[4]}"]
	sendEvent(name: "Preset_${preset}", value: "${currentlyPlaying[1]}")
}

//	===== Create Group Preset =====
def groupCreate(groupNo) {
	logDebug("groupCreate: groupNo = ${groupNp}")
	if (groupNo < 1 || groupNo > 3) {
		log.info "groupCreate: Group Number out of range (1-3)!"
		setErrorMsg("groupCreate: Group Number out of range (1-3)!")
		return
	}
	updateDataValue("noSubSpks", "0")
	updateDataValue("spkType", "")
	updateDataValue("groupType", "")
	updateDataValue("groupName", "")
	state.groupNo = groupNo
	GetAcmMode()
	pauseExecution(200)
	GetGroupName()	//	try to move to generalResponse
	runIn(1, contGroupCreate)
}

def contGroupCreate() {
	def groupNo = state.groupNo
	def spkType = getDataValue("spkType")
	logDebug("$contGroupCreate: spkType = ${spkType}")
	if (spkType != "Main") {
		log.warn "contGroupCreate: Not a group main speaker."
		return
	}
	parent.requestSubSpeakerData(device.deviceNetworkId)
	runIn(5, generateGroupData)
}

def generateSpeakerData() {
	logDebug("generateSpeakerData")
	GetChVolMultich()
	pauseExecution(200)
	GetVolume()
	pauseExecution(500)
	def speakerData = [:]
	speakerData["spkName"] = device.label
	speakerData["spkDNI"] = device.deviceNetworkId
	speakerData["spkMAC"] = getDataValue("deviceMac")
	speakerData["spkChVol"] = device.currentValue("multiChVol")
	speakerData["spkDefVol"] = device.currentValue("level")
	speakerData["spkLoc"] = spkGroupLoc
	return speakerData
}

def getSubSpeakerData(mainSpkDNI) {
	logDebug("getSubSpeaderData: mainSpkDNI = ${mainSpkDNI}")
	updateDataValue("spkType", "")
	GetAcmMode()	//	try to move to generalResponse
	runIn(1, contSubSpeakerData, [data: mainSpkDNI])
}

def contSubSpeakerData(data) {
	if (getDataValue("spkType") != "Sub") {
		log.warn "contSubSpeakerData: Not a group sub-speaker."
		return
	}
	def subSpkData = generateSpeakerData()
	logDebug("contSubSpkData = subSpkData = ${subSpkData}")
	parent.sendSubspeakerDataToMain(data, subSpkData)
}

def createTempSubData(subSpkData) {
	def spkNo = getDataValue("noSubSpks").toInteger().toInteger() + 1
	updateDataValue("noSubSpks", "${spkNo}")
	state."sub_${spkNo}" = subSpkData
	logDebug("createTempSubData: Sub_${spkNo} = ${state."sub_${spkNo}"}")
}

def generateGroupData() {
	def groupNo = state.groupNo
	def speakerData = generateSpeakerData()
	def groupName = getDataValue("groupName")
	def groupData = state."group_${groupNo}_Data"
	logDebug("generateGroupData: groupData = ${groupData}")
	groupData["groupName"] = groupName
	groupData["groupType"] = getDataValue("groupType")
	groupData["noSubSpks"] = getDataValue("noSubSpks")
	groupData["Main"] = speakerData
	def noSubSpks = getDataValue("noSubSpks").toInteger()
	def i = 1
	while (i < noSubSpks +1) {
		groupData["Sub_${i}"] = state."sub_${i}"
		state."sub_${i}" = ""
		i = i + 1
	}
	state.activeGroupNo = groupNo
	sendEvent(name: "Group_${groupNo}", value: "${groupName}")
	state.activeGroupNo = groupNo
	sendEvent(name: "activeGroup", value: "${groupName}")
}

//	===== Start and Stop the Group =====
def groupStart(groupNo) {
	def groupData = state."group_${groupNo}_Data"
	logDebug("groupStart: groupNo = ${groupNo}, groupData = ${groupData}")
	if (groupNo < 1 || groupNo > 3) {
		log.warn "groupStart: Group Number out of range (1-3)!"
		return
	} else if (groupData == [:]) {
		log.warn "groupStart: Group is not defined!"
		return
	} else if (state.activeGroupNo == "") {
		state.groupNo = groupNo
	} else {
		log.info "groupStart: A group is already active."
		return
	}
	def groupCmd = ""
	def subCmdStr = ""
	def groupType = groupData.groupType
	def groupName = groupData.groupName
	def spksInGroup = groupData.noSubSpks.toInteger() + 1
	def mainData = groupData.Main
	def mainSpkMAC = mainData.spkMAC
	def speakerName = mainData.spkName
	def mainSpkChVol = mainData.spkChVol
	def mainSpkLoc = mainData.spkLoc
	def mainSpkDefVol = mainData.spkDefVol.toInteger()
	updateDataValue("noSubSpks", "${groupData.noSubSpks}")
	if (groupType == "Group") {
		groupCmd = createGroupCommandMain(groupName, spksInGroup, mainSpkMAC, speakerName)
	} else {
		groupCmd = createSurrCommandMain(groupName, spksInGroup, mainSpkMAC, speakerName, mainSpkLoc, mainSpkChVol)
	}
	def i = 1
	while (i < spksInGroup) {
		def spkData = groupData."Sub_${i}"
		def subSpkDNI = spkData.spkDNI
		def subSpkIP = parent.getIP(subSpkDNI)
		def subSpkMAC = spkData.spkMAC
		def subSpkDefVol = spkData.spkDefVol.toInteger()
		def subSpkLoc = spkData.spkLoc
		def subSpkChVol = spkData.spkChVol
		if (groupType == "Group") {
			subCmdStr = createGroupCommandSub(subSpkIP, subSpkMAC)
		} else {
			subCmdStr = createSurrCommandSub(subSpkIP, subSpkMAC, subSpkLoc)
		}
		i = i + 1
		groupCmd = groupCmd + subCmdStr
		parent.sendCmdToSpeaker(subSpkDNI, "SetFunc", "wifi", "", "")
		parent.sendCmdToSpeaker(subSpkDNI, "updateData", "spkType", "Sub", "")
		parent.sendCmdToSpeaker(subSpkDNI, "setLevel", subSpkDefVol,"","")
		parent.sendCmdToSpeaker(subSpkDNI, "SetChVolMultich", subSpkChVol.toInteger(), "", "")
	}
	sendCmd(groupCmd, "generalResponse")
	updateDataValue("groupName", groupName)
	updateDataValue("spkType", "Main")
	state.activeGroupNo = groupNo
	sendEvent(name: "activeGroup", value: "${groupName}")
	pauseExecution(1000)
	setLevel(mainSpkDefVol)
	pauseExecution(200)
	SetChVolMultich(mainSpkChVol)
}

def groupStop() {
	def groupNo = state.activeGroupNo
	def groupData = state."group_${groupNo}_Data"
	logDebug("groupStop: groupNo = ${groupNo}, groupData = ${groupData}")
	if (!groupData) {
		log.warn "groupStop: No Group is active."
		setErrorMsg("groupStop: No Group is active.")
		state.activeGroupNo = ""
		sendEvent(name: "activeGroup", value: "No Group Active")
		return
	}
 	SetUngroup()
	def spksInGroup = groupData.noSubSpks.toInteger() + 1
	def i = 1
	while (i < spksInGroup) {
		def spkData = groupData."Sub_${i}"
		i = i + 1
		def subSpkDNI = spkData.spkDNI
		parent.sendCmdToSpeaker(subSpkDNI, "updateData", "spkType", "Solo", "")
	}
	updateDataValue("spkType", "Solo")
	updateDataValue("groupName", "")
	sendEvent(name: "activeGroup", value: "No Group Active")
	state.activeGroupNo = ""
}

//	===== Group-Related Volume Buttons
def groupVolume(groupVolume, curVol) {
	logDebug("groupVolume: groupVolume = ${groupVolume}, curVol = ${curVol}")
	if (getDataValue("spkType") != "Main") { return }
	def groupData = state."group_${state.groupNo}_Data"
	def spksInGroup = groupData.noSubSpks.toInteger() + 1
	def volChange = (groupVolume - curVol)/curVol
	def i = 1
	while (i < spksInGroup) {
		def spkData = groupData."Sub_${i}"
		def subSpkDNI = spkData.spkDNI
		parent.sendCmdToSpeaker(subSpkDNI, "setSubSpkVolume", volChange, "", "")
		i = i + 1
	}
}

def setSubSpkVolume(volChange) {
	def curVol = device.currentValue("level").toInteger()
	logDebug("setSubSpkVolume: volChange = ${volChange}, curVol = ${curVol}")
	def newLevel = curVol*(1 + volChange)
	setLevel(newLevel)
}
//	===== AudioNotification / TTS =====
def speak(text) {
	if (getDataValue("spkType") == "Sub") {
		logDebug ("speak: sending command to ${state.mainSpkDNI}.")
		parent.sendCmdToMain(state.mainSpkDNI, "speak", text, "", "", "")
	} else { playTextAndResume(text) }
}

def playText(text, volume=null) {
log.debug "playText: Text = ${text}, Volume = ${volume}"
	if (getDataValue("spkType") == "Sub") {
		logDebug ("playText: sending command to ${state.mainSpkDNI}.")
		parent.sendCmdToMain(state.mainSpkDNI, "speak", text, volume, "", "")
	} else { playTextAndResume(text, volume) }
}

def playTextAndRestore(text, volume=null) {
log.debug "playText: Text = ${text}, Volume = ${volume}"
	if (getDataValue("spkType") == "Sub") {
		logDebug ("playTextAndRetore: sending command to ${state.mainSpkDNI}.")
		parent.sendCmdToMain(state.mainSpkDNI, "speak", text, volume, "", "")
	} else { playTextAndResume(text, volume) }
}

def playTextAndResume(text, volume=null) {
	logDebug("playTextAndResume: Text = ${text}, Volume = ${volume}")
log.debug "playTextAndResume: Text = ${text}, Volume = ${volume}"
	if (getDataValue("spkType") == "Sub") {
		logDebug ("playTextAndResume: sending command to ${state.mainSpkDNI}.")
		parent.sendCmdToMain(state.mainSpkDNI, "playTextAndResume", text, volume, "", "")
		return
	}
	def swType = getDataValue("swType")
	def track
	if (swType == "SoundPlus") {
		track = textToVoice(text)
	} else {
		track = textToSpeech(text)
	}
	playTrackAndResume(track, volume)
}

def textToVoice(text) {
	//	Command in-lieu of textToSpeech (since TTS does not work for MS Soundbars)
	def uriText = URLEncoder.encode(text, "UTF-8").replaceAll(/\+/, "%20")
	def trackUri = "http://api.voicerss.org/?" +
		"key=${ttsApiKey.trim()}" +
		"&f=48khz_16bit_stereo" +
		"&hl=${ttsLang}" +
		"&src=${uriText}"
	def duration = textToSpeech(text).duration
	def track = [uri: trackUri, duration: duration]
	return track
}

def setTrack(trackUri) {
	log.info "setTrack: Not impleted."
}

def playTrack(trackUri, volume=null) {
	log.info "playTrack: Not impleted."
}

def playTrackAndRestore(track, volume=null) {
	playTrackAndResume(track, volume)
}

def playTrackAndResume(track, volume=null) {
	logDebug("playTrackAndResume: trackUri = ${track}, Volume = ${volume}")
	if (getDataValue("spkType") == "Sub") {
		parent.sendCmdToMain(state.mainSpkDNI, "playTrackAndResume", track, volume, "", "")
		return
	}
	def oldLevel = device.currentValue("level").toInteger()
	def newLevel
	if (volume == null) {
		newLevel = oldLevel
	} else {
		newLevel = volume.toInteger()
	}
	setLevel(newLevel)
	def subMode = device.currentValue("subMode")
	def delayTime = track.duration.toInteger() + 2 //	allows for audio connect mssg on speakers.
	def inputSource = device.currentValue("inputSource")
	//	Assures that radio info is collected so we can recover
	if (subMode == "cp") { GetRadioInfo() } 
	else if (subMode == "dlna") { GetMusicInfo() } 
	else if (inputSource != "wifi") { delayTime = delayTime + 4 }
	runIn(delayTime, resumePlayer, [data: [level: oldLevel, inputsource: inputSource, submode: subMode]])
	pause()
	def swType = getDataValue("swType")
	if (swType == "SoundPlus") {
log.error "SENDING TO UPNPPLAYBACK"
		setUpnpPlayback(track.uri)
	} else {
		SetUrlPlayback(track.uri, "1")
	}
}

def setUpnpPlayback(trackUri) {
	logDebug("setUpnpPlayback: trackUri = ${trackUri}")
	def result = []
 	result << sendUpnpCmd("SetAVTransportURI", [InstanceID: 0, CurrentURI: trackUri, CurrentURIMetaData: ""])
	result << pauseExecution(3000)
	result << sendUpnpCmd("Play")
	result
}

def restorePlayer(data) {
	logDebug("restorePlayer: data = ${data}")
	setLevel(data.level)
	SetFunc(data.inputsource)
}

def resumePlayer(data) {
	logDebug("resumePlayer: data = ${data}")
	def swType = getDataValue("swType")
	def shuffle = device.currentValue("shuffle")
	def repeat = device.currentValue("repeat")
	setLevel(data.level)
	if (data.inputsource != "wifi") { SetFunc(data.inputsource) } 
	else if (data.submode == "dlna") {
		if (swType == "SoundPlus") { SetTrickMode("next") } 
		else { log.info "Resume Play stops after the first song on Speakers in DLNA mode" }
	} else if (data.submode == "cp") {
		def currentlyPlaying = state.currentlyPlaying
		def title = currentlyPlaying[1]
		def player = currentlyPlaying[2]
		def playerNo = currentlyPlaying[3]
		def path = currentlyPlaying[4]
		log.info "resumePlayer: Resume play with Player = ${player}, Path = ${path}, Title = ${title}, PlayerNo = ${playerNo}"
		if (swType == "SoundPlus") {
			switch(player) {
				case "Pandora":
				case "AmazonPrime":
					SetCpService(playerNo)
					break
				default:
					PlayById(player, path)
					break
			}
		} else {
			play()
			switch(player) {
				case "iHeartRadio":
				case "Pandora":
				case "TuneIn":
				case "AmazonPrime":
					break
			   default:
					PlayById(player, path)
					break
			}
		}
	}
	play()
	runIn(5, setTrackDescription)
}

//	=====	Utility Functions =====
def refresh() {
	logDebug("refresh, unreachableCount = ${state.unreachableCount}")
	if (state.unreachableCount > 3) { return }
	state.unreachableCount += 1
	GetMute("pollingResp")
}

def finishRefresh() {
	logDebug("finishRefresh")
	def cmds = [
		getPwr(),
		pauseExecution(500),
		GetFunc(),
		pauseExecution(500),
		GetVolume(),
		pauseExecution(500),
		GetAcmMode(),
		pauseExecution(2000),
 		setTrackDescription()
		]
	cmds
}

def convertMac(dni) {
	dni = dni.toString()
	def mac = "${dni.substring(0,2)}${dni.substring(3,5)}${dni.substring(6,8)}${dni.substring(9,11)}${dni.substring(12,14)}${dni.substring(15,17)}"
	mac = mac.toUpperCase()
	return mac
}

def logDebug(msg) {
	if (debugMode == true) {
		log.debug "${device.label}, ${driverVersion()}: ${msg}"
	}
}

//	===== SEND Commands to Devices =====
private sendCmd(command, action){
	def host = "${getDataValue("deviceIP")}:55001"
	logDebug("sendCmd: Command= ${command}, Action = ${action}, host = ${host}")
	sendHubCommand(new hubitat.device.HubAction("""GET ${command} HTTP/1.1\r\nHOST: ${host}\r\n\r\n""",
												hubitat.device.Protocol.LAN, host, [callback: action]))
}

private sendUpnpCmd(String action, Map body=[InstanceID:0, Speed:1]){
	logDebug("sendUpnpCmd: upnpAction = ${action}, upnpBody = ${body}")
	def deviceIP = getDataValue("deviceIP")
	def host = "${deviceIP}:9197"
	def path = "/upnp/control/AVTransport1"
	def hubCmd = new hubitat.device.HubSoapAction(
		path:	path,
		urn:	 "urn:schemas-upnp-org:service:AVTransport:1",
		action:  action,
		body:	body,
		headers: [Host: host, CONNECTION: "close"]
	)
	hubCmd
}

//	===== Response Parse =====
def generalResponse(resp) {
	if(resp.status != 200) {
		log.warn "generalResponse: Command generated an error return: ${resp.status}"
		return
	} else if (resp.body == null){
		log.warn "generalResponse: no data in command response."
		return
	}
	def respMethod = (new XmlSlurper().parseText(resp.body)).method
	def respData = (new XmlSlurper().parseText(resp.body)).response
	logDebug("generalResponse_${respMethod}")
	state.unreachableCount = 0

	switch(respMethod) {
//	----- SOUNDBAR STATUS METHODS -----
		case "PowerStatus":
			def pwrStat = respData.powerStatus
			if (pwrStat == "0") {
				sendEvent(name: "switch", value: "off")
			} else {
				sendEvent(name: "switch", value: "on")
			}
			break
		case "CurrentFunc":
log.error respData.submode
			sendEvent(name: "inputSource", value: respData.function)
			sendEvent(name: "subMode", value: respData.submode)
			break
		case "VolumeLevel":
			def scale = getDataValue("volScale").toInteger()
			def level = respData.volume.toInteger()
			def spkType = getDataValue("spkType")
			level = Math.round(100*level/scale).toInteger()
			sendEvent(name: "level", value: level)
			break
		case "MuteStatus":
			if (respData.mute == "on") {
				sendEvent(name: "mute", value: "muted")
			} else {
				sendEvent(name: "mute", value: "unmuted")
			}
				break
		case "7BandEQList":
			cmdEqPreset(respData.listcount.toString())
			break
		case "EQMode":
		case "EQDrc":
			GetCurrentEQMode()
			break
		case "7bandEQMode":
		case "CurrentEQMode":
			sendEvent(name: "eqPreset", value: "eqPreset: ${respData.presetname}")
			state.currentEqPreset = respData.presetindex.toInteger()
			break
		case "RearLevel":
				state.rearLevel = "${respData.level}"
			break
//	----- MEDIA CONTROL STATUS METHODS -----
		case "PlayStatus":
		case "PlaybackStatus":
			def playerStatus
			def prevStatus = device.currentValue("status")
				switch(respData.playstatus) {
				case "play":
					playerStatus = "playing"
					break
				case "pause":
					playerStatus = "paused"
					break
				case "stop":
					playerStatus = "stopped"
					break
				default:
					 break
			}
			sendEvent(name: "status", value: playerStatus)
			if (playerStatus == "playing") {
				runIn(5, getPlayTime)
			} else {
				state.updateTrackDescription = "no"
			}
			break
		case "RepeatMode":
			def subMode = device.currentValue("subMode")
			if (submode == "dlna") {
				if (respData.repeat == "one") {
					sendEvent(name: "repeat", value: "1")
				} else {
					sendEvent(name: "repeat", value: "0")
				}
			} else if (submode == "cp") {
				sendEvent(name: "repeat", value: "Repeat ${respData.repeatmode}")
			}
			break
		case "ShuffleMode":
			if (respData.shuffle == "on") {
				sendEvent(name: "shuffle", value: "1")
			} else {
				sendEvent(name: "shuffle", value: "0")
			}
			break
		case "ToggleShuffle":
			sendEvent(name: "shuffle", value: "Shuffle: ${respData.shufflemode}")
			break
//	----- MUSIC INFORMATION METHODS
		case "MusicInfo":
			def trackDescription = ""
			def timeLength = respData.timelength.toString()
			def subMode = device.currentValue("subMode")
			state.currentlyPlaying = ["${subMode}", "${respData.artist}", 
									  "${respData.device_udn}", "${respData.objectid}", 
									  "${respData.parentid}", "${respData.playindex}"]
			state.updateTrackDescription = "yes"
			if (respData == "No Music" || respData.errCode == "fail to play") {
				trackDescription = "WiFi DLNA not playing"
				return
			} else {
				trackDescription ="${respData.title}\n\r${respData.artist}"
			}
			sendEvent(name: "trackDescription", value: trackDescription)
			break
		case "RadioInfo":
			def title = ""
			def player = respData.cpname
			def cpChannels = state.cpChannels
			def playerNo  =cpChannels."${player}"
			def path = ""
			def trackDescription = ""
			if (respData.tracklength == "" || respData.tracklength == "0") {
				state.trackLength = 0
				state.updateTrackDescription = "no"
			} else {
				state.trackLength = respData.tracklength.toInteger()
				state.updateTrackDescription = "yes"
			}
			switch(player) {
				case "Amazon":
				case "AmazonPrime":
					trackDescription = "${respData.artist}: ${respData.title}"
					GetCurrentRadioList()
					break
				case "iHeartRadio":
					path = "l${respData.mediaid.toString().take(4)}"
	 			   	title = "${respData.title}"
					trackDescription = "${respData.title}"
					break
				case "Pandora":
					path = "${respData.mediaid}"
					title = "${respData.station}"
					trackDescription = "${respData.artist}: ${respData.title}"
					if (state.tracklength == 0) {
						trackDescription = "Pandora Commercial"
 					   state.trackLength = 30
 					}
					break
				case "8tracks":
					path = "${respData.mediaid}"
					title = "${respData.mixname}"
					trackDescription = "${respData.artist}: ${respData.title}"
					break
				default:
				 	path = "${respData.mediaid}"
					title = "${respData.title}"
					trackDescription = "${respData.title}"
					break
			}
			state.currentlyPlaying = ["cp", "${title}", "${player}", "${playerNo}", "${path}"]
			sendEvent(name: "trackDescription", value: trackDescription)

			if (respData.shufflemode == "") {
				sendEvent(name: "shuffle", value: "Shuffle inactive")
			} else {
				sendEvent(name: "shuffle", value: "Shuffle ${respData.shufflemode}")
			}
			if (respData.repeatmode == "") {
				sendEvent(name: "repeat", value: "Repeat inactive")
			} else  {
				sendEvent(name: "repeat", value: "Repeat ${respData.repeatmode}")
			}
			break
		case "MusicPlayTime":
			def subMode = device.currentValue("subMode")
			if (subMode == "dlna"){
				state.trackLength = respData.timelength.toInteger()
			}
			if (respData.playtime != "" && respData.playtime != null){
				schedSetTrackDescription(respData.playtime.toInteger())
			} else {
				log.warn "generalResponse_${respMethod}: Null playtime ignored. schedUpdateTrackDescription not called."
			}
			break
 		case "RadioList":
			def currentlyPlaying = state.currentlyPlaying
			state.currentlyPlaying = ["cp", "${respData.category}", "${currentlyPlaying[2]}", 
									  "${currentlyPlaying[3]}", "${respData.root}"]
			if(state.startPreset == "yes") {
				SetPlaySelect("0")
				runIn(4, play)
				state.startPreset = "No"
			}
			break
		case "SongInfo":
			break
//	----- PLAY PRESET METHODS
		case "CpChanged":
			def currentlyPlaying = state.currentlyPlaying
			def player = currentlyPlaying[2]
			def path = currentlyPlaying[4]
			state.startPreset = "yes"
			if (player == "AmazonPrime") {
				if (path == "Playlists") {
					SetSelectCpSubmenu(1, "searchRadioList")
				} else if (path == "Prime Stations") {
					SetSelectCpSubmenu(2, "searchRadioList")
				} else if (path == "My Music") {
 					SetSelectCpSubmenu(6, "searchRadioList")
				}
				GetCurrentRadioList("searchRadioList")
			} else if (player == "Pandora") {
				BrowseMain("searchRadioList")
			} else {
				PlayById(player, path)
				runIn(4, play)
				state.startPreset = "no"
			}
			break
		case "RadioSelected":
			def currentlyPlaying = state.currentlyPlaying
			def player = currentlyPlaying[2]
			def path = currentlyPlaying[4]
			PlayById(player, path)
			runIn(4, play)
			break
//	----- GROUP METHODS
		case "GroupName":
			groupName = respData.groupname
			updateDataValue("groupName", "${groupName}")
			break
		case "AcmMode":
			def acmMode = respData.acmmode
			def sourceMac = respData.audiosourcemacaddr
			def deviceMac = getDataValue("deviceMac")
			if (sourceMac == "00:00:00:00:00:00") {
				updateDataValue("spkType", "Solo")
				state.mainSpkDNI = ""
			} else if (sourceMac == deviceMac) {
 				updateDataValue("spkType", "Main")
				state.mainSpkDNI = device.deviceNetworkId
				if (acmMode == "aasync") {
					updateDataValue("groupType", "Group")
				} else {
					updateDataValue("groupType", "Surround")
				}
			} else {
				updateDataValue("spkType", "Sub")
				state.mainSpkDNI = convertMac(sourceMac)
			}
			break
		case "ChVolMultich":
			sendEvent(name: "multiChVol", value: "${respData.channelvolume}")
			break
		case "SkipInfo":
			log.warn "SkipInfo: Function Failed. ${respData.errmessage}"
			log.warn "SkipInfo: Error Code is ${respData.errcode}"
			break
		case "ErrorEvent":
			log.warn "Speaker Error: ${respMethod} : ${respData}"
			sendEvent(name: "ERROR", value: "${respMethod} : ${respData}")
			break
		case "SubMenu":
		case "MainInfo":
		case "RequestDeviceInfo":
		case "SelectCpService":
		case "Ungroup":
		case "RadioPlayList":
		case "MultispkGroup":
		case "SoftwareVersion":
		case "MultispkGroupStartEvent":
		case "MultiQueueList":
			break
		case "MultispkGroupStartEvent":
		case "StartPlaybackEvent":
		case "MediaBufferStartEvent":
		case "StopPlaybackEvent":
		case "EndPlaybackEvent":
		case "MediaBufferEndEvent":
 		case "PausePlaybackEvent":
 			runIn(12, getPlayStatus)
			runIn(10, setTrackDescription)
			break
		default:
			log.warn "generalResponse_${respMethod}: Method not handed.  Data: ${respData}"
			break
	}
}

def searchRadioList(resp) {
	def respMethod = (new XmlSlurper().parseText(resp.body)).method
	def respData = (new XmlSlurper().parseText(resp.body)).response
	logDebug("searchRadioList_${respMethod}:  Determining station start parameters")
	if (respMethod == "SubMenu") {
		return
	} else if (respData.root == "My Music" && respData.category.@isroot == "1") {
		GetSelectRadioList("0", "searchRadioList")
		return
	}
	def contentId = ""
	def currentlyPlaying = state.currentlyPlaying
	def title = currentlyPlaying[1]
	def player = currentlyPlaying[2]
	def playerNo = currentlyPlaying[3]
	def path = currentlyPlaying[4]
	def menuItems = respData.menulist.menuitem
 	menuItems.each {
		if (contentId == "") {
			if (it.title == title) {
				contentId = it.contentid
			}
		}
	}
	if (contentId == "") {
		log.warn "${device.label} searchRadioList: Invalid Preset Title: ${title}"
		log.warn "searchRadioList Added info: ${respData}"
		return
	}
	switch(player) {
		case "AmazonPrime":
			if (path == "Playlists" || path == "My Music") {
 				GetSelectRadioList(contentId)
			} else {
				log.warn "${device.label} searchRadioList: Invalid Amazon Prime selection"
			}
			break
		case "Pandora":
			SetPlaySelect(contentId)
			runIn(4, play)
			break
		default:
			log.warn "${device.label} searchRadioList: Invalid information"
	}
}

def pollingResp(resp) {
	if(resp.status != 200) {
		log.warn "generalResponse: Command generated an error return: ${resp.status}"
		return
	} else if (resp.body == null){
		log.warn "generalResponse: no data in command response."
		return
	}
	def respMethod = (new XmlSlurper().parseText(resp.body)).method
	def respData = (new XmlSlurper().parseText(resp.body)).response
	logDebug("pollingResponse_${respMethod}")
	if (respData.mute == "on") {
		sendEvent(name: "mute", value: "muted")
	} else {
		sendEvent(name: "mute", value: "unmuted")
	}
	state.unreachableCount = 0
	finishRefresh()
}

def parse(resp) {
	try {
		resp = parseLanMessage(resp)
	} catch (Exception e) {
		log.warn "${device.label} parse:  parseLanMesage failed.  ${resp.status}:::::${resp.body}"
		return
	}
	def respMethod = (new XmlSlurper().parseText(resp.body)).method
	if (respMethod != null) {
		switch(respMethod) {
			case "MainInfo":
			case "RadioInfo":
				generalResponse(resp)
				logDebug("parse_${respMethod}:  FORWARD TO GENERAL RESPONSE")
				break
			default:
				logDebug("parse_${respMethod}:  IGNORED")
				break
		}
	} else {
		logDebug("UPNP Response: ${resp}")
	}
}

//	===== Samsung Port 55001 Control API =====
def createGroupCommandMain(groupName, spksInGrp, mainSpkMAC, mainSpkName) {
	groupName = groupName.replaceAll(' ','%20')
	mainSpkName = mainSpkName.replaceAll(' ','%20')
	def spkCmd = "/UIC?cmd=%3Cpwron%3Eon%3C/pwron%3E%3Cname%3ESetMultispkGroup%3C/name%3E" +
		"%3Cp%20type=%20%22cdata%22%20name=%20%22name%22%20val=%20%22empty%22%3E%3C![CDATA[${groupName}]]%3E%3C/p%3E" +
		"%3Cp%20type=%20%22dec%22%20name=%20%22index%22%20val=%20%221%22/%3E" +
		"%3Cp%20type=%20%22str%22%20name=%20%22type%22%20val=%20%22main%22/%3E" +
		"%3Cp%20type=%20%22dec%22%20name=%20%22spknum%22%20val=%20%22${spksInGrp}%22/%3E" +
		"%3Cp%20type=%20%22str%22%20name=%20%22audiosourcemacaddr%22%20val=%20%22${mainSpkMAC}%22/%3E" +
		"%3Cp%20type=%20%22cdata%22%20name=%20%22audiosourcename%22%20val=%20%22empty%22%3E%3C![CDATA[${mainSpkName}]]%3E" +
		"%3C/p%3E%3Cp%20type=%20%22str%22%20name=%20%22audiosourcetype%22%20val=%20%22speaker%22/%3E"
	return spkCmd
}

def createGroupCommandSub(subSpkIP, subSpkMAC) {
	def subSpkData = "%3Cp%20type=%20%22str%22%20name=%20%22subspkip%22%20val=%20%22${subSpkIP}%22/%3E" +
		"%3Cp%20type=%20%22str%22%20name=%20%22subspkmacaddr%22%20val=%20%22${subSpkMAC}%22/%3E"
	return subSpkData
}

def createSurrCommandMain(groupName, spksInGroup, mainSpkMAC, mainSpkName, mainSpkLoc, mainSpkChVol) {
	groupName = groupName.replaceAll(' ','%20')
	mainSpkName = mainSpkName.replaceAll(' ','%20')
	def spkCmd = "/UIC?cmd=%3Cpwron%3Eon%3C/pwron%3E%3Cname%3ESetMultichGroup%3C/name%3E" +
		"%3Cp%20type=%22cdata%22%20name=%22name%22%20val=%22empty%22%3E%3C![CDATA[${groupName}]]%3E%3C/p%3E" +
		"%3Cp%20type=%22dec%22%20name=%22index%22%20val=%221%22/%3E" +
		"%3Cp%20type=%22str%22%20name=%22type%22%20val=%22main%22/%3E" +
		"%3Cp%20type=%22dec%22%20name=%22spknum%22%20val=%22${spksInGroup}%22/%3E" +
		"%3Cp%20type=%22str%22%20name=%22audiosourcemacaddr%22%20val=%22${mainSpkMAC}%22/%3E" +
		"%3Cp%20type=%22cdata%22%20name=%22audiosourcename%22%20val=%22empty%22%3E%3C![CDATA[${mainSpkName}]]%3E%3C/p%3E" +
		"%3Cp%20type=%22str%22%20name=%22audiosourcetype%22%20val=%22speaker%22/%3E" +
		"%3Cp%20type=%22str%22%20name=%22channeltype%22%20val=%22${mainSpkLoc}%22/%3E" +
		"%3Cp%20type=%22dec%22%20name=%22channelvolume%22%20val=%22${mainSpkChVol}%22/%3E"
	return spkCmd
}

def createSurrCommandSub(subSpkIP, subSpkMAC, subSpkLoc) {
	def subSpkData = "%3Cp%20type=%20%22str%22%20name=%20%22subspkip%22%20val=%20%22${subSpkIP}%22/%3E" +
		"%3Cp%20type=%20%22str%22%20name=%20%22subspkmacaddr%22%20val=%20%22${subSpkMAC}%22/%3E" +
		"%3Cp%20type=%22str%22%20name=%22subchanneltype%22%20val=%22${subSpkLoc}%22/%3E"
	return subSpkData
}

def Get7BandEQList(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGet7BandEQList%3C/name%3E",
			action)
}

def GetAcmMode(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGetAcmMode%3C/name%3E",
			action)
}

def BrowseMain(action = "generalResponse"){
	sendCmd("/CPM?cmd=%3Cname%3EBrowseMain%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22startindex%22%20val=%220%22/%3E" +
			"%3Cp%20type=%22dec%22%20name=%22listcount%22%20val=%2230%22/%3E",
			action)
}

def GetChVolMultich(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGetChVolMultich%3C/name%3E",
			action)
}

def GetCpSubmenu(action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3EGetCpSubmenu%3C/name%3E",
			action)
}

def GetCpPlayerPlaylist(action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3EGetCpPlayerPlaylist%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22startindex%22%20val=%220%22/%3E" +
			"%3Cp%20type=%22dec%22%20name=%22listcount%22%20val=%2255%22/%3E",
			action)
}

def GetCurrentEQMode(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGetCurrentEQMode%3C/name%3E",
			action)
}

def GetCurrentPlayTime(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGetCurrentPlayTime%3C/name%3E",
			action)
}

def GetCurrentRadioList(action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3EGetCurrentRadioList%3C/name%3E" +
			"%3Cp%20type%3D%22dec%22%20name%3D%22startindex%22%20val%3D%220%22/%3E" +
			"%3Cp%20type%3D%22dec%22%20name%3D%22listcount%22%20val%3D%2255%22/%3E",
			action)
}

def GetFunc(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGetFunc%3C/name%3E",
			action)
}

def GetGroupName(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGetGroupName%3C/name%3E",
			action)
}

def GetMainInfo(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGetMainInfo%3C/name%3E",
			action)
}

def GetMusicInfo(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGetMusicInfo%3C/name%3E",
			action)
}

def GetMute(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGetMute%3C/name%3E",
			action)
}

def cpm_GetPlayStatus(action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3EGetPlayStatus%3C/name%3E",
			action)
}

def GetPowerStatus(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGetPowerStatus%3C/name%3E",
			action)
}

def GetRadioInfo(action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3EGetRadioInfo%3C/name%3E",
			action)
}

def GetRearLevel(action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3EGetRearLevel%3C/name%3E",
			action)
}

def GetSelectRadioList(contentId, action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3EGetSelectRadioList%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22contentid%22%20val=%22${contentId}%22/%3E" +
			"%3Cp%20type=%22dec%22%20name=%22startindex%22%20val=%220%22/%3E" +
			"%3Cp%20type=%22dec%22%20name=%22listcount%22%20val=%2290%22/%3E",
			action)
}

def uic_GetPlayStatus(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGetPlayStatus%3C/name%3E",
			action)
}

def GetVolume(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3EGetVolume%3C/name%3E",
			action)
}

def cpm_SetPlaybackControl(playbackControl, action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3ESetPlaybackControl%3C/name%3E" +
			"%3Cp%20type=%22str%22%20name=%22playbackcontrol%22%20val=%22${playbackControl}%22/%3E",
			action)
}

def cpm_SetRepeatMode(mode, action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3ESetRepeatMode%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22mode%22%20val=%22${mode}%22/%3E",
			action)
}

def PlayById(player, mediaId, action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3EPlayById%3C/name%3E" +
		"%3Cp%20type=%22str%22%20name=%22cpname%22%20val=%22${player}%22/%3E" +
		"%3Cp%20type=%22str%22%20name=%22mediaid%22%20val=%22${mediaId}%22/%3E",
		action)
}

def Set7bandEQMode(newEqPreset, action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3ESet7bandEQMode%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22presetindex%22%20val=%22${newEqPreset}%22/%3E",
			action)
}

def SetChVolMultich(chVol, action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cpwron%3Eon%3C/pwron%3E%3Cname%3ESetChVolMultich%3C%2Fname%3E" +
			"%3Cp%20type%3D%22dec%22%20name%3D%22chvol%22%20val%3D%22${chVol}%22%2F%3E",
			action)
}

def SetCpService(cpId, action = "generalResponse"){
	sendCmd("/CPM?cmd=%3Cname%3ESetCpService%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22cpservice_id%22%20val=%22${cpId}%22/%3E",
			action)
}

def SetFunc(newSource, action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cpwron%3Eon%3C/pwron%3E%3Cname%3ESetFunc%3C/name%3E" +
			"%3Cp%20type=%22str%22%20name=%22function%22%20val=%22${newSource}%22/%3E",
			action)
}

def SetIpInfo(hubId, hubIpPort, action = "generalResponse") {
	sendCmd("UIC?cmd=%3Cname%3ESetIpInfo%3Cname%3E" + 
			"%3Cp%20type=%22str%22%20name=%22uuid%22%20val=%22${hubId}%22/%3E" +
			"%3Cp%20type=%22str%22%20name=%22ip%22%20val=%22${hubIpPort}%22/%3E",
			action)
}

def SetMute(mute, action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cpwron%3Eon%3C/pwron%3E%3Cname%3ESetMute%3C/name%3E" +
			"%3Cp%20type=%22str%22%20name=%22mute%22%20val=%22${mute}%22/%3E",
			action)
}

def SetPlaySelect(contentId, action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3ESetPlaySelect%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22selectitemid%22%20val=%22${contentId}%22/%3E",
			action)
}

def SetPowerStatus(powerStatus, action = "generalResponse") {
	//	Soundbars only
	sendCmd("/UIC?cmd=%3Cname%3ESetPowerStatus%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22powerstatus%22%20val=%22${powerStatus}%22/%3E",
			action)
}

def SetPreviousTrack(action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3ESetPreviousTrack%3C/name%3E",
			action)
}

def SetRearLevel(rearLevel, action = "generalResponse") {
	//===== Soundbars only =====
	sendCmd("/UIC?cmd=%3Cname%3ESetRearLevel%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22rearlevel%22%20val=%22${rearLevel}%22/%3E" +
			"%3Cp%20type=%22str%22%20name=%22activate%22%20val=%22on%22/%3E" +
			"%3Cp%20type=%22dec%22%20name=%22connection%22%20val=%22on%22/%3E",
			action)
}

def SetSelectAmazonCp(action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cpwron%3Eon%3C/pwron%3E%3Cname%3ESetSelectAmazonCp%3C/name%3E",
			action)
}

def SetSelectCpSubmenu(contentId, action = "generalResponse"){
	sendCmd("/CPM?cmd=%3Cname%3ESetSelectCpSubmenu%3C/name%3E" +
			"%3Cp%20type%3D%22dec%22%20name%3D%22contentid%22%20val%3D%22${contentId}%22/%3E" +
			"%3Cp%20type%3D%22dec%22%20name%3D%22startindex%22%20val%3D%220%22/%3E" +
			"%3Cp%20type%3D%22dec%22%20name%3D%22listcount%22%20val%3D%2230%22/%3E",
			action)
}

def SetSelectRadio(action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cpwron%3Eon%3C/pwron%3E%3Cname%3ESetSelectRadio%3C/name%3E",
			action)
}

def SetShuffleMode(shuffleMode, action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3ESetShuffleMode%3C/name%3E" +
			"%3Cp%20type=%22str%22%20name=%22shufflemode%22%20val=%22${shuffleMode}%22/%3E",
			action)
}

def SetSkipCurrentTrack(action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3ESetSkipCurrentTrack%3C/name%3E",
			action)
}

def SetToggleShuffle(mode, action = "generalResponse") {
	sendCmd("/CPM?cmd=%3Cname%3ESetToggleShuffle%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22mode%22%20val=%22${mode}%22/%3E",
			action)
}

def SetTrickMode(trickMode, action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cpwron%3Eon%3C/pwron%3E%3Cname%3ESetTrickMode%3C/name%3E" +
			"%3Cp%20type=%22str%22%20name=%22trickmode%22%20val=%22${trickMode}%22/%3E",
			action)
}

def SetUngroup(action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cpwron%3Eon%3C/pwron%3E%3Cname%3ESetUngroup%3C/name%3E",
			action)
}

def SetUrlPlayback(trackUrl, resume, action = "generalResponse") {
	//	Speakers and non-SoundPlus Soundbars
	sendCmd("/UIC?cmd=%3Cpwron%3Eon%3C/pwron%3E%3Cname%3ESetUrlPlayback%3C/name%3E" +
			"%3Cp%20type=%22cdata%22%20name=%22url%22%20val=%22empty%22%3E" +
			"%3C![CDATA[${trackUrl}]]%3E%3C/p%3E" +
			"%3Cp%20type=%22dec%22%20name=%22buffersize%22%20val=%220%22/%3E" +
			"%3Cp%20type=%22dec%22%20name=%22seektime%22%20val=%220%22/%3E" +
			"%3Cp%20type=%22dec%22%20name=%22resume%22%20val=%22${resume}%22/%3E",
			action)
}

def SetVolume(deviceLevel, action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cpwron%3Eon%3C/pwron%3E%3Cname%3ESetVolume%3C/name%3E" +
			"%3Cp%20type=%22dec%22%20name=%22volume%22%20val=%22${deviceLevel}%22/%3E",
			action)
}

def uic_SetPlaybackControl(playbackControl, action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cpwron%3Eon%3C/pwron%3E%3Cname%3ESetPlaybackControl%3C/name%3E" +
			"%3Cp%20type=%22str%22%20name=%22playbackcontrol%22%20val=%22${playbackControl}%22/%3E",
			action)
}

def uic_SetRepeatMode(repeatMode, action = "generalResponse") {
	sendCmd("/UIC?cmd=%3Cname%3ESetRepeatMode%3C/name%3E" +
			"%3Cp%20type=%22str%22%20name=%22repeatmode%22%20val=%22${repeatMode}%22/%3E",
			action)
}

//	End-of-File