/*	Samsung Washer with Power Monitor
		Original author: Dave Gutheinz
		Modified by:     Ricardo Nogueira Garcia
License Information:
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Description
This driver is for SmartThings-installed Samsung Washers for import of control
and status of defined functions into Hubitat Environment.

===== Modifications by Ricardo Nogueira Garcia
  v2.0 (2026-06-04):
    - Fixed the energy unit: SmartThings reports Wh, not kWh
    - Added calculated power from SmartThings' own deltaEnergy/deltaTime
    - Automatic cycle start/end detection from machineState
    - Automatic energyCycleKwh per wash cycle
    - Added EnergyMeter and PowerMeter capabilities (e.g. for Home Assistant)
    - Atributos: energyWh, calculatedPower, deltaEnergy, energyCycleKwh,
                 energyStart, energyEnd, energyLastUpdated
===== Version History
  v1.x - Dave Gutheinz (original)
  v2.0 - Ricardo Nogueira Garcia (2026-06-04) — power monitor
==============================================================================*/
def driverVer() { return "2.0" }
def nameSpace() { return "davegut" }

metadata {
	definition (name: "Samsung Washer with Power Monitor",
				namespace: nameSpace(),
				author: "Ricardo Nogueira Garcia (original: David Gutheinz)",
				importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Washer_Power_Monitor.groovy"
			   ){
		capability "Refresh"
		capability "EnergyMeter"    // exposes "energy" (kWh), standard Hubitat attribute
		capability "PowerMeter"     // exposes "power" (W), standard Hubitat attribute

		// ----- Atributos originais -----
		attribute "switch", "string"
		command "start"
		command "pause"
		command "stop"
		attribute "machineState", "string"
		attribute "kidsLock", "string"
		attribute "remoteControlEnabled", "string"
		attribute "completionTime", "string"
		attribute "timeRemaining", "number"
		attribute "waterTemperature", "string"
		attribute "jobState", "string"
		attribute "soilLevel", "string"
		attribute "spinLevel", "string"

		// ----- Atributos de energia -----
		// energy (kWh) — via capability EnergyMeter acima.
		//   Value = API energy_Wh / 1000. Lifetime total since manufacture.
		// power (W)    — via capability PowerMeter acima.
		//   Calculado como deltaEnergy_Wh / deltaTime_h entre dois polls.
		//   The API's native "power" field is always 0 on this model (Samsung limitation).

		attribute "energyWh", "number"          // raw Wh from the API (debug/reference)
		attribute "deltaEnergy", "number"        // Wh used in the start→end period
		attribute "calculatedPower", "number"    // W calculado (deltaWh / deltaHoras)
		attribute "energyStart", "string"        // start of the measurement period
		attribute "energyEnd", "string"          // end of the measurement period
		attribute "energyCycleKwh", "number"     // kWh consumidos no ciclo atual (acum. desde start)
		attribute "energyLastUpdated", "string"

	}
	preferences {
		input ("stApiKey", "string", title: "SmartThings API Key", defaultValue: "")
		if (stApiKey) {
			input ("stDeviceId", "string", title: "SmartThings Device ID", defaultValue: "")
		}
		if (stDeviceId) {
			input ("pollInterval", "enum", title: "Poll Interval (minutes)",
				   options: ["10sec", "20sec", "30sec", "1", "5", "10", "30"], defaultValue: "10")
			input ("infoLog", "bool", title: "Info logging", defaultValue: true)
			input ("debugLog", "bool", title: "Enable debug logging for 30 minutes", defaultValue: false)
			input("installChildren", "bool", title: "Install child devices", defaultValue: false)
		}
	}
}

def installed() {
	state.prevEnergyEnd  = null   // last "end" seen (detects a new SmartThings period)
	state.cycleBaseWh    = null   // Wh baseline for the cycle consumption
}

def updated() {
	def commonStatus = commonUpdate()
	if (commonStatus.status == "FAILED") {
		logWarn("updated: ${commonStatus}")
	} else {
		logInfo("updated: ${commonStatus}")
	}
	if (installChildren) {
		deviceSetup()
		device.updateSetting("installChildren", [type:"bool", value: false])
	}
}

