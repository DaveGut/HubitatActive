/*	Kasa Local Integration

		Copyright Dave Gutheinz

License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md

Changes since version 6:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Version%206%20Change%20Log.md

===== Version 6.4.1 =====
1.  Switched to Library-based development.  Groovy file will have a lot of comments
	related to importing the library methods into the driver for publication.
2.	Added bulb and lightStrip preset capabilities.
3.	Modified LANcommunications timeouts and error handling to account for changes 
	in Hubitat platform.
===================================================================================================*/
def appVersion() { return "6.4.1" }
def rel() { return "7" }
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
	documentationLink: "https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Documentation.pdf",
	importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/Application/KasaIntegrationApp.groovy"
)

preferences {
	page(name: "startPage")
	page(name: "kasaAuthenticationPage")
	page(name: "addDevicesPage")
	page(name: "removeDevicesPage")
	page(name: "listDevicesByIp")
	page(name: "listDevicesByName")
	page(name: "startGetToken")
}

def installed() { initialize() }

def updated() { initialize() }

def initialize() {
	logInfo("initialize")
	unschedule()
	if (useKasaCloud == true) {
		schedule("0 30 2 ? * WED", schedGetToken)
	}
}

def uninstalled() {
    getAllChildDevices().each { 
        deleteChildDevice(it.deviceNetworkId)
    }
}

def startPage() {
	logInfo("starting Kasa Integration")
	if (selectedRemoveDevices) { removeDevices() }
	if (selectedAddDevices) { addDevices() }
	if (!ver600) {
		app?.removeSetting("displayLicense")
		app?.removeSetting("infoLog")
		app?.removeSetting("licenseAcknowldege")
		app?.removeSetting("rebootDevice")
		state.remove("commsError")
		state.remove("currMsg")
		state.remove("devicesBindingData")
		state.remove("oundDevices")
		state.remove("hs300Error")
		state.remove("missingDevice")
		app?.updateSetting("ver600", [type:"bool", value: true])
	}
	if (!debugLog) { app.updateSetting("debugLog", false) }
	def segments
	if (!lanSegment) {
		def hub = location.hubs[0]
		def hubIpArray = hub.localIP.split('\\.')
		segments = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")
		app?.updateSetting("lanSegment", [type:"string", value: segments])
	} else {
		segments = lanSegment
	}
	state.segArray = segments.split('\\,')

	return dynamicPage(name:"startPage",
					   title:"<b>Kasa Local Hubitat Integration, Version ${appVersion()}-rel${rel()}</b>",
					   uninstall: true,
					   install: true) {
		section() {
			input "showInstructions", "bool",
				title: "<b>Page Hints</b>",
				submitOnChange: true,
				defaultalue: true
			if (showInstructions == true) {
				paragraph "<textarea rows=19 cols=66 readonly='true'>${stPgIns()}</textarea>"
			}
			input "lanSegment", "string",
				title: "<b>Lan Segments</b>",
				description: "Select if your devices and Hub are on different LAN segments",
				submitOnChange: true
			input "useKasaCloud", "bool",
				title: "<b>Interface to Kasa Cloud</b>",
				description: "Use if you want to use the Kasa Cloud as an alternate control method",
				submitOnChange: true,
				defaultalue: false
			if (useKasaCloud == true) {
				href "kasaAuthenticationPage",
					title: "<b>Kasa Login and Token Update</b>",
					description: "Go to Kasa Login Update."
				paragraph "\tCurrent Token: ${kasaToken}\n\tKasa Cloud Url: ${kasaCloudUrl}"
				def msg = "After running Kasa Login and Token Update, refresh this page."
				if (useKasaCloud && !kasaCloudUrl) {
					msg += "\nYou must have at least one device bound to the cloud and "
					msg += "run Add Devices to set the KasaCloudUrl and use the Kasa Cloud."
				}
				paragraph msg
			}
			href "addDevicesPage",
				title: "<b>Install Kasa Devices</b>",
				description: "Installs newly detected Kasa Device"
			href "removeDevicesPage",
				title: "<b>Remove Kasa Devices</b>",
				description: "Removes user selected Kasa Device"
			href "listDevicesByIp",
				title: "<b>List All Kasa Devices by IP Address</b>",
				description: "Finds devices and list by IP address. Also updates IP addresses."
			href "listDevicesByName",
				title: "<b>List All Kasa Devices by Name</b>",
				description: "Finds devices and list by device name. Also updates IP addresses."
			input "debugLog", "bool", 
				title: "<b>Enable debug logging for 30 minutes</b>", 
				submitOnChange: true,
				defaultValue: false
		}
	}
}

