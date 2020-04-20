/*
Kasa Local Device Driver
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
		a.	Changed version number to Ln.n.n format where the L refers to LOCAL installation.
		b.	Moved Quick Polling from preferences to a command with number (seconds) input value.  A value of
			blank or 0 is disabled.  A value below 5 is read as 5.
		c.	Upaded all drivers to eight individual divers.
03.03	Manual install and functional testing complete.  Auto Installation testing complete.
===== GitHub Repository =====
=======================================================================================================*/
def driverVer() { return "L5.0.1" }

metadata {
	definition (name: "Kasa Plug Switch",
    			namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: " https://raw.githubusercontent.com/DaveGut/Hubitat-TP-Link-Integration/master/DeviceDrivers/TP-LinkPlug-Switch(Hubitat).groovy"
			   ) {
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		command "setPollFreq", ["NUMBER"]
	}
    preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP", defaultValue: getDataValue("deviceIP"))
		}
		input ("refresh_Rate", "enum", title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "10", "15", "30"], defaultValue: "30")
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}

def installed() {
	log.info "Installing .."
	updateDataValue("driverVersion", driverVer())	
	state.pollFreq = 0
	updated()
}

def updated() {
	log.info "Updating .."
	unschedule()
	state.errorCount = 0
	if (device.currentValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
		if (shortPoll) {
			state.pollFreq = shortPoll
			removeSetting("shortPoll")
		} else {
			state.pollFreq = 0
		}
	}
	if (!getDataValue("applicationVersion")) {
		if (!device_IP) {
			logWarn("updated: Device IP is not set.")
			return
		}
		if (getDataValue("deviceIP") != device_IP.trim()) {
			updateDataValue("deviceIP", device_IP.trim())
			logInfo("updated: Device IP set to ${device_IP.trim()}")
		}
	}
	switch(refresh_Rate) {
		case "1" : runEvery1Minute(refresh); break
		case "5" : runEvery5Minutes(refresh); break
		case "10" : runEvery10Minutes(refresh); break
		case "15" : runEvery15Minutes(refresh); break
		default: runEvery30Minutes(refresh)
	}
	logInfo("updated: Refresh set for every ${refresh_Rate} minute(s).")
	if (debug == true) { runIn(1800, debugLogOff) }
	logInfo("updated: Debug logging is: ${debug} for 30 minutes.")
	logInfo("updated: Description text logging is ${descriptionText}.")
	refresh()
}

//	Device Cloud and Local Common Methods
def on() {
	logDebug("on")
	sendCmd("""{"system":{"set_relay_state":{"state":1}}}""", "commandResponse")
}

def off() {
	logDebug("off")
	sendCmd("""{"system":{"set_relay_state":{"state":0}}}""", "commandResponse")
}

def refresh() {
	logDebug("refresh")
	sendCmd("""{"system" :{"get_sysinfo" :{}}}""", "statusResponse")
}

def commandResponse(response) {
	logDebug("commandResponse")
	sendCmd("""{"system":{"get_sysinfo":{}}}""", "statusResponse")
}

//	Device Local Only Methods
def setPollFreq(interval = 0) {
	logDebug("setPollFreq: interval = ${interval}")
	interval = interval.toInteger()
	if (interval !=0 && interval < 5) { interval = 5 }
	if (interval != state.pollFreq) {
		state.pollFreq = interval
		refresh()
		logInfo("setPollFreq: interval set to ${interval}")
	} else {
		logWarn("setPollFreq: No change in interval from command.")
	}
}

def statusResponse(response) {
	def status = parseInput(response).system.get_sysinfo
	logDebug("statusResponse: status = ${status}")
	def onOff = "on"
	if (status.relay_state == 0) { onOff = "off" }
	if (onOff != device.currentValue("switch")) {
		sendEvent(name: "switch", value: onOff)
	}
	logInfo("statusResponse: switch: ${onOff}")
	if (state.pollFreq > 0) {
		runIn(state.pollFreq, quickPoll)
	}
}

def quickPoll() {
	logDebug("quickPoll: executing a quickPoll")
	sendCmd("""{"system" :{"get_sysinfo" :{}}}""", "quickPollResponse")
}

def quickPollResponse(response) {
	def status = parseInput(response).system.get_sysinfo
	def onOff = "on"
	if (status.relay_state == 0) { onOff = "off" }
	if (onOff != device.currentValue("switch")) {
		sendEvent(name: "switch", value: onOff)
		logInfo("quickPoll: switch: ${onOff}")
	}
	if (state.pollFreq > 0) {
		runIn(state.pollFreq, quickPoll)
	}
}
	
//	Cloud and Local Common Methods
def setCommsError() {
	logWarn("setCommsError")
	state.errorCount += 1
	if (state.errorCount > 4) {
		return
	} else if (state.errorCount < 3) {
		repeatCommand()
		logInfo("Executing attempt ${state.errorCount} to recover communications")
	} else if (state.errorCount == 3) {
		if (getDataValue("applicationVersion")) {
			logWarn("setCommsError: Attempting to update Kasa Device IPs.")
			parent.requestDataUpdate()
			runIn(30, repeatCommand)
		} else {
			runIn(3, repeatCommand)
			logInfo("Executing attempt ${state.errorCount} to recover communications")
		}
	} else if (state.errorCount == 4) {	
		def warnText = "<b>setCommsError</b>: Your device is not reachable.\r" +
						"Complete corrective action then execute any command to continue"
		logWarn(warnText)
	}
}

def repeatCommand() { 
	logDebug("repeatCommand: ${state.lastCommand}")
	sendCmd(state.lastCommand.command, state.lastCommand.action)
}

def logInfo(msg) {
	if (descriptionText == true) { log.info "${device.label} ${driverVer()} ${msg}" }
}

def debugLogOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("Debug logging is false.")
}

def logDebug(msg){
	if(debug == true) { log.debug "${device.label} ${driverVer()} ${msg}" }
}

def logWarn(msg){ log.warn "${device.label} ${driverVer()} ${msg}" }

//	Local Communications Methods
private sendCmd(command, action) {
	logDebug("sendCmd: command = ${command} // device IP = ${getDataValue("deviceIP")}, action = ${action}")
	state.lastCommand = [command: "${command}", action: "${action}"]
	runIn(3, setCommsError)
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${getDataValue("deviceIP")}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 timeout: 2,
		 callback: action])
	sendHubCommand(myHubAction)
}

def parseInput(response) {
	unschedule(setCommsError)
	state.errorCount = 0
	try {
		return parseJson(inputXOR(parseLanMessage(response).payload))
	} catch (e) {
		logWarn("parseInput: JsonParse failed. Response = ${inputXOR(parseLanMessage(response).payload)}.")
	}
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
	for(int i = 0; i < strBytes.length; i++) {
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative
		XORtemp = nextKey ^ key
		key = nextKey
		cmdResponse += new String(XORtemp)
	}
	return cmdResponse
}

//	end-of-file