def start() { setMachineState("run") }
def pause() { setMachineState("pause") }
def stop()  { setMachineState("stop") }
def setMachineState(machState) {
	def cmdData = [
		component: "main",
		capability: "dryerOperatingState",
		command: "setMachineState",
		arguments: [machState]]
	def cmdStatus = deviceCommand(cmdData)
	logInfo("setMachineState: [cmd: ${machState}, status: ${cmdStatus}]")
}

// Zera o acumulador de ciclo — chame ao iniciar uma lavagem
// resetCycleEnergy: manual override if needed (normally automatic via trackCycleEnergy)
def resetCycleEnergy() {
	def currentWh = device.currentValue("energyWh")
	if (currentWh != null) {
		state.cycleBaseWh = currentWh.toBigDecimal()
		sendEvent(name: "energyCycleKwh", value: 0, unit: "kWh",
				  descriptionText: "Ciclo zerado manualmente. Base: ${currentWh} Wh")
		logInfo("resetCycleEnergy: baseline manual definido em ${currentWh} Wh")
	} else {
		logWarn("resetCycleEnergy: aguarde o primeiro poll antes de zerar o ciclo")
	}
}

def distResp(resp, data) {
	def respLog = [:]
	if (resp.status == 200) {
		try {
			def respData = new JsonSlurper().parseText(resp.data)
			if (data.reason == "deviceSetup") {
				deviceSetupParse(respData)
			} else {
				def children = getChildDevices()
				children.each { it.statusParse(respData) }
				statusParse(respData)
			}
		} catch (err) {
			respLog << [status: "ERROR", errorMsg: err, respData: resp.data]
		}
	} else {
		respLog << [status: "ERROR", httpCode: resp.status, errorMsg: resp.errorMessage]
	}
	if (respLog != [:]) { logWarn("distResp: ${respLog}") }
}

def deviceSetupParse(respData) {
	def respLog = []
	def compData = respData.components
	def dni = device.getDeviceNetworkId()
	compData.each {
		if (it.key != "main") {
			def childDni = dni + "-${it.key}"
			def isChild = getChildDevice(childDni)
			if (!isChild) {
				def disabledComponents = []
				if (disabledComponents == null) {
					disabledComponents = compData.main["custom.disabledComponents"].disabledComponents.value
				}
				if(!disabledComponents.contains(it.key)) {
					respLog << [component: it.key, status: "Installing"]
					addChild(it.key, childDni)
				}
			}
		} else {
			updateDataValue("component", "main")
		}
	}
	if (respLog != []) { logInfo("deviceSetupParse: ${respLog}") }
}

def addChild(component, childDni) {
	def type = "Samsung Washer flex"
	try {
		addChildDevice(nameSpace(), "${type}", "${childDni}", [
			 "label": "${device.displayName} flex", component: component])
		logInfo("addChild: [status: ADDED, label: ${component}, type: ${type}]")
	} catch (error) {
		logWarn("addChild: [status: FAILED, type: ${type}, dni: ${childDni}, component: ${component}, error: ${error}]")
	}
}

def statusParse(respData) {
	def parseData
	try {
		parseData = respData.components.main
	} catch (error) {
		logWarn("statusParse: [parseData: ${respData}, error: ${error}]")
		return
	}

	// ----- Atributos originais -----
	def onOff = parseData.switch.switch.value
	sendEvent(name: "switch", value: onOff)
	if (device.currentValue("switch") != onOff) {
		if (onOff == "off") { setPollInterval(state.pollInterval) }
		else { runEvery1Minute(poll) }
	}

	if (parseData["samsungce.kidsLock"]) {
		def kidsLock = parseData["samsungce.kidsLock"].lockState.value
		sendEvent(name: "kidsLock", value: kidsLock)
	}

	def machineState = parseData.washerOperatingState.machineState.value
	trackCycleEnergy(machineState)
	sendEvent(name: "machineState", value: machineState)

	def jobState = parseData.washerOperatingState.washerJobState.value
	sendEvent(name: "jobState", value: jobState)

	def remoteControlEnabled = parseData.remoteControlStatus.remoteControlEnabled.value
	sendEvent(name: "remoteControlEnabled", value: remoteControlEnabled)

	def completionTime = parseData.washerOperatingState.completionTime.value
	if (completionTime != null) {
		def timeRemaining = calcTimeRemaining(completionTime)
		sendEvent(name: "completionTime", value: completionTime)
		sendEvent(name: "timeRemaining", value: timeRemaining)
	}

	def waterTemperature = parseData["custom.washerWaterTemperature"].washerWaterTemperature.value
	sendEvent(name: "waterTemperature", value: waterTemperature)

	def soilLevel = parseData["custom.washerSoilLevel"].washerSoilLevel.value
	sendEvent(name: "soilLevel", value: soilLevel)

	def spinLevel = parseData["custom.washerSpinLevel"].washerSpinLevel.value
	sendEvent(name: "spinLevel", value: spinLevel)

	// ----- Energia -----
	energyParse(parseData)

	if (simulate() == true) { runIn(1, listAttributes, [data: true]) }
	else { runIn(1, listAttributes) }
}

