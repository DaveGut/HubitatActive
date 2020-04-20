/*
TP-Link Integration Application, Version 4.6
	Copyright Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file except in compliance with the
License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.

DISCLAIMER:  This Applicaion and the associated Device Drivers are in no way sanctioned or supported by TP-Link.  
All  development is based upon open-source data on the TP-Link devices; primarily various users on GitHub.com.

===== 2020 History =====
01.03	4.6.01	Update from 4.5 to incorporate enhanced communications error processing.
01.16	4.6.02	Added updating driver application version.

===== GitHub Repository =====
	https://github.com/DaveGut/Hubitat-TP-Link-Integration
=============================================================================================*/
def appVersion() { return "4.6.02" }
import groovy.json.JsonSlurper
definition(
	name: "TP-Link Integration",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Application to install TP-Link bulbs, plugs, and switches.  Does not require a Kasa Account nor a Node Applet",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	singleInstance: true,
	importUrl: "https://raw.githubusercontent.com/DaveGut/Hubitat-TP-Link-Integration/master/Application/TP-LinkHubitatApplication.groovy"
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
	app?.removeSetting("selectedAddDevices")
	app?.removeSetting("missingDevice")
	if (state.deviceIps) { state.remove("deviceIps") }
	if (selectedAddDevices) { addDevices() }
}

//	=====	Main Page	=====
def mainPage() {
	logDebug("mainPage")
	initialize()
	return dynamicPage(name:"mainPage",
		title:"<b>Kasa Device Manager</b>",
		uninstall: true,
		install: true) {
		section() {
			href "addDevicesPage",
				title: "<b>Install Kasa Devices</b>",
				description: "Gets device information. Then offers new devices for installation.\n" +
							 "It may take several minutes for the next page to load."
			href "listDevicesPage",
				title: "<b>Update the Hubitat WiFi IP Definitions</b>",
				description: "List all available Kasa deevices and update the IP address for installed devices.\n" +
							 "It may take several minutes for the next page to load."
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
			paragraph "<b>Recommendation:  Set Static IP Address in your WiFi router for Kasa Devices."
			paragraph "<b>Note:  If you have problems with this application, Manual installation is supported by all drivers."
		}
	}
}

//	=====	Add Devices	=====
def addDevicesPage() {
	state.devices = [:]
	findDevices(100, "parseDeviceData")
    def devices = state.devices
	def newDevices = [:]
	devices.each {
		def isChild = getChildDevice(it.value.DNI)
		if (!isChild) {
			newDevices["${it.value.DNI}"] = "${it.value.model} ${it.value.alias}"
		}
	}
	logDebug("addDevicesPage: newDevices = ${newDevices}")
	return dynamicPage(name:"addDevicesPage",
		title:"<b>Add Kasa Devices to Hubitat</b>",
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
	def resp = parseLanMessage(response.description)
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload)).system.get_sysinfo
	logDebug("parseDeviceData: ${convertHexToIP(resp.ip)} // ${cmdResp}")
	def type
	if (cmdResp.mic_type) { type = cmdResp.mic_type }
	else { type = cmdResp.type }
	def dni
	if (type == "IOT.SMARTBULB") { dni = cmdResp.mic_mac }
	else { dni = cmdResp.mac.replace(/:/, "") }
	def model
	if (cmdResp.system) { model = cmdResp.system.model.substring(0,5) }
	else { model = cmdResp.model.substring(0,5) }

	if (cmdResp.children) {
		def totPlugs = cmdResp.child_num
		def children = cmdResp.children
		for (def i = 0; i < totPlugs; i++) {
			addData("${resp.mac}${children[i].id}", model, convertHexToIP(resp.ip), 
					children[i].alias, type, children[i].id, "${cmdResp.deviceId}${children[i].id}")
		}
	} else {
		addData(dni, model, convertHexToIP(resp.ip), cmdResp.alias, type)
	}
}

