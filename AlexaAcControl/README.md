# Alexa Control of a Hubitat Thermostat

Controlling a Hubitat thermostat via Alexa is not automatic (since the Amazon Echo Skill does not support thermostats).  As a work-around, I created several virtual devices drivers/devices, Hubitat rules, and one Amazon Echo routine.

## Virtual devices
1.  virtualAcCool.  Sets thermostat cool set point via the alexa command "set cool to (temperature)" using the "AC Cool Rule".  Triggers the "AC Status" rule (notify via echo speaks) via the alexa routine "get house temperature" using the attribute switch set to on.
2.  Virtual AcHeat.  Sets thermostat heat set point via the alexa command "set heat to (temperature)" using the "AC Heat Rule"
3.  Virtual AcFan.  Turns on (via rule "AC Fan On") or to auto (via rule "AC Fan Auto") using the attribute switch (off = auto).

# Rules





