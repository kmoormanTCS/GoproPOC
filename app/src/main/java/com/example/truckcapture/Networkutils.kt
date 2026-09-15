package com.example.truckcapture

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Shared network plumbing used by BOTH the capture Activity and the background
 * UploadWorker. The Worker can't reuse a network the Activity acquired (it may
 * run when the Activity is dead), so each side acquires its own via these
 * helpers. The key correctness points, learned the hard way:
 *
 *  - socketFactory pins WHERE the connection goes.
 *  - a custom Dns pins where hostname resolution goes. Without it, DNS leaks to
 *    the phone's default network — which, while on the internet-less GoPro
 *    Wi-Fi, fails with UnknownHostException even though the socket is on cell.
 */
object NetworkUtils {

    /**
     * Requests a network of the given transport and suspends until it's
     * available or times out. The caller MUST hold the returned callback and
     * unregister it (via releaseNetwork) when done, or Android tears the
     * network down. Returns null on timeout / unavailable.
     */
    suspend fun acquireNetwork(
        context: Context,
        transport: Int,
        requireInternet: Boolean,
        timeoutMs: Long,
        onCallbackStored: (ConnectivityManager.NetworkCallback) -> Unit
    ): Network? = suspendCoroutine { cont ->
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val builder = NetworkRequest.Builder().addTransportType(transport)
        if (requireInternet) {
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val request = builder.build()

        var resumed = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (resumed) return
                resumed = true
                cont.resume(network)
            }
            override fun onUnavailable() {
                if (resumed) return
                resumed = true
                cont.resume(null)
            }
        }
        onCallbackStored(callback)
        cm.requestNetwork(request, callback, timeoutMs.toInt())
    }

    fun releaseNetwork(context: Context, callback: ConnectivityManager.NetworkCallback?) {
        if (callback == null) return
        try {
            context.getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(callback)
        } catch (_: Exception) {}
    }

    /** OkHttp client pinned to a specific network for BOTH socket and DNS. */
    fun clientFor(
        network: Network,
        connectSec: Long,
        readSec: Long,
        writeSec: Long
    ): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                network.getAllByName(hostname).toList()
        })
        .connectTimeout(connectSec, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS)
        .writeTimeout(writeSec, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}