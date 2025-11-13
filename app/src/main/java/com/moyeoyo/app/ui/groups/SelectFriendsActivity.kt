// com.moyeoyo.app.ui.groups/SelectFriendsActivity.kt
package com.moyeoyo.app.ui.groups

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.FriendRepository
import kotlinx.coroutines.launch

class SelectFriendsActivity : AppCompatActivity() {

    private val friendRepository = FriendRepository()
    private val selectedUids = mutableSetOf<String>()

    companion object {
        const val EXTRA_SELECTED_UIDS = "extra_selected_uids"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // R.layout.activity_select_friends 사용을 가정합니다.
        setContentView(R.layout.activity_select_friends)

        val friendsContainer = findViewById<LinearLayout>(R.id.friends_checkbox_container)
        val btnComplete = findViewById<Button>(R.id.btn_complete_selection)

        loadFriends(friendsContainer)

        btnComplete.setOnClickListener {
            // 결과를 CreateGroupActivity로 반환
            val resultIntent = Intent().apply {
                // 선택된 UID 목록을 ArrayList로 변환하여 전달
                putStringArrayListExtra(EXTRA_SELECTED_UIDS, ArrayList(selectedUids))
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun loadFriends(container: LinearLayout) {
        container.removeAllViews()

        lifecycleScope.launch {
            val friendUids = friendRepository.getFriendUids()

            if (friendUids.isEmpty()) {
                val textView = TextView(this@SelectFriendsActivity).apply {
                    text = "친구 목록이 비어 있습니다. 먼저 친구를 추가해주세요."
                    setPadding(16, 16, 16, 16)
                }
                container.addView(textView)
                return@launch
            }

            friendUids.forEach { uid ->
                val nickname = friendRepository.getUserNickname(uid)

                // 체크박스 항목 동적 생성
                val checkBox = CheckBox(this@SelectFriendsActivity).apply {
                    text = nickname ?: uid.take(8)
                    tag = uid
                    textSize = 18f
                    setPadding(16, 16, 16, 16)

                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            selectedUids.add(uid)
                        } else {
                            selectedUids.remove(uid)
                        }
                    }
                }
                container.addView(checkBox)
            }
        }
    }
}