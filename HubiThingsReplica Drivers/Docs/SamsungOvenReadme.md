# HubiThings Replica Samsung Oven

## NOTE: Not all functions work for all devices.  Samsung has chosen to disable non-SmartThings access to Start, Pause, and Set Operation Time functions for "safety" reasons.
Link to SmartThings Article:  https://community.smartthings.com/t/samsung-oven-apis-for-setting-cooking-mode-setpoint-cooking-time/251558/7?u=gutheinz

## Remote control Note.
Remote control must be enabled on the Oven panel prior to controlling the oven.  The attributes will be correct regardless.

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
Jeff Page: Provide a LOT of support verify functions and troubleshooting/identifying issues with the start commands.
Bloodtick_Jones: Development of a great SmartThings API interface app and supporting my peculiar needs.

# Contributions
I do not take contributions for my developments.  If you find the my integrations to be of value, you may make a donation to the charity of your choice or perform an act of kindness for a stranger!
