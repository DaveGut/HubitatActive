/*	Kasa Integration Application
	Copyright Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Link to list of changes =====
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Changes.pdf
===== Link to Documentation =====
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Documentation.pdf

Version 2.3.6
	1.	Added initial support for NEW Kasa Matter plugs and switches.
	2.	Added support for the Kasa KH100 Hub
Changed menus for Hubitat device.

===================================================================================================*/
//	App name is used in the lib_tpLink_discovery to check that the device brand is KASA
def appName() { return "Kasa Integration" }
def nameSpace() { return "davegut" }
def appVersion() { return "2.3.6" }
import groovy.json.JsonSlurper
import java.security.MessageDigest




definition(
	name: "Kasa Integration",
	namespace: nameSpace(),
	author: "Dave Gutheinz",
	description: "Application to install TP-Link bulbs, plugs, and switches.",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	installOnOpen: true,
	singleInstance: true,
	documentationLink: "https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/README.md",
	importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/Application/KasaIntegrationApp.groovy"
)

preferences {
	page(name: "initInstance")
	page(name: "startPage")
	page(name: "lanAddDevicesPage")
	page(name: "manAddDevicesPage")
	page(name: "manAddStart")
	page(name: "cloudAddDevicesPage")
	page(name: "cloudAddStart")
	page(name: "addDevicesPage")
	page(name: "addDevStatus")
	page(name: "listDevices")
	page(name: "kasaAuthenticationPage")
	page(name: "startGetToken")
	page(name: "removeDevicesPage")
	page(name: "listDevicesByIp")
	page(name: "listDevicesByName")
	page(name: "commsTest")
	page(name: "commsTestDisplay")
	page(name: "enterCredentialsPage")
	page(name: "processCredentials")
}

def installed() { updated() }

def updated() {
	logInfo("updated: Updating device configurations and (if cloud enabled) Kasa Token")
	unschedule()
	app?.updateSetting("appSetup", [type:"bool", value: false])
	app?.updateSetting("utilities", [type:"bool", value: false])
	app?.updateSetting("debugLog", [type:"bool", value: false])
	app?.removeSetting("pingKasaDevices")
	app?.removeSetting("devAddresses")
	app?.removeSetting("devPort")
	state.remove("lanTest")
	state.remove("addedDevices")
	state.remove("failedAdds")
	state.remove("listDevices")
	configureEnable()
	if (userName && userName != "") {
		schedule("0 30 2 ? * MON,WED,SAT", schedGetToken)
	}
}

def uninstalled() {
    getAllChildDevices().each { 
        deleteChildDevice(it.deviceNetworkId)
    }
}

def initInstance() {
	logDebug("initInstance: Getting external data for the app.")
	if (!debugLog) { app.updateSetting("debugLog", false) }
	state.devices = [:]
	if (!lanSegment) {
		def hub = location.hub
		def hubIpArray = hub.localIP.split('\\.')
		def segments = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")
		app?.updateSetting("lanSegment", [type:"string", value: segments])
	}
	if (!ports) {
		app?.updateSetting("ports", [type:"string", value: "9999"])
	}
	if (!hostLimits) {
		app?.updateSetting("hostLimits", [type:"string", value: "1, 254"])
	}
	startPage()
}

def startPage() {
	logInfo("starting Kasa Integration")
	if (selectedRemoveDevices) { removeDevices() }
	if (selectedAddDevices) { addDevices() }
	if (debugLog) { runIn(1800, debugOff) }
	try {
		state.segArray = lanSegment.split('\\,')
		state.portArray = ports.split('\\,')
		def rangeArray = hostLimits.split('\\,')
		def array0 = rangeArray[0].toInteger()
		def array1 = array0 + 2
		if (rangeArray.size() > 1) {
			array1 = rangeArray[1].toInteger()
		}
		state.hostArray = [array0, array1]
	} catch (e) {
		logWarn("startPage: Invalid entry for Lan Segements, Host Array Range, or Ports. Resetting to default!")
		def hub = location.hubs[0]
		def hubIpArray = hub.localIP.split('\\.')
		def segments = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")
		app?.updateSetting("lanSegment", [type:"string", value: segments])
		app?.updateSetting("ports", [type:"string", value: "9999"])
		app?.updateSetting("hostLimits", [type:"string", value: "1, 254"])
	}
	return dynamicPage(name:"startPage",
					   title:"<b>Kasa Hubitat Integration</b>",
					   uninstall: true,
					   install: true) {
		section() {
			paragraph "<b>LAN Configuration</b>:  [LanSegments: ${state.segArray},  " +
				"Ports ${state.portArray},  hostRange: ${state.hostArray}]"
			input "appSetup", "bool",
				title: "<b>Modify LAN Configuration</b>",
				submitOnChange: true,
				defaultalue: false
			if (appSetup) {
				input "lanSegment", "string",
					title: "<b>Lan Segments</b> (ex: 192.168.50, 192,168.01)",
					submitOnChange: true
				input "hostLimits", "string",
					title: "<b>Host Address Range</b> (ex: 5, 100)",
					submitOnChange: true
				input "ports", "string",
					title: "<b>Ports for Port Forwarding</b> (ex: 9999, 8000)",
					submitOnChange: true
			}
			
			if (!encUsername || !encPassword) {
				def credText = "<b>Credentials May require attention</b>\n"
				credText += "Kasa Matter and Kasa Hub required use of LOCAL only credentials "
				credText += "that are parsed versions of your Kasa username and password.  Enter "
				credText += "these credentials below to install Kasa Matter and Kasa Hub devices."
				paragraph credText
			}
			paragraph credData
			href "enterCredentialsPage",
				title: "<b>Enter/Update tpLink Credentials</b>",
				description: "Credentials are used by app and tpLink devices during periodic login."

//			if (encUsername && encPassword) {
				href "lanAddDevicesPage",
					title: "<b>Scan LAN for Kasa devices and add</b>",
					description: "Primary Method to discover and add devices."
				input "altInstall", "bool",
					   title: "<b>Problems with Install?  Try Cloud Installation.</b>",
					   submitOnChange: true,
					   defaultalue: false
				if (altInstall) {
					href "cloudAddDevicesPage",
						title: "<b>Get Kasa devices from the Kasa Cloud and add</b>",
						description: "For use with devices that can't be controlled on LAN."
					}

				paragraph " "
				href "removeDevicesPage",
					title: "<b>Remove Kasa Devices</b>",
					description: "Select to remove selected Kasa Device from Hubitat."
				paragraph " "

				input "utilities", "bool",
					title: "<b>Kasa Integration Utilities</b>",
					submitOnChange: true,
					defaultalue: false
				if (utilities == true) {
						href "listDevicesByIp",
							title: "<b>Test Device LAN Status and List Devices by IP Address</b>",
							description: "Select to test devices and get list."	

					href "listDevicesByName",
						title: "<b>Test Device LAN Status and List Devices by Name</b>",
						description: "Select to test devices and get list."

					href "commsTest", title: "<b>IP Comms Ping Test Tool</b>",
						description: "Select for Ping Test Page."
				}
//			}
			input "debugLog", "bool",
				   title: "<b>Enable debug logging for 30 minutes</b>",
				   submitOnChange: true,
				   defaultValue: false
		}
	}
}

//	===== Enter Creentials =====
def enterCredentialsPage() {
	logInfo("enterCredentialsPage")
	return dynamicPage (name: "enterCredentialsPage", 
    					title: "Enter TP-Link Credentials",
						nextPage: startPage,
                        install: false) {
		section() {
			String currState = "<b>Current Credentials</b> = "
			if (state.userCredentials) {
				currState += "${state.userCredentials}"
			} else {
				currState += "NOT SET"
			}
			paragraph currState
			input ("userName", "email",
            		title: "TP-Link Email Address", 
                    required: false,
                    submitOnChange: true)
			input ("userPassword", "password",
            		title: "TP-Link Account Password",
                    required: false,
                    submitOnChange: true)
			if (userName && userPassword && userName != null && userPassword != null) {
				logDebug("enterCredentialsPage: [username: ${userName}, pwdLen: ${userPassword.length()}]")
				href "processCredentials", title: "Create Encoded Credentials",
					description: "You may have to press this twice."
			}
		}
	}
}

private processCredentials() {
	String encUsername = mdEncode(userName).bytes.encodeBase64().toString()
	app?.updateSetting("encUsername", [type: "password", value: encUsername])
	Map logData = [encUsername: encUsername]
	String encPassword = userPassword.bytes.encodeBase64().toString()
	app?.updateSetting("encPassword", [type: "password", value: encPassword])
	logData << [encPassword: encPassword]
	logInfo("processCredentials: ${logData}")
	return startPage()
}

private String mdEncode(String message) {
	MessageDigest md = MessageDigest.getInstance("SHA-1")
	md.update(message.getBytes())
	byte[] digest = md.digest()
	return digest.encodeHex()
}

def lanAddDevicesPage() {
	logInfo("lanAddDevicesPage")
	addDevicesPage("LAN")
}

def cloudAddDevicesPage() {
	logInfo("cloudAddDevicesPage")
	return dynamicPage (name: "cloudAddDevicesPage", 
    					title: "Get device data from Kasa Cloud",
						nextPage: startPage,
                        install: false) {
		def note = "Instructions: \n\ta.\tIf not already done, select 'Kasa " +
			"Login and Token Update. \n\tb.\tVerify the token is not null. " +
			"\n\tc.\tSelect 'Add Devices to the Device Array'."
		section() {
			paragraph note
			href "kasaAuthenticationPage",
				title: "<b>Kasa Login and Token Update</b>",
				description: "Get token"
			paragraph "<b>Current Kasa Token</b>: = ${kasaToken}" 
			href "cloudAddStart", title: "<b>Add Devices to the Device Array</b>",
				description: "Press to continue"
			href "startPage", title: "<b>Exit without Updating</b>",
				description: "Return to start page without attempting"
		}
	}
}

def cloudAddStart() { addDevicesPage("CLOUD") }

def addDevicesPage(discType) {
	logDebug("addDevicesPage: [scan: ${scan}]")
	if (discType == "LAN") {
		def action = findDevices()
	} else if (discType == "CLOUD") {
		def action = cloudGetDevices()
	}
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
	uninstalledDevices.sort()
	def reqDrivers = []
	requiredDrivers.each {
		reqDrivers << it.key
	}

	return dynamicPage(name:"addDevicesPage",
					   title: "Add Kasa Devices to Hubitat",
					   nextPage: addDevStatus,
					   install: false) {
		def text = "This page updates every 30 seconds. "
		text += "It can take up to two minutes for all discovered devices to appear."
	 	section() {
			paragraph text
			input ("selectedAddDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Devices to add (${uninstalledDevices.size() ?: 0} available).\n\t" +
				   "Total Devices: ${devices.size()}",
				   description: "Use the dropdown to select devices.  Then select 'Done'.",
				   options: uninstalledDevices)
		}
	}
}

def addDevStatus() {
	addDevices()
	logInfo("addDevStatus")
	def addMsg = ""
	if (state.addedDevices == null) {
		addMsg += "Added Devices: No devices added."
	} else {
		addMsg += "<b>The following devices were installed:</b>\n"
		state.addedDevices.each{
			addMsg += "\t${it}\n"
		}
	}
	def failMsg = ""
	if (state.failedAdds) {
		failMsg += "<b>The following devices were not installed:</b>\n"
		state.failedAdds.each{
			failMsg += "\t${it}\n"
		}
	}
		
	return dynamicPage(name:"addDeviceStatus",
					   title: "Installation Status",
					   nextPage: listDevices,
					   install: false) {
	 	section() {
			paragraph addMsg
			paragraph failMsg
		}
	}
	app?.removeSetting("selectedAddDevices")
}

