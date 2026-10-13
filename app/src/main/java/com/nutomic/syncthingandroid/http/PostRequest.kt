package com.nutomic.syncthingandroid.http

import android.content.Context
import com.android.volley.Request
import java.net.URL

/** Performs a POST request to the Syncthing API. */
class PostRequest(
    context: Context,
    url: URL,
    path: String,
    apiKey: String?,
    params: Map<String, String>?,
    postBody: String?,
    listener: OnSuccessListener?,
) : ApiRequest(context, url, path, apiKey) {

    init {
        connect(Request.Method.POST, buildUri(params.orEmpty()), postBody, listener, null)
    }

    companion object {
        const val URI_DB_IGNORES = "/rest/db/ignores"
        const val URI_DB_OVERRIDE = "/rest/db/override"
        const val URI_DB_REVERT = "/rest/db/revert"
        const val URI_DB_SCAN = "/rest/db/scan"
        const val URI_SYSTEM_CONFIG = "/rest/system/config"
        const val URI_SYSTEM_SHUTDOWN = "/rest/system/shutdown"
    }
}
