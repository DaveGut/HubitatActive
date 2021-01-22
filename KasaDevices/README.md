# Hubitat Kasa Device Integration
Combined LAN and Cloud Integration with user choice of cloud usage at the application level and the individual device level.  Accounts for on-going TP-Link firmware updates that cause the LAN integration to fail.

## Instructions:  
https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Documentation.pdf

## Version 6.0 changes:
a. Application
   1. Added capability to select/deselect Kasa Cloud Access with login / token access pages.
   2. Added capability to set the LAN segment to find devices (when different from Hubitat LAN segment)
   3. Moved Kasa Tools (bind/unbind device, LED on/off, reboot device) to the individual device drivers.
   
b. Drivers
   1. Merged quick poll command and refresh preference to new Refresh preference.
   2. Added preferences for Kasa Tools.
   3. Added preference (capability) to use Kasa Cloud or LAN.
   4. Limit Refresh to one minute ONLY when Kasa Cloud is used for the device.
   5. Added Cloud Communications / Parse methods.
   
c. REMOVED capability to do a manual (without application) installation. (Note: When using LAN instalation, the APP is not used during operations.)
