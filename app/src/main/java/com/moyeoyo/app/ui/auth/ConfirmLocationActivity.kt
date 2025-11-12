package com.moyeoyo.app.ui.auth

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.material.snackbar.Snackbar
import com.moyeoyo.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class ConfirmLocationActivity : AppCompatActivity(), OnMapReadyCallback, GoogleMap.OnCameraIdleListener {

    companion object {
        const val EXTRA_LATLNG = "extra_latlng"
        const val EXTRA_IS_HOME = "extra_is_home"
        const val EXTRA_LOCATION_DATA = "extra_location_data"
        const val RESULT_CODE_LOCATION_CONFIRMED = 100

        fun newIntent(context: Context, latLng: LatLng, isHome: Boolean): Intent {
            return Intent(context, ConfirmLocationActivity::class.java).apply {
                putExtra(EXTRA_LATLNG, latLng)
                putExtra(EXTRA_IS_HOME, isHome)
            }
        }
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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm_location)

        // API Key 로드
        apiKey = getMapsApiKey()

        // 1. Intent 데이터 수신
        receivedLatLng = intent.getParcelableExtra(EXTRA_LATLNG) ?: return finishWithToast("위치 정보가 없습니다.")
        isHomeLocation = intent.getBooleanExtra(EXTRA_IS_HOME, true)

        // 2. View 초기화
        textLocationName = findViewById(R.id.text_location_name)
        textAddress = findViewById(R.id.text_address)
        btnConfirm = findViewById(R.id.btn_confirm)
        btnBack = findViewById(R.id.back_button)

        // 3. 지도 Fragment 초기화
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // 4. 리스너 설정
        btnConfirm.setOnClickListener {
            returnConfirmedLocation()
        }
        btnBack.setOnClickListener {
            finish()
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isMapToolbarEnabled = false

        googleMap.setOnCameraIdleListener(this)

        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(receivedLatLng, 17f))
    }

    // 지도를 드래그하여 카메라 이동이 멈췄을 때 호출됨
    override fun onCameraIdle() {
        val centerLatLng = googleMap.cameraPosition.target
        fetchLocationDetails(centerLatLng)
    }

    /**
     * Places API(POI Name)와 Geocoding API(Address Line & Fallback Name)를 사용합니다.
     */
    private fun fetchLocationDetails(latLng: LatLng) = lifecycleScope.launch {
        textLocationName.text = "주소 로딩 중..."
        textAddress.text = "잠시만 기다려주세요..."

        // 1. POI Name (가장 정확한 상호명)을 Places API로 가져옴
        val (poiName, poiStatus) = fetchPoiNameFromPlacesApi(latLng)

        // 2. Geocoding API로 주소 라인과 폴백 이름을 동시에 가져옴 (훨씬 안정적)
        val (addressLine, fallbackName, geoStatus) = fetchAddressDetailsFromGeocodingApi(latLng)

        // 최종 이름 결정: POI Name이 유효하면 사용, 아니면 Geocoding API에서 가져온 폴백 이름 사용
        val finalName = if (poiName.isNotBlank() && poiName != "위치 이름 없음" && !poiName.startsWith("위치 이름 없음")) poiName else fallbackName

        // UI 업데이트
        textLocationName.text = finalName
        textAddress.text = addressLine

        // API Status가 OK가 아니면 Snackbar로 표시
        if (poiStatus != "OK" || geoStatus != "OK") {
            val rootLayout = findViewById<View>(android.R.id.content)
            Snackbar.make(rootLayout, "🚨 POI($poiStatus) / GEO($geoStatus) 오류 발생", Snackbar.LENGTH_LONG).show()
        }


        // ProfileSetupActivity에 반환할 데이터 준비
        confirmedLocationMap = mapOf(
            "name" to finalName,
            "address" to addressLine,
            "latLng" to latLng
        )
    }

    /**
     * ⭐ MODIFIED: Places API의 Nearby Search를 사용하여 가장 가까운 POI(상호명/장소명)를 가져옵니다.
     * type=establishment 필터를 제거하여 POI 검색 성공률을 높입니다.
     */
    private suspend fun fetchPoiNameFromPlacesApi(latLng: LatLng): Pair<String, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Pair("위치 이름 없음", "API_KEY_MISSING")
        }

        // Places API Nearby Search (100m 반경 내 가장 prominent한 POI 검색)
        // ⭐ type 필터를 제거하고 Prominence(중요도) 정렬을 사용하여 최적의 이름을 가져옵니다.
        val urlString = "https://maps.googleapis.com/maps/api/place/nearbysearch/json?location=${latLng.latitude},${latLng.longitude}&radius=100&language=ko&key=$apiKey"

        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val jsonResponse = JSONObject(response)

                val status = jsonResponse.getString("status")

                if (status != "OK") {
                    return@withContext Pair("위치 이름 없음", status)
                }

                val results = jsonResponse.getJSONArray("results")
                if (results.length() > 0) {
                    val name = results.getJSONObject(0).getString("name")

                    // POI 이름 유효성 검사 (너무 짧거나 숫자만 있는 경우는 무시)
                    if (name.length > 2 && !name.matches(Regex("^[0-9\\s,-]+$"))) {
                        return@withContext Pair(name, "OK")
                    }
                }
                // POI는 검색되었으나 유효성 검사를 통과하지 못한 경우 (이름이 너무 짧거나 숫자)
                return@withContext Pair("위치 이름 없음", "OK_BUT_GENERIC_POI")

            } else {
                return@withContext Pair("위치 이름 없음", "NETWORK_ERROR: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            return@withContext Pair("위치 이름 없음", "NETWORK_EXCEPTION")
        }
    }

    /**
     * Geocoding API (REST)를 사용하여 상세 주소와 폴백 이름을 가져옵니다. (변경 없음)
     */
    private suspend fun fetchAddressDetailsFromGeocodingApi(latLng: LatLng): Triple<String, String, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Triple("주소 변환 실패", "API_KEY_MISSING", "ERROR")
        }

        // Geocoding API 호출
        val urlString = "https://maps.googleapis.com/maps/api/geocode/json?latlng=${latLng.latitude},${latLng.longitude}&language=ko&key=$apiKey"

        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                val jsonResponse = JSONObject(response)

                val status = jsonResponse.getString("status")

                if (status != "OK" || !jsonResponse.has("results") || jsonResponse.getJSONArray("results").length() == 0) {
                    return@withContext Triple("주소 변환 실패", "위치 이름 없음", status)
                }

                val results = jsonResponse.getJSONArray("results")
                val firstResult = results.getJSONObject(0)

                // 1. 상세 주소 라인 (Formatted Address)
                val addressLine = firstResult.getString("formatted_address")

                // 2. 폴백 이름 (가장 상세한 행정 구역명 또는 도로명) 결정
                var fallbackName = ""

                // address_components를 순회하여 가장 구체적인 이름 (도로, 동, 구)을 찾습니다.
                val components = firstResult.getJSONArray("address_components")
                for (i in 0 until components.length()) {
                    val component = components.getJSONObject(i)
                    val longName = component.getString("long_name")
                    val types = component.getJSONArray("types").toString()

                    // 도로명 또는 상세 주소(route/street_address)를 가장 높은 우선순위로
                    if (types.contains("route") || types.contains("street_address")) {
                        fallbackName = longName
                        break
                    }
                    // 동/구 이름 (sublocality/political)이면서 아직 fallbackName이 설정되지 않았을 경우
                    else if (fallbackName.isBlank() && types.contains("sublocality") && types.contains("political")) {
                        fallbackName = longName
                    }
                }

                // 최종 폴백 이름이 없으면 'formatted_address'에서 시/구 이름을 추출하여 사용
                if (fallbackName.isBlank()) {
                    val addressParts = addressLine.split(" ")
                    fallbackName = if (addressParts.size > 2) addressParts[1] else if (addressParts.isNotEmpty()) addressParts[0] else ""
                }

                // 여전히 빈 경우 기본값 사용
                if (fallbackName.isBlank() || fallbackName == "서울특별시") {
                    fallbackName = if (isHomeLocation) "집 근처" else "직장 근처"
                }

                return@withContext Triple(addressLine, fallbackName, status)

            } else {
                return@withContext Triple("주소 변환 실패", "위치 이름 없음", "NETWORK_ERROR: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            return@withContext Triple("주소 변환 실패", "NETWORK_EXCEPTION", "EXCEPTION")
        }
    }

    /**
     * Manifest에서 API Key를 읽어옵니다. (변경 없음)
     */
    private fun getMapsApiKey(): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            appInfo.metaData.getString("com.google.android.geo.API_KEY") ?: ""
        } catch (e: Exception) {
            Log.e("API_KEY", "Failed to retrieve API Key from Manifest", e)
            ""
        }
    }

    private fun returnConfirmedLocation() {
        if (confirmedLocationMap == null) {
            finishWithToast("위치 정보가 유효하지 않아 설정할 수 없습니다.")
            return
        }

        val resultIntent = Intent().apply {
            val bundle = Bundle().apply {
                putSerializable(EXTRA_LOCATION_DATA, confirmedLocationMap as java.io.Serializable)
                putBoolean(EXTRA_IS_HOME, isHomeLocation)
            }
            putExtras(bundle)
        }

        setResult(RESULT_CODE_LOCATION_CONFIRMED, resultIntent)
        finish()
    }

    private fun finishWithToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}