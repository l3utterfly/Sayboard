package com.elishaazaria.sayboard.services

import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.elishaazaria.sayboard.recognition.InjectedAudioSource
import com.elishaazaria.sayboard.recognition.ModelManager
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger


class SayboardRecognitionService : RecognitionService(), ModelManager.Listener {

    init {
        Log.d(TAG, "init")
    }

    private val modelManager: ModelManager = ModelManager(this, this)

    private var listener: Callback? = null

    private var lastPartialResult: String? = null

    private var activeSessionId = 0
    private var partialResultCount = 0
    private var emptyPartialResultCount = 0
    private var segmentedSession = false

    /**************** RecognitionService functions ***************/

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val sessionId = NEXT_SESSION_ID.incrementAndGet()
        activeSessionId = sessionId
        partialResultCount = 0
        emptyPartialResultCount = 0
        lastPartialResult = null
        segmentedSession = false

        if (listener == null) {
            Log.e(TAG, "Service session=$sessionId onStartListening: callback is null; cannot start")
            return
        }
        if (recognizerIntent == null) {
            Log.e(TAG, "Service session=$sessionId onStartListening: intent is null; cannot start")
            return
        }
        this.listener = listener

        val locale = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            ?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()

        val hasAudioSource = recognizerIntent.hasExtra(EXTRA_AUDIO_SOURCE)
        val hasLegacyAudioSource = recognizerIntent.hasExtra(EXTRA_AUDIO_INJECT_SOURCE)
        val segmentedSessionType = recognizerIntent.getStringExtra(EXTRA_SEGMENTED_SESSION)
        segmentedSession = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                segmentedSessionType == EXTRA_AUDIO_SOURCE && hasAudioSource
        val injectedAudioSource = if (hasAudioSource) {
            val descriptor = recognizerIntent.parcelFileDescriptorExtra(EXTRA_AUDIO_SOURCE)
            if (descriptor == null) {
                Log.e(
                    TAG,
                    "Service session=$sessionId: EXTRA_AUDIO_SOURCE was present but did not " +
                            "contain a ParcelFileDescriptor"
                )
                try {
                    listener.error(SpeechRecognizer.ERROR_CLIENT)
                } catch (e: RemoteException) {
                    Log.e(TAG, "Exception reporting invalid audio source to caller", e)
                }
                return
            }
            InjectedAudioSource(
                descriptor = descriptor,
                channelCount = recognizerIntent.getIntExtra(
                    EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,
                    DEFAULT_AUDIO_SOURCE_CHANNEL_COUNT
                ),
                encoding = recognizerIntent.getIntExtra(
                    EXTRA_AUDIO_SOURCE_ENCODING,
                    AudioFormat.ENCODING_PCM_16BIT
                ),
                sampleRate = recognizerIntent.getIntExtra(
                    EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                    DEFAULT_AUDIO_SOURCE_SAMPLE_RATE
                )
            )
        } else {
            null
        }
        Log.d(
            TAG,
            "Service session=$sessionId start: action=${recognizerIntent.action}, " +
                    "locale=${locale.toLanguageTag()}, " +
                    "partialResults=${recognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)}, " +
                    "languageModel=${recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)}, " +
                    "maxResults=${recognizerIntent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, -1)}, " +
                    "preferOffline=${recognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)}, " +
                    "hasAudioSource=$hasAudioSource, hasLegacyAudioSource=$hasLegacyAudioSource, " +
                    "segmentedSessionType=$segmentedSessionType, segmentedSessionEnabled=$segmentedSession, " +
                    "extraKeys=${recognizerIntent.extras?.keySet()?.sorted()?.joinToString()}"
        )
        if (injectedAudioSource != null) {
            Log.d(
                TAG,
                "Service session=$sessionId: using caller-supplied EXTRA_AUDIO_SOURCE " +
                        "(channels=${injectedAudioSource.channelCount}, " +
                        "encoding=${injectedAudioSource.encoding}, " +
                        "sampleRate=${injectedAudioSource.sampleRate}); Sayboard will not open the microphone"
            )
        }
        if (segmentedSessionType != null && !segmentedSession) {
            Log.w(
                TAG,
                "Service session=$sessionId: unsupported segmented session type=$segmentedSessionType; " +
                        "results will use the ordinary non-segmented callback"
            )
        }
        if (hasLegacyAudioSource) {
            Log.w(
                TAG,
                "Service session=$sessionId: caller supplied legacy EXTRA_AUDIO_INJECT_SOURCE, " +
                        "but Sayboard does not consume injected audio yet"
            )
        }

