/*
bleBox Device Integration Application, Version 0/1
		Copyright 2018, 2019 Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file 
except in compliance with the License. You may obtain a copy of the License at: 
		http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the 
License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
either express or implied. See the License for the specific language governing permissions 
and limitations under the License.

===== Version Description =====
09.20.19	Version 1.1 update.
			Application Execution Options
			a.	Install bleBox Devices.
				1.	Scans Lan segment for bleBox Devices and creates database entry for found devices.
				2.	Updates children device's deviceIP baseed on scanning.
				3.	Offers non-children devices for installation.
				4.	Installs user-selected devices.
			b.	Lists bleBox Devices.
				1.	Scans Lan segment for bleBox Devices and creates database entry for found devices.
				2.	Updates children device's deviceIP baseed on scanning.
				3.	Displays all found bleBox devices.
			c.	Selection of information and debug logging.
			Child-Device Called IP Poll (scan)
			a.	Checks if scan has been done in last 15 minutes.  Exits if true.
			b.	Scans Lan segment for bleBox Devices and creates database entry for found devices.
			c.	Updates children device's deviceIP baseed on scanning.
=============================================================================================*/
def appVersion() { return "1.1.01" }
import groovy.json.JsonSlurper
definition(
	name: "bleBox Integration",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Application to install bleBox devices.",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	singleInstance: true,
	importUrl: "https://raw.githubusercontent.com/DaveGut/bleBox-Hubitat/master/Application/blebox%20Manager%20App.groovy"
	)
preferences {
	page(name: "mainPage")
	page(name: "addDevicesPage")
	page(name: "listDevicesPage")
}

def installed() {
	if (!state.devices) { state.devices = [:] }
	initialize()
}

def updated() { initialize() }

def initialize() {
	logDebug("initialize")
	unschedule()
	app?.updateSetting("pollEnabled", [type:"bool", value: true])
	if (state.deviceIps) { state.remove("deviceIps") }
	if (selectedAddDevices) { addDevices() }
}


//	=====	Main Page	=====
def mainPage() {
	logDebug("mainPage")
	initialize()
	return dynamicPage(name:"mainPage",
		title:"<b>bleBox Device Manager</b>",
		uninstall: true,
		install: true) {
		section() {
			href "addDevicesPage",
				title: "<b>Install bleBox Devices</b>",
				description: "Gets device information. Then offers new devices for install.\n" +
							 "(It may take several minutes for the next page to load.)"
			href "listDevicesPage",
					title: "<b>List all available bleBox devices and update the IP address for installed devices.</b>",
					description: "Lists available devices.\n" +
								 "(It may take several minutes for the next page to load.)"
			input ("infoLog", "bool",
				   defaultValue: true,
				   required: false,
				   submitOnChange: true,
				   title: "Enable Application Info Logging")
			input ("debugLog", "bool",
				   defaultValue: false,
				   required: false,
				   submitOnChange: true,
				   title: "Enable Application Debug Logging")
			paragraph "<b>Recommendation:  Set Static IP Address in your WiFi router for all bleBox Devices."
		}
	}
}


//	=====	Add Devices	=====
def addDevicesPage() {
	state.devices = [:]
	findDevices(200, "parseDeviceData")
	def devices = state.devices
	def newDevices = [:]
	devices.each {
		def isChild = getChildDevice(it.value.dni)
		if (!isChild) {
			newDevices["${it.value.dni}"] = "${it.value.type} ${it.value.label}"
		}
	}
	logDebug("addDevicesPage: newDevices = ${newDevices}")
	return dynamicPage(name:"addDevicesPage",
		title:"<b>Add bleBox Devices to Hubitat</b>",
		install: true) {
	 	section() {
			input ("selectedAddDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Devices to add (${newDevices.size() ?: 0} available)",
				   description: "Use the dropdown to select devices to add.  Then select 'Done'.",
				   options: newDevices)
		}
	}
}

