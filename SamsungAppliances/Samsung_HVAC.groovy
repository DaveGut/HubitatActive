/*	Samsung HVAC using SmartThings Interface
		Copyright Dave Gutheinz
License Information:
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Description
This driver is for SmartThings-installed Samsung HVAC for import of control
and status of defined functions into Hubitat Environment.
=====	Library Use
This driver uses libraries for the functions common to SmartThings devices. 
Library code is at the bottom of the distributed single-file driver.
===== Installation Instructions Link =====
https://github.com/DaveGut/HubitatActive/blob/master/SamsungAppliances/Install_Samsung_Appliance.pdf
=====	Version B0.5
Beta release for further user testiong
a.	Update major commands, as follows:
	1.	setThermostatMode now has all Samsung HVAC modes plus mode samsungAuto.  samsungAuto mode
		is different than auto. auto mode emulates standard hubitat auto mode where the mode is auto
		controlled by temperature as it goes above "coolingSetpoint" and below "heatingSetpoint".
		samsungAuto uses the native HVAC auto mode with a single setpoint (samsungAutoSetpoint).
	2.	setCoolingSetpoint and setHeatingSetpoint.  When setting a calculation will be made to assue
		that colling setpoint > heatingSetpoint plus the preference "min Heating/Cooling delta".
		This precludes unsuccessful operation while in auto mode.
	3.	setSamsungAutoSetpoint.  Sets the single setpoint when you set the mode to the HVAC-
		internal auto mode.
	4.	setLight: new command to set the panel light on or off.
b.	Developed standard thermostat auto mode emulation.
c.	Developed methods to track and control the resultant thermostat modes and operating states.
==============================================================================*/
def driverVer() { return "1.0" }

metadata {
	definition (name: "Samsung HVAC",
				namespace: "davegut",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_HVAC.groovy"
			   ){
		capability "Refresh"
		capability "Thermostat"
		command "setThermostatMode", [[
			name: "Thermostat Mode",
			constraints: ["off", "auto", "cool", "heat", "dry", "wind", "samsungAuto"],
			type: "ENUM"]]
		command "emergencyHeat", [[name: "NOT IMPLEMENTED"]]
		command "samsungAuto"
		command "wind"
		command "dry"
		command "setThermostatFanMode", [[
			name: "Thermostat Fan Mode",
			constraints: ["auto", "low", "medium", "high"],
			type: "ENUM"]]
		command "fanLow"
		command "fanMedium"
		command "fanHigh"
		command "setSamsungAutoSetpoint", ["number"]
		attribute "samsungAutoSetpoint", "number"
		//	Set the light on the remote control.
		command "togglePanelLight"
		attribute "lightStatus", "string"
		command "setLevel", ["number"]		//	To set samsungAutoSetpoint via slider
		attribute "level", "number"			//	Reflects samsungAutoSetpoint

//		command "setTemperature", ["number"]
	}
	preferences {
		input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "")
		if (stApiKey) {
			input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "")
		}
		input ("tempOffset", "number", title: "Min Heat/Cool temperature delta",
				   defaultValue: 4)
		input ("pollInterval", "enum", title: "Poll Interval (minutes)",
			   options: ["1", "5", "10", "30"], defaultValue: "1")
		input ("debugLog", "bool",  
			   title: "Enable debug logging for 30 minutes", defaultValue: false)
	}
}

def installed() { }

def updated() {
	def commonStatus = commonUpdate()
	if (commonStatus.status == "FAILED") {
		logWarn("updated: ${commonStatus}")
	} else {
		logInfo("updated: ${commonStatus}")
	}
	deviceSetup()
}

def auto() { setThermostatMode("auto") }
def cool() { setThermostatMode("cool") }
def heat() { setThermostatMode("heat") }
def wind() { setThermostatMode("wind") }
def dry() { setThermostatMode("dry") }
def samsungAuto() { setThermostatMode("samsungAuto") }
def emergencyHeat() { logInfo("emergencyHeat: Not Available on this device") }
def off() { setThermostatMode("off") }
def setOn() {
	def cmdData = [
		component: "main",
		capability: "switch",
		command: "on",
		arguments: []]
	def cmdStatus = deviceCommand(cmdData)
	return cmdStatus
}
def setOff() {
	def cmdData = [
		component: "main",
		capability: "switch",
		command: "off",
		arguments: []]
	def cmdStatus = deviceCommand(cmdData)
	return cmdStatus
}
def setThermostatMode(thermostatMode) {
	def cmdStatus
	def prevMode = device.currentValue("thermostatMode")
	if (thermostatMode == "auto") {
		state.autoMode = true
		cmdStatus = [status: "OK", mode: "Auto Emulation"]
		poll()
	} else if (thermostatMode == "off") {
		state.autoMode = false
		cmdStatus = setOff()
	} else {
		state.autoMode = false
		if (thermostatMode == "samsungAuto") {
			thermostatMode = "auto"
		}
		cmdStatus = setOn()
		cmdStatus << sendModeCommand(thermostatMode)
	}
	logInfo("setThermostatMode: [cmd: ${thermostatMode}, ${cmdStatus}]")
}
def sendModeCommand(thermostatMode) {
	def cmdData = [
		component: "main",
		capability: "airConditionerMode",
		command: "setAirConditionerMode",
		arguments: [thermostatMode]]
	cmdStatus = deviceCommand(cmdData)
	return cmdStatus
}

