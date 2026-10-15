# Sill protocol notes (from reading the iOS client and Mac host)

Paths are relative to reference/sill (github.com/Saffsanity/sill).

## 1. Discovery (Bonjour / NSD)

- **Service type:** `_sill._tcp`, domain nil (that is, `local.`). Device: iOS/StreamClient.swift:463-470 and makeBrowser 942-945 (`NWBrowser(for: .bonjourWithTXTRecord(type:"_sill._tcp", domain:nil))`). Host: Host/StreamServer.swift:405-413.
- **Service name:** `Host.current().localizedName ?? "Mac"`. Bonjour may rename it to "Mac (2)" after a name clash.
- **Home port is ephemeral.** `NWListener(using: params)` binds no port (Host/StreamServer.swift:611-613). Read it from the SRV record. It is kept across Direct Wireless listener swaps but changes on every app launch.
- **Remote door port:** fixed default 7455 (Host/HostConfig.swift:90-94). It is user-configurable to 0 or 1024–65535; the CLI's `--remote` uses any free port.
- **TXT record** (Host/HostIdentity.swift:92-97), rebuilt for every registration:
  - `r` is the recognition tag: base64url (no padding) of 12 bytes, so 16 characters. The bytes are `nonce6 ‖ HMAC-SHA256(recognitionKey32, "sill-tag-v1" ‖ nonce6)[0..<6]`, with a fresh random nonce per registration (Proto/Pairing.swift:143-176).
    - The device resolves it by trying every saved Mac's 32-byte recognitionKey (iOS/SavedMacs.swift:240-243). The key arrives only in kind 20 ok.
    - The tag is a hint, not authentication; it can be replayed.
  - `p` says how the home door speaks (Proto/Pairing.swift:185-217):
    - `"1"`: TLS, paired devices only (Require pairing on, the default).
    - `"0"`: TLS, any device.
    - Absent: a plain-TCP door (CLI without `--pairing`, or pre-0.4 Sill.app).
    - Any other value, or a bare `p` with no value, means `"1"`. Keys are case-insensitive (`p` or `P`).
- **The unmodified Sill.app home door always speaks TLS.** If it has no identity, the door is closed (fail-closed).
- **Device dial decision** (iOS/DiscoveryPolicy.swift:995-1004, `homeDial`):
  - A saved Mac (tag matched) is dialed `sill/1` pinned to its key.
  - An unsaved Mac with `p=0` is dialed `sill/1` with any key. After `.ready` the session pins the key it saw (`trust(_:readyWith:)`, line 1170).
  - An unsaved Mac with `p=1`, on a tap, gets the "ask" (`sill-pair/1`).
  - A plain door: Release iOS refuses ("Update Sill"); DEBUG dials plain.
  - A row with no tag under a saved Mac's Bonjour name is dialed pinned to that saved Mac (`rowMac`, 872-877). A tag that resolves to no saved Mac is never treated as a saved Mac.
- **Network browser vs nearby browser:** the network browser never uses peer-to-peer; only the "nearby" browser does (AWDL; iOS-only, see §7).
- **Rows say "Remote"** for saved Macs not listed after 3 s (`remoteWait`/`networkFirst` = 3.0, DiscoveryPolicy 23 and 812).
- **Origin limits on the host:**
  - The home door admits only loopback, LAN (own subnets, link-local, or RFC1918/ULA private addresses) and AWDL. VPN and internet sources are refused before any byte (Host/DoorPolicy.swift:45-61, Host/OriginPolicy.swift classify).
  - The remote door admits loopback, LAN and VPN; internet only with "Allow connections from the internet".

## 2. TLS

**One builder for both doors and both connection kinds:** Proto/RemoteTLS.swift:53-81.
- TLS 1.3 only: `min = max = .TLSv13` (lines 57-58).
- Tickets and resumption disabled (60-61). Do the same on Android: use a fresh SSLContext per connection or disable session caching/tickets.
- **ALPN:**
  - `"sill/1"` is a session; `"sill-pair/1"` is pairing (26-28).
  - The server offers both forever (`serverALPNs`, 35). The client offers exactly one.
  - An unoffered ALPN fails the handshake (server -9810).
