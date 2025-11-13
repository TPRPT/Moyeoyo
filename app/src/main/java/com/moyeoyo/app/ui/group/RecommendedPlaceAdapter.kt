package com.moyeoyo.app.ui.group

import android.animation.ObjectAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R
import com.moyeoyo.app.model.Place

class RecommendedPlaceAdapter :
    RecyclerView.Adapter<RecommendedPlaceAdapter.PlaceViewHolder>() {

    private var placeList = mutableListOf<Place>()

    fun submitList(list: List<Place>) {
        placeList = list.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_place, parent, false)
        return PlaceViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        holder.bind(placeList[position])
    }

    override fun getItemCount(): Int = placeList.size

    inner class PlaceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvName: TextView = itemView.findViewById(R.id.tvPlaceName)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
        private val tvRating: TextView = itemView.findViewById(R.id.tvRating)
        private val tvDistance: TextView = itemView.findViewById(R.id.tvDistance)

        private val layoutLike: View = itemView.findViewById(R.id.layoutLike)
        private val icLike: ImageView = itemView.findViewById(R.id.icLike)
        private val tvLikeCount: TextView = itemView.findViewById(R.id.tvLikeCount)

        fun bind(place: Place) {

            val context = itemView.context

            tvName.text = place.name
            tvCategory.text = place.category
            tvRating.text = "%.1f".format(place.rating)
            tvDistance.text = "평균 %.1fkm · %s".format(place.distanceKm, place.walkingTime)

            // 좋아요 초기 UI
            updateLikeIcon(place.isLiked, context)
            tvLikeCount.text = place.likeCount.toString()

            layoutLike.setOnClickListener {

                place.isLiked = !place.isLiked
                place.likeCount += if (place.isLiked) 1 else -1

                if (place.isLiked) animateLike(icLike)

                updateLikeIcon(place.isLiked, context)
                tvLikeCount.text = place.likeCount.toString()

                notifyDataSetChanged()
            }
        }

        private fun updateLikeIcon(isLiked: Boolean, context: Context) {
            val color = if (isLiked)
                ContextCompat.getColor(context, R.color.brand_blue)
            else
                ContextCompat.getColor(context, R.color.gray_700)

            icLike.setColorFilter(color)
            tvLikeCount.setTextColor(color)
        }

        private fun animateLike(icon: ImageView) {
            val sx = ObjectAnimator.ofFloat(icon, "scaleX", 1f, 1.3f, 1f)
            val sy = ObjectAnimator.ofFloat(icon, "scaleY", 1f, 1.3f, 1f)
            sx.duration = 200
            sy.duration = 200
            sx.start()
            sy.start()
        }
    }
}
