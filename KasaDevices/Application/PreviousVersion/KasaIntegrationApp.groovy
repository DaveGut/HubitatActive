/*	Kasa Local Integration
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2020 History =====
08.01	Release on new version 5.3.  Minor updates supporting 5.3.
08.25	5.3.1	Update Error Process to check for IPs on comms error.  Limited to once ever 15 min.
09.08	5.3.1.1	Added KP105 to list of Smart Plugs.
11.19	5.3.2	Added KP115 to the list of Energy Monitor Smart Plugs.
11.27	5.3.3	Fixed error handling to properly cancel quick polling and refresh after 10 errors.
12.31	5.3.4	a.	Added KL430 as a color bulb
				b.	Added capability to enter segment other than segment Hubit hub is on.
===== 2021 History =====
01.03	5.3.5	Fixed the other subnet integration issues.
=======================================================================================================*/
def appVersion() { return "5.3.5" }
import groovy.json.JsonSlurper

definition(
	name: "Kasa Integration",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Application to install TP-Link bulbs, plugs, and switches.",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	singleInstance: true,
	documentationLink: "https://github.com/DaveGut/Hubitat-TP-Link-Integration/wiki",
	importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/Application/KasaIntegrationApp.groovy"
)
preferences {
	page(name: "startPage")
	page(name: "mainPage")
	page(name: "addDevicesPage")
	page(name: "removeDevicesPage")
	page(name: "kasaToolsPage")
	page(name: "unbindDevicesPage")
	page(name: "bindDevicesPage")
	page(name: "ledOnPage")
	page(name: "ledOffPage")
	page(name: "rebootSingleDevicePage")
}
def installed() {
	log.info "installed"
}
def updated() { logDebug("updated") }
def uninstalled() {
    getAllChildDevices().each { 
        deleteChildDevice(it.deviceNetworkId)
    }
}

//	Application start-up methods
def startPage() {

	logInfo("starting Kasa Integration")
	app?.removeSetting("selectedAddDevices")
	app?.removeSetting("selectedRemoveDevices")
	app?.removeSetting("unbindDevices")
	app?.removeSetting("bindDevices")
	if (!debugLog) {
		app.updateSetting("debugLog", false)
		app.updateSetting("licenseAcknowledged", false)
	}
	state.hs300Error = ""

	if (!lanSegment) {
		def hub = location.hubs[0]
		def hubIpArray = hub.localIP.split('\\.')
		def networkPrefix = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")
		app?.updateSetting("lanSegment", [type:"string", value: networkPrefix])
	}
	state.devices = [:]
	findDevices(25, "parseDeviceData")
	
	return dynamicPage(name:"mainPage",
					   title:"<b>Kasa Local Hubitat Integration, Version ${appVersion()}</b>",
					   uninstall: false,
					   install: false) {
		section() {
			paragraph "<textarea rows=15 cols=50 readonly='true'>${license()}</textarea>"
			input "lanSegment", "string", 
				title: "<b>Device Lan Segment.</b>  Change if Device is on differnt segment from Hub.", 
				submitOnChange: true,
				defaultValue: true
			href "mainPage",
				title: "<b>Go to the Next Page</b>",
				description: "Goes to the Application Main Page."
		}
	}
}
def parseDeviceData(response) {
	def resp = parseLanMessage(response.description)
	if (resp.type != "LAN_TYPE_UDPCLIENT") { return }
	def clearResp = inputXOR(resp.payload)
	if (clearResp.length() > 1022) {
		if (clearResp.indexOf("HS300") != -1) {
			state.hs300Error = "<b>HS300 Error: </b>Parsing failed due to return length too long.\n" +
			"<b>Probable cause::</b> For the HS300, the names for the six plugs must not exceed a " +
			"total of 132 characters (or less}. \n<b>Using the Kasa App, " + 
			"shorten the HS300 plug names and try again.</b>"
			logWarn("parseDeviceData: ${state.hs300Error}")
			return
		}
		clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}"
	}
	def cmdResp
	cmdResp = new JsonSlurper().parseText(clearResp).system.get_sysinfo
	def ip = convertHexToIP(resp.ip)
	logDebug("parseDeviceData: ${ip} // ${cmdResp}")