// ============================================================
//  trackCycleEnergy — detects cycle start/end automatically
// ============================================================
//  Quando machineState muda para 'run':
//    salva energyWh atual como baseline do ciclo
//  Quando machineState sai de 'run' (pausa, fim, stop):
//    calcula energyCycleKwh = (energyWh_atual - baseline) / 1000
//    e registra como consumo do ciclo
//
def trackCycleEnergy(newMachineState) {
	def prevMachineState = device.currentValue("machineState")
	def currentWh = device.currentValue("energyWh")

	// Cycle start: machineState enters 'run'
	if (newMachineState == "run" && prevMachineState != "run") {
		if (currentWh != null) {
			state.cycleBaseWh = currentWh.toBigDecimal()
			sendEvent(name: "energyCycleKwh", value: 0, unit: "kWh",
					  descriptionText: "Ciclo iniciado. Base: ${currentWh} Wh")
			logInfo("trackCycleEnergy: ciclo iniciado, baseline=${currentWh} Wh")
		}
	}

	// Fim de ciclo: machineState sai de 'run'
	if (prevMachineState == "run" && newMachineState != "run") {
		if (state.cycleBaseWh != null && currentWh != null) {
			def cycleWh  = currentWh.toBigDecimal() - state.cycleBaseWh
			def cycleKwh = (cycleWh / 1000).setScale(3, java.math.RoundingMode.HALF_UP)
			if (cycleKwh >= 0) {
				sendEvent(name: "energyCycleKwh", value: cycleKwh, unit: "kWh",
						  descriptionText: "Cycle finished: ${cycleKwh} kWh (state: ${newMachineState})")
				logInfo("trackCycleEnergy: cycle finished [${prevMachineState}→${newMachineState}], used=${cycleKwh} kWh")
			}
			// Keep cycleBaseWh so a resumed cycle (pause→run) still counts
		}
	}
}

