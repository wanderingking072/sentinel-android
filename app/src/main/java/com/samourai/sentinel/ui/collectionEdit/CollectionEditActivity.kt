package com.samourai.sentinel.ui.collectionEdit

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.databinding.ActivityCollectionEditBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.fragments.AddNewPubKeyBottomSheet
import com.samourai.sentinel.ui.fragments.QRBottomSheetDialog
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.alertWithInput
import com.samourai.sentinel.ui.views.confirm
import com.samourai.sentinel.util.AppUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject


class CollectionEditActivity : SentinelActivity() {

    private var needCollectionRefresh = false
    private val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private val prefs: PrefsUtil by inject(PrefsUtil::class.java)
    private val transactionsRepository: TransactionsRepository by inject(TransactionsRepository::class.java)
    private val viewModel: CollectionEditViewModel by viewModels()
    private val pubKeyAdapter: PubKeyAdapter = PubKeyAdapter()
    private lateinit var binding: ActivityCollectionEditBinding
    private var isEditNewPub = false
    private var editIndex: Int = -1
    private lateinit var newPubEdit: PubKeyModel
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectionEditBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setSupportActionBar(binding.toolbarCollectionDetails)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        checkIntent()

        setUpPubKeyList()

        viewModel.getCollection().observe(this) {
            binding.collectionEdiText.setText(it.collectionLabel.trimEnd())
            binding.collectionEdiText.doOnTextChanged { text, _, _, _ ->
                it.collectionLabel = text.toString()
            }
        }

        viewModel.getPubKeys().observe(this, Observer {
            pubKeyAdapter.update(it)
        })

        viewModel.message.observe(this, Observer {
            if (it != null) {
                if (it.isEmpty()) {
                    return@Observer
                }
                AndroidUtil.hideKeyboard(this)
                this@CollectionEditActivity.showFloatingSnackBar(
                    binding.collectionEdiText.parent as ViewGroup,
                    text = "Success", duration = Snackbar.LENGTH_LONG
                )
            }
        })