- **SNI:** the client always sends `"sill"` (38); the host ignores it.
- **Client certificates are required** on every connection, pairing included: `peer_authentication_required(true)` (64), and `DoorPolicy.trusts` returns false without a P-256 key (Host/DoorPolicy.swift:69-79).
- **Cipher suites:** not configured. Measured negotiated suite is 0x1302 (TLS_AES_256_GCM_SHA384) (comment at 19).
- **Keys:** ECDSA P-256 only on both sides. A non-P-256 leaf gives a nil fingerprint, which nothing matches (Proto/RemoteIdentity.swift:45-60).
- **Certificates** (Proto/RemoteIdentity.swift:93-123):
  - Self-signed X.509 v3, no extensions, 16-byte random serial (top bit cleared).
  - Issuer = subject = `CN=<16 random hex chars>` (UTF8String).
  - notBefore UTCTime `260101000000Z`, notAfter GeneralizedTime `99991231235959Z`, ecdsa-with-SHA256.
  - Rebuilt at every launch from the stored key; only the key is pinned.
  - The Python test client uses an ordinary `openssl req -x509` cert (with extensions) against the real host (Scripts/sillclient.py:278-300), so any valid P-256 cert works. On Android, the AndroidKeyStore auto-generated self-signed cert is fine.
- **Fingerprint (the pin):** `SHA-256(SubjectPublicKeyInfo DER)` of the leaf (Proto/RemoteIdentity.swift:33-43).
  - SPKI = `3059301306072a8648ce3d020106082a8648ce3d030107034200 ‖ 04‖X‖Y` (named curve, uncompressed point; prefix also at sillclient.py:264).
  - In Kotlin, `MessageDigest("SHA-256").digest(cert.publicKey.encoded)` should equal this. Verify that it produces the named-curve form.
  - The pin is not a hash of the cert DER or of the raw point.
- **No trust evaluation:** neither side evaluates chains, names or dates. The verify block is the whole of trust (Proto/RemoteTLS.swift:70-79).
- **PSK:** none.
- **What the Mac's verify block decides in-handshake** (Host/Door.swift:149-151, DoorPolicy.trusts):
  - `sill/1`: at home, paired OR Require pairing off; at the remote door, paired only.
  - `sill-pair/1`: at home, always; at the remote door, only while a Mac-opened ("forRemote") pairing window is open.
- **Client-side failure as seen by the device:** with TLS 1.3 the client reaches "ready", then its first read fails with -9825 (the Mac refused our cert; comment at RemoteTLS.swift:20-23). On Android expect an SSLException on read after a "successful" handshake. The Mac sends nothing in that case.
- **Device verify** (iOS/StreamClient+Home.swift:33-41): with `pin == nil`, accept any P-256 key; otherwise require `fp == pin` (or a key from `alsoAccept`). A refused pin is -9808 on the device.
- **TCP options:**
  - Remote door dialing: `noDelay`, keepalive idle 5 / interval 2 / count 3, `connectionDropTime 15`, `connectionTimeout 10` (RemoteTLS.swift:93-103).
  - Home door from the device: `noDelay` only, plus `connectionTimeout 10` for pairing dials (iOS/StreamClient+Home.swift:47-56).
  - Host home listener: noDelay plus keepalive 5/2/3 (Host/StreamServer.swift:593-600).
  - Service class `.interactiveVideo` (WMM video). Android equivalent is setting the socket's DSCP/TrafficClass.
- **Admission limits on the host** (Host/Door.swift:55-59):
  - At most 8 pending connections in total and 2 per source.
  - 10 s from accept to admission.
  - 5 refusals in 60 s give a 300 s backoff.
- **What an unpaired device can do:**
  - At a `p=1` home door: only `sill-pair/1` with one kind 19 (ask, qr, code or cancel). `sill/1` is refused in the handshake.
  - At a `p=0` home door: a full session (unpaired), plus kind 21 "pairingWanted".
  - At the remote door: nothing, except `sill-pair/1` while the Mac's user has a pairing window open.

## 3. Session start sequence