def fanAuto() { setThermostatFanMode("auto") }
def fanCirculate() { setThermostatFanMode("medium") }
def fanOn() { setThermostatFanMode("medium") }
def fanLow() { setThermostatFanMode("low") }
def fanMedium() { setThermostatFanMode("medium") }
def fanHigh() { setThermostatFanMode("high") }
def setThermostatFanMode(fanMode) {
	def cmdData = [
		component: "main",
		capability: "airConditionerFanMode",
		command: "setFanMode",
		arguments: [fanMode]]
	def cmdStatus = deviceCommand(cmdData)
	logInfo("setThermostatFanMode: [cmd: ${fanMode}, ${cmdStatus}]")
}

def setHeatingSetpoint(setpoint) {
	if (setpoint < state.minSetpoint || setpoint > state.maxSetpoint) {
		logWarn("setHeatingSetpoint: Setpoint out of range")
		return
	}
	def logData = [:]
	def offset = tempOffset
	if (offset < 0) { offset = -offset }
	if (state.tempUnit == "°F") {
		setpoint = setpoint.toInteger()
		offset = offset.toInteger()		
	}

	if (device.currentValue("heatingSetpoint") != setpoint) {
		sendEvent(name: "heatingSetpoint", value: setpoint, unit: state.tempUnit)
		logData << [heatingSetpoint: setpoint]
	}

	def minSetpoint = setpoint + offset
	if (minSetpoint > device.currentValue("coolingSetpoint")) {
		sendEvent(name: "coolingSetpoint", value: minSetpoint, unit: state.tempUnit)
		logData << [coolingSetpoint: minSetpoint]
	}
	
	runIn(1, updateOperation)
	if (logData != [:]) {
		logInfo("setHeatingSetpoint: ${logData}")
	}
}
def setCoolingSetpoint(setpoint) {
	if (setpoint < state.minSetpoint || setpoint > state.maxSetpoint) {
		logWarn("setCoolingSetpoint: Setpoint out of range")
		return
	}
	def logData = [:]
	def offset = tempOffset
	if (offset < 0) { offset = -offset }
	if (state.tempUnit == "°F") {
		setpoint = setpoint.toInteger()
		offset = offset.toInteger()		
	}

	if (device.currentValue("coolingSetpoint") != setpoint) {
		sendEvent(name: "coolingSetpoint", value: setpoint, unit: state.tempUnit)
		logData << [coolingSetpoint: setpoint]
	}

	def maxSetpoint = setpoint - 4
	if (maxSetpoint < device.currentValue("heatingSetpoint")) {
		sendEvent(name: "heatingSetpoint", value: maxSetpoint, unit: state.tempUnit)
		logData << [heatingSetpoint: maxSetpoint]
	}
	
	runIn(1, updateOperation)
	if (logData != [:]) {
		logInfo("setCoolingSetpoint: ${logData}")
	}
}
def setSamsungAutoSetpoint(setpoint) {
	if (setpoint < state.minSetpoint || setpoint > state.maxSetpoint) {
		logWarn("setSamsungAutoSetpoint: Setpoint out of range")
		return
	}
	if (state.tempUnit == "°F") {
		setpoint = setpoint.toInteger()
	}
	def logData = [:]
	if (device.currentValue("samsungAutoSetpoint") != setpoint) {
		sendEvent(name: "samsungAutoSetpoint", value: setpoint, unit: state.tempUnit)
		sendEvent(name: "level", value: setpoint)
		logData << [samsungAutoSetpoint: setpoint]
		if (samsungAuto && device.currentValue("thermostatMode") == "auto") {
			setThermostatSetpoint(setpoint)
		}
	}
	
	runIn(1, updateOperation)
	if (logData != [:]) {
		logInfo("setSamsungAutoSetpoint: ${logData}")
	}
}
def setLevel(level) { setSamsungAutoSetpoint(level) }
def setThermostatSetpoint(setpoint) {
	def cmdData = [
		component: "main",
		capability: "thermostatCoolingSetpoint",
		command: "setCoolingSetpoint",
		arguments: [setpoint]]
	def cmdStatus = deviceCommand(cmdData)
	logInfo("setThermostatSetpoint: [cmd: ${setpoint}, ${cmdStatus}]")
}

