package com.samourai.sentinel.ui.collectionDetails.transactions

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.databinding.FragmentTransactionsBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.collectionEdit.CollectionEditActivity
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.utxos.UtxosActivity
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.PrefsUtil
import org.bitcoinj.core.Coin
import org.koin.java.KoinJavaComponent.inject
import java.text.DecimalFormat


class TransactionsFragment : Fragment() {

    private lateinit var fiatBalanceLiveData: LiveData<String>
    private lateinit var balanceLiveData: LiveData<Long>
    private val transactionsViewModel: TransactionsViewModel by viewModels()
    private lateinit var collection: PubKeyCollection
    private val monetaryUtil: MonetaryUtil by inject(MonetaryUtil::class.java)
    private var _binding: FragmentTransactionsBinding? = null
    private val binding get() = _binding!!
    private val prefsUtil: com.samourai.sentinel.ui.utils.PrefsUtil by inject(com.samourai.sentinel.ui.utils.PrefsUtil::class.java)
    val indexPubSelected: MutableLiveData<Int> by lazy { MutableLiveData<Int>() }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransactionsBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViewModel()

        setUpToolBar()

    }

    private fun initViewModel() {
        val df = DecimalFormat("#")
        df.minimumIntegerDigits = 1
        df.minimumFractionDigits = 8
        df.maximumFractionDigits = 8

        transactionsViewModel.setCollection(collection)

        binding.txViewPager.adapter = CollectionPubKeysViewpager(this.activity, collection)
        binding.txViewPager.offscreenPageLimit = 5
        TabLayoutMediator(binding.tabLayout, binding.txViewPager) { tab, position ->
            if(position == 0){
                tab.text = "All"
            }else{
                tab.text = collection.pubs[position-1].label
            }
        }.attach()

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab:
                                       TabLayout.Tab
                                       ?) {
                indexPubSelected.value = tab?.position!!
            }

            override fun onTabUnselected(tab:
                                         TabLayout.Tab
                                         ?) {
                println("Tab unselected: " + tab?.text)
            }

            override fun onTabReselected(tab:
                                         TabLayout.Tab
                                         ?) {
                println("Tab reselected: " + tab?.text)
            }

        })

        balanceLiveData.observe(viewLifecycleOwner) {
            binding.collectionBalanceBtc.text = "${df.format(it.div(1e8))} BTC"
        }

        binding.collectionBalanceFiat.visibility = if (prefsUtil.fiatDisabled!!) View.INVISIBLE else View.VISIBLE
        fiatBalanceLiveData.observe(viewLifecycleOwner, Observer {
            if (isAdded) {
                binding.collectionBalanceFiat.text = it
            }
        })
        transactionsViewModel.getMessage().observe(
                this.viewLifecycleOwner
        ) {
            if (it != "null")
                (requireActivity() as AppCompatActivity)
                    .showFloatingSnackBar(binding.collectionBalanceBtc, "Error : $it")
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    private fun setUpToolBar() {
        (activity as SentinelActivity).setSupportActionBar(binding.toolbarCollectionDetails)
        (activity as SentinelActivity).supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarCollectionDetails.title = collection.collectionLabel
        binding.collectionBalanceBtc.text = monetaryUtil.formatToBtc(collection.balance)
    }

    fun initViewModel(collection: PubKeyCollection) {
        // check if the new instance added / removed pub keys
        // if the size changed we need to fetch transactions
        this.collection = collection
        if (isAdded) {
            initViewModel()
            setUpToolBar()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.collection_detail_transaction_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        if (item.itemId == R.id.collection_details_transaction_edit_option) {
            startActivityForResult(Intent(context, CollectionEditActivity::class.java).apply {
                putExtra("collection", collection.id)
            }, EDIT_REQUEST_ID)
        }
        if (item.itemId == R.id.collection_details_transaction_utxos) {
            startActivityForResult(Intent(context, UtxosActivity::class.java).apply {
                putExtra("collection", collection.id)
            }, EDIT_REQUEST_ID)
        }

        return super.onOptionsItemSelected(item)
    }

    fun setBalance(balance: LiveData<Long>) {
        this.balanceLiveData = balance
    }

    fun setBalanceFiat(fiatBalance: LiveData<String>) {
        this.fiatBalanceLiveData = fiatBalance

    }

    companion object {
        const val EDIT_REQUEST_ID = 11
    }

    private class CollectionPubKeysViewpager(
            fa: FragmentActivity?,
            private val collection: PubKeyCollection,

            ) : FragmentStateAdapter(fa!!) {
        override fun createFragment(position: Int): Fragment {
            return TransactionsListFragment(position, collection)
        }

        override fun getItemCount(): Int {
            return collection.pubs.size + 1
        }
    }


}

