// Package helper is the Mac's side of the relay: it keeps a control connection to the relay and,
// for each phone with this Mac's access key, opens a data connection to the relay and Sill's remote
// door on loopback and passes bytes between them. Sill itself stays as it is. See docs/relay.md.
package helper

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"dev.remotemac/relay/wire"
)

// DefaultSillPort is Sill's remote door unless the Mac's user changed it.
const DefaultSillPort = 7455

// Sill's remote door takes at most 2 pending handshakes from one address, and every relayed
// phone is the same address to it (127.0.0.1), so the helper never has more in flight.
const sillHandshakes = 2

type Config struct {
	Relay    string `json:"relay"` // host:port of rmrelay
	SillPort int    `json:"sillPort"`
	// SillKey is Sill's key fingerprint (base64url), read once: every probe of Sill's door
	// without a device key counts as a refused handshake there, and five a minute lock it.
	SillKey string `json:"sillKey,omitempty"`
}

func (c Config) sillAddr() string { return net.JoinHostPort("127.0.0.1", strconv.Itoa(c.SillPort)) }

// LoadKey reads the helper's Ed25519 key from dir, making one the first time.
func LoadKey(dir string) (ed25519.PrivateKey, error) {
	seed, err := loadSecret(dir, "key", ed25519.SeedSize, false)
	if err != nil {
		return nil, err
	}
	return ed25519.NewKeyFromSeed(seed), nil
}

// LoadAccess reads the access key phones must present, making one the first time. A new one
// (fresh = true) turns away every phone that has the old QR code.
func LoadAccess(dir string, fresh bool) ([]byte, error) {
	return loadSecret(dir, "access", wire.AccessLen, fresh)
}

