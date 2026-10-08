package app.waypoint.ui.favourites

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import app.waypoint.core.util.dp
import app.waypoint.data.db.FavouriteEntity
import app.waypoint.data.geo.Geo
import app.waypoint.databinding.ItemFavouriteBinding
import java.util.Collections

class FavouritesAdapter(
    private val onClick: (FavouriteEntity) -> Unit,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<FavouritesAdapter.VH>() {

    val items = mutableListOf<FavouriteEntity>()

    inner class VH(val b: ItemFavouriteBinding) : RecyclerView.ViewHolder(b.root)

    @SuppressLint("NotifyDataSetChanged")
    fun setItems(list: List<FavouriteEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun onItemMove(from: Int, to: Int): Boolean {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return false
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFavouriteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        val ctx = holder.b.root.context
        holder.b.favName.text = f.name
        holder.b.favCoords.text = Geo.fmtCoord(f.lat, f.lon)
        holder.b.favTile.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(11f)
            setColor(ColorUtils.setAlphaComponent(f.colour, 31))
            setStroke(ctx.dp(1f).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(f.colour, 85))
        }
        holder.b.favDot.backgroundTintList = android.content.res.ColorStateList.valueOf(f.colour)
        holder.b.root.setOnClickListener { onClick(f) }
        holder.b.dragHandle.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(holder)
            false
        }
    }
}
