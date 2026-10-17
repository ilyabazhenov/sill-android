// Package server is the relay: Macs keep a control connection to it, phones ask it for a Mac
// by id, and it splices each phone with a data connection the Mac opens for it. It never reads
// what passes through: inside is Sill's own TLS. See docs/relay.md.
package server

import (
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"io"
	"log"
	"net"
	"sync"
	"time"

	"dev.remotemac/relay/wire"
)

const (
	maxConns    = 2048
	openTimeout = 10 * time.Second
)

type token [wire.TokenLen]byte

type mac struct {
	id   wire.ID
	conn net.Conn
	wmu  sync.Mutex
}

func (m *mac) send(b []byte) error {
	m.wmu.Lock()
	defer m.wmu.Unlock()
	m.conn.SetWriteDeadline(time.Now().Add(wire.HandshakeTimeout))
	_, err := m.conn.Write(b)
	return err
}

// A phone waiting for its Mac's data connection.
type waiting struct {
	mac   wire.ID
	ready chan net.Conn // the Mac's data connection
	fail  chan byte     // the Mac turned it down: the status for the phone
}

type Server struct {
	Log *log.Logger

	mu       sync.Mutex
	macs     map[wire.ID]*mac
	sessions map[wire.ID]int // per Mac: phones waiting or spliced
	pending  map[token]*waiting
	conns    chan struct{}
	limits   *limiter
}

func New() *Server {
	return &Server{
		Log:      log.Default(),
		macs:     map[wire.ID]*mac{},
		sessions: map[wire.ID]int{},
		pending:  map[token]*waiting{},
		conns:    make(chan struct{}, maxConns),
		limits:   newLimiter(2, 40), // 120 a minute, 40 at once
	}
}

// Serve accepts until the listener closes.
func (s *Server) Serve(l net.Listener) error {
	for {
		c, err := l.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				time.Sleep(50 * time.Millisecond)
				continue
			}
			return err
		}
		if !s.limits.allow(hostOf(c.RemoteAddr())) {
			c.Close()
			continue
		}
		select {
		case s.conns <- struct{}{}:
		default:
			c.Close()
			continue
		}
		go func() {
			defer func() { <-s.conns }()
			s.handle(c)
		}()
	}
}

func (s *Server) handle(c net.Conn) {
	if tc, ok := c.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
		tc.SetKeepAlive(true)
		tc.SetKeepAlivePeriod(15 * time.Second)
	}
	c.SetDeadline(time.Now().Add(wire.HandshakeTimeout))
	hello, err := wire.ReadFull(c, len(wire.Magic)+1)
	if err != nil || string(hello[:len(wire.Magic)]) != wire.Magic {
		c.Close()
		return
	}
	switch hello[len(wire.Magic)] {
	case wire.RoleMac:
		s.handleMac(c)
	case wire.RoleData:
		s.handleData(c)
	case wire.RolePhone:
		s.handlePhone(c)
	default:
		c.Close()
	}
}

// --- the Mac's control connection

func (s *Server) handleMac(c net.Conn) {
	defer c.Close()
	pub, err := wire.ReadFull(c, ed25519.PublicKeySize)
	if err != nil {
		return
	}
	nonce := make([]byte, wire.NonceLen)
	rand.Read(nonce)
	if _, err := c.Write(nonce); err != nil {
		return
	}
	sig, err := wire.ReadFull(c, ed25519.SignatureSize)
	if err != nil {
		return
	}
	if !ed25519.Verify(pub, wire.Challenge(nonce), sig) {
		c.Write([]byte{wire.StatusBadAuth})
		return
	}
	if _, err := c.Write([]byte{wire.StatusOK}); err != nil {
		return
	}
	c.SetDeadline(time.Time{})

	m := &mac{id: wire.IDOf(pub), conn: c}
	s.mu.Lock()
	old := s.macs[m.id]
	s.macs[m.id] = m
	s.mu.Unlock()
	if old != nil {
		old.conn.Close()
	}
	s.Log.Printf("mac %s up from %s", short(m.id), hostOf(c.RemoteAddr()))

	done := make(chan struct{})
	go func() {
		t := time.NewTicker(wire.PingEvery)
		defer t.Stop()
		for {
			select {
			case <-done:
				return
			case <-t.C:
				if m.send([]byte{wire.FramePing}) != nil {
					c.Close()
					return
				}
			}
		}
	}()
	s.readMac(m)
	close(done)

	s.mu.Lock()
	if s.macs[m.id] == m {
		delete(s.macs, m.id)
	}
	s.mu.Unlock()
	s.Log.Printf("mac %s down", short(m.id))
}

