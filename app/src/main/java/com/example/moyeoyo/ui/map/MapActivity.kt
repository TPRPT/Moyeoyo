package com.example.moyeoyo.ui.map

// MapActivity: 위치 입력/검색 화면의 컨테이너 액티비티
// - 위치 권한 요청 → 현재 위치 획득 트리거
// - 검색 UI(SearchBar) 이벤트를 ViewModel에 연결// - 선택 완료 시 중간지점 화면으로 이동

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.moyeoyo.data.model.InputLocation
import com.example.moyeoyo.data.model.LatLngData
import com.example.moyeoyo.data.repository.MapRepository
import com.example.moyeoyo.databinding.ActivityLocationInputBinding
import com.example.moyeoyo.ui.map.components.SearchBar

class MapActivity : AppCompatActivity() {

    // Activity 레이아웃 바인딩 (activity_location_input.xml)
    private lateinit var binding: ActivityLocationInputBinding

    // ViewModel
    private lateinit var viewModel: MapViewModel

    // 위치 권한 런처
    // - 두 권한 중 하나라도 승인되면 현재 위치를 가져오도록 ViewModel 호출
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        val granted = res.values.any { it } // 권한 중 하나라도 승인되었는지 확인
        if (granted) viewModel.fetchMyLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationInputBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 간단 DI: Repository 주입
        // - 화면 생명주기에 맞춰 ViewModel 생성
        val repo = MapRepository(this)
        viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MapViewModel(repo) as T
            }
        })[MapViewModel::class.java]

        setupUI()
        observe()
        requestLocationIfNeeded()
    }

    /** UI 리스너/콜백 연결 */
    private fun setupUI() = with(binding) {
        // 뒤로가기
        btnBack.setOnClickListener { finish() }

        // 현재 위치 사용
        btnCurrent.setOnClickListener { requestLocationIfNeeded() }

        // 검색 콜백 연결
        viewSearchBar.onQueryChange = { q ->
            if (q.length >= 2) viewModel.searchPlaces(q)
        }
        viewSearchBar.onSuggestionClick = { s ->
            viewModel.pickSuggestion(s)
        }

        // ✅ [신규 추가] 로컬 테스트 버튼
        btnLocalTest.setOnClickListener {
            // 1. 가짜 그룹원 데이터 생성
            val fakeMembers = createFakeMembers()
            Log.d("MapLocalTest", "로컬 테스트 시작. 가짜 멤버: $fakeMembers")

            // 2. ViewModel의 테스트 함수 호출
            viewModel.runLocalTest(fakeMembers)
        }

        // 확정 버튼 → 중간지점 화면으로 이동
        btnConfirm.setOnClickListener {
            // groupId 인텐트로 전달 (Auth/Group 화면에서 넘겨주는 값 기대)
            val groupId = intent.getStringExtra("groupId")
            if (groupId != null) {
                // Firestore에 내 선택 위치 저장 후 이동
                viewModel.saveSelectedToGroup(groupId)
                startActivity(Intent(this@MapActivity, MidpointActivity::class.java).apply {
                    putExtra("groupId", groupId)
                })
            } else {
                startActivity(Intent(this@MapActivity, MidpointActivity::class.java))
            }
        }
    }

    /** LiveData 관찰 → UI 반영 */
    private fun observe() = with(binding) {
        viewModel.state.observe(this@MapActivity) { s ->
            // 선택 라벨 반영
            viewSearchBar.setSelectedLabel(s.selected?.label)
            // 자동완성 반영
            viewSearchBar.submitSuggestions(s.suggestions)
            // 버튼 활성화
            btnConfirm.isEnabled = s.selected != null

            // ✅ [신규 추가] 테스트 결과 확인용 로그
            if (s.weightedCenter != null) {
                Log.d("MapLocalTest", "계산된 중간지점: ${s.weightedCenter}")
            }
            if (s.distanceByMember.isNotEmpty()) {
                Log.d("MapLocalTest", "계산된 멤버별 거리/시간: ${s.distanceByMember}")
            }
        }
    }

    /** 위치 권한 체크 & 요청 */
    private fun requestLocationIfNeeded() {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            viewModel.fetchMyLocation()
        } else {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    /** ✅ [신규 추가] 로컬 테스트를 위한 가짜 그룹원 위치 데이터 생성 */
    private fun createFakeMembers(): List<InputLocation> {
        return listOf(
            // 서울역 (팀원1)
            InputLocation(
                uid = "user1_seoul_station",
                latLng = LatLngData(37.5547, 126.9704),
                label = "팀원1 (서울역)"
            ),
            // 강남역 (팀원2)
            InputLocation(
                uid = "user2_gangnam_station",
                latLng = LatLngData(37.4981, 127.0276),
                label = "팀원2 (강남역)"
            ),
            // 잠실역 (팀원3)
            InputLocation(
                uid = "user3_jamsil_station",
                latLng = LatLngData(37.5133, 127.1001),
                label = "팀원3 (잠실역)"
            )
        )
    }
}
