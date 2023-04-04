# HubiThings Replica Samsung Washer

## NOTE: Some functions may not work.  Samsung has chosen to disable non-SmartThings access to Start, Pause, and Set Operation Time functions for "safety" reasons.
Link to SmartThings Article:  https://community.smartthings.com/t/samsung-oven-apis-for-setting-cooking-mode-setpoint-cooking-time/251558/7?u=gutheinz

## Current limitation
The washer integration does not include child devices (i.e., flex capabilities of the washer).

## Remote control Note.
Remote control must be enabled on the Oven panel prior to controlling the oven.  The attributes will be correct regardless.  You must follow the same pre-control procedures on this driver as you do for control via the SmartThings application.

## Main Device Command Description:
* Configure: Reloads and updates the device and child device configuration to current.  Used as a first troubleshooting step.
* Refresh: Request a full refresh of the device and then update to attributes.
* Run: Start the washer.
  * Remote control must be enabled.
  * Attribute: machineState, value "run".
* Pause: Pause the washer.
  * Remote control must be enabled.
  * Attribute: machineState, value "pause".
* Stop: Stop the washer.
  * Remote control must be enabled.
  * Attribute: machineState, value "stop".

# Appreciation:
### Bloodtick_Jones: Development of a great SmartThings API interface app and supporting my peculiar needs.

# Contributions
I do not take contributions for my developments.  If you find the my integrations to be of value, you may make a donation to the charity of your choice or perform an act of kindness for a stranger!

Note: This readme will be updated as problems and resolutions are developed by users.
