package kr.contest.pleosapp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 휴식 알람. 작게 시작해 점점 커진다 (깊이 잠든 사람이 놀라 깨지 않게, 그래도 결국은 깨게).
 * 기기 알람음이 있으면 반복 재생하며 음량을 올리고, 없으면 알림음을 1.5초마다 점점 크게 낸다.
 */
class AlarmPlayer(context: Context) {
    private val ringtone: Ringtone? = runCatching {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        uri?.let { RingtoneManager.getRingtone(context.applicationContext, it) }
    }.getOrNull()
    private val tones: List<ToneGenerator> = TONE_VOLUMES.mapNotNull {
        runCatching { ToneGenerator(AudioManager.STREAM_ALARM, it) }.getOrNull()
    }
    private val main = Handler(Looper.getMainLooper())
    private var ringing = false
    private var startedAt = 0L
    private var volume = START_VOLUME

    private val beepLoop = object : Runnable {
        override fun run() {
            if (!ringing || tones.isEmpty()) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val index = (elapsed / RAMP_MS.toDouble() * tones.size).toInt().coerceIn(0, tones.size - 1)
            runCatching { tones[index].startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600) }
            main.postDelayed(this, 1_500L)
        }
    }

    private val rampLoop = object : Runnable {
        override fun run() {
            if (!ringing) return
            volume = (volume + VOLUME_STEP).coerceAtMost(1f)
            runCatching { ringtone?.volume = volume }
            if (volume < 1f) main.postDelayed(this, RAMP_STEP_MS)
        }
    }

    fun start() {
        if (ringing) return
        ringing = true
        startedAt = SystemClock.elapsedRealtime()
        volume = START_VOLUME
        val played = runCatching {
            val r = ringtone ?: error("no ringtone")
            r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            r.isLooping = true
            r.volume = volume
            r.play()
        }.isSuccess
        if (played) main.postDelayed(rampLoop, RAMP_STEP_MS) else main.post(beepLoop)
    }

    fun stop() {
        ringing = false
        main.removeCallbacks(beepLoop)
        main.removeCallbacks(rampLoop)
        runCatching { ringtone?.stop() }
    }

    fun release() {
        stop()
        tones.forEach { runCatching { it.release() } }
    }

    companion object {
        private const val START_VOLUME = 0.15f
        private const val VOLUME_STEP = 0.1f
        private const val RAMP_STEP_MS = 2_500L
        /** 알림음 대체 재생에서 가장 큰 소리까지 걸리는 시간 */
        private const val RAMP_MS = 20_000L
        private val TONE_VOLUMES = listOf(35, 65, 95)
    }
}
