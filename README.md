# KaloriKu

Pencatat kalori makanan Indonesia dengan **input suara bahasa Indonesia**.
Ada dua aplikasi: **aplikasi jam (Wear OS 5)** untuk melihat daftar makanan terakhir
yang dimakan beserta kalorinya, dan **aplikasi HP (Android)** untuk dashboard,
statistik, dan hasil analisa.

Semua keputusan kalori dibuat oleh **Jev (System One)** dari Kenari; model bahasa
`deepseek-v4-1-flash` hanya dipakai untuk memahami dan merapikan bahasa.

## Arsitektur

```
calories-tracker/
├── shared/   library Android (id.kaloriku.shared) — otak aplikasi
│   ├── domain/     model, katalog makanan lokal, rubrik kalori, waktu Jakarta
│   ├── ai/         KenariClient (chat + System One), tipe pertanyaan Jev
│   ├── analysis/   FoodAnalyzer (pipeline suara -> kalori), InsightsGenerator
│   ├── data/       Room (FoodEntry), DataStore (pengaturan), FoodRepository
│   └── sync/       protokol + koordinator sinkronisasi HP <-> jam (DataLayer)
├── phone/    aplikasi HP  (id.kaloriku) — dashboard, statistik, wawasan
└── wear/     aplikasi jam (id.kaloriku) — daftar makanan terakhir + suara
```

> **Penting:** modul `phone` dan `wear` wajib memakai `applicationId` yang sama
> (`id.kaloriku`) dan sertifikat penandatanganan yang sama. Wear OS DataLayer hanya
> merutekan pesan antar-APK yang memenuhi kedua syarat itu.

## Cara kerja analisa (maksimalkan Jev)

1. **Suara → teks.** `SpeechRecognizer` Android dengan locale `id-ID` (di jam maupun HP),
   dengan fallback mengetik manual.
2. **Teks → data.** `deepseek-v4-1-flash` menormalkan kalimat dan mengeluarkan JSON
   berisi daftar makanan, porsi, dan waktu makan. Nama merek/gerai dipertahankan apa
   adanya ("burger ayam dikichi" → "Burger Ayam Dikichi"), karena merek adalah penanda
   penting untuk memperkirakan kalori makanan kemasan atau restoran.
3. **Jev menjawab pertanyaan bertipe.** Untuk tiap makanan, `FoodAnalyzer` mengirim
   satu panggilan `POST /v1/systemone` berisi `state` (transkrip + kandidat katalog)
   dan pertanyaan bertipe:
   - `score` — **kalori satu porsi** memakai rubrik khusus kategori makanan
     (nasi, lauk hewani/nabati, sayur, gorengan, kuah, minuman, jajanan, buah).
   - `choice` — **ukuran porsi** (kecil/sedang/besar/tidak disebut).
   - `noul` — **apakah makanan khas Indonesia**, dan **apakah cukup jelas** untuk dihitung.
   - `choice` — **waktu makan**.
   - `score` — **skor kesehatan** pola makan (0–4).
   - `noul` — **deteksi duplikat** terhadap catatan terbaru.
4. **Makanan tak dikenal → pencarian web (bila perlu).** Kalau katalog lokal tidak
   punya acuan **dan** Jev menilai makanan itu bukan khas Indonesia sekaligus tidak
   cukup jelas — ciri khas makanan bermerek seperti "Burger Ayam Dikichi" — pipeline
   memanggil `POST /v1/web/search` Kenari, merangkum hasilnya, lalu **bertanya ulang
   ke Jev** untuk kalori item tersebut dengan bukti itu di dalam `state`. Jev tetap
   yang memutuskan angkanya; web hanya memasok fakta. Sumber yang tidak menyebut angka
   kalori dilaporkan apa adanya supaya Jev tahu tidak ada acuan. Pencarian dibatasi
   maksimal tiga per ucapan agar biaya tetap terkendali, dan kegagalan pencarian tidak
   pernah menggagalkan analisa — estimasi Jev pertama tetap dipakai. Item yang memakai
   jalur ini ditandai **"dari web"** di HP maupun jam.