//	5.3.5
	def dni
	if (cmdResp.mic_mac == null) {
		dni = cmdResp.mac.replace(/:/, "")
	} else {
		dni = cmdResp.mic_mac
	}
//	5.3.5
	def alias = cmdResp.alias
	def model = cmdResp.model.substring(0,5)
	def type = getType(model)
	def ledOff = ""
	if (type != "Mono Bulb" && type != "CT Bulb" && type != "Color Bulb"
		&& type != "Multi Plug" && type != "EM Multi Plug") {
		ledOff = true
		if (cmdResp.led_off == 0) { ledOff = false }
	}
	def plugNo
	def plugId
	if (cmdResp.children) {
		def childPlugs = cmdResp.children
		childPlugs.each {
			plugNo = it.id
			def plugDni = "${dni}${plugNo}"
			plugId = cmdResp.deviceId + plugNo
			alias = it.alias
			updateDevices(plugDni, ip, alias, model, type, plugNo, plugId, ledOff)
		}
	} else {
		updateDevices(dni, ip, alias, model, type, plugNo, plugId, ledOff)
	}
}

def getType(model) {
	switch(model) {
		case "HS100" :
		case "HS103" :
		case "HS105" :
		case "HS200" :
		case "HS210" :
		case "KP100" :
		case "KP105" :
			return "Plug Switch"
			break
		case "HS110" :
		case "KP115" :
			return "EM Plug"
			break
		case "KP200" :
		case "HS107" :
		case "KP303" :
		case "KP400" :
			return "Multi Plug"
			break
		case "HS300" :
			return "EM Multi Plug"
			break
		case "HS220" :
			return "Dimming Switch"
			break
		case "KB100" :
		case "LB100" :
		case "LB110" :
		case "KL110" :
		case "LB200" :
		case "KL50(" :
		case "KL60(" :
			return "Mono Bulb"
			break
		case "LB120" :
		case "KL120" :
			return "CT Bulb"
			break
		case "KB130" :
		case "LB130" :
		case "KL130" :
		case "LB230" :
		case "KL430" :
			return "Color Bulb"
			break
		default :
			logWarn("getType: Model not on current list.  Contact developer.")
	}
}
def updateDevices(dni, ip, alias, model, type, plugNo, plugId, ledOff) {
	logDebug("updateDevices")
	def devices = state.devices
	def device = [:]
	device["dni"] = dni
	device["ip"] = ip
	device["alias"] = alias
	device["model"] = model
	device["type"] = type
	device["plugNo"] = plugNo
	device["plugId"] = plugId
	device["ledOff"] = ledOff
	devices << ["${dni}" : device]
	def child = getChildDevice(dni)
	if (child) {
		child.updateDataValue("deviceIP", ip)
		child.updateDataValue("applicationVersion", appVersion())
		logInfo("updateDevices: ${alias} IP updated to ${ip}")
		child.updated()
		logInfo("updateDevices: ${alias} running updatDriverData")
	}		
	logInfo("updateDevices: ${alias} added to devices array")
}

//	Main page
def mainPage() {
	if(state.devices == [:]) { startPage() }
	logDebug("mainPage")
	if (selectedAddDevices) { addDevices() }
	if (selectedRemoveDevices) { removeDevices() }
	if (debugLog == true) { runIn(1800, debugOff) }

	def devices = state.devices
	def foundDevices = "Kasa App Alias  Inst Hubitat Driver ID"
	def count = 0
	devices.each {
		def installed = "No"
		def child = getChildDevice(it.value.dni)
		def driverVer = ""							   
		if (child) {
			installed = "Yes"
		}
		foundDevices += "\n${it.value.alias.padRight(15)} ${installed.padRight(4)} Kasa ${it.value.type}"
		count += 1
	}
	return dynamicPage(name:"mainPage",
					   title:"<b>Kasa Local Hubitat Integration, Version ${appVersion()}</b>",
					   uninstall: true,
					   install: true) {
		section() {
			href "addDevicesPage",
				title: "<b>Install Kasa Devices</b>",
				description: "Installs newly detected Kasa Device."
			href "removeDevicesPage",
				title: "<b>Remove Kasa Devices</b>",
				description: "Removes user selected Kasa Device."
			href "kasaToolsPage",
				title: "<b>Advanced Kasa Device Tools</b>",
				description: "Bind/Unbind/Reboot Devices"
			paragraph "<b>${count} Devices are in the Application Database</b>"
			paragraph "<textarea rows=10 cols=40 readonly='true'>${foundDevices}</textarea>"
			paragraph state.hs300Error
			input "debugLog", "bool", 
				title: "Enable debug logging for 30 minutes", 
				submitOnChange: true,
				defaultValue: false
		}

	}
}

