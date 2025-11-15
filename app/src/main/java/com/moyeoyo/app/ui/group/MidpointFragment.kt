package com.moyeoyo.app.ui.group

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.gms.maps.model.LatLng
import com.moyeoyo.app.R

class MidpointFragment : Fragment(R.layout.fragment_midpoint) {

    private val args: MidpointFragmentArgs by navArgs()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 🔙 뒤로가기 버튼
        view.findViewById<View>(R.id.toolbarMidpoint).setOnClickListener {
            findNavController().navigateUp()
        }

        val selectedLocation = args.selectedLocation

        // 임시 멤버 위치 예시
        val memberLocations = listOf(
            LatLng(37.5012, 127.0264),
            LatLng(37.5078, 127.0220),
            LatLng(37.5044, 127.0291)
        )

        val midpoint = calculateMidpoint(memberLocations)

        view.findViewById<TextView>(R.id.tvMidpointAddress).text = "서울시 강남구 역삼동 일대"
        view.findViewById<TextView>(R.id.tvMidpointCoords).text =
            "위도: %.4f° / 경도: %.4f°".format(midpoint.latitude, midpoint.longitude)

        view.findViewById<TextView>(R.id.tvStationLineAndName).text = "2호선 역삼역"
        view.findViewById<TextView>(R.id.tvStationDistance).text = "중간 지점에서 약 350m (도보 4분)"

        view.findViewById<TextView>(R.id.tvAvgDistance).text = "평균 이동 거리: 2.5km"
        view.findViewById<TextView>(R.id.tvMaxDeviation).text = "최대 편차: ±0.4km (공평한 위치)"

        // 🟦 주변 장소 추천 보기 버튼
        view.findViewById<Button>(R.id.btnNearbyPlaces).setOnClickListener {
            val action = MidpointFragmentDirections.actionMidpointFragmentToRecommendedPlaceFragment()
            findNavController().navigate(action)
        }
    }

    private fun calculateMidpoint(locations: List<LatLng>): LatLng {
        var latSum = 0.0
        var lngSum = 0.0
        for (loc in locations) {
            latSum += loc.latitude
            lngSum += loc.longitude
        }
        return LatLng(latSum / locations.size, lngSum / locations.size)
    }
}