// ============================================================
//  energyParse — reads powerConsumptionReport and calculates power
// ============================================================
//
//  SOBRE AS UNIDADES:
//    SmartThings reports "energy" in WATT-HOURS (Wh), NOT kWh.
//
//  SOBRE O CAMPO "power":
//    Always 0 — Samsung firmware limitation. Instantaneous power
//    is not available through the SmartThings API on this model.
//
//  STRATEGY FOR calculatedPower:
//    SmartThings only updates the "energy" snapshot when it creates a new period
//    (the "end" field changes). Between polls "energy" stays frozen, so
//    calcular ΔWh / Δt_hubitat resulta em 0.
//
//    The right approach is to use SmartThings' own fields —
//      P(W) = deltaEnergy(Wh) / (end - start) em horas
//    This is only calculated when "end" changes (a new period reported by SmartThings).
//    "deltaEnergy" is the Wh used in exactly that start→end interval.
//
def energyParse(parseData) {
	try {
		def powerReport = parseData["powerConsumptionReport"]
		if (powerReport == null) {
			logDebug("energyParse: powerConsumptionReport not found on this device")
			return
		}

		def pc = powerReport?.powerConsumption?.value
		if (pc == null) {
			logDebug("energyParse: powerConsumption null")
			return
		}

		def energyLog = [:]

		// --- energy: acumulado total em Wh → converte para kWh ---
		if (pc.energy != null) {
			def rawWh = pc.energy.toBigDecimal()
			sendEvent(name: "energyWh", value: rawWh, unit: "Wh",
					  descriptionText: "Energia acumulada (bruto): ${rawWh} Wh")
			def kwhVal = (rawWh / 1000).setScale(3, java.math.RoundingMode.HALF_UP)
			sendEvent(name: "energy", value: kwhVal, unit: "kWh",
					  descriptionText: "Energia acumulada: ${kwhVal} kWh")
			energyLog << [energyKwh: kwhVal, rawWh: rawWh]

			// --- Consumo do ciclo atual ---
			if (state.cycleBaseWh != null) {
				def cycleWh  = rawWh - state.cycleBaseWh.toBigDecimal()
				def cycleKwh = (cycleWh / 1000).setScale(3, java.math.RoundingMode.HALF_UP)
				if (cycleKwh >= 0) {
					sendEvent(name: "energyCycleKwh", value: cycleKwh, unit: "kWh",
							  descriptionText: "Consumo do ciclo: ${cycleKwh} kWh")
					energyLog << [cycleKwh: cycleKwh]
				}
			}
		}

		// --- Power calculated from SmartThings' own start/end/deltaEnergy ---
		// SmartThings creates a new period every ~5-15 min while running.
		// Only recalculate when "end" changes, which signals a new period.
		def currentEnd = pc.end
		def prevEnd    = state.prevEnergyEnd

		if (currentEnd != null && currentEnd != prevEnd) {
			// New period available: calculate average power
			if (pc.start != null && pc.deltaEnergy != null) {
				try {
					def startMs = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", pc.start, TimeZone.getTimeZone('UTC')).getTime()
					def endMs   = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", currentEnd, TimeZone.getTimeZone('UTC')).getTime()
					def deltaMs = endMs - startMs
					def deltaHours = deltaMs / 3600000.0
					def deltaWh = pc.deltaEnergy.toBigDecimal()

					energyLog << [newPeriod: true, deltaEnergyWh: deltaWh,
								  periodMin: (deltaMs/60000).setScale(1, java.math.RoundingMode.HALF_UP)]

					if (deltaHours > 0 && deltaWh > 0) {
						def calcPowerW = (deltaWh / deltaHours).setScale(1, java.math.RoundingMode.HALF_UP)
						sendEvent(name: "calculatedPower", value: calcPowerW, unit: "W",
								  descriptionText: "Calculated power (SmartThings period): ${calcPowerW} W")
						sendEvent(name: "power", value: calcPowerW, unit: "W",
								  descriptionText: "Power: ${calcPowerW} W")
						energyLog << [calculatedPowerW: calcPowerW]
					} else if (deltaWh == 0) {
						// Period with no consumption (paused / standby)
						sendEvent(name: "calculatedPower", value: 0, unit: "W",
								  descriptionText: "Calculated power: 0 W (no consumption in period)")
						sendEvent(name: "power", value: 0, unit: "W")
						energyLog << [calculatedPowerW: 0, reason: "deltaEnergy=0"]
					}
				} catch (parseErr) {
					logWarn("energyParse: erro ao parsear timestamps: ${parseErr}")
				}
			}
			state.prevEnergyEnd = currentEnd
		} else {
			energyLog << [newPeriod: false, sameEnd: currentEnd]
		}

		// --- deltaEnergy bruto do ST (Wh) ---
		if (pc.deltaEnergy != null) {
			def deltaVal = pc.deltaEnergy.toBigDecimal().setScale(1, java.math.RoundingMode.HALF_UP)
			sendEvent(name: "deltaEnergy", value: deltaVal, unit: "Wh",
					  descriptionText: "SmartThings period energy: ${deltaVal} Wh")
		}

		// --- Measurement period ---
		if (pc.start != null) { sendEvent(name: "energyStart", value: pc.start) }
		if (pc.end   != null) { sendEvent(name: "energyEnd",   value: pc.end) }

		def ts = new Date().format("yyyy-MM-dd'T'HH:mm:ss", location.timeZone)
		sendEvent(name: "energyLastUpdated", value: ts)

		logDebug("energyParse: ${energyLog}")

	} catch (err) {
		logWarn("energyParse: [status: ERROR, errorMsg: ${err}]")
	}
}

//	===== Library Integration =====

def simulate() { return false }
//#include davegut.Samsung-Washer-Sim

// ~~~~~ start include (1072) davegut.Logging ~~~~~
library ( // library marker davegut.Logging, line 1
	name: "Logging", // library marker davegut.Logging, line 2
	namespace: "davegut", // library marker davegut.Logging, line 3
	author: "Dave Gutheinz", // library marker davegut.Logging, line 4
	description: "Common Logging Methods", // library marker davegut.Logging, line 5
	category: "utilities", // library marker davegut.Logging, line 6
	documentationLink: "" // library marker davegut.Logging, line 7
) // library marker davegut.Logging, line 8

