package com.moyeoyo.app.ui.groups

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
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
                val userData = friendRepository.getUserProfile(uid)

                val nickname = userData?.get("nickname") as? String ?: uid.take(8)
                val profileImageUrl = userData?.get("profileImageUrl") as? String   // 구글 사진
                val photoUrl = userData?.get("photoUrl") as? String                 // 앱에서 설정한 사진

                // 🔥 앱에서 업로드한 photoUrl 우선 적용
                val imageUrl = photoUrl ?: profileImageUrl

                val itemView = inflater.inflate(R.layout.item_friend_card, container, false)

                val imgProfile = itemView.findViewById<ImageView>(R.id.imgProfile)
                val tvName = itemView.findViewById<TextView>(R.id.tvName)
                val tvUid = itemView.findViewById<TextView>(R.id.tvUid)
                val checkbox = itemView.findViewById<CheckBox>(R.id.checkboxSelect)

                // 🔥 Glide로 이미지 로딩
                Glide.with(this@SelectFriendsActivity)
                    .load(imageUrl)
                    .placeholder(R.drawable.default_user)
                    .circleCrop()
                    .into(imgProfile)

                tvName.text = nickname
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
