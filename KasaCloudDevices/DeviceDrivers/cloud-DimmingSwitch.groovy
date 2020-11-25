/*	Kasa Cloud Device Driver Series
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md

This is a special cloud version for devices that have had firmware updates that disabled port 9999.
===== 2020 History =====
11.27	1.0.0	Initial release of cloud version.

===================================================================================================*/
def driverVer() { return "1.0.0" }
metadata {
	definition (name: "Kasa Cloud Dimming Switch",
    			namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaCloudDevices/DeviceDrivers/cloud-DimmingSwitch.groovy"
			   ) {
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		capability "Switch Level"
		command "presetLevel",  ["NUMBER"]
		attribute "deviceError", "string"
	}

	preferences {
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

	refresh()
}

//	===== Device Command Methods =====
def on() {
	logDebug("on")
	def command = """{"system":{"set_relay_state":{"state":1},""" +
		""""get_sysinfo":{}}}"""
	sendCmd(command, "setSysInfo")
}

def off() {
	logDebug("off")
	def command = """{"system":{"set_relay_state":{"state":0},""" +
		""""get_sysinfo":{}}}"""
	sendCmd(command, "setSysInfo")
}

def setLevel(percentage, transition = null) {
	logDebug("setLevel: level = ${percentage}")
	percentage = percentage.toInteger()
	if (percentage < 0) { percentage = 0 }
	if (percentage > 100) { percentage = 100 }
	percentage = percentage.toInteger()
	def command = """{"smartlife.iot.dimmer":{"set_brightness":{"brightness":${percentage}}},""" +
		""""system":{"set_relay_state":{"state":1},"get_sysinfo":{}}}"""
	sendCmd(command, "setSysInfo")
}

def presetLevel(percentage) {
	logDebug("presetLevel: level = ${percentage}")
	percentage = percentage.toInteger()
	if (percentage < 0) { percentage = 0 }
	if (percentage > 100) { percentage = 100 }
	percentage = percentage.toInteger()
	def command = """{"smartlife.iot.dimmer":{"set_brightness":{"brightness":${percentage}}},""" +
		""""system" :{"get_sysinfo" :{}}}"""
	sendCmd(command, "setSysInfo")
}

def refresh() {
	logDebug("refresh")
	def command = """{"system":{"get_sysinfo":{}}}"""
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