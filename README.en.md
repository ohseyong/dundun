# DunDun

An Android app for when your laptop can't use your hotspot because the tethering allowance ran out. It adds `dun` to your APN so the hotspot goes out on your remaining regular data. It all happens on the phone; you don't need a PC or root.

The name comes from `dun`, the APN type it adds.

[한국어](README.md) · [Download the latest APK](https://github.com/ohseyong/dundun/releases/latest) · [Website](https://seyong.me/dundun/en/)

<p>
  <img src="docs/screenshots/en-steps.png" width="250" alt="Setup steps">
  <img src="docs/screenshots/en-code.png" width="250" alt="Code notification over the pairing dialog">
  <img src="docs/screenshots/en-done.png" width="250" alt="Applied, with APN details">
</p>

## When to use it

- Your plan says unlimited, but the hotspot hits a tethering limit when you connect a laptop
- Your carrier's app says the tethering allowance is used up
- The hotspot turns on, but connected devices get no internet
- You're throttled and still want to use the hotspot at that speed

This used to mean installing DunUnlocker and Shizuku, then pairing wireless debugging and editing the APN across several screens. DunDun does it in one app, in four steps.

## Install

Download `dundun-*.apk` from [Releases](https://github.com/ohseyong/dundun/releases/latest) and open it on your phone. If it asks about installing unknown apps, allow it. You need Android 13 or later.

## How to use

1. Turn on Developer options. Tap [Open About phone] in the app, then tap Build number 7 times.
2. Turn on Wireless debugging. Tap [Open Wireless debugging], switch it on, and tap Allow if asked. You need Wi-Fi, but it doesn't need internet.
3. Tap [Start code entry] and open "Pair device with pairing code". Type the 6 digits into the notification at the top.
4. The app changes the APN and turns wireless debugging off. When you see "Applied", turn on your hotspot.

You only do this once. To apply again or undo, turn on wireless debugging and tap the button. You won't need a new code.

<details>
<summary>Step-by-step screenshots (11)</summary>

Taken on an emulator (stock Android 15) set to Korean. Galaxy menus look a little different, but the order is the same. The blue box is where to tap.

**1. Turn on Developer options**

<table>
<tr><td width="33%" valign="top"><img src="docs/tutorial/tut-01.webp" width="220" alt="Open the app and tap [Open About phone]."><br>Open the app and tap [Open About phone].</td><td width="33%" valign="top"><img src="docs/tutorial/tut-02.webp" width="220" alt="Tap Build number 7 times. On Galaxy it is inside Software information."><br>Tap Build number 7 times. On Galaxy it is inside Software information.</td></tr>
</table>

**2. Turn on Wireless debugging**

<table>
<tr><td width="33%" valign="top"><img src="docs/tutorial/tut-03.webp" width="220" alt="Back in the app, tap [Open Wireless debugging]."><br>Back in the app, tap [Open Wireless debugging].</td><td width="33%" valign="top"><img src="docs/tutorial/tut-04.webp" width="220" alt="In Developer options, open Wireless debugging."><br>In Developer options, open Wireless debugging.</td><td width="33%" valign="top"><img src="docs/tutorial/tut-05.webp" width="220" alt="Turn on Use wireless debugging."><br>Turn on Use wireless debugging.</td></tr>
<tr><td width="33%" valign="top"><img src="docs/tutorial/tut-06.webp" width="220" alt="Tap Allow. On Wi-Fi you use a lot, tick Always allow."><br>Tap Allow. On Wi-Fi you use a lot, tick Always allow.</td></tr>
</table>

**3. Enter the code**

<table>
<tr><td width="33%" valign="top"><img src="docs/tutorial/tut-07.webp" width="220" alt="Back in the app, tap [Start code entry]."><br>Back in the app, tap [Start code entry].</td><td width="33%" valign="top"><img src="docs/tutorial/tut-08.webp" width="220" alt="When the notification shows up, tap Pair device with pairing code."><br>When the notification shows up, tap Pair device with pairing code.</td><td width="33%" valign="top"><img src="docs/tutorial/tut-09.webp" width="220" alt="When the 6 digits appear, tap [Enter code] in the notification. Leave the pairing dialog open."><br>When the 6 digits appear, tap [Enter code] in the notification. Leave the pairing dialog open.</td></tr>
<tr><td width="33%" valign="top"><img src="docs/tutorial/tut-10.webp" width="220" alt="Type the digits and send."><br>Type the digits and send.</td></tr>
</table>

**4. Done**

<table>
<tr><td width="33%" valign="top"><img src="docs/tutorial/tut-11.webp" width="220" alt="When you see Applied, you are done. Turn on the hotspot from [Open hotspot settings]."><br>When you see Applied, you are done. Turn on the hotspot from [Open hotspot settings].</td></tr>
</table>

</details>

## Two SIMs

Both SIMs get `dun` and both use that APN as default, so switching your data SIM doesn't undo anything. Restore also works per SIM and leaves the other SIM alone.

## Checking the APN

Go to Settings → Connections → Mobile networks → Access Point Names. The selected APN is the one DunDun changed; open it and the APN type ends with `dun`.

Depending on the phone, `dun` is added to your existing APN or a second APN with the same name shows up. If you can't tell them apart, look for `dun` in the type. Carrier APNs like "Tethering" are left as they are. The app's done screen also lists the APN name and type for each SIM.

## Also

- The top of the app shows whether it's applied right now, so you notice if a carrier update undoes it.
- New versions download from GitHub and install on their own. You allow it once, and you can turn it off at the bottom of the app.
- Available in 12 languages, including English, Korean, Japanese, Chinese and Spanish.

## Safety

- All the code is in this repo. Read it or build it yourself.
- There is no server behind it and no ads or analytics in it. The only thing the app talks to online is GitHub, when it checks for a new version.
- Wireless debugging is used only to change the APN, and the app turns it off right after. Some banking apps won't open while it's on, so you don't have to go back and switch it off.
- You can turn Developer options off afterwards and the APN change stays.
- Updates only install if signed with the same key. The signing certificate SHA-256 is `4B:4B:E3:6B:A7:5E:D7:EA:5D:86:2D:23:8D:FC:C0:7A:10:0E:57:5C:19:49:75:05:D1:12:55:F9:17:F0:C1:66`. Check a downloaded APK with `apksigner verify --print-certs dundun-*.apk`; each release also lists the APK's SHA-256.

These are all the permissions it asks for.

| Permission | Used for |
|---|---|
| Internet | Check and download new versions from GitHub; connect to wireless debugging on the phone |
| Network and Wi-Fi state | Check Wi-Fi and whether it's applied right now |
| Wi-Fi multicast | Find the wireless debugging address on the phone |
| Notifications, full-screen notifications | Keep the pairing code notification at the top |
| Foreground service | Keep the app running while you enter the code and apply |
| Install packages, update without asking | Install new versions from GitHub. Asks only the first time |
| Wake lock, boot completed | Schedule the daily update check |

It doesn't ask for contacts, location, photos or files, phone, camera, or microphone. DunDun stays under Paired devices in wireless debugging; remove it if you won't apply or restore again.

## FAQ

**Does this make tethering unlimited?**
No. The hotspot uses your regular data instead of the tethering allowance. Once your regular data runs out, your plan's slow speed applies to the hotspot too.

**Will the tethering allowance stop going down?**
Usually, yes. Carriers mostly tell hotspot traffic apart by APN, and now it goes out through the same APN as normal data. If your carrier detects tethering another way, it may still count it or block it. Try a little first and check in your carrier's app.

**How is this different from NetShare?**
NetShare shares the connection through a proxy, so you set up a proxy on the laptop and some programs can't log in. DunDun only changes the phone's APN and you use the normal hotspot, with nothing to set up on the laptop.

**Only on Galaxy?**
Any phone on Android 13 or later. Galaxy phones on One UI 7+ hit this problem a lot, so it was tried there first, but APNs are standard Android.

**What about One UI or carrier updates?**
They can undo or block it. The top of the app shows whether it's applied, so re-apply if it was undone. If an update blocks it entirely, please open an [issue](https://github.com/ohseyong/dundun/issues).

**Can I undo it?**
Yes. Restore original puts the APN type and default APN back, on both SIMs if you have two.

## Notes

Using the hotspot past your tethering allowance may break your carrier's terms, and your line could be restricted.

The full flow (pairing, apply, restore) was tested on an Android 15 emulator. Real phones and carrier lines can behave differently; please share what you see in [Issues](https://github.com/ohseyong/dundun/issues).

## How it works

1. The app pairs and connects to the phone's wireless debugging with [libadb-android](https://github.com/MuntashirAkon/libadb-android).
2. From that shell it starts its own worker (`ApnBroker`) with `am instrument --no-restart`.
3. The worker borrows the shell's `WRITE_APN_SETTINGS` permission (`adoptShellPermissionIdentity`), adds `dun` to the default APN of each active SIM, and sets that APN as default.
4. It turns wireless debugging off with `settings put global adb_wifi_enabled 0`.

Restore uses the per-SIM record saved when applying (original type and original default APN).

## Build

```bash
JAVA_HOME=/path/to/jdk-17-or-later ./gradlew :app:assembleRelease
```

The APK is at `app/build/outputs/apk/release/app-release.apk`. To sign with your own key, put a `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) in the repo root. Without it the debug key is used.

## Thanks

- [DunUnlocker](https://github.com/naijun0403/DunUnlocker) found the way to add `dun` to the APN. DunDun uses the same approach with new code.
- The settings shortcuts and notification code entry came from studying [ShutterSoundZero](https://github.com/hoons1994/ShutterSoundZero).
- Built with [libadb-android](https://github.com/MuntashirAkon/libadb-android), [Conscrypt](https://github.com/google/conscrypt) and [Bouncy Castle](https://www.bouncycastle.org).

## Also from the maker: ExcelKOSPI

Services run by the person who made DunDun (in Korean).

- 📶 [Korean MVNO plan comparison](https://excelkospi.ai/mvno?utm_source=dundun&utm_medium=github&utm_campaign=readme_en_mvno)
  If you keep running out of data, a different plan might be better. It collects official MVNO plans every 6 hours so you can compare price, data, network and perks, including the price after discounts end.
- 📊 [ExcelKOSPI](https://excelkospi.ai/?utm_source=dundun&utm_medium=github&utm_campaign=readme_en_promo)
  Korean stock prices in a sheet that looks like Excel, so you can check the market at work.

## License

[Apache License 2.0](LICENSE)
