package com.moyeoyo.app.ui.friend

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.moyeoyo.app.R
import com.moyeoyo.app.model.Friend

class FriendSearchFragment : Fragment(R.layout.fragment_friends_search) {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private lateinit var adapter: FriendListAdapter
    private lateinit var rvFriendList: RecyclerView
    private lateinit var tvFriendCount: TextView
    private lateinit var etFriendEmail: EditText
    private lateinit var btnSearch: Button

    // 검색 결과 카드 뷰들
    private lateinit var cardSearchResult: CardView
    private lateinit var tvResultInitial: TextView
    private lateinit var tvResultName: TextView
    private lateinit var tvResultEmail: TextView
    private lateinit var btnSendRequest: MaterialButton

    // 친구 요청 수락 감시용 리스너
    private var requestAcceptedListener: ListenerRegistration? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 툴바 뒤로가기
        val toolbar = view.findViewById<Toolbar>(R.id.toolbarFriend)
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        tvFriendCount = view.findViewById(R.id.tvFriendCount)
        etFriendEmail = view.findViewById(R.id.etFriendEmail)
        btnSearch = view.findViewById(R.id.btnSearch)
        rvFriendList = view.findViewById(R.id.rvFriendList)

        // 검색 결과 카드
        cardSearchResult = view.findViewById(R.id.cardSearchResult)
        tvResultInitial = view.findViewById(R.id.tvInitial)
        tvResultName = view.findViewById(R.id.tvName)
        tvResultEmail = view.findViewById(R.id.tvEmail)
        btnSendRequest = view.findViewById(R.id.btnSendRequest)

        rvFriendList.layoutManager = LinearLayoutManager(requireContext())
        adapter = FriendListAdapter(mutableListOf()) { friend ->
            deleteFriend(friend)
        }
        rvFriendList.adapter = adapter

        btnSearch.setOnClickListener {
            val email = etFriendEmail.text.toString().trim()
            if (email.isEmpty()) {
                Toast.makeText(requireContext(), "이메일을 입력하세요.", Toast.LENGTH_SHORT).show()
            } else {
                searchUserByEmail(email)
            }
        }

