package com.moyeoyo.app.ui.group

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.moyeoyo.app.R
import com.moyeoyo.app.model.Place

class PlaceAdapter : RecyclerView.Adapter<PlaceAdapter.PlaceViewHolder>() {

    private var placeList = mutableListOf<Place>()

    fun submitList(list: List<Place>) {
        placeList = list.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_place, parent, false)
        return PlaceViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        holder.bind(placeList[position])
    }

    override fun getItemCount(): Int = placeList.size

    inner class PlaceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val icLike: ImageView = itemView.findViewById(R.id.icLike)
        private val tvLikeCount: TextView = itemView.findViewById(R.id.tvLikeCount)

        fun bind(place: Place) {
            val context = itemView.context

            itemView.findViewById<TextView>(R.id.tvPlaceName).text = place.name
            itemView.findViewById<TextView>(R.id.tvCategory).text = place.category
            itemView.findViewById<TextView>(R.id.tvRating).text = "%.1f".format(place.rating)

            //이동 수단 필터로 바뀐 walkingTime 표시됨
            itemView.findViewById<TextView>(R.id.tvDistance).text =
                " · %.1fkm · %s".format(place.distanceKm, place.walkingTime)

            // 좋아요 UI
            tvLikeCount.text = place.likeCount.toString()
            updateLikeIcon(place.isLiked, icLike, context)

            // 좋아요 클릭
            itemView.findViewById<View>(R.id.layoutLike).setOnClickListener {
                if (place.isLiked) {
                    // 투표 취소
                    place.isLiked = false
                    place.likeCount -= 1
                    updateLikeIcon(false, icLike, context)
                } else {
                    // 투표 추가 (인원 수 초과 방지)
                    if (place.likeCount >= place.memberCount) {
                        Snackbar.make(
                            itemView,
                            "투표 수는 그룹 인원(${place.memberCount}명)을 초과할 수 없습니다.",
                            Snackbar.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }

                    place.isLiked = true
                    place.likeCount += 1
                    animateLike(icLike)
                    updateLikeIcon(true, icLike, context)
                }

                tvLikeCount.text = place.likeCount.toString()
            }
        }

        private fun updateLikeIcon(isLiked: Boolean, icon: ImageView, context: android.content.Context) {
            val color = if (isLiked)
                ContextCompat.getColor(context, R.color.brand_blue)
            else
                ContextCompat.getColor(context, R.color.gray_700)

            icon.setColorFilter(color)
        }

        private fun animateLike(icon: ImageView) {
            val scaleUpX = ObjectAnimator.ofFloat(icon, "scaleX", 1f, 1.3f, 1f)
            val scaleUpY = ObjectAnimator.ofFloat(icon, "scaleY", 1f, 1.3f, 1f)
            scaleUpX.duration = 200
            scaleUpY.duration = 200
            scaleUpX.start()
            scaleUpY.start()
        }
    }
}