def listAttributes(trace = false) { // library marker davegut.Logging, line 11
	def attrs = device.getSupportedAttributes() // library marker davegut.Logging, line 12
	def attrList = [:] // library marker davegut.Logging, line 13
	attrs.each { // library marker davegut.Logging, line 14
		def val = device.currentValue("${it}") // library marker davegut.Logging, line 15
		attrList << ["${it}": val] // library marker davegut.Logging, line 16
	} // library marker davegut.Logging, line 17
	if (trace == true) { logTrace("Attributes: ${attrList}") } // library marker davegut.Logging, line 18
	else { logDebug("Attributes: ${attrList}") } // library marker davegut.Logging, line 19
} // library marker davegut.Logging, line 20

def logTrace(msg){ log.trace "${device.displayName}: ${msg}" } // library marker davegut.Logging, line 22
def logInfo(msg) { // library marker davegut.Logging, line 24
	if (!infoLog || infoLog == true) { log.info "${device.displayName}: ${msg}" } // library marker davegut.Logging, line 25
} // library marker davegut.Logging, line 26
def debugLogOff() { // library marker davegut.Logging, line 28
	if (debug == true) { device.updateSetting("debug", [type:"bool", value: false]) } // library marker davegut.Logging, line 29
	else if (debugLog == true) { device.updateSetting("debugLog", [type:"bool", value: false]) } // library marker davegut.Logging, line 30
	logInfo("Debug logging is false.") // library marker davegut.Logging, line 31
} // library marker davegut.Logging, line 32
def logDebug(msg) { // library marker davegut.Logging, line 34
	if (debug == true || debugLog == true) { log.debug "${device.displayName}: ${msg}" } // library marker davegut.Logging, line 35
} // library marker davegut.Logging, line 36
def logWarn(msg) { log.warn "${device.displayName}: ${msg}" } // library marker davegut.Logging, line 38
// ~~~~~ end include (1072) davegut.Logging ~~~~~

// ~~~~~ start include (1091) davegut.ST-Communications ~~~~~
library ( // library marker davegut.ST-Communications, line 1
	name: "ST-Communications", // library marker davegut.ST-Communications, line 2
	namespace: "davegut", // library marker davegut.ST-Communications, line 3
	author: "Dave Gutheinz", // library marker davegut.ST-Communications, line 4
	description: "ST Communications Methods", // library marker davegut.ST-Communications, line 5
	category: "utilities", // library marker davegut.ST-Communications, line 6
	documentationLink: "" // library marker davegut.ST-Communications, line 7
) // library marker davegut.ST-Communications, line 8
import groovy.json.JsonSlurper // library marker davegut.ST-Communications, line 9

private asyncGet(sendData, passData = "none") { // library marker davegut.ST-Communications, line 11
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 12
		logWarn("asyncGet: [status: ERROR, errorMsg: no stApiKey]") // library marker davegut.ST-Communications, line 13
	} else { // library marker davegut.ST-Communications, line 14
		logDebug("asyncGet: ${sendData}, ${passData}") // library marker davegut.ST-Communications, line 15
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 16
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 17
			path: sendData.path, // library marker davegut.ST-Communications, line 18
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()]] // library marker davegut.ST-Communications, line 19
		try { // library marker davegut.ST-Communications, line 20
			asynchttpGet(sendData.parse, sendCmdParams, [reason: passData]) // library marker davegut.ST-Communications, line 21
		} catch (error) { // library marker davegut.ST-Communications, line 22
			logWarn("asyncGet: [status: FAILED, errorMsg: ${error}]") // library marker davegut.ST-Communications, line 23
		} // library marker davegut.ST-Communications, line 24
	} // library marker davegut.ST-Communications, line 25
} // library marker davegut.ST-Communications, line 26

