// rmhelper keeps this Mac on the relay, so a phone away from home reaches Sill through it
// (docs/relay.md). Sill stays as it is; turn on its Remote Access.
//
//	rmhelper install -relay HOST:PORT   set up, start at login, print the QR code for the phone
//	rmhelper link [-png FILE]            the QR code again
//	rmhelper new-access                  a new access key: phones with the old QR code are turned away
//	rmhelper run                         run in the foreground (what launchd runs)
//	rmhelper uninstall                   stop and remove the login item
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"

	"dev.remotemac/relay/helper"
	"rsc.io/qr"
)

const label = "dev.remotemac.helper"

func main() {
	log.SetFlags(log.LstdFlags)
	if len(os.Args) < 2 {
		usage()
	}
	cmd, args := os.Args[1], os.Args[2:]
	var err error
	switch cmd {
	case "install":
		err = install(args)
	case "uninstall":
		err = uninstall()
	case "run":
		err = run(args)
	case "link":
		err = link(args)
	case "new-access":
		err = newAccess()
	default:
		usage()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "rmhelper:", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, `usage:
  rmhelper install -relay HOST:PORT [-sill-port 7455]
  rmhelper link [-png FILE] [-refresh]
  rmhelper new-access
  rmhelper run [-relay HOST:PORT] [-sill-port N]
  rmhelper uninstall`)
	os.Exit(2)
}

func dir() string {
	d, err := os.UserConfigDir()
	if err != nil {
		log.Fatal(err)
	}
	return filepath.Join(d, "RemoteMac Helper")
}

func configPath() string { return filepath.Join(dir(), "config.json") }

func loadConfig() (helper.Config, error) {
	cfg := helper.Config{SillPort: helper.DefaultSillPort}
	b, err := os.ReadFile(configPath())
	if errors.Is(err, os.ErrNotExist) {
		return cfg, nil
	}
	if err != nil {
		return cfg, err
	}
	return cfg, json.Unmarshal(b, &cfg)
}

func saveConfig(cfg helper.Config) error {
	if err := os.MkdirAll(dir(), 0o700); err != nil {
		return err
	}
	b, _ := json.MarshalIndent(cfg, "", "  ")
	return os.WriteFile(configPath(), append(b, '\n'), 0o600)
}

// configFlags lets the command line override the saved config.
func configFlags(fs *flag.FlagSet, args []string) (helper.Config, error) {
	cfg, err := loadConfig()
	if err != nil {
		return cfg, err
	}
	relay := fs.String("relay", cfg.Relay, "the relay's HOST:PORT")
	port := fs.Int("sill-port", cfg.SillPort, "Sill's remote door port (Sill → Settings → Remote Access)")
	if err := fs.Parse(args); err != nil {
		return cfg, err
	}
	cfg.Relay, cfg.SillPort = *relay, *port
	if cfg.Relay == "" {
		return cfg, errors.New("no relay: pass -relay HOST:PORT")
	}
	if _, p, err := net.SplitHostPort(cfg.Relay); err != nil || p == "" {
		return cfg, fmt.Errorf("the relay is HOST:PORT, not %q", cfg.Relay)
	}
	return cfg, nil
}

func run(args []string) error {
	cfg, err := configFlags(flag.NewFlagSet("run", flag.ExitOnError), args)
	if err != nil {
		return err
	}
	key, err := helper.LoadKey(dir())
	if err != nil {
		return err
	}
	access, err := helper.LoadAccess(dir(), false)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	log.Printf("Mac id %s, relay %s, Sill at 127.0.0.1:%d", helper.IDOf(key), cfg.Relay, cfg.SillPort)
	helper.New(cfg, key, access, log.Default()).Run(ctx)
	return nil
}