//	Add Devices
def addDevicesPage() {
	logDebug("addDevicesPage")
	def devices = state.devices
	def uninstalledDevices = [:]
	devices.each {
		def isChild = getChildDevice(it.value.dni)
		if (!isChild) {
			uninstalledDevices["${it.value.dni}"] = "${it.value.alias}     ${it.value.model}"
		}
	}
	def pageInstructions = "<b>Before Installing New Devices</b>\n"
	pageInstructions += "1.\tEnsure the appropriate drivers from "
	pageInstructions += "the previous page are installed.\n"
	pageInstructions += "2.\tAssign Static IP Addresses.\n"
	return dynamicPage(name:"addDevicesPage",
		title:"<b>Add Kasa Devices to Hubitat</b>",
		install: false) {
	 	section() {
			paragraph pageInstructions
		}
	 	section("<b>Select Devices to Add to Hubitat</b>") {
			input ("selectedAddDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Devices to add (${uninstalledDevices.size() ?: 0} available)",
				   description: "Use the dropdown to select devices.  Then select 'Done'.",
				   options: uninstalledDevices)
		}
	}
}
def addDevices() {
	logDebug("addDevices: ${selectedAddDevices}")
	def hub
	try { hub = location.hubs[0] }
	catch (error) { 
		logWarn("Hub not detected.  You must have a hub to install this app.")
		return
	}
	def hubId = hub.id
	selectedAddDevices.each { dni ->
		def isChild = getChildDevice(dni)
		if (!isChild) {
			def device = state.devices.find { it.value.dni == dni }
			def deviceData = [:]
			deviceData["applicationVersion"] = appVersion()
			deviceData["deviceIP"] = device.value.ip
			if (device.value.type == "Multi Plug" || device.value.type == "EM Multi Plug") {
				deviceData["plugNo"] = device.value.plugNo
				deviceData["plugId"] = device.value.plugId
			}
			try {
				addChildDevice(
					"davegut",
					"Kasa ${device.value.type}",
					device.value.dni,
					hubId, [
						"label": device.value.alias,
						"name" : device.value.model,
						"data" : deviceData
					]
				)
				logInfo("Installed Kasa ${device.value.model} with alias ${device.value.alias}")
			} catch (error) {
				logWarn("Failed to install ${device.value.alias}.  Driver most likely not installed.")
			}
		}
		pauseExecution(3000)
	}
	app?.removeSetting("selectedAddDevices")
}


//	Remove Devices
def removeDevicesPage() {
	def devices = state.devices
	def installedDevices = [:]
	devices.each {
		def isChild = getChildDevice(it.value.dni)
		if (isChild) {
			installedDevices["${it.value.dni}"] = "${it.value.model} ${it.value.alias}"
		}
	}
	logDebug("removeDevicesPage: newDevices = ${newDevices}")
	return dynamicPage(name:"removedDevicesPage",
		title:"<b>Remove Kasa Devices from Hubitat</b>",
		install: false) {
	 	section("<b>Select Devices to Remove from Hubitat</b>") {
			input ("selectedRemoveDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Devices to remove (${installedDevices.size() ?: 0} available)",
				   description: "Use the dropdown to select devices.  Then select 'Done'.",
				   options: installedDevices)
		}
	}
}
def removeDevices() {
	logDebug("removeDevices: ${selectedRemoveDevices}")
	selectedRemoveDevices.each { dni ->
		def isChild = getChildDevice(dni)
		if (isChild) {
			def device = state.devices.find { it.value.dni == dni }
			try {
				deleteChildDevice(dni)
				logInfo("Deleted ${device.value.alias}")
			} catch (error) {
				logWarn("Failed to delet ${device.value.alias}.")
			}
		}
	}
	app?.removeSetting("selectedRemoveDevices")
}

