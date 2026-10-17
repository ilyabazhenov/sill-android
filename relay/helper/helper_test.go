package helper

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"io"
	"log"
	"math/big"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"dev.remotemac/relay/server"
	"dev.remotemac/relay/wire"
)

func listen(t *testing.T) net.Listener {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	return l
}

func port(l net.Listener) int { return l.Addr().(*net.TCPAddr).Port }

func quietLog() *log.Logger { return log.New(io.Discard, "", 0) }

func selfSigned(t *testing.T) tls.Certificate {
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{SerialNumber: big.NewInt(1), NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &k.PublicKey, k)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: k}
}

// fakeSill is a TLS 1.3 echo server offering sill/1 and sill-pair/1, like Sill's remote door
// minus the key checks; it counts the connections it gets.
func fakeSill(t *testing.T) (net.Listener, *atomic.Int32) {
	l := listen(t)
	conf := &tls.Config{Certificates: []tls.Certificate{selfSigned(t)}, MinVersion: tls.VersionTLS13, NextProtos: []string{"sill/1", "sill-pair/1"}}
	n := new(atomic.Int32)
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			n.Add(1)
			go func() { s := tls.Server(c, conf); io.Copy(s, s); s.Close() }()
		}
	}()
	return l, n
}

type rig struct {
	relay  string
	id     wire.ID
	access []byte
}

func setup(t *testing.T, sillPort int) rig {
	rl := listen(t)
	srv := server.New()
	srv.Log = quietLog()
	go srv.Serve(rl)
	d := t.TempDir()
	key, _ := LoadKey(d)
	access, _ := LoadAccess(d, false)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	h := New(Config{Relay: rl.Addr().String(), SillPort: sillPort}, key, access, quietLog())
	go h.Run(ctx)
	return rig{relay: rl.Addr().String(), id: IDOf(key), access: access}
}

// phone asks the relay for a Mac and returns the status and the connection.
func phone(t *testing.T, relay string, id wire.ID, access []byte) (byte, net.Conn) {
	c, err := net.Dial("tcp", relay)
	if err != nil {
		t.Fatal(err)
	}
	c.Write(append(append(wire.Hello(wire.RolePhone), id[:]...), access...))
	c.SetReadDeadline(time.Now().Add(15 * time.Second))
	st, err := wire.ReadFull(c, 1)
	if err != nil {
		c.Close()
		t.Fatalf("no status: %v", err)
	}
	c.SetReadDeadline(time.Time{})
	return st[0], c
}

// online asks until the Mac is on the relay and returns the first answer that isn't "offline".
func online(t *testing.T, r rig, access []byte) (byte, net.Conn) {
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		st, c := phone(t, r.relay, r.id, access)
		if st != wire.StatusOffline {
			return st, c
		}
		c.Close()
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("the Mac never came online")
	return 0, nil
}

func tlsOver(c net.Conn, proto string) *tls.Conn {
	return tls.Client(c, &tls.Config{InsecureSkipVerify: true, MinVersion: tls.VersionTLS13, NextProtos: []string{proto}, ServerName: "sill"})
}

func TestSessionCarriesBytesBothWays(t *testing.T) {
	sill, _ := fakeSill(t)
	r := setup(t, port(sill))
	st, c := online(t, r, r.access)
	if st != wire.StatusOK {
		t.Fatalf("status %d", st)
	}
	s := tlsOver(c, "sill/1")
	defer s.Close()
	msg := bytes.Repeat([]byte("sill frame "), 100_000)
	go s.Write(msg)
	got := make([]byte, len(msg))
	s.SetReadDeadline(time.Now().Add(10 * time.Second))
	if _, err := io.ReadFull(s, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, msg) {
		t.Fatal("bytes changed on the way")
	}

	// Several phones at once, each with its own session.
	for i := 0; i < 3; i++ {
		st, c2 := phone(t, r.relay, r.id, r.access)
		if st != wire.StatusOK {
			t.Fatalf("another session: status %d", st)
		}
		s2 := tlsOver(c2, "sill/1")
		s2.Write([]byte("hi"))
		b, err := wire.ReadFull(s2, 2)
		if err != nil || string(b) != "hi" {
			t.Fatalf("another session: %q %v", b, err)
		}
		s2.Close()
	}
}

func TestWrongAccessKeyNeverReachesSill(t *testing.T) {
	sill, count := fakeSill(t)
	r := setup(t, port(sill))
	wrong := make([]byte, wire.AccessLen)
	st, c := online(t, r, wrong)
	c.Close()
	if st != wire.StatusDenied {
		t.Fatalf("status %d, want denied", st)
	}
	if count.Load() != 0 {
		t.Fatal("a phone without the key reached Sill")
	}
}

