/*	Kasa Integration Application
	Copyright Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Link to list of changes =====
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Changes.pdf
===== Link to Documentation =====
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Documentation.pdf
===================================================================================================*/
def appVersion() { return "6.7.0" }
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
}

def installed() { 
	updated()
}

def updated() {
	logInfo("updated: Updating device configurations and (if cloud enabled) Kasa Token")
	unschedule()
	app?.updateSetting("appSetup", [type:"bool", value: false])
	app?.updateSetting("utilities", [type:"bool", value: false])
	app?.updateSetting("debugLog", [type:"bool", value: false])
	app?.removeSetting("pingKasaDevices")
	app?.removeSetting("lanSegment")
	app?.removeSetting("devAddresses")
	app?.removeSetting("devPort")
	app?.removeSetting("installHelp")
	app?.removeSetting("missingDevHelp")
	if (userName && userName != "") {
		schedule("0 30 2 ? * MON,WED,SAT", schedGetToken)
	}
	configureEnable()
	state.remove("lanTest")
	state.remove("addedDevices")
	state.remove("failedAdds")
	state.remove("listDevices")
}

def uninstalled() {
    getAllChildDevices().each { 
        deleteChildDevice(it.deviceNetworkId)
    }
}

def initInstance() {
	logDebug("initInstance: Getting external data for the app.")
	if (!debugLog) { app.updateSetting("debugLog", false) }
	if (!state.devices) { state.devices = [:] }
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
					   title:"<b>Kasa Hubitat Integration, Version ${appVersion()}</b>" +
					   		 "\n(Instructions available using <b>?</b> at upper right corner.)",
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

			href "lanAddDevicesPage",
				title: "<b>Scan LAN for Kasa devices and add</b>",
				description: "Primary Method to discover and add devices."
			input "altInstall", "bool",
				   title: "<b>Problems with Install?  Try Manual or Cloud Installation.</b>",
				   submitOnChange: true,
				   defaultalue: false
			if (altInstall) {
				href "manAddDevicesPage",
					title: "<b>Manually enter data then add Kasa devices</b>",
					description: "For use if devices are missed by Scan LAN."
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
			input "debugLog", "bool",
				   title: "<b>Enable debug logging for 30 minutes</b>",
				   submitOnChange: true,
				   defaultValue: false
		}
	}
}

def lanAddDevicesPage() {
	logInfo("lanAddDevicesPage")
	addDevicesPage("LAN")
}

def cloudAddDevicesPage() {
	logInfo("cloudAddDevicesPage")
	return dynamicPage (name: "cloudAddDevicesPage", 
    					title: "Get device data from Kasa Cloud, Version ${appVersion()}",
						nextPage: startPage,
                        install: false) {
		def note = "Instructions: \n\ta.\tIf not already done, select 'Kasa " +
			"Login and Token Update. \n\tb.\tVerify the token is not null. " +
			"\n\tc.\tSelect 'Add Devices to the Device Array'."
		def twoFactor = "<b>Cloud Integration won't work if two-factor authentication " +
			"is enabled in the Kasa phone app.</b>"
		section("Enter Device IP and Port: ") {
			paragraph note
			paragraph twoFactor				
			href "kasaAuthenticationPage",
				title: "<b>Kasa Login and Token Update</b>",
				description: "Select to enter credentials and get token"
			paragraph "<b>Current Kasa Token</b>: = ${kasaToken}" 
			href "cloudAddStart", title: "<b>Add Devices to the Device Array</b>",
				description: "Press to continue"
			href "startPage", title: "<b>Exit without Updating</b>",
				description: "Return to start page without attempting"
		}
	}
}

def cloudAddStart() { addDevicesPage("CLOUD") }

def manAddDevicesPage() {
	logInfo("manAddDevicesPage")
	return dynamicPage (name: "manAddDevicesPage", 
    					title: "Manually add devices by IP, Version ${appVersion()}",
						nextPage: startPage,
                        install: false) {
		def note = "Instructions: \n\ta.\tEnter the segment for you LAN.\n\tab.\t" +
			"Enter the device IP\n\tc.\tEnter the Port.\nThe system will attempt " +
			"to find the devices two times then display a selection menu with all uninstalled " +
			"devices; including, the device not previously discovered.  If this " +
			"fails, check your local wifi configuration."
        section("Enter Device IP and Port: ") {
			def hub = location.hubs[0]
			def hubIpArray = hub.localIP.split('\\.')
			def segment = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")

			paragraph note
			input ("lanSegment", "string",
				   title: "<b>Lan Segment</b> (ex: 192.168.50)",
				   defaultValue: segment,
				   submitOnChange: true)
			input ("devAddresses", "string",
				   title: "<b>Added Devicse Segment Addresses</b> (ex: 21, 22)",
				   required: false,
				   submitOnChange: true)
			input ("devPort", "string",
            		title: "Device Port (default is 9999)",
                    required: true,
				    defaultValue: "9999",
                    submitOnChange: true)
			if (devAddresses) {
				href "manAddStart", title: "<b>Add Devices to the Device Array</b>",
					description: "Press to continue"
			}
			href "startPage", title: "<b>Exit without Updating</b>",
				description: "Return to start page without attempting"
		}
	}
}

