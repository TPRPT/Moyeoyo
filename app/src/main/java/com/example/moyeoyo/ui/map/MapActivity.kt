package com.example.moyeoyo.ui.map

// MapActivity: 위치 입력/검색 화면의 컨테이너 액티비티
// - 위치 권한 요청 → 현재 위치 획득 트리거
// - 검색 UI(SearchBar) 이벤트를 ViewModel에 연결
// - 선택 완료 시 중간지점 화면으로 이동

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
        val granted = res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
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
    // - 검색어 변경 → 자동완성 요청
    // - 추천 클릭 → 좌표 조회 및 선택 반영
    private fun setupUI() = with(binding) {
        // 뒤로가기
        btnBack.setOnClickListener { finish() }

        // 현재 위치 사용
        btnCurrent.setOnClickListener { requestLocationIfNeeded() }

        // 🔴 중요: viewSearchBar는 SearchBar(커스텀뷰)여야 함 (아래 XML 지시 참고)
        val sb: SearchBar = viewSearchBar

        // 검색 콜백 연결
        sb.onQueryChange = { q ->
            if (q.length >= 2) viewModel.searchPlaces(q)
        }
        sb.onSuggestionClick = { s ->
            viewModel.pickSuggestion(s)
        }

        // 확정 버튼 → 중간지점 화면으로 이동
        btnConfirm.setOnClickListener {
            startActivity(Intent(this@MapActivity, MidpointActivity::class.java))
        }
    }

    /** LiveData 관찰 → UI 반영 */
    // - 선택 라벨/추천 목록/확정 버튼 상태를 상태값에 맞게 갱신
    private fun observe() = with(binding) {
        viewModel.state.observe(this@MapActivity) { s ->
            // 선택 라벨 반영
            viewSearchBar.setSelectedLabel(s.selected?.label)
            // 자동완성 반영
            viewSearchBar.submitSuggestions(s.suggestions)
            // 버튼 활성화
            btnConfirm.isEnabled = s.selected != null
        }
    }

    /** 위치 권한 체크 & 요청 */
    // - 이미 권한이 있다면 바로 현재 위치를 조회, 없으면 요청
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
}