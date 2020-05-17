/*
Virtual AC Heat Driver
Design:
	setLevel:
		1.	Ranges level to between 55 and 80F
		2.	set attribute level
		3.	push button 1 to trigger rule "AC Heat"
*/
metadata {
	definition (name: "Virtual AC Heat",
    			namespace: "davegut",
				author: "Dave Gutheinz",
				importUrl: ""
			   ) {
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
def setLevel(temperature, duration = null) {
	logInfo("setLevel: level = ${temperature}")
	temperature = temperature.toInteger()
	//	Limit Heat to 55 to 80 range
	if (temperature < 55) { temperature = 55 }
	if (temperature > 80) { temperature = 80 }
	sendEvent(name: "level", value: temperature, isStateChange: true)
	sendEvent(name: "pushed", value: 1, isStateChange: true)
}
def logInfo(msg) {
	if (descriptionText == true) { log.info "${device.label}} ${msg}" }
}
//	end-of-file