        binding.addNewPubFab.setOnClickListener {
            if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext)) {
                this.askCameraPermission()
            } else {
                if (AppUtil.getInstance(applicationContext).isOfflineMode
                    ||  SentinelState.torState ==SentinelState.TorState.WAITING)
                    Toast.makeText(this, "No data connection. Please wait, then try again", Toast.LENGTH_LONG).show()
                else
                    showPubKeyBottomSheet()
            }
        }

        binding.collectionEditNestedScroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY > oldScrollY + 12 && binding.addNewPubFab.isExtended) {
                binding.addNewPubFab.hide()
            }
            if (scrollY < oldScrollY - 12 && !binding.addNewPubFab.isExtended) {
                binding.addNewPubFab.show()
                binding.addNewPubFab.extend()
            }
            if (scrollY == 0) {
                binding.addNewPubFab.show()
                binding.addNewPubFab.extend()
            }
        })

    }

    private fun checkIntent() {
        if (intent == null) {
            finish()
            return
        }
        if (intent.extras != null && intent.extras!!.containsKey("collection")) {
            val collectionModel = PubKeyCollection()
            collectionModel.collectionLabel = binding.collectionEdiText.text.toString()
            val model = intent.extras?.getString("collection")?.let { repository.findById(it) }
            if (model != null) {
                viewModel.setCollection(model)
                // Check if any new Public key is passed through intent
                // we will set last item as editable so the new Public key  will be shown in edit layout
                if (intent.extras!!.containsKey("pubKey")) {
                    val newPubKey = intent.extras!!.getParcelable<PubKeyModel>("pubKey")

                    if (newPubKey?.pubKey?.let { model.getPubKey(it) } != null) {
                        this@CollectionEditActivity.showFloatingSnackBar(
                                binding.collectionDetailsRootLayout,
                            text = "Public key already exists in this collection",
                            duration = Snackbar.LENGTH_LONG
                        )
                        return
                    }
                    if (intent.extras!!.containsKey("editIndex")) {
                        editIndex = intent.extras!!.getInt("editIndex")
                        isEditNewPub = true
                        newPubEdit = newPubKey!!
                    }
                    viewModel.setPubKeys(model.pubs.apply { add(newPubKey!!) })
                    importWalletIfSegwit(newPubKey)
                } else {
                    viewModel.setPubKeys(model.pubs)
                    if (model.pubs.isNotEmpty()) {
                        pubKeyAdapter.setEditingPubKey(model.pubs[model.pubs.lastIndex].pubKey)
                    }
                }
            }
        } else {
            val collectionModel = PubKeyCollection()
            collectionModel.balance = 0
            collectionModel.collectionLabel = binding.collectionEdiText.text.toString()
            viewModel.setCollection(collectionModel)
            if (intent.extras!!.containsKey("pubKey")) {
                val newPubKey = intent.extras!!.getParcelable<PubKeyModel>("pubKey")
                edit(newPubKey!!, 0)
                needCollectionRefresh = true
                viewModel.setPubKeys(arrayListOf(newPubKey!!))
                importWalletIfSegwit(newPubKey)
            }
        }

    }

    private fun importWalletIfSegwit(newPubKey: PubKeyModel?) {
        val apiService: ApiService by inject(ApiService::class.java)

        if (newPubKey != null) {
            if (newPubKey.type == AddressTypes.ADDRESS) {
                apiScope.launch {
                    apiService.importAddress(newPubKey.pubKey)
                }
            }
            else if (newPubKey.type == AddressTypes.BIP84 || newPubKey.type == AddressTypes.BIP49) {
                apiScope.launch {
                    apiService.importXpub(newPubKey.pubKey, newPubKey.type!!.name)
                }
            }
            else {
                apiScope.launch {
                    apiService.importXpub(newPubKey.pubKey, "44")
                }
            }
        }
    }

    override fun onBackPressed() {
        if (binding.collectionEdiText.text.isNullOrEmpty() || binding.collectionEdiText.text.isBlank()) {
            this@CollectionEditActivity.showFloatingSnackBar(
                binding.collectionEdiText.parent as ViewGroup,
                text = "Please enter collection label",
                duration = Snackbar.LENGTH_SHORT
            )
            return
        }
        super.onBackPressed()
    }


    private fun setUpPubKeyList() {
        var isMoreButton = false
        val linearLayoutManager = LinearLayoutManager(this)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL

        binding.pubKeyRecyclerView.apply {
            layoutManager = linearLayoutManager
            adapter = pubKeyAdapter
        }

        binding.pubKeyRecyclerView.addOnItemTouchListener(object :
            RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                fun  viewPubKey(pubKeyModel: PubKeyModel){
                    val dialog =  QRBottomSheetDialog(
                        pubKeyModel.pubKey,
                        pubKeyModel.label,
                        pubKeyModel.label,
                        secure = prefs.displaySecure!!
                    )
                    dialog.show(supportFragmentManager, dialog.tag)
                }
                apiScope.launch {
                    withContext(Dispatchers.IO) {
                        delay(400)
                        val childView: View? = rv.findChildViewUnder(e.x, e.y)
                        if (childView != null && e.action == MotionEvent.ACTION_UP) {
                            val position = rv.getChildAdapterPosition(childView)
                            if (position != RecyclerView.NO_POSITION && !isMoreButton) {
                                println("This goes first 1")
                                viewPubKey(viewModel.getPubKeys().value!!.get(position))
                            }
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })

        fun delete(index: Int){
            this.confirm(label = "Confirm",
                message = "Are you sure want to remove this public key ?",
                positiveText = "Yes",
                negativeText = "No",
                onConfirm = { confirmed ->
                    if (confirmed) {
                        val collection = viewModel.getCollection().value ?: return@confirm
                        apiScope.launch(context = Dispatchers.IO) {
                            transactionsRepository.removeTxsRelatedToPubKey(
                                collection.pubs[index],
                                collection.id
                            )
                            needCollectionRefresh = true
                            withContext(Dispatchers.Main) {
                                setResult(Activity.RESULT_OK)
                            }
                            viewModel.removePubKey(index)

                            //TODO: find a better way to refresh the pubkey list
                            binding.pubKeyRecyclerView.post {
                                pubKeyAdapter.notifyDataSetChanged()
                            }
                        }
                        try {
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            )
        }

        fun  viewPubKey(pubKeyModel: PubKeyModel){
           val dialog =  QRBottomSheetDialog(
               pubKeyModel.pubKey,
               pubKeyModel.label,
               pubKeyModel.label,
               secure = prefs.displaySecure!!
           )
            dialog.show(supportFragmentManager, dialog.tag)
        }

        val items = arrayListOf("Edit", "View Master Fingerprint","Delete")
        pubKeyAdapter.setOnEditClickListener { i, pubKeyModel ->
            println("This goes first 2")
            isMoreButton = true
            MaterialAlertDialogBuilder(this)
                .setItems(
                    items.toTypedArray()
                ) { _, which ->
                    when (which) {
                        0 -> {
                            edit(pubKeyModel, i)
                        }
                        1 -> {
                            editFingerprint(pubKeyModel, i)
                        }
                        2 -> {
                            delete(i)
                        }
                    }
                }
                .setTitle(getString(R.string.options))
                .setOnDismissListener {
                    isMoreButton = false
                }
                .show()
        }

        if(isEditNewPub) {
            edit(newPubEdit, editIndex)
            isEditNewPub = false
        }
    }

    fun edit(pubKeyModel: PubKeyModel, i: Int){
            this.alertWithInput(
                label = "Public key label",
                onConfirm = {
                    pubKeyModel.label = it
                    viewModel.updateKey(i, pubKeyModel)
                    pubKeyAdapter.notifyItemChanged(i)
                },
                isCancelable = false,
                maxLen = 30,
                labelEditText = "Label",
                value = pubKeyModel.label,
                buttonLabel = "Save"
            )
    }

    fun editFingerprint(pubKeyModel: PubKeyModel, i: Int){
        this.alertWithInput(
            label = "Master Fingerprint",
            onConfirm = {
                pubKeyModel.fingerPrint = it
                viewModel.updateKey(i, pubKeyModel)
                pubKeyAdapter.notifyItemChanged(i)
            },
            isCancelable = true,
            maxLen = 8,
            labelEditText = "Fingerprint",
            value = if (pubKeyModel.fingerPrint == null) "" else pubKeyModel.fingerPrint!!,
            buttonLabel = "Save"
        )
    }

    override fun onDestroy() {
        if (needCollectionRefresh) {
            viewModel.getCollection().value?.id?.let {
                apiScope.launch {
                    transactionsRepository.fetchFromServer(it)
                }
            }
        }
        super.onDestroy()
    }


    private fun showPubKeyBottomSheet() {
        val bottomSheetFragment = AddNewPubKeyBottomSheet(secure = prefsUtil.displaySecure!!)
        bottomSheetFragment.setPubKeyListener {
            if (it != null) {
                val items: ArrayList<PubKeyModel> = arrayListOf()
                if (viewModel.getCollection().value?.getPubKey(it.pubKey) != null) {
                    this@CollectionEditActivity.showFloatingSnackBar(
                            binding.collectionDetailsRootLayout,
                        text = "Public key already exists in this collection",
                        duration = Snackbar.LENGTH_LONG
                    )
                } else {
                    //Add all existing public keys
                    viewModel.getPubKeys().value?.let { it1 -> items.addAll(it1) }
                    items.add(it)
                    needCollectionRefresh = true
                    pubKeyAdapter.setEditingPubKey(it.pubKey)
                    viewModel.setPubKeys(items)
                    edit(it, items.size-1)
                }

            }
        }
        bottomSheetFragment.show(supportFragmentManager, bottomSheetFragment.tag)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.saveCollectionMenuItem || item.itemId == android.R.id.home) {
            if (binding.collectionEdiText.text.isNullOrEmpty() || binding.collectionEdiText.text.isBlank()) {
                this@CollectionEditActivity.showFloatingSnackBar(
                        binding.collectionEdiText.parent as ViewGroup,
                    text = "Please enter collection label",
                    duration = Snackbar.LENGTH_SHORT
                )
            } else {
                viewModel.save()
                this.finish()
            }
        }
        else if (item.itemId == R.id.deleteCollection) {
            deleteCollection()
        }
        else
            return super.onOptionsItemSelected(item)
        return true
    }

    private fun deleteCollection() {

        this.confirm(label = "Confirm",
            message = "Are you sure want to delete this Collection ?",
            positiveText = "Yes",
            negativeText = "No",
            onConfirm = { confirmed ->
                if (confirmed) {
                    val collection = viewModel.getCollection().value ?: return@confirm
                    CoroutineScope(Dispatchers.Default).launch {
                        val job = async { viewModel.removeCollection(collection.id) }
                        job.await()
                        withContext(Dispatchers.Main) {
                            job.invokeOnCompletion {
                                if (it == null) {
                                    Toast.makeText(
                                        applicationContext,
                                        "Collection removed",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    startActivity(Intent(
                                        this@CollectionEditActivity,
                                        HomeActivity::class.java
                                    ).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    })
                                    finish()
                                } else {
                                    it.printStackTrace()
                                }
                            }
                        }
                    }
                }
            }
        )

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.collection_details_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }
}