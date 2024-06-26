package com.samourai.sentinel.ui.collectionDetails.send

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.send.FeeUtil
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale
import kotlin.collections.set

class SendViewModel : ViewModel() {

    private val utxos: ArrayList<Utxo> = arrayListOf()
    private val selectedUtxos: ArrayList<Utxo> = arrayListOf()
    private var address: String = ""
    private var amount: Double = 0.0
    private var enteredAmount: Double = 0.0
    var job: Job? = null
    private var selectedFee = 0L
    private var transactionComposer = TransactionComposer()
    private val utxoDao: UtxoDao by inject(UtxoDao::class.java)
    private var receivers: HashMap<String, BigInteger> = hashMapOf()

    private val _psbt = MutableLiveData("")
    val psbtLive: LiveData<String> = _psbt

    private val _minerFee = MutableLiveData("")
    val minerFee: LiveData<String> = _minerFee

    private val _validSpend = MutableLiveData(false)
    val validSpend: LiveData<Boolean> = _validSpend


    init {
        //Listen for the miner fee change from TransactionComposer
        viewModelScope.launch {
            for (value in transactionComposer.getMinerFee()) {
                withContext(Dispatchers.Main) {
                    val formattedFee = MonetaryUtil.getInstance().getBTCDecimalFormat(value)
                    _minerFee.postValue("$formattedFee BTC")
                }
            }
        }
    }

    fun setPublicKey(
        pubKeyModel: PubKeyModel,
        lifecycleOwner: LifecycleOwner,
        mCollection: PubKeyCollection
    ) {
        if (mCollection.isImportFromWallet && pubKeyModel.label.equals("Deposit")) {
            transactionComposer.setPubKey(listOf(
                getPubKeyModelByLabel("Deposit BIP84", mCollection),
                getPubKeyModelByLabel("Deposit BIP49", mCollection),
                getPubKeyModelByLabel("Deposit BIP44", mCollection)
            ))
            utxoDao.getUTXObyPubKeys(listOf(
                getPubKeyModelByLabel("Deposit BIP84", mCollection).pubKey,
                getPubKeyModelByLabel("Deposit BIP49", mCollection).pubKey,
                getPubKeyModelByLabel("Deposit BIP44", mCollection).pubKey
            ))
                .observe(lifecycleOwner, utxoObserver)
        } else {
            transactionComposer.setPubKey(listOf(pubKeyModel))
            utxoDao.getUtxoWithPubKey(pubKeyModel.pubKey)
                .observe(lifecycleOwner, utxoObserver)
        }
    }

    private fun getPubKeyModelByLabel(label: String, mCollection: PubKeyCollection): PubKeyModel {
        mCollection.pubs?.forEach {
            if (it.label == label)
                return it
        }
        return mCollection.pubs[0]
    }

    private fun prepareSpend(): Boolean {
        selectedUtxos.clear()

        if (address.isBlank() || address.isEmpty()) {
            return false
        }
        val dAmount: Double = enteredAmount
        amount = (dAmount * 1e8)

        if (dAmount == 0.0) {
            return false
        }

        if (utxos.isEmpty() || amount <= 0.0) {
            return false
        }
        job?.let {
            if (it.isActive) {
                it.cancel()
            }
        }
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                receivers = HashMap()
                receivers[address] = BigInteger.valueOf(amount.toLong())
                try {
                    val isValid = transactionComposer.compose(
                        inputAddress = address,
                        inputAmount = amount,
                        inputFee = selectedFee,
                        inputUtxos = ArrayList<Utxo>(utxos.filter { utxo ->  !UtxoMetaUtil.has(utxo)})
                    )
                    withContext(Dispatchers.Main) {
                        _validSpend.postValue(isValid)
                    }
                } catch (e: Exception) {
                    viewModelScope.launch(Dispatchers.Main) {
                        _validSpend.postValue(false)
                    }
                    println("Exception: " + e)
                }
            } catch (ex: TransactionComposer.ComposeException) {
                throw CancellationException("unable to compose")
            }
        }
        job?.invokeOnCompletion {
            if (it != null) {
                it.printStackTrace()
                viewModelScope.launch(Dispatchers.Main) {
//                    Toast.makeText(requireContext(), it.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
//        transactionComposer.setFeeCallBack { l, l2 ->
////            composeTransactionFragment.setMinerFeeTotal("${MonetaryUtil.getInstance().formatToBtc(l)} BTC")
//        }

        return true
    }

    fun setAmount(amount: Double) {
        enteredAmount = amount
        prepareSpend()
    }

    private val utxoObserver: Observer<List<Utxo>> = Observer<List<Utxo>> {
        utxos.clear()
        utxos.addAll(it)
        if (utxos.isEmpty()) {
            _validSpend.postValue(false)
        } else {
            prepareSpend()
        }
    }

    fun setDestinationAddress(enteredAddress: String) {
        this.address = enteredAddress
        prepareSpend()
    }

    fun setFee(fee: Float) {
        Timber.i("setFee: $fee")
        val decFormat = NumberFormat.getInstance(Locale.US)
        decFormat.maximumFractionDigits = 3
        decFormat.minimumFractionDigits = 0
        var customValue = 0.0
        customValue = try {
            fee.toDouble()
        } catch (e: java.lang.Exception) {
            return
        }
        val suggestedFee = SuggestedFee()
        suggestedFee.isStressed = false
        suggestedFee.isOK = true
        suggestedFee.defaultPerKB = BigInteger.valueOf((customValue).toLong())
        FeeUtil.getInstance().suggestedFee = suggestedFee
        selectedFee = fee.toLong()
        prepareSpend()
    }

    fun makeTx(): Boolean {
        if (validSpend.value == true) {
            val psbt = transactionComposer.getPSBT()
            psbt?.let {
                _psbt.postValue(it.toString())
                return true
            }
            return false
        }
        return false
    }

    fun getPsbtBytes(): ByteArray? {
        if (validSpend.value == true) {
            val psbt = transactionComposer.getPSBT()
            return psbt?.toBytes()
        }
        return null
    }


}