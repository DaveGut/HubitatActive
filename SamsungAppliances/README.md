# Hubitat Samsung (via SmartThings) Appliances
Drivers for Samsung Appliances that have aready installed in the SmartThings Environment

## Installation Requirements:
1.  SmartThings Account with Appliance working through SmartThings
2.  Hubitat Environment device

## Driver Installation Instructions
You can install the driver using three methods:
1.  Hubitat Package Manager.  Search on tags "Appliances" or the keyword "Samsung"
2.  Copy files over from the locations below
3.  Use the Import function on a new driver edit page using the locations from below.

## Installation into Hubitat Instructions
File Location:  https://github.com/DaveGut/HubitatActive/blob/master/SamsungAppliances/Install_Samsung_Appliance.pdf

## Device Driver Information
### Samsung Dishwasher
1.  Status: Beta
2.  File Locaton: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Dishwasher.groovy

### Samsung Dryer
1.  Status: Beta
2.  File Location: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Dryer.groovy
3.  Future: Adding the upper dryer compartment in a future release.

### Samsung HVAC
1.  Status: Release 1.0
2.  File Location: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_HVAC.groovy

### Samsung Oven
1.  Status: Beta
2.  Special Condition: Requires ALL CHILD DRIVERS to load properly
3.  File Locations:
	Parent: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Oven_cavity.groovy
	Cavity: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Oven_cavity.groovy
	TempProbe: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Oven_probe.groovy

### Samsung Refrigerator
1.	Status: Beta
2.	Special Condition: Requires ALL CHILD DRIVERS to load properly
3.	File Locations:
	Parent: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Refrig.groovy
	Cavity: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Refrig_cavity.groovy
	cvRoom: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Refrig_cvRoom.groovy
	Icemaker: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Refrig_icemaker.groovy

### Samsung Washer
1.	Status: Beta
2.	File Location: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Washer.groovy
3.	Future: Adding the upper dryer compartment in a future release.