def parseDeviceData(response) {
	def cmdResponse = parseResponse(response)
	logDebug("parseDeviceData: <b>${cmdResponse}")
	if (cmdResponse == "error") { return }
	if (cmdResponse.device) { cmdResponse = cmdResponse.device }
	def label = cmdResponse.deviceName
	def dni = cmdResponse.id.toUpperCase()
	def type = cmdResponse.type
	def ip = convertHexToIP(response.ip)
	def typeData
	def devData = [:]
	devData["dni"] = dni
	devData["ip"] = ip
	devData["label"] = label
	devData["type"] = type
	state.devices << ["${dni}" : devData]
	def isChild = getChildDevice(dni)
	if (isChild) {
		isChild.updateDataValue("deviceIP", ip)
	}
	if (type == "switchBoxD") {
		sendGetCmd(ip, """/api/relay/state""", "parseRelayData")
	}
}


//	===== Device Specific Actions =====
def getDeviceData() {
	logDebug("getDeviceData")
	devices = state.devices
	devices.each {
		if (it.value.type == "switchBoxD") {
			sendGetCmd(it.value.ip, """/api/relay/state""", "parseRelayData")
		}
	}
}

def parseRelayData(response) {
	def cmdResponse = parseResponse(response)
	logDebug("parseRelayData: <b>${cmdResponse}")
	if (cmdResponse == "error") { return }
	def relays = cmdResponse.relays
	def devIp = convertHexToIP(response.ip)
	def device = state.devices.find { it.value.ip == devIp }
	def dni = device.value.dni
	device.value << [dni:"${dni}-0", label:"${relays[0].name}", relayNumber:"0"]
	def relay2Data = ["dni": "${dni}-1",
					  "ip": device.value.ip,
					  "type": device.value.type,
					  "label": relays[1].name,
					  "relayNumber": "1"]
	state.devices << ["${dni}-1" : relay2Data]
}


//	===== Add Devices =====
def addDevices() {
	logDebug("addDevices:  Devices = ${state.devices}")
	try { hub = location.hubs[0] }
	catch (error) { 
		logWarn("Hub not detected.  You must have a hub to install this app.")
		return
	}
	def hubId = hub.id
	selectedAddDevices.each { dni ->
		def isChild = getChildDevice(dni)
		if (!isChild) {
			def device = state.devices.find { it.value.dni == dni }
			def deviceData = [:]
			deviceData["applicationVersion"] = appVersion()
			deviceData["deviceIP"] = device.value.ip
			if (device.value.relayNumber) { deviceData["relayNumber"] = device.value.relayNumber }
			try {
				addChildDevice(
					"davegut",
					"bleBox ${device.value.type}",
					device.value.dni,
					hubId, [
						"label" : device.value.label,
						"name" : device.value.type,
						"data" : deviceData
					]
				)
			} catch (error) {
				logWarn("Failed to install ${device.value.label}.  Driver bleBox ${device.value.type} most likely not installed.")
			}
		}
	}
}


//	=====	Update Device IPs	=====
def listDevicesPage() {
	logDebug("listDevicesPage")
	state.devices = [:]
	findDevices(200, "parseDeviceData")
	pauseExecution(5000)
	def devices = state.devices
	def foundDevices = "<b>Found Devices (Installed / DNI / IP / Alias):</b>"
	def count = 1
	devices.each {
		def installed = false
		if (getChildDevice(it.value.dni)) { installed = true }
		foundDevices += "\n${count}:\t${installed}\t${it.value.dni}\t${it.value.ip}\t${it.value.label}"
		count += 1
	}
	return dynamicPage(name:"listDevicesPage",
		title:"<b>Available bleBox Devices on your LAN</b>",
		install: false) {
	 	section() {
			paragraph "The appliation has searched and found the below devices. If any are " +
				"missing, there may be a problem with the device.\n\n${foundDevices}\n\n" +
				"<b>RECOMMENDATION: Set Static IP Address in your WiFi router for bleBox Devices.</b>"
		}
	}
}


//	===== Recurring IP Check =====
def updateDeviceIps() {
	logDebug("updateDeviceIps: Updating Device IPs after hub reboot.")
	runIn(5, updateDevices)
}

