#!/usr/bin/env python3
"""Minimal ADB client and helper for CleanMyPhone.

Usage:
    python tools/phone.py --probe
    python tools/phone.py --screenshot <output.png>
    python tools/phone.py --shell "<command>"
    python tools/phone.py --disable <package>
    python tools/phone.py --enable <package>
    python tools/phone.py --list-disabled
    python tools/phone.py --packages
"""
import os
import subprocess
import sys

ADB_DEVICE = os.environ.get("PHONE_HOST") or os.environ.get("TV_HOST") or "192.168.0.7:5555"

def run_adb(args):
    cmd = ["adb", "-s", ADB_DEVICE] + args
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
    return res.returncode, res.stdout, res.stderr

def probe():
    print(f"Connecting to {ADB_DEVICE}...")
    run_adb(["connect", ADB_DEVICE])
    code, model, _ = run_adb(["shell", "getprop", "ro.product.model"])
    _, brand, _ = run_adb(["shell", "getprop", "ro.product.brand"])
    _, release, _ = run_adb(["shell", "getprop", "ro.build.version.release"])
    _, sdk, _ = run_adb(["shell", "getprop", "ro.build.version.sdk"])
    _, mem, _ = run_adb(["shell", "cat /proc/meminfo | grep MemTotal"])
    print(f"Device: {brand.strip()} {model.strip()} (Android {release.strip()}, SDK {sdk.strip()})")
    print(f"Memory: {mem.strip()}")

def screenshot(path):
    remote = "/sdcard/phone_screenshot.png"
    run_adb(["shell", "screencap", "-p", remote])
    code, out, err = run_adb(["pull", remote, path])
    run_adb(["shell", "rm", remote])
    if code == 0:
        print(f"Screenshot saved to {path}")
    else:
        print(f"Failed to pull screenshot: {err}", file=sys.stderr)

def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        sys.exit(0)

    arg = sys.argv[1]
    if arg == "--probe":
        probe()
    elif arg == "--screenshot":
        if len(sys.argv) < 3:
            sys.exit("Usage: phone.py --screenshot <path.png>")
        screenshot(sys.argv[2])
    elif arg == "--shell":
        if len(sys.argv) < 3:
            sys.exit("Usage: phone.py --shell '<command>'")
        _, out, err = run_adb(["shell", sys.argv[2]])
        if out:
            print(out, end="")
        if err:
            print(err, end="", file=sys.stderr)
    elif arg == "--disable":
        if len(sys.argv) < 3:
            sys.exit("Usage: phone.py --disable <package>")
        pkg = sys.argv[2]
        code, out, err = run_adb(["shell", f"pm disable-user --user 0 {pkg}"])
        print(out.strip() if out else err.strip())
    elif arg == "--enable":
        if len(sys.argv) < 3:
            sys.exit("Usage: phone.py --enable <package>")
        pkg = sys.argv[2]
        code, out, err = run_adb(["shell", f"pm enable {pkg}"])
        print(out.strip() if out else err.strip())
    elif arg == "--list-disabled":
        code, out, _ = run_adb(["shell", "pm list packages -d"])
        print(out, end="")
    elif arg == "--packages":
        code, out, _ = run_adb(["shell", "pm list packages -e"])
        print(out, end="")
    else:
        # Run generic shell command
        code, out, err = run_adb(["shell"] + sys.argv[1:])
        if out:
            print(out, end="")
        if err:
            print(err, end="", file=sys.stderr)

if __name__ == "__main__":
    main()