def createSegArray() {
	def segments
	if (!lanSegment) {
		def hub = location.hubs[0]
		def hubIpArray = hub.localIP.split('\\.')
		segments = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")
		app?.updateSetting("lanSegment", [type:"string", value: segments])
	} else {
		segments = lanSegment
	}
	state.segArray = segments.split('\\,')
}

//	Get Kasa Cloud Credentials
def kasaAuthenticationPage() {
	logDebug("kasaAuthenticationPage")
	return dynamicPage (name: "kasaAuthenticationPage", 
    					title: "Initial Kasa Login Page, Version ${appVersion()}-rel${rel()}",
						nextPage: startPage,
                        install: false) {
        section("Enter Kasa Account Credentials: ") {
			input ("userName", "email",
            		title: "TP-Link Kasa Email Address", 
                    required: true,
                    submitOnChange: true)
			input ("userPassword", "password",
            		title: "TP-Link Kasa Account Password",
                    required: true,
                    submitOnChange: true)
			if (userName && userPassword && userName != null && userPassword != null) {
				href "startGetToken", title: "Get or Update Kasa Token", 
					description: "Tap to Get Kasa Token"
			}
			paragraph "Select  '<'  at upper left corner to exit."
		}
	}
}

def startGetToken() {
	logInfo("getTokenFromStart: Result = ${getToken()}")
	startPage()
}

def schedGetToken() {
	logInfo("schedGetToken: Result = ${getToken()}")
}

def getToken() {
	logInfo("getToken ${userName}")
	def message = ""
	def hub = location.hubs[0]
	def cmdBody = [
		method: "login",
		params: [
			appType: "Kasa_Android",
			cloudUserName: "${userName}",
			cloudPassword: "${userPassword}",
			terminalUUID: "${hub.id}"
		]
	]
	def getTokenParams = [
		uri: "https://wap.tplinkcloud.com",
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		body : new groovy.json.JsonBuilder(cmdBody).toString()
	]
	httpPostJson(getTokenParams) {resp ->
		if (resp.status == 200 && resp.data.error_code == 0) {
			app?.updateSetting("kasaToken", resp.data.result.token)
			message = "Token updated to ${resp.data.result.token}"
		} else {
			message = "Error obtaining token from Kasa Cloud. Message = ${resp.data.msg}"
			logWarn("getToken: ${message}.")
		}
	}
	return message
//	startPage()
}

//	Add Devices
def addDevicesPage() { 
	logDebug("addDevicesPage")
	state.devices = [:]
	findDevices()
	
	def devices = state.devices
	def uninstalledDevices = [:]
	def requiredDrivers = [:]
	devices.each {
		def isChild = getChildDevice(it.value.dni)
		if (!isChild) {
			uninstalledDevices["${it.value.dni}"] = "${it.value.alias}, ${it.value.type}"
			requiredDrivers["${it.value.type}"] = "${it.value.type}"
		}
	}
	def reqDrivers = "1.\t<b>Ensure the following drivers are installed:</b>"
	requiredDrivers.each {
		reqDrivers += "\n\t\t${it.key}"
	}
	def pageInstructions = "<b>Before Installing New Devices</b>\n"
	pageInstructions += "${reqDrivers}\n"
	pageInstructions += "2.\t<b>Assign Static IP Addresses.</b>"
	return dynamicPage(name:"addDevicesPage",
					   title: "Add Kasa Devices to Hubitat, Version ${appVersion()}-R${rel()}",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			paragraph pageInstructions
			input ("selectedAddDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Total Discovered deviced: ${devices.size() ?: 0}.  " +
				   "Devices to add (${uninstalledDevices.size() ?: 0} available).",
				   description: "Use the dropdown to select devices.  Then select 'Done'.",
				   options: uninstalledDevices)
		}
	}
}