def togglePanelLight() {
	def newOnOff = "on"
	if (device.currentValue("lightStatus") == "on") {
		newOnOff = "off"
	}
	def lightCmd = "Light_Off"
	if (newOnOff == "off") {
		lightCmd = "Light_On"
	}
	if (simulate == true) {
		state.light = ["Sleep_0", lightCmd, "Volume_Mute"]
	}
	def cmdString = """{"commands":[{"component": "main","capability": "execute",""" +
		   """"command": "execute","arguments": ["mode/vs/0",""" +
		   """{"x.com.samsung.da.options":["${lightCmd}"]}]}]}"""
	def respData = stringPostHttp(cmdString)
	logInfo("togglePanelLight [newOnOff: ${newOnOff}, cmd: ${lightCmd}, ${respData}]")
}

def stringPostHttp(cmdString) {
	def respData = [:]
	def sendCmdParams = [
			uri: "https://api.smartthings.com/v1",
			path: "/devices/${stDeviceId.trim()}/commands",
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()],
			body : cmdString]
	try {
		httpPost(sendCmdParams) {resp ->
			if (resp.status == 200 && resp.data != null) {
				respData << [status: "OK", results: resp.data.results]
				refresh()
			} else {
				respData << [status: "FAILED",
							 httpCode: resp.status,
							 errorMsg: resp.errorMessage]
			}
		}
	} catch (error) {
		respData << [status: "FAILED",
					 httpCode: "Timeout",
					 errorMsg: error]
	}
	return respData
}

def distResp(resp, data) {
	def respLog = [:]
	if (resp.status == 200) {
		try {
			def respData = new JsonSlurper().parseText(resp.data)
			if (data.reason == "deviceSetup") {
				deviceSetupParse(respData.components.main)
			}
			statusParse(respData.components.main)
		} catch (err) {
			respLog << [status: "ERROR",
						errorMsg: err,
						respData: resp.data]
		}
	} else {
		respLog << [status: "ERROR",
					httpCode: resp.status,
					errorMsg: resp.errorMessage]
	}
	if (respLog != [:]) {
//		logWarn("distResp: ${respLog}")
	}
}

def deviceSetupParse(parseData) {
	def logData = [:]
	tempUnit = parseData.temperatureMeasurement.temperature.unit
	state.tempUnit = "°${tempUnit}"
	logData << [tempUnit: tempUnit]

	def supportedThermostatModes = parseData.airConditionerMode.supportedAcModes.value
	supportedThermostatModes << "samsungAuto"
	supportedThermostatModes << "off"
	sendEvent(name: "supportedThermostatModes", value: supportedThermostatModes)
	logData << [supportedThermostatModes: supportedThermostatModes]

	def supportedThermostatFanModes = parseData.airConditionerFanMode.supportedAcFanModes.value
	sendEvent(name: "supportedThermostatFanModes", value: supportedThermostatFanModes)
	logData << [supportedThermostatFanModes: supportedThermostatFanModes]

	state.minSetpoint = parseData["custom.thermostatSetpointControl"].minimumSetpoint.value
	state.maxSetpoint = parseData["custom.thermostatSetpointControl"].maximumSetpoint.value 
	
	//	Initialize setpoints if required.
	def coolSetpoint = 76
	def heatSetpoint = 68
	def samsungAutoSetpoint = 72
	if (state.tempUnit == "°C") {
		coolSetpoint = 24
		heatSetpoint = 20
		samsungAutoSetpoint = 22
	}
	if (!device.currentValue("coolingSetpoint")) {
		sendEvent(name: "coolingSetpoint", value: coolSetpoint, unit: state.tempUnit)
	}
	if (!device.currentValue("heatingSetpoint")) {
		sendEvent(name: "heatingSetpoint", value: heatSetpoint, unit: state.tempUnit)
	}
	if (!device.currentValue("samsungAutoSetpoint")) {
		sendEvent(name: "samsungAutoSetpoint", value: samsungAutoSetpoint, unit: state.tempUnit)
	}
	logInfo("deviceSetupParse: ${logData}")
}

