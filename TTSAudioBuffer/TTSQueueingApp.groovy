/*
TTS Queueing Application, Version 1

	Copyright 2019 Dave Gutheinz

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file except in compliance with the
License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.

Description:  This application installs a virtual speaker that can be used as the TTS target in speech synthesis rules.
It has been tested against the Samsaung Multiroom Audio devices by Dave Gutheinz.  It should work with all speakers
having the Speech Synthesis capability (and audio notification).  However, because the recovery from a audio notification
is specific to the speaker driver, it is not guaranteed to work with other devices.

===== History =====
03.04.19	0.5.01	Initial release of beta version of TTS Queing Application
03.05.19	0.6.01	Updated to support multiple devices.  Removed all "Audio Notification" commands limiting the app
					and driver to the speak(text) command.
06.15.19	1.0.01	First production release
*/
def appVersion() { return "1.0.01" }
def appName() { return "TTS Queuing" }
//	def debugLog() { return false }
	def debugLog() { return true }
definition(
	name: "${appName()}",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Queue text-to-speech messages for sequential playback.",
	importUrl: "https://github.com/DaveGut/Hubitat-TTS-Audio-Buffer/blob/master/TTSQueueingApp.groovy",
	iconUrl: "",
	iconX2Url: ""
)

preferences {
	page(name: "mainPage", title: "Queue message for speaker playback", install: true, uninstall: true)
}

def mainPage() {
	dynamicPage(name: "mainPage") {
		section {
			paragraph "You may only select a single real device in this application"
		}
		section {
			input "speaker", "capability.speechSynthesis", title: "On this real Speech Synthesis capable Speaker player", multiple: true, required: false
        }
	}
}

def setLevel(level) { speaker.setLevel(level) }

def playTTS(playItem, realSpeaker) {
	logDebug("playTTS: playint: ${playItem}, volume = ${volume}, method = ${method}, realSpeaker = ${realSpeaker}")
	def thisSpeaker = speaker.find{ it.toString() == realSpeaker }
	thisSpeaker.speak(playItem)
}

def addDevices() {
	logDebug("addDevices: speaker = ${speaker}")
	try { 
		hub = location.hubs[0] 
	} catch (error) { 
		log.error "Hub not detected.  You must have a hub to install this app."
		return
	}
	def hubId = hub.id
	speaker.each { thisSpeaker ->
		def virtualDni = "${thisSpeaker.getDeviceNetworkId()}_TTS"
		def label = "${thisSpeaker.label} TTS Queue"
		def child = getChildDevice(virtualDni)
		if (!child) {
			logDebug("addDevices: Adding ${virtualDni} / ${hubId} / speaker = ${thisSpeaker.label}")
			addChildDevice(
				"davegut",
				"Virtual TTS Speaker",
				virtualDni,
				hubId, [
					"label" : label,
					"name" : "Virtual TTS Speaker",
					"data": ["realSpeaker": thisSpeaker.label]
				]
			)
				log.info "Installed Virtual Speaker named ${label}"
		} else {
			logDebug("addDevices: Updating ${label}")
			child.updateDataValue("realSpeaker", thisSpeaker.label)
		}
	}
}

def setInitialStates() { }

def installed() {
	initialize()
}

def updated() { initialize() }

def initialize() {
	logDebug("initialize: speaker = ${speaker}")
	unsubscribe()
	unschedule()
	if (speaker) { addDevices() }
}

def uninstalled() {
    	getAllChildDevices().each { 
        deleteChildDevice(it.deviceNetworkId)
    }
}

def removeChildDevice(alias, deviceNetworkId) {
	try {
		deleteChildDevice(it.deviceNetworkId)
		sendEvent(name: "DeviceDelete", value: "${alias} deleted")
	} catch (Exception e) {
		sendEvent(name: "DeviceDelete", value: "Failed to delete ${alias}")
	}
}

//	===== Logging Data
def logDebug(msg){
	if(debugLog() == true) { log.debug msg }
}

//	end-of-file