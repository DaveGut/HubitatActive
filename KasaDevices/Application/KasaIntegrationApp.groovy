/*	Kasa Local Integration
	Copyright Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file except in compliance with the
License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.

DISCLAIMER:  This Applicaion and the associated Device Drivers are in no way sanctioned or supported by TP-Link.  
All  development is based upon open-source data on the TP-Link devices; primarily various users on GitHub.com.

===== 2020 History =====
02.28	New version 5.0
		a.	Changed version number to Ln.n.n format.
		b.	Updated error handling request from children.
03.03	Automated installation using app verified.  Updated doc and import links.
Removed test code.  Tested Update Method.
04.20	5.1.0	Update for Hubitat Package Manager
=======================================================================================================*/
def appVersion() { return "5.1.0" }
import groovy.json.JsonSlurper
definition(
	name: "Kasa Integration",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Application to install TP-Link bulbs, plugs, and switches.",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	singleInstance: true,
	documentationLink: "https://github.com/DaveGut/Hubitat-TP-Link-Integration/wiki",
	importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/Application/KasaIntegrationApp.groovy"
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
	if (state.deviceIps) { state.remove("deviceIps") }
	if (selectedAddDevices) { addDevices() }
	if (debugLog) { runIn(1800, debugOff) }
}

def uninstalled() {
    getAllChildDevices().each { 
        deleteChildDevice(it.deviceNetworkId)
    }
}

def mainPage() {
	logDebug("mainPage")
	initialize()
	return dynamicPage(name:"mainPage",
					   title:"<b>Kasa Local Hubitat Integration</b>\n" +
					   "Help via <b>?</b> is enabled!",
					   uninstall: true,
					   install: true) {
		section() {
			href "addDevicesPage",
				title: "<b>Install Kasa Devices</b>",
				description: "<b>Note</b>: Set a static IP address before installing a new device."
			href "listDevicesPage",
				title: "<b>List Kasa Devices</b>",
				description: "List all Kasa devices and (if installed) updates the IP addresses."
		}
		section() {
			input ("debugLog", "bool",
				   defaultValue: false,
				   required: false,
				   submitOnChange: true,
				   title: "Enable Application Debug Logging for 30 minutes")
		}
	}
}