def statusParse(parseData) {
	def logData = [:]
	
	def temperature = parseData.temperatureMeasurement.temperature.value
	if (device.currentValue("temperature") != temperature) {
		sendEvent(name: "temperature", value: temperature, unit: tempUnit)
		logData << [temperature: temperature]
	}

	def thermostatSetpoint = parseData.thermostatCoolingSetpoint.coolingSetpoint.value
	if (device.currentValue("thermostatSetpoint") != thermostatSetpoint) {
		sendEvent(name: "thermostatSetpoint", value: thermostatSetpoint, unit: tempUnit)
		logData << [thermostatSetpoint: thermostatSetpoint]
	}

	def onOff = parseData.switch.switch.value
	def thermostatMode = parseData.airConditionerMode.airConditionerMode.value
	state.rawMode = thermostatMode
	if (state.autoMode) {
		thermostatMode = "auto"
	} else if (onOff == "off") {
		thermostatMode = "off"
	} else if (thermostatMode != "cool" && thermostatMode != "heat" &&
			   thermostatMode != "wind" && thermostatMode != "dry" &&
			   thermostatMode != "off") {
		thermostatMode = "samsungAuto"
	}
	if (device.currentValue("thermostatMode") != thermostatMode) {
		sendEvent(name: "thermostatMode", value: thermostatMode)
		logData << [thermostatMode: thermostatMode]
	}

	def thermostatFanMode = parseData.airConditionerFanMode.fanMode.value
	if (device.currentValue("thermostatFanMode") != thermostatFanMode) {
		sendEvent(name: "thermostatFanMode", value: thermostatFanMode)
		logData << [thermostatFanMode: thermostatFanMode]
	}
	
	def execStatus = parseData.execute.data.value.payload["x.com.samsung.da.options"]
	def lightStatus = "on"
	if (execStatus.contains("Light_On")) { lightStatus = "off" }
	if (device.currentValue("lightStatus") != lightStatus) {
		sendEvent(name: "lightStatus", value: lightStatus)
		logData << [lightStatus: lightStatus]
	}
	
	runIn(2, updateOperation)
	if (logData != [:]) { logInfo("statusParse: ${logData}") }
	runIn(4, listAttributes)
}

def updateOperation() {
	def respData = [:]
	def setpoint = device.currentValue("thermostatSetpoint")
	def temperature = device.currentValue("temperature")
	def heatPoint = device.currentValue("heatingSetpoint")
	def coolPoint = device.currentValue("coolingSetpoint")
	def samsungPoint = device.currentValue("samsungAutoSetpoint")
	def mode = device.currentValue("thermostatMode")
	def rawMode = state.rawMode
	def autoMode = state.autoMode

	if (state.autoMode) {
		def opMode
		if (temperature <= heatPoint) {
			opMode = "heat"
		} else if (temperature >= coolSetpoint) {
			opMode = "cool"
		}
		if (rawMode != opMode) {
			def cmdStatus = sendModeCommand(opMode)
			respData << [sendModeCommand: opMode]
			logInfo("updateOperation: ${respData}")
			return
		}
	}

	def newSetpoint = setpoint
	if (rawMode == "cool") {
		newSetpoint = coolPoint
	} else if (rawMode == "heat") {
		newSetpoint = heatPoint
	} else if (mode == "samsungAuto") {
		newSetpoint = samsungPoint
	}
	if (newSetpoint != setpoint) {
		setThermostatSetpoint(newSetpoint)
		respData << [thermostatSetpoint: newSetpoint]
		logInfo("updateOperation: ${respData}")
		return
	}
	
	def opState = "idle"
	if (mode == "off" || mode == "wind" || mode == "dry") {
		opState = mode
	} else if (mode == "samsungAuto") {
		if (temperature - setpoint > 1.5) {
			opState = "cooling"
		} else if (setpoint - temperature > 1.5) {
			opState = "heating"
		}
	} else if (rawMode == "cool") {
		if (temperature - setpoint > 0) {
			opState = "cooling"
		}
	} else if (rawMode == "heat") {
		if (setpoint - temperature > 0) {
			opState = "heating"
		}
	}
	if (device.currentValue("thermostatOperatingState") != opState) {
		sendEvent(name: "thermostatOperatingState", value: opState)
		respData << [thermostatOperatingState: opState]
		logInfo("updateOperation: ${respData}")
	}
}

//	===== Library Integration =====



def simulate() { return false }
//#include davegut.Samsung-AC-Sim

// ~~~~~ start include (1072) davegut.Logging ~~~~~
library ( // library marker davegut.Logging, line 1
	name: "Logging", // library marker davegut.Logging, line 2
	namespace: "davegut", // library marker davegut.Logging, line 3
	author: "Dave Gutheinz", // library marker davegut.Logging, line 4
	description: "Common Logging Methods", // library marker davegut.Logging, line 5
	category: "utilities", // library marker davegut.Logging, line 6
	documentationLink: "" // library marker davegut.Logging, line 7
) // library marker davegut.Logging, line 8

