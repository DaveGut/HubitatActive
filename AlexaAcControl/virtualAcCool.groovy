/*
Virtual AC Cool Driver
Design:
	on:
		1.	triggers rule "AC Status" by setting switch to on.
		2.	turns switch to off after 3 seconds.
	setLevel:
		1.	Ranges level to between 65 and 90F
		2.	set attribute level
		3.	push button 1 to trigger rule "AC Cool"
*/
metadata {
	definition (name: "Virtual AC Cool",
    			namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: ""
			   ) {
		capability "Switch"
		capability "Switch Level"
		capability "Pushable Button"
	}
    preferences {
		input ("descriptionText", "bool", title: "Enable description text logging", defaultValue: true)
	}
}
def installed() {
	sendEvent(name: "numberOfButtons", value: 1)	
	updated()
}
def updated() {
	log.info "Updating .."
	logInfo("updated: Description text logging is ${descriptionText}.")
}
def on() {
	logInfo("Triggering temperature report to Alexa Devices")
	sendEvent(name: "switch", value: "on", isStateChange: true)
	runIn(3, off)
}
def off() {
	sendEvent(name: "switch", value: "off", isStateChange: true)
}
def setLevel(temperature, duration = null) {
	logInfo("setLevel: level = ${temperature}, trigger Cool Setting Change")
	temperature = temperature.toInteger()
	//	Limit Cool to 65 to 90 range
	if (temperature < 65) { temperature = 65 }
	if (temperature > 90) { temperature = 90 }
	sendEvent(name: "level", value: temperature, isStateChange: true)
	sendEvent(name: "pushed", value: 1, isStateChange: true)
}
def logInfo(msg) {
	if (descriptionText == true) { log.info "${device.label}} ${msg}" }
}
//	end-of-file