def addData(dni, model, ip, alias, type, plugNo = null, plugId = null) {
	logInfo("addData: DNI = ${dni}, model = ${model}, ip = ${ip}, alias = ${alias}, type = ${type}, plugNo = ${plugNo}, plugId = ${plugId}")
	if (model == "RE270" || model == "RE370") { return }
	def device = [:]
	device["DNI"] = dni
	device["IP"] = ip
	device["alias"] = alias
	device["model"] = model
	device["type"] = type
	if (plugNo) {
		device["plugNo"] = plugNo
		device["plugId"] = plugId
	}
	state.devices << ["${dni}" : device]
	def isChild = getChildDevice(dni)
	if (isChild) {
		isChild.updateDataValue("deviceIP", ip)
		isChild.updateDataValue("applicationVersion", appVersion())
	}		
}

def addDevices() {
	logDebug("addDevices: ${selectedAddDevices}")
	def tpLinkModel = [:]
	//	Plug-Switch Devices (no energy monitor capability)
	tpLinkModel << ["HS100" : "TP-Link Plug-Switch"]
	tpLinkModel << ["HS103" : "TP-Link Plug-Switch"]
	tpLinkModel << ["HS105" : "TP-Link Plug-Switch"]
	tpLinkModel << ["HS200" : "TP-Link Plug-Switch"]
	tpLinkModel << ["HS210" : "TP-Link Plug-Switch"]
	tpLinkModel << ["KP100" : "TP-Link Plug-Switch"]
	//	Miltiple Outlet Plug
	tpLinkModel << ["HS107" : "TP-Link Multi-Plug"]
	tpLinkModel << ["KP200" : "TP-Link Multi-Plug"]
	tpLinkModel << ["KP303" : "TP-Link Multi-Plug"]
	tpLinkModel << ["KP400" : "TP-Link Multi-Plug"]
	//	Dimming Switch Devices
	tpLinkModel << ["HS220" : "TP-Link Dimming Switch"]
	//	Energy Monitor Multi Plugs
	tpLinkModel << ["HS300" : "TP-Link Engr Mon Multi-Plug"]
	//	Energy Monitor Plugs
	tpLinkModel << ["HS110" : "TP-Link Engr Mon Plug"]
	tpLinkModel << ["HS115" : "TP-Link Engr Mon Plug"]
	//	Soft White Bulbs
	tpLinkModel << ["KB100" : "TP-Link Soft White Bulb"]
	tpLinkModel << ["LB100" : "TP-Link Soft White Bulb"]
	tpLinkModel << ["LB110" : "TP-Link Soft White Bulb"]
	tpLinkModel << ["KL110" : "TP-Link Soft White Bulb"]
	tpLinkModel << ["LB200" : "TP-Link Soft White Bulb"]
	//	Tunable White Bulbs
	tpLinkModel << ["LB120" : "TP-Link Tunable White Bulb"]
	tpLinkModel << ["KL120" : "TP-Link Tunable White Bulb"]
	//	Color Bulbs
	tpLinkModel << ["KB130" : "TP-Link Color Bulb"]
	tpLinkModel << ["LB130" : "TP-Link Color Bulb"]
	tpLinkModel << ["KL130" : "TP-Link Color Bulb"]
	tpLinkModel << ["LB230" : "TP-Link Color Bulb"]
	def hub
	try { hub = location.hubs[0] }
	catch (error) { 
		logWarn("Hub not detected.  You must have a hub to install this app.")
		return
	}
	def hubId = hub.id
	selectedAddDevices.each { dni ->
		def isChild = getChildDevice(dni)
		if (!isChild) {
			def device = state.devices.find { it.value.DNI == dni }
			def deviceModel = device.value.model
			def deviceData = [:]
			deviceData["applicationVersion"] = appVersion()
			deviceData["deviceIP"] = device.value.IP
			if (device.value.plugNo != null) {
				deviceData["plugNo"] = device.value.plugNo
				deviceData["plugId"] = device.value.plugId
			}
			logDebug("addDevices: ${tpLinkModel["${deviceModel}"]} / ${device.value.DNI} / ${hubId} / ${device.value.alias} / ${deviceModel} / ${deviceData}")
			logInfo("Adding device: ${tpLinkModel["${deviceModel}"]} / ${device.value.alias}.")
			try {
				addChildDevice(
					"davegut",
					tpLinkModel["${deviceModel}"],
					device.value.DNI,
					hubId, [
						"label" : device.value.alias,
						"name" : deviceModel,
						"data" : deviceData
					]
				)
			} catch (error) {
				logWarn("Failed to install ${device.value.alias}.  Driver ${tpLinkModel["${deviceModel}"]} most likely not installed.")
			}
		}
	}
	app?.removeSetting("selectedAddDevices")
}

