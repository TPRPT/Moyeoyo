package com.moyeoyo.app.map

// MapActivity: 위치 입력/검색 화면의 컨테이너 액티비티
// - 위치 권한 요청 → 현재 위치 획득 트리거
// - 검색 UI(SearchBar) 이벤트를 ViewModel에 연결// - 선택 완료 시 중간지점 화면으로 이동

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.moyeoyo.app.databinding.ActivityLocationInputBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MapActivity : AppCompatActivity() {

    // Activity 레이아웃 바인딩 (activity_location_input.xml)
    private lateinit var binding: ActivityLocationInputBinding

    // ViewModel
    private val viewModel: MapViewModel by viewModels()

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

        intent.getStringExtra("groupId")?.let { viewModel.setGroupId(it) }

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

        // 확정 버튼 → 중간지점 화면으로 이동
        btnConfirm.setOnClickListener {
            val groupId = viewModel.state.value?.groupId
            if (groupId == null) {
                Toast.makeText(this@MapActivity, "그룹 정보가 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.saveSelectedToGroup()
            startActivity(Intent(this@MapActivity, MidpointActivity::class.java).apply {
                putExtra("groupId", groupId)
            })
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
}
