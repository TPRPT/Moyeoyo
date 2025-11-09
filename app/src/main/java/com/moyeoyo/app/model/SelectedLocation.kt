package com.moyeoyo.app.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SelectedLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double
) : Parcelable