//	Logging during development // library marker davegut.Logging, line 10
def listAttributes(trace = false) { // library marker davegut.Logging, line 11
	def attrs = device.getSupportedAttributes() // library marker davegut.Logging, line 12
	def attrList = [:] // library marker davegut.Logging, line 13
	attrs.each { // library marker davegut.Logging, line 14
		def val = device.currentValue("${it}") // library marker davegut.Logging, line 15
		attrList << ["${it}": val] // library marker davegut.Logging, line 16
	} // library marker davegut.Logging, line 17
	if (trace == true) { // library marker davegut.Logging, line 18
		logTrace("Attributes: ${attrList}") // library marker davegut.Logging, line 19
	} else { // library marker davegut.Logging, line 20
		logDebug("Attributes: ${attrList}") // library marker davegut.Logging, line 21
	} // library marker davegut.Logging, line 22
} // library marker davegut.Logging, line 23

def logTrace(msg){ // library marker davegut.Logging, line 25
	log.trace "${device.displayName} ${driverVer()}: ${msg}" // library marker davegut.Logging, line 26
} // library marker davegut.Logging, line 27

def logInfo(msg) {  // library marker davegut.Logging, line 29
	if (infoLog == true) { // library marker davegut.Logging, line 30
		log.info "${device.displayName} ${driverVer()}: ${msg}" // library marker davegut.Logging, line 31
	} // library marker davegut.Logging, line 32
} // library marker davegut.Logging, line 33

def debugLogOff() { // library marker davegut.Logging, line 35
	if (debug == true) { // library marker davegut.Logging, line 36
		device.updateSetting("debug", [type:"bool", value: false]) // library marker davegut.Logging, line 37
	} else if (debugLog == true) { // library marker davegut.Logging, line 38
		device.updateSetting("debugLog", [type:"bool", value: false]) // library marker davegut.Logging, line 39
	} // library marker davegut.Logging, line 40
	logInfo("Debug logging is false.") // library marker davegut.Logging, line 41
} // library marker davegut.Logging, line 42

def logDebug(msg) { // library marker davegut.Logging, line 44
	if (debug == true || debugLog == true) { // library marker davegut.Logging, line 45
		log.debug "${device.displayName} ${driverVer()}: ${msg}" // library marker davegut.Logging, line 46
	} // library marker davegut.Logging, line 47
} // library marker davegut.Logging, line 48

def logWarn(msg) { log.warn "${device.displayName} ${driverVer()}: ${msg}" } // library marker davegut.Logging, line 50

// ~~~~~ end include (1072) davegut.Logging ~~~~~

// ~~~~~ start include (1091) davegut.ST-Communications ~~~~~
library ( // library marker davegut.ST-Communications, line 1
	name: "ST-Communications", // library marker davegut.ST-Communications, line 2
	namespace: "davegut", // library marker davegut.ST-Communications, line 3
	author: "Dave Gutheinz", // library marker davegut.ST-Communications, line 4
	description: "ST Communications Methods", // library marker davegut.ST-Communications, line 5
	category: "utilities", // library marker davegut.ST-Communications, line 6
	documentationLink: "" // library marker davegut.ST-Communications, line 7
) // library marker davegut.ST-Communications, line 8
import groovy.json.JsonSlurper // library marker davegut.ST-Communications, line 9

private asyncGet(sendData, passData = "none") { // library marker davegut.ST-Communications, line 11
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 12
		logWarn("asyncGet: [status: ERROR, errorMsg: no stApiKey]") // library marker davegut.ST-Communications, line 13
	} else { // library marker davegut.ST-Communications, line 14
		logDebug("asyncGet: ${sendData}, ${passData}") // library marker davegut.ST-Communications, line 15
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 16
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 17
			path: sendData.path, // library marker davegut.ST-Communications, line 18
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()]] // library marker davegut.ST-Communications, line 19
		try { // library marker davegut.ST-Communications, line 20
			asynchttpGet(sendData.parse, sendCmdParams, [reason: passData]) // library marker davegut.ST-Communications, line 21
		} catch (error) { // library marker davegut.ST-Communications, line 22
			logWarn("asyncGet: [status: FAILED, errorMsg: ${error}]") // library marker davegut.ST-Communications, line 23
		} // library marker davegut.ST-Communications, line 24
	} // library marker davegut.ST-Communications, line 25
} // library marker davegut.ST-Communications, line 26

