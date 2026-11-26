# Starting the server on boot, before unlock

Goal: start Shizuku's server on device boot, **before** the phone is unlocked where the
device allows it, and as soon as possible after unlock otherwise.

## How it works

- `manager/.../receiver/BootCompleteReceiver.kt` is the single boot entry point. It is
  `directBootAware` and listens for both `LOCKED_BOOT_COMPLETED` (before first unlock) and
  `BOOT_COMPLETED` (after unlock). The "Start on boot" setting toggles this receiver, so
  nothing runs when that setting is off.
- `manager/.../service/DirectBootStartService.kt` is a `directBootAware` foreground service
  that does the actual start, without WorkManager (WorkManager / JobScheduler do not run
  before first unlock):
  1. After unlock, if the server is already running (binder alive), stop: no restart. The
     full 6 s wait for that only happens if a start command was sent earlier in this boot
     (marker with `Settings.Global.BOOT_COUNT` in device-encrypted prefs); otherwise 0.5 s.
  2. Root launch mode: run the starter through `su`.
  3. ADB launch mode with a fixed TCP port: connect over loopback, no Wi-Fi or mDNS needed.
  4. ADB launch mode with wireless debugging: wait for Wi-Fi, switch `adb_wifi_enabled` on
     and watch it. If Android writes it straight back to 0, the attempt was refused (no
     Wi-Fi info yet, or untrusted network) and is retried after about 1 s. If it stays on,
     the port is found by a loopback port scan or by mDNS, whichever is first. Windows grow
     from 4 s by 2 s up to 15 s; the whole step gives up after 120 s.
  5. Before unlock, wait up to 5 s for the binder after sending the start command and log
     whether it arrived. After unlock, wait for the binder.
  - If a run is still in progress when the next boot broadcast arrives, the new request is
    ignored and the running attempt continues, so there is never a double start.
  - If an attempt fails after unlock, it hands over to the normal `AdbStartWorker` path,
    which retries with backoff and shows the "Attempt now" notification.
- The app's settings and adb key live in device-encrypted storage, so all of this can read
  them before unlock. That move alone cut boot start time from roughly 30 s to about 10 s.

## Log file

Every step is written to a device-encrypted log file, so it survives reboots, first unlock
and adb authorization resets. Read it in the app: home screen menu → **Boot log**, with Share
and Copy (IP addresses and the adb serial are masked) and Clear. The viewer comes from
Dimlos's Dimzuku fork. On debuggable builds it can also be read with:

```
adb shell run-as <applicationId> cat /data/user_de/0/<applicationId>/files/boot_start_log.txt
```

It rotates to `boot_start_log.old.txt` after 256 KB.

## Finding the port: scan and measured range

adbd opens its TLS port within milliseconds of the switch, but its mDNS announcement
appears about 1-2 s later and can fail right after boot. `service.adb.tls.port` would give
the port directly, but apps can't read it (SELinux, `adbd_prop`; confirmed: it read -1 while
adbd was listening). So `AdbPortScanner` (from Dimlos's Dimzuku fork) tries every port on
127.0.0.1 and sends each open one an adb CNXN packet; adbd answers STLS. mDNS runs alongside.

The scan order follows how the kernel picks the port when adbd binds port 0 with
`SO_REUSEADDR`: odd ports only, from the lower half of `ip_local_port_range`
(`inet_csk_find_open_port`). Order: the range measured in Settings, then ±500 around ports
found at earlier boots (`AdbPortHistory`, last 10, device-encrypted), then the default odd
lower half (32769-46883), then all other ports. Every port is still scanned; only the order
changes.

**Settings → Advanced → Wireless debugging port range** (`AdbPortRange`) finds the band:

1. It reads `ip_local_port_range`: from `/proc/sys` if the app may, otherwise with
   `sysctl net.ipv4.ip_local_port_range` through the running Shizuku server (adb shell or
   root). On the Asus ROG Phone 7, `cat` of the file is denied even from adb shell, but
   `sysctl` works: `37000 50000`. The band is then computed with the kernel's formula
   (`high++; half = low + (((high - low) >> 2) << 1)`, ports `low..half-1`, here
   37000-43499), and 20,000 binds check that every port really falls inside it.
2. If the range can't be read (Shizuku not running), it samples: bind port 0 with
   `SO_REUSEADDR` (the band) and without it (the whole range) until the lowest and highest
   port haven't changed for 50,000 binds, or 20 binds per candidate port on wider ranges,
   at most 1,000,000 per round. On a desktop Linux 6.18 kernel with the default range this
   stopped after about 140,000 and 290,000 binds, with the exact bounds, in under 1.5 s.

The result is saved and shown, with where the range came from. It matters on devices whose
manufacturer changed the range: on the ROG Phone 7 wireless debugging ports were always
odd and within 37000-44000.

**Scan odd ports only** (switch below it): a measurement shows the share of odd ports and sets
the switch accordingly (on at 99.9%+, as on Linux 4.6+); it can be changed by hand. When on,
the boot scan leaves even ports out entirely, so a full pass is half as long. mDNS still finds
the port if it is ever even.

## Why wireless debugging looked "dead" before unlock

The first tests (Asus ROG Phone 7, Nov 2026) found no wireless adb port open while
locked, which looked like adbd refusing to listen before unlock. Dimlos found the actual
cause: `AdbDebuggingManager` evaluates `adb_wifi_enabled` only at the moment the setting
changes. If the device isn't connected to a trusted Wi-Fi network at that moment, it logs
"Not connected to any wireless network. Not enabling adbwifi." and never retries. Early in a
locked boot Wi-Fi usually isn't up yet, so the setting was evaluated too early.

Waiting for Wi-Fi and then toggling `adb_wifi_enabled` 0 → 1 makes the framework
re-evaluate, and the start can then succeed before unlock.

Requirements for the wireless path:
- Wireless debugging was enabled on this network before, with "Always allow on this
  network" ticked. Otherwise Android needs a confirmation prompt, which can't be shown on
  the lock screen.
- The device connects to that Wi-Fi network before unlock (most do for ordinary saved
  networks, not always for enterprise ones).

Other notes:
- Classic `adb tcpip` does not survive a reboot without `persist.adb.tcp.port`, which an
  unrooted app can't set. `service.adb.tcp.port` is only the runtime copy and is cleared
  on reboot.
- Starting the server over adb from a PC while locked works, and the server keeps running
  after the PC and Wi-Fi are disconnected, so key authorization and the server launch both
  work before unlock.
- The server's binder reaches the app before unlock too (the `.shizuku` provider is
  directBootAware): on the ROG Phone 7 it arrived about 1.3 s after the starter finished,
  ~4 s after `LOCKED_BOOT_COMPLETED` with Wi-Fi already up.