def addDevices() {
	logDebug("addDevices: ${selectedAddDevices}")
	def hub = location.hubs[0]
	selectedAddDevices.each { dni ->
		def isChild = getChildDevice(dni)
		if (!isChild) {
			def device = state.devices.find { it.value.dni == dni }
			def deviceData = [:]
			deviceData["deviceIP"] = device.value.ip
			deviceData["plugNo"] = device.value.plugNo
			deviceData["plugId"] = device.value.plugId
			deviceData["deviceId"] = device.value.deviceId
			deviceData["model"] = device.value.model
			deviceData["feature"] = device.value.feature
			try {
				addChildDevice(
					"davegut",
					device.value.type,
					device.value.dni,
					hub.id, [
						"label": device.value.alias,
						"name" : device.value.type,
						"data" : deviceData
					]
				)
				logInfo("Installed ${device.value.alias}.")
			} catch (error) {
				logWarn("Failed to install device." + 
						"\nDevice: ${device}" +
						"\n<b>Driver: ${device.value.type}")
			}
		}
		pauseExecution(3000)
	}
	app?.removeSetting("selectedAddDevices")
}

//	Remove Devices
def removeDevicesPage() {
	logDebug("removeDevicesPage")
	def devices = state.devices
	def installedDevices = [:]
	devices.each {
		def isChild = getChildDevice(it.value.dni)
		if (isChild) {
			installedDevices["${it.value.dni}"] = "${it.value.alias}, type = ${it.value.type}"
		}
	}
	logDebug("removeDevicesPage: newDevices = ${newDevices}")
	return dynamicPage(name:"removedDevicesPage",
					   title:"<b>Remove Kasa Devices from Hubitat</b>",
					   nextPage: startPage,
					   install: false) {
		section("Select Devices to Remove from Hubitat, Version ${appVersion()}-rel${rel()}") {
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

def listDevicesByIp() { 
	logDebug("listDevicesByIp")
	state.devices = [:]
	findDevices()
	def devices = state.devices
	def deviceList = []
	deviceList << "<b>DeviceIp:  Alias,  DeviceType,  Installed</b>"
	devices.each{
		def installed = "NO"
		def isChild = getChildDevice(it.value.dni)
		if (isChild) {
			installed = "YES"
		}
		def type = it.value.type.replace("Kasa ","")
		deviceList << "${it.value.ip}:  ${it.value.alias},  ${type},  ${installed}"
	}
	def theList = "<b>List of all Kasa Devices by device name</b>\n"
	theList += "Total Kasa devices: ${devices.size() ?: 0}\n\n"
	deviceList.each {
		theList += "${it}\n"
	}
	return dynamicPage(name:"listDevicesByIp",
					   title: "List Kasa Devices by IP Address, Version ${appVersion()}-R${rel()}",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			paragraph theList
		}
	}
}

def listDevicesByName() { 
	logDebug("listDevicesByName")
	state.devices = [:]
	findDevices()
	def devices = state.devices
	def deviceList = []
	deviceList << "<b>Alias:  DeviceType,  DeviceIP,  Installed</b>"
	devices.each{
		def installed = ""
		def isChild = getChildDevice(it.value.dni)
		if (isChild) {
			installed = "installed"
		}
		def type = it.value.type.replace("Kasa ","")
		deviceList << "${it.value.alias},  ${type},  ${it.value.ip},  ${installed}"
	}
	deviceList.sort()
	def theList = "<b>List of all Kasa Devices by device name</b>\n"
	theList += "Total Kasa devices: ${devices.size() ?: 0}\n\n"
	deviceList.each {
		theList += "${it}\n"
	}
	return dynamicPage(name:"listDevicesByName",
					   title: "List Kasa Devices by DeviceName, Version ${appVersion()}-R${rel()}",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			paragraph theList
		}
	}
}

//	Get Device Data Methods
def findDevices() {
	def pollSegment
	state.segArray.each {
		pollSegment = it.trim()
		logInfo("findDevices: Searching for LAN deivces on IP Segment = ${pollSegment}")
		for(int i = 2; i < 255; i++) {
			def deviceIP = "${pollSegment}.${i.toString()}"
			sendLanCmd(deviceIP, """{"system":{"get_sysinfo":{}}}""", "parseLanData")
			pauseExecution(100)
		}
	}
	if (useKasaCloud == true) {
		logInfo("findDevices: ${cloudGetDevices()}")
	}
	runIn(3,updateChildren)
}

def cloudGetDevices() {
	logInfo("cloudGetDevices ${kasaToken}")
	if (kasaToken == null) {
		logWarn("clogGetDevices: kasaToken is null.  Run Kasa Login and Token Update")
		return
	}
	def cloudDevices = ""
	def message = ""
	def cloudUrl = ""
	def cmdBody = [method: "getDeviceList"]
	def getDevicesParams = [
		uri: "https://wap.tplinkcloud.com?token=${kasaToken}",
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		body : new groovy.json.JsonBuilder(cmdBody).toString()
	]
	httpPostJson(getDevicesParams) {resp ->
		if (resp.status == 200 && resp.data.error_code == 0) {
			cloudDevices = resp.data.result.deviceList
		} else {
			logWarn("Error from the Kasa Cloud: ${resp.data.error_code} = ${resp.data.msg}")
		}
	}
	cloudDevices.each {
		if (it.deviceType != "IOT.SMARTPLUGSWITCH" && it.deviceType != "IOT.SMARTBULB") {
			logInfo("<b>cloudGetDevice: Ignore device type ${it.deviceType}.")
			return
		} else if (it.status == 0) {
			logInfo("<b>cloudGetDevice: Device name ${it.alias} is offline and not included.")
			return
		}
		cloudUrl = it.appServerUrl
		def cmdResp = sendKasaCmd(it.deviceId, '{"system":{"get_sysinfo":{}}}', it.appServerUrl)
		if (cmdResp.error) {
			logWarn("cloudGetDevices: ${it.alias}, ${cmdResp}")
			return
		}
		parseDeviceData(cmdResp.system.get_sysinfo, null)
	}
	message += "Device data sent to parse methods."
	if (cloudUrl != "" && cloudUrl != kasaCloudUrl) {
		app?.updateSetting("kasaCloudUrl", cloudUrl)
		message += " kasaCloudUrl uptdated to ${cloudUrl}."
	}
	return message
}

def parseLanData(response) {
	def resp = parseLanMessage(response.description)
	if (resp.type == "LAN_TYPE_UDPCLIENT") {
		def clearResp = inputXOR(resp.payload)
		if (clearResp.length() > 1022) {
			clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}"
		}
		def ip = convertHexToIP(resp.ip)
		def cmdResp = new JsonSlurper().parseText(clearResp).system.get_sysinfo
		parseDeviceData(cmdResp, ip)
	} else if (resp.type != "LAN_TYPE_UDPCLIENT_ERROR") {
		logWarn("parseLanData: errorType = ${resp.type}, payload = ${resp.payload}")
	}
}

