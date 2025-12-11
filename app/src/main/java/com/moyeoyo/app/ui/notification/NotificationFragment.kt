package com.moyeoyo.app.ui.notification

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.Notification
import com.moyeoyo.app.data.model.NotificationUi
import com.moyeoyo.app.data.repository.NotificationRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import com.moyeoyo.app.data.repository.FriendRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import java.util.*
import androidx.appcompat.app.AlertDialog
import android.widget.Switch
import android.content.SharedPreferences
import com.moyeoyo.app.data.repository.GroupRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class NotificationFragment : Fragment() {

    private val notificationRepository = NotificationRepository()

    private val friendRepository = FriendRepository(
        FirebaseFirestore.getInstance(),
        FirebaseAuth.getInstance(),
        NotificationRepository()
    )
    
    @Inject
    lateinit var groupRepository: GroupRepository

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: NotificationAdapter
    private lateinit var tvNotificationCount: TextView
    private lateinit var emptyText: TextView
    private lateinit var switchNotification: Switch
    private lateinit var sharedPreferences: SharedPreferences
    
    // ⭐ 읽지 않은 알림 ID 저장 (onResume에서 저장, onPause에서 읽음 처리)
    private var unreadNotificationIds: MutableSet<String> = mutableSetOf()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_notification, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerNotifications)
        tvNotificationCount = view.findViewById(R.id.tvNotificationCount)
        emptyText = view.findViewById(R.id.tvEmpty)
        
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { 
            findNavController().popBackStack()
        }

        // 푸시 알림 토글 초기화
        switchNotification = view.findViewById(R.id.switchNotification)
        sharedPreferences = requireContext().getSharedPreferences("app_preferences", android.content.Context.MODE_PRIVATE)
        
        val isNotificationEnabled = sharedPreferences.getBoolean("push_notification_enabled", true)
        switchNotification.isChecked = isNotificationEnabled

        // 푸시 알림 토글 리스너
        switchNotification.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit()
                .putBoolean("push_notification_enabled", isChecked)
                .apply()
            
            if (isChecked) {
                Toast.makeText(requireContext(), "푸시 알림이 켜졌습니다.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "푸시 알림이 꺼졌습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // 전체 삭제 버튼 추가 (툴바에 메뉴로 추가)
        try {
            toolbar.inflateMenu(R.menu.notification_menu)
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_delete_all -> {
                        showDeleteAllDialog()
                        true
                    }
                    else -> false
                }
            }
        } catch (e: Exception) {
            Log.e("NotificationFragment", "Failed to inflate menu: ${e.message}")
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        adapter = NotificationAdapter(mutableListOf())
        recyclerView.adapter = adapter
        initSwipeToDelete(adapter)

        // ⭐ 실시간 알림 리스너 시작
        observeNotifications()
    }
    
    override fun onResume() {
        super.onResume()
        // ⭐ 알림창에 들어올 때 읽지 않은 알림 ID 저장
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val notifications = notificationRepository.getNotifications()
                unreadNotificationIds = notifications
                    .filter { !it.read && it.type != "friend_request" }
                    .map { it.id }
                    .toMutableSet()
                Log.d("NotificationFragment", "onResume: 읽지 않은 알림 ${unreadNotificationIds.size}개 저장")
            } catch (e: Exception) {
                Log.e("NotificationFragment", "onResume에서 알림 로드 실패: ${e.message}", e)
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // ⭐ 알림창을 떠날 때 읽지 않은 알림 전체 읽음 처리
        if (unreadNotificationIds.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    notificationRepository.markNotificationsAsRead(unreadNotificationIds.toList())
                    Log.d("NotificationFragment", "onPause: ${unreadNotificationIds.size}개 알림 읽음 처리 완료")
                    unreadNotificationIds.clear()
                } catch (e: Exception) {
                    Log.e("NotificationFragment", "onPause에서 읽음 처리 실패: ${e.message}", e)
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // 🔵 실시간 알림 리스너 (Firestore 변경 감지)
    // -------------------------------------------------------------------
    private fun observeNotifications() {
        viewLifecycleOwner.lifecycleScope.launch {
            notificationRepository.observeNotifications().collectLatest { rawList ->
                try {
                    // 1) UI 업데이트 (최신순 정렬)
                    val sorted = rawList
                        .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                        .map { convertToUi(it) }
                        .sortedByDescending { it.timestamp }   // 최신순 보장

                    tvNotificationCount.text = "${sorted.size}개"

                    adapter.items.clear()
                    adapter.items.addAll(sorted)
                    adapter.notifyDataSetChanged()

                    emptyText.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE

                    // ⭐ 자동 읽음 처리 제거 - onPause에서 처리하도록 변경

                } catch (e: Exception) {
                    Log.e("NotificationFragment", "Error updating notifications: ${e.message}", e)
                }
            }
        }
    }


    // -------------------------------------------------------------------
    // 🔵 Notification → NotificationUi 변환
    // -------------------------------------------------------------------
    private fun convertToUi(n: Notification): NotificationUi {
        val ts = n.createdAt?.toDate()?.time ?: 0L

        return NotificationUi(
            id = n.id,                             // Firestore 문서 ID
            title = n.title ?: "",
            message = n.message ?: "",
            type = n.type ?: "unknown",            // 기본값 처리
            groupId = n.groupId,                   // 그룹 알림이면 groupId 존재
            senderUid = n.senderUid,               // 친구 요청이면 senderUid 존재
            time = formatTime(ts),
            timestamp = ts,
            read = n.read,                          // 읽음 여부 반영
            handled = n.handled
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
    private inner class NotificationAdapter(val items: MutableList<NotificationUi>) :
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

            // 읽음/안읽음 시각 효과
            // 친구 요청은 handled=true 일 때만 흐림!
            if (item.type == "friend_request") {
                holder.card.alpha = if (item.handled) 0.4f else 1.0f
            } else {
                holder.card.alpha = if (item.read) 0.4f else 1.0f
            }

            holder.card.setOnClickListener {

                // 알림 타입 분기
                when (item.type) {

                    // 🔵 친구 요청 알림
                    "friend_request" -> {
                        // handled=true 이면 아예 비활성화
                        if (item.handled) {
                            Toast.makeText(
                                requireContext(),
                                "이미 처리된 친구 요청입니다.",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnClickListener
                        }
                        showFriendRequestDialog(item)   // 수락/거절 처리
                    }

                    // 🔵 그룹 관련 알림 → 그룹 상세로 이동
                    "time_vote", "location_input", "final_vote", "finalized", "ranking", "reminder" -> {
                        if (item.groupId == null || item.groupId.isBlank()) {
                            Log.e("NotificationFragment", "그룹 ID가 없습니다. 알림 ID: ${item.id}, 타입: ${item.type}")
                            Toast.makeText(
                                requireContext(),
                                "그룹 정보를 찾을 수 없습니다. 알림에 그룹 ID가 포함되지 않았습니다.", Toast.LENGTH_LONG
                            ).show()
                            return@setOnClickListener
                        }

                        // ⭐ 그룹 존재 여부 확인
                        viewLifecycleOwner.lifecycleScope.launch {
                            val group = groupRepository.getGroupById(item.groupId)
                            
                            // 그룹이 존재하지 않으면 메시지만 표시하고 네비게이션하지 않음
                            if (group == null) {
                                Log.w("NotificationFragment", "그룹이 존재하지 않습니다. groupId: ${item.groupId}")
                                Toast.makeText(
                                    requireContext(),
                                    "존재하지 않는 그룹입니다.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }
                            
                            // 단건 읽음 처리
                            notificationRepository.markNotificationAsRead(item.id)

                            // GroupDetailFragment로 Navigation
                            findNavController().navigate(
                                R.id.action_notificationFragment_to_groupDetailFragment,
                                Bundle().apply {
                                    putString("groupId", item.groupId)
                                    putString("groupName", group.groupName)
                                }
                            )

                            // UI 흐림 처리 반영
                            holder.card.alpha = 0.4f
                        }
                    }

                    else -> {
                        Toast.makeText(
                            requireContext(),
                            "지원되지 않는 알림 유형입니다.", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun showFriendRequestDialog(item: NotificationUi) {
        val senderUid = item.senderUid ?: return

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("친구 요청")
            .setMessage("이 사용자의 친구 요청을 수락할까요?")
            .setPositiveButton("수락") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = friendRepository.acceptFriendRequest(senderUid)

                    if (ok) {
                        // 읽음 처리
                        notificationRepository.markNotificationAsRead(item.id)
                        notificationRepository.markNotificationAsHandled(item.id)

                        Toast.makeText(
                            requireContext(),
                            "친구 요청을 수락했습니다!",
                            Toast.LENGTH_SHORT
                        ).show()

                        // ⭐ UI 갱신은 실시간 리스너(observeNotifications)에서 자동으로 처리됨
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "친구 요청 수락 실패",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("거절") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    notificationRepository.markNotificationAsRead(item.id)
                    notificationRepository.markNotificationAsHandled(item.id)
                    // ⭐ UI 갱신은 실시간 리스너(observeNotifications)에서 자동으로 처리됨
                }
            }
            .create()

        dialog.show()
    }

    private fun initSwipeToDelete(adapter: NotificationAdapter) {

        val swipeHelper = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val item = adapter.items[position]

                viewLifecycleOwner.lifecycleScope.launch {
                    notificationRepository.deleteNotification(item.id)
                    adapter.items.removeAt(position)
                    adapter.notifyItemRemoved(position)
                    tvNotificationCount.text = "${adapter.items.size}개"
                }
            }
        }

        ItemTouchHelper(swipeHelper).attachToRecyclerView(recyclerView)
    }

    /**
     * 전체 삭제 확인 다이얼로그
     */
    private fun showDeleteAllDialog() {
        if (adapter.items.isEmpty()) {
            Toast.makeText(requireContext(), "삭제할 알림이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("알림 전체 삭제")
            .setMessage("모든 알림을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val success = notificationRepository.deleteAllNotifications()
                    if (success) {
                        adapter.items.clear()
                        adapter.notifyDataSetChanged()
                        tvNotificationCount.text = "0개"
                        emptyText.visibility = View.VISIBLE
                        Toast.makeText(requireContext(), "모든 알림이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "알림 삭제에 실패했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}

