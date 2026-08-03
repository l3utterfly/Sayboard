package com.elishaazaria.sayboard.recognition

import android.os.ParcelFileDescriptor

/**
 * PCM audio supplied by a SpeechRecognizer client through RecognizerIntent.EXTRA_AUDIO_SOURCE.
 * MySpeechService takes ownership of this process's descriptor and closes it when recognition ends.
 */
data class InjectedAudioSource(
    val descriptor: ParcelFileDescriptor,
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: Int
) : AutoCloseable {
    override fun close() {
        descriptor.close()
    }
}