def addDevices() {
	logInfo("addDevices: [selectedDevices: ${selectedAddDevices}]")
	def hub = location.hubs[0]
	state.addedDevices = []
	state.failedAdds = []
	selectedAddDevices.each { dni ->
		def isChild = getChildDevice(dni)
		if (!isChild) {
			def device = state.devices.find { it.value.dni == dni }
			def alias = device.value.alias.replaceAll("[\u201C\u201D]", "\"").replaceAll("[\u2018\u2019]", "'").replaceAll("[^\\p{ASCII}]", "")
			def deviceData = [:]
			deviceData["deviceIP"] = device.value.ip
			deviceData["deviceId"] = device.value.deviceId
			if (device.value.type.contains("Smart")) {
				deviceData << [encUsername: encUsername]
				deviceData << [encPassword: encPassword]
				deviceData << [capability: device.value.capability]
			} else {
				deviceData["devicePort"] = device.value.port
				deviceData["model"] = device.value.model
				deviceData["feature"] = device.value.feature
				if (device.value.plugNo) {
					deviceData["plugNo"] = device.value.plugNo
					deviceData["plugId"] = device.value.plugId
				}
			}
			try {
				addChildDevice(
					nameSpace(),
					device.value.type,
					device.value.dni,
					[
						"label": alias,
						"data" : deviceData
					]
				)
				state.addedDevices << [label: device.value.alias, ip: device.value.ip]
				logInfo("Installed ${device.value.alias}.")
			} catch (error) {
				state.failedAdds << [label: device.value.alias, driver: device.value.type, ip: device.value.ip]
				def msgData = [status: "failedToAdd", label: device.value.alias, driver: device.value.type, ip: device.value.ip]
				msgData << [errorMsg: error]
				logWarn("addDevice: ${msgData}")
			}
		}
		pauseExecution(3000)
	}
	app?.removeSetting("selectedAddDevices")
}

def listDevices() {
	logInfo("listDevices")
	def theList = ""
	def theListTitle= ""
	def devices = state.devices
	if (devices == null) {
		theListTitle += "<b>No devices in the device database.</b>"
	} else {
		theListTitle += "<b>Total Kasa devices: ${devices.size() ?: 0}</b>\n"
		theListTitle +=  "<b>Alias: [Ip:Port, RSSI, Installed?</b>]\n"
		def deviceList = []
		devices.each{
			def dni = it.key
			def installed = "No"
			def isChild = getChildDevice(it.key)
			if (isChild) {
				installed = "Yes"
			}
			deviceList << "<b>${it.value.alias} - ${it.value.model}</b>: [${it.value.ip}:${it.value.port}, ${it.value.rssi}, ${installed}]"
		}
		deviceList.each {
			theList += "${it}\n"
		}
		deviceList.sort()
	}
	return dynamicPage(name:"listDevices",
					   title: "List Kasa Devices from Add Devices",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			paragraph theListTitle
			paragraph "<p style='font-size:14px'>${theList}</p>"
		}
	}
}

def kasaAuthenticationPage() {
	logInfo("kasaAuthenticationPage")
	return dynamicPage (name: "kasaAuthenticationPage", 
    					title: "Initial Kasa Login Page",
						nextPage: startPage,
                        install: false) {
		def note = "You only need to get a token " +
			"if you need to use the cloud integration.  This is unusual.  If you wish " +
			"to not get a token, simply press 'Exit without Credentials' below." +
			"\n\nIf you have already installed and find you need the cloud:" +
			"\na.\tEnter the credentials and get a token" +
			"\nb.\tRun Install Kasa Devices"
        section("Get Kasa Token: ") {
			paragraph note
			if (userName && userPassword && userName != null && userPassword != null) {
				href "startGetToken", title: "Get or Update Kasa Token", 
					description: "Tap to Get Kasa Token"
			href "startPage", title: "<b>Exit without Updating</b>",
				description: "Return to start page without getting token"
			}
				
			paragraph "Select  '<'  at upper left corner to exit."
		}
	}
}

def startGetToken() {
	logInfo("getTokenFromStart: Result = ${getToken()}")
	cloudAddDevicesPage()
}

def getToken() {
	def message = []
	def termId = java.util.UUID.randomUUID()
	def cmdBody = [
		method: "login",
		params: [
			appType: "Kasa_Android",
			cloudUserName: "${userName}",
			cloudPassword: "${userPassword.replaceAll('&gt;', '>').replaceAll('&lt;','<')}",
			terminalUUID: "${termId}"]]
	cmdData = [uri: "https://wap.tplinkcloud.com",
			   cmdBody: cmdBody]
	def respData = sendKasaCmd(cmdData)
	if (respData.error_code == 0) {
		app?.updateSetting("kasaToken", respData.result.token)
		message << "[newToken: [data: ${respData.result.token}]"
		if (!kasaCloudUrl) {
			message << getCloudUrl()
		}
	} else {
		message << "[updateFailed: ${respData}]"
		logWarn("getToken: ${message}]")
		runIn(600, startGetToken)
	}
	return message
}

def getCloudUrl() {
	logInfo("cloudGetDevices ${kasaToken}")
	def message = []
	def cmdData = [uri: "https://wap.tplinkcloud.com?token=${kasaToken}", 
				   cmdBody: [method: "getDeviceList"]]
	def respData = sendKasaCmd(cmdData)
	if (respData.error_code == 0) {
		def cloudDevices = respData.result.deviceList
		def cloudUrl = cloudDevices[0].appServerUrl
		message << "[getCloudUrl: ${cloudUrl}]"
		app?.updateSetting("kasaCloudUrl", cloudUrl)
	} else {
		message << "[getCloudUrl: Devices not returned from Kasa Cloud]"
		logWarn("getCloudUrl: <b>Devices not returned from Kasa Cloud.</b> Return = ${respData}\n\r")
	}
	return message
}

def schedGetToken() {
	logInfo("schedGetToken: Result = ${getToken()}")
}

def findDevices() {
	def start = state.hostArray.min().toInteger()
	def finish = state.hostArray.max().toInteger() + 1
	logInfo("findDevices: [hostArray: ${state.hostArray}, portArray: ${state.portArray}, pollSegment: ${state.segArray}]")
	def cmdData = outputXOR("""{"system":{"get_sysinfo":{}}}""")
	state.portArray.each {
		def port = it.trim()
		List deviceIPs = []
		state.segArray.each {
			def pollSegment = it.trim()
			logInfo("findDevices: Searching for LAN deivces on IP Segment = ${pollSegment}, port = ${port}")
            for(int i = start; i < finish; i++) {
				deviceIPs.add("${pollSegment}.${i.toString()}")
			}
			sendLanCmd(deviceIPs.join(','), port, cmdData, "getLanData", 15)
			if (encUsername && encPassword) {
				pauseExecution(20000)
				cmdData = "0200000101e51100095c11706d6f58577b22706172616d73223a7b227273615f6b6579223a222d2d2d2d2d424547494e205055424c4943204b45592d2d2d2d2d5c6e4d494942496a414e42676b71686b6947397730424151454641414f43415138414d49494243674b43415145416d684655445279687367797073467936576c4d385c6e54646154397a61586133586a3042712f4d6f484971696d586e2b736b4e48584d525a6550564134627532416257386d79744a5033445073665173795679536e355c6e6f425841674d303149674d4f46736350316258367679784d523871614b33746e466361665a4653684d79536e31752f564f2f47474f795436507459716f384e315c6e44714d77373563334b5a4952387a4c71516f744657747239543337536e50754a7051555a7055376679574b676377716e7338785a657a78734e6a6465534171765c6e3167574e75436a5356686d437931564d49514942576d616a37414c47544971596a5442376d645348562f2b614a32564467424c6d7770344c7131664c4f6a466f5c6e33737241683144744a6b537376376a624f584d51695666453873764b6877586177717661546b5658382f7a4f44592b2f64684f5374694a4e6c466556636c35585c6e4a514944415141425c6e2d2d2d2d2d454e44205055424c4943204b45592d2d2d2d2d5c6e227d7d"
				sendLanCmd(deviceIPs.join(','), "20002", cmdData, "getSmartLanData", 15)
			}
		}
	}
	pauseExecution(20000)
	updateChildren()
	return
}

def getLanData(response) {
	if (response instanceof Map) {
		def lanData = parseLanData(response)
		if (lanData.error) { return }
		def cmdResp = lanData.cmdResp
		if (cmdResp.system) {
			cmdResp = cmdResp.system
		}
		parseDeviceData(cmdResp, lanData.ip, lanData.port)
	} else {
		response.each {
			def lanData = parseLanData(it)
			if (lanData.error) { return }
			def cmdResp = lanData.cmdResp
			if (cmdResp.system) {
				cmdResp = cmdResp.system
			}
			parseDeviceData(cmdResp, lanData.ip, lanData.port)
			if (lanData.cmdResp.children) {
				pauseExecution(120)
			} else {
				pauseExecution(40)
			}
		}
	}
}

def parseSmartDeviceData(devData) {
	def dni = devData.mac.replaceAll("-", "")
	Map deviceData = [dni: dni]
	String deviceType = devData.type
	byte[] plainBytes = devData.nickname.decodeBase64()
	String alias = new String(plainBytes)
	deviceData << [alias: alias]
	deviceData << [model: devData.model]
	deviceData << [ip: devData.ip]
	deviceData << [deviceId: devData.device_id]
	String capability = "newType"
	String feature
	if (deviceType == "SMART.KASASWITCH" || deviceType == "SMART.KASAPLUG") {
		capability = "plug"
		if (devData.brightness) {
			capability = "plug_dimmer"
		}
		if (devData.power_protection_status) {
			capability = "plug_em"
		}
	} else if (deviceType == "SMART.KASAHUB") {
		capability = "hub"
	}
	String type = "kasaSmart_${capability}"
	deviceData << [type: type]
	deviceData << [capability: capability]
	state.devices << ["${dni}": deviceData]
	logDebug("parseSmartDeviceData: [${dni}: ${deviceData}]")
}

def cloudGetDevices() {
	logInfo("cloudGetDevices ${kasaToken}")
	def message = ""
	def cmdData = [uri: "https://wap.tplinkcloud.com?token=${kasaToken}", 
				   cmdBody: [method: "getDeviceList"]]
	def respData = sendKasaCmd(cmdData)
	def cloudDevices
	def cloudUrl
	if (respData.error_code == 0) {
		cloudDevices = respData.result.deviceList
		cloudUrl = ""
	} else {
		message = "Devices not returned from Kasa Cloud."
		logWarn("cloudGetDevices: <b>Devices not returned from Kasa Cloud.</b> Return = ${respData}\n\r")
		return message
	}
	cloudDevices.each {
		if (it.deviceType != "IOT.SMARTPLUGSWITCH" && it.deviceType != "IOT.SMARTBULB" &&
		    it.deviceType != "IOT.IPCAMERA") {
			logInfo("<b>cloudGetDevice: Ignore device type ${it.deviceType}.")
		} else if (it.status == 0) {
			logInfo("cloudGetDevice: Device name ${it.alias} is offline and not included.")
			cloudUrl = it.appServerUrl
		} else {
			cloudUrl = it.appServerUrl
			def cmdBody = [
				method: "passthrough",
				params: [
					deviceId: it.deviceId,
					requestData: """{"system":{"get_sysinfo":{}}}"""]]
			cmdData = [uri: "${cloudUrl}/?token=${kasaToken}",
					   cmdBody: cmdBody]
			def cmdResp
			respData = sendKasaCmd(cmdData)
			if (respData.error_code == 0) {
				def jsonSlurper = new groovy.json.JsonSlurper()
				cmdResp = jsonSlurper.parseText(respData.result.responseData).system.get_sysinfo
				if (cmdResp.system) {
					cmdResp = cmdResp.system
				}
				parseDeviceData(cmdResp)
			} else {
				message = "Data for one or more devices not returned from Kasa Cloud.\n\r"
				logWarn("cloudGetDevices: <b>Device datanot returned from Kasa Cloud.</b> Return = ${respData}\n\r")
				return message
			}
		}
	}
	message += "Available device data sent to parse methods.\n\r"
	if (cloudUrl != "" && cloudUrl != kasaCloudUrl) {
		app?.updateSetting("kasaCloudUrl", cloudUrl)
		message += " kasaCloudUrl uptdated to ${cloudUrl}."
	}
	return message
}

