/*	Kasa Local Integration
Copyright Dave Gutheinz
License Information:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
Special Cloud Version to handle updated firmware where port 9999 is removed.
=======================================================================================================*/
def appVersion() { return "1.0.0" }
import groovy.json.JsonSlurper

definition(
	name: "Kasa Cloud Integration",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Application to install TP-Link bulbs, plugs, and switches via the Cloud.",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	singleInstance: true,
	documentationLink: "https://github.com/DaveGut/Hubitat-TP-Link-Integration/wiki",
	importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaCloudDevices/Application/cloud-KasaIntegrationApp.groovy"
)

preferences {
	page(name: "startPage")
	page(name: "mainPage")
	page(name: "kasaAuthenticationPage")
	page(name: "addDevicesPage")
	page(name: "removeDevicesPage")
}

def installed() { initialize() }

def updated() { initialize() }

def initialize() {
	logInfo("initialize")
	unsubscribe()
	unschedule()
	schedule("0 30 2 ? * WED", getToken)
//	if (selectedAddDevices) { addDevices() }
//    else if (selectedUpdateDevices) { updatePreferences() }
//    else { flowDirector() }
}
def uninstalled() {
    getAllChildDevices().each { 
        deleteChildDevice(it.deviceNetworkId)
    }
}

//	Application start-up methods
def startPage() {
	logInfo("starting Kasa Cloud Integration")
	app?.removeSetting("selectedAddDevices")
	app?.removeSetting("selectedRemoveDevices")
	if (!debugLog) {
		app.updateSetting("debugLog", false)
		app.updateSetting("licenseAcknowledged", false)
	}
	
	if (licenseAcknowledged == true) { return mainPage() }
	return dynamicPage(name:"mainPage",
					   title:"<b>Kasa Cloud Integration, Version ${appVersion()}</b>",
					   uninstall: false,
					   install: false) {
		section() {
			paragraph "<textarea rows=15 cols=50 readonly='true'>${license()}</textarea>"
			input "licenseAcknowledged", "bool", 
				title: "ACKNOWLEDGE READING LICENSE", 
				submitOnChange: true,
				defaultValue: true
			href "mainPage",
				title: "<bGo to the Next Page</b>",
				description: "Goes to the Application Main Page."
		}
	}
}

//	Main page
def mainPage() {
	logDebug("mainPage")
	if (selectedAddDevices) { addDevices() }
	if (selectedRemoveDevices) { removeDevices() }
	if (debugLog == true) { runIn(1800, debugOff) }
	return dynamicPage(name:"mainPage",
					   title:"<b>Kasa Cloud Integration, Version ${appVersion()}</b>",
					   uninstall: true,
					   install: true) {
		section() {
			href "kasaAuthenticationPage",
				title: "<b>Kasa Login and Token Update</b>",
				description: "Go to Kasa Login Update"
			href "addDevicesPage",
				title: "<b>Install Kasa Devices</b>",
				description: "Installs newly detected Kasa Device."
			href "removeDevicesPage",
				title: "<b>Remove Kasa Devices</b>",
				description: "Removes user selected Kasa Device."
			input "debugLog", "bool", 
				title: "Enable debug logging for 30 minutes", 
				submitOnChange: true,
				defaultValue: false
		}
	}
}

def kasaAuthenticationPage() {
	logDebug("kasaAuthenticationPage")
	return dynamicPage (name: "kasaAuthenticationPage", 
    					title: "Initial Kasa Login Page",
                        install: true) {
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
				getToken()
				pauseExecution(1000)
				href "mainPage", title: "Get or Update Kasa Token", description: "Tap to Get Kasa Token"
            }
			paragraph "Select  '<'  at upper left corner to exit."
		}
	}
}

def getToken() {
	logInfo("getToken ${userName}")
    state.flowType = null
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
			sendEvent(name: "TokenUpdate", value: "tokenUpdate Successful.")
			if (state.currentError != null) {
				state.currentError = null
			}
		} else if (resp.status != 200) {
			state.currentError = resp.statusLine
			sendEvent(name: "currentError", value: resp.data)
			log.error "Error in getToken: ${state.currentError}"
			sendEvent(name: "TokenUpdate", value: state.currentError)
		} else if (resp.data.error_code != 0) {
			state.currentError = resp.data
			sendEvent(name: "currentError", value: resp.data)
			log.error "Error in getToken: ${state.currentError}"
			sendEvent(name: "TokenUpdate", value: state.currentError)
		}
	}
}