//	=====	Update Device IPs	=====
def listDevicesPage() {
	logDebug("listDevicesPage")
	state.devices = [:]
	findDevices(100, "parseDeviceData")
	def devices = state.devices
	def foundDevices = "<b>Found Devices (Installed / DNI / IP / Label):</b>"
	def count = 1
	devices.each {
		def installed = false
		if (getChildDevice(it.value.DNI)) { installed = true }
		foundDevices += "\n${count}:\t${installed}\t${it.value.DNI}\t${it.value.IP}\t${it.value.alias}"
		count += 1
	}
	return dynamicPage(name:"listDevicesPage",
		title:"<b>Available Kasa Switches, Plugs, and Bulbs on your LAN</b>",
		install: false) {
	 	section() {
			paragraph "The appliation has searched and found the below devices. If any are " +
				"missing, there may be a problem with the device.\n${foundDevices}\n\n" +
				"<b>RECOMMENDATION: Set Static IP Address in your WiFi router for Kasa Devices.</b>"
		}
	}
}

//	===== IP Check =====
def updateDeviceIps() {
	logDebug("updateDeviceIps: Updating Device IPs.")
	runIn(5, updateDevices)
}

def updateDevices() {
	if (pollEnabled == true) {
		app?.updateSetting("pollEnabled", [type:"bool", value: false])
		runIn(3600, pollEnable)
	} else {
		logWarn("updateDevices: a poll was run within the last hour.  Exited.")
		return
	}
	state.devices= [:]
	findDevices(100, parseDeviceData)
}

def pollEnable() {
	logDebug("pollEnable")
	app?.updateSetting("pollEnabled", [type:"bool", value: true])
}

//	=====	Kasa Specific Communications	=====
def findDevices(pollInterval, action) {
	def hub
	try { hub = location.hubs[0] }
	catch (error) { 
		logWarn "Hub not detected.  You must have a hub to install this app."
		return
	}
	def hubIpArray = hub.localIP.split('\\.')
	def networkPrefix = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")
	logInfo("findDevices: IP Segment = ${networkPrefix}")
	for(int i = 2; i < 255; i++) {
		def deviceIP = "${networkPrefix}.${i.toString()}"
		sendPoll(deviceIP, action)
		pauseExecution(pollInterval)
	}
	pauseExecution(3000)
}

private sendPoll(ip, action) {
	def myHubAction = new hubitat.device.HubAction(
		"d0f281f88bff9af7d5f5cfb496f194e0bfccb5c6afc1a7c8eacaf08bf68bf6",
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${ip}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 timeout: 1,
		 callback: action])
	sendHubCommand(myHubAction)
}

private outputXOR(command) {
	def str = ""
	def encrCmd = ""
 	def key = 0xAB
	for (int i = 0; i < command.length(); i++) {
		str = (command.charAt(i) as byte) ^ key
		key = str
		encrCmd += Integer.toHexString(str)
	}
   	return encrCmd
}

private inputXOR(encrResponse) {
	String[] strBytes = encrResponse.split("(?<=\\G.{2})")
	def cmdResponse = ""
	def key = 0xAB
	def nextKey
	byte[] XORtemp
	for(int i = 0; i < strBytes.length-1; i++) {
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative
		XORtemp = nextKey ^ key
		key = nextKey
		cmdResponse += new String(XORtemp)
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
	if(debugLog == true) { log.debug "${appVersion()} ${msg}" }
}

def logInfo(msg){
	if(infoLog == true) { log.info "${appVersion()} ${msg}" }
}

def logWarn(msg) { log.warn "${appVersion()} ${msg}" }

//	end-of-file