1. TLS handshake with ALPN `sill/1`, pinned as above.
2. **The device's first message is kind 23 Hello**, written before anything else on that connection (iOS/StreamClient.swift:1742-1768, 1537). Example: `{"appVersion":"0.5.1","build":"4","protocol":1,"device":"iPad (iPad14,1)"}` (Proto/Compatibility.swift:103-117).
   - `device` is `"<UIDevice.name> (<utsname.machine>)"` (iOS/DiagnosticsHUD.swift:46-63).
   - Today's floor is `"0"` (Host/DeviceGate.swift:21), so nothing waits on the hello.
   - If the floor is ever raised, the host reads the first message within 2 s (24): anything other than a hello whose `appVersion` is at or above the floor gets kind 22 `{"reason":"update",...,"reconnect":false}` and is closed (Host/StreamServer.swift:1095-1140).
   - Hello is never sent on a `sill-pair/1` connection.
3. **The host registers the client and sends the catalog in this order** (Host/StreamCoordinator.swift:353-377 and sendCatalog 2330-2355):
   1. kind 2 WindowList (`macName, windows, active, launchID, hostVersion, protocol:1, gestures:1, clipboard:1|nil`)
   2. kind 16 HostSettingsState
   3. kind 18 SignedMacInfo (hosts with an identity, which is always the case for Sill.app)
   4. kind 4 AppIcon, once per icon (`u16 BE bundleID length ‖ bundleID ‖ PNG`)
   5. kind 5 AppList (JSON `[AppInfo]`)
   6. kind 14 CursorShape if known
   Then it calls `encoder.requestKeyframe()`.
   - Thumbnails (kind 3: `u32 BE windowID ‖ JPEG`) are broadcast as the catalog polls.
   - kind 13 ticks (empty payload) arrive every 30 ms while streaming or within 3 s of input (Host/StreamServer.swift:211-254). TLS clients skip a tick right after any other send.
   - kind 26 MacPointer arrives when the Mac or another device moves the pointer.
4. **The device's handling of the first window list** (iOS/StreamClient.swift:3617-3717):
   - A TLS session counts as connected only now (`homeSessionReady`, iOS/StreamClient+Home.swift:336). A deadline of 10 s from `.ready` applies (365-374, `RemoteDialPolicy.firstListDeadline`).
   - If `active == .none`, it sends kind 6 `{"desktop":{}}`; repeats are spaced at least 10 s apart (DiscoveryPolicy.autoDesktop).
   - It sends a menu subscription, kind 27 FetchMenu with only a token (4139-4141).
   - If `list.clipboard >= 1` and the user's switch is on, it sends kind 32 `{}`.
5. **Viewport** (kind 9): sent when `connected` becomes true and on panel or scale changes, debounced 0.25 s (iOS/StreamScreen.swift:348-386, 457-476).
6. **Pings and stats:** pings start at `.ready` (every 0.25 s). kind 12 ClientStats is sent once a second while connected.
7. **Video starts:** kind 0 ParameterSets, then kind 1 keyframe, then deltas.

**Frame (14-byte header, big-endian)** (Proto/StreamMessage.swift:4, 115-134):

| Bytes | Field |
|---|---|
| 0 | kind (u8) |
| 1–8 | timestamp (IEEE754 Double bits) |
| 9 | isKeyframe (1/0) |
| 10–13 | payload length (u32) |

- Payload caps: client→host 1 MiB, pairing 4096, frames 32 MiB, other host messages 4 MiB (95-101).
- JSON is Swift's default `JSONEncoder`, so nil optionals are omitted.
- Enums with associated values use synthesized Codable: `{"window":{"_0":5}}`, `{"desktop":{}}`, `{"none":{}}`, `{"pointer":{"_0":"leftDown","x":0.5,"y":0.5}}` (confirmed in sillclient.py:160-162, 644).

## 4. Video

- **ParameterSets.encoded()** (Proto/StreamMessage.swift:147-157): `u8 nalUnitHeaderLength ‖ u8 count ‖ { u16 BE len ‖ bytes }×count`.
  - The sets are VPS, SPS, PPS without start codes, taken from `CMVideoFormatDescriptionGetHEVCParameterSetAtIndex` (Host/HEVCEncoder.swift:525-541).
  - `nalUnitHeaderLength` is normally 4.
  - The kind 0 header has isKeyframe=1 (Host/StreamCoordinator.swift:1465-1471).
