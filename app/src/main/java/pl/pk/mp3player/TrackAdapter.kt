package pl.pk.mp3player

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Element listy prezentowanej w oknie glownym. */
data class TrackRow(val title: String, val subtitle: String)

/**
 * Adapter listy utworow. Kolejnosc odpowiada kolejnosci playlisty
 * (tryb losowy zmienia wylacznie kolejnosc ODTWARZANIA, nie kolejnosc listy).
 */
class TrackAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    private var rows: List<TrackRow> = emptyList()
    private var currentIndex: Int = -1

    fun submit(newRows: List<TrackRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    fun setCurrentIndex(index: Int) {
        if (index == currentIndex) return
        val previous = currentIndex
        currentIndex = index
        if (previous in rows.indices) notifyItemChanged(previous)
        if (currentIndex in rows.indices) notifyItemChanged(currentIndex)
    }

    fun currentIndex(): Int = currentIndex

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        val isCurrent = position == currentIndex

        holder.number.text = (position + 1).toString()
        holder.title.text = row.title
        holder.subtitle.text = row.subtitle
        holder.indicator.visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
        holder.title.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)

        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.trackNumber)
        val title: TextView = view.findViewById(R.id.trackTitle)
        val subtitle: TextView = view.findViewById(R.id.trackSubtitle)
        val indicator: ImageView = view.findViewById(R.id.trackIndicator)
    }
}
