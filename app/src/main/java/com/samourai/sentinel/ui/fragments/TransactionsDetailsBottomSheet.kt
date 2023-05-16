package com.samourai.sentinel.ui.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.databinding.ContentTransactionsDetailsBinding
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.ui.webview.ExplorerWebViewActivity
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import java.text.SimpleDateFormat
import java.util.*

/**
 * sentinel-android
 *
 * @author Sarath
 *
 */


class TransactionsDetailsBottomSheet(private var tx: Tx, val secure: Boolean = false) : GenericBottomSheet(secure = secure) {

    data class TxFeeData(val fee: Long?, val feeRate: Long?, val size: Long?)

    private val apiService: ApiService by inject(ApiService::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
    var job: Job? = null

    private var _binding: ContentTransactionsDetailsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        _binding = ContentTransactionsDetailsBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.txDetailsOpenInExplorerBtn.setOnClickListener {
            SentinelState.selectedTx = tx
            startActivity(Intent(requireContext(), ExplorerWebViewActivity::class.java))
        }
        binding.txDetailsOpenInExplorerBtn2.setOnClickListener {
            SentinelState.selectedTx = tx
            startActivity(Intent(requireContext(), ExplorerWebViewActivity::class.java))
        }
        setTx(tx)
        fetchFee()

        binding.txDetailsBlockId.setOnClickListener { copyToClipBoard(binding.txDetailsBlockId) }
        binding.txDetailsConfirmation.setOnClickListener { copyToClipBoard(binding.txDetailsConfirmation) }
        binding.txDetailsFees.setOnClickListener { copyToClipBoard(binding.txDetailsFees) }
        binding.txDetailsHash.setOnClickListener { copyToClipBoard(binding.txDetailsHash) }
        binding.txDetailsFeeRate.setOnClickListener { copyToClipBoard(binding.txDetailsFeeRate) }
        binding.txDetailsAmount.setOnClickListener { copyToClipBoard(binding.txDetailsAmount) }
    }

    private fun setTx(tx: Tx) {
        val fmt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
        fmt.timeZone = TimeZone.getDefault()
        binding.txDetailsAmount.text = "${MonetaryUtil.getInstance().formatToBtc(tx.result)} BTC | ${getFiatBalance(tx.result, exchangeRateRepository.getRateLive().value)} "
        binding.txDetailsBlockId.text = "${tx.block_height ?: "__"}"
        var latestBlockHeight = 1L
        if (SentinelState.blockHeight != null)
            latestBlockHeight = SentinelState.blockHeight?.height!!
        val txBlockHeight = tx.block_height ?: 0
        binding.txDetailsConfirmation.text =
                if (latestBlockHeight > 0L && txBlockHeight > 0L) ((latestBlockHeight.minus(
                    txBlockHeight
                )) + 1).toString() else 0.toString()
        if (tx.result != null)
            binding.txDetailsTime.text = "${fmt.format(Date(tx.time * 1000))} "
        binding.txDetailsHash.text = tx.hash

    }


    private fun setFeeDetails(txFeeData: TxFeeData) {
        binding.txDetailsFeesProgress.visibility = View.GONE
        binding.txDetailsFeesRateProgress.visibility = View.GONE
        binding.txDetailsFees.text = txFeeData.fee.toString()
        binding.txDetailsFeeRate.text = txFeeData.feeRate.toString()
        binding.txDetailsSize.text = txFeeData.size.toString()
    }

    private fun fetchFee() {
        job = apiScope.launch(Dispatchers.IO) {
            try {
                val response = apiService.getTx(tx.hash)
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val jsonObject = JSONObject(body)
                        var fee = 0L
                        var feeRate = 0L
                        var size = 0L
                        if (jsonObject.has("fees")) {
                            fee = jsonObject.getLong("fees")
                        }
                        if (jsonObject.has("vfeerate")) {
                            feeRate = jsonObject.getLong("vfeerate")
                        }
                        if (jsonObject.has("size")) {
                            size = jsonObject.getLong("size")
                        }
                        val txFeeData = TxFeeData(fee, feeRate, size)
                        withContext(Dispatchers.Main) {
                            setFeeDetails(txFeeData)
                        }
                    }
                }
            } catch (ex: Exception) {
                throw CancellationException(ex.message)
            }
        }
        job?.invokeOnCompletion {
            if (it != null)
                CoroutineScope(Dispatchers.Main).launch {
                    if (!(it is CancellationException))
                        Toast.makeText(requireContext(), "Error: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun getFiatBalance(balance: Long?, rate: ExchangeRateRepository.Rate?): String {
        if (rate != null) {
            balance?.let {
                return try {
                    val fiatRate = MonetaryUtil.getInstance().getFiatFormat(prefsUtil.selectedCurrency)
                            .format((balance / 1e8) * rate.rate)
                    "$fiatRate ${rate.currency}"
                } catch (e: Exception) {
                    "00.00 ${rate.currency}"
                }
            }
            return "00.00"
        } else {
            return "00.00"
        }
    }


    private fun copyToClipBoard(txtView: TextView) {
        val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        val clipData = ClipData
                .newPlainText("", (txtView).text)
        if (cm != null) {
            cm.setPrimaryClip(clipData)
            Toast.makeText(context, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        job?.let {
            if (it.isActive) it.cancel()
        }
        super.onDestroy()
    }


}