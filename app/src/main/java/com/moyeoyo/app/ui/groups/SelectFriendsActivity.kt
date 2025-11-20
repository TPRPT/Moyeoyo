package com.moyeoyo.app.ui.groups

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.FriendRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SelectFriendsActivity : AppCompatActivity() {

    @Inject
    lateinit var friendRepository: FriendRepository
    private val selectedUids = mutableSetOf<String>()

    companion object {
        const val EXTRA_SELECTED_UIDS = "extra_selected_uids"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_friends)

        val container = findViewById<LinearLayout>(R.id.friends_checkbox_container)
        val btnComplete = findViewById<Button>(R.id.btn_complete_selection)

        loadFriends(container)

        btnComplete.setOnClickListener {
            val resultIntent = Intent().apply {
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

            val inflater = LayoutInflater.from(this@SelectFriendsActivity)

            friendUids.forEach { uid ->
                val nickname = friendRepository.getUserNickname(uid)
                val itemView = inflater.inflate(R.layout.item_friend_card, container, false)

                val tvInitial = itemView.findViewById<TextView>(R.id.tvInitial)
                val tvName = itemView.findViewById<TextView>(R.id.tvName)
                val tvUid = itemView.findViewById<TextView>(R.id.tvUid)
                val checkbox = itemView.findViewById<CheckBox>(R.id.checkboxSelect)

                tvInitial.text = (nickname ?: uid.take(1)).uppercase()
                tvName.text = nickname ?: uid.take(8)
                tvUid.text = uid.take(8)

                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedUids.add(uid)
                    else selectedUids.remove(uid)
                }

                container.addView(itemView)
            }
        }
    }
}
