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

class PlaceListFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var middleCard: LinearLayout
    private lateinit var tvAddress: TextView
    private lateinit var tvDistance: TextView

    private val placeList = listOf(
        Place("스타벅스 역삼역점", "카페", "평균 2.3km"),
        Place("투썸플레이스 선릉점", "카페", "평균 2.8km"),
        Place("할리스 강남역점", "카페", "평균 3.1km"),
        Place("메가커피 역삼역점", "카페", "평균 2.5km")
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

        // 더미 데이터
        setupMiddlePointCard()
        setupRecycler()


    //  "추천 장소 > 위치" 버튼 클릭 시 위치 입력 화면으로 이동

        val btnFilterLocation = view.findViewById<View>(R.id.btnFilterLocation)
        btnFilterLocation.setOnClickListener {
            findNavController().navigate(R.id.action_groupDetailFragment_to_locationInputFragment)
        }

        val btnFilterTime = view.findViewById<View>(R.id.btnFilterTime)
        btnFilterTime.setOnClickListener {
            val navController = requireActivity()
                .supportFragmentManager
                .findFragmentById(R.id.nav_host)
                ?.findNavController()
            navController?.navigate(R.id.action_groupDetailFragment_to_timeVoteFragment)
        }

        return view
    }

    private fun setupMiddlePointCard() {
        // 나중에 Firestore에서 받아올 부분
        middleCard.visibility = View.VISIBLE
        tvAddress.text = "서울시 강남구 역삼동 일대"
        tvDistance.text = "모든 멤버의 평균 이동거리: 2.5km"
    }

    private fun setupRecycler() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = PlaceAdapter(placeList)
        recyclerView.isNestedScrollingEnabled = false
    }

    data class Place(val name: String, val category: String, val distance: String)

    inner class PlaceAdapter(private val places: List<Place>) :
        RecyclerView.Adapter<PlaceAdapter.PlaceViewHolder>() {

        inner class PlaceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName = view.findViewById<TextView>(R.id.tvPlaceName)
            val tvCategory = view.findViewById<TextView>(R.id.tvPlaceCategory)
            val tvDistance = view.findViewById<TextView>(R.id.tvPlaceDistance)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_place_card, parent, false)
            return PlaceViewHolder(view)
        }

        override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
            val place = places[position]
            holder.tvName.text = place.name
            holder.tvCategory.text = place.category
            holder.tvDistance.text = place.distance
        }

        override fun getItemCount(): Int = places.size
    }
}
