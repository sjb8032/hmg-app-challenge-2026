package kr.contest.pleosapp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 짧은 음성 안내.
 *
 * Pleos TextToSpeech SDK가 '준비 중'이라 1단계에서는 안드로이드 기본 TTS를 쓰고,
 * TTS를 쓸 수 없으면 알림음만 낸다 (문구는 화면에 함께 보여 준다).
 * 내비 회전 안내가 가까우면 말을 미뤘다가 안내가 끝난 뒤에 한다.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val tts: TextToSpeech? = runCatching { TextToSpeech(context.applicationContext, this) }.getOrNull()
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull()
    private val pending = ArrayDeque<String>()
    private var counter = 0

    var available = false
        private set

    /** true를 돌려주는 동안에는 말을 미룬다 (예: 회전 안내 직전). */
    var shouldHold: () -> Boolean = { false }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        available = status == TextToSpeech.SUCCESS &&
            engine.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE
        if (available) {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }
        Log.i(TAG, "TTS available=$available")
        flush()
    }

    fun say(text: String) {
        pending.addLast(text)
        while (pending.size > MAX_PENDING) pending.removeFirst()
        flush()
    }

    /** 미뤄 둔 말을 지금 할 수 있으면 한다. 주기적으로 불러 준다. */
    fun flush() {
        if (pending.isEmpty() || shouldHold()) return
        val engine = tts
        while (pending.isNotEmpty()) {
            val text = pending.removeFirst()
            if (available && engine != null) {
                engine.speak(text, TextToSpeech.QUEUE_ADD, null, "restmate-${counter++}")
            } else {
                beep()
            }
        }
    }

    fun beep() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300) }
    }

    fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        runCatching { tone?.release() }
    }

    companion object {
        private const val TAG = "RestMateSpeaker"
        private const val MAX_PENDING = 3
    }
}
