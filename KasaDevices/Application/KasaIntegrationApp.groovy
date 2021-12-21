/*	Kasa Local Integration

		Copyright Dave Gutheinz

License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md

Changes since version 6:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Version%206%20Change%20Log.md

===== Version 6.4.2 =====
1.	Added Cloud, Lan and Device Control Setup section
	a.	Kasa Login and Token Update
	b.	Select Inteface to Kasa Cloud or Device Control
	c.	User-defined search segment or multiple search segment.
	d.	Use-defined Host address range.
2.	Device database no longer zeorized on each installation.
3.	Added Application Utilities section
	a.	List All Kasa Devices with IP Address.
	b.	List All Kasa Devices by Name
	c.	Ping IP Tool.  Allow pinging an individual IP to see if the device is seen by HE.
	d.	Reset the Device Database.  Zeroizes the DB then rediscovers devices.
===================================================================================================*/
def appVersion() { return "6.4.3" }
def rel() { return "3" }
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
	documentationLink: "https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/README.md"
/*	Link for Driver Import (Copy and paste into import function above)
"https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/Application/KasaIntegrationApp.groovy"
*/
)

preferences {
	page(name: "startPage")
	page(name: "kasaAuthenticationPage")
	page(name: "addDevicesPage")
	page(name: "removeDevicesPage")
	page(name: "listDevicesByIp")
	page(name: "listDevicesByName")
	page(name: "startGetToken")
	page(name: "commsTest")
	page(name: "commsTestDisplay")
	page(name: "dbReset")
}

def installed() { initialize() }

def updated() { initialize() }

def initialize() {
	logInfo("initialize")
	unschedule()
	if (useKasaCloud == true) {
		schedule("0 30 2 ? * WED", schedGetToken)
	}
	app?.updateSetting("appSetup", [type:"bool", value: false])
	app?.updateSetting("utilities", [type:"bool", value: false])
	app?.updateSetting("debugLog", [type:"bool", value: false])
	if (showInstructions == true || showInstructions == false) {
		app.removeSetting("showInstructions")
	}
}

def uninstalled() {
    getAllChildDevices().each { 
        deleteChildDevice(it.deviceNetworkId)
    }
}

