/*
TP-Link Device Application, Version 4.3
		Copyright 2018, 2019 Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file 
except in compliance with the License. You may obtain a copy of the License at: 
		http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the 
License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
either express or implied. See the License for the specific language governing permissions 
and limitations under the License.

DISCLAIMER:  This Applicaion and the associated Device Drivers are in no way sanctioned or 
supported by TP-Link. All  development is based upon open-source data on the TP-Link 
devices; primarily various users on GitHub.com.

===== History =====
8.26.19	Initial Release
===============================================*/
def debugLog() { return true }
def appVersion() { return "1.0.01" }
import groovy.json.JsonSlurper

definition(
	name: "Kasa Tools",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Tools application for Kasa Devices",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	iconX3Url: "",
	singleInstance: true,
	importUrl: "https://raw.githubusercontent.com/DaveGut/Hubitat-KasaTools/master/KasaTools.groovy"
	)
preferences {
	page(name: "mainPage")
	page(name: "unbindDevicePage")
	page(name: "bindDevicePage")
	page(name: "rebootDevicePage")
}


//	Page definitions
def mainPage() {
	logDebug("mainPage")
	setInitialStates()
	state.IpError = false
	return dynamicPage(
		name: "mainPage",
		title: "Bind/Unbind TP-Link Devices from the Kasa Cloud",
		install: true,
		uninstall: true){
		section () {
			paragraph"Current Communications Error: ${state.commsError}"
		}
		section() {
			input ("deviceType", "enum",
				   required: true,
				   multiple: false,
				   submitOnChange: true,
				   title: "Type of device (bulb or plug/switch).",
				   description: "Select the type of device.",
				   options: ["bulb", "plugSwitch"])
			input ("deviceIp", "text",
				   required: true,
				   multiple: false,
				   submitOnChange: true,
				   title: "Type of device (bulb or plug/switch).",
				   description: "Enter the device IP then ENTER.")

			if (deviceIp) {
				def trimIp = deviceIp.replace(" ", "")
				app.updateSetting("deviceIp", "${trimIp}")
				try {
					String ipTest = deviceIp.tokenize( '.' ).collect {  String.format( '%02x', it.toInteger() ) }.join()
				} catch (error) {
					state.IpError = true
					logWarn("Device IP format invalid.  Resetting to null")
					paragraph "The device IP format was incorrect. Valid format is similar to:\n" +
						"\t192.168.1.111\nCorrect IP to continue."
				}
			}
			if (deviceType && deviceIp && state.IpError == false) {
				state.lastMessage = ""
				href "unbindDevicePage",
					title: "Unbind Device from the Kasa Cloud",
					description: "Go to Unbind Device."
				href "bindDevicePage",
					title: "Bind Device to the Kasa Cloud",
					description: "Go to Bind Device (Requires Username and Password)."
				href "rebootDevicePage",
					title: "Reboot a Kasa Device",
					description: "Go to Reboot Device."
			}
			paragraph "Select 'Remove' to exit."
		}
	}
}

def unbindDevicePage() {
	logDebug("unbindDevicePage: type = ${deviceType}, IP = ${deviceIp}")
	state.currMsg = "None"
	def preamble = "cnCloud"
	if (deviceType == "bulb") { preamble = "smartlife.iot.common.cloud" }
	sendCmd(deviceIp,
			"""{"${preamble}":{"unbind": "" }, "${preamble}":{"get_info":{}}}""",
			"unbindResponse")
	pauseExecution(2000)
	return dynamicPage(name:"unbindDevicePage",
		title:"Unbind Kasa Device from the Kasa Cloud",
		install: false) {
		section() {
			paragraph "Results: ${state.currMsg}"
			href "mainPage",
				title: "Return to Main Page.",
				description: "Go to the Main Page."
		}	
	}
}
def unbindResponse(response) {
	state.commsError = "None."
	def resp = parseLanMessage(response.description)
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload))
	logDebug("unbindResponse: cmdResp = ${cmdResp}")
	def binded
	if (cmdResp.cnCloud) {
		try { binded = cmdResp.cnCloud.get_info.binded }
		catch (error) { binded = -1 }
	} else {
		try { binded = cmdResp["smartlife.iot.common.cloud"].get_info.binded }
		catch (error) { binded = -1 }
	}
	if (binded == 0) {
		logInfo("SUCCESS: Device with DNI = ${resp.mac} is ubbound from the Kasa Cloud")
		state.currMsg = "SUCCESS: Device with DNI = ${resp.mac} is ubbound from the Kasa Cloud"
	} else {
		logWarn("FAILED: DNI: ${resp.mac} unbind failed. Error = ${cmdResp}")
		state.currMsg = "FAILED: DNI: ${resp.mac} unbind failed. Error = ${cmdResp}"
	}
}


