/*	Kasa Local Integration
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== 2021 History =====
01.25	6.0.0.  Update to combine Cloud and Local LAN integration.
		a.	Added operator selected cloud function.
		b.	Modified data creation to accommodate using cloud.
		c.	Changed device data update process to include Save Preferences on child devices.
=======================================================================================================*/
def appVersion() { return "6.0.0" }
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
				title: "<b>Display instructions on page commands</b>",
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
					description: "Go to Kasa Login Update.  Current token = ${state.TpLinkToken}"
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
    					title: "Initial Kasa Login Page",
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
			if (userName != null && userPassword != null) {
				href "getToken", title: "Get or Update Kasa Token", description: "Tap to Get Kasa Token"
            }
			paragraph "Select  '<'  at upper left corner to exit."
		}
	}
}
def getToken() {
	logInfo("getToken ${userName}")
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
			state.TpLinkToken = resp.data.result.token
			log.info "TpLinkToken updated to ${state.TpLinkToken}"
		} else {
			log.error "Error obtaining token from Kasa Cloud."
		}
	}
	pauseExecution(2000)
	startPage()
}

//	Add Devices
def addDevicesPage() {
	logDebug("addDevicesPage")
	state.devices = [:]
	findDevices("parseLanData")
	
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
					   nextPage: startPage,
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
	def hub = location.hubs[0]
	selectedAddDevices.each { dni ->
		def isChild = getChildDevice(dni)
		if (!isChild) {
			def device = state.devices.find { it.value.dni == dni }
			def deviceData = [:]
			deviceData["applicationVersion"] = appVersion()
			deviceData["deviceIP"] = device.value.ip
			deviceData["appServerUrl"] = device.value.appServerUrl
			deviceData["plugNo"] = device.value.plugNo
			deviceData["plugId"] = device.value.plugId
			deviceData["deviceId"] = device.value.deviceId
			try {
				addChildDevice(
					"davegut",
					"Kasa ${device.value.type}",
					device.value.dni,
					hub.id, [
						"label": device.value.alias,
						"name" : device.value.model,
						"data" : deviceData
					]
				)
				logInfo("Installed ${device.value.alias}. Data = ${device}")
			} catch (error) {
				logWarn("Failed to install device.  Data = ${device}")
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
	findDevices("parseLanData")
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
					   nextPage: startPage,
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

//	Get Device Data Methods
def findDevices(action) {
	logInfo("findDevices: Searching for LAN deivces on IP Segment = ${lanSegment}")
	for(int i = 2; i < 255; i++) {
		def deviceIP = "${lanSegment}.${i.toString()}"
		sendLanCmd(deviceIP, """{"system":{"get_sysinfo":{}}}""", action)
		pauseExecution(25)
	}
	if (useKasaCloud == true) {
		cloudGetDevices()
	}
	runIn(5, updateChildDeviceData)
}
def parseLanData(response) {
	def resp = parseLanMessage(response.description)
	if (resp.type != "LAN_TYPE_UDPCLIENT") { return }
	def clearResp = inputXOR(resp.payload)
	if (clearResp.length() > 1022) {
		clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}"
	}
	def cmdResp
	cmdResp = new JsonSlurper().parseText(clearResp).system.get_sysinfo
	def ip = convertHexToIP(resp.ip)
	logDebug("parseLanData: ${ip} // ${cmdResp}")
	def dni
	if (cmdResp.mic_mac == null) {
		dni = cmdResp.mac.replace(/:/, "")
	} else {
		dni = cmdResp.mic_mac
	}
	def alias = cmdResp.alias
	def model = cmdResp.model.substring(0,5)
	def plugNo
	def plugId
	def deviceId = cmdResp.deviceId
	def appServerUrl
	if (cmdResp.children) {
		def childPlugs = cmdResp.children
		childPlugs.each {
			plugNo = it.id
			def childDni = "${dni}${plugNo}"
			plugId = "${deviceId}${plugNo}"
			alias = it.alias
			updateDevices(childDni, ip, alias, model, plugNo, appServerUrl, deviceId, plugId)
		}
	} else {
		updateDevices(dni, ip, alias, model, plugNo, appServerUrl, deviceId, plugId)
	}
}
def cloudGetDevices() {
	logInfo("cloudGetDevices ${state.TpLinkToken}")
	def currentDevices = ""
	def cmdBody = [method: "getDeviceList"]
	def getDevicesParams = [
		uri: "https://wap.tplinkcloud.com?token=${state.TpLinkToken}",
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		body : new groovy.json.JsonBuilder(cmdBody).toString()
	]
	httpPostJson(getDevicesParams) {resp ->
		if (resp.status == 200 && resp.data.error_code == 0) {
			currentDevices = resp.data.result.deviceList
		} else {
			logWarn("Error getting data from the Kasa Cloud")
		}
	}
	currentDevices.each {
		cloudFinishGet(it)
	}
}
def cloudFinishGet(device) {
	def dni = device.deviceMac
	def ip
	def alias = device.alias
	def model = device.deviceModel.substring(0,5)
	def plugNo
	def plugId
	def deviceId = device.deviceId
	def appServerUrl = device.appServerUrl
	def cmdResp = sendKasaCmd(device.appServerUrl, device.deviceId, '{"system":{"get_sysinfo":{}}}')
	if (cmdResp.error) {
		logWarn("cloudFinishGet: Error = ${cmdResp.error}")
		return
	}
	if (cmdResp.system.get_sysinfo.children) {
		def childPlugs = cmdResp.system.get_sysinfo.children
		childPlugs.each {
			alias = it.alias
			plugId = it.id
			plugNo = it.id.substring(it.id.length() - 2)
			def childDni = "${dni}${plugNo}"
			updateDevices(childDni, ip, alias, model, plugNo, appServerUrl, deviceId, plugId)
		}
	} else {
		updateDevices(dni, ip, alias, model, plugNo, appServerUrl, deviceId, plugId)
	}
}
def updateDevices(dni, ip, alias, model, plugNo, appServerUrl, deviceId, plugId) {
	logDebug("updateDevices")
	def devices = state.devices

	def existingDev = devices.find { it.key == dni }
	if (existingDev) {
		if (ip == null) {
			ip = existingDev.value.ip
		}
		if (appServerUrl == null) {
			appServerUrl = existingDev.value.appServerUrl
		}
	}

	def device = [:]
	device["dni"] = dni
	device["alias"] = alias
	device["model"] = model
	device["type"] = getType(model)
	device["plugNo"] = plugNo
	device["plugId"] = plugId
	device["ip"] = ip
	device["deviceId"] = deviceId
	device["appServerUrl"] = appServerUrl
	devices << ["${dni}" : device]
	logDebug("updateDevices: added to array ${alias} = ${device}")
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
//		case "KL430" :
			return "Color Bulb"
			break
		default :
			logWarn("getType: Model not on current list.  Contact developer.")
	}
}
def updateChildDeviceData() {
	logDebug("updateChildDeviceData")
	def devices = state.devices
	devices.each {
		def child = getChildDevice(it.key)
		if (child) {
			child.updateDataValue("deviceIP", it.value.ip)
			child.updateDataValue("applicationVersion", appVersion())
			child.updateDataValue("appServerUrl", it.value.appServerUrl)
			child.updateDataValue("deviceId", it.value.deviceId)
			child.updateDataValue("plugNo", it.value.plugNo)
			child.updateDataValue("plugId", it.value.plugId)
			logDebug("updateChildDeviceData: ${it.value.alias} updated using ${it}")
		}
	}
	
}

//	Local LAN Update IP Data on Error
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
		findDevices(updateDeviceIps)
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
def sendKasaCmd(appServerUrl, deviceId, command) {
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
		uri: "${appServerUrl}/?token=${state.TpLinkToken}",
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
def logDebug(msg){
	if(debugLog == true) { log.debug "${appVersion()} ${msg}" }
}
def logInfo(msg){ log.info "${appVersion()} ${msg}" }
def logWarn(msg) { log.warn "${appVersion()} ${msg}" }
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