def addDevicesPage() {
	state.devices = [:]
	findDevices(50, "parseDeviceData")
    def devices = state.devices
	def newDevices = [:]
	devices.each {
		def isChild = getChildDevice(it.value.dni)
		if (!isChild) {
			newDevices["${it.value.dni}"] = "${it.value.model} ${it.value.alias}"
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

def listDevicesPage() {
	logDebug("listDevicesPage")
	state.devices = [:]
	findDevices(50, "parseDeviceData")
	def devices = state.devices
	def foundDevices = "<b>Found Devices (Installed / IP / Label/ DNI ):</b>"
	def count = 1
	devices.each {
		def installed = false
		if (getChildDevice(it.value.dni)) { installed = true }
		foundDevices += "\n${count}:\t${installed}\t${it.value.ip}\t${it.value.alias}\t${it.value.dni}"
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

def parseDeviceData(response) {
	def resp = parseLanMessage(response.description)
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload)).system.get_sysinfo
	def ip = convertHexToIP(resp.ip)
	logDebug("parseDeviceData: ${ip} // ${cmdResp}")
	def dni
	if (cmdResp.mic_mac) { dni = cmdResp.mic_mac }
	else { dni = cmdResp.mac.replace(/:/, "") }
	def alias = cmdResp.alias
	def model = cmdResp.model.substring(0,5)
	def type = getType(model)
	def plugNo
	def plugId
	if (cmdResp.children) {
		def childPlugs = cmdResp.children
		childPlugs.each {
			plugNo = it.id
			def plugDni = "${dni}${plugNo}"
			plugId = cmdResp.deviceId + plugNo
			alias = it.alias
			updateDevices(plugDni, ip, alias, model, type, plugNo, plugId)
		}
	} else {
		updateDevices(dni, ip, alias, model, type, plugNo, plugId)
	}
}

def getType(model) {
	switch(model) {
		case "HS100" :
		case "HS103" :
		case "HS105" :
		case "HS200" :
		case "HS210" :
		case "KP100" :
			return "Plug Switch"
			break
		case "HS110" :
			return "EM Plug"
			break
		case "KP200" :
		case "HS107" :
		case "KP303" :
		case "KP400" :
			return "Multi Plug"
			break
		case "HS300" :
			return "EM Multi Plug"
			break
		case "HS220" :
			return "Dimming Switch"
			break
		case "KB100" :
		case "LB100" :
		case "LB110" :
		case "KL110" :
		case "LB200" :
		case "KL50(" :
		case "KL60(" :
			return "Mono Bulb"
			break
		case "LB120" :
		case "KL120" :
			return "CT Bulb"
			break
		case "KB130" :
		case "LB130" :
		case "KL130" :
		case "LB230" :
			return "Color Bulb"
			break
		default :
			logWarn("getType: Model not on current list.  Contact developer.")
	}
}

def updateDevices(dni, ip, alias, model, type, plugNo, plugId) {
	logDebug("updateDevices")
	def devices = state.devices
	def device = [:]
	device["dni"] = dni
	device["ip"] = ip
	device["alias"] = alias
	device["model"] = model
	device["type"] = type
	device["plugNo"] = plugNo
	device["plugId"] = plugId
	devices << ["${dni}" : device]
	def child = getChildDevice(dni)
	if (child) {
		logInfo("updateDevices: ${alias} IP updated to ${ip}")
		child.updateDataValue("deviceIP", ip)
		child.updateDataValue("applicationVersion", appVersion())
		logInfo("updateDevices: ${alias} IP updated to ${ip}")
	}		
	logInfo("updateDevices: ${alias} added to devices array")
}

def addDevices() {
	logDebug("addDevices: ${selectedAddDevices}")
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
			def device = state.devices.find { it.value.dni == dni }
			def deviceData = [:]
			deviceData["applicationVersion"] = appVersion()
			deviceData["deviceIP"] = device.value.ip
			if (device.value.type == "Multi Plug" || device.value.type == "EM Multi Plug") {
				deviceData["plugNo"] = device.value.plugNo
				deviceData["plugId"] = device.value.plugId
			}
			try {
				addChildDevice(
					"davegut",
					"Kasa ${device.value.type}",
					device.value.dni,
					hubId, [
						"label": device.value.alias,
						"name" : device.value.model,
						"data" : deviceData
					]
				)
				logInfo("Installed Kasa ${model} with alias ${device.value.alias}")
			} catch (error) {
				logWarn("Failed to install ${device.value.alias}.  Driver most likely not installed.")
			}
		}
	}
	app?.removeSetting("selectedAddDevices")
}

def requestDataUpdate() {
	logInfo("requestDataUpdate: Received device IP request from a Kasa device.")
	runIn(5, pollForIps)
}

def pollForIps() {
	if (pollEnabled == false) {
		logWarn("pollForIps: a poll was run within the last hour.  Poll not run.  Try running manually through the application.")
	} else {
		logInfo("pollForIps: Diabling poll capability for one hour")
		app?.updateSetting("pollEnabled", [type:"bool", value: false])
		runIn(3600, pollEnable)
		logInfo("pollForIps: starting poll for Kasa Device IPs.")
		state.devices= [:]
		findDevices(50, updateDeviceIps)
	}
}

def pollEnable() {
	logInfo("pollEnable: polling capability enabled.")
	app?.updateSetting("pollEnabled", [type:"bool", value: true])
}

def updateDeviceIps(response) {
	def resp = parseLanMessage(response.description)
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload)).system.get_sysinfo
	def ip = convertHexToIP(resp.ip)
	def dni
	if (cmdResp.mic_mac) { dni = cmdResp.mic_mac }
	else { dni = cmdResp.mac.replace(/:/, "") }
	def child = getChildDevice(dni)
	if (child) {
		child.updateDataValue("deviceIP", ip)
		logInfo("updateDeviceIps: updated IP for device ${dni} to ${ip}.")
	}		
}

def findDevices(pollInterval, action) {
	logInfo("findDevices: This process will generate a LOT of WARNING messages related to UDP Timeout.  THIS IS NORMAL!")
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
		sendCmd(deviceIP, action)
		pauseExecution(pollInterval)
	}
	pauseExecution(3000)
}

private sendCmd(ip, action) {
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

private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private Integer convertHexToInt(hex) { Integer.parseInt(hex,16) }

def debugOff() {
	app?.updateSetting("debugLog", [type:"bool", value: false])
}

def logDebug(msg){
	if(debugLog == true) { log.debug "${appVersion()} ${msg}" }
}

def logInfo(msg){ log.info "${appVersion()} ${msg}" }

def logWarn(msg) { log.warn "${appVersion()} ${msg}" }

//	end-of-file
