package com.example.truckcapture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight persistent queue of photos awaiting upload. Backed by a single
 * SharedPreferences JSON string, so it survives app death and reboot without a
 * database dependency. Each entry records enough to run the upload from a cold
 * start: the local file path, the truck ID, and a status.
 *
 * This is intentionally simple. When the app moves to S3, AWS TransferUtility
 * keeps its own persistent transfer records and this class is retired.
 */
class UploadQueue(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("upload_queue", Context.MODE_PRIVATE)
    private val KEY = "entries"

    data class Entry(
        val id: String,          // unique; also the WorkManager unique work name
        val path: String,        // absolute local file path
        val fileName: String,    // name to use in Dropbox
        val truckId: String,
        val status: String,      // PENDING | UPLOADING | DONE | FAILED
        val updatedAt: Long
    )

    @Synchronized
    fun all(): List<Entry> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                id = o.getString("id"),
                path = o.getString("path"),
                fileName = o.getString("fileName"),
                truckId = o.getString("truckId"),
                status = o.getString("status"),
                updatedAt = o.optLong("updatedAt", 0L)
            )
        }
    }

    @Synchronized
    fun add(entry: Entry) {
        val list = all().toMutableList()
        list.removeAll { it.id == entry.id }
        list.add(entry)
        persist(list)
    }

    @Synchronized
    fun updateStatus(id: String, status: String) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(status = status, updatedAt = System.currentTimeMillis())
            persist(list)
        }
    }

    @Synchronized
    fun remove(id: String) {
        persist(all().filterNot { it.id == id })
    }

    @Synchronized
    fun get(id: String): Entry? = all().firstOrNull { it.id == id }

    private fun persist(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("path", e.path)
                put("fileName", e.fileName)
                put("truckId", e.truckId)
                put("status", e.status)
                put("updatedAt", e.updatedAt)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}