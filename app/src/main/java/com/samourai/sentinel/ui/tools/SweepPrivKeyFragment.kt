package com.samourai.sentinel.ui.tools

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.invertedx.hummingbird.QRScanner
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.hd.HD_Account
import com.samourai.sentinel.core.segwit.P2SH_P2WPKH
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.databinding.ContentCollectionSelectBinding
import com.samourai.sentinel.databinding.ContentPubkeySelectBinding
import com.samourai.sentinel.databinding.ContentSweepPreviewBinding
import com.samourai.sentinel.databinding.FragmentBottomsheetViewPagerBinding
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.ui.adapters.CollectionsAdapter
import com.samourai.sentinel.ui.adapters.PubkeysAdapter
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.RecyclerViewItemDividerDecorator
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.util.apiScope
import com.samourai.wallet.api.backend.beans.UnspentOutput
import com.samourai.wallet.bipFormat.BIP_FORMAT
import com.samourai.wallet.bipFormat.BipFormat
import com.samourai.wallet.segwit.SegwitAddress
import com.samourai.wallet.util.FeeUtil
import com.samourai.wallet.util.PrivKeyReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent
import java.math.BigInteger
import java.text.DecimalFormat
import kotlin.math.ceil


class SweepPrivKeyFragment(private val privKey: String = "", private val secure: Boolean = false) : GenericBottomSheet(secure = secure) {

    private val scanPubKeyFragment = ScanPubKeyFragment()
    private var newPubKeyListener: ((pubKey: PubKeyModel?) -> Unit)? = null
    private val chooseCollectionFragment = ChooseCollectionFragment()
    private val choosePubkeyFragment = ChoosePubkeyFragment()
    private val previewSweep = PreviewFragment()
    private var pubKeyString = ""
    var privKeyReader: PrivKeyReader? = null
    private var _binding: FragmentBottomsheetViewPagerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentBottomsheetViewPagerBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        setUpViewPager()

        scanPubKeyFragment.setOnScanListener {
            validate(it)
            pubKeyString = it
        }


        chooseCollectionFragment.setOnSelectListener {
            choosePubkeyFragment.setSelectedCollection(it!!)
            binding.pager.setCurrentItem(2, true)
        }

        choosePubkeyFragment.setOnSelectListener {
            previewSweep.setSelectedPubkey(it!!)
            binding.pager.setCurrentItem(3)
        }

