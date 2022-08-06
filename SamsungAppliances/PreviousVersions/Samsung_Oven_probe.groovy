/*	===== HUBITAT Samsung HVAC Using SmartThings ==========================================
		Copyright 2022 Dave Gutheinz
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this  file
except in compliance with the License. You may obtain a copy of the License at:
		http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software distributed under the
License is distributed on an  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied. See the License for the specific language governing permissions
and limitations under the  License.
===== HISTORY =============================================================================
05.27	PASSED final testing for Beta Release.
===== Installation Instructions Link =====
https://github.com/DaveGut/HubitatActive/blob/master/SamsungAppliances/Install_Samsung_Appliance.pdf
===== Update Instructions =====
a.	Use Browser import feature and select the default file.
b.	Save the update to the driver.
c.	Open a logging window and the device's edit page
d.	Run a Save Preferences noting no ERRORS on the log page
e.	If errors, contact developer.
//	===== CHILD DEVICE =====
===========================================================================================*/
def driverVer() { return "B0.1" }

metadata {
	definition (name: "Samsung Oven probe",
				namespace: "davegut",
				author: "David Gutheinz",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Oven_probe.groovy"
			   ){
		capability "Thermostat Setpoint"
		capability "Temperature Measurement"
		command "setProbeSetpoint", ["NUM"]
		attribute "probeStatus", "string"
	}
	preferences {
		input ("debugLog", "bool",  
			   title: "Enable debug logging for 30 minutes", defaultValue: false)
		input ("infoLog", "bool",  
			   title: "Enable description text logging", defaultValue: true)
	}
}

def installed() {
	runIn(1, updated)
}

def updated() {
	def logData = [:]
	if (driverVer() != parent.driverVer()) {
		logWarn("updated: Child driver version does not match parent.")
	}
	if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) {
		updateDataValue("driverVersion", driverVer())
		logData << [driverVersion: driverVer()]
	}
	if (logData != [:]) {
		logInfo("updated: ${logData}")
	}
}

def setProbeSetpoint(setpoint) {
	logDebug("setProbeSetpoint: ${setpoint}")
	def cmdData = [
		component: "main",
		capability: "samsungce.meatProbe",
		command: "setTemperatureSetpoint",
		arguments: [setpoint]]
	def cmdStatus = parent.deviceCommand(cmdData)
	logInfo("setProbeSetpoint: [setpoint: ${setpoint}, status: ${cmdStatus}]")
}

def statusParse(respData) {
	try {
		respData = respData.components.main["samsungce.meatProbe"]
	} catch (err) {
		logWarn("status: [status: ERROR, error: ${err}]")
		return
	}

	def logData = [:]
	def tempUnit = respData.temperature.unit
	def temperature = 0
	def thermostatSetpoint = 0
	def probeStatus = "not present"
	def status = respData.status.value
	if (status == "connected") {probeStatus = "present" }
	if (probeStatus == "present") {
		temperature = respData.temperature.value
		thermostatSetpoint = respData.temperatureSetpoint.value
	}
	
	if (device.currentValue("probeStatus") != probeStatus) {
		sendEvent(name: "probeStatus", value: probeStatus)
		logData << [probeStatus:probeStatus]
	}
	
	if (device.currentValue("temperature") != temperature) {
		sendEvent(name: "temperature", value: temperature, unit: tempUnit)
		logData << [temperature: temperature, unit: tempUnit]
	}

	if (device.currentValue("thermostatSetpoint") != thermostatSetpoint) {
		sendEvent(name: "thermostatSetpoint", value: thermostatSetpoint, unit: tempUnit)
		logData << [thermostatSetpoint: thermostatSetpoint, unit: tempUnit]
	}

	if (logData != [:]) {
		logDebug("statusParse: ${logData}")
	}
	runIn(1, listAttributes)
}

//	===== Library Integration =====


// ~~~~~ start include (611) davegut.Logging ~~~~~
library ( // library marker davegut.Logging, line 1
	name: "Logging", // library marker davegut.Logging, line 2
	namespace: "davegut", // library marker davegut.Logging, line 3
	author: "Dave Gutheinz", // library marker davegut.Logging, line 4
	description: "Common Logging Methods", // library marker davegut.Logging, line 5
	category: "utilities", // library marker davegut.Logging, line 6
	documentationLink: "" // library marker davegut.Logging, line 7
) // library marker davegut.Logging, line 8

//	Logging during development // library marker davegut.Logging, line 10
def listAttributes() { // library marker davegut.Logging, line 11
	def attrs = device.getSupportedAttributes() // library marker davegut.Logging, line 12
	def attrList = [:] // library marker davegut.Logging, line 13
	attrs.each { // library marker davegut.Logging, line 14
		def val = device.currentValue("${it}") // library marker davegut.Logging, line 15
		attrList << ["${it}": val] // library marker davegut.Logging, line 16
	} // library marker davegut.Logging, line 17
	logDebug("Attributes: ${attrList}") // library marker davegut.Logging, line 18
} // library marker davegut.Logging, line 19

def logTrace(msg){ // library marker davegut.Logging, line 21
	log.trace "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" // library marker davegut.Logging, line 22
} // library marker davegut.Logging, line 23

def logInfo(msg) {  // library marker davegut.Logging, line 25
	if (infoLog == true) { // library marker davegut.Logging, line 26
		log.info "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" // library marker davegut.Logging, line 27
	} // library marker davegut.Logging, line 28
} // library marker davegut.Logging, line 29

def debugLogOff() { // library marker davegut.Logging, line 31
	device.updateSetting("debugLog", [type:"bool", value: false]) // library marker davegut.Logging, line 32
	logInfo("Debug logging is false.") // library marker davegut.Logging, line 33
} // library marker davegut.Logging, line 34

def logDebug(msg) { // library marker davegut.Logging, line 36
	if (debugLog == true) { // library marker davegut.Logging, line 37
		log.debug "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" // library marker davegut.Logging, line 38
	} // library marker davegut.Logging, line 39
} // library marker davegut.Logging, line 40

def logWarn(msg) { log.warn "${device.displayName} ${getDataValue("driverVersion")}: ${msg}" } // library marker davegut.Logging, line 42

// ~~~~~ end include (611) davegut.Logging ~~~~~
