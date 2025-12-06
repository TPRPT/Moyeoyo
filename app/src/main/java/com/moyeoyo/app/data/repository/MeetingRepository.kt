package com.moyeoyo.app.data.repository

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.data.local.AppDatabase
import com.moyeoyo.app.data.local.NextMeetingEntity
import com.moyeoyo.app.widget.NextMeetingWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MeetingRepository(private val context: Context) {

    private val firestore = FirebaseFirestore.getInstance()
    private val db = AppDatabase.getInstance(context)

    /** Firestore에서 모임 정보를 받아 Room DB에 저장 */
    fun syncFromFirestore(userId: String) {
        firestore.collection("groups")
            .whereArrayContains("memberUids", userId)
            .get()
            .addOnSuccessListener { snapshot ->

                val meetings = snapshot.documents.mapNotNull { doc ->

                    // Firestore 필드명 → confirmedTime, confirmedPlace
                    val confirmedTime = doc.get("confirmedTime")
                    val confirmedPlace = doc.get("confirmedPlace") as? Map<*, *>

                    if (confirmedTime == null || confirmedPlace == null) return@mapNotNull null

                    val time = when (confirmedTime) {
                        is com.google.firebase.Timestamp -> confirmedTime.toDate().time
                        is Long -> confirmedTime   // 혹시 Long으로 저장된 경우 대비
                        else -> return@mapNotNull null
                    }

                    val placeName = confirmedPlace["name"] as? String ?: "장소 미정"
                    val groupName = doc.getString("groupName") ?: "모임"

                    NextMeetingEntity(
                        groupId = doc.id,
                        groupName = groupName,
                        finalMeetingAt = time,
                        finalMeetingPlace = placeName
                    )
                }

                CoroutineScope(Dispatchers.IO).launch {
                    val dao = db.nextMeetingDao()

                    meetings.forEach { dao.upsert(it) }

                    // 위젯 새로고침
                    val intent = Intent(context, NextMeetingWidgetProvider::class.java)
                    intent.action = "android.appwidget.action.APPWIDGET_UPDATE"
                    context.sendBroadcast(intent)
                }
            }
    }

}
