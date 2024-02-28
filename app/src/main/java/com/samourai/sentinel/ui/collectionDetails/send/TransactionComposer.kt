package com.samourai.sentinel.ui.collectionDetails.send

import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.SentinelState.Companion.bDust
import com.samourai.sentinel.core.hd.HD_Account
import com.samourai.sentinel.core.segwit.P2SH_P2WPKH
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.send.FeeUtil
import com.samourai.sentinel.send.SendFactory
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.wallet.psbt.PSBT
import com.samourai.wallet.segwit.SegwitAddress
import com.samourai.wallet.send.MyTransactionOutPoint
import com.samourai.wallet.send.UTXO
import com.samourai.wallet.util.FormatsUtilGeneric
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.codec.binary.Hex
import org.apache.commons.lang3.tuple.Triple
import org.bitcoinj.core.Address
import org.bitcoinj.core.ECKey
import org.bitcoinj.params.MainNetParams
import org.json.JSONObject
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import java.math.BigInteger
import java.util.Collections
import java.util.Vector


/**
 * sentinel-android
 */
class TransactionComposer {

    data class ComposeException(private val displayMessage: String) : Exception(displayMessage)

    private var changeECKey: ECKey? = null
    private var changeIndex: Int? = null
    private var psbt: PSBT? = null
    private var balance: Long = 0L
    private var selectedUtxos: ArrayList<UTXO> = arrayListOf()
    private var selectPubKeyModel: PubKeyModel? = null;
    private var address: String = ""
    private var amount: Double = 0.0
    private var selectedFee = 0L;
    private var receivers: HashMap<String, BigInteger> = hashMapOf()
    private var feeCallBack: ((Long, minerFee: Long) -> Unit)? = null;
    private val minerFeeChannel = Channel<Long>()
    private val apiService: ApiService by KoinJavaComponent.inject(ApiService::class.java)
    private var txData:String = ""
    private val HARDENED = 2147483648
    private val composeMutex = Mutex()

    fun setBalance(value: Long) {
        this.balance = value;
    }

    fun getMinerFee(): Channel<Long> {
        return minerFeeChannel
    }

    fun setPubKey(selectPubKeyModel: PubKeyModel) {
        this.selectPubKeyModel = selectPubKeyModel
    }

