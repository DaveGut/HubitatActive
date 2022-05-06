/*	Kasa Device Driver Series
		Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
6.5.1	Hot fix for loop in EM Month Stat Processing due to month = 1
6.5.2	Minor Changes: Energy Monitor Functios, Bulbs/Light Strips, Dimming Switch.
		New Capability: Configuration. Selecting updates the version configuration of the APP and ALL
						Kasa Devices.
6.5.3	Bug Fix in Sync Names for multi-plugs.
		sendEvent for switch will be sent at each poll to update Hub LastActivity for the device
6.5.4	a.	Bug fix to transition time.
		b.	Increased debug logging on Parse Methods.
		c.	Change setSysinfo method to call get power in all cases using runIn(2, getPower)

Link to change details:
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Change_Descriptions.pdf
===================================================================================================*/
def driverVer() { return "6.5.4" }
//def type() { return "Plug Switch" }
//def type() { return "EM Plug" }
//def type() { return "Multi Plug" }
def type() { return "EM Multi Plug" }
def file() { return type().replaceAll(" ", "-") }

metadata {
	definition (name: "Kasa ${type()}",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/${file()}.groovy"
			   ) {
		capability "Configuration"
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		command "ledOn"
		command "ledOff"
		attribute "led", "string"
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["default", "5 seconds", "10 seconds", "15 seconds",
						  "30 seconds", "1 minute", "5 minutes",  "10 minutes",
						  "30 minutes"],
			type: "ENUM"]]
		capability "Power Meter"
		capability "Energy Meter"
		attribute "currMonthTotal", "number"
		attribute "currMonthAvg", "number"
		attribute "lastMonthTotal", "number"
		attribute "lastMonthAvg", "number"
		attribute "connection", "string"
		attribute "commsError", "string"
	}
	preferences {
		input ("descriptionText", "bool", 
			   title: "Enable information logging", 
			   defaultValue: true)
		input ("debug", "bool",
			   title: "30 minutes of debug logging", 
			   defaultValue: false)
		if (getDataValue("feature") == "TIM:ENE") {
			input ("emFunction", "bool", 
				   title: "Enable Energy Monitor", 
				   defaultValue: false)
		}
		if (getDataValue("model") == "HS200" && getDataValue("deviceIP") != "CLOUD") {
			input ("altLan", "bool",
				   title: "Alternate LAN Comms (for comms problems only)",
				   defaultValue: false)
		}
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
	logInfo("installed: ${instStatus}")
}

def updated() {
	def updStatus = updateCommon()
	if (getDataValue("feature") == "TIM:ENE") {
		updStatus << [emFunction: setupEmFunction()]
	}
	logInfo("updated: ${updStatus}")
}

def deviceConfigure() { }

//	==================================================
def setSysInfo(status) {
	def updates = [:]
	def switchStatus = status.relay_state
	def ledStatus = status.led_off
	if (getDataValue("plugNo") != null) {
		if (device.currentValue("connection") != "CLOUD") {
			status = status.children.find { it.id == getDataValue("plugNo") }
		} else {
			status = status.children.find { it.id == getDataValue("plugId") }
		}
		switchStatus = status.state
	}
	def onOff = "on"
	if (switchStatus == 0) { onOff = "off" }
	if (onOff != device.currentValue("switch")) {
		updates << [switch: onOff]
		//	5.2.3	sendEvent(name: "switch", value: onOff, type: "digital")
	}
	sendEvent(name: "switch", value: onOff, type: "digital")	//	5.2.3
	def ledOnOff = "on"
	if (ledStatus == 1) { ledOnOff = "off" }
	if (ledOnOff != device.currentValue("led")) {
		updates << [led: ledOnOff]
		sendEvent(name: "led", value: ledOnOff)
	}
	if (emFunction) {
		runIn(3, getPower)
	}	//	6.5.4
	if (updates != [:]) { logInfo("setSysinfo: ${updates}") }
	if (nameSync == "device") {
		updateName(status)
	}
}

def coordUpdate(cType, coordData) {
	def msg = "coordinateUpdate: "
	if (cType == "commsData") {
		device.updateSetting("bind", [type:"bool", value: coordData.bind])
		device.updateSetting("useCloud", [type:"bool", value: coordData.useCloud])
		sendEvent(name: "connection", value: coordData.connection)
		msg += "[commsData: ${coordData}]"
	} else {
		msg += "Not updated."
	}
	logInfo(msg)
}


//	==================================================



//	==================================================



//	==================================================



//	==================================================


// ~~~~~ start include (579) davegut.kasaPlugs ~~~~~
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



// ~~~~~ end include (579) davegut.kasaPlugs ~~~~~

// ~~~~~ start include (545) davegut.kasaCommon ~~~~~
library ( // library marker davegut.kasaCommon, line 1
	name: "kasaCommon", // library marker davegut.kasaCommon, line 2
	namespace: "davegut", // library marker davegut.kasaCommon, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaCommon, line 4
	description: "Kasa Device Common Methods", // library marker davegut.kasaCommon, line 5
	category: "utilities", // library marker davegut.kasaCommon, line 6
	documentationLink: "" // library marker davegut.kasaCommon, line 7
) // library marker davegut.kasaCommon, line 8

//	====== Common Install / Update Elements ===== // library marker davegut.kasaCommon, line 10
def installCommon() { // library marker davegut.kasaCommon, line 11
	pauseExecution(3000) // library marker davegut.kasaCommon, line 12
	def instStatus = [:] // library marker davegut.kasaCommon, line 13
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 14
		sendEvent(name: "connection", value: "CLOUD") // library marker davegut.kasaCommon, line 15
		device.updateSetting("useCloud", [type:"bool", value: true]) // library marker davegut.kasaCommon, line 16
		instStatus << [useCloud: true, connection: "CLOUD"] // library marker davegut.kasaCommon, line 17
	} else { // library marker davegut.kasaCommon, line 18
		sendEvent(name: "connection", value: "LAN") // library marker davegut.kasaCommon, line 19
		device.updateSetting("useCloud", [type:"bool", value: false]) // library marker davegut.kasaCommon, line 20
		instStatus << [useCloud: false, connection: "LAN"] // library marker davegut.kasaCommon, line 21
	} // library marker davegut.kasaCommon, line 22
	sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommon, line 23
	state.errorCount = 0 // library marker davegut.kasaCommon, line 24
	state.pollInterval = "30 minutes" // library marker davegut.kasaCommon, line 25
	instStatus << [driverVersion: driverVer()] // library marker davegut.kasaCommon, line 26
	runIn(2, updated) // library marker davegut.kasaCommon, line 27
	return instStatus // library marker davegut.kasaCommon, line 28
} // library marker davegut.kasaCommon, line 29

def updateCommon() { // library marker davegut.kasaCommon, line 31
	unschedule() // library marker davegut.kasaCommon, line 32
	def updStatus = [:] // library marker davegut.kasaCommon, line 33
	if (rebootDev) { // library marker davegut.kasaCommon, line 34
		updStatus << [rebootDev: rebootDevice()] // library marker davegut.kasaCommon, line 35
		return updStatus // library marker davegut.kasaCommon, line 36
	} // library marker davegut.kasaCommon, line 37
	updStatus << [bind: bindUnbind()] // library marker davegut.kasaCommon, line 38
	if (nameSync != "none") { // library marker davegut.kasaCommon, line 39
		updStatus << [nameSync: syncName()] // library marker davegut.kasaCommon, line 40
	} // library marker davegut.kasaCommon, line 41
	if (debug) { runIn(1800, debugOff) } // library marker davegut.kasaCommon, line 42
	updStatus << [debug: debug] // library marker davegut.kasaCommon, line 43
	state.errorCount = 0 // library marker davegut.kasaCommon, line 44
	sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommon, line 45
	updStatus << [pollInterval: setPollInterval()] // library marker davegut.kasaCommon, line 46
	refresh() // library marker davegut.kasaCommon, line 47
	configure() // library marker davegut.kasaCommon, line 48
	pauseExecution(3000) // library marker davegut.kasaCommon, line 49
	return updStatus // library marker davegut.kasaCommon, line 50
} // library marker davegut.kasaCommon, line 51