func loadSecret(dir, name string, size int, fresh bool) ([]byte, error) {
	p := filepath.Join(dir, name)
	if !fresh {
		if b, err := os.ReadFile(p); err == nil {
			if len(b) != size {
				return nil, fmt.Errorf("%s is damaged: delete it to make a new one (phones then need the new QR code)", p)
			}
			return b, nil
		} else if !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	b := make([]byte, size)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	if err := os.WriteFile(p, b, 0o600); err != nil {
		return nil, err
	}
	return b, nil
}

func IDOf(key ed25519.PrivateKey) wire.ID { return wire.IDOf(key.Public().(ed25519.PublicKey)) }

type Helper struct {
	Config Config
	Key    ed25519.PrivateKey
	Access []byte
	Log    *log.Logger

	mu       sync.Mutex
	sessions int
	slots    chan struct{} // Sill handshakes in flight
}

func New(cfg Config, key ed25519.PrivateKey, access []byte, lg *log.Logger) *Helper {
	return &Helper{Config: cfg, Key: key, Access: access, Log: lg, slots: make(chan struct{}, sillHandshakes)}
}

// Run keeps the Mac on the relay until ctx ends: it reconnects after any failure, waiting from
// 1 s up to a minute; a connection that lasted a minute starts the wait over.
func (h *Helper) Run(ctx context.Context) {
	wait := time.Second
	for ctx.Err() == nil {
		start := time.Now()
		err := h.session(ctx)
		if ctx.Err() != nil {
			return
		}
		if time.Since(start) > time.Minute {
			wait = time.Second
		}
		h.Log.Printf("relay %s: %v; again in %s", h.Config.Relay, err, wait)
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
		wait = min(wait*2, time.Minute)
	}
}

func dial(ctx context.Context, addr string) (net.Conn, error) {
	d := net.Dialer{Timeout: wire.HandshakeTimeout, KeepAlive: 15 * time.Second}
	return d.DialContext(ctx, "tcp", addr)
}

func (h *Helper) session(ctx context.Context) error {
	c, err := dial(ctx, h.Config.Relay)
	if err != nil {
		return err
	}
	defer c.Close()
	stop := context.AfterFunc(ctx, func() { c.Close() })
	defer stop()

	c.SetDeadline(time.Now().Add(wire.HandshakeTimeout))
	pub := h.Key.Public().(ed25519.PublicKey)
	if _, err := c.Write(append(wire.Hello(wire.RoleMac), pub...)); err != nil {
		return err
	}
	nonce, err := wire.ReadFull(c, wire.NonceLen)
	if err != nil {
		return fmt.Errorf("no challenge: %w", err)
	}
	if _, err := c.Write(ed25519.Sign(h.Key, wire.Challenge(nonce))); err != nil {
		return err
	}
	st, err := wire.ReadFull(c, 1)
	if err != nil {
		return fmt.Errorf("no answer to the signature: %w", err)
	}
	if st[0] != wire.StatusOK {
		return fmt.Errorf("the relay refused this Mac (status %d)", st[0])
	}
	c.SetDeadline(time.Time{})
	h.Log.Printf("on relay %s as %s", h.Config.Relay, wire.IDOf(pub))

	var wmu sync.Mutex
	send := func(b []byte) error {
		wmu.Lock()
		defer wmu.Unlock()
		c.SetWriteDeadline(time.Now().Add(wire.HandshakeTimeout))
		_, err := c.Write(b)
		return err
	}
	one := make([]byte, 1)
	for {
		c.SetReadDeadline(time.Now().Add(wire.DeadAfter))
		if _, err := io.ReadFull(c, one); err != nil {
			return fmt.Errorf("lost: %w", err)
		}
		switch one[0] {
		case wire.FramePing:
			if err := send([]byte{wire.FramePong}); err != nil {
				return err
			}
		case wire.FramePong:
		case wire.FrameOpen:
			b, err := wire.ReadFull(c, wire.TokenLen+wire.AccessLen)
			if err != nil {
				return err
			}
			t, access := b[:wire.TokenLen], b[wire.TokenLen:]
			go func() {
				if st := h.open(ctx, t, access); st != wire.StatusOK {
					send(append(append([]byte{wire.FrameFailed}, t...), st))
				}
			}()
		default:
			return fmt.Errorf("unknown frame %q", one[0])
		}
	}
}

// open answers a phone the relay announced: a wrong key or too many sessions is turned down
// before anything touches Sill.
func (h *Helper) open(ctx context.Context, t, access []byte) byte {
	if subtle.ConstantTimeCompare(access, h.Access) != 1 {
		h.Log.Printf("a phone without this Mac's access key (an old QR code?) was turned away")
		return wire.StatusDenied
	}
	h.mu.Lock()
	if h.sessions >= wire.MaxSessions {
		h.mu.Unlock()
		return wire.StatusBusy
	}
	h.sessions++
	h.mu.Unlock()
	r, err := dial(ctx, h.Config.Relay)
	if err == nil {
		_, err = r.Write(append(wire.Hello(wire.RoleData), t...))
	}
	if err != nil {
		h.Log.Printf("relay data connection: %v", err)
		if r != nil {
			r.Close()
		}
		h.done()
		return wire.StatusRefused
	}
	go func() {
		defer h.done()
		h.serve(ctx, r)
	}()
	return wire.StatusOK
}

func (h *Helper) done() {
	h.mu.Lock()
	h.sessions--
	h.mu.Unlock()
}

// TLS alerts sent to the phone when the helper ends the handshake itself, before Sill.
const (
	alertInternalError  = 80
	alertNoAppProtocol  = 120
	clientHelloDeadline = 10 * time.Second
)

func alert(desc byte) []byte { return []byte{21, 3, 3, 0, 2, 2, desc} }

// serve takes the phone's ClientHello first: only a session (ALPN exactly sill/1) goes on to
// Sill. Pairing (sill-pair/1) is for home or a VPN, never a door anyone on the relay can knock on.
func (h *Helper) serve(ctx context.Context, r net.Conn) {
	defer r.Close()
	r.SetReadDeadline(time.Now().Add(clientHelloDeadline))
	hello, protos, err := ReadClientHello(r)
	if err != nil {
		h.Log.Printf("session: %v", err)
		return
	}
	if len(protos) != 1 || protos[0] != "sill/1" {
		h.Log.Printf("refused %q through the relay: only sessions (sill/1) go through it; pair at home", protos)
		r.Write(alert(alertNoAppProtocol))
		return
	}

	select {
	case h.slots <- struct{}{}:
	case <-time.After(clientHelloDeadline):
		r.Write(alert(alertInternalError))
		return
	case <-ctx.Done():
		return
	}
	var once sync.Once
	release := func() { once.Do(func() { <-h.slots }) }
	defer release()

	sill, err := dial(ctx, h.Config.sillAddr())
	if err != nil {
		h.Log.Printf("Sill's remote door at %s: %v (is Remote Access on?)", h.Config.sillAddr(), err)
		r.Write(alert(alertInternalError))
		return
	}
	r.SetReadDeadline(time.Time{})
	if _, err := sill.Write(hello); err != nil {
		sill.Close()
		return
	}
	// The phone's next bytes are its second flight: from then on the handshake is Sill's to
	// finish, and the slot goes to the next phone. A phone that never sends them gives it up
	// at Sill's own admission deadline.
	timer := time.AfterFunc(clientHelloDeadline, release)
	defer timer.Stop()
	start := time.Now()
	up, down := splice(sill, &firstRead{Conn: r, first: release})
	h.Log.Printf("session ended after %s: to phone %d B, from phone %d B", time.Since(start).Round(time.Second), up, down+int64(len(hello)))
}

// firstRead calls first once the connection has given its first bytes.
type firstRead struct {
	net.Conn
	first func()
}

func (f *firstRead) Read(b []byte) (int, error) {
	n, err := f.Conn.Read(b)
	if n > 0 {
		f.first()
	}
	return n, err
}

func splice(a, b net.Conn) (aToB, bToA int64) {
	done := make(chan struct{}, 2)
	go func() { aToB, _ = io.Copy(b, a); done <- struct{}{} }()
	go func() { bToA, _ = io.Copy(a, b); done <- struct{}{} }()
	<-done
	a.Close()
	b.Close()
	<-done
	return
}

// SillFingerprint reads the key Sill's remote door presents: SHA-256 of its SubjectPublicKeyInfo,
// the pin the phone saved when it paired. Sill then refuses this connection (no device key) and
// counts it against 127.0.0.1, which is why the result is kept in the config.
func SillFingerprint(port int) ([]byte, error) {
	var fp []byte
	conf := &tls.Config{
		InsecureSkipVerify: true,
		MinVersion:         tls.VersionTLS13,
		NextProtos:         []string{"sill/1"},
		ServerName:         "sill",
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			if len(raw) == 0 {
				return errors.New("no certificate")
			}
			cert, err := x509.ParseCertificate(raw[0])
			if err != nil {
				return err
			}
			sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
			fp = sum[:]
			return nil
		},
	}
	d := net.Dialer{Timeout: 3 * time.Second}
	addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	c, err := tls.DialWithDialer(&d, "tcp", addr, conf)
	if c != nil {
		c.Close()
	}
	if fp != nil {
		return fp, nil
	}
	if err == nil {
		err = errors.New("no certificate")
	}
	return nil, fmt.Errorf("Sill's remote door at %s: %w (is Sill running with Remote Access on?)", addr, err)
}

// Link is what the phone scans: the relay, this Mac's id on it, the access key, and Sill's key so
// the phone knows which of its paired Macs this is. Whoever has it can reach Sill's door (a
// paired key is still needed for a session), so it's shown only on this Mac.
func Link(cfg Config, key ed25519.PrivateKey, access, sillFP []byte, name string) string {
	q := url.Values{}
	q.Set("r", cfg.Relay)
	q.Set("i", IDOf(key).String())
	q.Set("s", base64.RawURLEncoding.EncodeToString(access))
	q.Set("k", base64.RawURLEncoding.EncodeToString(sillFP))
	if name != "" {
		q.Set("n", name)
	}
	return "remotemac://relay?" + q.Encode()
}
