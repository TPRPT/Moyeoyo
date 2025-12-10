package com.moyeoyo.app.data.repository

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.data.local.AppDatabase
import com.moyeoyo.app.data.local.NextMeetingEntity
import com.moyeoyo.app.widget.NextMeetingWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MeetingRepository(private val context: Context) {

    private val firestore = FirebaseFirestore.getInstance()
    private val db = AppDatabase.getInstance(context)

    // 수동 동기화 (앱 실행 시 1회 적용)
    fun syncFromFirestore(userId: String) {
        firestore.collection("groups")
            .whereArrayContains("memberUids", userId)
            .get()
            .addOnSuccessListener { snapshot ->

                val meetings = snapshot.documents.mapNotNull { doc ->
                    mapToEntity(doc)
                }

                CoroutineScope(Dispatchers.IO).launch {
                    val dao = db.nextMeetingDao()
                    meetings.forEach { dao.upsert(it) }

                    // 위젯 업데이트
                    sendWidgetRefresh()
                }
            }
    }

    // 🔥 실시간 동기화 (앱이 열려 있는 동안 계속 동작)
    fun observeGroupsRealtime(userId: String) {
        firestore.collection("groups")
            .whereArrayContains("memberUids", userId)
            .addSnapshotListener { snapshot, error ->

                if (error != null) {
                    Log.e("MeetingRepository", "Snapshot error: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot == null) return@addSnapshotListener

                val meetings = snapshot.documents.mapNotNull { doc ->
                    mapToEntity(doc)
                }

                CoroutineScope(Dispatchers.IO).launch {
                    val dao = db.nextMeetingDao()

                    val existingIds = dao.getAllGroupIds()

                    val newIds = meetings.map { it.groupId }

                    val removedIds = existingIds.filter { it !in newIds }

                    removedIds.forEach { dao.deleteByGroupId(it) }

                    meetings.forEach { dao.upsert(it) }

                    // 위젯 새로고침
                    sendWidgetRefresh()
                }
            }
    }

    // 공통 엔티티 변환 함수
    private fun mapToEntity(doc: com.google.firebase.firestore.DocumentSnapshot): NextMeetingEntity? {

        val confirmedPlace = doc.get("confirmedPlace") as? Map<*, *> ?: return null
        val confirmedTime = doc.get("confirmedTime") ?: return null

        val finalAt = when (confirmedTime) {
            is Timestamp -> confirmedTime.toDate().time
            is Long -> confirmedTime
            else -> return null
        }

        val groupName = doc.getString("groupName") ?: "모임"
        val placeName = confirmedPlace["name"] as? String ?: "장소 미정"

        return NextMeetingEntity(
            groupId = doc.id,
            groupName = groupName,
            finalMeetingAt = finalAt,
            finalMeetingPlace = placeName
        )
    }

    // 위젯 업데이트 브로드캐스트
    private fun sendWidgetRefresh() {
        val intent = Intent(context, NextMeetingWidgetProvider::class.java).apply {
            action = "android.appwidget.action.APPWIDGET_UPDATE"
        }
        context.sendBroadcast(intent)
    }
}
