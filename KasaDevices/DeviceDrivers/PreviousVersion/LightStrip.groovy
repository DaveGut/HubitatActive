/*	Kasa Device Driver Series
		Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
ver 6.5.0
===================================================================================================*/
def type() { return "Light Strip" }
def driverVer() { return "6.5.0" }

metadata {
	definition (name: "Kasa ${type()}",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/LightStrip.groovy"
			   ) {
        capability "Light"
		capability "Switch"
		capability "Switch Level"
		capability "Change Level"
 		capability "Refresh"
		capability "Actuator"
		capability "Color Temperature"
		capability "Color Mode"
		capability "Color Control"
		capability "Light Effects"
		command "effectSet", [[
			name: "Name for effect.", 
			type: "STRING"]]
		command "effectCreate"
		command "effectDelete", [[
			name: "Name for effect to delete.", 
			type: "STRING"]]
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["default", "5 seconds", "10 seconds", "15 seconds",
						  "30 seconds", "1 minute", "5 minutes",  "10 minutes",
						  "30 minutes"],
			type: "ENUM"]]
		capability "Power Meter"
		capability "Energy Meter"
		//	Psuedo Capability Energy Statistics
		attribute "currMonthTotal", "number"
		attribute "currMonthAvg", "number"
		attribute "lastMonthTotal", "number"
		attribute "lastMonthAvg", "number"
		//	SCommunications Attributes
		attribute "connection", "string"
		attribute "commsError", "string"
		//	Psuedo capability Light Presets
		command "bulbPresetCreate", [[
			name: "Name for preset.", 
			type: "STRING"]]
		command "bulbPresetDelete", [[
			name: "Name for preset.", 
			type: "STRING"]]
		command "bulbPresetSet", [[
			name: "Name for preset.", 
			type: "STRING"],[
			name: "Transition Time (seconds).", 
			type: "STRING"]]
		command "setRGB", [[
			name: "red,green,blue", 
			type: "STRING"]]
	}

	preferences {
		input ("emFunction", "bool", 
				   title: "Enable Energy Monitor", 
				   defaultValue: false)
		input ("syncEffects", "bool",
			   title: "Sync Effect Preset Data",
			   defaultValue: false)
		input ("syncBulbs", "bool",
			   title: "Sync Bulb Preset Data",
			   defaultValue: false)
		input ("debug", "bool",
			   title: "Debug logging, 30 min.", 
			   defaultValue: false)
		input ("nameSync", "enum", title: "Synchronize Names",
			   defaultValue: "none",
			   options: ["none": "Don't synchronize",
						 "device" : "Kasa device name master", 
						 "Hubitat" : "Hubitat label master"])
		input ("bind", "bool",
			   title: "Kasa Cloud Binding",
			   defalutValue: true)
		if (bind && parent.kasaToken) {
			input ("useCloud", "bool",
				   title: "Use Kasa Cloud for device control",
				   defaultValue: false)
		}
		input ("rebootDev", "bool",
			   title: "Reboot Device",
			   defaultValue: false)
	}
}

def installed() {
	def instStatus= installCommon()
	state.bulbPresets = [:]
	state.effectPresets = []
	sendEvent(name: "lightEffects", value: [])
	logInfo("installed: ${instStatus}")
	runIn(2, updated)
}

def updated() {
	def updStatus = updateCommon()
	if (!state.bulbPresets) { state.bulbPresets = [:] }
	if (!state.effectPresets) { state.effectPresets = [] }
	if (transition_Time == null) {
		device.updateSetting("transition_Time", [type:"number", value: 1])
	}
	if (syncBulbs) {
		updStatus << [syncBulbs: syncBulbPresets()]
	}
	if (syncEffects) {
		updStatus << [syncEffects: syncEffectPresets()]
	}
	logInfo("updated: ${updStatus}")
	runIn(3, refresh)
}

def on() {
	sendCmd("""{"smartlife.iot.lightStrip":{"set_light_state":{"on_off":1}}}""")
}

def off() {
	sendCmd("""{"smartlife.iot.lightStrip":{"set_light_state":{"on_off":0}}}""")
}

def setLevel(level, transTime = 0) {
	if (level < 0) { level = 0 }
	else if (level > 100) { level = 100 }
	if (level == 0) {
		off()
	} else {
		sendCmd("""{"smartlife.iot.lightStrip":{"set_light_state":{"ignore_default":1,"on_off":1,""" +
				""""brightness":${level}}}}""")
	}
}

def setColorTemperature(colorTemp, level = device.currentValue("level"), transTime = 0) {
	if (level < 0) { level = 0 }
	else if (level > 100) { level = 100 }
	if (level == 0) {
		off()
	} else {
		if (colorTemp < 1000) { colorTemp = 1000 }
		else if (colorTemp > 12000) { colorTemp = 12000 }
		def hsvData = getCtHslValue(colorTemp)
		state.currentCT = colorTemp
		sendCmd("""{"smartlife.iot.lightStrip":{"set_light_state":{"ignore_default":1,""" +
				""""on_off":1,"brightness":${level},"hue":${hsvData.hue},""" +
				""""saturation":${hsvData.saturation}}}}""")
	}
}

def getCtHslValue(kelvin) {
	kelvin = 100 * Math.round(kelvin / 100)
	switch(kelvin) {
		case 1000: rgb= [255, 56, 0]; break
		case 1100: rgb= [255, 71, 0]; break
		case 1200: rgb= [255, 83, 0]; break
		case 1300: rgb= [255, 93, 0]; break
		case 1400: rgb= [255, 101, 0]; break
		case 1500: rgb= [255, 109, 0]; break
		case 1600: rgb= [255, 115, 0]; break
		case 1700: rgb= [255, 121, 0]; break
		case 1800: rgb= [255, 126, 0]; break
		case 1900: rgb= [255, 131, 0]; break
		case 2000: rgb= [255, 138, 18]; break
		case 2100: rgb= [255, 142, 33]; break
		case 2200: rgb= [255, 147, 44]; break
		case 2300: rgb= [255, 152, 54]; break
		case 2400: rgb= [255, 157, 63]; break
		case 2500: rgb= [255, 161, 72]; break
		case 2600: rgb= [255, 165, 79]; break
		case 2700: rgb= [255, 169, 87]; break
		case 2800: rgb= [255, 173, 94]; break
		case 2900: rgb= [255, 177, 101]; break
		case 3000: rgb= [255, 180, 107]; break
		case 3100: rgb= [255, 184, 114]; break
		case 3200: rgb= [255, 187, 120]; break
		case 3300: rgb= [255, 190, 126]; break
		case 3400: rgb= [255, 193, 132]; break
		case 3500: rgb= [255, 196, 137]; break
		case 3600: rgb= [255, 199, 143]; break
		case 3700: rgb= [255, 201, 148]; break
		case 3800: rgb= [255, 204, 153]; break
		case 3900: rgb= [255, 206, 159]; break
		case 4000: rgb= [100, 209, 200]; break
		case 4100: rgb= [255, 211, 168]; break
		case 4200: rgb= [255, 213, 173]; break
		case 4300: rgb= [255, 215, 177]; break
		case 4400: rgb= [255, 217, 182]; break
		case 4500: rgb= [255, 219, 186]; break
		case 4600: rgb= [255, 221, 190]; break
		case 4700: rgb= [255, 223, 194]; break
		case 4800: rgb= [255, 225, 198]; break
		case 4900: rgb= [255, 227, 202]; break
		case 5000: rgb= [255, 228, 206]; break
		case 5100: rgb= [255, 230, 210]; break
		case 5200: rgb= [255, 232, 213]; break
		case 5300: rgb= [255, 233, 217]; break
		case 5400: rgb= [255, 235, 220]; break
		case 5500: rgb= [255, 236, 224]; break
		case 5600: rgb= [255, 238, 227]; break
		case 5700: rgb= [255, 239, 230]; break
		case 5800: rgb= [255, 240, 233]; break
		case 5900: rgb= [255, 242, 236]; break
		case 6000: rgb= [255, 243, 239]; break
		case 6100: rgb= [255, 244, 242]; break
		case 6200: rgb= [255, 245, 245]; break
		case 6300: rgb= [255, 246, 247]; break
		case 6400: rgb= [255, 248, 251]; break
		case 6500: rgb= [255, 249, 253]; break
		case 6600: rgb= [254, 249, 255]; break
		case 6700: rgb= [252, 247, 255]; break
		case 6800: rgb= [249, 246, 255]; break
		case 6900: rgb= [247, 245, 255]; break
		case 7000: rgb= [245, 243, 255]; break
		case 7100: rgb= [243, 242, 255]; break
		case 7200: rgb= [240, 241, 255]; break
		case 7300: rgb= [239, 240, 255]; break
		case 7400: rgb= [237, 239, 255]; break
		case 7500: rgb= [235, 238, 255]; break
		case 7600: rgb= [233, 237, 255]; break
		case 7700: rgb= [231, 236, 255]; break
		case 7800: rgb= [230, 235, 255]; break
		case 7900: rgb= [228, 234, 255]; break
		case 8000: rgb= [227, 233, 255]; break
		case 8100: rgb= [225, 232, 255]; break
		case 8200: rgb= [224, 231, 255]; break
		case 8300: rgb= [222, 230, 255]; break
		case 8400: rgb= [221, 230, 255]; break
		case 8500: rgb= [220, 229, 255]; break
		case 8600: rgb= [218, 229, 255]; break
		case 8700: rgb= [217, 227, 255]; break
		case 8800: rgb= [216, 227, 255]; break
		case 8900: rgb= [215, 226, 255]; break
		case 9000: rgb= [214, 225, 255]; break
		case 9100: rgb= [212, 225, 255]; break
		case 9200: rgb= [211, 224, 255]; break
		case 9300: rgb= [210, 223, 255]; break
		case 9400: rgb= [209, 223, 255]; break
		case 9500: rgb= [208, 222, 255]; break
		case 9600: rgb= [207, 221, 255]; break
		case 9700: rgb= [207, 221, 255]; break
		case 9800: rgb= [206, 220, 255]; break
		case 9900: rgb= [205, 220, 255]; break
		case 10000: rgb= [207, 218, 255]; break
		case 10100: rgb= [207, 218, 255]; break
		case 10200: rgb= [206, 217, 255]; break
		case 10300: rgb= [205, 217, 255]; break
		case 10400: rgb= [204, 216, 255]; break
		case 10500: rgb= [204, 216, 255]; break
		case 10600: rgb= [203, 215, 255]; break
		case 10700: rgb= [202, 215, 255]; break
		case 10800: rgb= [202, 214, 255]; break
		case 10900: rgb= [201, 214, 255]; break
		case 11000: rgb= [200, 213, 255]; break
		case 11100: rgb= [200, 213, 255]; break
		case 11200: rgb= [199, 212, 255]; break
		case 11300: rgb= [198, 212, 255]; break
		case 11400: rgb= [198, 212, 255]; break
		case 11500: rgb= [197, 211, 255]; break
		case 11600: rgb= [197, 211, 255]; break
		case 11700: rgb= [197, 210, 255]; break
		case 11800: rgb= [196, 210, 255]; break
		case 11900: rgb= [195, 210, 255]; break
		case 12000: rgb= [195, 209, 255]; break
		default:
			logWarn("setRgbData: Unknown.")
			colorName = "Unknown"
	}
	def hsvData = hubitat.helper.ColorUtils.rgbToHSV([rgb[0].toInteger(), rgb[1].toInteger(), rgb[2].toInteger()])
	def hue = (0.5 + hsvData[0]).toInteger()
	def saturation = (0.5 + hsvData[1]).toInteger()
	def level = (0.5 + hsvData[2]).toInteger()
	def hslData = [
		hue: hue,
		saturation: saturation,
		level: level
		]
	return hslData
}

