package com.elishaazaria.sayboard.recognition.recognizers.sources

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.elishaazaria.sayboard.data.VoskLocalModel
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import org.json.JSONException
import org.json.JSONObject
import org.vosk.Model
import java.util.concurrent.Executor
import java.util.Locale

class VoskLocal(private val localModel: VoskLocalModel) : RecognizerSource {
    private val stateMLD = MutableLiveData(RecognizerState.NONE)
    override val stateLD: LiveData<RecognizerState>
        get() = stateMLD
    private var myRecognizer: MyRecognizer? = null
    override val recognizer: Recognizer
        get() = myRecognizer!!
    private var model: Model? = null
    override fun initialize(executor: Executor, onLoaded: Observer<RecognizerSource?>) {
        stateMLD.postValue(RecognizerState.LOADING)
        val handler = Handler(Looper.getMainLooper())
        executor.execute {
            val model = Model(localModel.path)
            handler.post {
                modelLoaded(model)
                onLoaded.onChanged(this)
            }
        }
    }

    override val closed: Boolean
        get() = myRecognizer == null
    override val addSpaces: Boolean
        get() = !listOf("ja", "zh").contains(localModel.locale.language)

    private fun modelLoaded(model: Model) {
        this.model = model
        stateMLD.postValue(RecognizerState.READY)
        myRecognizer = MyRecognizer(model, 16000.0f, localModel.locale)
    }

    private class MyRecognizer     //            setMaxAlternatives(3); // TODO: implement
        (model: Model, override val sampleRate: Float, override val locale: Locale?) :
        org.vosk.Recognizer(model, sampleRate),
        Recognizer {

        override fun getResult(): String {
            var raw: String? = null
            try {
                val rawResult = super.getResult()
                raw = rawResult
                val text = JSONObject(rawResult).getString("text").trim { it <= ' ' }
                Log.d(TAG, "Vosk result JSON: raw=$raw, parsedLength=${text.length}")
                return removeSpaceForLocale(text)
            } catch (e: JSONException) {
                Log.e(TAG, "Vosk result JSON parse failed: raw=$raw", e)
            }
            return ""
        }

        override fun getPartialResult(): String {
            try {
                val raw = super.getPartialResult()
                val partial = JSONObject(raw).getString("partial").trim { it <= ' ' }
                if (partial.isEmpty()) {
                    // Empty partial = Vosk has not recognized any words yet (usually silence).
                    Log.v(TAG, "Vosk partial JSON is empty: raw=$raw")
                } else {
                    Log.d(TAG, "Vosk partial JSON: raw=$raw, parsed='$partial'")
                }
                return removeSpaceForLocale(partial)
            } catch (e: JSONException) {
                Log.e(TAG, "Vosk partial JSON parse failed", e)
            }
            return ""
        }

        override fun getFinalResult(): String {
            var raw: String? = null
            try {
                val rawResult = super.getFinalResult()
                raw = rawResult
                val text = JSONObject(rawResult).getString("text").trim { it <= ' ' }
                Log.d(TAG, "Vosk final JSON: raw=$raw, parsedLength=${text.length}")
                return removeSpaceForLocale(text)
            } catch (e: JSONException) {
                Log.e(TAG, "Vosk final JSON parse failed: raw=$raw", e)
            }
            return ""
        }
    }

    override fun close(freeRAM: Boolean) {
        if (freeRAM) {
            myRecognizer?.close()
            myRecognizer = null
            model?.close()
            model = null
        }
    }

    override val errorMessage: Int
        get() = 0
    override val name: String
        get() = localModel.locale.displayName ?: ""
    override val locale: Locale
        get() = localModel.locale

    companion object {
        private const val TAG = "SayboardRecognition"
    }
}
