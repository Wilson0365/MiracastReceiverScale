# Side-by-side APK identity

User requirement: copy the existing feature branch, then make a distinct TV app so installation does not replace either existing app. Base is feature/system-p2p-app-media-v1.2.13 at c18d7b5b051b53d6419dd37d3781d6577b432ce0. Change only feature/system-p2p-app-media-side-by-side.

- Set applicationId to com.weekd.miracastreceiver.systemp2p; keep Kotlin/Android namespace com.weekd.miracastreceiver for existing components and JNI names.
- Set launcher/application name to Miracast Scale SP2P; versionCode 20, versionName 1.2.13-system-p2p.
- Update this variant's adb permission command and README. Existing explicit class intents and broadcasts scoped with context.packageName route to the installed variant automatically.
- Verify the old artifact identifies as com.weekd.miracastreceiver; build tests/assembleDebug/lintDebug, then inspect the new APK's actual package, label, launcher and provider identities. Verify new and old package names differ and classes still resolve.
- Push only the copied branch and export a clearly named APK/source ZIP. Preserve the earlier feature branch and its APK artifacts. Shared network ports mean both apps may be installed, while only one receiver should run at a time.

## Verification evidence

Gradle testDebugUnitTest assembleDebug lintDebug: BUILD SUCCESSFUL, 2m 27s. 90 tests, zero failures/errors/skips; lint zero errors. aapt identified old artifact com.weekd.miracastreceiver / Miracast Receiver and new artifact com.weekd.miracastreceiver.systemp2p / Miracast Scale SP2P. New versionCode is 20; launcher remains the existing com.weekd.miracastreceiver.ui.MainActivity class. Startup Provider authority and dynamic receiver permission both use the new installation ID. apksigner verification succeeded. The old feature branch and its artifacts are preserved.
