/*	Kasa Device Driver Series
		Copyright Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Link to list of changes =====
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Changes.pdf
===== Link to Documentation =====
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Documentation.pdf
===================================================================================================*/
def driverVer() { return "6.6.0" }
def type() { return "Dimming Switch" }

metadata {
	definition (name: "Kasa ${type()}",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/DimmingSwitch.groovy"
			   ) {
		capability "Configuration"
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		capability "Switch Level"
		capability "Level Preset"
		capability "Change Level"
		command "ledOn"
		command "ledOff"
		attribute "led", "string"
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["default", "5 seconds", "10 seconds", "15 seconds",
						  "30 seconds", "1 minute", "5 minutes",  "10 minutes",
						  "30 minutes"],
			type: "ENUM"]]
		attribute "connection", "string"
		attribute "commsError", "string"
	}
	preferences {
		input ("infoLog", "bool", 
			   title: "Enable information logging " + helpLogo(),
			   defaultValue: true)
		input ("debug", "bool",
			   title: "30 minutes of debug logging", 
			   defaultValue: false)
		input ("gentleOn", "number",
			   title: "Gentle On (max 7000 msec)",
			   defaultValue:5000,
			   range: 0 .. 7100)
		input ("gentleOff", "number",
			   title: "Gentle On (max 7000 msec)",
			   defaultValue:5000,
			   range: 0 .. 7100)
		def fadeOpts = [0: "Instant",  1000: "Fast",
						2000: "Medium", 3000: "Slow"]
		input ("fadeOn", "enum",
			   title: "Fade On",
			   defaultValue:"Fast",
			   options: fadeOpts)
		input ("fadeOff", "enum",
			   title: "Fade Off",
			   defaultValue:"Fast",
			   options: fadeOpts)
		def pressOpts = ["none",  "instant_on_off", "gentle_on_off",
						 "Preset 0", "Preset 1", "Preset 2", "Preset 3"]
		input ("longPress", "enum", title: "Long Press Action",
			   defaultValue: "gentle_on_off",
			   options: pressOpts)
		input ("doubleClick", "enum", title: "Double Tap Action",
			   defaultValue: "Preset 1",
			   options: pressOpts)
		input ("bind", "bool",
			   title: "Kasa Cloud Binding",
			   defalutValue: true)
		input ("useCloud", "bool",
			   title: "Use Kasa Cloud for device control",
			   defaultValue: false)
		input ("nameSync", "enum", title: "Synchronize Names",
			   defaultValue: "none",
			   options: ["none": "Don't synchronize",
						 "device" : "Kasa device name master", 
						 "Hubitat" : "Hubitat label master"])
		input ("rebootDev", "bool",
			   title: "Reboot device <b>[Caution]</b>",
			   defaultValue: false)
	}
}

def installed() {
	def instStatus = installCommon()
	pauseExecution(3000)
	getDimmerConfiguration()
	logInfo("installed: ${instStatus}")
}

def updated() {
	def updStatus = updateCommon()
	configureDimmer()
	logInfo("updated: ${updStatus}")
	refresh()
}	//	6.6.0

def configureDimmer() {
	logDebug("configureDimmer")
	if (longPress == null || doubleClick == null || gentleOn == null
	    || gentleOff == null || fadeOff == null || fadeOn == null) {
		def dimmerSet = getDimmerConfiguration()
		pauseExecution(2000)
	}
	sendCmd("""{"smartlife.iot.dimmer":{"set_gentle_on_time":{"duration": ${gentleOn}}, """ +
			""""set_gentle_off_time":{"duration": ${gentleOff}}, """ +
			""""set_fade_on_time":{"fadeTime": ${fadeOn}}, """ +
			""""set_fade_off_time":{"fadeTime": ${fadeOff}}}}""")
	pauseExecution(2000)

	def action1 = """{"mode":"${longPress}"}"""
	if (longPress.contains("Preset")) {
		action1 = """{"mode":"customize_preset","index":${longPress[-1].toInteger()}}"""
	}
	def action2 = """{"mode":"${doubleClick}"}"""
	if (doubleClick.contains("Preset")) {
		action2 = """{"mode":"customize_preset","index":${doubleClick[-1].toInteger()}}"""
	}
	sendCmd("""{"smartlife.iot.dimmer":{"set_double_click_action":${action2}, """ +
			""""set_long_press_action":${action1}}}""")

	runIn(1, getDimmerConfiguration)
}

def setDimmerConfig(response) {
	logDebug("setDimmerConfiguration: ${response}")
	def params
	def dimmerConfig = [:]
	if (response["get_dimmer_parameters"]) {
		params = response["get_dimmer_parameters"]
		if (params.err_code == "0") {
			logWarn("setDimmerConfig: Error in getDimmerParams: ${params}")
		} else {
			def fadeOn = getFade(params.fadeOnTime.toInteger())
			def fadeOff = getFade(params.fadeOffTime.toInteger())
			device.updateSetting("fadeOn", [type:"integer", value: fadeOn])
			device.updateSetting("fadeOff", [type:"integer", value: fadeOff])
			device.updateSetting("gentleOn", [type:"integer", value: params.gentleOnTime])
			device.updateSetting("gentleOff", [type:"integer", value: params.gentleOffTime])
			dimmerConfig << [fadeOn: fadeOn, fadeOff: fadeOff,
							 genleOn: gentleOn, gentleOff: gentleOff]
		}
	}
	if (response["get_default_behavior"]) {
		params = response["get_default_behavior"]
		if (params.err_code == "0") {
			logWarn("setDimmerConfig: Error in getDefaultBehavior: ${params}")
		} else {
			def longPress = params.long_press.mode
			if (params.long_press.index != null) { longPress = "Preset ${params.long_press.index}" }
			device.updateSetting("longPress", [type:"enum", value: longPress])
			def doubleClick = params.double_click.mode
			if (params.double_click.index != null) { doubleClick = "Preset ${params.double_click.index}" }
			device.updateSetting("doubleClick", [type:"enum", value: doubleClick])
			dimmerConfig << [longPress: longPress, doubleClick: doubleClick]
		}
	}
	logInfo("setDimmerConfig: ${dimmerConfig}")
}

def getFade(fadeTime) {
	def fadeSpeed = "Instant"
	if (fadeTime == 1000) {
		fadeSpeed = "Fast"
	} else if (fadeTime == 2000) {
		fadeSpeed = "Medium"
	} else if (fadeTime == 3000) {
		fadeSpeed = "Slow"
	}
	return fadeSpeed
}	//	6.6.0 Updated fadeOn/Off parsing

//	==================================================
def setLevel(level, transTime = gentleOn) {
	setDimmerTransition(level, transTime)
	def updates = [:]
	updates << [switch: "on", level: level]
	sendEvent(name: "switch", value: "on", type: "digital")
	sendEvent(name: "level", value: level, type: "digital")
	logInfo("setLevel: ${updates}")
	runIn(9, getSysinfo)
}

def presetLevel(level) {
	presetBrightness(level)
}

