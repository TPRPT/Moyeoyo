package com.moyeoyo.app.ui.place

import android.os.Parcelable
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.RankedPlace
import kotlinx.parcelize.Parcelize

@Parcelize
data class RankedPlaceParcelable(
    val placeId: String,
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    val categories: List<String>,
    val rating: Double?,
    val distanceMeters: Double,
    val rank: Int,
    val score: Int
) : Parcelable {

    companion object {
        fun from(rankedPlace: RankedPlace): RankedPlaceParcelable {
            return RankedPlaceParcelable(
                placeId = rankedPlace.place.placeId,
                name = rankedPlace.place.name,
                address = rankedPlace.place.address,
                lat = rankedPlace.place.latLng.lat,
                lng = rankedPlace.place.latLng.lng,
                categories = rankedPlace.place.categories,
                rating = rankedPlace.place.rating,
                distanceMeters = rankedPlace.place.distanceMeters,
                rank = rankedPlace.rank,
                score = rankedPlace.score
            )
        }
    }

    fun toRankedPlace(): RankedPlace {
        val place = NearbyPlace(
            placeId = placeId,
            name = name,
            address = address,
            latLng = LatLngData(lat, lng),
            categories = categories,
            rating = rating,
            distanceMeters = distanceMeters
        )
        return RankedPlace(place, rank, score)
    }
}

