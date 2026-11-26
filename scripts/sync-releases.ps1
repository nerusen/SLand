[CmdletBinding()]
param(
    [string]$Owner = "nerusen",
    [string]$Repo = "SLand",
    [switch]$DryRun,
    [switch]$OverwriteExisting
)

$ErrorActionPreference = "Stop"

function Get-GitHubToken {
    if ($env:GH_TOKEN) { return $env:GH_TOKEN.Trim() }
    if ($env:GITHUB_TOKEN) { return $env:GITHUB_TOKEN.Trim() }
    $cred = ("protocol=https`nhost=github.com`n`n" | git credential fill)
    $line = ($cred -split "`n" | Where-Object { $_ -like "password=*" }) | Select-Object -First 1
    if (-not $line) {
        throw "GitHub token tidak ditemukan. Set GH_TOKEN / GITHUB_TOKEN atau login melalui git credential."
    }
    return $line.Substring(9).Trim()
}

function Invoke-GitHubJson {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers,
        [object]$Body = $null
    )
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 10
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers -ContentType "application/json; charset=utf-8" -Body $bytes
    }
    return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $Headers
}

# Catatan rilis terkurasi per build sukses (commit gagal/error build dilewati sebagai rilis terpisah,
# sementara fitur & perbaikan nyata yang ikut terbangun di build sukses dirangkum dengan akurat).
$ReleaseCatalog = @{
    "f344d3e" = @{
        Title = "SLand v1.0.0 (f344d3e)"
        Summary = "Rilis perdana SLand dengan state capsule diam (Idle) yang selalu tampil di sekitar kamera, rebranding paket aplikasi, dan pipeline build otomatis."
        Bullets = @(
            "Menambahkan state keempat Island (Idle): capsule lebar di sekitar kamera saat tidak ada aktivitas, dengan lebar yang dapat diatur 90-260 dp (default 150 dp).",
            "Capsule hanya menerima sentuhan saat Aksesibilitas aktif agar tidak mengganggu gestur tarik-turun bilah notifikasi.",
            "Rebranding penuh ke SLand (`com.nelsencreative.sland`) mulai versi 1.0.0, termasuk perbaikan `ShelfProvider`, `shortcuts.xml`, dan `BuildConfig.REPO`.",
            "Menambahkan workflow GitHub Actions untuk membangun APK debug dan release secara otomatis."
        )
    }
    "4419fe2" = @{
        Title = "SLand v1.0.0"
        Summary = "Pembaruan visual capsule mode diam dengan logo Nelsen dan ikon baterai bergaya iOS."
        Bullets = @(
            "Menampilkan logo Nelsen rata kiri (digambar sebagai `Path` lossless dari `assets/nelsen-logo.svg`) pada state Idle.",
            "Menampilkan ikon baterai bergaya iOS rata kanan tanpa angka dengan warna dinamis (hijau saat mengisi, kuning <=20%, merah <=10%, ink normal).",
            "Tata letak logo dan ikon baterai dihitung adaptif dari ruang datar antara ujung capsule dan kamera agar tidak saling menimpa."
        )
    }
    "c8e6ba2" = @{
        Title = "SLand v1.0.1 (c8e6ba2)"
        Summary = "Perbaikan animasi capsule agar terpusat di lensa kamera, stabilisasi lebar bar lirik, dan penambahan baris navigasi ikon."
        Bullets = @(
            "Mengubah animasi bentuk island dari dua spring tepi menjadi pusat dan lebar agar posisi selalu konsisten di lensa kamera saat buka/tutup.",
            "Mengukur lebar teks lirik satu kali per lagu dari baris terpanjang sehingga bar lirik tidak membesar-mengecil setiap pergantian baris.",
            "Menambahkan baris navigasi ikon antar halaman beserta ikon pengaturan di bawah indikator titik panel."
        )
    }
    "24e69c7" = @{
        Title = "SLand v1.0.1 (24e69c7)"
        Summary = "Menghilangkan pergeseran visual capsule ke kiri saat panel ditutup kembali ke mode diam."
        Bullets = @(
            "Menonaktifkan animasi perpindahan bawaan `WindowManager` (`setCanPlayMoveAnimation(false)` di Android 14+ dan `PRIVATE_FLAG_NO_MOVE_ANIMATION` di Android 10-13).",
            "Memastikan seluruh gerakan buka-tutup hanya berasal dari spring internal island tanpa jeda posisi jendela sistem."
        )
    }
    "444cc79" = @{
        Title = "SLand v1.0.1 (444cc79)"
        Summary = "Pembaruan halaman Beranda dengan kolom Wi-Fi & Bluetooth, gestur tahan tanpa bentrok, pengaturan navigasi, serta tab kontribusi GitHub."
        Bullets = @(
            "Mengganti baris tombol bawah Beranda dengan dua kolom cepat Wi-Fi dan Bluetooth lengkap dengan nama koneksi serta persentase baterai TWS/headset.",
            "Menambahkan dukungan gestur tahan per scene (`Scene.hold`) agar aksi tahan pada kolom cepat tidak bentrok dengan menu cepat island.",
            "Menambahkan sakelar pengaturan `Navigasi selalu tampil` (`Prefs.navAlways`).",
            "Menambahkan tab baru kalender kontribusi GitHub (`GithubTab`) dengan token terenkripsi di Vault dan integrasi GraphQL API."
        )
    }
    "71e6653" = @{
        Title = "SLand v1.0.1"
        Summary = "Menambahkan delapan pemicu aktivitas sistem baru di island: kartu screenshot, bilah volume, dan notifikasi satu baris enam mode sistem."
        Bullets = @(
            "Menambahkan kartu notifikasi screenshot satu baris (`ShotScene` & `Capture.kt`) dengan thumbnail serta tombol chip Buka dan Bagikan.",
            "Menambahkan bilah volume (`VolumeScene`) dengan ikon speaker, angka persentase, dan animasi isian spring.",
            "Menambahkan notifikasi satu baris untuk enam mode sistem (`Flags.kt` & `FlagScene`): Jangan Ganggu, Hening, Senter, Hotspot, Mode Pesawat, dan Hemat Daya.",
            "Menyediakan sakelar pengaturan masing-masing pemicu di menu Pengaturan > Aktivitas serta delapan ikon baru di `Icons.kt`."
        )
    }
    "45e4504" = @{
        Title = "SLand v1.1.5"
        Summary = "Rilis v1.1.5 yang menghadirkan poni island bermata di mode landscape, bilah volume yang bisa digeser, dan tab grid screenshot."
        Bullets = @(
            "Menambahkan poni island mode landscape (`Notch.kt`, `Eye.kt`) dengan empat suasana mata: Calm (berkedip tiap 7 detik), Startled, Angry, dan Happy.",
            "Menambahkan dukungan drag langsung (`grab`/`drag`/`release`) pada bilah volume sebagai slider tanpa memicu gestur buka/tutup panel.",
            "Menambahkan chip tombol Simpan ke Rak pada kartu screenshot untuk menyalin tangkapan layar ke penyimpanan Rak aplikasi.",
            "Menambahkan tab grid screenshot terbaru (`ShotsTab.kt`) dengan pemuatan thumbnail di thread latar."
        )
    }
    "2793ae3" = @{
        Title = "SLand v1.1.6 (2793ae3)"
        Summary = "Penyesuaian besar poni mode landscape: pindah ke tengah tepi atas, slider ukuran poni & mata, serta mata sabit untuk mood marah dan senang."
        Bullets = @(
            "Memindahkan poni landscape dari sisi kamera ke tengah tepi atas layar agar tidak mengganggu genggaman tangan dan gestur kembali.",
            "Menambahkan dua slider ukuran di Pengaturan > Island > Mode landscape: ukuran poni (70-150%) dan ukuran mata (60-120%).",
            "Memperbaiki bentuk mata Angry (sabit berujung lancip tanpa alis terpisah) dan Happy (sabit gemuk berujung tumpul dengan transisi crossfade).",
            "Menambahkan lantai ketebalan 1.2 dp agar kedipan mata tetap terlihat jelas pada ukuran poni kecil."
        )
    }
    "c12ee69" = @{
        Title = "SLand v1.1.6 (c12ee69)"
        Summary = "Pembaruan visual poni mode landscape dengan sepasang mata serempak, sudut atas melebar ke bezel, dan serah-terima mulus dengan panel island."
        Bullets = @(
            "Mengubah tampilan poni menjadi sepasang mata simetris yang berkedip dan bereaksi serempak (`pairWidth()`).",
            "Mengubah sudut atas poni dan panel landscape agar melebar cekung (`Ui.glassFlare`) menyatu dengan bezel layar.",
            "Menyatukan siluet island landscape dan poni pada tepi atas dengan transisi serah-terima ukuran, pegas fade mata, dan pengelolaan sentuhan `FLAG_NOT_TOUCHABLE`.",
            "Memperbarui informasi nama penulis pada aplikasi dan README."
        )
    }
    "7e97f60" = @{
        Title = "SLand v1.1.6"
        Summary = "Perbaikan sudut atas poni agar tidak berlubang, perbaikan bug mata hilang saat menu ditutup, dan penekukan path mood yang lebih halus."
        Bullets = @(
            "Menambahkan `Ui.block()` agar sudut atas yang menempel kaca selalu persegi sebelum diberi pelebaran cekung sehingga tidak muncul celah berlubang.",
            "Memperbaiki urutan `snap()` dan penetapan target pada `Notch.show()` agar mata tidak hilang saat panel ditutup dengan mengetuk area luar.",
            "Menghitung jarak antar mata berdasarkan ruang udara aktual terhadap bentuk sabit yang sedang digambar.",
            "Mengubah animasi peralihan mood mata menjadi penekukan kurva kuadratik yang mulus (bukan pergantian gambar tembus pandang)."
        )
    }
    "b97b532" = @{
        Title = "SLand v1.2.0 (b97b532)"
        Summary = "Rilis fitur v1.2.0: tab Maps & pelacakan jalan, 9 tema warna island, 11 pilihan tanda capsule, kisi 10 shortcut aplikasi, dan berbagai penyesuaian visual."
        Bullets = @(
            "Menambahkan tab Maps (`MapsTab`): peta monokrom berbasis petak CARTO + pelacakan jarak, langkah, waktu bergerak, dan estimasi kalori.",
            "Menambahkan 9 palet tema warna island (4 monokrom & 5 kombinasi warna) dengan penyesuaian kontras otomatis.",
            "Menambahkan 11 pilihan tanda capsule (kosong, baterai, cincin, titik, jam, CPU, RAM, operator, teks kustom, Wi-Fi, Bluetooth).",
            "Menambahkan kisi shortcut 10 aplikasi di island, tile Data Seluler & Kunci Layar di Beranda, peringatan kecerahan layar, dan tombol kunci pada tab Musik.",
            "Menambahkan efek charging (ikon petir berlubang di capsule, glow hijau di poni), goyangan mata mengikuti musik, serta kisi 4 kolom pada tab screenshot.",
            "Memperbaiki empat error kompilasi rilis 1.2.0 (`Ui.adopt()`, `zoomBy()`, argumen `getString`, dan referensi lokal layanan sistem pada `Steps.kt`)."
        )
    }
    "cb15a58" = @{
        Title = "SLand v1.2.0"
        Summary = "Pembaruan dukungan kunci API CARTO pada v1.2.0, kepatuhan batas cache 30 hari, dan diagnostik kegagalan peta."
        Bullets = @(
            "Membaca kunci peta dari build property `CARTO_KEY` dan mengirim header pembatas `X-Android-Package` serta `X-Android-Cert` (SHA-1).",
            "Mencegah petak placeholder watermark ter-cache dan memigrasikan cache ke direktori `tiles/v2` dengan masa simpan maksimal 30 hari.",
            "Menambahkan `MapFault` tiga status (kunci tidak ada, ditolak, tanpa jaringan) beserta baris status kunci di pengaturan dan pembaruan halaman privasi."
        )
    }
    "0ac2548" = @{
        Title = "SLand v2.0.1"
        Summary = "Rilis v2.0.1 dengan fitur baru Manajemen Dompet (Supabase read-only) dan integrasi Asisten AI (Gemini)."
        Bullets = @(
            "Menambahkan modul Manajemen Dompet (`Wallet.kt`, `Money.kt`, `scene/Money.kt`) dengan tab Keuangan dan Papan Kanban sebagai cermin read-only dari Supabase.",
            "Menambahkan integrasi Asisten AI berbasis Gemini API (`Assistant.kt`) lengkap dengan tab interaksi Asisten dan pengaturan kunci API.",
            "Menambahkan ikon, entri string EN/ID, serta dokumentasi `docs/wallet-bridge.md`.",
            "Menyelesaikan perbaikan inferensi tipe lambda/scene dan komentar block pada `Assistant.kt` hingga build berhasil."
        )
    }
    "87f552e" = @{
        Title = "SLand v2.0.5"
        Summary = "Pembaruan v2.0.5 untuk menyempurnakan ikon, tata letak, dan perilaku scrolling pada tab baru."
        Bullets = @(
            "Memetakan ikon khusus untuk tab Keuangan (`Icon.Purse`), Papan (`Icon.Kanban`), dan Asisten (`Icon.Flash`) di `Island.kt`.",
            "Memperbaiki tata letak konten dan responsivitas scroll di dalam `scene/Money.kt`."
        )
    }
    "576bc03" = @{
        Title = "SLand v2.1.0 (576bc03)"
        Summary = "Rilis v2.1.0 dengan fitur privasi saldo dompet dan mode asisten tetap terbuka."
        Bullets = @(
            "Menambahkan opsi `finance_hide_balance` di pengaturan untuk menyembunyikan total saldo pada tab Keuangan.",
            "Menambahkan pengaturan agar panel Asisten AI dapat tetap terbuka selama sesi tanya-jawab berlangsung."
        )
    }
    "182cf64" = @{
        Title = "SLand v2.1.0 (182cf64)"
        Summary = "Pembatasan jumlah tab navigasi aktif, pemisahan tampilan lirik, serta penambahan mode Fokus 30 senyap."
        Bullets = @(
            "Membatasi navigasi aktif maksimal 7 tab selain Beranda dan Pengaturan agar baris ikon tetap proporsional.",
            "Memisahkan tampilan capsule lirik dan menambahkan pintasan `Fokus 30` mode senyap pada `Timers.kt` & `TimerScene.kt`.",
            "Memperbaiki pemanggilan konstruktor `Timers` di `Listener.kt` agar kompilasi sukses."
        )
    }
    "984923c" = @{
        Title = "SLand v2.1.0"
        Summary = "Penyesuaian tata letak capsule lirik agar tampil di bawah capsule utama."
        Bullets = @(
            "Memindahkan posisi `lyricBubble` di `Island.kt` dari sisi samping ke bawah capsule utama secara terpusat."
        )
    }
    "9342f0e" = @{
        Title = "SLand v2.2.1"
        Summary = "Rilis v2.2.1 yang menyempurnakan kontrol musik dengan fitur Shuffle & Repeat serta perbaikan tampilan capsule lirik."
        Bullets = @(
            "Menambahkan dukungan tombol dan ikon `Shuffle`, `Repeat`, dan `RepeatOne` pada `MusicScene` dan `Media.kt`.",
            "Menggunakan pemanggilan metode aman berbasis refleksi untuk `getShuffleMode`/`getRepeatMode` dan `setShuffleMode`/`setRepeatMode` demi kompatibilitas API lintas perangkat.",
            "Menyempurnakan posisi dan tampilan capsule lirik saat musik diputar."
        )
    }
    "96f0abd" = @{
        Title = "SLand v2.3.0"
        Summary = "Rilis v2.3.0 dengan tambahan efek drop shadow island, pilihan logo capsule, sapaan berkala, dan empat tema visual baru."
        Bullets = @(
            "Menambahkan efek drop shadow pada island beserta opsi pengaturannya.",
            "Menambahkan pilihan logo capsule dan fitur sapaan berkala setiap 20 menit.",
            "Menambahkan empat tema visual baru untuk memperkaya kustomisasi tampilan island.",
            "Memberikan tipe `Runnable` eksplisit pada timer sapaan berkala untuk mengatasi recursive type checking Kotlin."
        )
    }
    "f2963bb" = @{
        Title = "SLand v2.3.1"
        Summary = "Rilis v2.3.1 menambahkan pengaturan kepekatan bayangan (drop shadow) island."
        Bullets = @(
            "Menambahkan preset kepekatan bayangan (Lembut, Seimbang, Kuat) serta slider kustom 0-100% pada pengaturan island."
        )
    }
    "23dd67d" = @{
        Title = "SLand v2.3.2"
        Summary = "Rilis v2.3.2 memperbaiki pemotongan bayangan island di tepi window overlay."
        Bullets = @(
            "Menambah kelonggaran ruang 12 dp pada bounds window overlay untuk blur 8 dp dan jarak bayangan 3 dp agar drop shadow merambat utuh tanpa terpotong."
        )
    }
    "5830683" = @{
        Title = "SLand v2.3.3 (5830683)"
        Summary = "Rilis v2.3.3 meningkatkan keandalan layanan overlay saat notification listener tersambung ulang."
        Bullets = @(
            "Memulihkan seluruh fitur aktif dan membangun ulang overlay island pada `onListenerConnected`.",
            "Menjadwalkan permintaan rebind otomatis setelah 2 detik apabila notification listener terputus."
        )
    }
    "dd142a4" = @{
        Title = "SLand v2.3.3"
        Summary = "Penambahan fitur awal Timer WhatsApp berbasis kontak target."
        Bullets = @(
            "Menambahkan `WhatsAppTimer.kt` dan pengaturan pemilihan hingga 5 kontak target WhatsApp untuk memulai timer otomatis 10 detik saat pesan masuk."
        )
    }
    "c4dd304" = @{
        Title = "SLand v2.3.4"
        Summary = "Rilis v2.3.4 menyempurnakan fitur Timer WhatsApp berbasis kontak dan menstabilkan lifecycle `Listener`."
        Bullets = @(
            "Mendukung WhatsApp dan WhatsApp Business dengan visibilitas intent Clock, peningkatan pencocokan nama kontak/notifikasi, serta penghapusan ranking gate timer.",
            "Menyelaraskan inisialisasi `Agenda`, lifecycle method, dan pemanggilan timer pada `Listener.kt`."
        )
    }
    "ae4b561" = @{
        Title = "SLand v2.4.0"
        Summary = "Rilis v2.4.0 menghadirkan jam digital poni landscape, animasi kaget alami, alert WhatsApp bersuara kustom, serta stabilisasi lirik dan rotasi."
        Bullets = @(
            "Menambahkan jam digital poni mode landscape (`HH:mm`) yang muncul tiap 2 menit selama 20 detik dengan transisi halus dan sakelar `notch.clock`.",
            "Menyempurnakan animasi mata kaget dengan getaran awal, tanda seru `'!'` bergoyang di kanan mata, dan tatapan penasaran sementara mata tetap di tengah.",
            "Menambahkan alert notifikasi khusus SLand untuk kontak WhatsApp dengan suara bawaan OGG maupun pilihan suara kustom (mp3/ogg/wav), izin `POST_NOTIFICATIONS`, dan cooldown 5 menit.",
            "Menstabilkan kapsul lirik kompak agar tidak bergetar kiri-kanan dan menyempurnakan transisi bentuk saat rotasi layar.",
            "Menyertakan perbaikan kompilasi pada `Eye.kt`, `Notch.kt`, dan `WhatsAppTimer.kt`."
        )
    }
    "a6cf36b" = @{
        Title = "SLand v2.5.0"
        Summary = "Rilis v2.5.0 membuat bunyi notifikasi WhatsApp kontak pilihan berbunyi berurutan mengikuti setiap pesan masuk."
        Bullets = @(
            "Mengubah pemutaran bunyi WhatsApp menjadi satu bunyi per pesan masuk dari kontak target (tidak lagi dibatasi jeda 5 menit).",
            "Mengantrekan pemutaran `MediaPlayer` secara berurutan agar suara tidak saling memotong saat banyak pesan masuk beruntun.",
            "Mengganti ID notifikasi tiap pesan dan membatalkan ID lama agar laci notifikasi tetap rapi sambil menjaga kanal sistem tetap senyap.",
            "Menyelaraskan tanda tangan metode `nextId(prefs, target)` dengan pemanggilnya serta memperbarui string pengaturan dan README."
        )
    }
    "b319834" = @{
        Title = "SLand v2.6.0"
        Summary = "Rilis v2.6.0 menyempurnakan animasi kapsul lirik, ekspresi mata notifikasi & lambaian tangan di poni landscape, tampilan jam poni, dan suara tunggal WhatsApp."
        Bullets = @(
            "Kapsul lirik mode compact: memperbarui lebar target hanya saat baris lirik berubah dan menyamakan sumber lebar layout serta `drawRow` agar kapsul stabil di tengah tanpa bergetar.",
            "Animasi mata saat notifikasi baru: menghilangkan titik hitam pupil, menambahkan lirikan condong kiri lalu kanan ke arah tanda seru `'!'` yang lebih tebal dengan pantulan halus.",
            "Notifikasi WhatsApp kontak terpilih: memindahkan notifikasi ke kanal senyap `IMPORTANCE_LOW` (`whatsapp_target_alert_silent`) dengan ID stabil dan deduplikasi di `Listener` agar berbunyi tepat 1 kali per pesan.",
            "Jam digital poni mode landscape: memakai font `sans-serif-black` tebal dengan skala ukuran lebih proporsional (`0.43f`).",
            "Animasi sapaan melambaikan tangan di poni mode landscape: muncul tiap 2 menit selama 10 detik dengan jeda anti-tabrakan terhadap jam dan tanda seru, memunculkan glow putih lembut, telapak tangan putih di kanan mata tanpa menggeser pusat mata, serta mata tersenyum bergoyang sambil tetap berkedip.",
            "Menghapus panggilan `setSilent()` yang tidak tersedia pada platform `Notification.Builder` agar kompilasi build berjalan bersih."
        )
    }
}

