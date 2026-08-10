# saha Video Downloader

แอป Android (Kotlin + Jetpack Compose) สำหรับเปิดหน้าเว็บใน WebView แล้วดักจับ URL วิดีโอ (MP4 / HLS / JW Player) เพื่อดาวน์โหลด

## วิธีใช้

1. วาง URL ของหน้าเว็บที่มีวิดีโอในช่องข้อความ
2. กดปุ่ม **ไป** — WebView จะโหลดหน้าเว็บ
3. รอให้รายการวิดีโอที่ตรวจพบขึ้นด้านล่าง (ดู badge / Snackbar เมื่อพบรายการใหม่)
4. เลือก URL ที่ต้องการด้วย radio
5. กดปุ่ม **ดาวน์โหลด**
   - **MP4** → ดาวน์โหลดผ่าน `DownloadManager` ไปยังโฟลเดอร์ Downloads
   - **HLS** → เลือกโหมด:
     1. **Mux เป็น MP4 (ffmpeg)** — รวม segment เป็นไฟล์ `.mp4` ใน Downloads
     2. **Media3 offline cache** — แคชสำหรับเล่นออฟไลน์ในแอป (ExoPlayer)
   - **UNKNOWN** → พยายามดาวน์โหลดแบบ progressive ผ่าน `DownloadManager`
6. กด **ดาวน์โหลด** มุมขวาบนเพื่อเปิดหน้า library:
   - Media3 → ปุ่ม **เล่น** (ExoPlayer อ่านจาก offline cache)
   - ffmpeg MP4 → ปุ่ม **เปิด** / **แชร์**
   - ลบรายการได้ทั้งสองโหมด

ปุ่ม **ล้างรายการ** ล้าง URL ที่ตรวจพบทั้งหมด (ไม่ลบไฟล์ที่ดาวน์โหลดแล้ว)

## ข้อจำกัด

1. **ไม่สามารถดาวน์โหลดวิดีโอที่มี DRM แข็งแรงได้** (Widevine ฯลฯ) — สตรีมที่เข้ารหัสด้วย DRM จะไม่สามารถบันทึกเป็นไฟล์ธรรมดาได้
2. **URL แบบ signed/token อาจหมดอายุเร็ว** — ถ้าลิงก์หมดอายุก่อนกดดาวน์โหลด ให้โหลดหน้าเว็บใหม่แล้วเลือก URL ล่าสุดจากรายการ
3. **HLS ที่มี encryption / DRM หรือ codec ที่ ffmpeg remux ไม่รองรับ** อาจล้มเหลว — ลองโหมด Media3 cache หรือโหลดหน้าใหม่

## สถาปัตยกรรมหลัก

| ไฟล์ | หน้าที่ |
|------|---------|
| `model/DetectedVideoUrl.kt` | โมเดล URL ที่ตรวจพบ + `VideoType` |
| `model/LibraryDownload.kt` | รายการในหน้า library |
| `webview/VideoUrlMatcher.kt` | pure function จับคู่ URL → ประเภทวิดีโอ (unit-test ได้) |
| `webview/VideoInterceptingWebViewClient.kt` | ดัก request ใน WebView แล้วส่ง callback (thread-safe) |
| `viewmodel/VideoDownloaderViewModel.kt` | StateFlow + synchronized set กัน URL ซ้ำ |
| `viewmodel/DownloadsViewModel.kt` | รวมสถานะ Media3 + ffmpeg history |
| `ui/MainScreen.kt` | Compose UI: TextField, WebView, LazyColumn |
| `ui/DownloadsScreen.kt` | library + offline player / เปิด-แชร์ MP4 |
| `download/DownloadHelper.kt` | DownloadManager สำหรับ MP4 + เมนูเลือกโหมด HLS |
| `download/HlsDownloadStrategy.kt` | interface + `Media3HlsDownloadStrategy` |
| `download/FfmpegHlsDownloadStrategy.kt` | mux HLS → MP4 ด้วย ffmpeg-kit |
| `download/OfflineDownloadRepository.kt` | รวม Media3 downloads + ffmpeg history |
| `download/FfmpegHistoryStore.kt` | persist รายการ MP4 ที่ mux แล้ว |
| `download/VideoDownloadService.kt` | Media3 `DownloadService` (foreground) |
| `download/Media3DownloadUtil.kt` | Singleton cache + `DownloadManager` |

## ความต้องการของระบบ

- minSdk 24 / targetSdk 34 / compileSdk 35
- Android Studio (แนะนำ Hedgehog ขึ้นไป) พร้อม JDK 17
- Gradle sync ตาม `gradle/libs.versions.toml`
  - Compose BOM `2025.08.00`
  - Media3 `1.5.1` (+ `media3-ui`)
  - ffmpeg-kit-https `8.1.7` (`dev.ffmpegkit-maintained`)

> หมายเหตุ: `compileSdk` ตั้งเป็น 35 เพราะ Compose BOM ล่าสุดบังคับ — `targetSdk` ยังเป็น 34 ตามสเปก

## โหมด HLS ที่รองรับแล้ว

### a) Media3 ExoPlayer DownloadService
- offline caching ของ HLS segment ในแอป
- เล่นออฟไลน์ผ่าน ExoPlayer ได้จากหน้า library

### b) ffmpeg-kit (mux เป็น `.mp4`)
- dependency: `dev.ffmpegkit-maintained:ffmpeg-kit-https`
- คำสั่งโดยประมาณ:

```bash
ffmpeg -user_agent "..." -i playlist.m3u8 -c copy -bsf:a aac_adtstoasc -movflags +faststart output.mp4
```

- บันทึกไฟล์ไปยังโฟลเดอร์ Downloads (MediaStore บน API 29+)
- เปิด/แชร์ได้จากหน้า library
- ขนาด APK ใหญ่ขึ้นเพราะ native binaries ของ ffmpeg

---

**หมายเหตุ:** แอปนี้สังเกต network ของ WebView เท่านั้น ไม่ bypass DRM และไม่ข้ามข้อจำกัดสิทธิ์ของเนื้อหาต้นทาง