def parseDeviceData(cmdResp, ip = "CLOUD", port = "CLOUD") {
	logDebug("parseDeviceData: ${cmdResp} //  ${ip} // ${port}")
	def dni
	if (cmdResp.mic_mac) {
		dni = cmdResp.mic_mac
	} else {
		dni = cmdResp.mac.replace(/:/, "")
	}
	def devices = state.devices
	def kasaType
	if (cmdResp.mic_type) {
		kasaType = cmdResp.mic_type
	} else {
		kasaType = cmdResp.type
	}
	def type = "Kasa Plug Switch"
	def feature = cmdResp.feature
	if (kasaType == "IOT.SMARTPLUGSWITCH") {
		if (cmdResp.dev_name && cmdResp.dev_name.contains("Dimmer")) {
			feature = "dimmingSwitch"
			type = "Kasa Dimming Switch"
		}		
	} else if (kasaType == "IOT.SMARTBULB") {
		if (cmdResp.lighting_effect_state) {
			feature = "lightStrip"
			type = "Kasa Light Strip"
		} else if (cmdResp.is_color == 1) {
			feature = "colorBulb"
			type = "Kasa Color Bulb"
		} else if (cmdResp.is_variable_color_temp == 1) {
			feature = "colorTempBulb"
			type = "Kasa CT Bulb"
		} else {
			feature = "monoBulb"
			type = "Kasa Mono Bulb"
		}
	} else if (kasaType == "IOT.IPCAMERA") {
		feature = "ipCamera"
		type = "NOT AVAILABLE"
	}
	def model = cmdResp.model.substring(0,5)
	def alias = cmdResp.alias
	def rssi = cmdResp.rssi
	def deviceId = cmdResp.deviceId
	def plugNo
	def plugId
	if (cmdResp.children) {
		def childPlugs = cmdResp.children
		childPlugs.each {
			plugNo = it.id
			plugNo = it.id.substring(it.id.length() - 2)
			def childDni = "${dni}${plugNo}"
			plugId = "${deviceId}${plugNo}"
			alias = it.alias
			def device = createDevice(childDni, ip, port, rssi, type, feature, model, alias, deviceId, plugNo, plugId)
			devices["${childDni}"] = device
			logDebug("parseDeviceData: ${type} ${alias} (${ip}) added to devices array.")
		}
	} else if (model == "HS300") {
		def parentAlias = alias
		for(int i = 0; i < 6; i++) {
			plugNo = "0${i.toString()}"
			def childDni = "${dni}${plugNo}"
			plugId = "${deviceId}${plugNo}"
			def child = getChildDevice(childDni)
			if (child) {
				alias = child.device.getLabel()
			} else {
				alias = "${parentAlias}_${plugNo}_TEMP"
			}
			def device = createDevice(childDni, ip, port, rssi, type, feature, model, alias, deviceId, plugNo, plugId)
			devices["${childDni}"] = device
			logDebug("parseDeviceData: ${type} ${alias} (${ip}) added to devices array.")
		}
	} else {
		def device = createDevice(dni, ip, port, rssi, type, feature, model, alias, deviceId, plugNo, plugId)
		devices["${dni}"] = device
		logDebug("parseDeviceData: ${type} ${alias} (${ip}) added to devices array.")
	}
}

def createDevice(dni, ip, port, rssi, type, feature, model, alias, deviceId, plugNo, plugId) {
	logDebug("createDevice: dni = ${dni}")
	def device = [:]
	device["dni"] = dni
	device["ip"] = ip
	device["port"] = port
	device["type"] = type
	device["rssi"] = rssi
	device["feature"] = feature
	device["model"] = model
	device["alias"] = alias
	device["deviceId"] = deviceId
	if (plugNo != null) {
		device["plugNo"] = plugNo
		device["plugId"] = plugId
	}
	return device
}

