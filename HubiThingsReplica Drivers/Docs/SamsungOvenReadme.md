# HubiThings Replica Samsung Oven

## NOTE: Not all functions work for all devices.  Samsung has chosen to disable non-SmartThings access to Start, Pause, and Set Operation Time functions for "safety" reasons.
Link to SmartThings Article:  https://community.smartthings.com/t/samsung-oven-apis-for-setting-cooking-mode-setpoint-cooking-time/251558/7?u=gutheinz

## Remote control Note.
Remote control must be enabled on the Oven panel prior to controlling the oven.  The attributes will be correct regardless.  You must follow the same pre-control procedures on this driver as you do for control via the SmartThings application.

## General troubleshooting recommendations for HubiThingsReplica devices.
* Open the Hubitat Apps page and look at the parent-child HubiThings Replica for any indications of issues. Expected result:  
  * HubiThings Replica  
  * HubiThings OAuth xxn:nnnn : Authorized
* Go to the SmartThings App and assure the failing function is working within that app.
  * If not, the issue is likely within SmartThings itself and further Hubitat troubleshooting is not warranted.
* From the Hubitat Devices page, device's edit page,
  * Open a separate logging window
  * Select the device command "Configure".  This will take about a minute to complete.
  * Review the log page for any WARNING or ERROR logs.  If there are some, copy log page and send to the developer.
  * Go to the data section of the driver (or parent) and verify that critical data has passed to the device.  If not, contact the developer.
  * Data (with first several characters as an example):
    * capabilities: {"components":[{"id":  (this is a very large amount of data)
    * commands: {"replicaEvent":[{"name":
    * description: {"name":
    * replica: {"deviceId"
    * rules: {"version":1,"components":[{
    * triggers: {
 * Recheck problemmatic function:
   * Open NEW logging window
   * Execute problemmatic function and note exactly what the anomolous behavior is.
   * If fuction still fails, send Command Executed, anomolous behavior, and logs to developer.


## Main Device Command Description:
* Configure: Reloads and updates the device and child device configuration to current.  Used as a first troubleshooting step.
* Refresh: Request a full refresh of the device and then update to attributes.
* Set Oven Mode:  Sets the oven mode of Bake, ConvectionBake, etc.  
  * Command MUST be spelled correctly; however, the input is not case sensitive.  
  * Attribute "ovenMode"
* Set Oven Setpoint: Requires mode set first.  Setpoint in the temperature scale user has set within the oven panel.  
  * Attribute "ovenSetpoint".
* Set Operation Time: Requires mode and oven setpoint set first.  Sets the operation time of the oven.
  * Entry is integer seconds or HH:MM:SS format.
  * Note: Function may not work on all ovens.
  * CAUTION: MAY START OVEN ON SOME OVENS.  
  * Attribute "operationTime", format HH:MM:SS
* Start: Starts the oven.  Mode, setpoint, and operationTime should already be set.
  * Note: Function may not work on all ovens.
  * Attribute "operatingState"
* Pause: Pauses the oven.
  * Note: Function may not work on all ovens.
  * Attribute "operatingState"
* Set Oven Light.  Turns oven light on/off.
  * Attribute "brightnessLevel" (high or off)
* SetProbeSetpoint: Sets the probe setpoint.
  * Attribute "probeSetpoint"

## Cavity Device Command Descriptions
For the cavity, commands will only work if attribute "ovenCavityStatus" is on.
Commands:  See above.  All commands except Configure, Set Oven Light and Set Probe Setpoint are available.

# Appreciation:
### Jeff Page: Provide a LOT of support verify functions and troubleshooting/identifying issues with the start commands.

### Bloodtick_Jones: Development of a great SmartThings API interface app and supporting my peculiar needs.

# Contributions
I do not take contributions for my developments.  If you find the my integrations to be of value, you may make a donation to the charity of your choice or perform an act of kindness for a stranger!
