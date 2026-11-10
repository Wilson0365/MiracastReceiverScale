# Receiver exit and release implementation plan

> Execute inline with superpowers:executing-plans; review the final diff independently.

**Goal:** TV Back stops all receivers and persists SYSTEM. DLNA sender disconnect remains ready; Miracast/AirPlay screen-mirror sender disconnect exits all receivers. Publish the tested Android release to Wilson0365/MiracastReceiverScale.

**Architecture:** Service owns an idempotent, awaitable exit. Controller serializes teardown, preserves dirty evidence on failure, and prevents queued restarts. AirPlay local termination explicitly clears playback independently of RTSP socket state.

**Tech stack:** Kotlin, Android API 21+, coroutines, JUnit, Gradle.

**Approved requirements:** Hide the Android connection row in all layouts; enable its internal strategy including upgrades and boot-start. All protocols stop all services on TV Back. User correction: DLNA remains ready after sender stop; screen mirroring does not. Preserve mirror-to-URL handoff. Keep user mode separate from resource-cleanup evidence.

## Tasks
- [x] Add regression tests for local AirPlay stop with no control socket, duplicate WFD stop notification, cleanup-result aggregation, and exit ordering. Run them failing before implementation.
- [x] AirPlayReceiver/RtspHandler: explicitly stop local media, reset lifecycle, reject late media starts during receiver stop, close client sockets while preserving listener for session-only disconnect.
- [x] WifiDirectManager/MiracastController/MiracastMode: return checked cleanup results, disable owned WFD advertisement, clear Root IE only when tracked, await serialized shutdown, retain sessionOpen on failure.
- [x] PlayerActivity/CastReceiverService: send one explicit local exit, persist SYSTEM before cleanup, prevent startup/rebuild during exit, stop all services before stopSelf; DLNA sender stop closes playback, mirror sender stop exits all receivers. Remove onDestroy disconnect.
- [x] Run full JVM suite and release build; independently review the final diff. Increment version, sign APKs with the existing application certificate, verify signatures and package metadata.
- [ ] Commit and push release branch/tag to the requested repository; publish release with signed APKs and verification limitations.

## Review focus
Local URL video after RTSP socket closure; queued setup callbacks during exit; repeat Back/STOP; service stop before network initialization; P2P timeout or root failure; immediate reopen while old controller is cleaning; DLNA sender stop keeps service, mirror sender stop exits; Android 21 APIs.

## Execution record
- Baseline Kotlin compile passed; existing ScaleManager tests referenced removed transform APIs, so tests were updated to the current pan/state interface.
- WFD regression reproduced a duplicate callback before the fix. New lifecycle and cleanup APIs were absent before their regression tests and now pass.
- Independent review findings: fixed API 24 collection use; closed playback admission at exit; added teardown-only initialization; added AirPlay generations; preserved manual reopen; waited on the Miracast audio worker; canceled in-progress controller startup.
- Ruling: standalone AirPlay URL video and Google Cast sender stop retain existing standby behavior; the user's correction specifically concerns screen mirroring. Mirror-to-URL handoff stays seamless.
- Full Lint reports pre-existing translation, Media3 opt-in and styling issues. New teardown permission handling and intentional task-clearing Back handling are addressed. Full Lint is not claimed passing.
- Release 1.2.13 (versionCode 18): 65 JVM tests passed; assembleRelease including lintVitalRelease passed. ARM64, ARMv7 and universal APKs verified with v1/v2/v3 signatures using the previous local certificate, with SHA-256 checksums generated. No TV device was available for casting verification.