private syncGet(path){ // library marker davegut.ST-Communications, line 28
	def respData = [:] // library marker davegut.ST-Communications, line 29
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 30
		respData << [status: "FAILED", errorMsg: "No stApiKey"] // library marker davegut.ST-Communications, line 31
	} else { // library marker davegut.ST-Communications, line 32
		logDebug("syncGet: ${sendData}") // library marker davegut.ST-Communications, line 33
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 34
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 35
			path: path, // library marker davegut.ST-Communications, line 36
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()] // library marker davegut.ST-Communications, line 37
		] // library marker davegut.ST-Communications, line 38
		try { // library marker davegut.ST-Communications, line 39
			httpGet(sendCmdParams) {resp -> // library marker davegut.ST-Communications, line 40
				if (resp.status == 200 && resp.data != null) { // library marker davegut.ST-Communications, line 41
					respData << [status: "OK", results: resp.data] // library marker davegut.ST-Communications, line 42
				} else { // library marker davegut.ST-Communications, line 43
					respData << [status: "FAILED", httpCode: resp.status, errorMsg: resp.errorMessage] // library marker davegut.ST-Communications, line 44
				} // library marker davegut.ST-Communications, line 45
			} // library marker davegut.ST-Communications, line 46
		} catch (error) { // library marker davegut.ST-Communications, line 47
			respData << [status: "FAILED", httpCode: "Timeout", errorMsg: error] // library marker davegut.ST-Communications, line 48
		} // library marker davegut.ST-Communications, line 49
	} // library marker davegut.ST-Communications, line 50
	return respData // library marker davegut.ST-Communications, line 51
} // library marker davegut.ST-Communications, line 52

private syncPost(sendData){ // library marker davegut.ST-Communications, line 54
	def respData = [:] // library marker davegut.ST-Communications, line 55
	if (!stApiKey || stApiKey.trim() == "") { // library marker davegut.ST-Communications, line 56
		respData << [status: "FAILED", errorMsg: "No stApiKey"] // library marker davegut.ST-Communications, line 57
	} else { // library marker davegut.ST-Communications, line 58
		logDebug("syncPost: ${sendData}") // library marker davegut.ST-Communications, line 59
		def cmdBody = [commands: [sendData.cmdData]] // library marker davegut.ST-Communications, line 60
		def sendCmdParams = [ // library marker davegut.ST-Communications, line 61
			uri: "https://api.smartthings.com/v1", // library marker davegut.ST-Communications, line 62
			path: sendData.path, // library marker davegut.ST-Communications, line 63
			headers: ['Authorization': 'Bearer ' + stApiKey.trim()], // library marker davegut.ST-Communications, line 64
			body : new groovy.json.JsonBuilder(cmdBody).toString() // library marker davegut.ST-Communications, line 65
		] // library marker davegut.ST-Communications, line 66
		try { // library marker davegut.ST-Communications, line 67
			httpPost(sendCmdParams) {resp -> // library marker davegut.ST-Communications, line 68
				if (resp.status == 200 && resp.data != null) { // library marker davegut.ST-Communications, line 69
					respData << [status: "OK", results: resp.data.results] // library marker davegut.ST-Communications, line 70
				} else { // library marker davegut.ST-Communications, line 71
					respData << [status: "FAILED", httpCode: resp.status, errorMsg: resp.errorMessage] // library marker davegut.ST-Communications, line 72
				} // library marker davegut.ST-Communications, line 73
			} // library marker davegut.ST-Communications, line 74
		} catch (error) { // library marker davegut.ST-Communications, line 75
			respData << [status: "FAILED", httpCode: "Timeout", errorMsg: error] // library marker davegut.ST-Communications, line 76
		} // library marker davegut.ST-Communications, line 77
	} // library marker davegut.ST-Communications, line 78
	return respData // library marker davegut.ST-Communications, line 79
} // library marker davegut.ST-Communications, line 80
// ~~~~~ end include (1091) davegut.ST-Communications ~~~~~

// ~~~~~ start include (1090) davegut.ST-Common ~~~~~
library ( // library marker davegut.ST-Common, line 1
	name: "ST-Common", // library marker davegut.ST-Common, line 2
	namespace: "davegut", // library marker davegut.ST-Common, line 3
	author: "Dave Gutheinz", // library marker davegut.ST-Common, line 4
	description: "ST Wash/Dryer Common Methods", // library marker davegut.ST-Common, line 5
	category: "utilities", // library marker davegut.ST-Common, line 6
	documentationLink: "" // library marker davegut.ST-Common, line 7
) // library marker davegut.ST-Common, line 8