function Build-FallbackNotes {
    param(
        [string]$Version,
        [string]$ShortSha,
        [string]$FullSha,
        [string]$PrevSuccessSha
    )
    $range = if ($PrevSuccessSha) { "$PrevSuccessSha..$FullSha" } else { "$FullSha^..$FullSha" }
    $fmt = "%h%x1f%s%x1f%b%x1e"
    $raw = (git --no-pager log --reverse "--format=$fmt" $range) -join "`n"
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("## SLand v$Version (``$ShortSha``)")
    $lines.Add("")
    $lines.Add("Ringkasan pembaruan yang tercakup dalam build sukses ini:")
    $lines.Add("")
    foreach ($entry in ($raw -split [char]0x1e)) {
        $trimmed = $entry.Trim()
        if (-not $trimmed) { continue }
        $parts = $trimmed -split [char]0x1f, 3
        $h = $parts[0].Trim()
        $s = if ($parts.Count -gt 1) { $parts[1].Trim() } else { "" }
        $b = if ($parts.Count -gt 2) { $parts[2].Trim() } else { "" }
        $lines.Add("- **$s** (``$h``)")
        if ($b) {
            foreach ($bLine in ($b -split "`r?`n")) {
                $bl = $bLine.Trim()
                if ($bl -and -not $bl.StartsWith("Co-authored-by:")) {
                    $lines.Add("  $bl")
                }
            }
        }
    }
    $lines.Add("")
    $lines.Add("---")
    $lines.Add("- **Commit:** ``$FullSha``")
    return ($lines -join "`n")
}