func install(args []string) error {
	cfg, err := configFlags(flag.NewFlagSet("install", flag.ExitOnError), args)
	if err != nil {
		return err
	}
	if _, err := helper.LoadKey(dir()); err != nil {
		return err
	}
	if _, err := helper.LoadAccess(dir(), false); err != nil {
		return err
	}
	// Sill's key, read now and kept: each read is a refused handshake at Sill's door.
	if cfg.SillKey == "" {
		if fp, err := helper.SillFingerprint(cfg.SillPort); err == nil {
			cfg.SillKey = base64.RawURLEncoding.EncodeToString(fp)
		} else {
			fmt.Println(err)
		}
	}
	if err := saveConfig(cfg); err != nil {
		return err
	}

	// A copy of this binary next to the config, so moving or rebuilding the original changes nothing.
	self, err := os.Executable()
	if err != nil {
		return err
	}
	bin := filepath.Join(dir(), "rmhelper")
	if err := copyFile(self, bin); err != nil {
		return err
	}
	home, _ := os.UserHomeDir()
	logPath := filepath.Join(home, "Library", "Logs", "RemoteMac Helper.log")
	plist := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key><string>%s</string>
	<key>ProgramArguments</key><array><string>%s</string><string>run</string></array>
	<key>RunAtLoad</key><true/>
	<key>KeepAlive</key><true/>
	<key>ProcessType</key><string>Interactive</string>
	<key>StandardOutPath</key><string>%s</string>
	<key>StandardErrorPath</key><string>%s</string>
</dict>
</plist>
`, label, xmlEscape(bin), xmlEscape(logPath), xmlEscape(logPath))
	agents := filepath.Join(home, "Library", "LaunchAgents")
	if err := os.MkdirAll(agents, 0o755); err != nil {
		return err
	}
	plistPath := filepath.Join(agents, label+".plist")
	if err := os.WriteFile(plistPath, []byte(plist), 0o644); err != nil {
		return err
	}
	domain := "gui/" + strconv.Itoa(os.Getuid())
	exec.Command("launchctl", "bootout", domain+"/"+label).Run()
	if out, err := exec.Command("launchctl", "bootstrap", domain, plistPath).CombinedOutput(); err != nil {
		return fmt.Errorf("launchctl bootstrap: %v: %s", err, strings.TrimSpace(string(out)))
	}
	fmt.Printf("Running, and again at every login. Log: %s\n\n", logPath)
	if cfg.SillKey == "" {
		fmt.Println("Turn on Remote Access in Sill, then run rmhelper link for the phone's QR code.")
		return nil
	}
	return link(nil)
}

func uninstall() error {
	home, _ := os.UserHomeDir()
	plistPath := filepath.Join(home, "Library", "LaunchAgents", label+".plist")
	exec.Command("launchctl", "bootout", "gui/"+strconv.Itoa(os.Getuid())+"/"+label).Run()
	if err := os.Remove(plistPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	fmt.Printf("Stopped. The key and config stay in %s: delete that folder to forget this Mac's relay id.\n", dir())
	return nil
}

func link(args []string) error {
	fs := flag.NewFlagSet("link", flag.ExitOnError)
	png := fs.String("png", "", "also save the QR code as a PNG")
	refresh := fs.Bool("refresh", false, "read Sill's key again (after Sill was set up anew)")
	cfg, err := configFlags(fs, args)
	if err != nil {
		return err
	}
	key, err := helper.LoadKey(dir())
	if err != nil {
		return err
	}
	access, err := helper.LoadAccess(dir(), false)
	if err != nil {
		return err
	}
	fp, _ := base64.RawURLEncoding.DecodeString(cfg.SillKey)
	if *refresh || len(fp) != 32 {
		if fp, err = helper.SillFingerprint(cfg.SillPort); err != nil {
			return err
		}
		if saved, err := loadConfig(); err == nil && saved.Relay != "" {
			saved.SillKey = base64.RawURLEncoding.EncodeToString(fp)
			saveConfig(saved)
		}
	}
	l := helper.Link(cfg, key, access, fp, computerName())
	code, err := qr.Encode(l, qr.L)
	if err != nil {
		return err
	}
	fmt.Println("On the phone: pair with this Mac first (at home), then scan this with the camera.")
	fmt.Println("Keep it to yourself: it opens the way to Sill's door (a paired phone is still needed).")
	fmt.Println()
	printQR(os.Stdout, code)
	fmt.Println()
	fmt.Println(l)
	if *png != "" {
		if err := os.WriteFile(*png, code.PNG(), 0o644); err != nil {
			return err
		}
		fmt.Println("Saved", *png)
	}
	return nil
}

func newAccess() error {
	if _, err := helper.LoadAccess(dir(), true); err != nil {
		return err
	}
	exec.Command("launchctl", "kickstart", "-k", "gui/"+strconv.Itoa(os.Getuid())+"/"+label).Run()
	fmt.Println("New access key: phones with the old QR code can't use the relay any more.")
	fmt.Println("Also remove a lost phone from the devices in Sill on this Mac. Scan this on the phones you keep:")
	fmt.Println()
	return link(nil)
}

func computerName() string {
	if out, err := exec.Command("scutil", "--get", "ComputerName").Output(); err == nil {
		return strings.TrimSpace(string(out))
	}
	h, _ := os.Hostname()
	return h
}

// printQR draws the code with half blocks, two modules per character, black on white with a
// quiet zone, whatever the terminal's own colors.
func printQR(w io.Writer, c *qr.Code) {
	const quiet = 2
	n := c.Size
	black := func(x, y int) bool {
		if x < 0 || y < 0 || x >= n || y >= n {
			return false
		}
		return c.Black(x, y)
	}
	for y := -quiet; y < n+quiet; y += 2 {
		var b strings.Builder
		for x := -quiet; x < n+quiet; x++ {
			top, bottom := black(x, y), black(x, y+1)
			fg, bg := "97", "107"
			if top {
				fg = "30"
			}
			if bottom {
				bg = "40"
			}
			fmt.Fprintf(&b, "\x1b[%s;%sm▀", fg, bg)
		}
		b.WriteString("\x1b[0m\n")
		io.WriteString(w, b.String())
	}
}

func copyFile(from, to string) error {
	in, err := os.Open(from)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := to + ".new"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, to)
}

func xmlEscape(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;").Replace(s)
}
