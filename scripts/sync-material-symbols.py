#!/usr/bin/env python3
"""Import unmodified Google Material Symbols paths as Android vectors."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import subprocess
import hashlib
import xml.etree.ElementTree as ET

REVISION = "49d4db35df873165d6bd6ba09b063c7dafbac2f4"
BASE = f"https://raw.githubusercontent.com/google/material-design-icons/{REVISION}"
ROOT = Path(__file__).resolve().parents[1]
# resource name: (official symbol, filled). Brand/launcher artwork is intentionally separate.
ICONS = {
    "animation": ("animation", True), "back": ("arrow_back", False),
    "bluetooth": ("bluetooth", False), "calendar": ("calendar_month", True),
    "check": ("check", False), "check_circle": ("check_circle", True),
    "chevron": ("chevron_right", False), "clock": ("schedule", True),
    "code": ("code", False),
    "delete_sweep": ("delete_sweep", True), "download": ("download", True),
    "eye": ("visibility", True), "eye_off": ("visibility_off", True),
    "faucet": ("faucet", True), "flashlight": ("flashlight_on", True),
    "gavel": ("gavel", True), "history": ("history", False),
    "home": ("home", True), "home_outline": ("home", False),
    "info": ("info", True), "lock": ("lock", True),
    "logout": ("logout", False), "notifications": ("notifications", True),
    "open_in_new": ("open_in_new", False), "palette": ("palette", True),
    "person": ("person", True), "person_outline": ("person", False),
    "phone": ("phone_iphone", True), "photo": ("image", True),
    "receipt": ("receipt_long", True), "refresh": ("refresh", False),
    "scan": ("qr_code_scanner", False), "shield": ("shield", True),
    "shower": ("shower", True), "star": ("star", False),
    "star_filled": ("star", True), "update": ("update", False),
    "vibration": ("mobile_vibrate", True), "wallet": ("account_balance_wallet", True),
    "wallet_outline": ("account_balance_wallet", False), "water": ("water_drop", True),
    "display_settings": ("text_fields", True),
}
# Use the Outlined filled/700 glyph at optical size 48 for animation.
SVG_OVERRIDES = {
    "animation": (
        "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/animation/wght700fill1/48px.svg",
        "55ddbee4a15dac36b34ae967f5b5515f6a7f34c16dc0b0c6a2c1d96bad2a8753",
    ),
}
ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)

def download(url):
    return subprocess.check_output(["curl", "-fLsS", "--retry", "2", "--max-time", "20", url])

def fetch(item):
    resource, (symbol, filled) = item
    if resource in SVG_OVERRIDES:
        url, digest = SVG_OVERRIDES[resource]
        raw = download(url)
        if hashlib.sha256(raw).hexdigest() != digest:
            raise ValueError(f"Upstream glyph changed: {url}")
        svg = ET.fromstring(raw)
        if svg.get("viewBox") != "0 -960 960 960":
            raise ValueError(f"Unexpected SVG viewport: {url}")
        vector = ET.Element("vector", {
            f"{{{ANDROID}}}width": "24dp", f"{{{ANDROID}}}height": "24dp",
            f"{{{ANDROID}}}viewportWidth": "960", f"{{{ANDROID}}}viewportHeight": "960",
        })
        group = ET.SubElement(vector, "group", {f"{{{ANDROID}}}translateY": "960"})
        for path in svg.findall("{http://www.w3.org/2000/svg}path"):
            ET.SubElement(group, "path", {
                f"{{{ANDROID}}}fillColor": "@android:color/white",
                f"{{{ANDROID}}}pathData": path.attrib["d"],
            })
        ET.indent(vector, space="    ")
        return resource, f"<!-- Google Material Symbols; {url}; sha256={digest}; Apache-2.0. -->\n" + ET.tostring(vector, encoding="unicode") + "\n"
    filename = f"{symbol}{'_fill1' if filled else ''}_24px.xml"
    path = f"symbols/android/{symbol}/materialsymbolsrounded/{filename}"
    raw = download(f"{BASE}/{path}")
    vector = ET.fromstring(raw)
    # Compose supplies the semantic tint. Do not depend on the framework's view theme.
    vector.attrib.pop(f"{{{ANDROID}}}tint", None)
    ET.indent(vector, space="    ")
    return resource, f"<!-- Google Material Symbols Rounded; {symbol}; fill={int(filled)}; {REVISION}. Apache-2.0. -->\n" + ET.tostring(vector, encoding="unicode") + "\n"

def main():
    # Fetch/validate the complete set before replacing any resource.
    with ThreadPoolExecutor(max_workers=6) as pool:
        resources = list(pool.map(fetch, ICONS.items()))
    license_text = download(f"{BASE}/LICENSE")
    for resource, xml in resources:
        (ROOT / f"app/src/main/res/drawable/ic_{resource}.xml").write_text(xml)
    license_path = ROOT / "app/src/main/assets/third_party/material-symbols-LICENSE.txt"
    license_path.parent.mkdir(parents=True, exist_ok=True)
    license_path.write_bytes(license_text)
    print(f"Imported {len(resources)} Material Symbols at {REVISION}")

if __name__ == "__main__":
    main()