def startLevelChange(direction) {
	logDebug("startLevelChange: [level: ${device.currentValue("level")}, direction: ${direction}]")
	if (device.currentValue("switch") == "off") {
		setRelayState(1)
		pauseExecution(1000)
	}
	if (direction == "up") { levelUp() }
	else { levelDown() }
}

def stopLevelChange() {
	logDebug("startLevelChange: [level: ${device.currentValue("level")}]")
	unschedule(levelUp)
	unschedule(levelDown)
}

def levelUp() {
	def curLevel = device.currentValue("level").toInteger()
	if (curLevel == 100) { return }
	def newLevel = curLevel + 4
	if (newLevel > 100) { newLevel = 100 }
	presetBrightness(newLevel)
	runIn(1, levelUp)
}

def levelDown() {
	def curLevel = device.currentValue("level").toInteger()
	if (curLevel == 0 || device.currentValue("switch") == "off") { return }
	def newLevel = curLevel - 4
	if (newLevel <= 0) { off() }
	else {
		presetBrightness(newLevel)
		runIn(1, levelDown)
	}
}

//	==================================================
def setSysInfo(status) {
	def updates = [:]
	def switchStatus = status.relay_state
	def ledStatus = status.led_off
	def onOff = "on"
	if (switchStatus == 0) { onOff = "off" }
	if (onOff != device.currentValue("switch")) {
		updates << [switch: onOff]
		//	5.2.3	sendEvent(name: "switch", value: onOff, type: "digital")
	}
	sendEvent(name: "switch", value: onOff, type: "digital")	//	5.2.3

	if (status.brightness != device.currentValue("level")) {
		updates << [level: status.brightness]
		sendEvent(name: "level", value: status.brightness, type: "digital")
	}
	def ledOnOff = "on"
	if (ledStatus == 1) { ledOnOff = "off" }
	if (ledOnOff != device.currentValue("led")) {
		updates << [led: ledOnOff]
		sendEvent(name: "led", value: ledOnOff)
	}
	if (updates != [:]) { logInfo("setSysinfo: ${updates}") }
	if (nameSync == "device") {
		updateName(status)
	}
}

//	==================================================
def checkTransTime(transTime) {
	if (transTime == null || transTime < 0) { transTime = 0 }
	transTime = 1000 * transTime.toInteger()
	if (transTime > 8000) { transTime = 8000 }
	return transTime
}

def checkLevel(level) {
	if (level == null || level < 0) {
		level = device.currentValue("level")
		logWarn("checkLevel: Entered level null or negative. Level set to ${level}")
	} else if (level > 100) {
		level = 100
		logWarn("checkLevel: Entered level > 100.  Level set to ${level}")
	}
	return level
}	//	6.6.0 Change null/<0 values to use currentValue("level")

def setDimmerTransition(level, transTime) {
	level = checkLevel(level)
	transTime = checkTransTime(transTime)
	logDebug("setDimmerTransition: [level: ${level}, transTime: ${transTime}]")
	if (level == 0) {
		setRelayState(0)
	} else {
		sendCmd("""{"smartlife.iot.dimmer":{"set_dimmer_transition":{"brightness":${level},""" +
				""""duration":${transTime}}}}""")
	}
}

def presetBrightness(level) {
	level = checkLevel(level)
	logDebug("presetLevel: [level: ${level}]")
	sendCmd("""{"smartlife.iot.dimmer":{"set_brightness":{"brightness":${level}}},""" +
			""""system" :{"get_sysinfo" :{}}}""")
}

def getDimmerConfiguration() {
	logDebug("getDimmerConfiguration")
	sendCmd("""{"smartlife.iot.dimmer":{"get_dimmer_parameters":{}, """ +
			""""get_default_behavior":{}}}""")
}

//	==================================================





// ~~~~~ start include (705) davegut.kasaCommon ~~~~~
library ( // library marker davegut.kasaCommon, line 1
	name: "kasaCommon", // library marker davegut.kasaCommon, line 2
	namespace: "davegut", // library marker davegut.kasaCommon, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaCommon, line 4
	description: "Kasa Device Common Methods", // library marker davegut.kasaCommon, line 5
	category: "utilities", // library marker davegut.kasaCommon, line 6
	documentationLink: "" // library marker davegut.kasaCommon, line 7
) // library marker davegut.kasaCommon, line 8

//	====== Common Install / Update Elements ===== // library marker davegut.kasaCommon, line 10
String helpLogo() { // library marker davegut.kasaCommon, line 11
	return """<a href="https://github.com/DaveGut/HubitatActive/blob/648a78b28b1cc02d48097e960c282702000fe6b6/KasaDevices/Documentation.pdf">""" + // library marker davegut.kasaCommon, line 12
		"""<div style="position: absolute; top: 10px; right: 10px; height: 80px; font-size: 15px;">Kasa Help</div></a>""" // library marker davegut.kasaCommon, line 13
} // library marker davegut.kasaCommon, line 14

def installCommon() { // library marker davegut.kasaCommon, line 16
	pauseExecution(3000) // library marker davegut.kasaCommon, line 17
	def instStatus = [:] // library marker davegut.kasaCommon, line 18
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 19
		sendEvent(name: "connection", value: "CLOUD") // library marker davegut.kasaCommon, line 20
		device.updateSetting("useCloud", [type:"bool", value: true]) // library marker davegut.kasaCommon, line 21
		instStatus << [useCloud: true, connection: "CLOUD"] // library marker davegut.kasaCommon, line 22
	} else { // library marker davegut.kasaCommon, line 23
		sendEvent(name: "connection", value: "LAN") // library marker davegut.kasaCommon, line 24
		device.updateSetting("useCloud", [type:"bool", value: false]) // library marker davegut.kasaCommon, line 25
		instStatus << [useCloud: false, connection: "LAN"] // library marker davegut.kasaCommon, line 26
	} // library marker davegut.kasaCommon, line 27
	sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommon, line 28
	state.errorCount = 0 // library marker davegut.kasaCommon, line 29
	state.pollInterval = "30 minutes" // library marker davegut.kasaCommon, line 30
	instStatus << [driverVersion: driverVer()] // library marker davegut.kasaCommon, line 31
	runIn(2, updated) // library marker davegut.kasaCommon, line 32
	return instStatus // library marker davegut.kasaCommon, line 33
} // library marker davegut.kasaCommon, line 34

