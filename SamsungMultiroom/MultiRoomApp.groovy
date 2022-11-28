/*	===== HUBITAT INTEGRATION VERSION =====================================================
Samsung WiFi Audio Hubitat Appliction
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
4.0 Changes
a.	Streamlined discovery to eliminate two run requirement to find devices.
b.	Updated addDevices for deprecated command format.

===== HUBITAT INTEGRATION VERSION =======================================================*/

import org.json.JSONObject
def appVersion() { return "4.0" }
def appName() { return "Samsung Speakers Integration" }

definition(
	name: "${appName()}",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "This is a Service Manager for Samsung WiFi speakers and soundbars.",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	singleInstance: true,
	importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungMultiroom/MultiRoomApp.groovy"
)

preferences {
	page(name: "mainPage")
	page(name: "speakerDiscovery")
}

//	===== Page Definitions =====
def mainPage() {
	setInitialStates()
	ssdpSubscribe()
	def intro = "This Service Manager installs and manages Samsung WiFi Speakers. The app provides: \n\t" +
				"1.\tUpdate device IPs every three hours.\n\t" +
				"2.\tCoordination between grouped speakers.\n" +
				"PROBLEMS OR ISSUES WITH THIS CODE SHOULD BE COORDINATED ON " +
				"https://community.hubitat.com/ AT THE APPROPRIATE THREAD.\n\n\n"
	def page1 = "Press 'Next' to install Speakers.  Select '<< App List' to return.  There are no " +
	 			"other options available."
	return dynamicPage(
		name: "mainPage",
		title: "Samsung Wifi Speaker Integration", 
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
	ssdpDiscover()
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
	unschedule()
	if (selectedSpeakers) {
		addSpeakers()
	}
	runEvery3Hours(ssdpDiscover)
}

def uninstalled() {
	def children = getAllChildDevices()
	logInfo("uninstalled: children = ${children}")
	children.each {
		deleteChildDevice(it.deviceNetworkId)
	}
}

//	===== Device Discovery =====
void ssdpSubscribe() {
	logInfo("ssdpSubscribe: location = ${location}")
	unsubscribe()
	subscribe(location, "ssdpTerm.urn:dial-multiscreen-org:device:dialreceiver:1", ssdpHandler)
	pauseExecution(2000)
	subscribe(location, "ssdpTerm.urn:schemas-upnp-org:device:MediaRenderer:1", ssdpHandler)
}

void ssdpDiscover() {
	logInfo("ssdpDiscover")
	sendHubCommand(new hubitat.device.HubAction("lan discovery urn:dial-multiscreen-org:device:dialreceiver:1", hubitat.device.Protocol.LAN))
	pauseExecution(2000)
	sendHubCommand(new hubitat.device.HubAction("lan discovery urn:schemas-upnp-org:device:MediaRenderer:1", hubitat.device.Protocol.LAN))
}

def ssdpHandler(evt) {
	def parsedEvent = parseLanMessage(evt.description)
	logInfo("ssdpHandler:  parsedEvent = ${parsedEvent}")
	def ip = convertHexToIP(parsedEvent.networkAddress)
	def dni = parsedEvent.mac
	def uuid = parsedEvent.ssdpUSN.replaceAll(/uuid:/, "").take(36)
	def speakers = state.speakers
	if (speakers."${uuid}") {
		def speaker = speakers."${uuid}"
		def child = getChildDevice(dni)
		if (child) {
			if (speaker.ip != ip) {
				speaker.ip = ip
					logInfo("ssdpHandler: updating child data")
					child.updateDataValue("deviceIP", "${ip}")
					child.updateDataValue("appVersion", "${appVersion()}")
			}
		}
	} else {
		def speaker = [:]
		speaker["dni"] = dni
		speaker["mac"] = convertDniToMac(dni)
		speaker["ip"] = ip
		speaker["ssdpPort"] = convertHexToInt(parsedEvent.deviceAddress)
		speaker["ssdpPath"] = parsedEvent.ssdpPath
		speakers << ["${uuid}": speaker]
		logInfo("ssdpHandler:  speaker = ${speaker}")
		verifySpeaker(speaker)
	}
}

def verifySpeaker(speaker) {
	logInfo("verifySpeaker: ${speaker}")
	sendCmd(speaker.ssdpPath, speaker.ip, speaker.ssdpPort, "verifySpeakerHandler")
}

void verifySpeakerHandler(hubResponse) {
	def respBody = new XmlSlurper().parseText(hubResponse.body)
	logInfo("verifySpeakerHandler: respBody = ${respBody}")
	def uuid = respBody?.device?.UDN?.text()
	uuid = uuid.replaceAll(/uuid:/, "")
	def speakers = state.speakers
	def speaker = speakers.find {it?.key?.contains("${uuid}")}
	if (speaker) {
		def resp = sendSyncCmd("/UIC?cmd=%3Cname%3EGetSpkName%3C/name%3E", speaker.value.ip)
		def model = respBody?.device?.modelName?.text()
		def hwType = "Speaker"
		if (model[0..1] == "HW") {
			hwType = "Soundbar"
		}
		speaker.value << [model: "${model}",
						  hwType: "${hwType}",
						  name: "${resp.spkname}",
						  verified: true]
	 }
}

def addSpeakers() {
	logInfo("addSpeakers: selectedSpeakers: ${selectedSpeakers}")
	selectedSpeakers.each { dni ->
		def selectedSpeaker = state.speakers.find { it.value.dni == dni }

		def child
		if (selectedSpeaker) {
			child = getChildDevices()?.find { it.deviceNetworkId == selectedSpeaker.value.dni }
		}
		if (!child) {
			def inputSources = getInputSources(selectedSpeaker.value.model)

			addChildDevice("davegut", "Samsung Wifi Speaker", selectedSpeaker.value?.dni, [
				"label": "${selectedSpeaker.value.name}",
				"name": "${selectedSpeaker.value.model}",
				"data": [
					"appVersion": appVersion(),
					"deviceIP": selectedSpeaker.value.ip,
					"deviceMac": selectedSpeaker.value.mac,
					"inputSources": inputSources,
					"hwType": selectedSpeaker.value.hwType
				]
			])

			selectedSpeaker.value << [installed: true]
			log.info "Installed Speaker ${selectedSpeaker.value.model} ${selectedSpeaker.value.name}"
		}
	}
}

def getInputSources(model) {
	logInfo("soundbarInputSources: model = ${model}")
	def sources
	switch(model) {
		case "HW-MS650":
		case "HW-MS6500":
		sources = "{1: wifi, 2: bt, 3: aux, 4: optical, 5: hdmi}"
 			break
		case "HW-MS750":
		case "HW-MS7500":
		sources = "{1: wifi, 2: bt, 3: aux, 4: optical, 5: hdmi1, 6: hdmi2}"
			break
		case "HW-J8500":
		case "HW-J7500":
		case "HW-J6500":
		case "HW-J650":
		case "HW-H750":
		case "HW-K650":
			sources = "{1: bt, 2: soundshare, 3: aux, 4: optical, 5: usb, 6: hdmi}"
			break
		default:
			sources = "{1: wifi,2: bt,3: soundshare}"
			break
	}
	return sources
}

//	===== Send commands to the Device =====
private sendCmd(command, deviceIP, devicePort, action){
	logInfo("sendCmd: IP = ${deviceIP} / Port = ${devicePort} / Command = ${command} / action = ${action}")
    def host = "${deviceIP}:${devicePort}"
    def sendCmd =sendHubCommand(new hubitat.device.HubAction("""GET ${command} HTTP/1.1\r\nHOST: ${host}\r\n\r\n""",
															 hubitat.device.Protocol.LAN, host, [callback: action]))
}

private sendSyncCmd(command, ip){
	def host = "http://${ip}:55001"
	logInfo("sendSyncCmd: Command= ${command}, host = ${host}")
	try {
		httpGet([uri: "${host}${command}", contentType: "text/xml", timeout: 5]) { resp ->
			return resp.data.response
		}
	} catch (error) {
		logWarn("sendSyncCmd: The speaker is not responding.  Error = ${error}")
		return "commsError"
	}
}

//	===== Support to child device handler =====
def requestSubSpeakerData(groupData, mainSpkDni) {
	logInfo("requestSubSpeakerData: groupData = ${groupData}, mainSpkDni = ${mainSpkDni}")
	def subSpkCount = 0
	selectedSpeakers.each { dni ->
		def selectedSpeaker = state.speakers.find { it.value.dni == dni }
		if (selectedSpeaker.value.dni != mainSpkDNI) {
			def child = getChildDevice(selectedSpeaker.value.dni)
			def subSpeakerData = child.getSubSpeakerData()
			if (subSpeakerData != "not Sub" && subSpeakerData != "error") {
				subSpkCount += 1
				groupData["Sub_${subSpkCount}"] = subSpeakerData
			}
		}
	}
	groupData << [noSubSpks: subSpkCount]
	return groupData
}

def getIP(spkDNI) {
	logInfo("getIP: spkDNI = ${spkDNI}")
	def selectedSpeaker = state.speakers.find { it.value.dni == spkDNI }
	def spkIP = selectedSpeaker.value.ip
	return spkIP
}

def sendCmdToSpeaker(spkDNI, command, param1, param2 = null, param3 = null) {
	def child = getChildDevice(spkDNI)
	child.execAppCommand(command, param1, param2, param3)
}

def logInfo(message) {
	log.info "${appName()} ${appVersion()}: ${message}"
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