private syncGet(path){ // library marker davegut.ST-Communications, line 28
	def respData = [:] // library marker davegut.ST-Communications, line 29
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 30
		respData << [status: "FAILED", // library marker davegut.ST-Communications, line 31
					 errorMsg: "No stApiKey"] // library marker davegut.ST-Communications, line 32
	} else { // library marker davegut.ST-Communications, line 33
		logDebug("syncGet: ${sendData}") // library marker davegut.ST-Communications, line 34
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 35
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 36
			path: path, // library marker davegut.ST-Communications, line 37
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()] // library marker davegut.ST-Communications, line 38
		] // library marker davegut.ST-Communications, line 39
		try { // library marker davegut.ST-Communications, line 40
			httpGet(sendCmdParams) {resp -> // library marker davegut.ST-Communications, line 41
				if (resp.status == 200 && resp.data != null) { // library marker davegut.ST-Communications, line 42
					respData << [status: "OK", results: resp.data] // library marker davegut.ST-Communications, line 43
				} else { // library marker davegut.ST-Communications, line 44
					respData << [status: "FAILED", // library marker davegut.ST-Communications, line 45
								 httpCode: resp.status, // library marker davegut.ST-Communications, line 46
								 errorMsg: resp.errorMessage] // library marker davegut.ST-Communications, line 47
				} // library marker davegut.ST-Communications, line 48
			} // library marker davegut.ST-Communications, line 49
		} catch (error) { // library marker davegut.ST-Communications, line 50
			respData << [status: "FAILED", // library marker davegut.ST-Communications, line 51
						 httpCode: "Timeout", // library marker davegut.ST-Communications, line 52
						 errorMsg: error] // library marker davegut.ST-Communications, line 53
		} // library marker davegut.ST-Communications, line 54
	} // library marker davegut.ST-Communications, line 55
	return respData // library marker davegut.ST-Communications, line 56
} // library marker davegut.ST-Communications, line 57

private syncPost(sendData){ // library marker davegut.ST-Communications, line 59
	def respData = [:] // library marker davegut.ST-Communications, line 60
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 61
		respData << [status: "FAILED", // library marker davegut.ST-Communications, line 62
					 errorMsg: "No stApiKey"] // library marker davegut.ST-Communications, line 63
	} else { // library marker davegut.ST-Communications, line 64
		logDebug("syncPost: ${sendData}") // library marker davegut.ST-Communications, line 65

		def cmdBody = [commands: [sendData.cmdData]] // library marker davegut.ST-Communications, line 67
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 68
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 69
			path: sendData.path, // library marker davegut.ST-Communications, line 70
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()], // library marker davegut.ST-Communications, line 71
			body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.ST-Communications, line 72
		] // library marker davegut.ST-Communications, line 73
		try { // library marker davegut.ST-Communications, line 74
			httpPost(sendCmdParams) {resp -> // library marker davegut.ST-Communications, line 75
				if (resp.status == 200 && resp.data != null) { // library marker davegut.ST-Communications, line 76
					respData << [status: "OK", results: resp.data.results] // library marker davegut.ST-Communications, line 77
				} else { // library marker davegut.ST-Communications, line 78
					respData << [status: "FAILED", // library marker davegut.ST-Communications, line 79
								 httpCode: resp.status, // library marker davegut.ST-Communications, line 80
								 errorMsg: resp.errorMessage] // library marker davegut.ST-Communications, line 81
				} // library marker davegut.ST-Communications, line 82
			} // library marker davegut.ST-Communications, line 83
		} catch (error) { // library marker davegut.ST-Communications, line 84
			respData << [status: "FAILED", // library marker davegut.ST-Communications, line 85
						 httpCode: "Timeout", // library marker davegut.ST-Communications, line 86
						 errorMsg: error] // library marker davegut.ST-Communications, line 87
		} // library marker davegut.ST-Communications, line 88
	} // library marker davegut.ST-Communications, line 89
	return respData // library marker davegut.ST-Communications, line 90
} // library marker davegut.ST-Communications, line 91

// ~~~~~ end include (1091) davegut.ST-Communications ~~~~~

// ~~~~~ start include (1090) davegut.ST-Common ~~~~~
library ( // library marker davegut.ST-Common, line 1
	name: "ST-Common", // library marker davegut.ST-Common, line 2
	namespace: "davegut", // library marker davegut.ST-Common, line 3
	author: "Dave Gutheinz", // library marker davegut.ST-Common, line 4
	description: "ST Wash/Dryer Common Methods", // library marker davegut.ST-Common, line 5
	category: "utilities", // library marker davegut.ST-Common, line 6
	documentationLink: "" // library marker davegut.ST-Common, line 7
) // library marker davegut.ST-Common, line 8

