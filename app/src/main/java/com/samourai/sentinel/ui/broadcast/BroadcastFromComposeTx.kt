package com.samourai.sentinel.ui.broadcast

import android.content.Intent
import android.os.Bundle
import com.samourai.sentinel.databinding.ActivityBroadcastUnsignedTxBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.fragments.QRBottomSheetDialog
import com.samourai.sentinel.ui.views.codeScanner.CameraFragmentBottomSheet


class BroadcastFromComposeTx : SentinelActivity() {

    private lateinit var binding: ActivityBroadcastUnsignedTxBinding


    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        binding = ActivityBroadcastUnsignedTxBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        val qrString: String = intent.getStringExtra("qrString").toString()
        var signedTxHex: String

        binding.showQrCode.setOnClickListener { _ ->
            val dialog = QRBottomSheetDialog(
                    qrString,
                    "Unsigned Transaction",
                    qrString)
            dialog.show(supportFragmentManager, dialog.tag)
            binding.stepView.setStep(2)
        }

        binding.launchQRScanner.setOnClickListener { _ ->
            binding.stepView.setStep(3)
            val camera = CameraFragmentBottomSheet()
            camera.show(supportFragmentManager, camera.tag)
            camera.setQrCodeScanLisenter {
                signedTxHex = it
                val intent = Intent(applicationContext, BroadcastTx::class.java).putExtra("signedTxHex", signedTxHex)
                startActivity(intent)
                camera.dismiss()
            }
        }


    }
}