//	Access Advanced Kasa Tools
def kasaToolsPage() {
	logDebug("kasaToolsPage")
	if (unbindDevices) { deviceUnbind() }
	if (bindDevices) { deviceBind() }
	if (rebootDevice) { deviceReboot() }
	if (selectedLedOnDevices) { ledOn() }
	if (selectedLedOffDevices) { ledOff() }
	getDeviceBinding()
	
	def devicesBindingData = state.devicesBindingData
	def bindingData = "Kasa App Alias   Binded  Device Type"
	def count = 0
	devicesBindingData.each {
		bindingData += "\n${it.value.alias.padRight(15)}  ${it.value.bindState.padRight(6)}  ${it.value.type}"
		count += 1
	}
	
	return dynamicPage(name:"kasaToolsPage",
					   title:"<b>Kasa Tools</b>",
					   uninstall: true,
					   install: false) {
		section("<b>${count} Devices Binding Status</b>") {
			paragraph "<textarea rows=10 cols=40 readonly='true'>${bindingData}</textarea>"
			href "unbindDevicesPage",
				title: "<b>Unbind Devices from Kasa Cloud</b>",
				description: "Makes devices accessible only through local WiFi in the Kasa App."
			href "bindDevicesPage",
				title: "<b>Bind Devices to Kasa Cloud</b>",
				description: "Makes devices accessible via the Cloud in the Kasa App."
			href "ledOnPage",
				title: "<b>Turn LED on for selected devices</b>",
				description: "Sets led_off to false."
			href "ledOffPage",
				title: "<b>Turn LED off for selected devices</b>",
				description: "Sets led_off to true."
			href "rebootSingleDevicePage",
				title: "<b>Reboot a Selected Kasa Device</b>",
				description: "For troubleshooting a single device."
		}
	}
}
def getDeviceBinding() {
	logDebug("getDeviceBinding")
	state.devicesBindingData = [:]
	def devices = state.devices
	devices.each {
		if (it.value.plugNo != null && it.value.plugNo != "00") { return }
		def preamble = "cnCloud"
		if (it.value.type == "Color Bulb" || it.value.type == "CT Bulb" ||
			it.value.type == "Mono Bulb") {
			preamble = "smartlife.iot.common.cloud"
		}
		sendDeviceCmd(it.value.ip,
					  """{"${preamble}":{"get_info":{}}}""",
					  "deviceBindingResponse")
		pauseExecution(100)
	}
	pauseExecution(5000)
}
def deviceBindingResponse(response) {
	logDebug("deviceBindingResponse")
	def resp = parseLanMessage(response.description)
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload))
	def binded
	if (cmdResp.cnCloud) {
		binded = cmdResp.cnCloud.get_info.binded
	} else {
		binded = cmdResp["smartlife.iot.common.cloud"].get_info.binded
	}
	def bindState = "true"
	if (binded == 0) { bindState = "false" }
	
	def devicesBindingData = state.devicesBindingData
	def deviceBindData = [:]
//	5.3.5
//	def device = state.devices.find { it.value.dni == resp.mac }
//	if (!device) {
//		device = state.devices.find { it.value.dni == "${resp.mac}00" }
//	}
	def device = state.devices.find { it.value.ip == convertHexToIP(resp.ip) }
//	5.3.5

	deviceBindData["dni"] = device.value.dni
	deviceBindData["ip"] = device.value.ip
	deviceBindData["alias"] = device.value.alias
	deviceBindData["type"] = device.value.type
	deviceBindData["bindState"] = bindState
	devicesBindingData << ["${device.value.dni}" : deviceBindData]
	logDebug("deviceBindingResponse: ${device.value.alias} added to devicesBindingData array")
}