def removeDevicesPage() {
	logInfo("removeDevicesPage")
	def devices = state.devices
	def installedDevices = [:]
	devices.each {
		def installed = false
		def isChild = getChildDevice(it.value.dni)
		if (isChild) {
			installedDevices["${it.value.dni}"] = "${it.value.alias}, type = ${it.value.type}, dni = ${it.value.dni}"
		}
	}
	logDebug("removeDevicesPage: newDevices = ${newDevices}")
	return dynamicPage(name:"removedDevicesPage",
					   title:"<b>Remove Kasa Devices from Hubitat</b>",
					   nextPage: startPage,
					   install: false) {
		section("Select Devices to Remove from Hubitat") {
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
	def devices = state.devices
	selectedRemoveDevices.each { dni ->
		def device = state.devices.find { it.value.dni == dni }
		def isChild = getChildDevice(dni)
		if (isChild) {
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
	logInfo("listDevicesByIp")
	def deviceList = getDeviceList("ip")
	deviceList.sort()
	def theListTitle = "<b>Total Kasa devices: ${deviceList.size() ?: 0}</b>\n"
	theListTitle +=  "<b>[Ip:Port:  Alias, DriverVersion, Installed?</b>]\n"
	String theList = ""
	deviceList.each {
		theList += "${it}\n"
	}
	return dynamicPage(name:"listDevicesByIp",
					   title: "List Kasa Devices by IP",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			paragraph theListTitle
			paragraph "<p style='font-size:14px'>${theList}</p>"
		}
	}
}

def listDevicesByName() {
	logInfo("listDevicesByName")
	def deviceList = getDeviceList("name")
	deviceList.sort()
	def theListTitle = "<b>Total Kasa devices: ${deviceList.size() ?: 0}</b>\n"
	theListTitle += "<b>Alias: Ip:Port, DriverVersion, Installed?]</b>\n"
	String theList = ""
	deviceList.each {
		theList += "${it}\n"
	}
	return dynamicPage(name:"listDevicesByName",
					   title: "List Kasa Devices by Name",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			paragraph theListTitle
			paragraph "<p style='font-size:14px'>${theList}</p>"
		}
	}
}

def getDeviceList(sortType) {
	state.devices = [:]
	def getData = findDevices()
	def devices = state.devices
	def deviceList = []
	if (devices == null) {
		deviceList << "<b>No Devices in devices.</b>]"
	} else {
		devices.each{
			def dni = it.key
			def result = ["Failed", "n/a"]
			def driverVer = "ukn"
			def installed = "No"
			def isChild = getChildDevice(it.key)
			if (isChild) {
				driverVer = isChild.driverVer()
				installed = "Yes"
			}
			if (sortType == "ip") {
				deviceList << "<b>${it.value.ip}:${it.value.port}</b>: ${it.value.alias}, ${driverVer}, ${installed}]"
			} else {
				deviceList << "<b>${it.value.alias}</b>: ${it.value.ip}:${it.value.port}, ${driverVer}, ${installed}]"
			}
		}
	}
	return deviceList
}

def commsTest() {
	logInfo("commsTest")
	return dynamicPage(name:"commsTest",
					   title: "IP Communications Test",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			def note = "This test measures ping from this Hub to any device on your  " +
				"LAN (wifi and connected). You enter your Router's IP address, a " +
				"non-Kasa device (other hub if you have one), and select the Kasa " +
				"devices to ping. (Each ping will take about 3 seconds)."
			paragraph note
			input "routerIp", "string",
				title: "<b>IP Address of your Router</b>",
				required: false,
				submitOnChange: true
			input "nonKasaIp", "string",
				title: "<b>IP Address of non-Kasa LAN device (other Hub?)</b>",
				required: false,
				submitOnChange: true

			def devices = state.devices
			def kasaDevices = [:]
			devices.each {
				kasaDevices["${it.value.dni}"] = "${it.value.alias}, ${it.value.ip}"
 			}
			input ("pingKasaDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Kasa devices to ping (${kasaDevices.size() ?: 0} available).",
				   description: "Use the dropdown to select devices.",
				   options: kasaDevices)
			paragraph "Test will take approximately 5 seconds per device."
			href "commsTestDisplay", title: "<b>Ping Selected Devices</b>",
				description: "Click to Test IP Comms."

			href "startPage", title: "<b>Exit without Testing</b>",
				description: "Return to start page without attempting"
		}
	}
}

def commsTestDisplay() {
	logDebug("commsTestDisplay: [routerIp: ${routerIp}, nonKasaIp: ${nonKasaIp}, kasaDevices: ${pingKasaDevices}]")
	def pingResults = []
	def pingResult
	if (routerIp != null) {
		pingResult = sendPing(routerIp, 5)
		pingResults << "<b>Router</b>: ${pingResult}"
	}
	if (nonKasaIp != null) {
		pingResult = sendPing(nonKasaIp, 5)
		pingResults << "<b>nonKasaDevice</b>: ${pingResult}"
	}
	def devices = state.devices
	if (pingKasaDevices != null) {
		pingKasaDevices.each {dni ->
			def device = devices.find { it.value.dni == dni }
			pingResult = sendPing(device.value.ip, 5)
			pingResults << "<b>${device.value.alias}</b>: ${pingResult}"
		}
	}
	def pingList = ""
	pingResults.each {
		pingList += "${it}\n"
	}
	return dynamicPage(name:"commsTestDisplay",
					   title: "Ping Testing Result",
					   nextPage: commsTest,
					   install: false) {
		section() {
			def note = "<b>Expectations</b>:\na.\tAll devices have similar ping results." +
				"\nb.\tAll pings are less than 1000 ms.\nc.\tSuccess is 100." +
				"\nIf not, test again to verify bad results." +
				"\nAll times are in ms. Success is percent of 5 total tests."
			paragraph note
			paragraph "<p style='font-size:14px'>${pingList}</p>"
		}
	}
}

def sendPing(ip, count = 3) {
	hubitat.helper.NetworkUtils.PingData pingData = hubitat.helper.NetworkUtils.ping(ip, count)
	def success = "nullResults"
	def minTime = "n/a"
	def maxTime = "n/a"
	if (pingData) {
		success = (100 * pingData.packetsReceived.toInteger()  / count).toInteger()
		minTime = pingData.rttMin
		maxTime = pingData.rttMax
	}
	def pingResult = [ip: ip, min: minTime, max: maxTime, success: success]
	return pingResult
}

def updateConfigurations() {
	def msg = ""
	if (configureEnabled) {
		app?.updateSetting("configureEnabled", [type:"bool", value: false])
		configureChildren()
		runIn(600, configureEnable)
		msg += "Updating App and device configurations"
	} else {
		msg += "<b>Not executed</b>.  Method run within last 10 minutes."
	}
	logInfo("updateConfigurations: ${msg}")
	return msg
}

def configureEnable() {
	logDebug("configureEnable: Enabling configureDevices")
	app?.updateSetting("configureEnabled", [type:"bool", value: true])
}

def configureChildren() {
	def fixConnect = fixConnection(true)
	def children = getChildDevices()
	children.each {
		it.updated()
	}
}

def fixConnection(force = false) {
	def msg = "fixConnection: "
	if (pollEnabled == true || pollEnabled == null || force == true) {
		msg += execFixConnection()
		msg += "Checking and updating all device IPs."
	} else {
		msg += "[pollEnabled: false]"
	}
	logInfo(msg)
	return msg
}

def pollEnable() {
	logDebug("pollEnable: Enabling IP check from device error.")
	app?.updateSetting("pollEnabled", [type:"bool", value: true])
}

def execFixConnection() {
	def message = [:]
	app?.updateSetting("pollEnabled", [type:"bool", value: false])
	runIn(900, pollEnable)
	def pollDevs = findDevices()
	message << [segmentArray: state.segArray, hostArray: state.hostArray, portArray: state.portArray]
	def tokenUpd = false
	if (kasaToken && userName != "") {
		def token = getToken()
		tokenUpd = true
	}
	message << [tokenUpdated: tokenUpd]
	return message
}

def updateChildren() {
	def devices = state.devices
	devices.each {
		def child = getChildDevice(it.key)
		if (child) {
			if (it.value.ip != null || it.value.ip != "" || it.value.ip != "CLOUD") {
				child.updateDataValue("deviceIP", it.value.ip)
				child.updateDataValue("devicePort", it.value.port.toString())
				def logData = [deviceIP: it.value.ip,port: it.value.port]
				logDebug("updateChildDeviceData: [${it.value.alias}: ${logData}]")
			}
		}
	}
}

def syncBulbPresets(bulbPresets) {
	logDebug("syncBulbPresets")
	def devices = state.devices
	devices.each {
		def type = it.value.type
		if (type == "Kasa Color Bulb" || type == "Kasa Light Strip") {
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

def coordinate(cType, coordData, deviceId, plugNo) {
	logDebug("coordinate: ${cType}, ${coordData}, ${deviceId}, ${plugNo}")
	def plugs = state.devices.findAll{ it.value.deviceId == deviceId }
	plugs.each {
		if (it.value.plugNo != plugNo) {
			def child = getChildDevice(it.value.dni)
			if (child) {
				child.coordUpdate(cType, coordData)
				pauseExecution(200)
			}
		}
	}
}

private sendLanCmd(ip, port, cmdData, action, commsTo = 5) {
	Map data = [ip: ip, port: port, action: action]
	logInfo("sendLanCmd: ${data}")
	def myHubAction = new hubitat.device.HubAction(
		cmdData,
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${ip}:${port}",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 parseWarning: true,
		 timeout: commsTo,
		 callback: action])
	try {
		sendHubCommand(myHubAction)
	} catch (error) {
		logWarn("sendLanCmd: command failed. Error = ${error}")
	}
}

def parseLanData(response) {
	def resp = parseLanMessage(response.description)
	if (resp.type == "LAN_TYPE_UDPCLIENT") {
		def ip = convertHexToIp(resp.ip)
		def port = convertHexToInt(resp.port)
		def clearResp = inputXOR(resp.payload)
		def cmdResp
		try {
			cmdResp = new JsonSlurper().parseText(clearResp).system.get_sysinfo
		} catch (err) {
			if (clearResp.contains("child_num")) {
				clearResp = clearResp.substring(0,clearResp.indexOf("child_num")-2) + "}}}"
			} else if (clearResp.contains("children")) {
				clearResp = clearResp.substring(0,clearResp.indexOf("children")-2) + "}}}"
			} else if (clearResp.contains("preferred")) {
				clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}"
			} else {
				logWarn("parseLanData: [error: msg too long, data: ${clearResp}]")
				return [error: "error", reason: "message to long"]
			}
			cmdResp = new JsonSlurper().parseText(clearResp).system.get_sysinfo
		}
		return [cmdResp: cmdResp, ip: ip, port: port]
	} else {
		return [error: "error", reason: "not LAN_TYPE_UDPCLIENT", respType: resp.type]
	}
}

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

def sendKasaCmd(cmdData) {
	def commandParams = [
		uri: cmdData.uri,
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		body : new groovy.json.JsonBuilder(cmdData.cmdBody).toString()
	]
	def respData
	try {
		httpPostJson(commandParams) {resp ->
			if (resp.status == 200) {
				respData = resp.data
			} else {
				def msg = "sendKasaCmd: <b>HTTP Status not equal to 200.  Protocol error.  "
				msg += "HTTP Protocol Status = ${resp.status}"
				logWarn(msg)
				respData = [error_code: resp.status, msg: "HTTP Protocol Error"]
			}
		}
	} catch (e) {
		def msg = "sendKasaCmd: <b>Error in Cloud Communications.</b> The Kasa Cloud is unreachable."
		msg += "\nAdditional Data: Error = ${e}\n\n"
		logWarn(msg)
		respData = [error_code: 9999, msg: e]
	}
	return respData
}

private String convertHexToIp(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}
private Integer convertHexToInt(hex) { Integer.parseInt(hex,16) }
def debugOff() { app.updateSetting("debugLog", false) }
def logTrace(msg) { log.trace "KasaInt-${appVersion()}: ${msg}" }
def logDebug(msg){
	if(debugLog == true) { log.debug "KasaInt-${appVersion()}: ${msg}" }
}
def logInfo(msg) { log.info "KasaInt-${appVersion()}: ${msg}" }
def logWarn(msg) { log.warn "KasaInt-${appVersion()}: ${msg}" }			  

// ~~~~~ start include (1327) davegut.lib_tpLink_comms ~~~~~
library ( // library marker davegut.lib_tpLink_comms, line 1
	name: "lib_tpLink_comms", // library marker davegut.lib_tpLink_comms, line 2
	namespace: "davegut", // library marker davegut.lib_tpLink_comms, line 3
	author: "Dave Gutheinz", // library marker davegut.lib_tpLink_comms, line 4
	description: "Tapo Communications", // library marker davegut.lib_tpLink_comms, line 5
	category: "utilities", // library marker davegut.lib_tpLink_comms, line 6
	documentationLink: "" // library marker davegut.lib_tpLink_comms, line 7
) // library marker davegut.lib_tpLink_comms, line 8
import org.json.JSONObject // library marker davegut.lib_tpLink_comms, line 9
import groovy.json.JsonOutput // library marker davegut.lib_tpLink_comms, line 10
import groovy.json.JsonBuilder // library marker davegut.lib_tpLink_comms, line 11
import groovy.json.JsonSlurper // library marker davegut.lib_tpLink_comms, line 12

def createMultiCmd(requests) { // library marker davegut.lib_tpLink_comms, line 14
	Map cmdBody = [ // library marker davegut.lib_tpLink_comms, line 15
		method: "multipleRequest", // library marker davegut.lib_tpLink_comms, line 16
		params: [requests: requests]] // library marker davegut.lib_tpLink_comms, line 17
	return cmdBody // library marker davegut.lib_tpLink_comms, line 18
} // library marker davegut.lib_tpLink_comms, line 19

def asyncPassthrough(cmdBody, method, action) { // library marker davegut.lib_tpLink_comms, line 21
	if (devIp == null) { devIp = getDataValue("deviceIP") }	//	used for Kasa Compatibility // library marker davegut.lib_tpLink_comms, line 22
	Map cmdData = [cmdBody: cmdBody, method: method, action: action] // library marker davegut.lib_tpLink_comms, line 23
	state.lastCmd = cmdData // library marker davegut.lib_tpLink_comms, line 24
	logDebug("asyncPassthrough: ${cmdData}") // library marker davegut.lib_tpLink_comms, line 25
	def uri = "http://${getDataValue("deviceIP")}/app?token=${getDataValue("deviceToken")}" // library marker davegut.lib_tpLink_comms, line 26
	Map reqBody = createReqBody(cmdBody) // library marker davegut.lib_tpLink_comms, line 27
	asyncPost(uri, reqBody, action, getDataValue("deviceCookie"), method) // library marker davegut.lib_tpLink_comms, line 28
} // library marker davegut.lib_tpLink_comms, line 29

def syncPassthrough(cmdBody) { // library marker davegut.lib_tpLink_comms, line 31
	if (devIp == null) { devIp = getDataValue("deviceIP") }	//	used for Kasa Compatibility // library marker davegut.lib_tpLink_comms, line 32
	Map logData = [cmdBody: cmdBody] // library marker davegut.lib_tpLink_comms, line 33
	def uri = "http://${getDataValue("deviceIP")}/app?token=${getDataValue("deviceToken")}" // library marker davegut.lib_tpLink_comms, line 34
	Map reqBody = createReqBody(cmdBody) // library marker davegut.lib_tpLink_comms, line 35
	def resp = syncPost(uri, reqBody, getDataValue("deviceCookie")) // library marker davegut.lib_tpLink_comms, line 36
	def cmdResp = "ERROR" // library marker davegut.lib_tpLink_comms, line 37
	if (resp.status == "OK") { // library marker davegut.lib_tpLink_comms, line 38
		try { // library marker davegut.lib_tpLink_comms, line 39
			cmdResp = new JsonSlurper().parseText(decrypt(resp.resp.data.result.response)) // library marker davegut.lib_tpLink_comms, line 40
			logData << [status: "OK"] // library marker davegut.lib_tpLink_comms, line 41
		} catch (err) { // library marker davegut.lib_tpLink_comms, line 42
			logData << [status: "cryptoError", error: "Error decrypting response", data: err] // library marker davegut.lib_tpLink_comms, line 43
		} // library marker davegut.lib_tpLink_comms, line 44
	} else { // library marker davegut.lib_tpLink_comms, line 45
		logData << [status: "postJsonError", postJsonData: resp] // library marker davegut.lib_tpLink_comms, line 46
	} // library marker davegut.lib_tpLink_comms, line 47
	if (logData.status == "OK") { // library marker davegut.lib_tpLink_comms, line 48
		logDebug("syncPassthrough: ${logData}") // library marker davegut.lib_tpLink_comms, line 49
	} else { // library marker davegut.lib_tpLink_comms, line 50
		logWarn("syncPassthrough: ${logData}") // library marker davegut.lib_tpLink_comms, line 51
	} // library marker davegut.lib_tpLink_comms, line 52
	return cmdResp // library marker davegut.lib_tpLink_comms, line 53
} // library marker davegut.lib_tpLink_comms, line 54

def createReqBody(cmdBody) { // library marker davegut.lib_tpLink_comms, line 56
	def cmdStr = JsonOutput.toJson(cmdBody).toString() // library marker davegut.lib_tpLink_comms, line 57
	Map reqBody = [method: "securePassthrough", // library marker davegut.lib_tpLink_comms, line 58
				   params: [request: encrypt(cmdStr)]] // library marker davegut.lib_tpLink_comms, line 59
	return reqBody // library marker davegut.lib_tpLink_comms, line 60
} // library marker davegut.lib_tpLink_comms, line 61

//	===== Sync comms for device update ===== // library marker davegut.lib_tpLink_comms, line 63
def syncPost(uri, reqBody, cookie=null) { // library marker davegut.lib_tpLink_comms, line 64
	def reqParams = [ // library marker davegut.lib_tpLink_comms, line 65
		uri: uri, // library marker davegut.lib_tpLink_comms, line 66
		headers: [ // library marker davegut.lib_tpLink_comms, line 67
			Cookie: cookie // library marker davegut.lib_tpLink_comms, line 68
		], // library marker davegut.lib_tpLink_comms, line 69
		body : new JsonBuilder(reqBody).toString() // library marker davegut.lib_tpLink_comms, line 70
	] // library marker davegut.lib_tpLink_comms, line 71
	logDebug("syncPost: [cmdParams: ${reqParams}]") // library marker davegut.lib_tpLink_comms, line 72
	Map respData = [:] // library marker davegut.lib_tpLink_comms, line 73
	try { // library marker davegut.lib_tpLink_comms, line 74
		httpPostJson(reqParams) {resp -> // library marker davegut.lib_tpLink_comms, line 75
			if (resp.status == 200 && resp.data.error_code == 0) { // library marker davegut.lib_tpLink_comms, line 76
				respData << [status: "OK", resp: resp] // library marker davegut.lib_tpLink_comms, line 77
			} else { // library marker davegut.lib_tpLink_comms, line 78
				respData << [status: "lanDataError", respStatus: resp.status,  // library marker davegut.lib_tpLink_comms, line 79
					errorCode: resp.data.error_code] // library marker davegut.lib_tpLink_comms, line 80
			} // library marker davegut.lib_tpLink_comms, line 81
		} // library marker davegut.lib_tpLink_comms, line 82
	} catch (err) { // library marker davegut.lib_tpLink_comms, line 83
		respData << [status: "HTTP Failed", data: err] // library marker davegut.lib_tpLink_comms, line 84
	} // library marker davegut.lib_tpLink_comms, line 85
	return respData // library marker davegut.lib_tpLink_comms, line 86
} // library marker davegut.lib_tpLink_comms, line 87

def asyncPost(uri, reqBody, parseMethod, cookie=null, reqData=null) { // library marker davegut.lib_tpLink_comms, line 89
	Map logData = [:] // library marker davegut.lib_tpLink_comms, line 90
	def reqParams = [ // library marker davegut.lib_tpLink_comms, line 91
		uri: uri, // library marker davegut.lib_tpLink_comms, line 92
		requestContentType: 'application/json', // library marker davegut.lib_tpLink_comms, line 93
		contentType: 'application/json', // library marker davegut.lib_tpLink_comms, line 94
		headers: [ // library marker davegut.lib_tpLink_comms, line 95
			Cookie: cookie // library marker davegut.lib_tpLink_comms, line 96
		], // library marker davegut.lib_tpLink_comms, line 97
		timeout: 4, // library marker davegut.lib_tpLink_comms, line 98
		body : new groovy.json.JsonBuilder(reqBody).toString() // library marker davegut.lib_tpLink_comms, line 99
	] // library marker davegut.lib_tpLink_comms, line 100
	try { // library marker davegut.lib_tpLink_comms, line 101
		asynchttpPost(parseMethod, reqParams, [data: reqData]) // library marker davegut.lib_tpLink_comms, line 102
		logData << [status: "OK"] // library marker davegut.lib_tpLink_comms, line 103
	} catch (e) { // library marker davegut.lib_tpLink_comms, line 104
		logData << [status: e, reqParams: reqParams] // library marker davegut.lib_tpLink_comms, line 105
	} // library marker davegut.lib_tpLink_comms, line 106
	if (logData.status == "OK") { // library marker davegut.lib_tpLink_comms, line 107
		logDebug("asyncPost: ${logData}") // library marker davegut.lib_tpLink_comms, line 108
	} else { // library marker davegut.lib_tpLink_comms, line 109
		logWarn("asyncPost: ${logData}") // library marker davegut.lib_tpLink_comms, line 110
		handleCommsError() // library marker davegut.lib_tpLink_comms, line 111
	} // library marker davegut.lib_tpLink_comms, line 112
} // library marker davegut.lib_tpLink_comms, line 113

def parseData(resp) { // library marker davegut.lib_tpLink_comms, line 115
	def logData = [:] // library marker davegut.lib_tpLink_comms, line 116
	if (resp.status == 200 && resp.json.error_code == 0) { // library marker davegut.lib_tpLink_comms, line 117
		def cmdResp // library marker davegut.lib_tpLink_comms, line 118
		try { // library marker davegut.lib_tpLink_comms, line 119
			cmdResp = new JsonSlurper().parseText(decrypt(resp.json.result.response)) // library marker davegut.lib_tpLink_comms, line 120
			setCommsError(false) // library marker davegut.lib_tpLink_comms, line 121
		} catch (err) { // library marker davegut.lib_tpLink_comms, line 122
			logData << [status: "cryptoError", error: "Error decrypting response", data: err] // library marker davegut.lib_tpLink_comms, line 123
		} // library marker davegut.lib_tpLink_comms, line 124
		if (cmdResp != null && cmdResp.error_code == 0) { // library marker davegut.lib_tpLink_comms, line 125
			logData << [status: "OK", cmdResp: cmdResp] // library marker davegut.lib_tpLink_comms, line 126
		} else { // library marker davegut.lib_tpLink_comms, line 127
			logData << [status: "deviceDataError", cmdResp: cmdResp] // library marker davegut.lib_tpLink_comms, line 128
		} // library marker davegut.lib_tpLink_comms, line 129
	} else { // library marker davegut.lib_tpLink_comms, line 130
		logData << [status: "lanDataError"] // library marker davegut.lib_tpLink_comms, line 131
	} // library marker davegut.lib_tpLink_comms, line 132
	if (logData.status == "OK") { // library marker davegut.lib_tpLink_comms, line 133
		logDebug("parseData: ${logData}") // library marker davegut.lib_tpLink_comms, line 134
	} else { // library marker davegut.lib_tpLink_comms, line 135
		logWarn("parseData: ${logData}") // library marker davegut.lib_tpLink_comms, line 136
		handleCommsError() // library marker davegut.lib_tpLink_comms, line 137
	} // library marker davegut.lib_tpLink_comms, line 138
	return logData // library marker davegut.lib_tpLink_comms, line 139
} // library marker davegut.lib_tpLink_comms, line 140

def handleCommsError() { // library marker davegut.lib_tpLink_comms, line 142
	Map logData = [:] // library marker davegut.lib_tpLink_comms, line 143
	if (state.lastCommand != "") { // library marker davegut.lib_tpLink_comms, line 144
		def count = state.errorCount + 1 // library marker davegut.lib_tpLink_comms, line 145
		state.errorCount = count // library marker davegut.lib_tpLink_comms, line 146
		def cmdData = new JSONObject(state.lastCmd) // library marker davegut.lib_tpLink_comms, line 147
		def cmdBody = parseJson(cmdData.cmdBody.toString()) // library marker davegut.lib_tpLink_comms, line 148
		logData << [count: count, command: cmdData] // library marker davegut.lib_tpLink_comms, line 149
		switch (count) { // library marker davegut.lib_tpLink_comms, line 150
			case 1: // library marker davegut.lib_tpLink_comms, line 151
				asyncPassthrough(cmdBody, cmdData.method, cmdData.action) // library marker davegut.lib_tpLink_comms, line 152
				logData << [status: "commandRetry"] // library marker davegut.lib_tpLink_comms, line 153
				logDebug("handleCommsError: ${logData}") // library marker davegut.lib_tpLink_comms, line 154
				break // library marker davegut.lib_tpLink_comms, line 155
			case 2: // library marker davegut.lib_tpLink_comms, line 156
				logData << [deviceLogin: deviceLogin()] // library marker davegut.lib_tpLink_comms, line 157
				Map data = [cmdBody: cmdBody, method: cmdData.method, action:cmdData.action] // library marker davegut.lib_tpLink_comms, line 158
				runIn(2, delayedPassThrough, [data:data]) // library marker davegut.lib_tpLink_comms, line 159
				logData << [status: "newLogin and commandRetry"] // library marker davegut.lib_tpLink_comms, line 160
				logWarn("handleCommsError: ${logData}") // library marker davegut.lib_tpLink_comms, line 161
				break // library marker davegut.lib_tpLink_comms, line 162
			case 3: // library marker davegut.lib_tpLink_comms, line 163
				logData << [setCommsError: setCommsError(true), status: "retriesDisabled"] // library marker davegut.lib_tpLink_comms, line 164
				logError("handleCommsError: ${logData}") // library marker davegut.lib_tpLink_comms, line 165
				break // library marker davegut.lib_tpLink_comms, line 166
			default: // library marker davegut.lib_tpLink_comms, line 167
				break // library marker davegut.lib_tpLink_comms, line 168
		} // library marker davegut.lib_tpLink_comms, line 169
	} // library marker davegut.lib_tpLink_comms, line 170
} // library marker davegut.lib_tpLink_comms, line 171

def delayedPassThrough(data) { // library marker davegut.lib_tpLink_comms, line 173
	asyncPassthrough(data.cmdBody, data.method, data.action) // library marker davegut.lib_tpLink_comms, line 174
} // library marker davegut.lib_tpLink_comms, line 175

def setCommsError(status) { // library marker davegut.lib_tpLink_comms, line 177
	if (!status) { // library marker davegut.lib_tpLink_comms, line 178
		updateAttr("commsError", false) // library marker davegut.lib_tpLink_comms, line 179
		state.errorCount = 0 // library marker davegut.lib_tpLink_comms, line 180
	} else { // library marker davegut.lib_tpLink_comms, line 181
		updateAttr("commsError", true) // library marker davegut.lib_tpLink_comms, line 182
		return "commsErrorSet" // library marker davegut.lib_tpLink_comms, line 183
	} // library marker davegut.lib_tpLink_comms, line 184
} // library marker davegut.lib_tpLink_comms, line 185

// ~~~~~ end include (1327) davegut.lib_tpLink_comms ~~~~~

// ~~~~~ start include (1337) davegut.lib_tpLink_security ~~~~~
library ( // library marker davegut.lib_tpLink_security, line 1
	name: "lib_tpLink_security", // library marker davegut.lib_tpLink_security, line 2
	namespace: "davegut", // library marker davegut.lib_tpLink_security, line 3
	author: "Dave Gutheinz", // library marker davegut.lib_tpLink_security, line 4
	description: "tpLink RSA and AES security measures", // library marker davegut.lib_tpLink_security, line 5
	category: "utilities", // library marker davegut.lib_tpLink_security, line 6
	documentationLink: "" // library marker davegut.lib_tpLink_security, line 7
) // library marker davegut.lib_tpLink_security, line 8
import groovy.json.JsonSlurper // library marker davegut.lib_tpLink_security, line 9
import java.security.spec.PKCS8EncodedKeySpec // library marker davegut.lib_tpLink_security, line 10
import javax.crypto.spec.SecretKeySpec // library marker davegut.lib_tpLink_security, line 11
import javax.crypto.spec.IvParameterSpec // library marker davegut.lib_tpLink_security, line 12
import javax.crypto.Cipher // library marker davegut.lib_tpLink_security, line 13
import java.security.KeyFactory // library marker davegut.lib_tpLink_security, line 14

def securityPreferences() { // library marker davegut.lib_tpLink_security, line 16
	input ("aesKey", "password", title: "Storage for the AES Key") // library marker davegut.lib_tpLink_security, line 17
} // library marker davegut.lib_tpLink_security, line 18

//	===== Device Login Core ===== // library marker davegut.lib_tpLink_security, line 20
def handshake(devIp) { // library marker davegut.lib_tpLink_security, line 21
	def rsaKeys = getRsaKeys() // library marker davegut.lib_tpLink_security, line 22
	Map handshakeData = [method: "handshakeData", rsaKeys: rsaKeys.keyNo] // library marker davegut.lib_tpLink_security, line 23
	def pubPem = "-----BEGIN PUBLIC KEY-----\n${rsaKeys.public}-----END PUBLIC KEY-----\n" // library marker davegut.lib_tpLink_security, line 24
	Map cmdBody = [ method: "handshake", params: [ key: pubPem]] // library marker davegut.lib_tpLink_security, line 25
	def uri = "http://${devIp}/app" // library marker davegut.lib_tpLink_security, line 26
	def respData = syncPost(uri, cmdBody) // library marker davegut.lib_tpLink_security, line 27
	if (respData.status == "OK") { // library marker davegut.lib_tpLink_security, line 28
		String deviceKey = respData.resp.data.result.key // library marker davegut.lib_tpLink_security, line 29
		try { // library marker davegut.lib_tpLink_security, line 30
			def cookieHeader = respData.resp.headers["set-cookie"].toString() // library marker davegut.lib_tpLink_security, line 31
			def cookie = cookieHeader.substring(cookieHeader.indexOf(":") +1, cookieHeader.indexOf(";")) // library marker davegut.lib_tpLink_security, line 32
			handshakeData << [cookie: cookie] // library marker davegut.lib_tpLink_security, line 33
		} catch (err) { // library marker davegut.lib_tpLink_security, line 34
			handshakeData << [respStatus: "FAILED", check: "respData.headers", error: err] // library marker davegut.lib_tpLink_security, line 35
		} // library marker davegut.lib_tpLink_security, line 36
		def aesArray = readDeviceKey(deviceKey, rsaKeys.private) // library marker davegut.lib_tpLink_security, line 37
		handshakeData << [aesKey: aesArray] // library marker davegut.lib_tpLink_security, line 38
		if (aesArray == "ERROR") { // library marker davegut.lib_tpLink_security, line 39
			handshakeData << [respStatus: "FAILED", check: "privateKey"] // library marker davegut.lib_tpLink_security, line 40
		} else { // library marker davegut.lib_tpLink_security, line 41
			handshakeData << [respStatus: "OK"] // library marker davegut.lib_tpLink_security, line 42
		} // library marker davegut.lib_tpLink_security, line 43
	} else { // library marker davegut.lib_tpLink_security, line 44
		handshakeData << [respStatus: "FAILED", check: "pubPem. devIp", respData: respData] // library marker davegut.lib_tpLink_security, line 45
	} // library marker davegut.lib_tpLink_security, line 46
	if (handshakeData.respStatus == "OK") { // library marker davegut.lib_tpLink_security, line 47
		logDebug("handshake: ${handshakeData}") // library marker davegut.lib_tpLink_security, line 48
	} else { // library marker davegut.lib_tpLink_security, line 49
		logWarn("handshake: ${handshakeData}") // library marker davegut.lib_tpLink_security, line 50
	} // library marker davegut.lib_tpLink_security, line 51
	return handshakeData // library marker davegut.lib_tpLink_security, line 52
} // library marker davegut.lib_tpLink_security, line 53

def readDeviceKey(deviceKey, privateKey) { // library marker davegut.lib_tpLink_security, line 55
	def response = "ERROR" // library marker davegut.lib_tpLink_security, line 56
	def logData = [:] // library marker davegut.lib_tpLink_security, line 57
	try { // library marker davegut.lib_tpLink_security, line 58
		byte[] privateKeyBytes = privateKey.decodeBase64() // library marker davegut.lib_tpLink_security, line 59
		byte[] deviceKeyBytes = deviceKey.getBytes("UTF-8").decodeBase64() // library marker davegut.lib_tpLink_security, line 60
    	Cipher instance = Cipher.getInstance("RSA/ECB/PKCS1Padding") // library marker davegut.lib_tpLink_security, line 61
		instance.init(2, KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes))) // library marker davegut.lib_tpLink_security, line 62
		byte[] cryptoArray = instance.doFinal(deviceKeyBytes) // library marker davegut.lib_tpLink_security, line 63
		response = cryptoArray // library marker davegut.lib_tpLink_security, line 64
		logData << [cryptoArray: "REDACTED for logs", status: "OK"] // library marker davegut.lib_tpLink_security, line 65
		logDebug("readDeviceKey: ${logData}") // library marker davegut.lib_tpLink_security, line 66
	} catch (err) { // library marker davegut.lib_tpLink_security, line 67
		logData << [status: "READ ERROR", data: err] // library marker davegut.lib_tpLink_security, line 68
		logWarn("readDeviceKey: ${logData}") // library marker davegut.lib_tpLink_security, line 69
	} // library marker davegut.lib_tpLink_security, line 70
	return response // library marker davegut.lib_tpLink_security, line 71
} // library marker davegut.lib_tpLink_security, line 72

