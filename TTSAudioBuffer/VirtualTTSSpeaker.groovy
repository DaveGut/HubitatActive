/*
Virtural TTS Speaker Device Driver, Version 1
	Copyright 2019 Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file except in compliance with the
License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.
Description:  This driver is for a virtual device created by the app "TTS Queueing".  The virtual device provides the
framework to capture the external audio notification and then send it to the application for buffering and passing to the
speaker.
===== History =====
03.04.19	0.5.00	Initial release.  Moved buffer to virtual device handler to simplify application.  Single device
					only.
03.05.19	0.6.01	Updated to support multi-device app.  Also limited commands to setLevel and speak.
06.15.19	1.0.01	First production release
07.17.19	1.0.02	Minor error correction causing failure.
11.19.22    1.0.03  Restored support for AudioVolume capability
*/

import groovy.transform.Field

@Field static final String SET_VOLUME = "@@setVolume"
@Field static final String MUTE = "@@mute"
@Field static final String UNMUTE = "@@unmute"
@Field static final String VOLUME_UP = "@@volumeUp"
@Field static final String VOLUME_DOWN = "@@volumeDown"
@Field static final String WAKEUP = "@@wakeup"
@Field static final String INITIALIZE = "@@initialize"
@Field static final String STOP = "@@stop"

def driverVer() {return "1.0.01" }
metadata {
	definition (name: "Virtual TTS Speaker", 
				namespace: "davegut", 
				author: "David Gutheinz",
			   	importUrl: "https://github.com/DaveGut/Hubitat-TTS-Audio-Buffer/blob/master/VirtualTTSSpeaker.groovy")
	{
		capability "Speech Synthesis"
        capability "AudioVolume"
		command "clearQueue"
        command "wakeUpChromecastDevice"
        command "stop"
	}
}

preferences {
    input name: "delayBetweenMessages", type: "number", title: "Delay between messages", defaultValue: 3
	input name: "debugMode", type: "bool", title: "Display debug messages?", defaultValue: false
}

//	===== Install / Uninstall =====
def installed() {
	logInfo("Installing ......")
	runIn(2, updated)
}

def updated() {
	logInfo("Updating ......")
	unschedule()
	state.playingTTS = false
	state.TTSQueue = []
	if (debugMode == true) { runIn(1800, stopDebugLogging) }
	else { stopDebugLogging() }
}

void uninstalled() {
	try {
		def alias = device.label
		log.info "Removing device ${alias} with DNI = ${device.deviceNetworkId}"
		parent.removeChildDevice(alias, device.deviceNetworkId)
	} catch (ex) {
		log.info "${device.name} ${device.label}: Either the device was manually installed or there was an error."
	}
}

//	===== Queuing Messages and send to App =====
def speak(text) {
	log.info "TEXT = ${text}"
	def duration = textToSpeech(text).duration + delayBetweenMessages
	addToQueue(text, duration)
}

def setVolume(volume) {
    addToQueue(SET_VOLUME, volume)
}

def mute() {
    addToQueue(MUTE)
}

def unmute() {
    addToQueue(UNMUTE)
}

def volumeUp() {
    addToQueue(VOLUME_UP)
}

def volumeDown() {
    addToQueue(VOLUME_DOWN)
}

def wakeUpChromecastDevice() {
    addToQueue(WAKEUP, null, false)
    addToQueue(INITIALIZE)
}

def stop() {
    addToQueue(STOP)
}

void addToQueue(command, duration=null, process=true) {
    def TTSQueue = state.TTSQueue
	TTSQueue << [command, duration]
	if (process && state.playingTTS == false) { runInMillis(100, processQueue) }
}

def processQueue() {
	logDebug("processQueue: TTSQueue = ${state.TTSQueue}")
	state.playingTTS = true
	def TTSQueue = state.TTSQueue
	if (TTSQueue.size() == 0) {
		state.playingTTS = false
		return
	}
	def nextTTS = TTSQueue[0]
	TTSQueue.remove(0)
	process(nextTTS)
}

def clearQueue() {
	state.TTSQueue = []
	state.playingTTS = false
	logDebug("clearQueue:  TTSQueue = ${state.TTSQueue}")
}

void process(nextTTS) {
    def realSpeaker = getDataValue("realSpeaker")
    switch (nextTTS[0]) {
        case SET_VOLUME:
            parent.setVolume(nextTTS[1], realSpeaker)
            break
        
        case MUTE:
            parent.mute(realSpeaker)
            break
        
        case UNMUTE:
            parent.unmute(realSpeaker)
            break
        
        case VOLUME_UP:
            parent.volumeUp(realSpeaker)
            break
        
        case VOLUME_DOWN:
            parent.volumeDown(realSpeaker)
            break
        
        case STOP:
            parent.stop(realSpeaker)
            break
        
        case INITIALIZE:
            parent.initialize(realSpeaker)
            runInMillis(100, processQueue)
            return
        
        case WAKEUP:
            parent.wakeUpChromecastDevice(realSpeaker)
            runIn(1, processQueue)
            return
        
        default:
            parent.playTTS(nextTTS[0], realSpeaker)
            runIn(nextTTS[1], processQueue)
            return
    }
    
    processQueue()
}

//	===== Logging =====
def stopDebugLogging() {
	logInfo("stopDebugLogging: Debug Logging is off.")
	device.updateSetting("debugMode", [type:"bool", value: false])
}

def logDebug(msg) {
	if (debugMode == true) {
		log.debug "${device.label} ${driverVer()}: ${msg}"
	}
}

def logInfo(msg) {
	log.info "${device.label} ${driverVer()}: ${msg}"
}

//	End-of-File
