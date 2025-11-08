package com.example.moyeoyo.data.repository

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
import com.example.moyeoyo.R
import com.example.moyeoyo.data.model.*
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

//MapRepository.kt: 지도와 관련된 모든 데이터 어디서, 어떻게 가져올지 정의

class MapRepository(
    private val context: Context,
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context),
    private val geocoder: Geocoder = Geocoder(context, Locale.KOREA),
    private val http: OkHttpClient = OkHttpClient(),
    private val firestore: FirebaseFirestore? = null, // ⬅️ null 허용 및 기본값 null
    private val auth: FirebaseAuth? = null           // ⬅️ null 허용 및 기본값 null
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
    private fun currentUid(): String? = auth?.currentUser?.uid

    // 그룹의 입력 위치 목록 1회 조회
    suspend fun getInputLocations(groupId: String): List<InputLocation> {
        val db = firestore ?: throw IllegalStateException("Firestore is not initialized")
        val snap = db.collection("groups")
            .document(groupId)
            .collection("inputLocations")
            .get()
            .await()
        return snap.documents.mapNotNull { it.toObject(InputLocation::class.java) }
    }

    // 내 입력 위치 저장/업데이트
    suspend fun saveMyInputLocation(groupId: String, location: InputLocation) {
        val db = firestore ?: throw IllegalStateException("Firestore is not initialized")
        val uid = currentUid() ?: location.uid
        db.collection("groups")
            .document(groupId)
            .collection("inputLocations")
            .document(uid)
            .set(location)
            .await()
    }

    // =========================
    // Firestore: users / groups / placeCandidates / timeCandidates (조회)
    // =========================

    // users/{uid}
    suspend fun getUserProfile(uid: String): UserProfile? {
        val db = firestore ?: throw IllegalStateException("Firestore is not initialized")
        val doc = db.collection("users").document(uid).get().await()
        return doc.toObject(UserProfile::class.java)
    }

    suspend fun updateFcmToken(uid: String, token: String) {
        val db = firestore ?: throw IllegalStateException("Firestore is not initialized")
        db.collection("users").document(uid)
            .update(mapOf("fcmToken" to token)).await()
    }

    suspend fun updateDefaultLocation(uid: String, loc: UserDefaultLocation) {
        val db = firestore ?: throw IllegalStateException("Firestore is not initialized")
        db.collection("users").document(uid)
            .update(mapOf("defaultLocation" to loc)).await()
    }

    // groups/{groupId}
    suspend fun getGroup(groupId: String): Group? {
        val db = firestore ?: throw IllegalStateException("Firestore is not initialized")
        val doc = db.collection("groups").document(groupId).get().await()
        return doc.toObject(Group::class.java)?.copy(groupId = groupId)
    }

    // groups/{groupId}/placeCandidates
    suspend fun getPlaceCandidates(groupId: String): List<PlaceCandidate> {
        val db = firestore ?: throw IllegalStateException("Firestore is not initialized")
        val snap = db.collection("groups").document(groupId)
            .collection("placeCandidates").get().await()
        return snap.documents.mapNotNull { d ->
            val pc = d.toObject(PlaceCandidate::class.java)
            pc?.copy(id = d.id)
        }
    }

    // groups/{groupId}/timeCandidates
    suspend fun getTimeCandidates(groupId: String): List<TimeCandidate> {
        val db = firestore ?: throw IllegalStateException("Firestore is not initialized")
        val snap = db.collection("groups").document(groupId)
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

        val cts = com.google.android.gms.tasks.CancellationTokenSource() // 코루틴 취소 대응
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
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
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
                            results += DistanceResult(uid, durationSec, distanceMeter)
                        }
                    }
                    cont.resume(results)
                }
            }
        })
    }
}