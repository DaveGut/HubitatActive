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
03.03	Manual install and functional testing of On/Off.  EM Functions not tested.  Auto Installation testing complete.
04.08	L5.0.2.  Initial development started for next version:
		a.	Add type to attribute "switch",
		b.	Sending multiple command for on/off eliminating need to send separate status command.
		c.	Add 60 and 180 minute refresh rates.  Change default to 60 minutes.
04.20	5.1.0	Update for Hubitat Program Manager
05.03	5.1.0.1	Update to correct Power Polling Function.
=======================================================================================================*/
def driverVer() { return "5.1.0.1" }
metadata {
	definition (name: "Kasa EM Plug",
    			namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/EM-Plug.groovy"
			   ) {
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		command "setPollFreq", [[name: "Set polling frequency in seconds", type: "NUMBER"]]
		capability "Power Meter"
		capability "Energy Meter"
		attribute "currMonthTotal", "number"
		attribute "currMonthAvg", "number"
		attribute "lastMonthTotal", "number"
		attribute "lastMonthAvg", "number"
	}
    preferences {
		if (!getDataValue("applicationVersion")) {
			input ("device_IP", "text", title: "Device IP", defaultValue: getDataValue("deviceIP"))
		}
		input ("emFunction", "bool", title: "Enable Energy Monitor Function", defaultValue: true)
		input ("refresh_Rate", "enum", title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "10", "15", "30", "60", "180"], defaultValue: "60")
		input ("debug", "bool", title: "Enable debug logging", defaultValue: false)
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}

def installed() {
	log.info "Installing .."
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
		case "30" : runEvery30Minutes(refresh); break
		case "180": runEvery3Hours(refresh); break
		default: runEvery1Hour(refresh)
	}
	logInfo("updated: Refresh set for every ${refresh_Rate} minute(s).")
	if (debug == true) { runIn(1800, debugLogOff) }
	logInfo("updated: Debug logging is: ${debug} for 30 minutes.")
	logInfo("updated: Description text logging is ${descriptionText}.")
	if (emFunction) {
		schedule("0 01 0 * * ?", updateStats)
		runIn(1, updateStats)
		logInfo("updated: Scheduled nightly energy statistics update.")
	}
	runIn(5, refresh)
}

//	Device Cloud and Local Common Methods
def on() {
	logDebug("on")
	sendCmd("""{"system":{"set_relay_state":{"state":1}},""" +
			""""system" :{"get_sysinfo" :{}}}""", 
			"commandResponse")
}

def off() {
	logDebug("off")
	sendCmd("""{"system":{"set_relay_state":{"state":0}},""" +
			""""system" :{"get_sysinfo" :{}}}""", 
			"commandResponse")
}

def refresh() {
	logDebug("refresh")
	sendCmd("""{"system" :{"get_sysinfo" :{}}}""", "commandResponse")
}

def commandResponse(response) {
	def status = parseInput(response).system.get_sysinfo
	logDebug("commandResponse: status = ${status}")
	def onOff = "on"
	if (status.relay_state == 0 || status.state == 0) { onOff = "off" }
	if (onOff != device.currentValue("switch")) {
		sendEvent(name: "switch", value: onOff, type: "digital")
	}
	logInfo("commandResponse: switch: ${onOff}")
	sendCmd("""{"emeter":{"get_realtime":{}}}""", "powerResponse")
	if (!emFunction && state.pollFreq > 0) {
		runIn(state.pollFreq, quickPoll)
	} else if (emFunction && state.pollFreq>0) {
		runIn(state.pollFreq, powerPoll)
	}
}

def powerResponse(response) {
	def cmdResponse = parseInput(response)
	logDebug("powerResponse: cmdResponse = ${cmdResponse}")
	def realtime = cmdResponse.emeter.get_realtime
	def power = realtime.power
	if (power == null) { power = realtime.power_mw / 1000 }
	power = (0.5 + Math.round(100*power)/100).toInteger()
	if (power != device.currentValue("power")) {
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W")
	}
	logInfo("powerResponse: [power: ${power}]")
	sendCmd("""{"emeter":{"get_monthstat":{"year": ${thisYear()}}}}""","setEngrToday")
}

def setEngrToday(response) {
	def cmdResponse = parseInput(response)
	logDebug("setEngrToday: ${cmdResponse}")
	def data = cmdResponse.emeter.get_monthstat.month_list.find { it.month == thisMonth() }
	def energyData = data.energy
	if (energyData == null) { energyData = data.energy_wh/1000 }
	energyData -= device.currentValue("currMonthTotal")
	energyData = Math.round(100*energyData)/100
	if (energyData != device.currentValue("energyData")) {
		sendEvent(name: "energy", value: energyData, descriptionText: "KiloWatt Hours", unit: "KWH")
	}
	logInfo("setEngrToday: [energy: ${energyData}]")
}

def updateStats() {
	logDebug("updateStats")
	sendCmd("""{"time":{"get_time":null}}""", "checkDateResponse")
}

