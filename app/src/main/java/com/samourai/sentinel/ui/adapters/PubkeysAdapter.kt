package com.samourai.sentinel.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import org.bitcoinj.core.Coin
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import java.math.BigInteger
import java.nio.ByteBuffer
import java.text.DecimalFormat
import java.util.*
import kotlin.collections.ArrayList


class PubkeysAdapter : RecyclerView.Adapter<PubkeysAdapter.CollectionHolder>() {

    private var onClickListener: (PubKeyModel) -> Unit = {};
    private val monetaryUtil: MonetaryUtil by KoinJavaComponent.inject(MonetaryUtil::class.java)
    private var layoutType: LayoutType = LayoutType.ROW;

    private val diffCallBack = object : DiffUtil.ItemCallback<PubKeyModel>() {

        override fun areItemsTheSame(oldItem: PubKeyModel, newItem: PubKeyModel): Boolean {
            return oldItem.pubKey == newItem.pubKey
        }

        override fun areContentsTheSame(oldItem: PubKeyModel, newItem: PubKeyModel): Boolean {
            return oldItem == newItem
        }

    }
//
//    init {
//        this.setHasStableIds(true)
//    }


    private val mDiffer: AsyncListDiffer<PubKeyModel> = AsyncListDiffer(this, diffCallBack)

    class CollectionHolder(var view: View) : RecyclerView.ViewHolder(view) {
        var title: TextView = view.findViewById(R.id.rvItemCollectionTitle)
        var balance: TextView = view.findViewById(R.id.rvItemCollectionBalance)
        var icon: ImageView = view.findViewById(R.id.rvItemCollectionIcon)
    }

    fun setOnClickListener(callback: (PubKeyModel) -> Unit) {
        this.onClickListener = callback
    }

    fun setLayoutType(layout: LayoutType) {
        this.layoutType = layout
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CollectionHolder {
        return if (layoutType == LayoutType.ROW) {
            val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.collection_item_row_layout, parent, false);
            CollectionHolder(view)

        } else {
            val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.collection_item_stacked_layout, parent, false);
            CollectionHolder(view)
        }
    }

    override fun getItemCount(): (Int) {
        return mDiffer.currentList.size
    }

    fun getPubkeyList(): MutableList<PubKeyModel> {
        return mDiffer.currentList
    }
//
//    override fun getItemId(position: Int): Long {
//        return getLongIdFromUUID(this.mDiffer.currentList[position].id);
//    }

    override fun onBindViewHolder(holder: CollectionHolder, position: Int) {
        val df = DecimalFormat("#")
        df.minimumIntegerDigits = 1
        df.minimumFractionDigits = 8
        df.maximumFractionDigits = 8

        val collection = mDiffer.currentList[position];
        holder.title.text = collection.label
        holder.balance.text = "${df.format(setBalance(position).div(1e8))} BTC"
        holder.view.setOnClickListener {
            onClickListener(collection)
        }
    }

    fun setBalance(position: Int): Long {
        val df = DecimalFormat("#")
        df.minimumIntegerDigits = 1
        df.minimumFractionDigits = 8
        df.maximumFractionDigits = 8

        var blockedUtxosBalanceSum = 0L
        val blockedUtxos1 = UtxoMetaUtil.getBlockedAssociatedWithPubKey(mDiffer.currentList.get(position).pubKey)
        blockedUtxos1.forEach { blockedUtxo ->
            blockedUtxosBalanceSum += blockedUtxo.amount
        }
        return mDiffer.currentList[position].balance - blockedUtxosBalanceSum
    }

    fun update(newItems: ArrayList<PubKeyModel>) {
        try {
            val list: ArrayList<PubKeyModel> = arrayListOf<PubKeyModel>()
            // Diff util will perform a shallow compare with updated list,
            // since we're using same list with updated items. we need to make a new copy
            // this will make shallow compare false
            newItems.forEach { list.add(it.copy()) }
            mDiffer.submitList(list)
        } catch (e: Exception) {
            Timber.e(e)
        }

    }

    companion object {
        fun getLongIdFromUUID(value: String): Long {
            var id = 1L;
            val uid = UUID.fromString(value)
            val buffer: ByteBuffer = ByteBuffer.wrap(ByteArray(16))
            buffer.putLong(uid.leastSignificantBits)
            buffer.putLong(uid.mostSignificantBits)
            val bi = BigInteger(buffer.array())
            id = bi.toLong() and Long.MAX_VALUE
            return id
        }

        enum class LayoutType {
            STACKED,
            ROW
        }
    }


    private fun getBTCDisplayAmount(value: Long): String? {
        return Coin.valueOf(value).toPlainString()
    }

}