- **When kind 0 is sent:** broadcast before every keyframe. A newly joined client gets the cached kind 0 immediately before its first keyframe (Host/StreamServer.swift:1467-1475).
- **kind 1 payload:** one HEVC access unit, copied straight from the encoder's CMBlockBuffer (Host/HEVCEncoder.swift:504-523). That is hvcC/AVCC style: each NAL prefixed by a big-endian length of `nalUnitHeaderLength` bytes (4).
- **Encoder settings** (Host/HEVCEncoder.swift:162-173): Main profile, real-time, no B-frames, `MaxKeyFrameInterval = fps*4` (a safety IDR every 4 s while frames flow).
- **No keyframe-request message exists from the device.** The host forces keyframes on connect, after it drops a delta for a client (home: when >2 messages are in flight, 1476-1483; remote: byte-budget pacing), and on pipeline restarts (source switch, resize, fps or settings change).
- **iOS decoder feeding** (iOS/HEVCDisplayView.swift):
  - `apply` (230-265): a new or changed kind 0 builds a format description, flushes the layer and waits for a keyframe. Identical repeats are ignored.
  - `enqueue` (280-319): frames are dropped until the first keyframe; each frame becomes one CMSampleBuffer with invalid timing and `DisplayImmediately=true`. On decoder failure it flushes and waits for the next keyframe.
- **Timestamp:** host wall clock in seconds since 1970 at encoder output. The device uses it only for frame age (`now − ts`, clamped ≥0, iOS/StreamClient.swift:3608-3616); it never paces display by it.
- **Android port:** for MediaCodec "video/hevc", convert to Annex-B by replacing each 4-byte length with `00 00 00 01`. Pass VPS+SPS+PPS (Annex-B) as csd-0, or as a CODEC_CONFIG buffer. Render at once (no PTS pacing). Reconfigure or flush on a parameter-set change; on error, wait for the next keyframe. Video size comes from the SPS.

## 5. Viewport and coordinates

- **Viewport** (Proto/Viewport.swift:11-27; iOS/StreamScreen.swift:470-473, iOS/StreamClient+Viewport.swift):
  - `{"width":W,"height":H,"scale":S?,"localCursor":true?,"fps":N}`.
  - W×H is the stream panel's size in iOS points; on Android, send dp.
  - `scale` is nil (leave the Mac window alone) or the Aa value, 0.5–1.5 in 0.25 steps. The host resizes the Mac window to panel÷scale Mac points.
  - `fps` is the screen's max refresh (≥30), capped at 60 in Low Power Mode or for a remote/away route (`wantedFPS`, 63-67).
  - `localCursor` is legacy; the host always strips the cursor from the video.
  - It is re-sent after a move or reconnect.
- **Input coordinates:** fractions 0…1 of the streamed video frame (Proto/Input.swift:3-5).
  - The video rect is the aspect-fit (letterboxed) rect of the frame in the view (iOS/HEVCDisplayView.swift:59-66).
  - Taps in the letterbox are dropped; moves, drags and scrolls clamp to the edge (iOS/InputOverlay.swift:154-164).
- **Direct-touch mode** (iOS/InputOverlay.swift):
  - Tap: `move, leftDown, leftUp` at the point (185-190). There is no double-tap recognizer; the Mac's own click timing makes double-clicks.
  - Long-press 0.45 s: `move, rightDown, rightUp` (193-201).
  - 1–2 finger pan scrolls (238-286): `scrollGesture(began,x,y)`, then `scroll(x,y,dx,dy)` with dx/dy as deltas ÷ video-rect size in natural sign (finger down ⇒ +dy), then `scrollGesture(ended)`.
  - Momentum after a lift faster than 150 pt/s (ScrollMomentum 1077-1153): `momentumBegan`, then steps at 60 Hz with velocity × 0.96 per 1/60 s tick, stopping below 0.3 pt per tick, then `momentumEnded`.
  - Pencil, trackpad or mouse touches act as the mouse (471-509): move, leftDown (or rightDown for a secondary button), moves while down, leftUp. Hover sends `move` (439-449).
  - Pointer moves are coalesced to at most one per 8 ms; any other event first flushes the pending move (iOS/StreamClient.swift:3259-3297).
