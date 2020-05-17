/*
Virtual FanDriver
*/
metadata {
	definition (name: "Virtual AC Fan",
    			namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: ""
			   ) {
		capability "Switch"
		capability "Pushable Button"
	}
    preferences {
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}
def installed() {
//	sendEvent(name: "numberOfButtons", value: 2)
	updated()
}
def updated() {
	log.info "Updating .."
	logInfo("updated: Description text logging is ${descriptionText}.")
}
def on() {
	logInfo("Thermostat Fan to On Mode")
	sendEvent(name: "switch", value: "on")
	pauseExecution(3000)
	sendEvent(name: "switch", value: "stby")
}
def off() {
	logInfo("Thermostat Fan to Auto Mode")
	sendEvent(name: "switch", value: "off")
	pauseExecution(3000)
	sendEvent(name: "switch", value: "stby")
}
def logInfo(msg) {
	if (descriptionText == true) { log.info "${device.label}} ${msg}" }
}
//	end-of-file