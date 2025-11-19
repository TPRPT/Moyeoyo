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
import com.moyeoyo.app.data.model.Notification
import com.moyeoyo.app.data.model.NotificationUi
import com.moyeoyo.app.data.repository.NotificationRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class NotificationActivity : AppCompatActivity() {

    private val notificationRepository = NotificationRepository()

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvNotificationCount: TextView
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification)

        recyclerView = findViewById(R.id.recyclerNotifications)
        tvNotificationCount = findViewById(R.id.tvNotificationCount)
        emptyText = findViewById(R.id.tvEmpty)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView.layoutManager = LinearLayoutManager(this)

        loadNotifications()
    }

    // -------------------------------------------------------------------
    // 🔵 Firestore Notifications 불러오기
    // -------------------------------------------------------------------
    private fun loadNotifications() {
        lifecycleScope.launch {
            try {
                val rawList = notificationRepository.getNotifications()

                val sorted = rawList
                    .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                    .map { convertToUi(it) }

                tvNotificationCount.text = "${sorted.size}개"

                if (sorted.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    recyclerView.adapter = NotificationAdapter(emptyList())
                } else {
                    emptyText.visibility = View.GONE
                    recyclerView.adapter = NotificationAdapter(sorted)
                }

            } catch (e: Exception) {
                Log.e("NotificationActivity", "Error: ${e.message}", e)
                Toast.makeText(this@NotificationActivity, "알림을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -------------------------------------------------------------------
    // 🔵 Notification → NotificationUi 변환
    // -------------------------------------------------------------------
    private fun convertToUi(n: Notification): NotificationUi {
        val ts = n.createdAt?.toDate()?.time ?: 0L
        return NotificationUi(
            title = n.title ?: "",
            message = n.message ?: "",
            type = "friend_request",
            time = formatTime(ts),
            timestamp = ts
        )
    }

    // -------------------------------------------------------------------
    // 🔵 timestamp → "몇분 전" 변환
    // -------------------------------------------------------------------
    private fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return ""

        val now = System.currentTimeMillis()
        val diff = now - timestamp

        val minutes = diff / 60000
        val hours = diff / 3600000
        val days = diff / 86400000

        return when {
            minutes < 1 -> "방금 전"
            minutes < 60 -> "${minutes}분 전"
            hours < 24 -> "${hours}시간 전"
            days < 7 -> "${days}일 전"
            else -> {
                val sdf = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
                sdf.format(Date(timestamp))
            }
        }
    }

    // -------------------------------------------------------------------
    // 🔵 RecyclerView Adapter
    // -------------------------------------------------------------------
    private inner class NotificationAdapter(private val items: List<NotificationUi>) :
        RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvTitle)
            val message: TextView = view.findViewById(R.id.tvMessage)
            val time: TextView = view.findViewById(R.id.tvTime)
            val card: CardView = view.findViewById(R.id.cardNotification)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notification, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]

            holder.title.text = item.title
            holder.message.text = item.message
            holder.time.text = item.time

            holder.card.setOnClickListener {
                Toast.makeText(this@NotificationActivity, "클릭됨: ${item.title}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
