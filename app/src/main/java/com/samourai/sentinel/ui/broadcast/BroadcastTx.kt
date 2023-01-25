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
import com.google.android.material.textfield.TextInputEditText
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.databinding.LayoutBroadcastBottomSheetBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.views.SuccessfulBottomSheet
import com.samourai.sentinel.ui.views.codeScanner.CameraFragmentBottomSheet
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
    private lateinit var binding: LayoutBroadcastBottomSheetBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutBroadcastBottomSheetBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        disableBtn(binding.broadCastTransactionBtn, false)

        if (intent.hasExtra("signedTxHex")) {
            val signedTxHex: String = intent.getStringExtra("signedTxHex").toString()
            signedTxHex.let {
                model.viewModelScope.launch(Dispatchers.Default) {
                    validate(it)
                }
            }
        }

        binding.pasteHex.setOnClickListener { _ ->
            val string = AndroidUtil.getClipBoardString(applicationContext)
            string?.let {
                model.viewModelScope.launch(Dispatchers.Default) {
                    validate(it)
                }
            }
        }

        binding.hexTextView.movementMethod = ScrollingMovementMethod()

        model.hex.observe({ lifecycle }, {
            binding.hexTextView.text = it
        })

        binding.pasteHex.setOnClickListener {
            val clipboardData = AndroidUtil.getClipBoardString(applicationContext)
            clipboardData?.takeIf { it.isNotEmpty() }?.let { string ->
                model.viewModelScope.launch(Dispatchers.Default) {
                    validate(string)
                }
            }
        }
        binding.scanHex.setOnClickListener {
            val camera = CameraFragmentBottomSheet()
            camera.show(supportFragmentManager, camera.tag)
            camera.setQrCodeScanLisenter {
                model.viewModelScope.launch(Dispatchers.Default) {
                    validate(it)
                    camera.dismiss()
                }
            }
        }

        binding.broadCastTransactionBtn.setOnClickListener {
            showLoading(true)
            model.broadCast().invokeOnCompletion {
                model.viewModelScope.launch(Dispatchers.Main) {
                    showLoading(false)
                    if (it == null) {
                        onBroadcastSuccess?.invoke(hash)
                        val bottomSheet = SuccessfulBottomSheet("Broadcast Successful", hash ,onViewReady = {
                            val view1 = it.view
                        })
                        bottomSheet.show(supportFragmentManager, bottomSheet.tag)
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
                disableBtn(binding.broadCastTransactionBtn, false)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.broadcastProgress.visibility = if (show) View.VISIBLE else View.GONE
        binding.broadCastTransactionBtn?.text = if (show) " " else getString(R.string.broadcast_transaction)
        disableAllButtons(!show)
    }

    fun setOnBroadcastSuccess(listener: (hash: String) -> Unit) {
        this.onBroadcastSuccess = listener
    }

    private fun disableAllButtons(enable: Boolean) {
        disableBtn(binding.hexTextView, enable)
        disableBtn(binding.scanHex, enable)
        disableBtn(binding.pasteHex, enable)
        disableBtn(binding.broadCastTransactionBtn, enable)
    }

    private fun disableBtn(button: View, enable: Boolean) {
        button.isEnabled = enable
        button.alpha = if (enable) 1F else 0.5f
    }
}
