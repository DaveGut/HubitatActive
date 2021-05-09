## Kasa Devices Version 6.3 Application Change Log

### 6.3
Added coordinate method to support multi-plug outlet data/state coordination.

### 6.3.0.1
a.  Added fixCommunications link to setCommsError.

    1.  Enables updating IP or token error when an commsError is declared in the app.
    2.  Clears error if corrected.

b.  Added application code to check the driver version for 6.3.x.

    1.  flag a warning on the Application log page if not.
    2. (Some HPM installations lost some of the links in past. This has been fixed in HPM.)

## Kasa Devices Version 6.3 Driver Change Log
### 6.3
a.	Attributes: connection(LAN/CLOUD) and commsError(true/false),  Added and deleted associated states.
b.	Communications:
	1.	Added LAN UDP Communications with associated changes to method parse and new state.lastCommand
	2.	Removed LAN Raw Socket Communication with associated states.
c.	Error Handling.  Change to repeat first command only, do not change poll/power poll intervals.
d.	Multiplugs:
	1.	Coordinate attribute connection, state.pollInterval, and settings bind / useCloud amoung devices.
	2.	On/Off polling set and run from last device to complete a save preferences.  Data coordinated.
e.	On/Off Polling, Power Polling, and Refersh
	1.	Merged three function control into a single command, setPollInterval.  I use a command so
		that users can access it through rule machine.
	2.	Power reporting:  Reduce event handling in overall system.
		a.	If power is below 5 W, will update if current power != new power +/- .5 W.
		b.	Otherwise, will update if current power != new power +/- 5 W.
f.	Data Cleanup.  Added method to clean up data, settings, and states from versions back to 5.3.3.
g.	Bulbs.  Converted capability Color Temperature to new definition.
h.	Save Preferences:  Added method to log all system states and data at the end the command.
	This provides trouble shooting data for issue resolution with the developer.
i.	Update Process: After updating code, run Application then Update Installed Devices.
	1.	Will execute method updated on each device, including data, setting, and state updates.
	2.	Still recommend checking each device's preferences and execute a Save Preferences.


