package com.samourai.sentinel.ui.tools

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.invertedx.hummingbird.QRScanner
import com.samourai.sentinel.R
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.databinding.ContentChooseAddressTypeBinding
import com.samourai.sentinel.databinding.ContentCollectionSelectBinding
import com.samourai.sentinel.databinding.ContentPubkeySelectBinding
import com.samourai.sentinel.databinding.ContentSweepPreviewBinding
import com.samourai.sentinel.databinding.FragmentBottomsheetViewPagerBinding
import com.samourai.sentinel.ui.adapters.CollectionsAdapter
import com.samourai.sentinel.ui.adapters.PubkeysAdapter
import com.samourai.sentinel.ui.collectionEdit.CollectionEditActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.RecyclerViewItemDividerDecorator
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.wallet.util.PrivKeyReader
import com.samourai.wallet.util.XPUB
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.PathComponent
import com.sparrowwallet.hummingbird.registry.RegistryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.bitcoinj.crypto.ChildNumber
import org.bouncycastle.util.encoders.Hex
import org.json.JSONObject
import org.koin.java.KoinJavaComponent
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets


class SweepPrivKeyFragment(private val pubKey: String = "", private val secure: Boolean = false) : GenericBottomSheet(secure = secure) {

    private val scanPubKeyFragment = ScanPubKeyFragment()
    private var newPubKeyListener: ((pubKey: PubKeyModel?) -> Unit)? = null
    private val chooseCollectionFragment = ChooseCollectionFragment()
    private val choosePubkeyFragment = ChoosePubkeyFragment()
    private val previewSweep = PreviewFragment()
    private var pubKeyString = ""
    private var pubKeyModel: PubKeyModel? = null
    private var selectedPubKeyCollection: PubKeyCollection? = null
    private val collectionRepository: CollectionRepository by KoinJavaComponent.inject(
        CollectionRepository::class.java
    )
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

    fun setSelectedCollection(pubKeyCollection: PubKeyCollection) {
        selectedPubKeyCollection = pubKeyCollection
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
            binding.pager.setCurrentItem(3)
        }

