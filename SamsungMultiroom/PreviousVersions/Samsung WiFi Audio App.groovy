/*
===== HUBITAT INTEGRATION VERSION =============================
Samsung WiFi Audio Hubitat Appliction
Copyright 2018 Dave Gutheinz
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
This application controls an supports the Samsung WiFi Audio
Hubitat Driver.  Testing was completed on the HW-MS650 Soundbar
and the R1 Speaker using commands derived frominternet data.
===== DISCLAIMERS==============================================
THE AUTHOR OF THIS INTEGRATION IS NOT ASSOCIATED WITH SAMSUNG.
THIS CODE USES TECHNICAL DATA ON THE SPEAKERS DERIVED FROM
GITHUB SOURCES AS WELL AS PERSONAL INVESTIGATION.
PROBLEMS OR ISSUES WITH THIS CODE SHOULD BE COORDINATED AT
https://community.hubitat.com/ AT THE APPROPRIATE THREAD.
===== History =================================================
2019
01.15	2.0.01
		a.	Moved child - device comms to child driver.
		b.	Added debugLogging (manually set in app code).
		c.	Validated method usage.
01.17	2.0.02.  Updated comms to child.  Added delete all
		child devices when application is uninstalled.
04.07	2.1.01.	Update to separate soundbar and speaker drivers.
04.10	2.1.02.	Fixed issue on installation.  Updated instructions
		to account for discovery work-around.
===== HUBITAT INTEGRATION VERSION ===========================*/
	state.debugLogging = true
//	state.debugLogging = false
	def appVersion() { return "2.1.02" }
definition(
	name: "Samsung WiFi Audio App",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "This is a Service Manager for Samsung WiFi speakers and soundbars.",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	iconX3Url: "")
	singleInstance: true
preferences {
	page(name: "mainPage")
	page(name: "speakerDiscovery")
}

//	===== Page Definitions =====
def mainPage() {
	setInitialStates()
	def intro = "This Service Manager installs and manages Samsung WiFi Speakers. Additionally," +
				"services are provided to the speakers during the grouping process.\n\n" +
				"THE AUTHOR OF THIS INTEGRATION IS NOT ASSOCIATED WITH SAMSUNG.  " +
				"THIS CODE USES TECHNICAL DATA ON THE SPEAKERS DERIVED FROM " +
				"GITHUB SOURCES AS WELL AS PERSONAL INVESTIGATION.\n\n" +
				"PROBLEMS OR ISSUES WITH THIS CODE SHOULD BE COORDINATED ON " +
				"https://community.hubitat.com/ AT THE APPROPRIATE THREAD.\n\n\n" +
				"SPECIAL INITIAL INSTALL INSTRUCTIONS:\na. Press 'Next' to install.\n" +
				"b. Immediately select 'Done' on the next page (installs app).\n" +
				"c. Run app again (now installed) and continue."
	def page1 = "Press 'Next' to install Speakers.  Select '<' to return.  There are no" +
	 			"other options available."
	return dynamicPage(
		name: "mainPage",
		title: "Samsung (Connect) Setup", 
		nextPage: "speakerDiscovery",
		install: false, 
		uninstall: true){
		section(intro) {}
		section(page1) {}
	}
}

def speakerDiscovery() {
	def options = [:]
	def verSpeakers = state.speakers.findAll{ it.value.verified == true }
	verSpeakers.each {
		def value = "${it.value.model} : ${it.value.name}"
		def key = it.value.dni
		options["${key}"] = value
	}
	ssdpSubscribe()
	runIn(2, ssdpDiscover)
	runIn(6, addSpeakerModel)
	runIn(10, addSwVersion)
	def text2 = "Please wait while we discover your Samsung Speakers. Discovery can take "+
				"several minutes\n\r\n\r" +
				"If no speakers are discovered after several minutes, press DONE.  This " +
				"will install the app.  Then re-run the application."
	return dynamicPage(
		name: "speakerDiscovery", 
		title: "Speaker Discovery",
		nextPage: "", 
		refreshInterval: 15, 
		install: true, 
		uninstall: true){
		section(text2) {
			input "selectedSpeakers", "enum", 
			required: false, 
			title: "Select Speakers (${options.size() ?: 0} found)", 
			multiple: true, 
			options: options
		}
	}
}