def commonUpdate() { // library marker davegut.ST-Common, line 10
	if (!stApiKey || stApiKey == "") { // library marker davegut.ST-Common, line 11
		return [status: "FAILED", reason: "No stApiKey"] // library marker davegut.ST-Common, line 12
	} // library marker davegut.ST-Common, line 13
	if (!stDeviceId || stDeviceId == "") { // library marker davegut.ST-Common, line 14
		getDeviceList() // library marker davegut.ST-Common, line 15
		return [status: "FAILED", reason: "No stDeviceId"] // library marker davegut.ST-Common, line 16
	} // library marker davegut.ST-Common, line 17
	unschedule() // library marker davegut.ST-Common, line 19
	def updateData = [:] // library marker davegut.ST-Common, line 20
	updateData << [status: "OK"] // library marker davegut.ST-Common, line 21
	if (debugLog) { runIn(1800, debugLogOff) } // library marker davegut.ST-Common, line 22
	updateData << [stDeviceId: stDeviceId] // library marker davegut.ST-Common, line 23
	updateData << [debugLog: debugLog, infoLog: infoLog] // library marker davegut.ST-Common, line 24
	if (!getDataValue("driverVersion") || getDataValue("driverVersion") != driverVer()) { // library marker davegut.ST-Common, line 25
		updateDataValue("driverVersion", driverVer()) // library marker davegut.ST-Common, line 26
		updateData << [driverVer: driverVer()] // library marker davegut.ST-Common, line 27
	} // library marker davegut.ST-Common, line 28
	setPollInterval(pollInterval) // library marker davegut.ST-Common, line 29
	updateData << [pollInterval: pollInterval] // library marker davegut.ST-Common, line 30
	runIn(5, refresh) // library marker davegut.ST-Common, line 32
	return updateData // library marker davegut.ST-Common, line 33
} // library marker davegut.ST-Common, line 34

def setPollInterval(pollInterval) { // library marker davegut.ST-Common, line 36
	logDebug("setPollInterval: ${pollInterval}") // library marker davegut.ST-Common, line 37
	state.pollInterval = pollInterval // library marker davegut.ST-Common, line 38
	switch(pollInterval) { // library marker davegut.ST-Common, line 39
		case "10sec": schedule("*/10 * * * * ?", "poll"); break // library marker davegut.ST-Common, line 40
		case "20sec": schedule("*/20 * * * * ?", "poll"); break // library marker davegut.ST-Common, line 41
		case "30sec": schedule("*/30 * * * * ?", "poll"); break // library marker davegut.ST-Common, line 42
		case "1" : runEvery1Minute(poll); break // library marker davegut.ST-Common, line 43
		case "5" : runEvery5Minutes(poll); break // library marker davegut.ST-Common, line 44
		case "10" : runEvery10Minutes(poll); break // library marker davegut.ST-Common, line 45
		case "30" : runEvery30Minutes(poll); break // library marker davegut.ST-Common, line 46
		default: runEvery10Minutes(poll) // library marker davegut.ST-Common, line 47
	} // library marker davegut.ST-Common, line 48
} // library marker davegut.ST-Common, line 49

def deviceCommand(cmdData) { // library marker davegut.ST-Common, line 51
	def respData = [:] // library marker davegut.ST-Common, line 52
	if (simulate() == true) { // library marker davegut.ST-Common, line 53
		respData = testResp(cmdData) // library marker davegut.ST-Common, line 54
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 55
		respData << [status: "FAILED", data: "no stDeviceId"] // library marker davegut.ST-Common, line 56
	} else { // library marker davegut.ST-Common, line 57
		def sendData = [ // library marker davegut.ST-Common, line 58
			path: "/devices/${stDeviceId.trim()}/commands", // library marker davegut.ST-Common, line 59
			cmdData: cmdData] // library marker davegut.ST-Common, line 60
		respData = syncPost(sendData) // library marker davegut.ST-Common, line 61
	} // library marker davegut.ST-Common, line 62
	if (cmdData.capability && cmdData.capability != "refresh") { refresh() } // library marker davegut.ST-Common, line 63
	else { poll() } // library marker davegut.ST-Common, line 64
	return respData // library marker davegut.ST-Common, line 65
} // library marker davegut.ST-Common, line 66

def refresh() { // library marker davegut.ST-Common, line 68
	if (stApiKey!= null) { // library marker davegut.ST-Common, line 69
		def cmdData = [component: "main", capability: "refresh", command: "refresh", arguments: []] // library marker davegut.ST-Common, line 70
		deviceCommand(cmdData) // library marker davegut.ST-Common, line 71
	} // library marker davegut.ST-Common, line 72
} // library marker davegut.ST-Common, line 73