def loginDevice(cookie, cryptoArray, credentials, devIp) { // library marker davegut.lib_tpLink_security, line 74
	Map tokenData = [method: "loginDevice"] // library marker davegut.lib_tpLink_security, line 75
	def uri = "http://${devIp}/app" // library marker davegut.lib_tpLink_security, line 76
	Map cmdBody = [method: "login_device", // library marker davegut.lib_tpLink_security, line 77
				   params: [password: credentials.encPassword, // library marker davegut.lib_tpLink_security, line 78
							username: credentials.encUsername], // library marker davegut.lib_tpLink_security, line 79
				   requestTimeMils: 0] // library marker davegut.lib_tpLink_security, line 80
	def cmdStr = JsonOutput.toJson(cmdBody).toString() // library marker davegut.lib_tpLink_security, line 81
	Map reqBody = [method: "securePassthrough", params: [request: encrypt(cmdStr, cryptoArray)]] // library marker davegut.lib_tpLink_security, line 82
	def respData = syncPost(uri, reqBody, cookie) // library marker davegut.lib_tpLink_security, line 83
	if (respData.status == "OK") { // library marker davegut.lib_tpLink_security, line 84
		if (respData.resp.data.error_code == 0) { // library marker davegut.lib_tpLink_security, line 85
			try { // library marker davegut.lib_tpLink_security, line 86
				def cmdResp = decrypt(respData.resp.data.result.response, cryptoArray) // library marker davegut.lib_tpLink_security, line 87
				cmdResp = new JsonSlurper().parseText(cmdResp) // library marker davegut.lib_tpLink_security, line 88
				if (cmdResp.error_code == 0) { // library marker davegut.lib_tpLink_security, line 89
					tokenData << [respStatus: "OK", token: cmdResp.result.token] // library marker davegut.lib_tpLink_security, line 90
				} else { // library marker davegut.lib_tpLink_security, line 91
					tokenData << [respStatus: "Error from device",  // library marker davegut.lib_tpLink_security, line 92
								  check: "cryptoArray, credentials", data: cmdResp] // library marker davegut.lib_tpLink_security, line 93
				} // library marker davegut.lib_tpLink_security, line 94
			} catch (err) { // library marker davegut.lib_tpLink_security, line 95
				tokenData << [respStatus: "Error parsing", error: err] // library marker davegut.lib_tpLink_security, line 96
			} // library marker davegut.lib_tpLink_security, line 97
		} else { // library marker davegut.lib_tpLink_security, line 98
			tokenData << [respStatus: "Error in respData.data", data: respData.data] // library marker davegut.lib_tpLink_security, line 99
		} // library marker davegut.lib_tpLink_security, line 100
	} else { // library marker davegut.lib_tpLink_security, line 101
		tokenData << [respStatus: "Error in respData", data: respData] // library marker davegut.lib_tpLink_security, line 102
	} // library marker davegut.lib_tpLink_security, line 103
	if (tokenData.respStatus == "OK") { // library marker davegut.lib_tpLink_security, line 104
		logDebug("handshake: ${tokenData}") // library marker davegut.lib_tpLink_security, line 105
	} else { // library marker davegut.lib_tpLink_security, line 106
		logWarn("handshake: ${tokenData}") // library marker davegut.lib_tpLink_security, line 107
	} // library marker davegut.lib_tpLink_security, line 108
	return tokenData // library marker davegut.lib_tpLink_security, line 109
} // library marker davegut.lib_tpLink_security, line 110

