package com.samourai.sentinel.data.exchange

import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.ui.utils.PrefsUtil
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.koin.java.KoinJavaComponent
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * sentinel-android
 *
 * @author Sarath
 */
class BitFinexExchangeProvider : ExchangeProviderImpl {

    private val bitFinexEndPoint = "https://api.bitfinex.com/v1/pubticker/btc"
    private val apiService: ApiService by inject(ApiService::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private val availableCurrencies = arrayListOf(
            "USD",
            "EUR",
            "GBP",
            "JPY"
    )
    private var rate: Long = 1L

    override fun getRate(): Long {
        if (prefsUtil.selectedCurrency.isNullOrBlank()) {
            return 1L
        }
        return rate
    }

    override fun parse(response: Response) {

    }

    override fun getCurrencies(): ArrayList<String> {
        return availableCurrencies
    }

    override fun getCurrency(): String {
        return prefsUtil.selectedCurrency!!
    }

    override fun setRate(rate: Long) {
        this.rate = rate
        prefsUtil.exchangeRate = rate
    }

    override fun getKey(): String {
        return "bitfinex.com "
    }

    override suspend fun fetch() {
        try {
            val request = Request.Builder()
                    .url(bitFinexEndPoint + prefsUtil.selectedCurrency?.lowercase())
                    .build()
            try {
                val response = apiService.request(request)
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    body?.let { responseBody ->
                        val jsonObject = JSONObject(responseBody)
                            var avgPrice: Long? = null
                            when {
                                jsonObject.has("mid") -> {
                                    avgPrice = jsonObject.getLong("mid");
                                }
                                jsonObject.has("ask") -> {
                                    avgPrice = jsonObject.getLong("ask");
                                }
                                jsonObject.has("last_price") -> {
                                    avgPrice = jsonObject.getLong("last_price");
                                }
                            }
                            avgPrice?.let {
                                setRate(it)
                            }
                    }
                }
            } catch (e: ApiService.ApiNotConfigured) {
                throw CancellationException(e.message)
            }

        } catch (e: Exception) {
            throw CancellationException(e.message)
        }
    }
}