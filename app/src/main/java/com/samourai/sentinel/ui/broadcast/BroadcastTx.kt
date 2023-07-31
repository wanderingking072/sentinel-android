package com.samourai.sentinel.ui.broadcast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.invertedx.hummingbird.QRScanner
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.databinding.LayoutBroadcastBottomSheetBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.views.SuccessfulBottomSheet
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext))
                this.askCameraPermission()
            else {
                scanTx()
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

    private fun scanTx() {
        val camera = ScanTxFragment()
        camera.show(supportFragmentManager, "scanner_tag")
        camera.setOnScanListener {
            model.viewModelScope.launch(Dispatchers.Default) {
                validate(it)
                camera.dismiss()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == Companion.CAMERA_PERMISSION && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanTx()
        } else {
            if (requestCode == Companion.CAMERA_PERMISSION && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_LONG).show()
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

class ScanTxFragment : BottomSheetDialogFragment() {

    private lateinit var  mCodeScanner: QRScanner;
    private val appContext: Context by inject(Context::class.java)
    private var onScan: (scanData: String) -> Unit = {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.content_scan_layout, container, false)
    }

    fun setOnScanListener(callback: (scanData: String) -> Unit) {
        this.onScan = callback
    }


    @SuppressLint("RestrictedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.pastePubKey).alpha = 0f
        mCodeScanner = view.findViewById(R.id.scannerViewXpub);
        mCodeScanner.setLifeCycleOwner(this)

        mCodeScanner.setQRDecodeListener {
            GlobalScope.launch(Dispatchers.Main) {
                    onScan(it)
            }
        }

        mCodeScanner.setURDecodeListener { result ->
            mCodeScanner.stopScanner()
            result.fold(
                onSuccess = {
                    val cryptoPSBT = it.ur.decodeFromRegistry() as CryptoPSBT
                    onScan(String(Hex.encode(cryptoPSBT.psbt)))
                },
                onFailure = {
                    mCodeScanner.stopScanner()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Error decoding UR")
                        .setMessage("Exception: ${it.message}")
                        .setPositiveButton("Ok") { dialog, which ->
                            dialog.dismiss()
                        }.show()
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, appContext)) {
            mCodeScanner.startScanner()
        }
    }

    override fun onPause() {
        mCodeScanner.stopScanner()
        super.onPause()
    }

}