$token = Get-GitHubToken
$headers = @{
    Authorization = "Bearer $token"
    Accept        = "application/vnd.github+json"
    "User-Agent"  = "SLand-Release-Sync"
}

Write-Host "Mengambil daftar workflow runs untuk $Owner/$Repo..."
$allRuns = @()
$page = 1
while ($true) {
    $resp = Invoke-GitHubJson -Method "GET" -Uri "https://api.github.com/repos/$Owner/$Repo/actions/runs?per_page=100&page=$page" -Headers $headers
    if (-not $resp.workflow_runs -or $resp.workflow_runs.Count -eq 0) { break }
    $allRuns += $resp.workflow_runs
    if ($resp.workflow_runs.Count -lt 100) { break }
    $page++
}

# Hanya ambil run yang benar-benar sukses; run gagal/dibatalkan dilewati.
$successRuns = $allRuns |
    Where-Object { $_.status -eq "completed" -and $_.conclusion -eq "success" } |
    Sort-Object run_number

$failedCount = ($allRuns | Where-Object { $_.conclusion -ne "success" }).Count
Write-Host "Total runs: $($allRuns.Count) | Sukses: $($successRuns.Count) | Gagal/lainnya (dilewati): $failedCount"

# Ambil artifact tiap run sukses dan ekstrak versi + short SHA
$candidates = New-Object System.Collections.Generic.List[hashtable]
foreach ($run in $successRuns) {
    $artResp = Invoke-GitHubJson -Method "GET" -Uri "https://api.github.com/repos/$Owner/$Repo/actions/runs/$($run.id)/artifacts" -Headers $headers
    $art = $artResp.artifacts | Where-Object { -not $_.expired -and $_.name -like "SLand-*" } | Select-Object -First 1
    if (-not $art) {
        Write-Warning "Run #$($run.run_number) ($($run.head_sha.Substring(0,7))) tidak memiliki artifact APK aktif, dilewati."
        continue
    }
    $shortSha = $run.head_sha.Substring(0, 7)
    if ($art.name -match '^SLand-(.+)-([0-9a-f]{7})$') {
        $ver = $Matches[1]
    } else {
        $ver = $art.name.Replace("SLand-", "")
    }
    $candidates.Add(@{
        RunNumber = [int]$run.run_number
        RunId     = $run.id
        Sha       = $run.head_sha
        ShortSha  = $shortSha
        Version   = $ver
        Artifact  = $art
        CreatedAt = $run.created_at
    })
}