//	Unbind Devices
def unbindDevicesPage() {
	logDebug("unbindDevicesPage")
	def devicesBindingData = state.devicesBindingData
	def bindedDevices = [:]
	devicesBindingData.each {
		if (it.value.bindState == "true") {
			bindedDevices["${it.value.dni}"] = "${it.value.type} ${it.value.alias}"
		}
	}
	
	return dynamicPage(name:"unBindDevicesPage",
		title:"<b>Unbind Kasa Devices from the Kasa Cloud</b>",
		install: false) {
	 	section("<b>Select Devices to Unbind from the Kasa Cloud</b>") {
			input ("unbindDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Devices to unbind (${bindedDevices.size() ?: 0} available)",
				   description: "Use the dropdown to select devices.  Then select 'Done'.",
				   options: bindedDevices)
		}
	}
}
def deviceUnbind() {
	logInfo("deviceUnbind")
	unbindDevices.each { dni ->
		def device = state.devicesBindingData.find { it.value.dni == dni }
		def preamble = "cnCloud"
		if (device.value.type == "Color Bulb" || device.value.type == "CT Bulb" ||
			device.value.type == "Mono Bulb") {
			preamble = "smartlife.iot.common.cloud"
		}
		logInfo("Unbinding device: dni = ${device.value.dni}, alias = ${device.value.alias}, ip = ${device.value.ip}")
		sendDeviceCmd(device.value.ip,
					  """{"${preamble}":{"unbind":""},"${preamble}":{"get_info":{}}}""",
					  "unbindResponse")
	}
	pauseExecution(4000)
	app?.removeSetting("unbindDevices")
}
def unbindResponse(response) {
	def resp = parseLanMessage(response.description)
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload))
	def binded
	if (cmdResp.cnCloud) {
		binded = cmdResp.cnCloud.get_info.binded
	} else {
		binded = cmdResp["smartlife.iot.common.cloud"].get_info.binded
	}
//	5.3.5
	def device = state.devices.find { it.value.ip == convertHexToIP(resp.ip) }
	if (binded == 0) {
//		logInfo("SUCCESS: Device with DNI = ${resp.mac} is ubbound from the Kasa Cloud")
		logInfo("SUCCESS: Device with DNI = ${device.dni} is ubbound from the Kasa Cloud")
	} else {
//		logWarn("FAILED: DNI: ${resp.mac} unbind failed. Error = ${cmdResp}")
		logWarn("FAILED: DNI: ${device.dni} unbind failed. Error = ${cmdResp}")
	}
//	5.3.5
}


//	Bind Devices
def bindDevicesPage() {
	logDebug("unbindDevicesPage")
	def devicesBindingData = state.devicesBindingData
	def unbindedDevices = [:]
	devicesBindingData.each {
		if (it.value.bindState == "false") {
			unbindedDevices["${it.value.dni}"] = "${it.value.type} ${it.value.alias}"
		}
	}
	
	return dynamicPage(name:"bindDevicesPage",
		title:"<b>Bind Kasa Devices to the Kasa Cloud</b>",
		install: false) {
		section("<b>Enter Kasa Account Credentials</b>") {
			input ("userName", "text",
				   title: "TP-Link Kasa Account E-Mail",
				   submitOnChange: true)
			input ("userPassword", "password",
				   title: "TP-Link Kasa Account Password",
				   submitOnChange: true)
		}
	 	section("<b>Select Devices to Bind to the Kasa Cloud</b>") {
			input ("bindDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Devices to bind (${unbindedDevices.size() ?: 0} available)",
				   description: "Use the dropdown to select devices.  Then select 'Done'.",
				   options: unbindedDevices)
		}
	}
}
def deviceBind() {
	logInfo("deviceBind")
	bindDevices.each { dni ->
		def device = state.devicesBindingData.find { it.value.dni == dni }
		def preamble = "cnCloud"
		if (device.value.type == "Color Bulb" || device.value.type == "CT Bulb" ||
			device.value.type == "Mono Bulb") {
			preamble = "smartlife.iot.common.cloud"
		}
		sendDeviceCmd(device.value.ip,
				"""{"${preamble}":{"bind":{"username":"${userName}","password":"${userPassword}"}},""" +
				""""${preamble}":{"get_info":{}}}""",
				"bindResponse")
	}
	pauseExecution(4000)
	app?.removeSetting("bindDevices")
}
def bindResponse(response) {
	def resp = parseLanMessage(response.description)
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload))
	logDebug("bindResponse: cmdResp = ${cmdResp}")
	def binded
	if (cmdResp.cnCloud) {
		binded = cmdResp.cnCloud.get_info.binded
	} else {
		binded = cmdResp["smartlife.iot.common.cloud"].get_info.binded
	}