        if (pubKey.isNotEmpty()) {
            validate(pubKey)
            pubKeyString = pubKey
        }
    }

    private fun validateXPUB(addressTypes: AddressTypes) {

        pubKeyModel = if ((addressTypes == AddressTypes.BIP49 || addressTypes == AddressTypes.BIP84) && (pubKeyString.startsWith("xpub") || pubKeyString.startsWith("tpub"))) {
            val xpub = XPUB(pubKeyString)
            xpub.decode()
            pubKeyString =
                if (addressTypes == AddressTypes.BIP49 && pubKeyString.startsWith("tpub"))
                    XPUB.makeXPUB(XPUB.MAGIC_UPUB, xpub.depth, xpub.fingerprint, xpub.child, xpub.chain, xpub.getPubkey())
                else if (addressTypes == AddressTypes.BIP49 && pubKeyString.startsWith("xpub"))
                    XPUB.makeXPUB(XPUB.MAGIC_YPUB, xpub.depth, xpub.fingerprint, xpub.child, xpub.chain, xpub.getPubkey())
                else if (addressTypes == AddressTypes.BIP84 && pubKeyString.startsWith("tpub"))
                    XPUB.makeXPUB(XPUB.MAGIC_VPUB, xpub.depth, xpub.fingerprint, xpub.child, xpub.chain, xpub.getPubkey())
                else if (addressTypes == AddressTypes.BIP84 && pubKeyString.startsWith("xpub"))
                    XPUB.makeXPUB(XPUB.MAGIC_ZPUB, xpub.depth, xpub.fingerprint, xpub.child, xpub.chain, xpub.getPubkey())
                else
                    pubKeyString
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = addressTypes, fingerPrint = scanPubKeyFragment.getFingerprint())
        } else if (pubKeyString.startsWith("ypub") || pubKeyString.startsWith("upub")) {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = addressTypes, fingerPrint = scanPubKeyFragment.getFingerprint())
        } else if (pubKeyString.startsWith("zpub") || pubKeyString.startsWith("vpub")) {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = addressTypes, fingerPrint = scanPubKeyFragment.getFingerprint())
        } else {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = AddressTypes.BIP44, fingerPrint = scanPubKeyFragment.getFingerprint())
        }

        if (newPubKeyListener != null) {
            this.newPubKeyListener?.let { it(pubKeyModel) }
            this.dismiss()
            newPubKeyListener = null
            return
        }
        if (selectedPubKeyCollection != null) {
            startActivity(Intent(context, CollectionEditActivity::class.java).apply {
                this.putExtra("pubKey", pubKeyModel)
                this.putExtra("collection", selectedPubKeyCollection?.id)
            })
            this.dismiss()
        } else {
            binding.pager.setCurrentItem(2, true)
        }

    }

    private fun validate(code: String) {
        val payload = FormatsUtil.extractPublicKey(code)
        val type = FormatsUtil.getPubKeyType(payload)
        if (FormatsUtil.isValidBitcoinAddress(payload.trim()) || FormatsUtil.isValidXpub(payload)) {
            if (isPublicKeyTesnet(payload) && !SentinelState.isTestNet()) {
                Toast.makeText(context, "Can't track Testnet public keys in Mainnet", Toast.LENGTH_LONG).show()
                this.dismiss()
                return
            }
            if (!isPublicKeyTesnet(payload) && SentinelState.isTestNet()) {
                Toast.makeText(context, "Can't track Mainnet public keys in Testnet", Toast.LENGTH_LONG).show()
                this.dismiss()
                return
            }
        }
        when {
            PrivKeyReader(payload.trim(), SentinelState.getNetworkParam()).format != null -> {
                binding.pager.setCurrentItem(1, true)
            }
            else -> {
                Toast.makeText(context, "Invalid private key", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isPublicKeyTesnet(payload: String): Boolean {
        if (payload.lowercase().startsWith("tb") || payload.lowercase().startsWith("2") || payload.lowercase().startsWith("m") || payload.lowercase().startsWith("n"))
            return true
        if (payload.lowercase().startsWith("tpub") || payload.lowercase().startsWith("upub") || payload.lowercase().startsWith("vpub"))
            return true
        return false
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
    private var fingerprintHex: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.content_scan_layout, container, false)
    }

    fun setOnScanListener(callback: (scanData: String) -> Unit) {
        this.onScan = callback
    }

    fun getFingerprint(): String? {
        return fingerprintHex
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

        mCodeScanner.setURDecodeListener { result ->
            mCodeScanner.stopScanner()
            result.fold(
                onSuccess = {
                    val xpub = getXpubFromUR(it)
                    if (xpub != null) {
                        onScan(xpub)
                    }
                    else {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Error decoding public key")
                            .setPositiveButton("Ok") { dialog, which ->
                                dialog.dismiss()
                            }.show()
                        mCodeScanner.stopScanner()
                    }
                },
                onFailure = {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Error decoding UR")
                        .setMessage("Exception: ${it.message}")
                        .setPositiveButton("Ok") { dialog, which ->
                            dialog.dismiss()
                        }.show()
                    mCodeScanner.stopScanner()
                }
            )
        }

        view.findViewById<Button>(R.id.pastePubKey).setOnClickListener {
            when {
                !clipboard.hasPrimaryClip() -> {
                    Toast.makeText(context, "PubKey not found in clipboard", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    try {
                        val item = clipboard.primaryClip?.getItemAt(0)
                        onScan(item?.text.toString())
                    } catch (e: Exception) {
                        Toast.makeText(context, "PubKey not found in clipboard", Toast.LENGTH_SHORT).show()
                    }

                }
            }
        }

    }

    private fun getXpubFromUR(it: URDecoder.Result): String? {
        if (it.ur.registryType == RegistryType.CRYPTO_ACCOUNT) {
            val cryptoAccount = it.ur.decodeFromRegistry() as CryptoAccount
            for (outputDescriptor in cryptoAccount.outputDescriptors) {
                fingerprintHex = Hex.toHexString(cryptoAccount.masterFingerprint).lowercase()
                val cryptoHDKey = outputDescriptor.hdKey
                var lastChild = ChildNumber.ZERO
                var depth = 1
                var parentFingerprint = ByteArray(4)
                var version: Int
                if (cryptoHDKey.origin != null) {
                    if (cryptoHDKey.origin.components.isNotEmpty()) {
                        val lastComponent: PathComponent =
                            cryptoHDKey.origin.components[cryptoHDKey.origin.components.size - 1]
                        lastChild = ChildNumber(lastComponent.index, lastComponent.isHardened)
                        depth = cryptoHDKey.origin.depth
                    }
                    if (cryptoHDKey.origin.sourceFingerprint != null) {
                        parentFingerprint = cryptoHDKey.parentFingerprint
                    }
                }
                val parentFingerprintInt = ByteBuffer.wrap(parentFingerprint).int

                if (cryptoHDKey.origin.path.substring(0..1) == "44")
                    version = if (SentinelState.isTestNet())  XPUB.MAGIC_TPUB else XPUB.MAGIC_XPUB
                else if (cryptoHDKey.origin.path.substring(0..1) == "49")
                    version = if (SentinelState.isTestNet())  XPUB.MAGIC_UPUB else XPUB.MAGIC_YPUB
                else
                    version = if (SentinelState.isTestNet())  XPUB.MAGIC_VPUB else XPUB.MAGIC_ZPUB

                val xpub = XPUB.makeXPUB(
                    version,
                    depth.toByte(),
                    parentFingerprintInt,
                    lastChild.i,
                    cryptoHDKey.chainCode,
                    cryptoHDKey.key
                )
                return xpub
            }
        }
        else if (it.ur.registryType == RegistryType.BYTES) {
            val urBytes = it.ur.decodeFromRegistry()
            val decoder = StandardCharsets.UTF_8.newDecoder()
            val buf = ByteBuffer.wrap(urBytes as ByteArray)
            val charBuffer = decoder.decode(buf)
            val xpubsJson = JSONObject(charBuffer.toString())
            if (xpubsJson.has("bip84")) {
                try {
                    fingerprintHex = xpubsJson.getString("xfp").lowercase()
                } catch (e: Exception) {}
                return xpubsJson.getJSONObject("bip84").getString("_pub")
            }
        }
        return null
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

        println("These are the pubkeys; " + pubkeysAdapter.getPubkeyList())
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
    private var onSelect: (PubKeyModel?) -> Unit = {}

    private var _binding: ContentSweepPreviewBinding? = null
    private val binding get() = _binding!!
    private lateinit var selectedCollection: PubKeyCollection


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = ContentSweepPreviewBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }
}