- **Text and keys** (InputOverlay 545-564):
  - Software keyboard: `{"text":{"_0":"…"}}`. Backspace is `"\u{8}"`; `"\n"` and `"\t"` become keys.
  - With ⌃/⌥/⌘ latched, a character is sent as a `key` (HID usage) chord instead.
  - `key(hidUsage:UInt16, down:Bool, modifiers:UInt64)`, JSON `{"key":{"hidUsage":44,"down":true,"modifiers":1048576}}`.
  - Modifier bits: shift 1<<17, control 1<<18, option 1<<19, command 1<<20 (iOS/KeyChords.swift:18-24).
  - Chords are sent as a keyboard would: each modifier's own key down (ctrl 0xE0, shift 0xE1, opt 0xE2, cmd 0xE3, each carrying cumulative flags), then key down and up with all flags, then modifier ups in reverse (56-87). Spotlight is `press(0x2C, .command)`.
  - Android must map KeyEvent keycodes to USB HID usages.
- **Trackpad mode** (iOS/TrackpadView.swift):
  - A virtual cursor in frame fractions starts at center and is clamped.
  - Movement: `dx/padWidth*1.25`, `dy/ySpan*1.25` (`travel=1.25`, 162). `ySpan` is the pad height, or on a phone the pad width ÷ 1.6.
  - A 2 pt slop applies before tracking (170). Each move sends `pointer(move, cursor)`.
  - Tap: left click at the cursor. Two-finger tap: right click (471-480, 526-535).
  - Long-press 0.45 s then drag: leftDown, moves, leftUp (482-506).
  - Two fingers scroll at the cursor with deltas ÷ pad size, natural sign, plus the same momentum (338-433).
  - Latched modifiers are pressed around clicks as key events, since pointer events carry no modifiers.
  - Three- or four-finger gestures go as kind 28 (only if `WindowList.gestures >= 1`).

## 6. Ping, tick, stats, liveness, reconnect, goodbye

- **Ping** (kind 10) every 0.25 s on a dispatch timer (iOS/StreamClient.swift:871, 3805-3829, 3908-3923).
  - Payload: 8 bytes, big-endian bits of a Double holding monotonic seconds (CACurrentMediaTime). The host echoes the payload unchanged as kind 11 (Host/StreamServer.swift:1388-1391).
  - A 16-byte pong is a clipboard receipt, not an RTT sample (3744).
- **ClientStats** (kind 12) once per 1 s window while connected (iOS/DiagnosticsHUD.swift:33-42): `{"fps","frameAgeMs","rttMs","device","frameAgeMaxMs","rttMaxMs"}`, medians and maxima, -1 when there was no sample.
- **Ticks** (kind 13): host→device only; ignore them.
- **Device liveness:** no bytes received for max(6 s, 4×worst RTT) means lost; every header and every 256 KB payload piece counts (3878-3888; iOS/MessageReader.swift:33). For remote sessions, an unviable path for 3 s also means lost.
- **Host eviction:**
  - Home clients that drain nothing for 4 s are dropped (8 s grace) (Host/StreamServer.swift:1231-1236).
  - Remote clients silent for 8 s are dropped; the drain backstop is 15 s after a 15 s grace (1240-1242).
  - So the device must keep pinging.
- **Reconnect** (iOS/StreamClient+Remote.swift:474-531, 588-709; iOS/DiscoveryPolicy.swift):
  - Retry loop: 2 s, doubling, capped at 10 s (iOS/StreamClient.swift:3089-3100).
  - A listed row is dialed at once, then 5, 10, 20, then every 30 s, at most 12 dials, foreground only (726-745).
  - A row dial not ready in 5 s is abandoned (`reconnectDialWait`).
  - A tap's dial gives up after 10 s (`tapDialDeadline`).
  - A wired dial falls back after 2.5 s (`wiredWait`).
  - Remote dial of a saved Mac:
    - First dial 3 s after the loss, or 6 s if the Mac was remembered with Direct Wireless on.
    - Not within 10 s of its network row leaving, unless the device's path changed.
    - Failures retry at 2, 4, 8, then 10 s; all retries stop 120 s after the loss (812-845).
- **Goodbye** (kind 22, iOS/GoodbyePolicy.swift:49-85):