//	5.3.5
	def device = state.devices.find { it.value.ip == convertHexToIP(resp.ip) }
	if (binded == 1) {
//		logInfo("SUCCESS: Device with DNI = ${resp.mac} is bound to the Kasa Cloud")
		logInfo("SUCCESS: Device with DNI = ${device.dni} is bound to the Kasa Cloud")
	} else {
//		logWarn("FAILED: DNI: ${resp.mac} bind failed. Error = ${cmdResp}")
		logWarn("FAILED: DNI: ${device.dni} bind failed. Error = ${cmdResp}")
	}
//	5.3.5
}

//	Turn LedOff On or Off
def ledOnPage() {
	logDebug("ledOnPage")
	def devices = state.devices
	def ledOffDevices = [:]
	devices.each {
		if (it.value.plugNo == null || it.value.plugNo == "00"){
			if (it.value.ledOff == true) {
				ledOffDevices["${it.value.dni}"] = "${it.value.model} ${it.value.alias}"
			}
		}
	}
	return dynamicPage(name:"ledOnPage",
		title:"<b>Turn On the Device LED (set led_off to false)</b>",
		install: false) {
	 	section("<b>Select Devices to turn on LED</b>") {
			input ("selectedLedOnDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Turn on LED devices (${ledOffDevices.size() ?: 0} available)",
				   description: "Use the dropdown to select devices.  Then select 'Done'.",
				   options: ledOffDevices)
		}
	}
}
def ledOn() {
	logInfo("ledOn: ${selectedLedOnDevices}")
	selectedLedOnDevices.each { dni ->
		def device = state.devices.find { it.value.dni == dni }
		logDebug("ledOn: dni = ${device.value.dni}, alias = ${device.value.alias}, ip = ${device.value.ip}")
		sendDeviceCmd(device.value.ip,
					  """{"system":{"set_led_off":{"off":0}}}""",
					  "ledOnOffResponse")
	}
	pauseExecution(4000)
	app?.removeSetting("selectedLedOnDevices")
}
def ledOffPage() {
	logDebug("ledOffPage")
	def devices = state.devices
	def ledOnDevices = [:]
	devices.each {
		if (it.value.plugNo == null || it.value.plugNo == "00"){
			if (it.value.ledOff == false) {
				ledOnDevices["${it.value.dni}"] = "${it.value.model} ${it.value.alias}"
			}
		}
	}
	return dynamicPage(name:"ledOnPage",
//	5.3.5
//		title:"<b>Turn On the Device LED (set led_off to true)</b>",
		title:"<b>Turn Off the Device LED (set led_off to true)</b>",
//	5.3.5
		install: false) {
	 	section("<b>Select Devices to turn on LED</b>") {
			input ("selectedLedOffDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Turn off LED devices (${ledOnDevices.size() ?: 0} available)",
				   description: "Use the dropdown to select devices.  Then select 'Done'.",
				   options: ledOnDevices)
		}
	}
}
def ledOff() {
	logInfo("ledOn: ${selectedLedOffDevices}")
	selectedLedOffDevices.each { dni ->
		def device = state.devices.find { it.value.dni == dni }
		logDebug("ledOff: dni = ${device.value.dni}, alias = ${device.value.alias}, ip = ${device.value.ip}")
		sendDeviceCmd(device.value.ip,
					  """{"system":{"set_led_off":{"off":1}}}""",
					  "ledOnOffResponse")
	}
	pauseExecution(4000)
	app?.removeSetting("selectedLedOffDevices")
}
def ledOnOffResponse(response) {
	def resp = parseLanMessage(response.description)
//	5.3.5
//	def dni = resp.mac
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload))
	logDebug("ledOnOff: cmdResp = ${cmdResp}")
	if (cmdResp.system.set_led_off.err_code == 0) {
//		def device = state.devices.find { it.value.dni == dni }
		def device = state.devices.find { it.value.ip == convertHexToIP(resp.ip) }
		if (device.value.ledOff == true) { device.value.ledOff = false }
		else if (device.value.ledOff == false) { device.value.ledOff = true }
//		logInfo("ledOnOffResponse: device ${dni} ledOff state set to ${device.value.ledOff}")
		logInfo("ledOnOffResponse: device ${device.dni} ledOff state set to ${device.value.ledOff}")
	}
