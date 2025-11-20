package com.moyeoyo.app.ui.friends

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.FriendRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AddFriendActivity : AppCompatActivity() {

    @Inject
    lateinit var friendRepository: FriendRepository

    private lateinit var editTextEmail: EditText
    private lateinit var btnSearchFriend: Button
    private lateinit var textSearchResult: TextView
    private lateinit var friendListContainer: LinearLayout
    private lateinit var btnInviteFriend: Button

    // ⭐ 카드뷰 요소
    private lateinit var cardSearchResult: View
    private lateinit var tvName: TextView
    private lateinit var tvEmail: TextView
    private lateinit var btnSendRequest: Button

    private var foundUserUid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_friend)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarFriend)
        toolbar.setNavigationOnClickListener { finish() }

        editTextEmail = findViewById(R.id.edit_text_email)
        btnSearchFriend = findViewById(R.id.btn_search_friend)
        textSearchResult = findViewById(R.id.text_search_result)
        friendListContainer = findViewById(R.id.friend_list_container)
        btnInviteFriend = findViewById(R.id.btn_invite_friend)

        // ⭐ 카드뷰 View 연결
        cardSearchResult = findViewById(R.id.cardSearchResult)
        tvName = findViewById(R.id.tvName)
        tvEmail = findViewById(R.id.tvEmail)
        btnSendRequest = findViewById(R.id.btn_send_request)

        // 처음에는 카드 숨김
        cardSearchResult.visibility = View.GONE

        btnSearchFriend.setOnClickListener { searchFriend() }
        btnSendRequest.setOnClickListener { foundUserUid?.let { uid -> sendFriendRequest(uid) } }
        btnInviteFriend.setOnClickListener { shareFriendInviteLink() }

        loadAndDisplayFriends()
    }

    // =====================================================
    // 🔥 NEW — 친구 초대 링크 생성 & 공유 기능
    // =====================================================
    private fun shareFriendInviteLink() {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Toast.makeText(this, "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val inviteUrl = "https://moyeoyo-57ac0.web.app/friend?uid=$myUid"

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Moyeoyo에서 나와 친구가 되어줘! 👋\n$inviteUrl")
            type = "text/plain"
        }

        startActivity(Intent.createChooser(sendIntent, "친구 초대 링크 공유"))
    }

    // =====================================================
    // 친구 검색 → 카드뷰 표시
    // =====================================================
    private fun searchFriend() {
        val email = editTextEmail.text.toString().trim()

        if (email.isEmpty()) {
            Toast.makeText(this, "이메일을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // 초기화
        textSearchResult.visibility = View.GONE
        cardSearchResult.visibility = View.GONE
        foundUserUid = null

        lifecycleScope.launch {
            val (uid, resultStatus) = friendRepository.findUserWithStatus(email)

            if (uid != null) {
                // 사용자 있음 → 카드뷰 표시
                val nickname = friendRepository.getUserNickname(uid)

                foundUserUid = uid
                tvName.text = nickname ?: "알 수 없음"
                tvEmail.text = email

                cardSearchResult.visibility = View.VISIBLE

            } else {
                // 사용자 없음 → 텍스트 메시지
                val message = when (resultStatus) {
                    "ALREADY_FRIEND" -> "이미 친구입니다."
                    "SELF" -> "본인은 친구로 추가할 수 없습니다."
                    else -> "사용자를 찾을 수 없습니다."
                }

                cardSearchResult.visibility = View.GONE
                textSearchResult.text = "🚨 $message"
                textSearchResult.visibility = View.VISIBLE
            }
        }
    }

    // =====================================================
    // 친구 요청 전송
    // =====================================================
    private fun sendFriendRequest(receiverUid: String) {
        lifecycleScope.launch {
            val success = friendRepository.sendFriendRequest(receiverUid)

            if (success) {
                Toast.makeText(this@AddFriendActivity, "친구 요청을 보냈습니다!", Toast.LENGTH_LONG).show()

                // UI 초기화
                editTextEmail.setText("")
                //cardSearchResult.visibility = View.GONE
                //textSearchResult.visibility = View.GONE

                btnSendRequest.isEnabled = false
                btnSendRequest.backgroundTintList = getColorStateList(R.color.gray_300)
                btnSendRequest.text = "요청 완료"
            } else {
                Toast.makeText(this@AddFriendActivity, "친구 요청 실패", Toast.LENGTH_LONG).show()
            }
        }
    }

    // =====================================================
    // 친구 목록 로드 및 삭제 버튼
    // =====================================================
    private fun loadAndDisplayFriends() {
        friendListContainer.removeAllViews()

        lifecycleScope.launch {
            val friendUids = friendRepository.getFriendUids()

            if (friendUids.isEmpty()) {
                val textView = TextView(this@AddFriendActivity).apply {
                    text = "아직 친구가 없습니다. 이메일로 추가하거나 초대 링크를 공유해보세요!"
                    setPadding(0, 16, 0, 16)
                    textSize = 16f
                }
                friendListContainer.addView(textView)
                return@launch
            }

            friendUids.forEach { uid ->
                val nickname = friendRepository.getUserNickname(uid)

                val friendItemView = LinearLayout(this@AddFriendActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 6
                        bottomMargin = 6
                    }
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(16, 12, 16, 12)
                }

                val nameTextView = TextView(this@AddFriendActivity).apply {
                    text = "${nickname ?: uid.take(8)} (ID: ${uid.take(4)}...)"
                    textSize = 17f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val deleteButton = Button(this@AddFriendActivity).apply {
                    text = "삭제"
                    textSize = 12f
                    setOnClickListener { showDeleteConfirmation(uid, nickname ?: uid.take(8)) }
                }

                friendItemView.addView(nameTextView)
                friendItemView.addView(deleteButton)
                friendListContainer.addView(friendItemView)
            }
        }
    }

    // 삭제 팝업
    private fun showDeleteConfirmation(friendUid: String, nickname: String) {
        AlertDialog.Builder(this)
            .setTitle("친구 삭제")
            .setMessage("$nickname 님을 친구 목록에서 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ -> deleteFriend(friendUid, nickname) }
            .setNegativeButton("취소", null)
            .show()
    }

    // 삭제 실행
    private fun deleteFriend(friendUid: String, nickname: String) {
        lifecycleScope.launch {
            val success = friendRepository.removeFriend(friendUid)

            if (success) {
                Toast.makeText(this@AddFriendActivity, "$nickname 님을 삭제했습니다.", Toast.LENGTH_LONG).show()
                loadAndDisplayFriends()
            } else {
                Toast.makeText(this@AddFriendActivity, "삭제 실패", Toast.LENGTH_LONG).show()
            }
        }
    }
}
