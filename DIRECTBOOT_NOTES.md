# Pre-unlock (Direct Boot) start — findings

Goal: start Shizuku's server on device boot **before** the phone is unlocked.

## Added files
- `manager/.../receiver/LockedBootReceiver.kt` — directBootAware receiver,
  listens for `LOCKED_BOOT_COMPLETED` (pre-unlock) and `BOOT_COMPLETED`
  (post-unlock fallback). Logs every delivery to a device-encrypted file
  **before** the launch-mode check so a fired-but-skipped receiver is visible.
- `manager/.../service/DirectBootStartService.kt` — directBootAware foreground
  service reproducing `AdbStartWorker` **without** WorkManager (WorkManager /
  JobScheduler do not dispatch before first unlock). Logs each stage
  (STAGE1 enable adb → STAGE2 mDNS → STAGE3 connect+key → STAGE4 binder) to
  the same file.
- Manifest: both components registered as `directBootAware="true"`.

## Log file
Device-encrypted, survives reboot / first-unlock / adb auth resets:
`/data/user_de/0/<applicationId>/files/directboot_log.txt`
Pull after unlock:
`adb shell run-as <applicationId> cat files/directboot_log.txt`

## Conclusion (Asus ROG Phone 7 — Nov 2026)
Pre-unlock start is **NOT possible** on this device. Root cause is below the app
layer, in `adbd`:

- `adbd` does **not** bring up its wireless/TLS listener until after first unlock.
  Verified by LAN port scan: **no** adb port open while locked; two ports open
  after unlock.
- Writing `adb_wifi_enabled=1` pre-unlock (via WRITE_SECURE_SETTINGS, which the
  app has) does **not** summon the listener early.
- Classic `adb tcpip` (plaintext TCP) does not survive reboot without
  `persist.adb.tcp.port`; that is a system-owned property an unrooted app cannot
  set (SELinux `untrusted_app` domain has no write access). `service.adb.tcp.port`
  is only the non-persistent runtime copy, wiped on reboot.

What WAS proven to work pre-unlock (so the ON-DEVICE half is fine; only the
network listener is the blocker):
- Starting the server over ADB from a PC while the phone was locked succeeded,
  then survived disconnecting USB + WiFi before unlock. So adbd authorizes the
  key, launches the server binary, and the server detaches — all pre-unlock.
  The *only* missing piece is a reachable listener before unlock.

## Realistic fallback
Start at the instant of unlock via a directBootAware `ACTION_USER_UNLOCKED`
receiver → existing `AdbStartWorker`. Earliest reliably-working point on this
device; in practice indistinguishable from "already running when you unlock."

Only root changes the verdict (root path starts the server directly, no ADB
listener involved).