def commonUpdate() { // library marker davegut.ST-Common, line 10
	if (!stApiKey || stApiKey == "") { // library marker davegut.ST-Common, line 11
		return [status: "FAILED", reason: "No stApiKey"] // library marker davegut.ST-Common, line 12
	} // library marker davegut.ST-Common, line 13
	if (!stDeviceId || stDeviceId == "") { // library marker davegut.ST-Common, line 14
		getDeviceList() // library marker davegut.ST-Common, line 15
		return [status: "FAILED", reason: "No stDeviceId"] // library marker davegut.ST-Common, line 16
	} // library marker davegut.ST-Common, line 17

	unschedule() // library marker davegut.ST-Common, line 19
	def updateData = [:] // library marker davegut.ST-Common, line 20
	updateData << [status: "OK"] // library marker davegut.ST-Common, line 21
	if (debugLog) { runIn(1800, debugLogOff) } // library marker davegut.ST-Common, line 22
	updateData << [stDeviceId: stDeviceId] // library marker davegut.ST-Common, line 23
	updateData << [debugLog: debugLog, infoLog: infoLog] // library marker davegut.ST-Common, line 24
	if (!getDataValue("driverVersion") ||  // library marker davegut.ST-Common, line 25
		getDataValue("driverVersion") != driverVer()) { // library marker davegut.ST-Common, line 26
		updateDataValue("driverVersion", driverVer()) // library marker davegut.ST-Common, line 27
		updateData << [driverVer: driverVer()] // library marker davegut.ST-Common, line 28
	} // library marker davegut.ST-Common, line 29
	setPollInterval(pollInterval) // library marker davegut.ST-Common, line 30
	updateData << [pollInterval: pollInterval] // library marker davegut.ST-Common, line 31

	runIn(5, refresh) // library marker davegut.ST-Common, line 33
	return updateData // library marker davegut.ST-Common, line 34
} // library marker davegut.ST-Common, line 35

def setPollInterval(pollInterval) { // library marker davegut.ST-Common, line 37
	logDebug("setPollInterval: ${pollInterval}") // library marker davegut.ST-Common, line 38
	state.pollInterval = pollInterval // library marker davegut.ST-Common, line 39
	switch(pollInterval) { // library marker davegut.ST-Common, line 40
		case "1" : runEvery1Minute(poll); break // library marker davegut.ST-Common, line 41
		case "5" : runEvery5Minutes(poll); break // library marker davegut.ST-Common, line 42
		case "10" : runEvery10Minutes(poll); break // library marker davegut.ST-Common, line 43
		case "30" : runEvery30Minutes(poll); break // library marker davegut.ST-Common, line 44
		default: runEvery10Minutes(poll) // library marker davegut.ST-Common, line 45
	} // library marker davegut.ST-Common, line 46
} // library marker davegut.ST-Common, line 47

def deviceCommand(cmdData) { // library marker davegut.ST-Common, line 49
	def respData = [:] // library marker davegut.ST-Common, line 50
	if (simulate() == true) { // library marker davegut.ST-Common, line 51
		respData = testResp(cmdData) // library marker davegut.ST-Common, line 52
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 53
		respData << "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 54
	} else { // library marker davegut.ST-Common, line 55
		def sendData = [ // library marker davegut.ST-Common, line 56
			path: "/devices/${stDeviceId.trim()}/commands", // library marker davegut.ST-Common, line 57
			cmdData: cmdData // library marker davegut.ST-Common, line 58
		] // library marker davegut.ST-Common, line 59
		respData = syncPost(sendData) // library marker davegut.ST-Common, line 60
	} // library marker davegut.ST-Common, line 61
	if (cmdData.capability && cmdData.capability != "refresh") { // library marker davegut.ST-Common, line 62
		refresh() // library marker davegut.ST-Common, line 63
	} else { // library marker davegut.ST-Common, line 64
		poll() // library marker davegut.ST-Common, line 65
	} // library marker davegut.ST-Common, line 66
	return respData // library marker davegut.ST-Common, line 67
} // library marker davegut.ST-Common, line 68

def refresh() {  // library marker davegut.ST-Common, line 70
	def cmdData = [ // library marker davegut.ST-Common, line 71
		component: "main", // library marker davegut.ST-Common, line 72
		capability: "refresh", // library marker davegut.ST-Common, line 73
		command: "refresh", // library marker davegut.ST-Common, line 74
		arguments: []] // library marker davegut.ST-Common, line 75
	deviceCommand(cmdData) // library marker davegut.ST-Common, line 76
} // library marker davegut.ST-Common, line 77

