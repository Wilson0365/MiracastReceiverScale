# Miracast Scale SP2P: System P2P / App Media

This side-by-side app is built on feature/system-p2p-app-media-side-by-side, copied from the original feature branch at c18d7b5. Its independent package ID lets it coexist with the original Miracast Receiver on the same TV. The earlier feature branch is preserved.

Feature project based on [release/v1.2.13](https://github.com/Wilson0365/MiracastReceiverScale/tree/release/v1.2.13), implementing the final two-mode design from [takeoveroff GPTPlan](GPTPlan) and [GPTReview](GPTReview.md).

- **APP mode (switch on):** Android manages P2P discovery, negotiation and groups. This app observes that network, actively connects to Source TCP 7236, binds UDP 19000 before RTSP, receives and decodes the stream, and renders in its own PlayerActivity. The existing scale, crop and rotation controls remain available.
- **SYSTEM mode (switch off):** this app stops its Miracast media and observer. Native system Miracast handles playback. Other receivers (AirPlay, DLNA, experimental Google Cast) retain the v1.2.13 implementation.

The app never creates, connects, discovers, removes or rebuilds P2P groups, and never injects WFD IE. Disconnect/recovery closes only its media sockets and waits for the system network. Long-pressing the mode switch restarts app media without purging system P2P. Explicit exit still stops all receivers; a remote Miracast disconnect leaves reception available for the next session.

## Firmware behavior

APP mode requests wifi_display_on=1, matching the takeoveroff experiment. This is a firmware setting, **not** proof of P2P or media ownership. If already 1, no write permission is needed. Otherwise grant the existing app permission:

~~~sh
adb shell pm grant com.weekd.miracastreceiver.systemp2p android.permission.WRITE_SECURE_SETTINGS
~~~

The prior setting is persisted before writing, and restored on release only if the current value still matches this session's write. External changes permanently relinquish the setting lease, including off/on cycles. After process death, the app cannot prove the old write stayed owned, so it discards that restoration claim and leaves the current platform setting unchanged. No unowned or legacy P2P group is deleted. If upgrading from a version that actively created groups, stop that old app first; this feature deliberately does not guess which system group is safe to remove.

OPPO/system firmware must establish P2P **without consuming the Source RTSP session before this app connects**. That hardware condition remains to be verified. Windows discovery also depends on the system advertising a compatible WFD Sink IE; a successful APK build cannot prove it.

## Build

Requirements: Android SDK 36, JDK 17, CMake 3.22.1 and installed Android NDK. Configure local.properties for your SDK and Gradle's JDK as needed.

~~~powershell
.\gradlew.bat testDebugUnitTest assembleDebug
~~~

The application ID is com.weekd.miracastreceiver.systemp2p and the TV launcher name is Miracast Scale SP2P. It installs separately from com.weekd.miracastreceiver, with separate settings and permissions. VersionName is 1.2.13-system-p2p and versionCode is 20. Both apps can be installed; run only one receiving service at a time because they use the same media ports. Disable auto-start on boot in one app when testing them side by side.

## Device acceptance

1. Select SYSTEM: app RTSP/RTP are stopped; verify native system mirroring.
2. Select APP and connect from OPPO. Confirm logs in order: system P2P group/GO, interface IP, app Source:7236 connection, matching PLAY, first RTP, decoder output to custom PlayerActivity.
3. Verify the app PID owns Source TCP 7236 and local UDP 19000, and scale controls change the app-rendered picture.
4. Stop from the phone, reconnect repeatedly, toggle APP/SYSTEM and long-press restart. Media must recover without app createGroup/connect/discoverPeers/removeGroup calls.
5. Exit via TV back/notification: no app media socket/audio remains; the system P2P network is not removed.
6. Change wifi_display_on externally during APP mode; app shutdown must preserve that external value.

~~~sh
adb shell ss -tnp
adb shell ss -unap
adb logcat -v time
~~~

Filter for Miracast, WFD, RTP and VideoDecoder. On restricted firmware PID socket visibility may require device debugging privileges.

Secondary locales fall back to English for receiver status/mode copy; English and Simplified Chinese are supplied for the new feature. Existing Media3 API use is explicitly opted in, the adb-grantable secure-settings declaration is documented for lint, and AppCompat icons use app:tint.

## Provenance and review

Base: 3208713c738fc62acc3bf12e140825575ee885ce. Reference takeoveroff: 0e9fd15c4b19fe275a3a511a78adce4ba60f8064. Neither source branch is changed. Earlier three-mode proposals in GPTPlan are historical; its final APP/SYSTEM media ownership definition is the implemented specification.

[Design](docs/superpowers/specs/2026-11-10-system-p2p-app-media-design.md) / [Implementation plan](docs/superpowers/plans/2026-11-10-system-p2p-app-media.md) / [Execution evidence](docs/superpowers/plans/2026-11-10-system-p2p-app-media-progress.md).