5. **Distribusi → angka.** `CalorieRubric.interpret` memakai rata-rata berbobot
   distribusi probabilitas Jev untuk menghasilkan estimasi titik plus rentang
   rendah–tinggi yang jujur. Porsi besar/kecil menskalakan hasilnya.
6. **Konfirmasi pengguna.** Pengguna melihat rincian per makanan lalu menyimpan.

`InsightsGenerator` memakai Jev lagi untuk memilih **fokus perbaikan** dan
`deepseek-v4-1-flash` untuk menulis ringkasan bahasa Indonesia yang berpijak pada
angka nyata pengguna.

## Menjalankan

Prasyarat: JDK 21, Android SDK (compileSdk 37), dan `KENARI_API_KEY` di environment.

```sh
export KENARI_API_KEY="kn-..."
./gradlew :phone:assembleDebug     # APK HP
./gradlew :wear:assembleDebug      # APK jam
./gradlew :shared:testDebugUnitTest
```

Kunci API **tertanam ke dalam APK saat build** dari environment
(`BuildConfig.KENARI_API_KEY_DEFAULT`), jadi aplikasi bisa langsung dipakai tanpa
pengaturan apa pun. Tidak ada kolom kunci atau pilihan model di menu Pengaturan;
model sudah tetap: `deepseek-v4-1-flash` untuk bahasa dan `jev-1-13-free` untuk
System One. Kunci tidak pernah ditulis ke dalam kode sumber.

Bila build dijalankan tanpa `KENARI_API_KEY`, analisa akan gagal dengan pesan jelas
("Kunci Kenari tidak tertanam di build ini") alih-alih gagal diam-diam.

Uji integrasi langsung ke Kenari (opsional, dilewati bila kunci tidak ada):

```sh
KENARI_API_KEY="$KENARI_API_KEY" ./gradlew :shared:testDebugUnitTest \
  --tests "id.kaloriku.shared.LiveKenariIntegrationTest" -i
```

## Fokus makanan lokal

`LocalFoodCatalog` memuat ratusan makanan Indonesia (nasi goreng, rendang, soto ayam,
gado-gado, tempe, kerupuk, es cendol, dan lainnya) dengan kalori per porsi, alias,
dan makronutrien. Katalog dipakai untuk pencocokan cepat dan sebagai landasan
kategori bagi pertanyaan Jev.

## Sinkronisasi jam ↔ HP

Kedua aplikasi menyimpan database sendiri dan saling menyalin lewat **Wear OS
DataLayer**, yang berjalan di atas sambungan **Bluetooth** (atau Wi-Fi) yang dikelola
Google Play Services.

**Otomatis.** Sinkronisasi dijalankan:

- setiap kali pengguna menyimpan catatan baru (di HP maupun di jam);
- setiap aplikasi dibuka / kembali ke depan;
- saat aplikasi menerima pesan dari pasangannya.

**Manual.** Tombol **"Sinkronkan sekarang"** hanya ada di aplikasi HP (tab **Hari
Ini** dan **Atur**). Jam tidak punya tombol sinkron: jam menyinkron sendiri saat
dibuka dan setiap kali menyimpan catatan, lalu ikut menanggapi permintaan dari HP.
Tombol menampilkan status jujur: pesan "Sinkronisasi selesai." bila berhasil, atau
peringatan Bluetooth bila pasangan tidak terjangkau, beserta waktu sinkron terakhir.
Jam hanya menampilkan status ("Tersinkron 17:10") tanpa tombol.

### Cara kerja

Setiap catatan punya `syncId` unik yang stabil lintas perangkat, jadi penggabungan
tidak bergantung pada nomor baris database masing-masing. Catatan yang dibuat lokal
ditandai `pendingSync = true` dan hanya dibersihkan setelah pasangannya mengirim
`Ack`. Karena itu pengiriman aman diulang: kalau Bluetooth putus di tengah jalan,
catatan akan dicoba lagi pada sinkronisasi berikutnya tanpa menghasilkan duplikat.

