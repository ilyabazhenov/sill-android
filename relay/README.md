# Relay: reaching your Mac away from home without Tailscale

How it works and why: [docs/relay.md](../docs/relay.md). There are two programs here:

- `rmrelay`: a server on a VPS with a public IP. It stores nothing and cannot read the traffic.
- `rmhelper`: runs on the Mac next to Sill. It keeps a connection to the relay open and hands the phone's connections to Sill.

Needs Go 1.22+.

## 1. Server

Any Linux VPS works. Pick one close to you: the picture's latency grows by the round trip to the VPS.

```bash
GOOS=linux GOARCH=amd64 go build -o rmrelay ./cmd/rmrelay
```

```bash
scp rmrelay deploy/rmrelay.service root@VPS:/tmp/
```

On the server:

```bash
install -m 755 /tmp/rmrelay /usr/local/bin/ && install -m 644 /tmp/rmrelay.service /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now rmrelay
```

Open TCP 7456 in the firewall (for example `ufw allow 7456/tcp`). Logs: `journalctl -u rmrelay -f`.

## 2. Mac

In Sill: Settings → Remote Access → on. "Allow connections from the internet" is not needed.

```bash
go build -o rmhelper ./cmd/rmhelper
```

```bash
./rmhelper install -relay VPS:7456
```

The helper starts now and at every login (LaunchAgent `dev.remotemac.helper`), and prints a QR code in the
terminal. Keys and settings live in `~/Library/Application Support/RemoteMac Helper/`, the log in
`~/Library/Logs/RemoteMac Helper.log`.

The QR code holds an access key: don't share it. It gives no session without a paired phone, but it lets
connection attempts reach Sill's door.

If Sill's remote door is not on port 7455, add `-sill-port N`.

## 3. Phone

1. Pair the phone with the Mac as usual (at home).
2. Scan the helper's QR code with the system camera or the app's "Scan Pairing Code" button.
   Show the code again: `./rmhelper link`, or `./rmhelper link -png qr.png`.
   Pairing over the relay is blocked: pair at home.

The Mac gets a "Remote · relay" entry under "Away from home". Away from home the app tries Sill's own addresses
(VPN, LAN) first, then the relay.

## Lost phone

1. Remove it in Sill on the Mac: its sessions drop and its key is no longer accepted.
2. Rotate the access key so the old QR code no longer reaches the Mac:

```bash
./rmhelper new-access
```

The remaining phones scan the new code.

If Sill on the Mac was reinstalled with a new key, read it again: `./rmhelper link -refresh`.

## Uninstall

```bash
./rmhelper uninstall
```

## Testing

```bash
go test -race ./...
```

End-to-end with a real Sill and no phone: a test SillHost with `--remote=17455`, `rmrelay -listen 127.0.0.1:17456`,
`rmhelper run -relay 127.0.0.1:17456 -sill-port 17455`, pair the command-line client (see the root README), then

```bash
protocol/build/install/protocol/bin/protocol 0 0 --relay-link "$(rmhelper link -relay 127.0.0.1:17456 -sill-port 17455 | grep ^remotemac)"
```

From the Android emulator the relay is at `10.0.2.2:17456`: `rmhelper link -relay 10.0.2.2:17456` prints its QR code.

Don't use `rmhelper install` for this kind of test: it installs a LaunchAgent into your real session.
Use `rmhelper run`.
