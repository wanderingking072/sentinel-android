package com.samourai.sentinel.data.db.dao

import androidx.lifecycle.LiveData
import androidx.paging.DataSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.samourai.sentinel.data.Tx

/**
 * sentinel-android
 *
 */
const val TX_PAGE_SIZE = 200;
@Dao
interface TxDao {

    @Query("SELECT * from transactions WHERE collectionId=:collectionID  ORDER BY time ASC LIMIT $TX_PAGE_SIZE")
    fun getAllTx(collectionID: String): LiveData<List<Tx>>


    @Query("SELECT * from transactions WHERE collectionId=:collectionID  AND associatedPubKey=:associatedPubKey ORDER BY time DESC ,confirmations ASC ")
    fun getPaginatedTx(collectionID: String, associatedPubKey: String): DataSource.Factory<Int, Tx>

    @Query("SELECT * FROM transactions WHERE collectionId=:collectionID AND associatedPubKey IN (:pubKeys)  ORDER BY time DESC ,confirmations ASC ")
    fun getPaginatedTx(collectionID: String, pubKeys: List<String>): DataSource.Factory<Int, Tx>

    @Query("SELECT * from transactions WHERE collectionId=:collectionID AND associatedPubKey=:pubKey  ORDER BY time DESC LIMIT $TX_PAGE_SIZE")
    fun getAssociated(collectionID: String, pubKey: String): List<Tx>

    @Query("SELECT * from transactions WHERE associatedPubKey=:pubKey  ORDER BY time DESC LIMIT $TX_PAGE_SIZE")
    fun getAssociated(pubKey: String): List<Tx>

    @Query("SELECT * from transactions WHERE collectionId=:collectionID  ORDER BY time DESC LIMIT $TX_PAGE_SIZE")
    fun getTxAssociatedToCollection(collectionID: String): DataSource.Factory<Int, Tx>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tx: Tx)

    @Query("DELETE FROM transactions")
    suspend fun delete()

    @Query("DELETE FROM transactions WHERE collectionId=:collectionID AND associatedPubKey=:pubKey")
    fun deleteByCollectionAndPubkey(collectionID: String, pubKey: String)

    @Query("DELETE FROM transactions WHERE collectionId=:collectionID")
    suspend fun deleteByCollectionID(collectionID: String)

    @Query("UPDATE transactions SET confirmations=:confirmations where hash=:txHash")
    fun updateTxConfirmations(txHash: String, confirmations: Long)
}