//	Add Devices
def addDevicesPage() {
	logInfo("addDevicesPage")
	def devices = state.devices
	def uninstalledDevices = [:]
	kasaGetDevices()
	logDebug("addDevicesPage: getDevicesResponse = ${getDevicesResponse}")
	devices.each {
		def isChild = getChildDevice(it.value.dni)
		if (!isChild) {
			uninstalledDevices["${it.value.dni}"] = "${it.value.alias}     ${it.value.model}"
		}
	}
	def pageInstructions = "<b>Before Installing New Devices</b>\n"
	pageInstructions += "Ensure the appropriate drivers are installed.\n"
	pageInstructions += "Note:  It may take several minutes for devices to appear in list."
	return dynamicPage(name:"addDevicesPage",
		title:"<b>Add Kasa Cloud Devices to Hubitat</b>",
		refreshInterval: 30,
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

def kasaGetDevices() {
	logInfo("kasaGetDevices ${state.TpLinkToken}")
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
			state.currentError = null
		} else if (resp.status != 200) {
			state.currentError = resp.statusLine
			sendEvent(name: "currentError", value: resp.data)
			return "Error in getDeviceData: ${state.currentError}"
 		} else if (resp.data.error_code != 0) {
			state.currentError = resp.data
			sendEvent(name: "currentError", value: resp.data)
			return "Error in getDeviceData: ${state.currentError}"
		}
	}
	state.devices = [:]
	currentDevices.each {
		def deviceModel = it.deviceModel.substring(0,5)
        def plugId = ""
 		if (deviceModel == "HS107" || deviceModel == "HS300" || deviceModel == "KP200" || deviceModel == "KP400") {
			def totalPlugs = 2
			if (deviceModel == "HS300") {
				totalPlugs = 6
			}
			for (int i = 0; i < totalPlugs; i++) {
				def deviceNetworkId = "${it.deviceMac}_0${i}"
				plugId = "${it.deviceId}0${i}"
				def sysinfo = sendDeviceCmd(it.appServerUrl, it.deviceId, '{"system" :{"get_sysinfo" :{}}}')
				def children = sysinfo.system.get_sysinfo.children
				def alias
				children.each {
					if (it.id == plugId) {
						alias = it.alias
					}
				}
                updateDevices(deviceNetworkId, alias, deviceModel, plugId, it.deviceId, it.appServerUrl)
			}
		} else {
            updateDevices(it.deviceMac, it.alias, deviceModel, plugId, it.deviceId, it.appServerUrl)
		}
	}
}

def updateDevices(deviceNetworkId, alias, deviceModel, plugId, deviceId, appServerUrl) {
	def devices = state.devices
	def device = [:]
	device["dni"] = deviceNetworkId
	device["alias"] = alias
	device["model"] = deviceModel
	device["plugId"] = plugId
	device["deviceId"] = deviceId
	device["appServerUrl"] = appServerUrl
	device["deviceType"] = getType(deviceModel)
	devices << ["${deviceNetworkId}" : device]
	def child = getChildDevice(deviceNetworkId)
	if (child) {
		def devVer = child.devVer()
 		child.updateDataValue("appVersion", appVersion())
    	child.updateDataValue("appServerUrl", appServerUrl)
    }
	log.info "Device ${alias} added to devices array"
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
			return "Color Bulb"
			break
		default :
			logWarn("getType: Model not on current list.  Contact developer.")
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
			deviceData = [
				"deviceId" : device.value.deviceId,
				"plugId" : device.value.plugId,
				"appServerUrl" : device.value.appServerUrl,
				"appVersion" : appVersion()
			]
			try {
				addChildDevice(
					"davegut",
					"Kasa Cloud ${device.value.deviceType}",
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

//	===== Device Command Method ===========
def sendDeviceCmd(appServerUrl, deviceId, command) {
	def cmdResponse = ""
	def cmdBody = [
		method: "passthrough",
		params: [
			deviceId: deviceId,
			requestData: "${command}"
		]
	]
	def sendCmdParams = [
		uri: "${appServerUrl}/?token=${state.TpLinkToken}",
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		body : new groovy.json.JsonBuilder(cmdBody).toString()
	]
	httpPostJson(sendCmdParams) {resp ->
		if (resp.status == 200 && resp.data.error_code == 0) {
			def jsonSlurper = new groovy.json.JsonSlurper()
			cmdResponse = jsonSlurper.parseText(resp.data.result.responseData)
			if (state.errorCount != 0) {
				state.errorCount = 0
			}
			if (state.currentError != null) {
				state.currentError = null
				sendEvent(name: "currentError", value: null)
				log.debug "state.errorCount = ${state.errorCount}"
			}
		} else if (resp.status != 200) {
			state.currentError = resp.statusLine
			cmdResponse = "ERROR: ${resp.statusLine}"
			sendEvent(name: "currentError", value: resp.data)
			log.error "Error in sendDeviceCmd: ${state.currentError}"
		} else if (resp.data.error_code != 0) {
			state.currentError = resp.data
			cmdResponse = "ERROR: ${resp.data.msg}"
			sendEvent(name: "currentError", value: resp.data)
			log.error "Error in sendDeviceCmd: ${state.currentError}"
		}
	}
	return cmdResponse
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