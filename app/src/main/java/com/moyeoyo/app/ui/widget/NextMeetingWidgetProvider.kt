package com.moyeoyo.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.moyeoyo.app.R
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class NextMeetingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)

        CoroutineScope(Dispatchers.IO).launch {
            appWidgetIds.forEach { id ->
                updateWidget(context, appWidgetManager, id)
            }
        }
    }


    companion object {

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {

            val views = RemoteViews(context.packageName, R.layout.widget_next_meeting)

            // 기본 표시값
            views.setTextViewText(R.id.tvWidgetTitle, "다가오는 모임")
            views.setTextViewText(R.id.tvWidgetGroupName, "")
            views.setTextViewText(R.id.tvWidgetDate, "모임 정보를 불러오는 중…")
            views.setTextViewText(R.id.tvWidgetTime, "")
            views.setTextViewText(R.id.tvWidgetPlace, "")

            // 숨겨야 할 뷰들
            views.setViewVisibility(R.id.iconDate, View.GONE)
            views.setViewVisibility(R.id.iconTime, View.GONE)
            views.setViewVisibility(R.id.iconPlace, View.GONE)
            views.setViewVisibility(R.id.tvWidgetTime, View.GONE)
            views.setViewVisibility(R.id.tvWidgetPlace, View.GONE)

            // 🔥 PendingIntent 캐싱 문제 해결 → requestCode를 항상 다르게 생성
            val uniqueRequestCode = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

            // 기본 클릭 → 앱 실행
            val baseIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val basePendingIntent = PendingIntent.getActivity(
                context,
                uniqueRequestCode, // ← 핵심!
                baseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widget_root, basePendingIntent)

            // -------------------------------
            // 🔥 Room DB 읽기 (가장 가까운 미래 모임)
            // -------------------------------
            CoroutineScope(Dispatchers.IO).launch {

                val dao = AppDatabase.getInstance(context).nextMeetingDao()
                val now = System.currentTimeMillis()
                val meeting = dao.getNextUpcomingMeeting(now)

                if (meeting == null) {
                    views.setTextViewText(R.id.tvWidgetDate, "다가오는 확정 모임이 없어요")
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                    return@launch
                }

                // UI 표시
                views.setViewVisibility(R.id.iconDate, View.VISIBLE)
                views.setViewVisibility(R.id.iconTime, View.VISIBLE)
                views.setViewVisibility(R.id.iconPlace, View.VISIBLE)
                views.setViewVisibility(R.id.tvWidgetTime, View.VISIBLE)
                views.setViewVisibility(R.id.tvWidgetPlace, View.VISIBLE)

                val dateFmt = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREA)
                val timeFmt = SimpleDateFormat("a h:mm", Locale.KOREA)
                val date = Date(meeting.finalMeetingAt)

                views.setTextViewText(R.id.tvWidgetGroupName, meeting.groupName)
                views.setTextViewText(R.id.tvWidgetDate, dateFmt.format(date))
                views.setTextViewText(R.id.tvWidgetTime, timeFmt.format(date))
                views.setTextViewText(R.id.tvWidgetPlace, meeting.finalMeetingPlace)

                // 🔗 상세 이동 PendingIntent
                val detailIntent = Intent(context, MainActivity::class.java).apply {
                    putExtra("from_widget", true)
                    putExtra("widget_group_id", meeting.groupId)
                    putExtra("widget_group_name", meeting.groupName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                val detailPendingIntent = PendingIntent.getActivity(
                    context,
                    uniqueRequestCode + 1, // ← requestCode 다르게!
                    detailIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                views.setOnClickPendingIntent(R.id.widget_root, detailPendingIntent)

                // 🔥 최종 UI 갱신
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        // 🔥 Firestore/Room 변화 시 강제 전체 위젯 업데이트 (MeetingRepository에서 호출)
        fun requestUpdateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, NextMeetingWidgetProvider::class.java)
            )
            ids.forEach { id ->
                updateWidget(context, manager, id)
            }
        }
    }
}