//	===== Start up Functions =====
def setInitialStates() {
    if (!state.speakers) {
		state.speakers = [:]
	}
}

def installed() {
	initialize()
}

def updated() {
	initialize()
}

def initialize() {
	unsubscribe()
	unschedule()
	ssdpSubscribe()
	if (selectedSpeakers) {
		addSpeakers()
	}
	runEvery1Hour(ssdpDiscover)
}

def uninstalled() {
	def children = getAllChildDevices()
	logDebug("uninstalled: children = ${children}")
	children.each {
		deleteChildDevice(it.deviceNetworkId)
	}
}

//	===== Device Discovery =====
void ssdpSubscribe() {
	logDebug("ssdpSubscribe")
	subscribe(location, "ssdpTerm.urn:dial-multiscreen-org:device:dialreceiver:1", ssdpHandler)
	subscribe(location, "ssdpTerm.urn:schemas-upnp-org:device:MediaRenderer:1", ssdpHandler)
}

void ssdpDiscover() {
	logDebug("ssdpDiscover")
	sendHubCommand(new hubitat.device.HubAction("lan discovery urn:dial-multiscreen-org:device:dialreceiver:1", hubitat.device.Protocol.LAN))
	sendHubCommand(new hubitat.device.HubAction("lan discovery urn:schemas-upnp-org:device:MediaRenderer:1", hubitat.device.Protocol.LAN))
}

def ssdpHandler(evt) {
	def parsedEvent = parseLanMessage(evt.description)
	logDebug("ssdpHandler:  parsedEvent = ${parsedEvent}")
	def ip = convertHexToIP(parsedEvent.networkAddress)
	def port = convertHexToInt(parsedEvent.deviceAddress)
	def mac = convertDniToMac(parsedEvent.mac)
	def ssdpUSN = parsedEvent.ssdpUSN
	def uuid = ssdpUSN.replaceAll(/uuid:/, "").take(36)
	def speakers = state.speakers
	if (speakers."${uuid}") {
		def d = speakers."${uuid}"
		def child = getChildDevice(parsedEvent.mac)
		if (d.ip != ip) {
			d.ip = ip
			if (child) {
				logDebug("ssdpHandler: updating child, IP = ${ip}")
				child.updateDataValue("deviceIP", ip)
				child.updateDataValue("appVersion", appVersion())
			}
		}
	} else {
		def speaker = [:]
		speaker["dni"] = parsedEvent.mac
		speaker["mac"] = mac
		speaker["ip"] = ip
		speaker["ssdpPort"] = port
		speaker["ssdpPath"] = parsedEvent.ssdpPath
		speakers << ["${uuid}": speaker]
		logDebug("ssdpHandler:  speaker = ${speaker}")
	}
}

//	===== Add Devices to Smart Things =====
void addSpeakerModel() {
	def speakers = state.speakers.findAll { !it?.value?.model }
	speakers.each {
	 	sendCmd(it.value.ssdpPath, it.value.ip, it.value.ssdpPort, "addSpeakerModelHandler")
	}
}

void addSpeakerModelHandler(hubResponse) {
	def respBody = new XmlSlurper().parseText(hubResponse.body)
	logDebug("addSpeakerModelHandle: respBody = ${respBody}")
	def model = respBody?.device?.modelName?.text()
    def hwType = "Speaker"
	if (model[0..1] == "HW") {
		hwType = "Soundbar"
	}
	def uuid = respBody?.device?.UDN?.text()
	uuid = uuid.replaceAll(/uuid:/, "")
	def speakers = state.speakers
	def speaker = speakers.find {it?.key?.contains("${uuid}")}
	if (speaker) {
 		speaker.value << [model: model, hwType: hwType]
	 }
}

