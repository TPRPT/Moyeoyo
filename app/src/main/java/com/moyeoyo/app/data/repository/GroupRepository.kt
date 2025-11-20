package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.Timestamp
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.model.Vote
import com.moyeoyo.app.data.model.FinalCandidateData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private val groupsCollection = db.collection("groups")
    private val usersCollection = db.collection("users")
    private val TAG = "GroupRepository"

    // -------------------------------------------------------
    // 1) 그룹 생성
    // -------------------------------------------------------
    suspend fun createGroupWithMembers(groupName: String, friendUids: List<String>): String? {
        val hostUid = auth.currentUser?.uid ?: return null

        val allMemberUids = (friendUids + hostUid).distinct()

        val newGroup = Group(
            groupName = groupName,
            hostUid = hostUid,
            memberUids = allMemberUids,
            status = "GROUP_CREATED"
        )

        return try {
            val groupRef = groupsCollection.add(newGroup).await()
            val groupId = groupRef.id

            // 기본 inputLocations 생성
            groupsCollection.document(groupId)
                .collection("inputLocations")
                .document(hostUid)
                .set(
                    mapOf(
                        "latLng" to GeoPoint(0.0, 0.0),
                        "transportMode" to "UNKNOWN",
                        "timestamp" to Timestamp.now()
                    )
                ).await()

            // 투표 문서 생성
            groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")
                .set(
                    Vote(
                        status = "RANKING",
                        rankedUsers = emptyList(),
                        finalCandidates = emptyList(),
                        finalVotedUsers = emptyList()
                    )
                ).await()

            // 모든 유저에 groupId 추가
            val batch = db.batch()
            allMemberUids.forEach { uid ->
                batch.update(
                    usersCollection.document(uid),
                    "groups",
                    FieldValue.arrayUnion(groupId)
                )
            }
            batch.commit().await()

            Log.d(TAG, "Group created successfully: $groupId")
            groupId
        } catch (e: Exception) {
            Log.e(TAG, "Failed createGroupWithMembers: ${e.message}")
            null
        }
    }

    // -------------------------------------------------------
    // 2) 투표 시작
    // -------------------------------------------------------
    suspend fun startVoting(groupId: String): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        val groupRef = groupsCollection.document(groupId)

        return try {
            db.runTransaction { tx ->
                val snapshot = tx.get(groupRef)
                if (!snapshot.exists()) throw IllegalStateException("Group not found")

                val hostUid = snapshot.getString("hostUid")
                if (hostUid != uid) {
                    throw IllegalStateException("Only host can start voting")
                }

                tx.update(groupRef, "status", "TIME_VOTE_REQUIRED")
                null
            }.await()

            Log.d(TAG, "Voting STARTED for group: $groupId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startVoting error: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------
    // 3) 그룹 참여
    // -------------------------------------------------------
    suspend fun joinGroup(groupId: String): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        val groupRef = groupsCollection.document(groupId)
        val userRef = usersCollection.document(uid)

        return try {
            db.runTransaction { tx ->
                val snapshot = tx.get(groupRef)
                if (!snapshot.exists()) throw IllegalStateException("Group missing")

                val status = snapshot.getString("status") ?: "GROUP_CREATED"
                if (status != "GROUP_CREATED") {
                    throw IllegalStateException("Voting already started. Cannot join.")
                }

                @Suppress("UNCHECKED_CAST")
                val members = snapshot.get("memberUids") as? List<String> ?: emptyList()
                if (members.contains(uid)) return@runTransaction null

                val newMembers = members + uid
                tx.update(groupRef, "memberUids", newMembers)

                val locationRef = groupRef.collection("inputLocations").document(uid)
                tx.set(
                    locationRef,
                    mapOf(
                        "latLng" to GeoPoint(0.0, 0.0),
                        "transportMode" to "UNKNOWN",
                        "timestamp" to Timestamp.now()
                    )
                )

                tx.update(userRef, "groups", FieldValue.arrayUnion(groupId))

                null
            }.await()

            true
        } catch (e: Exception) {
            Log.e(TAG, "joinGroup error: ${e.message}")
            false
        }
    }

    // -------------------------------------------------------
    // 이하 공통 CRUD
    // -------------------------------------------------------
    suspend fun getGroupById(groupId: String): Group? {
        return try {
            val snapshot = groupsCollection.document(groupId).get().await()
            if (snapshot.exists())
                snapshot.toObject(Group::class.java)?.copy(id = snapshot.id)
            else null
        } catch (e: Exception) {
            Log.e(TAG, "getGroupById error: ${e.message}", e)
            null
        }
    }

    suspend fun getGroupsForUser(): List<Group> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        return try {
            val userDoc = usersCollection.document(uid).get().await()
            @Suppress("UNCHECKED_CAST")
            val groupIds = userDoc.get("groups") as? List<String> ?: emptyList()
            if (groupIds.isEmpty()) return emptyList()

            val result = mutableListOf<Group>()
            groupIds.chunked(10).forEach { chunk ->
                val snap = groupsCollection.whereIn(FieldPath.documentId(), chunk).get().await()
                result.addAll(snap.toObjects(Group::class.java))
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "getGroupsForUser error: ${e.message}")
            emptyList()
        }
    }

    // 그룹에서 특정 멤버 제거
    suspend fun removeMember(groupId: String, memberUid: String): Boolean {
        return try {
            val groupRef = groupsCollection.document(groupId)
            val userRef = usersCollection.document(memberUid)

            db.runTransaction { tx ->
                val snap = tx.get(groupRef)
                if (!snap.exists()) throw IllegalStateException("Group missing")

                @Suppress("UNCHECKED_CAST")
                val members = snap.get("memberUids") as? List<String> ?: emptyList()

                if (!members.contains(memberUid)) return@runTransaction null

                val newMembers = members.filter { it != memberUid }
                tx.update(groupRef, "memberUids", newMembers)

                tx.update(userRef, "groups", FieldValue.arrayRemove(groupId))

                tx.delete(groupRef.collection("inputLocations").document(memberUid))
                null
            }.await()

            true
        } catch (e: Exception) {
            false
        }
    }

    // 내가 그룹 나가기
    suspend fun leaveGroup(groupId: String) =
        removeMember(groupId, auth.currentUser?.uid ?: "")


    // 그룹 삭제
    suspend fun deleteGroup(groupId: String): Boolean {
        val groupRef = groupsCollection.document(groupId)

        return try {
            val snap = groupRef.get().await()
            @Suppress("UNCHECKED_CAST")
            val members = snap.get("memberUids") as? List<String> ?: emptyList()

            // 하위 컬렉션 삭제
            deleteCollection(groupRef.collection("inputLocations"))
            deleteCollection(groupRef.collection("placeCandidates"))
            deleteCollection(groupRef.collection("timeCandidates"))

            // 그룹 문서 삭제
            groupRef.delete().await()

            // 모든 유저에서 groupId 제거
            val batch = db.batch()
            members.forEach { uid ->
                batch.update(usersCollection.document(uid), "groups", FieldValue.arrayRemove(groupId))
            }
            batch.commit().await()

            true
        } catch (e: Exception) {
            false
        }
    }

    // 하위 컬렉션 삭제
    private suspend fun deleteCollection(col: CollectionReference, batchSize: Int = 100) {
        val snap = col.limit(batchSize.toLong()).get().await()
        if (snap.isEmpty) return

        val batch = db.batch()
        snap.documents.forEach { batch.delete(it.reference) }
        batch.commit().await()
    }

    // -------------------------------------------------------
    // 투표 시스템 전체 (HEAD 유지)
    // -------------------------------------------------------
    suspend fun addUserToRankedList(groupId: String, uid: String) {
        try {
            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")

            voteRef.update("rankedUsers", FieldValue.arrayUnion(uid)).await()
            Log.d(TAG, "User $uid added to rankedUsers for group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add user to rankedUsers: ${e.message}", e)
            throw e
        }
    }

    suspend fun checkAllUsersRanked(groupId: String): Boolean {
        return try {
            val group = getGroupById(groupId) ?: return false
            val totalMembers = group.memberUids.size

            val voteSnapshot = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")
                .get().await()

            @Suppress("UNCHECKED_CAST")
            val rankedUsers = voteSnapshot.get("rankedUsers") as? List<String> ?: emptyList()

            rankedUsers.size == totalMembers &&
                    group.memberUids.all { it in rankedUsers }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check all users ranked: ${e.message}", e)
            false
        }
    }

    suspend fun updateVoteStatus(groupId: String, status: String) {
        try {
            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")
            voteRef.update("status", status).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update vote status: ${e.message}", e)
            throw e
        }
    }

    suspend fun updateFinalCandidates(groupId: String, finalCandidates: List<FinalCandidateData>) {
        try {
            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")

            val candidatesData = finalCandidates.map { candidate ->
                mapOf(
                    "placeId" to candidate.placeId,
                    "name" to candidate.name,
                    "latLng" to candidate.latLng,
                    "totalScore" to candidate.totalScore,
                    "categories" to candidate.categories,
                    "rating" to (candidate.rating ?: ""),
                    "address" to (candidate.address ?: "")
                )
            }

            voteRef.update(
                "finalCandidates", candidatesData,
                "status", "FINAL_VOTING"
            ).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update final candidates: ${e.message}", e)
            throw e
        }
    }

    suspend fun addUserToFinalVotedList(groupId: String, uid: String) {
        try {
            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")
            voteRef.update("finalVotedUsers", FieldValue.arrayUnion(uid)).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add user: ${e.message}", e)
            throw e
        }
    }

    suspend fun checkAllUsersFinalVoted(groupId: String): Boolean {
        return try {
            val group = getGroupById(groupId) ?: return false
            val totalMembers = group.memberUids.size

            val voteSnapshot = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")
                .get(Source.SERVER)
                .await()

            @Suppress("UNCHECKED_CAST")
            val finalVotedUsers =
                voteSnapshot.get("finalVotedUsers") as? List<String> ?: emptyList()

            finalVotedUsers.size == totalMembers &&
                    group.memberUids.all { it in finalVotedUsers }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check all users final voted: ${e.message}", e)
            false
        }
    }

    suspend fun getVoteStatus(groupId: String): Vote? {
        return try {
            val snapshot = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")
                .get()
                .await()

            if (snapshot.exists()) {
                convertSnapshotToVote(snapshot)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed getVoteStatus: ${e.message}")
            null
        }
    }

    private fun convertSnapshotToVote(snapshot: DocumentSnapshot): Vote {
        val status = snapshot.getString("status") ?: "RANKING"
        @Suppress("UNCHECKED_CAST")
        val rankedUsers = snapshot.get("rankedUsers") as? List<String> ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val finalVotedUsers = snapshot.get("finalVotedUsers") as? List<String> ?: emptyList()
        val winningPlaceId = snapshot.getString("winningPlaceId")
        val winningPlaceName = snapshot.getString("winningPlaceName")

        @Suppress("UNCHECKED_CAST")
        val finalCandidates =
            (snapshot.get("finalCandidates") as? List<Map<String, Any>>)?.mapNotNull { map ->
                try {
                    val placeId = map["placeId"] as? String ?: return@mapNotNull null
                    val name = map["name"] as? String ?: return@mapNotNull null

                    val latLng = when (val geo = map["latLng"]) {
                        is GeoPoint -> geo
                        is Map<*, *> -> {
                            val lat = (geo["lat"] as? Number)?.toDouble()
                                ?: (geo["latitude"] as? Number)?.toDouble()
                            val lng = (geo["lng"] as? Number)?.toDouble()
                                ?: (geo["longitude"] as? Number)?.toDouble()
                            if (lat != null && lng != null) GeoPoint(lat, lng) else null
                        }
                        else -> null
                    } ?: return@mapNotNull null

                    val totalScore = (map["totalScore"] as? Number)?.toInt() ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val categories = map["categories"] as? List<String> ?: emptyList()
                    val rating = when (val r = map["rating"]) {
                        is Number -> r.toDouble()
                        is String -> r.toDoubleOrNull()
                        else -> null
                    }
                    val address = map["address"] as? String

                    FinalCandidateData(
                        placeId = placeId,
                        name = name,
                        latLng = latLng,
                        totalScore = totalScore,
                        categories = categories,
                        rating = rating,
                        address = address
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "convert finalCandidates error: ${e.message}")
                    null
                }
            } ?: emptyList()

        return Vote(
            status = status,
            rankedUsers = rankedUsers,
            finalCandidates = finalCandidates,
            finalVotedUsers = finalVotedUsers,
            winningPlaceId = winningPlaceId,
            winningPlaceName = winningPlaceName
        )
    }

    fun listenToVoteStatus(groupId: String): Flow<Vote?> = callbackFlow {
        val voteRef = groupsCollection.document(groupId)
            .collection("vote")
            .document("vote")

        val listener = voteRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "vote listener error: ${error.message}")
                trySend(null)
                return@addSnapshotListener
            }
            trySend(snapshot?.let { convertSnapshotToVote(it) })
        }
        awaitClose { listener.remove() }
    }

    suspend fun setWinningPlace(groupId: String, placeId: String, placeName: String) {
        try {
            groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")
                .update(
                    "winningPlaceId", placeId,
                    "winningPlaceName", placeName,
                    "status", "FINISHED"
                )
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "setWinningPlace error: ${e.message}")
            throw e
        }
    }

    suspend fun resetVoteStatus(groupId: String) {
        try {
            groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")
                .update(
                    "status", "RANKING",
                    "rankedUsers", emptyList<String>(),
                    "finalCandidates", emptyList<Map<String, Any>>(),
                    "finalVotedUsers", emptyList<String>(),
                    "winningPlaceId", null,
                    "winningPlaceName", null
                )
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "resetVoteStatus error: ${e.message}")
            throw e
        }
    }
}
