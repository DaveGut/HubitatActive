# Kasa CAM

## Installation
* Make sure two-factor identification is DISABLED in your Kasa Phone App (this integration will not work otherwise).
* Create a static IP address for the device on your router.
* install Driver from gitHub into the Hubitat drivers.
* Create a Virtual Device using this driver as the type.
* Open the device's page
* Enter the following into the preferences:
  * deviceIp
  * port (defalts to expected value of 9999)
  * Kasa account username (e-mail)
  * Kasa account password (password will be masked)
* Save Preferences.  You shoule see the following data
  * Current States: motionDetect, switch
  * State Variables: pollInterval
* If motionDetect is off or switch is off, execute the Motion Deect  or switch command
* If Motion Poll Interval is off, execute that to the desired value
  * if set, the attribute motion will appear once a motion is detected.

## Command Information:
* Motion Detection
  * Once detected, will remain active for 30 seconds.
  * To preclude multiple events being thrown, a period of 300 seconds between active reports is hard-coded into the design.
* Motion Poll - will force a poll of motion (even if interval is off).
* Refresh - attains the status of all preferences from the cloud (every 30 minutes)
* Preferences for CAM reflect the same values on the Kasa App device's details page.

## General troubleshooting recommendations
If you are having issues:
* Do a Save Preference w/o making any changes.
* Check the Kasa Phone App to assure the device is working properly.
* Check your router to assure the LAN address has not changed.  If changed, change in the preferences section.
* Reboot your router to reload the various tables.
* Open the Logging page in Hubitat.
  * Select debug logging in preferences and save preferences
  * Try to duplicate the failed command.  IF it still fails, copy TEXT version of the logs and Private Message to the developer.
