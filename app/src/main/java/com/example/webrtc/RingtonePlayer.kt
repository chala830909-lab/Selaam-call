package com.example.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RingtonePlayer(context: Context) {
    private val appContext = context.applicationContext
    private var ringtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null
    private var toneJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun startIncomingRingtone() {
        stop()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uri != null) {
                val r = RingtoneManager.getRingtone(appContext, uri)
                if (r != null) {
                    r.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        r.isLooping = true
                    }
                    r.play()
                    ringtone = r
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Default ringtone playback failed, falling back to ToneGenerator", e)
        }

        // Fallback / supplemental vibration & tone pulse if system ringtone couldn't play
        if (ringtone == null) {
            startToneLoop(ToneGenerator.TONE_SUP_RINGTONE, 1400L, 1800L)
        }
        vibratePulse()
    }

    fun startOutgoingRingback() {
        stop()
        startToneLoop(ToneGenerator.TONE_SUP_RINGTONE, 900L, 2200L)
    }

    private fun startToneLoop(toneType: Int, onDurationMs: Long, pauseMs: Long) {
        toneJob = scope.launch {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 55)
                toneGenerator = tg
                while (isActive) {
                    tg.startTone(toneType, onDurationMs.toInt())
                    delay(onDurationMs + pauseMs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "ToneGenerator warning: ${e.message}")
            }
        }
    }

    private fun vibratePulse() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 900), 0)
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 500, 900), 0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrator unavailable: ${e.message}")
        }
    }

    private fun cancelVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.cancel()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        toneJob?.cancel()
        toneJob = null
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        } catch (_: Exception) {
        }
        toneGenerator = null
        cancelVibration()
    }

    private companion object {
        const val TAG = "RingtonePlayer"
    }
}
