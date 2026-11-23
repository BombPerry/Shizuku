<div align="center">
   
# Shizuku

What this adds compared to thedjchi's 13.7.0 version:

moved the app from credential encrypted storage to device encrypted storage.
This allows the app to start pre-unlock after a reboot and start the server within about 3 seconds after connecting to a trusted WiFi network.

Additionally a "auto pairing pilot" for lazy first time pairing that is very experimental and can be easily interrupted and probably doesn't work fully on your device.



Update Notes:

v3:
Added Dimzuku's faster port bombarding when connecting to WiFi instead of waiting for mDNS, with an additional function to scan the device specific port range and only use odd ports in the range on reboot.
Also added Dimzuku's boot log menu button.

Removed and edited most thedjchi links and naming references according to this being it's own fork.