        if (privKey.isNotEmpty()) {
            validate(privKey)
            pubKeyString = privKey
        }
    }


    private fun validate(payload: String) {
        when {
            PrivKeyReader(payload.trim(), SentinelState.getNetworkParam()).format != null -> {
                privKeyReader = PrivKeyReader(payload.trim(), SentinelState.getNetworkParam())
                apiScope.launch {
                    withContext(Dispatchers.Default) {
                        findUTXOs()
                    }
                }
            }
            else -> {
                Toast.makeText(context, "Invalid private key", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun findUTXOs(timelockDerivationIndex: Int = -1) {
        val apiService: ApiService by KoinJavaComponent.inject(ApiService::class.java)
        withContext(Dispatchers.IO) {
            val bipFormats: Collection<BipFormat> = getBipFormats(timelockDerivationIndex)
            bipFormats.forEach {
                val address = it.getToAddress(privKeyReader!!.key, privKeyReader!!.params)
                runBlocking {
                    val apiCall = async(Dispatchers.IO) {
                        apiService.fetchAddressForSweep(address)
                    }

                    val items = apiCall.await()
                    if (items.isNotEmpty()) {
                        previewSweep.setUTXOList(items)
                        previewSweep.setBipFormat(it)
                        binding.pager.setCurrentItem(1, true)
                        return@runBlocking
                    }
                }
            }
            requireActivity().runOnUiThread(Runnable {
                Toast.makeText(context, "This private key doesn't have any UTXOs", Toast.LENGTH_SHORT).show()
            })

            dismiss()
        }
    }

    private fun getBipFormats(timelockDerivationIndex: Int = -1) : List<BipFormat> {
        if (timelockDerivationIndex >= 0) {
            return emptyList()
            //return listOf(FidelityBondsTimelockedBipFormat.create(timelockDerivationIndex))
        } else {
            return listOf(
                BIP_FORMAT.LEGACY,
                BIP_FORMAT.SEGWIT_COMPAT,
                BIP_FORMAT.SEGWIT_NATIVE,
                BIP_FORMAT.TAPROOT
            )
        }
    }

    private fun setUpViewPager() {

        val item = arrayListOf<Fragment>()
        item.add(scanPubKeyFragment)
        item.add(chooseCollectionFragment)
        item.add(choosePubkeyFragment)
        item.add(previewSweep)
        //item.add(choosePubKey)
        //item.previewSweep
        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return item.size
            }

            override fun createFragment(position: Int): Fragment {
                return item[position]
            }

        }
        binding.pager.isUserInputEnabled = false
    }

    fun setPubKeyListener(listener: (pubKey: PubKeyModel?) -> Unit) {
        newPubKeyListener = listener
    }
}


class ScanPubKeyFragment : Fragment() {

    private lateinit var  mCodeScanner: QRScanner;
    private val appContext: Context by KoinJavaComponent.inject(Context::class.java)
    private var onScan: (scanData: String) -> Unit = {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.content_scan_layout, container, false)
    }

    fun setOnScanListener(callback: (scanData: String) -> Unit) {
        this.onScan = callback
    }

    fun showLoading(show: Boolean) {
        view?.findViewById<CircularProgressIndicator>(R.id.sweepProgress)?.visibility = if (show) View.VISIBLE else View.GONE
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.pastePubKey).text = "Paste Private Key"
        mCodeScanner = view.findViewById(R.id.scannerViewXpub);
        mCodeScanner.setLifeCycleOwner(this)

        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        mCodeScanner.setQRDecodeListener {
            GlobalScope.launch(Dispatchers.Main) {
                mCodeScanner.stopScanner();
                onScan(it)
            }
        }

        view.findViewById<Button>(R.id.pastePubKey).setOnClickListener {
            when {
                !clipboard.hasPrimaryClip() -> {
                    Toast.makeText(context, "Private key not found in clipboard", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    try {
                        val item = clipboard.primaryClip?.getItemAt(0)
                        onScan(item?.text.toString())
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error parsing private key", Toast.LENGTH_SHORT).show()
                    }

                }
            }
        }

    }

    override fun onResume() {
        super.onResume()
        if (AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, appContext)) {
            mCodeScanner?.startScanner()
        }
    }

    override fun onPause() {
        mCodeScanner?.stopScanner()
        super.onPause()
    }

}

class ChooseCollectionFragment : Fragment() {

    private val repository: CollectionRepository by KoinJavaComponent.inject(CollectionRepository::class.java)
    private val collectionsAdapter = CollectionsAdapter()
    private var onSelect: (PubKeyCollection?) -> Unit = {}

    private var _binding: ContentCollectionSelectBinding? = null
    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = ContentCollectionSelectBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    fun setOnSelectListener(callback: (PubKeyCollection?) -> Unit = {}) {
        this.onSelect = callback
    }


    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        binding.createNewCollection.visibility = View.INVISIBLE
        setUpCollectionSelectList()
        binding.createNewCollection.setOnClickListener {
            this.onSelect(null)
        }
    }

    private fun setUpCollectionSelectList() {

        repository.collectionsLiveData.observe(viewLifecycleOwner, Observer {
            collectionsAdapter.update(it)
        })
        collectionsAdapter.setLayoutType(CollectionsAdapter.Companion.LayoutType.STACKED)
        val linearLayoutManager = LinearLayoutManager(context)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL
        val decorator = RecyclerViewItemDividerDecorator(ContextCompat.getDrawable(requireContext(), R.drawable.divider_grey)!!)
        binding.collectionSelectRecyclerView.apply {
            adapter = collectionsAdapter
            layoutManager = linearLayoutManager
            setHasFixedSize(true)
            addItemDecoration(decorator)
        }

        collectionsAdapter.setOnClickListener {
            this.onSelect(it)
        }
    }


}

