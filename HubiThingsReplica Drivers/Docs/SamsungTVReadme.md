# HubiThings Replica Samsung TV

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

## Remote control Note.
Remote control must be enabled on the washer panel prior to controlling the washer.  The attributes will be correct regardless.  You must follow the same pre-control procedures on this driver as you do for control via the SmartThings application.

## General troubleshooting recommendations for HubiThings Replica devices.
* Open the Hubitat Apps page and look at the parent-chile HubiThings Replica for any indications of issues. Expected result:  
  * HubiThings Replica  
  * HubiThings OAuth ea3-5122 : Authorized
* Go to the SmartThings App and assure the failing function is working within that app.
  * If not, the issue is likely within SmartThings itself and further Hubitat troubleshooting is not warranted.
* From the Hubitat Devices page, device's edit page,
  * Open a separate logging window
  * Select the device command "Configure".  This will take about a minute to complete.
  * Review the log page for any WARNING or ERROR logs.  If there are some, copy log page and send to the developer.
  * Go to the data section of the driver (or parent) and verify that critical data has passed to the device.  If not, contact the developer.
  * Data (with first several characters as an example):
    * capabilities: {"components":[{"id":  (this is a very large amount of data)
    * commands: {"replicaEvent":[{"name":
    * description: {"name":
    * replica: {"deviceId"
    * rules: {"version":1,"components":[{
    * triggers: {
 * Recheck problemmatic function:
   * Open NEW logging window
   * Execute problemmatic function and note exactly what the anomolous behavior is.
   * If fuction still fails, send Command Executed, anomolous behavior, and logs to developer.

## Main Device Command Description:
* Configure: Reloads and updates the device and child device configuration to current.  Used as a first troubleshooting step.
* Refresh: Request a full refresh of the device and then update to attributes.

# Appreciation:
### Bloodtick_Jones: Development of a great SmartThings API interface app and supporting my peculiar needs.

# Contributions
I do not take contributions for my developments.  If you find the my integrations to be of value, you may make a donation to the charity of your choice or perform an act of kindness for a stranger!

Note: This readme will be updated as problems and resolutions are developed by users.
