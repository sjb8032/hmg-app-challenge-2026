package kr.contest.pleosapp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** 휴식 알람. 기기 알람음을 반복 재생하고, 없으면 알림음을 1.5초마다 낸다. */
class AlarmPlayer(context: Context) {
    private val ringtone: Ringtone? = runCatching {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        uri?.let { RingtoneManager.getRingtone(context.applicationContext, it) }
    }.getOrNull()
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull()
    private val main = Handler(Looper.getMainLooper())
    private var ringing = false

    private val beepLoop = object : Runnable {
        override fun run() {
            if (!ringing) return
            runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600) }
            main.postDelayed(this, 1_500L)
        }
    }

    fun start() {
        if (ringing) return
        ringing = true
        val played = runCatching {
            val r = ringtone ?: error("no ringtone")
            r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            r.isLooping = true
            r.play()
        }.isSuccess
        if (!played) main.post(beepLoop)
    }

    fun stop() {
        ringing = false
        main.removeCallbacks(beepLoop)
        runCatching { ringtone?.stop() }
    }

    fun release() {
        stop()
        runCatching { tone?.release() }
    }
}