def updateCommon() { // library marker davegut.kasaCommon, line 36
	unschedule() // library marker davegut.kasaCommon, line 37
	def updStatus = [:] // library marker davegut.kasaCommon, line 38
	if (rebootDev) { // library marker davegut.kasaCommon, line 39
		updStatus << [rebootDev: rebootDevice()] // library marker davegut.kasaCommon, line 40
		return updStatus // library marker davegut.kasaCommon, line 41
	} // library marker davegut.kasaCommon, line 42
	updStatus << [bind: bindUnbind()] // library marker davegut.kasaCommon, line 43
	if (nameSync != "none") { // library marker davegut.kasaCommon, line 44
		updStatus << [nameSync: syncName()] // library marker davegut.kasaCommon, line 45
	} // library marker davegut.kasaCommon, line 46
	if (debug) { runIn(1800, debugLogOff) } // library marker davegut.kasaCommon, line 47
	updStatus << [debug: debug] // library marker davegut.kasaCommon, line 48
	state.errorCount = 0 // library marker davegut.kasaCommon, line 49
	sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommon, line 50
	def pollInterval = state.pollInterval // library marker davegut.kasaCommon, line 51
	if (pollInterval == null) { pollInterval = "30 minutes" } // library marker davegut.kasaCommon, line 52
	updStatus << [pollInterval: setPollInterval(pollInterval)] // library marker davegut.kasaCommon, line 53
	if(getDataValue("driverVersion") != driverVer()){ // library marker davegut.kasaCommon, line 54
		updateDataValue("driverVersion", driverVer()) // library marker davegut.kasaCommon, line 55
		updStatus << [driverVersion: driverVer()] // library marker davegut.kasaCommon, line 56
		if (state.pollNote) { state.remove("pollNote") } // library marker davegut.kasaCommon, line 57
		if (state.pollWarning) { state.remove("pollWarning") } // library marker davegut.kasaCommon, line 58
		if (!infoLog || infoLog == null) { // library marker davegut.kasaCommon, line 59
			device.updateSetting("infoLog", [type:"bool", value: true]) // library marker davegut.kasaCommon, line 60
		} // library marker davegut.kasaCommon, line 61
	} // library marker davegut.kasaCommon, line 62
	if (emFunction) { // library marker davegut.kasaCommon, line 63
		schedule("10 0 0 * * ?", getEnergyThisMonth) // library marker davegut.kasaCommon, line 64
		schedule("15 2 0 1 * ?", getEnergyLastMonth) // library marker davegut.kasaCommon, line 65
		state.getEnergy = "This Month" // library marker davegut.kasaCommon, line 66
		updStatus << [emFunction: "scheduled"] // library marker davegut.kasaCommon, line 67
	} // library marker davegut.kasaCommon, line 68
	if (parent.configureEnabled == true) { // library marker davegut.kasaCommon, line 69
		updStatus << [parentConfig: parent.updateConfigurations()] // library marker davegut.kasaCommon, line 70
	} else { // library marker davegut.kasaCommon, line 71
		updStatus << [parentConfig: "notAvailable"] // library marker davegut.kasaCommon, line 72
	} // library marker davegut.kasaCommon, line 73
	return updStatus // library marker davegut.kasaCommon, line 74
}	//	6.6.0 // library marker davegut.kasaCommon, line 75

def childConfigure(updateData) { // library marker davegut.kasaCommon, line 77
	def confResp = [:] // library marker davegut.kasaCommon, line 78
	logDebug("childConfigure: ${updateData}") // library marker davegut.kasaCommon, line 79
	confResp << [ipUpdate: "complete"] // library marker davegut.kasaCommon, line 80
	if (driverVer().trim() != updateData.appVersion) { // library marker davegut.kasaCommon, line 81
		confResp << [driverAppVersion: "mismatched"] // library marker davegut.kasaCommon, line 82
		state.DRIVER_MISMATCH = "Driver version (${driverVer()}) not the same as App version (${updateData.appVersion})" // library marker davegut.kasaCommon, line 83
		logWarn("childConfigure: Current driver does not match with App Version.  Update to assure proper operation.") // library marker davegut.kasaCommon, line 84
	} else { // library marker davegut.kasaCommon, line 85
		confResp << [driverAppVersion: "matched"] // library marker davegut.kasaCommon, line 86
		state.remove("DRIVER_MISMATCH") // library marker davegut.kasaCommon, line 87
	} // library marker davegut.kasaCommon, line 88
	if (updateData.updateAvailable) { // library marker davegut.kasaCommon, line 89
		confResp << [driverUpdate: "available"] // library marker davegut.kasaCommon, line 90
		state.releaseNotes = "${updateData.releaseNotes}" // library marker davegut.kasaCommon, line 91
		if (updateData.releaseNotes.contains("CRITICAL")) { // library marker davegut.kasaCommon, line 92
			state.UPDATE_AVAILABLE = "A CRITICAL UPDATE TO APP AND DRIVER ARE AVAILABLE to version  ${updateData.currVersion}." // library marker davegut.kasaCommon, line 93
			logWarn("<b>A CRITICAL</b> Applications and Drivers update is available for the Kasa Integration") // library marker davegut.kasaCommon, line 94
		} else { // library marker davegut.kasaCommon, line 95
			state.UPDATE_AVAILABLE = "App and driver updates are available to version ${updateData.currVersion}.  Consider updating." // library marker davegut.kasaCommon, line 96
		} // library marker davegut.kasaCommon, line 97
	} else { // library marker davegut.kasaCommon, line 98
		confResp << [driverUpdate: "noneAvailable"] // library marker davegut.kasaCommon, line 99
		state.remove("UPDATE_AVAILABLE") // library marker davegut.kasaCommon, line 100
		state.remove("releaseNotes") // library marker davegut.kasaCommon, line 101
	} // library marker davegut.kasaCommon, line 102
	return confResp // library marker davegut.kasaCommon, line 103
}	//	6.6.0 // library marker davegut.kasaCommon, line 104

//	===== Poll/Refresh ===== // library marker davegut.kasaCommon, line 106
def refresh() { poll() } // library marker davegut.kasaCommon, line 107

def poll() { getSysinfo() } // library marker davegut.kasaCommon, line 109

//	===== Preference Methods ===== // library marker davegut.kasaCommon, line 111
def setPollInterval(interval = state.pollInterval) { // library marker davegut.kasaCommon, line 112
	if (interval == "default" || interval == "off" || interval == null) { // library marker davegut.kasaCommon, line 113
		interval = "30 minutes" // library marker davegut.kasaCommon, line 114
	} else if (useCloud && interval.contains("sec")) { // library marker davegut.kasaCommon, line 115
		interval = "1 minute" // library marker davegut.kasaCommon, line 116
	} // library marker davegut.kasaCommon, line 117
	state.pollInterval = interval // library marker davegut.kasaCommon, line 118
	def pollInterval = interval.substring(0,2).toInteger() // library marker davegut.kasaCommon, line 119
	if (interval.contains("sec")) { // library marker davegut.kasaCommon, line 120
		def start = Math.round((pollInterval-1) * Math.random()).toInteger() // library marker davegut.kasaCommon, line 121
		schedule("${start}/${pollInterval} * * * * ?", "poll") // library marker davegut.kasaCommon, line 122
		logWarn("setPollInterval: Polling intervals of less than one minute " + // library marker davegut.kasaCommon, line 123
				"can take high resources and may impact hub performance.") // library marker davegut.kasaCommon, line 124
	} else { // library marker davegut.kasaCommon, line 125
		def start = Math.round(59 * Math.random()).toInteger() // library marker davegut.kasaCommon, line 126
		schedule("${start} */${pollInterval} * * * ?", "poll") // library marker davegut.kasaCommon, line 127
	} // library marker davegut.kasaCommon, line 128
	logDebug("setPollInterval: interval = ${interval}.") // library marker davegut.kasaCommon, line 129
	return interval // library marker davegut.kasaCommon, line 130
} // library marker davegut.kasaCommon, line 131

