package com.samourai.sentinel.ui.fragments

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
import com.invertedx.hummingbird.QRScanner
import com.samourai.sentinel.R
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.databinding.ContentChooseAddressTypeBinding
import com.samourai.sentinel.databinding.ContentCollectionSelectBinding
import com.samourai.sentinel.databinding.FragmentBottomsheetViewPagerBinding
import com.samourai.sentinel.ui.adapters.CollectionsAdapter
import com.samourai.sentinel.ui.collectionEdit.CollectionEditActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.RecyclerViewItemDividerDecorator
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.util.FormatsUtil
import kotlinx.coroutines.*
import org.koin.java.KoinJavaComponent


class AddNewPubKeyBottomSheet(private val pubKey: String = "") : GenericBottomSheet() {

    private val scanPubKeyFragment = ScanPubKeyFragment()
    private var newPubKeyListener: ((pubKey: PubKeyModel?) -> Unit)? = null
    private val selectAddressTypeFragment = SelectAddressTypeFragment()
    private val chooseCollectionFragment = ChooseCollectionFragment()
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

        selectAddressTypeFragment.setOnSelectListener {
            validateXPUB(it)
        }

        chooseCollectionFragment.setOnSelectListener {
            startActivity(Intent(context, CollectionEditActivity::class.java).apply {
                this.putExtra("pubKey", pubKeyModel)
                if (it != null) {
                    this.putExtra("editIndex", collectionRepository.findById(it.id)?.pubs?.size!!)
                    this.putExtra("collection", it.id)
                }
            })
            this.dismiss()
        }

        if (pubKey.isNotEmpty()) {
            validate(pubKey)
            pubKeyString = pubKey
        }
    }

    private fun validateXPUB(addressTypes: AddressTypes) {

        pubKeyModel = if ((addressTypes == AddressTypes.BIP49 || addressTypes == AddressTypes.BIP84) && (pubKeyString.startsWith("xpub") || pubKeyString.startsWith("tpub"))) {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = addressTypes)
        } else if (pubKeyString.startsWith("ypub") || pubKeyString.startsWith("upub")) {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = addressTypes)
        } else if (pubKeyString.startsWith("zpub") || pubKeyString.startsWith("vpub")) {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = addressTypes)
        } else {
            PubKeyModel(pubKey = pubKeyString, balance = 0, account_index = 0, change_index = 1, label = "Untitled", type = AddressTypes.BIP44)
        }

        if (newPubKeyListener != null) {
            this.newPubKeyListener?.let { it(pubKeyModel) }
            this.dismiss()
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
        when {
            FormatsUtil.isValidBitcoinAddress(payload.trim()) -> {
                if (newPubKeyListener != null) {
                    val pubKey = PubKeyModel(pubKey = payload, type = AddressTypes.ADDRESS, label = "Untitled")
                    newPubKeyListener?.let { it(pubKey) }
                    this.dismiss()
                    return
                } else {
                    pubKeyModel = PubKeyModel(pubKey = payload, type = AddressTypes.ADDRESS, label = "Untitled")
                    // Skip type selection screen since payload is an address
                    binding.pager.setCurrentItem(2, true)
                }
            }
            FormatsUtil.isValidXpub(code) -> {
                //show option to choose xpub type
                if (type == AddressTypes.BIP84 || type == AddressTypes.BIP49) {
                    pubKeyString = code
                    validateXPUB(type)
                }
                else {
                    binding.pager.setCurrentItem(1, true)
                    binding.pager.post {
                        selectAddressTypeFragment.setType(type)
                    }
                }
            }
            else -> {
                Toast.makeText(context, "Invalid public key or payload", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setUpViewPager() {
        val item = arrayListOf<Fragment>()
        item.add(scanPubKeyFragment)
        item.add(selectAddressTypeFragment)
        item.add(chooseCollectionFragment)
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

    private var mCodeScanner: QRScanner? = null
    private val appContext: Context by KoinJavaComponent.inject(Context::class.java)
    private var onScan: (scanData: String) -> Unit = {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.content_scan_layout, container, false)
    }

    fun setOnScanListener(callback: (scanData: String) -> Unit) {
        this.onScan = callback
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mCodeScanner = view.findViewById(R.id.scannerViewXpub);

        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        mCodeScanner?.setQRDecodeListener {
            GlobalScope.launch(Dispatchers.Main) {
                mCodeScanner?.stopScanner();
                onScan(it)
            }
        }

        mCodeScanner?.setURDecodeListener { bytes, type ->
            //  TODO: Handle UR Qr
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

class SelectAddressTypeFragment : Fragment() {
    private var onSelect: (type: AddressTypes) -> Unit = {}
    var addressType: AddressTypes = AddressTypes.BIP44

    private var _binding: ContentChooseAddressTypeBinding? = null
    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = ContentChooseAddressTypeBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    fun setOnSelectListener(callback: (type: AddressTypes) -> Unit = {}) {
        this.onSelect = callback
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        binding.radioGroup.setOnCheckedChangeListener { radioGroup, i ->
            when (i) {
                0 -> {
                    addressType = AddressTypes.BIP44
                }
                1 -> {
                    addressType = AddressTypes.BIP49
                }
                2 -> {
                    addressType = AddressTypes.BIP84
                }
            }
        }
        binding.nextBtn.setOnClickListener {
            onSelect(addressType)
        }
        when (addressType) {
            AddressTypes.BIP44 -> {
                binding.buttonBIP44.isChecked = true
            }
            AddressTypes.BIP49 -> {
                binding.buttonBIP49.isChecked = true
            }
            AddressTypes.BIP84 -> {
                binding.buttonBIP84.isChecked = true
            }
            AddressTypes.ADDRESS -> {
                //No-op
            }
        }
    }

    fun setType(type: AddressTypes?) {
        if (type != null) {
            addressType = type

        }
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