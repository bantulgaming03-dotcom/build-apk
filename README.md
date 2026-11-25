# Klip Otomatis (APK)

Aplikasi Android: tempel link YouTube atau pilih file video, tekan **CLIP!**, lalu AI memilih bagian terpenting
dan merender klip vertikal 9:16 lengkap dengan subtitle dan judul hook. Hasil tersimpan di Galeri
(`Movies/KlipOtomatis`).

Terinspirasi dari https://github.com/zhouxiaoka/autoclip (alur: link → pilih momen terbaik → klip vertikal).
Ini bukan port langsung: AutoClip aslinya Python + FFmpeg desktop, di sini alurnya ditulis ulang native Android.

## Cara kerja
1. **Mesin tersembunyi**: Python + yt-dlp + FFmpeg ada di dalam APK (library youtubedl-android) untuk unduh YouTube.
2. **Transkrip**: subtitle YouTube kalau ada; kalau tidak (atau file lokal) audio ditranskripsi Gemini.
3. **Analisis**: Gemini memilih N momen terbaik (hook, durasi sesuai pengaturan, tidak overlap).
4. **Render**: Media3 Transformer, crop 9:16, subtitle, judul 3,5 detik pertama, H.264 + AAC.

## Build lewat GitHub
1. Ekstrak ZIP, upload isinya ke repo GitHub (folder `.github` harus ada di root repo).
2. Tab **Actions** > **Build APK** > jalan otomatis saat push (atau Run workflow).
3. Unduh artifact `KlipOtomatis-debug-apk` > `app-debug.apk`.

## Pemakaian
- Buka **Pengaturan** (ikon gerigi), isi **Gemini API key** (gratis di aistudio.google.com).
- Bisa juga: di aplikasi YouTube pilih Bagikan > Klip Otomatis.
- Biarkan layar menyala dan aplikasi terbuka selama proses.

## Catatan
- Hanya arm64-v8a (HP 64-bit), Android 10+.
- Jika unduh YouTube gagal: Pengaturan > **Perbarui mesin yt-dlp**, atau pakai **Pilih file video**.
