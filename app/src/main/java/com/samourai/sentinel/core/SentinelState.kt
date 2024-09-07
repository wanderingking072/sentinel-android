package com.samourai.sentinel.core

import android.os.CountDownTimer
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.samourai.sentinel.data.LatestBlock
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.Preferences
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.TestNet3Params
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.math.BigInteger
import java.net.Proxy
import kotlin.reflect.KProperty

/**
 * Utility class for handling basic app states
 */
class SentinelState {

    companion object {

        private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
        private val dojoUtility: DojoUtility by inject(DojoUtility::class.java)
        private val transactionsRepository: TransactionsRepository by inject(TransactionsRepository::class.java)
        private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
        private val collectionRepository: CollectionRepository by inject(CollectionRepository::class.java)
        private var testnetParams: NetworkParameters? = NetworkParameters.fromID(NetworkParameters.ID_TESTNET)
        private var mainNetParams: NetworkParameters? = NetworkParameters.fromID(NetworkParameters.ID_MAINNET)
        private var networkParams: NetworkParameters? = mainNetParams
        var checkedClipBoard: Boolean = false
        var hasAppJustStarted: Boolean = true

        var blockHeight: LatestBlock? = null
        private var isOffline = false

        private var countDownTimer: CountDownTimer? = null
        var torProxy: Proxy? = null

        //Shared field for passing tx object between activities and fragments
        var selectedTx: Tx? = null

        val bDust: BigInteger = BigInteger.valueOf(Coin.parseCoin("0.00000546").longValue())


        fun getNetworkParam(): NetworkParameters? {
            return this.networkParams
        }

        fun isTestNet(): Boolean {
            return getNetworkParam() is TestNet3Params
        }

        init {
            readPrefs()
            prefsUtil.addListener(object : Preferences.SharedPrefsListener {
                override fun onSharedPrefChanged(property: KProperty<*>) {
                    readPrefs()
                }
            })
        }

        private fun refreshCollection() {
            if (!isRecentlySynced()) {
                exchangeRateRepository.fetch()
                collectionRepository.pubKeyCollections.forEach {
                    val job = apiScope.launch {
                        try {
                            transactionsRepository.fetchFromServer(it)
                        } catch (e: Exception) {
                            Timber.e(e)
                            throw CancellationException(e.message)
                        }
                    }
                    job.invokeOnCompletion {
                        it?.let {
                            Timber.e(it)
                        }
                        if (it == null) {
                            prefsUtil.lastSynced = System.currentTimeMillis()
                        }
                    }
                }
            }

        }

        private fun readPrefs() {
            this.networkParams = if (prefsUtil.testnet!!) testnetParams else mainNetParams
            this.isOffline = prefsUtil.offlineMode!!
        }


        fun isTorRequired(): Boolean {
            return true
            //return dojoUtility.isDojoEnabled() || prefsUtil.enableTor!!
        }

        fun isDojoEnabled(): Boolean {
            return dojoUtility.isDojoEnabled();
        }


        fun isRecentlySynced(): Boolean {
            val lastSync = prefsUtil.lastSynced!!
            val currentTime = System.currentTimeMillis()
            return currentTime.minus(lastSync) < 60000
        }
    }
}