class ChoosePubkeyFragment : Fragment() {

    private val repository: CollectionRepository by KoinJavaComponent.inject(CollectionRepository::class.java)
    private val pubkeysAdapter = PubkeysAdapter()
    private var onSelect: (PubKeyModel?) -> Unit = {}

    private var _binding: ContentPubkeySelectBinding? = null
    private val binding get() = _binding!!
    private lateinit var selectedCollection: PubKeyCollection


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = ContentPubkeySelectBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    fun setOnSelectListener(callback: (PubKeyModel?) -> Unit = {}) {
        this.onSelect = callback
    }


    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        binding.createNewCollection.visibility = View.INVISIBLE
        binding.createNewCollection.setOnClickListener {
            this.onSelect(null)
        }
    }

    public fun setSelectedCollection(collection: PubKeyCollection) {
        this.selectedCollection = collection
        setUpCollectionSelectList()
    }

    private fun setUpCollectionSelectList() {

        repository.findById(selectedCollection.id)
        repository.collectionsLiveData.observe(viewLifecycleOwner, Observer {
            pubkeysAdapter.update(repository.findById(selectedCollection.id)!!.pubs)
        })

        pubkeysAdapter.setLayoutType(PubkeysAdapter.Companion.LayoutType.STACKED)
        val linearLayoutManager = LinearLayoutManager(context)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL
        val decorator = RecyclerViewItemDividerDecorator(ContextCompat.getDrawable(requireContext(), R.drawable.divider_grey)!!)
        binding.collectionSelectRecyclerView.apply {
            adapter = pubkeysAdapter
            layoutManager = linearLayoutManager
            setHasFixedSize(true)
            addItemDecoration(decorator)
        }

        pubkeysAdapter.setOnClickListener {
            this.onSelect(it)
        }
    }


}

class PreviewFragment : Fragment() {
    private var _binding: ContentSweepPreviewBinding? = null
    private val binding get() = _binding!!

    private lateinit var selectedPubkey: PubKeyModel
    private lateinit var utxoList: MutableList<UnspentOutput>
    private lateinit var bipFormat: BipFormat

    private var feeLow: Long = 0L
    private var feeMed: Long = 0L
    private var feeHigh: Long = 0L

    private var selectedFee: Long = 1000L


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = ContentSweepPreviewBinding.inflate(inflater, container, false)
        val view = binding.root

        preparePreview()

        setUpFee()

        binding.sweepBtn.setOnClickListener {
            prepareSweep()
        }

