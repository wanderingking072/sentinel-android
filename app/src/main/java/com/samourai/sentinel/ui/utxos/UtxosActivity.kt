package com.samourai.sentinel.ui.utxos

import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.ViewPager
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.databinding.ActivityUtxosBinding
import com.samourai.sentinel.ui.SentinelActivity
import org.koin.java.KoinJavaComponent.inject

class UtxosActivity : SentinelActivity() {

    private val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private var collection: PubKeyCollection? = null
    private var pubKeys: ArrayList<PubKeyModel> = arrayListOf()
    private val utxoFragments: MutableMap<String, UTXOFragment> = mutableMapOf()
    private lateinit var binding: ActivityUtxosBinding
    private var indexPubSelected = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUtxosBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setSupportActionBar(findViewById(R.id.toolbar))
        checkIntent()
        setUpToolbar()

        val utxoViewModel: UtxoActivityViewModel by viewModels(factoryProducer = { UtxoActivityViewModel.getFactory(collection!!) })
        setUpPager(utxoViewModel)

        binding.pager.post {
            utxoViewModel.getPubKeys().observe(this) {
                pubKeys.clear()
                pubKeys.addAll(it)
                binding.pager.adapter?.notifyDataSetChanged()
                listenChanges(utxoViewModel)
            }
        }
        //wait for pager to get ready before setting the index
        binding.pager.post {
            binding.pager.setCurrentItem(indexPubSelected,true)
        }
    }

    private fun getPubKeyModelByLabel(label: String): PubKeyModel {
        collection!!.pubs.forEach {
            if (it.label == label)
                return it
        }
        return collection!!.pubs[0]
    }

    private fun listenChanges(utxoViewModel: UtxoActivityViewModel) {
        if (collection!!.isImportFromWallet) {
            pubKeys.forEach { pubKeyModel ->
                if (pubKeys.indexOf(pubKeyModel) == 0) {
                    utxoViewModel.getUtxoByPubOnly(listOf(
                        getPubKeyModelByLabel("Deposit BIP84").pubKey,
                        getPubKeyModelByLabel("Deposit BIP49").pubKey,
                        getPubKeyModelByLabel("Deposit BIP44").pubKey))
                        .observe(this@UtxosActivity) { utxoFragments[pubKeyModel.pubKey]?.setUtxos(ArrayList(it.sortedBy { it.value }))
                    }
                }
                else {
                    utxoViewModel.getUtxoByPubOnly(pubKeyModel.pubKey).observe(this@UtxosActivity) {
                        utxoFragments[pubKeyModel.pubKey]?.setUtxos(ArrayList(it.sortedBy { it.value }))
                    }
                }
            }
        }
        else {
            pubKeys.forEach { pubKeyModel ->
                utxoViewModel.getUtxoByPubOnly(pubKeyModel.pubKey).observe(this@UtxosActivity) {
                    utxoFragments[pubKeyModel.pubKey]?.setUtxos(ArrayList(it.sortedBy { it.value }))
                }
            }
        }
    }


    private fun setUpToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.title = "Unspent outputs"
        binding.toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.mpm_black))
        window.statusBarColor = ContextCompat.getColor(this, R.color.mpm_black)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.grey_homeActivity)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun checkIntent() {
        if (intent == null) {
            finish()
            return
        }
        if (intent.extras != null && intent.extras!!.containsKey("collection")) {
            val model = intent.extras?.getString("collection")?.let { repository.findById(it) }
            if (model != null) {
                collection = model
            } else {
                finish()
            }
        }

        if (intent.extras != null && intent.extras!!.containsKey("indexPub")) {
            indexPubSelected = intent.extras?.getInt("indexPub")!! - 1
         }
    }

    private fun setUpPager(utxoViewModel: UtxoActivityViewModel) {
        binding.tabLayout.setupWithViewPager(binding.pager)
        binding.pager.setBackgroundColor( ContextCompat.getColor(this, R.color.grey_homeActivity))
        binding.pager.adapter = object : FragmentStatePagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
            override fun getCount(): Int {
                return if (collection!!.isImportFromWallet)
                    pubKeys.size - 2
                else
                    return pubKeys.size
            }

            override fun getItem(position: Int): Fragment {
                if (!utxoFragments.containsKey(pubKeys[position].pubKey)) {
                    utxoFragments[pubKeys[position].pubKey] = UTXOFragment()
                }
                return utxoFragments[pubKeys[position].pubKey]!!
            }

            override fun getPageTitle(position: Int): CharSequence {
                if (collection!!.isImportFromWallet) {
                    when (position) {
                        0 -> return "Deposit"
                        1 -> return "Premix"
                        2 -> return "Postmix"
                        3 -> return "Badbank"
                    }
                    return pubKeys[position].label
                }
                else
                    return pubKeys[position].label
            }
        }
        binding.pager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                if (collection!!.isImportFromWallet) {
                    when (position) {
                        0 -> {
                            utxoViewModel.getUtxoByPubOnly(listOf(
                                pubKeys[0].pubKey,
                                pubKeys[4].pubKey,
                                pubKeys[5].pubKey)).observe(this@UtxosActivity) {
                                utxoFragments[pubKeys[position].pubKey]?.setUtxos(ArrayList(it.sortedBy { it.value }))
                            }
                        }
                        1 -> {
                            utxoViewModel.getUtxoByPubOnly(pubKeys[1].pubKey).observe(this@UtxosActivity) {
                                utxoFragments[pubKeys[position].pubKey]?.setUtxos(ArrayList(it.sortedBy { it.value }))
                            }
                        }
                        2 -> {
                            utxoViewModel.getUtxoByPubOnly(pubKeys[2].pubKey).observe(this@UtxosActivity) {
                                utxoFragments[pubKeys[position].pubKey]?.setUtxos(ArrayList(it.sortedBy { it.value }))
                            }
                        }
                        3 -> {
                            utxoViewModel.getUtxoByPubOnly(pubKeys[3].pubKey).observe(this@UtxosActivity) {
                                utxoFragments[pubKeys[position].pubKey]?.setUtxos(ArrayList(it.sortedBy { it.value }))
                            }
                        }
                    }

                }
                else {
                    utxoViewModel.getUtxoByPubOnly(pubKeys[position].pubKey).observe(this@UtxosActivity) {
                        utxoFragments[pubKeys[position].pubKey]?.setUtxos(ArrayList(it.sortedBy { it.value }))
                    }
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                utxoFragments.values.forEach {
                    it.clearSelection()
                }
            }

        })
    }


}
