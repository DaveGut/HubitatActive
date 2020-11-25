/*	Kasa Cloud Device Driver Series
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md

This is a special cloud version for devices that have had firmware updates that disabled port 9999.
===== 2020 History =====
11.27	1.0.0	Initial release of cloud version.

===================================================================================================*/
def driverVer() { return "1.0.0" }
metadata {
	definition (name: "Kasa Cloud Mono Bulb",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaCloudDevices/DeviceDrivers/cloud-WhiteBulb.groovy"
			   ) {
        capability "Light"
		capability "Switch"
		capability "Switch Level"
		capability "Change Level"
 		capability "Refresh"
		capability "Actuator"
	}
	preferences {
		input ("transition_Time", "num", 
			   title: "Default Transition time (seconds)", 
			   defaultValue: 0)
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
	state.transTime = 1000*transition_Time.toInteger()
	logInfo("updated: Light transition time set to ${transition_Time} seconds.")
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

	refresh()
}

//	===== Command Methods =====
def on() {
	logDebug("on: transition time = ${state.transTime}")
	def command = """{"smartlife.iot.smartbulb.lightingservice":""" +
		"""{"transition_light_state":{"on_off":1,"transition_period":${state.transTime}}}}"""
	sendCmd(command, "setBulbData")
}

def off() {
	logDebug("off: transition time = ${state.transTime}")
	def command = """{"smartlife.iot.smartbulb.lightingservice":""" +
		"""{"transition_light_state":{"on_off":0,"transition_period":${state.transTime}}}}"""
	sendCmd(command, "setBulbData")
}

def setLevel(percentage, rate = null) {
	logDebug("setLevel(x,x): rate = ${rate} // percentage = ${percentage}")
	if (percentage < 0) { percentage = 0 }
	else if (percentage > 100) { percentage = 100 }
	if (rate == null) {
		rate = state.transTime.toInteger()
	} else {
		rate = 1000*rate.toInteger()
	}
	def command = """{"smartlife.iot.smartbulb.lightingservice":""" +
		"""{"transition_light_state":{"ignore_default":1,"on_off":1,""" +
		""""brightness":${percentage},"transition_period":${rate}}}}"""
	sendCmd(command, "setBulbData")
}

def startLevelChange(direction) {
	logDebug("startLevelChange: direction = ${direction}")
	if (direction == "up") { levelUp() }
	else { levelDown() }
}

def stopLevelChange() {
	logDebug("stopLevelChange")
	unschedule(levelUp)
	unschedule(levelDown)
}

def levelUp() {
	def curLevel = device.currentValue("level").toInteger()
	if (curLevel == 100) { return }
	def newLevel = curLevel + 4
	if (newLevel > 100) { newLevel = 100 }
	setLevel(newLevel, 0)
	runIn(1, levelUp)
}

def levelDown() {
	def curLevel = device.currentValue("level").toInteger()
	if (curLevel == 0) { return }
	def newLevel = curLevel - 4
	if (newLevel < 0) { newLevel = 0 }
	setLevel(newLevel, 0)
	if (newLevel == 0) { off() }
	runIn(1, levelDown)
}

def refresh(){
	logDebug("refresh")
	def command = """{"system":{"get_sysinfo":{}}}"""
	sendCmd(command, "setBulbData")
}

def setBulbData(resp) {
	def status
	if (resp["smartlife.iot.smartbulb.lightingservice"]) {
		status = resp["smartlife.iot.smartbulb.lightingservice"].transition_light_state
	} else {
		status = resp.system.get_sysinfo.light_state
	}
	logDebug("setBulbData: ${status}")
	def deviceStatus = [:]
	def type = getDataValue("devType")
	if (status.on_off == 0) { 
		sendEvent(name: "switch", value: "off", type: "digital")
		sendEvent(name: "circadianState", value: "normal")
		deviceStatus << ["power" : "off"]
	} else {
		sendEvent(name: "switch", value: "on", type: "digital")
		deviceStatus << ["power" : "on"]
		sendEvent(name: "level", value: status.brightness, unit: "%")
		deviceStatus << ["level" : status.brightness]
	}
	logInfo("setBulbData: Status = ${deviceStatus}")
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
	if (action == "setBulbData") { setBulbData(cmdResponse) }
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