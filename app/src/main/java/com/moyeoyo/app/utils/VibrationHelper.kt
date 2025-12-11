package com.moyeoyo.app.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 진동 피드백을 제공하는 헬퍼 클래스
 */
object VibrationHelper {
    
    /**
     * 가벼운 진동 (알림 수신 등)
     */
    fun lightVibration(context: Context) {
        vibrate(context, 100, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    
    /**
     * 중간 진동 (투표 완료, 단계 전환 등)
     */
    fun mediumVibration(context: Context) {
        vibrate(context, 150, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    
    /**
     * 강한 진동 (중요한 이벤트 - 약속 확정 등)
     */
    fun strongVibration(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 짧은 진동 두 번 (더 강한 느낌)
            val vibrator = getVibrator(context)
            val pattern = longArrayOf(0, 100, 50, 100)
            val effect = VibrationEffect.createWaveform(pattern, -1)
            vibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(200)
        }
    }
    
    /**
     * 커스텀 진동
     * @param duration 진동 지속 시간 (밀리초)
     * @param amplitude 진동 강도 (0-255, VibrationEffect.DEFAULT_AMPLITUDE는 시스템 기본값)
     */
    private fun vibrate(context: Context, duration: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        val vibrator = getVibrator(context) ?: return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(duration, amplitude)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            (vibrator as? Vibrator)?.vibrate(duration)
        }
    }
    
    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}

