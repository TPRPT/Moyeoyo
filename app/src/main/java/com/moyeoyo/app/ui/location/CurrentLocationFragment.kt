package com.moyeoyo.app.ui.location

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.moyeoyo.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL

class CurrentLocationFragment : Fragment(), OnMapReadyCallback, GoogleMap.OnCameraIdleListener {

    companion object {
        const val RESULT_KEY = "current_location_result"
        const val EXTRA_LOCATION_DATA = "extra_location_data"
        const val EXTRA_IS_HOME = "extra_is_home"
    }

    private lateinit var googleMap: GoogleMap
    private lateinit var receivedLatLng: LatLng
    private var isHomeLocation: Boolean = true

    private lateinit var textLocationName: TextView
    private lateinit var textAddress: TextView
    private lateinit var btnConfirm: Button
    private lateinit var btnBack: ImageView

    private var apiKey: String = ""

    private var confirmedLocationMap: Map<String, Any>? = null


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.activity_confirm_location, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        apiKey = getMapsApiKey()

        // Navigation arguments에서 데이터 가져오기
        val lat = arguments?.getFloat("lat")?.toDouble() ?: run {
            finishWithToast("위치 정보가 없습니다.")
            return
        }
        val lng = arguments?.getFloat("lng")?.toDouble() ?: run {
            finishWithToast("위치 정보가 없습니다.")
            return
        }
        receivedLatLng = LatLng(lat, lng)
        isHomeLocation = arguments?.getBoolean("isHome", true) ?: true

        textLocationName = view.findViewById(R.id.text_location_name)
        textAddress = view.findViewById(R.id.text_address)
        btnConfirm = view.findViewById(R.id.btn_confirm)
        btnBack = view.findViewById(R.id.back_button)

