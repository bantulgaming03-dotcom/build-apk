# Renzz Windows Container

Android project branded "Created By Renzz".

## Build dengan GitHub Actions

1. Buat repository GitHub baru.
2. Upload seluruh isi project ini ke repository.
3. Masuk ke tab Actions.
4. Pilih workflow `Build Android APK`.
5. Tekan `Run workflow`.
6. Setelah selesai, buka hasil workflow dan download artifact `Renzz-Windows-debug`.

Workflow sudah menyiapkan JDK 17 dan Gradle 8.9 di GitHub Actions, jadi HP tidak perlu memiliki Java, Gradle, atau Android SDK.

## Catatan

Project ini adalah container/UI Windows-style. Ini BUKAN Windows VM penuh. Agar benar-benar menjalankan Windows, diperlukan backend virtualisasi/emulasi dan image Windows yang sesuai.
