package net.thompsoncs.truckcapture.capture

import android.content.ContentValues
import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.thompsoncs.truckcapture.NetworkUtils

/**
 * The GoPro capture sequence, with no UI attached.
 *
 * Lifted verbatim (behaviour-wise) out of the old MainActivity so the flow can
 * be driven from a ViewModel and survive rotation — previously the capture ran
 * in an Activity-scoped coroutine and a screen rotation killed it mid-download.
 * All the field-earned resilience is preserved: the 90s read timeout for a
 * camera still stitching a 360 frame, the 20-attempt media-list poll, and the
 * re-acquire-on-EPERM retry for when Android yanks the GoPro Wi-Fi to go back
 * to mobile data.
 *
 * Progress is reported through [onStage] / [onLog] rather than returned, so the
 * caller can render it live.
 */
class CaptureEngine(
    private val context: Context,
    private val onStage: (CaptureStage, StageState, String?) -> Unit,
    private val onLog: (String) -> Unit
) {
    private companion object {
        const val CAMERA = "http://10.5.5.9"
        const val MEDIA_BASE = "http://10.5.5.9:8080"
        const val SHUTTER_PATH = "/gp/gpControl/command/shutter?p=1"

        // Force Photo mode before firing so a tech can't leave the camera on
        // video. On the GoPro Max, Photo = mode 17.
        const val PHOTO_MODE_PATH = "/gp/gpControl/command/mode?p=17"
        const val MEDIA_LIST_PATH = "/gp/gpMediaList"

        const val MAX_ATTEMPTS = 20
        const val POLL_MS = 2000L
    }

    private var wifiCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /**
     * The download call currently in flight. Cancelling the coroutine alone
     * won't interrupt a blocking OkHttp read, so Stop cancels this directly to
     * force-close the socket.
     */
    @Volatile
    private var inFlightCall: okhttp3.Call? = null

    /** Hard-stops an in-progress capture. Safe to call when nothing is running. */
    fun abort() {
        try { inFlightCall?.cancel() } catch (_: Exception) {}
        inFlightCall = null
        releaseWifi()
    }

    private fun releaseWifi() {
        NetworkUtils.releaseNetwork(context, wifiCallback)
        wifiCallback = null
    }

    /**
     * Runs one full capture and returns the photo, saved locally and ready to
     * enqueue. Throws on failure; the caller maps that to [CaptureState.Failed].
     *
     * @param guid    truck GUID from the QR code; blank for manual entry.
     * @param truckNo the folder / truck number the photo is filed under.
     */
    suspend fun capture(guid: String, truckNo: String): CapturedPhoto {
        try {
            // ── 1. GoPro Wi-Fi ──────────────────────────────────────────────
            onStage(CaptureStage.WIFI, StageState.ACTIVE, null)
            val wifi = NetworkUtils.acquireNetwork(
                context = context,
                transport = NetworkCapabilities.TRANSPORT_WIFI,
                requireInternet = false,
                timeoutMs = 8000
            ) { wifiCallback = it }

            if (wifi == null) {
                onStage(CaptureStage.WIFI, StageState.FAILED, "No GoPro Wi-Fi found")
                throw CaptureException(
                    "Couldn't reach the GoPro. Check the camera is on and the " +
                            "phone is joined to its Wi-Fi."
                )
            }
            onLog("Wi-Fi acquired; checking the camera answers.")

            var client = NetworkUtils.clientFor(wifi, connectSec = 10, readSec = 90, writeSec = 30)

            // Snapshot what's already on the card so we can spot the new frame.
            // This stays inside the WIFI stage deliberately: acquiring *a* Wi-Fi
            // network proves nothing — a phone can join the yard's AP instead of
            // the camera's. Until the GoPro answers here we are not connected,
            // and a failure belongs on the "Connecting to GoPro" row rather
            // than falling between two stages with nothing marked red.
            val beforeKeys = try {
                withContext(Dispatchers.IO) {
                    extract360Jpgs(httpGetString(client, CAMERA + MEDIA_LIST_PATH))
                }.keys.toSet()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onLog("Camera did not answer: ${e.javaClass.simpleName}: ${e.message}")
                onStage(CaptureStage.WIFI, StageState.FAILED, "Camera not answering")
                throw CaptureException(
                    "Joined a Wi-Fi network, but the GoPro didn't answer at " +
                            "10.5.5.9. Check the phone is on the camera's own " +
                            "Wi-Fi and not the yard network."
                )
            }
            onStage(CaptureStage.WIFI, StageState.DONE, null)
            onLog("Camera answered. Existing 360 JPGs on card: ${beforeKeys.size}")

            // ── 2. Photo mode ───────────────────────────────────────────────
            onStage(CaptureStage.PHOTO_MODE, StageState.ACTIVE, null)
            withContext(Dispatchers.IO) {
                try { httpGetString(client, CAMERA + PHOTO_MODE_PATH) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { onLog("Mode set warning: ${e.message}") }
            }
            delay(600)   // let the camera settle into Photo mode before shutter
            onStage(CaptureStage.PHOTO_MODE, StageState.DONE, null)

            // ── 3. Shutter ──────────────────────────────────────────────────
            onStage(CaptureStage.SHUTTER, StageState.ACTIVE, null)
            withContext(Dispatchers.IO) { httpGetString(client, CAMERA + SHUTTER_PATH) }
            onStage(CaptureStage.SHUTTER, StageState.DONE, null)
            onLog("Shutter fired.")

            // ── 4. Wait for the frame to appear ─────────────────────────────
            onStage(CaptureStage.WAITING, StageState.ACTIVE, "0 / $MAX_ATTEMPTS")
            var newKey: String? = null
            for (attempt in 1..MAX_ATTEMPTS) {
                delay(POLL_MS)
                onStage(CaptureStage.WAITING, StageState.ACTIVE, "$attempt / $MAX_ATTEMPTS")
                val current = try {
                    withContext(Dispatchers.IO) {
                        extract360Jpgs(httpGetString(client, CAMERA + MEDIA_LIST_PATH))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onLog("Poll $attempt retry: ${e.message}")
                    continue
                }
                val fresh = current.keys.filter { it !in beforeKeys }
                    .sortedByDescending { current[it] ?: 0L }
                if (fresh.isNotEmpty()) { newKey = fresh.first(); break }
            }
            if (newKey == null) {
                onStage(CaptureStage.WAITING, StageState.FAILED, "No new photo appeared")
                throw CaptureException(
                    "The camera never produced a new 360 photo. It may still be " +
                            "stitching — wait a moment and try again."
                )
            }
            onStage(CaptureStage.WAITING, StageState.DONE, null)
            onLog("New image: $newKey")

            // ── 5. Download ─────────────────────────────────────────────────
            onStage(CaptureStage.DOWNLOAD, StageState.ACTIVE, null)
            val goProName = newKey.substringAfterLast("/")
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            // {guid}_{timestamp}.JPG when scanned; the camera's own name on
            // manual entry. The timestamp keeps re-shoots of one truck distinct.
            val uploadName = if (guid.isNotEmpty()) "${guid}_$stamp.JPG" else goProName
            val url = "$MEDIA_BASE/videos/DCIM/$newKey"

            val bytes = withContext(Dispatchers.IO) {
                var lastErr: Exception? = null
                var result: ByteArray? = null
                for (attempt in 1..3) {
                    try {
                        val call = client.newCall(Request.Builder().url(url).build())
                        inFlightCall = call       // so Stop can kill a blocked read
                        val t0 = System.currentTimeMillis()
                        result = call.execute().use { resp ->
                            if (!resp.isSuccessful) throw Exception("Download HTTP ${resp.code}")
                            resp.body?.bytes() ?: throw Exception("Empty download body")
                        }
                        val ms = System.currentTimeMillis() - t0
                        val kb = (result?.size ?: 0) / 1024
                        val kbps = if (ms > 0) kb * 1000L / ms else 0
                        onStage(
                            CaptureStage.DOWNLOAD, StageState.ACTIVE,
                            "${formatSize((result?.size ?: 0).toLong())} at $kbps KB/s"
                        )
                        onLog("Download OK: $kb KB in ${ms}ms ($kbps KB/s)")
                        inFlightCall = null
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        inFlightCall = null
                        throw e
                    } catch (e: Exception) {
                        lastErr = e
                        inFlightCall = null
                        onLog("Download attempt $attempt/3 failed: ${e.javaClass.simpleName}: ${e.message}")
                        onStage(
                            CaptureStage.DOWNLOAD, StageState.ACTIVE,
                            "Retrying ($attempt of 3)…"
                        )

                        // If Android tore the GoPro Wi-Fi down mid-transfer (the
                        // Samsung "switch to mobile data" behaviour), the old
                        // Network handle is dead and binding fails with EPERM.
                        // Reusing it is pointless — re-acquire and rebuild.
                        val msg = e.message ?: ""
                        val networkGone = e is java.net.SocketException &&
                                (msg.contains("EPERM") || msg.contains("Binding socket") ||
                                        msg.contains("connection abort") || msg.contains("ENETUNREACH"))

                        if (networkGone && attempt < 3) {
                            onLog("GoPro Wi-Fi dropped — re-acquiring…")
                            releaseWifi()
                            val rewifi = NetworkUtils.acquireNetwork(
                                context = context,
                                transport = NetworkCapabilities.TRANSPORT_WIFI,
                                requireInternet = false,
                                timeoutMs = 8000
                            ) { wifiCallback = it }
                            if (rewifi != null) {
                                client = NetworkUtils.clientFor(rewifi, 10, 90, 30)
                                onLog("GoPro Wi-Fi re-acquired.")
                            } else {
                                onLog("Could not re-acquire GoPro Wi-Fi.")
                            }
                        } else if (attempt < 3) {
                            delay(3000)
                        }
                    }
                }
                result ?: throw (lastErr ?: Exception("Download failed"))
            }
            onStage(CaptureStage.DOWNLOAD, StageState.DONE, formatSize(bytes.size.toLong()))

            // ── 6. Save ─────────────────────────────────────────────────────
            onStage(CaptureStage.SAVE, StageState.ACTIVE, null)
            val savedFile = withContext(Dispatchers.IO) { saveForUpload(truckNo, uploadName, bytes) }
            // Mirror to Documents so the tech keeps their own copy regardless
            // of whether the upload ever succeeds.
            withContext(Dispatchers.IO) {
                try { saveToDocuments(truckNo, uploadName, bytes) }
                catch (e: Exception) { onLog("Documents copy failed (non-fatal): ${e.message}") }
            }
            onLog("Saved: ${savedFile.absolutePath} (${bytes.size / 1024} KB)")
            onStage(CaptureStage.SAVE, StageState.DONE, null)

            return CapturedPhoto(
                entryId = "${truckNo}_${uploadName}_${System.currentTimeMillis()}",
                fileName = uploadName,
                path = savedFile.absolutePath,
                sizeBytes = bytes.size.toLong(),
                capturedAt = System.currentTimeMillis(),
                sourceName = newKey
            )
        } finally {
            inFlightCall = null
            releaseWifi()
        }
    }

    // ── Storage ─────────────────────────────────────────────────────────────

    /** App-private copy the UploadWorker reads from; deleted after upload. */
    private fun saveForUpload(truckNo: String, fileName: String, bytes: ByteArray): File {
        val dir = File(context.filesDir, "pending/$truckNo").apply { mkdirs() }
        val f = File(dir, fileName)
        f.outputStream().use { it.write(bytes) }
        return f
    }

    /** User-visible copy in Documents, kept regardless of upload state. */
    private fun saveToDocuments(truckNo: String, fileName: String, bytes: ByteArray): String {
        val relativeDir = "${Environment.DIRECTORY_DOCUMENTS}/TruckCapture/$truckNo"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = MediaStore.Files.getContentUri("external")
        val uri = resolver.insert(collection, values) ?: throw Exception("Could not create file")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw Exception("No output stream")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return "Documents/TruckCapture/$truckNo/$fileName"
    }

    // ── GoPro helpers ───────────────────────────────────────────────────────

    /** Maps "folder/NAME.JPG" → mtime for every GS*.JPG (360 stills) on the card. */
    private fun extract360Jpgs(json: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        val media = JSONObject(json).optJSONArray("media") ?: return out
        for (i in 0 until media.length()) {
            val dir = media.getJSONObject(i)
            val folder = dir.optString("d")
            val files = dir.optJSONArray("fs") ?: continue
            for (j in 0 until files.length()) {
                val f = files.getJSONObject(j)
                val name = f.optString("n")
                if (name.matches(Regex("^GS.*\\.JPG$", RegexOption.IGNORE_CASE))) {
                    out["$folder/$name"] = f.optString("mod").toLongOrNull() ?: 0L
                }
            }
        }
        return out
    }

    private fun httpGetString(client: OkHttpClient, url: String): String {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: throw Exception("Empty body for $url")
        }
    }
}

/** A failure with a message already written for a field tech, not a developer. */
class CaptureException(message: String) : Exception(message)

/** "4.2 MB" / "812 KB" — used in stage detail and on the Review screen. */
fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