//	===== AES Methods ===== // library marker davegut.lib_tpLink_security, line 112
//def encrypt(plainText, keyData) { // library marker davegut.lib_tpLink_security, line 113
def encrypt(plainText, keyData = null) { // library marker davegut.lib_tpLink_security, line 114
	if (keyData == null) { // library marker davegut.lib_tpLink_security, line 115
		keyData = new JsonSlurper().parseText(aesKey) // library marker davegut.lib_tpLink_security, line 116
	} // library marker davegut.lib_tpLink_security, line 117
	byte[] keyenc = keyData[0..15] // library marker davegut.lib_tpLink_security, line 118
	byte[] ivenc = keyData[16..31] // library marker davegut.lib_tpLink_security, line 119

	def cipher = Cipher.getInstance("AES/CBC/PKCS5Padding") // library marker davegut.lib_tpLink_security, line 121
	SecretKeySpec key = new SecretKeySpec(keyenc, "AES") // library marker davegut.lib_tpLink_security, line 122
	IvParameterSpec iv = new IvParameterSpec(ivenc) // library marker davegut.lib_tpLink_security, line 123
	cipher.init(Cipher.ENCRYPT_MODE, key, iv) // library marker davegut.lib_tpLink_security, line 124
	String result = cipher.doFinal(plainText.getBytes("UTF-8")).encodeBase64().toString() // library marker davegut.lib_tpLink_security, line 125
	return result.replace("\r\n","") // library marker davegut.lib_tpLink_security, line 126
} // library marker davegut.lib_tpLink_security, line 127

def decrypt(cypherText, keyData = null) { // library marker davegut.lib_tpLink_security, line 129
	if (keyData == null) { // library marker davegut.lib_tpLink_security, line 130
		keyData = new JsonSlurper().parseText(aesKey) // library marker davegut.lib_tpLink_security, line 131
	} // library marker davegut.lib_tpLink_security, line 132
	byte[] keyenc = keyData[0..15] // library marker davegut.lib_tpLink_security, line 133
	byte[] ivenc = keyData[16..31] // library marker davegut.lib_tpLink_security, line 134

    byte[] decodedBytes = cypherText.decodeBase64() // library marker davegut.lib_tpLink_security, line 136
    def cipher = Cipher.getInstance("AES/CBC/PKCS5Padding") // library marker davegut.lib_tpLink_security, line 137
    SecretKeySpec key = new SecretKeySpec(keyenc, "AES") // library marker davegut.lib_tpLink_security, line 138
    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(ivenc)) // library marker davegut.lib_tpLink_security, line 139
	String result = new String(cipher.doFinal(decodedBytes), "UTF-8") // library marker davegut.lib_tpLink_security, line 140
	return result // library marker davegut.lib_tpLink_security, line 141
} // library marker davegut.lib_tpLink_security, line 142

//	===== RSA Key Methods ===== // library marker davegut.lib_tpLink_security, line 144
def getRsaKeys() { // library marker davegut.lib_tpLink_security, line 145
	def keyNo = Math.round(5 * Math.random()).toInteger() // library marker davegut.lib_tpLink_security, line 146
	def keyData = keyData() // library marker davegut.lib_tpLink_security, line 147
	def RSAKeys = keyData.find { it.keyNo == keyNo } // library marker davegut.lib_tpLink_security, line 148
	return RSAKeys // library marker davegut.lib_tpLink_security, line 149
} // library marker davegut.lib_tpLink_security, line 150

