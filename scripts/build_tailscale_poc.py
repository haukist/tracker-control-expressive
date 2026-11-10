#!/usr/bin/env python3
"""Build and validate the source-built Tailscale Android bindings."""

import argparse
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


MOBILE_VERSION = "v0.0.0-20261011034528-197770444867"
NDK_VERSION = "29.0.14206865"
# Upstream features this app never uses are compiled out, not just left
# disabled: inbound services, remote access and administration, routing roles
# outside the feature's scope, and every log/telemetry uploader. The first tag
# is mandatory (New refuses to start without it); the rest shrink the binary
# and what a compromised or misbehaving control plane could ask it to do.
OMITTED_FEATURES = (
    "peerapiserver", "ssh", "taildrop", "serve", "webclient", "drive", "capture",
    "relayserver", "advertiseroutes", "advertiseexitnode", "useexitnode",
    "appconnectors", "wakeonlan", "clientupdate", "kube", "aws", "bird", "tap",
    "tpm", "acme", "outboundproxy", "systray", "completion", "desktop_sessions",
    "flashappliance", "synology", "debugportmapper", "logtail", "netlog", "posture",
)
BUILD_TAGS = ",".join(f"ts_omit_{feature}" for feature in OMITTED_FEATURES)
ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def find_tool(name: str, supplied: str | None) -> str:
    path = shutil.which(supplied or name)
    if path is None:
        if supplied:
            raise RuntimeError(f"Specified {name} executable not found: {supplied}")
        raise RuntimeError(f"Missing {name}; install it explicitly (see tailscale-go/README.md).")
    return str(Path(path).resolve())


def check_mobile_tool(go: str, tool: str) -> None:
    result = subprocess.run(
        [go, "version", "-m", tool], check=True, capture_output=True, text=True
    )
    expected = ["mod", "golang.org/x/mobile", MOBILE_VERSION]
    if not any(line.split()[:3] == expected for line in result.stdout.splitlines()):
        raise RuntimeError(f"{tool} must be built from golang.org/x/mobile@{MOBILE_VERSION}.")


def build_inputs(tools: dict[str, str]) -> dict:
    """Describe the same toolchain the build will run, before Gradle skips it."""
    env = os.environ.copy()
    env["GOTOOLCHAIN"] = "local"
    names = ["GOROOT", "GOTOOLDIR", "GOVERSION", "GOFLAGS", "GOEXPERIMENT",
             "GOOS", "GOARCH", "GOARM", "CGO_ENABLED"]
    result = subprocess.run([tools["go"], "env", "-json", *names], check=True,
                            capture_output=True, text=True, env=env)
    go_env = json.loads(result.stdout)
    files = set(tools.values())
    files.add(str(Path(sys.executable).resolve()))
    # The Go driver delegates compilation to these binaries. Track their
    # contents too, including replacements that leave go.exe unchanged.
    files.add(str(Path(go_env["GOTOOLDIR"]).resolve()))
    version = Path(go_env["GOROOT"]) / "VERSION"
    if version.is_file():
        files.add(str(version.resolve()))
    return {"tools": tools, "goEnv": go_env, "files": sorted(files)}


