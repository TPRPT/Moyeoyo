package com.moyeoyo.app.ui.calendar

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import com.moyeoyo.app.R

class CalendarSyncFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_calendar_sync, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)

        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
        val tvDate = view.findViewById<TextView>(R.id.tvDate)
        val tvTime = view.findViewById<TextView>(R.id.tvTime)
        val tvPlace = view.findViewById<TextView>(R.id.tvPlace)
        val etMemo = view.findViewById<EditText>(R.id.etMemo)
        val btnLater = view.findViewById<Button>(R.id.btnLater)

        // 뒤로가기
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        // 전달받은 인자
        val args = CalendarSyncFragmentArgs.fromBundle(requireArguments())

        val dateTime = args.dateTime ?: ""

        // 제목 설정
        tvTitle.text = args.title + " 모임"

        // 날짜/시간 파싱
        val parts = dateTime.split(" ")

        if (parts.size >= 4) {
            // ex: "2025년 10월 20일 (일) 18:00"
            tvDate.text = parts[0] + " " + parts[1] + " " + parts[2]  // 날짜
            tvTime.text = parts[3]                                      // 시간
        } else {
            tvDate.text = dateTime
        }

        // 장소
        tvPlace.text = args.place

        // “나중에 하기”
        btnLater.setOnClickListener {
            findNavController().popBackStack()
        }
    }
}
