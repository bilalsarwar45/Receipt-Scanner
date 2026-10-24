#!/usr/bin/env python3
"""Emulator smoke test: installs the debug APKs, walks through the main screens with adb, and fails on
any crash. Each step prints the visible texts from a UI dump so the CI log shows what was on screen.
Screenshots are saved to build/smoke/ and uploaded as an artifact.

Usage: smoke_test.py <app-debug.apk> [<sample-debug.apk>]
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

OUT = "build/smoke"
os.makedirs(OUT, exist_ok=True)
APP = "com.receiptscan.app.debug"
SAMPLE = "com.receiptscan.sample.scannerhost"
failures = []


def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=120)
    if check and r.returncode != 0:
        print("adb", args, "failed:", r.stderr.strip())
    return r.stdout


def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        if "<hierarchy" in xml:
            return ET.fromstring(xml[xml.index("<hierarchy"):])
        time.sleep(2)
    return None


def texts(root):
    if root is None:
        return []
    out = []
    for n in root.iter("node"):
        t = (n.get("text") or n.get("content-desc") or "").strip()
        if t and t not in out:
            out.append(t)
    return out


def find(root, label, last=False, contains=False):
    if root is None:
        return None
    hits = []
    for n in root.iter("node"):
        t = (n.get("text") or "").strip()
        d = (n.get("content-desc") or "").strip()
        if (t == label or d == label) or (contains and (label in t or label in d)):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
    if not hits:
        return None
    return hits[-1] if last else hits[0]


def crashed(pkg):
    log = adb("logcat", "-d", "-b", "crash", check=False)
    return [l for l in log.splitlines() if pkg in l or "FATAL EXCEPTION" in l]


def step(name, pkg=APP):
    time.sleep(2.5)
    root = dump()
    adb("shell", "screencap", "-p", f"/sdcard/{name}.png", check=False)
    adb("pull", f"/sdcard/{name}.png", f"{OUT}/{name}.png", check=False)
    shown = texts(root)
    print(f"\n=== {name}: {len(shown)} texts")
    print(" | ".join(shown[:60]))
    c = crashed(pkg)
    alive = adb("shell", "pidof", pkg, check=False).strip()
    if c or not alive:
        failures.append(name)
        print(f"!!! {name}: process alive={bool(alive)}; crash log:\n" + "\n".join(c[-40:]))
    return root


def tap(root, label, **kw):
    p = find(root, label, **kw)
    if p is None:
        print(f"(could not find '{label}')")
        return False
    adb("shell", "input", "tap", str(p[0]), str(p[1]))
    return True


def type_text(s):
    adb("shell", "input", "text", s.replace(" ", "%s"))


def main():
    app_apk = sys.argv[1]
    adb("logcat", "-c", check=False)
    print(adb("install", "-r", "-g", app_apk))
    adb("shell", "am", "start", "-W", "-n", f"{APP}/com.receiptscan.app.MainActivity")
    time.sleep(6)
    r = step("01-onboarding")
    tap(r, "Skip")
    r = step("02-home")
    tap(r, "Add manually")
    r = step("03-add-expense-form")
    tap(r, "Amount (PKR)", contains=True)
    time.sleep(1)
    type_text("300")
    adb("shell", "input", "keyevent", "111")  # hide keyboard (ESC)
    r = step("04-amount-typed")
    tap(r, "Fuel")
    r = step("05-category-picked")
    tap(r, "Add expense", last=True)
    r = step("06-after-add-home")
    tap(r, "Library")
    r = step("07-library")
    tap(r, "Expenses")
    r = step("08-library-expenses")
    tap(r, "Tools")
    r = step("09-tools")
    tap(r, "Insights")
    r = step("10-insights")
    tap(r, "Home")
    r = step("11-home-again")
    tap(r, "Ask or add an expense…")
    r = step("12-command-sheet")
    adb("shell", "input", "keyevent", "4")  # back closes the sheet
    r = step("13-sheet-closed")
    tap(r, "Settings")
    r = step("14-settings")
    tap(r, "About")
    r = step("15-about")
    adb("shell", "input", "keyevent", "4")
    adb("shell", "input", "keyevent", "4")
    r = step("16-back-home")
    tap(r, "Scan receipt")
    r = step("17-receipt-camera")
    adb("shell", "input", "keyevent", "4")
    step("18-after-camera-back")

    if len(sys.argv) > 2:
        print(adb("install", "-r", "-g", sys.argv[2]))
        adb("shell", "am", "start", "-W", "-n", f"{SAMPLE}/com.receiptscan.sample.SampleActivity")
        time.sleep(5)
        r = step("20-sample-home", SAMPLE)
        tap(r, "Text")
        step("21-sample-text-scanner", SAMPLE)

    print("\n==== smoke result:", "FAILED at " + ", ".join(failures) if failures else "no crashes")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
