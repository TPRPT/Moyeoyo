package com.moyeoyo.app.ui.location

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.User
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.map.MidpointActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

class LocationInputActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    private var selectedLocation: LatLng? = null
    private var selectedLocationType: String? = null // "current", "home", "work", "search"
    private var selectedAddress: String? = null

    private lateinit var rootView: View
    private var groupId: String = ""
    private var memberUids: List<String> = emptyList()
    private var inputLocationListener: com.google.firebase.firestore.ListenerRegistration? = null

    // 위치 권한 요청 런처
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            getCurrentLocation()
        } else {
            Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }


    // Places Autocomplete 런처
    private val autocompleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val place = Autocomplete.getPlaceFromIntent(result.data!!)
            handleSelectedPlace(place, "search")
        }
    }

    // ConfirmLocationActivity는 Places Autocomplete로 대체되므로 제거

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_input)

        rootView = findViewById(android.R.id.content)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        groupId = intent.getStringExtra("groupId") ?: ""

        setupViews(groupId)
        loadUserLocations()
        loadGroupInfo()
        startMonitoringInputLocations()
    }

    override fun onDestroy() {
        super.onDestroy()
        inputLocationListener?.remove()
    }

    private fun setupViews(groupId: String) {
        // 뒤로가기 버튼
        findViewById<View>(R.id.btn_back).setOnClickListener {
            finish()
        }

        // 현재 위치 버튼
        findViewById<View>(R.id.btn_use_current_location).setOnClickListener {
            requestLocationPermission()
        }

        // 집 버튼
        findViewById<View>(R.id.btn_use_home).setOnClickListener {
            loadHomeLocation()
        }

        // 회사 버튼
        findViewById<View>(R.id.btn_use_work).setOnClickListener {
            loadWorkLocation()
        }

        // 검색창 클릭
        findViewById<View>(R.id.layoutSearchBar).setOnClickListener {
            openPlacesAutocomplete()
        }

        // 위치 저장 버튼
        findViewById<View>(R.id.btn_save_location).setOnClickListener {
            if (selectedLocation != null) {
                saveMyLocationToFirestore()
            } else {
                Toast.makeText(this, "위치를 선택해주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        // 중간값 계산하기 버튼
        val goToMidpoint = findViewById<View>(R.id.btn_go_to_midpoint)
        goToMidpoint.setOnClickListener {
            if (!goToMidpoint.isEnabled) return@setOnClickListener
            // MidpointActivity로 이동
            val intent = Intent(this, MidpointActivity::class.java).apply {
                putExtra("groupId", groupId)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun requestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                getCurrentLocation()
            }
            else -> {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun getCurrentLocation() {
        // 권한 체크
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val cts = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        selectedLocation = LatLng(location.latitude, location.longitude)
                        selectedLocationType = "current"
                        getAddressFromLocation(selectedLocation!!) { address ->
                            selectedAddress = address
                            updateSelectedLocationUI()
                        }
                    } else {
                        Toast.makeText(this, "위치를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("LocationInput", "위치 가져오기 실패: ${e.message}")
                    Toast.makeText(this, "위치를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
        } catch (e: SecurityException) {
            Log.e("LocationInput", "위치 권한 오류: ${e.message}")
            Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUserLocations() {
        val currentUser = auth.currentUser ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userDoc = firestore.collection("users").document(currentUser.uid).get().await()
                val user = userDoc.toObject(User::class.java)

                withContext(Dispatchers.Main) {
                    // 집 주소 표시
                    user?.homeLocation?.let { home ->
                        // addressName 또는 address 필드 확인
                        val address = (home["addressName"] as? String) ?: (home["address"] as? String) ?: ""
                        findViewById<android.widget.TextView>(R.id.tv_home_address).text = 
                            if (address.isNotEmpty()) address else getString(R.string.home_not_set)
                    } ?: run {
                        findViewById<android.widget.TextView>(R.id.tv_home_address).text = 
                            getString(R.string.home_not_set)
                    }

                    // 회사 주소 표시
                    user?.workLocation?.let { work ->
                        // addressName 또는 address 필드 확인
                        val address = (work["addressName"] as? String) ?: (work["address"] as? String) ?: ""
                        findViewById<android.widget.TextView>(R.id.tv_work_address).text = 
                            if (address.isNotEmpty()) address else getString(R.string.work_not_set)
                    } ?: run {
                        findViewById<android.widget.TextView>(R.id.tv_work_address).text = 
                            getString(R.string.work_not_set)
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationInput", "사용자 정보 로드 실패: ${e.message}")
            }
        }
    }

    private fun loadHomeLocation() {
        val currentUser = auth.currentUser ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userDoc = firestore.collection("users").document(currentUser.uid).get().await()
                val user = userDoc.toObject(User::class.java)

                user?.homeLocation?.let { home ->
                    // latLng 필드 확인 (GeoPoint 또는 배열 형태)
                    val latLngValue = home["latLng"]
                    val lat: Double?
                    val lng: Double?
                    
                    when (latLngValue) {
                        is GeoPoint -> {
                            lat = latLngValue.latitude
                            lng = latLngValue.longitude
                        }
                        is List<*> -> {
                            // 배열 형태인 경우 [lat, lng]
                            if (latLngValue.size >= 2) {
                                lat = (latLngValue[0] as? Number)?.toDouble()
                                lng = (latLngValue[1] as? Number)?.toDouble()
                            } else {
                                lat = null
                                lng = null
                            }
                        }
                        else -> {
                            lat = null
                            lng = null
                        }
                    }
                    
                    if (lat != null && lng != null) {
                        withContext(Dispatchers.Main) {
                            selectedLocation = LatLng(lat, lng)
                            selectedLocationType = "home"
                            // addressName 또는 address 필드 확인
                            selectedAddress = (home["addressName"] as? String) ?: (home["address"] as? String) ?: ""
                            updateSelectedLocationUI()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@LocationInputActivity, "집 위치 정보가 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LocationInputActivity, "집 주소가 설정되지 않았습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationInput", "집 위치 로드 실패: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LocationInputActivity, "집 위치를 불러올 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadWorkLocation() {
        val currentUser = auth.currentUser ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userDoc = firestore.collection("users").document(currentUser.uid).get().await()
                val user = userDoc.toObject(User::class.java)

                user?.workLocation?.let { work ->
                    // latLng 필드 확인 (GeoPoint 또는 배열 형태)
                    val latLngValue = work["latLng"]
                    val lat: Double?
                    val lng: Double?
                    
                    when (latLngValue) {
                        is GeoPoint -> {
                            lat = latLngValue.latitude
                            lng = latLngValue.longitude
                        }
                        is List<*> -> {
                            // 배열 형태인 경우 [lat, lng]
                            if (latLngValue.size >= 2) {
                                lat = (latLngValue[0] as? Number)?.toDouble()
                                lng = (latLngValue[1] as? Number)?.toDouble()
                            } else {
                                lat = null
                                lng = null
                            }
                        }
                        else -> {
                            lat = null
                            lng = null
                        }
                    }
                    
                    if (lat != null && lng != null) {
                        withContext(Dispatchers.Main) {
                            selectedLocation = LatLng(lat, lng)
                            selectedLocationType = "work"
                            // addressName 또는 address 필드 확인
                            selectedAddress = (work["addressName"] as? String) ?: (work["address"] as? String) ?: ""
                            updateSelectedLocationUI()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@LocationInputActivity, "회사 위치 정보가 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LocationInputActivity, "회사 주소가 설정되지 않았습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationInput", "회사 위치 로드 실패: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LocationInputActivity, "회사 위치를 불러올 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openPlacesAutocomplete() {
        try {
            val fields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS)
            val intent = Autocomplete.IntentBuilder(AutocompleteActivityMode.FULLSCREEN, fields)
                .build(this)
            autocompleteLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("LocationInput", "Places Autocomplete 오류: ${e.message}")
            Toast.makeText(this, "검색 기능을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSelectedPlace(place: Place, type: String) {
        val latLng = place.latLng
        if (latLng != null) {
            selectedLocation = latLng
            selectedLocationType = type
            selectedAddress = place.address ?: ""
            updateSelectedLocationUI()
        }
    }

    private fun updateSelectedLocationUI() {
        val layoutSelected = findViewById<View>(R.id.layout_selected_location)
        val tvSelectedType = findViewById<android.widget.TextView>(R.id.tv_selected_type)
        val tvSelectedAddress = findViewById<android.widget.TextView>(R.id.tv_selected_address)
        val saveButton = findViewById<android.widget.Button>(R.id.btn_save_location)

        if (selectedLocation != null) {
            layoutSelected.visibility = View.VISIBLE
            tvSelectedType.text = when (selectedLocationType) {
                "current" -> "현재 위치"
                "home" -> "집"
                "work" -> "회사"
                "search" -> "검색한 위치"
                else -> "선택된 위치"
            }
            tvSelectedAddress.text = selectedAddress ?: "주소 정보 없음"
            
            // 위치가 선택되었을 때 저장 버튼 표시
            saveButton.visibility = View.VISIBLE
            saveButton.isEnabled = true
        } else {
            layoutSelected.visibility = View.GONE
            // 위치가 선택되지 않았을 때는 저장 버튼 숨김
            saveButton.visibility = View.GONE
        }
    }

    private fun getAddressFromLocation(latLng: LatLng, callback: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val geocoder = Geocoder(this@LocationInputActivity, Locale.getDefault())
                val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                val address = addresses?.firstOrNull()?.getAddressLine(0) ?: "주소 정보 없음"
                withContext(Dispatchers.Main) {
                    callback(address)
                }
            } catch (e: Exception) {
                Log.e("LocationInput", "주소 변환 실패: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback("주소 정보 없음")
                }
            }
        }
    }

    private fun loadGroupInfo() {
        if (groupId.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val groupDoc = firestore.collection("groups").document(groupId).get().await()
                val group = groupDoc.toObject(com.moyeoyo.app.data.model.Group::class.java)
                withContext(Dispatchers.Main) {
                    memberUids = group?.memberUids ?: emptyList()
                    checkAllMembersInputted()
                }
            } catch (e: Exception) {
                Log.e("LocationInput", "그룹 정보 로드 실패: ${e.message}")
            }
        }
    }

    private fun startMonitoringInputLocations() {
        if (groupId.isEmpty()) return
        inputLocationListener = firestore.collection("groups")
            .document(groupId)
            .collection("inputLocations")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("LocationInput", "inputLocations 리스너 오류: ${error.message}")
                    return@addSnapshotListener
                }
                checkAllMembersInputted()
            }
    }

    private fun checkAllMembersInputted() {
        if (groupId.isEmpty() || memberUids.isEmpty()) {
            updateUIForInputStatus(false, emptyList())
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snapshot = firestore.collection("groups")
                    .document(groupId)
                    .collection("inputLocations")
                    .get()
                    .await()

                val inputtedUids = snapshot.documents.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    
                    // 좌표 추출 및 유효성 검증
                    val lat: Double?
                    val lng: Double?
                    
                    when (val latLngValue = data["latLng"]) {
                        is GeoPoint -> {
                            lat = latLngValue.latitude
                            lng = latLngValue.longitude
                        }
                        is Map<*, *> -> {
                            lat = (latLngValue["lat"] as? Number)?.toDouble()
                                ?: (latLngValue["latitude"] as? Number)?.toDouble()
                            lng = (latLngValue["lng"] as? Number)?.toDouble()
                                ?: (latLngValue["longitude"] as? Number)?.toDouble()
                        }
                        else -> {
                            // latLng 필드가 없으면 latitude, longitude 별도 필드 확인
                            lat = (data["latitude"] as? Number)?.toDouble()
                            lng = (data["longitude"] as? Number)?.toDouble()
                        }
                    }
                    
                    // 좌표가 유효한지 확인 (0.0, 0.0은 유효하지 않음, 실제 좌표 범위 확인)
                    val isValidLocation = lat != null && lng != null && 
                                         lat != 0.0 && lng != 0.0 &&
                                         lat >= -90.0 && lat <= 90.0 &&
                                         lng >= -180.0 && lng <= 180.0
                    
                    if (isValidLocation) doc.id else null
                }.toSet()

                val allInputted = memberUids.all { it in inputtedUids }
                val missingUids = memberUids.filter { it !in inputtedUids }

                withContext(Dispatchers.Main) {
                    updateUIForInputStatus(allInputted, missingUids)
                }
            } catch (e: Exception) {
                Log.e("LocationInput", "입력 상태 확인 실패: ${e.message}")
                withContext(Dispatchers.Main) {
                    updateUIForInputStatus(false, emptyList())
                }
            }
        }
    }

    private fun updateUIForInputStatus(allInputted: Boolean, missingUids: List<String>) {
        val infoTextView = findViewById<android.widget.TextView>(R.id.location_input_info)
        val goToMidpointButton = findViewById<android.widget.Button>(R.id.btn_go_to_midpoint)

        val missingCount = missingUids.size
        if (allInputted) {
            infoTextView.text = getString(R.string.location_input_ready)
        } else {
            val message = if (missingCount > 0) {
                "⏳ 아직 ${missingCount}명의 그룹원이 위치를 입력하지 않았습니다. 모든 멤버가 위치를 입력하면 중간 지점을 계산할 수 있습니다."
            } else {
                getString(R.string.location_input_waiting)
            }
            infoTextView.text = message
        }

        goToMidpointButton.visibility = View.VISIBLE
        goToMidpointButton.isEnabled = allInputted
        goToMidpointButton.alpha = if (allInputted) 1f else 0.5f
    }

    private fun saveMyLocationToFirestore() {
        val currentUser = auth.currentUser ?: return
        val location = selectedLocation ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputLocation = InputLocation(
                    uid = currentUser.uid,
                    latLng = LatLngData(location.latitude, location.longitude),
                    transportMode = TransportMode.TRANSIT, // 기본값, 나중에 선택 가능하도록 확장 가능
                    label = when (selectedLocationType) {
                        "current" -> "현재 위치"
                        "home" -> "집"
                        "work" -> "회사"
                        "search" -> selectedAddress
                        else -> selectedAddress
                    }
                )

                val data = mapOf(
                    "latLng" to GeoPoint(location.latitude, location.longitude),
                    "transportMode" to inputLocation.transportMode.name,
                    "label" to (inputLocation.label ?: "")
                )

                firestore.collection("groups")
                    .document(groupId)
                    .collection("inputLocations")
                    .document(currentUser.uid)
                    .set(data)
                    .await()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LocationInputActivity, "위치가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                    // 리스너가 자동으로 UI를 업데이트할 것임
                }
            } catch (e: Exception) {
                Log.e("LocationInput", "위치 저장 실패: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LocationInputActivity, "위치 저장에 실패했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