    suspend fun compose(
        inputUtxos: ArrayList<Utxo>,
        inputAddress: String,
        inputFee: Long,
        inputAmount: Double
    ): Boolean {
        composeMutex.withLock {
            if (selectPubKeyModel == null) {
                return false;
            }
            receivers = hashMapOf()
            selectedUtxos = arrayListOf()
            amount = inputAmount
            address = inputAddress
            selectedFee = inputFee
            var totalValueSelected = 0L
            var change: Long
            var fee: BigInteger? = BigInteger.ZERO

            val xpub = XPUB(this.selectPubKeyModel!!.pubKey)
            xpub.decode()
            val accountIdx = (xpub.child + HARDENED)

            val utxos: ArrayList<UTXO> = arrayListOf();
            for (utxoCoin in inputUtxos) {
                if (utxoCoin.pubKey != selectPubKeyModel?.pubKey) {
                    continue
                } else {
                    val u = UTXO()
                    val outs: MutableList<MyTransactionOutPoint> = ArrayList()
                    outs.add(utxoCoin.getOutPoints())
                    u.outpoints = outs
                    utxos.add(u)
                }
            }
            // sort in ascending order by value
            Collections.sort(utxos, UTXO.UTXOComparator())
            utxos.reverse()
            receivers[address] = BigInteger.valueOf(amount.toLong())
            // get smallest 1 UTXO > than spend + fee + dust
            for (u in utxos) {
                val outpointTypes: Triple<Int, Int, Int> =
                    FeeUtil.getInstance().getOutpointCount(Vector(u.outpoints))
                if (u.value >= amount + bDust.toLong() + FeeUtil.getInstance().estimatedFeeSegwit(
                        outpointTypes.left,
                        outpointTypes.middle,
                        outpointTypes.right,
                        2
                    ).toLong()
                ) {
                    selectedUtxos.add(u)
                    totalValueSelected += u.value
                    Timber.i("single output")
                    Timber.i("amount:$amount")
                    Timber.i("value selected:" + u.value)
                    Timber.i("total value selected:$totalValueSelected")
                    Timber.i("nb inputs:" + u.outpoints.size)
                    break
                }
            }

            if (selectedUtxos.size == 0) {
                // sort in descending order by value
                Collections.sort(utxos, UTXO.UTXOComparator())
                var selected = 0
                var p2pkh = 0
                var p2sh_p2wpkh = 0
                var p2wpkh = 0

                // get largest UTXOs > than spend + fee + dust
                for (u in utxos) {
                    selectedUtxos.add(u)
                    totalValueSelected += u.value
                    selected += u.outpoints.size

//                            Log.d("SendActivity", "value selected:" + u.getValue());
//                            Log.d("SendActivity", "total value selected/threshold:" + totalValueSelected + "/" + (amount + SamouraiWallet.bDust.longValue() + FeeUtil.getInstance().estimatedFee(selected, 2).longValue()));
                    val outpointTypes: Triple<Int, Int, Int> =
                        FeeUtil.getInstance().getOutpointCount(Vector(u.outpoints))
                    p2pkh += outpointTypes.left
                    p2sh_p2wpkh += outpointTypes.middle
                    p2wpkh += outpointTypes.right
                    if (totalValueSelected >= amount + bDust.toLong() + FeeUtil.getInstance()
                            .estimatedFeeSegwit(p2pkh, p2sh_p2wpkh, p2wpkh, 2).toLong()
                    ) {

                        break
                    }
                }
            }

            change = (totalValueSelected - (amount + fee!!.toLong())).toLong()

            if (change > 0L && change < bDust.toLong()) {
                throw ComposeException("Change is dust")
            }

            val outpoints: MutableList<MyTransactionOutPoint?> = ArrayList()

            for (utxo in selectedUtxos) {
                balance += utxo.value
            }
//            List<UTXO> utxos = preselectedUTXOs;
            // sort in descending order by value
            for (utxo in selectedUtxos) {
                outpoints.addAll(utxo.outpoints)
            }
            val outpointTypes = FeeUtil.getInstance().getOutpointCount(Vector(outpoints))
            fee = FeeUtil.getInstance().estimatedFeeSegwit(
                outpointTypes.left,
                outpointTypes.middle,
                outpointTypes.right,
                2
            )
            if (amount.toLong() == balance) {
                fee = FeeUtil.getInstance().estimatedFeeSegwit(
                    outpointTypes.left,
                    outpointTypes.middle,
                    outpointTypes.right,
                    1
                )
                amount -= fee.toLong()
                receivers.clear()
                receivers[address] = BigInteger.valueOf(amount.toLong())

            }

            change = (totalValueSelected - (amount + fee.toLong())).toLong()
            //
            //                    Log.d("SendActivity", "change:" + change);

            if (change < 0L && change < bDust.toLong()) {
                throw ComposeException("Change is dust")
            }
            var changeType = "P2PKH"
            if (FormatsUtilGeneric.getInstance().isValidBech32(address))
                changeType = "P2WPKH"
            else if (Address.fromBase58(SentinelState.getNetworkParam(), address).isP2SHAddress)
                changeType = "P2SH"

            val changeAddress = getNextChangeAddress(accountIdx, changeType)
                ?: throw ComposeException("Change address is invalid");
            receivers[changeAddress] = BigInteger.valueOf(change)


            val outPoints: ArrayList<MyTransactionOutPoint> = ArrayList()


            for (u in selectedUtxos) {
                outPoints.addAll(u.outpoints)
            }

            for (receiver in receivers)
                if (receiver.value.toLong() == 0L)
                    receivers.remove(receiver.key)

            val transaction = try {
                SendFactory.getInstance()
                    .makeTransaction(accountIdx.toInt(), outPoints, receivers)
            } catch (e: Exception) {
                throw ComposeException("Unable to create tx")
            }

            psbt = PSBT(transaction)

            val networkParameters = SentinelState.getNetworkParam();
            val type = if (networkParameters is MainNetParams) 0 else 1

            val purpose = selectPubKeyModel?.getPurpose();
            val data =
                if (selectPubKeyModel?.fingerPrint != null) selectPubKeyModel?.fingerPrint?.toCharArray() else "00000000".toCharArray()

            /*
        psbt!!.addOutput(
            networkParameters,
            Hex.decodeHex(data),
            changeECKey,
            purpose!!,
            type,
            getAccount()!!.id,
            1,
            changeIndex!!
        )

         */

            //external receiving address output
            /*
        psbt!!.addOutput(
            PSBT.PSBT_OUT_BIP32_DERIVATION,
            //org.bouncycastle.util.encoders.Hex.decode("028aeea96b86f67d91af6f3bee75abbd5e85976a4fe489b0d1c4851f744b58e2b5"),
            changeECKey!!.getPubKey(),
            PSBT.writeBIP32Derivation(Hex.decodeHex(data), purpose!!, type, getAccount()!!.id, 0, changeIndex!!)
        )

         */

            //change address output
            psbt!!.addOutput(
                PSBT.PSBT_OUT_BIP32_DERIVATION,
                changeECKey!!.getPubKey(),
                PSBT.writeBIP32Derivation(
                    Hex.decodeHex(data),
                    purpose!!,
                    type,
                    getAccount()!!.id,
                    1,
                    changeIndex!!
                )
            )
            psbt!!.addOutputSeparator()

            psbt!!.addOutputSeparator()

            /*
        psbt!!.addOutput(
            networkParameters,
            Hex.decodeHex(data),
            changeECKey,
            purpose,
            type,
            getAccount()!!.id,
            0,
            changeIndex!!
        )
         */

            psbt?.addGlobalUnsignedTx(String(Hex.encodeHex(transaction.bitcoinSerialize())));
            psbt?.addGlobalXpubRecord(
                PSBT.deserializeXPUB(getAccount()?.xpubstr()),
                Hex.decodeHex(data),
                purpose,
                type,
                getAccount()!!.id
            );
            psbt?.addGlobalSeparator();

            for (outPoint in outPoints) {
                for (input in inputUtxos) {
                    if (input.txHash == outPoint.hash.toString() && outPoint.txOutputN == input.txOutputN) {
                        val accountIdx = (xpub.child + HARDENED)
                        val path: String = input.path;
                        val addressIndex: Int = path.split("/".toRegex()).toTypedArray()[2].toInt()
                        val chainIndex = path.split("/".toRegex()).toTypedArray()[1].toInt()
                        val eckeyInput = if (chainIndex == 1) {
                            getAccount()?.change?.getAddressAt(addressIndex)?.ecKey
                        } else {
                            getAccount()?.receive?.getAddressAt(addressIndex)?.ecKey
                        }

                        if (purpose == 84 && FormatsUtil.isValidBech32(input.addr!!)) {
                            psbt?.addInput(
                                networkParameters,
                                Hex.decodeHex(data),
                                eckeyInput,
                                outPoint.value.value,
                                purpose,
                                type,
                                accountIdx.toInt(),
                                chainIndex,
                                addressIndex
                            )
                        } else if (purpose == 49 || Address.fromBase58(
                                SentinelState.getNetworkParam(),
                                input.addr!!
                            ).isP2SHAddress
                        ) {
                            val response = apiService.getTxHex(outPoint.hash.toString())
                            if (response.isSuccessful) {
                                val body = response.body?.string()
                                if (body != null) {
                                    val jsonObject = JSONObject(body)
                                    if (jsonObject.has("data"))
                                        txData = jsonObject.getString("data")
                                }
                            }
                            if (txData != "")
                                psbt!!.addInputCompatibility(
                                    networkParameters,
                                    Hex.decodeHex(data),
                                    eckeyInput,
                                    outPoint.value.value,
                                    purpose,
                                    type,
                                    accountIdx.toInt(),
                                    chainIndex,
                                    addressIndex,
                                    txData,
                                    input.txOutputN!!
                                );
                        } else if (purpose == 44 || !FormatsUtil.isValidBech32(input.addr!!)) {
                            val response = apiService.getTxHex(outPoint.hash.toString())
                            if (response.isSuccessful) {
                                val body = response.body?.string()
                                if (body != null) {
                                    val jsonObject = JSONObject(body)
                                    if (jsonObject.has("data"))
                                        txData = jsonObject.getString("data")
                                }
                            }

                            psbt?.addInputLegacy(
                                Hex.decodeHex(data),
                                eckeyInput,
                                outPoint.value.value,
                                purpose,
                                type,
                                accountIdx.toInt(),
                                chainIndex,
                                addressIndex,
                                txData
                            );
                        }
                        break
                    }
                }
            }

            if (psbt != null) {
                fee?.let {
                    Timber.i("compose: ${fee} ${feeCallBack.hashCode()}")
                }
            }

            return true
        }
    }

