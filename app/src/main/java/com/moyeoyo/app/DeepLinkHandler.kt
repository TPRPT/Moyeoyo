package com.moyeoyo.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.moyeoyo.app.ui.groups.GroupDetailActivity

class DeepLinkHandler(private val activity: AppCompatActivity) {

    fun handle(intent: Intent?) {
        if (intent == null) return

        val fromWidget = intent.getBooleanExtra("from_widget", false)
        if (!fromWidget) return  // 위젯이 보낸 딥링크일 때만 처리

        val groupId = intent.getStringExtra("widget_group_id") ?: return
        val groupName = intent.getStringExtra("widget_group_name") ?: "모임"

        // 🔥 Fragment가 아니라 Activity로 이동!!!
        val detailIntent = Intent(activity, GroupDetailActivity::class.java).apply {
            putExtra("GROUP_ID", groupId)
            putExtra("GROUP_NAME", groupName)
        }

        activity.startActivity(detailIntent)
    }
}
