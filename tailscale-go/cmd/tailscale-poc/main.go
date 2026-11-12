// tailscale-poc is a manual, stdin-driven integration harness. It never creates
// OS routes or reads the device TUN. State may contain keys: keep it private.
package main

import (
	"bufio"
	"encoding/hex"
	"flag"
	"fmt"
	"os"
	"strings"

	bridge "trackercontrol.org/tailscale"
)

func main() {
	state := flag.String("state-dir", "", "absolute private state directory (required)")
	hostname := flag.String("hostname", "trackercontrol-poc", "Tailnet device name")
	flag.Parse()
	client, err := bridge.New(*state, *hostname, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	defer client.Close()
	fmt.Println("Commands: status, login, send <IP packet hex>, read, network-changed, quit")
	fmt.Println("status can contain a sensitive login URL; do not redirect it to shared logs.")
	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		command := strings.TrimSpace(scanner.Text())
		if command == "quit" {
			return
		}
		if err := run(client, command); err != nil {
			fmt.Fprintln(os.Stderr, err)
		}
	}
	if err := scanner.Err(); err != nil {
		fmt.Fprintln(os.Stderr, err)
	}
}

func run(client *bridge.Client, command string) error {
	switch {
	case command == "status":
		status, err := client.StatusJSON()
		if err == nil {
			fmt.Println(status)
		}
		return err
	case command == "login":
		return client.Login()
	case command == "network-changed":
		return client.NetworkChanged("")
	case command == "read":
		packet, err := client.ReadPacket()
		if err == nil {
			fmt.Println(hex.EncodeToString(packet))
		}
		return err
	case strings.HasPrefix(command, "send "):
		packet, err := hex.DecodeString(strings.TrimSpace(strings.TrimPrefix(command, "send ")))
		if err != nil {
			return err
		}
		return client.SendPacket(packet)
	default:
		return fmt.Errorf("unknown command")
	}
}
