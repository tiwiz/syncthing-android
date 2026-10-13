package com.nutomic.syncthingandroid.http

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.volley.Request
import com.android.volley.VolleyError
import java.net.ConnectException
import java.net.URL

/** Polls to load the web interface, until it is available. */
class PollWebGuiAvailableTask(
    context: Context,
    url: URL,
    apiKey: String?,
    listener: OnSuccessListener,
) : ApiRequest(context, url, "", apiKey) {

    private val handler = Handler(Looper.getMainLooper())

    /** Guarded by [listenerLock]. */
    private var listener: OnSuccessListener? = listener
    private val listenerLock = Any()

    private var logIncidence = 0

    init {
        Log.i(TAG, "Starting to poll for web gui availability")
        performRequest()
    }

    fun cancelRequestsAndCallback() {
        synchronized(listenerLock) { listener = null }
    }

    private fun performRequest() {
        connect(Request.Method.GET, buildUri(emptyMap()), null, ::onSuccess, ::onError)
    }

    private fun onSuccess(result: String) {
        synchronized(listenerLock) {
            val current = listener
            if (current != null) {
                current.onSuccess(result)
            } else {
                Log.v(TAG, "Cancelled callback and outstanding requests")
            }
        }
    }

    private fun onError(error: VolleyError) {
        synchronized(listenerLock) {
            if (listener == null) {
                Log.v(TAG, "Cancelled callback and outstanding requests")
                return
            }
        }
        handler.postDelayed(::performRequest, WEB_GUI_POLL_INTERVAL)
        val cause = error.cause
        if (cause == null || cause.javaClass == ConnectException::class.java) {
            // Reduce lag caused by massively logging the same line while waiting.
            logIncidence++
            if (logIncidence == 1 || logIncidence % LOG_EVERY == 0) {
                Log.v(TAG, "Polling web gui ... ($logIncidence)")
            }
        } else {
            Log.w(TAG, "Unexpected error while polling web gui", error)
        }
    }

    private companion object {
        const val TAG = "PollWebGuiAvailableTask"

        /**
         * Interval in ms, at which connections to the web gui are performed on first start to find
         * out if it's online.
         */
        const val WEB_GUI_POLL_INTERVAL = 150L

        const val LOG_EVERY = 10
    }
}
