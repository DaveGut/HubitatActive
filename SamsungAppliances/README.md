# Hubitat Samsung (via SmartThings) Appliances
Drivers for Samsung Appliances that have aready installed in the SmartThings Environment

## Installation Requirements:
* SmartThings Account with Appliance working through SmartThings
* Hubitat Environment device

## Driver Installation Instructions
You can install the driver using three methods:
* Hubitat Package Manager.  Search on tags "Appliances" or the keyword "Samsung"
* Copy files over from the locations below
* Use the Import function on a new driver edit page using the locations from below.

## Installation into Hubitat Instructions
* File Location:  https://github.com/DaveGut/HubitatActive/blob/master/SamsungAppliances/Install_Samsung_Appliance.pdf

## Device Driver Information
### Samsung Dishwasher
* Status: V1.1
* File Locaton: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Dishwasher.groovy

### Samsung Dryer
* Status: V1.1
* Special Condition:  Must load Samsung_Dryer_Flex to add the upper compartment.
* File Location: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Dryer.groovy
* File Location: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Dryer_flex.groovy

### Samsung HVAC
* Status: V1.1
* File Location: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_HVAC.groovy

### Samsung Oven
* Status: V1.1
* Special Condition: Requires ALL CHILD DRIVERS to load properly
* File Locations:
	* Parent: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Oven.groovy
	* Cavity: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Oven_cavity.groovy
	* TempProbe: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Oven_probe.groovy

### Samsung Refrigerator
* Status: V1.1
* Special Condition: Requires ALL CHILD DRIVERS to load properly
* File Locations:
	* Parent: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Refrig.groovy
	* Cavity: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Refrig_cavity.groovy
	* Cvroom: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Refrig_cvroom.groovy
	* Icemaker: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Refrig_icemaker.groovy

### Samsung Soundbar
* Status: 1.1
* Note: Works with soundbars that do not use the multi-room app.  For Soundbars that use the Multiroom app, use the Samsung Speaker Integration.
* File Location: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Soundbar.groovy

### Samsung Washer
* Status: V1.1
* Special Condition:  Must load Samsung_Dryer_Flex to add the upper compartment.
* File Location: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Washer.groovy
* File Location: https://raw.githubusercontent.com/DaveGut/HubitatActive/master/SamsungAppliances/Samsung_Washer_flex.groovy