def parseDeviceData(cmdResp, ip = null) {
	logDebug("parseDeviceData: ${cmdResp} //  ${ip}")
	def dni
	if (cmdResp.mic_mac) {
		dni = cmdResp.mic_mac
	} else {
		dni = cmdResp.mac.replace(/:/, "")
	}
	def kasaType
	if (cmdResp.mic_type) {
		kasaType = cmdResp.mic_type
	} else {
		kasaType = cmdResp.type
	}
	def type
	def feature = cmdResp.feature
	if (kasaType == "IOT.SMARTPLUGSWITCH") {
		type = "Kasa Plug Switch"
		if (feature == "TIM:ENE") {
			type = "Kasa EM Plug"
		}
		if (cmdResp.brightness) {
			type = "Kasa Dimming Switch"
		} else if (cmdResp.children) {
			type = "Kasa Multi Plug"
			if (feature == "TIM:ENE") {
				type = "Kasa EM Multi Plug"
			}
		}
	} else if (kasaType == "IOT.SMARTBULB") {
		if (cmdResp.lighting_effect_state) {
			feature = "lightStrip"
			type = "Kasa Light Strip"
		} else if (cmdResp.is_color == 1) {
			type = "Kasa Color Bulb"
		} else if (cmdResp.is_variable_color_temp == 1) {
			type = "Kasa CT Bulb"
		} else {
			type = "Kasa Mono Bulb"
		}
	}

	def model = cmdResp.model.substring(0,5)
	def alias = cmdResp.alias
	def plugNo
	def plugId
	def deviceId = cmdResp.deviceId
	if (cmdResp.children) {
		def childPlugs = cmdResp.children
		childPlugs.each {
			plugNo = it.id
			plugNo = it.id.substring(it.id.length() - 2)
			def childDni = "${dni}${plugNo}"
			plugId = "${deviceId}${plugNo}"
			alias = it.alias
			updateDevices(childDni, ip, type, feature, model, alias, deviceId, plugNo, plugId)
		}
	} else {
		updateDevices(dni, ip, type, feature, model, alias, deviceId, plugNo, plugId)
	}
}

