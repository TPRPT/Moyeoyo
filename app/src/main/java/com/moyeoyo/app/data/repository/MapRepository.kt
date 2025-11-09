package com.moyeoyo.app.data.repository

// MapRepository: 지도/위치 관련 데이터 취득과 연산을 담당하는 계층
// - 현재 위치 조회(GPS/FusedLocation)
// - Google Places 자동완성/디테일 조회
// - 그룹원 위치들의 가중중심(중간지점) 계산
// - Distance Matrix API 호출로 멤버별 소요 시간/거리 계산

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import com.moyeoyo.app.R
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.data.model.DistanceResult
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.PlaceCandidate
import com.moyeoyo.app.data.model.PlaceSuggestion
import com.moyeoyo.app.data.model.TimeCandidate
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.data.model.UserDefaultLocation
import com.moyeoyo.app.data.model.UserProfile
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.plusAssign

//MapRepository.kt: 지도와 관련된 모든 데이터 어디서, 어떻게 가져올지 정의

@Singleton
class MapRepository @Inject constructor(
    // 1. Hilt에게 주입을 요청하는 부품들 (직접 만들지 않음)
    private val fusedClient: FusedLocationProviderClient,
    private val geocoder: Geocoder,

    // 2. 프로젝트 규칙에 따라 직접 만드는 부품들
    @ApplicationContext private val context: Context, // `Places.initialize`에 필요하므로 유지
    private val http: OkHttpClient = OkHttpClient(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    // 내부에서 Places 초기화(중복 초기화 방지)
    // - 앱 전역(Application)에서 초기화되어 있어도 안전하게 1회 보장
    private fun ensurePlacesClient() {
        if (!Places.isInitialized()) {
            // strings.xml에 외부 API Key 받아와서 넣어둘 자리
            Places.initialize(
                context.applicationContext,
                context.getString(R.string.google_maps_key),
                Locale.getDefault()
            )
        }
    }

    // =========================
    // Firestore (groups/{groupId}/inputLocations)
    // =========================

    // 현재 사용자 UID
    private fun currentUid(): String? = auth.currentUser?.uid

    fun currentUserId(): String? = currentUid()

    // 그룹의 입력 위치 목록 1회 조회
    suspend fun getInputLocations(groupId: String): List<InputLocation> {
        val snap = firestore.collection("groups")
            .document(groupId)
            .collection("inputLocations")
            .get()
            .await()
        return snap.documents.mapNotNull { it.toInputLocation() }
    }

    // 내 입력 위치 저장/업데이트
    suspend fun saveMyInputLocation(groupId: String, location: InputLocation) {
        val uid = currentUid() ?: location.uid
        firestore.collection("groups")
            .document(groupId)
            .collection("inputLocations")
            .document(uid)
            .set(location.toFirestoreMap())
            .await()
    }

    // =========================
    // Firestore: users / groups / placeCandidates / timeCandidates (조회)
    // =========================

    // users/{uid}
    suspend fun getUserProfile(uid: String): UserProfile? {
        val doc = firestore.collection("users").document(uid).get().await()
        return doc.toObject(UserProfile::class.java)
    }

    suspend fun updateFcmToken(uid: String, token: String) {
        firestore.collection("users").document(uid)
            .update(mapOf("fcmToken" to token)).await()
    }

    suspend fun updateDefaultLocation(uid: String, loc: UserDefaultLocation) {
        firestore.collection("users").document(uid)
            .update(mapOf("defaultLocation" to loc)).await()
    }

    // groups/{groupId}
    suspend fun getGroup(groupId: String): Group? {
        val doc = firestore.collection("groups").document(groupId).get().await()
        return doc.toObject(Group::class.java)?.copy(groupId = groupId)
    }

    // groups/{groupId}/placeCandidates
    suspend fun getPlaceCandidates(groupId: String): List<PlaceCandidate> {
        val snap = firestore.collection("groups").document(groupId)
            .collection("placeCandidates").get().await()
        return snap.documents.mapNotNull { d ->
            val pc = d.toObject(PlaceCandidate::class.java)
            pc?.copy(id = d.id)
        }
    }

    // groups/{groupId}/timeCandidates
    suspend fun getTimeCandidates(groupId: String): List<TimeCandidate> {
        val snap = firestore.collection("groups").document(groupId)
            .collection("timeCandidates").get().await()
        return snap.documents.mapNotNull { d ->
            val tc = d.toObject(TimeCandidate::class.java)
            tc?.copy(id = d.id)
        }
    }

    // 현재 위치 1회 획득 (권한 체크 + 예외 처리)
    // - Activity/Fragment에서 권한이 없는 경우 null 반환 → UI에서 권한 요청 유도
    // - 성공 시 LatLngData로 변환하여 반환
    // 즉, 사용자 현재 위치 비동기로 한번만 가져오는 함수
    suspend fun getCurrentLocation(): LatLngData? = suspendCancellableCoroutine { cont ->
        // 1) 권한 체크 (둘 중 하나라도 승인되면 OK)
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            // 권한이 없으면 null 반환 (콜러가 UI에서 권한 요청 트리거)
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val cts = CancellationTokenSource() // 코루틴 취소 대응
        try {
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    // 위치 객체를 우리 도메인 모델로 매핑
                    cont.resume(loc?.let { LatLngData(it.latitude, it.longitude) })
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        } catch (se: SecurityException) {
            // 혹시 모를 보수적 처리
            cont.resume(null)
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
        cont.invokeOnCancellation { cts.cancel() }
    }

    // Places 자동완성
    // - 검색어(query)에 대한 후보 목록을 PlaceSuggestion 리스트로 반환
    suspend fun findSuggestions(query: String): List<PlaceSuggestion> =
        suspendCancellableCoroutine { cont ->
            ensurePlacesClient()
            val client = Places.createClient(context)
            val request = FindAutocompletePredictionsRequest.builder()
                .setQuery(query)
                .build()

            client.findAutocompletePredictions(request)
                .addOnSuccessListener { res ->
                    val list = res.autocompletePredictions.map {
                        PlaceSuggestion(
                            placeId = it.placeId,
                            label = it.getPrimaryText(null).toString(),
                            address = it.getSecondaryText(null)?.toString()
                        )
                    }
                    cont.resume(list)
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    // Place Details로 placeId -> 위경도
    // - 자동완성에서 선택된 placeId를 실제 좌표로 변환
    suspend fun fetchPlaceLatLng(placeId: String): LatLngData =
        suspendCancellableCoroutine { cont ->
            ensurePlacesClient()
            val client = Places.createClient(context)
            val req = FetchPlaceRequest
                .builder(placeId, listOf(Place.Field.LAT_LNG))
                .build()

            client.fetchPlace(req)
                .addOnSuccessListener { resp ->
                    val latLng: LatLng = resp.place.latLng
                        ?: return@addOnSuccessListener cont.resumeWithException(
                            IllegalStateException("No LAT_LNG in Place")
                        )
                    cont.resume(LatLngData(latLng.latitude, latLng.longitude))
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    // 가중치 규칙
    // - 이동수단에 따라 중간지점 계산 시 기여도를 다르게 부여
    // - 순서: DRIVE(운전) > SUBWAY(지하철) > BUS(버스) > WALK(도보)
    private fun weight(mode: TransportMode) = when (mode) {
        TransportMode.DRIVE -> 1.3
        TransportMode.SUBWAY -> 1.0
        TransportMode.BUS -> 0.9
        TransportMode.WALK -> 0.8
    }

    // 가중중심 계산
    // - 단순 평균이 아닌 이동수단 가중치를 반영해 위경도 평균을 계산
    // - 멤버가 없으면 null
    fun computeWeightedCenter(members: List<InputLocation>): LatLngData? {
        if (members.isEmpty()) return null
        var wSum = 0.0
        var latAcc = 0.0
        var lngAcc = 0.0
        for (m in members) {
            val w = weight(m.transportMode)
            latAcc += w * m.latLng.lat
            lngAcc += w * m.latLng.lng
            wSum += w
        }
        return LatLngData(latAcc / wSum, lngAcc / wSum)
    }

    // Distance Matrix API 호출 (origins UIDs 순서 유지)
    // - Google Distance Matrix Web API 호출
    // - origins 순서와 동일한 결과를 보장하기 위해 인덱스 기반 매핑 유지
    // - 서버키(web key) 사용 권장
    suspend fun fetchDistanceMatrix(
        origins: List<Pair<String, LatLngData>>, // uid to latlng
        destination: LatLngData,
        mode: String = "transit" // driving, walking, transit 등
    ): List<DistanceResult> = suspendCancellableCoroutine { cont ->
        if (origins.isEmpty()) {
            cont.resume(emptyList()); return@suspendCancellableCoroutine
        }
        val key = context.getString(R.string.maps_web_key) // Google Cloud에서 받아올 key 들어갈 자리
        val originsParam = origins.joinToString("|") { "${it.second.lat},${it.second.lng}" }
        val destParam = "${destination.lat},${destination.lng}"
        val url =
            "https://maps.googleapis.com/maps/api/distancematrix/json?origins=$originsParam&destinations=$destParam&mode=$mode&key=$key"

        val req = Request.Builder().url(url).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        cont.resumeWithException(
                            IllegalStateException("DistanceMatrix HTTP ${it.code}")
                        )
                        return
                    }
                    val body = it.body?.string().orEmpty()
                    val json = JSONObject(body) // 응답 JSON 파싱
                    val rows = json.optJSONArray("rows") ?: return
                    val results = mutableListOf<DistanceResult>()
                    for (i in 0 until rows.length()) {
                        val elements = rows.getJSONObject(i).optJSONArray("elements") ?: continue
                        val el0 = elements.optJSONObject(0) ?: continue
                        val status = el0.optString("status")
                        if (status == "OK") {
                            val durationSec = el0.getJSONObject("duration").optInt("value")
                            val distanceMeter = el0.getJSONObject("distance").optInt("value")
                            val (uid, _) = origins[i]
                            // 각 origin(uid)에 대한 결과 매핑
                            results.plusAssign(DistanceResult(uid, durationSec, distanceMeter)) // ✨ 점(.) 하나 찍고 괄호로 감싸주면 끝!
                        }
                    }
                    cont.resume(results)
                }
            }
        })
    }

    // =========================
    // Firestore: placeCandidates / timeCandidates 쓰기 & 투표
    // =========================

    suspend fun addPlaceCandidate(groupId: String, candidate: PlaceCandidate): String {
        val data = mutableMapOf<String, Any>(
            "placeId" to candidate.placeId,
            "name" to candidate.name,
            "voterUids" to candidate.voterUids
        )
        candidate.latLng?.let { data["latLng"] = it }
        val ref = firestore.collection("groups")
            .document(groupId)
            .collection("placeCandidates")
            .add(data)
            .await()
        return ref.id
    }

    suspend fun votePlaceCandidate(groupId: String, candidateId: String, uid: String) {
        firestore.collection("groups")
            .document(groupId)
            .collection("placeCandidates")
            .document(candidateId)
            .update("voterUids", FieldValue.arrayUnion(uid))
            .await()
    }

    suspend fun unvotePlaceCandidate(groupId: String, candidateId: String, uid: String) {
        firestore.collection("groups")
            .document(groupId)
            .collection("placeCandidates")
            .document(candidateId)
            .update("voterUids", FieldValue.arrayRemove(uid))
            .await()
    }

    suspend fun addTimeCandidate(groupId: String, candidate: TimeCandidate): String {
        val data = mutableMapOf<String, Any>(
            "voterUids" to candidate.voterUids
        )
        candidate.time?.let { data["time"] = it }
        val ref = firestore.collection("groups")
            .document(groupId)
            .collection("timeCandidates")
            .add(data)
            .await()
        return ref.id
    }

    suspend fun voteTimeCandidate(groupId: String, candidateId: String, uid: String) {
        firestore.collection("groups")
            .document(groupId)
            .collection("timeCandidates")
            .document(candidateId)
            .update("voterUids", FieldValue.arrayUnion(uid))
            .await()
    }

    suspend fun unvoteTimeCandidate(groupId: String, candidateId: String, uid: String) {
        firestore.collection("groups")
            .document(groupId)
            .collection("timeCandidates")
            .document(candidateId)
            .update("voterUids", FieldValue.arrayRemove(uid))
            .await()
    }

    // =========================
    // 내부 변환 헬퍼
    // =========================

    private fun DocumentSnapshot.toInputLocation(): InputLocation? {
        val data = data ?: return null

        val latLngData = when (val raw = data["latLng"]) {
            is GeoPoint -> LatLngData(raw.latitude, raw.longitude)
            is Map<*, *> -> {
                val lat = (raw["lat"] as? Number)?.toDouble()
                    ?: (raw["latitude"] as? Number)?.toDouble()
                val lng = (raw["lng"] as? Number)?.toDouble()
                    ?: (raw["longitude"] as? Number)?.toDouble()
                if (lat != null && lng != null) LatLngData(lat, lng) else null
            }
            else -> null
        } ?: return null

        val modeName = data["transportMode"] as? String ?: TransportMode.SUBWAY.name
        val label = data["label"] as? String
        val mode = runCatching { TransportMode.valueOf(modeName) }
            .getOrDefault(TransportMode.SUBWAY)

        return InputLocation(
            uid = id,
            latLng = latLngData,
            transportMode = mode,
            label = label
        )
    }

    private fun InputLocation.toFirestoreMap(): Map<String, Any> {
        val data = mutableMapOf<String, Any>(
            "latLng" to GeoPoint(latLng.lat, latLng.lng),
            "transportMode" to transportMode.name
        )
        label?.let { data["label"] = it }
        return data
    }
}