void addSwVersion() {
	def speakers = state.speakers.findAll { !it?.value?.swType }
	 speakers.each {
		if (it.value.model) {
			  GetSoftwareVersion(it.value.ip, "addSwVersionHandler")
		  }
	 }
}

def addSwVersionHandler(resp) {
	def respBody = new XmlSlurper().parseText(resp.body)
	logDebug("addSwVesionHandle: respBody = ${respBody}")
	def swVersion = respBody.response.version.text()
	def swType = "Standard"
	if (swVersion[-6..-5] == "11") {
		swType = "SoundPlus"
	}
	def volScale = 30
	if (swType == "SoundPlus") {
		volScale = 60
	}
	def ip = respBody.speakerip
	def speakers = state.speakers
	def speaker = speakers.find { "$it.value.ip" == "$ip" }
	if (speaker) {
		speaker.value << [swType: swType, volScale: volScale]
		GetSpkName(ip, "verifySpeakersHandler")
	}
}

def verifySpeakersHandler(resp) {
	def respBody = new XmlSlurper().parseText(resp.body)
	logDebug("verifySpeakersHandle: respBody = ${respBody}")
	def ip = respBody.speakerip
	def speakers = state.speakers
	def speaker = speakers.find { "$it.value.ip" == "$ip" }
	if (speaker) {
		if (!speaker.value.name) {
			speaker.value << [name: respBody.response.spkname.toString(), verified: true]
		}
	}
}

def addSpeakers() {
	logDebug("addSpeakers: selectedSpeakers: ${selectedSpeakers}")
	def hub = location.hubs[0]
	def hubId = hub.id
	selectedSpeakers.each { dni ->
		def selectedSpeaker = state.speakers.find { it.value.dni == dni }

		def d
		if (selectedSpeaker) {
			d = getChildDevices()?.find { it.deviceNetworkId == selectedSpeaker.value.dni }
		}
		if (!d) {
			def swType = selectedSpeaker.value.swType
			def driver
			if (swType == "SoundPlus") { driver = "Samsung Soundbar" }
			else { driver = "Samsung Speaker" }
			addChildDevice("davegut", driver, selectedSpeaker.value?.dni, hubId, [
				"label": "${selectedSpeaker.value.name}",
				"name": "${selectedSpeaker.value.model}",
				"data": [
					"appVersion": appVersion,
					"deviceIP": selectedSpeaker.value.ip,
					"deviceMac": selectedSpeaker.value.mac,
					"model": selectedSpeaker.value.model,
				]
			])
			selectedSpeaker.value << [installed: true]
			log.info "Installed Speaker ${selectedSpeaker.value.model} ${selectedSpeaker.value.name}"
		}
	}
    
}

//	===== Send commands to the Device =====
private sendCmd(command, deviceIP, devicePort, action){
	logDebug("sendCmd: IP = ${deviceIP} / Port = ${devicePort} / Command = ${command} / action = ${action}")
    def host = "${deviceIP}:${devicePort}"
    def sendCmd =sendHubCommand(new hubitat.device.HubAction("""GET ${command} HTTP/1.1\r\nHOST: ${host}\r\n\r\n""",
															 hubitat.device.Protocol.LAN, host, [callback: action]))
}

//	===== Support to child device handler =====
def requestSubSpeakerData(mainSpkDNI) {
	logDebug("requestSubSpeakerData: mainSpkData = ${mainSpkDNI}")
    state.spkCount = 1
    selectedSpeakers.each { dni ->
		def selectedSpeaker = state.speakers.find { it.value.dni == dni }
		if (selectedSpeaker.value.dni != mainSpkDNI) {
			def child = getChildDevice(selectedSpeaker.value.dni)
			child.getSubSpeakerData(mainSpkDNI)
			state.spkCount = state.spkCount + 1
		}
	}
}

