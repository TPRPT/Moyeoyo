package com.moyeoyo.app.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Group(
    val name: String,
    val memberCount: Int,
    val date: String?,
    val location: String?,
    val isVoting: Boolean, // true면 투표중, false면 완료
    val dDay: String? = null // D-5 등
) : Parcelable
