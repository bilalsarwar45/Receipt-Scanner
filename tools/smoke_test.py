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
missed = []


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


def clear_system_dialogs(root, pkg):
    """Emulator system apps (launcher, System UI) sometimes stall on slow CI machines and show an ANR
    dialog over everything. Close those; an ANR in the app under test is recorded as a failure."""
    for _ in range(4):
        shown = " | ".join(texts(root))
        if "isn't responding" not in shown and "keeps stopping" not in shown:
            return root
        ours = ("Receipt" in shown and "Scan isn't responding" in shown) or ("Scanner host" in shown and "isn't responding" in shown)
        if ours:
            failures.append("ANR in app: " + shown[:120])
            print("!!! app not responding:", shown)
            label = "Wait"
        else:
            print("(closing system dialog: " + shown[:80] + ")")
            label = "Wait" if find(root, "Wait") else "Close app"
        p = find(root, label)
        if p is None:
            return root
        adb("shell", "input", "tap", str(p[0]), str(p[1]))
        time.sleep(2)
        root = dump()
    return root


def step(name, pkg=APP):
    time.sleep(2.5)
    root = clear_system_dialogs(dump(), pkg)
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


def tap(root, label, scroll=True, **kw):
    p = find(root, label, **kw)
    tries = 0
    while p is None and scroll and tries < 4:
        adb("shell", "input", "swipe", "540", "1700", "540", "700", "300")
        time.sleep(1.2)
        root = dump()
        p = find(root, label, **kw)
        tries += 1
    if p is None:
        print(f"(could not find '{label}')")
        missed.append(f"tap '{label}'")
        return False
    adb("shell", "input", "tap", str(p[0]), str(p[1]))
    return True


def edits(root):
    out = []
    if root is None:
        return out
    for n in root.iter("node"):
        if n.get("class") == "android.widget.EditText":
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                out.append(((x1 + x2) // 2, (y1 + y2) // 2, n.get("text") or ""))
    return out


def type_into(root, index, s):
    e = edits(root)
    if len(e) <= index:
        print(f"(no text field #{index})")
        missed.append(f"text field #{index}")
        return
    adb("shell", "input", "tap", str(e[index][0]), str(e[index][1]))
    time.sleep(1)
    type_text(s)
    time.sleep(0.5)


def expect(root, name, *labels):
    shown = " | ".join(texts(root)) + " | " + " | ".join(t for _, _, t in edits(root))
    for l in labels:
        if l not in shown:
            print(f"!!! {name}: expected to see '{l}'")
            missed.append(f"{name}: '{l}'")
        else:
            print(f"ok {name}: sees '{l}'")


def type_text(s):
    adb("shell", "input", "text", s.replace(" ", "%s"))


def main():
    app_apk = sys.argv[1]
    # Let the freshly booted emulator settle so its launcher is idle before the app starts.
    adb("shell", "input", "keyevent", "3")
    time.sleep(25)
    clear_system_dialogs(dump(), APP)
    adb("logcat", "-c", check=False)
    print(adb("install", "-r", "-g", app_apk))
    adb("shell", "am", "start", "-W", "-n", f"{APP}/com.receiptscan.app.MainActivity")
    time.sleep(6)
    r = step("01-onboarding")
    tap(r, "Skip")
    r = step("02-home")
    expect(r, "02-home", "Scan receipt", "Add manually")
    tap(r, "Add manually")
    r = step("03-add-expense-form")
    type_into(r, 0, "300")
    adb("shell", "input", "keyevent", "111")
    r = step("04-amount-typed")
    expect(r, "04-amount-typed", "300")
    tap(r, "Fuel")
    r = step("05-category-picked")
    tap(r, "Add expense", last=True, scroll=False)
    r = step("06-after-add-home")
    expect(r, "06-after-add-home", "Added")
    tap(r, "Library", last=True)
    r = step("07-library")
    tap(r, "Expenses", scroll=False)
    r = step("08-library-expenses")
    expect(r, "08-library-expenses", "300", "Fuel")
    tap(r, "Tools", last=True, scroll=False)
    r = step("09-tools")
    expect(r, "09-tools", "QR code", "Plants", "Calories")
    tap(r, "Insights", last=True, scroll=False)
    r = step("10-insights")
    expect(r, "10-insights", "300")
    tap(r, "Home", last=True, scroll=False)
    r = step("11-home-again")
    tap(r, "Ask or add an expense…")
    r = step("12-command-sheet")
    type_into(r, 0, "spent 450 on groceries")
    adb("shell", "input", "keyevent", "66")  # enter submits
    r = step("13-command-typed")
    expect(r, "13-command-typed", "450")
    adb("shell", "input", "keyevent", "4")
    adb("shell", "input", "keyevent", "4")
    r = step("14-sheet-closed")
    if find(r, "Settings") is None:
        adb("shell", "input", "keyevent", "4")
        r = step("14b-sheet-closed")
    tap(r, "Settings", scroll=False)
    r = step("15-settings")
    tap(r, "About")
    r = step("16-about")
    expect(r, "16-about", "Signing key")
    adb("shell", "input", "keyevent", "4")
    r = step("17-settings-again")
    adb("shell", "input", "keyevent", "4")
    r = step("18-back-home")
    tap(r, "Scan receipt", scroll=False)
    r = step("19-receipt-camera")
    adb("shell", "input", "keyevent", "4")
    step("20-after-camera-back")

    if len(sys.argv) > 2 and sys.argv[2]:
        print(adb("install", "-r", "-g", sys.argv[2]))
        adb("shell", "am", "start", "-W", "-n", f"{SAMPLE}/com.receiptscan.sample.SampleActivity")
        time.sleep(5)
        r = step("30-sample-home", SAMPLE)
        expect(r, "30-sample-home", "QR code", "Text", "Plants")
        tap(r, "Text", scroll=False)
        step("31-sample-text-scanner", SAMPLE)

    print("\n==== smoke result:", ("CRASHED at " + ", ".join(failures)) if failures else "no crashes")
    print("==== expectations missed:", "; ".join(missed) if missed else "none")
    sys.exit(1 if failures or missed else 0)


if __name__ == "__main__":
    main()
