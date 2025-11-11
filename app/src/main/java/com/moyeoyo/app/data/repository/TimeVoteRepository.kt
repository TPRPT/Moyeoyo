package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class TimeVoteRepository {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private fun currentUserUid(): String? = auth.currentUser?.uid

    // ✅ 특정 날짜 문서를 실시간으로 감시
    fun observeVotes(groupId: String, date: String): Flow<Map<String, List<String>>> = callbackFlow {
        val listener: ListenerRegistration = db.collection("groups")
            .document(groupId)
            .collection("timeVotes")
            .document(date)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val data = snapshot?.data as? Map<String, List<String>> ?: emptyMap()
                trySend(data)
            }
        awaitClose { listener.remove() }
    }

    // ✅ 투표 등록
    suspend fun voteTime(groupId: String, date: String, time: String) {
        val uid = currentUserUid() ?: "sample_uid_1"
        val ref = db.collection("groups").document(groupId)
            .collection("timeVotes").document(date)

        try {
            db.runTransaction { tx ->
                val snapshot = tx.get(ref)
                val existingData = snapshot.data ?: hashMapOf()
                val list = (existingData[time] as? MutableList<*>)?.toMutableList() ?: mutableListOf()
                if (!list.contains(uid)) list.add(uid)
                existingData[time] = list
                tx.set(ref, existingData, com.google.firebase.firestore.SetOptions.merge()) // ✅ 수정 포인트
            }.await()

            Log.d("FIRESTORE", "voteTime 성공: $date / $time / uid=$uid")
        } catch (e: Exception) {
            Log.e("FIRESTORE", "voteTime 실패: ${e.message}", e)
        }
    }


    // ✅ 투표 취소
    suspend fun unvoteTime(groupId: String, date: String, time: String) {
        val uid = currentUserUid() ?: return
        val ref = db.collection("groups").document(groupId)
            .collection("timeVotes").document(date)

        db.runTransaction { tx ->
            val snapshot = tx.get(ref)
            val existingData = snapshot.data ?: hashMapOf()
            val list = (existingData[time] as? MutableList<*>)?.toMutableList() ?: mutableListOf()
            list.remove(uid)
            existingData[time] = list
            tx.set(ref, existingData)
        }.await()
    }

    // 테스트용 더미 데이터 자동 생성 (로컬 Firestore 확인용)
    fun seedDummyVotes(groupId: String, date: String) {
        val times = (0..23).map { String.format("%02d:00", it) }

        val dummyData = mutableMapOf<String, Any>()
        for (time in times) {
            val randomCount = (0..5).random()
            val dummyUids = (1..randomCount).map { "user_$it" }
            dummyData[time] = dummyUids
        }

        CoroutineScope(Dispatchers.IO).launch {
            db.collection("groups")
                .document(groupId)
                .collection("timeVotes")
                .document(date)
                .set(dummyData)
                .addOnSuccessListener {
                    println("✅ Firestore 더미 데이터 추가 완료: $date")
                }
                .addOnFailureListener { e ->
                    println("❌ Firestore 더미 데이터 추가 실패: ${e.message}")
                }
        }
        println("🔥 Firestore 더미 데이터 넣는 중: groupId=$groupId, date=$date")

    }


}