def validate_aar(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        found = {
            name.split("/")[1]
            for name in names
            if len(name.split("/")) == 3 and name.startswith("jni/") and name.endswith("/libgojni.so")
        }
        if found != ABIS:
            raise RuntimeError(f"AAR ABI mismatch: expected {sorted(ABIS)}, got {sorted(found)}")
        for abi in ABIS:
            validate_elf_alignment(archive.read(f"jni/{abi}/libgojni.so"), abi)
        if "AndroidManifest.xml" not in names:
            raise RuntimeError("AAR is missing AndroidManifest.xml.")
        manifest = ET.fromstring(archive.read("AndroidManifest.xml"))
        uses_sdk = manifest.find("uses-sdk")
        if uses_sdk is None or uses_sdk.get(ANDROID_NS + "minSdkVersion") != "23":
            raise RuntimeError("AAR must declare minSdkVersion 23.")


def validate_elf_alignment(data: bytes, abi: str) -> None:
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[5] != 1:
        raise RuntimeError(f"Invalid ELF library for {abi}.")
    if data[4] == 1:
        table = struct.unpack_from("<I", data, 28)[0]
        entry_size, count = struct.unpack_from("<HH", data, 42)
        minimum_size, alignment_offset, alignment_format = 32, 28, "<I"
    elif data[4] == 2:
        table = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
        minimum_size, alignment_offset, alignment_format = 56, 48, "<Q"
    else:
        raise RuntimeError(f"Unsupported ELF class for {abi}.")
    if entry_size < minimum_size or table + entry_size * count > len(data):
        raise RuntimeError(f"Invalid ELF program headers for {abi}.")
    load_segments = 0
    for i in range(count):
        offset = table + i * entry_size
        if struct.unpack_from("<I", data, offset)[0] == 1:  # PT_LOAD
            load_segments += 1
            if struct.unpack_from(alignment_format, data, offset + alignment_offset)[0] < 16384:
                raise RuntimeError(f"{abi} ELF load segments must have at least 16 KB alignment.")
    if not load_segments:
        raise RuntimeError(f"No ELF load segments for {abi}.")


def bind_command(mobile: str, output: Path) -> list[str]:
    """The gomobile invocation that produces the shipped AAR."""
    return [mobile, "bind", "-target=android", "-androidapi=23", f"-tags={BUILD_TAGS}",
            "-trimpath", "-ldflags=-buildid= -s -w -extldflags=-Wl,-z,max-page-size=16384",
            "-o", str(output), "."]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--go", help="Go executable")
    parser.add_argument("--gomobile", help="Pinned gomobile executable")
    parser.add_argument("--gobind", help="Pinned gobind executable")
    parser.add_argument("--print-tags", action="store_true", help="Print the Go build tags and exit")
    parser.add_argument("--print-build-inputs", action="store_true", help="Print toolchain inputs as JSON without building")
    parser.add_argument("--python-launcher", help="Python launcher used by Gradle (input tracking)")
    parser.add_argument("--android-sdk", help="Android SDK directory; otherwise use ANDROID_HOME")
    parser.add_argument("--android-ndk", help=f"NDK {NDK_VERSION} directory; otherwise use ANDROID_NDK_HOME or SDK/ndk")
    args = parser.parse_args()
    if args.print_tags:
        print(BUILD_TAGS)
        return 0
    root = Path(__file__).resolve().parents[1]
    go = find_tool("go", args.go)
    mobile = find_tool("gomobile", args.gomobile)
    bind = find_tool("gobind", args.gobind)
    if args.print_build_inputs:
        tools = {"python": find_tool("Python", args.python_launcher or sys.executable),
                 "go": go, "gomobile": mobile, "gobind": bind}
        print(json.dumps(build_inputs(tools), sort_keys=True))
        return 0
    check_mobile_tool(go, mobile)
    check_mobile_tool(go, bind)

    # gomobile invokes go and gobind by name. Avoid an unrelated version in PATH.
    if Path(mobile).parent != Path(bind).parent:
        raise RuntimeError("Install gomobile and gobind into the same tool directory.")
    env = os.environ.copy()
    env["GOTOOLCHAIN"] = "local"
    env["PATH"] = os.pathsep.join(filter(None, [str(Path(go).parent), str(Path(bind).parent), env.get("PATH")]))
    if args.android_sdk:
        env["ANDROID_HOME"] = str(Path(args.android_sdk).resolve())
    if not (env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")):
        raise RuntimeError("Set ANDROID_HOME or provide --android-sdk.")
    sdk = Path(env.get("ANDROID_HOME") or env["ANDROID_SDK_ROOT"])
    ndk = Path(args.android_ndk or env.get("ANDROID_NDK_HOME") or sdk / "ndk" / NDK_VERSION).resolve()
    properties = ndk / "source.properties"
    if not properties.is_file() or not any(
        line.strip() == f"Pkg.Revision = {NDK_VERSION}"
        for line in properties.read_text(encoding="utf-8").splitlines()
    ):
        raise RuntimeError(f"Install NDK {NDK_VERSION} or pass its directory with --android-ndk.")
    env["ANDROID_NDK_HOME"] = str(ndk)

    output = root / "tailscale-go" / "build" / "tailscale.aar"
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(bind_command(mobile, output), cwd=root / "tailscale-go", env=env, check=True)
    validate_aar(output)
    print(f"Validated Tailscale AAR: {output}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except subprocess.CalledProcessError as error:
        print(error, file=sys.stderr)
        if error.stderr:
            print(error.stderr, file=sys.stderr)
        sys.exit(error.returncode or 1)
    except (RuntimeError, OSError, zipfile.BadZipFile, ET.ParseError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
