package com.example.moyeoyo.ui

import android.os.Bundle
import android.util.Log // ⬅️ [추가] 로그 출력을 위한 import
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope // ⬅️ [추가] 코루틴 사용을 위한 import
import com.example.moyeoyo.R
import com.example.moyeoyo.data.model.InputLocation // ⬅️ [추가]
import com.example.moyeoyo.data.model.LatLngData // ⬅️ [추가]
import com.example.moyeoyo.data.model.TransportMode // ⬅️ [추가]
import com.example.moyeoyo.data.repository.MapRepository // ⬅️ [추가]
import kotlinx.coroutines.launch // ⬅️ [추가]

class MainActivity : AppCompatActivity() {

    // ⬇️⬇️ [추가] MapRepository 변수 선언 ⬇️⬇️
    private lateinit var mapRepository: MapRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // ⬇️⬇️ [추가] MapRepository를 초기화하고 테스트를 실행하는 코드 ⬇️⬇️

        // 1. MapRepository 객체 생성
        mapRepository = MapRepository(
            context = this
        )

        // 2. 테스트 함수 실행
        runMapLogicTests()
    }

    // ⬇️⬇️ [추가] MapTestActivity에 있던 테스트 함수들을 그대로 가져옴 ⬇️⬇️

    private fun runMapLogicTests() {
        // Coroutine을 사용하여 비동기 함수들을 호출합니다.
        lifecycleScope.launch {
            Log.d("MainActivity-Test", "======== 지도 로직 테스트 시작 ========")

            // 1. 가중중심 계산 테스트 (computeWeightedCenter)
            testWeightedCenter()

            // 2. Distance Matrix API 테스트 (fetchDistanceMatrix)
            testDistanceMatrix()

            // 3. 장소 검색 자동완성 테스트 (findSuggestions)
            testFindSuggestions("강남역")

            Log.d("MainActivity-Test", "======== 지도 로직 테스트 종료 ========")
        }
    }

    private fun testWeightedCenter() {
        Log.d("MainActivity-Test", "--- 1. 가중중심 계산 테스트 ---")
        val members = listOf(
            InputLocation("user1", LatLngData(37.4979, 127.0276), TransportMode.DRIVE),  // 강남역 (운전)
            InputLocation("user2", LatLngData(37.5172, 127.0473), TransportMode.SUBWAY), // 삼성역 (지하철)
            InputLocation("user3", LatLngData(37.5547, 126.9706), TransportMode.WALK)    // 서울역 (도보)
        )
        val center = mapRepository.computeWeightedCenter(members)
        Log.d("MainActivity-Test", "계산된 중간지점: $center") // 결과 확인
    }

    private suspend fun testDistanceMatrix() {
        Log.d("MainActivity-Test", "--- 2. Distance Matrix API 테스트 ---")
        val origins = listOf(
            "user1" to LatLngData(37.4979, 127.0276), // 강남역
            "user2" to LatLngData(37.5172, 127.0473)  // 삼성역
        )
        val destination = LatLngData(37.5093, 127.0382) // 계산된 중간지점(가상)

        try {
            val results = mapRepository.fetchDistanceMatrix(origins, destination, "transit")
            Log.d("MainActivity-Test", "거리 계산 결과:")
            results.forEach {
                Log.d("MainActivity-Test", "  - 유저: ${it.uid}, 소요시간: ${it.durationSeconds}초, 거리: ${it.distanceMeters}미터")
            }
        } catch (e: Exception) {
            Log.e("MainActivity-Test", "Distance Matrix 오류: ", e)
        }
    }

    private suspend fun testFindSuggestions(query: String) {
        Log.d("MainActivity-Test", "--- 3. 장소 검색 테스트 ---")
        try {
            val suggestions = mapRepository.findSuggestions(query)
            Log.d("MainActivity-Test", "'$query' 검색 결과:")
            suggestions.take(3).forEach { // 상위 3개만 출력
                Log.d("MainActivity-Test", "  - ${it.label} (${it.address})")
            }
        } catch (e: Exception) {
            Log.e("MainActivity-Test", "장소 검색 오류: ", e)
        }
    }
}
