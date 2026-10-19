# Relay: reaching your Mac away from home without Tailscale

At home the phone talks to Sill directly. Away from home there is no direct path: the Mac sits behind NAT, often
without a public IP, and Tailscale has to be installed on both devices. The relay solves this the usual way: the
Mac keeps an outgoing connection to a server, and a phone that can't reach the Mac any other way goes through
that server.

How it fits Sill:

- Sill on the Mac runs unchanged and only listens. A separate program, `rmhelper` (a launchd agent), keeps the
  outgoing connection and hands the phone's connections to Sill's remote door on `127.0.0.1:7455`. The remote
  door admits loopback (`OriginPolicy.remoteAdmits`), so turning on Remote Access in Sill is enough.
- The relay carries Sill's continuous stream (TLS 1.3 with video), not HTTP. The relay parses nothing: it is a
  blind byte pipe over TCP on your own VPS. Encryption is already there: inside the pipe runs Sill's TLS, where
  the phone and the Mac check each other's keys. The relay sees only addresses, timing and volume.

```
phone ──TCP──▶ VPS: rmrelay :7456 ◀──TCP── rmhelper on the Mac ──TCP──▶ Sill remote door 127.0.0.1:7455
      └─────────────── Sill TLS (sill/1, both keys pinned) ──────────────────────────────┘
```

## Security

The trust boundary is Sill's: only a paired device key gets a session, and a paired device controls the Mac
completely (keyboard and mouse, password fields included). The relay adds a path to Sill's door from the
internet, so the helper stands in front of the door and doesn't let everything through.

- **Access key.** The helper's QR code carries a random 16-byte key. A phone without it is refused by the
  helper and never reaches Sill. Otherwise anyone who learned the relay address and the Mac's id could knock on
  Sill's door. Through it they could read the Mac's key fingerprint, burn the pairing window, or lock the relay
  out: Sill blocks a source for 5 minutes after 5 failures in a minute, and to Sill every phone coming through
  the relay is one address, 127.0.0.1 (`Door.swift`, `countFailure`). `rmhelper new-access` rotates the key and
  old QR codes stop working.
- **Sessions only.** The helper reads the phone's ClientHello and passes to Sill only ALPN exactly `sill/1`.
  Pairing (`sill-pair/1`) over the relay is blocked: the Mac's pairing window opens the remote door too, code
  entry isn't protected by a PAKE, and a malicious relay could brute-force the code offline. Pair at home, with
  Sill's QR code.
- **At most 2 handshakes to Sill at once** (what Sill allows per address) and at most 8 sessions. The helper
  reads Sill's key fingerprint once at install and keeps it in its settings: every read counts as a failure from
  127.0.0.1 to Sill.
- **A relay link from another app or a web page** is applied by the phone only after confirmation.
- **Over the relay** the shared clipboard is off until turned on: otherwise copies from the Mac would travel over
  the internet.
- **App lock.** A phone with a screen lock asks for a fingerprint, face or PIN on open and after a minute in the
  background.

What remains:

- The relay is internet access, though Sill doesn't call it that. Sill shows relay sessions as "by address" and
  gives them home quality. The "Allow connections from the internet" switch doesn't stop them; only turning off
  Remote Access does.
- Anyone who knows the access key (that is, saw the QR code) can lock the relay out for 5 minutes with junk
  handshakes.
- Lost phone: remove it in Sill on the Mac (this drops its sessions) and run `rmhelper new-access`.
- The connection to the relay is plain TCP. Its content is hidden by Sill's TLS. The access key, the Mac's id,
  addresses and volume are visible along the path and to the VPS owner.

## Protocol (`relay/wire`)

Every TCP connection to the relay starts with `RMR1` and a role byte.

**Mac, control connection (`M`).** One per Mac, kept open all the time.

1. Mac → `pub` (32 bytes, the helper's Ed25519 key).
2. Relay → `nonce` (32 random bytes).
3. Mac → Ed25519 signature over `"remote-mac relay v1 mac" ‖ nonce` (64 bytes).
4. Relay → status (1 byte): `0` accepted, `0x10` bad signature.

The Mac's id is the first 16 bytes of SHA-256(`pub`), 32 hex characters as text. Without the key nobody can pose
as the Mac, and the relay stores nothing: the id is derived from the key. A new connection from the same Mac
replaces the old one.

Then frames with a 1-byte type:

| Frame | Direction | Meaning |
|---|---|---|
| `O` + token (16) + access key (16) | relay → Mac | a phone is waiting: check the key and open a `D` connection with this token |
| `F` + token (16) + status (1) | Mac → relay | no session; the status goes to the phone |
| `P` | either | ping, answered with `p` |
| `p` | either | pong |

The relay sends `P` every 25 s. A side that has received nothing for 70 s considers the connection dead. The
helper reconnects with a backoff from 1 s to a minute.

**Mac, data connection (`D`).** Mac → token (16). From then on the relay splices this connection with the phone.
The helper reads the phone's ClientHello from it, checks the ALPN, and only then opens Sill and forwards the
ClientHello. If the ALPN isn't `sill/1`, the helper answers with a `no_application_protocol` TLS alert. If Sill is
unreachable, `internal_error` (the phone shows "helper couldn't reach Sill").

**Phone (`C`).** Phone → Mac id (16) + access key (16). Relay → status:

| Status | Meaning |
|---|---|
| `0` | the Mac opened its side; raw Sill bytes follow |
| `1` | the Mac isn't connected to the relay (helper not running, Mac asleep or offline) |
| `2` | busy: this Mac already has 8 sessions through the relay |
| `3` | the Mac didn't answer within 10 s |
| `4` | the helper couldn't open Sill |
| `5` | wrong access key (an old QR code) |

After `0` the phone starts the usual Sill TLS handshake over the same socket.

## Relay limits

- All leading bytes (role, key, signature, token, id) have a 10 s timeout.
- At most 8 sessions per Mac (as many remote sessions as Sill itself allows), 2048 connections in total.
- New connections from one IP: 120 per minute with a burst of 40.
- Spliced connections live as long as either end is alive; TCP keepalive is on.

## How the phone learns about the relay

`rmhelper link` prints a QR code of the link

```
remotemac://relay?r=<host:port>&i=<Mac id>&s=<access key>&k=<Sill key fingerprint>&n=<Mac name>
```

The app finds the saved Mac with that fingerprint and stores the relay and access key for it. So the Mac has to
be paired the normal way first (at home), and only then is the helper's QR code scanned.

## Path selection on the phone

The relay is one more candidate in the race of remote addresses (`Controller.raceRemote`), the last in order.
First comes the path that worked last time, then VPN, LAN, Sill's internet addresses, manually entered
addresses, and only then the relay. If the relay worked last time (`lastWorked = "relay:<host:port>"`), it goes
first.

## Operations

See [relay/README.md](../relay/README.md).
