package com.samourai.sentinel.sweep;

import com.samourai.sentinel.core.SentinelState;
import com.samourai.sentinel.util.FormatsUtil;
import com.samourai.wallet.send.MyTransactionOutPoint;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.bitcoinj.core.Address;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

public class FeeUtil  {

    private static String[] providers = {
            "Samourai (bitcoind)",
    };

    private static final int ESTIMATED_INPUT_LEN_P2PKH = 148; // compressed key
    private static final int ESTIMATED_INPUT_LEN_P2SH_P2WPKH = 102; // p2sh, includes segwit discount (ex: 146)
    private static final int ESTIMATED_INPUT_LEN_P2WPKH = 102;
    private static final int ESTIMATED_OUTPUT_LEN = 34;

    private static SuggestedFee suggestedFee = null;
    private static SuggestedFee highFee = null;
    private static SuggestedFee normalFee = null;
    private static SuggestedFee lowFee = null;
    private static List<SuggestedFee> estimatedFees = null;

    private static FeeUtil instance = null;

    private FeeUtil() { ; }

    public static FeeUtil getInstance() {

        if(instance == null)    {
            estimatedFees = new ArrayList<SuggestedFee>();
            highFee = new SuggestedFee();
            suggestedFee = new SuggestedFee();
            lowFee = new SuggestedFee();
            instance = new FeeUtil();
        }

        return instance;
    }

    public String[] getProviders()	 {
        return providers;
    }

    public synchronized SuggestedFee getSuggestedFee() {
        if(suggestedFee != null)    {
            return suggestedFee;
        }
        else    {
            SuggestedFee fee = new SuggestedFee();
            fee.setDefaultPerKB(BigInteger.valueOf(10000L));
            return fee;
        }
    }

    public synchronized void setSuggestedFee(SuggestedFee suggestedFee) {
        FeeUtil.suggestedFee = suggestedFee;
    }

    public synchronized SuggestedFee getHighFee() {
        if(highFee == null)    {
            highFee = getSuggestedFee();
        }

        return highFee;
    }

    public synchronized void setHighFee(SuggestedFee highFee) {
        FeeUtil.highFee = highFee;
    }

    public synchronized SuggestedFee getNormalFee() {
        if(normalFee == null)    {
            normalFee = getSuggestedFee();
        }

        return normalFee;
    }

    public synchronized void setNormalFee(SuggestedFee normalFee) {
        FeeUtil.normalFee = normalFee;
    }

    public synchronized SuggestedFee getLowFee() {
        if(lowFee == null)    {
            lowFee = getSuggestedFee();
        }

        return lowFee;
    }

    public synchronized void setLowFee(SuggestedFee lowFee) {
        FeeUtil.lowFee = lowFee;
    }

    public synchronized List<SuggestedFee> getEstimatedFees() {
        return estimatedFees;
    }

    public synchronized void setEstimatedFees(List<SuggestedFee> estimatedFees) {
        FeeUtil.estimatedFees = estimatedFees;

        switch(estimatedFees.size())    {
            case 1:
                suggestedFee = highFee = normalFee = lowFee = estimatedFees.get(0);
                break;
            case 2:
                suggestedFee = highFee = normalFee = estimatedFees.get(0);
                lowFee = estimatedFees.get(1);
                break;
            case 3:
                highFee = estimatedFees.get(0);
                suggestedFee = estimatedFees.get(1);
                normalFee = estimatedFees.get(1);
                lowFee = estimatedFees.get(2);
                break;
            default:
                break;
        }

    }

    public BigInteger estimatedFee(int inputs, int outputs)   {
        int size = estimatedSize(inputs, outputs);
        return calculateFee(size, getSuggestedFee().getDefaultPerKB());
    }

    public BigInteger estimatedFeeSegwit(int inputsP2PKH, int inputsP2SHP2WPKH, int outputs)   {
        int size = estimatedSizeSegwit(inputsP2PKH, inputsP2SHP2WPKH, outputs);
        return calculateFee(size, getSuggestedFee().getDefaultPerKB());
    }

    public BigInteger estimatedFeeSegwit(int inputsP2PKH, int inputsP2SHP2WPKH, int inputsP2WPKH, int outputs)   {
        int size = estimatedSizeSegwit(inputsP2PKH, inputsP2SHP2WPKH, inputsP2WPKH, outputs);
        return calculateFee(size, getSuggestedFee().getDefaultPerKB());
    }

    public int estimatedSize(int inputs, int outputs)   {
        return (outputs * ESTIMATED_OUTPUT_LEN) + (inputs * ESTIMATED_INPUT_LEN_P2PKH) + inputs + 8 + 1 + 1;
    }

    public int estimatedSizeSegwit(int inputsP2PKH, int inputsP2SHP2WPKH, int outputs)   {
        return (outputs * ESTIMATED_OUTPUT_LEN) + (inputsP2PKH * ESTIMATED_INPUT_LEN_P2PKH) + (inputsP2SHP2WPKH * ESTIMATED_INPUT_LEN_P2SH_P2WPKH) + inputsP2PKH + inputsP2SHP2WPKH + 8 + 1 + 1;
    }

    public int estimatedSizeSegwit(int inputsP2PKH, int inputsP2SHP2WPKH, int inputsP2WPKH, int outputs)   {
        return (outputs * ESTIMATED_OUTPUT_LEN) + (inputsP2PKH * ESTIMATED_INPUT_LEN_P2PKH) + (inputsP2SHP2WPKH * ESTIMATED_INPUT_LEN_P2SH_P2WPKH) + (inputsP2WPKH * ESTIMATED_INPUT_LEN_P2WPKH) + inputsP2PKH + inputsP2SHP2WPKH + inputsP2WPKH + 8 + 1 + 1;
    }

    public BigInteger estimatedFee(int inputs, int outputs, BigInteger feePerKb)   {
        int size = estimatedSize(inputs, outputs);
        return calculateFee(size, feePerKb);
    }

    public BigInteger calculateFee(int txSize, BigInteger feePerKb)   {
        double fee = ((double)txSize / 1000.0 ) * feePerKb.doubleValue();
        return BigInteger.valueOf((long)fee);
    }

    public Pair<Integer,Integer> getOutpointCount(List<MyTransactionOutPoint> outpoints) {

        int p2sh_p2wpkh = 0;
        int p2pkh = 0;

        for(MyTransactionOutPoint out : outpoints)   {
            if(Address.fromBase58(SentinelState.Companion.getNetworkParam(), out.getAddress()).isP2SHAddress())    {
                p2sh_p2wpkh++;
            }
            else   {
                p2pkh++;
            }
        }

        return Pair.of(p2pkh, p2sh_p2wpkh);
    }

    public Triple<Integer,Integer,Integer> getOutpointCount(Vector<MyTransactionOutPoint> outpoints) {

        int p2wpkh = 0;
        int p2sh_p2wpkh = 0;
        int p2pkh = 0;

        for(MyTransactionOutPoint out : outpoints)   {
            if(Address.fromBase58(SentinelState.Companion.getNetworkParam(), out.getAddress()).isP2SHAddress())    {
                p2sh_p2wpkh++;
            }
            else if(FormatsUtil.Companion.isValidBech32(out.getAddress()))    {
                p2wpkh++;
            }
            else   {
                p2pkh++;
            }
        }

        return Triple.of(p2pkh, p2sh_p2wpkh, p2wpkh);
    }

}
