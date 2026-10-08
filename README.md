# PlayMask

[![Version](https://img.shields.io/badge/version-v1.2.2-blue.svg)](https://github.com/midori01/PlayMask/releases)
[![Android](https://img.shields.io/badge/Android-8.0%20--%2015+-green.svg)](#)
[![Target Package](https://img.shields.io/badge/Target-com.android.vending-orange.svg)](#)
[![Framework](https://img.shields.io/badge/Framework-LSPosed%20%7C%20Xposed-purple.svg)](#)
[![Tests](https://img.shields.io/badge/Tests-3%2F3%20Passed-brightgreen.svg)](tests/)
[![License](https://img.shields.io/badge/License-Apache--2.0-yellow.svg)](LICENSE)

**PlayMask** is an ultra-lightweight, zero-overhead **LSPosed / Xposed module** that dynamically masks personal Google account email addresses within the **Google Play Store (`com.android.vending`)** to protect privacy during screen sharing, recordings, and presentations.

---

## 1. Overview

Whenever the Google Play Store is opened or the account switcher dialog is summoned, personal Google account email addresses are displayed in plain text in the top-right OneGoogle header. **PlayMask** intercepts the text rendering pipeline and UI layout inflation to sanitize email prefixes (e.g. `john.doe@gmail.com` -> `***@gmail.com`) while preserving domain recognition, host styling, and user experience.

---

## 2. Key Features

- **👁️ Interactive Eye Toggle Button**: Seamlessly injects a native-styled eye icon into the Google Play OneGoogle account header, allowing you to reveal or mask email addresses instantly on demand.
- **⚡ Zero Background Overhead**: Pure view-level hooking without persistent background services, broadcast receivers, or wake locks. Guarantees 0% battery consumption.
- **🔄 Resilient AXML Auto-Discovery Engine**: Dynamically analyzes the host APK's binary XML layouts at runtime to identify internal View IDs. Fully immune to layout ID shifts in Google Play updates (tested and verified against Google Play Store v53.4.34+ and backwards compatible).
- **🌐 6-Language Localization**: Full translation coverage across English, Simplified Chinese (`zh-CN`), Traditional Chinese (`zh-HK`, `zh-TW`), Japanese (`ja`), and Korean (`ko`).
- **🛡️ Strict Scope Isolation**: Declared scope is restricted solely to the Google Play Store (`com.android.vending`). Does not inject into or hook any other application or system process.
- **🧪 Automated Verification Suite**: Comprehensive Python test suite verifying email masking regex patterns, 6-locale translation keys parity, APK structural integrity, and v2/v3 signature schemes.

---

## 3. Installation & Setup

1. Download the latest `PlayMask.apk` from [GitHub Releases](https://github.com/midori01/PlayMask/releases).
2. Install the APK and enable **PlayMask** in **LSPosed Manager**.
3. Verify that the scope is selected:
   - `Google Play Store (com.android.vending)`
4. Force stop and restart the Google Play Store.

---

## 4. Building from Source

### Standalone On-Device Build (Termux / Linux)
The repository includes a standalone build script using `aapt2`, `javac`, and `d8`:
```bash
chmod +x build.sh
bash build.sh
```

To run the verification suite:
```bash
python3 tests/test_playmask.py
```

### Standard Gradle Build (Desktop / Android Studio / CI)
```bash
git clone https://github.com/midori01/PlayMask.git
cd PlayMask
./gradlew assembleRelease
```

---

## 5. Automated Tests

Run the test suite:
```bash
python3 tests/test_playmask.py
```

- **Test 1**: Regex masking logic verification (positive and negative assertions).
- **Test 2**: 6-language XML localization parity and zero-missing key checks.
- **Test 3**: APK manifest, Xposed descriptors, and v2/v3 signature scheme integrity.

---

## 6. License

This project is licensed under the [Apache License 2.0](LICENSE).
