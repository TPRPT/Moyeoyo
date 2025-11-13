package com.moyeoyo.app.ui.group

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.moyeoyo.app.R
import com.moyeoyo.app.model.Place

class RecommendedPlaceFragment : Fragment(R.layout.fragment_recommended_place) {

    private lateinit var categoryButtons: List<TextView>
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PlaceAdapter

    private var places = mutableListOf<Place>()
    private var currentCategory = "전체"
    private var currentTransport = "대중교통"   // 기본값


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 🔙 뒤로가기
        view.findViewById<ImageView>(R.id.btnBack)?.setOnClickListener {
            findNavController().navigateUp()
        }

        // 필터 버튼
        val btnFilter = view.findViewById<LinearLayout>(R.id.btnFilter)
        btnFilter?.setOnClickListener {
            showFilterBottomSheet()
        }

        // 카테고리 버튼
        categoryButtons = listOf(
            view.findViewById(R.id.btnAll),
            view.findViewById(R.id.btnCafe),
            view.findViewById(R.id.btnRestaurant),
            view.findViewById(R.id.btnPub),
            view.findViewById(R.id.btnDessert)
        )

        categoryButtons.forEach { btn ->
            btn.setOnClickListener { selectCategory(btn.text.toString()) }
        }

        // RecyclerView 설정
        recyclerView = view.findViewById(R.id.rvPlaceList)
        adapter = PlaceAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        loadDummyData()
        updatePlaceList()
    }


    // ------------------------------------------------------
    // ⭐ 더미 데이터 로드 (이동수단 시간 추가됨)
    // ------------------------------------------------------
    private fun loadDummyData() {
        places = mutableListOf(
            Place(
                "스타벅스 역삼역점", "카페", 4.5, 2.3,
                walkingTime = "도보 8분",
                transitTime = "대중교통 6분",
                drivingTime = "자동차 3분",
                likeCount = 0,
                memberCount = 8
            ),
            Place(
                "본죽 강남점", "식당", 4.3, 2.1,
                walkingTime = "도보 7분",
                transitTime = "대중교통 5분",
                drivingTime = "자동차 2분",
                likeCount = 0,
                memberCount = 8
            ),
            Place(
                "투썸플레이스 선릉점", "카페", 4.8, 2.8,
                walkingTime = "도보 10분",
                transitTime = "대중교통 7분",
                drivingTime = "자동차 4분",
                likeCount = 0,
                memberCount = 8
            ),
            Place(
                "김가네 김밥", "식당", 4.0, 1.9,
                walkingTime = "도보 6분",
                transitTime = "대중교통 4분",
                drivingTime = "자동차 2분",
                likeCount = 0,
                memberCount = 8
            )
        )
    }


    // ------------------------------------------------------
    // ⭐ 카테고리 선택
    // ------------------------------------------------------
    private fun selectCategory(category: String) {
        currentCategory = category

        // 버튼 스타일 변경
        categoryButtons.forEach {
            if (it.text == category) {
                it.background = requireContext().getDrawable(R.drawable.bg_header_blue)
                it.setTextColor(requireContext().getColor(R.color.white))
            } else {
                it.background = requireContext().getDrawable(R.drawable.bg_light_outline_button)
                it.setTextColor(requireContext().getColor(R.color.black))
            }
        }

        updatePlaceList()
    }


    // ------------------------------------------------------
    // ⭐ 이동수단 필터 + 거리/평점 필터 적용된 리스트 갱신
    // ------------------------------------------------------
    private fun updatePlaceList() {
        val filteredByCategory = if (currentCategory == "전체") places
        else places.filter { it.category == currentCategory }

        // 선택된 이동수단에 맞게 시간 변경
        val updatedTransportList = filteredByCategory.map { place ->
            val time = when (currentTransport) {
                "도보" -> place.walkingTime
                "자동차" -> place.drivingTime
                else -> place.transitTime // 기본값 대중교통
            }

            place.copy(
                // PlaceAdapter 에서 항상 walkingTime 을 표시하고 있으므로
                // 여기에 표시용으로 넣어줌
                walkingTime = time
            )
        }

        adapter.submitList(updatedTransportList)
    }


    // ------------------------------------------------------
    // ⭐ 필터 바텀시트
    // ------------------------------------------------------
    private fun showFilterBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottomsheet_filter, null)

        val seekBarDistance = sheetView.findViewById<SeekBar>(R.id.seekBarDistance)
        val seekBarRating = sheetView.findViewById<SeekBar>(R.id.seekBarRating)
        val tvMaxDistance = sheetView.findViewById<TextView>(R.id.tvMaxDistanceValue)
        val tvMinRating = sheetView.findViewById<TextView>(R.id.tvMinRatingValue)
        val btnClose = sheetView.findViewById<ImageView>(R.id.btnCloseFilter)
        val btnApply = sheetView.findViewById<Button>(R.id.btnApplyFilter)

        // ⭐ 이동수단 버튼
        val btnWalk = sheetView.findViewById<TextView>(R.id.btnWalk)
        val btnTransit = sheetView.findViewById<TextView>(R.id.btnTransit)
        val btnCar = sheetView.findViewById<TextView>(R.id.btnCar)

        // 선택 UI 업데이트 함수
        fun updateTransportSelection(selected: String) {
            currentTransport = selected

            val buttons = listOf(btnWalk, btnTransit, btnCar)
            buttons.forEach { btn ->
                if (btn.text == selected) {
                    btn.setBackgroundResource(R.drawable.bg_transport_selected)
                    btn.setTextColor(requireContext().getColor(R.color.black))
                } else {
                    btn.setBackgroundResource(R.drawable.bg_transport_unselected)
                    btn.setTextColor(requireContext().getColor(R.color.black))
                }
            }
        }

        // 버튼 클릭 리스너
        btnWalk.setOnClickListener { updateTransportSelection("도보") }
        btnTransit.setOnClickListener { updateTransportSelection("대중교통") }
        btnCar.setOnClickListener { updateTransportSelection("자동차") }

        updateTransportSelection(currentTransport)


        // 거리 슬라이더
        seekBarDistance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val distanceValue = progress / 10.0
                tvMaxDistance.text = String.format("%.1fkm", distanceValue)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 평점 슬라이더
        seekBarRating.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratingValue = progress / 10.0
                tvMinRating.text = String.format("%.1f점 이상", ratingValue)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnClose.setOnClickListener { dialog.dismiss() }

        btnApply.setOnClickListener {

            val maxDistance = seekBarDistance.progress / 10.0
            val minRating = seekBarRating.progress / 10.0

            places = places.filter {
                it.distanceKm <= maxDistance && it.rating >= minRating
            }.toMutableList()

            updatePlaceList()
            dialog.dismiss()
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }
}