# Tentukan index terakhir untuk setiap versi supaya rilis terbaru tiap versi memakai tag bersih v<version>
$latestIndexByVersion = @{}
for ($i = 0; $i -lt $candidates.Count; $i++) {
    $latestIndexByVersion[$candidates[$i].Version] = $i
}

# Ambil daftar release yang sudah ada
$existingReleases = @{}
$relPage = 1
while ($true) {
    $relResp = Invoke-GitHubJson -Method "GET" -Uri "https://api.github.com/repos/$Owner/$Repo/releases?per_page=100&page=$relPage" -Headers $headers
    if (-not $relResp -or $relResp.Count -eq 0) { break }
    foreach ($rel in $relResp) {
        $existingReleases[$rel.tag_name] = $rel
    }
    if ($relResp.Count -lt 100) { break }
    $relPage++
}

$tmpRoot = Join-Path $env:TEMP "sland-release-sync"
if (Test-Path $tmpRoot) { Remove-Item -Recurse -Force $tmpRoot }
New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null

try {
    $prevSha = $null
    for ($i = 0; $i -lt $candidates.Count; $i++) {
        $c = $candidates[$i]
        $isLatestOfVersion = ($latestIndexByVersion[$c.Version] -eq $i)
        $tag = if ($isLatestOfVersion) { "v$($c.Version)" } else { "v$($c.Version)-$($c.ShortSha)" }

        if ($ReleaseCatalog.ContainsKey($c.ShortSha)) {
            $meta = $ReleaseCatalog[$c.ShortSha]
            $title = $meta.Title
            $bulletText = ($meta.Bullets | ForEach-Object { "- $_" }) -join "`n"
            $body = @"
## $title

$($meta.Summary)

### Rincian Pembaruan
$bulletText

---
- **Versi:** ``$($c.Version)``
- **Commit:** ``$($c.Sha)``
- **Workflow Run:** #$($c.RunNumber)
"@
        } else {
            $title = if ($isLatestOfVersion) { "SLand v$($c.Version)" } else { "SLand v$($c.Version) ($($c.ShortSha))" }
            $body = Build-FallbackNotes -Version $c.Version -ShortSha $c.ShortSha -FullSha $c.Sha -PrevSuccessSha $prevSha
        }

        if ($DryRun) {
            Write-Host "[DRY-RUN] #$($c.RunNumber) | $tag | $title | artifact=$($c.Artifact.name) ($($c.Artifact.id))"
            $prevSha = $c.Sha
            continue
        }

        $rel = $null
        if ($existingReleases.ContainsKey($tag)) {
            if (-not $OverwriteExisting) {
                Write-Host "[SKIP] Release $tag sudah ada."
                $prevSha = $c.Sha
                continue
            }
            $existing = $existingReleases[$tag]
            $rel = Invoke-GitHubJson -Method "PATCH" -Uri "https://api.github.com/repos/$Owner/$Repo/releases/$($existing.id)" -Headers $headers -Body @{
                tag_name         = $tag
                target_commitish = $c.Sha
                name             = $title
                body             = $body
                draft            = $false
                prerelease       = $false
            }
            Write-Host "[UPDATE] $tag ($title)"
        } else {
            $rel = Invoke-GitHubJson -Method "POST" -Uri "https://api.github.com/repos/$Owner/$Repo/releases" -Headers $headers -Body @{
                tag_name         = $tag
                target_commitish = $c.Sha
                name             = $title
                body             = $body
                draft            = $false
                prerelease       = $false
            }
            Write-Host "[CREATE] $tag ($title)"
        }

        # Unduh artifact zip dan ekstrak APK
        $workDir = Join-Path $tmpRoot "$($c.RunNumber)-$($c.ShortSha)"
        New-Item -ItemType Directory -Force -Path $workDir | Out-Null
        $zipPath = Join-Path $workDir "artifact.zip"
        $zipUrl = "https://api.github.com/repos/$Owner/$Repo/actions/artifacts/$($c.Artifact.id)/zip"
        & curl.exe -sSL --fail -H "Authorization: Bearer $token" -H "Accept: application/vnd.github+json" -H "User-Agent: SLand-Release-Sync" $zipUrl -o $zipPath
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $zipPath)) {
            throw "Gagal mengunduh artifact $($c.Artifact.id) untuk $tag"
        }

        $extractDir = Join-Path $workDir "apk"
        Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

        # Urutkan -release.apk lebih dulu daripada -debug.apk agar updater in-app memilih APK rilis
        $apks = @()
        $apks += Get-ChildItem -Path $extractDir -Filter "*-release.apk" | Sort-Object Name
        $apks += Get-ChildItem -Path $extractDir -Filter "*-debug.apk" | Sort-Object Name
        $apks += Get-ChildItem -Path $extractDir -Filter "*.apk" | Where-Object { $_.Name -notlike "*-release.apk" -and $_.Name -notlike "*-debug.apk" } | Sort-Object Name

        if ($apks.Count -eq 0) {
            throw "Tidak ditemukan berkas .apk pada artifact $($c.Artifact.name)"
        }

        $existingAssets = @{}
        foreach ($ea in $rel.assets) {
            $existingAssets[$ea.name] = $ea.id
        }

        $uploadBase = $rel.upload_url -replace '\{\?name,label\}$', ''
        foreach ($apk in $apks) {
            if ($existingAssets.ContainsKey($apk.Name)) {
                if (-not $OverwriteExisting) { continue }
                Invoke-GitHubJson -Method "DELETE" -Uri "https://api.github.com/repos/$Owner/$Repo/releases/assets/$($existingAssets[$apk.Name])" -Headers $headers | Out-Null
            }
            $encodedName = [Uri]::EscapeDataString($apk.Name)
            $uploadUrl = "${uploadBase}?name=$encodedName"
            $uploadOut = & curl.exe -sSL --fail -X POST `
                -H "Authorization: Bearer $token" `
                -H "Accept: application/vnd.github+json" `
                -H "Content-Type: application/vnd.android.package-archive" `
                -H "User-Agent: SLand-Release-Sync" `
                --data-binary "@$($apk.FullName)" `
                $uploadUrl
            if ($LASTEXITCODE -ne 0) {
                throw "Gagal mengunggah aset $($apk.Name) ke release $tag"
            }
            Write-Host "  + Asset: $($apk.Name) ($([Math]::Round($apk.Length / 1MB, 2)) MB)"
        }

        Remove-Item -Recurse -Force $workDir
        $prevSha = $c.Sha
    }
} finally {
    if (Test-Path $tmpRoot) {
        Remove-Item -Recurse -Force $tmpRoot
    }
}

Write-Host "Sinkronisasi GitHub Releases selesai."
