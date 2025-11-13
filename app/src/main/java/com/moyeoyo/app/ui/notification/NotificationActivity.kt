package com.moyeoyo.app.ui.notification

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.NotificationUi
import com.moyeoyo.app.data.repository.NotificationRepository
import kotlinx.coroutines.launch

/**
 * 알림 목록을 표시하는 Activity로, 모든 UI 로직과 Adapter 구현을 포함합니다.
 */
class NotificationActivity : AppCompatActivity() {

    private val notificationRepository = NotificationRepository()
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvNotificationCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // activity_notification.xml 레이아웃을 직접 사용합니다.
        setContentView(R.layout.activity_notification)

        // 1. View 초기화
        recyclerView = findViewById(R.id.recyclerNotifications)
        tvNotificationCount = findViewById(R.id.tvNotificationCount)

        // 2. 툴바 설정 (뒤로가기 버튼)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            finish() // Activity 종료하여 이전 화면(MainActivity)으로 돌아갑니다.
        }

        // 3. RecyclerView 설정
        recyclerView.layoutManager = LinearLayoutManager(this)

        // 4. 알림 데이터 로드 및 표시
        loadNotifications()
    }

    /**
     * NotificationRepository를 사용하여 알림 데이터를 로드하고 UI를 업데이트합니다.
     */
    private fun loadNotifications() {
        // Repository에서 알림을 가져오는 동안 UI 스레드를 차단하지 않도록 lifecycleScope 사용
        lifecycleScope.launch {
            try {
                // Repository에서 Firestore에 저장된 알림 목록을 가져옵니다.
                val notifications = notificationRepository.getNotifications()

                // 뷰 업데이트
                tvNotificationCount.text = "${notifications.size}개"
                recyclerView.adapter = NotificationAdapter(notifications)

            } catch (e: Exception) {
                Log.e("NotiActivity", "Failed to load notifications: ${e.message}", e)
                Toast.makeText(this@NotificationActivity, "알림 목록을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                tvNotificationCount.text = "0개"
            }
        }
    }

    // =================================================================================
    // RecyclerView Adapter를 Activity 내부에 정의 (UI 로직 통합)
    // =================================================================================

    /**
     * 알림 목록을 표시하는 RecyclerView Adapter
     */
    private inner class NotificationAdapter(private val items: List<NotificationUi>) :
        RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            // item_notification.xml의 ID를 사용합니다.
            val title = view.findViewById<TextView>(R.id.tvTitle)
            val message = view.findViewById<TextView>(R.id.tvMessage)
            val time = view.findViewById<TextView>(R.id.tvTime)
            val card = view.findViewById<CardView>(R.id.cardNotification)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notification, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.message.text = item.message
            holder.time.text = item.time

            // 알림 타입에 따라 배경 또는 클릭 리스너 설정
            when (item.type) {
                "friend_request" -> {
                    // ⭐ 배경 리소스를 참조하여 설정합니다.
                    holder.card.setBackgroundResource(R.drawable.bg_card_confirmed)

                    holder.card.setOnClickListener {
                        Toast.makeText(this@NotificationActivity, "친구 요청 처리 화면으로 이동합니다.", Toast.LENGTH_SHORT).show()
                        // TODO: 친구 요청 처리 다이얼로그나 Activity 이동 로직 구현
                    }
                }
                "confirmed" -> holder.card.setBackgroundResource(R.drawable.bg_card_confirmed)
                "d1" -> holder.card.setBackgroundResource(R.drawable.bg_card_d1)
                "changed" -> holder.card.setBackgroundResource(R.drawable.bg_card_changed)
                else -> holder.card.setBackgroundResource(R.drawable.bg_card_gray)
            }
        }
    }
}