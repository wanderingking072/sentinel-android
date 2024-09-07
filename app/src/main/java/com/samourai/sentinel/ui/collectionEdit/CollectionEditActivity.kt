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
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.databinding.ActivityCollectionEditBinding
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
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
    private var pressStartTime: Long = 0
    private var pressedX: Float = 0.0F
    private var pressedY: Float = 0.0F

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCollectionEditBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setSupportActionBar(binding.toolbarCollectionDetails)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        checkIntent()


        viewModel.getCollection().observe(this) {
            binding.collectionEdiText.setText(it.collectionLabel.trimEnd())
            pubKeyAdapter.setIsImportFromWallet(it.isImportFromWallet)
            setUpPubKeyList()
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
                    ||  SentinelTorManager.getTorState().state == EnumTorState.STARTING)
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
                if (model.isImportFromWallet)
                    binding.addNewPubFab.visibility = View.GONE
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
        val linearLayoutManager = LinearLayoutManager(this)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL

        binding.pubKeyRecyclerView.apply {
            layoutManager = linearLayoutManager
            adapter = pubKeyAdapter
        }

        binding.pubKeyRecyclerView.addOnItemTouchListener(object :
            RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {

                fun  viewPubKey(pubKeyModel: PubKeyModel, position: Int){
                    val dialog =  QRBottomSheetDialog(
                        pubKeyModel.pubKey,
                        pubKeyModel.label,
                        pubKeyModel.label,
                        secure = prefs.displaySecure!!,
                        collection = if (viewModel.getCollection().value!!.isImportFromWallet && position == 0) viewModel.getCollection().value else null
                    )
                    dialog.show(supportFragmentManager, dialog.tag)
                }

                fun pxToDp(px: Float): Float {
                    return px / resources.displayMetrics.density
                }

                fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
                    val dx = x1 - x2
                    val dy = y1 - y2
                    val distanceInPx = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    return pxToDp(distanceInPx)
                }

                //Max allowed duration for a "click", in milliseconds.
                val MAX_CLICK_DURATION = 4000;
                //Max allowed distance to move during a "click", in DP.
                val MAX_CLICK_DISTANCE = 15;

                val childView: View? = rv.findChildViewUnder(e.x, e.y)
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        pressStartTime = System.currentTimeMillis()
                        pressedX = e.getX()
                        pressedY = e.getY()
                    }
                    MotionEvent.ACTION_UP -> {
                        val pressDuration = System.currentTimeMillis() - pressStartTime
                        if (pressDuration < MAX_CLICK_DURATION && distance(pressedX, pressedY, e.getX(), e.getY()) < MAX_CLICK_DISTANCE && childView != null) {
                            val position = rv.getChildAdapterPosition(childView)
                            if (position != RecyclerView.NO_POSITION && (e.x < 920)) {
                                viewPubKey(viewModel.getPubKeys().value!![position], position)
                            }
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })

        fun delete(index: Int, pubKeyModel: PubKeyModel){
            this.confirm(label = "Confirm",
                message = "Are you sure want to remove this public key ?",
                positiveText = "Yes",
                negativeText = "No",
                onConfirm = { confirmed ->
                    if (confirmed) {
                        val collection = viewModel.getCollection().value ?: return@confirm
                        apiScope.launch(context = Dispatchers.IO) {
                            transactionsRepository.removeTxsRelatedToPubKey(
                                collection.pubs[collection.pubs.indexOf(pubKeyModel)],
                                collection.id
                            )
                            needCollectionRefresh = true
                            withContext(Dispatchers.Main) {
                                setResult(Activity.RESULT_OK)
                            }
                            viewModel.removePubKey(collection.pubs.indexOf(pubKeyModel))
                            pubKeyAdapter.update(viewModel.getPubKeys().value!!)
                            //TODO: find a better way to refresh the pubkey list
                            binding.pubKeyRecyclerView.post {
                                pubKeyAdapter.notifyItemRemoved(index)
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

        val items =
            if (viewModel.getCollection().value!!.isImportFromWallet)
                arrayListOf("View Master Fingerprint","Delete")
            else
                arrayListOf("Edit","View Master Fingerprint","Delete")
        pubKeyAdapter.setOnEditClickListener { i, pubKeyModel ->
            MaterialAlertDialogBuilder(this)
                .setItems(
                    items.toTypedArray()
                ) { _, which ->
                    when (which) {
                        0 -> {
                            if (!viewModel.getCollection().value!!.isImportFromWallet)
                                edit(pubKeyModel, i)
                            else
                                editFingerprint(pubKeyModel, i)
                        }
                        1 -> {
                            if (!viewModel.getCollection().value!!.isImportFromWallet)
                                editFingerprint(pubKeyModel, i)
                            else
                                delete(i, pubKeyModel)
                        }
                        2 -> {
                            if (!viewModel.getCollection().value!!.isImportFromWallet)
                                delete(i, pubKeyModel)
                        }
                    }
                }
                .setTitle(getString(R.string.options))
                .setOnDismissListener {
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
            buttonLabel = "Save",
            isEditable = !(viewModel.getCollection().value?.isImportFromWallet != null && viewModel.getCollection().value?.isImportFromWallet!!)
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
            buttonLabel = "Save",
            isEditable = !viewModel.getCollection().value?.isImportFromWallet!!
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
                    importWalletIfSegwit(items.last())
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