def configure() { // library marker davegut.kasaCommon, line 53
	def config = parent.updateConfigurations() // library marker davegut.kasaCommon, line 54
	logInfo("configure: ${config}") // library marker davegut.kasaCommon, line 55
} // library marker davegut.kasaCommon, line 56

def childConfigure(updateData) { // library marker davegut.kasaCommon, line 58
	def message = "configure: Configuring ${device.getLabel()}." // library marker davegut.kasaCommon, line 59
	message += "\n\t\t\t  *\tUpdated IP for all devices." // library marker davegut.kasaCommon, line 60
	if(getDataValue("driverVersion") != driverVer()){ // library marker davegut.kasaCommon, line 61
		updateDataValue("driverVersion", driverVer()) // library marker davegut.kasaCommon, line 62
		if (state.pollNote) { state.remove("pollNote") } // library marker davegut.kasaCommon, line 63
		if (state.pollWarning) { state.remove("pollWarning") } // library marker davegut.kasaCommon, line 64
		if (!descriptionText || descriptionText == null) { // library marker davegut.kasaCommon, line 65
			device.updateSetting("descriptionText", [type:"bool", value: true]) // library marker davegut.kasaCommon, line 66
		} // library marker davegut.kasaCommon, line 67
		if (!state.pollInterval) { state.pollInterval = "30 minutes" } // library marker davegut.kasaCommon, line 68
		if (emFunction) { // library marker davegut.kasaCommon, line 69
			//	Kick start new version getEnergyThisMonth which adds state.getEnergy // library marker davegut.kasaCommon, line 70
			schedule("10 0 0 * * ?", getEnergyThisMonth) // library marker davegut.kasaCommon, line 71
			schedule("15 2 0 1 * ?", getEnergyLastMonth) // library marker davegut.kasaCommon, line 72
			state.getEnergy = "This Month" // library marker davegut.kasaCommon, line 73
		} // library marker davegut.kasaCommon, line 74
		deviceConfigure() // library marker davegut.kasaCommon, line 75
	} // library marker davegut.kasaCommon, line 76
	logDebug("childConfigure: ${updateData}") // library marker davegut.kasaCommon, line 77
	if (driverVer().trim() != updateData.appVersion) { // library marker davegut.kasaCommon, line 78
		state.DRIVER_MISMATCH = "Driver version (${driverVer()}) not the same as App version (${updateData.appVersion})" // library marker davegut.kasaCommon, line 79
		message += "\n\t\t\t  *\t<b>Driver/App Versions: Don't match!  Update!</b>" // library marker davegut.kasaCommon, line 80
		logWarn("configure: Current driver does not match with App Version.  Update to assure proper operation.") // library marker davegut.kasaCommon, line 81
	} else { // library marker davegut.kasaCommon, line 82
		state.remove("DRIVER_MISMATCH") // library marker davegut.kasaCommon, line 83
		message += "\n\t\t\t  *\tDriver/App Versions: OK, same for each." // library marker davegut.kasaCommon, line 84
	} // library marker davegut.kasaCommon, line 85
	if (updateData.updateAvailable) { // library marker davegut.kasaCommon, line 86
		state.releaseNotes = "${updateData.releaseNotes}" // library marker davegut.kasaCommon, line 87
		if (updateData.releaseNotes.contains("CRITICAL")) { // library marker davegut.kasaCommon, line 88
			state.UPDATE_AVAILABLE = "A CRITICAL UPDATE TO APP AND DRIVER ARE AVAILABLE to version  ${updateData.currVersion}." // library marker davegut.kasaCommon, line 89
			message += "\n\t\t\t  *\t<b>Driver/App Updates: CRITICAL UPDATES AVAILABLE.</b>" // library marker davegut.kasaCommon, line 90
			logWarn("<b>A CRITICAL</b> Applications and Drivers update is available for the Kasa Integration") // library marker davegut.kasaCommon, line 91
		} else { // library marker davegut.kasaCommon, line 92
			state.UPDATE_AVAILABLE = "App and driver updates are available to version ${updateData.currVersion}.  Consider updating." // library marker davegut.kasaCommon, line 93
			message += "\n\t\t\t  *\t<b>Driver/App Updates: Available.</b>" // library marker davegut.kasaCommon, line 94
		} // library marker davegut.kasaCommon, line 95
	} else { // library marker davegut.kasaCommon, line 96
		message += "\n\t\t\t  *\tDriver/App Updates: No updates available." // library marker davegut.kasaCommon, line 97
		state.remove("UPDATE_AVAILABLE") // library marker davegut.kasaCommon, line 98
		state.remove("releaseNotes") // library marker davegut.kasaCommon, line 99
	} // library marker davegut.kasaCommon, line 100
	logInfo(message) // library marker davegut.kasaCommon, line 101
} // library marker davegut.kasaCommon, line 102

//	===== Poll/Refresh ===== // library marker davegut.kasaCommon, line 104
def refresh() { poll() } // library marker davegut.kasaCommon, line 105

def poll() { getSysinfo() } // library marker davegut.kasaCommon, line 107

//	===== Preference Methods ===== // library marker davegut.kasaCommon, line 109
def setPollInterval(interval = state.pollInterval) { // library marker davegut.kasaCommon, line 110
	if (interval == "default" || interval == "off" || interval == null) { // library marker davegut.kasaCommon, line 111
		interval = "30 minutes" // library marker davegut.kasaCommon, line 112
	} else if (useCloud && interval.contains("sec")) { // library marker davegut.kasaCommon, line 113
		interval = "1 minute" // library marker davegut.kasaCommon, line 114
	} // library marker davegut.kasaCommon, line 115
	state.pollInterval = interval // library marker davegut.kasaCommon, line 116
	def pollInterval = interval.substring(0,2).toInteger() // library marker davegut.kasaCommon, line 117
	if (interval.contains("sec")) { // library marker davegut.kasaCommon, line 118
		def start = Math.round((pollInterval-1) * Math.random()).toInteger() // library marker davegut.kasaCommon, line 119
		schedule("${start}/${pollInterval} * * * * ?", "poll") // library marker davegut.kasaCommon, line 120
		logWarn("setPollInterval: Polling intervals of less than one minute " + // library marker davegut.kasaCommon, line 121
				"can take high resources and may impact hub performance.") // library marker davegut.kasaCommon, line 122
	} else { // library marker davegut.kasaCommon, line 123
		def start = Math.round(59 * Math.random()).toInteger() // library marker davegut.kasaCommon, line 124
		schedule("${start} */${pollInterval} * * * ?", "poll") // library marker davegut.kasaCommon, line 125
	} // library marker davegut.kasaCommon, line 126
	logDebug("setPollInterval: interval = ${interval}.") // library marker davegut.kasaCommon, line 127
	return interval // library marker davegut.kasaCommon, line 128
} // library marker davegut.kasaCommon, line 129

