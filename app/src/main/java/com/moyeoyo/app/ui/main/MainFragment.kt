package com.moyeoyo.app.ui.main

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.data.repository.NotificationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import android.app.ProgressDialog
import javax.inject.Inject

@AndroidEntryPoint
class MainFragment : Fragment(R.layout.fragment_main) {

    @Inject lateinit var friendRepository: FriendRepository
    @Inject lateinit var groupRepository: GroupRepository

    private val notificationRepository = NotificationRepository()

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var progressDialog: ProgressDialog
    private var progressDialogShowTime: Long = 0
    private val MIN_PROGRESS_DISPLAY_TIME = 500L // 최소 표시 시간 (밀리초)

    // UI
    private lateinit var profileImage: ImageView
    private lateinit var textNickname: TextView
    private lateinit var textEmail: TextView
    private lateinit var btnLogout: Button
    private lateinit var btnCreateGroup: Button
    private lateinit var btnAddFriend: Button
    private lateinit var btnNotifications: ImageButton
    private lateinit var notificationBadge: TextView
    private lateinit var profileCardArea: LinearLayout
    private lateinit var groupListContainer: LinearLayout
    private lateinit var textNoGroups: TextView
    private lateinit var textGroupCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        progressDialog = ProgressDialog(context).apply {
            setMessage("정보 불러오는 중...")
            setCancelable(false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        profileImage = view.findViewById(R.id.profile_image)
        textNickname = view.findViewById(R.id.text_nickname)
        textEmail = view.findViewById(R.id.text_email)
        btnLogout = view.findViewById(R.id.btn_logout)
        btnCreateGroup = view.findViewById(R.id.btn_create_group)
        btnAddFriend = view.findViewById(R.id.btn_add_friend)
        btnNotifications = view.findViewById(R.id.btn_notifications)
        notificationBadge = view.findViewById(R.id.notification_badge)
        profileCardArea = view.findViewById(R.id.profile_card)
        groupListContainer = view.findViewById(R.id.group_list_container)
        textNoGroups = view.findViewById(R.id.text_no_groups)
        textGroupCount = view.findViewById(R.id.text_group_count)

        profileCardArea.setOnClickListener {
            findNavController().navigate(MainFragmentDirections.actionMainFragmentToProfileSetupFragment())
        }

        btnLogout.setOnClickListener {
            auth.signOut()
            // TODO: Safe Args가 생성되면 Directions 사용
            findNavController().navigate(R.id.loginFragment)
        }

        btnCreateGroup.setOnClickListener {
            val action = MainFragmentDirections.actionMainFragmentToCreateGroupFragment()
            findNavController().navigate(action)
        }

        btnAddFriend.setOnClickListener {
            findNavController().navigate(R.id.addFriendFragment)
        }

        btnNotifications.setOnClickListener {
            findNavController().navigate(R.id.notificationFragment)
        }
    }

    override fun onResume() {
        super.onResume()
        loadUserProfile()
        loadGroups()
        updateNotificationBadge()
    }

    // ─────────────────────────────
    // 🔥 알림 뱃지 업데이트 (최종 정답)
    // ─────────────────────────────
    fun updateNotificationBadge() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val allNotifications = notificationRepository.getNotifications()

                val hasUnread = allNotifications.any { n ->
                    when (n.type) {
                        "friend_request" -> !n.handled       // 친구요청: handled=false
                        else -> !n.read                      // 일반 알림: read=false
                    }
                }

                notificationBadge.visibility =
                    if (hasUnread) View.VISIBLE else View.GONE

            } catch (e: Exception) {
                Log.e("MAIN", "Error checking notifications: ${e.message}")
                notificationBadge.visibility = View.GONE
            }
        }
    }

    // ─ 사용자 프로필 로드 ─
    private fun loadUserProfile() {
        val user = auth.currentUser ?: return
        textEmail.text = user.email ?: ""

        showProgressDialog()

        firestore.collection("users").document(user.uid)
            .get()
            .addOnSuccessListener { doc ->
                dismissProgressDialog()

                if (!doc.exists()) {
                    Snackbar.make(requireView(), "사용자 정보를 찾을 수 없습니다.", Snackbar.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val nickname = doc.getString("nickname") ?: "닉네임 없음"
                val photoUrl = doc.getString("photoUrl")

                textNickname.text = nickname

                if (!photoUrl.isNullOrEmpty()) {
                    // 프로필 사진 업데이트를 위해 캐시 무시
                    Glide.with(this)
                        .load(photoUrl)
                        .skipMemoryCache(true) // 메모리 캐시 무시
                        .circleCrop()
                        .placeholder(R.drawable.ic_user_placeholder)
                        .into(profileImage)
                } else {
                    profileImage.setImageResource(R.drawable.ic_user_placeholder)
                }
            }
            .addOnFailureListener {
                dismissProgressDialog()
                Snackbar.make(requireView(), "불러오기 실패", Snackbar.LENGTH_LONG).show()
            }
    }

    /**
     * ProgressDialog를 표시하고 시간을 기록
     */
    private fun showProgressDialog() {
        progressDialogShowTime = System.currentTimeMillis()
        progressDialog.show()
    }

    /**
     * ProgressDialog를 닫되, 최소 표시 시간이 지나지 않았으면 대기 후 닫기
     */
    private fun dismissProgressDialog() {
        val elapsedTime = System.currentTimeMillis() - progressDialogShowTime
        val remainingTime = MIN_PROGRESS_DISPLAY_TIME - elapsedTime

        if (remainingTime > 0) {
            // 최소 표시 시간이 지나지 않았으면 대기 후 닫기
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(remainingTime)
                if (progressDialog.isShowing) {
                    progressDialog.dismiss()
                }
            }
        } else {
            // 이미 최소 표시 시간이 지났으면 바로 닫기
            progressDialog.dismiss()
        }
    }

    // ─ 그룹 목록 로드 ─
    private fun loadGroups() {
        viewLifecycleOwner.lifecycleScope.launch {
            val groups = groupRepository.getGroupsForUser()

            textGroupCount.text = "참여중인 그룹 ${groups.size}개"

            if (groups.isEmpty()) {
                textNoGroups.visibility = View.VISIBLE
                groupListContainer.removeAllViews()
            } else {
                textNoGroups.visibility = View.GONE
                displayGroups(groups)
            }
        }
    }

    // ─ 그룹 리스트 UI 구성 ─
    private fun displayGroups(groups: List<Group>) {
        groupListContainer.removeAllViews()
        val inflater = LayoutInflater.from(context)

        groups.forEach { group ->
            val groupView = inflater.inflate(R.layout.item_group_card, groupListContainer, false)

            groupView.findViewById<TextView>(R.id.group_card_name).text = group.groupName
            groupView.findViewById<TextView>(R.id.group_card_members).text =
                "${group.memberUids.size}명 참여 중"

            // 확정된 일정 정보 표시 (오른쪽에 배치)
            val scheduleLayout = groupView.findViewById<LinearLayout>(R.id.group_card_schedule)
            val timeText = groupView.findViewById<TextView>(R.id.group_card_time)
            val placeText = groupView.findViewById<TextView>(R.id.group_card_place)

            if (group.status == "FINALIZED" && group.confirmedTime != null && group.confirmedPlace != null) {
                scheduleLayout.visibility = View.VISIBLE

                // 시간 포맷팅 (간단하게)
                val sdf = java.text.SimpleDateFormat("M/d (E) a h:mm", java.util.Locale.getDefault())
                val formattedTime = sdf.format(group.confirmedTime.toDate())
                timeText.text = formattedTime

                // 장소 정보 (간단하게)
                val placeName = group.confirmedPlace["name"] as? String ?: ""
                val displayPlace = if (placeName.isNotEmpty()) {
                    if (placeName.length > 8) placeName.substring(0, 8) + "..." else placeName
                } else {
                    "장소 미정"
                }
                placeText.text = displayPlace
            } else {
                scheduleLayout.visibility = View.GONE
            }

            groupView.setOnClickListener {
                val action = MainFragmentDirections.actionMainFragmentToGroupDetailFragment(
                    groupId = group.id,
                    groupName = group.groupName
                )
                findNavController().navigate(action)
            }

            groupListContainer.addView(groupView)
        }
    }
}
