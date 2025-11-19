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
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.ui.auth.LoginActivity
import com.moyeoyo.app.ui.auth.ProfileSetupActivity
import com.moyeoyo.app.ui.friends.AddFriendActivity
import com.moyeoyo.app.ui.groups.CreateGroupActivity
import com.moyeoyo.app.ui.groups.GroupDetailActivity
import com.moyeoyo.app.ui.notification.NotificationActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import android.app.ProgressDialog
import javax.inject.Inject

@AndroidEntryPoint
class MainFragment : Fragment(R.layout.activity_main) {

    @Inject lateinit var friendRepository: FriendRepository

    private val groupRepository = GroupRepository(
        db = FirebaseFirestore.getInstance(),
        auth = FirebaseAuth.getInstance()
    )

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var progressDialog: ProgressDialog

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

        profileCardArea.setOnClickListener {
            startActivity(Intent(activity, ProfileSetupActivity::class.java))
        }

        btnLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(activity, LoginActivity::class.java))
            activity?.finish()
        }

        btnCreateGroup.setOnClickListener {
            startActivity(Intent(activity, CreateGroupActivity::class.java))
        }

        btnAddFriend.setOnClickListener {
            startActivity(Intent(activity, AddFriendActivity::class.java))
        }

        btnNotifications.setOnClickListener {
            startActivity(Intent(activity, NotificationActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadUserProfile()
        loadGroups()
        updateNotificationBadge()
    }

    // ─ 알림 배지 업데이트 ─
    private fun updateNotificationBadge() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pendingRequests = friendRepository.getPendingRequests()
                notificationBadge.visibility =
                    if (pendingRequests.isNotEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Log.e("MAIN", "Error checking pending requests: ${e.message}")
                notificationBadge.visibility = View.GONE
            }
        }
    }

    // ─ 사용자 프로필 로드 ─
    private fun loadUserProfile() {
        val user = auth.currentUser ?: return
        textEmail.text = user.email ?: ""

        progressDialog.show()

        firestore.collection("users").document(user.uid)
            .get()
            .addOnSuccessListener { doc ->
                progressDialog.dismiss()

                if (!doc.exists()) {
                    Snackbar.make(requireView(), "사용자 정보를 찾을 수 없습니다.", Snackbar.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val nickname = doc.getString("nickname") ?: "닉네임 없음"
                val photoUrl = doc.getString("photoUrl")

                textNickname.text = nickname

                if (!photoUrl.isNullOrEmpty()) {
                    Glide.with(this)
                        .load(photoUrl)
                        .circleCrop()
                        .placeholder(R.drawable.ic_user_placeholder)
                        .into(profileImage)
                } else {
                    profileImage.setImageResource(R.drawable.ic_user_placeholder)
                }
            }
            .addOnFailureListener {
                progressDialog.dismiss()
                Snackbar.make(requireView(), "불러오기 실패", Snackbar.LENGTH_LONG).show()
            }
    }

    // ─ 그룹 목록 로드 ─
    private fun loadGroups() {
        viewLifecycleOwner.lifecycleScope.launch {
            val groups = groupRepository.getGroupsForUser()

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

            groupView.setOnClickListener {
                startActivity(
                    Intent(activity, GroupDetailActivity::class.java)
                        .putExtra("GROUP_ID", group.id)
                        .putExtra("GROUP_NAME", group.groupName)
                )
            }

            groupListContainer.addView(groupView)
        }
    }
}
