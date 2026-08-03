package com.elishaazaria.sayboard.recognition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.ime.ViewManager
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.providers.Providers
import com.elishaazaria.sayboard.sayboardPreferenceModel
import org.vosk.android.RecognitionListener
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class ModelManager(
    private val context: Context,
    private val listener: Listener
) {
    private val prefs by sayboardPreferenceModel()
    private var speechService: MySpeechService? = null
    var isRunning = false
        private set

    val openSettingsOnMic: Boolean
        get() = recognizerSources.size == 0

    private var recognizerSourceProviders = Providers(context)

    private var recognizerSourceModels: List<InstalledModelReference> = listOf()
    private var recognizerSources: MutableList<RecognizerSource> = ArrayList()
    private var currentRecognizerSourceIndex = 0
    private var currentRecognizerSource: RecognizerSource? = null
    private var pendingInjectedAudioSource: InjectedAudioSource? = null
    private var initializationGeneration = 0
    private val executor: Executor = Executors.newSingleThreadExecutor()


    init {
        reloadModels()
    }

    private fun initializeRecognizer(
        autoStart: Boolean,
        attributionContext: Context? = null,
        injectedAudioSource: InjectedAudioSource? = null
    ) {
        if (recognizerSources.size == 0) {
            Log.e(TAG, "ModelManager initialize: no recognizer sources")
            injectedAudioSource?.closeQuietly()
            return
        }

        initializationGeneration++
        val generation = initializationGeneration
        pendingInjectedAudioSource?.closeQuietly()
        pendingInjectedAudioSource = injectedAudioSource
        currentRecognizerSource = recognizerSources[currentRecognizerSourceIndex]
        Log.d(
            TAG,
            "ModelManager initialize: index=$currentRecognizerSourceIndex, " +
                    "name=${currentRecognizerSource!!.name}, " +
                    "locale=${currentRecognizerSource!!.locale.toLanguageTag()}, autoStart=$autoStart, " +
                    "hasAttributionContext=${attributionContext != null}, " +
                    "inputMode=${if (injectedAudioSource == null) "microphone" else "injected"}"
        )
        listener.onRecognizerSource(currentRecognizerSource!!)

        val onLoaded = Observer { r: RecognizerSource? ->
            if (generation != initializationGeneration) {
                Log.d(TAG, "ModelManager loaded: ignoring stale initialization generation=$generation")
                return@Observer
            }
            Log.d(
                TAG,
                "ModelManager loaded: name=${r?.name}, locale=${r?.locale?.toLanguageTag()}, " +
                        "closed=${r?.closed}, autoStart=$autoStart"
            )
            val pendingAudioSource = pendingInjectedAudioSource
            pendingInjectedAudioSource = null
            if (autoStart) {
                start(attributionContext, pendingAudioSource) // execute after initialize
            } else {
                pendingAudioSource?.closeQuietly()
            }
        }
        currentRecognizerSource!!.initialize(executor, onLoaded)
    }

    val currentRecognizerSourceAddSpaces: Boolean
        get() = currentRecognizerSource?.addSpaces ?: true

    fun switchToNextRecognizer(
        autoStart: Boolean,
        attributionContext: Context? = null,
        injectedAudioSource: InjectedAudioSource? = null
    ) {
        if (recognizerSources.size == 0 || recognizerSources.size == 1) {
            injectedAudioSource?.closeQuietly()
            return
        }
        stop(true)
        currentRecognizerSourceIndex++
        if (currentRecognizerSourceIndex >= recognizerSources.size) {
            currentRecognizerSourceIndex = 0
        }
        initializeRecognizer(autoStart, attributionContext, injectedAudioSource)
    }

    fun switchToRecognizerOfLocale(
        locale: Locale,
        autoStart: Boolean,
        attributionContext: Context? = null,
        injectedAudioSource: InjectedAudioSource? = null
    ): Boolean {
        Log.d(
            TAG,
            "ModelManager select locale=${locale.toLanguageTag()} from " +
                    recognizerSources.joinToString(prefix = "[", postfix = "]") {
                        "${it.name}:${it.locale.toLanguageTag()}"
                    }
        )
        var bestSource = -1
        var foundLanguage = false
        var foundCountry = false

        recognizerSources.forEachIndexed { index, recognizerSource ->
            if (recognizerSource.locale.language == locale.language) {
                if (recognizerSource.locale.country == locale.country) {
                    if (recognizerSource.locale.variant == locale.variant) {
                        // Same language, country, and variant
                        bestSource = index
                        foundLanguage = true
                        foundCountry = true
                        return@forEachIndexed
                    } else if (!foundCountry) {
                        // Same language and country, but not variant
                        bestSource = index
                        foundLanguage = true
                        foundCountry = true
                    }
                } else if (!foundLanguage) {
                    // Same language, but not country
                    foundLanguage = true
                    bestSource = index
                }
            } else if (recognizerSource.locale == Locale.ROOT && !foundLanguage && bestSource == -1) {
                // A root locale. Pick it if we didn't find anything.
                bestSource = index
            }
        }

        if (bestSource == -1) {
            Log.w(TAG, "ModelManager select: no model matched locale=${locale.toLanguageTag()}")
            return false
        }

        stop(true)
        currentRecognizerSourceIndex = bestSource
        Log.d(
            TAG,
            "ModelManager select: chose index=$bestSource, " +
                    "name=${recognizerSources[bestSource].name}, " +
                    "locale=${recognizerSources[bestSource].locale.toLanguageTag()}"
        )

        initializeRecognizer(
            autoStart,
            attributionContext,
            injectedAudioSource
        ) // start is called after the recognizer is initialized

        return true
    }

    fun initializeFirstLocale(
        autoStart: Boolean,
        attributionContext: Context? = null,
        injectedAudioSource: InjectedAudioSource? = null
    ): Boolean {
        if (recognizerSources.size == 0) {
            injectedAudioSource?.closeQuietly()
            listener.onError(ErrorType.NO_RECOGNIZERS_INSTALLED)
            listener.onStateChanged(State.STATE_ERROR)
            return false
        }

        currentRecognizerSourceIndex = 0
        initializeRecognizer(autoStart, attributionContext, injectedAudioSource)
        return true
    }

    fun start(
        attributionContext: Context? = null,
        injectedAudioSource: InjectedAudioSource? = null
    ) {
        if (currentRecognizerSource == null) {
            Log.w(
                TAG,
                "currentRecognizerSource is null!"
            )
            injectedAudioSource?.closeQuietly()
            return
        }
        if (currentRecognizerSource!!.closed) {
            Log.w(
                TAG,
                "Trying to start a closed Recognizer Source: ${currentRecognizerSource!!.name}"
            )
            injectedAudioSource?.closeQuietly()
            return
        }
        if (isRunning || speechService != null) {
            speechService?.stop()
        }
        isRunning = true
        listener.onStateChanged(State.STATE_LISTENING)
        try {
            val recognizer = currentRecognizerSource!!.recognizer
            val hasRecordAudioPermission = ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            Log.d(
                TAG,
                "ModelManager start: source=${currentRecognizerSource!!.name}, " +
                        "locale=${currentRecognizerSource!!.locale.toLanguageTag()}, " +
                        "sampleRate=${recognizer.sampleRate}, hasRecordAudioPermission=$hasRecordAudioPermission, " +
                        "hasAttributionContext=${attributionContext != null}, recordDevice=${recordDevice?.productName}, " +
                        "inputMode=${if (injectedAudioSource == null) "microphone" else "injected"}"
            )
            if (injectedAudioSource == null && !hasRecordAudioPermission) {
                Log.e(TAG, "ModelManager start aborted: Sayboard RECORD_AUDIO permission is not granted")
                return
            }
            speechService = MySpeechService(
                recognizer,
                recognizer.sampleRate,
                attributionContext,
                injectedAudioSource
            )
            speechService!!.recordDevice = recordDevice
            val started = speechService!!.startListening(listener)
            Log.d(TAG, "ModelManager start: MySpeechService.startListening returned $started")
        } catch (e: IOException) {
            injectedAudioSource?.closeQuietly()
            Log.e(TAG, "ModelManager start: failed to initialize or start AudioRecord", e)
            listener.onError(ErrorType.MIC_IN_USE)
            listener.onStateChanged(State.STATE_ERROR)
        } catch (e: IllegalArgumentException) {
            injectedAudioSource?.closeQuietly()
            Log.e(TAG, "ModelManager start: invalid injected audio source", e)
            listener.onError(ErrorType.INVALID_AUDIO_SOURCE)
            listener.onStateChanged(State.STATE_ERROR)
        }
    }

    private var pausedState = false

    fun reloadModels() {

        // TODO: make sure we actually need this
//        val newModels = prefs.modelsOrder.get()
//        if (newModels == recognizerSourceModels) {
//            if (autoStart) {
//                if (currentRecognizerSource != null) {
//                    start()
//                }
//            }
//            return
//        }

        val newModels = prefs.modelsOrder.get()
        if (newModels == recognizerSourceModels) {
            Log.d(TAG, "ModelManager reload: model order unchanged (${newModels.size} models)")
            return
        }

        recognizerSources.clear()
        recognizerSourceModels = newModels
        recognizerSourceModels.forEach { model ->
            recognizerSourceProviders.recognizerSourceForModel(model)?.let {
                recognizerSources.add(it)
            }
        }

        Log.d(
            TAG,
            "ModelManager reload: references=${recognizerSourceModels.size}, " +
                    "usableSources=${recognizerSources.size}, sources=" +
                    recognizerSources.joinToString(prefix = "[", postfix = "]") {
                        "${it.name}:${it.locale.toLanguageTag()}"
                    }
        )

        if (recognizerSources.size == 0) {
            listener.onError(ErrorType.NO_RECOGNIZERS_INSTALLED)
            listener.onStateChanged(State.STATE_ERROR)
        }
    }

    fun pause(checked: Boolean) {
        if (speechService != null) {
            speechService!!.setPause(checked)
            pausedState = checked
            if (checked) {
                listener.onStateChanged(State.STATE_PAUSED)
            } else {
                listener.onStateChanged(State.STATE_LISTENING)
            }
        } else {
            pausedState = false
        }
    }

    val isPaused: Boolean
        get() = pausedState && speechService != null

    fun stop(forceFreeRam: Boolean = false) {
        Log.d(
            TAG,
            "ModelManager stop: forceFreeRam=$forceFreeRam, isRunning=$isRunning, " +
                    "hasSpeechService=${speechService != null}"
        )
        initializationGeneration++
        pendingInjectedAudioSource?.closeQuietly()
        pendingInjectedAudioSource = null
        speechService?.let {
            executor.execute {
                it.stop()
                it.shutdown()
            }
        }
        speechService = null
        isRunning = false
        stopRecognizerSource(forceFreeRam || !prefs.logicKeepModelInRam.get())
    }

    private fun stopRecognizerSource(freeRam: Boolean) {
        currentRecognizerSource?.let {
            executor.execute {
                it.close(freeRam)
            }
        }
        listener.onStateChanged(State.STATE_STOPPED)
    }

    fun onDestroy() {
        stop(true)
    }

    var recordDevice: AudioDeviceInfo? = null
        set(value) {
            field = value
            speechService?.recordDevice = value
        }

    companion object {
        private const val TAG = "SayboardRecognition"
    }

    private fun InjectedAudioSource.closeQuietly() {
        try {
            close()
        } catch (e: IOException) {
            Log.w(TAG, "ModelManager: failed to close injected audio descriptor", e)
        }
    }

    interface Listener : RecognitionListener {
        fun onStateChanged(state: State)

        fun onError(type: ErrorType)

        fun onRecognizerSource(source: RecognizerSource)
    }

    enum class State {
        STATE_INITIAL, STATE_LOADING, STATE_READY, STATE_LISTENING, STATE_PAUSED, STATE_ERROR, STATE_STOPPED
    }

    enum class ErrorType {
        MIC_IN_USE, NO_RECOGNIZERS_INSTALLED, INVALID_AUDIO_SOURCE
    }
}
