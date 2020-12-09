/*	===== HUBITAT INTEGRATION VERSION =====================================================
UpNp Device List
	Dave Gutheinz
This is a quick tool to list your UPnP devices using ssdp discovery.
It provides a list of the Path to the UPnP data (copy to browser
address line) and the MAC.  No other function.
===== HUBITAT INTEGRATION VERSION =======================================================*/
//import org.json.JSONObject
def appVersion() { return "1.0" }
def appName() { return "UPnP Device List" }
definition(
	name: "UPnP Device List",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Application to identify UPnP devices.",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	singleInstance: true,
	importUrl: ""
)
preferences {
	page(name: "mainPage")
	page(name: "discovery")
}

//	===== Page Definitions =====
def mainPage() {
	logInfo("mainPage")
	setInitialStates()
	ssdpSubscribe()
	def page1 = "0.  Turn on devices you wish to check for at least 1 minute.\n"
	page1 += "1.  Press 'Next' to find UPnP devices.\n"
	return dynamicPage(
		name: "mainPage",
		title: "UPnP Device List", 
		nextPage: "discovery",
		install: false,
		uninstall: true){
		section(page1) {}
	}
}
def discovery() {
	logInfo("discovery")
	def devices = state.devices
	def devList = ""
	devices.each {
		devList += "${it}\n\n"
	}
	ssdpDiscover()
	def text2 = "<b>Allow at least two minutes to discover your devices</b>\n\r\n\r"
	return dynamicPage(
		name: "discovery", 
		title: "Device Discovery",
		nextPage: "", 
		refreshInterval: 10, 
		install: true, 
		uninstall: true){
			section("<b>Allow at least 2 minutes for discovery</b>") {
				paragraph "<b>UPnP Devices</b>"
				paragraph "<textarea rows=30 cols=50 readonly='true'>${devList}</textarea>"
			}
	}
}

//	===== Start up Functions =====
def setInitialStates() {
//	state.ssdpDevices = [:]
	state.devices = [:]
}
def installed() { initialize() }
def updated() { initialize() }
def initialize() { unschedule() }

//	===== Device Discovery =====
void ssdpSubscribe() {
	logInfo("ssdpSubscribe")
	unsubscribe()
	subscribe(location, "ssdpTerm.upnp:rootdevice", ssdpHandler)
	subscribe(location, "ssdpTerm.ssdp:all", ssdpHandler)
}
void ssdpDiscover() {
	logInfo("ssdpDiscover")
	sendHubCommand(new hubitat.device.HubAction("lan discovery upnp:rootdevice", hubitat.device.Protocol.LAN))
	pauseExecution(1000)
	sendHubCommand(new hubitat.device.HubAction("lan discovery ssdp:all", hubitat.device.Protocol.LAN))
}
def ssdpHandler(evt) {
	def parsedEvent = parseLanMessage(evt.description)
	def ip = convertHexToIP(parsedEvent.networkAddress)
	def path = parsedEvent.ssdpPath
	def port = convertHexToInt(parsedEvent.deviceAddress)
	def mac = parsedEvent.mac
	def key = "${ip}:${port}${path}"

	def devices = state.devices
	device = [:]
	device["mac"] = mac
	devices << ["${key}": device]
	logInfo("ssdpHandler: found device at = ${key}")
}

def logWarn(message) {
	log.warn "${appName()} ${appVersion()}: ${message}"
}
def logInfo(message) {
	log.info "${appName()} ${appVersion()}: ${message}"
}
private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}
private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}