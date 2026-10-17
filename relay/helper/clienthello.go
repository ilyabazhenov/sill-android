package helper

import (
	"errors"
	"io"
)

const (
	recordHandshake      = 22
	maxRecord            = 16384 + 256
	maxClientHello       = 64 << 10
	extensionALPN        = 16
	handshakeClientHello = 1
)

var errNotClientHello = errors.New("the phone didn't start a TLS handshake")

// ReadClientHello reads the TLS records that carry the phone's ClientHello and returns them as
// read (to pass on to Sill unchanged) with the ALPN protocols it offers.
func ReadClientHello(r io.Reader) (raw []byte, protocols []string, err error) {
	var msg []byte
	for {
		if len(msg) >= 4 {
			if n := int(msg[1])<<16 | int(msg[2])<<8 | int(msg[3]); len(msg) >= 4+n {
				msg = msg[:4+n]
				break
			}
		}
		hdr := make([]byte, 5)
		if _, err := io.ReadFull(r, hdr); err != nil {
			return nil, nil, err
		}
		n := int(hdr[3])<<8 | int(hdr[4])
		if hdr[0] != recordHandshake || hdr[1] != 3 || n == 0 || n > maxRecord {
			return nil, nil, errNotClientHello
		}
		body := make([]byte, n)
		if _, err := io.ReadFull(r, body); err != nil {
			return nil, nil, err
		}
		raw = append(append(raw, hdr...), body...)
		msg = append(msg, body...)
		if len(raw) > maxClientHello {
			return nil, nil, errNotClientHello
		}
	}
	if msg[0] != handshakeClientHello {
		return nil, nil, errNotClientHello
	}
	protocols, err = alpnOf(msg[4:])
	return raw, protocols, err
}

type reader struct {
	b   []byte
	bad bool
}

func (r *reader) take(n int) []byte {
	if r.bad || n < 0 || n > len(r.b) {
		r.bad = true
		return nil
	}
	out := r.b[:n]
	r.b = r.b[n:]
	return out
}

func (r *reader) u8() int {
	b := r.take(1)
	if b == nil {
		return 0
	}
	return int(b[0])
}

func (r *reader) u16() int {
	b := r.take(2)
	if b == nil {
		return 0
	}
	return int(b[0])<<8 | int(b[1])
}

// alpnOf finds the ALPN extension in a ClientHello body; none is an empty list.
func alpnOf(body []byte) ([]string, error) {
	r := &reader{b: body}
	r.take(2 + 32)  // legacy_version, random
	r.take(r.u8())  // legacy_session_id
	r.take(r.u16()) // cipher_suites
	r.take(r.u8())  // legacy_compression_methods
	ext := &reader{b: r.take(r.u16())}
	if r.bad {
		return nil, errNotClientHello
	}
	var protocols []string
	seen := false
	for len(ext.b) > 0 && !ext.bad {
		typ := ext.u16()
		data := ext.take(ext.u16())
		if ext.bad {
			break
		}
		if typ != extensionALPN {
			continue
		}
		if seen {
			return nil, errNotClientHello
		}
		seen = true
		list := &reader{b: data}
		names := &reader{b: list.take(list.u16())}
		if list.bad || len(list.b) != 0 {
			return nil, errNotClientHello
		}
		for len(names.b) > 0 {
			name := names.take(names.u8())
			if names.bad || len(name) == 0 {
				return nil, errNotClientHello
			}
			protocols = append(protocols, string(name))
		}
	}
	if ext.bad {
		return nil, errNotClientHello
	}
	return protocols, nil
}
