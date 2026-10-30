# CleanMyPhone - LYF Flame 8 (LS-4508) Optimizations & Customization Guide

## 1. Important Note on "Bootloader" vs "Bloatware"
- **Bootloader**: The bootloader is the low-level code stored in the phone's read-only flash memory (e.g. `sbl1`, `aboot`, `tz`, `rpm`) that initializes the Qualcomm Snapdragon 210 processor and boots the Linux kernel. **It cannot be deleted or removed without permanently hard-bricking the device.**
- **Bloatware**: What you want to remove is the **bloatware** — the preinstalled carrier trackers, Chinese telemetry (ADUPS), Qualcomm test APKs, and obsolete background services that hoard your 920 MB of RAM. CleanMyPhone disables these safely via ADB without touching system partitions.

---

## 2. Security & Privacy Audit (Critical Findings)
On this LYF Flame 8 device, we discovered two major background services running right now:
1. **ADUPS FOTA (`com.adups.fota` & `com.adups.fota.sysoper`)**:
   - An infamous Chinese third-party firmware update service preinstalled on many budget phones.
   - Security researchers (Kryptowire / US-CERT) found ADUPS actively transmitted IMEI, call logs, SMS texts, and installed apps to servers in Shanghai without user consent.
   - **Recommendation**: Disable immediately.
2. **Qualcomm RIDL (`com.qualcomm.qti.RIDL`)**:
   - Qualcomm "Robust Issue Detection and Logging" engine. Constantly runs diagnostic threads and writes system traces, wasting CPU and battery.
   - **Recommendation**: Disable immediately.
3. **SalesTracker (`com.salestracker.asan`)**:
   - Carrier/retail store tracking daemon. Active in RAM right now.
   - **Recommendation**: Disable immediately.

---

## 3. Performance & RAM Optimizations (Snapdragon 210 / 1GB RAM)

### Current System Status:
- Total RAM: 920 MB
- Available RAM: ~18 MB (Severely starved for memory; causes apps to lag and close aggressively)
- Internal Storage: 3.2 GB partition with ~850 MB free

### Optimizations:
1. **Free Up ~120-180 MB of RAM**:
   - Disabling the 45 bloatware packages in `debloat.txt` stops Qualcomm test daemons, live wallpaper services, and telemetry from running at boot.
2. **Animation Scales**:
   - Already verified set to `0.0` (off), which gives instant window transitions on slow Adreno 304 GPUs.
3. **Background Process Limit**:
   - On 1GB devices, Android tries to keep too many background cached processes, causing low-memory thrashing (LMK killing processes constantly).
   - Setting background limit to 3 or 4 processes makes multitasking much smoother.
4. **Cache Cleanup**:
   - Trim caches across packages via `pm trim-caches 1000000000`.

---

## 4. Customization Options

### A. Ultra-Lightweight Launcher
- Current Launcher: `com.android.launcher` (Launcher3 / Trebuchet, uses ~35 MB of RAM).
- Alternative: **KISS Launcher** (Open source, uses <5 MB RAM, lightning-fast instant search for apps and contacts). Can be sideloaded via ADB.

### B. Ad Blocking & Privacy on Android 6.0.1
- Android 6.0.1 (Marshmallow) does *not* support native "Private DNS" (DNS-over-TLS was introduced in Android 9 Pie).
- Safe ad-blocking solutions for Android 6:
  1. **Local DNS / Hosts blocker**: Open-source **DNS66** or **Blokada 4** (works on Android 5.0+ via local VPN profile to block ads system-wide without root).
  2. **Router-level Ad Blocking**: AdGuard Home or Pi-hole on your Wi-Fi router.
  3. **Browser Ad-blocking**: Your installed **Via Browser** (`mark.via.gp`) already has built-in ad blocking!

### C. GMS (Google Play Services) Management
- `com.google.android.gms` is currently using ~150-200 MB of your 920 MB RAM.
- If you use Google Play Store and WhatsApp notifications, keep GMS enabled.
- If you want maximum speed, we can disable Google Play Store and background sync services while keeping WhatsApp functional.