func (s *Server) readMac(m *mac) {
	one := make([]byte, 1)
	for {
		m.conn.SetReadDeadline(time.Now().Add(wire.DeadAfter))
		if _, err := io.ReadFull(m.conn, one); err != nil {
			return
		}
		switch one[0] {
		case wire.FramePing:
			if m.send([]byte{wire.FramePong}) != nil {
				return
			}
		case wire.FramePong:
		case wire.FrameFailed:
			b, err := wire.ReadFull(m.conn, wire.TokenLen+1)
			if err != nil {
				return
			}
			var t token
			copy(t[:], b)
			status := b[wire.TokenLen]
			if status != wire.StatusBusy && status != wire.StatusDenied {
				status = wire.StatusRefused
			}
			s.mu.Lock()
			w := s.pending[t]
			if w != nil && w.mac == m.id {
				delete(s.pending, t)
			} else {
				w = nil
			}
			s.mu.Unlock()
			if w != nil {
				w.fail <- status
			}
		default:
			return
		}
	}
}

// --- a Mac's data connection for one phone

func (s *Server) handleData(c net.Conn) {
	b, err := wire.ReadFull(c, wire.TokenLen)
	if err != nil {
		c.Close()
		return
	}
	var t token
	copy(t[:], b)
	s.mu.Lock()
	w := s.pending[t]
	delete(s.pending, t)
	s.mu.Unlock()
	if w == nil {
		c.Close()
		return
	}
	c.SetDeadline(time.Time{})
	w.ready <- c // buffered: the phone takes it
}

// --- a phone

func (s *Server) handlePhone(c net.Conn) {
	defer c.Close()
	b, err := wire.ReadFull(c, wire.IDLen+wire.AccessLen)
	if err != nil {
		return
	}
	var id wire.ID
	copy(id[:], b)
	// The access key is the Mac's to check: the relay keeps nothing about any Mac.
	access := b[wire.IDLen:]

	var t token
	rand.Read(t[:])
	w := &waiting{mac: id, ready: make(chan net.Conn, 1), fail: make(chan byte, 1)}
	s.mu.Lock()
	m := s.macs[id]
	switch {
	case m == nil:
		s.mu.Unlock()
		c.Write([]byte{wire.StatusOffline})
		return
	case s.sessions[id] >= wire.MaxSessions:
		s.mu.Unlock()
		c.Write([]byte{wire.StatusBusy})
		return
	}
	s.sessions[id]++
	s.pending[t] = w
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		if s.sessions[id]--; s.sessions[id] <= 0 {
			delete(s.sessions, id)
		}
		s.mu.Unlock()
	}()

	open := append(append([]byte{wire.FrameOpen}, t[:]...), access...)
	if m.send(open) != nil {
		s.forget(t)
		c.Write([]byte{wire.StatusOffline})
		return
	}

	var d net.Conn
	timer := time.NewTimer(openTimeout)
	defer timer.Stop()
	select {
	case d = <-w.ready:
	case st := <-w.fail:
		c.Write([]byte{st})
		return
	case <-timer.C:
		if !s.forget(t) {
			// The data connection took the token in the same instant.
			d = <-w.ready
			break
		}
		c.Write([]byte{wire.StatusNoAnswer})
		return
	}
	defer d.Close()

	if _, err := c.Write([]byte{wire.StatusOK}); err != nil {
		return
	}
	c.SetDeadline(time.Time{})
	start := time.Now()
	up, down := splice(c, d)
	s.Log.Printf("mac %s session from %s: %s, up %d B, down %d B", short(id), hostOf(c.RemoteAddr()), time.Since(start).Round(time.Second), up, down)
}

// forget drops a pending token; false when the Mac's data connection took it first.
func (s *Server) forget(t token) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.pending[t]; !ok {
		return false
	}
	delete(s.pending, t)
	return true
}

// splice passes bytes both ways until either side ends, then closes both.
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

func hostOf(a net.Addr) string {
	if ta, ok := a.(*net.TCPAddr); ok {
		return ta.IP.String()
	}
	h, _, err := net.SplitHostPort(a.String())
	if err != nil {
		return a.String()
	}
	return h
}

func short(id wire.ID) string { return id.String()[:8] }

// --- new connections per address

type limiter struct {
	mu      sync.Mutex
	perSec  float64
	burst   float64
	buckets map[string]*bucket
	swept   time.Time
}

type bucket struct {
	tokens float64
	at     time.Time
}

func newLimiter(perSec, burst float64) *limiter {
	return &limiter{perSec: perSec, burst: burst, buckets: map[string]*bucket{}, swept: time.Now()}
}

func (l *limiter) allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	if now.Sub(l.swept) > time.Minute {
		for k, b := range l.buckets {
			if now.Sub(b.at) > time.Minute {
				delete(l.buckets, k)
			}
		}
		l.swept = now
	}
	b := l.buckets[key]
	if b == nil {
		b = &bucket{tokens: l.burst, at: now}
		l.buckets[key] = b
	}
	b.tokens = min(l.burst, b.tokens+now.Sub(b.at).Seconds()*l.perSec)
	b.at = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}
