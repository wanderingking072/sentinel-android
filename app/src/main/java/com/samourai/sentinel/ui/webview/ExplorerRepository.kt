package com.samourai.sentinel.ui.webview

import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.MonetaryUtil
import org.koin.java.KoinJavaComponent
import org.koin.java.KoinJavaComponent.inject

/**
 * sentinel-android
 *
 * @author Sarath
 */

class ExplorerRepository {

    private val TX_KEY = ":TXID:"

    data class Explorer(val url: String, val name: String, val testnet: Boolean = false, val tor: Boolean = false)

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);

    private val Explorers: ArrayList<Explorer> = arrayListOf(
        //Explorer("https://m.oxt.me/transaction/${TX_KEY}", name = "oxt.me"),
        //Explorer("http://oxtmblv4v7q5rotqtbbmtbcc5aa5vehr72eiebyamclfo3rco5zm3did.onion/transaction/${TX_KEY}", name = "oxt.me", tor = true),
        //Explorer("https://blockstream.info/tx/${TX_KEY}", name = "blockstream.info"),
        Explorer("explorerzydxu5ecjrkwceayqybizmpjjznk5izmitf2modhcusuqlid.onion/tx/${TX_KEY}", name = "Blockstream", tor = true),
        Explorer("https://blockstream.info/testnet/tx/${TX_KEY}", name = "Blockstream",testnet = true),

        Explorer("https://blockchair.com/bitcoin/testnet/transaction/${TX_KEY}", name = "Blockchair",testnet = true),
        Explorer("http://blkchairbknpn73cfjhevhla7rkp4ed5gg2knctvv7it4lioy22defid.onion/bitcoin/transaction/${TX_KEY}", "Blockchair", tor = true)
    )

    fun getExplorer(txId: String): String {
        return if (SentinelState.isTestNet()) {
            val selection = prefsUtil.selectedExplorer
            makeUrl(Explorers.first { it.testnet.and(it.name == selection) }, txId)
        } else {
            val selection = prefsUtil.selectedExplorer
            val explorer =
                if (SentinelTorManager.getTorState().state == EnumTorState.ON)
                    Explorers.first { it.tor.and(it.name == selection) }
                else
                    Explorers.first { it.name == selection }
            makeUrl(explorer, txId)
        }
    }

    private fun makeUrl(explorer: Explorer, txId: String): String {
        return explorer.url.replace(TX_KEY, txId)
    }

    fun getExplorers(): ArrayList<String> {
        return ArrayList<String>().apply {
            this.addAll(Explorers.filter {
                it.tor
            }.map { it.name }.toTypedArray())
        }
    }
}