/*
 * org.vosk.SpeechService, extended to support other recognizers.
 */
package com.elishaazaria.sayboard.recognition

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import org.vosk.android.RecognitionListener
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ModelManager verifies RECORD_AUDIO before constructing microphone mode. Injected mode does not
// access the microphone, so @RequiresPermission on this conditional constructor would be incorrect.
@SuppressLint("MissingPermission")
class MySpeechService constructor(
    private val recognizer: Recognizer, sampleRate: Float,
    attributionContext: Context? = null,
    private val injectedAudioSource: InjectedAudioSource? = null
) {
    private val sampleRate: Int
    private val bufferSize: Int
    private val recorder: AudioRecord?
    private val injectedAudioStream: ParcelFileDescriptorInputStream?
    private var recognizerThread: RecognizerThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var stopping = false

    init {
        this.sampleRate = sampleRate.toInt()
        bufferSize = (this.sampleRate.toFloat() * BUFFER_SIZE_SECONDS).roundToInt()

        if (injectedAudioSource != null) {
            require(injectedAudioSource.encoding == AudioFormat.ENCODING_PCM_16BIT) {
                "Injected audio encoding ${injectedAudioSource.encoding} is unsupported; " +
                        "expected ENCODING_PCM_16BIT (${AudioFormat.ENCODING_PCM_16BIT})"
            }
            require(injectedAudioSource.channelCount == 1) {
                "Injected audio has ${injectedAudioSource.channelCount} channels; only mono is supported"
            }
            require(injectedAudioSource.sampleRate == this.sampleRate) {
                "Injected audio sample rate ${injectedAudioSource.sampleRate} does not match " +
                        "recognizer sample rate ${this.sampleRate}"
            }

            recorder = null
            injectedAudioStream = ParcelFileDescriptorInputStream(
                injectedAudioSource.descriptor,
                bufferSize
            )
            Log.d(
                TAG,
                "Audio init: mode=injected, sampleRate=${injectedAudioSource.sampleRate}, " +
                        "channels=${injectedAudioSource.channelCount}, encoding=${injectedAudioSource.encoding}, " +
                        "bufferSamples=$bufferSize"
            )
        } else {
            injectedAudioStream = null
            recorder = AudioRecord.Builder().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && attributionContext != null) {
                    setContext(attributionContext)
                }
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setAudioFormat(AudioFormat.Builder().apply {
                    setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    setSampleRate(this@MySpeechService.sampleRate)
                    setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                }.build())
                setBufferSizeInBytes(bufferSize * 2)
            }.build()

            Log.d(
                TAG,
                "Audio init: mode=microphone, sampleRate=${this.sampleRate}, " +
                        "bufferSamples=$bufferSize, bufferBytes=${bufferSize * 2}, " +
                        "hasAttributionContext=${attributionContext != null}, " +
                        "recorderState=${recorder.state}, audioSessionId=${recorder.audioSessionId}"
            )

            if (recorder.state == AudioRecord.STATE_UNINITIALIZED) {
                recorder.release()
                throw IOException("Failed to initialize recorder. Microphone might be already in use.")
            }
        }
    }

    fun startListening(listener: RecognitionListener): Boolean {
        return if (null != recognizerThread) {
            Log.w(TAG, "Audio startListening rejected: recognizer thread is already active")
            false
        } else {
            recognizerThread =
                RecognizerThread(listener)
            recognizerThread!!.start()
            true
        }
    }

    var recordDevice: AudioDeviceInfo?
        get() = recorder?.routedDevice
        set(value) {
            recorder?.preferredDevice = value
        }

    fun startListening(listener: RecognitionListener, timeout: Int): Boolean {
        return if (null != recognizerThread) {
            false
        } else {
            recognizerThread =
                RecognizerThread(listener, timeout)
            recognizerThread!!.start()
            true
        }
    }

    private fun stopRecognizerThread(): Boolean {
        return if (null == recognizerThread) {
            false
        } else {
            try {
                stopping = true
                recognizerThread!!.interrupt()
                // FileInputStream reads from an injected pipe are not reliably interruptible.
                // Closing our read end wakes the worker so join() cannot hang.
                injectedAudioStream?.close()
                recognizerThread!!.join()
            } catch (var2: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            recognizerThread = null
            true
        }
    }

    fun stop(): Boolean {
        return stopRecognizerThread()
    }

    fun cancel(): Boolean {
        if (recognizerThread != null) {
            recognizerThread!!.setPause(true)
        }
        return stopRecognizerThread()
    }

    fun shutdown() {
        injectedAudioStream?.close()
        recorder?.release()
    }

    fun setPause(paused: Boolean) {
        if (recognizerThread != null) {
            recognizerThread!!.setPause(paused)
        }
    }

    fun reset() {
        if (recognizerThread != null) {
            recognizerThread!!.reset()
        }
    }

    private inner class RecognizerThread @JvmOverloads constructor(
        var listener: RecognitionListener,
        timeout: Int = -1
    ) : Thread() {
        private var remainingSamples: Int
        private val timeoutSamples: Int

        @Volatile
        private var paused = false

        @Volatile
        private var reset = false

        init {
            if (timeout != -1) {
                timeoutSamples = timeout * sampleRate / 1000
            } else {
                timeoutSamples = -1
            }
            remainingSamples = timeoutSamples
        }

        fun setPause(paused: Boolean) {
            this.paused = paused
        }

        fun reset() {
            reset = true
        }

        override fun run() {
            val microphoneRecorder = recorder
            if (microphoneRecorder != null) {
                microphoneRecorder.startRecording()
                Log.d(
                    TAG,
                    "Audio started: mode=microphone, recordingState=${microphoneRecorder.recordingState} " +
                            "(3=RECORDING, 1=STOPPED), sampleRate=$sampleRate, bufferSize=$bufferSize, " +
                            "audioSource=${microphoneRecorder.audioSource}, " +
                            "routedDevice=${microphoneRecorder.routedDevice?.productName} " +
                            "type=${microphoneRecorder.routedDevice?.type}"
                )
                if (microphoneRecorder.recordingState == AudioRecord.RECORDSTATE_STOPPED) {
                    microphoneRecorder.stop()
                    val ioe =
                        IOException("Failed to start recording. Microphone might be already in use.")
                    mainHandler.post { listener.onError(ioe) }
                    // Bail out: continuing into the read loop here would call acceptWaveForm on a
                    // recognizer that can be torn down by the racing stop()/close(), crashing natively.
                    return
                }
            } else {
                Log.d(
                    TAG,
                    "Audio started: mode=injected, sampleRate=$sampleRate, bufferSize=$bufferSize"
                )
            }
            val buffer = ShortArray(bufferSize)
            var logCounter = 0
            var bufferCount = 0L
            var totalSamples = 0L
            var zeroPeakBufferCount = 0L
            var maximumPeak = 0
            var emittedNonEmptyPartial = false
            var audioReadFailed = false
            while (!interrupted() && (timeoutSamples == -1 || remainingSamples > 0)) {
                val nread = try {
                    microphoneRecorder?.read(buffer, 0, buffer.size)
                        ?: injectedAudioStream!!.readSamples(buffer)
                } catch (e: IOException) {
                    if (stopping) {
                        Log.d(TAG, "Audio injected stream closed during stop")
                    } else {
                        Log.e(TAG, "Audio injected stream read failed", e)
                        audioReadFailed = true
                        mainHandler.post { listener.onError(e) }
                    }
                    break
                }

                if (nread == END_OF_STREAM) {
                    Log.d(TAG, "Audio injected stream reached EOF after $totalSamples samples")
                    break
                }
                if (nread == 0) continue
                if (!paused) {
                    if (reset) {
                        recognizer.reset()
                        reset = false
                    }
                    if (nread < 0) {
                        // Read error (e.g. mic access revoked mid-stream). Report and stop cleanly
                        // instead of throwing, which would crash this thread.
                        Log.e(TAG, "run: audio read error nread=$nread, stopping")
                        audioReadFailed = true
                        val ioe = IOException("error reading audio buffer ($nread)")
                        mainHandler.post { listener.onError(ioe) }
                        break
                    }
                    // Diagnostic: measure the audio level of this buffer. A peak that stays at/near
                    // 0 means AudioRecord is returning silence (e.g. the calling app lacks
                    // RECORD_AUDIO / attribution failed), which makes Vosk emit empty partials.
                    var peak = 0
                    var sumSquares = 0.0
                    for (i in 0 until nread) {
                        val a = abs(buffer[i].toInt())
                        if (a > peak) peak = a
                        sumSquares += buffer[i].toDouble() * buffer[i].toDouble()
                    }
                    val rms = if (nread > 0) sqrt(sumSquares / nread).roundToInt() else 0
                    bufferCount++
                    totalSamples += nread
                    if (peak == 0) zeroPeakBufferCount++
                    if (peak > maximumPeak) maximumPeak = peak
                    if (logCounter++ % 5 == 0) {
                        Log.d(
                            TAG,
                            "Audio PCM: buffers=$bufferCount, latestSamples=$nread, peak=$peak, rms=$rms " +
                                    "(0..32767), zeroPeakBuffers=$zeroPeakBufferCount, maxPeak=$maximumPeak"
                        )
                    }
                    if (recognizer.acceptWaveForm(buffer, nread)) {
                        val result = recognizer.getResult()
                        Log.d(
                            TAG,
                            "Audio decoder result: '${result.replace("\n", "\\n")}' (length=${result.length})"
                        )
                        mainHandler.post { listener.onResult(result) }
                    } else {
                        val result = recognizer.getPartialResult()
                        if (result.isNotEmpty()) {
                            emittedNonEmptyPartial = true
                            Log.d(
                                TAG,
                                "Audio decoder partial: '${result.replace("\n", "\\n")}' " +
                                        "(length=${result.length})"
                            )
                        } else if (bufferCount == 1L || bufferCount % 10L == 0L) {
                            Log.d(
                                TAG,
                                "Audio decoder partial is empty after $bufferCount buffers; " +
                                        "latestPeak=$peak, latestRms=$rms, maxPeak=$maximumPeak"
                            )
                        }
                        mainHandler.post { listener.onPartialResult(result) }
                    }
                    if (timeoutSamples != -1) {
                        remainingSamples -= nread
                    }
                }
            }
            if (microphoneRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                microphoneRecorder.stop()
            }
            Log.d(
                TAG,
                "Audio stopped: buffers=$bufferCount, samples=$totalSamples, " +
                        "zeroPeakBuffers=$zeroPeakBufferCount, maxPeak=$maximumPeak, " +
                        "emittedNonEmptyPartial=$emittedNonEmptyPartial, paused=$paused, " +
                        "timeoutExpired=${timeoutSamples != -1 && remainingSamples <= 0}"
            )
            if (!paused && !audioReadFailed) {
                if (timeoutSamples != -1 && remainingSamples <= 0) {
                    mainHandler.post { listener.onTimeout() }
                } else {
                    val finalResult = recognizer.getFinalResult()
                    Log.d(
                        TAG,
                        "Audio decoder final: '${finalResult.replace("\n", "\\n")}' " +
                                "(length=${finalResult.length})"
                    )
                    mainHandler.post { listener.onFinalResult(finalResult) }
                }
            }
        }
    }

    /** Converts the little-endian PCM16 byte stream in EXTRA_AUDIO_SOURCE into Vosk samples. */
    private class ParcelFileDescriptorInputStream(
        descriptor: ParcelFileDescriptor,
        bufferSamples: Int
    ) : AutoCloseable {
        private val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        private val bytes = ByteArray(bufferSamples * 2)
        private var pendingByte = -1

        fun readSamples(samples: ShortArray): Int {
            var offset = 0
            if (pendingByte != -1) {
                bytes[0] = pendingByte.toByte()
                pendingByte = -1
                offset = 1
            }

            val bytesRead = input.read(bytes, offset, bytes.size - offset)
            if (bytesRead == END_OF_STREAM) {
                if (offset != 0) {
                    Log.w(TAG, "Audio injected stream ended with an incomplete PCM16 sample")
                }
                return END_OF_STREAM
            }

            val availableBytes = offset + bytesRead
            val completeBytes = availableBytes and 1.inv()
            var sampleIndex = 0
            var byteIndex = 0
            while (byteIndex < completeBytes) {
                val low = bytes[byteIndex].toInt() and 0xff
                val high = bytes[byteIndex + 1].toInt() shl 8
                samples[sampleIndex++] = (low or high).toShort()
                byteIndex += 2
            }

            if (completeBytes != availableBytes) {
                pendingByte = bytes[availableBytes - 1].toInt() and 0xff
            }
            return sampleIndex
        }

        override fun close() {
            input.close()
        }
    }

    companion object {
        private const val TAG = "SayboardRecognition"
        private const val END_OF_STREAM = -1
        private const val NO_TIMEOUT = -1
        private const val BUFFER_SIZE_SECONDS = 0.2f
    }
}
