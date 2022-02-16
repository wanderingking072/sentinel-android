package com.samourai.sentinel.ui.broadcast

import android.content.Intent
import android.os.Bundle
import com.samourai.sentinel.R
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.fragments.QRBottomSheetDialog
import com.samourai.sentinel.ui.views.codeScanner.CameraFragmentBottomSheet
import kotlinx.android.synthetic.main.activity_broadcast_unsigned_tx.*


class BroadcastFromComposeTx : SentinelActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_broadcast_unsigned_tx)
        val qrString: String = intent.getStringExtra("qrString").toString()
        var signedTxHex: String

        showQrCode.setOnClickListener { _ ->
            val dialog = QRBottomSheetDialog(
                    qrString,
                    "Unsigned Transaction",
                    qrString)
            dialog.show(supportFragmentManager, dialog.tag)
            step_view.setStep(2)
        }

        launchQRScanner.setOnClickListener { _ ->
            val camera = CameraFragmentBottomSheet()
            camera.show(supportFragmentManager, camera.tag)
            camera.setQrCodeScanLisenter {
                signedTxHex = it
                val intent = Intent(applicationContext, BroadcastTx::class.java).putExtra("signedTxHex", signedTxHex)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK.or(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                camera.dismiss()
            }
        }


    }
}