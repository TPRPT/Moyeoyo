package com.moyeoyo.app.ui.location

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.moyeoyo.app.ui.location.CurrentLocationFragment
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
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.data.model.User
import com.moyeoyo.app.data.repository.GroupRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class LocationInputFragment : Fragment() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    
    @Inject
    lateinit var groupRepository: GroupRepository

    private var selectedLocation: LatLng? = null
    private var selectedLocationType: String? = null // "current", "home", "work", "search"
    private var selectedAddress: String? = null

    private var rootView: View? = null
    private var groupId: String = ""
    private var memberUids: List<String> = emptyList()
    private var inputLocationListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var isLocationLocked = false

    private val args: LocationInputFragmentArgs by navArgs()

    // 위치 권한 요청 런처
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            getCurrentLocation()
        } else {
            Toast.makeText(requireContext(), "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    // Places Autocomplete 런처
    private val autocompleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val place = Autocomplete.getPlaceFromIntent(result.data!!)
            handleSelectedPlace(place, "search")
        }
    }

    // Fragment Result 리스너는 onViewCreated에서 설정

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // ⭐ null을 반환하지 않도록 View를 반환
        val view = inflater.inflate(R.layout.fragment_location_input, container, false)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootView = view

        // Fragment Result 리스너 설정
        // Navigation을 사용할 때는 Activity의 supportFragmentManager를 사용해야 함
        requireActivity().supportFragmentManager.setFragmentResultListener(CurrentLocationFragment.RESULT_KEY, viewLifecycleOwner) { requestKey, bundle ->
            Log.d("LocationInput", "Fragment result received in listener: requestKey=$requestKey, bundle=$bundle")
            handleConfirmedLocation(bundle)
        }
        
        Log.d("LocationInput", "Fragment Result listener registered with key: ${CurrentLocationFragment.RESULT_KEY}")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        groupId = args.groupId

        // ⭐ 처음 입장 시 UI 초기화 (선택된 위치 카드와 저장 버튼 숨김)
        initializeUI()
        
        setupViews()
        loadUserLocations()
        loadGroupInfo()
        startMonitoringInputLocations()
        // ⭐ 처음 입장 시에는 저장된 위치 정보를 표시하지 않음 (사용자가 직접 선택해야 함)
    }
    
    /**
     * UI 초기 상태 설정 (선택된 위치 카드와 저장 버튼 숨김)
     */
    private fun initializeUI() {
        val layoutSelected = rootView?.findViewById<View>(R.id.layout_selected_location)
        val saveButton = rootView?.findViewById<android.widget.Button>(R.id.btn_save_location)
        
        layoutSelected?.visibility = View.GONE
        saveButton?.visibility = View.GONE
        
        // 선택된 위치 정보 초기화
        selectedLocation = null
        selectedLocationType = null
        selectedAddress = null
    }

    override fun onResume() {
        super.onResume()
        Log.d("LocationInput", "onResume: Checking for Fragment Result")
        
        // Fragment가 다시 나타날 때 Fragment Result를 확인
        // 1. currentBackStackEntry의 savedStateHandle 확인
        findNavController().currentBackStackEntry?.savedStateHandle?.get<Bundle>(CurrentLocationFragment.RESULT_KEY)?.let { bundle ->
            Log.d("LocationInput", "Fragment result found in currentBackStackEntry savedStateHandle: $bundle")
            findNavController().currentBackStackEntry?.savedStateHandle?.remove<Bundle>(CurrentLocationFragment.RESULT_KEY)
            handleConfirmedLocation(bundle)
            return@onResume
        }
        
        // 2. previousBackStackEntry의 savedStateHandle 확인
        findNavController().previousBackStackEntry?.savedStateHandle?.get<Bundle>(CurrentLocationFragment.RESULT_KEY)?.let { bundle ->
            Log.d("LocationInput", "Fragment result found in previousBackStackEntry savedStateHandle: $bundle")
            findNavController().previousBackStackEntry?.savedStateHandle?.remove<Bundle>(CurrentLocationFragment.RESULT_KEY)
            handleConfirmedLocation(bundle)
            return@onResume
        }
        
        Log.d("LocationInput", "onResume: No Fragment Result found in savedStateHandle")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        allMembersInputtedDialog?.dismiss()
        allMembersInputtedDialog = null
        hasShownAllInputtedDialog = false
        inputLocationListener?.remove()
        rootView = null
    }

    private fun setupViews() {
        // 뒤로가기 버튼
        rootView?.findViewById<View>(R.id.btn_back)?.setOnClickListener {
            findNavController().popBackStack()
        }

        // 현재 위치 버튼
        rootView?.findViewById<View>(R.id.btn_use_current_location)?.setOnClickListener {
            if (isLocationInputLocked()) {
                Toast.makeText(requireContext(), "중간값 계산이 완료되어 위치를 변경할 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestLocationPermission()
        }

        // 집 버튼
        rootView?.findViewById<View>(R.id.btn_use_home)?.setOnClickListener {
            if (isLocationInputLocked()) {
                Toast.makeText(requireContext(), "중간값 계산이 완료되어 위치를 변경할 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loadHomeLocation()
        }

        // 회사 버튼
        rootView?.findViewById<View>(R.id.btn_use_work)?.setOnClickListener {
            if (isLocationInputLocked()) {
                Toast.makeText(requireContext(), "중간값 계산이 완료되어 위치를 변경할 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loadWorkLocation()
        }

        // 검색창 EditText 클릭 (ProfileSetupFragment처럼 동작)
        rootView?.findViewById<EditText>(R.id.et_search_address)?.apply {
            setOnClickListener {
                if (isLocationInputLocked()) {
                    Toast.makeText(requireContext(), "중간값 계산이 완료되어 위치를 변경할 수 없습니다.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                openPlacesAutocomplete()
            }
            isFocusable = false
            keyListener = null
        }
        
        // 돋보기 아이콘 클릭 (검색창 내부 ImageView)
        // layoutSearchBar의 첫 번째 자식이 ImageView (돋보기)
        rootView?.findViewById<View>(R.id.layoutSearchBar)?.let { searchBarLayout ->
            if (searchBarLayout is android.view.ViewGroup && searchBarLayout.childCount > 0) {
                val searchIcon = searchBarLayout.getChildAt(0)
                if (searchIcon is ImageView) {
                    searchIcon.setOnClickListener {
                        if (isLocationInputLocked()) {
                            Toast.makeText(requireContext(), "중간값 계산이 완료되어 위치를 변경할 수 없습니다.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        openPlacesAutocomplete()
                    }
                }
            }
        }
        
        // 검색창 전체 레이아웃 클릭 (기존 동작 유지)
        rootView?.findViewById<View>(R.id.layoutSearchBar)?.setOnClickListener {
            if (isLocationInputLocked()) {
                Toast.makeText(requireContext(), "중간값 계산이 완료되어 위치를 변경할 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openPlacesAutocomplete()
        }

        // 위치 저장 버튼
        rootView?.findViewById<View>(R.id.btn_save_location)?.setOnClickListener {
            if (isLocationInputLocked()) {
                Toast.makeText(requireContext(), "중간값 계산이 완료되어 위치를 변경할 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedLocation != null) {
                saveMyLocationToFirestore()
            } else {
                Toast.makeText(requireContext(), "위치를 선택해주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        // 중간값 계산하기 버튼
        val goToMidpoint = rootView?.findViewById<View>(R.id.btn_go_to_midpoint)
        goToMidpoint?.setOnClickListener {
            if (goToMidpoint.isEnabled) {
                // ⭐ 중간값 계산하기 버튼을 누를 때 PLACE_RANKING 상태로 변경 (알림 전송을 위해)
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val currentGroup = groupRepository.getGroupDetail(groupId)
                        // ⭐ LOCATION_INPUT_REQUIRED 또는 LOCATION_DONE 상태일 때 PLACE_RANKING으로 변경
                        if (currentGroup?.status == "LOCATION_INPUT_REQUIRED" || currentGroup?.status == "LOCATION_DONE") {
                            val success = groupRepository.updateGroupStatus(groupId, "PLACE_RANKING")
                            if (success) {
                                Log.d("LocationInput", "✅ 그룹 상태 변경: ${currentGroup.status} → PLACE_RANKING (중간값 계산하기 버튼 클릭)")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("LocationInput", "그룹 상태 변경 중 오류: ${e.message}")
                    }
                }
                
                val action = LocationInputFragmentDirections.actionLocationInputFragmentToMidpointFragment(
                    groupId = groupId
                )
                findNavController().navigate(action)
            }
        }
    }

    private fun requestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
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
        // ⭐ 권한 체크 (requestLocationPermission에서 이미 체크했지만 안전을 위해 다시 확인)
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(requireContext(), "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val cts = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        val latLng = LatLng(location.latitude, location.longitude)
                        Log.d("LocationInput", "현재 위치 가져오기 성공: lat=${latLng.latitude}, lng=${latLng.longitude}")

                        // CurrentLocationFragment로 Navigation
                        findNavController().navigate(
                            R.id.currentLocationFragment,
                            Bundle().apply {
                                putFloat("lat", latLng.latitude.toFloat())
                                putFloat("lng", latLng.longitude.toFloat())
                                putBoolean("isHome", false) // home/work 여부는 여기선 의미 없음
                            }
                        )
                    } else {
                        Log.w("LocationInput", "위치가 null입니다. 위치 서비스를 확인해주세요.")
                        Toast.makeText(requireContext(), "위치를 가져올 수 없습니다. GPS를 켜고 잠시 후 다시 시도해주세요.", Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("LocationInput", "위치 가져오기 실패: ${e.message}", e)
                    val errorMessage = when {
                        e.message?.contains("Settings", ignoreCase = true) == true -> 
                            "위치 서비스를 활성화해주세요."
                        e.message?.contains("permission", ignoreCase = true) == true -> 
                            "위치 권한이 필요합니다."
                        else -> "위치를 가져올 수 없습니다. GPS를 켜고 잠시 후 다시 시도해주세요."
                    }
                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
                }
        } catch (e: SecurityException) {
            Log.e("LocationInput", "위치 권한 오류: ${e.message}", e)
            Toast.makeText(requireContext(), "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("LocationInput", "위치 가져오기 중 예외 발생: ${e.message}", e)
            Toast.makeText(requireContext(), "위치를 가져오는 중 오류가 발생했습니다: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleConfirmedLocation(bundle: Bundle) {
        Log.d("LocationInput", "handleConfirmedLocation called with bundle: $bundle")
        Log.d("LocationInput", "handleConfirmedLocation: rootView = $rootView")
        
        if (rootView == null) {
            Log.e("LocationInput", "handleConfirmedLocation: rootView is null, cannot update UI")
            // rootView가 null이면 나중에 다시 시도하기 위해 savedStateHandle에 저장
            findNavController().currentBackStackEntry?.savedStateHandle?.set(CurrentLocationFragment.RESULT_KEY, bundle)
            return
        }
        
        @Suppress("UNCHECKED_CAST")
        val locationMap = bundle
            .getSerializable(CurrentLocationFragment.EXTRA_LOCATION_DATA) as? Map<String, Any>

        Log.d("LocationInput", "handleConfirmedLocation: locationMap = $locationMap")

        // lat/lng를 분리해서 받아서 LatLng 객체 생성
        val lat = (locationMap?.get("lat") as? Number)?.toDouble()
        val lng = (locationMap?.get("lng") as? Number)?.toDouble()
        val address = locationMap?.get("address") as? String ?: ""
        val name = locationMap?.get("name") as? String ?: ""

        Log.d("LocationInput", "handleConfirmedLocation: lat=$lat, lng=$lng, address=$address, name=$name")

        if (lat != null && lng != null) {
            selectedLocation = LatLng(lat, lng)
            // name을 우선으로 사용, 없으면 address 사용
            selectedAddress = name.ifBlank { address }.ifBlank { "주소 정보 없음" }
            selectedLocationType = "current" // 현재 위치로 설정

            Log.d("LocationInput", "handleConfirmedLocation: selectedLocation=$selectedLocation, selectedAddress=$selectedAddress, selectedLocationType=$selectedLocationType")
            
            updateSelectedLocationUI()
            
            Log.d("LocationInput", "handleConfirmedLocation: UI updated, showing toast")
            Toast.makeText(requireContext(), "위치가 선택되었습니다.", Toast.LENGTH_SHORT).show()
        } else {
            Log.e("LocationInput", "handleConfirmedLocation: lat or lng is null")
            Toast.makeText(requireContext(), "위치 정보를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
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
                        val address = (home["addressName"] as? String) ?: (home["address"] as? String) ?: ""
                        rootView?.findViewById<android.widget.TextView>(R.id.tv_home_address)?.text = 
                            if (address.isNotEmpty()) address else getString(R.string.home_not_set)
                    } ?: run {
                        rootView?.findViewById<android.widget.TextView>(R.id.tv_home_address)?.text = 
                            getString(R.string.home_not_set)
                    }

                    // 회사 주소 표시
                    user?.workLocation?.let { work ->
                        val address = (work["addressName"] as? String) ?: (work["address"] as? String) ?: ""
                        rootView?.findViewById<android.widget.TextView>(R.id.tv_work_address)?.text = 
                            if (address.isNotEmpty()) address else getString(R.string.work_not_set)
                    } ?: run {
                        rootView?.findViewById<android.widget.TextView>(R.id.tv_work_address)?.text = 
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
                    val latLngValue = home["latLng"]
                    val lat: Double?
                    val lng: Double?
                    
                    when (latLngValue) {
                        is GeoPoint -> {
                            lat = latLngValue.latitude
                            lng = latLngValue.longitude
                        }
                        is List<*> -> {
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
                            selectedAddress = (home["addressName"] as? String)
                                ?: (home["address"] as? String) ?: ""

                            updateSelectedLocationUI()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "집 위치 정보가 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "집 주소가 설정되지 않았습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationInput", "집 위치 로드 실패: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "집 위치를 불러올 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    val latLngValue = work["latLng"]
                    val lat: Double?
                    val lng: Double?
                    
                    when (latLngValue) {
                        is GeoPoint -> {
                            lat = latLngValue.latitude
                            lng = latLngValue.longitude
                        }
                        is List<*> -> {
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
                            selectedAddress = (work["addressName"] as? String)
                                ?: (work["address"] as? String) ?: ""

                            updateSelectedLocationUI()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "회사 위치 정보가 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "회사 주소가 설정되지 않았습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationInput", "회사 위치 로드 실패: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "회사 위치를 불러올 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openPlacesAutocomplete() {
        try {
            val fields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS)
            val intent = Autocomplete.IntentBuilder(AutocompleteActivityMode.OVERLAY, fields)
                .build(requireContext())
            autocompleteLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("LocationInput", "Places Autocomplete 오류: ${e.message}")
            Toast.makeText(requireContext(), "검색 기능을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSelectedPlace(place: Place, type: String) {
        val latLng = place.latLng
        if (latLng != null) {
            // 검색창 EditText에 선택된 장소 이름 표시 (ProfileSetupFragment처럼)
            val addressText = place.name ?: place.address ?: "선택된 장소"
            rootView?.findViewById<EditText>(R.id.et_search_address)?.setText(addressText)
            
            // CurrentLocationFragment로 Navigation
            findNavController().navigate(
                R.id.currentLocationFragment,
                Bundle().apply {
                    putFloat("lat", latLng.latitude.toFloat())
                    putFloat("lng", latLng.longitude.toFloat())
                    putBoolean("isHome", false)
                }
            )
        }
    }

    private fun updateSelectedLocationUI() {
        val layoutSelected = rootView?.findViewById<View>(R.id.layout_selected_location)
        val tvSelectedType = rootView?.findViewById<android.widget.TextView>(R.id.tv_selected_type)
        val tvSelectedAddress = rootView?.findViewById<android.widget.TextView>(R.id.tv_selected_address)
        val saveButton = rootView?.findViewById<android.widget.Button>(R.id.btn_save_location)

        if (selectedLocation != null && selectedAddress != null) {
            // ⭐ 위치와 주소가 모두 있을 때만 카드와 버튼 표시
            layoutSelected?.visibility = View.VISIBLE
            tvSelectedType?.text = when (selectedLocationType) {
                "current" -> "현재 위치"
                "home" -> "집"
                "work" -> "회사"
                "search" -> "검색한 위치"
                else -> "선택된 위치"
            }
            tvSelectedAddress?.text = selectedAddress ?: "주소 정보 없음"
            
            saveButton?.visibility = View.VISIBLE
            saveButton?.isEnabled = true
        } else {
            // ⭐ 위치가 없거나 주소가 없으면 카드와 버튼 숨김
            layoutSelected?.visibility = View.GONE
            saveButton?.visibility = View.GONE
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
                            lat = (data["latitude"] as? Number)?.toDouble()
                            lng = (data["longitude"] as? Number)?.toDouble()
                        }
                    }
                    
                    val isValidLocation = lat != null && lng != null && 
                                         lat != 0.0 && lng != 0.0 &&
                                         lat >= -90.0 && lat <= 90.0 &&
                                         lng >= -180.0 && lng <= 180.0
                    
                    if (isValidLocation) doc.id else null
                }.toSet()

                val allInputted = memberUids.all { it in inputtedUids }
                val missingUids = memberUids.filter { it !in inputtedUids }

                withContext(Dispatchers.Main) {
                    if (allInputted) {
                        isLocationLocked = true
                        
                        // ⭐ 모든 멤버 위치 입력 완료 다이얼로그 표시
                        showAllMembersLocationInputtedDialog()
                        
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val currentGroup = groupRepository.getGroupDetail(groupId)
                                val currentStatus = currentGroup?.status
                                
                                // ⭐ 모든 멤버가 위치를 입력했을 때만 PLACE_RANKING 상태로 변경 (중복 알림 방지)
                                // 이미 PLACE_RANKING 이상의 상태면 변경하지 않음
                                if ((currentStatus == "LOCATION_INPUT_REQUIRED" || currentStatus == "LOCATION_DONE") && 
                                    currentStatus != "PLACE_RANKING" && 
                                    currentStatus != "FINAL_PLACE_VOTE" && 
                                    currentStatus != "FINALIZED") {
                                    val success = groupRepository.updateGroupStatus(groupId, "PLACE_RANKING")
                                    if (success) {
                                        Log.d("LocationInput", "✅ 그룹 상태 변경: $currentStatus → PLACE_RANKING (모든 멤버 위치 입력 완료)")
                                    }
                                } else {
                                    Log.d("LocationInput", "ℹ️ 상태 변경 불필요: 현재 상태=$currentStatus")
                                }
                            } catch (e: Exception) {
                                Log.e("LocationInput", "그룹 상태 변경 중 오류: ${e.message}")
                            }
                        }
                    }
                    updateUIForInputStatus(allInputted, missingUids)
                    updateUIForLockedState(isLocationLocked)
                }
            } catch (e: Exception) {
                Log.e("LocationInput", "입력 상태 확인 실패: ${e.message}")
                withContext(Dispatchers.Main) {
                    updateUIForInputStatus(false, emptyList())
                }
            }
        }
    }

    private var allMembersInputtedDialog: androidx.appcompat.app.AlertDialog? = null
    private var hasShownAllInputtedDialog = false

    /**
     * 모든 멤버 위치 입력 완료 다이얼로그 표시
     */
    private fun showAllMembersLocationInputtedDialog() {
        // ⭐ 다이얼로그 중복 표시 방지
        if (hasShownAllInputtedDialog) {
            return
        }
        
        if (context == null || !isAdded) {
            return
        }
        
        hasShownAllInputtedDialog = true
        allMembersInputtedDialog?.dismiss()
        
        allMembersInputtedDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("모든 그룹원 위치 입력 완료")
            .setMessage("모든 그룹원이 위치를 입력했습니다!\n\n이제 중간 지점을 계산하여 장소를 추천받을 수 있습니다.")
            .setPositiveButton("확인") { _, _ ->
                hasShownAllInputtedDialog = false
            }
            .setCancelable(false)
            .setOnDismissListener {
                hasShownAllInputtedDialog = false
            }
            .create()
        
        allMembersInputtedDialog?.show()
    }

    private fun updateUIForInputStatus(allInputted: Boolean, missingUids: List<String>) {
        val infoTextView = rootView?.findViewById<android.widget.TextView>(R.id.location_input_info)
        val goToMidpointButton = rootView?.findViewById<android.widget.Button>(R.id.btn_go_to_midpoint)

        val missingCount = missingUids.size
        if (allInputted) {
            infoTextView?.text = getString(R.string.location_input_ready)
        } else {
            val message = if (missingCount > 0) {
                "⏳ 아직 ${missingCount}명의 그룹원이 위치를 입력하지 않았습니다. 모든 멤버가 위치를 입력하면 중간 지점을 계산할 수 있습니다."
            } else {
                "⏳ 아직 그룹원이 위치를 입력하지 않았습니다"
            }
            infoTextView?.text = message
        }

        goToMidpointButton?.visibility = View.VISIBLE
        goToMidpointButton?.isEnabled = allInputted
        goToMidpointButton?.alpha = if (allInputted) 1f else 0.5f
    }

    private fun saveMyLocationToFirestore() {
        val currentUser = auth.currentUser ?: return
        val location = selectedLocation ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputLocation = InputLocation(
                    uid = currentUser.uid,
                    latLng = LatLngData(location.latitude, location.longitude),
                    transportMode = TransportMode.TRANSIT,
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
                    Toast.makeText(requireContext(), "위치가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("LocationInput", "위치 저장 실패: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "위치 저장에 실패했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun isLocationInputLocked(): Boolean {
        return isLocationLocked
    }

    private fun updateUIForLockedState(isLocked: Boolean) {
        val currentLocationBtn = rootView?.findViewById<View>(R.id.btn_use_current_location)
        val homeBtn = rootView?.findViewById<View>(R.id.btn_use_home)
        val workBtn = rootView?.findViewById<View>(R.id.btn_use_work)
        val searchBar = rootView?.findViewById<View>(R.id.layoutSearchBar)
        val saveBtn = rootView?.findViewById<View>(R.id.btn_save_location)
        val infoTextView = rootView?.findViewById<android.widget.TextView>(R.id.location_input_info)
        
        if (isLocked) {
            currentLocationBtn?.isEnabled = false
            currentLocationBtn?.alpha = 0.5f
            homeBtn?.isEnabled = false
            homeBtn?.alpha = 0.5f
            workBtn?.isEnabled = false
            workBtn?.alpha = 0.5f
            searchBar?.isEnabled = false
            searchBar?.alpha = 0.5f
            saveBtn?.isEnabled = false
            saveBtn?.alpha = 0.5f
            
            infoTextView?.text = "중간값 계산이 완료되어 위치를 변경할 수 없습니다."
        } else {
            currentLocationBtn?.isEnabled = true
            currentLocationBtn?.alpha = 1f
            homeBtn?.isEnabled = true
            homeBtn?.alpha = 1f
            workBtn?.isEnabled = true
            workBtn?.alpha = 1f
            searchBar?.isEnabled = true
            searchBar?.alpha = 1f
            saveBtn?.isEnabled = true
            saveBtn?.alpha = 1f
        }
    }
    
    /**
     * Firestore에서 저장된 위치 정보를 불러와서 UI에 표시
     */
    private fun loadSavedLocation() {
        val currentUser = auth.currentUser ?: return
        if (groupId.isEmpty() || rootView == null) return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val locationDoc = firestore.collection("groups")
                    .document(groupId)
                    .collection("inputLocations")
                    .document(currentUser.uid)
                    .get()
                    .await()
                
                if (locationDoc.exists()) {
                    val data = locationDoc.data ?: return@launch
                    
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
                            lat = (data["latitude"] as? Number)?.toDouble()
                            lng = (data["longitude"] as? Number)?.toDouble()
                        }
                    }
                    
                    if (lat != null && lng != null) {
                        val label = data["label"] as? String ?: ""
                        
                        withContext(Dispatchers.Main) {
                            selectedLocation = LatLng(lat, lng)
                            selectedAddress = label.ifBlank { "저장된 위치" }
                            selectedLocationType = when {
                                label.contains("집") || label.contains("home", ignoreCase = true) -> "home"
                                label.contains("회사") || label.contains("work", ignoreCase = true) -> "work"
                                label.contains("현재 위치") || label.contains("current", ignoreCase = true) -> "current"
                                else -> "search"
                            }
                            
                            updateSelectedLocationUI()
                            Log.d("LocationInput", "✅ 저장된 위치 정보 복원: $selectedAddress")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LocationInput", "저장된 위치 정보 로드 실패: ${e.message}")
            }
        }
    }
}

