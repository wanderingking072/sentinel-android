package com.samourai.sentinel.ui.broadcast

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.views.codeScanner.CameraFragmentBottomSheet
import kotlinx.android.synthetic.main.layout_broadcast_bottom_sheet.*
import kotlinx.coroutines.*
import org.bitcoinj.core.Transaction
import org.bouncycastle.util.encoders.Hex
import org.koin.java.KoinJavaComponent.inject

class BroadcastTx : SentinelActivity() {

    class BroadcastVm : ViewModel() {

        private val _hex = MutableLiveData("")
        val hex: LiveData<String> get() = _hex
        private val apiService: ApiService by inject(ApiService::class.java)
        fun broadCast(): Job {
            return viewModelScope.launch(Dispatchers.IO) {
                hex.value?.let {
                    try {
                        apiService.broadcast(it)
                    } catch (e: Exception) {
                        throw CancellationException(e.message)
                    }
                }
            }
        }

        fun setHex(hex: String) {
            _hex.postValue(hex)
        }
    }

    private var onBroadcastSuccess: ((hash: String) -> Unit)? = null

    private val model: BroadcastVm by viewModels()
    private var hash = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_broadcast_bottom_sheet)

        disableBtn(broadCastTransactionBtn, false)

        if (intent.hasExtra("signedTxHex")) {
            val signedTxHex: String = intent.getStringExtra("signedTxHex").toString()
            signedTxHex.let {
                model.viewModelScope.launch(Dispatchers.Default) {
                    validate(it)
                }
            }
        }

        pasteHex.setOnClickListener { _ ->
            val string = AndroidUtil.getClipBoardString(applicationContext)
            string?.let {
                model.viewModelScope.launch(Dispatchers.Default) {
                    validate(it)
                }
            }
        }

        hexTextView.movementMethod = ScrollingMovementMethod()

        model.hex.observe({ lifecycle }, {
            hexTextView.text = it
        })

        pasteHex.setOnClickListener {
            val clipboardData = AndroidUtil.getClipBoardString(applicationContext)
            clipboardData?.takeIf { it.isNotEmpty() }?.let { string ->
                model.viewModelScope.launch(Dispatchers.Default) {
                    validate(string)
                }
            }
        }
        scanHex.setOnClickListener {
            val camera = CameraFragmentBottomSheet()
            camera.show(supportFragmentManager, camera.tag)
            camera.setQrCodeScanLisenter {
                model.viewModelScope.launch(Dispatchers.Default) {
                    validate(it)
                    camera.dismiss()
                }
            }
        }

        broadCastTransactionBtn.setOnClickListener {
            showLoading(true)
            model.broadCast().invokeOnCompletion {
                model.viewModelScope.launch(Dispatchers.Main) {
                    showLoading(false)
                    if (it == null) {
                        onBroadcastSuccess?.invoke(hash)
                        Toast.makeText(
                                applicationContext,
                                hash,
                                Toast.LENGTH_SHORT
                        ).show()
                        model.setHex("")
                    } else {
                        Toast.makeText(
                                applicationContext,
                                "Unable to broadcast ${it.message}",
                                Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

    }

    private suspend fun validate(hex: String) {
        try {
            val transaction = Transaction(SentinelState.getNetworkParam(), Hex.decode(hex))
            hash = transaction.hashAsString
            withContext(Dispatchers.Main) {
                disableAllButtons(true)
                model.setHex(hex)
            }

        } catch (ex: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "Text in clipboard is not a hex transaction", Toast.LENGTH_LONG).show()
                disableBtn(broadCastTransactionBtn, false)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        broadcastProgress.visibility = if (show) View.VISIBLE else View.GONE
        broadCastTransactionBtn?.text = if (show) " " else getString(R.string.broadcast_transaction)
        disableAllButtons(!show)
    }

    fun setOnBroadcastSuccess(listener: (hash: String) -> Unit) {
        this.onBroadcastSuccess = listener
    }

    private fun disableAllButtons(enable: Boolean) {
        disableBtn(hexTextView, enable)
        disableBtn(scanHex, enable)
        disableBtn(pasteHex, enable)
        disableBtn(broadCastTransactionBtn, enable)
    }

    private fun disableBtn(button: View, enable: Boolean) {
        button.isEnabled = enable
        button.alpha = if (enable) 1F else 0.5f
    }

}