def updateDevices() {
	if (pollEnabled == true) {
		app?.updateSetting("pollEnabled", [type:"bool", value: false])
		runIn(900, pollEnable)
	} else {
		logWarn("updateDevices: a poll was run within the last 15 minutes.  Exited.")
		return
	}
	def children = getChildDevices()
	logDebug("UpdateDevices: ${children} / ${pollEnabled}")
	app?.updateSetting("missingDevice", [type:"bool", value: false])
	children.each {
		if (it.isDisabled()) {
			logDebug("updateDevices: ${it} is disabled and not checked.")
			return
		}
		def ip = it.getDataValue("deviceIP")
		runIn(2, setMissing)
		sendGetCmd(ip, "/api/device/state", "checkValid")
		pauseExecution(3000)
	}
	runIn(2, pollIfMissing)
}

def pollIfMissing() {
	logDebug("pollIfMissing: ${missingDevice}.")
	if (missingDevice == true) {
		state.devices= [:]
		findDevices(200, parseDeviceData)
		app?.updateSetting("missingDevice", [type:"bool", value: false])
	}
}

def checkValid(response) {
	unschedule("setMissing")
	def resp = parseLanMessage(response.description)
	logDebug("checkValid: response received from ${convertHexToIP(resp.ip)}")
}

def setMissing() {
	logWarn("setMissing: Setting missingDevice to true")
	app?.updateSetting("missingDevice", [type:"bool", value: true])
}

def pollEnable() {
	logDebug("pollEnable")
	app?.updateSetting("pollEnabled", [type:"bool", value: true])
}


//	=====	bleBox Specific Communications	=====
def findDevices(pollInterval, action) {
	logDebug("findDevices: ${pollInterval} / ${action}")
	def hub
	try { hub = location.hubs[0] }
	catch (error) { 
		logWarn "Hub not detected.  You must have a hub to install this app."
		return
	}
	def hubIpArray = hub.localIP.split('\\.')
	def networkPrefix = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")
	logInfo("findDevices: IP Segment = ${networkPrefix}")
	for(int i = 2; i < 254; i++) {
		def deviceIP = "${networkPrefix}.${i.toString()}"
		sendGetCmd(deviceIP, "/api/device/state", action)
		pauseExecution(pollInterval)
	}
	pauseExecution(5000)
}
private sendGetCmd(ip, command, action){
	logDebug("sendGetCmd: ${ip} / ${command} / ${action}")
	sendHubCommand(new hubitat.device.HubAction("GET ${command} HTTP/1.1\r\nHost: ${ip}\r\n\r\n",
												hubitat.device.Protocol.LAN, null, [callback: action]))
}
def parseResponse(response) {
	def cmdResponse
	if(response.status != 200) {
		logWarn("parseInput: Error - ${convertHexToIP(response.ip)} // ${response.status}")
		cmdResponse = "error"
	} else if (response.body == null){
		logWarn("parseInput: ${convertHexToIP(response.ip)} // no data in command response.")
		cmdResponse = "error"
	} else {
		def jsonSlurper = new groovy.json.JsonSlurper()
        try {
        	cmdResponse = jsonSlurper.parseText(response.body)
        } catch (error) {
        	cmdResponse = "error"
        	logWarn("parseInput: error parsing body = ${response.body}")
        }
	}
	return cmdResponse
}


//	===== General Utility methods =====
def uninstalled() {
    getAllChildDevices().each { 
        deleteChildDevice(it.deviceNetworkId)
    }
}
private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}
private Integer convertHexToInt(hex) { Integer.parseInt(hex,16) }
def logDebug(msg){
	if(debugLog == true) { log.debug "<b>${appVersion()}</b> ${msg}" }
}
def logInfo(msg){
	if(infoLog == true) { log.info "<b>${appVersion()}</b> ${msg}" }
}
def logWarn(msg) { log.warn "<b>${appVersion()}</b> ${msg}" }

//	end-of-file