| reason | reconnect? |
|---|---|
| `quit` | yes, but leave the departing row alone for 3 s (`quitWait`) |
| `removed` | no; revoke the saved Mac |
| `pairingRequired` | no |
| `remoteOff`, `internetOff` | yes, home only |
| `busy` (8 remote sessions) | yes |
| `update`, or any unknown reason | show `message` verbatim; reconnect only if `reconnect: true` |

## 7. Remote access and platform-specific routes

- **Kind 18 SignedMacInfo** (Proto/Remote.swift:67-109): `{"info": base64(MacInfo JSON bytes), "key": base64(65-byte X9.63 point), "sig": base64(DER ECDSA-P256-SHA256 over the exact info bytes)}`.
- **Device-side checks:**
  1. The signature verifies over the exact decoded bytes.
  2. fp = SHA256(SPKI(point)).
  3. `info.macID == MacID(fp)`.
  4. The signer fp equals this connection's TLS peer fingerprint (`macInfoNamesSession`).
  5. It matches a saved Mac's pin.
  6. It is saved only if `issuedAt` is newer (iOS/StreamClient+Remote.swift:726-759).
- **MacInfo** (Proto/Remote.swift:39-63): `v`, `macID`, `name`, `issuedAt`, `remoteAccess`, `remotePort`, `internet`, and `addresses` as `[{host, port?, kind:"vpn"|"lan"|"internet", via}]`.
  - Addresses are empty while Remote Access is off; keep the old ones when the list is empty.
- **MacID:** Crockford base32 of the fingerprint's first 10 bytes, 16 characters (Proto/RemoteIdentity.swift:65-82).
- **Dial** (iOS/StreamClient+Remote.swift:293-358, iOS/RemoteConnector.swift):
  - Candidate port = address `port` ?? `remotePort` (7455).
  - Order per `RemoteDialPolicy.order` (iOS/RemoteDialPolicy.swift:64-85): last-worked first, then VPN names, VPN IPv4, VPN IPv6, LAN addresses in a shared subnet, internet names, internet IPs, other LAN addresses.
  - Tailscale shapes count as VPN: 100.64/10, fd7a:115c:a1e0::/48, `*.ts.net`.
  - TLS `sill/1` pinned to the saved fp. Attempts start 1 s apart (happy-eyeballs style); each connect has a 10 s timeout; the whole dial gives up after 20 s. The first `.ready` wins and the others are cancelled.
  - Connected only at the first window list, within 10 s.
  - Hello is sent first on the winner.
- **Platform-specific routes:**
  - **USB cable auto-pairing:** iOS-only. The Mac requires an Apple NCM interface from an iPhone or iPad (vendor 0x05AC, product "iPhone"/"iPad"; Host/CableLink.swift:48-56) plus the device's `cable:true` claim. Android cannot get "cable" pairing.
  - **Android USB tethering:** traffic over it would look like LAN to the Mac and can still use the home door with normal pairing.
  - **Direct Wireless (AWDL peer-to-peer):** Apple-only.
  - **Move-home/fences:** iOS-internal (iOS/SessionLink.swift). For a minimal port, skip them and just reconnect.

## 8. Pairing, end to end

- **Device identity** (iOS/DeviceIdentity.swift):
  - Software P-256 key in the Keychain, tag `me.saffer.sill.device-key`, `WhenUnlockedThisDeviceOnly`, created at the first TLS dial (102-128).
  - Its SHA-256(SPKI) is the device's pin on the Mac's trust list (Host/HostIdentity.swift:8-53).
  - Android: use an AndroidKeyStore EC P-256 key. Use a custom X509KeyManager that always returns the alias, whatever issuers the server's CertificateRequest lists.
- **Pairing connection:** TLS `sill-pair/1`, then exactly one kind 19 (≤4096 bytes), then one kind 20 within 15 s, then the Mac closes (Host/Door.swift:339-378; iOS/StreamClient+Home.swift:1048-1095).
  - PairRequest: `{"v":1,"method":"qr"|"code"|"ask"|"cancel","proof":b64url,"name":..,"model":..,"cable":true?}`.
