package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.api.okHttp.await
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.tor.SentinelTorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches the community Dojo directory (Dojo Bay) over the app's embedded Tor proxy.
 * Callers are responsible for making sure Tor is bootstrapped first
 * (see [SentinelTorManager]) - fetchDirectory() itself only reads the current proxy.
 */
object CommunityDojoRepository {

    suspend fun fetchDirectory(): List<CommunityDojoNode> = withContext(Dispatchers.IO) {
        val proxy = SentinelTorManager.getProxy()
            ?: throw IllegalStateException("Tor is not connected")

        val client = OkHttpClient.Builder()
            .proxy(proxy)
            // Dojo Bay probes every listed node over Tor before answering, so the
            // request can take a while to build a response - same 120s allowance
            // used elsewhere in the app for onion requests (see ApiService).
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(DojoBayConstants.DIRECTORY_JSON_URL)
            .build()

        val response = client.newCall(request).await()
        val body = response.body?.string()
        if (!response.isSuccessful || body.isNullOrBlank()) {
            throw IllegalStateException("Dojo Bay returned HTTP ${response.code}")
        }

        val directory = fromJSON<CommunityDojoDirectory>(body)
            ?: throw IllegalStateException("Could not parse the Dojo Bay directory")

        directory.nodes.orEmpty()
    }
}
