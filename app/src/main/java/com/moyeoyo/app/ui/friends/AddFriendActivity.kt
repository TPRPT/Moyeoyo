// com.moyeoyo.app.ui.friends.AddFriendActivity.kt
package com.moyeoyo.app.ui.friends

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
import androidx.appcompat.app.AlertDialog // AlertDialog 추가
import androidx.lifecycle.lifecycleScope
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.FriendRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

// ⭐ NOTE: R.color.black, R.color.design_default_color_error 등은 프로젝트에 정의되어 있어야 합니다.

@AndroidEntryPoint
class AddFriendActivity : AppCompatActivity() {

    @Inject
    lateinit var friendRepository: FriendRepository

    private lateinit var editTextEmail: EditText
    private lateinit var btnSearchFriend: Button
    private lateinit var textSearchResult: TextView
    private lateinit var btnSendRequest: Button
    private lateinit var friendListContainer: LinearLayout

    private var foundUserUid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_friend)

        editTextEmail = findViewById(R.id.edit_text_email)
        btnSearchFriend = findViewById(R.id.btn_search_friend)
        textSearchResult = findViewById(R.id.text_search_result)
        btnSendRequest = findViewById(R.id.btn_send_request)
        friendListContainer = findViewById(R.id.friend_list_container)

        btnSearchFriend.setOnClickListener {
            searchFriend()
        }

        btnSendRequest.setOnClickListener {
            foundUserUid?.let { uid ->
                sendFriendRequest(uid)
            }
        }

        loadAndDisplayFriends()
    }

    private fun searchFriend() {
        val email = editTextEmail.text.toString().trim()
        if (email.isEmpty()) {
            Toast.makeText(this, "이메일을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // UI 상태 초기화
        textSearchResult.visibility = View.GONE
        btnSendRequest.visibility = View.GONE
        foundUserUid = null

        lifecycleScope.launch {
            // FriendRepository의 findUserWithStatus 호출 (FriendRepository.kt에 구현된 것으로 가정)
            val (uid, resultStatus) = friendRepository.findUserWithStatus(email)

            if (uid != null) {
                // 검색 성공
                val nickname = friendRepository.getUserNickname(uid)
                foundUserUid = uid
                textSearchResult.text = "사용자 찾음: ${nickname ?: "알 수 없는 사용자"} ($email)"
                // textSearchResult.setTextColor(resources.getColor(R.color.black)) // 성공 메시지 색상
                textSearchResult.visibility = View.VISIBLE
                btnSendRequest.visibility = View.VISIBLE
            } else {
                // 검색 실패: 상태에 따른 상세 메시지 표시
                val message = when (resultStatus) {
                    "ALREADY_FRIEND" -> "이미 친구 목록에 추가된 사용자입니다."
                    "SELF" -> "본인 계정은 친구로 추가할 수 없습니다."
                    else -> "검색된 사용자가 없거나, 아직 가입하지 않은 사용자입니다." // "NOT_FOUND"
                }
                textSearchResult.text = "🚨 $message"
                // textSearchResult.setTextColor(resources.getColor(R.color.design_default_color_error)) // 실패 메시지 색상
                textSearchResult.visibility = View.VISIBLE
                btnSendRequest.visibility = View.GONE
            }
        }
    }

    private fun sendFriendRequest(receiverUid: String) {
        lifecycleScope.launch {
            val success = friendRepository.sendFriendRequest(receiverUid)

            if (success) {
                Toast.makeText(this@AddFriendActivity, "친구 요청을 보냈습니다!", Toast.LENGTH_LONG).show()
                // 요청 성공 후 액티비티를 종료하지 않고 목록을 새로고침 하거나, 이전 화면으로 돌아갈 수 있도록 처리
                // 여기서는 토스트만 띄우고 UI를 리셋합니다.
                editTextEmail.setText("")
                textSearchResult.visibility = View.GONE
                btnSendRequest.visibility = View.GONE
                // loadAndDisplayFriends() // 친구 목록 새로고침 (이 단계에서 친구가 추가되지는 않으므로 선택 사항)
            } else {
                Toast.makeText(this@AddFriendActivity, "친구 요청 실패. (자신에게 요청 불가/네트워크 오류 등)", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ⭐ MODIFIED: 친구 목록에 삭제 버튼 추가
    private fun loadAndDisplayFriends() {
        friendListContainer.removeAllViews()

        lifecycleScope.launch {
            val friendUids = friendRepository.getFriendUids()

            if (friendUids.isEmpty()) {
                val textView = TextView(this@AddFriendActivity).apply {
                    text = "아직 친구가 없습니다. 이메일로 친구를 추가해보세요!"
                    setPadding(0, 16, 0, 16)
                    textSize = 16f
                    // setTextColor(resources.getColor(R.color.material_dynamic_neutral_variant60)) // 임시 색상
                }
                friendListContainer.addView(textView)
                return@launch
            }

            friendUids.forEach { uid ->
                val nickname = friendRepository.getUserNickname(uid)

                // 각 친구 항목을 담을 LinearLayout 생성
                val friendItemView = LinearLayout(this@AddFriendActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 4 // 항목 간 간격
                        bottomMargin = 4
                    }
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(16, 12, 16, 12)
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }

                // 1. 닉네임 TextView
                val nameTextView = TextView(this@AddFriendActivity).apply {
                    text = "${nickname ?: uid.take(8)} (ID: ${uid.take(4)}...)"
                    textSize = 18f
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1.0f // 가중치를 주어 공간을 최대로 차지하게 함
                    )
                }

                // 2. 삭제 버튼
                val deleteButton = Button(this@AddFriendActivity).apply {
                    text = "삭제"
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = 16
                    }
                    // 버튼 클릭 리스너 설정
                    setOnClickListener {
                        showDeleteConfirmation(uid, nickname ?: uid.take(8))
                    }
                }

                friendItemView.addView(nameTextView)
                friendItemView.addView(deleteButton)

                friendListContainer.addView(friendItemView)
            }
        }
    }

    // ⭐ NEW: 삭제 확인 다이얼로그
    private fun showDeleteConfirmation(friendUid: String, nickname: String) {
        AlertDialog.Builder(this)
            .setTitle("친구 삭제")
            .setMessage("${nickname} 님을 친구 목록에서 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                deleteFriend(friendUid, nickname)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ⭐ NEW: 실제 친구 삭제 로직
    private fun deleteFriend(friendUid: String, nickname: String) {
        lifecycleScope.launch {
            val success = friendRepository.removeFriend(friendUid)

            if (success) {
                Toast.makeText(this@AddFriendActivity, "${nickname} 님을 친구 목록에서 삭제했습니다.", Toast.LENGTH_LONG).show()
                loadAndDisplayFriends() // 목록 갱신
            } else {
                Toast.makeText(this@AddFriendActivity, "친구 삭제에 실패했습니다. 네트워크를 확인해주세요.", Toast.LENGTH_LONG).show()
            }
        }
    }
}