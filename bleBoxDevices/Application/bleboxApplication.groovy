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
V2.1.0
Updated comms from hubAction to asyncHttpPost to accommodate changes in hub environment.
=============================================================================================*/
def appVersion() { return "2.1.0" }
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
	importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/bleBoxDevices/Application/bleboxApplication.groovy"
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
	if (selectedAddDevices) { addDevices() }
	state.devices - [:]
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
	findDevices(25, "parseDeviceData")
	runIn(10, updateChildren)
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

def parseDeviceData(response, data) {
	def cmdResponse = parseResponse(response)
	if (cmdResponse == "error") { return }
	logDebug("parseDeviceData: ${cmdResponse}")
	if (cmdResponse.device) { cmdResponse = cmdResponse.device }
	def dni = cmdResponse.id.toUpperCase()
	def ip = cmdResponse.ip
	def apiLevel = 20000000
	if (cmdResponse.apiLevel) { apiLevel = cmdResponse.apiLevel.toInteger() }
	def type = cmdResponse.type
	if (type == "multiSensor" && cmdResponse.product == "windRainSensor") {
		type = "windRainSensor"
	}
	def devices = state.devices
	if (type != "switchBoxD") {
		def devData = [:]
		devData["ip"] = ip
		devData["apiLevel"] = apiLevel
		devData["type"] = type
		devData["dni"] = dni
		devData["label"] = cmdResponse.deviceName
		state.devices << ["${dni}" : devData]
	} else {
		//	relay0
		def relayDni = dni + "-0"
		def devData = [:]
		devData["ip"] = ip
		devData["apiLevel"] = apiLevel
		devData["type"] = type
		devData["dni"] = relayDni
		devData["label"] = cmdResponse.deviceName + "-0"
		devData["relayNumber"] = "0"
		state.devices << ["${relayDni}" : devData]
		//	relay1
		relayDni = dni + "-1"
		devData = [:]
		devData["ip"] = ip
		devData["apiLevel"] = apiLevel
		devData["type"] = cmdResponse.type
		devData["dni"] = relayDni
		devData["label"] = cmdResponse.deviceName+ "-1"
		devData["relayNumber"] = "1"
		state.devices << ["${relayDni}" : devData]
	}
}

def updateChildren() {
	def devices = state.devices
	devices.each{
		def isChild = getChildDevice(it.value.dni)
		if (isChild) {
			isChild.updateDataValue("deviceIP", it.value.ip)
			isChild.updateDataValue("apiLevel", it.value.apiLevel.toString())
			logInfo("updateChildren: updated [${it.value.label}, ${it.value.ip}, ${it.value.apiLevel}]")
			isChild.updated()
		}
	}
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
			deviceData["apiLevel"] = device.value.apiLevel
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
	def findDev = findDevices(25, "parseDeviceData")
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
//	for(int i = 2; i < 254; i++) {
//		def deviceIP = "${networkPrefix}.${i.toString()}"
//		sendGetCmd("/info", action, deviceIP)
//		pauseExecution(pollInterval)
//	}

	for(int i = 2; i < 254; i++) {
		def deviceIP = "${networkPrefix}.${i.toString()}"
		sendGetCmd("/api/device/state", action, deviceIP)
		pauseExecution(pollInterval)
	}
	pauseExecution(5000)
	return
}

private sendGetCmd(command, action, ip){
	logDebug("sendGetCmd: ${command} / ${action} / ${ip}")
	def respData = [:]
	def sendCmdParams = [
		uri: "http://${ip}:80${command}",
		timeout: 3]
	try {
		asynchttpGet(action, sendCmdParams, [reason: "none"])
	} catch (error) {
			logWarn("asyncGet: [status: FAILED, errorMsg: ${error}]")
	}
}

def parseResponse(response) {
	def cmdResponse
	if(response.status != 200) {
		cmdResponse = "error"
	} else if (response.data == null){
		cmdResponse = "error"
	} else {
		def jsonSlurper = new groovy.json.JsonSlurper()
        try {
        	cmdResponse = jsonSlurper.parseText(response.data)
        } catch (error) {
        	cmdResponse = "error"
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

def logTrace(msg){ log.trace "${msg}" }

def logWarn(msg) { log.warn "<b>${appVersion()}</b> ${msg}" }

//	end-of-file
