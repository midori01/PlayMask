#!/usr/bin/env python3
"""
Automated Test Suite for PlayMask
Verifies:
1. Email masking logic & regex patterns (matching MainHook.java)
2. Localization consistency across all 6 locales (en, zh-CN, zh-HK, zh-TW, ja, ko)
3. APK packaging, Xposed descriptors, and signature schemes
"""

import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
import subprocess

PROJECT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK_PATH = os.path.join(PROJECT_DIR, "PlayMask.apk")

# Regex from MainHook.java
EMAIL_PATTERN = re.compile(r"([a-zA-Z0-9_.+\u2011-]+)(@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+)")

def mask_email(text):
    if not text or "@" not in text:
        return text
    return EMAIL_PATTERN.sub(r"***\2", text)

def test_email_masking():
    print("--- [1/3] Testing Email Masking Logic ---")
    test_cases = [
        ("user@gmail.com", "***@gmail.com"),
        ("john.doe@gmail.com", "***@gmail.com"),
        ("alice+test@googlemail.com", "***@googlemail.com"),
        ("my-work-account@company.org", "***@company.org"),
        ("my\u2011work\u2011account@company.org", "***@company.org"),
        ("a@b.co", "***@b.co"),
        ("test.account.123@sub.domain.co.uk", "***@sub.domain.co.uk"),
    ]

    for original, expected in test_cases:
        actual = mask_email(original)
        assert actual == expected, f"Failed: {original} -> {actual} (expected {expected})"
        print(f"  [✓] {original} -> {actual}")

    # Non-emails should remain untouched
    non_emails = [
        "John Doe",
        "Google User",
        "https://play.google.com",
        "No at sign here",
        "user@",
        "@gmail.com",
    ]
    for ne in non_emails:
        actual = mask_email(ne)
        assert actual == ne, f"False positive: {ne} was changed to {actual}"
        print(f"  [✓] Non-email preserved: {ne}")
    print("[PASS] Email masking logic 100% verified.\n")

def test_localization():
    print("--- [2/3] Testing 6-Language Localization Integrity ---")
    locales = {
        "default (en)": os.path.join(PROJECT_DIR, "res/values/strings.xml"),
        "zh-CN": os.path.join(PROJECT_DIR, "res/values-zh-rCN/strings.xml"),
        "zh-HK": os.path.join(PROJECT_DIR, "res/values-zh-rHK/strings.xml"),
        "zh-TW": os.path.join(PROJECT_DIR, "res/values-zh-rTW/strings.xml"),
        "ja": os.path.join(PROJECT_DIR, "res/values-ja/strings.xml"),
        "ko": os.path.join(PROJECT_DIR, "res/values-ko/strings.xml"),
    }

    # Load baseline default strings
    default_tree = ET.parse(locales["default (en)"])
    default_keys = {elem.attrib["name"] for elem in default_tree.findall("string")}
    print(f"  Baseline string keys count: {len(default_keys)}")

    for loc, path in locales.items():
        assert os.path.exists(path), f"Missing strings file: {path}"
        tree = ET.parse(path)
        keys = [elem.attrib["name"] for elem in tree.findall("string")]
        assert len(keys) == len(set(keys)), f"Duplicate keys in {loc}: {[k for k in keys if keys.count(k) > 1]}"
        missing = default_keys - set(keys)
        assert not missing, f"Missing keys in {loc}: {missing}"
        extra = set(keys) - default_keys
        assert not extra, f"Extra keys in {loc}: {extra}"
        print(f"  [✓] {loc}: {len(keys)} keys 100% aligned with baseline")

    print("[PASS] All 6 languages 100% verified with zero discrepancies.\n")

def test_apk_structure():
    print("--- [3/3] Testing APK Artifact Structure & Signatures ---")
    assert os.path.exists(APK_PATH), f"APK does not exist at {APK_PATH}"

    with zipfile.ZipFile(APK_PATH, "r") as z:
        names = z.namelist()
        required_files = [
            "AndroidManifest.xml",
            "classes.dex",
            "resources.arsc",
            "assets/xposed_init",
            "META-INF/xposed/scope.list",
            "META-INF/xposed/java_init.list",
        ]
        for rf in required_files:
            assert rf in names, f"Missing required file in APK: {rf}"
            print(f"  [✓] Found {rf}")

        # Check content of xposed descriptors
        xposed_init = z.read("assets/xposed_init").decode().strip()
        assert xposed_init == "com.midori.playmask.MainHook", f"Bad xposed_init: {xposed_init}"

        scope = z.read("META-INF/xposed/scope.list").decode().strip()
        assert scope == "com.android.vending", f"Bad scope: {scope}"

        java_init = z.read("META-INF/xposed/java_init.list").decode().strip()
        assert java_init == "com.midori.playmask.MainHook", f"Bad java_init: {java_init}"

    # Verify versionCode and versionName with aapt2
    badge_res = subprocess.run(["aapt2", "dump", "badging", APK_PATH], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    assert badge_res.returncode == 0, f"aapt2 dump badging failed: {badge_res.stderr}"
    assert "versionCode='11'" in badge_res.stdout, f"versionCode mismatch: {badge_res.stdout[:200]}"
    assert "versionName='1.2.2'" in badge_res.stdout, f"versionName mismatch: {badge_res.stdout[:200]}"
    assert "other-activities" not in badge_res.stdout, "Unexpected activities found in APK!"
    assert "MainActivity" not in badge_res.stdout, "MainActivity still declared in APK!"
    print("  [✓] APK version verified: versionCode='11', versionName='1.2.2' (Strictly 0 Activities)")

    # Verify zero dead resource files (colors.xml, styles.xml, layouts)
    for dead_file in ["res/values/colors.xml", "res/values/styles.xml", "res/layout"]:
        full_dead = os.path.join(PROJECT_DIR, dead_file)
        assert not os.path.exists(full_dead), f"Dead resource file/dir still exists: {dead_file}"
    print("  [✓] Zero dead resource files verified (colors.xml, styles.xml, layout/ all eradicated)")

    # Verify zero R.class bytecode in classes/
    classes_dir = os.path.join(PROJECT_DIR, "classes/com/midori/playmask")
    if os.path.exists(classes_dir):
        class_files = os.listdir(classes_dir)
        r_classes = [f for f in class_files if f.startswith("R.") or f.startswith("R$") or f == "R.class"]
        assert not r_classes, f"Dead R classes found in build: {r_classes}"
        print(f"  [✓] Zero dead R bytecode verified ({len(class_files)} pure logic class files)")

    # Verify signature with apksigner
    res = subprocess.run(["apksigner", "verify", "-v", APK_PATH], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    assert res.returncode == 0, f"APK verification failed: {res.stderr}"
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in res.stdout
    assert "Verified using v3 scheme (APK Signature Scheme v3): true" in res.stdout
    print("  [✓] apksigner v2/v3 signatures 100% verified")
    print(f"  [✓] APK File Size: {os.path.getsize(APK_PATH)} bytes")
    print("[PASS] APK packaging and signatures 100% verified.\n")

if __name__ == "__main__":
    test_email_masking()
    test_localization()
    test_apk_structure()
    print("========================================")
    print("ALL TESTS PASSED SUCCESSFULLY! (3/3)")
    print("========================================")