def sendSubspeakerDataToMain(mainSpkDNI, subSpkData) {
	logDebug("sendSubspeakerDataToMain: mainSpkData = ${mainSpkDNI} / speakerData = ${subSpkData}")
    def spkNo = state.spkCount.toInteger()
	def child = getChildDevice(mainSpkDNI)
	child.createTempSubData(subSpkData)
}

def getIP(spkDNI) {
	logDebug("getIP: spkDNI = ${spkDNI}")
	def selectedSpeaker = state.speakers.find { it.value.dni == spkDNI }
	def spkIP = selectedSpeaker.value.ip
	return spkIP
}

def sendCmdToMain(spkDNI, command, param1, param2, param3, param4) {
	def child = getChildDevice(spkDNI)
	switch(command) {
		case "speak":
			child.speak(param1)
			break
		case "playText":
			child.playText(param1, param2)
			break
		case "playTextAndRestore":
			child.playTextAndRestore(param1, param2)
			break
		case "playTextAndResume":
			child.playTextAndResume(param1, param2)
			break
		case "playTrackAndResume":
			child.playTrackAndResume(param1, param2)
			break
		case "playTrackAndRestore":
			child.playTrackAndRestore(param1, param2)
			break
		case "playTrack":
			child.playTrack(param1, param2)
			break
        case "setTrack":
        	child.setTrack(param1)
        	break
        case "masterVolume":
        	child.setLevel(param1)
        	break
 		default:
			break
	}
}

def sendCmdToSpeaker(spkDNI, command, param1, param2, parseAction) {
	def child = getChildDevice(spkDNI)
	switch(command) {
		case "SetChVolMultich":
			child.SetChVolMultich(param1)
			break
		case "setLevel":
		  	child.setLevel(param1)
			break
		case "setSubSpkVolume":
		  	child.setSubSpkVolume(param1)
			break
		case "SetFunc":
		  	child.SetFunc(param1)
			break
		case "updateData":
			child.updateData(param1, param2)
			break
		case "off":
			child.off()
			break
		default:
			break
	}
}

def getDataFromSpeaker(spkDNI, command) {
	def child = getChildDevice(spkDNI)
	 switch(command) {
 		case "getSpkVolume":
			  def spkVol = child.getSpkVol()
			  return spkVol
	 	case "getSpkEqLevel":
		  	def spkEqVol = child.getSpkEqLevel()
				return spkEqVol
		  default:
		  	break
	 }
}

def logDebug(message) {
	if (state.debugLogging == true) { log.debug "${appVersion()} ${message}" }
}

//	===== Samsung WiFi Speaker API =====
def GetMainInfo(deviceIP, action) {
	sendCmd("/UIC?cmd=%3Cname%3EGetMainInfo%3C/name%3E",
	 		deviceIP, "55001", action)
}

def GetSpkName(deviceIP, action) {
	sendCmd("/UIC?cmd=%3Cname%3EGetSpkName%3C/name%3E", 
	 		 deviceIP, "55001", action)
}

def GetSoftwareVersion(deviceIP, action) {
	sendCmd("/UIC?cmd=%3Cname%3EGetSoftwareVersion%3C/name%3E", 
	 		 deviceIP, "55001", action)
}

def nextMsg(deviceIP, action) {
	//	bogus message to cause second response to be parsed.
	sendCmd("/UIC?cmd=%3Cname%3ENEXTMESSAGE%3C/name%3E",
	 		deviceIP, "55001", action)
}

//	----- Utility Functions  SHOULD DISAPPEAR-----
private convertDniToMac(dni) {
	def mac = "${dni.substring(0,2)}:${dni.substring(2,4)}:${dni.substring(4,6)}:${dni.substring(6,8)}:${dni.substring(8,10)}:${dni.substring(10,12)}"
	mac = mac.toLowerCase()
	return mac
}

private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}

private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}