//	===== Page Methods =====
def startPage() {
	logInfo("starting Kasa Integration")
	if (selectedRemoveDevices) { removeDevices() }
	if (selectedAddDevices) { addDevices() }
	if (!debugLog) { app.updateSetting("debugLog", false) }
	if (!state.devices) { state.devices = [:] }

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

	def range = "1, 254"
	if (!hostLimits) {
		app?.updateSetting("hostLimits", [type:"string", value: range])
	} else {
		range = hostLimits
	}
	def rangeArray = range.split('\\,')
	state.hostArray = [rangeArray[0].toInteger(), rangeArray[1].toInteger()]

	def useCloud = false
	if (useKasaCloud == null) {
		app?.updateSetting("useKasaCloud", false)
	} else {
		useCloud = useKasaCloud
	}

	def searchMethod = "LAN"
	if (!discMethod) {
		app?.updateSetting("discMethod", "LAN")
	} else {
		searchMethod = discMethod
	}

	return dynamicPage(name:"startPage",
					   title:"<b>Kasa Local Hubitat Integration, Version ${appVersion()}-rel${rel()}</b>" +
					   		 "\n(Instructions available using <b>?</b> at upper right corner.)",
					   uninstall: true,
					   install: true) {
		section() {
			if (appSetup) {
				href "kasaAuthenticationPage",
					title: "<b>Kasa Login and Token Update</b>",
					description: "Click to enter credentials and get token"
				paragraph "After running Kasa Login and Token Update, refresh this page."

				paragraph "Kasa Cloud URL = ${kasaCloudUrl}. If null," +
					"\ndiscover devices with CLOUD to create a Kasa Cloud URL."
				input "useKasaCloud", "bool",
					title: "<b>Interface to Kasa Cloud for device control.</b>",
					submitOnChange: true
				paragraph ""

				def discOptions = [LAN: "LAN. Local wifi only.",
								   BOTH: "LAN and CLOUD."]
				input "discMethod", "enum",
					title: "<b>Select from Discovery Options</b>  (LAN, CLOUD, BOTH)",
					required: false,
					multiple: false,
					options: discOptions,
					submitOnChange: true,
					defaultValue: ["LAN"]
				paragraph ""

				input "lanSegment", "string",
					title: "<b>Lan Segments</b> (ex: 192.168.50, 192,168.01)",
					submitOnChange: true

				input "hostLimits", "string",
					title: "<b>Host Address Range</b> (ex: 5, 100)",
					submitOnChange: true
			}
			paragraph "<b>Current Configuration:  token</b> = ${kasaToken}, <b>Cloud Device Control</b> = ${useCloud}, " +
				"<b>discMethod</b> = ${searchMethod}, <b>LanSegments</b> = ${segments}, " +
				"<b>hostRange</b> = ${range}"
			input "appSetup", "bool",
				title: "<b>Modify Configuration</b>",
				submitOnChange: true,
				defaultalue: false
			paragraph "\n"

			href "addDevicesPage",
				title: "<b>Install Kasa Devices</b>",
				description: "Also updates all device IP addresses."

			href "removeDevicesPage",
				title: "<b>Remove Kasa Devices</b>",
				description: "Click to select and delete devices"
			paragraph "\n"

			input "utilities", "bool",
				   title: "<b>Application Utilities</b>",
				   submitOnChange: true,
				   defaultalue: false
	
			if (utilities == true) {
				href "listDevicesByIp",
					title: "<b>List All Kasa Devices with IP Address.</b>",
					description: "Click to get List"

				href "listDevicesByName",
					title: "<b>List All Kasa Devices by Name</b>",
					description: "Click to get List"

				href "commsTest", title: "<b>IP Comms Test Tool</b>",
					description: "Click to go to IP Comms Test"
				href "dbReset", title: "<b>Reset the Device Database</b>",
					description: "Click to reset the device database"
			}
			paragraph ""
			input "debugLog", "bool",
				   title: "<b>Enable debug logging for 30 minutes</b>",
				   submitOnChange: true,
				   defaultValue: false
		}
	}
}

def kasaAuthenticationPage() {
	logInfo("kasaAuthenticationPage")
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

def addDevicesPage() { 
	logInfo("addDevicesPage")
	def action = findDevices()
	
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
	pageInstructions += "2.\t<b>Assign Static IP Addresses.</b>\n"
	return dynamicPage(name:"addDevicesPage",
					   title: "Add Kasa Devices to Hubitat, Version ${appVersion()}-R${rel()}",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			paragraph pageInstructions
			input ("selectedAddDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Devices to add (${uninstalledDevices.size() ?: 0} available).",
				   description: "Use the dropdown to select devices.  Then select 'Done'.",
				   options: uninstalledDevices)
		}
	}
}

def getDevices () { return state.devices }

