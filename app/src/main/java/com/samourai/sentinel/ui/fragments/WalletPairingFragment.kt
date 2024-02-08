package com.samourai.sentinel.ui.fragments

import android.os.Bundle
import android.text.InputFilter
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.databinding.FragmentBottomsheetViewPagerBinding
import com.samourai.sentinel.databinding.LayoutLoadingBottomBinding
import com.samourai.sentinel.ui.home.HomeViewModel
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.util.ExportImportUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent


class WalletPairingFragment(private val payload: String = "", secure: Boolean = false) : GenericBottomSheet(secure = secure) {

    private var _binding: FragmentBottomsheetViewPagerBinding? = null
    private val binding get() = _binding!!
    private val passwordFragment = PasswordFragment()
    private val loadingFragment = LoadingFragment()
    private val successFragment = SuccessFragment()
    private val homeViewModel: HomeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBottomsheetViewPagerBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setUpViewPager()

        passwordFragment.setPayload(payload.trim())
        passwordFragment.setOnSelectListener {
            loadingFragment.setCollection(it!!)
            binding.pager.currentItem = 1
        }
        loadingFragment.setSuccessCheck {
            apiScope.launch {
                homeViewModel.fetchBalance()
                delay(3000)
            }.invokeOnCompletion {
                binding.pager.currentItem = 2
            }
        }
    }

    private fun setUpViewPager() {

        val item = arrayListOf<Fragment>()
        item.add(passwordFragment)
        item.add(loadingFragment)
        item.add(successFragment)

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
}

class PasswordFragment : Fragment() {

    private lateinit var payload: String
    private var onSelect: (PubKeyCollection?) -> Unit = {}

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.layout_bottom_sheet, container)
        view.findViewById<TextView>(R.id.dialogTitle).text = "Enter one time pairing password"
        val inputContent = inflater.inflate(R.layout.content_bottom_sheet_input, null)
        val content = view.findViewById<FrameLayout>(R.id.contentContainer)
        content.addView(inputContent)
        return view
    }

    fun setOnSelectListener(callback: (PubKeyCollection?) -> Unit = {}) {
        this.onSelect = callback
    }

    fun setPayload(payload:String) {
        this.payload = payload
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        view?.findViewById<MaterialButton>(R.id.bottomSheetConfirmPositiveBtn)?.text = "Decrypt"
        view?.findViewById<TextInputLayout>(R.id.bottomSheetInputFieldLayout)?.hint = "password"
        val textInput = view?.findViewById<TextInputEditText>(R.id.bottomSheetInputField);
        textInput?.inputType = EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
        textInput?.transformationMethod = PasswordTransformationMethod.getInstance();
        textInput?.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(34))
        view?.findViewById<MaterialButton>(R.id.bottomSheetConfirmPositiveBtn)?.setOnClickListener { _ ->
            val password = textInput?.text.toString()
            val collection = ExportImportUtil().decryptAndParseSamouraiPayload(payload, password)
            if (collection == null) {
                Toast.makeText(context, "Error decrypting payload. Check password.", Toast.LENGTH_LONG).show()
            }
            else {
                this.onSelect(collection)
                this.onDestroy()
            }
        }
    }
}

class LoadingFragment : Fragment() {

    private var _binding: LayoutLoadingBottomBinding? = null
    private val binding get() = _binding!!
    private lateinit var collection: PubKeyCollection
    private var onSelect: () -> Unit = {}


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutLoadingBottomBinding.inflate(inflater, container, false)
        binding.dialogTitle.text = "Adding collection"
        val view = binding.root
        return view
    }

    fun setCollection(collection: PubKeyCollection) {
        this.collection = collection
    }

    fun setSuccessCheck(callback: () -> Unit = {}) {
        this.onSelect = callback
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        binding.broadcastProgress.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                ExportImportUtil().startImportCollections(arrayListOf(collection), false)

                onSelect()
            } catch (e: Exception) {
                println("Error: ${e.message}")
            }
        }
    }
}

class SuccessFragment : Fragment() {

    private var _binding: LayoutLoadingBottomBinding? = null
    private val binding get() = _binding!!
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutLoadingBottomBinding.inflate(inflater, container, false)
        binding.dialogTitle.text = "New collection added"
        val view = binding.root
        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        binding.successCheck.visibility = View.VISIBLE
    }
}