def updateDevices(dni, ip, type, feature, model, alias, deviceId, plugNo, plugId) {
	logDebug("updateDevices: dni = ${dni}")
	def devices = state.devices
	def existingDev = devices.find { it.key == dni }
	if (existingDev && ip == null) {
		ip = existingDev.value.ip
	}
	def device = [:]
	device["dni"] = dni
	device["type"] = type
	device["feature"] = feature
	device["alias"] = alias
	device["model"] = model
	device["deviceId"] = deviceId
	device["ip"] = ip
	if (plugNo) {
		device["plugNo"] = plugNo
		device["plugId"] = plugId
	}
	devices << ["${dni}" : device]
	logInfo("updateDevices: ${type} ${alias} added to devices array.")
}

def updateChildren() {
	logDebug("updateChildDeviceData")
	def devices = state.devices
	devices.each {
		def child = getChildDevice(it.key)
		if (child) {
			child.debugOff()
			child.updateDataValue("type", it.value.type)
			child.updateDataValue("feature", it.value.feature)
			child.updateDataValue("deviceId", it.value.deviceId)
			if (it.value.ip != null || it.value.ip != "") {
				child.updateDataValue("deviceIP", it.value.ip)
			}
			def childVer = child.driverVer().substring(0,3).toString()
			def appVer = appVersion().substring(0,3).toString()
			if (childVer != appVer) {
				logWarn("<b>updateDevices:  Child Driver is not up to date. Device = ${it.value.alias}!</b>")
			}
			child.updated()
		}
	}
}

//	Update LAN IP or CLOUD Token
def fixConnection(type) {
	logInfo("fixData: Update ${type} data")
	def message = ""
	if (pollEnable == false) {
		message = "App fixConnection: Unable to update data.  Updated in last 15 minutes."
		return message
	} else {
		runIn(900, pollEnable)
		app?.updateSetting("pollEnabled", [type:"bool", value: false])
	}
	if (type == "CLOUD") {
		message = "App fixConnection: Value = ${getToken()}."
	} else if (type == "LAN") {
		def pollSegment
		message = "App fixConnection: Updating IPs on segments ${state.segArray}"
		state.segArray.each {
			pollSegment = it.trim()
			for(int i = 2; i < 255; i++) {
				def deviceIP = "${pollSegment}.${i.toString()}"
				sendLanCmd(deviceIP, """{"system":{"get_sysinfo":{}}}""", "updateDeviceIps")
				pauseExecution(25)
			}
		}
	}
	return message
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

def pollEnable() {
	logInfo("pollEnable: polling capability enabled.")
	app?.updateSetting("pollEnabled", [type:"bool", value: true])
}

//	===== Device Communications =====
private sendLanCmd(ip, command, action) {
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${ip}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 parseWarning: true,
		 timeout: 5,
		 callback: action])
	try {
		sendHubCommand(myHubAction)
	} catch (error) {
		logWarn("sendLanCmd: command to ${ip} failed. Error = ${error}")
	}
}

