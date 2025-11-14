package com.moyeoyo.app.ui.main

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.R
import com.moyeoyo.app.model.GroupUi

/**
 * 메인 화면 (내 그룹 리스트 + 프로필 정보 + AppBar)
 */
class MainFragment : Fragment(R.layout.fragment_main) {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            // 로그인 안 되어 있으면 로그인 화면으로 보내기
            findNavController().navigate(R.id.action_mainFragment_to_loginFragment)
            return
        }

        /** -------------------------------
         * ✅ 1. AppBar (툴바)
         * ------------------------------- */
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_notifications -> {
                    findNavController().navigate(R.id.action_mainFragment_to_notificationFragment)
                    true
                }
                R.id.action_settings -> {
                    Toast.makeText(requireContext(), "설정 클릭됨", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }

        /** -------------------------------
         * ✅ 2. 프로필 카드
         * ------------------------------- */
        val cardProfile = view.findViewById<CardView>(R.id.cardProfile)
        val btnLogout = view.findViewById<Button>(R.id.btnLogout)
        val tvName = view.findViewById<TextView>(R.id.tvName)
        val tvEmail = view.findViewById<TextView>(R.id.tvEmail)
        val imgProfile = view.findViewById<ImageView>(R.id.imgProfile)

        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(requireContext(), "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
            findNavController().navigate(R.id.action_mainFragment_to_loginFragment)
            return
        }

        // Firestore 사용자 정보 로드
        firestore.collection("users").document(currentUser.uid)
            .get()
            .addOnSuccessListener { doc ->
                val nickname = doc.getString("nickname") ?: "닉네임 없음"
                val homeLocation =
                    (doc.get("homeLocation") as? Map<*, *>)?.get("address") ?: "주소 미설정"
                val photoUrl = doc.getString("photoUrl")

                tvName.text = "$nickname ($homeLocation)"
                tvEmail.text = currentUser.email ?: "이메일 없음"

                if (!photoUrl.isNullOrEmpty()) {
                    Glide.with(requireContext())
                        .load(photoUrl)
                        .placeholder(R.drawable.ic_user_placeholder)
                        .circleCrop()
                        .into(imgProfile)
                } else {
                    imgProfile.setImageResource(R.drawable.ic_user_placeholder)
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "프로필 정보를 불러오지 못했습니다.", Toast.LENGTH_SHORT)
                    .show()
            }

        // 프로필 카드 클릭 → 프로필 설정 페이지 이동
        cardProfile.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_profileSetupFragment)
        }

        // 로그아웃
        btnLogout.setOnClickListener {
            auth.signOut()
            Toast.makeText(requireContext(), "로그아웃 되었습니다.", Toast.LENGTH_SHORT).show()
            findNavController().navigate(R.id.action_mainFragment_to_loginFragment)
        }

        val btnAddFriend = view.findViewById<MaterialButton>(R.id.btnAddFriend)
        btnAddFriend.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_friendSearchFragment)
        }

        /** -------------------------------
         * ✅ 3. 새 그룹 만들기 버튼
         * ------------------------------- */
        val btnNewGroup = view.findViewById<Button>(R.id.btnNewGroup)
        btnNewGroup.setOnClickListener {
            findNavController().navigate(R.id.action_mainFragment_to_createGroupFragment)
        }

        /** -------------------------------
         * ✅ 4. 그룹 목록 로딩 (Firestore)
         * ------------------------------- */
        val recyclerView = view.findViewById<RecyclerView>(R.id.groupRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        firestore.collection("groups")
            .whereArrayContains("memberUids", currentUser.uid)
            .get()
            .addOnSuccessListener { result ->
                val groupUis = result.documents.map { doc ->
                    GroupUi(
                        id = doc.id,
                        name = doc.getString("groupName") ?: "이름 없음",
                        memberCount = (doc.get("memberUids") as? List<*>)?.size ?: 0,
                        date = doc.getString("meetingDate"),
                        location = doc.getString("locationName"),
                        isVoting = (doc.getString("status") ?: "진행중").contains("투표"),
                        dDay = if ((doc.getString("status") ?: "").startsWith("D-"))
                            doc.getString("status") else null
                    )
                }

                val adapter = GroupListAdapter(groupUis) { clickedGroup ->
                    val args = bundleOf(
                        "groupId" to clickedGroup.id,
                        "groupName" to clickedGroup.name,
                        "memberCount" to clickedGroup.memberCount
                    )
                    findNavController().navigate(
                        R.id.action_mainFragment_to_groupDetailFragment,
                        args
                    )
                }
                recyclerView.adapter = adapter

                // 그룹 개수 텍스트
                val subTitle = view.findViewById<TextView>(R.id.subTitle)
                subTitle.text = "참여중인 그룹 ${groupUis.size}개"
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "그룹 목록을 불러오지 못했습니다.", Toast.LENGTH_SHORT)
                    .show()
            }
    }
}
