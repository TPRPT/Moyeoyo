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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 🔙 뒤로가기
        view.findViewById<ImageView>(R.id.btnBack)?.setOnClickListener {
            findNavController().navigateUp()
        }

        // 필터 버튼 (정렬 바텀시트 열기)
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

        recyclerView = view.findViewById(R.id.rvPlaceList)
        adapter = PlaceAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        loadDummyData()
    }

    private fun loadDummyData() {
        // 초기에는 모든 투표 수를 0으로 시작
        places = mutableListOf(
            Place("스타벅스 역삼역점", "카페", 4.5, 2.3, "도보 8분", 0, memberCount = 8),
            Place("본죽 강남점", "식당", 4.3, 2.1, "도보 7분", 0, memberCount = 8),
            Place("투썸플레이스 선릉점", "카페", 4.8, 2.8, "도보 10분", 0, memberCount = 8),
            Place("김가네 김밥", "식당", 4.0, 1.9, "도보 6분", 0, memberCount = 8)
        )
        adapter.submitList(places)
    }



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

        val filtered = if (category == "전체") places
        else places.filter { it.category == category }

        adapter.submitList(filtered)
    }

    private fun showFilterBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottomsheet_filter, null)

        val seekBarDistance = sheetView.findViewById<SeekBar>(R.id.seekBarDistance)
        val seekBarRating = sheetView.findViewById<SeekBar>(R.id.seekBarRating)
        val tvMaxDistance = sheetView.findViewById<TextView>(R.id.tvMaxDistanceValue)
        val tvMinRating = sheetView.findViewById<TextView>(R.id.tvMinRatingValue)
        val btnClose = sheetView.findViewById<ImageView>(R.id.btnCloseFilter)
        val btnApply = sheetView.findViewById<Button>(R.id.btnApplyFilter)

        // 거리 슬라이더 동작
        seekBarDistance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val distanceValue = progress / 10.0
                tvMaxDistance.text = String.format("%.1fkm", distanceValue)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 평점 슬라이더 동작
        seekBarRating.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratingValue = progress / 10.0
                tvMinRating.text = String.format("%.1f점 이상", ratingValue)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 닫기 버튼 (필터 적용 없이 닫기)
        btnClose.setOnClickListener { dialog.dismiss() }

        // ✅ 필터 적용 버튼
        btnApply.setOnClickListener {
            val maxDistance = seekBarDistance.progress / 10.0
            val minRating = seekBarRating.progress / 10.0

            val filtered = places.filter {
                it.distanceKm <= maxDistance && it.rating >= minRating
            }

            adapter.submitList(filtered)
            dialog.dismiss()
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }

}
