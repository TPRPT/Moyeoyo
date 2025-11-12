package com.moyeoyo.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog // ⭐ AlertDialog 사용을 위해 import
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.ui.auth.LoginActivity
import com.moyeoyo.app.ui.auth.ProfileSetupActivity
import com.moyeoyo.app.ui.groups.CreateGroupActivity
import com.moyeoyo.app.ui.groups.GroupDetailActivity
import kotlinx.coroutines.launch
import android.app.ProgressDialog // ProgressDialog import 유지


class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var progressDialog: ProgressDialog

    private val groupRepository = GroupRepository()

    // ⭐ NEW: GroupInviteActivity에서 정의된 도메인과 일치해야 합니다.
    private val HOSTING_DOMAIN = "moyeoyo-57ac0.web.app"

    // View 변수 선언
    private lateinit var profileImage: ImageView
    private lateinit var textNickname: TextView
    private lateinit var textEmail: TextView
    private lateinit var btnLogout: Button
    private lateinit var btnCreateGroup: Button

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

        // 🔹 앱이 처음 시작될 때 딥링크 확인
        handleIntent(intent)

        // 💡 사용자 정보 로딩을 별도 함수로 호출
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
    }

    /**
     * ⭐ NEW: 사용자 프로필 정보를 Firestore에서 로드하고 UI를 업데이트합니다.
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

                    // 홈 위치 정보 로드 (Map 필드 접근)
                    val homeLocationMap = doc.get("homeLocation") as? Map<*, *>

                    var homeAddressDisplay: String? = null

                    if (homeLocationMap != null) {
                        homeAddressDisplay = homeLocationMap["addressName"] as? String
                        if (homeAddressDisplay.isNullOrEmpty()) {
                            homeAddressDisplay = homeLocationMap["name"] as? String
                        }

                        val geoPoint = homeLocationMap["latLng"] as? GeoPoint
                        if (geoPoint != null) {
                            Log.d("MAIN", "Loaded GeoPoint: Lat=${geoPoint.latitude}, Lng=${geoPoint.longitude}")
                        }
                    }

                    // ⭐ UI 업데이트 시 주소 표시
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


    // ⭐ Activity가 재개될 때마다 목록과 프로필을 새로고침
    override fun onResume() {
        super.onResume()
        if (auth.currentUser != null) {
            loadUserProfile()
            loadGroups()
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

        // 로그인이 안 되어 있다면 처리 중단 및 로그인 화면으로 이동
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data

            // ⭐ 딥링크 호스트와 경로 검증
            if (uri != null && uri.host == HOSTING_DOMAIN && uri.path?.startsWith("/join") == true) {

                val groupId = uri.getQueryParameter("groupId")

                if (groupId != null) {
                    Toast.makeText(this, "그룹 초대 링크를 확인했습니다. (정보 로딩 중)", Toast.LENGTH_SHORT).show()
                    showJoinConfirmation(groupId) // ⭐ 변경: 바로 참여 대신 확인 모달 호출
                } else {
                    Log.e("MAIN", "Deep link is missing groupId parameter.")
                    Snackbar.make(groupListContainer, "초대 링크가 유효하지 않습니다. (ID 누락)", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * ⭐ NEW: 그룹 정보를 로드하여 사용자에게 참여 여부를 묻는 AlertDialog를 표시합니다.
     */
    private fun showJoinConfirmation(groupId: String) {
        progressDialog.setMessage("그룹 정보 확인 중...")
        progressDialog.show()

        lifecycleScope.launch {
            // GroupRepository의 getGroupById 함수를 사용
            val group: Group? = groupRepository.getGroupById(groupId)
            progressDialog.dismiss()

            if (group != null) {
                // 💡 AlertDialog를 띄워 그룹명과 함께 사용자에게 참여 의사를 묻습니다.
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("${group.groupName}에 참가할까요?")
                    .setMessage("그룹 '${group.groupName}'에 참여하여 모임 활동을 시작할 수 있습니다. (현재 멤버 ${group.memberUids.size}명)")
                    .setPositiveButton("참가하기") { _, _ ->
                        // '참가하기' 클릭 시 그룹 참여 로직 실행
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
     * ⭐ MODIFIED: 그룹에 참여하고 GroupDetailActivity로 이동합니다.
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
                    // GroupDetailActivity에서 필요하다면 groupName도 전달 가능
                    // flags는 여기서는 제거해도 무방하나, 스택 정리가 목적이라면 유지
                }
                startActivity(intent)
            } else {
                Toast.makeText(this@MainActivity, "그룹 참여에 실패했습니다. (이미 참여했거나 오류)", Toast.LENGTH_LONG).show()
            }
            loadGroups() // 그룹 목록을 갱신합니다.
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