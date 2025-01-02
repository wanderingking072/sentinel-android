package com.samourai.sentinel.data.exchange

import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.ui.utils.PrefsUtil
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import kotlin.coroutines.cancellation.CancellationException

class CryptoCompareExchangeProvider : ExchangeProviderImpl {

    private val endpoint = "https://min-api.cryptocompare.com/data/price?fsym=BTC&tsyms="
    private val apiService: ApiService by inject(ApiService::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private val availableCurrencies = arrayListOf(
        "USD",
        "EUR",
        "GBP",
        "JPY",
        "RUB",
        "CHF",
        "CNY"
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
        return "cryptocompare.com"
    }

    override suspend fun fetch() {
        try {
            val request = Request.Builder()
                .url(endpoint + prefsUtil.selectedCurrency?.lowercase())
                .build()
            try {
                val response = apiService.request(request)
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    body?.let { responseBody ->
                        val jsonObject = JSONObject(responseBody)
                        var avgPrice: Long? = null
                        when {
                            jsonObject.has(prefsUtil.selectedCurrency!!) -> {
                                avgPrice = jsonObject.getLong(prefsUtil.selectedCurrency!!);
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