def rebootDevice() { // library marker davegut.kasaCommon, line 133
	device.updateSetting("rebootDev", [type:"bool", value: false]) // library marker davegut.kasaCommon, line 134
	reboot() // library marker davegut.kasaCommon, line 135
	pauseExecution(10000) // library marker davegut.kasaCommon, line 136
	return "REBOOTING DEVICE" // library marker davegut.kasaCommon, line 137
} // library marker davegut.kasaCommon, line 138

def bindUnbind() { // library marker davegut.kasaCommon, line 140
	def message // library marker davegut.kasaCommon, line 141
	if (bind == null || // library marker davegut.kasaCommon, line 142
	    getDataValue("deviceIP") == "CLOUD" || // library marker davegut.kasaCommon, line 143
	    type() == "Light Strip") { // library marker davegut.kasaCommon, line 144
		message = "Getting current bind state" // library marker davegut.kasaCommon, line 145
		getBind() // library marker davegut.kasaCommon, line 146
	} else if (bind == true) { // library marker davegut.kasaCommon, line 147
		if (!parent.kasaToken || parent.userName == null || parent.userPassword == null) { // library marker davegut.kasaCommon, line 148
			message = "Username/pwd not set." // library marker davegut.kasaCommon, line 149
			getBind() // library marker davegut.kasaCommon, line 150
		} else { // library marker davegut.kasaCommon, line 151
			message = "Binding device to the Kasa Cloud." // library marker davegut.kasaCommon, line 152
			setBind(parent.userName, parent.userPassword) // library marker davegut.kasaCommon, line 153
		} // library marker davegut.kasaCommon, line 154
	} else if (bind == false) { // library marker davegut.kasaCommon, line 155
		message = "Unbinding device from the Kasa Cloud." // library marker davegut.kasaCommon, line 156
		setUnbind() // library marker davegut.kasaCommon, line 157
	} // library marker davegut.kasaCommon, line 158
	pauseExecution(5000) // library marker davegut.kasaCommon, line 159
	return message // library marker davegut.kasaCommon, line 160
} // library marker davegut.kasaCommon, line 161

def setBindUnbind(cmdResp) { // library marker davegut.kasaCommon, line 163
	def bindState = true // library marker davegut.kasaCommon, line 164
	if (cmdResp.get_info) { // library marker davegut.kasaCommon, line 165
		if (cmdResp.get_info.binded == 0) { bindState = false } // library marker davegut.kasaCommon, line 166
		logInfo("setBindUnbind: Bind status set to ${bindState}") // library marker davegut.kasaCommon, line 167
		setCommsType(bindState) // library marker davegut.kasaCommon, line 168
	} else if (cmdResp.bind.err_code == 0){ // library marker davegut.kasaCommon, line 169
		getBind() // library marker davegut.kasaCommon, line 170
	} else { // library marker davegut.kasaCommon, line 171
		logWarn("setBindUnbind: Unhandled response: ${cmdResp}") // library marker davegut.kasaCommon, line 172
	} // library marker davegut.kasaCommon, line 173
} // library marker davegut.kasaCommon, line 174

def setCommsType(bindState) { // library marker davegut.kasaCommon, line 176
	def commsType = "LAN" // library marker davegut.kasaCommon, line 177
	def cloudCtrl = false // library marker davegut.kasaCommon, line 178
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 179
		commsType = "CLOUD" // library marker davegut.kasaCommon, line 180
		cloudCtrl = true // library marker davegut.kasaCommon, line 181
	} else if (bindState == false && useCloud == true) { // library marker davegut.kasaCommon, line 182
		logWarn("setCommsType: Can not use cloud.  Device is not bound to Kasa cloud.") // library marker davegut.kasaCommon, line 183
	} else if (bindState == true && useCloud == true && parent.kasaToken) { // library marker davegut.kasaCommon, line 184
		commsType = "CLOUD" // library marker davegut.kasaCommon, line 185
		cloudCtrl = true // library marker davegut.kasaCommon, line 186
	} else if (altLan == true) { // library marker davegut.kasaCommon, line 187
		commsType = "AltLAN" // library marker davegut.kasaCommon, line 188
		state.response = "" // library marker davegut.kasaCommon, line 189
	} // library marker davegut.kasaCommon, line 190
	def commsSettings = [bind: bindState, useCloud: cloudCtrl, commsType: commsType] // library marker davegut.kasaCommon, line 191
	device.updateSetting("bind", [type:"bool", value: bindState]) // library marker davegut.kasaCommon, line 192
	device.updateSetting("useCloud", [type:"bool", value: cloudCtrl]) // library marker davegut.kasaCommon, line 193
	sendEvent(name: "connection", value: "${commsType}") // library marker davegut.kasaCommon, line 194
	logInfo("setCommsType: ${commsSettings}") // library marker davegut.kasaCommon, line 195
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaCommon, line 196
		def coordData = [:] // library marker davegut.kasaCommon, line 197
		coordData << [bind: bindState] // library marker davegut.kasaCommon, line 198
		coordData << [useCloud: cloudCtrl] // library marker davegut.kasaCommon, line 199
		coordData << [connection: commsType] // library marker davegut.kasaCommon, line 200
		parent.coordinate("commsData", coordData, getDataValue("deviceId"), getDataValue("plugNo")) // library marker davegut.kasaCommon, line 201
	} // library marker davegut.kasaCommon, line 202
	pauseExecution(1000) // library marker davegut.kasaCommon, line 203
} // library marker davegut.kasaCommon, line 204

def syncName() { // library marker davegut.kasaCommon, line 206
	def message // library marker davegut.kasaCommon, line 207
	if (nameSync == "Hubitat") { // library marker davegut.kasaCommon, line 208
		message = "Hubitat Label Sync" // library marker davegut.kasaCommon, line 209
		setDeviceAlias(device.getLabel()) // library marker davegut.kasaCommon, line 210
	} else if (nameSync == "device") { // library marker davegut.kasaCommon, line 211
		message = "Device Alias Sync" // library marker davegut.kasaCommon, line 212
	} else { // library marker davegut.kasaCommon, line 213
		message = "Not Syncing" // library marker davegut.kasaCommon, line 214
	} // library marker davegut.kasaCommon, line 215
	return message // library marker davegut.kasaCommon, line 216
} // library marker davegut.kasaCommon, line 217

def updateName(response) { // library marker davegut.kasaCommon, line 219
	device.updateSetting("nameSync",[type:"enum", value:"none"]) // library marker davegut.kasaCommon, line 220
	def name = device.getLabel() // library marker davegut.kasaCommon, line 221
	if (response.alias) { // library marker davegut.kasaCommon, line 222
		name = response.alias // library marker davegut.kasaCommon, line 223
		device.setLabel(name) // library marker davegut.kasaCommon, line 224
	} else if (response.err_code != 0) { // library marker davegut.kasaCommon, line 225
		def msg = "updateName: Name Sync from Hubitat to Device returned an error." // library marker davegut.kasaCommon, line 226
		msg+= "Note: <b>some devices do not support syncing name from the hub.</b>\n\r" // library marker davegut.kasaCommon, line 227
		logWarn(msg) // library marker davegut.kasaCommon, line 228
		return // library marker davegut.kasaCommon, line 229
	} // library marker davegut.kasaCommon, line 230
	logInfo("updateName: Hubitat and Kasa device name synchronized to ${name}") // library marker davegut.kasaCommon, line 231
} // library marker davegut.kasaCommon, line 232

