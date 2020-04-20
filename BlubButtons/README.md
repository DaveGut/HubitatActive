# Hubitat-Blub-Buttons
Buttons for Hubitat Dashboard use to control bulb color and color temperature selection

This app has the following flow:

a.  User selects the bulbs from the capability.colorControl and capability.colorTemperature lists.

b.  A virtual device is instaled for EACH selected bulb.
    1.  Color Bulbs have 22 buttons, as detailed below.
    
    2.  Color Temperature bulbs have 9 buttons, as detailed below.
    
    3.  Attributes are created to provide a means to label (using attribute tile).
    
    4.  The color name is updated based on the devices color.

c.  The user can then select the buttons to implement within their environment.

Buttons, Attributes, and Labels

1 - "Color_1" // "Red"

2 - "Color_2" // "Orange"

3 - "Color_3" // "Yellow"

4 - "Color_4" // "Chartreuse"

5 - "Color_5" // "Green"

6 - "Color_6" // "Spring"

7 - "Color_7" // "Cyan"

8 - "Color_8" // "Azure"

9 - "Color_9" // "Blue"

10 - "Color_10" // "Violet"

11 - "Color_11" // "Magenta"

12 -"Color_12" // "Rose"

13 - NONE // ToggleColor {toggles across buttons 1 - 12, allowing a single-buttom interface for color)

14 - NONE // RandomColor (randomly sets hue and saturation)

20 - "SetCircadian" // TP-Link bulbs only.  Sets circadian mode.

21 - "CTemp_1" // "Incandescent")

22 - "CTemp_2" // "Soft White")

23 - "CTemp_3" // "Warm White")

24 - "CTemp_4" // "Moonlight")

25 - "CTemp_5" // "Horizon")

26 - "CTemp_6" // "Daylight")

27 - "CTemp_7" // "Electronic")

28 - "CTemp_8" // "Skylight")

29 - NONE // ToggleColorTemp (toggles across buttons 21 - 28, allowing a single-buttom interface for color temp.)
