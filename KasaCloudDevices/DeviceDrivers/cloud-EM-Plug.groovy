/*	Kasa Cloud Device Driver Series
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md

This is a special cloud version for devices that have had firmware updates that disabled port 9999.
===== 2020 History =====
11.27	1.0.0	Initial release of cloud version.

===================================================================================================*/
def driverVer() { return "1.0.0" }
metadata {
	definition (name: "Kasa Cloud EM Plug",
    			namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaCloudDevices/DeviceDrivers/cloud-EM-Plug.groovy"
			   ) {
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		capability "Power Meter"
		capability "Energy Meter"
		attribute "currMonthTotal", "number"
		attribute "currMonthAvg", "number"
		attribute "lastMonthTotal", "number"
		attribute "lastMonthAvg", "number"
		attribute "deviceError", "string"
	}
	preferences {
		input ("emFunction", "bool", 
			   title: "Enable Energy Monitor", 
			   defaultValue: false)
		input ("refresh_Rate", "enum",  
			   title: "Device Refresh Interval (minutes)", 
			   options: ["1", "5", "10", "15", "30", "60", "180"], 
			   defaultValue: "60")
		input ("debug", "bool", 
			   title: "Enable debug logging", 
			   defaultValue: false)
		input ("descriptionText", "bool", 
			   title: "Enable description text logging", 
			   defaultValue: true)
	}
}
def installed() {
	logInfo("Installing Device....")
	runIn(2, updated)
}
//	===== Updated and associated methods =====
def updated() {
	logInfo("Updating device preferences....")
	unschedule()
	//	Update various preferences.
	if (debug == true) { 
		runIn(1800, debugLogOff)
		logInfo("updated: Debug logging enabled for 30 minutes.")
	} else {
		unschedule(debugLogOff)
		logInfo("updated: Debug logging is off.")
	}
	logInfo("updated: Description text logging is ${descriptionText}.")
	updateDataValue("driverVersion", driverVer())
	switch(refresh_Rate) {
		case "1" : runEvery1Minute(refresh); break
		case "5" : runEvery5Minutes(refresh); break
		case "10" : runEvery10Minutes("refresh"); break
		case "15" : runEvery15Minutes(refresh); break
		case "30" : runEvery30Minutes(refresh); break
		case "180": runEvery3Hours(refresh); break
		default:
			runEvery1Hour(refresh); break
	}

	//	Energy Monitor startup
	if (emFunction) {
		pauseExecution(1000)
		sendEvent(name: "power", value: 0, descriptionText: "Watts", unit: "W")
		schedule("0 01 0 * * ?", updateEmStats)
		runEvery30Minutes(getEnergyToday)
		runIn(1, getEnergyToday)
		runIn(2, updateEmStats)
		logInfo("updated: Energy Monitor Function enabled.")
	} else {
		refresh()
	}
}
def updateEmStats() {
	logDebug("updateEmStats: Updating daily energy monitor data.")
	def year = new Date().format("yyyy").toInteger()
	def command = """{"emeter":{"get_monthstat":{"year":${year}}}}"""
	sendCmd(command, "setThisMonth")
}

//	===== Device Command Methods =====
def on() {
	logDebug("on")
	def command
	if (emFunction) {
		command = """{"system":{"set_relay_state":{"state":1},""" +
			""""get_sysinfo":{}},""" +
			""""emeter":{"get_realtime":{}}}"""
	} else {
		command = """{"system":{"set_relay_state":{"state":1},""" +
			""""get_sysinfo":{}}}"""
	}
	sendCmd(command, "setSysInfo")
}

def off() {
	logDebug("off")
	def command
	if (emFunction) {
		command = """{"system":{"set_relay_state":{"state":0},""" +
			""""get_sysinfo":{}},""" +
			""""emeter":{"get_realtime":{}}}"""
	} else {
		command = """{"system":{"set_relay_state":{"state":0},""" +
			""""get_sysinfo":{}}}"""
	}
	sendCmd(command, "setSysInfo")
}

def refresh() {
	logDebug("refresh")
	if (pollTest) { logTrace("Poll Test.  Time = ${now()}") }
	def command
	if (emFunction) {
		command = """{"system":{"get_sysinfo":{}},""" +
			""""emeter":{"get_realtime":{}}}"""
	} else {
		command = """{"system":{"get_sysinfo":{}}}"""
	}
	sendCmd(command, "setSysInfo")
}

def setSysInfo(resp) {
	def status = resp.system.get_sysinfo
	logDebug("setSysInfo: status = ${status}")
	def onOff = "on"
	if (status.relay_state == 0) { onOff = "off" }
	if (onOff != device.currentValue("switch")) {
		sendEvent(name: "switch", value: onOff, type: "digital")
		logInfo("setSysInfo: switch: ${onOff}")
	}
	if (resp.emeter) { setPower(resp) }
}

//	===== Device Energy Monitor Methods =====
def getPower() {
	logDebug("getPower")
	def command = """{"emeter":{"get_realtime":{}}}"""
	sendCmd(command, "setPower")
}