def keyData() { // library marker davegut.lib_tpLink_security, line 152
/*	User Note.  You can update these keys at you will using the site: // library marker davegut.lib_tpLink_security, line 153
		https://www.devglan.com/online-tools/rsa-encryption-decryption // library marker davegut.lib_tpLink_security, line 154
	with an RSA Key Size: 1024 bit // library marker davegut.lib_tpLink_security, line 155
	This is at your risk.*/ // library marker davegut.lib_tpLink_security, line 156
	return [ // library marker davegut.lib_tpLink_security, line 157
		[ // library marker davegut.lib_tpLink_security, line 158
			keyNo: 0, // library marker davegut.lib_tpLink_security, line 159
			public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDGr/mHBK8aqx7UAS+g+TuAvE3J2DdwsqRn9MmAkjPGNon1ZlwM6nLQHfJHebdohyVqkNWaCECGXnftnlC8CM2c/RujvCrStRA0lVD+jixO9QJ9PcYTa07Z1FuEze7Q5OIa6pEoPxomrjxzVlUWLDXt901qCdn3/zRZpBdpXzVZtQIDAQAB", // library marker davegut.lib_tpLink_security, line 160
			private: "MIICeAIBADANBgkqhkiG9w0BAQEFAASCAmIwggJeAgEAAoGBAMav+YcErxqrHtQBL6D5O4C8TcnYN3CypGf0yYCSM8Y2ifVmXAzqctAd8kd5t2iHJWqQ1ZoIQIZed+2eULwIzZz9G6O8KtK1EDSVUP6OLE71An09xhNrTtnUW4TN7tDk4hrqkSg/GiauPHNWVRYsNe33TWoJ2ff/NFmkF2lfNVm1AgMBAAECgYEAocxCHmKBGe2KAEkq+SKdAxvVGO77TsobOhDMWug0Q1C8jduaUGZHsxT/7JbA9d1AagSh/XqE2Sdq8FUBF+7vSFzozBHyGkrX1iKURpQFEQM2j9JgUCucEavnxvCqDYpscyNRAgqz9jdh+BjEMcKAG7o68bOw41ZC+JyYR41xSe0CQQD1os71NcZiMVqYcBud6fTYFHZz3HBNcbzOk+RpIHyi8aF3zIqPKIAh2pO4s7vJgrMZTc2wkIe0ZnUrm0oaC//jAkEAzxIPW1mWd3+KE3gpgyX0cFkZsDmlIbWojUIbyz8NgeUglr+BczARG4ITrTV4fxkGwNI4EZxBT8vXDSIXJ8NDhwJBAIiKndx0rfg7Uw7VkqRvPqk2hrnU2aBTDw8N6rP9WQsCoi0DyCnX65Hl/KN5VXOocYIpW6NAVA8VvSAmTES6Ut0CQQCX20jD13mPfUsHaDIZafZPhiheoofFpvFLVtYHQeBoCF7T7vHCRdfl8oj3l6UcoH/hXMmdsJf9KyI1EXElyf91AkAvLfmAS2UvUnhX4qyFioitjxwWawSnf+CewN8LDbH7m5JVXJEh3hqp+aLHg1EaW4wJtkoKLCF+DeVIgbSvOLJw" // library marker davegut.lib_tpLink_security, line 161
		],[ // library marker davegut.lib_tpLink_security, line 162
			keyNo: 1, // library marker davegut.lib_tpLink_security, line 163
			public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCshy+qBKbJNefcyJUZ/3i+3KyLji6XaWEWvebUCC2r9/0jE6hc89AufO41a13E3gJ2es732vaxwZ1BZKLy468NnL+tg6vlQXaPkDcdunQwjxbTLNL/yzDZs9HRju2lJnupcksdJWBZmjtztMWQkzBrQVeSKzSTrKYK0s24EEXmtQIDAQAB", // library marker davegut.lib_tpLink_security, line 164
			private: "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAKyHL6oEpsk159zIlRn/eL7crIuOLpdpYRa95tQILav3/SMTqFzz0C587jVrXcTeAnZ6zvfa9rHBnUFkovLjrw2cv62Dq+VBdo+QNx26dDCPFtMs0v/LMNmz0dGO7aUme6lySx0lYFmaO3O0xZCTMGtBV5IrNJOspgrSzbgQRea1AgMBAAECgYBSeiX9H1AkbJK1Z2ZwEUNF6vTJmmUHmScC2jHZNzeuOFVZSXJ5TU0+jBbMjtE65e9DeJ4suw6oF6j3tAZ6GwJ5tHoIy+qHRV6AjA8GEXjhSwwVCyP8jXYZ7UZyHzjLQAK+L0PvwJY1lAtns/Xmk5GH+zpNnhEmKSZAw23f7wpj2QJBANVPQGYT7TsMTDEEl2jq/ZgOX5Djf2VnKpPZYZGsUmg1hMwcpN/4XQ7XOaclR5TO/CJBJl3UCUEVjdrR1zdD8g8CQQDPDoa5Y5UfhLz4Ja2/gs2UKwO4fkTqqR6Ad8fQlaUZ55HINHWFd8FeERBFgNJzszrzd9BBJ7NnZM5nf2OPqU77AkBLuQuScSZ5HL97czbQvwLxVMDmLWyPMdVykOvLC9JhPgZ7cvuwqnlWiF7mEBzeHbBx9JDLJDd4zE8ETBPLgapPAkAHhCR52FaSdVQSwfNjr1DdHw6chODlj8wOp8p2FOiQXyqYlObrOGSpkH8BtuJs1sW+DsxdgR5vE2a2tRYdIe0/AkEAoQ5MzLcETQrmabdVCyB9pQAiHe4yY9e1w7cimsLJOrH7LMM0hqvBqFOIbSPrZyTp7Ie8awn4nTKoZQtvBfwzHw==" // library marker davegut.lib_tpLink_security, line 165
		],[ // library marker davegut.lib_tpLink_security, line 166
			keyNo: 2, // library marker davegut.lib_tpLink_security, line 167
			public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCBeqRy4zAOs63Sc5yc0DtlFXG1stmdD6sEfUiGjlsy0S8aS8X+Qcjcu5AK3uBBrkVNIa8djXht1bd+pUof5/txzWIMJw9SNtNYqzSdeO7cCtRLzuQnQWP7Am64OBvYkXn2sUqoaqDE50LbSQWbuvZw0Vi9QihfBYGQdlrqjCPUsQIDAQAB", // library marker davegut.lib_tpLink_security, line 168
			private: "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAIF6pHLjMA6zrdJznJzQO2UVcbWy2Z0PqwR9SIaOWzLRLxpLxf5ByNy7kAre4EGuRU0hrx2NeG3Vt36lSh/n+3HNYgwnD1I201irNJ147twK1EvO5CdBY/sCbrg4G9iRefaxSqhqoMTnQttJBZu69nDRWL1CKF8FgZB2WuqMI9SxAgMBAAECgYBBi2wkHI3/Y0Xi+1OUrnTivvBJIri2oW/ZXfKQ6w+PsgU+Mo2QII0l8G0Ck8DCfw3l9d9H/o2wTDgPjGzxqeXHAbxET1dS0QBTjR1zLZlFyfAs7WO8tDKmHVroUgqRkJgoQNQlBSe1E3e7pTgSKElzLuALkRS6p1jhzT2wu9U04QJBAOFr/G36PbQ6NmDYtVyEEr3vWn46JHeZISdJOsordR7Wzbt6xk6/zUDHq0OGM9rYrpBy7PNrbc0JuQrhfbIyaHMCQQCTCvETjXCMkwyUrQT6TpxVzKEVRf1rCitnNQCh1TLnDKcCEAnqZT2RRS3yNXTWFoJrtuEHMGmwUrtog9+ZJBlLAkEA2qxdkPY621XJIIO404mPgM7rMx4F+DsE7U5diHdFw2fO5brBGu13GAtZuUQ7k2W1WY0TDUO+nTN8XPDHdZDuvwJABu7TIwreLaKZS0FFJNAkCt+VEL22Dx/xn/Idz4OP3Nj53t0Guqh/WKQcYHkowxdYmt+KiJ49vXSJJYpiNoQ/NQJAM1HCl8hBznLZLQlxrCTdMvUimG3kJmA0bUNVncgUBq7ptqjk7lp5iNrle5aml99foYnzZeEUW6jrCC7Lj9tg+w==" // library marker davegut.lib_tpLink_security, line 169
		],[ // library marker davegut.lib_tpLink_security, line 170
			keyNo: 3, // library marker davegut.lib_tpLink_security, line 171
			public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCFYaoMvv5kBxUUbp4PQyd7RoZlPompsupXP2La0qGGxacF98/88W4KNUqLbF4X5BPqxoEA+VeZy75qqyfuYbGQ4fxT6usE/LnzW8zDY/PjhVBht8FBRyAUsoYAt3Ip6sDyjd9YzRzUL1Q/OxCgxz5CNETYxcNr7zfMshBHDmZXMQIDAQAB", // library marker davegut.lib_tpLink_security, line 172
			private: "MIICdQIBADANBgkqhkiG9w0BAQEFAASCAl8wggJbAgEAAoGBAIVhqgy+/mQHFRRung9DJ3tGhmU+iamy6lc/YtrSoYbFpwX3z/zxbgo1SotsXhfkE+rGgQD5V5nLvmqrJ+5hsZDh/FPq6wT8ufNbzMNj8+OFUGG3wUFHIBSyhgC3cinqwPKN31jNHNQvVD87EKDHPkI0RNjFw2vvN8yyEEcOZlcxAgMBAAECgYA3NxjoMeCpk+z8ClbQRqJ/e9CC9QKUB4bPG2RW5b8MRaJA7DdjpKZC/5CeavwAs+Ay3n3k41OKTTfEfJoJKtQQZnCrqnZfq9IVZI26xfYo0cgSYbi8wCie6nqIBdu9k54nqhePPshi22VcFuOh97xxPvY7kiUaRbbKqxn9PFwrYQJBAMsO3uOnYSJxN/FuxksKLqhtNei2GUC/0l7uIE8rbRdtN3QOpcC5suj7id03/IMn2Ks+Vsrmi0lV4VV/c8xyo9UCQQCoKDlObjbYeYYdW7/NvI6cEntgHygENi7b6WFk+dbRhJQgrFH8Z/Idj9a2E3BkfLCTUM1Z/Z3e7D0iqPDKBn/tAkBAHI3bKvnMOhsDq4oIH0rj+rdOplAK1YXCW0TwOjHTd7ROfGFxHDCUxvacVhTwBCCw0JnuriPEH81phTg2kOuRAkAEPR9UrsqLImUTEGEBWqNto7mgbqifko4T1QozdWjI10K0oCNg7W3Y+Os8o7jNj6cTz5GdlxsHp4TS/tczAH7xAkBY6KPIlF1FfiyJAnBC8+jJr2h4TSPQD7sbJJmYw7mvR+f1T4tsWY0aGux69hVm8BoaLStBVPdkaENBMdP+a07u" // library marker davegut.lib_tpLink_security, line 173
		],[ // library marker davegut.lib_tpLink_security, line 174
			keyNo: 4, // library marker davegut.lib_tpLink_security, line 175
			public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQClF0yuCpo3r1ZpYlGcyI5wy5nnvZdOZmxqz5U2rklt2b8+9uWhmsGdpbTv5+qJXlZmvUKbpoaPxpJluBFDJH2GSpq3I0whh0gNq9Arzpp/TDYaZLb6iIqDMF6wm8yjGOtcSkB7qLQWkXpEN9T2NsEzlfTc+GTKc07QXHnzxoLmwQIDAQAB", // library marker davegut.lib_tpLink_security, line 176
			private: "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBAKUXTK4KmjevVmliUZzIjnDLmee9l05mbGrPlTauSW3Zvz725aGawZ2ltO/n6oleVma9Qpumho/GkmW4EUMkfYZKmrcjTCGHSA2r0CvOmn9MNhpktvqIioMwXrCbzKMY61xKQHuotBaRekQ31PY2wTOV9Nz4ZMpzTtBcefPGgubBAgMBAAECgYB4wCz+05RvDFk45YfqFCtTRyg//0UvO+0qxsBN6Xad2XlvlWjqJeZd53kLTGcYqJ6rsNyKOmgLu2MS8Wn24TbJmPUAwZU+9cvSPxxQ5k6bwjg1RifieIcbTPC5wHDqVy0/Ur7dt+JVMOHFseR/pElDw471LCdwWSuFHAKuiHsaUQJBANHiPdSU3s1bbJYTLaS1tW0UXo7aqgeXuJgqZ2sKsoIEheEAROJ5rW/f2KrFVtvg0ITSM8mgXNlhNBS5OE4nSD0CQQDJXYJxKvdodeRoj+RGTCZGZanAE1naUzSdfcNWx2IMnYUD/3/2eB7ZIyQPBG5fWjc3bGOJKI+gy/14bCwXU7zVAkAdnsE9HBlpf+qOL3y0jxRgpYxGuuNeGPJrPyjDOYpBwSOnwmL2V1e7vyqTxy/f7hVfeU7nuKMB5q7z8cPZe7+9AkEAl7A6aDe+wlE069OhWZdZqeRBmLC7Gi1d0FoBwahW4zvyDM32vltEmbvQGQP0hR33xGeBH7yPXcjtOz75g+UPtQJBAL4gknJ/p+yQm9RJB0oq/g+HriErpIMHwrhNoRY1aOBMJVl4ari1Ch2RQNL9KQW7yrFDv7XiP3z5NwNDKsp/QeU=" // library marker davegut.lib_tpLink_security, line 177
		],[ // library marker davegut.lib_tpLink_security, line 178
			keyNo: 5, // library marker davegut.lib_tpLink_security, line 179
			public: "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQChN8Xc+gsSuhcLVM1W1E+e1o+celvKlOmuV6sJEkJecknKFujx9+T4xvyapzyePpTBn0lA9EYbaF7UDYBsDgqSwgt0El3gV+49O56nt1ELbLUJtkYEQPK+6Pu8665UG17leCiaMiFQyoZhD80PXhpjehqDu2900uU/4DzKZ/eywwIDAQAB", // library marker davegut.lib_tpLink_security, line 180
			private: "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAKE3xdz6CxK6FwtUzVbUT57Wj5x6W8qU6a5XqwkSQl5yScoW6PH35PjG/JqnPJ4+lMGfSUD0RhtoXtQNgGwOCpLCC3QSXeBX7j07nqe3UQtstQm2RgRA8r7o+7zrrlQbXuV4KJoyIVDKhmEPzQ9eGmN6GoO7b3TS5T/gPMpn97LDAgMBAAECgYAy+uQCwL8HqPjoiGR2dKTI4aiAHuEv6m8KxoY7VB7QputWkHARNAaf9KykawXsNHXt1GThuV0CBbsW6z4U7UvCJEZEpv7qJiGX8UWgEs1ISatqXmiIMVosIJJvoFw/rAoScadCYyicskjwDFBVNU53EAUD3WzwEq+dRYDn52lqQQJBAMu30FEReAHTAKE/hvjAeBUyWjg7E4/lnYvb/i9Wuc+MTH0q3JxFGGMb3n6APT9+kbGE0rinM/GEXtpny+5y3asCQQDKl7eNq0NdIEBGAdKerX4O+nVDZ7PXz1kQ2ca0r1tXtY/9sBDDoKHP2fQAH/xlOLIhLaH1rabSEJYNUM0ohHdJAkBYZqhwNWtlJ0ITtvSEB0lUsWfzFLe1bseCBHH16uVwygn7GtlmupkNkO9o548seWkRpnimhnAE8xMSJY6aJ6BHAkEAuSFLKrqGJGOEWHTx8u63cxiMb7wkK+HekfdwDUzxO4U+v6RUrW/sbfPNdQ/FpPnaTVdV2RuGhg+CD0j3MT9bgQJARH86hfxp1bkyc7f1iJQT8sofdqqVz5grCV5XeGY77BNmCvTOGLfL5pOJdgALuOoP4t3e94nRYdlW6LqIVugRBQ==" // library marker davegut.lib_tpLink_security, line 181
		] // library marker davegut.lib_tpLink_security, line 182
	] // library marker davegut.lib_tpLink_security, line 183
} // library marker davegut.lib_tpLink_security, line 184

// ~~~~~ end include (1337) davegut.lib_tpLink_security ~~~~~

