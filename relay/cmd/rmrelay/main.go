// rmrelay is the relay between phones and Macs away from home (docs/relay.md). It runs on a server
// with a public address: rmrelay -listen :7456.
package main

import (
	"flag"
	"log"
	"net"

	"dev.remotemac/relay/server"
)

func main() {
	listen := flag.String("listen", ":7456", "address to listen on")
	flag.Parse()
	l, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("relay listening on %s", l.Addr())
	log.Fatal(server.New().Serve(l))
}
