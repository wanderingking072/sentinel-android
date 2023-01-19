package com.samourai.sentinel.ui.collectionDetails.receive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.client.android.Contents
import com.google.zxing.client.android.encode.QRCodeEncoder
import com.samourai.sentinel.R
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.hd.HD_Account
import com.samourai.sentinel.core.segwit.P2SH_P2WPKH
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.views.confirm
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.wallet.segwit.SegwitAddress
import kotlinx.android.synthetic.main.advanced_receive_fragment.view.*
import kotlinx.android.synthetic.main.fragment_spend.*
import kotlinx.android.synthetic.main.fragment_spend.view.*
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.uri.BitcoinURI
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.ParseException
import java.util.*

class ReceiveFragment : Fragment() {

    private lateinit var fiatBalanceLiveData: LiveData<String>
    private lateinit var balanceLiveData: LiveData<Long>
    private lateinit var collection: PubKeyCollection;
    private lateinit var toolbar: Toolbar
    private lateinit var receiveQR: ImageView
    private lateinit var receiveAddressText: TextView
    private lateinit var btcEditText: EditText
    private lateinit var satEditText: EditText
    private lateinit var advancedButton: LinearLayout
    private lateinit var tvPath: TextView
    private var pubKeyIndex = 0
    private lateinit var pubKeyDropDown: AutoCompleteTextView
    private val receiveViewModel: ReceiveViewModel by viewModels()
    private lateinit var advancedContainer: ConstraintLayout