def rebootDevice() { // library marker davegut.kasaCommon, line 131
	device.updateSetting("rebootDev", [type:"bool", value: false]) // library marker davegut.kasaCommon, line 132
	reboot() // library marker davegut.kasaCommon, line 133
	pauseExecution(10000) // library marker davegut.kasaCommon, line 134
	return "REBOOTING DEVICE" // library marker davegut.kasaCommon, line 135
} // library marker davegut.kasaCommon, line 136

def bindUnbind() { // library marker davegut.kasaCommon, line 138
	def message // library marker davegut.kasaCommon, line 139
	if (bind == null || // library marker davegut.kasaCommon, line 140
	    getDataValue("deviceIP") == "CLOUD" || // library marker davegut.kasaCommon, line 141
	    type() == "Light Strip") { // library marker davegut.kasaCommon, line 142
		message = "Getting current bind state" // library marker davegut.kasaCommon, line 143
		getBind() // library marker davegut.kasaCommon, line 144
	} else if (bind == true) { // library marker davegut.kasaCommon, line 145
		if (!parent.kasaToken || parent.userName == null || parent.userPassword == null) { // library marker davegut.kasaCommon, line 146
			message = "Username/pwd not set." // library marker davegut.kasaCommon, line 147
			getBind() // library marker davegut.kasaCommon, line 148
		} else { // library marker davegut.kasaCommon, line 149
			message = "Binding device to the Kasa Cloud." // library marker davegut.kasaCommon, line 150
			setBind(parent.userName, parent.userPassword) // library marker davegut.kasaCommon, line 151
		} // library marker davegut.kasaCommon, line 152
	} else if (bind == false) { // library marker davegut.kasaCommon, line 153
		message = "Unbinding device from the Kasa Cloud." // library marker davegut.kasaCommon, line 154
		setUnbind() // library marker davegut.kasaCommon, line 155
	} // library marker davegut.kasaCommon, line 156
	pauseExecution(5000) // library marker davegut.kasaCommon, line 157
	return message // library marker davegut.kasaCommon, line 158
} // library marker davegut.kasaCommon, line 159

def setBindUnbind(cmdResp) { // library marker davegut.kasaCommon, line 161
	def bindState = true // library marker davegut.kasaCommon, line 162
	if (cmdResp.get_info) { // library marker davegut.kasaCommon, line 163
		if (cmdResp.get_info.binded == 0) { bindState = false } // library marker davegut.kasaCommon, line 164
		logInfo("setBindUnbind: Bind status set to ${bindState}") // library marker davegut.kasaCommon, line 165
		setCommsType(bindState) // library marker davegut.kasaCommon, line 166
	} else if (cmdResp.bind.err_code == 0){ // library marker davegut.kasaCommon, line 167
		getBind() // library marker davegut.kasaCommon, line 168
	} else { // library marker davegut.kasaCommon, line 169
		logWarn("setBindUnbind: Unhandled response: ${cmdResp}") // library marker davegut.kasaCommon, line 170
	} // library marker davegut.kasaCommon, line 171
} // library marker davegut.kasaCommon, line 172

def setCommsType(bindState) { // library marker davegut.kasaCommon, line 174
	def commsType = "LAN" // library marker davegut.kasaCommon, line 175
	def cloudCtrl = false // library marker davegut.kasaCommon, line 176
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 177
		commsType = "CLOUD" // library marker davegut.kasaCommon, line 178
		cloudCtrl = true // library marker davegut.kasaCommon, line 179
	} else if (bindState == false && useCloud == true) { // library marker davegut.kasaCommon, line 180
		logWarn("setCommsType: Can not use cloud.  Device is not bound to Kasa cloud.") // library marker davegut.kasaCommon, line 181
	} else if (bindState == true && useCloud == true && parent.kasaToken) { // library marker davegut.kasaCommon, line 182
		commsType = "CLOUD" // library marker davegut.kasaCommon, line 183
		cloudCtrl = true // library marker davegut.kasaCommon, line 184
	} else if (altLan == true) { // library marker davegut.kasaCommon, line 185
		commsType = "AltLAN" // library marker davegut.kasaCommon, line 186
		state.response = "" // library marker davegut.kasaCommon, line 187
	} // library marker davegut.kasaCommon, line 188
	def commsSettings = [bind: bindState, useCloud: cloudCtrl, commsType: commsType] // library marker davegut.kasaCommon, line 189
	device.updateSetting("bind", [type:"bool", value: bindState]) // library marker davegut.kasaCommon, line 190
	device.updateSetting("useCloud", [type:"bool", value: cloudCtrl]) // library marker davegut.kasaCommon, line 191
	sendEvent(name: "connection", value: "${commsType}") // library marker davegut.kasaCommon, line 192
	logInfo("setCommsType: ${commsSettings}") // library marker davegut.kasaCommon, line 193
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaCommon, line 194
		def coordData = [:] // library marker davegut.kasaCommon, line 195
		coordData << [bind: bindState] // library marker davegut.kasaCommon, line 196
		coordData << [useCloud: cloudCtrl] // library marker davegut.kasaCommon, line 197
		coordData << [connection: commsType] // library marker davegut.kasaCommon, line 198
		parent.coordinate("commsData", coordData, getDataValue("deviceId"), getDataValue("plugNo")) // library marker davegut.kasaCommon, line 199
	} // library marker davegut.kasaCommon, line 200
	pauseExecution(1000) // library marker davegut.kasaCommon, line 201
} // library marker davegut.kasaCommon, line 202

def syncName() { // library marker davegut.kasaCommon, line 204
	def message // library marker davegut.kasaCommon, line 205
	if (nameSync == "Hubitat") { // library marker davegut.kasaCommon, line 206
		message = "Hubitat Label Sync" // library marker davegut.kasaCommon, line 207
		setDeviceAlias(device.getLabel()) // library marker davegut.kasaCommon, line 208
	} else if (nameSync == "device") { // library marker davegut.kasaCommon, line 209
		message = "Device Alias Sync" // library marker davegut.kasaCommon, line 210
	} else { // library marker davegut.kasaCommon, line 211
		message = "Not Syncing" // library marker davegut.kasaCommon, line 212
	} // library marker davegut.kasaCommon, line 213
	return message // library marker davegut.kasaCommon, line 214
} // library marker davegut.kasaCommon, line 215

def updateName(response) { // library marker davegut.kasaCommon, line 217
	device.updateSetting("nameSync",[type:"enum", value:"none"]) // library marker davegut.kasaCommon, line 218
	def name = device.getLabel() // library marker davegut.kasaCommon, line 219
	if (response.alias) { // library marker davegut.kasaCommon, line 220
		name = response.alias // library marker davegut.kasaCommon, line 221
		device.setLabel(name) // library marker davegut.kasaCommon, line 222
	} else if (response.err_code != 0) { // library marker davegut.kasaCommon, line 223
		def msg = "updateName: Name Sync from Hubitat to Device returned an error." // library marker davegut.kasaCommon, line 224
		msg+= "Note: <b>some devices do not support syncing name from the hub.</b>\n\r" // library marker davegut.kasaCommon, line 225
		logWarn(msg) // library marker davegut.kasaCommon, line 226
		return // library marker davegut.kasaCommon, line 227
	} // library marker davegut.kasaCommon, line 228
	logInfo("updateName: Hubitat and Kasa device name synchronized to ${name}") // library marker davegut.kasaCommon, line 229
} // library marker davegut.kasaCommon, line 230