def checkDateResponse(response) {
	def cmdResponse = parseInput(response)
	logDebug("checkDateResponse: ${cmdResponse}")
	def data = cmdResponse.time.get_time
	def newDate = new Date()
	def year = newDate.format("yyyy").toInteger()
	def month = newDate.format("M").toInteger()
	def day = newDate.format("d").toInteger()
	if(year == data.year.toInteger() && month == data.month.toInteger() && day == data.mday.toInteger()) {
		state.currDate = [data.year, data.month, data.mday]
		pauseExecution(1000)
		logInfo("checkDateResponse: currDate = ${state.currDate}")
		sendCmd("""{"emeter":{"get_monthstat":{"year": ${thisYear()}}}}""", "setThisMonth")
	} else {
		logInfo("checkDateResponse: date is not current.")
		def hour = newDate.format("H").toInteger()
		def min = newDate.format("m").toInteger()
		def sec = newDate.format("s").toInteger()
		changeDate(year, month, day, hour, min, sec)
	}
}

def changeDate(year, month, mday, hour, min, sec) {
	logInfo("changeDate: Updating date to ${year} /${month} /${mday} /${hour} /${min} /${sec}")
	sendCmd("""{"time":{"set_timezone":{"year":${year},"month":${month},"mday":${mday},"hour":${hour},"min":${min},"sec":${sec},"index":55}}}""", 
			"changeDateResponse")
}

def changeDateResponse(response) { 
	def cmdResponse = parseInput(response)
	logInfo("changeDateResponse: cmdResponse = cmdResponse}")
	updateStats()
}

def thisYear() {
	return state.currDate[0].toInteger()
}

def thisMonth() {
	return state.currDate[1].toInteger()
}

def today() {
	return state.currDate[2].toInteger()
}

def setThisMonth(response) {
	def cmdResponse = parseInput(response)
	logDebug("setThisMonth: cmdResponse = ${cmdResponse}")
	def data = cmdResponse.emeter.get_monthstat.month_list.find { it.month == thisMonth() }
	def scale = "energy"
	def energyData
	if (data == null) { energyData = 0 }
	else {
		if (data.energy == null) { scale = "energy_wh" }
		energyData = data."${scale}"
	}
	def avgEnergy = 0
	def day = today()
	if (day !=1) { avgEnergy = energyData/(day - 1) }
	if (scale == "energy_wh") {
		energyData = energyData/1000
		avgEnergy = avgEnergy/1000
	}
	energyData = Math.round(100*energyData)/100
	avgEnergy = Math.round(100*avgEnergy)/100
	sendEvent(name: "currMonthTotal", value: energyData, descriptionText: "KiloWatt Hours", unit: "KWH")
	sendEvent(name: "currMonthAvg", value: avgEnergy, descriptionText: "KiloWatt Hours per Day", unit: "KWH/D")
	logInfo("This month's energy stats set to ${energyData} // ${avgEnergy}")
	def year = thisYear()
	if (thisMonth() == 1) { year = year -1 }
	sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""", "setLastMonth")
}

def setLastMonth(response) {
	def cmdResponse = parseInput(response)
	logDebug("setLastMonth: cmdResponse = ${cmdResponse}")
	def lastMonth = thisMonth() -1
	if (lastMonth == 0) { lastMonth = 12 }
	def monthLength
	switch(lastMonth) {
		case 4:
		case 6:
		case 9:
		case 11:
			monthLength = 30
			break
		case 2:
			monthLength = 28
			def year = thisYear()
			if (year == 2020 || year == 2024 || year == 2028) { monthLength = 29 }
			break
		default:
			monthLength = 31
	}
	def data = cmdResponse.emeter.get_monthstat.month_list.find { it.month == lastMonth }
	def scale = "energy"
	def energyData
	if (data == null) { energyData = 0 }
	else {
		if (data.energy == null) { scale = "energy_wh" }
		energyData = data."${scale}"
	}
	def avgEnergy = energyData/monthLength
	if (scale == "energy_wh") {
		energyData = energyData/1000
		avgEnergy = avgEnergy/1000
	}
	energyData = Math.round(100*energyData)/100
	avgEnergy = Math.round(100*avgEnergy)/100
	sendEvent(name: "lastMonthTotal", value: energyData, descriptionText: "KiloWatt Hours", unit: "KWH")
	sendEvent(name: "lastMonthAvg", value: avgEnergy, descriptionText: "KiloWatt Hoursper Day", unit: "KWH/D")
	logInfo("Last month's energy stats set to ${energyData} // ${avgEnergy}")
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

def quickPoll() {
	sendCmd("""{"system" :{"get_sysinfo" :{}}}""", "quickPollResponse")
}

def quickPollResponse(response) {
	def status = parseInput(response).system.get_sysinfo
	def onOff = "on"
	if (status.relay_state == 0) { onOff = "off" }
	if (onOff != device.currentValue("switch")) {
		sendEvent(name: "switch", value: onOff, type: "physical")
		logInfo("quickPollResponse: switch: ${onOff}")
	}
	if (state.pollFreq > 0) {
		runIn(state.pollFreq, quickPoll)
	}
}
	
def powerPoll() {
	sendCmd("""{"emeter":{"get_realtime":{}}}""", "powerPollResponse")
}

def powerPollResponse(response) {
	logDebug("powerPollResponse")
	def status = resp.emeter.get_realtime
	def power = status.power
	if (power == null) { power = status.power_mw / 1000 }
	power = (0.5 + Math.round(100*power)/100).toInteger()
	def curPwr = device.currentValue("power").toInteger()
	if (power > curPwr + 3 || power < curPwr - 3) { 
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W")
		logInfo("powerPollResponse: power = ${power}")
	}
	if (emFunction && state.pollFreq > 0) {
		runIn(state.pollFreq, powerPoll)
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