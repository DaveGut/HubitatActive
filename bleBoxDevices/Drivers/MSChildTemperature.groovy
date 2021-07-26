/*
===== Blebox Hubitat Integration Driver 2021 Updates
	Copyright 2021, Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file except in compliance with the
License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0.
Unless required by applicable law or agreed to in writing,software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.

DISCLAIMER: The author of this integration is not associated with blebox.  This code uses the blebox
open API documentation for development and is intended for integration into the Hubitat Environment.

===== Hiatory =====
7.30.21	Child driver for multiSensor type temperature.
	a.	Must be used in conjunction with parent "bleBox multiSensor".
	b.	Following type temperature functions:
		1.	Entry of temp-offset for device
		2.	Select C or F as temperature scale (C is default)
		3.	Attributes for temperature, trend, and sensor health.
		4.	Synchronize sensor name between Hubitat and blebox multiSensor.
This is the child driver for the blebox tempSensorPro.
*/
//	===== Definitions, Installation and Updates =====
def driverVer() { return "2.0.0" }
def apiLevel() { return 20210413 }	//	bleBox latest API Level, 7.6.2021

metadata {
	definition (name: "bleBox MSChild temperature",
				namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/bleBoxDevices/Drivers/MSChildTemperature.groovy"
			   ) {
		capability "Temperature Measurement"
		attribute "trend", "string"
		attribute "sensorHealth", "string"
	}
	preferences {

		input ("tOffset", "integer",
			   title: "temperature offset in 10 times degrees C [-120 -> +120]",
			   defaultValue: 0)
		input ("tempScale", "enum", 
			   title: "Temperature Scale",
			   options: ["C", "F"], 
			   defaultValue: "C")
		input ("nameSync", "enum", 
			   title: "Synchronize Names", 
			   defaultValue: "none",
			   options: ["none": "Don't synchronize",
						 "device" : "bleBox device name master", 
						 "hub" : "Hubitat label master"])
		input ("debug", "bool", 
			   title: "Enable debug logging", 
			   defaultValue: true)
		input ("descriptionText", "bool", 
			   title: "Enable description text logging", 
			   defaultValue: true)
	}
}

def deviceApi() { return getDataValue("apiLevel").toInteger() }
def sensorId() { return getDataValue("sensorId").toInteger() }

def installed() {
	runIn(5, updated)
}

def updated() {
	logInfo("Updating...")
	updateDataValue("driverVersion", driverVer())

	//	update data based on preferences
	if (debug) { runIn(1800, debugOff) }
	logInfo("Debug logging is: ${debug}.")
	logInfo("Description text logging is ${descriptionText}.")

	setDevice()
}

def setDevice() {
	logDebug("setDevice: statusLed: ${statusLed}, nameSync = ${nameSync}")
	if (state.tempBaseline != tOffset.toInteger() || nameSync == "hub") {
		def command = "/api/settings/set"
		def cmdText = """{"settings":{"multiSensor":[{"id":${getDataValue("sensorId")},"type":"temperature","settings":{"""
		//	tempOffset
		cmdText = cmdText + """"userTempOffset":${tOffset}"""
		//	Name
		if (nameSync == "hub") {
			cmdText = cmdText + ""","name":"${device.label}" """
		}
		cmdText = cmdText + """}}]}}"""
		parent.sendPostCmd(command, cmdText, "updateDeviceSettings")
	} else {
		parent.sendGetCmd("/api/settings/state", "updateDeviceSettings")
	}
}


def updateDeviceSettings(settingsArrays) {
	def settings = settingsArrays.find { it.id == getDataValue("sensorId").toInteger() }
	settings = settings.settings
	logDebug("updateDeviceSettings: ${settings}")
	device.setLabel(settings.name)
//	updateDataValue("tempOffset", "${settings.userTempOffset}")
	device.updateSetting("tOffset",[type:"number", value: settings.userTempOffset.toInteger()])
	state.tempBaseline = settings.userTempOffset.toInteger()
	def settingsUpdate = ["tempOffset": settings.userTempOffset, "HubitatName": settings.name]
	logInfo("updateDeviceSettings: ${settingsUpdate}")
}

def commandParse(stateArrays) {
	def status = stateArrays.find { it.id == getDataValue("sensorId").toInteger() }
	logDebug("commandParse: ${status}")
	if (!status.value) {
		logWarn("commandParse: sensor not reporting temperature data")
		return
	}
	def temperature = Math.round(status.value.toInteger() / 10) / 10
	if (tempScale == "F") {
		temperature = Math.round((3200 + 9*status.value.toInteger() / 5) / 100)
	}
	def trend
	switch(status.trend) {
		case "1": trend = "even"; break
		case "2": trend = "down"; break
		case "3": trend = "up"; break
		default: trend = "No Data"
	}
	def sensorHealth = "normal"
	if (status.state == "3") {
		sensorHealth = "sensor error"
		logWarn("Sensor Error")
	}
	def statusUpdate = [:]
	if (temperature != device.currentValue("temperature")) {
		sendEvent(name: "temperature", value: temperature, unit: tempScale)
		statusUpdate << ["temperature": temperature]
	}
	if (trend != device.currentValue("trend")) {
		sendEvent(name: "trend", value: trend)
		statusUpdate << ["trend": trend]
	}
	if (sensorHealth != device.currentValue("sensorHealth")) {
		sendEvent(name: "sensorHealth", value: sensorHealth)
		statusUpdate << ["sensorHealth": sensorHealth]
	}
	logInfo("commandParse: ${statusUpdate}")
}

//	===== Utility Methods =====
def logTrace(msg) { log.trace "<b>${device.label} ${driverVer()}</b> ${msg}" }

def logInfo(msg) {
	if (descriptionText == true) { log.info "<b>${device.label} ${driverVer()}</b> ${msg}" }
}

def logDebug(msg){
	if(debug == true) { log.debug "<b>${device.label} ${driverVer()}</b> ${msg}" }
}

def debugOff() {
	device.updateSetting("debug", [type:"bool", value: false])
	logInfo("debugLogOff: Debug logging is off.")
}

def logWarn(msg){ log.warn "<b>${device.label} ${driverVer()}</b> ${msg}" }

//	end-of-file