//	5.3.5
	else { logWarn("ledOnOffResponse: Error returned from device.") }
}

//	Reboot Single Device
def rebootSingleDevicePage() {
	def devices = state.devices
	def allDevices = [:]
	devices.each {
		if (it.value.plugNo == null || it.value.plugNo == "00") {
			allDevices["${it.value.dni}"] = "${it.value.model} ${it.value.alias}"
		}
	}
	logDebug("rebootSingleDevicePage: allDevices = ${allDevices}")
	return dynamicPage(name:"rebootSingleDevicePage",
		title:"<b>Reboot One Selected Kasa Device for Troubleshooting</b>",
		install: false) {
	 	section("<b>Select One Device to Reboot</b>") {
			input ("rebootDevice", "enum",
				   required: false,
				   multiple: false,
				   title: "Device to reboot (${allDevices.size() ?: 0} available)",
				   description: "Use the dropdown to select one device.  Then select 'Done'.",
				   options: allDevices)
		}
	}
}
def deviceReboot() {
	logInfo("deviceReboot")
	def device = state.devices.find { it.value.dni == rebootDevice }
	def preamble = "system"
	if (device.value.type == "Color Bulb" || device.value.type == "CT Bulb" ||
		device.value.type == "Mono Bulb") {
		preamble = preamble = "smartlife.iot.common.system"
	}
//	5.3.5
//	logInfo("Unbinding device: dni = ${device.value.dni}, alias = ${device.value.alias}, ip = ${device.value.ip}")
	logInfo("Rebooting device: dni = ${device.value.dni}, alias = ${device.value.alias}, ip = ${device.value.ip}")
//	5.3.5
	sendDeviceCmd(device.value.ip,
				  """{"${preamble}":{"reboot":{"delay":3}}}""",
				  "rebootResponse")
	app?.removeSetting("removeDevice")
}
def rebootResponse(response) {
	def resp = parseLanMessage(response.description)
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload))
	logDebug("rebootResponse: cmdResp = ${cmdResp}")
	def err_code
	if (cmdResp.system) {
		try { err_code = cmdResp.system.reboot.err_code }
		catch (error) { err_code = -1 }
	} else {
		try { err_code = cmdResp["smartlife.iot.common.system"].reboot.err_code }
		catch (error) { err_code = -1 }
	}
//	5.3.5
	def device = state.devices.find { it.value.ip == convertHexToIP(resp.ip) }
	if (err_code == 0) {
//		logInfo("SUCCESS. Device with DNI = ${resp.mac} rebooting.")
//		state.currMsg = "SUCCESS. Device with DNI = ${resp.mac} rebooting."
		logInfo("SUCCESS. Device with DNI = ${device.dni} rebooting.")
	} else {
//		logWarn("FAILED.  DNI: ${resp.mac} reboot command failed.  Error = ${cmdResp}")
//		state.currMsg = "FAILED.  DNI: ${resp.mac} reboot command failed.  Error = ${cmdResp}"
		logWarn("FAILED.  DNI: ${device.dni} reboot command failed.  Error = ${cmdResp}")
	}
//	5.3.5
}

//	Device Communications Failure Methods
def updateIpData() {
	logInfo("requestDataUpdate: Received device IP request from a Kasa device.")
	runIn(5, pollForIps)
}
def pollForIps() {
	if (pollEnabled == false) {
		logWarn("pollForIps: a poll was run within the 15 min.  Poll not run.  Try running manually through the application.")
		return
	} else {
		logInfo("pollForIps: Diabling poll capability for one hour")
		app?.updateSetting("pollEnabled", [type:"bool", value: false])
		runIn(900, pollEnable)
		logInfo("pollForIps: starting poll for Kasa Device IPs.")
		findDevices(25, updateDeviceIps)
	}
	return pollEnabled
}
def pollEnable() {
	logInfo("pollEnable: polling capability enabled.")
	app?.updateSetting("pollEnabled", [type:"bool", value: true])
}
def updateDeviceIps(response) {
	def resp = parseLanMessage(response.description)
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload)).system.get_sysinfo
	def ip = convertHexToIP(resp.ip)
	def dni
	if (cmdResp.mic_mac) { dni = cmdResp.mic_mac }
	else { dni = cmdResp.mac.replace(/:/, "") }
	def child = getChildDevice(dni)
	if (child) {
		child.updateDataValue("deviceIP", ip)
		logInfo("updateDeviceIps: updated IP for device ${dni} to ${ip}.")
	}		
}

