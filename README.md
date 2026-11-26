# Renzz Windows QEMU

GitHub Actions build project for a Renzz-branded Android PC/Windows VM application.

The workflow builds the QEMU-based RedBox Android backend (v1.1.0), which supports ARM64 Android hosts and x86/x86_64 guest emulation. The upstream project documents Windows guest support, configurable RAM/CPU, ISO/disk images, touchscreen input, networking and other VM features.

The APK does NOT contain Windows itself. A Windows installation image and appropriate license must be supplied separately by the user.

Upstream project:
https://github.com/RedBoxEmu/RedBox-PC-Emulator

Upstream license:
GPL-3.0

Branding:
Created By Renzz

## Build

Upload the contents of this repository to GitHub, then open Actions and run `Build Renzz Windows VM`.

The resulting artifact is:
`Renzz-Windows-QEMU-debug.apk`

## Important

This is a QEMU-based x86/x86_64 emulator on ARM64 Android. Guest CPU instructions are translated in software, so performance varies by device and guest OS. Snapdragon 865 is an ARM64 host and meets the architecture requirement documented by the upstream emulator.
