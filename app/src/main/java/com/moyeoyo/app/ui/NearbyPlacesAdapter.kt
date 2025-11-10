package com.moyeoyo.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.databinding.ItemNearbyPlaceBinding

class NearbyPlacesAdapter : RecyclerView.Adapter<NearbyPlacesAdapter.ViewHolder>() {

    private val items = mutableListOf<NearbyPlace>()

    fun submitList(newItems: List<NearbyPlace>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNearbyPlaceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        private val binding: ItemNearbyPlaceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(place: NearbyPlace) = with(binding) {
            textPlaceName.text = place.name
            textPlaceAddress.text = place.address ?: ""
            textPlaceAddress.isVisible = !place.address.isNullOrBlank()

            val categoryText = place.categories.firstOrNull()
            textPlaceCategory.text = categoryText ?: ""
            textPlaceCategory.isVisible = !categoryText.isNullOrBlank()

            val rating = place.rating
            textPlaceRating.text = if (rating != null) {
                textPlaceRating.context.getString(
                    com.moyeoyo.app.R.string.place_rating_format,
                    rating
                )
            } else {
                ""
            }
            textPlaceRating.isVisible = rating != null
        }
    }
}