//	===== Kasa API Commands ===== // library marker davegut.kasaCommon, line 232
def getSysinfo() { // library marker davegut.kasaCommon, line 233
	sendCmd("""{"system":{"get_sysinfo":{}}}""") // library marker davegut.kasaCommon, line 234
} // library marker davegut.kasaCommon, line 235

def reboot() { // library marker davegut.kasaCommon, line 237
	def method = "system" // library marker davegut.kasaCommon, line 238
	if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaCommon, line 239
		method = "smartlife.iot.common.system" // library marker davegut.kasaCommon, line 240
	} // library marker davegut.kasaCommon, line 241
	sendCmd("""{"${method}":{"reboot":{"delay":1}}}""") // library marker davegut.kasaCommon, line 242
} // library marker davegut.kasaCommon, line 243

def bindService() { // library marker davegut.kasaCommon, line 245
	def service = "cnCloud" // library marker davegut.kasaCommon, line 246
	if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaCommon, line 247
		service = "smartlife.iot.common.cloud" // library marker davegut.kasaCommon, line 248
	} // library marker davegut.kasaCommon, line 249
	return service // library marker davegut.kasaCommon, line 250
} // library marker davegut.kasaCommon, line 251

def getBind() {	 // library marker davegut.kasaCommon, line 253
	sendLanCmd("""{"${bindService()}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 254
} // library marker davegut.kasaCommon, line 255

def setBind(userName, password) { // library marker davegut.kasaCommon, line 257
	sendLanCmd("""{"${bindService()}":{"bind":{"username":"${userName}",""" + // library marker davegut.kasaCommon, line 258
			   """"password":"${password}"}},""" + // library marker davegut.kasaCommon, line 259
			   """"${bindService()}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 260
} // library marker davegut.kasaCommon, line 261

def setUnbind() { // library marker davegut.kasaCommon, line 263
	sendLanCmd("""{"${bindService()}":{"unbind":""},""" + // library marker davegut.kasaCommon, line 264
			   """"${bindService()}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 265
} // library marker davegut.kasaCommon, line 266

def setDeviceAlias(newAlias) { // library marker davegut.kasaCommon, line 268
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaCommon, line 269
	sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaCommon, line 270
				""""system":{"set_dev_alias":{"alias":"${device.getLabel()}"}}}""") // library marker davegut.kasaCommon, line 271
	} else { // library marker davegut.kasaCommon, line 272
		sendCmd("""{"system":{"set_dev_alias":{"alias":"${device.getLabel()}"}}}""") // library marker davegut.kasaCommon, line 273
	} // library marker davegut.kasaCommon, line 274
} // library marker davegut.kasaCommon, line 275

// ~~~~~ end include (545) davegut.kasaCommon ~~~~~

// ~~~~~ start include (546) davegut.kasaCommunications ~~~~~
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
	def val = getDataValue("devicePort")
	if (val == "null") {
		removeDataValue("devicePort")
	} else if (val) {
		port = val
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
}	//	6/5/4 // library marker davegut.kasaCommunications, line 80

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
//		distResp(jsonSlurper.parseText(response.result.responseData)) // library marker davegut.kasaCommunications, line 117
		def cmdResp = new JsonSlurper().parseText(response.result.responseData) // library marker davegut.kasaCommunications, line 118
		logDebug("cloudParse: ${cmdResp}") // library marker davegut.kasaCommunications, line 119
		distResp(cmdResp) // library marker davegut.kasaCommunications, line 120
		resetCommsError() // library marker davegut.kasaCommunications, line 121
	} else { // library marker davegut.kasaCommunications, line 122
		def msg = "sendKasaCmd:\n<b>Error from the Kasa Cloud.</b> Most common cause is " // library marker davegut.kasaCommunications, line 123
		msg += "your Kasa Token has expired.  Run Kasa Login and Token update and try again." // library marker davegut.kasaCommunications, line 124
		msg += "\nAdditional Data: Error = ${resp.data}\n\n" // library marker davegut.kasaCommunications, line 125
		logDebug(msg) // library marker davegut.kasaCommunications, line 126
		handleCommsError() // library marker davegut.kasaCommunications, line 127
	} // library marker davegut.kasaCommunications, line 128
}	//	6.5.4 // library marker davegut.kasaCommunications, line 129

private sendTcpCmd(command) { // library marker davegut.kasaCommunications, line 131
	logDebug("sendTcpCmd: ${command}") // library marker davegut.kasaCommunications, line 132
	try { // library marker davegut.kasaCommunications, line 133
		interfaces.rawSocket.connect("${getDataValue("deviceIP")}", // library marker davegut.kasaCommunications, line 134
									 getPort().toInteger(), byteInterface: true) // library marker davegut.kasaCommunications, line 135
	} catch (error) { // library marker davegut.kasaCommunications, line 136
		logDebug("SendTcpCmd: Unable to connect to device at ${getDataValue("deviceIP")}:${getDataValue("devicePort")}. " + // library marker davegut.kasaCommunications, line 137
				 "Error = ${error}") // library marker davegut.kasaCommunications, line 138
	} // library marker davegut.kasaCommunications, line 139
	state.lastCommand = command // library marker davegut.kasaCommunications, line 140
	interfaces.rawSocket.sendMessage(outputXorTcp(command)) // library marker davegut.kasaCommunications, line 141
	runIn(2, close) // library marker davegut.kasaCommunications, line 142
} // library marker davegut.kasaCommunications, line 143

def close() { interfaces.rawSocket.close() } // library marker davegut.kasaCommunications, line 145

def socketStatus(message) { // library marker davegut.kasaCommunications, line 147
	if (message != "receive error: Stream closed.") { // library marker davegut.kasaCommunications, line 148
		logDebug("socketStatus: Socket Established") // library marker davegut.kasaCommunications, line 149
	} else { // library marker davegut.kasaCommunications, line 150
		logWarn("socketStatus = ${message}") // library marker davegut.kasaCommunications, line 151
	} // library marker davegut.kasaCommunications, line 152
} // library marker davegut.kasaCommunications, line 153

def parse(message) { // library marker davegut.kasaCommunications, line 155
	def response = state.response.concat(message) // library marker davegut.kasaCommunications, line 156
	state.response = response // library marker davegut.kasaCommunications, line 157
	runInMillis(50, extractTcpResp, [data: response]) // library marker davegut.kasaCommunications, line 158
} // library marker davegut.kasaCommunications, line 159

def extractTcpResp(response) { // library marker davegut.kasaCommunications, line 161
	state.response = "" // library marker davegut.kasaCommunications, line 162
	if (response.length() == null) { // library marker davegut.kasaCommunications, line 163
		logDebug("extractTcpResp: null return rejected.") // library marker davegut.kasaCommunications, line 164
		return  // library marker davegut.kasaCommunications, line 165
	} // library marker davegut.kasaCommunications, line 166
	logDebug("extractTcpResp: ${response}") // library marker davegut.kasaCommunications, line 167
	try { // library marker davegut.kasaCommunications, line 168
//		distResp(parseJson(inputXorTcp(response))) // library marker davegut.kasaCommunications, line 169
		def cmdResp = parseJson(inputXorTcp(response)) // library marker davegut.kasaCommunications, line 170
		logDebug("cloudParse: ${cmdResp}") // library marker davegut.kasaCommunications, line 171
		distResp(cmdResp) // library marker davegut.kasaCommunications, line 172
		resetCommsError() // library marker davegut.kasaCommunications, line 173
	} catch (e) { // library marker davegut.kasaCommunications, line 174
		logDebug("extractTcpResponse: comms error = ${e}") // library marker davegut.kasaCommunications, line 175
		handleCommsError() // library marker davegut.kasaCommunications, line 176
	} // library marker davegut.kasaCommunications, line 177
}	//	6.5.4 // library marker davegut.kasaCommunications, line 178

def handleCommsError() { // library marker davegut.kasaCommunications, line 180
	def count = state.errorCount + 1 // library marker davegut.kasaCommunications, line 181
	state.errorCount = count // library marker davegut.kasaCommunications, line 182
	def retry = true // library marker davegut.kasaCommunications, line 183
	def status = [count: count, command: state.lastCommand] // library marker davegut.kasaCommunications, line 184
	if (count == 3) { // library marker davegut.kasaCommunications, line 185
		def attemptFix = parent.fixConnection() // library marker davegut.kasaCommunications, line 186
		status << [attemptFixResult: [attemptFix]] // library marker davegut.kasaCommunications, line 187
	} else if (count >= 4) { // library marker davegut.kasaCommunications, line 188
		retry = false // library marker davegut.kasaCommunications, line 189
	} // library marker davegut.kasaCommunications, line 190
	if (retry == true) { // library marker davegut.kasaCommunications, line 191
		def commsTo = 5 // library marker davegut.kasaCommunications, line 192
		sendLanCmd(state.lastCommand, commsTo) // library marker davegut.kasaCommunications, line 193
		if (count > 1) { // library marker davegut.kasaCommunications, line 194
			logDebug("handleCommsError: [count: ${count}, timeout: ${commsTo}]") // library marker davegut.kasaCommunications, line 195
		} // library marker davegut.kasaCommunications, line 196
	} else { // library marker davegut.kasaCommunications, line 197
		setCommsError() // library marker davegut.kasaCommunications, line 198
	} // library marker davegut.kasaCommunications, line 199
	status << [retry: retry] // library marker davegut.kasaCommunications, line 200
	if (status.count > 2) { // library marker davegut.kasaCommunications, line 201
		logWarn("handleCommsError: ${status}") // library marker davegut.kasaCommunications, line 202
	} else { // library marker davegut.kasaCommunications, line 203
		logDebug("handleCommsError: ${status}") // library marker davegut.kasaCommunications, line 204
	} // library marker davegut.kasaCommunications, line 205
} // library marker davegut.kasaCommunications, line 206

def setCommsError() { // library marker davegut.kasaCommunications, line 208
	if (device.currentValue("commsError") == "false") { // library marker davegut.kasaCommunications, line 209
		def message = "Can't connect to your device at ${getDataValue("deviceIP")}:${getPort()}. " // library marker davegut.kasaCommunications, line 210
		message += "Refer to troubleshooting guide commsError section." // library marker davegut.kasaCommunications, line 211
		sendEvent(name: "commsError", value: "true") // library marker davegut.kasaCommunications, line 212
		state.COMMS_ERROR = message			 // library marker davegut.kasaCommunications, line 213
		logWarn("setCommsError: <b>${message}</b>") // library marker davegut.kasaCommunications, line 214
		runIn(15, limitPollInterval) // library marker davegut.kasaCommunications, line 215
	} // library marker davegut.kasaCommunications, line 216
} // library marker davegut.kasaCommunications, line 217

def limitPollInterval() { // library marker davegut.kasaCommunications, line 219
	state.nonErrorPollInterval = state.pollInterval // library marker davegut.kasaCommunications, line 220
	setPollInterval("30 minutes") // library marker davegut.kasaCommunications, line 221
} // library marker davegut.kasaCommunications, line 222

def resetCommsError() { // library marker davegut.kasaCommunications, line 224
	state.errorCount = 0 // library marker davegut.kasaCommunications, line 225
	if (device.currentValue("commsError") == "true") { // library marker davegut.kasaCommunications, line 226
		sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommunications, line 227
		setPollInterval(state.nonErrorPollInterval) // library marker davegut.kasaCommunications, line 228
		state.remove("nonErrorPollInterval") // library marker davegut.kasaCommunications, line 229
		state.remove("COMMS_ERROR") // library marker davegut.kasaCommunications, line 230
		logInfo("resetCommsError: Comms error cleared!") // library marker davegut.kasaCommunications, line 231
	} // library marker davegut.kasaCommunications, line 232
} // library marker davegut.kasaCommunications, line 233

private outputXOR(command) { // library marker davegut.kasaCommunications, line 235
	def str = "" // library marker davegut.kasaCommunications, line 236
	def encrCmd = "" // library marker davegut.kasaCommunications, line 237
 	def key = 0xAB // library marker davegut.kasaCommunications, line 238
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 239
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 240
		key = str // library marker davegut.kasaCommunications, line 241
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 242
	} // library marker davegut.kasaCommunications, line 243
   	return encrCmd // library marker davegut.kasaCommunications, line 244
} // library marker davegut.kasaCommunications, line 245

private inputXOR(encrResponse) { // library marker davegut.kasaCommunications, line 247
	String[] strBytes = encrResponse.split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 248
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 249
	def key = 0xAB // library marker davegut.kasaCommunications, line 250
	def nextKey // library marker davegut.kasaCommunications, line 251
	byte[] XORtemp // library marker davegut.kasaCommunications, line 252
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 253
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 254
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 255
		key = nextKey // library marker davegut.kasaCommunications, line 256
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 257
	} // library marker davegut.kasaCommunications, line 258
	return cmdResponse // library marker davegut.kasaCommunications, line 259
} // library marker davegut.kasaCommunications, line 260

private outputXorTcp(command) { // library marker davegut.kasaCommunications, line 262
	def str = "" // library marker davegut.kasaCommunications, line 263
	def encrCmd = "000000" + Integer.toHexString(command.length())  // library marker davegut.kasaCommunications, line 264
 	def key = 0xAB // library marker davegut.kasaCommunications, line 265
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 266
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 267
		key = str // library marker davegut.kasaCommunications, line 268
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 269
	} // library marker davegut.kasaCommunications, line 270
   	return encrCmd // library marker davegut.kasaCommunications, line 271
} // library marker davegut.kasaCommunications, line 272

private inputXorTcp(resp) { // library marker davegut.kasaCommunications, line 274
	String[] strBytes = resp.substring(8).split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 275
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 276
	def key = 0xAB // library marker davegut.kasaCommunications, line 277
	def nextKey // library marker davegut.kasaCommunications, line 278
	byte[] XORtemp // library marker davegut.kasaCommunications, line 279
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 280
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 281
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 282
		key = nextKey // library marker davegut.kasaCommunications, line 283
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 284
	} // library marker davegut.kasaCommunications, line 285
	return cmdResponse // library marker davegut.kasaCommunications, line 286
} // library marker davegut.kasaCommunications, line 287

def logTrace(msg){ // library marker davegut.kasaCommunications, line 289
	log.trace "[${device.getLabel()}: ${driverVer()}]: ${msg}" // library marker davegut.kasaCommunications, line 290
} // library marker davegut.kasaCommunications, line 291

def logInfo(msg) { // library marker davegut.kasaCommunications, line 293
	if(descriptionText == true) { // library marker davegut.kasaCommunications, line 294
		log.info "[${device.getLabel()}: ${driverVer()}]: ${msg}" // library marker davegut.kasaCommunications, line 295
	} // library marker davegut.kasaCommunications, line 296
} // library marker davegut.kasaCommunications, line 297

def logDebug(msg){ // library marker davegut.kasaCommunications, line 299
	if(debug == true) { // library marker davegut.kasaCommunications, line 300
		log.debug "[${device.getLabel()}: ${driverVer()}]: ${msg}" // library marker davegut.kasaCommunications, line 301
	} // library marker davegut.kasaCommunications, line 302
} // library marker davegut.kasaCommunications, line 303

def debugOff() { // library marker davegut.kasaCommunications, line 305
	device.updateSetting("debug", [type:"bool", value: false]) // library marker davegut.kasaCommunications, line 306
	logInfo("debugLogOff: Debug logging is off.") // library marker davegut.kasaCommunications, line 307
} // library marker davegut.kasaCommunications, line 308

def logWarn(msg) { // library marker davegut.kasaCommunications, line 310
	log.warn "[${device.getLabel()}: ${driverVer()}]: ${msg}" // library marker davegut.kasaCommunications, line 311
} // library marker davegut.kasaCommunications, line 312

// ~~~~~ end include (546) davegut.kasaCommunications ~~~~~

// ~~~~~ start include (547) davegut.kasaEnergyMonitor ~~~~~
library ( // library marker davegut.kasaEnergyMonitor, line 1
	name: "kasaEnergyMonitor", // library marker davegut.kasaEnergyMonitor, line 2
	namespace: "davegut", // library marker davegut.kasaEnergyMonitor, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaEnergyMonitor, line 4
	description: "Kasa Device Energy Monitor Methods", // library marker davegut.kasaEnergyMonitor, line 5
	category: "energyMonitor", // library marker davegut.kasaEnergyMonitor, line 6
	documentationLink: "" // library marker davegut.kasaEnergyMonitor, line 7
) // library marker davegut.kasaEnergyMonitor, line 8

def setupEmFunction() { // library marker davegut.kasaEnergyMonitor, line 10
	if (emFunction && device.currentValue("currMonthTotal") > 0) { // library marker davegut.kasaEnergyMonitor, line 11
		runEvery30Minutes(getEnergyToday) // library marker davegut.kasaEnergyMonitor, line 12
		schedule("10 0 0 * * ?", getEnergyThisMonth) // library marker davegut.kasaEnergyMonitor, line 13
		schedule("15 2 0 1 * ?", getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 14
		state.getEnergy = "This Month" // library marker davegut.kasaEnergyMonitor, line 15
		return "Continuing EM Function" // library marker davegut.kasaEnergyMonitor, line 16
	} else if (emFunction) { // library marker davegut.kasaEnergyMonitor, line 17
		sendEvent(name: "power", value: 0, unit: "W") // library marker davegut.kasaEnergyMonitor, line 18
		sendEvent(name: "energy", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 19
		sendEvent(name: "currMonthTotal", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 20
		sendEvent(name: "currMonthAvg", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 21
		sendEvent(name: "lastMonthTotal", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 22
		sendEvent(name: "lastMonthAvg", value: 0, unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 23
		state.response = "" // library marker davegut.kasaEnergyMonitor, line 24
		runEvery30Minutes(getEnergyToday) // library marker davegut.kasaEnergyMonitor, line 25
		schedule("10 0 0 * * ?", getEnergyThisMonth) // library marker davegut.kasaEnergyMonitor, line 26
		schedule("15 2 0 1 * ?", getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 27
		state.getEnergy = "This Month" // library marker davegut.kasaEnergyMonitor, line 28
		//	Run order / delay is critical for successful operation. // library marker davegut.kasaEnergyMonitor, line 29
		getEnergyThisMonth() // library marker davegut.kasaEnergyMonitor, line 30
		runIn(10, getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 31
		return "Initialized" // library marker davegut.kasaEnergyMonitor, line 32
	} else if (device.currentValue("power") != null) { // library marker davegut.kasaEnergyMonitor, line 33
		//	for power != null, EM had to be enabled at one time.  Set values to 0. // library marker davegut.kasaEnergyMonitor, line 34
		sendEvent(name: "power", value: 0) // library marker davegut.kasaEnergyMonitor, line 35
		sendEvent(name: "energy", value: 0) // library marker davegut.kasaEnergyMonitor, line 36
		sendEvent(name: "currMonthTotal", value: 0) // library marker davegut.kasaEnergyMonitor, line 37
		sendEvent(name: "currMonthAvg", value: 0) // library marker davegut.kasaEnergyMonitor, line 38
		sendEvent(name: "lastMonthTotal", value: 0) // library marker davegut.kasaEnergyMonitor, line 39
		sendEvent(name: "lastMonthAvg", value: 0) // library marker davegut.kasaEnergyMonitor, line 40
		state.remove("getEnergy") // library marker davegut.kasaEnergyMonitor, line 41
		return "Disabled" // library marker davegut.kasaEnergyMonitor, line 42
	} else { // library marker davegut.kasaEnergyMonitor, line 43
		return "Not initialized" // library marker davegut.kasaEnergyMonitor, line 44
	} // library marker davegut.kasaEnergyMonitor, line 45
} // library marker davegut.kasaEnergyMonitor, line 46

def getDate() { // library marker davegut.kasaEnergyMonitor, line 48
	def currDate = new Date() // library marker davegut.kasaEnergyMonitor, line 49
	int year = currDate.format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 50
	int month = currDate.format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 51
	int day = currDate.format("d").toInteger() // library marker davegut.kasaEnergyMonitor, line 52
	return [year: year, month: month, day: day] // library marker davegut.kasaEnergyMonitor, line 53
} // library marker davegut.kasaEnergyMonitor, line 54

def distEmeter(emeterResp) { // library marker davegut.kasaEnergyMonitor, line 56
	def date = getDate() // library marker davegut.kasaEnergyMonitor, line 57
	logDebug("distEmeter: ${emeterResp}, ${date}, ${state.getEnergy}") // library marker davegut.kasaEnergyMonitor, line 58
	def lastYear = date.year - 1 // library marker davegut.kasaEnergyMonitor, line 59
	if (emeterResp.get_realtime) { // library marker davegut.kasaEnergyMonitor, line 60
		setPower(emeterResp.get_realtime) // library marker davegut.kasaEnergyMonitor, line 61
	} else if (emeterResp.get_monthstat) { // library marker davegut.kasaEnergyMonitor, line 62
		def monthList = emeterResp.get_monthstat.month_list // library marker davegut.kasaEnergyMonitor, line 63
		if (state.getEnergy == "Today") { // library marker davegut.kasaEnergyMonitor, line 64
			setEnergyToday(monthList, date) // library marker davegut.kasaEnergyMonitor, line 65
		} else if (state.getEnergy == "This Month") { // library marker davegut.kasaEnergyMonitor, line 66
			setThisMonth(monthList, date) // library marker davegut.kasaEnergyMonitor, line 67
		} else if (state.getEnergy == "Last Month") { // library marker davegut.kasaEnergyMonitor, line 68
			setLastMonth(monthList, date) // library marker davegut.kasaEnergyMonitor, line 69
		} else if (monthList == []) { // library marker davegut.kasaEnergyMonitor, line 70
			logDebug("distEmeter: monthList Empty. No data for year.") // library marker davegut.kasaEnergyMonitor, line 71
		} // library marker davegut.kasaEnergyMonitor, line 72
	} else { // library marker davegut.kasaEnergyMonitor, line 73
		logWarn("distEmeter: Unhandled response = ${emeterResp}") // library marker davegut.kasaEnergyMonitor, line 74
	} // library marker davegut.kasaEnergyMonitor, line 75
} // library marker davegut.kasaEnergyMonitor, line 76

def getPower() { // library marker davegut.kasaEnergyMonitor, line 78
	if (device.currentValue("switch") == "on") { // library marker davegut.kasaEnergyMonitor, line 79
		getRealtime() // library marker davegut.kasaEnergyMonitor, line 80
	} else if (device.currentValue("power") != 0) { // library marker davegut.kasaEnergyMonitor, line 81
		sendEvent(name: "power", value: 0, descriptionText: "Watts", unit: "W", type: "digital") // library marker davegut.kasaEnergyMonitor, line 82
	} // library marker davegut.kasaEnergyMonitor, line 83
} // library marker davegut.kasaEnergyMonitor, line 84

def setPower(response) { // library marker davegut.kasaEnergyMonitor, line 86
	logDebug("setPower: ${response}") // library marker davegut.kasaEnergyMonitor, line 87
	def status = [:] // library marker davegut.kasaEnergyMonitor, line 88
	def power = response.power // library marker davegut.kasaEnergyMonitor, line 89
	if (power == null) { power = response.power_mw / 1000 } // library marker davegut.kasaEnergyMonitor, line 90
	power = (power + 0.5).toInteger() // library marker davegut.kasaEnergyMonitor, line 91
	def curPwr = device.currentValue("power") // library marker davegut.kasaEnergyMonitor, line 92
	def pwrChange = false // library marker davegut.kasaEnergyMonitor, line 93
	if (curPwr != power) { // library marker davegut.kasaEnergyMonitor, line 94
		if (curPwr == null || (curPwr == 0 && power > 0)) { // library marker davegut.kasaEnergyMonitor, line 95
			pwrChange = true // library marker davegut.kasaEnergyMonitor, line 96
		} else { // library marker davegut.kasaEnergyMonitor, line 97
			def changeRatio = Math.abs((power - curPwr) / curPwr) // library marker davegut.kasaEnergyMonitor, line 98
			if (changeRatio > 0.03) { // library marker davegut.kasaEnergyMonitor, line 99
				pwrChange = true // library marker davegut.kasaEnergyMonitor, line 100
			} // library marker davegut.kasaEnergyMonitor, line 101
		} // library marker davegut.kasaEnergyMonitor, line 102
	} // library marker davegut.kasaEnergyMonitor, line 103
	if (pwrChange == true) { // library marker davegut.kasaEnergyMonitor, line 104
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital") // library marker davegut.kasaEnergyMonitor, line 105
		status << [power: power] // library marker davegut.kasaEnergyMonitor, line 106
	} // library marker davegut.kasaEnergyMonitor, line 107
	if (status != [:]) { logInfo("setPower: ${status}") } // library marker davegut.kasaEnergyMonitor, line 108
} // library marker davegut.kasaEnergyMonitor, line 109

def getEnergyToday() { // library marker davegut.kasaEnergyMonitor, line 111
	state.getEnergy = "Today" // library marker davegut.kasaEnergyMonitor, line 112
	def year = getDate().year // library marker davegut.kasaEnergyMonitor, line 113
	logDebug("getEnergyToday: ${year}") // library marker davegut.kasaEnergyMonitor, line 114
	runIn(5, getMonthstat, [data: year]) // library marker davegut.kasaEnergyMonitor, line 115
} // library marker davegut.kasaEnergyMonitor, line 116

def setEnergyToday(monthList, date) { // library marker davegut.kasaEnergyMonitor, line 118
	logDebug("setEnergyToday: ${date}, ${monthList}") // library marker davegut.kasaEnergyMonitor, line 119
	def data = monthList.find { it.month == date.month && it.year == date.year} // library marker davegut.kasaEnergyMonitor, line 120
	def status = [:] // library marker davegut.kasaEnergyMonitor, line 121
	def energy = 0 // library marker davegut.kasaEnergyMonitor, line 122
	if (data == null) { // library marker davegut.kasaEnergyMonitor, line 123
		status << [msgError: "Return Data Null"] // library marker davegut.kasaEnergyMonitor, line 124
	} else { // library marker davegut.kasaEnergyMonitor, line 125
		energy = data.energy // library marker davegut.kasaEnergyMonitor, line 126
		if (energy == null) { energy = data.energy_wh/1000 } // library marker davegut.kasaEnergyMonitor, line 127
		energy = Math.round(100*energy)/100 - device.currentValue("currMonthTotal") // library marker davegut.kasaEnergyMonitor, line 128
	} // library marker davegut.kasaEnergyMonitor, line 129
	if (device.currentValue("energy") != energy) { // library marker davegut.kasaEnergyMonitor, line 130
		sendEvent(name: "energy", value: energy, descriptionText: "KiloWatt Hours", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 131
		status << [energy: energy] // library marker davegut.kasaEnergyMonitor, line 132
	} // library marker davegut.kasaEnergyMonitor, line 133
	if (status != [:]) { logInfo("setEnergyToday: ${status}") } // library marker davegut.kasaEnergyMonitor, line 134
	if (!state.getEnergy) { // library marker davegut.kasaEnergyMonitor, line 135
		schedule("10 0 0 * * ?", getEnergyThisMonth) // library marker davegut.kasaEnergyMonitor, line 136
		schedule("15 2 0 1 * ?", getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 137
		state.getEnergy = "This Month" // library marker davegut.kasaEnergyMonitor, line 138
		getEnergyThisMonth() // library marker davegut.kasaEnergyMonitor, line 139
		runIn(10, getEnergyLastMonth) // library marker davegut.kasaEnergyMonitor, line 140
	} // library marker davegut.kasaEnergyMonitor, line 141
} // library marker davegut.kasaEnergyMonitor, line 142

def getEnergyThisMonth() { // library marker davegut.kasaEnergyMonitor, line 144
	state.getEnergy = "This Month" // library marker davegut.kasaEnergyMonitor, line 145
	def year = getDate().year // library marker davegut.kasaEnergyMonitor, line 146
	logDebug("getEnergyThisMonth: ${year}") // library marker davegut.kasaEnergyMonitor, line 147
	runIn(5, getMonthstat, [data: year]) // library marker davegut.kasaEnergyMonitor, line 148
} // library marker davegut.kasaEnergyMonitor, line 149

def setThisMonth(monthList, date) { // library marker davegut.kasaEnergyMonitor, line 151
	logDebug("setThisMonth: ${date} // ${monthList}") // library marker davegut.kasaEnergyMonitor, line 152
	def data = monthList.find { it.month == date.month && it.year == date.year} // library marker davegut.kasaEnergyMonitor, line 153
	def status = [:] // library marker davegut.kasaEnergyMonitor, line 154
	def totEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 155
	def avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 156
	if (data == null) { // library marker davegut.kasaEnergyMonitor, line 157
		status << [msgError: "Return Data Null"] // library marker davegut.kasaEnergyMonitor, line 158
	} else { // library marker davegut.kasaEnergyMonitor, line 159
		status << [msgError: "OK"] // library marker davegut.kasaEnergyMonitor, line 160
		totEnergy = data.energy // library marker davegut.kasaEnergyMonitor, line 161
		if (totEnergy == null) { totEnergy = data.energy_wh/1000 } // library marker davegut.kasaEnergyMonitor, line 162
		if (date.day == 1) { // library marker davegut.kasaEnergyMonitor, line 163
			avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 164
		} else { // library marker davegut.kasaEnergyMonitor, line 165
			avgEnergy = totEnergy /(date.day - 1) // library marker davegut.kasaEnergyMonitor, line 166
		} // library marker davegut.kasaEnergyMonitor, line 167
	} // library marker davegut.kasaEnergyMonitor, line 168
	totEnergy = Math.round(100*totEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 169
	avgEnergy = Math.round(100*avgEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 170
	sendEvent(name: "currMonthTotal", value: totEnergy,  // library marker davegut.kasaEnergyMonitor, line 171
			  descriptionText: "KiloWatt Hours", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 172
	status << [currMonthTotal: totEnergy] // library marker davegut.kasaEnergyMonitor, line 173
	sendEvent(name: "currMonthAvg", value: avgEnergy,  // library marker davegut.kasaEnergyMonitor, line 174
		 	 descriptionText: "KiloWatt Hours per Day", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 175
	status << [currMonthAvg: avgEnergy] // library marker davegut.kasaEnergyMonitor, line 176
	//	Update energy today in sync with energyThisMonth // library marker davegut.kasaEnergyMonitor, line 177
	getEnergyToday() // library marker davegut.kasaEnergyMonitor, line 178
	logInfo("setThisMonth: ${status}") // library marker davegut.kasaEnergyMonitor, line 179
} // library marker davegut.kasaEnergyMonitor, line 180

def getEnergyLastMonth() { // library marker davegut.kasaEnergyMonitor, line 182
	state.getEnergy = "Last Month" // library marker davegut.kasaEnergyMonitor, line 183
	def date = getDate() // library marker davegut.kasaEnergyMonitor, line 184
	def year = date.year // library marker davegut.kasaEnergyMonitor, line 185
	if (date.month == 1) { // library marker davegut.kasaEnergyMonitor, line 186
		year = year - 1 // library marker davegut.kasaEnergyMonitor, line 187
	} // library marker davegut.kasaEnergyMonitor, line 188
	logDebug("getEnergyLastMonth: ${year}") // library marker davegut.kasaEnergyMonitor, line 189
	runIn(5, getMonthstat, [data: year]) // library marker davegut.kasaEnergyMonitor, line 190
} // library marker davegut.kasaEnergyMonitor, line 191

def setLastMonth(monthList, date) { // library marker davegut.kasaEnergyMonitor, line 193
	logDebug("setLastMonth: ${date} // ${monthList}") // library marker davegut.kasaEnergyMonitor, line 194
	def lastMonthYear = date.year // library marker davegut.kasaEnergyMonitor, line 195
	def lastMonth = date.month - 1 // library marker davegut.kasaEnergyMonitor, line 196
	if (date.month == 1) { // library marker davegut.kasaEnergyMonitor, line 197
		lastMonthYear -+ 1 // library marker davegut.kasaEnergyMonitor, line 198
		lastMonth = 12 // library marker davegut.kasaEnergyMonitor, line 199
	} // library marker davegut.kasaEnergyMonitor, line 200
	def data = monthList.find { it.month == lastMonth } // library marker davegut.kasaEnergyMonitor, line 201
	def status = [:] // library marker davegut.kasaEnergyMonitor, line 202
	def totEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 203
	def avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 204
	if (data == null) { // library marker davegut.kasaEnergyMonitor, line 205
		status << [msgError: "Return Data Null"] // library marker davegut.kasaEnergyMonitor, line 206
	} else { // library marker davegut.kasaEnergyMonitor, line 207
		status << [msgError: "OK"] // library marker davegut.kasaEnergyMonitor, line 208
		def monthLength // library marker davegut.kasaEnergyMonitor, line 209
		switch(lastMonth) { // library marker davegut.kasaEnergyMonitor, line 210
			case 4: // library marker davegut.kasaEnergyMonitor, line 211
			case 6: // library marker davegut.kasaEnergyMonitor, line 212
			case 9: // library marker davegut.kasaEnergyMonitor, line 213
			case 11: // library marker davegut.kasaEnergyMonitor, line 214
				monthLength = 30 // library marker davegut.kasaEnergyMonitor, line 215
				break // library marker davegut.kasaEnergyMonitor, line 216
			case 2: // library marker davegut.kasaEnergyMonitor, line 217
				monthLength = 28 // library marker davegut.kasaEnergyMonitor, line 218
				if (lastMonthYear == 2020 || lastMonthYear == 2024 || lastMonthYear == 2028) {  // library marker davegut.kasaEnergyMonitor, line 219
					monthLength = 29 // library marker davegut.kasaEnergyMonitor, line 220
				} // library marker davegut.kasaEnergyMonitor, line 221
				break // library marker davegut.kasaEnergyMonitor, line 222
			default: // library marker davegut.kasaEnergyMonitor, line 223
				monthLength = 31 // library marker davegut.kasaEnergyMonitor, line 224
		} // library marker davegut.kasaEnergyMonitor, line 225
		totEnergy = data.energy // library marker davegut.kasaEnergyMonitor, line 226
		if (totEnergy == null) { totEnergy = data.energy_wh/1000 } // library marker davegut.kasaEnergyMonitor, line 227
		avgEnergy = totEnergy / monthLength // library marker davegut.kasaEnergyMonitor, line 228
	} // library marker davegut.kasaEnergyMonitor, line 229
	totEnergy = Math.round(100*totEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 230
	avgEnergy = Math.round(100*avgEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 231
	sendEvent(name: "lastMonthTotal", value: totEnergy,  // library marker davegut.kasaEnergyMonitor, line 232
			  descriptionText: "KiloWatt Hours", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 233
	status << [lastMonthTotal: totEnergy] // library marker davegut.kasaEnergyMonitor, line 234
	sendEvent(name: "lastMonthAvg", value: avgEnergy,  // library marker davegut.kasaEnergyMonitor, line 235
			  descriptionText: "KiloWatt Hoursper Day", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 236
	status << [lastMonthAvg: avgEnergy] // library marker davegut.kasaEnergyMonitor, line 237
	logInfo("setLastMonth: ${status}") // library marker davegut.kasaEnergyMonitor, line 238
} // library marker davegut.kasaEnergyMonitor, line 239

//	===== API Commands: Energy Monitor ===== // library marker davegut.kasaEnergyMonitor, line 241
def getRealtime() { // library marker davegut.kasaEnergyMonitor, line 242
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaEnergyMonitor, line 243
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 244
				""""emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 245
	} else if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaEnergyMonitor, line 246
		sendCmd("""{"smartlife.iot.common.emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 247
	} else { // library marker davegut.kasaEnergyMonitor, line 248
		sendCmd("""{"emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 249
	} // library marker davegut.kasaEnergyMonitor, line 250
} // library marker davegut.kasaEnergyMonitor, line 251

def getMonthstat(year) { // library marker davegut.kasaEnergyMonitor, line 253
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaEnergyMonitor, line 254
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 255
				""""emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 256
	} else if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaEnergyMonitor, line 257
		sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 258
	} else { // library marker davegut.kasaEnergyMonitor, line 259
		sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 260
	} // library marker davegut.kasaEnergyMonitor, line 261
} // library marker davegut.kasaEnergyMonitor, line 262

// ~~~~~ end include (547) davegut.kasaEnergyMonitor ~~~~~
