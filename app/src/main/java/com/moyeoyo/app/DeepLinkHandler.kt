package com.moyeoyo.app

import android.content.Intent
import android.os.Bundle
import androidx.navigation.NavController
import com.moyeoyo.app.R

class DeepLinkHandler(private val navController: NavController?) {

    fun handle(intent: Intent?) {
        if (intent == null) return

        val fromWidget = intent.getBooleanExtra("from_widget", false)
        if (!fromWidget) return  // 위젯이 보낸 딥링크일 때만 처리

        val groupId = intent.getStringExtra("widget_group_id") ?: return
        val groupName = intent.getStringExtra("widget_group_name") ?: "모임"

        // Navigation을 사용하여 GroupDetailFragment로 이동
        navController?.let { nav ->
            val bundle = Bundle().apply {
                putString("groupId", groupId)
                putString("groupName", groupName)
            }
            // MainFragment로 먼저 이동 (백스택에 없을 수 있음)
            nav.navigate(R.id.mainFragment)
            // MainFragment에서 GroupDetailFragment로 이동하는 action 사용
            nav.navigate(R.id.action_mainFragment_to_groupDetailFragment, bundle)
        }
    }
}
