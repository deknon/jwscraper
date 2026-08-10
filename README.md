# saha Video Downloader

แอป Android (Kotlin + Jetpack Compose) สำหรับเปิดหน้าเว็บใน WebView แล้วดักจับ URL วิดีโอ (MP4 / HLS / JW Player) เพื่อดาวน์โหลด

## วิธีใช้

1. วาง URL ของหน้าเว็บที่มีวิดีโอในช่องข้อความ
2. กดปุ่ม **ไป** — WebView จะโหลดหน้าเว็บ
3. รอให้รายการวิดีโอที่ตรวจพบขึ้นด้านล่าง (ดู badge / Snackbar เมื่อพบรายการใหม่)
4. เลือก URL ที่ต้องการด้วย radio
5. กดปุ่ม **ดาวน์โหลด**
   - **MP4** → ดาวน์โหลดผ่าน `DownloadManager` ไปยังโฟลเดอร์ Downloads
   - **HLS** → แจ้งเตือนว่ายังไม่รวม segment จริง (stub)
   - **UNKNOWN** → พยายามดาวน์โหลดแบบ progressive ผ่าน `DownloadManager`

ปุ่ม **ล้างรายการ** ล้าง URL ที่ตรวจพบทั้งหมด

## ข้อจำกัด

1. **ไม่สามารถดาวน์โหลดวิดีโอที่มี DRM แข็งแรงได้** (Widevine ฯลฯ) — สตรีมที่เข้ารหัสด้วย DRM จะไม่สามารถบันทึกเป็นไฟล์ธรรมดาได้
2. **URL แบบ signed/token อาจหมดอายุเร็ว** — ถ้าลิงก์หมดอายุก่อนกดดาวน์โหลด ให้โหลดหน้าเว็บใหม่แล้วเลือก URL ล่าสุดจากรายการ
3. **HLS (`.m3u8`) เวอร์ชันนี้ดักจับ URL ได้อย่างเดียว** — ยังไม่ดาวน์โหลดหรือรวม segment จริง

## สถาปัตยกรรมหลัก

| ไฟล์ | หน้าที่ |
|------|---------|
| `model/DetectedVideoUrl.kt` | โมเดล URL ที่ตรวจพบ + `VideoType` |
| `webview/VideoUrlMatcher.kt` | pure function จับคู่ URL → ประเภทวิดีโอ (unit-test ได้) |
| `webview/VideoInterceptingWebViewClient.kt` | ดัก request ใน WebView แล้วส่ง callback (thread-safe) |
| `viewmodel/VideoDownloaderViewModel.kt` | StateFlow + synchronized set กัน URL ซ้ำ |
| `ui/MainScreen.kt` | Compose UI: TextField, WebView, LazyColumn, ปุ่มดาวน์โหลด |
| `download/DownloadHelper.kt` | DownloadManager สำหรับ MP4 + จัดการ HLS stub |
| `download/HlsDownloadStrategy.kt` | interface + stub ที่โยน `UnsupportedOperationException` |

## ความต้องการของระบบ

- minSdk 24 / targetSdk 34 / compileSdk 35
- Android Studio (แนะนำ Hedgehog ขึ้นไป) พร้อม JDK 17
- Gradle sync ตาม `gradle/libs.versions.toml` (Compose BOM `2025.08.00`)

> หมายเหตุ: `compileSdk` ตั้งเป็น 35 เพราะ Compose BOM ล่าสุดบังคับ — `targetSdk` ยังเป็น 34 ตามสเปก

## ต้องทำอะไรเพิ่มถ้าจะรองรับ HLS แบบสมบูรณ์

เลือกอย่างใดอย่างหนึ่ง:

### a) Media3 ExoPlayer DownloadService (ทางที่ Google แนะนำ)

- ใช้ `androidx.media3:media3-exoplayer` + `media3-exoplayer-hls` + `media3-datasource`
- สร้าง `DownloadService` ของ Media3 เพื่อ offline caching ของ HLS/DASH
- รองรับ progressive download ของ playlist และ segment ในตัว พร้อม resume

### b) ffmpeg ผ่าน ffmpeg-kit-android

- เพิ่ม dependency `com.arthenica:ffmpeg-kit-full` (หรือ variant ที่เหมาะ)
- รันคำสั่งประมาณ:

```bash
ffmpeg -i playlist.m3u8 -c copy output.mp4
```

- ข้อดี: ได้ไฟล์ MP4 เดียวหลัง mux segment
- ข้อควรระวัง: ขนาดแอปใหญ่ขึ้น และต้องจัดการ network / storage เอง

---

**หมายเหตุ:** แอปนี้สังเกต network ของ WebView เท่านั้น ไม่ bypass DRM และไม่ข้ามข้อจำกัดสิทธิ์ของเนื้อหาต้นทาง
