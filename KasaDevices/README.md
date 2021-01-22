## Hubitat Kasa Device Integration
Combined LAN and Cloud Integration with user choice of cloud usage at the application level and the individual device level.  Accounts for on-going TP-Link firmware updates that cause the LAN integration to fail.

# Instructions:  
https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Documentation.pdf

# Version 6.0 changes:
a. Application
   1. Added capability to select/deselect Kasa Cloud Access with login / token access pages.
   2. Added capability to set the LAN segment to find devices (when different from Hubitat LAN segment)
   3  Moved Kasa Tools (bind/unbind device, LED on/off, reboot device) to the individual device drivers.
b. Drivers
   1. Merged quick poll command and refresh preference to new Refresh preference.
   2. Added preferences for Kasa Tools.
   3. Added preference (capability) to use Kasa Cloud or LAN.
   4. Limit Refresh to one minute ONLY when Kasa Cloud is used for the device.
   5. Added Cloud Communications / Parse methods.
   

## Update Instructions:
a. Driver Update:
   1.  Replace the driver code in Hubitat using either the import function or copy/paste.
   2.  Open the device and Save Preferences.

b. Application Update:
   1.  Replace the application code in Hubitat using either the import function or copy/paste.
   2.  Run the app and run either of the functions.

## Initial Installation:
a. Manual installation where the user enters the IP address and other information.  Does not utilize the integration application (see: https://github.com/DaveGut/Hubitat-TP-Link-Integration/wiki).

b. Integrated installation using the Application (see: https://github.com/DaveGut/Hubitat-TP-Link-Integration/wiki).,

c.  Installation using Hubitat Package Manager.
    1.  You will likely need to reference the instructions at: https://github.com/DaveGut/Hubitat-TP-Link-Integration/wiki.

# Version 5.3 Changes
The Integrtion has been updated to versio 5.3.  This is a major communications method update but overall functionality is minor.

    1.  Implemented rawSocket for driver communications to alleviate problems with UDP communications
    
        a)  excessive errors due to other LAN UDP traffic
        
        b)  inability to process multiple return message packets in UDP.
        
    2.  Changed quickPoll to be a scheduled event vice runIn for reliability of the function.
    
##  Note on rawSocket
Hubitat has the following information on QuickPolling:

a.  "NOTE: This interface is in alpha status and some users have reported issues with their hubs when using this, use at your own risk and please report any issues in the community"

b.  "Hubitat Provided Methods (Since 2.1.2)"

My testing has indicated a stable interface on my operational hub.  However, overuse of quick polling can slow down an operational hub and may impact performance. 

## Warning:  Quick Polling can have negative impact on the Hubitat Hub and network performance. If you encounter performance issues, before contacting Hubitat support, turn off quick polling and check your sysem out.
