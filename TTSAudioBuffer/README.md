# Hubitat-TTS-Audio-Buffer
Buffers  notification messages for speakers so that messages are not interrupted and desroyed.

This integration supports multiple speakers audio notification buffering using the capability "Voice Synthesis" command "speak(text)".  For use in rules or other external triggers.

# Installation:
a.  Install the device driver and application into Hubitat.

b.  Run the application and select devices you want to buffer audio from. Note:  A new virtual device with the original speaker name plus " - TTS Queue" will be creates. This is the device you select in  automations that may generate multiple audio notifications in a short period of time.
    
c.  Fine tune the delay time for your device's recovery function.  This is
    necessary since the devices do not recover audio the same way and 
    sometimes a recovery will cause the device recovery to truncate an 
    audio message (I had to adjust to 15 seconds for Samsung Multiroom 
    Audio speakers.)
           
# Upgrade from previous:
a.  Replace the file contents of the driver and application in Hubitat.

b.  Run the application, install devices (if desired), and select done.

c.  Fine tune the delay time for your device's recovery function.  This 
    is necessary since the devices do not recover audio the same way and 
    sometimes a recovery will cause the device recovery to truncate an 
    audio message (I had to adjust to 15 seconds for Samsung Multiroom 
    Audio speakers.)
           
# Preferences in the Driver:
a.  Delaby between buffered messages in seconds:  Time between messages 
    (see c. above).  Default is 10 seconds.
    
b.  Enter desired test text:  The message you want to use for testing 
    your device.  A default is set.
    
c.  Display debug messages?  Boolean.  Will run debug messages for 30 
    minutes.

# Commands:
a.  Clear Queue:  Clears the present queue (in case of issue).

b.  Set Level:  Allows you to set level in the rule machine before and 
    after a notification.
    
c.  Speak:  The rule machine command that will pass the audio 
    notification to the speaker.
    
d.  Test Queue:  Set up for calibrating.  Has four messages so you can 
    test the message and audio transitions on your device.