def setHue(hue) { setColor([hue: hue]) }

def setSaturation(saturation) { setColor([saturation: saturation]) }

def setColor(Map color) {
	if (color == null) {
		LogWarn("setColor: Color map is null. Command not executed.")
		return
	}
	def level = device.currentValue("level")
	if (color.level) { level = color.level }
	def hue = device.currentValue("hue")
	if (color.hue || color.hue == 0) { hue = color.hue.toInteger() }
	def saturation = device.currentValue("saturation")
	if (color.saturation || color.saturation == 0) { saturation = color.saturation }
	hue = Math.round(0.49 + hue * 3.6).toInteger()
	if (hue < 0 || hue > 360 || saturation < 0 || saturation > 100 || level < 0 || level > 100) {
		logWarn("setColor: Entered hue, saturation, or level out of range! (H:${hue}, S:${saturation}, L:${level}")
        return
    }
	state.currentCT = 0
	sendCmd("""{"smartlife.iot.lightStrip":{"set_light_state":{"ignore_default":1,""" +
			""""on_off":1,"brightness":${level},"hue":${hue},"saturation":${saturation}}}}""")
}

def setRGB(rgb) {
	logDebug("setRGB: ${rgb}") 
	def rgbArray = rgb.split('\\,')
	def hsvData = hubitat.helper.ColorUtils.rgbToHSV([rgbArray[0].toInteger(), rgbArray[1].toInteger(), rgbArray[2].toInteger()])
	def hue = (0.5 + hsvData[0]).toInteger()
	def saturation = (0.5 + hsvData[1]).toInteger()
	def level = (0.5 + hsvData[2]).toInteger()
	def Map hslData = [
		hue: hue,
		saturation: saturation,
		level: level
		]
	setColor(hslData)
}

def refresh() { poll() }

def poll() {
	sendCmd("""{"system":{"get_sysinfo":{}}}""")
}

def distResp(response) {
	if (response["smartlife.iot.lightStrip"]) {
		sendCmd("""{"system":{"get_sysinfo":{}}}""")
	} else if (response.system) {
		if (response.system.get_sysinfo) {
			updateBulbData(response.system.get_sysinfo)
		} else if (response.system.reboot) {
			logWarn("distResp: Rebooting device.")
		} else if (response.system.set_dev_alias) {
			device.updateSetting("nameSync",[type:"enum", value:"none"])
			if (response.system.set_dev_alias.err_code != 0) {
				def msg = "distResp: Name Sync from Hubitat to Device returned an error."
				msg+= "Note: <b>some devices do not support syncing name from the hub.</b>\n\r"
				logWarn(msg)
			}
		} else {
			logWarn("distResp: Unhandled response = ${response}")
		}
	} else if (response["smartlife.iot.lighting_effect"]) {
		parseEffect(response["smartlife.iot.lighting_effect"])
	} else if (response["smartlife.iot.common.emeter"]) {
		def emeterResp = response["smartlife.iot.common.emeter"]
		distEmeter(emeterResp)
	} else if (response["smartlife.iot.common.cloud"]) {
		setBindUnbind(response["smartlife.iot.common.cloud"])
	} else if (response["smartlife.iot.common.system"]) {
		logWarn("distResp: Rebooting device")
	} else {
		logWarn("distResp: Unhandled response = ${response}")
	}
	resetCommsError()
}

def updateBulbData(status) {
	logDebug("updateBulbData: ${status}")
	def effect = status.lighting_effect_state
	status = status.light_state
	def deviceStatus = [:]
	def onOff = "on"
	if (status.on_off == 0) { onOff = "off" }
	deviceStatus << ["switch" : onOff]
	def isChange = false
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff, type: "digital")
		isChange = true
	}
	if (onOff == "on") {
		def colorMode = "RGB"
		if (effect.enable == 1) { colorMode = "EFFECTS" }
		else if (state.currentCT > 0) { colorMode = "CT" }
		def hue = status.hue
		def hubHue = (hue / 3.6).toInteger()
		def saturation = status.saturation
		def level = status.brightness
		if (status.groups) {
			hue = status.groups[0][2]
			saturation = status.groups[0][3]
			level = status.groups[0][4]
		}
		def colorTemp = state.currentCT
		def color = " "
		def colorName = " "
		def effectName = " "
		if (colorMode == "EFFECTS") {
			effectName = effect.name
			level = effect.brightness
			hubHue = 0	
			saturation = 0
			colorTemp = 0
		} else if (colorMode == "CT") {
			colorName = getCtName(colorTemp)
			hubHue = 0
			saturation = 0
		} else if (colorMode == "RGB") {
			colorName = getColorName(hue)
			color = "{hue: ${hubHue},saturation:${saturation},level: ${level}}"
		}
		if (level != device.currentValue("level")) {
			deviceStatus << ["level" : level]
			sendEvent(name: "level", value: level, unit: "%")
			isChange = true
		}
		if (effectName != device.currentValue("effectName")) {
			deviceStatus << ["effectName" : effectName]
			sendEvent(name: "effectName", value: effectName)
			isChange = true
		}
		if (device.currentValue("colorTemperature") != colorTemp) {
			isChange = true
			deviceStatus << ["colorTemp" : colorTemp]
			sendEvent(name: "colorTemperature", value: colorTemp)
		}
		if (color != device.currentValue("color")) {
			isChange = true
			deviceStatus << ["color" : color]
			sendEvent(name: "hue", value: hubHue)
			sendEvent(name: "saturation", value: saturation)
			sendEvent(name: "color", value: color)
		}
		if (device.currentValue("colorName") != colorName) {
			deviceStatus << ["colorName" : colorName]
			deviceStatus << ["colorMode" : colorMode]
			sendEvent(name: "colorMode", value: colorMode)
		    sendEvent(name: "colorName", value: colorName)
		}
	}
	if (isChange == true) {
		logInfo("updateBulbData: Status = ${deviceStatus}")
	}
	if(emFunction) { getPower() }
	if (nameSync == "device") {
		device.setLabel(status.alias)
		device.updateSetting("nameSync",[type:"enum", value:"none"])
	}
}

//	===== Effect Presets =====
def effectCreate() {
	state.createEffect = true
	sendCmd("""{"smartlife.iot.lighting_effect":{"get_lighting_effect":{}}}""")
}

def parseEffect(resp) {
	logDebug("parseEffect: ${resp}")
	if (resp.get_lighting_effect) {
		def effData = resp.get_lighting_effect
		def effName = effData.name
		if (state.createEffect == true) {
			def existngEffect = state.effectPresets.find { it.name == effName }
			if (existngEffect == null) {
				state.effectPresets << effData
				resetLightEffects()
				logDebug("parseEffect: ${effName} added to effectPresets")
			} else {
				logWarn("parseEffect: ${effName} already exists.")
			}
			state.remove("createEffect")
		}
		refresh()
	} else {
		if (resp.set_lighting_effect.err_code != 0) {
			logWarn("parseEffect: Error setting effect.")
		}
		sendCmd("""{"smartlife.iot.lighting_effect":{"get_lighting_effect":{}}}""")
	}
}

def resetLightEffects() {
	if (state.effectsPresets != [:]) {
		def lightEffects = []
		state.effectPresets.each{
			def name = """ "${it.name}" """
			lightEffects << name
		}
		sendEvent(name: "lightEffects", value: lightEffects)
	}
	return "Updated lightEffects list"
}

def setEffect(index) {
	logDebug("setEffect: effNo = ${index}")
	index = index.toInteger()
	def effectPresets = state.effectPresets
	if (effectPresets == []) {
		logWarn("setEffect: effectPresets database is empty.")
		return
	}
	def effData = effectPresets[index]
	sendEffect(effData)						 
}

def setPreviousEffect() {
	def effectPresets = state.effectPresets
	if (device.currentValue("colorMode") != "EFFECTS" || effectPresets == []) {
		logWarn("setPreviousEffect: Not available. Either not in Effects or data is empty.")
		return
	}
	def effName = device.currentValue("effectName").trim()
	def index = effectPresets.findIndexOf { it.name == effName }
	if (index == -1) {
		logWarn("setPreviousEffect: ${effName} not found in effectPresets.")
	} else {
		def size = effectPresets.size()
		if (index == 0) { index = size - 1 }
		else { index = index-1 }
		def effData = effectPresets[index]
		sendEffect(effData)						 
	}
}

