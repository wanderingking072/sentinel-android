package com.samourai.sentinel.ui.utxos

import android.os.Bundle
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.databinding.ActivityUtxoDetailsBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.util.BlockedUTXO
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
        val addressTextView = binding.utxoDetailsAddress
        val amountTextView = binding.utxoDetailsAmount
        val statusTextView = binding.utxoDetailsSpendableStatus
        val hashTextView = binding.utxoDetailsHash

        if (intent.extras != null && intent.extras!!.containsKey("idx")) {
            idx = intent.extras!!.getString("idx")
        } else {
            finish()
        }

        utxoDao.getUTXObyIdx(idx!!).observe(this@UtxoDetailsActivity) {
            amountTextView.text = (it[0].value)?.div(1e8).toString() + " BTC"
            addressTextView.text = it[0].addr
        }


        if (isBlocked()) {
            statusTextView.text = getText(R.string.blocked)
        } else {
            statusTextView.text = getText(R.string.spendable)
        }
        hashTextView.setText(idx)
    }

    private fun isBlocked(): Boolean {
        val hash = idx!!.split(":")[0]
        val outN = idx!!.split(":")[1]
        return BlockedUTXO.getInstance().contains(hash, outN.toInt())
    }

}
