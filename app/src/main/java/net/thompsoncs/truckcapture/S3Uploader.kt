package net.thompsoncs.truckcapture

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import java.io.File

/**
 * Uploads a single file to S3 with a one-shot PutObject, authenticating via
 * Amazon Cognito temporary credentials (NOT a hardcoded key).
 *
 * Why Cognito: the app no longer carries a permanent AWS secret. It asks the
 * Cognito identity pool for temporary, auto-expiring credentials scoped (by the
 * pool's guest IAM role) to only uploading to this bucket. The provider caches
 * them and refreshes automatically when they expire - which is exactly what
 * makes deferred background uploads safe: a photo queued for hours gets fresh
 * credentials at the moment the Worker actually uploads it, not stale ones.
 *
 * Network pinning: the AWS SDK's client has no per-client socket factory, so we
 * pin the whole PROCESS to the acquired network for the duration of the upload
 * (bindProcessToNetwork), then unbind. Safe because the Worker is a
 * single-purpose background job. This also covers the Cognito credential fetch,
 * which is itself a network call and must ride the same network.
 */
class S3Uploader(
    private val context: Context,
    private val log: (String) -> Unit
) {
    companion object {
        // ---- CONFIG ----
        private const val IDENTITY_POOL_ID = "us-east-1:5965e81c-0219-4dd2-8610-b296ebafba5e"
        private const val BUCKET = "tcs-gopro-images"   // <-- fill in your bucket name
        private val REGION = Regions.US_EAST_1
        // ----------------
    }

    // Credentials provider is created once and reused; it caches + auto-refreshes.
    private val credentialsProvider by lazy {
        CognitoCachingCredentialsProvider(
            context.applicationContext,
            IDENTITY_POOL_ID,
            REGION
        )
    }

    /**
     * Uploads [file] to s3://BUCKET/[key], with all traffic (including the
     * Cognito credential fetch) bound to [network].
     * @return true on success.
     */
    fun upload(network: Network, file: File, key: String): Boolean {
        if (!file.exists() || file.length() == 0L) {
            log("S3 upload aborted: file missing or empty (${file.name}).")
            return false
        }

        val cm = context.getSystemService(ConnectivityManager::class.java)

        val metadata = ObjectMetadata().apply {
            contentLength = file.length()
            contentType = "image/jpeg"
        }

        var bound = false
        return try {
            // Pin all sockets in this process to the acquired network. This must
            // wrap BOTH the credential fetch and the upload.
            bound = cm.bindProcessToNetwork(network)
            if (!bound) log("Warning: bindProcessToNetwork returned false; may use default network.")

            val s3 = AmazonS3Client(credentialsProvider, com.amazonaws.regions.Region.getRegion(REGION))

            val t0 = System.currentTimeMillis()
            file.inputStream().use { stream ->
                val req = PutObjectRequest(BUCKET, key, stream, metadata)
                s3.putObject(req)
            }
            val ms = System.currentTimeMillis() - t0
            val kb = file.length() / 1024
            val kbps = if (ms > 0) (kb * 1000L / ms) else 0
            log("S3 upload OK: $key ($kb KB in ${ms}ms, ${kbps} KB/s)")
            true
        } catch (e: Exception) {
            log("S3 upload failed for $key: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        }
    }
}