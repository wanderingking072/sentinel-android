@file:Suppress("BlockingMethodInNonBlockingContext")

package com.samourai.sentinel.api

import com.samourai.sentinel.BuildConfig
import com.samourai.sentinel.api.okHttp.await
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.apiScope
import com.samourai.wallet.api.backend.beans.UnspentOutput
import com.samourai.wallet.api.backend.beans.WalletResponse
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.util.Arrays
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


/**
 * sentinel-android
 *
 * @author Sarath
 */

open class ApiService {

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);
    private val dojoUtility: DojoUtility by inject(DojoUtility::class.java);
    private var ACCESS_TOKEN: String? = null
    private val ACCESS_TOKEN_REFRESH = 300L
    lateinit var client: OkHttpClient
    private val  JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val HARDENED = 2147483648


    init {
        try {
            buildClient()
        } catch (_: Exception) {
        } catch (e: ApiNotConfigured) {
            Timber.e(e)
        }
    }


    private fun buildClient(excludeApiKey: Boolean = false, excludeAuthenticator: Boolean = false) {
        client = buildClient(
            excludeApiKey,
            getAPIUrl(),
            this,
            prefsUtil.authorization,
            excludeAuthenticator
        )
    }


    fun authenticateDojo(): Job {
        return apiScope.launch {
            if (dojoUtility.getApiKey() != null) {
                try {
                    val response = authenticateDojo(dojoUtility.getApiKey()!!)
                    if (response.isSuccessful) {
                        val string = response.body?.string()
                        string?.let { dojoUtility.setAuthToken(it) }
                    } else {
                        throw  Throwable(response.message)
                    }
                } catch (e: Exception) {
                    throw  Throwable(e.message)
                }
            }
        }
    }

    suspend fun checkImportStatus(pubKey: String) = withContext(Dispatchers.IO) {
        buildClient(excludeAuthenticator = true)
        val request = Request.Builder()
            .url("${getAPIUrl()}/xpub/${pubKey}/import/status")
            .build()
        val response = client.newCall(request).await()
        val status = response.body?.string()
        if (!status.isNullOrEmpty()) {
            val json = JSONObject(status)
            if (json["status"] == "ok") {
                return@withContext true
            } else {
                try {
                    return@withContext json.getJSONObject("data").getBoolean("import_in_progress")
                } catch (e: Exception) {
                    return@withContext false
                }
            }
        } else {
            return@withContext false
        }
    }

    suspend fun importXpub(pubKey: String, segwit: String): Response {
        val xpub = XPUB(pubKey)
        xpub.decode()
        var segwitValue = segwit

        if (segwit == "44" || segwit == "bip44")
            segwitValue = ""

        if ((xpub.child + HARDENED).toString().equals("2147483646"))
            segwitValue = "bip84"

        buildClient(excludeAuthenticator = true)
        client.newBuilder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)

        val formBody = FormBody.Builder()
            .add("xpub", pubKey)
            .add("segwit", segwitValue)
            .add("type", "restore")
            .build()
        val request = Request.Builder()
            .url("${getAPIUrl()}/xpub")
            .post(formBody)
            .build()

        return client.newCall(request).await()
    }

    suspend fun fetchAddressForSweep(address: String): MutableList<UnspentOutput> {
        val response = getWallet(address)
        val items: WalletResponse = fromJSON<WalletResponse>(response.body!!.string())!!
        return Arrays.asList(*items.unspent_outputs)
    }

    suspend fun importAddress(pubKey: String): Response {

        buildClient(excludeAuthenticator = true)
        client.newBuilder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)

        val formBody = FormBody.Builder()
            .add("xpub", pubKey)
            .add("type", "restore")
            .build()
        val request = Request.Builder()
            .url("${getAPIUrl()}/xpub")
            .post(formBody)
            .build()

        return client.newCall(request).await()
    }


    suspend fun getTxHex(utxoHash: String): Response {
        buildClient(excludeAuthenticator = true)
        client.newBuilder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
        val request = Request.Builder()
            .get()
            .url("${getAPIUrl()}/tx/$utxoHash/HEX")
            .build()
        return client.newCall(request).await()
    }


    suspend fun authenticateDojo(apiKey: String): Response {
        buildClient()
        val formBody = FormBody.Builder()
            .add("apikey", apiKey)
            .build()
        val request = Request.Builder()
            .post(formBody)
            .url("${getAPIUrl()}/auth/login")
            .build()
        return client.newCall(request).await()
    }


    suspend fun getTx(txId: String): Response {
        buildClient()
        val request = Request.Builder()
            .url("${getAPIUrl()}/tx/${txId}?fees=1")
            .build()
        return client.newCall(request).await()
    }


    suspend fun getFees(): Response {
        buildClient()
        val request = Request.Builder()
            .url("${getAPIUrl()}/fees")
            .build()
        return client.newCall(request).await()
    }


    suspend fun getWallet(pubKey: String): Response {
        buildClient()
        val request = Request.Builder()
            .url("${getAPIUrl()}/wallet?active=${pubKey}")
            .build()
        return client.newCall(request).await()
    }


    suspend fun request(request: Request, excludeApiKey: Boolean = true): Response {
        buildClient(excludeApiKey=excludeApiKey)
        return client.newCall(request).await()
    }


    public fun getAPIUrl(): String? {
        return if (SentinelState.isTorRequired()) {
            if (prefsUtil.apiEndPointTor == null) {
                throw  ApiNotConfigured()
            }
            prefsUtil.apiEndPointTor
        } else {
            if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
                return prefsUtil.apiEndPointTor
            }
            if (prefsUtil.apiEndPoint == null) {
                throw  ApiNotConfigured()
            }
            prefsUtil.apiEndPoint
        }
    }


    fun setAccessToken(accessToken: String?) {
        this.ACCESS_TOKEN = accessToken
    }

    suspend fun broadcast(hex: String): String {
        buildClient()
        val formBody: RequestBody = FormBody.Builder()
            .add("tx", hex.trim())
            .build()
        val request = Request.Builder()
            .url("${getAPIUrl()}/pushtx/")
            .post(formBody)
            .build()
        val response = client.newCall(request).await()
        val string = response.body?.string() ?: "{}"
        val json = JSONObject(string)
        return if (response.isSuccessful) {
            if (json.has("status") && json.getString("status").equals("ok")) {
                json.getString("data")
            } else {
                "TX_ID_NOT_FOUND"
            }
        } else {
            if (json.has("status") && json.getString("status").equals("error")) {
                throw  Exception(json.getJSONObject("error").getString("message"))
            } else {
                throw InvalidResponse()
            }
        }
    }

    class ApiNotConfigured : Throwable(message = "Api endpoint is not configured")
    class InvalidResponse : Throwable(message = "Invalid response")

    companion object {
        fun buildClient(
            excludeApiKey: Boolean = false, url: String?,
            apiService: ApiService?,
            authToken: String?,
            excludeAuthenticator: Boolean = false,
        ): OkHttpClient {
            val builder = OkHttpClient.Builder()
            if (BuildConfig.DEBUG) {
                builder.addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            }
            builder.callTimeout(60, TimeUnit.SECONDS)
            builder.readTimeout(60, TimeUnit.SECONDS)
            builder.readTimeout(90, TimeUnit.SECONDS)
            builder.connectTimeout(120, TimeUnit.SECONDS)
            if (url != null && apiService != null) {
                if (!excludeAuthenticator)
                    builder.authenticator(TokenAuthenticator(apiService))
            }
            if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
                builder.callTimeout(90, TimeUnit.SECONDS)
                builder.readTimeout(90, TimeUnit.SECONDS)
                builder.readTimeout(90, TimeUnit.SECONDS)
                builder.connectTimeout(120, TimeUnit.SECONDS)
                getHostNameVerifier(builder)
                builder.proxy(SentinelTorManager.getProxy())
            }

            /**
             * Intercept current request and add apiKey if needed
             * for more please refer https://code.samourai.io/dojo/samourai-dojo/-/blob/master/doc/POST_auth_login.md#authentication
             */
            if (!excludeApiKey) {
                try {
                    builder.addInterceptor(Interceptor { chain ->
                        val original = chain.request()
                        val newBuilder = original.newBuilder()
                        if (!authToken.isNullOrEmpty() && SentinelState.isDojoEnabled()) {
                            newBuilder.url(
                                original.url.newBuilder()
                                    .addQueryParameter("at", authToken)
                                    .build()
                            )
                        }
                        val request = newBuilder.build()
                        chain.proceed(request)
                    })
                } catch (_:Exception) {}
            }
            return builder.build()
        }

        @Throws(Exception::class)
        protected fun getHostNameVerifier(clientBuilder: OkHttpClient.Builder) {

            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String
                ) {
                }

                override fun checkServerTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String
                ) {
                }

                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                    return arrayOf()
                }
            })

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory = sslContext.socketFactory


            clientBuilder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            clientBuilder.hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
    }


}
