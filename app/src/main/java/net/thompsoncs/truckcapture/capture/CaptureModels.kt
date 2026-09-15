package net.thompsoncs.truckcapture.capture

/**
 * The six stages a capture moves through, in order. These are the same steps
 * the old single-screen build reported via setStatus() — modelling them as data
 * lets the Capturing screen show a live checklist instead of one status line,
 * so a field tech can see exactly where a slow capture is stuck.
 */
enum class CaptureStage(val label: String) {
    WIFI("Connecting to GoPro"),
    PHOTO_MODE("Setting photo mode"),
    SHUTTER("Firing shutter"),
    WAITING("Waiting for the image"),
    DOWNLOAD("Downloading from camera"),
    SAVE("Saving and queueing upload");

    companion object {
        val ordered: List<CaptureStage> = entries.toList()
    }
}

enum class StageState { PENDING, ACTIVE, DONE, FAILED }

/**
 * One row of the Capturing checklist. [detail] carries the live sub-status —
 * "3 / 20", "4.2 MB at 812 KB/s", or an error message on a FAILED row.
 */
data class StageRow(
    val stage: CaptureStage,
    val state: StageState,
    val detail: String? = null
)

/** A photo that made it to local storage and onto the upload queue. */
data class CapturedPhoto(
    val entryId: String,
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val capturedAt: Long,
    /** The GoPro's own name for it, e.g. "100GOPRO/GS__1234.JPG". */
    val sourceName: String
)

/**
 * The truck currently being worked. Survives Capture → Review → Capture so the
 * tech can shoot several angles without rescanning the QR code; [photos] is the
 * running list for this truck only, newest last.
 */
data class TruckSession(
    val guid: String,
    val truckNo: String,
    val photos: List<CapturedPhoto> = emptyList()
) {
    /** What the tech sees on screen; blank GUID means it was typed, not scanned. */
    val wasScanned: Boolean get() = guid.isNotEmpty()
    val photoCount: Int get() = photos.size
}

/** Where the capture flow currently is. Drives which UI the Capturing screen shows. */
sealed interface CaptureState {
    data object Idle : CaptureState

    data class Running(
        val stages: List<StageRow>,
        val canStop: Boolean = true
    ) : CaptureState

    data class Succeeded(val photo: CapturedPhoto) : CaptureState

    data class Failed(
        val stages: List<StageRow>,
        /** Written for a field tech. */
        val message: String,
        /** Raw exception text, shown small; null when [message] already says it. */
        val detail: String? = null
    ) : CaptureState

    /** The tech tapped Stop. Distinct from Failed: it is not an error. */
    data class Stopped(val stages: List<StageRow>) : CaptureState
}