        return view
    }

    private fun prepareSweep() {
        Toast.makeText(context, "This is the address ${getAddress(selectedPubkey)}", Toast.LENGTH_SHORT).show()
    }

    fun getAddress(pubKey: PubKeyModel): String {
        val accountIndex = pubKey.account_index

        return when (pubKey.type) {
            AddressTypes.BIP44 -> {
                val account = HD_Account(SentinelState.getNetworkParam(), pubKey.pubKey, "", 0)
                account.getChain(0).addrIdx = accountIndex
                val hdAddress = account.getChain(0).getAddressAt(accountIndex)
                hdAddress.addressString
            }
            AddressTypes.BIP49 -> {
                val account = HD_Account(SentinelState.getNetworkParam(), pubKey.pubKey, "", 0)
                account.getChain(0).addrIdx = accountIndex
                val address = account.getChain(0).getAddressAt(accountIndex)
                val ecKey = address.ecKey
                val p2shP2wpkH = P2SH_P2WPKH(ecKey.pubKey, SentinelState.getNetworkParam())
                p2shP2wpkH.addressAsString
            }
            AddressTypes.BIP84 -> {
                val account = HD_Account(SentinelState.getNetworkParam(), pubKey.pubKey, "", 0)
                account.getChain(0).addrIdx = accountIndex
                val address = account.getChain(0).getAddressAt(accountIndex)
                val ecKey = address.ecKey
                val segwitAddress = SegwitAddress(ecKey.pubKey, SentinelState.getNetworkParam())
                segwitAddress.bech32AsString
            }
            AddressTypes.ADDRESS -> {
                pubKey.pubKey
            }
            else -> ""
        }

    }

    fun setSelectedPubkey(pubkey: PubKeyModel) {
        this.selectedPubkey = pubkey
    }

    fun setUTXOList(utxoList: MutableList<UnspentOutput>) {
        this.utxoList = utxoList
    }

    fun setBipFormat(bipFormat: BipFormat) {
        this.bipFormat = bipFormat
    }

    private fun preparePreview() {
        //TODO: figure out the amount of the UTXO
        //when scanning / pasting it should set the amount of the UTXO in this fragment.
        //findUTXOs(context: Context, timelockDerivationIndex: Int = -1)
        binding.receiveAddress.text = getAddress(selectedPubkey)
        binding.fromAddress.text = this.utxoList.get(0).addr
        binding.amount.text = "${this.utxoList.get(0).value.div(1e8)} BTC"
    }

    private fun setUpFee() {
        val feeRepository: FeeRepository by KoinJavaComponent.inject(FeeRepository::class.java)
        val decimalFormat = DecimalFormat("##.00")
        val multiplier = 10000
//        FEE_TYPE = PrefsUtil.getInstance(this).getValue(PrefsUtil.CURRENT_FEE_TYPE, SendActivity.FEE_NORMAL)
        feeLow = feeRepository.getLowFee().defaultPerKB.toLong() / 1000L
        feeMed = feeRepository.getNormalFee().defaultPerKB.toLong() / 1000L
        feeHigh = feeRepository.getHighFee().defaultPerKB.toLong() / 1000L

        val high = feeHigh / 2 + feeHigh
        val feeHighSliderValue = (high * multiplier)
        val feeMedSliderValue = (feeMed * multiplier)
        val valueTo = (feeHighSliderValue - multiplier).toFloat()
        binding.feeSelector.feeSlider.stepSize = 1F
        binding.feeSelector.feeSlider.valueTo = if (valueTo <= 0) feeHighSliderValue.toFloat() else valueTo
        binding.feeSelector.feeSlider.valueTo = if (valueTo <= 0) feeHighSliderValue.toFloat() else valueTo
        binding.feeSelector.feeSlider.valueFrom = 1F
        binding.feeSelector.feeSlider.setLabelFormatter { i: Float ->
            val value = (i + multiplier) / multiplier
            val formatted = "${decimalFormat.format(value)} sats/b"
            binding.feeSelector.selectedFeeRate.text = formatted
            formatted
        }
        if (feeLow == feeMed && feeMed == feeHigh) {
            feeLow = (feeMed.toDouble() * 0.85).toLong()
            feeHigh = (feeMed.toDouble() * 1.15).toLong()
            val loSf = SuggestedFee()
            loSf.defaultPerKB = BigInteger.valueOf(feeLow * 1000L)
            feeRepository.setLowFee(loSf)
            val hiSf = SuggestedFee()
            hiSf.defaultPerKB = BigInteger.valueOf(feeHigh * 1000L)
            feeRepository.setHighFee(hiSf)
        } else if (feeLow == feeMed || feeMed == feeMed) {
            feeMed = (feeLow + feeHigh) / 2L
            val miSf = SuggestedFee()
            miSf.defaultPerKB = BigInteger.valueOf(feeHigh * 1000L)
            feeRepository.setNormalFee(miSf)
        }
        if (feeLow < 1L) {
            feeLow = 1L
            val loSf = SuggestedFee()
            loSf.defaultPerKB = BigInteger.valueOf(feeLow * 1000L)
            feeRepository.setLowFee(loSf)
        }
        if (feeMed < 1L) {
            feeMed = 1L
            val miSf = SuggestedFee()
            miSf.defaultPerKB = BigInteger.valueOf(feeMed * 1000L)
            feeRepository.setNormalFee(miSf)
        }
        if (feeHigh < 1L) {
            feeHigh = 1L
            val hiSf = SuggestedFee()
            hiSf.defaultPerKB = BigInteger.valueOf(feeHigh * 1000L)
            feeRepository.setHighFee(hiSf)
        }
        binding.feeSelector.selectedFeeRateLayman.text = getString(R.string.normal)
        feeRepository.sanitizeFee()
        binding.feeSelector.selectedFeeRate.text = ("$feeMed sats/b")
        binding.feeSelector.feeSlider.value = (feeMedSliderValue - multiplier + 1).toFloat()
        setFeeLabels()
        selectedFee =
            (((binding.feeSelector.feeSlider.value + multiplier) / multiplier)*1000).toLong()
        var nbBlocks = 6
        binding.feeSelector.feeSlider.addOnChangeListener { slider, sliderVal, fromUser ->
            val value = (sliderVal + multiplier) / multiplier
            var pct = 0F
            if (value <= feeLow) {
                pct = feeLow / value
                nbBlocks = ceil(pct * 24.0).toInt()
            } else if (value >= feeHigh.toFloat()) {
                pct = feeHigh / value
                nbBlocks = ceil(pct * 2.0).toInt()
                if (nbBlocks < 1) {
                    nbBlocks = 1
                }
            } else {
                pct = feeMed / value
                nbBlocks = ceil(pct * 6.0).toInt()
            }
            binding.feeSelector.estBlockTime.text = "$nbBlocks blocks"
            if (nbBlocks > 50) {
                binding.feeSelector.estBlockTime.text = "50+ blocks"
            }
            setFeeLabels()
            selectedFee = (value * 1000).toLong()
            binding.feeSelector.totalMinerFee.text =
                computeFee(bipFormat, utxoList, selectedFee.div(1000.0).toLong()).toString()
        }
        binding.feeSelector.estBlockTime.text = "$nbBlocks blocks"
        binding.feeSelector.totalMinerFee.text =
            computeFee(bipFormat, utxoList, selectedFee.div(1000.0).toLong()).toString()
    }

    private fun setFeeLabels() {
        if (binding.feeSelector.feeSlider.valueTo <= 0) {
            return
        }
        val sliderValue: Float = binding.feeSelector.feeSlider.value / binding.feeSelector.feeSlider.valueTo
        val sliderInPercentage = sliderValue * 100
        if (sliderInPercentage < 33) {
            binding.feeSelector.selectedFeeRateLayman.setText(R.string.low)
        } else if (sliderInPercentage > 33 && sliderInPercentage < 66) {
            binding.feeSelector.selectedFeeRateLayman.setText(R.string.normal)
        } else if (sliderInPercentage > 66) {
            binding.feeSelector.selectedFeeRateLayman.setText(R.string.urgent)
        }
    }

    private fun computeFee(bipFormat: BipFormat, unspentOutputs: Collection<UnspentOutput?>, feePerB: Long): Long {
        var inputsP2PKH = 0
        var inputsP2WPKH = 0
        var inputsP2SH_P2WPKH = 0
        if (bipFormat === BIP_FORMAT.SEGWIT_COMPAT) {
            inputsP2SH_P2WPKH = unspentOutputs.size
        } else if (bipFormat === BIP_FORMAT.SEGWIT_NATIVE) {
            inputsP2WPKH = unspentOutputs.size
        } else {
            inputsP2PKH = unspentOutputs.size
        }
        return FeeUtil.getInstance().estimatedFeeSegwit(inputsP2PKH, inputsP2SH_P2WPKH, inputsP2WPKH, 1, 0, feePerB)
    }

}