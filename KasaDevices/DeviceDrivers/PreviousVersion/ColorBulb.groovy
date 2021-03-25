/*	Kasa Device Driver Series
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2021 History =====
01-25	6.0.0 Update.  Combine Cloud and LAN Driver code to one driver/app set.
02-01	6.1.0	a.	Combined driver files for ease maintenance.
				b.	Recreated setPollInterval (erroneously left out of 6.0).
				c.	Moved cloud comms to within driver / device.
				d.	Added energy monitor functions into bulbs.
				e.	Added interim support for the KL430 Light strip.
02-22	6.1.1	a.	Update to access kasaServerUrl and kasaToken from app.  Had problems with
					updating the data when enabling cloud access.
				b.	Reworked logic for bind/unbind and Lccal/Cloud due to problems with transition.
					Beefed up error message for these functions.
===================================================================================================*/
def driverVer() { return "6.1.1" }
def type() { return "Color Bulb" }
//def type() { return "CT Bulb" }
//def type() { return "Mono Bulb" }
def file = type().replaceAll(" ", "")
								   
metadata {
	definition (name: "Kasa ${type()}",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/DeviceDrivers/${file}.groovy"
			   ) {
        capability "Light"
		capability "Switch"
		capability "Switch Level"
		capability "Change Level"
 		capability "Refresh"
		capability "Actuator"
		if (type() != "Mono Bulb") {
			capability "Color Temperature"
			command "setCircadian"
			attribute "circadianState", "string"
		}
		if (type() == "Color Bulb" || type() == "Light Strip") {
			capability "Color Mode"
			capability "Color Control"
		}
//	Added EM Function
		capability "Power Meter"
		capability "Energy Meter"
		attribute "currMonthTotal", "number"
		attribute "currMonthAvg", "number"
		attribute "lastMonthTotal", "number"
		attribute "lastMonthAvg", "number"
	}
	preferences {
		def refreshIntervals = ["60": "1 minute", "300": "5 minutes", 
								"900": "15 minutes", "1800": "30 minutes"]
		input ("refreshInterval", "enum",
			   title: "Refresh Interval",
			   options: refreshIntervals,
			   defaultValue: "1800")
		input ("emFunction", "bool", 
			   title: "Enable Energy Monitor", 
			   defaultValue: false)
		input ("transition_Time", "num", 
			   title: "Default Transition time (seconds)", 
			   defaultValue: 0)
		if (type() == "Color Bulb" || type() == "Light Strip") {
			input ("highRes", "bool", 
				   title: "(Color Bulb) High Resolution Hue Scale", 
				   defaultValue: false)
		}
		input ("debug", "bool", 
			   title: "Enable debug logging", 
			   defaultValue: false)
		input ("descriptionText", "bool", 
			   title: "Enable description text logging", 
			   defaultValue: true)
		input ("bind", "enum",
			   options: ["0": "Unbound from Cloud", "1": "Bound to Cloud"],
			   title: "Kasa Cloud Binding <b>[Caution]</b>",
			   defaultValue: "1")
		if (bind == "1") {
			input ("useCloud", "bool",
				   title: "Use Kasa Cloud for device control (must already be bound to cloud)",
				   defaultValue: false)
		}
		input ("rebootDev", "bool",
			   title: "Reboot device <b>[Caution]</b>",
			   defaultValue: false)
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
	if (state.currentBind == null) {
		state.currentBind = bind
		logInfo("updated: state.currentBind updated to ${bind}")
	}
	if (state.currentCloud == null) {
		state.currentCloud = useCloud
		logInfo("updated: state.currentCloud updated to ${useCloud}")
	}
	if (rebootDev) {
		logInfo("updated: ${rebootDevice()}")
	}
	logInfo("updated: ${updateDriverData()}")
	if (debug == true) {
		runIn(1800, debugLogOff)
		logInfo("updated: Debug logging enabled for 30 minutes.")
	}
	logInfo("updated: Description text logging is ${descriptionText}.")
	state.transTime = 1000*transition_Time.toInteger()
	logInfo("updated: transition time set to ${transition_Time} seconds.")

	logInfo("updated: ${setCommsType()}")		//	set actual comms type after checking data
	logInfo("updated: ${bindUnbind()}.")
	
	def interval = "1800"
	if (!state.pollInterval) { state.pollInterval = "off" }
	if (refreshInterval) {
		interval = refreshInterval
	} else {
		device.updateSetting("refreshInterval", [type:"enum", value: "1800"])
	}
	logInfo("updated: ${setInterval(interval)}")
	
	if (emFunction) {
		pauseExecution(1000)
		sendEvent(name: "power", value: 0, descriptionText: "Watts", unit: "W")
		schedule("0 01 0 * * ?", updateEmStats)
		runEvery30Minutes(getEnergyToday)
		runIn(1, getEnergyToday)
		runIn(2, updateEmStats)
		logInfo("updated: Energy Monitor Function enabled.")
	}
	refresh()
}
def updateDriverData() {
	if (getDataValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
		return "Driver data updated to latest values."
	} else {
		return "Driver version and data already correct."
	}
}
def setCommsType() {
	def commsType = "local"
	if (state.currentCloud) { commsType = "Kasa cloud" }
	def commsParams = [:]
	commsParams["useKasaCloud"] = parent.useKasaCloud
	commsParams["kasaToken"] = parent.kasaToken
	commsParams["kasaCloudUrl"] = parent.kasaCloudUrl
	commsParams["deviceIP"] = getDataValue("deviceIP")	
	commsParams["useCloud"] = useCloud
	commsParams["currentCloud"] = state.currentCloud
	commsParams["bind"] = bind
	commsParams["currentBind"] = state.currentBind
	commsParams["commsType"] = commsType
	logDebug("setCommsType: ${commsParams}")

	def message
	if (state.currentCloud == useCloud) {
		message = "device already set use ${commsType} communications."
	} else if (useCloud) {
		if (!parent.useKasaCloud || state.currentBind == "0" || 
			!parent.kasaToken || !parent.kasaCloudUrl) {
			//	Cloud not available.
			logWarn("setCommsType: <b>Can't set to Kasa cloud communications.</b> Check items:" +
				    "\n1.  Kasa Integration app must be set to Interface to Kasa Cloud." +
				    "\n    * set Interface to Kasa Cloud in the app." +
				    "\n2.  Device must be bound to Kasa Cloud." +
				    "\n    * open the Kasa phone app and set device to remote control, or" +
				    "\n    * use device page and attempt updating w/o changing cloud parameters." +
				    "\n3.  The token is not set in the Kasa Integration app." +
				    "\n    * run Kasa Login and Token Update in the app." +
				    "\n4.  The kasaCloudUrl is not set int the Kasa Integration app." +
				    "\n    * run Update Installed Devices in the app.")
			commsType = "local"
			device.updateSetting("useCloud", [type:"bool", value: false])
			state.currentCloud = false
			message = "ERROR: device reset use ${commsType} communications."
		} else {
			commsType = "Kasa cloud"
			state.currentCloud = true
			message = "device set use ${commsType} communications."
		}
	} else if (!useCloud) {
		if (!getDataValue("deviceIP")) {
			//	No IP set - may not be able to use device locally.
			logWarn("setCommsType: <b>Device IP is not available.</b>  Check items:" +
				    "\n1.  Device IP not set." +
				    "\n    * Go to Kasa Integration app and run Update Installed Devices." +
				    "\n2.  Device firmware updated to not allow local comms." +
				    "\n    *Use cloud only.")
			commsType = "Kasa cloud"
			device.updateSetting("useCloud", [type:"bool", value: true])
			state.currentCloud = true
			message = "ERROR: device reset use ${commsType} communications."
		} else {
			commsType = "local"
			state.currentCloud = false
			message = "device set use ${commsType} communications."
		}
	} else { message = "useCloud not set to valid value." }

	if (commsType == "Kasa cloud") {
		state.remove("respLength")
		state.remove("response")
		state.remove("lastConnect")
		state.remove("errorCount")
		state.remove("socketTimeout")
	} else {
		state.respLength = 0
		state.response = ""
		state.lastConnect = 0
		state.errorCount = 0
	}
	return message
}
def bindUnbind() {
	def bindParams = [:]
	bindParams["currentCloud"] = state.currentCloud
	bindParams["bind"] = bind
	bindParams["currentBind"] = state.currentBind
	bindParams["password"] = parent.userPassword
	bindParams["name"] = parent.userName
	logDebug("bindUnbind: ${bindParams}")

	if (state.currentBind == bind) {
		message = "No change in bind state."
		sendCmd("""{"smartlife.iot.common.cloud":{"get_info":{}}}""")	//	get Bind state
	} else if (bind == "1") {
		if (!parent.userName || !parent.userPassword) {
			//	Username or password not set, can not bind.
			logWarn("bindUnbind: <b>Username or Password not set</b>  Check items:" +
				    "\n1.  userName in Kasa Integration app." +
				    "\n2.  userPassword in Kasa Integration app." +
				    "\nRun Kasa Login and Token Update in Kasa Integration app.")
			device.updateSetting("bind", [type:"enum", value: "0"])
			state.currentBind = "0"
			message = "ERROR: Username or Password not set."
			sendCmd("""{"smartlife.iot.common.cloud":{"get_info":{}}}""")	//	get Bind state
		} else {
			state.currentBind = "1"
			message = "Binding device to Kasa Cloud."
			sendCmd("""{"smartlife.iot.common.cloud":{"bind":{"username":"${parent.userName}",""" +
					""""password":"${parent.userPassword}"}},""" +
					""""smartlife.iot.common.cloud":{"get_info":{}}}""")
		}
	} else if (bind == "0") {
		if (state.currentCloud) {
			//	Can not unbind if current comms is cloud
			logWarn("bindUnbind: <b>Unable to unbind while using cloud.</b>  Try " +
				    "updating using cloud to local first.")
			device.updateSetting("bind", [type:"enum", value: "1"])
			state.currentBind = "1"
			message = "ERROR: Can't set to unbind while useCloud is true."
			sendCmd("""{"smartlife.iot.common.cloud":{"get_info":{}}}""")	//	get Bind state
		} else {
			state.currentBind = "0"
			message = "Unbinding device from Kasa Cloud."
			sendCmd("""{"smartlife.iot.common.cloud":{"unbind":""},""" +
					""""smartlife.iot.common.cloud":{"get_info":{}}}""")
		}
	} else { message = "ERROR.  Bind value not properly set." }
	pauseExecution 1000
	return message
}
def setBindUnbind(cmdResp) {
	def binded = cmdResp["smartlife.iot.common.cloud"].get_info.binded.toString()
	device.updateSetting("bind", [type:"enum", value: binded])
	pauseExecution(1000)
	state.currentBind = binded
	logInfo("setBindUnbind: Bind status set to ${binded}")
}
def setInterval(interval) {
	interval = interval.toInteger()
	def minInterval = (	interval/60).toInteger()
	schedule("0 */${minInterval} * * * ?", refresh)
	message += "\n\t\t\tPoll interval set to ${minInterval} minutes."
	return message
}
def rebootDevice() {
	logWarn("rebootDevice: User Commanded Reboot Device!")
	device.updateSetting("rebootDev", [type:"bool", value: false])
	sendCmd("""{"smartlife.iot.common.system":{"reboot":{"delay":1}}}""")
	pauseExecution(10000)
	return "REBOOTING DEVICE"
}

//	Setting command pre-amble for bulb vice light strip
def service() {
	def service = "smartlife.iot.smartbulb.lightingservice"
	if (getDataValue("feature") == "lightStrip") { service = "smartlife.iot.lightStrip" }
	return service
}
def method() {
	def method = "transition_light_state"
	if (getDataValue("feature") == "lightStrip") { method = "set_light_state" }
	return method
}

//	===== Command and Parse Methods =====
def on() {
	logDebug("on: transition time = ${state.transTime}")
	sendCmd("""{"${service()}":""" +
			"""{"${method()}":{"on_off":1,"transition_period":${state.transTime}}}}""")
}
def off() {
	logDebug("off: transition time = ${state.transTime}")
	sendCmd("""{"${service()}":""" +
			"""{"${method()}":{"on_off":0,"transition_period":${state.transTime}}}}""")
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
	sendCmd("""{"${service()}":""" +
			"""{"${method()}":{"ignore_default":1,"on_off":1,""" +
			""""brightness":${percentage},"transition_period":${rate}}}}""")
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

def refresh() {
	logDebug("refresh")
	sendCmd("""{"system":{"get_sysinfo":{}}}""")
}

def setColorTemperature(kelvin) {
	logDebug("setColorTemperature: colorTemp = ${kelvin}")
	def lowCt = 2500
	def highCt = 9000
	if (type() == "CT Bulb") {
		lowCt = 2700
		highCt = 6500
	}
	if (kelvin < lowCt) { kelvin = lowCt }
	if (kelvin > highCt) { kelvin = highCt }
	sendCmd("""{"${service()}":{"${method()}":""" +
			"""{"ignore_default":1,"on_off":1,"color_temp": ${kelvin},"hue":0,"saturation":0}}}""")
}

def setCircadian() {
	logDebug("setCircadian")
	sendCmd("""{"${service()}":""" +
			"""{"${method()}":{"mode":"circadian"}}}""")
}

def setHue(hue) {
	logDebug("setHue:  hue = ${hue}")
	setColor([hue: hue])
}
def setSaturation(saturation) {
	logDebug("setSaturation: saturation = ${saturation}")
	setColor([saturation: saturation])
}
def setColor(Map color) {
	logDebug("setColor:  color = ${color}")
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
	if (highRes != true) {
		hue = Math.round(0.49 + hue * 3.6).toInteger()
	}
	if (hue < 0 || hue > 360 || saturation < 0 || saturation > 100 || level < 0 || level > 100) {
		logWarn("setColor: Entered hue, saturation, or level out of range! (H:${hue}, S:${saturation}, L:${level}")
        return
    }
	sendCmd("""{"${service()}":{"${method()}":""" +
			"""{"ignore_default":1,"on_off":1,"brightness":${level},"color_temp":0,""" +
			""""hue":${hue},"saturation":${saturation}}}}""")
}
def updateBulbData(status) {
	logDebug("updateBulbData: ${status}")
	def deviceStatus = [:]
	if (status.on_off == 0) { 
		sendEvent(name: "switch", value: "off", type: "digital")
		deviceStatus << ["power" : "off"]
	} else {
		sendEvent(name: "switch", value: "on", type: "digital")
		deviceStatus << ["power" : "on"]
		sendEvent(name: "level", value: status.brightness, unit: "%")
		deviceStatus << ["level" : status.brightness]
		if (type() != "Mono Bulb") {
			sendEvent(name: "circadianState", value: status.mode)
			deviceStatus << ["mode" : status.mode]
			sendEvent(name: "colorTemperature", value: status.color_temp, unit: " K")
			deviceStatus << ["colorTemp" : status.color_temp]
			if (type() == "CT Bulb") { 
				setColorTempData(status.color_temp)
			} else {
				def hue = status.hue.toInteger()
				if (highRes != true) { hue = (hue / 3.6).toInteger() }
				sendEvent(name: "hue", value: hue)
				deviceStatus << ["hue" : hue]
				sendEvent(name: "saturation", value: status.saturation)
				deviceStatus << ["sat" : status.saturation]
				def color = [:]
				color << ["hue" : hue]
				color << ["saturation" : status.saturation]
				if (status.color_temp.toInteger() > 2000) {
					color << ["level" : 0]
				} else {
					color << ["level" : status.brightness]
				}
				sendEvent(name: "color", value: color)
				if (status.color_temp.toInteger() == 0) { 
					setRgbData(hue, status.saturation) }
				else { 
					setColorTempData(status.color_temp) 
				}
			}
		}
	}
	if(emFunction) { getPower() }
	logInfo("updateBulbData: Status = ${deviceStatus}")
}
def setColorTempData(temp){
	logDebug("setColorTempData: color temperature = ${temp}")
    def value = temp.toInteger()
	state.lastColorTemp = value
    def colorName
	if (value <= 2800) { colorName = "Incandescent" }
	else if (value <= 3300) { colorName = "Soft White" }
	else if (value <= 3500) { colorName = "Warm White" }
	else if (value <= 4150) { colorName = "Moonlight" }
	else if (value <= 5000) { colorName = "Horizon" }
	else if (value <= 5500) { colorName = "Daylight" }
	else if (value <= 6000) { colorName = "Electronic" }
	else if (value <= 6500) { colorName = "Skylight" }
	else { colorName = "Polar" }
	//	Color Bulb Only.
	if (device.currentValue("colorMode") != "CT") {
 		sendEvent(name: "colorMode", value: "CT")
		logInfo("setColorTempData: Color Mode is CT")
	}
	if (device.currentValue("colorName") != colorName) {
	    sendEvent(name: "colorName", value: colorName)
		logInfo("setColorTempData: Color name is ${colorName}.")
	}
}
def setRgbData(hue, saturation){
	logDebug("setRgbData: hue = ${hue} // highRes = ${highRes}")
	state.lastHue = hue
	state.lastSaturation = saturation
	if (highRes != true) { hue = (hue * 3.6).toInteger() }
    def colorName
	switch (hue){
		case 0..15: colorName = "Red"
            break
		case 16..45: colorName = "Orange"
            break
		case 46..75: colorName = "Yellow"
            break
		case 76..105: colorName = "Chartreuse"
            break
		case 106..135: colorName = "Green"
            break
		case 136..165: colorName = "Spring"
            break
		case 166..195: colorName = "Cyan"
            break
		case 196..225: colorName = "Azure"
            break
		case 226..255: colorName = "Blue"
            break
		case 256..285: colorName = "Violet"
            break
		case 286..315: colorName = "Magenta"
            break
		case 316..345: colorName = "Rose"
            break
		case 346..360: colorName = "Red"
            break
		default:
			logWarn("setRgbData: Unknown.")
			colorName = "Unknown"
    }
	if (device.currentValue("colorMode") != "RGB") {
 		sendEvent(name: "colorMode", value: "RGB")
		logInfo("setRgbData: Color Mode is RGB")
	}
	if (device.currentValue("colorName") != colorName) {
	    sendEvent(name: "colorName", value: colorName)
		logInfo("setRgbData: Color name is ${colorName}.")
	}
}

//	===== Device Energy Monitor Methods =====
def getPower() {
	logDebug("getPower")
	sendCmd("""{"smartlife.iot.common.emeter":{"get_realtime":{}}}""")
}
def setPower(resp) {
	logDebug("setPower: status = ${resp}")
	def power = resp.power
	if (power == null) { power = resp.power_mw / 1000 }
	power = (0.5 + Math.round(100*power)/100).toInteger()
	def curPwr = device.currentValue("power").toInteger()
	if (power > curPwr + 1 || power < curPwr - 1) { 
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W")
		logInfo("pollResp: power = ${power}")
	}
}

def getEnergyToday() {
	def year = new Date().format("yyyy").toInteger()
	def month = new Date().format("M").toInteger()
	sendCmd("""{"smartlife.iot.common.emeter":{"get_daystat":{"month": ${month}, "year": ${year}}}}""")
}
def setEnergyToday(resp) {
	logDebug("setEnergyToday: ${resp}")
	def day = new Date().format("d").toInteger()
	def data = resp.day_list.find { it.day == day }
	def energyData
	if (data == null) {
		energyData = 0
	} else {
		energyData = data.energy
		if (energyData == null) { energyData = data.energy_wh/1000 }
	}
	energyData = Math.round(100*energyData)/100
	if (energyData != device.currentValue("energy")) {
		sendEvent(name: "energy", value: energyData, descriptionText: "KiloWatt Hours", unit: "kWH")
		logInfo("setEngrToday: [energy: ${energyData}]")
	}
}

def updateEmStats() {
	logDebug("updateEmStats: Updating daily energy monitor data.")
	def year = new Date().format("yyyy").toInteger()
	sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""")
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
		sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year-1}}}}""")
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
	sendEvent(name: "lastMonthTotal", value: energyData, descriptionText: "KiloWatt Hours", unit: "KWH")
	sendEvent(name: "lastMonthAvg", value: avgEnergy, descriptionText: "KiloWatt Hoursper Day", unit: "KWH/D")
	logInfo("setLastMonth: Energy stats set to ${energyData} // ${avgEnergy}")
}

//	===== Communications =====
private sendCmd(command) {
	if (!useCloud) { sendLanCmd(command) }
	else { sendKasaCmd(command) }
}
//	LAN
private sendLanCmd(command) {
	logDebug("sendLanCmd: ${command}")
	runIn(2, rawSocketTimeout, [data: command])
	command = outputXOR(command)
	if (now() - state.lastConnect > 35000) {
		logDebug("sendLanCmd: Attempting to connect.....")
		try {
			interfaces.rawSocket.connect("${getDataValue("deviceIP")}", 
										 9999, byteInterface: true)
		} catch (error) {
			logDebug("SendCmd: Unable to connect to device at ${getDataValue("deviceIP")}. " +
					 "Error = ${error}")
			def pollEnabled = parent.pollForIps()
			if (pollEnabled == true) {
				logWarn("SendCmd: Attempting to update IP address via the Application.")
				runIn(10, rawSocketTimeout, [data: command])
			} else {
				logWarn("SendCmd: IP address update attempted within last hour./n" + 
					    "Check your device. Disable if not longer in use.")
			}
			return
		}
	}
	interfaces.rawSocket.sendMessage(command)
}
def rawSocketTimeout(command) {
	state.errorCount += 1
	if (state.errorCount <= 2) {
		logDebug("rawSocketTimeout: attempt = ${state.errorCount}")
		state.lastConnect = 0
		sendLanCmd(command)
	} else {
		logWarn("rawSocketTimeout: Retry on error limit exceeded. Error " +
				"count = ${state.errorCount}.  Run Application to update IP.")
		if (state.errorCount > 10) {
			unschedule(quickPoll)
			unschedule(refresh)
			logWarn("rawSocketTimeout: Quick Poll and Refresh Disabled.")
		}
	}
}
def socketStatus(message) {
	if (message == "receive error: Stream closed.") {
		logDebug("socketStatus: Socket Established")
	} else {
		logWarn("socketStatus = ${message}")
		logWarn("Check: Device Name must be first 5 characters of Model (i.e., HS200).")
	}
}
def parse(message) {
	def respLength
	if (message.length() > 8 && message.substring(0,4) == "0000") {
		def hexBytes = message.substring(0,8)
		respLength = 8 + 2 * hubitat.helper.HexUtils.hexStringToInt(hexBytes)
		if (message.length() == respLength) {
			prepResponse(message)
			state.lastConnect = now()
		} else {
			state.response = message
			state.respLength = respLength
		}
	} else {
		def resp = state.response
		resp = resp.concat(message)
		if (resp.length() == state.respLength) {
			state.response = ""
			state.respLength = 0
			state.lastConnect = now()
			prepResponse(resp)
		} else {
			state.response = resp
		}
	}
}
def prepResponse(response) {
	logDebug("prepResponse: response length = ${response.length()}")
	if (response.length() == null) {
		logDebug("distResp: null return rejected.")
		return 
	}
	def resp
	try {
		resp = parseJson(inputXOR(response))
	} catch (e) {
		resp = ["error": "Invalid or incomplete return. Error = ${e}"]
	}
	state.errorCount = 0
	unschedule(rawSocketTimeout)
	distResp(resp)
}
//	Cloud
private sendKasaCmd(command) {
	logDebug("sendKasaCmd: ${command}")
	def cmdResponse = ""
	if (!parent.kasaCloudUrl || !parent.kasaToken) {
			cmdResponse = ["error": "kasaCloudUrl or kasaToken not set in Kasa Integration app."]
			logWarn("sendKasaCmd: <b>Failed to communicate with Kasa Cloud.</b> Check items:" +
				    "\n1.  kasaCloudUrl must be set in Kasa Integration app." +
				    "\n2.  kasaToken must be set in the Kasa Integration app.")
		return
	}

	def cmdBody = [
		method: "passthrough",
		params: [
			deviceId: getDataValue("deviceId"),
			requestData: "${command}"
		]
	]
	def sendCloudCmdParams = [
		uri: "${parent.kasaCloudUrl}/?token=${parent.kasaToken}",
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		timeout: 5,
		body : new groovy.json.JsonBuilder(cmdBody).toString()
	]
	try {
		httpPostJson(sendCloudCmdParams) {resp ->
			if (resp.status == 200 && resp.data.error_code == 0) {
				def jsonSlurper = new groovy.json.JsonSlurper()
				cmdResponse = jsonSlurper.parseText(resp.data.result.responseData)
			} else {
				logWarn("sendKasaCmd: Error returned from Kasa Cloud")
				cmdResponse = ["error": "${resp.data.error_code} = ${resp.data.msg}"]
			}
		}
	} catch (e) {
		logWarn("sendKasaCmd: <b>Failed to communicate with Kasa Cloud.</b> Check items:" +
				"\n1.  kasaCloudUrl must be set in Kasa Integration app." +
				"\n2.  useKasaCloud must be set in Kasa Integration app." +
				"\n3.  kasaToken must be set in the Kasa Integration app.")
		cmdResponse = ["error": "Protocol Error = ${e}"]
	}
	distResp(cmdResponse)
}
//	Distribute to parsing methods
def distResp(response) {
	if (response["${service()}"]) {
		updateBulbData(response["${service()}"]."${method()}")
	} else if (response.system) {
		updateBulbData(response.system.get_sysinfo.light_state)
//	Added EM Function
	} else if (response["smartlife.iot.common.emeter"]) {
		def month = new Date().format("M").toInteger()
		def emeterResp = response["smartlife.iot.common.emeter"]
		if (emeterResp.get_realtime) {
			setPower(emeterResp.get_realtime)
		} else if (emeterResp.get_daystat) {
			setEnergyToday(emeterResp.get_daystat)
		} else if (emeterResp.get_monthstat.month_list.find { it.month == month }) {
			setThisMonth(emeterResp.get_monthstat)
		} else if (emeterResp.get_monthstat.month_list.find { it.month == month - 1 }) {
			setLastMonth(emeterResp.get_monthstat)
		}
	} else if (response["smartlife.iot.common.cloud"]) {
		setBindUnbind(response)
	} else if (response["smartlife.iot.common.system"]) {
		logInfo("distResp: Rebooting device")
	} else if (response.error) {
		logWarn("distResp: Error = ${response.error}")
	} else {
		logWarn("distResp: Unhandled response = ${response}")
	}
}

//	===== Encryption / Decryption =====
private outputXOR(command) {
	def str = ""
	def encrCmd = "000000" + Integer.toHexString(command.length()) 
 	def key = 0xAB
	for (int i = 0; i < command.length(); i++) {
		str = (command.charAt(i) as byte) ^ key
		key = str
		encrCmd += Integer.toHexString(str)
	}
   	return encrCmd
}
private inputXOR(resp) {
	String[] strBytes = resp.substring(8).split("(?<=\\G.{2})")
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

//	 ===== Logging =====
def logTrace(msg){ 
	log.trace "[${type()}/${driverVer()}] ${device.label} ${msg}"
}
def logInfo(msg) {
	if (descriptionText == true) { 
		log.info "[${type()}/${driverVer()}] ${device.label} ${msg}"
	}
}
def logDebug(msg){
	if(debug == true) {
		log.debug "[${type()}/${driverVer()}] ${device.label} ${msg}"
	}
}
def debugLogOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("debugLogOff: Debug logging is off.")
}
def logWarn(msg){ 
	log.warn "[${type()}/${driverVer()}] ${device.label} ${msg}"
}

//	End of File
