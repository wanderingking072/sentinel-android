package com.samourai.sentinel.ui.utxos

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.databinding.ActivityUtxoDetailsBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.util.UtxoMetaUtil
import org.koin.java.KoinJavaComponent.bind
import org.koin.java.KoinJavaComponent.inject
import java.text.DecimalFormat

class UtxoDetailsActivity : SentinelActivity() {

    private val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private var collection: PubKeyCollection? = null
    private lateinit var binding: ActivityUtxoDetailsBinding
    private var idx: String? = null
    private var address: String? = null
    private var amount: String? = null
    private var blocked: Boolean? = null
    private val utxoDao: UtxoDao by inject(UtxoDao::class.java)
    val df = DecimalFormat("#")


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUtxoDetailsBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setSupportActionBar(findViewById(R.id.toolbar))
        window.statusBarColor = ContextCompat.getColor(this, R.color.mpm_black)
        binding.toolbarUtxoActivity.setBackgroundColor(ContextCompat.getColor(this, R.color.mpm_black))
        val addressTextView = binding.utxoDetailsAddress
        val amountTextView = binding.utxoDetailsAmount
        val statusTextView = binding.utxoDetailsSpendableStatus
        val hashTextView = binding.utxoDetailsHash
        val df = DecimalFormat("#")

        df.minimumIntegerDigits = 1
        df.minimumFractionDigits = 8
        df.maximumFractionDigits = 8

        if (intent.extras != null && intent.extras!!.containsKey("idx")) {
            idx = intent.extras!!.getString("idx")
        } else {
            finish()
        }

        utxoDao.getUTXObyIdx(idx!!).observe(this@UtxoDetailsActivity) {
            address = it[0].addr
            amountTextView.text = (df.format(it[0].value?.div(1e8))).toString() + " BTC"
            addressTextView.text = it[0].addr
        }
        hashTextView.setText(idx)

        if (isBlocked()) {
            statusTextView.text = getText(R.string.blocked)
        } else {
            statusTextView.text = getText(R.string.spendable)
        }

        addressTextView.setOnClickListener { event: View? ->
            MaterialAlertDialogBuilder(this@UtxoDetailsActivity)
                .setTitle(R.string.app_name)
                .setMessage(R.string.receive_address_to_clipboard)
                .setCancelable(false)
                .setPositiveButton(R.string.yes) { dialog, whichButton ->
                    val clipboard =
                        this@UtxoDetailsActivity.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    var clip: ClipData? = null
                    clip = ClipData.newPlainText("address", address)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        this@UtxoDetailsActivity,
                        R.string.copied_to_clipboard,
                        Toast.LENGTH_SHORT
                    )
                        .show()
                }.setNegativeButton(R.string.no) { dialog, whichButton -> }.show()
        }

        hashTextView.setOnClickListener { view: View? ->
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.txid_to_clipboard)
                .setCancelable(false)
                .setPositiveButton(R.string.yes) { dialog, whichButton ->
                    val clipboard =
                        this@UtxoDetailsActivity.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip: ClipData
                    clip = ClipData.newPlainText("tx id", idx)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        this@UtxoDetailsActivity,
                        R.string.copied_to_clipboard,
                        Toast.LENGTH_SHORT
                    )
                        .show()
                }.setNegativeButton(R.string.no) { dialog, whichButton -> }.show()
        }
    }

    private fun isBlocked(): Boolean {
        val hash = idx!!.split(":")[0]
        val outN = idx!!.split(":")[1]
        return UtxoMetaUtil.has(hash, outN.toInt())
    }

}