def sendKasaCmd(deviceId, command, appServerUrl = kasaCloudUrl) {
	def cmdResponse = ""
	if (!useKasaCloud) {
		cmdResponse = ["error": "App not set for Kasa Cloud"]
		return cmdResponse
	}
	def cmdBody = [
		method: "passthrough",
		params: [
			deviceId: deviceId,
			requestData: "${command}"
		]
	]
	def sendCloudCmdParams = [
		uri: "${appServerUrl}/?token=${kasaToken}",
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		timeout: 5,
		body : new groovy.json.JsonBuilder(cmdBody).toString()
	]
	httpPostJson(sendCloudCmdParams) {resp ->
		if (resp.status == 200 && resp.data.error_code == 0) {
			def jsonSlurper = new groovy.json.JsonSlurper()
			cmdResponse = jsonSlurper.parseText(resp.data.result.responseData)
		} else {
			logWarn("sendKasaCmd: Error returned from Kasa Cloud")
			cmdResponse = ["error": "${resp.data.error_code} = ${resp.data.msg}"]
		}
	}
	return cmdResponse
}

//	===== Coordinate Bulb and Light Strip Preset Data =====
def syncBulbPresets(bulbPresets, devType) {
	logDebug("syncBulbPresets")
	def devices = state.devices
	devices.each {
		def type = it.value.type
		if (type == "Kasa ${devType}") {
			def child = getChildDevice(it.value.dni)
			if (child) {
				child.updatePresets(bulbPresets)
			}
		}
	}
}

def resetStates(deviceNetworkId) {
	logDebug("resetStates: ${deviceNetworkId}")
	def devices = state.devices
	devices.each {
		def type = it.value.type
		def dni = it.value.dni
		if (type == "Kasa Light Strip") {
			def child = getChildDevice(dni)
			if (child && dni != deviceNetworkId) {
				child.resetStates()
			}
		}
	}
}
	
def syncEffectPreset(effData, deviceNetworkId) {
	logDebug("syncEffectPreset: ${effData.name} || ${deviceNetworkId}")
	def devices = state.devices
	devices.each {
		def type = it.value.type
		def dni = it.value.dni
		if (type == "Kasa Light Strip") {
			def child = getChildDevice(dni)
			if (child && dni != deviceNetworkId) {
				child.updateEffectPreset(effData)
			}
		}
	}
}

//	===== Coordinate between multi-plugs =====
def coordinate(cType, coordData, deviceId, plugNo) {
	logDebug("coordinate: ${cType}, ${coordData}, ${deviceId}, ${plugNo}")
	def plugs = state.devices.findAll{ it.value.deviceId == deviceId }
	plugs.each {
		if (it.value.plugNo != plugNo) {
			def child = getChildDevice(it.value.dni)
			if (child) {
				child.coordUpdate(cType, coordData)
				pauseExecution(700)
			}
		}
	}
}

//	===== Utility Methods =====
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

def logTrace(msg){ log.trace "[KasaInt/${appVersion()}_R${rel()}] ${msg}" }

def logDebug(msg){
	if(debugLog == true) { log.debug "[KasaInt/${appVersion()}_R${rel()}]: ${msg}" }
}

def logInfo(msg){ log.info "[KasaInt/${appVersion()}_R${rel()}]: ${msg}" }

def logWarn(msg) { log.warn "[KasaInt/${appVersion()}_R${rel()}]: ${msg}" }

//	Page Instructions
def stPgIns() {
	def startPgIns = "Use Alternate Lan Segment: Use if you use a different LAN segment for your devices that your Hub.  Usually false."
	startPgIns += "\n\nAlternate Lan Segment: Displayed whte Use Alternate Lan Segment is true.  Enter alternate segment."
	startPgIns += "\n\nInterface to Kasa Cloud: Select if you want to use the Kasa Cloud for some devices.  "
	startPgIns += "Device must be bound to the Kasa Cloud. If not selected, Kasa Cloud will not be accessed even if previously active."
	startPgIns += "\n\nKasa Login and Token Update: Displayed whe Interface to Kasa Cloud is true.  Access Kasa Login page."
	startPgIns += "\n\nInstall Kasa Devices / Update Installed Devices: Searches for Kasa Devices and allows selection for adding to Hubitat Hub.  "
	startPgIns += "Also updated data and Saves Preferences on installed devices, i.e., after updating the app/driver versions."
	startPgIns += "\n\nRemove Kasa Devices: Searches for installed devices and allows removal of those devices."
	return startPgIns
}

//	end-of-file