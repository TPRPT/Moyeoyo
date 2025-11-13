package com.moyeoyo.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.ui.auth.LoginActivity
import com.moyeoyo.app.ui.auth.ProfileSetupActivity
import com.moyeoyo.app.ui.friends.AddFriendActivity
import com.moyeoyo.app.ui.groups.CreateGroupActivity
import com.moyeoyo.app.ui.groups.GroupDetailActivity
import kotlinx.coroutines.launch
import android.app.ProgressDialog


class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var progressDialog: ProgressDialog

    private val groupRepository = GroupRepository()
    private val friendRepository = FriendRepository()

    private val HOSTING_DOMAIN = "moyeoyo-57ac0.web.app"

    // View 변수 선언
    private lateinit var profileImage: ImageView
    private lateinit var textNickname: TextView
    private lateinit var textEmail: TextView
    private lateinit var btnLogout: Button
    private lateinit var btnCreateGroup: Button
    private lateinit var btnAddFriend: Button
    private lateinit var btnNotifications: ImageButton
    private lateinit var notificationBadge: TextView // ⭐ 배지 View 필드

    private lateinit var profileCardArea: LinearLayout

    // 그룹 목록 관련 View
    private lateinit var groupListContainer: LinearLayout
    private lateinit var textNoGroups: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // View 초기화 (findViewById)
        profileImage = findViewById(R.id.profile_image)
        textNickname = findViewById(R.id.text_nickname)
        textEmail = findViewById(R.id.text_email)
        btnLogout = findViewById(R.id.btn_logout)
        btnCreateGroup = findViewById(R.id.btn_create_group)
        btnAddFriend = findViewById(R.id.btn_add_friend)
        btnNotifications = findViewById(R.id.btn_notifications)
        notificationBadge = findViewById(R.id.notification_badge) // ⭐ 배지 View 초기화

        profileCardArea = findViewById(R.id.profile_card)

        // 그룹 목록 View 초기화
        groupListContainer = findViewById(R.id.group_list_container)
        textNoGroups = findViewById(R.id.text_no_groups)

        progressDialog = ProgressDialog(this).apply {
            setMessage("정보 불러오는 중...")
            setCancelable(false)
        }

        val user = auth.currentUser
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        handleIntent(intent)
        loadUserProfile()

        // 💡 프로필 카드 클릭 리스너 설정
        profileCardArea.setOnClickListener {
            val intent = Intent(this, ProfileSetupActivity::class.java)
            startActivity(intent)
        }


        // 🔹 버튼 리스너 설정
        btnLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        btnCreateGroup.setOnClickListener {
            startActivity(Intent(this, CreateGroupActivity::class.java))
        }

        btnAddFriend.setOnClickListener {
            startActivity(Intent(this, AddFriendActivity::class.java))
        }

        // ⭐ 알림 버튼 클릭 리스너 설정
        btnNotifications.setOnClickListener {
            showFriendRequestsDialog()
        }
    }

    /**
     * ⭐ NEW: 대기 중인 친구 요청이 있는지 확인하고 알림 배지를 업데이트합니다.
     */
    private fun updateNotificationBadge() {
        lifecycleScope.launch {
            try {
                // FriendRepository를 통해 대기 중인 요청이 있는지 확인
                val pendingRequests = friendRepository.getPendingRequests()

                if (pendingRequests.isNotEmpty()) {
                    // 요청이 있으면 VISIBLE
                    notificationBadge.visibility = View.VISIBLE
                } else {
                    // 요청이 없으면 GONE
                    notificationBadge.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e("MAIN", "Error checking pending requests for badge: ${e.message}", e)
                notificationBadge.visibility = View.GONE
            }
        }
    }


    /**
     * 사용자 프로필 정보를 Firestore에서 로드하고 UI를 업데이트합니다.
     */
    private fun loadUserProfile() {
        val user = auth.currentUser ?: return
        textEmail.text = user.email ?: "이메일 없음"

        progressDialog.show()
        firestore.collection("users").document(user.uid)
            .get()
            .addOnSuccessListener { doc ->
                progressDialog.dismiss()
                if (doc.exists()) {
                    val nickname = doc.getString("nickname") ?: "닉네임 없음"
                    val photoUrl = doc.getString("photoUrl")

                    val homeLocationMap = doc.get("homeLocation") as? Map<*, *>
                    var homeAddressDisplay: String? = null

                    if (homeLocationMap != null) {
                        homeAddressDisplay = homeLocationMap["addressName"] as? String
                        if (homeAddressDisplay.isNullOrEmpty()) {
                            homeAddressDisplay = homeLocationMap["name"] as? String
                        }
                    }

                    textNickname.text = "$nickname (${homeAddressDisplay ?: "주소 미설정"})"

                    if (!photoUrl.isNullOrEmpty()) {
                        Glide.with(this)
                            .load(photoUrl)
                            .placeholder(R.drawable.ic_user_placeholder)
                            .circleCrop()
                            .into(profileImage)
                    } else {
                        profileImage.setImageResource(R.drawable.ic_user_placeholder)
                    }
                } else {
                    Snackbar.make(findViewById(android.R.id.content),
                        "사용자 정보를 찾을 수 없습니다.",
                        Snackbar.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { e ->
                progressDialog.dismiss()
                Snackbar.make(findViewById(android.R.id.content),
                    "불러오기 실패: ${e.message}",
                    Snackbar.LENGTH_LONG).show()
                Log.e("MAIN", "Firestore error", e)
            }
    }


    // ⭐ onResume 함수 수정: 화면이 재개될 때마다 배지 상태를 포함한 모든 정보를 새로고침합니다.
    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) {
            loadUserProfile()
            loadGroups()
            updateNotificationBadge() // ⭐ NEW: 배지 업데이트 호출
        }
    }


    // =========================================================================
    // ⭐ 친구 요청 처리 메서드 영역 ⭐
    // =========================================================================

    /**
     * 친구 요청 목록을 가져와 다이얼로그로 보여주는 함수 (알림 기능)
     */
    private fun showFriendRequestsDialog() {
        lifecycleScope.launch {
            val senderUids = friendRepository.getPendingRequests()

            if (senderUids.isEmpty()) {
                Toast.makeText(this@MainActivity, "새로운 친구 요청이 없습니다.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val requestItems = mutableListOf<String>()
            val uidToNicknameMap = mutableMapOf<String, String>()

            for (uid in senderUids) {
                val nickname = friendRepository.getUserNickname(uid) ?: uid.take(8)
                uidToNicknameMap[uid] = nickname
                requestItems.add("${nickname} 님이 친구 요청을 보냈습니다.")
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("새로운 친구 요청 (${requestItems.size})")
                .setItems(requestItems.toTypedArray()) { _, which ->
                    val selectedUid = senderUids[which]
                    val selectedNickname = uidToNicknameMap[selectedUid] ?: "친구"

                    showAcceptConfirmationDialog(selectedUid, selectedNickname)
                }
                .setNegativeButton("닫기", { _, _ ->
                    // 다이얼로그 닫을 때 배지 상태를 다시 확인하여 갱신
                    updateNotificationBadge()
                })
                .show()
        }
    }

    /**
     * 친구 요청 승인 확인 다이얼로그
     */
    private fun showAcceptConfirmationDialog(senderUid: String, nickname: String) {
        AlertDialog.Builder(this)
            .setTitle("친구 요청 수락")
            .setMessage("${nickname} 님을 친구로 추가하시겠습니까?")
            .setPositiveButton("수락") { _, _ ->
                acceptFriendRequest(senderUid, nickname)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 친구 요청 승인 처리 (FriendRepository의 acceptFriendRequest 사용)
     */
    private fun acceptFriendRequest(senderUid: String, nickname: String) {
        progressDialog.setMessage("친구 요청 수락 중...")
        progressDialog.show()

        lifecycleScope.launch {
            val success = friendRepository.acceptFriendRequest(senderUid)
            progressDialog.dismiss()

            if (success) {
                Toast.makeText(this@MainActivity, "${nickname} 님과 친구가 되었습니다! 🎉", Toast.LENGTH_LONG).show()
                updateNotificationBadge() // ⭐ NEW: 수락 후 배지 갱신
            } else {
                Toast.makeText(this@MainActivity, "친구 요청 수락 실패.", Toast.LENGTH_LONG).show()
            }
        }
    }


    // =========================================================================
    // ⭐ 딥링크 처리 메서드 영역 ⭐
    // =========================================================================

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val currentUser = auth.currentUser

        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data

            if (uri != null && uri.host == HOSTING_DOMAIN && uri.path?.startsWith("/join") == true) {

                val groupId = uri.getQueryParameter("groupId")

                if (groupId != null) {
                    Toast.makeText(this, "그룹 초대 링크를 확인했습니다. (정보 로딩 중)", Toast.LENGTH_SHORT).show()
                    showJoinConfirmation(groupId)
                } else {
                    Log.e("MAIN", "Deep link is missing groupId parameter.")
                    Snackbar.make(groupListContainer, "초대 링크가 유효하지 않습니다. (ID 누락)", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 그룹 정보를 로드하여 사용자에게 참여 여부를 묻는 AlertDialog를 표시합니다.
     */
    private fun showJoinConfirmation(groupId: String) {
        progressDialog.setMessage("그룹 정보 확인 중...")
        progressDialog.show()

        lifecycleScope.launch {
            val group: Group? = groupRepository.getGroupById(groupId)
            progressDialog.dismiss()

            if (group != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("${group.groupName}에 참가할까요?")
                    .setMessage("그룹 '${group.groupName}'에 참여하여 모임 활동을 시작할 수 있습니다. (현재 멤버 ${group.memberUids.size}명)")
                    .setPositiveButton("참가하기") { _, _ ->
                        joinGroup(groupId)
                    }
                    .setNegativeButton("취소하기", null)
                    .show()
            } else {
                Toast.makeText(this@MainActivity, "초대된 그룹 정보를 찾을 수 없거나 이미 삭제된 그룹입니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 그룹에 참여하고 GroupDetailActivity로 이동합니다.
     */
    private fun joinGroup(groupId: String) {
        progressDialog.setMessage("그룹에 참여 중...")
        progressDialog.show()

        lifecycleScope.launch {
            val success = groupRepository.joinGroup(groupId)
            progressDialog.dismiss()

            if (success) {
                Toast.makeText(this@MainActivity, "그룹 참여 성공! 그룹 상세 화면으로 이동합니다.", Toast.LENGTH_LONG).show()
                val intent = Intent(this@MainActivity, GroupDetailActivity::class.java).apply {
                    putExtra("GROUP_ID", groupId)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this@MainActivity, "그룹 참여에 실패했습니다. (이미 참여했거나 오류)", Toast.LENGTH_LONG).show()
            }
            loadGroups()
        }
    }

    // =========================================================================
    // ⭐ 그룹 목록 로딩 메서드 영역 ⭐
    // =========================================================================

    /**
     * Firestore에서 사용자가 참여 중인 그룹 목록을 로드하고 UI를 업데이트합니다.
     */
    private fun loadGroups() {
        lifecycleScope.launch {
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

    /**
     * 로드된 그룹 데이터를 기반으로 동적 View를 생성하여 목록 컨테이너에 추가합니다.
     */
    private fun displayGroups(groups: List<Group>) {
        groupListContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        groups.forEach { group ->
            // item_group_card 레이아웃 사용 (XML은 직접 생성해야 함)
            val groupView = inflater.inflate(R.layout.item_group_card, groupListContainer, false) as LinearLayout

            // View ID 참조 (item_group_card.xml에 정의된 ID 사용)
            val groupNameText = groupView.findViewById<TextView>(R.id.group_card_name)
            val memberCountText = groupView.findViewById<TextView>(R.id.group_card_members)

            groupNameText.text = group.groupName
            memberCountText.text = "${group.memberUids.size}명 참여 중"

            // 그룹 클릭 시 상세 화면으로 이동 (그룹 ID 전달)
            groupView.setOnClickListener {
                val intent = Intent(this, GroupDetailActivity::class.java).apply {
                    putExtra("GROUP_ID", group.id) // Group ID (Document ID) 전달
                    putExtra("GROUP_NAME", group.groupName)
                }
                startActivity(intent)
            }

            groupListContainer.addView(groupView)
        }
    }
}