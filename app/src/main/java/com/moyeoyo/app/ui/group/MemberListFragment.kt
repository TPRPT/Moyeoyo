package com.moyeoyo.app.ui.group

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.R
import kotlinx.coroutines.launch
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop


class MemberListFragment : Fragment() {

    private lateinit var progressVote: ProgressBar
    private lateinit var tvVoteCount: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnInviteMember: LinearLayout

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var groupId: String? = null
    private var groupName: String? = null

    private val memberList = mutableListOf<Member>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_member_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        progressVote = view.findViewById(R.id.progressVote)
        tvVoteCount = view.findViewById(R.id.tvVoteCount)
        recyclerView = view.findViewById(R.id.recyclerMemberList)
        btnInviteMember = view.findViewById(R.id.btnInviteMember)

        // ➤ 그룹 정보 받기
        groupId = arguments?.getString("groupId")
        groupName = arguments?.getString("groupName")

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = MemberAdapter(memberList)

        // ➤ 초대 버튼 클릭 → InviteGroupFragment로 이동
        btnInviteMember.setOnClickListener {
            if (groupId != null && groupName != null) {
                val action = GroupDetailFragmentDirections
                    .actionGroupDetailFragmentToInviteGroupFragment(
                        groupId!!,
                        groupName!!,
                        emptyArray()   // ⭐ 친구 선택 없이 초대 화면 이동
                    )
                findNavController().navigate(action)
            } else {
                Toast.makeText(requireContext(), "그룹 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // ➤ Firestore 멤버 목록 로드
        loadMembersFromFirestore()
    }

    /**
     * Firestore에서 그룹 멤버 목록 + 투표 현황 불러오기
     */
    private fun loadMembersFromFirestore() {
        val gid = groupId ?: return

        lifecycleScope.launch {
            firestore.collection("groups")
                .document(gid)
                .addSnapshotListener { snapshot, e ->
                    if (e != null || snapshot == null) {
                        tvVoteCount.text = "데이터 로드 실패"
                        return@addSnapshotListener
                    }

                    memberList.clear()

                    // ➤ 멤버 UID 리스트
                    val memberUids = snapshot.get("memberUids") as? List<String> ?: emptyList()

                    // ➤ 투표 완료 UID 리스트
                    val votedUids = snapshot.get("votedMembers") as? List<String> ?: emptyList()

                    // ➤ 각 멤버의 실제 정보 Firestore에서 가져오기
                    memberUids.forEach { uid ->

                        firestore.collection("users")
                            .document(uid)
                            .get()
                            .addOnSuccessListener { userDoc ->

                                val isMe = uid == auth.currentUser?.uid

                                val name = if (isMe)
                                    "나"
                                else
                                    userDoc.getString("name") ?: "이름 없음"

                                val region = userDoc.getString("region") ?: "주소 미정"
                                val profileUrl = userDoc.getString("profileImageUrl") // ★ 핵심!
                                val voted = votedUids.contains(uid)

                                // ➤ Member 데이터 모델 새 구조로 추가
                                memberList.add(
                                    Member(
                                        name = name,
                                        region = region,
                                        voted = voted,
                                        profileImageUrl = profileUrl
                                    )
                                )

                                recyclerView.adapter?.notifyDataSetChanged()
                            }
                    }

                    // ➤ 바깥쪽 progress 표시만 먼저 업데이트
                    updateVoteProgress(memberUids.size, votedUids.size)
                }
        }
    }


    /**
     * 투표 현황 계산 및 표시
     */
    private fun updateVoteProgress(totalMembers: Int, votedMembers: Int) {
        val notVotedMembers = totalMembers - votedMembers
        val progressPercent =
            if (totalMembers > 0) ((votedMembers.toFloat() / totalMembers) * 100).toInt() else 0

        progressVote.max = 100
        progressVote.progress = progressPercent
        tvVoteCount.text = "${votedMembers}명 완료 / ${notVotedMembers}명 미완료"
    }

    // ===============================
    // RecyclerView 어댑터
    // ===============================

    data class Member(
        val name: String,
        val region: String,
        val voted: Boolean,
        val profileImageUrl: String? = null
    )

    inner class MemberAdapter(private val members: List<Member>) :
        RecyclerView.Adapter<MemberAdapter.MemberViewHolder>() {

        inner class MemberViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvInitial: TextView = view.findViewById(R.id.tvMemberInitial)
            val tvName: TextView = view.findViewById(R.id.tvMemberName)
            val tvRegion: TextView = view.findViewById(R.id.tvMemberRegion)
            val tvStatus: TextView = view.findViewById(R.id.tvVoteStatusBadge)
            val ivProfile: ImageView = view.findViewById(R.id.ivProfile)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_member_status, parent, false)
            return MemberViewHolder(view)
        }

        override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
            val member = members[position]

            val profileUrl = member.profileImageUrl   // 🔥 이 필드 추가해야 함
            val initial = member.name.first().toString()

            if (!profileUrl.isNullOrEmpty()) {
                holder.ivProfile.visibility = View.VISIBLE
                holder.tvInitial.visibility = View.GONE

                Glide.with(holder.itemView)
                    .load(profileUrl)
                    .transform(CircleCrop())
                    .into(holder.ivProfile)
            } else {
                holder.ivProfile.visibility = View.GONE
                holder.tvInitial.visibility = View.VISIBLE
                holder.tvInitial.text = initial
            }

            holder.tvName.text = member.name
            holder.tvRegion.text = member.region

            // 투표 상태 UI
            if (member.voted) {
                holder.tvStatus.text = "투표 완료"
                holder.tvStatus.setBackgroundResource(R.drawable.bg_badge_green)
                holder.tvStatus.setTextColor(resources.getColor(R.color.badge_green_text, null))
            } else {
                holder.tvStatus.text = "미완료"
                holder.tvStatus.setBackgroundResource(R.drawable.bg_light_gray_outline_box)
                holder.tvStatus.setTextColor(resources.getColor(R.color.badge_gray_text, null))
            }
        }

        override fun getItemCount(): Int = members.size
    }
}
