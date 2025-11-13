package com.moyeoyo.app.ui.group

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R
import com.moyeoyo.app.model.Place

class PlaceListFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var middleCard: LinearLayout
    private lateinit var tvAddress: TextView
    private lateinit var tvDistance: TextView

    // 🔹 기존 더미 데이터를 model.Place 로 변환
    private val placeList = listOf(
        Place(
            name = "스타벅스 역삼역점",
            category = "카페",
            rating = 4.5,
            distanceKm = 2.3,
            walkingTime = "",
            transitTime = "",
            drivingTime = "",
            likeCount = 0
        ),
        Place(
            name = "투썸플레이스 선릉점",
            category = "카페",
            rating = 4.2,
            distanceKm = 2.8,
            walkingTime = "",
            transitTime = "",
            drivingTime = "",
            likeCount = 0
        ),
        Place(
            name = "할리스 강남역점",
            category = "카페",
            rating = 4.3,
            distanceKm = 3.1,
            walkingTime = "",
            transitTime = "",
            drivingTime = "",
            likeCount = 0
        ),
        Place(
            name = "메가커피 역삼역점",
            category = "카페",
            rating = 4.1,
            distanceKm = 2.5,
            walkingTime = "",
            transitTime = "",
            drivingTime = "",
            likeCount = 0
        )
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_place_list, container, false)

        recyclerView = view.findViewById(R.id.recyclerPlaceList)
        middleCard = view.findViewById(R.id.layoutMiddlePoint)
        tvAddress = view.findViewById(R.id.tvMiddleAddress)
        tvDistance = view.findViewById(R.id.tvMiddleDistance)

        setupMiddlePointCard()
        setupRecycler()

        // 🔹 “추천 장소 > 위치” 필터 이동
        view.findViewById<View>(R.id.btnFilterLocation).setOnClickListener {
            findNavController().navigate(R.id.action_groupDetailFragment_to_locationInputFragment)
        }

        // 🔹 “추천 장소 > 시간” 필터 이동
        view.findViewById<View>(R.id.btnFilterTime).setOnClickListener {
            findNavController().navigate(
                R.id.action_groupDetailFragment_to_timeVoteFragment
            )
        }

        return view
    }

    // 가운데 상단 미들포인트 카드 (더미 데이터)
    private fun setupMiddlePointCard() {
        middleCard.visibility = View.VISIBLE
        tvAddress.text = "서울시 강남구 역삼동 일대"
        tvDistance.text = "모든 멤버의 평균 이동거리: 2.5km"
    }

    // 여기서 진짜 PlaceAdapter(좋아요 기능 있는)를 연결해줌
    private fun setupRecycler() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val adapter = com.moyeoyo.app.ui.group.PlaceAdapter() // ← 진짜 좋아요 있는 어댑터
        recyclerView.adapter = adapter

        // 리스트 연결
        adapter.submitList(placeList)

        // NestedScrollView 안에서 클릭 가능하게 함
        recyclerView.isNestedScrollingEnabled = false
    }
}
