# Hubitat Kasa Device Integration
Combined LAN and Cloud Integration with user choice of cloud usage at the application level and the individual device level.  Accounts for on-going TP-Link firmware updates that cause the LAN integration to fail.

## Instructions:  
https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Documentation.pdf

## Version 6.3 changes:
Version 6.3 converts the LAN communications to UDP messaging in order to reduce Hubitat resource usage.  Various other changes are implements both because of that change and to simplify the overall functionality.  Below is a summary of the changes.

a.	Attributes: connection(LAN/CLOUD) and commsError(true/false),  Added and deleted associated states.

b.	Communications:

	1.	Added LAN UDP Communications with associated changes to method parse and new state.lastCommand
	2.	
	3.	Removed LAN Raw Socket Communication with associated states.
c.	Error Handling.  Change to repeat first command only, do not change poll/power poll intervals.

d.	Multiplugs:

	1.	Coordinate attribute connection, state.pollInterval, and settings bind / useCloud amoung devices.
	2.	
	3.	On/Off polling set and run from last device to complete a save preferences.  Data coordinated.
e.	On/Off Polling, Power Polling, and Refersh

	1.	Merged three function control into a single command, setPollInterval.  I use a command so
		that users can access it through rule machine.
		
	2.	Power reporting:  Reduce event handling in overall system.
	3.	
		a.	If power is below 5 W, will update if current power != new power +/- .5 W.
		
		b.	Otherwise, will update if current power != new power +/- 5 W
		.
f.	Data Cleanup.  Added method to clean up data, settings, and states from versions back to 5.3.3.

g.	Bulbs.  Converted capability Color Temperature to new definition.

h.	Save Preferences:  Added method to log all system states and data at the end the command.

	This provides trouble shooting data for issue resolution with the developer.
	
i.	Update Process: After updating code, run Application then Update Installed Devices.

	1.	Will execute method updated on each device, including data, setting, and state updates.
	2.	
	3.	Still recommend checking each device's preferences and execute a Save Preferences.
