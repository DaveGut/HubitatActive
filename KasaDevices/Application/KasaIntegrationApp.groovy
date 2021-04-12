/*	Kasa Local Integration
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Changes from 6.1 =====
1.	Added coordinate method to support multi-plug outlet data/state coordination.
2.	Cleaned up page displayed documentation.
=======================================================================================================*/
def appVersion() { return "6.3.0" }
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
	page(name: "getToken")
}
def installed() { initialize() }
def updated() { initialize() }
def initialize() {
	logInfo("initialize")
	unschedule()
	if (useKasaCloud == true) {
		schedule("0 30 2 ? * WED", getToken)
	}
}
def uninstalled() {
    getAllChildDevices().each { 
        deleteChildDevice(it.deviceNetworkId)
    }
}

def startPage() {
	logInfo("starting Kasa Integration, Version ${appVersion()}")
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
	if (!lanSegment) {
		def hub = location.hubs[0]
		def hubIpArray = hub.localIP.split('\\.')
		def networkPrefix = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")
		app?.updateSetting("lanSegment", [type:"string", value: networkPrefix])
	}

	return dynamicPage(name:"startPage",
					   title:"<b>Kasa Local Hubitat Integration, Version ${appVersion()}</b>",
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
			input "altLanSegment", "bool",
				title: "<b>Use Alternate LAN Segment</b>",
				submitOnChange: true,
				defaultalue: false
			if (altLanSegment == true) {
				input "lanSegment", "string",
					title: "<b>Alternate Lan Segment.</b>",
					description: "Select if your devices and Hub are on different LAN segments",
					submitOnChange: true
			}
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
				title: "<b>Install Kasa Devices / Update Installed Devices</b>",
				description: "Installs newly detected Kasa Device."
			href "removeDevicesPage",
				title: "<b>Remove Kasa Devices</b>",
				description: "Removes user selected Kasa Device."
			input "debugLog", "bool", 
				title: "<b>Enable debug logging for 30 minutes</b>", 
				submitOnChange: true,
				defaultValue: false
		}
	}
}

//	Get Kasa Cloud Credentials
def kasaAuthenticationPage() {
	logDebug("kasaAuthenticationPage")
	return dynamicPage (name: "kasaAuthenticationPage", 
    					title: "Initial Kasa Login Page, Version ${appVersion()}",
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
//			if (userName != null && userPassword != null) {
			if (userName && userPassword && userName != null && userPassword != null) {
				href "getToken", title: "Get or Update Kasa Token", description: "Tap to Get Kasa Token"
            }
			paragraph "Select  '<'  at upper left corner to exit."
		}
	}
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
			message += "getToken: TpLinkToken updated to ${resp.data.result.token}"
			logInfo(message)
		} else {
			message += "getToken: Error obtaining token from Kasa Cloud."
			logWarn(message)
		}
	}
	startPage()
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
			uninstalledDevices["${it.value.dni}"] = "${it.value.alias} // ${it.value.type}"
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
					   title: "Add Kasa Devices to Hubitat, Version ${appVersion()}",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			paragraph pageInstructions
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
						"\n<b>Driver: Dev ${device.value.type}")
			}
		}
		pauseExecution(3000)
	}
	app?.removeSetting("selectedAddDevices")
}

//	Remove Devices
def removeDevicesPage() {
	logDebug("removeDevicesPage")
	state.devices = [:]
	findDevices()
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
		section("Select Devices to Remove from Hubitat, Version ${appVersion()}") {
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

//	Get Device Data Methods
def findDevices() {
	logInfo("findDevices: Searching for LAN deivces on IP Segment = ${lanSegment}")
	for(int i = 2; i < 255; i++) {
		def deviceIP = "${lanSegment}.${i.toString()}"
		sendLanCmd(deviceIP, """{"system":{"get_sysinfo":{}}}""", "parseLanData")
		pauseExecution(25)
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
	def cloudUrl
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
	if (resp.type != "LAN_TYPE_UDPCLIENT") { return }
	def clearResp = inputXOR(resp.payload)
	if (clearResp.length() > 1022) {
		clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}"
	}
	def ip = convertHexToIP(resp.ip)
	def cmdResp = new JsonSlurper().parseText(clearResp).system.get_sysinfo
	parseDeviceData(cmdResp, ip)
}

//def parseDeviceData(cmdResp, appServerUrl = null, ip = null) {
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
			type = "Kasa Color Bulb"
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
	logDebug("updateDevices: ${alias} added to array. Data = ${device}")
}
def updateChildren() {
	logDebug("updateChildDeviceData")
	def devices = state.devices
	devices.each {
		def child = getChildDevice(it.key)
		if (child) {
			child.updateDataValue("type", it.value.type)
			child.updateDataValue("feature", it.value.feature)
			child.updateDataValue("deviceId", it.value.deviceId)
			child.updateDataValue("deviceIP", it.value.ip)
			child.updated()
		}
	}
}

//	Local LAN Update IP Data on Error
def updateIpData() {
	logInfo("requestDataUpdate: Received device IP request from a Kasa device.")
	runIn(5, pollForIps)
	return "Parent attempting to update IP Data for devices."
}
def pollForIps() {
	if (pollEnabled == false) {
		logWarn("pollForIps: a poll was run within the 15 min.  Poll not run.  Try running manually through the application.")
		return
	} else {
		logInfo("pollForIps: Diabling poll capability for one hour")
		app?.updateSetting("pollEnabled", [type:"bool", value: false])
		runIn(900, pollEnable)
		for(int i = 2; i < 255; i++) {
			def deviceIP = "${lanSegment}.${i.toString()}"
			sendLanCmd(deviceIP, """{"system":{"get_sysinfo":{}}}""", "updateDeviceIps")
			pauseExecution(25)
		}
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

//	===== Device Communications =====
private sendLanCmd(ip, command, action) {
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${ip}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 parseWarning: true,
		 timeout: 3,
		 callback: action])
	sendHubCommand(myHubAction)
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

//	===== Coordinate between multiPlug =====
def coordPoll(deviceId, plugNo, data) {
//	logDebug("coordPoll: ${deviceId} ${data}")
	def devices = state.devices
	devices.each {
			def child = getChildDevice(it.value.dni)
		if (child && it.value.plugNo != plugNo) {
			child.coordPoll(data)
		}
	}
}
def coordinate(deviceId, plugNo, type, data) {
	logDebug("coordinate: ${deviceId} / ${plugNo} / ${type} / ${data}")
	def devices = state.devices
	devices.each {
		if (it.value.deviceId == deviceId) {
			def child = getChildDevice(it.value.dni)
			if (child) {
				child.coord(type, data, plugNo)
			}
		}
	}
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
def logTrace(msg){ log.trace "[KasaInt/${appVersion()}] ${device.label} ${msg}" }
def logDebug(msg){
	if(debugLog == true) { log.debug "[KasaInt/${appVersion()}]: ${msg}" }
}
def logInfo(msg){ log.info "[KasaInt/${appVersion()}]: ${msg}" }
def logWarn(msg) { log.warn "[KasaInt/${appVersion()}]: ${msg}" }
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
