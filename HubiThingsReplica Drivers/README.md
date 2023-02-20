# HubiThings Replica Drivers

## Samsung Refrigerator
* Requires Replica_Samsung_Refrigerator driver plus two children: Replica_Samsung_Refrigerator_cavity, and Replica_Samsung_Refrigerator_icemaker.
* Added installation steps: None
* Design Notes: 
  * If a child does not install, attempt the Configure Command on the parent device.
  * Not all commands are available on all of the large number of years/models of Samsung Refrigerators.
  * Design does not support scales nor patries.

## Samsung TV
* Requires Replica_Smsung_Refrigerator driver
* Additional installation steps
  * Turn your TV set ON.
  * Assure your TV's IP is on the static list on your router.
  * Enter the TV's IP address in the preferences section of the Device's page and SAVE PREFERENCES
  * Assure the TV's internal settings at Menu > General > External Device Manager > Device Connect Manager > Access Notification is set to "First Time Only"
  * With remote in-hand, select the menu key and select "Allow" on the pop-up on your TV set.  This will enable Websocket communications to complete the control chain.
* Audio Notification.  This is for test/demonstration purposes only.
  * Select "Use Alternate TTS Method" and save preferences
  * Go to the Free voices.rss and create an account and obtain a key.
  * Enter the Key and save preferences.
  * To test, turn the TV on (make sure switch value is on) and select "Test Audio Notify".  This will start with barking dogs and then a TTS Stream.
* TV Applications.  Driver includes this capability.  It must be loaded via the device before it will work
  * Installing Apps (searching on TV)
    * In preferences, select "Scan for App Codes (use rarely)" and save preferences.
    * It will run for about 10 minutes to scan likely apps to see if they are installed.  During this period, you can still use the remote for normal TV control.
  * Once installed, the following commands will work.  A list of apps and codes is contained in the state section of the device page.
    *  App Open By Code.
    * App Open By Name.  Not case sensitive.  Also searches for first match on characters entered (i.e., "Amazo" will open Amazon Prime).
* Design Notes
  * Driver is designed for maximum use of Local control (using LAN commands) and augment these with the smartThings attributes and capabilities.
  * If you only wish to use this to monitor TV power state, then use the Replica Switch Driver instead.

## Samsung Soundbar
* Requires Replica_Samsung_Soundbar driver
* Designed for soundbars that are NOT multi-room application devices.
  * If the SmartThings phone interface has the function "Go To Multiroom App", then this driver is very limited in functions.  Another driver is available at https://community.hubitat.com/t/release-samsung-multiroom-wifi-audio/1805.
* Audio Notification.  This is now available on the driver.  To test it, (after entering the DeviceIp), I recommend selecting using an Alternate TTS method (the Hubitat method does not work on my 2020 TV).  
  * Select "Use Alternate TTS Method and save preferences
  * Go to the Free voices.rss and create an account and obtain a key.
  * Enter the Key and save preferences.
  * To test, turn the TV on (make sure switch value is on) and select "Test Audio Notify".  This will start with barking dogs and then a TTS Stream.

## Replica_Motion_Sensing_Doorbell.groovy: 
Provides motion sensing and bell press notification to the Hubitat interface.


## Replica_Color_Bulb.groovy.groovy: 
    Generic Color Temperature bulb using SmartThings as the execution platform


## Replica_CT_Bulb.groovyy: 
    Generic Color bulb using SmartThings as the execution platform

# Contributions
I do not take contributions for my developments.  If you find the my integrations to be of value, you may make a donation to the charity of your choice or perform an act of kindness for a stranger!