//	Communications Methods
def findDevices(pollInterval, action) {
	logInfo("findDevices: Searching the LAN for your Kasa Devices")
//	5.3.4
//	def hub
//	try { hub = location.hubs[0] }
//	catch (error) { 
//		logWarn "Hub not detected.  You must have a hub to install this app."
//		return
//	}
//	def hubIpArray = hub.localIP.split('\\.')
//	def networkPrefix = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")
//	5.3.4	
	
//	5.3.4	
//	logInfo("findDevices: IP Segment = ${networkPrefix}")
	logInfo("findDevices: IP Segment = ${lanSegment}")
	for(int i = 2; i < 255; i++) {
//		def deviceIP = "${networkPrefix}.${i.toString()}"
		def deviceIP = "${lanSegment}.${i.toString()}"
		sendCmd(deviceIP, action)
		pauseExecution(pollInterval)
	}
//	5.3.4
	pauseExecution(3000)
}
private sendCmd(ip, action) {
	def myHubAction = new hubitat.device.HubAction(
		"d0f281f88bff9af7d5f5cfb496f194e0bfccb5c6afc1a7c8eacaf08bf68bf6",
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${ip}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 parseWarning: true,
		 timeout: 3,
		 callback: action])
	sendHubCommand(myHubAction)
}
private sendDeviceCmd(ip, command, action) {
	logDebug("sendDeviceCmd: ip = ${ip}, command = ${command}")
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${ip}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 parseWarning: true,
		 timeout: 5,
		 callback: action])
	sendHubCommand(myHubAction)
}

//	Utility Methods
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
	for(int i = 0; i < strBytes.length-1; i++) {
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative
		XORtemp = nextKey ^ key
		key = nextKey
		cmdResponse += new String(XORtemp)
	}
	return cmdResponse
}

private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}
private Integer convertHexToInt(hex) { Integer.parseInt(hex,16) }
def debugOff() { app.updateSetting("debugLog", false) }
def logTrace(msg){ log.trace "${device.label} ${msg}" }
def logDebug(msg){
	if(debugLog == true) { log.debug "${appVersion()} ${msg}" }
}
def logInfo(msg){ log.info "${appVersion()} ${msg}" }
def logWarn(msg) { log.warn "${appVersion()} ${msg}" }
def license() {
	def licText = "Copyright 2020, Dave Gutheinz\n\n"
	licText += "DISCLAIMER:  This Applicaion and the associated Device "
	licText += "Drivers are in no way sanctioned or supported by "
	licText += "TP-Link.  All  development is based upon open-source "
	licText += "data on the TP-Link devices; primarily various users "
	licText += "on GitHub.com.\n\n"
	licText += "This license applies to all Kasa Integration applications "
	licText += "and drivers developed by David Gutheinz. By operating "
	licText += "this application and associated drivers, the user agrees "
	licText += "to the terms contained below:\n\n"
	licText += "Licensed under the Apache License, Version 2.0 "
	licText += "(the License); you may not use this  file except in "
	licText += "compliance with the License. You may obtain a copy of "
	licText += "the License at the below link:\n\n"
	licText += "http://www.apache.org/licenses/LICENSE-2.0\n\n"
	licText += "Unless required by applicable law or agreed to in "
	licText += "writing, software distributed under the License is "
	licText += "distributed on an AS IS BASIS, WITHOUT WARRANTIES OR "
	licText += "CONDITIONS OF ANY KIND, either express or implied. "
	licText += "See the License for the specific language governing "
	licText += "permissions and limitations under the License."
	return licText
}

//	end-of-file