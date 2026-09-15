package net.thompsoncs.truckcapture.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.thompsoncs.truckcapture.R
import net.thompsoncs.truckcapture.UploadQueue
import net.thompsoncs.truckcapture.databinding.ItemQueueBinding

/**
 * Upload queue rows. Status maps onto the brand's badge colours: green for done,
 * red for failed, orange for in-flight, neutral grey for waiting.
 */
class QueueAdapter : ListAdapter<UploadQueue.Entry, QueueAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<UploadQueue.Entry>() {
            override fun areItemsTheSame(a: UploadQueue.Entry, b: UploadQueue.Entry) = a.id == b.id
            override fun areContentsTheSame(a: UploadQueue.Entry, b: UploadQueue.Entry) = a == b
        }
    }

    class VH(val binding: ItemQueueBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = getItem(position)
        val b = holder.binding
        b.rowTruck.text = e.truckId
        b.rowFile.text = e.fileName

        val (bg, fg) = when (e.status) {
            "DONE" -> R.drawable.bg_badge_ok to R.color.tcs_green
            "FAILED" -> R.drawable.bg_badge_flag to R.color.tcs_red
            "UPLOADING" -> R.drawable.bg_badge_note to R.color.tcs_amber
            else -> R.drawable.bg_badge_neutral to R.color.tcs_muted
        }
        b.rowStatus.setBackgroundResource(bg)
        b.rowStatus.setTextColor(b.root.context.getColor(fg))
        b.rowStatus.text = e.status.lowercase().replaceFirstChar { it.uppercase() }
    }
}
