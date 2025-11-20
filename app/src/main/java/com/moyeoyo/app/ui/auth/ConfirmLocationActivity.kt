package com.moyeoyo.app.ui.auth

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

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

        apiKey = getMapsApiKey()

        receivedLatLng = intent.getParcelableExtra(EXTRA_LATLNG) ?: return finishWithToast("위치 정보가 없습니다.")
        isHomeLocation = intent.getBooleanExtra(EXTRA_IS_HOME, true)

        textLocationName = findViewById(R.id.text_location_name)
        textAddress = findViewById(R.id.text_address)
        btnConfirm = findViewById(R.id.btn_confirm)
        btnBack = findViewById(R.id.back_button)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        btnConfirm.setOnClickListener { returnConfirmedLocation() }
        btnBack.setOnClickListener { finish() }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isMapToolbarEnabled = false
        googleMap.setOnCameraIdleListener(this)

        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(receivedLatLng, 17f))
    }

    override fun onCameraIdle() {
        val centerLatLng = googleMap.cameraPosition.target
        fetchLocationDetails(centerLatLng)
    }


    // ================================
    // 🚀 3단계 통합: TextSearch → PlaceDetails → Geocoding
    // ================================
    private fun fetchLocationDetails(latLng: LatLng) = lifecycleScope.launch {
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

        confirmedLocationMap = mapOf(
            "name" to finalName,
            "address" to addressLine,
            "latLng" to latLng
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
            val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            info.metaData.getString("com.google.android.geo.API_KEY") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun returnConfirmedLocation() {
        if (confirmedLocationMap == null) {
            finishWithToast("위치 정보가 유효하지 않습니다.")
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

    private fun finishWithToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