Urutan pertukaran dibuat satu arah per inisiasi agar kedua perangkat tidak saling
membalas tanpa henti:

1. Inisiator mengirim catatan yang belum terkirim, lalu `RequestSync` dan daftar
   catatannya.
2. Penerima membalas `RequestSync` dengan catatan miliknya **tanpa** mengirim
   `RequestSync` baru.
3. Masing-masing mengirim `Ack` untuk catatan yang sudah tersimpan.

## Status verifikasi

Diverifikasi di perangkat nyata: **Xiaomi Watch 2 (Wear OS 5, API 34)** dan
**Samsung Galaxy S22 (Android 16, API 36)**.

- `:shared`, `:phone`, `:wear` kompilasi bersih (0 warning) dan menghasilkan APK.
- 130 unit test lulus (rubrik kalori, analisa, grounding web, katalog, waktu, sync,
  wawasan, migrasi skema).
- Alur uji langsung di perangkat:
  - HP: "sarapan bubur ayam satu mangkuk sama kerupuk, minum kopi susu"
    → Bubur Ayam 292, Kerupuk 71, Kopi Susu 160 kkal (total 523, Sarapan).
  - HP: "siang ini nasi padang dengan rendang dan daun singkong, minum es teh manis"
    → total 1010 kkal, 4 makanan lokal, tersimpan ke dashboard.
  - Jam: "makan malam sate ayam sepuluh tusuk sama lontong"
    → Sate Ayam 201, Lontong 115 kkal, muncul di daftar Makanan Terakhir.
- Uji akurasi terhadap acuan katalog (batas rasio 0,4–2,5):
  nasi goreng 425 (acuan 380), sate ayam 211 (acuan 200),
  es teh manis 90 (acuan 90), tempe goreng 105 (acuan 120).
- Uji makanan bermerek tak dikenal: "tadi siang aku makan burger ayam dikichi"
  → **Burger Ayam Dikichi 528 kkal** (rentang 180–700), dicari lewat web search
  Kenari lalu dinilai Jev, ditandai "dari web".

### Hasil uji sinkronisasi

Diuji pada pasangan HP–jam di atas (DataLayer lewat Bluetooth):

| Uji | Hasil |
|---|---|
| Simpan di HP → muncul di jam | ✅ Bubur Ayam 300 + Kerupuk 70 tersalin |
| Simpan di jam → muncul di HP | ✅ Sate Ayam 201 + Lontong 115 tersalin |
| Isi database kedua sisi | ✅ identik, 4 baris, `pendingSync = 0` |
| Tombol **Sinkronkan sekarang** di HP | ✅ log `sync(manual=true)`, UI "Terakhir sinkron 17:10" |
| Jam tanpa tombol sinkron | ✅ menyinkron otomatis saat dibuka, saat menyimpan, dan saat ditanya HP |
| Pengiriman ulang (idempoten) | ✅ 3× kirim batch yang sama tetap 2 baris |
| Tanpa pasangan | ✅ pesan "Belum terhubung … lewat Bluetooth", tanpa crash |

Tidak ada `FATAL EXCEPTION` di kedua perangkat, dan tile jam tetap terdaftar.

## Debug test seam

Pada build debug saja, `MainActivity` (HP dan jam) menerima extra intent untuk
menguji pipeline tanpa suara:

```sh
adb shell am start -n id.kaloriku/id.kaloriku.phone.MainActivity \
  --es kaloriku_test_transcript 'sarapan bubur ayam satu mangkuk'   # pratinjau
adb shell am start -n id.kaloriku/id.kaloriku.phone.MainActivity \
  --es kaloriku_test_save 'siang makan nasi padang'                 # analisa + simpan
adb shell am start -n id.kaloriku/id.kaloriku.wear.MainActivity \
  --es kaloriku_test_save 'makan malam sate ayam sepuluh tusuk'     # di jam
```

Catatan: bungkus perintah perangkat dalam tanda kutip ganda agar teks transkrip
tidak terpotong oleh shell di komputer (mis. hanya kata pertama yang terkirim).

