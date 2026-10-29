// Signs a Fleet Telemetry configuration as Tesla's vehicle-command proxy does: a fleet-wide JWS
// (Schnorr over P-256) for the "TelemetryClient" application, by the Tesla application's private
// key. Used by scripts/tesla-telemetry.sh in place of Tesla's tesla-jws, whose command-line package
// pulls in the macOS Bluetooth connector and can't be built for a Mac without cgo; this needs only
// the key and signing packages.
//
//	tesla-sign KEY_FILE < config.json
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"

	"github.com/golang-jwt/jwt/v5"
	"github.com/teslamotors/vehicle-command/pkg/protocol"
	"github.com/teslamotors/vehicle-command/pkg/sign"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: tesla-sign KEY_FILE < config.json")
		os.Exit(2)
	}
	key, err := protocol.LoadPrivateKey(os.Args[1])
	if err != nil {
		fmt.Fprintf(os.Stderr, "couldn't load the private key: %s\n", err)
		os.Exit(1)
	}
	config, err := io.ReadAll(os.Stdin)
	if err != nil {
		fmt.Fprintf(os.Stderr, "couldn't read the configuration: %s\n", err)
		os.Exit(1)
	}
	var claims jwt.MapClaims
	if err := json.Unmarshal(config, &claims); err != nil {
		fmt.Fprintf(os.Stderr, "the configuration isn't a JSON object: %s\n", err)
		os.Exit(1)
	}
	token, err := sign.SignMessageForFleet(key, "TelemetryClient", claims)
	if err != nil {
		fmt.Fprintf(os.Stderr, "couldn't sign: %s\n", err)
		os.Exit(1)
	}
	fmt.Println(token)
}
