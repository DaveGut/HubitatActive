# Replica Samsung Oven Description

There device installs as a parent and a child.  The child's default name is PARENTNAME "cavity".

##Attribute remoteControlEnabled.  Remote control must be enabled at the stove prior to any command working.

##Parent Commands
	*	Configure - used to configure the device.  Useful if there are operational issues.
	*	Refresh - refreshes the ST's-device interface then requests the device status from ST.
	*	Pause, Stop
	*	Start
		*	If ANY entry is blank, sends a simple "start" command to the stove.
		* 	Parameters:
			*	mode.  Mode must be in the state.supportedOvenModes.  Not case sensitive.  This list is different if the partition (cavity) is installed.
			*	Time.  Format is hh:mm:ss OR seconds.
			*	Setpoint.  Must be in allowed setpoints for oven.  CODE DOES NOT CHECK.
	*	Set Oven Mode: Must be from state.supportedOvenModes.  Not case sensitive.  This list is different if the partition (cavity) is installed.
	*	Set Oven Setpoint: Must be in allowed setpoints for oven.  CODE DOES NOT CHECK.
	*	set Operation Time: Format is hh:mm:ss OR seconds.
	*	Set Oven Light.  From state.supportedBrightnessLevel.
	*	Set Probe Setpoint.  Probe must be installed and attribute probeStatus = "connected".
	*	Starting the Oven.  It is up to you and may be model-dependent on how to best start the oven.  Two methods.
		*	Use newer method.
			*	Enable remote at stove
			*	Set Oven Setpoint
			*	Set Operation Time
			*	Set Oven Mode
			*	Start (without parameters).
		*	Legacy Method
			*	Start (without parameters mode, time, and setpoint).

##Child (cavity) Commands
	*	The child will only work if the partition is installed and the parent attribute remoteControlEnabled is true.
	*	For those commands on the Childs page, the same instructions.
