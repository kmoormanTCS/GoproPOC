package net.thompsoncs.truckcapture.capture

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import net.thompsoncs.truckcapture.UploadQueue
import net.thompsoncs.truckcapture.UploadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Owns the truck session and the capture in progress.
 *
 * Activity-scoped, so the session and a running capture both survive rotation
 * and fragment swaps — that's what lets the tech shoot several angles of one
 * truck (Capture → Review → Capture) without rescanning, and fixes the old
 * build's habit of killing an in-flight download when the phone rotated.
 */
class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val queue = UploadQueue(app)

    private val _session = MutableStateFlow<TruckSession?>(null)
    val session: StateFlow<TruckSession?> = _session.asStateFlow()

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _queueEntries = MutableStateFlow<List<UploadQueue.Entry>>(emptyList())
    val queueEntries: StateFlow<List<UploadQueue.Entry>> = _queueEntries.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /**
     * Whether uploads can currently get out. Watched live, because in the field
     * this flips as the tech joins and leaves the GoPro's Wi-Fi, and the whole
     * point of showing it is to tell them what to do next.
     */
    private val _readiness = MutableStateFlow(UploadReadiness.OFFLINE)
    val readiness: StateFlow<UploadReadiness> = _readiness.asStateFlow()

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = refreshReadiness()
        override fun onLost(network: android.net.Network) = refreshReadiness()
        override fun onCapabilitiesChanged(
            network: android.net.Network,
            caps: NetworkCapabilities
        ) = refreshReadiness()
    }

    private var captureJob: Job? = null
    private var engine: CaptureEngine? = null

    /** Live stage rows, rebuilt as the engine reports progress. */
    private var stages: MutableList<StageRow> = freshStages()

    init {
        refreshQueue()
        refreshReadiness()
        // Default-network callbacks don't fire for the non-active networks we
        // care about (cellular while joined to the GoPro AP), so screens also
        // poll refreshReadiness() while visible.
        try {
            app.getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(connectivityCallback)
        } catch (e: Exception) {
            android.util.Log.d("TRUCKCAP", "Connectivity callback failed: ${e.message}")
        }
    }

    fun refreshReadiness() {
        _readiness.value = readUploadReadiness(getApplication())
    }

    private fun freshStages() = CaptureStage.ordered
        .map { StageRow(it, StageState.PENDING) }
        .toMutableList()

    // ── Session ─────────────────────────────────────────────────────────────

    /**
     * Records exactly what came off the scanner, before any parsing.
     *
     * This exists for field diagnosis: if a truck's QR files a photo under the
     * wrong number, the only way to tell a bad code from a bad parser is to see
     * the raw payload — and a tech on a lot has no access to logcat.
     */
    fun logRawScan(raw: String, format: String) {
        log("SCAN [$format] raw: [$raw]")
    }

    /**
     * Parses a scanned or typed truck value and starts a session.
     *
     * QR format is "guid | truckno | othernumbers". With no pipes (manual
     * entry) the whole value is the truck number and the GUID is blank.
     * Returns false if nothing usable could be read.
     */
    fun startSession(rawInput: String): Boolean {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return false

        val (guid, truckNo) = if (!raw.contains("|")) {
            "" to raw
        } else {
            val parts = raw.split("|")
            parts.getOrNull(0)?.trim().orEmpty() to parts.getOrNull(1)?.trim().orEmpty()
        }

        // Fall back to the raw text so we never file photos under an empty folder.
        val folder = truckNo.ifEmpty { raw }
        if (folder.isEmpty()) return false

        _session.value = TruckSession(guid = guid, truckNo = folder)
        val how = if (raw.contains("|")) "split on '|'" else "no '|' — treated as truck no"
        log("Parsed ($how) → truck [$folder], GUID [${guid.ifEmpty { "none" }}]")
        log("Session started — truck $folder")
        return true
    }

    /** Drops the truck and its photo list. Called when the tech taps Done. */
    fun endSession() {
        _session.value = null
        _captureState.value = CaptureState.Idle
    }

    // ── Capture ─────────────────────────────────────────────────────────────

    fun startCapture() {
        val s = _session.value ?: return
        if (_captureState.value is CaptureState.Running) return

        stages = freshStages()
        _captureState.value = CaptureState.Running(stages.toList())

        val app = getApplication<Application>()
        val eng = CaptureEngine(
            context = app,
            onStage = { stage, state, detail -> updateStage(stage, state, detail) },
            onLog = { log(it) }
        )
        engine = eng

        captureJob = viewModelScope.launch {
            try {
                val photo = eng.capture(guid = s.guid, truckNo = s.truckNo)

                // Review is informational: the upload is enqueued the moment the
                // file is on disk, so walking away never loses a photo.
                enqueueUpload(photo, s.truckNo)

                _session.value = _session.value?.let { cur ->
                    cur.copy(photos = cur.photos + photo)
                }
                _captureState.value = CaptureState.Succeeded(photo)
                refreshQueue()
            } catch (e: CancellationException) {
                log("Capture cancelled.")
                _captureState.value = CaptureState.Stopped(stages.toList())
                throw e
            } catch (e: Exception) {
                // CaptureException messages are already written for a field
                // tech. Anything else gets a plain headline, with the raw
                // exception kept as detail for whoever is testing.
                val isKnown = e is CaptureException
                val msg = if (isKnown) e.message.orEmpty()
                else "Capture failed before the photo was saved."
                val detail = if (isKnown) null else "${e.javaClass.simpleName}: ${e.message}"

                log("CAPTURE ERROR: ${e.javaClass.simpleName}: ${e.message}")
                markActiveStageFailed()
                _captureState.value = CaptureState.Failed(stages.toList(), msg, detail)
            }
        }
    }

    /** Hard-stops the capture: kills the socket first, then the coroutine. */
    fun stopCapture() {
        log("Stop requested.")
        engine?.abort()
        captureJob?.cancel()
        captureJob = null
        _captureState.value = CaptureState.Stopped(stages.toList())
    }

    /** Returns to Idle so the Capture screen can be re-entered cleanly. */
    fun resetCapture() {
        stages = freshStages()
        _captureState.value = CaptureState.Idle
    }

    private fun updateStage(stage: CaptureStage, state: StageState, detail: String?) {
        val idx = stages.indexOfFirst { it.stage == stage }
        if (idx < 0) return
        stages[idx] = StageRow(stage, state, detail)
        // A stage going ACTIVE implies everything before it finished — the
        // engine can skip a DONE report on a non-fatal warning path.
        if (state == StageState.ACTIVE || state == StageState.DONE) {
            for (i in 0 until idx) {
                if (stages[i].state == StageState.PENDING || stages[i].state == StageState.ACTIVE) {
                    stages[i] = stages[i].copy(state = StageState.DONE)
                }
            }
        }
        _captureState.value = CaptureState.Running(stages.toList())
    }

    private fun markActiveStageFailed() {
        val idx = stages.indexOfLast { it.state == StageState.ACTIVE }
        if (idx >= 0) stages[idx] = stages[idx].copy(state = StageState.FAILED)
    }

    // ── Upload queue ────────────────────────────────────────────────────────

    private fun enqueueUpload(photo: CapturedPhoto, truckNo: String) {
        val app = getApplication<Application>()

        queue.add(
            UploadQueue.Entry(
                id = photo.entryId,
                path = photo.path,
                fileName = photo.fileName,
                truckId = truckNo,
                status = "PENDING",
                updatedAt = System.currentTimeMillis()
            )
        )

        val data = Data.Builder()
            .putString(UploadWorker.KEY_ID, photo.entryId)
            .putString(UploadWorker.KEY_PATH, photo.path)
            .putString(UploadWorker.KEY_FILENAME, photo.fileName)
            .putString(UploadWorker.KEY_TRUCK_ID, truckNo)
            .build()

        // Any connectivity will do — the Worker acquires cellular itself. This
        // constraint only stops it waking with no network at all.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("upload")
            .build()

        // Unique per photo, so each upload is independent and one stuck
        // transfer never blocks the rest.
        WorkManager.getInstance(app)
            .enqueueUniqueWork(photo.entryId, ExistingWorkPolicy.KEEP, request)

        log("Enqueued upload: $truckNo/${photo.fileName}")
    }

    fun refreshQueue() {
        _queueEntries.value = queue.all().sortedByDescending { it.updatedAt }
    }

    /** Destructive: cancels pending uploads and deletes the local files. */
    fun clearQueue() {
        val app = getApplication<Application>()
        WorkManager.getInstance(app).cancelAllWorkByTag("upload")

        val entries = queue.all()
        var deleted = 0
        entries.forEach { e ->
            try { if (File(e.path).delete()) deleted++ } catch (_: Exception) {}
            queue.remove(e.id)
        }
        log("Queue cleared: ${entries.size} entries, $deleted files deleted.")
        refreshQueue()
    }

    fun queueCounts(): Map<String, Int> =
        _queueEntries.value.groupingBy { it.status }.eachCount()

    // ── Diagnostics log ─────────────────────────────────────────────────────

    private val maxLogLines = 300

    private fun log(msg: String) {
        android.util.Log.d("TRUCKCAP", msg)
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val next = _log.value + "$stamp  $msg"
        _log.value = if (next.size > maxLogLines) next.takeLast(maxLogLines) else next
    }

    fun clearLog() { _log.value = emptyList() }

    override fun onCleared() {
        super.onCleared()
        engine?.abort()
        try {
            getApplication<Application>()
                .getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(connectivityCallback)
        } catch (_: Exception) {}
    }
}