- **Proofs** (Proto/Pairing.swift:80-136):
  - proof_D = HMAC-SHA256(K, `"sill-pair-v1 device" ‖ 0x00 ‖ fp_dev ‖ fp_mac`).
  - proof_M = HMAC-SHA256(K, `"sill-pair-v1 mac" ‖ 0x00 ‖ fp_mac ‖ fp_dev`).
  - Both fingerprints come from the TLS session, never from a message.
  - QR path: K = the 16-byte secret.
  - Code path: K = PBKDF2-HMAC-SHA256(12 ASCII digits, salt `"sill-pair-v1" ‖ fp_mac`, 600 000 iterations, 32 bytes).
  - The code is 12 digits whose last is a Damm check digit (table at 18-23). Check it locally first.
- **Window limits:** a pairing window lasts 300 s and allows 5 wrong tries, with waits of 1, 2, 4, 8 s and a 5 s per-source spacing; `busy` carries `retryAfter` (Host/PairingWindow.swift:75-80, 136-170).
- **Home typed path** (unpaired row, `p=1`):
  1. Kind 19 `ask` with any key; remember `askedKey` (iOS/StreamClient+Home.swift:467-604).
  2. The answer `shown` means the Mac opened its window.
  3. The user types the code. The device sends kind 19 `code` to the same endpoint, pinned to `askedKey` (686-696, 868-967).
  4. Other answers: `openOnMac` (the user must open "Pair iPhone or iPad…" on the Mac), `locked`, or `busy` (retry once after `retryAfter`).
  5. "cancel" withdraws a shown window.
- **QR link** (Proto/Pairing.swift:223-307): `sill://pair?v=1&m=<MacID>&k=<b64url fp, 43 chars>&s=<b64url secret, 22 chars>&n=<name>&p=<remote port>&a=<addr>`, with `a` repeated up to 5 times.
  - Values are percent-encoded to unreserved characters plus `:`.
  - Reject `m != MacID(k)`.
  - The device tries home rows with `p`, pinned to `k`, then the link's addresses at the remote door, one at a time, pinned (iOS/StreamClient+Home.swift:833-861; iOS/StreamClient+Remote.swift:862-868).
- **From afar typed path:** address plus code, any key, `sill-pair/1` to the remote door (port 7455 default) (871-878).
- **On kind 20 ok:**
  1. Check proof_M.
  2. Check `macID == MacID(fp_mac)`.
  3. Check the recognitionKey decodes to 32 bytes (921-944, 1000-1010).
  4. Save the SavedMac to UserDefaults `Sill.savedMacs` (JSON array, cap 16). Fields: macID, fingerprint (b64url), name, recognitionKey, remotePort, addresses, typedAddresses, infoIssuedAt, bonjourName, lastWorked, pairedAt, method, lastConnectedAt, lastRoute, homeTLS, revoked, newKey (iOS/SavedMacs.swift:11-66).
  5. Then dial a pinned `sill/1` session; if not connected within 10 s, leave it as a saved row.
- **Later connections:** mutual-TLS pins are the only authentication. The device presents the same key; the Mac checks its fp against the trust list in the handshake; the device pins the Mac's fp. There are no tokens.

---

## iOS-specific pieces an Android port must replace

| iOS | Android |
|---|---|
| NWBrowser and NWEndpoint.service dialing | NsdManager resolve (TXT attributes) |
| Network.framework TLS | Conscrypt TLS 1.3 with SSLParameters.setApplicationProtocols (API 29+) and SNIHostName("sill") |
| Keychain | AndroidKeyStore |
| AVSampleBufferDisplayLayer | MediaCodec with Annex-B conversion |
| VisionKit DataScannerViewController (iOS/CodeScanner.swift) | ML Kit or ZXing |
| UIKeyboardHIDUsage | KeyEvent→HID table |
| CACurrentMediaTime | SystemClock.elapsedRealtimeNanos |
| UIScreen.maximumFramesPerSecond | Display refresh rate |
| AWDL, USB cable pairing | none (Apple-only) |

Two open points:
- **Android version in Hello:** the device floor is compared against `appVersion`, which follows iOS app versions. An Android client should send a version string that sorts at or above whatever floor the Mac may adopt.
- **Pin check timing:** checking the pin after the handshake, before sending any byte, as sillclient.py does, also works if a TrustManager callback is awkward.
