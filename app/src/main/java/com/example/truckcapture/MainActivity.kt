package com.example.truckcapture

import android.content.ContentValues
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Capture-and-enqueue only. Capture acquires the GoPro Wi-Fi (and nothing else),
 * fires the shutter, downloads the photo, saves it to app storage, records it in
 * the persistent UploadQueue, and enqueues a per-photo UploadWorker. Uploading
 * happens entirely in the background (WorkManager) — the user never waits on it,
 * and it survives app close / reboot.
 */
class MainActivity : AppCompatActivity() {

    private val CAMERA = "http://10.5.5.9"
    private val MEDIA_BASE = "http://10.5.5.9:8080"
    private val SHUTTER_PATH = "/gp/gpControl/command/shutter?p=1"
    // Force the camera into Photo mode before firing, so the field user can't
    // accidentally leave it on video. From the Max's status, Photo = mode 17.
    private val PHOTO_MODE_PATH = "/gp/gpControl/command/mode?p=17"
    private val MEDIA_LIST_PATH = "/gp/gpMediaList"
    private val MAX_ATTEMPTS = 20
    private val POLL_MS = 2000L

    private lateinit var truckIdField: EditText
    private lateinit var captureBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var clearLogBtn: Button
    private lateinit var statusView: TextView
    private lateinit var queueView: TextView
    private lateinit var logView: TextView

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var queue: UploadQueue

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    // Launcher for the QR scanner. On a successful scan we fill the field,
    // parse guid + truck number, show them, and AUTO-CAPTURE.
    private val scanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val raw = result.data?.getStringExtra(ScanActivity.EXTRA_RAW) ?: ""
            val fmt = result.data?.getStringExtra(ScanActivity.EXTRA_FORMAT) ?: "?"
            log("Scanned ($fmt): [$raw]")
            truckIdField.setText(raw)
            val (guid, truckNo) = parseTruckField(raw)
            if (truckNo.isEmpty() && guid.isEmpty()) {
                setStatus("Scan unreadable — enter truck ID manually.")
            } else {
                setStatus("Scanned truck $truckNo (GUID ${guid.take(8)}…) — capturing...")
                captureAndEnqueue()   // auto-capture
            }
        } else {
            setStatus("Scan cancelled.")
        }
    }

    // Handles for a true hard-stop of an in-progress capture: cancelling the
    // coroutine alone won't interrupt a blocking OkHttp read, so we also hold
    // the in-flight Call and cancel it directly (force-closes the socket).
    private var captureJob: kotlinx.coroutines.Job? = null
    private var inFlightCall: okhttp3.Call? = null

    // Clear Queue is destructive, so it requires a second confirming tap.
    private var clearArmed = false

    // Refreshes the queue display once a second while the app is in the
    // foreground, so UPLOADING counts settle to DONE live instead of only on
    // resume. Stopped in onPause so it does nothing in the background; onResume
    // restarts it (and does one immediate refresh to catch background progress).
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshQueueView()
            refreshHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        truckIdField = findViewById(R.id.truckIdField)
        captureBtn   = findViewById(R.id.captureBtn)
        scanBtn      = findViewById(R.id.scanBtn)
        stopBtn      = findViewById(R.id.stopBtn)
        clearBtn     = findViewById(R.id.clearBtn)
        clearLogBtn  = findViewById(R.id.clearLogBtn)
        statusView   = findViewById(R.id.status)
        queueView    = findViewById(R.id.queue)
        logView      = findViewById(R.id.log)

        queue = UploadQueue(this)

        captureBtn.setOnClickListener { captureAndEnqueue() }
        scanBtn.setOnClickListener {
            scanLauncher.launch(android.content.Intent(this, ScanActivity::class.java))
        }
        stopBtn.setOnClickListener { stopCapture() }
        clearBtn.setOnClickListener { clearQueue() }
        clearLogBtn.setOnClickListener { clearLog() }

        stopBtn.isEnabled = false
        refreshQueueView()
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Stop capture — hard-cancels the in-progress capture only.
    // ─────────────────────────────────────────────────────────────────────

    private fun stopCapture() {
        log("Stop requested — cancelling capture.")
        // Cancel the OkHttp call first so a blocked socket read throws now,
        // not after the 90s timeout. Then cancel the coroutine.
        try { inFlightCall?.cancel() } catch (_: Exception) {}
        captureJob?.cancel()
        inFlightCall = null
        // Release the GoPro Wi-Fi we were holding.
        NetworkUtils.releaseNetwork(this, wifiCallback)
        wifiCallback = null
        setStatus("Capture stopped.")
        stopBtn.isEnabled = false
        captureBtn.isEnabled = true
        captureBtn.text = "Capture 360 Photo"
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Clear queue — destructive; cancels pending uploads and deletes files.
    //  Requires two taps to confirm.
    // ─────────────────────────────────────────────────────────────────────

    private fun clearQueue() {
        if (!clearArmed) {
            clearArmed = true
            clearBtn.text = "Tap again to CONFIRM clear"
            setStatus("Clear queue: tap again to confirm, or wait to cancel.")
            // Auto-disarm after 4s so it doesn't stay armed indefinitely.
            clearBtn.postDelayed({
                if (clearArmed) {
                    clearArmed = false
                    clearBtn.text = "Clear Upload Queue"
                    setStatus("Clear cancelled.")
                }
            }, 4000)
            return
        }
        // Confirmed.
        clearArmed = false
        clearBtn.text = "Clear Upload Queue"

        // Cancel all queued/running background uploads.
        androidx.work.WorkManager.getInstance(this).cancelAllWorkByTag("upload")

        // Delete pending local files and clear the index.
        val entries = queue.all()
        var deleted = 0
        entries.forEach { e ->
            try { if (java.io.File(e.path).delete()) deleted++ } catch (_: Exception) {}
            queue.remove(e.id)
        }
        log("Queue cleared: ${entries.size} entries removed, $deleted files deleted.")
        setStatus("Upload queue cleared.")
        refreshQueueView()
    }

    override fun onResume() {
        super.onResume()
        // Immediate refresh to catch progress made while backgrounded, then
        // start the 1s live refresh loop.
        refreshQueueView()
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 1000L)
    }

    override fun onPause() {
        super.onPause()
        // Stop refreshing in the background; onResume will restart and do an
        // immediate refresh when the app is reopened.
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private val MAX_LOG_LINES = 200
    private val logLines = ArrayDeque<String>()

    private fun log(msg: String) {
        android.util.Log.d("TRUCKCAP", msg)
        runOnUiThread {
            logLines.addLast(msg)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            logView.text = logLines.joinToString("\n")
            (logView.parent as? ScrollView)?.post {
                (logView.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun clearLog() {
        runOnUiThread {
            logLines.clear()
            logView.text = ""
        }
    }

    private fun setStatus(msg: String) { runOnUiThread { statusView.text = msg } }

    private fun refreshQueueView() {
        val entries = queue.all().sortedByDescending { it.updatedAt }
        if (entries.isEmpty()) {
            queueView.text = "Queue: empty"
            return
        }
        val counts = entries.groupingBy { it.status }.eachCount()
        val header = "Queue: " + counts.entries.joinToString("  ") { "${it.key}=${it.value}" }
        val lines = entries.take(12).joinToString("\n") { e ->
            "  ${e.truckId}/${e.fileName} — ${e.status}"
        }
        queueView.text = "$header\n$lines"
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Capture: GoPro Wi-Fi only, save locally, enqueue background upload.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Parses the scanned/typed value into (guid, truckNo).
     * QR format is "guid | truckno | othernumbers" (pipe-separated).
     *  - guid    = part before the first '|'  (trimmed)
     *  - truckNo = part between 1st and 2nd '|' (trimmed)
     * If there are no pipes (manual entry), the whole value is treated as the
     * truck number with a blank GUID. (A future GUID lookup can fill that in.)
     */
    private fun parseTruckField(rawInput: String): Pair<String, String> {
        val raw = rawInput.trim()
        if (!raw.contains("|")) return Pair("", raw)   // manual entry: truckNo only
        val parts = raw.split("|")
        val guid = parts.getOrNull(0)?.trim().orEmpty()
        val truckNo = parts.getOrNull(1)?.trim().orEmpty()
        return Pair(guid, truckNo)
    }

    private fun captureAndEnqueue() {
        val rawField = truckIdField.text?.toString()?.trim().orEmpty()
        if (rawField.isEmpty()) {
            setStatus("Scan or enter a Truck ID first.")
            return
        }

        val (guid, truckNo) = parseTruckField(rawField)
        // Folder = truck number. If manual entry gave no truck number, fall back
        // to the raw text so we never write to an empty folder.
        val folder = if (truckNo.isNotEmpty()) truckNo else rawField
        if (folder.isEmpty()) {
            setStatus("Could not determine a truck number.")
            return
        }

        captureBtn.isEnabled = false
        captureBtn.text = "Capturing..."
        stopBtn.isEnabled = true
        setStatus("Truck $folder${if (guid.isNotEmpty()) " (GUID ${guid.take(8)}…)" else ""} — acquiring GoPro Wi-Fi...")
        log("Truck no: $folder | GUID: ${guid.ifEmpty { "(none)" }}")

        captureJob = scope.launch {
            try {
                val wifi = NetworkUtils.acquireNetwork(
                    context = this@MainActivity,
                    transport = NetworkCapabilities.TRANSPORT_WIFI,
                    requireInternet = false,
                    timeoutMs = 8000
                ) { wifiCallback = it }

                if (wifi == null) {
                    log("ERROR: could not acquire the GoPro Wi-Fi.")
                    setStatus("No GoPro Wi-Fi")
                    return@launch
                }
                log("GoPro Wi-Fi acquired.")

                // Read timeout is generous: the GoPro→phone Wi-Fi download is
                // normally fast, but right after a shot the camera may still be
                // finalizing the 360 stitch and the transfer can trickle. 20s
                // was too tight and killed slow-but-working downloads; 90s gives
                // the camera room without hanging forever on a truly dead link.
                val goProClient = NetworkUtils.clientFor(wifi, connectSec = 10, readSec = 90, writeSec = 30)

                setStatus("Checking existing media...")
                val beforeKeys = withContext(Dispatchers.IO) {
                    extract360Jpgs(httpGetString(goProClient, CAMERA + MEDIA_LIST_PATH))
                }.keys.toSet()

                setStatus("Setting photo mode...")
                log("Forcing Photo mode...")
                withContext(Dispatchers.IO) {
                    // Just ensure the camera is in Photo mode (17) before firing.
                    // The shutter command already works; this only guarantees the
                    // user didn't leave it on video/another mode.
                    try { httpGetString(goProClient, CAMERA + PHOTO_MODE_PATH) }
                    catch (e: Exception) { log("Mode set warning: ${e.message}") }
                }
                // Brief pause so the camera settles into Photo mode before shutter.
                delay(600)

                setStatus("Capturing 360 photo...")
                log("Firing shutter...")
                withContext(Dispatchers.IO) { httpGetString(goProClient, CAMERA + SHUTTER_PATH) }

                log("Waiting for the image...")
                var newKey: String? = null
                for (attempt in 1..MAX_ATTEMPTS) {
                    delay(POLL_MS)
                    val current = try {
                        withContext(Dispatchers.IO) {
                            extract360Jpgs(httpGetString(goProClient, CAMERA + MEDIA_LIST_PATH))
                        }
                    } catch (e: Exception) { log("Poll retry: ${e.message}"); continue }
                    val fresh = current.keys.filter { it !in beforeKeys }
                        .sortedByDescending { current[it] ?: 0L }
                    if (fresh.isNotEmpty()) { newKey = fresh.first(); break }
                    log("Still waiting... ($attempt/$MAX_ATTEMPTS)")
                }
                if (newKey == null) throw Exception("No new 360 JPG appeared on the GoPro.")
                log("New image: $newKey")

                setStatus("Downloading from camera...")
                // Upload filename: {guid}_{timestamp}.JPG when we have a GUID,
                // else fall back to the GoPro's own name (manual entry case).
                // Timestamp prevents collisions if a truck is ever re-shot.
                val goProName = newKey.substringAfterLast("/")
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                val uploadName = if (guid.isNotEmpty()) "${guid}_${stamp}.JPG" else goProName
                val url = "$MEDIA_BASE/videos/DCIM/$newKey"
                // Retry the download. If the GoPro Wi-Fi got torn down mid-transfer
                // (Samsung "switch to mobile data", or the link dropping), the old
                // network handle becomes invalid and binding fails with EPERM.
                // Reusing it is pointless, so on a socket/binding failure we
                // RE-ACQUIRE the Wi-Fi network and rebuild the client before retrying.
                var activeClient = goProClient
                val bytes = withContext(Dispatchers.IO) {
                    var lastErr: Exception? = null
                    var result: ByteArray? = null
                    for (attempt in 1..3) {
                        try {
                            val req = Request.Builder().url(url).build()
                            val call = activeClient.newCall(req)
                            inFlightCall = call   // let Stop cancel this blocking read
                            val t0 = System.currentTimeMillis()
                            result = call.execute().use { resp ->
                                if (!resp.isSuccessful) throw Exception("Download HTTP ${resp.code}")
                                resp.body?.bytes() ?: throw Exception("Empty download body")
                            }
                            val ms = System.currentTimeMillis() - t0
                            val kb = (result?.size ?: 0) / 1024
                            val kbps = if (ms > 0) (kb * 1000L / ms) else 0
                            log("Download OK: $kb KB in ${ms}ms (${kbps} KB/s)")
                            inFlightCall = null
                            break
                        } catch (e: Exception) {
                            lastErr = e
                            log("Download attempt $attempt/3 failed: ${e.javaClass.simpleName}: ${e.message}")
                            inFlightCall = null
                            // If this looks like the network went away, re-acquire it.
                            val msg = e.message ?: ""
                            val networkGone = e is java.net.SocketException &&
                                    (msg.contains("EPERM") || msg.contains("Binding socket") ||
                                            msg.contains("connection abort") || msg.contains("ENETUNREACH"))
                            if (networkGone && attempt < 3) {
                                log("GoPro Wi-Fi appears to have dropped — re-acquiring...")
                                NetworkUtils.releaseNetwork(this@MainActivity, wifiCallback)
                                wifiCallback = null
                                val rewifi = NetworkUtils.acquireNetwork(
                                    context = this@MainActivity,
                                    transport = NetworkCapabilities.TRANSPORT_WIFI,
                                    requireInternet = false,
                                    timeoutMs = 8000
                                ) { wifiCallback = it }
                                if (rewifi != null) {
                                    activeClient = NetworkUtils.clientFor(rewifi, connectSec = 10, readSec = 90, writeSec = 30)
                                    log("GoPro Wi-Fi re-acquired.")
                                } else {
                                    log("Could not re-acquire GoPro Wi-Fi.")
                                }
                            } else if (attempt < 3) {
                                Thread.sleep(3000)
                            }
                        }
                    }
                    result ?: throw (lastErr ?: Exception("Download failed"))
                }

                // Save into app-private storage (the Worker reads from here).
                val savedFile = withContext(Dispatchers.IO) { saveForUpload(folder, uploadName, bytes) }
                // Also mirror to Documents for the user's own copy (optional).
                withContext(Dispatchers.IO) { saveToDocuments(folder, uploadName, bytes) }
                log("Saved: ${savedFile.absolutePath} (${bytes.size / 1024} KB)")

                enqueueUpload(savedFile, uploadName, folder)
                setStatus("Captured truck $folder — upload queued.")
                refreshQueueView()

            } catch (e: kotlinx.coroutines.CancellationException) {
                // User tapped Stop — not an error. UI already reset by stopCapture().
                log("Capture cancelled.")
                throw e   // rethrow so the coroutine is properly marked cancelled
            } catch (e: Exception) {
                log("CAPTURE ERROR: ${e.javaClass.simpleName}: ${e.message}")
                setStatus("Capture failed — see log.")
            } finally {
                inFlightCall = null
                NetworkUtils.releaseNetwork(this@MainActivity, wifiCallback)
                wifiCallback = null
                stopBtn.isEnabled = false
                captureBtn.isEnabled = true
                captureBtn.text = "Capture 360 Photo"
            }
        }
    }

    private fun enqueueUpload(file: File, fileName: String, truckId: String) {
        val id = "${truckId}_${fileName}_${System.currentTimeMillis()}"
        queue.add(
            UploadQueue.Entry(
                id = id,
                path = file.absolutePath,
                fileName = fileName,
                truckId = truckId,
                status = "PENDING",
                updatedAt = System.currentTimeMillis()
            )
        )

        val data = Data.Builder()
            .putString(UploadWorker.KEY_ID, id)
            .putString(UploadWorker.KEY_PATH, file.absolutePath)
            .putString(UploadWorker.KEY_FILENAME, fileName)
            .putString(UploadWorker.KEY_TRUCK_ID, truckId)
            .build()

        // Require ANY network (cellular OR wifi). We can't require "cellular
        // only" via constraints, so the Worker itself acquires cellular; this
        // constraint just avoids waking with no connectivity at all.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("upload")
            .build()

        // Unique per photo so re-enqueues don't duplicate, and each photo is
        // independent — one stuck upload never blocks the others.
        WorkManager.getInstance(this)
            .enqueueUniqueWork(id, ExistingWorkPolicy.KEEP, request)

        log("Enqueued background upload: $truckId/$fileName")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Storage
    // ─────────────────────────────────────────────────────────────────────

    /** App-private copy the Worker uploads from. Deleted after successful upload. */
    private fun saveForUpload(truckId: String, fileName: String, bytes: ByteArray): File {
        val dir = File(filesDir, "pending/$truckId").apply { mkdirs() }
        val f = File(dir, fileName)
        f.outputStream().use { it.write(bytes) }
        return f
    }

    /** User-visible copy in Documents (kept regardless of upload state). */
    private fun saveToDocuments(truckId: String, fileName: String, bytes: ByteArray): String {
        val relativeDir = "${Environment.DIRECTORY_DOCUMENTS}/TruckCapture/$truckId"
        val resolver = contentResolver
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
        return "Documents/TruckCapture/$truckId/$fileName"
    }

    // ─────────────────────────────────────────────────────────────────────
    //  GoPro helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun extract360Jpgs(json: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        val root = JSONObject(json)
        val media = root.optJSONArray("media") ?: return out
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
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: throw Exception("Empty body for $url")
        }
    }
}