    private fun getAccount(): HD_Account? {
        val pubKey = this.selectPubKeyModel;
        return when (this.selectPubKeyModel?.type) {
            AddressTypes.BIP44 -> {
                return HD_Account(SentinelState.getNetworkParam(), pubKey?.pubKey, "", 0);
            }
            AddressTypes.BIP49 -> {
                return HD_Account(SentinelState.getNetworkParam(), pubKey?.pubKey, "", 0)
            }
            AddressTypes.BIP84 -> {
                return HD_Account(SentinelState.getNetworkParam(), pubKey?.pubKey, "", 0)
            }
            AddressTypes.ADDRESS -> {
                return null;
            }
            else -> null
        }
    }

    private fun getNextChangeAddress(accountIdx: Long = 0L, toAddrType: String = ""): String? {
        val pubKey = this.selectPubKeyModel;
        changeIndex = pubKey?.change_index!!
        val account = getAccount()

        return when (this.selectPubKeyModel?.type) {
            AddressTypes.BIP44 -> {
                val address = account?.change?.getAddressAt(changeIndex!!)
                changeECKey = address?.ecKey
                address?.addressString
            }
            AddressTypes.BIP49 -> {
                val address = account?.change?.getAddressAt(changeIndex!!)
                changeECKey = address?.ecKey
                val p2shP2wpkH = P2SH_P2WPKH(changeECKey?.pubKey, SentinelState.getNetworkParam())
                p2shP2wpkH.addressAsString
            }
            AddressTypes.BIP84 -> {
                val address = account?.getChain(1)?.getAddressAt(changeIndex!!)
                val ecKey = address?.ecKey
                var changeAddress = ""
                changeECKey = address?.ecKey
                val segwitAddress = SegwitAddress(ecKey?.pubKey, SentinelState.getNetworkParam())
                if (accountIdx == 2147483646L && toAddrType == "P2SH") {
                    changeAddress = segwitAddress.address.toString()
                }
                else if (accountIdx == 2147483646L && toAddrType == "P2PKH") {
                    changeAddress = address!!.address.toString()
                }
                else {
                    changeAddress = segwitAddress.bech32AsString
                }
                changeAddress
            }
            AddressTypes.ADDRESS -> {
                pubKey.pubKey
            }
            else -> ""
        }
    }

    fun getPSBT(): PSBT? {
        return psbt;
    }

    companion object {
        private const val TAG = "TransactionComposer"
    }

}