// ~~~~~ start include (1370) davegut.lib_tpLink_discovery ~~~~~
library ( // library marker davegut.lib_tpLink_discovery, line 1
	name: "lib_tpLink_discovery", // library marker davegut.lib_tpLink_discovery, line 2
	namespace: "davegut", // library marker davegut.lib_tpLink_discovery, line 3
	author: "Dave Gutheinz", // library marker davegut.lib_tpLink_discovery, line 4
	description: "Common tpLink Smart Discovery Methods", // library marker davegut.lib_tpLink_discovery, line 5
	category: "utilities", // library marker davegut.lib_tpLink_discovery, line 6
	documentationLink: "" // library marker davegut.lib_tpLink_discovery, line 7
) // library marker davegut.lib_tpLink_discovery, line 8

def getSmartLanData(response) { // library marker davegut.lib_tpLink_discovery, line 10
	logDebug("getSmartLanData: responses returned from devices") // library marker davegut.lib_tpLink_discovery, line 11
	def devIp // library marker davegut.lib_tpLink_discovery, line 12
	List ipList = [] // library marker davegut.lib_tpLink_discovery, line 13
	def respData // library marker davegut.lib_tpLink_discovery, line 14
	if (response instanceof Map) { // library marker davegut.lib_tpLink_discovery, line 15
		devIp = getDeviceIp(response) // library marker davegut.lib_tpLink_discovery, line 16
		if (devIp != "INVALID") { // library marker davegut.lib_tpLink_discovery, line 17
			ipList << devIp // library marker davegut.lib_tpLink_discovery, line 18
		} // library marker davegut.lib_tpLink_discovery, line 19
	} else { // library marker davegut.lib_tpLink_discovery, line 20
		response.each { // library marker davegut.lib_tpLink_discovery, line 21
			devIp = getDeviceIp(it) // library marker davegut.lib_tpLink_discovery, line 22
			if (devIp != "INVALID") { // library marker davegut.lib_tpLink_discovery, line 23
				ipList << devIp // library marker davegut.lib_tpLink_discovery, line 24
			} // library marker davegut.lib_tpLink_discovery, line 25
			pauseExecution(100) // library marker davegut.lib_tpLink_discovery, line 26
		} // library marker davegut.lib_tpLink_discovery, line 27
	} // library marker davegut.lib_tpLink_discovery, line 28
	getAllSmartDeviceData(ipList) // library marker davegut.lib_tpLink_discovery, line 29
} // library marker davegut.lib_tpLink_discovery, line 30

def getDeviceIp(response) { // library marker davegut.lib_tpLink_discovery, line 32
	log.trace response // library marker davegut.lib_tpLink_discovery, line 33
	def brand = "KASA" // library marker davegut.lib_tpLink_discovery, line 34
	if (appName() == "tapo_device_install") { brand = "TAPO" } // library marker davegut.lib_tpLink_discovery, line 35
	def devIp = "INVALID" // library marker davegut.lib_tpLink_discovery, line 36
	try { // library marker davegut.lib_tpLink_discovery, line 37
		def respData = parseLanMessage(response.description) // library marker davegut.lib_tpLink_discovery, line 38
		if (respData.type == "LAN_TYPE_UDPCLIENT") { // library marker davegut.lib_tpLink_discovery, line 39
			byte[] payloadByte = hubitat.helper.HexUtils.hexStringToByteArray(respData.payload.drop(32))  // library marker davegut.lib_tpLink_discovery, line 40
			String payloadString = new String(payloadByte) // library marker davegut.lib_tpLink_discovery, line 41
			Map payload = new JsonSlurper().parseText(payloadString).result // library marker davegut.lib_tpLink_discovery, line 42
			Map payloadData = [type: payload.device_type, model: payload.device_model,  // library marker davegut.lib_tpLink_discovery, line 43
							   mac: payload.mac, ip: payload.ip] // library marker davegut.lib_tpLink_discovery, line 44
			if (payload.device_type.contains(brand)) { // library marker davegut.lib_tpLink_discovery, line 45
				devIp = payload.ip // library marker davegut.lib_tpLink_discovery, line 46
				logInfo("getDeviceIp: [TAPOdevice: ${payloadData}]") // library marker davegut.lib_tpLink_discovery, line 47
			} else { // library marker davegut.lib_tpLink_discovery, line 48
				logInfo("getDeviceIp: [KASAdevice: ${payloadData}]") // library marker davegut.lib_tpLink_discovery, line 49
			} // library marker davegut.lib_tpLink_discovery, line 50
		} // library marker davegut.lib_tpLink_discovery, line 51
	} catch (err) { // library marker davegut.lib_tpLink_discovery, line 52
		logWarn("getDevIp: [status: ERROR, respData: ${resData}, error: ${err}]") // library marker davegut.lib_tpLink_discovery, line 53
	} // library marker davegut.lib_tpLink_discovery, line 54
	return devIp // library marker davegut.lib_tpLink_discovery, line 55
} // library marker davegut.lib_tpLink_discovery, line 56

def getAllSmartDeviceData(List ipList) { // library marker davegut.lib_tpLink_discovery, line 58
	Map logData = [:] // library marker davegut.lib_tpLink_discovery, line 59
	ipList.each { devIp -> // library marker davegut.lib_tpLink_discovery, line 60
		Map devData = [:] // library marker davegut.lib_tpLink_discovery, line 61
		def cmdResp = getSmartDeviceData([method: "get_device_info"], devIp) // library marker davegut.lib_tpLink_discovery, line 62
		if (cmdResp == "ERROR") { // library marker davegut.lib_tpLink_discovery, line 63
			devData << [status: "ERROR", data: "Failure in getSmartDeviceData"] // library marker davegut.lib_tpLink_discovery, line 64
		} else { // library marker davegut.lib_tpLink_discovery, line 65
			if (cmdResp.result.type.contains("SMART")) { // library marker davegut.lib_tpLink_discovery, line 66
				devData << [status: "OK"] // library marker davegut.lib_tpLink_discovery, line 67
				parseSmartDeviceData(cmdResp.result) // library marker davegut.lib_tpLink_discovery, line 68
			} else { // library marker davegut.lib_tpLink_discovery, line 69
				if (cmdResp.result.type) { // library marker davegut.lib_tpLink_discovery, line 70
					devData << [status: "OK", devType: cmdResp.result.type, devIp: cmdResp.result.ip] // library marker davegut.lib_tpLink_discovery, line 71
				} else { // library marker davegut.lib_tpLink_discovery, line 72
					devData << [status: "ERROR", data: cmdResp] // library marker davegut.lib_tpLink_discovery, line 73
				} // library marker davegut.lib_tpLink_discovery, line 74
			} // library marker davegut.lib_tpLink_discovery, line 75
		} // library marker davegut.lib_tpLink_discovery, line 76
		logData << [devIp: devData] // library marker davegut.lib_tpLink_discovery, line 77
		pauseExecution(200) // library marker davegut.lib_tpLink_discovery, line 78
	} // library marker davegut.lib_tpLink_discovery, line 79
	if (!logData.toString().contains("ERROR")) { // library marker davegut.lib_tpLink_discovery, line 80
		logDebug("getSmartDeviceData: ${logData}") // library marker davegut.lib_tpLink_discovery, line 81
	} else { // library marker davegut.lib_tpLink_discovery, line 82
		logWarn("getSmartDeviceData: ${logData}") // library marker davegut.lib_tpLink_discovery, line 83
	} // library marker davegut.lib_tpLink_discovery, line 84
	pauseExecution(5000) // library marker davegut.lib_tpLink_discovery, line 85
	state.findingDevices = "done" // library marker davegut.lib_tpLink_discovery, line 86
} // library marker davegut.lib_tpLink_discovery, line 87

def deviceLogin(devIp) { // library marker davegut.lib_tpLink_discovery, line 89
	Map logData = [:] // library marker davegut.lib_tpLink_discovery, line 90
	def handshakeData = handshake(devIp) // library marker davegut.lib_tpLink_discovery, line 91
	if (handshakeData.respStatus == "OK") { // library marker davegut.lib_tpLink_discovery, line 92
		Map credentials = [encUsername: encUsername, encPassword: encPassword] // library marker davegut.lib_tpLink_discovery, line 93
		def tokenData = loginDevice(handshakeData.cookie, handshakeData.aesKey,  // library marker davegut.lib_tpLink_discovery, line 94
									credentials, devIp) // library marker davegut.lib_tpLink_discovery, line 95
		if (tokenData.respStatus == "OK") { // library marker davegut.lib_tpLink_discovery, line 96
			logData << [rsaKeys: handshakeData.rsaKeys, // library marker davegut.lib_tpLink_discovery, line 97
						cookie: handshakeData.cookie, // library marker davegut.lib_tpLink_discovery, line 98
						aesKey: handshakeData.aesKey, // library marker davegut.lib_tpLink_discovery, line 99
						token: tokenData.token] // library marker davegut.lib_tpLink_discovery, line 100
		} else { // library marker davegut.lib_tpLink_discovery, line 101
			logData << [tokenData: tokenData] // library marker davegut.lib_tpLink_discovery, line 102
		} // library marker davegut.lib_tpLink_discovery, line 103
	} else { // library marker davegut.lib_tpLink_discovery, line 104
		logData << [handshakeData: handshakeData] // library marker davegut.lib_tpLink_discovery, line 105
	} // library marker davegut.lib_tpLink_discovery, line 106
	return logData // library marker davegut.lib_tpLink_discovery, line 107
} // library marker davegut.lib_tpLink_discovery, line 108

def getSmartDeviceData(cmdBody, devIp) { // library marker davegut.lib_tpLink_discovery, line 110
	def cmdResp = "ERROR" // library marker davegut.lib_tpLink_discovery, line 111
	def loginData = deviceLogin(devIp) // library marker davegut.lib_tpLink_discovery, line 112
	Map logData = [cmdBody: cmdBody, devIp: devIp, token: loginData.token, aeskey: loginData.aesKey, cookie: loginData.cookie] // library marker davegut.lib_tpLink_discovery, line 113
	if (loginData.token == null) { // library marker davegut.lib_tpLink_discovery, line 114
		logData << [respStatus: "FAILED", reason: "Check Credentials"] // library marker davegut.lib_tpLink_discovery, line 115
	} else { // library marker davegut.lib_tpLink_discovery, line 116
		def uri = "http://${devIp}/app?token=${loginData.token}" // library marker davegut.lib_tpLink_discovery, line 117
		cmdBody = JsonOutput.toJson(cmdBody).toString() // library marker davegut.lib_tpLink_discovery, line 118
		Map reqBody = [method: "securePassthrough", // library marker davegut.lib_tpLink_discovery, line 119
					   params: [request: encrypt(cmdBody, loginData.aesKey)]] // library marker davegut.lib_tpLink_discovery, line 120
		def respData = syncPost(uri, reqBody, loginData.cookie) // library marker davegut.lib_tpLink_discovery, line 121
		if (respData.status == "OK") { // library marker davegut.lib_tpLink_discovery, line 122
			logData << [respStatus: "OK"] // library marker davegut.lib_tpLink_discovery, line 123
			respData = respData.resp.data.result.response // library marker davegut.lib_tpLink_discovery, line 124
			cmdResp = new JsonSlurper().parseText(decrypt(respData, loginData.aesKey)) // library marker davegut.lib_tpLink_discovery, line 125
		} else { // library marker davegut.lib_tpLink_discovery, line 126
			logData << respData // library marker davegut.lib_tpLink_discovery, line 127
		} // library marker davegut.lib_tpLink_discovery, line 128
	} // library marker davegut.lib_tpLink_discovery, line 129
	if (logData.respStatus == "OK") { // library marker davegut.lib_tpLink_discovery, line 130
		logDebug("getSmartDeviceData: ${logData}") // library marker davegut.lib_tpLink_discovery, line 131
	} else { // library marker davegut.lib_tpLink_discovery, line 132
		logWarn("getSmartDeviceData: ${logData}") // library marker davegut.lib_tpLink_discovery, line 133
	} // library marker davegut.lib_tpLink_discovery, line 134
	return cmdResp // library marker davegut.lib_tpLink_discovery, line 135
} // library marker davegut.lib_tpLink_discovery, line 136

// ~~~~~ end include (1370) davegut.lib_tpLink_discovery ~~~~~