        loadFriends()
        startRequestAcceptedListener()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 리스너 정리
        requestAcceptedListener?.remove()
    }

    /* ---------------------------
       1. 친구 목록 불러오기
       --------------------------- */
    private fun loadFriends() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(requireContext(), "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        firestore.collection("users")
            .document(currentUser.uid)
            .collection("friends")
            .get()
            .addOnSuccessListener { result ->
                val friends = result.documents.map { doc ->
                    Friend(
                        uid = doc.getString("uid") ?: doc.id,
                        name = doc.getString("name") ?: "",
                        email = doc.getString("email") ?: ""
                    )
                }

                adapter.submitList(friends)
                tvFriendCount.text = "${friends.size}명"
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "친구 목록을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
    }

    /* ---------------------------
       2. 이메일로 사용자 검색
       --------------------------- */
    private fun searchUserByEmail(email: String) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(requireContext(), "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        if (email == currentUser.email) {
            Toast.makeText(requireContext(), "본인은 친구로 추가할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        firestore.collection("users")
            .whereEqualTo("email", email)
            .get()
            .addOnSuccessListener { result ->
                if (result.isEmpty) {
                    cardSearchResult.visibility = View.GONE
                    Toast.makeText(requireContext(), "해당 이메일의 사용자를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val doc = result.documents[0]
                val friendUid = doc.id
                val friendName = doc.getString("nickname") ?: email.substringBefore("@")

                // 이미 친구인지 체크
                firestore.collection("users")
                    .document(currentUser.uid)
                    .collection("friends")
                    .document(friendUid)
                    .get()
                    .addOnSuccessListener { friendDoc ->
                        // 검색 결과 카드 채우기
                        tvResultName.text = friendName
                        tvResultEmail.text = email
                        tvResultInitial.text = friendName.firstOrNull()?.toString() ?: "?"

                        cardSearchResult.visibility = View.VISIBLE

                        if (friendDoc.exists()) {
                            // 이미 친구인 경우 버튼 비활성화
                            setRequestButtonAsAlreadyFriend()
                        } else {
                            // 친구 요청 가능 상태
                            setRequestButtonAsNormal()
                            btnSendRequest.setOnClickListener {
                                sendFriendRequest(
                                    targetUid = friendUid,
                                    targetName = friendName,
                                    targetEmail = email
                                )
                            }
                        }
                    }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "사용자 검색에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
    }

    /* ---------------------------
       3. 친구 요청 전송
       --------------------------- */
    private fun sendFriendRequest(targetUid: String, targetName: String, targetEmail: String) {
        val currentUser = auth.currentUser ?: return
        val myUid = currentUser.uid
        val myEmail = currentUser.email ?: ""
        val myName = currentUser.displayName ?: myEmail.substringBefore("@")

        // 내가 상대에게 보낸 요청 정보
        val sentData = mapOf(
            "uid" to targetUid,
            "name" to targetName,
            "email" to targetEmail,
            "status" to "pending"
        )

        // 상대가 받은 요청 정보
        val receivedData = mapOf(
            "uid" to myUid,
            "name" to myName,
            "email" to myEmail,
            "status" to "pending"
        )

        // 내가 보낸 요청 컬렉션에 저장
        firestore.collection("users")
            .document(myUid)
            .collection("friendRequests_sent")
            .document(targetUid)
            .set(sentData)

        // 상대가 받은 요청 컬렉션에 저장
        firestore.collection("users")
            .document(targetUid)
            .collection("friendRequests_received")
            .document(myUid)
            .set(receivedData)
            .addOnSuccessListener {
                setRequestButtonAsSent()

                Snackbar.make(
                    requireView(),
                    "$targetEmail 에게 친구 요청을 보냈습니다",
                    Snackbar.LENGTH_LONG
                ).show()
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "친구 요청 전송에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
    }

    /* ---------------------------
       4. 내 요청이 수락됐는지 감시
       --------------------------- */
    private fun startRequestAcceptedListener() {
        val currentUser = auth.currentUser ?: return
        val myUid = currentUser.uid

        requestAcceptedListener = firestore.collection("users")
            .document(myUid)
            .collection("friendRequests_sent")
            .whereEqualTo("status", "accepted")
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener

                for (doc in snapshots.documents) {
                    val friendName = doc.getString("name") ?: "친구"
                    Snackbar.make(
                        requireView(),
                        "${friendName}님이 친구 요청을 수락했습니다!",
                        Snackbar.LENGTH_LONG
                    ).show()

                    // 친구 목록 새로고침
                    loadFriends()

                    // 한 번 알림 후 요청 문서 삭제 (중복 팝업 방지)
                    firestore.collection("users")
                        .document(myUid)
                        .collection("friendRequests_sent")
                        .document(doc.id)
                        .delete()
                }
            }
    }

    /* ---------------------------
       5. 친구 삭제
       --------------------------- */
    private fun deleteFriend(friend: Friend) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(requireContext(), "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        firestore.collection("users")
            .document(currentUser.uid)
            .collection("friends")
            .document(friend.uid)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "친구가 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                loadFriends()
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "친구 삭제에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
    }

    /* ---------------------------
       6. 버튼 상태 변경 헬퍼
       --------------------------- */

    // 아직 요청 보내기 전: "친구 요청" (검은 배경, 흰 글씨)
    private fun setRequestButtonAsNormal() {
        btnSendRequest.apply {
            text = "친구 요청"
            setBackgroundColor(Color.parseColor("#000000"))
            setTextColor(Color.WHITE)
            isEnabled = true
            icon = resources.getDrawable(R.drawable.ic_person_add_24, null)
            iconTint = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
    }

    // 요청 보낸 후: "요청 전송됨" (연회색 배경, 회색 글씨, 비활성화)
    private fun setRequestButtonAsSent() {
        btnSendRequest.apply {
            text = "요청 전송됨"
            setBackgroundColor(Color.parseColor("#F5F5F7"))
            setTextColor(Color.parseColor("#666666"))
            isEnabled = false
            icon = resources.getDrawable(R.drawable.ic_time_24, null)
            iconTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#666666"))
        }
    }

    // 이미 친구인 상태: "이미 친구" 비활성
    private fun setRequestButtonAsAlreadyFriend() {
        btnSendRequest.apply {
            text = "이미 친구"
            setBackgroundColor(Color.parseColor("#F5F5F7"))
            setTextColor(Color.parseColor("#666666"))
            isEnabled = false
            icon = null
        }
    }
}
