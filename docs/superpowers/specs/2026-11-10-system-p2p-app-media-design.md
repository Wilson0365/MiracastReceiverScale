# System P2P / App Media

User-authorized migration: base `release/v1.2.13` at `3208713c738fc62acc3bf12e140825575ee885ce`, reference `takeoveroff` at `0e9fd15c4b19fe275a3a511a78adce4ba60f8064`. Follow the final two-mode definition in GPTPlan, superseding its earlier three-mode proposals. Work only on `feature/system-p2p-app-media-v1.2.13`; publish that branch without merging either source branch.

APP: the platform owns P2P; this app observes connection information, connects to Source TCP 7236, binds UDP 19000 before advertising it, handles RTSP/RTP, demuxes and decodes into the existing custom PlayerActivity with scale controls. SYSTEM: this app stops its Miracast media and observer; the platform handles native Miracast. AirPlay/DLNA/Cast retain the release baseline.

## Approaches
Copying takeoveroff would discard v1.2.13 shutdown fixes and would not implement GPTPlan. A three-mode hybrid would contradict the final plan. Chosen: reuse the v1.2.13 media implementation and replace active P2P management with a read-only observer and serialized media lifecycle.

## Constraints and decisions
- Never create, connect, discover, advertise WFD IE, purge, or remove system P2P groups in either mode, recovery, or shutdown. There is no reliable proof that an existing group belongs to this process, so no legacy group deletion is attempted.
- Preserve APP/SYSTEM saved mode names. APP no longer means ownership of P2P.
- APP enables wifi_display_on with value 1 (takeoveroff experiment), recording the prior value before writing. Restore only a value written by this session that has not subsequently been changed externally. Do not retry writes against the system. Keep the adapter isolated because firmware behavior requires device validation.
- Bind failures abort before RTSP; only a matching successful SETUP leads to PLAY, and only a matching successful PLAY starts the player.
- Network group loss and handshake failure close media and wait/rescan, without manipulating P2P. A remote Miracast session ending closes its player while keeping reception available; an explicit user exit still stops all receivers.
- Retain minSdk 21 and existing native ABI builds. Branch creates a separately reviewable project; keep the application ID for normal upgrade compatibility and mark versionName as 1.2.13-system-p2p.

## Verification
Lifecycle tests exercise idempotence, clean restart, stop failures, and failed handoff. Loopback UDP/TCP tests exercise socket binding and RTSP response ordering/rejections. Test RTP sequence wrap, duplicates and reordering; scan real IPv4 prefixes and multiple P2P interfaces. Run full unit suite and assembleDebug, check diffs, and confirm remote source branch SHAs are unchanged. OPPO media ownership and Windows WFD advertising are manual hardware acceptance criteria, not inferred from a successful APK build.