        val mapFragment = childFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        btnConfirm.setOnClickListener { returnConfirmedLocation() }
        btnBack.setOnClickListener { 
            findNavController().popBackStack()
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isMapToolbarEnabled = false
        googleMap.setOnCameraIdleListener(this)

        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(receivedLatLng, 17f))
        // Activity 버전과 동일: onCameraIdle이 자동으로 호출되어 초기 위치 정보 로드
        // 하지만 안전을 위해 초기 위치 정보도 즉시 로드
        fetchLocationDetails(receivedLatLng)
    }

    override fun onCameraIdle() {
        val centerLatLng = googleMap.cameraPosition.target
        fetchLocationDetails(centerLatLng)
    }


    // ================================
    // 🚀 3단계 통합: TextSearch → PlaceDetails → Geocoding
    // ================================
    private fun fetchLocationDetails(latLng: LatLng) = viewLifecycleOwner.lifecycleScope.launch {
        textLocationName.text = "장소명 로딩 중..."
        textAddress.text = "주소 확인 중..."

        // Reverse Geocode → place_id → Place Details
        val (placeDetailsName, detailsStatus) = fetchPlaceDetailsName(latLng)

        // Geocoding API → formatted address + fallbackName
        val (addressLine, fallbackName, geoStatus) = fetchAddressDetails_Geocoding(latLng)

        // ======================
        // 🧠 최종 장소명 결정
        // ======================
        val finalName = when {
            placeDetailsName.isNotBlank() -> placeDetailsName
            fallbackName.isNotBlank() -> fallbackName
            else -> addressLine
        }

        textLocationName.text = finalName
        textAddress.text = addressLine

        // Fragment Result의 Bundle은 Serializable만 지원하므로 lat/lng를 분리해서 저장
        // (Activity 버전에서는 Intent에 Bundle을 넣을 수 있어 Parcelable도 가능했음)
        confirmedLocationMap = mapOf(
            "name" to finalName,
            "address" to addressLine,
            "lat" to latLng.latitude,
            "lng" to latLng.longitude
        )
    }

    // ================================
    // Reverse Geocoding → Place Details API
    // ================================
    private suspend fun fetchPlaceDetailsName(latLng: LatLng): Pair<String, String> =
        withContext(Dispatchers.IO) {

            if (apiKey.isBlank()) return@withContext Pair("", "API_KEY_MISSING")

            try {
                // 2-1) Reverse Geocoding으로 place_id 가져오기
                val radius = 2000  // 🔥 검색 반경 확대 (200m → 2000m)
                val geoUrl =
                    "https://maps.googleapis.com/maps/api/geocode/json" +
                            "?latlng=${latLng.latitude},${latLng.longitude}" +
                            "&locationbias=circle:$radius@${latLng.latitude},${latLng.longitude}" +
                            "&language=ko" +
                            "&key=$apiKey"

                val geoResp = JSONObject(httpGet(geoUrl))
                val geoStatus = geoResp.getString("status")
                if (geoStatus != "OK") return@withContext Pair("", geoStatus)

                val placeId =
                    geoResp.getJSONArray("results").getJSONObject(0).getString("place_id")

                // 2-2) Place Details API 호출
                val detailsUrl =
                    "https://maps.googleapis.com/maps/api/place/details/json" +
                            "?place_id=$placeId" +
                            "&fields=name" +
                            "&language=ko" +
                            "&key=$apiKey"

                val detailsResp = JSONObject(httpGet(detailsUrl))
                val detailsStatus = detailsResp.getString("status")
                if (detailsStatus != "OK") return@withContext Pair("", detailsStatus)

                val name =
                    detailsResp.getJSONObject("result").optString("name", "")

                Pair(name, "OK")

            } catch (e: Exception) {
                Pair("", "EXCEPTION")
            }
        }


    // ================================
    // Geocoding API (Fallback)
    // ================================
    private suspend fun fetchAddressDetails_Geocoding(latLng: LatLng)
            : Triple<String, String, String> =
        withContext(Dispatchers.IO) {

            if (apiKey.isBlank()) {
                return@withContext Triple("주소 변환 실패", "", "API_KEY_MISSING")
            }

            val urlString =
                "https://maps.googleapis.com/maps/api/geocode/json" +
                        "?latlng=${latLng.latitude},${latLng.longitude}" +
                        "&language=ko" +
                        "&key=$apiKey"

            return@withContext try {
                val json = JSONObject(httpGet(urlString))
                val status = json.getString("status")
                if (status != "OK") return@withContext Triple("", "", status)

                val result = json.getJSONArray("results").getJSONObject(0)
                val address = result.getString("formatted_address")

                // fallbackName (행정동, 도로명 등 가장 구체적인 컴포넌트 추출)
                var fallbackName = extractFallbackName(result)

                Triple(address, fallbackName, "OK")

            } catch (e: Exception) {
                Triple("주소 변환 실패", "", "EXCEPTION")
            }
        }


    // Geocoding에서 가장 구체적인 행정단위를 추출
    private fun extractFallbackName(result: JSONObject): String {
        val components = result.getJSONArray("address_components")

        var road = ""
        var sublocal = ""

        for (i in 0 until components.length()) {
            val comp = components.getJSONObject(i)
            val long = comp.getString("long_name")
            val types = comp.getJSONArray("types").toString()

            when {
                "route" in types -> road = long
                "sublocality" in types -> sublocal = long
            }
        }

        return when {
            road.isNotBlank() -> road
            sublocal.isNotBlank() -> sublocal
            else -> ""
        }
    }

    // 공통 GET 요청 함수
    private fun httpGet(urlString: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
    }

    private fun getMapsApiKey(): String {
        return try {
            val info = requireContext().packageManager.getApplicationInfo(
                requireContext().packageName,
                PackageManager.GET_META_DATA
            )
            info.metaData.getString("com.google.android.geo.API_KEY") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun returnConfirmedLocation() {
        // Activity 버전과 동일: null 체크만 수행
        if (confirmedLocationMap == null) {
            finishWithToast("위치 정보가 유효하지 않습니다.")
            return
        }

        Log.d("CurrentLocation", "returnConfirmedLocation: confirmedLocationMap = $confirmedLocationMap")
        Log.d("CurrentLocation", "returnConfirmedLocation: isHomeLocation = $isHomeLocation")

        // Activity 버전과 동일한 방식으로 Bundle 생성
        val result = Bundle().apply {
            putSerializable(EXTRA_LOCATION_DATA, confirmedLocationMap as Serializable)
            putBoolean(EXTRA_IS_HOME, isHomeLocation)
        }
        
        Log.d("CurrentLocation", "returnConfirmedLocation: Setting fragment result with key = $RESULT_KEY")
        
        // 여러 방법으로 Fragment Result 전달 시도
        // 1. Activity의 supportFragmentManager 사용 (가장 확실한 방법)
        requireActivity().supportFragmentManager.setFragmentResult(RESULT_KEY, result)
        Log.d("CurrentLocation", "returnConfirmedLocation: Fragment result set via Activity supportFragmentManager")
        
        // 2. Navigation의 savedStateHandle에도 저장 (onResume에서 확인 가능)
        // previousBackStackEntry에 저장 (LocationInputFragment가 이전 Fragment)
        findNavController().previousBackStackEntry?.savedStateHandle?.set(RESULT_KEY, result)
        Log.d("CurrentLocation", "returnConfirmedLocation: Fragment result set in previousBackStackEntry savedStateHandle")
        
        // 3. currentBackStackEntry에도 저장 (혹시 모를 경우를 대비)
        findNavController().currentBackStackEntry?.savedStateHandle?.set(RESULT_KEY, result)
        Log.d("CurrentLocation", "returnConfirmedLocation: Fragment result set in currentBackStackEntry savedStateHandle")
        
        Log.d("CurrentLocation", "returnConfirmedLocation: Fragment result set via all methods, popping back")
        findNavController().popBackStack()
    }

    private fun finishWithToast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        findNavController().popBackStack()
    }
}