def bindDevicePage() {
	logDebug("bindDevicePage: type = ${deviceType}, IP = ${deviceIp}")
	state.currMsg = "None"
	if (userName && userPassword) {
		def preamble = "cnCloud"
		if (deviceType == "bulb") { preamble = "smartlife.iot.common.cloud" }
		sendCmd(deviceIp,
				"""{"${preamble}":{"bind":{"username":"${userName}", "password": "${userPassword}"}}, """ +
				""""${preamble}":{"get_info":{}}}""",
				"bindResponse")
		pauseExecution(2000)
	}
	return dynamicPage(name:"bindDevicePage",
		title:"Bind Kasa Devices to the Kasa Cloud",
		install: false) {
		section() {
			paragraph "Result: ${state.currMsg}"
			paragraph "Enter Kasa Account Username (E-Mail) and Password"
			input ("userName", "text",
				   title: "TP-Link Kasa Account E-Mail",
				   submitOnChange: true)
			input ("userPassword", "password",
				   title: "TP-Link Kasa Account Password",
				   submitOnChange: true)
		}
		section() {
			href "mainPage",
				title: "Return to Main Page.",
				description: "Go to the Main Page."
		}		
	}
}
def bindResponse(response) {
	state.commsError = "None."
	def resp = parseLanMessage(response.description)
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload))
	logDebug("bindResponse: cmdResp = ${cmdResp}")
	def binded
	if (cmdResp.cnCloud) {
		try { binded = cmdResp.cnCloud.get_info.binded }
		catch (error) { binded = -1 }
	} else {
		try { binded = cmdResp["smartlife.iot.common.cloud"].get_info.binded }
		catch (error) { binded = -1 }
	}
	if (binded == 1) {
		logInfo("SUCCESS: Device with DNI = ${resp.mac} is bound to the Kasa Cloud")
		state.currMsg = "SUCCESS: Device with DNI = ${resp.mac} is bound to the Kasa Cloud"
	} else {
		logWarn("FAILED: DNI: ${resp.mac} bind failed. Error = ${cmdResp}")
		state.currMsg = "FAILED: DNI: ${resp.mac} bind failed. Error = ${cmdResp}"
	}
}


def rebootDevicePage() {
	logDebug("rebootDevicePage: type = ${deviceType}, IP = ${deviceIp}")
	state.currMsg = "None"
	def preamble = "system"
	if (deviceType == "bulb") { preamble = "smartlife.iot.common.system" }
	sendCmd(deviceIp,
			"""{"${preamble}":{"reboot":{"delay":3}}}""",
			"rebootResponse")
	pauseExecution(2000)
	return dynamicPage(name:"rebootDevicePage",
		title:"Reboot a Kasa Device",
		install: false) {
		section() {
			paragraph "Result: ${state.currMsg}"
			href "mainPage",
				title: "Return to Main Page.",
				description: "Go to the Main Page."
		}
	}
}
def rebootResponse(response) {
	state.commsError = "None."
	def resp = parseLanMessage(response.description)
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload))
	logDebug("rebootResponse: cmdResp = ${cmdResp}")
	def err_code
	if (cmdResp.system) {
		try { err_code = cmdResp.system.reboot.err_code }
		catch (error) { err_code = -1 }
	} else {
		try { err_code = cmdResp["smartlife.iot.common.system"].reboot.err_code }
		catch (error) { err_code = -1 }
	}
	if (err_code == 0) {
		logInfo("SUCCESS. Device with DNI = ${resp.mac} rebooting.")
		state.currMsg = "SUCCESS. Device with DNI = ${resp.mac} rebooting."
	} else {
		logWarn("FAILED.  DNI: ${resp.mac} reboot command failed.  Error = ${cmdResp}")
		state.currMsg = "FAILED.  DNI: ${resp.mac} reboot command failed.  Error = ${cmdResp}"
	}
}


//	Install and Initialization methods
def setInitialStates() {
	logDebug("setInitialStates")
}
def installed() { initialize() }
def updated() { initialize() }
def initialize() {}


//	Communications
private sendCmd(ip, command, action) {
	logDebug("sendCmd: ip = ${ip}, command = ${command}")
	state.commsError = "Communications Timeout or No Response Received.  Check IP Address.  IP = ${deviceIp}."
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command), 	//	Encrypted command
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${ip}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 timeout: 2,
		 callback: action])
	sendHubCommand(myHubAction)
}


//	Utility methods
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
def logDebug(msg){
	if(debugLog() == true) { log.debug "${appVersion()} ${msg}" }
}
def logInfo(msg) { log.info "${appVersion()} ${msg}" }
def logWarn(msg) { log.warn "${appVersion()} ${msg}" }

//	end-of-file