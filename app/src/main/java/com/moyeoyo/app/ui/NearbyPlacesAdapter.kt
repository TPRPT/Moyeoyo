package com.moyeoyo.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.databinding.ItemNearbyPlaceBinding

class NearbyPlacesAdapter(
    private val onPlaceSelected: (NearbyPlace) -> Unit
) : RecyclerView.Adapter<NearbyPlacesAdapter.ViewHolder>() {

    private val items = mutableListOf<NearbyPlace>()
    private var selectedPlaceId: String? = null

    fun submitList(newItems: List<NearbyPlace>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateSelected(placeId: String?) {
        if (selectedPlaceId == placeId) return
        val previousId = selectedPlaceId
        selectedPlaceId = placeId
        previousId?.let { prev ->
            val prevIndex = items.indexOfFirst { it.placeId == prev }
            if (prevIndex >= 0) notifyItemChanged(prevIndex)
        }
        placeId?.let { current ->
            val newIndex = items.indexOfFirst { it.placeId == current }
            if (newIndex >= 0) notifyItemChanged(newIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNearbyPlaceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding, ::handleSelection)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], selectedPlaceId)
    }

    override fun getItemCount(): Int = items.size

    private fun handleSelection(place: NearbyPlace) {
        updateSelected(place.placeId)
        onPlaceSelected(place)
    }

    class ViewHolder(
        private val binding: ItemNearbyPlaceBinding,
        private val onSelect: (NearbyPlace) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(place: NearbyPlace, selectedPlaceId: String?) = with(binding) {
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

            val isSelected = place.placeId == selectedPlaceId
            root.strokeWidth = if (isSelected) {
                (root.resources.displayMetrics.density * 2.5f).toInt()
            } else {
                0
            }
            root.setOnClickListener { onSelect(place) }
        }
    }
}