def setNextEffect() {
	def effectPresets = state.effectPresets
	if (device.currentValue("colorMode") != "EFFECTS" || effectPresets == []) {
		logWarn("setNextEffect: Not available. Either not in Effects or data is empty.")
		return
	}
	def effName = device.currentValue("effectName").trim()
	def index = effectPresets.findIndexOf { it.name == effName }
	if (index == -1) {
		logWarn("setNextEffect: ${effName} not found in effectPresets.")
	} else {
		def size = effectPresets.size()
		if (index == size - 1) { index = 0 }
		else { index = index + 1 }
		def effData = effectPresets[index]
		sendEffect(effData)						 
	}
}

def effectSet(effName) {
	if (state.effectPresets == []) {
		logWarn("effectSet: effectPresets database is empty.")
		return
	}
	effName = effName.trim()
	logDebug("effectSet: ${effName}.")
	def effData = state.effectPresets.find { it.name == effName }
	if (effData == null) {
		logWarn("effectSet: ${effName} not found.")
		return
	}
	sendEffect(effData)
}

def effectDelete(effName) {
	sendEvent(name: "lightEffects", value: [])
	effName = effName.trim()
	def index = state.effectPresets.findIndexOf { it.name == effName }
	if (index == -1 || nameIndex == -1) {
		logWarn("effectDelete: ${effName} not in effectPresets!")
	} else {
		state.effectPresets.remove(index)
		resetLightEffects()
	}
	logDebug("effectDelete: deleted effect ${effName}")
}

def syncEffectPresets() {
	device.updateSetting("syncEffects", [type:"bool", value: false])
	parent.resetStates(device.deviceNetworkId)
	state.effectPresets.each{
		def effData = it
		parent.syncEffectPreset(effData, device.deviceNetworkId)
		pauseExecution(1000)
	}
	return "Synching"
}

def resetStates() { state.effectPresets = [] }

def updateEffectPreset(effData) {
	logDebug("updateEffectPreset: ${effData.name}")
	state.effectPresets << effData
	runIn(5, resetLightEffects)
}

def sendEffect(effData) {
	effData = new groovy.json.JsonBuilder(effData).toString()
	sendCmd("""{"smartlife.iot.lighting_effect":{"set_lighting_effect":""" +
			"""${effData}},"context":{"source":"<id>"}}""")
}

//	===== includes =====




// ~~~~~ start include (227) davegut.kasaCommon ~~~~~
library ( // library marker davegut.kasaCommon, line 1
	name: "kasaCommon", // library marker davegut.kasaCommon, line 2
	namespace: "davegut", // library marker davegut.kasaCommon, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaCommon, line 4
	description: "Kasa updated and preferences routines", // library marker davegut.kasaCommon, line 5
	category: "energyMonitor", // library marker davegut.kasaCommon, line 6
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
	updateDataValue("driverVersion", driverVer()) // library marker davegut.kasaCommon, line 26
	instStatus << [driverVersion: driverVer()] // library marker davegut.kasaCommon, line 27
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
	updStatus << [emFunction: setupEmFunction()] // library marker davegut.kasaCommon, line 46
	updStatus << [pollInterval: setPollInterval()] // library marker davegut.kasaCommon, line 47
	state.remove("ISSUE") // library marker davegut.kasaCommon, line 48
	if(getDataValue("driverVersion") != driverVer()){ // library marker davegut.kasaCommon, line 49
		updStatus << updateDriverData() // library marker davegut.kasaCommon, line 50
	} // library marker davegut.kasaCommon, line 51
	return updStatus // library marker davegut.kasaCommon, line 52
} // library marker davegut.kasaCommon, line 53

def updateDriverData() { // library marker davegut.kasaCommon, line 55
	def drvVer = getDataValue("driverVersion") // library marker davegut.kasaCommon, line 56
	state.remove("lastLanCmd") // library marker davegut.kasaCommon, line 57
	state.remove("commsErrorText") // library marker davegut.kasaCommon, line 58
	if (!state.pollInterval) { state.pollInterval = "30 minutes" } // library marker davegut.kasaCommon, line 59
	updateDataValue("driverVersion", driverVer()) // library marker davegut.kasaCommon, line 60
	return [driverVersion: driverVer()] // library marker davegut.kasaCommon, line 61
} // library marker davegut.kasaCommon, line 62

//	===== Preference Methods ===== // library marker davegut.kasaCommon, line 64
def setPollInterval(interval = state.pollInterval) { // library marker davegut.kasaCommon, line 65
	if (interval == "default" || interval == "off") { // library marker davegut.kasaCommon, line 66
		interval = "30 minutes" // library marker davegut.kasaCommon, line 67
	} else if (useCloud && interval.contains("sec")) { // library marker davegut.kasaCommon, line 68
		interval = "1 minute" // library marker davegut.kasaCommon, line 69
	} // library marker davegut.kasaCommon, line 70
	state.pollInterval = interval // library marker davegut.kasaCommon, line 71
	def pollInterval = interval.substring(0,2).toInteger() // library marker davegut.kasaCommon, line 72
	if (interval.contains("sec")) { // library marker davegut.kasaCommon, line 73
		def start = Math.round((pollInterval-1) * Math.random()).toInteger() // library marker davegut.kasaCommon, line 74
		schedule("${start}/${pollInterval} * * * * ?", "poll") // library marker davegut.kasaCommon, line 75
		state.pollWarning = "Polling intervals of less than one minute can take high " + // library marker davegut.kasaCommon, line 76
			"resources and may impact hub performance." // library marker davegut.kasaCommon, line 77
	} else { // library marker davegut.kasaCommon, line 78
		def start = Math.round(59 * Math.random()).toInteger() // library marker davegut.kasaCommon, line 79
		schedule("${start} */${pollInterval} * * * ?", "poll") // library marker davegut.kasaCommon, line 80
		state.remove("pollWarning") // library marker davegut.kasaCommon, line 81
	} // library marker davegut.kasaCommon, line 82
	logDebug("setPollInterval: interval = ${interval}.") // library marker davegut.kasaCommon, line 83
	return interval // library marker davegut.kasaCommon, line 84
} // library marker davegut.kasaCommon, line 85

def rebootDevice() { // library marker davegut.kasaCommon, line 87
	logWarn("rebootDevice: User Commanded Reboot Device!") // library marker davegut.kasaCommon, line 88
	device.updateSetting("rebootDev", [type:"bool", value: false]) // library marker davegut.kasaCommon, line 89
	if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaCommon, line 90
		sendCmd("""{"smartlife.iot.common.system":{"reboot":{"delay":1}}}""") // library marker davegut.kasaCommon, line 91
	} else { // library marker davegut.kasaCommon, line 92
		sendCmd("""{"system":{"reboot":{"delay":1}}}""") // library marker davegut.kasaCommon, line 93
	} // library marker davegut.kasaCommon, line 94
	pauseExecution(10000) // library marker davegut.kasaCommon, line 95
	return "REBOOTING DEVICE" // library marker davegut.kasaCommon, line 96
} // library marker davegut.kasaCommon, line 97

