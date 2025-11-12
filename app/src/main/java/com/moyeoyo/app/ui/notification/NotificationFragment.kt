package com.moyeoyo.app.ui.notification

import NotificationAdapter
import NotificationUi
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R

class NotificationFragment : Fragment(R.layout.fragment_notification) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerNotifications)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val dummyNotifications = listOf(
            NotificationUi("약속 확정", "대학 동기들 모임이 10월 20일 18:00로 확정되었습니다", "방금 전", "confirmed"),
            NotificationUi("D-1 알림", "내일 18:00 스타벅스 역삼역점에서 만나요!", "5분 전", "d1"),
            NotificationUi("일정 변경", "회사 동료 모임 장소가 변경되었습니다", "1시간 전", "changed"),
            NotificationUi("새 멤버 참여", "김철수님이 고등학교 친구들 그룹에 참여했습니다", "2시간 전", "member"),
            NotificationUi("투표 마감 임박", "시간 투표가 2시간 후 마감됩니다", "어제", "vote"),
            NotificationUi("모임 취소", "회사 동료 모임이 취소되었습니다", "2일 전", "canceled")
        )

        recyclerView.adapter = NotificationAdapter(dummyNotifications)
    }
}
