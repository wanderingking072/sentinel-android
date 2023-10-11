package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.MonetaryUtil
import org.koin.java.KoinJavaComponent
import org.koin.java.KoinJavaComponent.inject

/**
 * sentinel-android
 *
 * @author Sarath
 */

object ExplorerRepository {

    private const val TX_KEY = ":TXID:"

    data class Explorer(val url: String, val name: String, val testnet: Boolean = false, val tor: Boolean = false)

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);

    private val Explorers: ArrayList<Explorer> = arrayListOf(
        Explorer("https://m.oxt.me/transaction/${TX_KEY}", name = "oxt.me"),
        Explorer("http://oxtmblv4v7q5rotqtbbmtbcc5aa5vehr72eiebyamclfo3rco5zm3did.onion/transaction/${TX_KEY}", name = "oxt.me", tor = true),
        Explorer("https://blockstream.info/tx/${TX_KEY}", name = "blockstream.info"),
        Explorer("explorerzydxu5ecjrkwceayqybizmpjjznk5izmitf2modhcusuqlid.onion/tx/${TX_KEY}", name = "blockstream.info-tor", tor = true),
        Explorer("https://blockstream.info/testnet/tx/${TX_KEY}", name = "blockstream.info-testnet",testnet = true),
        Explorer("explorerzydxu5ecjrkwceayqybizmpjjznk5izmitf2modhcusuqlid.onion/testnet/tx/${TX_KEY}", name = "blockstream.info-tor", testnet = true, tor = true),
        Explorer("https://blockchair.com/bitcoin/testnet/transaction/${TX_KEY}", name = "blockstream.info-testnet",testnet = true)
    )

    fun getExplorer(txId: String): String {
        return if (SentinelState.isTestNet()) {
            if (SentinelState.isTorStarted())
                makeUrl(Explorers.first { it.testnet.and(it.tor) }, txId)
            else
                makeUrl(Explorers.first { it.testnet }, txId)
        } else {
            val selection = prefsUtil.selectedExplorer
            val explorer =
                if (SentinelState.isTorStarted())
                    Explorers.first { it.tor.and(it.name == selection) }
                else
                    Explorers.first { it.name == selection }
            makeUrl(explorer, txId)
        }
    }

    private fun makeUrl(explorer: Explorer, txId: String): String {
        return explorer.url.replace(TX_KEY, txId)
    }

    fun getExplorers(): ArrayList<Explorer> {
        return Explorers
    }
}