func TestPairingIsNotRelayed(t *testing.T) {
	sill, count := fakeSill(t)
	r := setup(t, port(sill))
	for _, proto := range []string{"sill-pair/1", "h2"} {
		st, c := online(t, r, r.access)
		if st != wire.StatusOK {
			t.Fatalf("status %d", st)
		}
		s := tlsOver(c, proto)
		s.SetDeadline(time.Now().Add(5 * time.Second))
		err := s.Handshake()
		s.Close()
		if err == nil {
			t.Fatalf("%s: the handshake went through", proto)
		}
	}
	// Not TLS at all.
	_, c := online(t, r, r.access)
	c.Write([]byte("GET / HTTP/1.1\r\n\r\n"))
	c.SetReadDeadline(time.Now().Add(5 * time.Second))
	io.ReadAll(c)
	c.Close()
	if count.Load() != 0 {
		t.Fatal("something other than a session reached Sill")
	}
}

func TestUnknownMacIsOffline(t *testing.T) {
	rl := listen(t)
	srv := server.New()
	srv.Log = quietLog()
	go srv.Serve(rl)
	st, c := phone(t, rl.Addr().String(), wire.ID{1, 2, 3}, make([]byte, wire.AccessLen))
	c.Close()
	if st != wire.StatusOffline {
		t.Fatalf("status %d, want offline", st)
	}
}

func TestSillOffEndsTheHandshake(t *testing.T) {
	closed := listen(t)
	sillPort := port(closed)
	closed.Close()
	r := setup(t, sillPort)
	st, c := online(t, r, r.access)
	if st != wire.StatusOK {
		t.Fatalf("status %d", st)
	}
	s := tlsOver(c, "sill/1")
	s.SetDeadline(time.Now().Add(5 * time.Second))
	if err := s.Handshake(); err == nil {
		t.Fatal("handshake with no Sill went through")
	}
	s.Close()
}

func TestWrongSignatureIsRefused(t *testing.T) {
	rl := listen(t)
	srv := server.New()
	srv.Log = quietLog()
	go srv.Serve(rl)

	c, err := net.Dial("tcp", rl.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	key, _ := LoadKey(t.TempDir())
	other, _ := LoadKey(t.TempDir())
	c.Write(append(wire.Hello(wire.RoleMac), key.Public().(ed25519.PublicKey)...))
	nonce, err := wire.ReadFull(c, wire.NonceLen)
	if err != nil {
		t.Fatal(err)
	}
	c.Write(ed25519.Sign(other, wire.Challenge(nonce)))
	st, err := wire.ReadFull(c, 1)
	if err != nil || st[0] != wire.StatusBadAuth {
		t.Fatalf("status %v %v, want bad auth", st, err)
	}
}

func TestSecretsPersistAndRenew(t *testing.T) {
	d := t.TempDir()
	a, _ := LoadKey(d)
	b, _ := LoadKey(d)
	if !bytes.Equal(a, b) {
		t.Fatal("a second load made a new key")
	}
	x, _ := LoadAccess(d, false)
	y, _ := LoadAccess(d, false)
	z, _ := LoadAccess(d, true)
	if !bytes.Equal(x, y) || bytes.Equal(x, z) {
		t.Fatal("the access key should stay until renewed")
	}
}

func TestClientHelloALPN(t *testing.T) {
	for _, want := range [][]string{{"sill/1"}, {"sill-pair/1"}, {"sill/1", "h2"}, nil} {
		a, b := net.Pipe()
		go func() {
			tls.Client(a, &tls.Config{InsecureSkipVerify: true, NextProtos: want, ServerName: "sill"}).Handshake()
		}()
		raw, got, err := ReadClientHello(b)
		a.Close()
		b.Close()
		if err != nil {
			t.Fatalf("%v: %v", want, err)
		}
		if len(raw) < 100 || raw[0] != 22 {
			t.Fatalf("%v: raw records %d bytes", want, len(raw))
		}
		if len(got) != len(want) || (len(want) > 0 && got[0] != want[0]) {
			t.Fatalf("got %q, want %q", got, want)
		}
	}
	if _, _, err := ReadClientHello(bytes.NewReader([]byte("GET / HTTP/1.1\r\n\r\n"))); err == nil {
		t.Fatal("HTTP taken for a ClientHello")
	}
}
