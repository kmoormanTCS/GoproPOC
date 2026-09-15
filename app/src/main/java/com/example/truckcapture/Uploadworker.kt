package com.example.truckcapture

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

/**
 * Uploads ONE photo to Dropbox in the background. Enqueued per-photo by
 * MainActivity. Survives app death and reboot (WorkManager persists it) and
 * runs when its network constraint is met.
 *
 * Critical: this Worker may run when the Activity is dead, so it CANNOT reuse
 * any network the Activity acquired. It requests its own cellular network,
 * pins socket + DNS to it, uploads, then releases it — the same pattern the
 * capture side uses, just self-contained here.
 *
 * Result contract:
 *  - success(): file uploaded and deleted; queue entry marked DONE.
 *  - retry(): transient failure (no network, timeout, 5xx). WorkManager backs
 *    off exponentially and tries again later, across reboots.
 *  - failure(): permanent (file missing, or genuinely unrecoverable).
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_ID = "entry_id"
        const val KEY_PATH = "path"
        const val KEY_FILENAME = "file_name"
        const val KEY_TRUCK_ID = "truck_id"
        // S3 credentials/bucket/region live in S3Uploader (POC config).
        private const val CELL_TIMEOUT_MS = 15000L
        private const val WIFI_TIMEOUT_MS = 8000L
    }

    override suspend fun doWork(): Result {
        val entryId = inputData.getString(KEY_ID) ?: return Result.failure()
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILENAME) ?: return Result.failure()
        val truckId = inputData.getString(KEY_TRUCK_ID) ?: return Result.failure()

        val queue = UploadQueue(applicationContext)
        val file = File(path)
        if (!file.exists()) {
            // Nothing to upload — treat as done so the job doesn't loop forever.
            queue.updateStatus(entryId, "DONE")
            return Result.success()
        }

        queue.updateStatus(entryId, "UPLOADING")
        android.util.Log.d("TRUCKCAP", "Worker start: $fileName (truck $truckId)")

        // Prefer Wi-Fi that actually has internet (e.g. home/office Wi-Fi). At
        // the truck yard the only Wi-Fi is the GoPro's, which has NO internet,
        // so this request fails there and we fall back to cellular. Requesting
        // NET_CAPABILITY_INTERNET is what distinguishes the two.
        var wifiCallback: ConnectivityManager.NetworkCallback? = null
        var cellCallback: ConnectivityManager.NetworkCallback? = null
        try {
            var network = NetworkUtils.acquireNetwork(
                context = applicationContext,
                transport = NetworkCapabilities.TRANSPORT_WIFI,
                requireInternet = true,
                timeoutMs = WIFI_TIMEOUT_MS
            ) { wifiCallback = it }

            if (network != null) {
                android.util.Log.d("TRUCKCAP", "Worker: using Wi-Fi with internet.")
            } else {
                android.util.Log.d("TRUCKCAP", "Worker: no Wi-Fi internet; trying cellular.")
                network = NetworkUtils.acquireNetwork(
                    context = applicationContext,
                    transport = NetworkCapabilities.TRANSPORT_CELLULAR,
                    requireInternet = true,
                    timeoutMs = CELL_TIMEOUT_MS
                ) { cellCallback = it }
                if (network != null) {
                    android.util.Log.d("TRUCKCAP", "Worker: using cellular.")
                }
            }

            if (network == null) {
                android.util.Log.d("TRUCKCAP", "Worker: no usable network; will retry later.")
                queue.updateStatus(entryId, "PENDING")
                return Result.retry()
            }

            val uploader = S3Uploader(applicationContext) { msg ->
                android.util.Log.d("TRUCKCAP", "[$fileName] $msg")
            }
            // S3 object key = same path scheme as before: {truckno}/{filename}.
            // fileName is already {guid}_{timestamp}.JPG from MainActivity.
            val key = "$truckId/$fileName"

            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uploader.upload(network, file, key)
            }
            return if (ok) {
                file.delete()
                queue.updateStatus(entryId, "DONE")
                android.util.Log.d("TRUCKCAP", "Worker done: $fileName")
                Result.success()
            } else {
                queue.updateStatus(entryId, "PENDING")
                android.util.Log.d("TRUCKCAP", "Worker upload failed: $fileName; retrying.")
                Result.retry()
            }
        } catch (e: Exception) {
            android.util.Log.d("TRUCKCAP", "Worker exception: ${e.javaClass.simpleName}: ${e.message}")
            queue.updateStatus(entryId, "PENDING")
            return Result.retry()
        } finally {
            NetworkUtils.releaseNetwork(applicationContext, wifiCallback)
            NetworkUtils.releaseNetwork(applicationContext, cellCallback)
        }
    }
}