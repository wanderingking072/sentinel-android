package com.samourai.sentinel.ui.collectionDetails.transactions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.databinding.FragmentTransactionsBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.collectionEdit.CollectionEditActivity
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.utxos.UtxosActivity
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.UtxoMetaUtil
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
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
    val indexPubSelected: MutableLiveData<Int> by lazy { MutableLiveData<Int>() }
    val df = DecimalFormat("#")
    private var tabChangeListener: OnTabChangedListener? = null


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransactionsBinding.inflate(inflater, container, false)
        val view = binding.root
        df.minimumIntegerDigits = 1
        df.minimumFractionDigits = 8
        df.maximumFractionDigits = 8

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.mpm_black)
        requireActivity().window.navigationBarColor = ContextCompat.getColor(requireContext(), R.color.grey_homeActivity)

        initViewModel()

        setUpToolBar()
    }

    private fun initViewModel() {
        transactionsViewModel.setCollection(collection)

        binding.txViewPager.adapter = CollectionPubKeysViewpager(this.activity, collection)
        binding.txViewPager.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.grey_homeActivity))
        binding.txViewPager.offscreenPageLimit = 5

        TabLayoutMediator(binding.tabLayout, binding.txViewPager) { tab, position ->
            if (collection.isImportFromWallet) {
                when (position) {
                    0 -> tab.text = "All"
                    1 -> tab.text = "Deposit"
                    2 -> tab.text = "Premix"
                    3 -> tab.text = "Postmix"
                    4 -> tab.text = "Badbank"
                }
            }
            else {
                if (position == 0)
                    tab.text = "All"
                else
                    tab.text = collection.pubs[position - 1].label
            }
        }.attach()


        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab:
                                       TabLayout.Tab
                                       ?) {
                if (tabChangeListener != null)
                    tabChangeListener?.onTabChanged(tab?.position!!-1)
                indexPubSelected.value = tab?.position!!
                setBalance(tab.position-1)
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
            if (it != null) {
                if (prefsUtil.streetMode == false)
                    binding.collectionBalanceBtc.text = "${df.format(it.div(1e8))} BTC"
                else
                    binding.collectionBalanceBtc.text = "********"
                setBalance(-1)
            }
        }

        binding.collectionBalanceBtc.setOnLongClickListener {
            goToUTXOActivity()
            true
        }

        binding.collectionBalanceFiat.setOnLongClickListener {
            goToUTXOActivity()
            true
        }

        binding.collectionBalanceFiat.visibility = if (prefsUtil.fiatDisabled!!) View.INVISIBLE else View.VISIBLE
        fiatBalanceLiveData.observe(viewLifecycleOwner, Observer {
            if (isAdded) {
                binding.collectionBalanceFiat.text = it
                setBalance(-1)
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

    private fun goToUTXOActivity() {
        startActivityForResult(Intent(context, UtxosActivity::class.java).apply {
            putExtra("collection", collection.id)
            putExtra("indexPub", indexPubSelected.value)
        }, EDIT_REQUEST_ID)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnTabChangedListener) {
            tabChangeListener = context
        } else {
            throw ClassCastException("$context must implement OnButtonClickListener")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onResume() {
        if (indexPubSelected.value != null)
            setBalance(indexPubSelected.value!!-1)
        else
            setBalance(-1)
        super.onResume()
    }

    fun setBalance(pubkeyIndex: Int) {
        //Handle deposit tab in collection imported from wallet
        if (collection.isImportFromWallet && pubkeyIndex == 0) {
            var blockedUtxoBalanceSum = 0L
            var balance = 0L
            collection.pubs.forEach { pub ->
                if (pub.label.lowercase().contains("deposit")) {
                    balance += pub.balance
                    val blockedUtxos1 =
                        UtxoMetaUtil.getBlockedAssociatedWithPubKey(pub.pubKey)
                    blockedUtxos1.forEach { blockedUtxo ->
                        blockedUtxoBalanceSum += blockedUtxo.amount
                    }
                }
            }
            val finalBalance = balance - blockedUtxoBalanceSum
            if (prefsUtil.streetMode == false) {
                binding.collectionBalanceBtc.text = df.format(finalBalance.div(1e8)) + " BTC"
                binding.collectionBalanceFiat.text = getFiatBalance(finalBalance, exchangeRateRepository.getRateLive().value)
            }
            else {
                binding.collectionBalanceBtc.text = "********"
                binding.collectionBalanceFiat.text = "********"
            }
        }
        else {
            if (pubkeyIndex != -1) {
                var blockedUtxoBalanceSum = 0L
                val blockedUtxos =
                    UtxoMetaUtil.getBlockedAssociatedWithPubKey(collection.pubs[pubkeyIndex].pubKey)
                blockedUtxos.forEach { utxo ->
                    blockedUtxoBalanceSum += utxo.amount
                }
                val balance = collection.pubs[pubkeyIndex].balance - blockedUtxoBalanceSum

                if (prefsUtil.streetMode == false) {
                    binding.collectionBalanceFiat.text =
                        getFiatBalance(balance, exchangeRateRepository.getRateLive().value)
                    binding.collectionBalanceBtc.text = df.format(balance.div(1e8)) + " BTC"
                }
                else {
                    binding.collectionBalanceFiat.text = "********"
                    binding.collectionBalanceBtc.text = "********"
                }
            } else {
                //Handle "All" tab
                var blockedUtxosBalanceSum = 0L
                collection.pubs.forEach { pubKeyModel ->
                    val blockedUtxos1 =
                        UtxoMetaUtil.getBlockedAssociatedWithPubKey(pubKeyModel.pubKey)
                    blockedUtxos1.forEach { blockedUtxo ->
                        blockedUtxosBalanceSum += blockedUtxo.amount
                    }
                }
                val balance = collection.balance - blockedUtxosBalanceSum

                if (prefsUtil.streetMode == false) {
                    binding.collectionBalanceFiat.text =
                        getFiatBalance(balance, exchangeRateRepository.getRateLive().value)
                    binding.collectionBalanceBtc.text = df.format(balance.div(1e8)) + " BTC"
                }
                else {
                    binding.collectionBalanceFiat.text = "********"
                    binding.collectionBalanceBtc.text = "********"
                }
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

    private fun setUpToolBar() {
        (activity as SentinelActivity).setSupportActionBar(binding.toolbarCollectionDetails)
        (activity as SentinelActivity).supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarCollectionDetails.title = collection.collectionLabel
        binding.toolbarCollectionDetails.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mpm_black))
        setBalance(-1)
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
                putExtra("indexPub", indexPubSelected.value)
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
            return if (collection.isImportFromWallet)
                collection.pubs.size - 1
            else
                collection.pubs.size + 1
        }
    }

    interface OnTabChangedListener {
        fun onTabChanged(position: Int)
    }
}