    private lateinit var qrFile: String

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {

        val root = inflater.inflate(R.layout.fragment_receive, container, false)
        val advancedRoot = inflater.inflate(R.layout.advanced_receive_fragment, container, true)

        val df = DecimalFormat("#")
        df.minimumIntegerDigits = 1
        df.minimumFractionDigits = 8
        df.maximumFractionDigits = 8

        toolbar = root.findViewById(R.id.toolbarReceive)
        receiveQR = root.findViewById(R.id.receiveQR)
        receiveAddressText = root.findViewById(R.id.receiveAddressText)
        pubKeyDropDown = root.findViewById(R.id.pubKeySelector)
        advancedButton = root.findViewById(R.id.advance_button)
        advancedContainer = root.container_advance_options
        btcEditText = root.findViewById(R.id.amountBTC)
        satEditText = root.findViewById(R.id.amountSAT)
        tvPath = root.path

        qrFile = "${requireContext().cacheDir.path}${File.separator}qr.png";


        setUpSpinner()

        generateQR()

        setUpToolBar()

        receiveAddressText.setOnClickListener {

            val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
            val clipData = ClipData
                    .newPlainText("Address", (it as TextView).text)
            if (cm != null) {
                cm.setPrimaryClip(clipData)
                Toast.makeText(context, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }
        }

        advancedButton.setOnClickListener {
            TransitionManager.beginDelayedTransition(advancedContainer, AutoTransition())
            val visibility =
                if (advancedContainer.visibility == View.VISIBLE)
                    View.INVISIBLE
                else
                    View.VISIBLE
            advancedContainer.visibility = visibility
        }

        btcEditText.addTextChangedListener(BTCWatcher)
        satEditText.addTextChangedListener(satWatcher)

        return root
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    private fun share() {

        (this.activity as (AppCompatActivity)).confirm(
                positiveText = "Share as QR code",
                negativeText = "Copy Address to clipboard",
                label = "Share options",
                onConfirm = {
                    if (it) {
                        shareQR()
                    } else {
                        val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                        val clipData = ClipData
                                .newPlainText("Address", receiveAddressText.text)
                        if (cm != null) {
                            cm.setPrimaryClip(clipData)
                            Toast.makeText(context, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
        )
    }

    private fun shareQR() {
        val file = File(qrFile)
        if (!file.exists()) {
            try {
                file.createNewFile()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
            }
        }
        file!!.setReadable(true, false)

        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(file)
        } catch (fnfe: FileNotFoundException) {
        }

        var clip: ClipData? = null
        clip = ClipData.newPlainText("Receive address", receiveAddressText.text)
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)

        if (fos != null) {
            val bitmap = (receiveQR.drawable as BitmapDrawable).bitmap
            bitmap.compress(Bitmap.CompressFormat.PNG, 0, fos)
            try {
                fos.close()
            } catch (ioe: IOException) {
            }
            val intent = Intent()
            intent.action = Intent.ACTION_SEND
            intent.type = "image/png"
            if (Build.VERSION.SDK_INT >= 24) {
                //From API 24 sending FIle on intent ,require custom file provider
                intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                        requireContext(),
                        requireContext()
                                .packageName + ".provider", file))
            } else {
                intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
            }
            startActivity(Intent.createChooser(intent, requireContext().getText(R.string.send_payment_code)))
        }

    }

    private fun     generateQR() {
        val display = activity?.windowManager?.defaultDisplay
        val size = Point()
        display?.getSize(size)
        val imgWidth = size.x - 200
        val addr = getAddress()
        receiveAddressText.text = addr

        try {
            val amount = NumberFormat.getInstance(Locale.US).parse(btcEditText.getText().toString().trim { it <= ' ' })

            val lamount: Long = (amount.toDouble() * 1e8).toLong()

            if (lamount != 0L) {
                if (!FormatsUtil.isValidBech32(addr)) {
                    receiveQR.setImageBitmap(
                        generateQRCode(
                            BitcoinURI.convertToBitcoinURI(
                                Address.fromBase58(
                                    SentinelState.getNetworkParam(),
                                    addr
                                ), Coin.valueOf(lamount), null, null
                            )
                        , imgWidth)
                    )
                }
                else {
                    var strURI = "bitcoin:$addr"
                    val df = DecimalFormat("#")
                    df.minimumIntegerDigits = 1
                    df.maximumFractionDigits = 8
                    strURI += "?amount=" + df.format(amount)
                    receiveQR.setImageBitmap(generateQRCode(strURI, imgWidth))
                }
            }
            //  receiveQR.setImageBitmap(generateQRCode(addr, imgWidth))
        } catch (nfe: NumberFormatException) {
            receiveQR.setImageBitmap(generateQRCode(addr, imgWidth))
        } catch (pe: ParseException) {
            receiveQR.setImageBitmap(generateQRCode(addr, imgWidth))
        }
    }

    private fun generateQRCode(uri: String, imgWidth: Int): Bitmap? {
        var bitmap: Bitmap? = null
        val qrCodeEncoder = QRCodeEncoder(uri, null, Contents.Type.TEXT, BarcodeFormat.QR_CODE.toString(), imgWidth)
        try {
            bitmap = qrCodeEncoder.encodeAsBitmap()
        } catch (e: WriterException) {
            e.printStackTrace()
        }
        return bitmap
    }


    fun getAddress(): String {
        if (collection.pubs.size == 0) {
            return ""
        }

        if (collection.pubs[pubKeyIndex].type == AddressTypes.ADDRESS) {
            return collection.pubs[pubKeyIndex].pubKey
        }
        val pubKey = collection.pubs[pubKeyIndex]
        val accountIndex = collection.pubs[pubKeyIndex].account_index

        return when (collection.pubs[pubKeyIndex].type) {
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
                collection.pubs[pubKeyIndex].pubKey
            }
            else -> ""
        }

    }

    private fun setUpToolBar() {
        (activity as SentinelActivity).setSupportActionBar(toolbar)
        (activity as SentinelActivity).supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.title = collection.collectionLabel
    }


    /**
     * This method will be called immediately after creating fragment
     * this ensure  onCreateView method
     */
    fun setCollection(collection: PubKeyCollection) {
        this.collection = collection
        if (isAdded) {
            receiveViewModel.setCollection(collection)
            setUpSpinner()
            setUpToolBar()
        }
    }


    private fun setUpSpinner() {
        val items = collection.pubs.map { it.label }.toMutableList()
        if (items.size != 0) {
            val adapter: ArrayAdapter<String> = ArrayAdapter(requireContext(),
                    R.layout.dropdown_menu_popup_item, items)
            pubKeyDropDown.inputType = InputType.TYPE_NULL
            pubKeyDropDown.setAdapter(adapter)
            pubKeyDropDown.threshold = 50
            pubKeyDropDown.setText(items.first(), false)
            pubKeyDropDown.onItemClickListener = AdapterView.OnItemClickListener { _, _, index, _ ->
                pubKeyIndex = index
                generateQR()
            }

        }

    }

    private val BTCWatcher: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
        override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
        override fun afterTextChanged(editable: Editable) {
            var editable = editable
            satEditText.removeTextChangedListener(satWatcher)
            btcEditText.removeTextChangedListener(this)
            try {
                if (editable.toString().length == 0) {
                    satEditText.setText("0")
                    btcEditText.setText("")
                    satEditText.setSelection(satEditText.getText().length)
                    satEditText.addTextChangedListener(satWatcher)
                    btcEditText.addTextChangedListener(this)
                    return
                }
                var btc = editable.toString().toDouble()
                if (btc > 21000000.0) {
                    btcEditText.setText("0.00")
                    btcEditText.setSelection(btcEditText.getText().length)
                    satEditText.setText("0")
                    satEditText.setSelection(satEditText.getText().length)
                    Toast.makeText(
                        context,
                        R.string.invalid_amount,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val format = DecimalFormat.getInstance(Locale.US) as DecimalFormat
                    val symbols = format.decimalFormatSymbols
                    val defaultSeparator = Character.toString(symbols.decimalSeparator)
                    val max_len = 8
                    val btcFormat = NumberFormat.getInstance(Locale.US)
                    btcFormat.maximumFractionDigits = max_len + 1
                    try {
                        val d = NumberFormat.getInstance(Locale.US).parse(editable.toString())
                            .toDouble()
                        val s1 = btcFormat.format(d)
                        if (s1.indexOf(defaultSeparator) != -1) {
                            var dec = s1.substring(s1.indexOf(defaultSeparator))
                            if (dec.length > 0) {
                                dec = dec.substring(1)
                                if (dec.length > max_len) {
                                    btcEditText.setText(s1.substring(0, s1.length - 1))
                                    btcEditText.setSelection(btcEditText.getText().length)
                                    editable = btcEditText.getEditableText()
                                    btc = btcEditText.getText().toString().toDouble()
                                }
                            }
                        }
                    } catch (nfe: java.lang.NumberFormatException) {
                    } catch (pe: ParseException) {
                    }
                    val sats: Double = java.lang.Double.valueOf(btc) * 100000000
                    satEditText.setText(formattedSatValue(sats))
                }

                //
            } catch (e: java.lang.NumberFormatException) {
                e.printStackTrace()
            }
            satEditText.addTextChangedListener(satWatcher)
            btcEditText.addTextChangedListener(this)
            generateQR()
        }
    }

    private val satWatcher: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
        override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
        override fun afterTextChanged(editable: Editable) {
            satEditText.removeTextChangedListener(this)
            btcEditText.removeTextChangedListener(BTCWatcher)
            try {
                if (editable.toString().length == 0) {
                    btcEditText.setText("0.00")
                    satEditText.setText("")
                    btcEditText.addTextChangedListener(this)
                    btcEditText.addTextChangedListener(BTCWatcher)
                    return
                }
                val cleared_space = editable.toString().replace(" ", "")
                val sats = cleared_space.toDouble()
                val btc: Double = (sats / 1e8)
                val formatted: String = formattedSatValue(sats)
                satEditText.setText(formatted)
                satEditText.setSelection(formatted.length)
                btcEditText.setText(String.format(Locale.ENGLISH, "%.8f", btc))
                if (btc > 21000000.0) {
                    btcEditText.setText("0.00")
                    btcEditText.setSelection(btcEditText.getText().length)
                    satEditText.setText("0")
                    satEditText.setSelection(satEditText.getText().length)
                    Toast.makeText(
                        context,
                        R.string.invalid_amount,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: java.lang.NumberFormatException) {
                e.printStackTrace()
            }
            satEditText.addTextChangedListener(this)
            btcEditText.addTextChangedListener(BTCWatcher)
            generateQR()
        }
    }

    private fun formattedSatValue(number: Any): String {
        val nformat = NumberFormat.getNumberInstance(Locale.US)
        val decimalFormat = nformat as DecimalFormat
        decimalFormat.applyPattern("#,###")
        return decimalFormat.format(number).replace(",", " ")
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        menu.clear()
        inflater.inflate(R.menu.collection_detail_receive_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.collection_details_share_qr) {
            share()
        }
        return super.onOptionsItemSelected(item)
    }

    fun setBalance(balance: LiveData<Long>) {
        this.balanceLiveData = balance
    }

    fun setBalanceFiat(balance: LiveData<String>) {
        this.fiatBalanceLiveData = balance
    }


}