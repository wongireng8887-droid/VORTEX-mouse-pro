Vortex Mouse Pro v9 - perbaikan kursor, klik, dan FPS Look

- Kursor sekarang bergerak di seluruh layar (sebelumnya pergeseran relatif mouse dipakai sebagai posisi absolut).
- Klik kiri saat FPS Look OFF = sentuhan di posisi kursor (bisa ditahan dan digeser).
- FPS Look punya tombol pemicu sendiri: pilih marker Look di editor, lalu tekan tombol keyboard atau pilih Mouse Kanan/Tengah.
  Mode: Toggle (tekan = ON/OFF) atau Tahan (aktif selama ditahan).
- Mode tombol Sentuh (Hold/Tap/Repeat/Double/Release) kembali bisa dipilih di editor.
- Tombol X di editor membatalkan semua perubahan sejak editor dibuka; centang menyimpan.
- Tap logo kembali membuka menu (Mapping ON/OFF, Profil, ukuran logo, kecepatan kursor, Edit tombol).
- Menu baru: Kecepatan kursor (x1 / x1.5 / x2 / x3).

Catatan build: belum dikompilasi di lingkungan ini.

v9.1 - menu samping baru
- Tap logo membuka panel samping penuh tinggi (grid 2 kolom): Edit Keymap, Profil Keymap, Pengaturan Mouse (kecepatan kursor),
  Macro (terkunci, belum tersedia), Mapping ON/OFF, Tombol di Layar, Ukuran Logo, Transparansi, Kembali ke Aplikasi, Sembunyikan.
- Tutup lewat gagang putih di tepi kanan, atau tap di luar panel.
- Kursor tetap tampil di atas panel sehingga bisa dipakai menekan tile.

v9.2 - tampilan Edit Keymap dirombak
- Toolbar atas: + (tambah), X (batalkan semua perubahan sejak editor dibuka), centang (simpan & keluar).
- Tombol + membuka grid tipe 3x2: Sentuh, Joystick, FPS Look, Template, Lihat Bebas (terkunci), Macro (terkunci).
- Pilih kontrol -> muncul bar: nama, hapus, pengaturan (roda gigi), dan panah geser (tahan untuk geser terus).
- Roda gigi membuka panel pengaturan di sisi layar yang berlawanan dengan kontrol:
  Tombol Trigger + ASSIGN (+ Mouse Kiri/Kanan/Tengah), Mode (Hold/Tap/Repeat/Double/Release), Ukuran;
  FPS Look: pemicu, Toggle/Tahan, Sensitivitas, Area putar; Joystick: radius.
- BATAL mengembalikan ke kondisi saat panel dibuka, SIMPAN menutup panel.
- Kontrol baru langsung membuka panel dalam mode ASSIGN: tinggal tekan tombol keyboard.

v9.3 - overlay otomatis + perbaikan klik
- Toggle "Bubble melayang" dihapus dari Pengaturan.
- Logo V otomatis muncul saat game (yang ada di daftar game) dibuka dan hilang saat keluar dari game.
  Deteksi memakai akses penggunaan (UsageStats). Izinnya diberikan otomatis lewat shell saat tersambung
  (Shizuku / debug nirkabel); bisa juga manual lewat kartu "Muncul otomatis di game" di Pengaturan.
  Tanpa izin itu, logo tetap tampil terus seperti sebelumnya.
- Layanan tetap berjalan di latar (notifikasi punya tombol Matikan; menu logo punya tile Matikan Overlay).
  Membuka aplikasi Vortex otomatis menyalakan pemantau.
- Saat masuk game, profil keymap otomatis mengikuti game yang sedang dibuka.
- Mode kursor (FPS Look OFF) sekarang selalu aktif selama mapping ON, walau pemetaan kosong:
  klik kiri = sentuhan di posisi kursor; kursor berkedip dan menu logo menampilkan "Terakhir: Klik kursor x,y".

v9.3.1 - perbaikan stutter FPS Look
- Gerakan mouse di mode FPS Look sekarang digabung (coalesce) tiap 6ms.
  Sebelumnya setiap event mouse langsung di-inject, sehingga saat sambil menahan
  tombol lain (gerak/tembak) antrian injeksi penuh dan kamera terasa patah-patah.
- Pending look delta dibersihkan dengan benar saat Look dimatikan / mapping OFF /
  pointer capture hilang.

v9.3.2 - tembak + look lebih stabil
- Flush gerak kamera segera saat tombol lain (termasuk mouse kiri/tembak) ditekan,
  supaya look tidak "macet" sementara jari tembak baru di-DOWN.
- Coalesce look 4ms (lebih rapat dari 6ms).
- Injector: hapus SEMUA MOVE lama per pointer (bukan cuma satu), worker thread tetap.
- Capture: pakai AXIS_RELATIVE_X/Y bila ada, biar drag tetap kebaca saat tombol mouse ditahan.
- Jarak auto-rebase look diperlebar (lebih jarang hitch saat putar jauh).