//	===== Kasa API Commands ===== // library marker davegut.kasaCommon, line 234
def getSysinfo() { // library marker davegut.kasaCommon, line 235
	sendCmd("""{"system":{"get_sysinfo":{}}}""") // library marker davegut.kasaCommon, line 236
} // library marker davegut.kasaCommon, line 237

def reboot() { // library marker davegut.kasaCommon, line 239
	def method = "system" // library marker davegut.kasaCommon, line 240
	if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaCommon, line 241
		method = "smartlife.iot.common.system" // library marker davegut.kasaCommon, line 242
	} // library marker davegut.kasaCommon, line 243
	sendCmd("""{"${method}":{"reboot":{"delay":1}}}""") // library marker davegut.kasaCommon, line 244
} // library marker davegut.kasaCommon, line 245

def bindService() { // library marker davegut.kasaCommon, line 247
	def service = "cnCloud" // library marker davegut.kasaCommon, line 248
	if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaCommon, line 249
		service = "smartlife.iot.common.cloud" // library marker davegut.kasaCommon, line 250
	} // library marker davegut.kasaCommon, line 251
	return service // library marker davegut.kasaCommon, line 252
} // library marker davegut.kasaCommon, line 253

def getBind() {	 // library marker davegut.kasaCommon, line 255
	sendLanCmd("""{"${bindService()}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 256
} // library marker davegut.kasaCommon, line 257

def setBind(userName, password) { // library marker davegut.kasaCommon, line 259
	sendLanCmd("""{"${bindService()}":{"bind":{"username":"${userName}",""" + // library marker davegut.kasaCommon, line 260
			   """"password":"${password}"}},""" + // library marker davegut.kasaCommon, line 261
			   """"${bindService()}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 262
} // library marker davegut.kasaCommon, line 263

def setUnbind() { // library marker davegut.kasaCommon, line 265
	sendLanCmd("""{"${bindService()}":{"unbind":""},""" + // library marker davegut.kasaCommon, line 266
			   """"${bindService()}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 267
} // library marker davegut.kasaCommon, line 268

def setDeviceAlias(newAlias) { // library marker davegut.kasaCommon, line 270
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaCommon, line 271
	sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaCommon, line 272
				""""system":{"set_dev_alias":{"alias":"${device.getLabel()}"}}}""") // library marker davegut.kasaCommon, line 273
	} else { // library marker davegut.kasaCommon, line 274
		sendCmd("""{"system":{"set_dev_alias":{"alias":"${device.getLabel()}"}}}""") // library marker davegut.kasaCommon, line 275
	} // library marker davegut.kasaCommon, line 276
} // library marker davegut.kasaCommon, line 277

// ~~~~~ end include (705) davegut.kasaCommon ~~~~~

// ~~~~~ start include (706) davegut.kasaCommunications ~~~~~
library ( // library marker davegut.kasaCommunications, line 1
	name: "kasaCommunications", // library marker davegut.kasaCommunications, line 2
	namespace: "davegut", // library marker davegut.kasaCommunications, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaCommunications, line 4
	description: "Kasa Communications Methods", // library marker davegut.kasaCommunications, line 5
	category: "communications", // library marker davegut.kasaCommunications, line 6
	documentationLink: "" // library marker davegut.kasaCommunications, line 7
) // library marker davegut.kasaCommunications, line 8

import groovy.json.JsonSlurper // library marker davegut.kasaCommunications, line 10

def getPort() { // library marker davegut.kasaCommunications, line 12
	def port = 9999 // library marker davegut.kasaCommunications, line 13
	if (getDataValue("devicePort")) { // library marker davegut.kasaCommunications, line 14
		port = getDataValue("devicePort") // library marker davegut.kasaCommunications, line 15
	} // library marker davegut.kasaCommunications, line 16
	return port // library marker davegut.kasaCommunications, line 17
} // library marker davegut.kasaCommunications, line 18

def sendCmd(command) { // library marker davegut.kasaCommunications, line 20
	if (!command.contains("password")) { // library marker davegut.kasaCommunications, line 21
		state.lastCommand = command // library marker davegut.kasaCommunications, line 22
	} // library marker davegut.kasaCommunications, line 23
	def connection = device.currentValue("connection") // library marker davegut.kasaCommunications, line 24
	if (connection == "LAN") { // library marker davegut.kasaCommunications, line 25
		sendLanCmd(command) // library marker davegut.kasaCommunications, line 26
	} else if (connection == "CLOUD"){ // library marker davegut.kasaCommunications, line 27
		sendKasaCmd(command) // library marker davegut.kasaCommunications, line 28
	} else if (connection == "AltLAN") { // library marker davegut.kasaCommunications, line 29
		sendTcpCmd(command) // library marker davegut.kasaCommunications, line 30
	} else { // library marker davegut.kasaCommunications, line 31
		logWarn("sendCmd: attribute connection is not set.") // library marker davegut.kasaCommunications, line 32
	} // library marker davegut.kasaCommunications, line 33
} // library marker davegut.kasaCommunications, line 34

def sendLanCmd(command, commsTo = 3) { // library marker davegut.kasaCommunications, line 36
	logDebug("sendLanCmd: [ip: ${getDataValue("deviceIP")}, commsTo: ${commsTo}, cmd: ${command}]") // library marker davegut.kasaCommunications, line 37
	def myHubAction = new hubitat.device.HubAction( // library marker davegut.kasaCommunications, line 38
		outputXOR(command), // library marker davegut.kasaCommunications, line 39
		hubitat.device.Protocol.LAN, // library marker davegut.kasaCommunications, line 40
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT, // library marker davegut.kasaCommunications, line 41
		 destinationAddress: "${getDataValue("deviceIP")}:${getPort()}", // library marker davegut.kasaCommunications, line 42
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING, // library marker davegut.kasaCommunications, line 43
		 parseWarning: true, // library marker davegut.kasaCommunications, line 44
		 timeout: commsTo, // library marker davegut.kasaCommunications, line 45
		 callback: parseUdp]) // library marker davegut.kasaCommunications, line 46
	try { // library marker davegut.kasaCommunications, line 47
		sendHubCommand(myHubAction) // library marker davegut.kasaCommunications, line 48
	} catch (e) { // library marker davegut.kasaCommunications, line 49
		logWarn("sendLanCmd: LAN Error = ${e}.\n\rNo retry on this error.") // library marker davegut.kasaCommunications, line 50
	} // library marker davegut.kasaCommunications, line 51
} // library marker davegut.kasaCommunications, line 52

def parseUdp(message) { // library marker davegut.kasaCommunications, line 54
	def resp = parseLanMessage(message) // library marker davegut.kasaCommunications, line 55
	if (resp.type == "LAN_TYPE_UDPCLIENT") { // library marker davegut.kasaCommunications, line 56
		def clearResp = inputXOR(resp.payload) // library marker davegut.kasaCommunications, line 57
		if (clearResp.length() > 1022) { // library marker davegut.kasaCommunications, line 58
			if (clearResp.contains("preferred")) { // library marker davegut.kasaCommunications, line 59
				clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}" // library marker davegut.kasaCommunications, line 60
			} else { // library marker davegut.kasaCommunications, line 61
				def msg = "parseUdp: Response is too long for Hubitat UDP implementation." // library marker davegut.kasaCommunications, line 62
				msg += "\n\t<b>Device attributes have not been updated.</b>" // library marker davegut.kasaCommunications, line 63
				if(device.getName().contains("Multi")) { // library marker davegut.kasaCommunications, line 64
					msg += "\n\t<b>HS300:</b>\tCheck your device names. The total Kasa App names of all " // library marker davegut.kasaCommunications, line 65
					msg += "\n\t\t\tdevice names can't exceed 96 charactrs (16 per device).\n\r" // library marker davegut.kasaCommunications, line 66
				} // library marker davegut.kasaCommunications, line 67
				logWarn(msg) // library marker davegut.kasaCommunications, line 68
				return // library marker davegut.kasaCommunications, line 69
			} // library marker davegut.kasaCommunications, line 70
		} // library marker davegut.kasaCommunications, line 71
		def cmdResp = new JsonSlurper().parseText(clearResp) // library marker davegut.kasaCommunications, line 72
		logDebug("parseUdp: ${cmdResp}") // library marker davegut.kasaCommunications, line 73
		distResp(cmdResp) // library marker davegut.kasaCommunications, line 74
		resetCommsError() // library marker davegut.kasaCommunications, line 75
	} else { // library marker davegut.kasaCommunications, line 76
		logDebug("parse: LAN Error = ${resp.type}") // library marker davegut.kasaCommunications, line 77
		handleCommsError() // library marker davegut.kasaCommunications, line 78
	} // library marker davegut.kasaCommunications, line 79
} // library marker davegut.kasaCommunications, line 80

def sendKasaCmd(command) { // library marker davegut.kasaCommunications, line 82
	logDebug("sendKasaCmd: ${command}") // library marker davegut.kasaCommunications, line 83
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 84
	def cmdBody = [ // library marker davegut.kasaCommunications, line 85
		method: "passthrough", // library marker davegut.kasaCommunications, line 86
		params: [ // library marker davegut.kasaCommunications, line 87
			deviceId: getDataValue("deviceId"), // library marker davegut.kasaCommunications, line 88
			requestData: "${command}" // library marker davegut.kasaCommunications, line 89
		] // library marker davegut.kasaCommunications, line 90
	] // library marker davegut.kasaCommunications, line 91
	if (!parent.kasaCloudUrl || !parent.kasaToken) { // library marker davegut.kasaCommunications, line 92
		logWarn("sendKasaCmd: Cloud interface not properly set up.") // library marker davegut.kasaCommunications, line 93
		return // library marker davegut.kasaCommunications, line 94
	} // library marker davegut.kasaCommunications, line 95
	def sendCloudCmdParams = [ // library marker davegut.kasaCommunications, line 96
		uri: "${parent.kasaCloudUrl}/?token=${parent.kasaToken}", // library marker davegut.kasaCommunications, line 97
		requestContentType: 'application/json', // library marker davegut.kasaCommunications, line 98
		contentType: 'application/json', // library marker davegut.kasaCommunications, line 99
		headers: ['Accept':'application/json; version=1, */*; q=0.01'], // library marker davegut.kasaCommunications, line 100
		timeout: 10, // library marker davegut.kasaCommunications, line 101
		body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.kasaCommunications, line 102
	] // library marker davegut.kasaCommunications, line 103
	try { // library marker davegut.kasaCommunications, line 104
		asynchttpPost("cloudParse", sendCloudCmdParams) // library marker davegut.kasaCommunications, line 105
	} catch (e) { // library marker davegut.kasaCommunications, line 106
		def msg = "sendKasaCmd: <b>Error in Cloud Communications.</b> The Kasa Cloud is unreachable." // library marker davegut.kasaCommunications, line 107
		msg += "\nAdditional Data: Error = ${e}\n\n" // library marker davegut.kasaCommunications, line 108
		logWarn(msg) // library marker davegut.kasaCommunications, line 109
	} // library marker davegut.kasaCommunications, line 110
} // library marker davegut.kasaCommunications, line 111

def cloudParse(resp, data = null) { // library marker davegut.kasaCommunications, line 113
	def jsonSlurper = new groovy.json.JsonSlurper() // library marker davegut.kasaCommunications, line 114
	def response = jsonSlurper.parseText(resp.data) // library marker davegut.kasaCommunications, line 115
	if (resp.status == 200 && response.error_code == 0) { // library marker davegut.kasaCommunications, line 116
		def cmdResp = new JsonSlurper().parseText(response.result.responseData) // library marker davegut.kasaCommunications, line 117
		logDebug("cloudParse: ${cmdResp}") // library marker davegut.kasaCommunications, line 118
		distResp(cmdResp) // library marker davegut.kasaCommunications, line 119
		resetCommsError() // library marker davegut.kasaCommunications, line 120
	} else { // library marker davegut.kasaCommunications, line 121
		def msg = "sendKasaCmd:\n<b>Error from the Kasa Cloud.</b> Most common cause is " // library marker davegut.kasaCommunications, line 122
		msg += "your Kasa Token has expired.  Run Kasa Login and Token update and try again." // library marker davegut.kasaCommunications, line 123
		msg += "\nAdditional Data: Error = ${resp.data}\n\n" // library marker davegut.kasaCommunications, line 124
		logDebug(msg) // library marker davegut.kasaCommunications, line 125
		handleCommsError() // library marker davegut.kasaCommunications, line 126
	} // library marker davegut.kasaCommunications, line 127
} // library marker davegut.kasaCommunications, line 128

private sendTcpCmd(command) { // library marker davegut.kasaCommunications, line 130
	logDebug("sendTcpCmd: ${command}") // library marker davegut.kasaCommunications, line 131
	try { // library marker davegut.kasaCommunications, line 132
		interfaces.rawSocket.connect("${getDataValue("deviceIP")}", // library marker davegut.kasaCommunications, line 133
									 getPort().toInteger(), byteInterface: true) // library marker davegut.kasaCommunications, line 134
	} catch (error) { // library marker davegut.kasaCommunications, line 135
		logDebug("SendTcpCmd: Unable to connect to device at ${getDataValue("deviceIP")}:${getDataValue("devicePort")}. " + // library marker davegut.kasaCommunications, line 136
				 "Error = ${error}") // library marker davegut.kasaCommunications, line 137
	} // library marker davegut.kasaCommunications, line 138
	state.lastCommand = command // library marker davegut.kasaCommunications, line 139
	interfaces.rawSocket.sendMessage(outputXorTcp(command)) // library marker davegut.kasaCommunications, line 140
	runIn(2, close) // library marker davegut.kasaCommunications, line 141
} // library marker davegut.kasaCommunications, line 142

def close() { interfaces.rawSocket.close() } // library marker davegut.kasaCommunications, line 144

def socketStatus(message) { // library marker davegut.kasaCommunications, line 146
	if (message != "receive error: Stream closed.") { // library marker davegut.kasaCommunications, line 147
		logDebug("socketStatus: Socket Established") // library marker davegut.kasaCommunications, line 148
	} else { // library marker davegut.kasaCommunications, line 149
		logWarn("socketStatus = ${message}") // library marker davegut.kasaCommunications, line 150
	} // library marker davegut.kasaCommunications, line 151
} // library marker davegut.kasaCommunications, line 152

def parse(message) { // library marker davegut.kasaCommunications, line 154
	def response = state.response.concat(message) // library marker davegut.kasaCommunications, line 155
	state.response = response // library marker davegut.kasaCommunications, line 156
	runInMillis(50, extractTcpResp, [data: response]) // library marker davegut.kasaCommunications, line 157
} // library marker davegut.kasaCommunications, line 158

def extractTcpResp(response) { // library marker davegut.kasaCommunications, line 160
	state.response = "" // library marker davegut.kasaCommunications, line 161
	if (response.length() == null) { // library marker davegut.kasaCommunications, line 162
		logDebug("extractTcpResp: null return rejected.") // library marker davegut.kasaCommunications, line 163
		return  // library marker davegut.kasaCommunications, line 164
	} // library marker davegut.kasaCommunications, line 165
	logDebug("extractTcpResp: ${response}") // library marker davegut.kasaCommunications, line 166
	try { // library marker davegut.kasaCommunications, line 167
//		distResp(parseJson(inputXorTcp(response))) // library marker davegut.kasaCommunications, line 168
		def cmdResp = parseJson(inputXorTcp(response)) // library marker davegut.kasaCommunications, line 169
		logDebug("cloudParse: ${cmdResp}") // library marker davegut.kasaCommunications, line 170
		distResp(cmdResp) // library marker davegut.kasaCommunications, line 171
		resetCommsError() // library marker davegut.kasaCommunications, line 172
	} catch (e) { // library marker davegut.kasaCommunications, line 173
		logDebug("extractTcpResponse: comms error = ${e}") // library marker davegut.kasaCommunications, line 174
		handleCommsError() // library marker davegut.kasaCommunications, line 175
	} // library marker davegut.kasaCommunications, line 176
} // library marker davegut.kasaCommunications, line 177

def handleCommsError() { // library marker davegut.kasaCommunications, line 179
	def count = state.errorCount + 1 // library marker davegut.kasaCommunications, line 180
	state.errorCount = count // library marker davegut.kasaCommunications, line 181
	def retry = true // library marker davegut.kasaCommunications, line 182
	def status = [count: count, command: state.lastCommand] // library marker davegut.kasaCommunications, line 183
	if (count == 3) { // library marker davegut.kasaCommunications, line 184
		def attemptFix = parent.fixConnection() // library marker davegut.kasaCommunications, line 185
		status << [attemptFixResult: [attemptFix]] // library marker davegut.kasaCommunications, line 186
	} else if (count >= 4) { // library marker davegut.kasaCommunications, line 187
		retry = false // library marker davegut.kasaCommunications, line 188
	} // library marker davegut.kasaCommunications, line 189
	if (retry == true) { // library marker davegut.kasaCommunications, line 190
		def commsTo = 5 // library marker davegut.kasaCommunications, line 191
		sendLanCmd(state.lastCommand, commsTo) // library marker davegut.kasaCommunications, line 192
		if (count > 1) { // library marker davegut.kasaCommunications, line 193
			logDebug("handleCommsError: [count: ${count}, timeout: ${commsTo}]") // library marker davegut.kasaCommunications, line 194
		} // library marker davegut.kasaCommunications, line 195
	} else { // library marker davegut.kasaCommunications, line 196
		setCommsError() // library marker davegut.kasaCommunications, line 197
	} // library marker davegut.kasaCommunications, line 198
	status << [retry: retry] // library marker davegut.kasaCommunications, line 199
	if (status.count > 2) { // library marker davegut.kasaCommunications, line 200
		logWarn("handleCommsError: ${status}") // library marker davegut.kasaCommunications, line 201
	} else { // library marker davegut.kasaCommunications, line 202
		logDebug("handleCommsError: ${status}") // library marker davegut.kasaCommunications, line 203
	} // library marker davegut.kasaCommunications, line 204
} // library marker davegut.kasaCommunications, line 205

def setCommsError() { // library marker davegut.kasaCommunications, line 207
	if (device.currentValue("commsError") == "false") { // library marker davegut.kasaCommunications, line 208
		def message = "Can't connect to your device at ${getDataValue("deviceIP")}:${getPort()}. " // library marker davegut.kasaCommunications, line 209
		message += "Refer to troubleshooting guide commsError section." // library marker davegut.kasaCommunications, line 210
		sendEvent(name: "commsError", value: "true") // library marker davegut.kasaCommunications, line 211
		state.COMMS_ERROR = message			 // library marker davegut.kasaCommunications, line 212
		logWarn("setCommsError: <b>${message}</b>") // library marker davegut.kasaCommunications, line 213
		runIn(15, limitPollInterval) // library marker davegut.kasaCommunications, line 214
	} // library marker davegut.kasaCommunications, line 215
} // library marker davegut.kasaCommunications, line 216

def limitPollInterval() { // library marker davegut.kasaCommunications, line 218
	state.nonErrorPollInterval = state.pollInterval // library marker davegut.kasaCommunications, line 219
	setPollInterval("30 minutes") // library marker davegut.kasaCommunications, line 220
} // library marker davegut.kasaCommunications, line 221

def resetCommsError() { // library marker davegut.kasaCommunications, line 223
	state.errorCount = 0 // library marker davegut.kasaCommunications, line 224
	if (device.currentValue("commsError") == "true") { // library marker davegut.kasaCommunications, line 225
		sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommunications, line 226
		setPollInterval(state.nonErrorPollInterval) // library marker davegut.kasaCommunications, line 227
		state.remove("nonErrorPollInterval") // library marker davegut.kasaCommunications, line 228
		state.remove("COMMS_ERROR") // library marker davegut.kasaCommunications, line 229
		logInfo("resetCommsError: Comms error cleared!") // library marker davegut.kasaCommunications, line 230
	} // library marker davegut.kasaCommunications, line 231
} // library marker davegut.kasaCommunications, line 232

private outputXOR(command) { // library marker davegut.kasaCommunications, line 234
	def str = "" // library marker davegut.kasaCommunications, line 235
	def encrCmd = "" // library marker davegut.kasaCommunications, line 236
 	def key = 0xAB // library marker davegut.kasaCommunications, line 237
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 238
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 239
		key = str // library marker davegut.kasaCommunications, line 240
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 241
	} // library marker davegut.kasaCommunications, line 242
   	return encrCmd // library marker davegut.kasaCommunications, line 243
} // library marker davegut.kasaCommunications, line 244

private inputXOR(encrResponse) { // library marker davegut.kasaCommunications, line 246
	String[] strBytes = encrResponse.split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 247
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 248
	def key = 0xAB // library marker davegut.kasaCommunications, line 249
	def nextKey // library marker davegut.kasaCommunications, line 250
	byte[] XORtemp // library marker davegut.kasaCommunications, line 251
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 252
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 253
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 254
		key = nextKey // library marker davegut.kasaCommunications, line 255
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 256
	} // library marker davegut.kasaCommunications, line 257
	return cmdResponse // library marker davegut.kasaCommunications, line 258
} // library marker davegut.kasaCommunications, line 259

private outputXorTcp(command) { // library marker davegut.kasaCommunications, line 261
	def str = "" // library marker davegut.kasaCommunications, line 262
	def encrCmd = "000000" + Integer.toHexString(command.length())  // library marker davegut.kasaCommunications, line 263
 	def key = 0xAB // library marker davegut.kasaCommunications, line 264
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 265
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 266
		key = str // library marker davegut.kasaCommunications, line 267
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 268
	} // library marker davegut.kasaCommunications, line 269
   	return encrCmd // library marker davegut.kasaCommunications, line 270
} // library marker davegut.kasaCommunications, line 271

private inputXorTcp(resp) { // library marker davegut.kasaCommunications, line 273
	String[] strBytes = resp.substring(8).split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 274
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 275
	def key = 0xAB // library marker davegut.kasaCommunications, line 276
	def nextKey // library marker davegut.kasaCommunications, line 277
	byte[] XORtemp // library marker davegut.kasaCommunications, line 278
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 279
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 280
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 281
		key = nextKey // library marker davegut.kasaCommunications, line 282
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 283
	} // library marker davegut.kasaCommunications, line 284
	return cmdResponse // library marker davegut.kasaCommunications, line 285
} // library marker davegut.kasaCommunications, line 286

// ~~~~~ end include (706) davegut.kasaCommunications ~~~~~

// ~~~~~ start include (611) davegut.Logging ~~~~~
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
	log.trace "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" // library marker davegut.Logging, line 26
} // library marker davegut.Logging, line 27

def logInfo(msg) {  // library marker davegut.Logging, line 29
	if (infoLog == true) { // library marker davegut.Logging, line 30
		log.info "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" // library marker davegut.Logging, line 31
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
		log.debug "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" // library marker davegut.Logging, line 46
	} // library marker davegut.Logging, line 47
} // library marker davegut.Logging, line 48

def logWarn(msg) { log.warn "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" } // library marker davegut.Logging, line 50

// ~~~~~ end include (611) davegut.Logging ~~~~~

// ~~~~~ start include (709) davegut.kasaPlugs ~~~~~
library ( // library marker davegut.kasaPlugs, line 1
	name: "kasaPlugs", // library marker davegut.kasaPlugs, line 2
	namespace: "davegut", // library marker davegut.kasaPlugs, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaPlugs, line 4
	description: "Kasa Plug and Switches Common Methods", // library marker davegut.kasaPlugs, line 5
	category: "utilities", // library marker davegut.kasaPlugs, line 6
	documentationLink: "" // library marker davegut.kasaPlugs, line 7
) // library marker davegut.kasaPlugs, line 8

//	===== Commands === // library marker davegut.kasaPlugs, line 10
def on() { setRelayState(1) } // library marker davegut.kasaPlugs, line 11

def off() { setRelayState(0) } // library marker davegut.kasaPlugs, line 13

def ledOn() { setLedOff(0) } // library marker davegut.kasaPlugs, line 15

def ledOff() { setLedOff(1) } // library marker davegut.kasaPlugs, line 17

//	================================================== // library marker davegut.kasaPlugs, line 19
def distResp(response) { // library marker davegut.kasaPlugs, line 20
	if (response.system) { // library marker davegut.kasaPlugs, line 21
		if (response.system.get_sysinfo) { // library marker davegut.kasaPlugs, line 22
			setSysInfo(response.system.get_sysinfo) // library marker davegut.kasaPlugs, line 23
		} else if (response.system.reboot) { // library marker davegut.kasaPlugs, line 24
			logWarn("distResp: Rebooting device.") // library marker davegut.kasaPlugs, line 25
		} else if (response.system.set_dev_alias) { // library marker davegut.kasaPlugs, line 26
			updateName(response.system.set_dev_alias) // library marker davegut.kasaPlugs, line 27
		} else { // library marker davegut.kasaPlugs, line 28
			logDebug("distResp: Unhandled response = ${response}") // library marker davegut.kasaPlugs, line 29
		} // library marker davegut.kasaPlugs, line 30
	} else if (response["smartlife.iot.dimmer"]) { // library marker davegut.kasaPlugs, line 31
		if (response["smartlife.iot.dimmer"].get_dimmer_parameters) { // library marker davegut.kasaPlugs, line 32
			setDimmerConfig(response["smartlife.iot.dimmer"]) // library marker davegut.kasaPlugs, line 33
		} else { // library marker davegut.kasaPlugs, line 34
			logDebug("distResp: Unhandled response: ${response["smartlife.iot.dimmer"]}") // library marker davegut.kasaPlugs, line 35
		} // library marker davegut.kasaPlugs, line 36
	} else if (response.emeter) { // library marker davegut.kasaPlugs, line 37
		distEmeter(response.emeter) // library marker davegut.kasaPlugs, line 38
	} else if (response.cnCloud) { // library marker davegut.kasaPlugs, line 39
		setBindUnbind(response.cnCloud) // library marker davegut.kasaPlugs, line 40
	} else { // library marker davegut.kasaPlugs, line 41
		logDebug("distResp: Unhandled response = ${response}") // library marker davegut.kasaPlugs, line 42
	} // library marker davegut.kasaPlugs, line 43
} // library marker davegut.kasaPlugs, line 44

//	===== API Prep and Call Methods ===== // library marker davegut.kasaPlugs, line 46
def setRelayState(onOff) { // library marker davegut.kasaPlugs, line 47
	logDebug("setRelayState: [switch: ${onOff}]") // library marker davegut.kasaPlugs, line 48
	if (getDataValue("plugNo") == null) { // library marker davegut.kasaPlugs, line 49
		sendCmd("""{"system":{"set_relay_state":{"state":${onOff}},"get_sysinfo":{}}}""") // library marker davegut.kasaPlugs, line 50
	} else { // library marker davegut.kasaPlugs, line 51
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaPlugs, line 52
				""""system":{"set_relay_state":{"state":${onOff}},"get_sysinfo":{}}}""") // library marker davegut.kasaPlugs, line 53
	} // library marker davegut.kasaPlugs, line 54
} // library marker davegut.kasaPlugs, line 55

def setLedOff(onOff) { // library marker davegut.kasaPlugs, line 57
	logDebug("setLedOff: [ledOff: ${onOff}]") // library marker davegut.kasaPlugs, line 58
	sendCmd("""{"system":{"set_led_off":{"off":${onOff}},"get_sysinfo":{}}}""") // library marker davegut.kasaPlugs, line 59
} // library marker davegut.kasaPlugs, line 60



// ~~~~~ end include (709) davegut.kasaPlugs ~~~~~