def setPower(resp) {
	status = resp.emeter.get_realtime
	logDebug("setPower: status = ${status}")
	def power = status.power
	if (power == null) { power = status.power_mw / 1000 }
	power = (0.5 + Math.round(100*power)/100).toInteger()
	def curPwr = device.currentValue("power").toInteger()
	if (power > curPwr + 1 || power < curPwr - 1) { 
		sendEvent(name: "power", value: power, 
				  descriptionText: "Watts", unit: "W")
		logInfo("pollResp: power = ${power}")
	}
}

def getEnergyToday() {
	def year = new Date().format("yyyy").toInteger()
	def month = new Date().format("M").toInteger()
	def command = """{"emeter":{"get_daystat":{"month":${month},"year":${year}}}}"""
	sendCmd(command, "setEnergyToday")
}

def setEnergyToday(resp) {
	logDebug("setEnergyToday: ${resp}")
	def day = new Date().format("d").toInteger()
	def data = resp.day_list.find { it.day == day }
	def energyData = data.energy
	if (energyData == null) { energyData = data.energy_wh/1000 }
	energyData = Math.round(100*energyData)/100
	if (energyData != device.currentValue("energy")) {
		sendEvent(name: "energy",value: energyData, 
				  descriptionText: "KiloWatt Hours", unit: "kWH")
		logInfo("setEngrToday: [energy: ${energyData}]")
	}
}

def setThisMonth(resp) {
	logDebug("setThisMonth: ${resp}")
	def year = new Date().format("yyyy").toInteger()
	def month = new Date().format("M").toInteger()
	def day = new Date().format("d").toInteger()
	def data = resp.month_list.find { it.month == month }
	def scale = "energy"
	def energyData
	if (data == null) {
		energyData = 0
	} else {
		if (data.energy == null) { scale = "energy_wh" }
		energyData = data."${scale}"
	}
	def avgEnergy = 0
	if (day !=1) { avgEnergy = energyData/(day - 1) }
	if (scale == "energy_wh") {
		energyData = energyData/1000
		avgEnergy = avgEnergy/1000
	}
	energyData = Math.round(100*energyData)/100
	avgEnergy = Math.round(100*avgEnergy)/100
	sendEvent(name: "currMonthTotal", value: energyData, 
			  descriptionText: "KiloWatt Hours", unit: "KWH")
	sendEvent(name: "currMonthAvg", value: avgEnergy, 
			  descriptionText: "KiloWatt Hours per Day", unit: "KWH/D")
	logInfo("setThisMonth: Energy stats set to ${energyData} // ${avgEnergy}")
	if (month != 1) {
		setLastMonth(resp)
	} else {
		def command = """{"emeter":{"get_monthstat":{"year":${year-1}}}}"""
		sendCmd(command, "setLastMonth")
	}
}

def setLastMonth(resp) {
	logDebug("setLastMonth: cmdResponse = ${resp}")
	def year = new Date().format("yyyy").toInteger()
	def month = new Date().format("M").toInteger()
	def lastMonth = month - 1
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
			if (year == 2020 || year == 2024 || year == 2028) { monthLength = 29 }
			break
		default:
			monthLength = 31
	}
	def data = resp.month_list.find { it.month == lastMonth }
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
	sendEvent(name: "lastMonthTotal", value: energyData, 
			  descriptionText: "KiloWatt Hours", unit: "KWH")
	sendEvent(name: "lastMonthAvg", value: avgEnergy, 
			  descriptionText: "KiloWatt Hoursper Day", unit: "KWH/D")
	logInfo("setLastMonth: Energy stats set to ${energyData} // ${avgEnergy}")
	refresh()
}

//	===== Common Kasa Driver code =====
private sendCmd(command, action) {
	logDebug("sendCmd")
	def appServerUrl = getDataValue("appServerUrl")
	def deviceId = getDataValue("deviceId")
	def cmdResponse = parent.sendDeviceCmd(appServerUrl, deviceId, command)
	String cmdResp = cmdResponse.toString()
	if (cmdResp.substring(0,5) == "ERROR"){
		def errMsg = cmdResp.substring(7,cmdResp.length())
		log.error "${device.label}: ${errMsg}"
		sendEvent(name: "switch", value: "unavailable", descriptionText: errMsg)
		sendEvent(name: "deviceError", value: errMsg)
		action = ""
	} else {
		sendEvent(name: "deviceError", value: "OK")
	}
	if (action == "setSysInfo") { setSysInfo(cmdResponse) }
	else if (action == "setPower") { setPower(cmdResponse) }
	else if (action == "setEnergyToday") { setEnergyToday(cmdResponse) }
	else if (action == "setThisMonth") { setThisMonth(cmdResponse) }
	else if (action == "setLastMonth") { setLastMonth(cmdResponse) }
	else { logError("sendCmd: Error.  Invalid action") }
}

//	 ===== Logging =====
def logTrace(msg){ log.trace "Cloud ${device.label} ${msg}" }
def logInfo(msg) {
	if (descriptionText == true) { log.info "Cloud ${device.label} ${msg}" }
}
def debugLogOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("debugLogOff: Debug logging is off.")
}
def logDebug(msg){
	if(debug == true) { log.debug "Cloud ${device.label} ${msg}" }
}
def logWarn(msg){ log.warn "Cloud ${device.label} ${msg}" }