        val attributionContext: Context? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callingSource = listener.callingAttributionSource
            val callerRecordAudio = callingSource.packageName?.let {
                packageManager.checkPermission(android.Manifest.permission.RECORD_AUDIO, it) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            Log.d(
                TAG,
                "Service session=$sessionId attribution: " +
                        "callingUid=${callingSource.uid}, callingPackage=${callingSource.packageName}, " +
                        "callerHasRecordAudio=$callerRecordAudio"
            )
            // Always create the caller attribution context during onStartListening, including for
            // injected audio. RecognitionService records this synchronous call and then knows that
            // the implementation handled attribution; otherwise the framework starts a second
            // RECORD_AUDIO AppOps delivery check after this method returns. That check can reject a
            // restarted injected session while the caller is already recording into the pipe.
            // MySpeechService only uses this context when it actually opens AudioRecord.
            this.createContext(
                ContextParams.Builder().setNextAttributionSource(callingSource)
                    .build()
            )
        } else {
            null
        }

        modelManager.reloadModels()

        if (!modelManager.switchToRecognizerOfLocale(
                locale,
                true,
                attributionContext,
                injectedAudioSource
            )
        ) {
            Log.w(
                TAG,
                "Could not find a Model for locale '${locale.toLanguageTag()}'. Using default"
            )
            modelManager.initializeFirstLocale(true, attributionContext, injectedAudioSource)
        }
    }

    override fun onCancel(listener: Callback?) {
        Log.d(TAG, "Service session=$activeSessionId onCancel")
        this.listener = listener
        modelManager.stop(true)
    }

    override fun onStopListening(listener: Callback?) {
        Log.d(
            TAG,
            "Service session=$activeSessionId onStopListening: lastPartial=${lastPartialResult.quotedForLog()}, " +
                    "partials=$partialResultCount, emptyPartials=$emptyPartialResultCount"
        )
        this.listener = listener
        modelManager.stop(true)

        if (!segmentedSession) {
            lastPartialResult?.let {
                onResult(it)
            }

            try {
                listener?.endOfSpeech()
            } catch (e: RemoteException) {
                Log.e(TAG, "Exception from caller", e)
            }
        }
    }

    /************* ModelManager.Listener functions ***********/

    override fun onStateChanged(state: ModelManager.State) {
        Log.d(TAG, "Service session=$activeSessionId state=$state")
        if (state == ModelManager.State.STATE_LISTENING) {
            try {
                listener?.readyForSpeech(Bundle())
                listener?.beginningOfSpeech()
            } catch (e: RemoteException) {
                Log.e(TAG, "Exception from caller", e)
            }
        }
    }

    override fun onError(type: ModelManager.ErrorType) {
        Log.e(TAG, "Service session=$activeSessionId model error=$type")
        try {
            listener?.error(
                when (type) {
                    ModelManager.ErrorType.MIC_IN_USE -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    ModelManager.ErrorType.INVALID_AUDIO_SOURCE -> SpeechRecognizer.ERROR_AUDIO
                    ModelManager.ErrorType.NO_RECOGNIZERS_INSTALLED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                    } else {
                        SpeechRecognizer.ERROR_CLIENT
                    }
                }
            )
        } catch (e: RemoteException) {
            Log.e(TAG, "Exception from caller", e)
        }
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Service session=$activeSessionId recognizer error", exception)
        try {
            listener?.error(SpeechRecognizer.ERROR_AUDIO)
        } catch (e: RemoteException) {
            Log.e(TAG, "Exception from caller", e)
        }
    }

    override fun onRecognizerSource(source: RecognizerSource) {
        Log.d(
            TAG,
            "Service session=$activeSessionId recognizer: name=${source.name}, " +
                    "locale=${source.locale.toLanguageTag()}, addSpaces=${source.addSpaces}, closed=${source.closed}"
        )
    }

    override fun onPartialResult(hypothesis: String?) {
        partialResultCount++
        if (hypothesis.isNullOrEmpty()) emptyPartialResultCount++
        if (!hypothesis.isNullOrEmpty() || partialResultCount == 1 || partialResultCount % 10 == 0) {
            Log.d(
                TAG,
                "Service session=$activeSessionId decoder partial #$partialResultCount: " +
                        "value=${hypothesis.quotedForLog()}, empty=${hypothesis.isNullOrEmpty()}; " +
                        "forwarding via Callback.partialResults"
            )
        }
        lastPartialResult = hypothesis
        try {
            listener?.partialResults(Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(hypothesis))
            })
        } catch (e: RemoteException) {
            Log.e(TAG, "Exception from caller", e)
        }
    }

    override fun onResult(hypothesis: String?) {
        Log.d(TAG, "Service session=$activeSessionId decoder result=${hypothesis.quotedForLog()}")
        if (segmentedSession && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lastPartialResult = null
            if (!hypothesis.isNullOrEmpty()) {
                try {
                    listener?.segmentResults(resultBundle(hypothesis))
                    Log.d(
                        TAG,
                        "Service session=$activeSessionId forwarded decoder result as segment"
                    )
                } catch (e: RemoteException) {
                    Log.e(TAG, "Exception forwarding segment to caller", e)
                }
            }
            return
        }
        // Konele seems to assume that results -> end of speech, so call onFinalResult to clean up too.
        onFinalResult(hypothesis)
//        lastPartialResult = null
//        try {
//            listener?.results(Bundle().apply {
//                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(hypothesis))
//            })
//        } catch (e: RemoteException) {
//            Log.e(TAG, "Exception from caller", e)
//        }
    }

    override fun onFinalResult(hypothesis: String?) {
        Log.d(
            TAG,
            "Service session=$activeSessionId decoder final=${hypothesis.quotedForLog()}; " +
                    "forwarding via Callback.results"
        )
        lastPartialResult = null
        if (segmentedSession && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                if (!hypothesis.isNullOrEmpty()) {
                    listener?.segmentResults(resultBundle(hypothesis))
                }
                listener?.endOfSegmentedSession()
                Log.d(TAG, "Service session=$activeSessionId ended segmented session")
            } catch (e: RemoteException) {
                Log.e(TAG, "Exception ending segmented session", e)
            }
            modelManager.stop(true)
            return
        }
        try {
            listener?.results(resultBundle(hypothesis))
            listener?.endOfSpeech()
        } catch (e: RemoteException) {
            Log.e(TAG, "Exception from caller", e)
        }
        modelManager.stop(true)
    }

    override fun onTimeout() {
        Log.w(TAG, "Service session=$activeSessionId timeout")
        try {
            listener?.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        } catch (e: RemoteException) {
            Log.e(TAG, "Exception from caller", e)
        }
    }

    companion object {
        private const val TAG = "SayboardRecognition"
        private val NEXT_SESSION_ID = AtomicInteger(0)

        // String literals keep these diagnostics safe on Android versions below the API level
        // where the public constants were added.
        private const val EXTRA_AUDIO_SOURCE = "android.speech.extra.AUDIO_SOURCE"
        private const val EXTRA_AUDIO_INJECT_SOURCE = "android.speech.extra.AUDIO_INJECT_SOURCE"
        private const val EXTRA_AUDIO_SOURCE_CHANNEL_COUNT =
            "android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT"
        private const val EXTRA_AUDIO_SOURCE_ENCODING = "android.speech.extra.AUDIO_SOURCE_ENCODING"
        private const val EXTRA_AUDIO_SOURCE_SAMPLING_RATE =
            "android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE"
        private const val EXTRA_SEGMENTED_SESSION = "android.speech.extra.SEGMENTED_SESSION"
        private const val DEFAULT_AUDIO_SOURCE_CHANNEL_COUNT = 1
        private const val DEFAULT_AUDIO_SOURCE_SAMPLE_RATE = 16000
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelFileDescriptorExtra(key: String): ParcelFileDescriptor? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, ParcelFileDescriptor::class.java)
        } else {
            getParcelableExtra(key)
        }

    private fun String?.quotedForLog(): String =
        if (this == null) "<null>" else "'${replace("\n", "\\n")}' (length=$length)"

    private fun resultBundle(hypothesis: String?): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(hypothesis))
    }
}
