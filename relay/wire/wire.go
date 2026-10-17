// Package wire is the relay's protocol, shared by the relay (rmrelay) and the Mac's helper
// (rmhelper). See docs/relay.md.
package wire

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"time"
)

// Magic opens every connection to the relay.
const Magic = "RMR1"

// Roles: the byte after Magic.
const (
	RoleMac   = 'M' // the Mac's one long-lived control connection
	RoleData  = 'D' // a Mac's connection for one phone session
	RolePhone = 'C' // a phone asking for its Mac
)

// Statuses the relay answers with.
const (
	StatusOK       byte = 0
	StatusOffline  byte = 1    // to a phone: the Mac isn't connected to the relay
	StatusBusy     byte = 2    // to a phone: the Mac has MaxSessions already
	StatusNoAnswer byte = 3    // to a phone: the Mac didn't open its side in time
	StatusRefused  byte = 4    // to a phone: the helper couldn't reach Sill
	StatusDenied   byte = 5    // to a phone: wrong access key (an old QR code, or not this Mac's)
	StatusBadAuth  byte = 0x10 // to a Mac: the signature didn't verify
)

// Frames on the control connection: one byte, then for Open a token and the phone's access key,
// for Failed a token and the status to give the phone.
const (
	FrameOpen   = 'O' // relay → Mac: open a data connection with this token
	FrameFailed = 'F' // Mac → relay: no session for this token, and why
	FramePing   = 'P'
	FramePong   = 'p'
)

const (
	IDLen       = 16
	TokenLen    = 16
	NonceLen    = 32
	AccessLen   = 16
	MaxSessions = 8

	HandshakeTimeout = 10 * time.Second
	PingEvery        = 25 * time.Second
	DeadAfter        = 70 * time.Second
)

// ID is a Mac's address on the relay: the first 16 bytes of SHA-256 of its public key.
type ID [IDLen]byte

func IDOf(pub ed25519.PublicKey) ID {
	sum := sha256.Sum256(pub)
	var id ID
	copy(id[:], sum[:IDLen])
	return id
}

func (id ID) String() string { return hex.EncodeToString(id[:]) }

func ParseID(s string) (ID, error) {
	var id ID
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != IDLen {
		return id, fmt.Errorf("a Mac id is %d hex characters", IDLen*2)
	}
	copy(id[:], b)
	return id, nil
}

// Challenge is what a Mac signs to prove it holds its key.
func Challenge(nonce []byte) []byte {
	return append([]byte("remote-mac relay v1 mac"), nonce...)
}

// Hello is the start of a connection in a role.
func Hello(role byte) []byte { return append([]byte(Magic), role) }

// ReadFull reads exactly n bytes.
func ReadFull(r io.Reader, n int) ([]byte, error) {
	b := make([]byte, n)
	_, err := io.ReadFull(r, b)
	return b, err
}