def removeDevicesPage() {
	logInfo("removeDevicesPage")
	def devices = state.devices
	def installedDevices = [:]
	devices.each {
		def installed = false
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

def listDevicesByIp() {
	logInfo("listDevicesByIp")
	def theList = ""
	def devices = state.devices
	if (devices == null) {
		theList += "<b>No devices in the device database.</b>"
	} else {
		theList += "<b>Total Kasa devices: ${devices.size() ?: 0}</b>\n"
		def deviceList = []
		theList +=  "<b>DeviceIp:  Alias,  DeviceType,  Installed</b>\n"
		devices.each{
			def installed = ""
			def isChild = getChildDevice(it.key)
			if (isChild) {
				installed = ", Installed"
			}
			def type = it.value.type.replace("Kasa ","")
			deviceList << "${it.value.ip}:  ${it.value.alias},  ${type}${installed}"
		}
		deviceList.sort()
		deviceList.each {
			theList += "${it}\n"
		}
	}
	return dynamicPage(name:"listDevicesByIp",
					   title: "List Kasa Devices with IP Address, Version ${appVersion()}-R${rel()}",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			paragraph theList
		}
	}
}

def listDevicesByName() { 
	logInfo("listDevicesByName")
	state.listDevices = [:]
	def devices = state.devices
	def theList = ""
	if (devices == null) {
		theList += "<b>No devices in the device database.</b>"
	} else {
		theList += "<b>Total Kasa devices: ${devices.size() ?: 0}</b>\n"
		theList += "<b>Alias:  DeviceType,  DeviceIP,  Installed</b>\n"
		def deviceList = []
		devices.each{
			def installed = ""
			def isChild = getChildDevice(it.key)
			if (isChild) {
				installed = ", Installed"
			}
			def type = it.value.type.replace("Kasa ","")
			deviceList << "${it.value.alias},  ${type},  ${it.value.ip}${installed}"
		}
		deviceList.sort()
		deviceList.each {
			theList += "${it}\n"
		}
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

def commsTest() {
	logInfo("commsTest")
	return dynamicPage(name:"commsTest",
					   title: "IP Communications Test, Version ${appVersion()}-R${rel()}",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			input "testIp", "string",
				title: "<b>IP Address to Test</b>",
				required: true,
				submitOnChange: true
			if (testIp && testIp != null) {
				href "commsTestDisplay", title: "<b>Test IP Address</b>",
					description: "Click to Test IP Comms."
			}
		}
	}
}

def commsTestDisplay() {
	def pingData = sendPing(testIp, 3)
	def success = 100 * (1 - pingData.packetLoss.toInteger()/3)
	def text = "<b>Ping Test</b>\n"
	text += "<b>\t   Time(min):</b>\t${pingData.rttMin}\n"
	text += "<b>\t  Time(max):</b>\t${pingData.rttMax}\n"
	text += "<b>\tSuccess(%):</b>\t${success.toInteger()}%\n\n"
	sendLanCmd(testIp, """{"system":{"get_sysinfo":{}}}""", "commsTestParse")
	pauseExecution(3000)
	text += "<b>Device Command Test:</b>\t${state.commsTest}"
	return dynamicPage(name:"commsTestDisplay",
					   title: "Communications Testing Results for IP = ${testIp}, Version ${appVersion()}-R${rel()}",
					   nextPage: commsTest,
					   install: false) {
		section() {
			paragraph text
		}
	}
//	return startPage()
}
	
def dbReset() {
	logInfo("dbReset")
	state.remove("devices")
	pauseExecution(1000)
	state.devices = [:]
	def action = findDevices()
	return dynamicPage(name:"dbReset",
					   title: "Reset the Kasa Device Database, Version ${appVersion()}-R${rel()}",
					   nextPage: listDevicesByIp,
					   install: false) {
		def notice = "The device database has been reset and devices rediscovered."
	 	section() {
			paragraph notice
		}
	}
}

//	===== Generate the device database =====
def findDevices() {
	def start = state.hostArray.min().toInteger()
	def finish = state.hostArray.max().toInteger() +1
	state.segArray.each {
		def pollSegment = it.trim()
		logInfo("findDevices: Searching for LAN deivces on IP Segment = ${pollSegment}")
		for(int i = start; i < finish; i++) {
			def deviceIP = "${pollSegment}.${i.toString()}"
			sendLanCmd(deviceIP, """{"system":{"get_sysinfo":{}}}""", "parseLanData")
			pauseExecution(200)
		}
	}
	if (discMethod == "BOTH") {
		if (kasaToken != "" || kasaToken != null) {
			logInfo("findDevices: ${cloudGetDevices()}")
		} else {
			logWarn("findDevices: No Kasa Token available.")
		}
	}
	runIn(3,updateChildren)
	return
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

def cloudGetDevices() {
	logInfo("cloudGetDevices ${kasaToken}")
	if (kasaToken == null) {
		logWarn("cloudGetDevices: kasaToken is null.  Run Kasa Login and Token Update")
		return
	}
	def message = ""
	def cmdData = [uri: "https://wap.tplinkcloud.com?token=${kasaToken}", 
				   cmdBody: [method: "getDeviceList"]]
	def respData = sendKasaCmd(cmdData)
	if (respData.error) { return }
	def cloudDevices = respData.deviceList
	def cloudUrl = ""
	cloudDevices.each {
		if (it.deviceType != "IOT.SMARTPLUGSWITCH" && it.deviceType != "IOT.SMARTBULB") {
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
			if (!respData.error) {
				def jsonSlurper = new groovy.json.JsonSlurper()
				cmdResp = jsonSlurper.parseText(respData.responseData)
				parseDeviceData(cmdResp.system.get_sysinfo)
			}
		}
	}
	message += "Device data sent to parse methods."
	if (cloudUrl != "" && cloudUrl != kasaCloudUrl) {
		app?.updateSetting("kasaCloudUrl", cloudUrl)
		message += " kasaCloudUrl uptdated to ${cloudUrl}."
	}
	return message
}

def parseDeviceData(cmdResp, ip = "CLOUD") {
	logDebug("parseDeviceData: ${cmdResp} //  ${ip}")
	def dni
	if (cmdResp.mic_mac) {
		dni = cmdResp.mic_mac.replace(/:/, "")
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
			def existingDev = devices.find { it.key == childDni }
			if (existingDev) {
				if (ip == "CLOUD" || ip == existingDev.value.ip) {
					logInfo("parseDeviceData: ${existingDev.value.alias} already in devices array.")
					return
				}
			}
			def device = createDevice(childDni, ip, type, feature, model, alias, deviceId, plugNo, plugId)
			devices << ["${childDni}" : device]
			logInfo("parseDeviceData: ${type} ${alias} (${ip}) added to devices array.")
		}
	} else {
		def existingDev = devices.find { it.key == dni }
		if (existingDev) {
			if (ip == "CLOUD" || ip == existingDev.value.ip) {
				logInfo("parseDeviceData: ${existingDev.value.alias} already in devices array.")
				return
			}
		}
		def device = createDevice(dni, ip, type, feature, model, alias, deviceId, plugNo, plugId)
		devices << ["${dni}" : device]
		logInfo("parseDeviceData: ${type} ${alias} (${ip}) added to devices array.")
	}
}

def createDevice(dni, ip, type, feature, model, alias, deviceId, plugNo, plugId) {
	logDebug("createDevice: dni = ${dni}")
	def device = [:]
	device["alias"] = alias
	device["dni"] = dni
	device["type"] = type
	device["feature"] = feature
	device["model"] = model
	device["deviceId"] = deviceId
	device["ip"] = ip
	if (plugNo != null) {
		device["plugNo"] = plugNo
		device["plugId"] = plugId
	}
	return device
}

def updateChildren() {
	logDebug("updateChildDeviceData")
	def devices = state.devices
	devices.each {
		def child = getChildDevice(it.key)
		if (child) {
			child.debugOff()
			if (it.value.ip != null || it.value.ip != "") {
				child.updateDataValue("deviceIP", it.value.ip)
			}
			child.updated()
		}
	}
}

//	===== Application Utility Methods =====
def addDevices() {
	logDebug("addDevices: ${selectedAddDevices}")
	def hub = location.hubs[0]
	selectedAddDevices.each { dni ->
		//	See if any installing devices are IP = CLOUD. 
		//	If so, set useKasaCloud to true so device can be controlled.
		def isChild = getChildDevice(dni)
		if (!isChild) {
			def device = state.devices.find { it.value.dni == dni }
			if (device.value.ip == "CLOUD") {
				app?.updateSetting("useKasaCloud", true)
			}
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

def commsTestParse(response) {
	def resp = parseLanMessage(response.description)
	if (resp.type == "LAN_TYPE_UDPCLIENT") {
		state.commsTest = "PASS"
	} else {
		state.commsTest = "FAIL"
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

def getToken() {
	logInfo("getToken ${userName}")
	app?.removeSetting("kasaToken")
	def message = ""
	def hub = location.hubs[0]
	def cmdBody = [
		method: "login",
		params: [
			appType: "Kasa_Android",
			cloudUserName: "${userName}",
			cloudPassword: "${userPassword}",
			terminalUUID: "${hub.id}"]]
	cmdData = [uri: "https://wap.tplinkcloud.com",
			   cmdBody: cmdBody]
	def respData = sendKasaCmd(cmdData)
	if (respData.error) {
		message = respData.error
	} else {
		app?.updateSetting("kasaToken", respData.token)
		message = "Token updated to ${respData.token}"
	}
	return message
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

def schedGetToken() {
	logInfo("schedGetToken: Result = ${getToken()}")
}

//	===== Device Service Methods =====
//	fix IP numbers for communications
def fixConnection(type) {
	logInfo("fixData: Update ${type} data")
	def message = ""
	if (type == "LAN") {
		if (pollEnable == false) {
			message = "unable to update data.  Updated in last 15 minutes."
			return message
		} else {
			def pollSegment
			state.segArray.each {
				pollSegment = it.trim()
				for(int i = 1; i < 255; i++) {
					def deviceIP = "${pollSegment}.${i.toString()}"
					sendLanCmd(deviceIP, """{"system":{"get_sysinfo":{}}}""", "updateDeviceIps")
					pauseExecution(50)
				}
			}
			message = "updated IPs on segments ${state.segArray}"
			runIn(900, pollEnable)
			app?.updateSetting("pollEnabled", [type:"bool", value: false])
		}
	} else if (type == "CLOUD") {
		message = "updated token: ${getToken()}."
	} else { message = "FAILED.  Control type neither LAN nor CLOUD" }
	return message
}

def updateDeviceIps(response) {
	def resp = parseLanMessage(response.description)
	def parser = new JsonSlurper()
	def cmdResp = parser.parseText(inputXOR(resp.payload)).system.get_sysinfo
	def ip = convertHexToIP(resp.ip)
	if (ip == "" || ip == null) { return }
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

//	Coordinate Bulb and Light Strip Preset Data
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

//	Coordinate between multi-plugs
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

//	===== Communications Methods =====
def sendPing(ip, count = 1) {
	hubitat.helper.NetworkUtils.PingData pingData = hubitat.helper.NetworkUtils.ping(ip, count)
	return pingData
}

private sendLanCmd(ip, command, action) {
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${ip}:9999",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 parseWarning: true,
		 timeout: 10,
		 callback: action])
	try {
		sendHubCommand(myHubAction)
	} catch (error) {
		logWarn("sendLanCmd: command to ${ip} failed. Error = ${error}")
	}
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
	httpPostJson(commandParams) {resp ->
		if (resp.status == 200 && resp.data.error_code == 0) {
			respData = resp.data.result
		} else {
			logWarn("Error from the Kasa Cloud: ${resp.data.error_code} = ${resp.data.msg}")
			respData = [error: "${resp.data.error_code} = ${resp.data.msg}"]
		}
	}
	return respData
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

def logTrace(msg){ log.trace "[KasaInt/${appVersion()}-r${rel()}] ${msg}" }

def logDebug(msg){
	if(debugLog == true) { log.debug "[KasaInt/${appVersion()}-r${rel()}] ${msg}" }
}

def logInfo(msg){ log.info "[KasaInt/${appVersion()}-r${rel()}] ${msg}" }

def logWarn(msg) { log.warn "[KasaInt/${appVersion()}-r${rel()}] ${msg}" }

//	end-of-file
