package com.samourai.sentinel.ui.tools.sweep;

import android.util.Log;

import com.samourai.wallet.SamouraiWalletConst;
import com.samourai.wallet.bip69.BIP69InputComparator;
import com.samourai.wallet.bip69.BIP69OutputComparator;
import com.samourai.wallet.bipFormat.BipFormatSupplier;
import com.samourai.wallet.send.MyTransactionOutPoint;
import com.samourai.wallet.send.SendFactoryGeneric;
import com.samourai.wallet.send.exceptions.MakeTxException;
import com.samourai.wallet.send.exceptions.SignTxException;
import com.samourai.wallet.util.FormatsUtilGeneric;

import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class TransactionForSweepHelper {

    /*
    public static Transaction makeTimelockTransaction(
            final Map<String, Long> receivers,
            final Collection<MyTransactionOutPoint> unspent,
            final FidelityBondsTimelockedBipFormat bipFormat,
            final NetworkParameters params) throws MakeTxException {

        final Transaction tx = new Transaction(params);
        tx.setVersion(2);
        tx.setLockTime(bipFormat.getTimelock());


        final FidelityBondsTimelockedBipFormatSupplier supplier = FidelityBondsTimelockedBipFormatSupplier.create(bipFormat);

        final List<TransactionOutput> outputs = new ArrayList<>();

        for(final Iterator<Map.Entry<String, Long>> iterator = receivers.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<String, Long> mapEntry = iterator.next();
            String toAddress = mapEntry.getKey();
            BigInteger value = BigInteger.valueOf(mapEntry.getValue());

            if(value == null || (value.compareTo(BigInteger.ZERO) <= 0
                    && !FormatsUtilGeneric.getInstance().isValidBIP47OpReturn(toAddress))) {
                throw new MakeTxException("Invalid amount");
            }

            try {
                TransactionOutput output = supplier.getTransactionOutput(toAddress, value.longValue(), params);
                outputs.add(output);
            } catch (final Exception e) {
                Log.e(TransactionForSweepHelper.class.getSimpleName(), "getTransactionOutput failed", e);
                throw new MakeTxException(e);
            }
        }

        final List<TransactionInput> inputs = new ArrayList<>();
        for(final MyTransactionOutPoint outPoint : unspent) {
            // check outpoint format
            try {
                supplier.getToAddress(outPoint.getScriptBytes(), params);
                // ok, outpoint format is supported
            } catch (Exception e) {
                // outpoint format is not supported, skip it
                Log.e(TransactionForSweepHelper.class.getSimpleName(), "skipping outPoint (unsupported type): "+outPoint);
                continue;
            }

            final TransactionInput input = outPoint.computeSpendInput();
            input.setSequenceNumber(SamouraiWalletConst.RBF_SEQUENCE_VAL_WITH_NLOCKTIME.longValue());
            inputs.add(input);
        }

        if (inputs.isEmpty()) {
            throw new MakeTxException("TX has no inputs");
        }
        if (outputs.isEmpty()) {
            throw new MakeTxException("TX has no outputs");
        }

        //
        // deterministically sort inputs and outputs, see BIP69 (OBPP)
        //
        Collections.sort(inputs, new BIP69InputComparator());
        for(final TransactionInput input : inputs) {
            tx.addInput(input);
        }

        Collections.sort(outputs, new BIP69OutputComparator());
        for(final TransactionOutput to : outputs) {
            tx.addOutput(to);
        }

        return tx;
    }


     */
    public static Transaction signTransactionForSweep(
            final Transaction unsignedTx,
            final ECKey privKey,
            final NetworkParameters params,
            final BipFormatSupplier bipFormatSupplier) throws SignTxException {

        final HashMap<String,ECKey> keyBag = new HashMap<>();
        for (final TransactionInput input : unsignedTx.getInputs()) {
            try {
                final DumpedPrivateKey pk = DumpedPrivateKey.fromBase58(params, privKey.getPrivateKeyAsWiF(params));
                keyBag.put(input.getOutpoint().toString(), pk.getKey());
            }  catch(Exception e) {
                Log.e(TransactionForSweepHelper.class.getCanonicalName(),
                        "cannot process private key for input=" + input,
                        e);
            }
        }

        return SendFactoryGeneric.getInstance()
                .signTransaction(unsignedTx, keyBag, bipFormatSupplier);
    }
}