def poll() { // library marker davegut.ST-Common, line 75
	if (simulate() == true) { // library marker davegut.ST-Common, line 76
		def children = getChildDevices() // library marker davegut.ST-Common, line 77
		if (children) { children.each { it.statusParse(testData()) } } // library marker davegut.ST-Common, line 78
		statusParse(testData()) // library marker davegut.ST-Common, line 79
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 80
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 81
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 82
	} else { // library marker davegut.ST-Common, line 83
		def sendData = [path: "/devices/${stDeviceId.trim()}/status", parse: "distResp"] // library marker davegut.ST-Common, line 84
		asyncGet(sendData, "statusParse") // library marker davegut.ST-Common, line 85
	} // library marker davegut.ST-Common, line 86
} // library marker davegut.ST-Common, line 87

def deviceSetup() { // library marker davegut.ST-Common, line 89
	if (simulate() == true) { // library marker davegut.ST-Common, line 90
		deviceSetupParse(testData()) // library marker davegut.ST-Common, line 91
	} else if (!stDeviceId || stDeviceId.trim() == "") { // library marker davegut.ST-Common, line 92
		respData = "[status: FAILED, data: no stDeviceId]" // library marker davegut.ST-Common, line 93
		logWarn("poll: [status: ERROR, errorMsg: no stDeviceId]") // library marker davegut.ST-Common, line 94
	} else { // library marker davegut.ST-Common, line 95
		def sendData = [path: "/devices/${stDeviceId.trim()}/status", parse: "distResp"] // library marker davegut.ST-Common, line 96
		asyncGet(sendData, "deviceSetup") // library marker davegut.ST-Common, line 97
	} // library marker davegut.ST-Common, line 98
} // library marker davegut.ST-Common, line 99

def getDeviceList() { // library marker davegut.ST-Common, line 101
	def sendData = [path: "/devices", parse: "getDeviceListParse"] // library marker davegut.ST-Common, line 102
	asyncGet(sendData) // library marker davegut.ST-Common, line 103
} // library marker davegut.ST-Common, line 104

def getDeviceListParse(resp, data) { // library marker davegut.ST-Common, line 106
	def respData // library marker davegut.ST-Common, line 107
	if (resp.status != 200) { // library marker davegut.ST-Common, line 108
		respData = [status: "ERROR", httpCode: resp.status, errorMsg: resp.errorMessage] // library marker davegut.ST-Common, line 109
	} else { // library marker davegut.ST-Common, line 110
		try { respData = new JsonSlurper().parseText(resp.data) } // library marker davegut.ST-Common, line 111
		catch (err) { respData = [status: "ERROR", errorMsg: err, respData: resp.data] } // library marker davegut.ST-Common, line 112
	} // library marker davegut.ST-Common, line 113
	if (respData.status == "ERROR") { // library marker davegut.ST-Common, line 114
		logWarn("getDeviceListParse: ${respData}") // library marker davegut.ST-Common, line 115
	} else { // library marker davegut.ST-Common, line 116
		log.info "" // library marker davegut.ST-Common, line 117
		respData.items.each { log.trace "${it.label}:   ${it.deviceId}" } // library marker davegut.ST-Common, line 118
		log.trace "<b>Copy your device's deviceId value and enter into the device Preferences.</b>" // library marker davegut.ST-Common, line 119
	} // library marker davegut.ST-Common, line 120
} // library marker davegut.ST-Common, line 121

def calcTimeRemaining(completionTime) { // library marker davegut.ST-Common, line 123
	Integer currTime = now() // library marker davegut.ST-Common, line 124
	Integer compTime // library marker davegut.ST-Common, line 125
	try { // library marker davegut.ST-Common, line 126
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 127
	} catch (e) { // library marker davegut.ST-Common, line 128
		compTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", completionTime,TimeZone.getTimeZone('UTC')).getTime() // library marker davegut.ST-Common, line 129
	} // library marker davegut.ST-Common, line 130
	Integer timeRemaining = ((compTime-currTime) /1000).toInteger() // library marker davegut.ST-Common, line 131
	if (timeRemaining < 0) { timeRemaining = 0 } // library marker davegut.ST-Common, line 132
	return timeRemaining // library marker davegut.ST-Common, line 133
} // library marker davegut.ST-Common, line 134
// ~~~~~ end include (1090) davegut.ST-Common ~~~~~