v9.3.3 - akar masalah look saat menembak
- Mouse: sistem mengelompokkan banyak sampel gerak dalam satu event (history) per frame. Sebelumnya hanya sampel
  terakhir yang dibaca, jadi sebagian besar gerakan hilang -> kamera lag / kadang tidak mau drag, terutama saat game
  berat (menembak). Sekarang SEMUA sampel dijumlahkan. Event tombol (press/release) tidak lagi dihitung sebagai gerak
  (sebelumnya bisa dihitung ganda saat klik).
- Jeda 4ms (coalesce) dihapus: event sudah dikelompokkan per frame oleh sistem, jadi langsung diteruskan.
- Gerak look sekarang lewat jalur ringan (hanya MOVE jari look), tidak lagi menghitung ulang semua sentuhan dan
  menggambar ulang HUD tiap event.
- Recenter jari look: jari diangkat, ditunggu 24ms, lalu dipasang lagi di marker dengan id jari BARU (sebelumnya UP dan
  DOWN beruntun dengan id yang sama sehingga game bisa melihat lompatan posisi = kamera "terbang" ke langit-langit).
  Gerakan mouse saat jeda ditampung dan diterapkan setelah jari terpasang.
- Jari look dijaga tetap di dalam layar (margin) dan di dalam jangkauan; kena batas = recenter.

v9.3.4 - perbaikan "stuck" (sentuhan/klik berhenti total)
- Injector: thread pekerja antrean sebelumnya keluar sendiri saat antrean kosong. Ada celah waktu: perintah baru masuk
  tepat sebelum thread benar-benar mati, thread lama dianggap masih hidup (tidak dibuat baru), lalu mati -> antrean
  macet SELAMANYA. Gejalanya persis: kamera tidak ikut drag, klik mouse tidak bereaksi, sampai sambungan diulang.
  Sekarang satu thread pekerja menunggu di condition dan tidak pernah keluar sendiri.
- Pembuangan MOVE lama di antrean dibuat lebih ringan (tanpa menyalin seluruh antrean tiap gerakan).
- Jeda recenter dipersingkat jadi 16ms dan ada pengaman: jika jeda tersangkut >100ms, jari look dipasang paksa.

v9.3.5 - pemulihan otomatis + diagnosa "stuck"
- Watchdog (tiap ~1,2 dtk): jika antrean injeksi berisi tapi tidak maju >0,8 dtk, sambungan injeksi di-reset otomatis
  (toast "Injeksi macet, menyambung ulang...").
- Watchdog fokus: jika penangkap mouse kehilangan fokus jendela >2,4 dtk, penangkap dibuat ulang (maks 1x per 5 dtk).
- Injector.stop() tidak lagi ikut macet bila penulis lain sedang tersangkut (Q ditulis di thread terpisah, lalu ditutup paksa).
- Logo V tidak lagi disembunyikan karena jendela sesaat: harus bukan-game 2 pemeriksaan berturut-turut.
- Menu logo (tap V) kini menampilkan "Fokus: ya/tidak" dan "Antrean: n" untuk membantu diagnosa saat macet.

v9.4.0 - FPS Look dibuat ulang (LookEngine.kt)
Mesin look lama (tersebar di OverlayService: applyLook/moveLook/recenterLook/lookFlush/lookRegrab/dst) dihapus total
dan diganti satu kelas terpisah, LookEngine.
- Thread sendiri (prioritas tinggi), tick 4 ms: tidak ikut tersendat oleh thread UI/HUD/refresh().
- Event mouse (dijumlahkan per frame) hanya menambah "sisa gerakan"; mesin mengeluarkannya halus (50% sisa per tick),
  jadi kecepatan kamera rata walau event dari sistem datang berkelompok.
- Jari virtual dipasang di marker, digeser, dan DIGANTI (angkat -> jeda 10 ms -> pasang di marker dengan id jari baru)
  saat mendekati batas layar/jangkauan. Sisa gerakan yang melewati batas dibawa ke jari berikutnya (tidak hilang).
- Saat mouse diam >150 ms dan jari sudah jauh dari marker, jari diganti lebih awal supaya gerakan berikutnya punya ruang penuh.
- Id jari look 20/21, terpisah dari id tombol 0..8: tidak bisa bertabrakan dengan tombol tembak/joystick; refresh()
  karena tombol ditekan tidak lagi menyentuh jari look.
- Look dimatikan bersih (jari diangkat) saat: toggle OFF, Mapping OFF, fokus hilang, editor dibuka, logo disembunyikan,
  sambungan injeksi putus.
- Menu logo menampilkan "Look: ON/jumlah-penggantian-jari" untuk diagnosa.


v9.4.1 - stabilitas FPS Look (revisi analisis source)
- Gerakan tidak lagi dilembutkan 50% per tick; seluruh delta dikirim agar input tidak tertahan.
- Recenter tidak lagi menunggu 10 ms di antara UP dan DOWN; perintah tetap berurutan pada antrean injector.
- Jari tidak lagi otomatis diangkat hanya karena mouse diam 150 ms.
- Target build Android 16 (API 36), AGP 8.9.1, Gradle 8.11.1.
- ABI dibatasi ke arm64-v8a.
Catatan: belum diuji pada perangkat/game nyata dan belum dibandingkan pada level metode dengan APK Nebula.
