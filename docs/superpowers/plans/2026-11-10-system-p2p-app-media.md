# System P2P / App Media Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. Steps use checkbox syntax for tracking.

**Goal:** Implement takeoveroff GPTPlan final APP/SYSTEM media modes on the release/v1.2.13 baseline.
**Architecture:** Serialized MiracastController delegates to a testable media lifecycle coordinator, a read-only WifiP2pObserver, and a compare-before-restore SystemP2pHandoff. Retain WfdServer and the release media/player path.
**Tech Stack:** Kotlin, Android Wi-Fi P2P, coroutines, Java sockets, JUnit, Gradle.
**Spec:** ../specs/2026-11-10-system-p2p-app-media-design.md

## Global Constraints
- Only feature/system-p2p-app-media-v1.2.13 changes; no merge to source branches.
- P2P always system managed; no active P2P operations or root calls in runtime paths.
- Two modes APP and SYSTEM; minSdk 21; preserve scale controls and release cleanup.

## Review Focus
- Late observer callbacks after APP to SYSTEM must not restart reception.
- Failed UDP bind must never advertise an unavailable port.
- Delayed OPTIONS/SETUP replies must never count as PLAY acknowledgements.
- System setting changes during APP mode must not be overwritten on exit.
- A group disappearing while scanning or streaming must close obsolete sockets without removing any group.

### Task 1: Observer and media lifecycle
**Files:** MiracastController.kt, WifiP2pObserver.kt, SystemP2pHandoff.kt, MiracastMediaLifecycle.kt, WifiDisplaySetting.kt, MiracastMode.kt, MiracastMediaLifecycleTest.kt.
**Interfaces:** lifecycle enterApp(force: Boolean): Boolean / enterSystem(): Boolean; observer start()/stop()/refresh(), connection callbacks; WfdServer start()/stopAndAwait().
- [x] Write lifecycle tests for repeated mode entry, forced restart, stop/handoff failure and retry. Run testDebugUnitTest and observe expected missing-feature failure.
- [x] Implement system-only observation and guarded setting handoff; replace active P2P controller paths. Run full suite.
- [x] Integrate service and UI, remove active P2P setting/copy, preserve explicit exit and make remote disconnect resumable. Commit task with progress evidence.

### Task 2: Media protocol reliability
**Files:** RtpReceiver.kt, WfdServer.kt, WfdSessionHandler.kt, RtpSequenceTracker.kt, P2pSubnet.kt and corresponding tests.
**Interfaces:** RtpReceiver.start(): Boolean; matching RTSP CSeq state; WfdServer.onGroupDropped(); bounded IPv4 candidates.
- [x] Write loopback and sequence/prefix regression tests; run to establish failure.
- [x] Confirm bind before creating RTSP handler; match successful response CSeq; count forward gaps and separately count late/duplicate packets; scan real prefixes and exclude local address.
- [x] Run full suite and commit with progress evidence.

### Task 3: Build, review and publish
**Files:** README.md, app/build.gradle.kts, source GPTPlan/GPTReview.md, acceptance documentation.
- [x] Record architecture, setting adapter limits, provenance and hardware validation commands; identify feature build in versionName.
- [x] Run testDebugUnitTest assembleDebug lintDebug, review complete diff with independent reviewer, fix important findings and reverify.
- [x] Push only the new feature branch, verify remote source SHAs and report APK locations and remaining hardware checks.