def manAddStart() { addDevicesPage("Manual") }

def addDevicesPage(discType) {
	logDebug("addDevicesPage: [scan: ${scan}]")
	if (discType == "LAN") {
		def action = findDevices()
	} else if (discType == "Manual") {
		def action = manualGetDevices()
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
	def reqDrivers = []
	requiredDrivers.each {
		reqDrivers << it.key
	}
	def pageInstructions = "<b>Before Installing New Devices "
	pageInstructions += "Assure the drivers listed below are installed.</b>"
	pageInstructions += "${reqDrivers}"

	return dynamicPage(name:"addDevicesPage",
					   title: "Add Kasa Devices to Hubitat, Version ${appVersion()}",
					   nextPage: addDevStatus,
					   install: false) {
	 	section() {
			paragraph pageInstructions
			input "missingDevHelp", "bool",
				title: "<b>Missing Device Help</b>",
				submitOnChange: true,
				defaultalue: false
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
	if (state.failedAdds == null) {
		failMsg += "Failed Adds: No devices failed to add."
	} else {
		failMsg += "<b>The following devices were not installed:</b>\n"
		state.failedAdds.each{
			failMsg += "\t${it}\n"
		}
		failMsg += "\t<b>Most common failure cause: Driver not installed.</b>"
	}
			
	return dynamicPage(name:"addDeviceStatus",
					   title: "Installation Status, Version ${appVersion()}",
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
			def deviceData = [:]
			deviceData["deviceIP"] = device.value.ip
			deviceData["devicePort"] = device.value.port
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
				state.addedDevices << [label: device.value.alias, ip: device.value.ip]
				logInfo("Installed ${device.value.alias}.")
			} catch (error) {
				state.failedAdds << [label: device.value.alias, driver: device.value.type, ip: device.value.ip]
				def msg = "addDevice: \n<b>Failed to install device.</b> Most likely "
				msg += "could not find driver <b>${device.value.type}</b> in the "
				msg += "Hubitat Drivers Code page.  Check that page."
				msg += "\nAdditional data: Device Data = ${device}.\n\r"
				logWarn(msg)
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
		theListTitle +=  "<b>Alias: [Ip:Port, RSSI, Driver Version, Installed?</b>]\n"
		def deviceList = []
		devices.each{
			def dni = it.key
			def driverVer = "n/a"
			def installed = "No"
			def isChild = getChildDevice(it.key)
			if (isChild) {
				driverVer = isChild.driverVer()
				installed = "Yes"
			}
			deviceList << "<b>${it.value.alias} - ${it.value.model}</b>: [${it.value.ip}:${it.value.port}, ${it.value.rssi}, ${driverVer}, ${installed}]"
		}
		deviceList.sort()
		deviceList.each {
			theList += "${it}\n"
		}
	}
	return dynamicPage(name:"listDevices",
					   title: "List Kasa Devices from Add Devices, Version ${appVersion()}",
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
    					title: "Initial Kasa Login Page, Version ${appVersion()}",
						nextPage: startPage,
                        install: false) {
		def note = "You only need to enter your Kasa credentials and get a token " +
			"if you need to use the cloud integration.  This is unusual.  If you wish " +
			"to not get a token, simply press 'Exit without Credentials' below." +
			"\n\nIf you have already installed and find you need the cloud:" +
			"\na.\tEnter the credentials and get a token" +
			"\nb.\tRun Install Kasa Devices"
        section("Enter Kasa Account Credentials: ") {
			paragraph note
			input ("userName", "email",
            		title: "TP-Link Kasa Email Address", 
                    required: false,
                    submitOnChange: true)
			input ("userPassword", "password",
            		title: "TP-Link Kasa Account Password",
                    required: false,
                    submitOnChange: true)
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
			cloudPassword: "${userPassword}",
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
	logDebug("findDevices: [hostArray: ${state.hostArray}, portArray: ${state.portArray}, pollSegment: ${state.segArray}]")
	state.portArray.each {
		def port = it.trim()
		List deviceIPs = []
		state.segArray.each {
			def pollSegment = it.trim()
			logInfo("findDevices: Searching for LAN deivces on IP Segment = ${pollSegment}, port = ${port}")
            for(int i = start; i < finish; i++) {
				deviceIPs.add("${pollSegment}.${i.toString()}")
			}
			sendLanCmd(deviceIPs.join(','), port, """{"system":{"get_sysinfo":{}}}""", "getLanData", 15)
		}
	}
	def delay = 50 * (finish - start) + 5000
	pauseExecution(delay)
	updateChildren()
	return
}

def manualGetDevices() {
	def addressArray = devAddresses.split('\\,')
	logInfo("manualGetDevices: [segment: ${lanSegment}, addresses: ${addressArray}, port: ${devPort}]")
	addressArray.each {
		def ip = "${lanSegment}.${it.trim()}"
		def ipInstalled = ipExists(ip)
		if (ipInstalled == false) {
			sendLanCmd(ip, devPort, """{"system":{"get_sysinfo":{}}}""", "getLanData", 5)
			pauseExecution(5000)
			ipInstalled = ipExists(ip)
			if (ipInstalled == false) {
				sendLanCmd(ip, devPort, """{"system":{"get_sysinfo":{}}}""", "getLanData", 10)
				pauseExecution(5000)
				ipInstalled = ipExists(ip)
				if (ipInstalled == false) {
					sendLanCmd(ip, devPort, """{"system":{"get_sysinfo":{}}}""", "getLanData", 20)
					pauseExecution(5000)
				}
			}
		} else {
			logWarn("manualGetDevices: Kasa device already assigned to ${ip}")
		}
		ipInstalled = ipExists(ip)
		if (ipInstalled == false) {
			logWarn("manualGetDevices: A Kasa device was not detected at ${ip}")
		}
	}
	return
}

def ipExists(ip) {
	def exists = false
	state.devices.each{
		if (it.value.ip == ip) { exists = true }
	}
	return exists
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
	pauseExecution(5000)
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
		feature = cmdResp.f_list
		type = "CAM NOT SUPPORTED"
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
			
			def existingDev = devices.find{ it.key == childDni}
			if ((existingDev && ip == "CLOUD") ||
			    (existingDev && ip == existingDev.value.ip)) {
				return
			}
			def device = createDevice(childDni, ip, port, rssi, type, feature, model, alias, deviceId, plugNo, plugId)
			devices["${childDni}"] = device
			logInfo("parseDeviceData: ${type} ${alias} (${ip}) added to devices array.")
		}
	} else {
		def existingDev = devices.find{ it.key == dni}
			if ((existingDev && ip == "CLOUD") ||
			    (existingDev && ip == existingDev.value.ip)) {
				return
			}
		def device = createDevice(dni, ip, port, rssi, type, feature, model, alias, deviceId, plugNo, plugId)
		devices["${dni}"] = device
		logInfo("parseDeviceData: ${type} ${alias} (${ip}) added to devices array.")
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
	theListTitle +=  "<b>[Ip:Port: [testResults, RSSI, Alias, Driver Version, Installed?</b>]\n"
	def theList = ""
	deviceList.each {
		theList += "${it}\n"
	}
	return dynamicPage(name:"listDevicesByIp",
					   title: "List Kasa Devices by IP with Lan Test Results, Version ${appVersion()}",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			input "missingDevHelp", "bool",
				title: "<b>Failed Device Help</b>",
				submitOnChange: true,
				defaultalue: false
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
	theListTitle += "<b>Alias: [testResults, RSSI, Ip:Port, Driver Version, Installed?]</b>\n"
	def theList = ""
	deviceList.each {
		theList += "${it}\n"
	}
	return dynamicPage(name:"listDevicesByName",
					   title: "List Kasa Devices by Name with Lan Test Results, Version ${appVersion()}",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			input "missingDevHelp", "bool",
				title: "<b>Failed Device Help</b>",
				submitOnChange: true,
				defaultalue: false
			paragraph theListTitle
			paragraph "<p style='font-size:14px'>${theList}</p>"
		}
	}
}

def getDeviceList(sortType) {
	def test = runLanTest()
	def lanTest = state.lanTest
	def devices = state.devices
	def deviceList = []
	if (devices == null) {
		deviceList << "<b>No Devices in devices.</b>]"
	} else {
		devices.each{
			def dni = it.key
			def result = ["Failed", "n/a"]
			def testResult = lanTest.find { it.key == dni }
			if (testResult) { result = testResult.value }
			def driverVer = "ukn"
			def installed = "No"
			def isChild = getChildDevice(it.key)
			if (isChild) {
				driverVer = isChild.getDataValue("driverVersion")
				installed = "Yes"
			}
			if (sortType == "ip") {
				deviceList << "<b>${it.value.ip}:${it.value.port}</b>: [${result[0]}, ${result[1]}, ${it.value.alias}, ${driverVer}, ${installed}]"
			} else {
				deviceList << "<b>${it.value.alias}</b>: [${result[0]}, ${result[1]}, ${it.value.ip}:${it.value.port}, ${driverVer}, ${installed}]"
			}
		}
	}
	return deviceList
}

def runLanTest() {
	state.lanTest = [:]
	List deviceIPs = []
	def devices = state.devices
	devices.each{
		if (it.value.ip != "CLOUD") {
			if (it.value.plugNo == null || it.value.plugNo == "00") {
				deviceIPs.add(it.value.ip)
			}
		} else {
			state.lanTest << ["${it.key}": ["CLOUD", "n/a"]]
		}
	}
	if (deviceIPs.size() > 0) {
		sendLanCmd(deviceIPs.join(','), "9999", """{"system":{"get_sysinfo":{}}}""", "lanTestParse")
		def delay = 1000 + 5000 + deviceIPs.size() * 50
		pauseExecution(delay)
	}
	return
}

def lanTestParse(response) {
	if (response instanceof Map) {
		def lanData = parseLanData(response)
		if (lanData.error) { return }
		def cmdResp = lanData.cmdResp
		if (cmdResp.system) {
			cmdResp = cmdResp.system
		}
		lanTestResult(cmdResp, lanData.ip)
	} else {
		response.each {
			def lanData = parseLanData(it)
			if (lanData.error) { return }
			def cmdResp = lanData.cmdResp
			if (cmdResp.system) {
				cmdResp = cmdResp.system
			}
			lanTestResult(cmdResp, lanData.ip)
			if (lanData.cmdResp.children) {
				pauseExecution(120)
			} else {
				pauseExecution(40)
			}
		}
	}
}

def lanTestResult(cmdResp, ip) {
	def lanTest = state.lanTest
	def devices = state.devices
	def dni
	try {
		def thisDevice = devices.find { it.value.ip == ip }
		dni = thisDevice.key
	} catch (e) {
		logWarn("lanTestParse: LAN device with ip = ${ip} is not in devices database.")
	}
	if (cmdResp.children) {
		dni = dni.substring(0,12)
		def childPlugs = cmdResp.children
		childPlugs.each {
			def plugNo = it.id.substring(it.id.length() - 2)
			def childDni = "${dni}${plugNo}"
			lanTest << ["${childDni}": ["PASSED", "${cmdResp.rssi}"]]
		}
	} else {
		lanTest << ["${dni}": ["PASSED", "${cmdResp.rssi}"]]
	}
	state.lanTest = lanTest
}

def commsTest() {
	logInfo("commsTest")
	return dynamicPage(name:"commsTest",
					   title: "IP Communications Test, Version ${appVersion()}",
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
					   title: "Ping Testing Result, Version ${appVersion()}",
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
				pauseExecution(700)
			}
		}
	}
}

private sendLanCmd(ip, port, command, action, commsTo = 5) {
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
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
		logWarn("sendLanCmd: command to ${ip}:${port} failed. Error = ${error}")
	}
}

def parseLanData(response) {
	def lanData
	def resp = parseLanMessage(response.description)
	if (resp.type == "LAN_TYPE_UDPCLIENT") {
		def ip = convertHexToIP(resp.ip)
		def port = convertHexToInt(resp.port)
		def clearResp = inputXOR(resp.payload)
		if (clearResp.length() > 1022) {
			clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}"
		}
		def cmdResp = new JsonSlurper().parseText(clearResp).system.get_sysinfo
		lanData = [cmdResp: cmdResp, ip: ip, port: port]
	} else {
		lanData = [error: "error"]
	}
	return lanData
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

private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private Integer convertHexToInt(hex) { Integer.parseInt(hex,16) }

def debugOff() { app.updateSetting("debugLog", false) }

def logTrace(msg) { log.trace "[KasaInt: ${appVersion()}]: ${msg}" }

def logDebug(msg){
	if(debugLog == true) { log.debug "[KasaInt: ${appVersion()}]: ${msg}" }
}

def logInfo(msg) { log.info "[KasaInt: ${appVersion()}]: ${msg}" }

def logWarn(msg) { log.warn "[KasaInt: ${appVersion()}]: ${msg}" }
