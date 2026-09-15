package net.thompsoncs.truckcapture.capture

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the device can currently reach the internet, and how.
 *
 * This exists because the field workflow puts the device on the GoPro's own
 * Wi-Fi, which has no internet at all. Android still reports "Wi-Fi connected",
 * so a plain connectivity check is misleading — the question that matters is
 * whether an upload can actually get out, which is what NET_CAPABILITY_INTERNET
 * answers.
 */
enum class UploadReadiness {
    /** Wi-Fi that actually reaches the internet — office or hotspot. */
    READY_WIFI,

    /** No usable Wi-Fi, but cellular data works. Uploads run over this. */
    READY_CELLULAR,

    /**
     * Connected to a network with no internet — almost always the GoPro's AP.
     * Captures work; uploads wait.
     */
    NO_INTERNET,

    /** Nothing connected at all. */
    OFFLINE;

    val canUpload: Boolean get() = this == READY_WIFI || this == READY_CELLULAR
}

/**
 * Reads current readiness. Checks cellular explicitly rather than trusting the
 * active network, because while the device is joined to the GoPro's AP the
 * active network is that Wi-Fi even though cellular is what uploads will use.
 */
fun readUploadReadiness(context: Context): UploadReadiness {
    val cm = context.getSystemService(ConnectivityManager::class.java)
        ?: return UploadReadiness.OFFLINE

    var sawAnyNetwork = false
    var wifiWithInternet = false
    var cellularWithInternet = false

    for (network in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        sawAnyNetwork = true
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!internet) continue
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifiWithInternet = true
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellularWithInternet = true
    }

    return when {
        wifiWithInternet -> UploadReadiness.READY_WIFI
        cellularWithInternet -> UploadReadiness.READY_CELLULAR
        sawAnyNetwork -> UploadReadiness.NO_INTERNET
        else -> UploadReadiness.OFFLINE
    }
}