def poll() { // library marker davegut.ST-Common, line 79
	if (simulate() == true) { // library marker davegut.ST-Common, line 80
		def children = getChildDevices() // library marker davegut.ST-Common, line 81
		if (children) { // library marker davegut.ST-Common, line 82
			children.each { // library marker davegut.ST-Common, line 83
				it.statusParse(testData()) // library marker davegut.ST-Common, line 84
			} // library marker davegut.ST-Common, line 85
		} // library marker davegut.ST-Common, line 86
		statusParse(testData()) // library marker davegut.ST-Common, line 87
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 88
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 89
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 90
	} else { // library marker davegut.ST-Common, line 91
		def sendData = [ // library marker davegut.ST-Common, line 92
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.ST-Common, line 93
			parse: "distResp" // library marker davegut.ST-Common, line 94
			] // library marker davegut.ST-Common, line 95
		asyncGet(sendData, "statusParse") // library marker davegut.ST-Common, line 96
	} // library marker davegut.ST-Common, line 97
} // library marker davegut.ST-Common, line 98

def deviceSetup() { // library marker davegut.ST-Common, line 100
	if (simulate() == true) { // library marker davegut.ST-Common, line 101
		def children = getChildDevices() // library marker davegut.ST-Common, line 102
		deviceSetupParse(testData()) // library marker davegut.ST-Common, line 103
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 104
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 105
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 106
	} else { // library marker davegut.ST-Common, line 107
		def sendData = [ // library marker davegut.ST-Common, line 108
			path: "/devices/${stDeviceId.trim()}/status", // library marker davegut.ST-Common, line 109
			parse: "distResp" // library marker davegut.ST-Common, line 110
			] // library marker davegut.ST-Common, line 111
		asyncGet(sendData, "deviceSetup") // library marker davegut.ST-Common, line 112
	} // library marker davegut.ST-Common, line 113
} // library marker davegut.ST-Common, line 114

def getDeviceList() { // library marker davegut.ST-Common, line 116
	def sendData = [ // library marker davegut.ST-Common, line 117
		path: "/devices", // library marker davegut.ST-Common, line 118
		parse: "getDeviceListParse" // library marker davegut.ST-Common, line 119
		] // library marker davegut.ST-Common, line 120
	asyncGet(sendData) // library marker davegut.ST-Common, line 121
} // library marker davegut.ST-Common, line 122

def getDeviceListParse(resp, data) { // library marker davegut.ST-Common, line 124
	def respData // library marker davegut.ST-Common, line 125
	if (resp.status != 200) { // library marker davegut.ST-Common, line 126
		respData = [status: "ERROR", // library marker davegut.ST-Common, line 127
					httpCode: resp.status, // library marker davegut.ST-Common, line 128
					errorMsg: resp.errorMessage] // library marker davegut.ST-Common, line 129
	} else { // library marker davegut.ST-Common, line 130
		try { // library marker davegut.ST-Common, line 131
			respData = new JsonSlurper().parseText(resp.data) // library marker davegut.ST-Common, line 132
		} catch (err) { // library marker davegut.ST-Common, line 133
			respData = [status: "ERROR", // library marker davegut.ST-Common, line 134
						errorMsg: err, // library marker davegut.ST-Common, line 135
						respData: resp.data] // library marker davegut.ST-Common, line 136
		} // library marker davegut.ST-Common, line 137
	} // library marker davegut.ST-Common, line 138
	if (respData.status == "ERROR") { // library marker davegut.ST-Common, line 139
		logWarn("getDeviceListParse: ${respData}") // library marker davegut.ST-Common, line 140
	} else { // library marker davegut.ST-Common, line 141
		log.info "" // library marker davegut.ST-Common, line 142
		respData.items.each { // library marker davegut.ST-Common, line 143
			log.trace "${it.label}:   ${it.deviceId}" // library marker davegut.ST-Common, line 144
		} // library marker davegut.ST-Common, line 145
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>" // library marker davegut.ST-Common, line 146
	} // library marker davegut.ST-Common, line 147
} // library marker davegut.ST-Common, line 148

def calcTimeRemaining(completionTime) { // library marker davegut.ST-Common, line 150
	Integer currTime = now() // library marker davegut.ST-Common, line 151
	Integer compTime // library marker davegut.ST-Common, line 152
	try { // library marker davegut.ST-Common, line 153
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 154
	} catch (e) { // library marker davegut.ST-Common, line 155
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 156
	} // library marker davegut.ST-Common, line 157
	Integer timeRemaining = ((compTime-currTime) /1000).toInteger() // library marker davegut.ST-Common, line 158
	if (timeRemaining < 0) { timeRemaining = 0 } // library marker davegut.ST-Common, line 159
	return timeRemaining // library marker davegut.ST-Common, line 160
} // library marker davegut.ST-Common, line 161

// ~~~~~ end include (1090) davegut.ST-Common ~~~~~
