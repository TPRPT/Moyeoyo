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
import android.location.Location
import android.util.Log
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
import com.google.firebase.firestore.SetOptions
import com.moyeoyo.app.data.model.DistanceResult
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.PlaceCandidate
import com.moyeoyo.app.data.model.PlaceSuggestion
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.TimeCandidate
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.data.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
    suspend fun getInputLocations(groupId: String): List<InputLocation> = coroutineScope {
        val snap = firestore.collection("groups")
            .document(groupId)
            .collection("inputLocations")
            .get()
            .await()
        val baseLocations = snap.documents.mapNotNull { it.toInputLocation() }
        val nicknameMap = baseLocations.map { location ->
            async {
                val nickname = getUser(location.uid)?.nickname
                location.uid to nickname
            }
        }.awaitAll().toMap()
        baseLocations.map { location ->
            location.copy(nickname = nicknameMap[location.uid])
        }
    }

    // 내 입력 위치 저장/업데이트
    suspend fun saveMyInputLocation(groupId: String, location: InputLocation) {
        val uid = currentUid() ?: location.uid
        firestore.collection("groups")
            .document(groupId)
            .collection("inputLocations")
            .document(uid)
            .set(location.copy(uid = uid).toFirestoreMap())
            .await()
    }

    // =========================
    // Firestore: users / groups / placeCandidates / timeCandidates (조회)
    // =========================

    // users/{uid}
    suspend fun getUser(uid: String): User? {
        val doc = firestore.collection("users").document(uid).get().await()
        return doc.toObject(User::class.java)
    }

    suspend fun updateFcmToken(uid: String, token: String) {
        firestore.collection("users").document(uid)
            .update(mapOf("fcmToken" to token)).await()
    }

    // groups/{groupId}
    suspend fun getGroup(groupId: String): Group? {
        val doc = firestore.collection("groups").document(groupId).get().await()
        return doc.toObject(Group::class.java)?.copy(id = groupId)
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
            // - 순서: WALK(도보) > 대중교통(TRANSIT) > DRIVE(자동차)
    private fun weight(mode: TransportMode) = when (mode) {
                TransportMode.WALK -> 1.3
                TransportMode.TRANSIT -> 1.0
                TransportMode.DRIVE -> 0.8
    }

    // 가중중심 계산
    // - 단순 평균이 아닌 이동수단 가중치를 반영해 위경도 평균을 계산
    // - 멤버가 없으면 null
    fun computeWeightedCenter(members: List<InputLocation>): LatLngData? {
        // 1. 방어 코드: 멤버가 없으면 계산할 수 없으므로 null 반환if (members.isEmpty()) return null

        // 2. 계산을 위한 변수 초기화
        var wSum = 0.0    // "가중치(Weight)의 총합(Sum)"을 담을 변수
        var latAcc = 0.0  // "가중치가 적용된 위도(Latitude)의 누적값(Accumulator)"
        var lngAcc = 0.0  // "가중치가 적용된 경도(Longitude)의 누적값"

        // 3. 핵심 계산 루프: 각 멤버를 순회하며 값을 누적
        for (m in members) {
            // 3-1. 현재 멤버의 이동수단에 따른 '가중치'를 가져옴
            // 예: WALK -> 1.3, TRANSIT -> 1.0, DRIVE -> 0.8
            val w = weight(m.transportMode)

            // 3-2. 가중치를 적용한 위도/경도 값을 누적
            // "이 멤버는 이만큼의 영향력을 가졌으니, 그만큼 좌표 값을 더 세게 더해준다"
            latAcc += w * m.latLng.lat
            lngAcc += w * m.latLng.lng

            // 3-3. 현재 멤버의 가중치를 '가중치 총합'에 더함
            wSum += w
        }

        // 4. 최종 계산 및 반환
        // "가중치가 적용되어 부풀려진 좌표 총합을, 가중치의 총합으로 나누어 '정규화(Normalize)'한다"
        return LatLngData(latAcc / wSum, lngAcc / wSum)
    }

    // Distance Matrix API 호출
    // - Google Distance Matrix Web API에 요청을 보내어 멤버별 소요 시간/거리를 계산
    // - DRIVE 모드의 좌표는 Roads API로 보정해 ZERO_RESULTS를 방지한다.
    suspend fun fetchDistanceMatrix(
        origins: List<InputLocation>,
        destination: LatLngData
    ): List<DistanceResult> = withContext(Dispatchers.IO) {
        if (origins.isEmpty()) return@withContext emptyList()

        val aggregatedResults = mutableListOf<DistanceResult>()
        val groups = origins.groupBy { it.transportMode }

        groups.forEach { (mode, members) ->
            if (members.isEmpty()) return@forEach

            val modeString = when (mode) {
                TransportMode.WALK -> "walking"
                TransportMode.TRANSIT -> "transit"
                TransportMode.DRIVE -> "driving"
            }
            val useSnapToRoad = mode == TransportMode.DRIVE
            val key = context.getString(R.string.google_maps_key)
            val departureParam =
                if (mode == TransportMode.TRANSIT) "&departure_time=${System.currentTimeMillis() / 1000}" else ""
            val adjustedDestination = if (useSnapToRoad) {
                snapToRoad(destination) ?: destination
            } else {
                destination
            }
            val adjustedOrigins = members.map { member ->
                val snappedLatLng = if (useSnapToRoad) {
                    snapToRoad(member.latLng) ?: member.latLng
                } else {
                    member.latLng
                }
                member.uid to snappedLatLng
            }

            val (snapResults, hadZeroResults) = performDistanceMatrixRequest(
                adjustedOrigins,
                adjustedDestination,
                modeString,
                departureParam,
                key,
                useSnapToRoad
            )

            var groupResults = snapResults
            if (useSnapToRoad && (groupResults.isEmpty() || hadZeroResults)) {
                Log.w(
                    "MapRepository",
                    "SnapToRoad 좌표로 경로를 찾지 못해 원본 좌표로 재시도합니다. mode=$modeString"
                )
                val rawOrigins = members.map { it.uid to it.latLng }
                val (fallbackResults, _) = performDistanceMatrixRequest(
                    rawOrigins,
                    destination,
                    modeString,
                    departureParam,
                    key,
                    false
                )
                if (fallbackResults.isNotEmpty()) {
                    groupResults = fallbackResults
                }
            }

            aggregatedResults += groupResults
        }

        aggregatedResults
    }

    private suspend fun performDistanceMatrixRequest(
        origins: List<Pair<String, LatLngData>>,
        destination: LatLngData,
        mode: String,
        departureParam: String,
        key: String,
        snapApplied: Boolean
    ): Pair<List<DistanceResult>, Boolean> = suspendCancellableCoroutine { cont ->
        if (origins.isEmpty()) {
            cont.resume(emptyList<DistanceResult>() to false); return@suspendCancellableCoroutine
        }

        val originsParam = origins.joinToString("|") { "${it.second.lat},${it.second.lng}" }
        val destParam = "${destination.lat},${destination.lng}"
        val url =
            "https://maps.googleapis.com/maps/api/distancematrix/json?origins=$originsParam&destinations=$destParam&mode=$mode$departureParam&key=$key"

        Log.d(
            "MapRepository",
            "Distance Matrix 요청: mode=$mode, origins=${origins.size}, dest=$destParam, snap=$snapApplied"
        )

        val req = Request.Builder().url(url).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!cont.isActive) return
                response.use {
                    if (!it.isSuccessful) {
                        cont.resumeWithException(
                            IllegalStateException("DistanceMatrix HTTP ${it.code}")
                        )
                        return
                    }
                    val body = it.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val matrixStatus = json.optString("status")
                    if (matrixStatus != "OK") {
                        Log.w(
                            "MapRepository",
                            "Distance Matrix 응답 status=$matrixStatus, mode=$mode, snap=$snapApplied, body=$body"
                        )
                    }
                    val rows = json.optJSONArray("rows")
                    if (rows == null || rows.length() == 0) {
                        cont.resume(emptyList<DistanceResult>() to (matrixStatus == "ZERO_RESULTS"));
                        return
                    }
                    val parsed = mutableListOf<DistanceResult>()
                    var zeroResultsEncountered = matrixStatus == "ZERO_RESULTS"
                    for (i in 0 until rows.length()) {
                        val elements = rows.getJSONObject(i).optJSONArray("elements") ?: continue
                        val el0 = elements.optJSONObject(0) ?: continue
                        val status = el0.optString("status")
                        if (status == "OK") {
                            val durationSec = el0.getJSONObject("duration").optInt("value")
                            val distanceMeter = el0.getJSONObject("distance").optInt("value")
                            val uid = origins.getOrNull(i)?.first ?: continue
                            parsed += DistanceResult(uid, durationSec, distanceMeter)
                        } else {
                            if (status == "ZERO_RESULTS") {
                                zeroResultsEncountered = true
                            }
                            Log.w(
                                "MapRepository",
                                "Distance Matrix element status=$status, originIndex=$i, mode=$mode, snap=$snapApplied"
                            )
                        }
                    }
                    cont.resume(parsed to zeroResultsEncountered)
                }
            }
        })
    }

    private fun snapToRoad(latLng: LatLngData): LatLngData? {
        val key = context.getString(R.string.google_maps_key)
        val path = "${latLng.lat},${latLng.lng}"
        val url =
            "https://roads.googleapis.com/v1/snapToRoads?path=$path&interpolate=false&key=$key"
        val request = Request.Builder().url(url).build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(
                        "MapRepository",
                        "snapToRoad 실패: HTTP ${response.code} ${response.message}"
                    )
                    return null
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val snappedPoints = json.optJSONArray("snappedPoints") ?: return null
                if (snappedPoints.length() == 0) return null
                val location = snappedPoints.getJSONObject(0).optJSONObject("location") ?: return null
                val snappedLat = location.optDouble("latitude")
                val snappedLng = location.optDouble("longitude")
                if (snappedLat.isNaN() || snappedLng.isNaN()) return null
                Log.d(
                    "MapRepository",
                    "SnapToRoads 성공: original=(${latLng.lat},${latLng.lng}) snapped=($snappedLat,$snappedLng)"
                )
                LatLngData(snappedLat, snappedLng)
            }
        } catch (e: Exception) {
            Log.e("MapRepository", "SnapToRoads 예외: ${e.message}", e)
            null
        }
    }

    suspend fun fetchNearbyPlaces(
        center: LatLngData,
        radiusMeters: Int = 1500,
        type: String = "point_of_interest"
    ): List<NearbyPlace> = suspendCancellableCoroutine { cont ->
        val key = context.getString(R.string.google_maps_key)
        val url =
            "https://maps.googleapis.com/maps/api/place/nearbysearch/json?location=${center.lat},${center.lng}&radius=$radiusMeters&type=$type&language=ko&key=$key"

        val req = Request.Builder().url(url).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        cont.resumeWithException(
                            IllegalStateException("NearbyPlaces HTTP ${it.code}")
                        )
                        return
                    }
                    val body = it.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val status = json.optString("status")
                    if (status != "OK" && status != "ZERO_RESULTS") {
                        val errorMessage = json.optString("error_message", status)
                        Log.e("MapRepository", "Places API 오류: $status / $errorMessage")
                        cont.resumeWithException(IllegalStateException(errorMessage.ifBlank { status }))
                        return
                    }
                    val results = json.optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        Log.w("MapRepository", "Places API 응답 결과가 비어 있습니다. status=$status")
                        cont.resume(emptyList())
                        return
                    }
                    Log.d(
                        "MapRepository",
                        "Places API 결과 ${results.length()}건, status=$status, firstResult=${
                            results.optJSONObject(0)?.optString("name")
                        }"
                    )
                    val places = mutableListOf<NearbyPlace>()
                    for (i in 0 until results.length()) {
                        val obj = results.optJSONObject(i) ?: continue
                        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
                        val placeId = obj.optString("place_id").takeIf { it.isNotBlank() } ?: continue
                        val geometry = obj.optJSONObject("geometry")
                            ?.optJSONObject("location") ?: continue
                        val lat = geometry.optDouble("lat")
                        val lng = geometry.optDouble("lng")
                        if (lat.isNaN() || lng.isNaN()) continue
                        val typesJson = obj.optJSONArray("types")
                        val categories = mutableListOf<String>()
                        if (typesJson != null) {
                            for (j in 0 until typesJson.length()) {
                                typesJson.optString(j)?.let { categories += it }
                            }
                        }
                        val rating = obj.optDouble("rating").takeUnless { it.isNaN() }
                        val vicinity = obj.optString("vicinity").takeIf { it.isNotBlank() }
                        val latLng = LatLngData(lat, lng)
                        val distanceMeters = calculateDistanceMeters(center, latLng)

                        places += NearbyPlace(
                            placeId = placeId,
                            name = name,
                            address = vicinity,
                            latLng = latLng,
                            categories = categories,
                            rating = rating,
                            distanceMeters = distanceMeters
                        )
                    }
                    cont.resume(places)
                }
            }
        })
    }

    private fun calculateDistanceMeters(from: LatLngData, to: LatLngData): Double {
        val result = FloatArray(1)
        Location.distanceBetween(from.lat, from.lng, to.lat, to.lng, result)
        return result.firstOrNull()?.toDouble() ?: 0.0
    }

    suspend fun saveComputedCenter(groupId: String, center: LatLngData) {
        val data = mapOf(
            "midPoint" to GeoPoint(center.lat, center.lng)
        )
        firestore.collection("groups")
            .document(groupId)
            .set(data, SetOptions.merge())
            .await()
    }

    // =========================
    // Firestore: placeCandidates / timeCandidates 쓰기 & 투표
    // =========================

    suspend fun addPlaceCandidate(groupId: String, candidate: PlaceCandidate): String {
        val data = mutableMapOf<String, Any>(
            "placeId" to candidate.placeId,
            "name" to candidate.name,
            "voterUids" to candidate.voterUids,
            "firstRoundScore" to candidate.firstRoundScore
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

        // latLng 필드 확인 (GeoPoint 또는 Map)
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
        }
        
        // latLng 필드가 없으면 latitude, longitude 별도 필드 확인
        val finalLatLng = latLngData ?: run {
            val lat = (data["latitude"] as? Number)?.toDouble()
            val lng = (data["longitude"] as? Number)?.toDouble()
            if (lat != null && lng != null) LatLngData(lat, lng) else null
        } ?: return null

                val modeName = data["transportMode"] as? String ?: TransportMode.TRANSIT.name
        val label = data["label"] as? String
                val mode = when (modeName.uppercase(Locale.ROOT)) {
                    "WALK" -> TransportMode.WALK
                    "TRANSIT" -> TransportMode.TRANSIT
                    "DRIVE" -> TransportMode.DRIVE
                    "SUBWAY", "BUS" -> TransportMode.TRANSIT
                    else -> TransportMode.TRANSIT
                }

        return InputLocation(
            uid = id,
            latLng = finalLatLng,
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