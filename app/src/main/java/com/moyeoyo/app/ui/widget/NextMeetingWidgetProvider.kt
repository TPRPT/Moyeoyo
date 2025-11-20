package com.moyeoyo.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.R
import com.moyeoyo.app.MainActivity
import java.text.SimpleDateFormat
import java.util.*

class NextMeetingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {

        private const val TAG = "NextMeetingWidget"

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_next_meeting)

            // 기본 텍스트
            views.setTextViewText(R.id.tvWidgetTitle, "다가오는 모임")
            views.setTextViewText(R.id.tvWidgetGroupName, "")
            views.setTextViewText(R.id.tvWidgetDate, "모임 정보를 불러오는 중…")
            views.setTextViewText(R.id.tvWidgetTime, "")
            views.setTextViewText(R.id.tvWidgetPlace, "")

            // 기본적으로 아이콘/텍스트 숨김
            views.setViewVisibility(R.id.iconDate, View.GONE)
            views.setViewVisibility(R.id.iconTime, View.GONE)
            views.setViewVisibility(R.id.iconPlace, View.GONE)
            views.setViewVisibility(R.id.tvWidgetTime, View.GONE)
            views.setViewVisibility(R.id.tvWidgetPlace, View.GONE)

            // 항상 동작하는 "기본" PendingIntent (홈 열기용)
            val baseIntent = Intent(context, MainActivity::class.java).apply {
                // from_widget 안 넣음 → 그냥 앱 기본 시작 화면으로
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val basePendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                baseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 확정된 모임이 있든 없든, 일단 기본으로 홈으로 가는 클릭 리스너는 항상 설정
            views.setOnClickPendingIntent(R.id.widget_root, basePendingIntent)

            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser

            if (user == null) {
                views.setTextViewText(R.id.tvWidgetDate, "로그인 후 이용해주세요")
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            val db = FirebaseFirestore.getInstance()

            db.collection("groups")
                .whereArrayContains("memberUids", user.uid)
                .get()
                .addOnSuccessListener { snapshot ->

                    var upcomingMeeting: UpcomingMeeting? = null
                    val now = System.currentTimeMillis()

                    for (doc in snapshot.documents) {
                        val groupName = doc.getString("groupName") ?: "모임"

                        // Firestore 필드: confirmedPlace (Map), confirmedTime (Timestamp)
                        val confirmedPlace = doc.get("confirmedPlace") as? Map<*, *>
                        val confirmedTime = doc.get("confirmedTime") as? Timestamp

                        // 🔹 둘 중 하나라도 없으면 확정된 모임 아님
                        if (confirmedPlace == null || confirmedTime == null) continue

                        val placeName = confirmedPlace["name"] as? String ?: "장소 미정"
                        val finalAt = confirmedTime.toDate().time

                        // 🔹 이미 지난 모임은 제외
                        if (finalAt <= now) continue

                        if (upcomingMeeting == null || finalAt < upcomingMeeting!!.finalAt) {
                            upcomingMeeting = UpcomingMeeting(
                                doc.id,
                                groupName,
                                finalAt,
                                placeName
                            )
                        }
                    }

                    // 확정된 모임 없음 → 기본 PendingIntent(홈으로 이동)만 유지
                    if (upcomingMeeting == null) {
                        views.setTextViewText(R.id.tvWidgetDate, "다가오는 확정 모임이 없어요")
                        // 아이콘, 시간/장소 텍스트는 숨긴 상태 그대로
                        appWidgetManager.updateAppWidget(appWidgetId, views)
                        return@addOnSuccessListener
                    }

                    // 확정된 모임 있음 → 아이콘/텍스트 보이기
                    views.setViewVisibility(R.id.iconDate, View.VISIBLE)
                    views.setViewVisibility(R.id.iconTime, View.VISIBLE)
                    views.setViewVisibility(R.id.iconPlace, View.VISIBLE)
                    views.setViewVisibility(R.id.tvWidgetTime, View.VISIBLE)
                    views.setViewVisibility(R.id.tvWidgetPlace, View.VISIBLE)

                    val meeting = upcomingMeeting!!

                    val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"), Locale.KOREA)
                    cal.timeInMillis = meeting.finalAt

                    val dateFmt = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREA)
                    val timeFmt = SimpleDateFormat("a h:mm", Locale.KOREA)

                    views.setTextViewText(R.id.tvWidgetGroupName, meeting.groupName)
                    views.setTextViewText(R.id.tvWidgetDate, dateFmt.format(cal.time))
                    views.setTextViewText(R.id.tvWidgetTime, timeFmt.format(cal.time))
                    views.setTextViewText(R.id.tvWidgetPlace, meeting.place)

                    // 확정된 모임이 있을 때만 "그룹 디테일로 가는" 인텐트로 덮어쓰기
                    val detailIntent = Intent(context, MainActivity::class.java).apply {
                        putExtra("from_widget", true)
                        putExtra("widget_group_id", meeting.groupId)
                        putExtra("widget_group_name", meeting.groupName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }

                    val detailPendingIntent = PendingIntent.getActivity(
                        context,
                        appWidgetId,
                        detailIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    // 이 줄이 basePending 을 덮어씀 → 이 경우엔 디테일로 이동
                    views.setOnClickPendingIntent(R.id.widget_root, detailPendingIntent)

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Widget load error: ${e.message}")
                    views.setTextViewText(R.id.tvWidgetDate, "데이터를 불러올 수 없어요")
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
        }

        fun requestUpdateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, NextMeetingWidgetProvider::class.java)
            )
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }
    }

    private data class UpcomingMeeting(
        val groupId: String,
        val groupName: String,
        val finalAt: Long,
        val place: String
    )
}