def bindUnbind() { // library marker davegut.kasaCommon, line 99
	def meth = "cnCloud" // library marker davegut.kasaCommon, line 100
	if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaCommon, line 101
		meth = "smartlife.iot.common.cloud" // library marker davegut.kasaCommon, line 102
	} // library marker davegut.kasaCommon, line 103
	def message // library marker davegut.kasaCommon, line 104
	if (bind == null) { // library marker davegut.kasaCommon, line 105
		message = "Getting bind state" // library marker davegut.kasaCommon, line 106
		sendCmd("""{"${meth}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 107
	} else if (getDataValue("deviceIP") == "CLOUD" || // library marker davegut.kasaCommon, line 108
			   type() == "Light Strip") { // library marker davegut.kasaCommon, line 109
		message = "Bind Only Device" // library marker davegut.kasaCommon, line 110
		sendLanCmd("""{"${meth}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 111
	} else if (bind == true) { // library marker davegut.kasaCommon, line 112
		if (!parent.kasaToken || parent.userName == null || parent.userPassword == null) { // library marker davegut.kasaCommon, line 113
			message = "Username/pwd not set" // library marker davegut.kasaCommon, line 114
			sendCmd("""{"${meth}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 115
		} else { // library marker davegut.kasaCommon, line 116
			message = "Binding" // library marker davegut.kasaCommon, line 117
			sendLanCmd("""{"${meth}":{"bind":{"username":"${parent.userName}",""" + // library marker davegut.kasaCommon, line 118
					   """"password":"${parent.userPassword}"}},""" + // library marker davegut.kasaCommon, line 119
					   """"${meth}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 120
		} // library marker davegut.kasaCommon, line 121
	} else if (bind == false) { // library marker davegut.kasaCommon, line 122
		message = "Unbinding" // library marker davegut.kasaCommon, line 123
		sendLanCmd("""{"${meth}":{"unbind":""},"${meth}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 124
	} // library marker davegut.kasaCommon, line 125
	pauseExecution(5000) // library marker davegut.kasaCommon, line 126
	return message // library marker davegut.kasaCommon, line 127
} // library marker davegut.kasaCommon, line 128

def setBindUnbind(cmdResp) { // library marker davegut.kasaCommon, line 130
	def bindState = true // library marker davegut.kasaCommon, line 131
	if (cmdResp.get_info) { // library marker davegut.kasaCommon, line 132
		if (cmdResp.get_info.binded == 0) { bindState = false } // library marker davegut.kasaCommon, line 133
		logInfo("setBindUnbind: Bind status set to ${bindState}") // library marker davegut.kasaCommon, line 134
		setCommsType(bindState) // library marker davegut.kasaCommon, line 135
	} else if (cmdResp.bind.err_code == 0){ // library marker davegut.kasaCommon, line 136
		def meth = "cnCloud" // library marker davegut.kasaCommon, line 137
		if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaCommon, line 138
			meth = "smartlife.iot.common.cloud" // library marker davegut.kasaCommon, line 139
		} // library marker davegut.kasaCommon, line 140
		sendLanCmd("""{"${meth}":{"get_info":{}}}""") // library marker davegut.kasaCommon, line 141
	} else { // library marker davegut.kasaCommon, line 142
		logWarn("setBindUnbind: Unhandled response: ${cmdResp}") // library marker davegut.kasaCommon, line 143
	} // library marker davegut.kasaCommon, line 144
} // library marker davegut.kasaCommon, line 145

def setCommsType(bindState) { // library marker davegut.kasaCommon, line 147
	def commsType = "LAN" // library marker davegut.kasaCommon, line 148
	def cloudCtrl = false // library marker davegut.kasaCommon, line 149
	if (getDataValue("deviceIP") == "CLOUD") { // library marker davegut.kasaCommon, line 150
		commsType = "CLOUD" // library marker davegut.kasaCommon, line 151
		cloudCtrl = true // library marker davegut.kasaCommon, line 152
	} else if (bindState == true && useCloud == true && parent.kasaToken) { // library marker davegut.kasaCommon, line 153
		commsType = "CLOUD" // library marker davegut.kasaCommon, line 154
		cloudCtrl = true // library marker davegut.kasaCommon, line 155
	} else if (altLan == true) { // library marker davegut.kasaCommon, line 156
		commsType = "AltLAN" // library marker davegut.kasaCommon, line 157
	} // library marker davegut.kasaCommon, line 158
	def commsSettings = [bind: bindState, useCloud: cloudCtrl, commsType: commsType] // library marker davegut.kasaCommon, line 159
	device.updateSetting("bind", [type:"bool", value: bindState]) // library marker davegut.kasaCommon, line 160
	device.updateSetting("useCloud", [type:"bool", value: cloudCtrl]) // library marker davegut.kasaCommon, line 161
	sendEvent(name: "connection", value: "${commsType}") // library marker davegut.kasaCommon, line 162
	logInfo("setCommsType: ${commsSettings}") // library marker davegut.kasaCommon, line 163
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaCommon, line 164
		def coordData = [:] // library marker davegut.kasaCommon, line 165
		coordData << [bind: bindState] // library marker davegut.kasaCommon, line 166
		coordData << [useCloud: cloudCtrl] // library marker davegut.kasaCommon, line 167
		coordData << [connection: commsType] // library marker davegut.kasaCommon, line 168
		parent.coordinate("commsData", coordData, getDataValue("deviceId"), getDataValue("plugNo")) // library marker davegut.kasaCommon, line 169
	} // library marker davegut.kasaCommon, line 170
	pauseExecution(1000) // library marker davegut.kasaCommon, line 171
} // library marker davegut.kasaCommon, line 172

def ledOn() { // library marker davegut.kasaCommon, line 174
	logDebug("ledOn: Setting LED to on") // library marker davegut.kasaCommon, line 175
	sendCmd("""{"system":{"set_led_off":{"off":0},""" + // library marker davegut.kasaCommon, line 176
			""""get_sysinfo":{}}}""") // library marker davegut.kasaCommon, line 177
} // library marker davegut.kasaCommon, line 178

def ledOff() { // library marker davegut.kasaCommon, line 180
	logDebug("ledOff: Setting LED to off") // library marker davegut.kasaCommon, line 181
	sendCmd("""{"system":{"set_led_off":{"off":1},""" + // library marker davegut.kasaCommon, line 182
			""""get_sysinfo":{}}}""") // library marker davegut.kasaCommon, line 183
} // library marker davegut.kasaCommon, line 184

def syncName() { // library marker davegut.kasaCommon, line 186
	def message // library marker davegut.kasaCommon, line 187
	if (nameSync == "Hubitat") { // library marker davegut.kasaCommon, line 188
		message = "Hubitat Label Sync" // library marker davegut.kasaCommon, line 189
		if (getDataValue("plugNo") != null) { // library marker davegut.kasaCommon, line 190
			sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaCommon, line 191
					""""system":{"set_dev_alias":{"alias":"${device.label}"}}}""") // library marker davegut.kasaCommon, line 192
		} else { // library marker davegut.kasaCommon, line 193
			sendCmd("""{"system":{"set_dev_alias":{"alias":"${device.label}"}}}""") // library marker davegut.kasaCommon, line 194
		} // library marker davegut.kasaCommon, line 195
	} else if (nameSync == "device") { // library marker davegut.kasaCommon, line 196
		message = "Device Alias Sync" // library marker davegut.kasaCommon, line 197
		poll() // library marker davegut.kasaCommon, line 198
	} else { // library marker davegut.kasaCommon, line 199
		message = "Not Syncing" // library marker davegut.kasaCommon, line 200
	} // library marker davegut.kasaCommon, line 201
	return message // library marker davegut.kasaCommon, line 202
} // library marker davegut.kasaCommon, line 203

// ~~~~~ end include (227) davegut.kasaCommon ~~~~~

// ~~~~~ start include (228) davegut.kasaCommunications ~~~~~
import groovy.json.JsonSlurper // library marker davegut.kasaCommunications, line 1
library ( // library marker davegut.kasaCommunications, line 2
	name: "kasaCommunications", // library marker davegut.kasaCommunications, line 3
	namespace: "davegut", // library marker davegut.kasaCommunications, line 4
	author: "Dave Gutheinz", // library marker davegut.kasaCommunications, line 5
	description: "Kasa Communications Methods", // library marker davegut.kasaCommunications, line 6
	category: "communications", // library marker davegut.kasaCommunications, line 7
	documentationLink: "" // library marker davegut.kasaCommunications, line 8
) // library marker davegut.kasaCommunications, line 9

def getPort() { // library marker davegut.kasaCommunications, line 11
	def port = 9999 // library marker davegut.kasaCommunications, line 12
	if (getDataValue("devicePort")) { // library marker davegut.kasaCommunications, line 13
		port = getDataValue("devicePort") // library marker davegut.kasaCommunications, line 14
	} // library marker davegut.kasaCommunications, line 15
	return port // library marker davegut.kasaCommunications, line 16
} // library marker davegut.kasaCommunications, line 17

def sendCmd(command) { // library marker davegut.kasaCommunications, line 19
	if (device.currentValue("connection") == "LAN") { // library marker davegut.kasaCommunications, line 20
		sendLanCmd(command) // library marker davegut.kasaCommunications, line 21
	} else if (device.currentValue("connection") == "CLOUD"){ // library marker davegut.kasaCommunications, line 22
		sendKasaCmd(command) // library marker davegut.kasaCommunications, line 23
	} else if (device.currentValue("connection") == "AltLAN") { // library marker davegut.kasaCommunications, line 24
		sendTcpCmd(command) // library marker davegut.kasaCommunications, line 25
	} else { // library marker davegut.kasaCommunications, line 26
		logWarn("sendCmd: attribute connection is not set.") // library marker davegut.kasaCommunications, line 27
	} // library marker davegut.kasaCommunications, line 28
} // library marker davegut.kasaCommunications, line 29

def sendLanCmd(command) { // library marker davegut.kasaCommunications, line 31
	logDebug("sendLanCmd: command = ${command}") // library marker davegut.kasaCommunications, line 32
	if (!command.contains("password")) { // library marker davegut.kasaCommunications, line 33
		state.lastCommand = command // library marker davegut.kasaCommunications, line 34
	} // library marker davegut.kasaCommunications, line 35
	def myHubAction = new hubitat.device.HubAction( // library marker davegut.kasaCommunications, line 36
		outputXOR(command), // library marker davegut.kasaCommunications, line 37
		hubitat.device.Protocol.LAN, // library marker davegut.kasaCommunications, line 38
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT, // library marker davegut.kasaCommunications, line 39
		 destinationAddress: "${getDataValue("deviceIP")}:${getPort()}", // library marker davegut.kasaCommunications, line 40
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING, // library marker davegut.kasaCommunications, line 41
		 parseWarning: true, // library marker davegut.kasaCommunications, line 42
		 timeout: 10, // library marker davegut.kasaCommunications, line 43
		 callback: parseUdp]) // library marker davegut.kasaCommunications, line 44
	try { // library marker davegut.kasaCommunications, line 45
		sendHubCommand(myHubAction) // library marker davegut.kasaCommunications, line 46
	} catch (e) { // library marker davegut.kasaCommunications, line 47
		logWarn("sendLanCmd: LAN Error = ${e}") // library marker davegut.kasaCommunications, line 48
		handleCommsError() // library marker davegut.kasaCommunications, line 49
	} // library marker davegut.kasaCommunications, line 50
} // library marker davegut.kasaCommunications, line 51

def parseUdp(message) { // library marker davegut.kasaCommunications, line 53
	def resp = parseLanMessage(message) // library marker davegut.kasaCommunications, line 54
	if (resp.type == "LAN_TYPE_UDPCLIENT") { // library marker davegut.kasaCommunications, line 55
		def clearResp = inputXOR(resp.payload) // library marker davegut.kasaCommunications, line 56
		if (clearResp.length() > 1022) { // library marker davegut.kasaCommunications, line 57
			if (clearResp.contains("preferred")) { // library marker davegut.kasaCommunications, line 58
				clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}" // library marker davegut.kasaCommunications, line 59
			} else { // library marker davegut.kasaCommunications, line 60
				def msg = "parseUdp: Response is too long for Hubitat UDP implementation." // library marker davegut.kasaCommunications, line 61
				msg += "\n\t<b>Device attributes have not been updated.</b>" // library marker davegut.kasaCommunications, line 62
				if(device.getName().contains("Multi")) { // library marker davegut.kasaCommunications, line 63
					msg += "\n\t<b>HS300:</b>\tCheck your device names. The total Kasa App names of all " // library marker davegut.kasaCommunications, line 64
					msg += "\n\t\t\tdevice names can't exceed 96 charactrs (16 per device).\n\r" // library marker davegut.kasaCommunications, line 65
				} // library marker davegut.kasaCommunications, line 66
				logWarn(msg) // library marker davegut.kasaCommunications, line 67
				return // library marker davegut.kasaCommunications, line 68
			} // library marker davegut.kasaCommunications, line 69
		} // library marker davegut.kasaCommunications, line 70
		def cmdResp = new JsonSlurper().parseText(clearResp) // library marker davegut.kasaCommunications, line 71
		distResp(cmdResp) // library marker davegut.kasaCommunications, line 72
	} else { // library marker davegut.kasaCommunications, line 73
		logDebug("parse: LAN Error = ${resp.type}") // library marker davegut.kasaCommunications, line 74
		handleCommsError() // library marker davegut.kasaCommunications, line 75
	} // library marker davegut.kasaCommunications, line 76
} // library marker davegut.kasaCommunications, line 77

def sendKasaCmd(command) { // library marker davegut.kasaCommunications, line 79
	logDebug("sendKasaCmd: ${command}") // library marker davegut.kasaCommunications, line 80
	state.lastCommand = command // library marker davegut.kasaCommunications, line 81
	runIn(5, handleCommsError) // library marker davegut.kasaCommunications, line 82
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 83
	def cmdBody = [ // library marker davegut.kasaCommunications, line 84
		method: "passthrough", // library marker davegut.kasaCommunications, line 85
		params: [ // library marker davegut.kasaCommunications, line 86
			deviceId: getDataValue("deviceId"), // library marker davegut.kasaCommunications, line 87
			requestData: "${command}" // library marker davegut.kasaCommunications, line 88
		] // library marker davegut.kasaCommunications, line 89
	] // library marker davegut.kasaCommunications, line 90
	def sendCloudCmdParams = [ // library marker davegut.kasaCommunications, line 91
		uri: "${parent.kasaCloudUrl}/?token=${parent.kasaToken}", // library marker davegut.kasaCommunications, line 92
		requestContentType: 'application/json', // library marker davegut.kasaCommunications, line 93
		contentType: 'application/json', // library marker davegut.kasaCommunications, line 94
		headers: ['Accept':'application/json; version=1, */*; q=0.01'], // library marker davegut.kasaCommunications, line 95
		timeout: 5, // library marker davegut.kasaCommunications, line 96
		body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.kasaCommunications, line 97
	] // library marker davegut.kasaCommunications, line 98
	try { // library marker davegut.kasaCommunications, line 99
		httpPostJson(sendCloudCmdParams) {resp -> // library marker davegut.kasaCommunications, line 100
			if (resp.status == 200 && resp.data.error_code == 0) { // library marker davegut.kasaCommunications, line 101
				def jsonSlurper = new groovy.json.JsonSlurper() // library marker davegut.kasaCommunications, line 102
				distResp(jsonSlurper.parseText(resp.data.result.responseData)) // library marker davegut.kasaCommunications, line 103
			} else { // library marker davegut.kasaCommunications, line 104
				def msg = "sendKasaCmd:\n<b>Error from the Kasa Cloud.</b> Most common cause is " // library marker davegut.kasaCommunications, line 105
				msg += "your Kasa Token has expired.  Run Kasa Login and Token update and try again." // library marker davegut.kasaCommunications, line 106
				msg += "\nAdditional Data: Error ${resp.data.error_code} = ${resp.data.msg}\n\n" // library marker davegut.kasaCommunications, line 107
				logWarn(msg) // library marker davegut.kasaCommunications, line 108
			} // library marker davegut.kasaCommunications, line 109
		} // library marker davegut.kasaCommunications, line 110
	} catch (e) { // library marker davegut.kasaCommunications, line 111
		def msg = "sendKasaCmd:\n<b>Error in Cloud Communications.</b> The Kasa Cloud is unreachable." // library marker davegut.kasaCommunications, line 112
		msg += "\nAdditional Data: Error = ${e}\n\n" // library marker davegut.kasaCommunications, line 113
		logWarn(msg) // library marker davegut.kasaCommunications, line 114
	} // library marker davegut.kasaCommunications, line 115
} // library marker davegut.kasaCommunications, line 116

private sendTcpCmd(command) { // library marker davegut.kasaCommunications, line 118
	logDebug("sendTcpCmd: ${command}") // library marker davegut.kasaCommunications, line 119
	try { // library marker davegut.kasaCommunications, line 120
		interfaces.rawSocket.connect("${getDataValue("deviceIP")}", // library marker davegut.kasaCommunications, line 121
									 getPort().toInteger(), byteInterface: true) // library marker davegut.kasaCommunications, line 122
	} catch (error) { // library marker davegut.kasaCommunications, line 123
		logDebug("SendTcpCmd: Unable to connect to device at ${getDataValue("deviceIP")}:${getDataValue("devicePort")}. " + // library marker davegut.kasaCommunications, line 124
				 "Error = ${error}") // library marker davegut.kasaCommunications, line 125
	} // library marker davegut.kasaCommunications, line 126
	runIn(5, handleCommsError) // library marker davegut.kasaCommunications, line 127
	state.lastCommand = command // library marker davegut.kasaCommunications, line 128
	interfaces.rawSocket.sendMessage(outputXorTcp(command)) // library marker davegut.kasaCommunications, line 129
} // library marker davegut.kasaCommunications, line 130

def socketStatus(message) { // library marker davegut.kasaCommunications, line 132
	if (message != "receive error: Stream closed.") { // library marker davegut.kasaCommunications, line 133
		logDebug("socketStatus: Socket Established") // library marker davegut.kasaCommunications, line 134
	} else { // library marker davegut.kasaCommunications, line 135
		logWarn("socketStatus = ${message}") // library marker davegut.kasaCommunications, line 136
	} // library marker davegut.kasaCommunications, line 137
} // library marker davegut.kasaCommunications, line 138

def parse(message) { // library marker davegut.kasaCommunications, line 140
	def respLength // library marker davegut.kasaCommunications, line 141
	if (message.length() > 8 && message.substring(0,4) == "0000") { // library marker davegut.kasaCommunications, line 142
		def hexBytes = message.substring(0,8) // library marker davegut.kasaCommunications, line 143
		respLength = 8 + 2 * hubitat.helper.HexUtils.hexStringToInt(hexBytes) // library marker davegut.kasaCommunications, line 144
		if (message.length() == respLength) { // library marker davegut.kasaCommunications, line 145
			extractResp(message) // library marker davegut.kasaCommunications, line 146
		} else { // library marker davegut.kasaCommunications, line 147
			state.response = message // library marker davegut.kasaCommunications, line 148
			state.respLength = respLength // library marker davegut.kasaCommunications, line 149
		} // library marker davegut.kasaCommunications, line 150
	} else if (message.length() == 0 || message == null) { // library marker davegut.kasaCommunications, line 151
		return // library marker davegut.kasaCommunications, line 152
	} else { // library marker davegut.kasaCommunications, line 153
		def resp = state.response // library marker davegut.kasaCommunications, line 154
		resp = resp.concat(message) // library marker davegut.kasaCommunications, line 155
		if (resp.length() == state.respLength) { // library marker davegut.kasaCommunications, line 156
			state.response = "" // library marker davegut.kasaCommunications, line 157
			state.respLength = 0 // library marker davegut.kasaCommunications, line 158
			extractResp(message) // library marker davegut.kasaCommunications, line 159
		} else { // library marker davegut.kasaCommunications, line 160
			state.response = resp // library marker davegut.kasaCommunications, line 161
		} // library marker davegut.kasaCommunications, line 162
	} // library marker davegut.kasaCommunications, line 163
} // library marker davegut.kasaCommunications, line 164

def extractResp(message) { // library marker davegut.kasaCommunications, line 166
	if (message.length() == null) { // library marker davegut.kasaCommunications, line 167
		logDebug("extractResp: null return rejected.") // library marker davegut.kasaCommunications, line 168
		return  // library marker davegut.kasaCommunications, line 169
	} // library marker davegut.kasaCommunications, line 170
	logDebug("extractResp: ${message}") // library marker davegut.kasaCommunications, line 171
	try { // library marker davegut.kasaCommunications, line 172
		distResp(parseJson(inputXorTcp(message))) // library marker davegut.kasaCommunications, line 173
	} catch (e) { // library marker davegut.kasaCommunications, line 174
		logWarn("extractResp: Invalid or incomplete return.\nerror = ${e}") // library marker davegut.kasaCommunications, line 175
		handleCommsError() // library marker davegut.kasaCommunications, line 176
	} // library marker davegut.kasaCommunications, line 177
} // library marker davegut.kasaCommunications, line 178

def handleCommsError() { // library marker davegut.kasaCommunications, line 180
	def count = state.errorCount + 1 // library marker davegut.kasaCommunications, line 181
	state.errorCount = count // library marker davegut.kasaCommunications, line 182
	def message = "handleCommsError: Count: ${count}." // library marker davegut.kasaCommunications, line 183
	if (count <= 3) { // library marker davegut.kasaCommunications, line 184
		message += "\n\t\t\t Retransmitting command, try = ${count}" // library marker davegut.kasaCommunications, line 185
		runIn(1, sendCmd, [data: state.lastCommand]) // library marker davegut.kasaCommunications, line 186
	} else if (count == 4) { // library marker davegut.kasaCommunications, line 187
		setCommsError() // library marker davegut.kasaCommunications, line 188
		message += "\n\t\t\t Setting Comms Error." // library marker davegut.kasaCommunications, line 189
	} // library marker davegut.kasaCommunications, line 190
	logDebug(message) // library marker davegut.kasaCommunications, line 191
} // library marker davegut.kasaCommunications, line 192

def setCommsError() { // library marker davegut.kasaCommunications, line 194
	def message = "setCommsError: Four consecutive errors.  Setting commsError to true." // library marker davegut.kasaCommunications, line 195
	if (device.currentValue("commsError") == "false") { // library marker davegut.kasaCommunications, line 196
		sendEvent(name: "commsError", value: "true") // library marker davegut.kasaCommunications, line 197
		message += "\n\t\tFix attempt ${parent.fixConnection(device.currentValue("connection"))}" // library marker davegut.kasaCommunications, line 198
		logWarn message // library marker davegut.kasaCommunications, line 199
	} // library marker davegut.kasaCommunications, line 200
} // library marker davegut.kasaCommunications, line 201

def resetCommsError() { // library marker davegut.kasaCommunications, line 203
	unschedule(handleCommsError) // library marker davegut.kasaCommunications, line 204
	sendEvent(name: "commsError", value: "false") // library marker davegut.kasaCommunications, line 205
	state.errorCount = 0 // library marker davegut.kasaCommunications, line 206
} // library marker davegut.kasaCommunications, line 207

private outputXOR(command) { // library marker davegut.kasaCommunications, line 209
	def str = "" // library marker davegut.kasaCommunications, line 210
	def encrCmd = "" // library marker davegut.kasaCommunications, line 211
 	def key = 0xAB // library marker davegut.kasaCommunications, line 212
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 213
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 214
		key = str // library marker davegut.kasaCommunications, line 215
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 216
	} // library marker davegut.kasaCommunications, line 217
   	return encrCmd // library marker davegut.kasaCommunications, line 218
} // library marker davegut.kasaCommunications, line 219

private inputXOR(encrResponse) { // library marker davegut.kasaCommunications, line 221
	String[] strBytes = encrResponse.split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 222
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 223
	def key = 0xAB // library marker davegut.kasaCommunications, line 224
	def nextKey // library marker davegut.kasaCommunications, line 225
	byte[] XORtemp // library marker davegut.kasaCommunications, line 226
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 227
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 228
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 229
		key = nextKey // library marker davegut.kasaCommunications, line 230
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 231
	} // library marker davegut.kasaCommunications, line 232
	return cmdResponse // library marker davegut.kasaCommunications, line 233
} // library marker davegut.kasaCommunications, line 234

private outputXorTcp(command) { // library marker davegut.kasaCommunications, line 236
	def str = "" // library marker davegut.kasaCommunications, line 237
	def encrCmd = "000000" + Integer.toHexString(command.length())  // library marker davegut.kasaCommunications, line 238
 	def key = 0xAB // library marker davegut.kasaCommunications, line 239
	for (int i = 0; i < command.length(); i++) { // library marker davegut.kasaCommunications, line 240
		str = (command.charAt(i) as byte) ^ key // library marker davegut.kasaCommunications, line 241
		key = str // library marker davegut.kasaCommunications, line 242
		encrCmd += Integer.toHexString(str) // library marker davegut.kasaCommunications, line 243
	} // library marker davegut.kasaCommunications, line 244
   	return encrCmd // library marker davegut.kasaCommunications, line 245
} // library marker davegut.kasaCommunications, line 246

private inputXorTcp(resp) { // library marker davegut.kasaCommunications, line 248
	String[] strBytes = resp.substring(8).split("(?<=\\G.{2})") // library marker davegut.kasaCommunications, line 249
	def cmdResponse = "" // library marker davegut.kasaCommunications, line 250
	def key = 0xAB // library marker davegut.kasaCommunications, line 251
	def nextKey // library marker davegut.kasaCommunications, line 252
	byte[] XORtemp // library marker davegut.kasaCommunications, line 253
	for(int i = 0; i < strBytes.length; i++) { // library marker davegut.kasaCommunications, line 254
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative // library marker davegut.kasaCommunications, line 255
		XORtemp = nextKey ^ key // library marker davegut.kasaCommunications, line 256
		key = nextKey // library marker davegut.kasaCommunications, line 257
		cmdResponse += new String(XORtemp) // library marker davegut.kasaCommunications, line 258
	} // library marker davegut.kasaCommunications, line 259
	return cmdResponse // library marker davegut.kasaCommunications, line 260
} // library marker davegut.kasaCommunications, line 261

def logTrace(msg){ // library marker davegut.kasaCommunications, line 263
	log.trace "[${type()} / ${driverVer()} / ${device.label}]| ${msg}" // library marker davegut.kasaCommunications, line 264
} // library marker davegut.kasaCommunications, line 265

def logInfo(msg) { // library marker davegut.kasaCommunications, line 267
	log.info "[${type()} / ${driverVer()} / ${device.label}]| ${msg}" // library marker davegut.kasaCommunications, line 268
} // library marker davegut.kasaCommunications, line 269

def logDebug(msg){ // library marker davegut.kasaCommunications, line 271
	if(debug == true) { // library marker davegut.kasaCommunications, line 272
		log.debug "[${type()} / ${driverVer()} / ${device.label}]| ${msg}" // library marker davegut.kasaCommunications, line 273
	} // library marker davegut.kasaCommunications, line 274
} // library marker davegut.kasaCommunications, line 275

def debugOff() { // library marker davegut.kasaCommunications, line 277
	device.updateSetting("debug", [type:"bool", value: false]) // library marker davegut.kasaCommunications, line 278
	logInfo("debugLogOff: Debug logging is off.") // library marker davegut.kasaCommunications, line 279
} // library marker davegut.kasaCommunications, line 280

def logWarn(msg) { // library marker davegut.kasaCommunications, line 282
	if (getDataValue("driverVersion") != driverVer()) { // library marker davegut.kasaCommunications, line 283
		msg += "\n\t\t<b>Run a Save Preferences and try again before reporting.</b>" // library marker davegut.kasaCommunications, line 284
		state.ISSUE = "<b>Run Save Preferences</b>\n\r" // library marker davegut.kasaCommunications, line 285
	} // library marker davegut.kasaCommunications, line 286
	log.warn "[${type()} / ${driverVer()} / ${device.label}]| ${msg}" // library marker davegut.kasaCommunications, line 287
} // library marker davegut.kasaCommunications, line 288

// ~~~~~ end include (228) davegut.kasaCommunications ~~~~~

// ~~~~~ start include (229) davegut.kasaEnergyMonitor ~~~~~
library ( // library marker davegut.kasaEnergyMonitor, line 1
	name: "kasaEnergyMonitor", // library marker davegut.kasaEnergyMonitor, line 2
	namespace: "davegut", // library marker davegut.kasaEnergyMonitor, line 3
	author: "Dave Gutheinz", // library marker davegut.kasaEnergyMonitor, line 4
	description: "Kasa energy monitor routines", // library marker davegut.kasaEnergyMonitor, line 5
	category: "energyMonitor", // library marker davegut.kasaEnergyMonitor, line 6
	documentationLink: "" // library marker davegut.kasaEnergyMonitor, line 7
) // library marker davegut.kasaEnergyMonitor, line 8

def setupEmFunction() { // library marker davegut.kasaEnergyMonitor, line 10
	if (emFunction) { // library marker davegut.kasaEnergyMonitor, line 11
		sendEvent(name: "power", value: 0) // library marker davegut.kasaEnergyMonitor, line 12
		sendEvent(name: "energy", value: 0) // library marker davegut.kasaEnergyMonitor, line 13
		sendEvent(name: "currMonthTotal", value: 0) // library marker davegut.kasaEnergyMonitor, line 14
		sendEvent(name: "currMonthAvg", value: 0) // library marker davegut.kasaEnergyMonitor, line 15
		sendEvent(name: "lastMonthTotal", value: 0) // library marker davegut.kasaEnergyMonitor, line 16
		sendEvent(name: "lastMonthAvg", value: 0) // library marker davegut.kasaEnergyMonitor, line 17
		def start = Math.round(30 * Math.random()).toInteger() // library marker davegut.kasaEnergyMonitor, line 18
		schedule("${start} */30 * * * ?", getEnergyToday) // library marker davegut.kasaEnergyMonitor, line 19
		runIn(1, getEnergyToday) // library marker davegut.kasaEnergyMonitor, line 20
		return "Initialized" // library marker davegut.kasaEnergyMonitor, line 21
	} else if (device.currentValue("power") != null) { // library marker davegut.kasaEnergyMonitor, line 22
		sendEvent(name: "power", value: 0) // library marker davegut.kasaEnergyMonitor, line 23
		sendEvent(name: "energy", value: 0) // library marker davegut.kasaEnergyMonitor, line 24
		sendEvent(name: "currMonthTotal", value: 0) // library marker davegut.kasaEnergyMonitor, line 25
		sendEvent(name: "currMonthAvg", value: 0) // library marker davegut.kasaEnergyMonitor, line 26
		sendEvent(name: "lastMonthTotal", value: 0) // library marker davegut.kasaEnergyMonitor, line 27
		sendEvent(name: "lastMonthAvg", value: 0) // library marker davegut.kasaEnergyMonitor, line 28
		return "Disabled" // library marker davegut.kasaEnergyMonitor, line 29
	} else { // library marker davegut.kasaEnergyMonitor, line 30
		return "Disabled" // library marker davegut.kasaEnergyMonitor, line 31
	} // library marker davegut.kasaEnergyMonitor, line 32
} // library marker davegut.kasaEnergyMonitor, line 33

def getPower() { // library marker davegut.kasaEnergyMonitor, line 35
	logDebug("getPower") // library marker davegut.kasaEnergyMonitor, line 36
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaEnergyMonitor, line 37
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 38
				""""emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 39
	} else if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaEnergyMonitor, line 40
		sendCmd("""{"smartlife.iot.common.emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 41
	} else { // library marker davegut.kasaEnergyMonitor, line 42
		sendCmd("""{"emeter":{"get_realtime":{}}}""") // library marker davegut.kasaEnergyMonitor, line 43
	} // library marker davegut.kasaEnergyMonitor, line 44
} // library marker davegut.kasaEnergyMonitor, line 45

def setPower(response) { // library marker davegut.kasaEnergyMonitor, line 47
	logDebug("setPower: ${response}") // library marker davegut.kasaEnergyMonitor, line 48
	def power = response.power // library marker davegut.kasaEnergyMonitor, line 49
	if (power == null) { power = response.power_mw / 1000 } // library marker davegut.kasaEnergyMonitor, line 50
	power = Math.round(10*(power))/10 // library marker davegut.kasaEnergyMonitor, line 51
	def curPwr = device.currentValue("power") // library marker davegut.kasaEnergyMonitor, line 52
	if (curPwr < 5 && (power > curPwr + 0.3 || power < curPwr - 0.3)) { // library marker davegut.kasaEnergyMonitor, line 53
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital") // library marker davegut.kasaEnergyMonitor, line 54
		logDebug("polResp: power = ${power}") // library marker davegut.kasaEnergyMonitor, line 55
	} else if (power > curPwr + 5 || power < curPwr - 5) { // library marker davegut.kasaEnergyMonitor, line 56
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital") // library marker davegut.kasaEnergyMonitor, line 57
		logDebug("polResp: power = ${power}") // library marker davegut.kasaEnergyMonitor, line 58
	} // library marker davegut.kasaEnergyMonitor, line 59
} // library marker davegut.kasaEnergyMonitor, line 60

def getEnergyToday() { // library marker davegut.kasaEnergyMonitor, line 62
	logDebug("getEnergyToday") // library marker davegut.kasaEnergyMonitor, line 63
	def year = new Date().format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 64
	if (getDataValue("plugNo") != null) { // library marker davegut.kasaEnergyMonitor, line 65
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 66
				""""emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 67
	} else if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaEnergyMonitor, line 68
		sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 69
	} else { // library marker davegut.kasaEnergyMonitor, line 70
		sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 71
	} // library marker davegut.kasaEnergyMonitor, line 72
} // library marker davegut.kasaEnergyMonitor, line 73

def setEnergyToday(response) { // library marker davegut.kasaEnergyMonitor, line 75
	logDebug("setEnergyToday: response = ${response}") // library marker davegut.kasaEnergyMonitor, line 76
	def month = new Date().format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 77
	def data = response.month_list.find { it.month == month } // library marker davegut.kasaEnergyMonitor, line 78
	def energy = data.energy // library marker davegut.kasaEnergyMonitor, line 79
	if (energy == null) { energy = data.energy_wh/1000 } // library marker davegut.kasaEnergyMonitor, line 80
	energy -= device.currentValue("currMonthTotal") // library marker davegut.kasaEnergyMonitor, line 81
	energy = Math.round(100*energy)/100 // library marker davegut.kasaEnergyMonitor, line 82
	def currEnergy = device.currentValue("energy") // library marker davegut.kasaEnergyMonitor, line 83
	if (currEnergy < energy + 0.05) { // library marker davegut.kasaEnergyMonitor, line 84
		sendEvent(name: "energy", value: energy, descriptionText: "KiloWatt Hours", unit: "KWH") // library marker davegut.kasaEnergyMonitor, line 85
		logDebug("setEngrToday: [energy: ${energy}]") // library marker davegut.kasaEnergyMonitor, line 86
	} // library marker davegut.kasaEnergyMonitor, line 87
	setThisMonth(response) // library marker davegut.kasaEnergyMonitor, line 88
} // library marker davegut.kasaEnergyMonitor, line 89

def setThisMonth(response) { // library marker davegut.kasaEnergyMonitor, line 91
	logDebug("setThisMonth: response = ${response}") // library marker davegut.kasaEnergyMonitor, line 92
	def month = new Date().format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 93
	def day = new Date().format("d").toInteger() // library marker davegut.kasaEnergyMonitor, line 94
	def data = response.month_list.find { it.month == month } // library marker davegut.kasaEnergyMonitor, line 95
	def totEnergy = data.energy // library marker davegut.kasaEnergyMonitor, line 96
	if (totEnergy == null) {  // library marker davegut.kasaEnergyMonitor, line 97
		totEnergy = data.energy_wh/1000 // library marker davegut.kasaEnergyMonitor, line 98
	} // library marker davegut.kasaEnergyMonitor, line 99
	totEnergy = Math.round(100*totEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 100
	def avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 101
	if (day != 1) {  // library marker davegut.kasaEnergyMonitor, line 102
		avgEnergy = totEnergy /(day - 1)  // library marker davegut.kasaEnergyMonitor, line 103
	} // library marker davegut.kasaEnergyMonitor, line 104
	avgEnergy = Math.round(100*avgEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 105

	sendEvent(name: "currMonthTotal", value: totEnergy,  // library marker davegut.kasaEnergyMonitor, line 107
			  descriptionText: "KiloWatt Hours", unit: "kWh") // library marker davegut.kasaEnergyMonitor, line 108
	sendEvent(name: "currMonthAvg", value: avgEnergy,  // library marker davegut.kasaEnergyMonitor, line 109
			  descriptionText: "KiloWatt Hours per Day", unit: "kWh/D") // library marker davegut.kasaEnergyMonitor, line 110
	logDebug("setThisMonth: Energy stats set to ${totEnergy} // ${avgEnergy}") // library marker davegut.kasaEnergyMonitor, line 111
	if (month != 1) { // library marker davegut.kasaEnergyMonitor, line 112
		setLastMonth(response) // library marker davegut.kasaEnergyMonitor, line 113
	} else { // library marker davegut.kasaEnergyMonitor, line 114
		def year = new Date().format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 115
		if (getDataValue("plugNo") != null) { // library marker davegut.kasaEnergyMonitor, line 116
			sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" + // library marker davegut.kasaEnergyMonitor, line 117
					""""emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 118
		} else if (type().contains("Bulb") || type().contains("Light")) { // library marker davegut.kasaEnergyMonitor, line 119
			sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 120
		} else { // library marker davegut.kasaEnergyMonitor, line 121
			sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""") // library marker davegut.kasaEnergyMonitor, line 122
		} // library marker davegut.kasaEnergyMonitor, line 123
	} // library marker davegut.kasaEnergyMonitor, line 124
} // library marker davegut.kasaEnergyMonitor, line 125

def setLastMonth(response) { // library marker davegut.kasaEnergyMonitor, line 127
	logDebug("setLastMonth: response = ${response}") // library marker davegut.kasaEnergyMonitor, line 128
	def year = new Date().format("yyyy").toInteger() // library marker davegut.kasaEnergyMonitor, line 129
	def month = new Date().format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 130
	def day = new Date().format("d").toInteger() // library marker davegut.kasaEnergyMonitor, line 131
	def lastMonth // library marker davegut.kasaEnergyMonitor, line 132
	if (month == 1) { // library marker davegut.kasaEnergyMonitor, line 133
		lastMonth = 12 // library marker davegut.kasaEnergyMonitor, line 134
	} else { // library marker davegut.kasaEnergyMonitor, line 135
		lastMonth = month - 1 // library marker davegut.kasaEnergyMonitor, line 136
	} // library marker davegut.kasaEnergyMonitor, line 137
	def monthLength // library marker davegut.kasaEnergyMonitor, line 138
	switch(lastMonth) { // library marker davegut.kasaEnergyMonitor, line 139
		case 4: // library marker davegut.kasaEnergyMonitor, line 140
		case 6: // library marker davegut.kasaEnergyMonitor, line 141
		case 9: // library marker davegut.kasaEnergyMonitor, line 142
		case 11: // library marker davegut.kasaEnergyMonitor, line 143
			monthLength = 30 // library marker davegut.kasaEnergyMonitor, line 144
			break // library marker davegut.kasaEnergyMonitor, line 145
		case 2: // library marker davegut.kasaEnergyMonitor, line 146
			monthLength = 28 // library marker davegut.kasaEnergyMonitor, line 147
			if (year == 2020 || year == 2024 || year == 2028) { monthLength = 29 } // library marker davegut.kasaEnergyMonitor, line 148
			break // library marker davegut.kasaEnergyMonitor, line 149
		default: // library marker davegut.kasaEnergyMonitor, line 150
			monthLength = 31 // library marker davegut.kasaEnergyMonitor, line 151
	} // library marker davegut.kasaEnergyMonitor, line 152
	def data = response.month_list.find { it.month == lastMonth } // library marker davegut.kasaEnergyMonitor, line 153
	def totEnergy // library marker davegut.kasaEnergyMonitor, line 154
	if (data == null) { // library marker davegut.kasaEnergyMonitor, line 155
		totEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 156
	} else { // library marker davegut.kasaEnergyMonitor, line 157
		totEnergy = data.energy // library marker davegut.kasaEnergyMonitor, line 158
		if (totEnergy == null) {  // library marker davegut.kasaEnergyMonitor, line 159
			totEnergy = data.energy_wh/1000 // library marker davegut.kasaEnergyMonitor, line 160
		} // library marker davegut.kasaEnergyMonitor, line 161
		totEnergy = Math.round(100*totEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 162
	} // library marker davegut.kasaEnergyMonitor, line 163
	def avgEnergy = 0 // library marker davegut.kasaEnergyMonitor, line 164
	if (day !=1) { // library marker davegut.kasaEnergyMonitor, line 165
		avgEnergy = totEnergy /(day - 1) // library marker davegut.kasaEnergyMonitor, line 166
	} // library marker davegut.kasaEnergyMonitor, line 167
	avgEnergy = Math.round(100*avgEnergy)/100 // library marker davegut.kasaEnergyMonitor, line 168
	sendEvent(name: "lastMonthTotal", value: totEnergy,  // library marker davegut.kasaEnergyMonitor, line 169
			  descriptionText: "KiloWatt Hours", unit: "kWh") // library marker davegut.kasaEnergyMonitor, line 170
	sendEvent(name: "lastMonthAvg", value: avgEnergy,  // library marker davegut.kasaEnergyMonitor, line 171
			  descriptionText: "KiloWatt Hoursper Day", unit: "kWh/D") // library marker davegut.kasaEnergyMonitor, line 172
	logDebug("setLastMonth: Energy stats set to ${totEnergy} // ${avgEnergy}") // library marker davegut.kasaEnergyMonitor, line 173
} // library marker davegut.kasaEnergyMonitor, line 174

def distEmeter(emeterResp) { // library marker davegut.kasaEnergyMonitor, line 176
	def month = new Date().format("M").toInteger() // library marker davegut.kasaEnergyMonitor, line 177
	if (emeterResp.get_realtime) { // library marker davegut.kasaEnergyMonitor, line 178
		setPower(emeterResp.get_realtime) // library marker davegut.kasaEnergyMonitor, line 179
	} else if (emeterResp.get_monthstat.month_list.find { it.month == month }) { // library marker davegut.kasaEnergyMonitor, line 180
		setEnergyToday(emeterResp.get_monthstat) // library marker davegut.kasaEnergyMonitor, line 181
	} else if (emeterResp.get_monthstat.month_list.find { it.month == month - 1 }) { // library marker davegut.kasaEnergyMonitor, line 182
		setLastMonth(emeterResp.get_monthstat) // library marker davegut.kasaEnergyMonitor, line 183
	} else { // library marker davegut.kasaEnergyMonitor, line 184
		logWarn("distEmeter: Unhandled response = ${response}") // library marker davegut.kasaEnergyMonitor, line 185
	} // library marker davegut.kasaEnergyMonitor, line 186
} // library marker davegut.kasaEnergyMonitor, line 187

// ~~~~~ end include (229) davegut.kasaEnergyMonitor ~~~~~

// ~~~~~ start include (226) davegut.bulbTools ~~~~~
/*	bulb tools // library marker davegut.bulbTools, line 1

		Copyright Dave Gutheinz // library marker davegut.bulbTools, line 3

License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md // library marker davegut.bulbTools, line 5

This library contains tools that can be useful to bulb developers in the future. // library marker davegut.bulbTools, line 7
It is designed to be hardware and communications agnostic.  Each method, when  // library marker davegut.bulbTools, line 8
called, returns the data within the specifications below. // library marker davegut.bulbTools, line 9
===================================================================================================*/ // library marker davegut.bulbTools, line 10
library ( // library marker davegut.bulbTools, line 11
	name: "bulbTools", // library marker davegut.bulbTools, line 12
	namespace: "davegut", // library marker davegut.bulbTools, line 13
	author: "Dave Gutheinz", // library marker davegut.bulbTools, line 14
	description: "Bulb and Light Strip Tools", // library marker davegut.bulbTools, line 15
	category: "utility", // library marker davegut.bulbTools, line 16
	documentationLink: "" // library marker davegut.bulbTools, line 17
) // library marker davegut.bulbTools, line 18

//	===== Level Up/Down for Bulbs and Dimmers ===== // library marker davegut.bulbTools, line 20
def startLevelChange(direction) { // library marker davegut.bulbTools, line 21
	if (direction == "up") { levelUp() } // library marker davegut.bulbTools, line 22
	else { levelDown() } // library marker davegut.bulbTools, line 23
} // library marker davegut.bulbTools, line 24

def stopLevelChange() { // library marker davegut.bulbTools, line 26
	unschedule(levelUp) // library marker davegut.bulbTools, line 27
	unschedule(levelDown) // library marker davegut.bulbTools, line 28
} // library marker davegut.bulbTools, line 29

def levelUp() { // library marker davegut.bulbTools, line 31
	def curLevel = device.currentValue("level").toInteger() // library marker davegut.bulbTools, line 32
	if (curLevel == 100) { return } // library marker davegut.bulbTools, line 33
	def newLevel = curLevel + 4 // library marker davegut.bulbTools, line 34
	if (newLevel > 100) { newLevel = 100 } // library marker davegut.bulbTools, line 35
	setLevel(newLevel, 0) // library marker davegut.bulbTools, line 36
	runIn(1, levelUp) // library marker davegut.bulbTools, line 37
} // library marker davegut.bulbTools, line 38

def levelDown() { // library marker davegut.bulbTools, line 40
	def curLevel = device.currentValue("level").toInteger() // library marker davegut.bulbTools, line 41
	if (curLevel == 0) { return } // library marker davegut.bulbTools, line 42
	def newLevel = curLevel - 4 // library marker davegut.bulbTools, line 43
	if (newLevel < 0) { off() } // library marker davegut.bulbTools, line 44
	else { // library marker davegut.bulbTools, line 45
		setLevel(newLevel, 0) // library marker davegut.bulbTools, line 46
		runIn(1, levelDown) // library marker davegut.bulbTools, line 47
	} // library marker davegut.bulbTools, line 48
} // library marker davegut.bulbTools, line 49

//	===== Data Looksup / Create ===== // library marker davegut.bulbTools, line 51
def getCtName(temp){ // library marker davegut.bulbTools, line 52
    def value = temp.toInteger() // library marker davegut.bulbTools, line 53
    def colorName // library marker davegut.bulbTools, line 54
	if (value <= 2800) { colorName = "Incandescent" } // library marker davegut.bulbTools, line 55
	else if (value <= 3300) { colorName = "Soft White" } // library marker davegut.bulbTools, line 56
	else if (value <= 3500) { colorName = "Warm White" } // library marker davegut.bulbTools, line 57
	else if (value <= 4150) { colorName = "Moonlight" } // library marker davegut.bulbTools, line 58
	else if (value <= 5000) { colorName = "Horizon" } // library marker davegut.bulbTools, line 59
	else if (value <= 5500) { colorName = "Daylight" } // library marker davegut.bulbTools, line 60
	else if (value <= 6000) { colorName = "Electronic" } // library marker davegut.bulbTools, line 61
	else if (value <= 6500) { colorName = "Skylight" } // library marker davegut.bulbTools, line 62
	else { colorName = "Polar" } // library marker davegut.bulbTools, line 63
	return colorName // library marker davegut.bulbTools, line 64
} // library marker davegut.bulbTools, line 65

def getColorName(hue){ // library marker davegut.bulbTools, line 67
    def colorName // library marker davegut.bulbTools, line 68
	switch (hue){ // library marker davegut.bulbTools, line 69
		case 0..15: colorName = "Red" // library marker davegut.bulbTools, line 70
            break // library marker davegut.bulbTools, line 71
		case 16..45: colorName = "Orange" // library marker davegut.bulbTools, line 72
            break // library marker davegut.bulbTools, line 73
		case 46..75: colorName = "Yellow" // library marker davegut.bulbTools, line 74
            break // library marker davegut.bulbTools, line 75
		case 76..105: colorName = "Chartreuse" // library marker davegut.bulbTools, line 76
            break // library marker davegut.bulbTools, line 77
		case 106..135: colorName = "Green" // library marker davegut.bulbTools, line 78
            break // library marker davegut.bulbTools, line 79
		case 136..165: colorName = "Spring" // library marker davegut.bulbTools, line 80
            break // library marker davegut.bulbTools, line 81
		case 166..195: colorName = "Cyan" // library marker davegut.bulbTools, line 82
            break // library marker davegut.bulbTools, line 83
		case 196..225: colorName = "Azure" // library marker davegut.bulbTools, line 84
            break // library marker davegut.bulbTools, line 85
		case 226..255: colorName = "Blue" // library marker davegut.bulbTools, line 86
            break // library marker davegut.bulbTools, line 87
		case 256..285: colorName = "Violet" // library marker davegut.bulbTools, line 88
            break // library marker davegut.bulbTools, line 89
		case 286..315: colorName = "Magenta" // library marker davegut.bulbTools, line 90
            break // library marker davegut.bulbTools, line 91
		case 316..345: colorName = "Rose" // library marker davegut.bulbTools, line 92
            break // library marker davegut.bulbTools, line 93
		case 346..360: colorName = "Red" // library marker davegut.bulbTools, line 94
            break // library marker davegut.bulbTools, line 95
		default: // library marker davegut.bulbTools, line 96
			logWarn("setRgbData: Unknown.") // library marker davegut.bulbTools, line 97
			colorName = "Unknown" // library marker davegut.bulbTools, line 98
    } // library marker davegut.bulbTools, line 99
	return colorName // library marker davegut.bulbTools, line 100
} // library marker davegut.bulbTools, line 101

//	===== Bulb Presets ===== // library marker davegut.bulbTools, line 103
def bulbPresetCreate(psName) { // library marker davegut.bulbTools, line 104
	if (!state.bulbPresets) { state.bulbPresets = [:] } // library marker davegut.bulbTools, line 105
	psName = psName.trim() // library marker davegut.bulbTools, line 106
	logDebug("bulbPresetCreate: ${psName}") // library marker davegut.bulbTools, line 107
	def psData = [:] // library marker davegut.bulbTools, line 108
	psData["hue"] = device.currentValue("hue") // library marker davegut.bulbTools, line 109
	psData["saturation"] = device.currentValue("saturation") // library marker davegut.bulbTools, line 110
	psData["level"] = device.currentValue("level") // library marker davegut.bulbTools, line 111
	def colorTemp = device.currentValue("colorTemperature") // library marker davegut.bulbTools, line 112
	if (colorTemp == null) { colorTemp = 0 } // library marker davegut.bulbTools, line 113
	psData["colTemp"] = colorTemp // library marker davegut.bulbTools, line 114
	state.bulbPresets << ["${psName}": psData] // library marker davegut.bulbTools, line 115
} // library marker davegut.bulbTools, line 116

def bulbPresetDelete(psName) { // library marker davegut.bulbTools, line 118
	psName = psName.trim() // library marker davegut.bulbTools, line 119
	logDebug("bulbPresetDelete: ${psName}") // library marker davegut.bulbTools, line 120
	def presets = state.bulbPresets // library marker davegut.bulbTools, line 121
	if (presets.toString().contains(psName)) { // library marker davegut.bulbTools, line 122
		presets.remove(psName) // library marker davegut.bulbTools, line 123
	} else { // library marker davegut.bulbTools, line 124
		logWarn("bulbPresetDelete: ${psName} is not a valid name.") // library marker davegut.bulbTools, line 125
	} // library marker davegut.bulbTools, line 126
} // library marker davegut.bulbTools, line 127

def syncBulbPresets() { // library marker davegut.bulbTools, line 129
	device.updateSetting("syncBulbs", [type:"bool", value: false]) // library marker davegut.bulbTools, line 130
	parent.syncBulbPresets(state.bulbPresets, type()) // library marker davegut.bulbTools, line 131
	return "Syncing" // library marker davegut.bulbTools, line 132
} // library marker davegut.bulbTools, line 133

def updatePresets(bulbPresets) { // library marker davegut.bulbTools, line 135
	logDebug("updatePresets: Preset Bulb Data: ${bulbPresets}.") // library marker davegut.bulbTools, line 136
	state.bulbPresets = bulbPresets // library marker davegut.bulbTools, line 137
} // library marker davegut.bulbTools, line 138

def bulbPresetSet(psName, transTime = transition_Time) { // library marker davegut.bulbTools, line 140
	psName = psName.trim() // library marker davegut.bulbTools, line 141
	if (transTime == null) { transTime = 0 } // library marker davegut.bulbTools, line 142
	transTime = 1000 * transTime.toInteger() // library marker davegut.bulbTools, line 143
	if (state.bulbPresets."${psName}") { // library marker davegut.bulbTools, line 144
		def psData = state.bulbPresets."${psName}" // library marker davegut.bulbTools, line 145
		if (psData.colTemp > 0) { // library marker davegut.bulbTools, line 146
			setColorTemperature(psData.colTemp, psData.level, transTime) // library marker davegut.bulbTools, line 147
		} else { // library marker davegut.bulbTools, line 148
			setColor(psData) // library marker davegut.bulbTools, line 149
		} // library marker davegut.bulbTools, line 150
	} else { // library marker davegut.bulbTools, line 151
		logWarn("bulbPresetSet: ${psName} is not a valid name.") // library marker davegut.bulbTools, line 152
	} // library marker davegut.bulbTools, line 153
} // library marker davegut.bulbTools, line 154

// ~~~~~ end include (226) davegut.bulbTools ~~~~~
