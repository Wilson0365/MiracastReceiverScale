# Execution ledger — plan: 2026-11-10-system-p2p-app-media.md

Baseline: 3208713. Sources inspected: GPTReview.md and GPTPlan at takeoveroff 0e9fd15. User requested direct continuation. Native worktree creation failed because task starts in a non-repository home directory; git worktree fallback used in a sibling project directory. Original checkout and its untracked plan left intact.
Pre-flight: Task 1 consumes existing WfdServer signatures; Task 2 changes start() to return Boolean and updates WfdServer caller. Observer generation invalidation and setting ownership are integration review focus.
Decision: no legacy removeGroup attempt; no group identity can prove exclusive app ownership. Firmware setting value 1 is provisional until OPPO validation; preserve prior value only when still owned.

Task 1: lifecycle RED (missing coordinator), GREEN full testDebugUnitTest (BUILD SUCCESSFUL, 47s). Clean release baseline passed (36s). Active P2P implementation removed; system observer, settings lease and idempotent media lifecycle integrated.
Task 2: RED missing prefix/sequence classes and UDP start Boolean contract; GREEN full testDebugUnitTest (BUILD SUCCESSFUL, 48s), including real UDP port conflict and loopback RTSP response matching.

Independent review: three Important findings (scan/observer generation invalidation and setting lease off/on ownership). Group-loss loopback test reproduced obsolete connection before the fix: expected SocketTimeoutException but a connection was accepted. Added observation-generation and setting-lease regression tests. No Critical findings.
Ruling: post-process-death setting ownership is unverifiable; discard restoration claim and keep current platform value, so a later native-system choice is never overwritten.
Ruling: exact untranslated status/mode resources intentionally use English fallback in secondary locales; no new hardware/platform guarantee is inferred.

Final review fixes: scan generation validated before probes, after connect, and before session admission; observation generation validated inside the controller mutex; setting changes permanently relinquish a live lease. Regression names: WfdGroupLossTest.groupLossDuringCandidateReadPreventsObsoleteConnection; MiracastMediaLifecycleTest.queuedFailureFromOldObserverCannotStopReplacementAppSession / queuedFailureAfterAppToSystemIsIgnored; SystemSettingLeaseTest.externalOffOnCycleRelinquishesOwnershipPermanently / failedRestorationRetainsRetryObligation / processRestartNeverClaimsAnUnverifiableOldWrite.
Final verification: testDebugUnitTest assembleDebug lintDebug -> BUILD SUCCESSFUL (1m 30s), 90 tests, zero failures/errors/skips. ARM64, ARM32 and universal debug APK generated. Intentional lint declarations document English locale fallback and adb-grantable secure setting; Media3 API opt-in and AppCompat tint were corrected rather than globally disabling lint.
Publication blocked externally: configured corporate proxy DNS unavailable; direct GitHub HTTPS times out. Feature branch committed locally, source archive and Git bundle exported; no source branch was merged or pushed.
