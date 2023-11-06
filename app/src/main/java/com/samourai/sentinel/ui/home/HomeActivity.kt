package com.samourai.sentinel.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.api.APIConfig
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.databinding.ActivityHomeBinding
import com.samourai.sentinel.service.WebSocketHandler
import com.samourai.sentinel.service.WebSocketService
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.adapters.CollectionsAdapter
import com.samourai.sentinel.ui.broadcast.BroadcastTx
import com.samourai.sentinel.ui.collectionDetails.CollectionDetailsActivity
import com.samourai.sentinel.ui.dojo.DojoConfigureBottomSheet
import com.samourai.sentinel.ui.fragments.AddNewPubKeyBottomSheet
import com.samourai.sentinel.ui.settings.NetworkActivity
import com.samourai.sentinel.ui.settings.SettingsActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.RecyclerViewItemDividerDecorator
import com.samourai.sentinel.ui.utils.SlideInItemAnimator
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.confirm
import com.samourai.sentinel.util.AppUtil
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import io.matthewnelson.topl_service.TorServiceController
import io.matthewnelson.topl_service_base.TorServicePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject


class HomeActivity : SentinelActivity() {

    private lateinit var linearLayoutManager: LinearLayoutManager
    private val collectionsAdapter = CollectionsAdapter()
    private val webSocketHandler: WebSocketHandler by inject(WebSocketHandler::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private var connectingDojo = false
    private lateinit var torServicePrefs: TorServicePrefs
    private lateinit var binding: ActivityHomeBinding
    private val model: HomeViewModel by viewModels()
    private var balance = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setSwipeBackEnable(false)
        setSupportActionBar(binding.toolbarHome)
        torServicePrefs = TorServicePrefs(this)

        val model: HomeViewModel by viewModels()
        if (SentinelState.isTorRequired() && SentinelState.torState == SentinelState.TorState.OFF) {
            TorServiceController.startTor()
            prefsUtil.enableTor = true
        }

        if (
            !AndroidUtil.isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS, applicationContext)
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && prefsUtil.firstRun == true
        )
            this.askNotificationPermission()

        setUp()

        setUpCollectionList()

        model.getCollections().observe(this, {
            if (it.isNotEmpty())
                binding.welcomeMessage.visibility = View.GONE
            else
                binding.welcomeMessage.visibility = View.VISIBLE

            collectionsAdapter.update(it)
        })

        model.getBalance().observe(this) {
            updateBalance(it)
            balance = it
        }

        binding.exchangeRateTxt.visibility = if (prefsUtil.fiatDisabled!!) View.INVISIBLE else View.VISIBLE

        model.getFiatBalance().observe(this, { updateFiat(it) })

        binding.fab.setOnClickListener {
            connectingDojo = false
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

        model.loading().observe(this, {
            binding.swipeRefreshCollection.isRefreshing = it
        })
        model.getErrorMessage().observe(this) {
            if (it != "null" &&  SentinelState.torState != SentinelState.TorState.WAITING)
                this@HomeActivity.showFloatingSnackBar(
                    binding.fab,
                    text = "No data connection available. Please enable data"
                )
        }

        if (intent != null) {
            if (intent.hasExtra("forceRefresh") && intent.getBooleanExtra("forceRefresh", true)) {
                model.fetchBalance()
            }
        }

        binding.swipeRefreshCollection.setOnRefreshListener {
            binding.swipeRefreshCollection.isRefreshing = false
            if (SentinelState.isTorRequired()) {
                if (SentinelState.torState == SentinelState.TorState.WAITING) {
                    this.showFloatingSnackBar(binding.fab, anchorView = binding.fab.id,
                            text = "Tor is bootstrapping! please wait and try again")
                }
                if (SentinelState.torState == SentinelState.TorState.OFF) {
                    this.showFloatingSnackBar(binding.fab,
                            text="Please wait while Tor is turning on")
                    TorServiceController.startTor()
                    prefsUtil.enableTor = true
                }
                if (SentinelState.torState == SentinelState.TorState.ON) {
                    model.fetchBalance()
                }
            } else {
                model.fetchBalance()
            }
        }

        fetch(model)

        if (SentinelState.isTorRequired()) {
            SentinelState.torStateLiveData().observe(this, {
                if (it == SentinelState.TorState.ON)
                    WebSocketService.start(applicationContext)
            })
        } else {
            WebSocketService.start(applicationContext)
        }

        checkClipBoard()
    }


    private fun fetch(model: HomeViewModel) {
        if (!SentinelState.isRecentlySynced()) {
            if (SentinelState.isTorRequired() && SentinelState.isTorStarted()) {
                model.fetchBalance()
            } else {
                SentinelState.torStateLiveData().observe(this, {
                    if (it == SentinelState.TorState.ON) {
                        GlobalScope.launch {
                            delay(250L)
                            withContext(Dispatchers.Main) {
                                model.fetchBalance()
                            }
                        }
                    }
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (SentinelState.isTestNet() && !title.contains("TestNet")) {
            title = "$title | TestNet"
        }
        if (balance != -1L)
            updateBalance(balance)

        binding.exchangeRateTxt.visibility = if (prefsUtil.fiatDisabled!!) View.INVISIBLE else View.VISIBLE
    }

    private fun setUp() {
        if (prefsUtil.firstRun!! && AppUtil.getInstance(applicationContext).isSideLoaded) {
            this.confirm(label = "Choose network",
                    positiveText = "Mainnet",
                    negativeText = "Testnet",
                    onConfirm = { confirm ->
                        prefsUtil.firstRun = false
                        if (!confirm) {
                            prefsUtil.testnet = true
                        }
                        if (!SentinelState.isTestNet()) {
                            title = "$title".removeSuffix("| TestNet")
                        }
                        showServerConfig()
                    })
        } else {
            showServerConfig()
        }
    }

    private fun showServerConfig() {
        if (prefsUtil.apiEndPoint.isNullOrEmpty()) {
            this.confirm(label = "Choose server",
                    positiveText = "Connect to Dojo",
                    negativeText = "Connect to Samourai’s server",
                    isCancelable = false,
                    onConfirm = { confirm ->
                        if (confirm) {
                            connectingDojo = true
                            if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext)) {
                                this.askCameraPermission()
                            } else {
                                showDojoSetUpBottomSheet()
                            }
                        } else {
                            this.confirm(label = "Connect through Tor?",
                                message = "",
                                positiveText = "Yes",
                                negativeText = "No",
                                onConfirm = { confirmed ->
                                    if (confirmed) {
                                        TorServiceController.startTor()
                                        prefsUtil.enableTor = true
                                    }
                                }
                            )
                            if (prefsUtil.testnet!!) {
                                prefsUtil.apiEndPoint = APIConfig.SAMOURAI_API_TESTNET
                                prefsUtil.apiEndPointTor = APIConfig.SAMOURAI_API_TOR_TESTNET
                            } else {
                                prefsUtil.apiEndPoint = APIConfig.SAMOURAI_API
                                prefsUtil.apiEndPointTor = APIConfig.SAMOURAI_API_TOR
                            }
                        }
                    })

        }
    }

    private fun checkClipBoard() {
        val clipData = AndroidUtil.getClipBoardString(applicationContext)

        if (clipData != null) {
            val formatted = FormatsUtil.extractPublicKey(clipData)
            if (FormatsUtil.isValidBitcoinAddress(formatted) || FormatsUtil.isValidXpub(formatted)) {
                showFloatingSnackBar(binding.fab, text = "Public Key detected in clipboard", actionText = "Add", actionClick = {
                    val bottomSheetFragment = AddNewPubKeyBottomSheet(formatted)
                    bottomSheetFragment.show(supportFragmentManager, bottomSheetFragment.tag)
                })
            }

        }
    }

    private fun showDojoSetUpBottomSheet() {
        val dojoConfigureBottomSheet = DojoConfigureBottomSheet()
        dojoConfigureBottomSheet.show(supportFragmentManager, dojoConfigureBottomSheet.tag)
        dojoConfigureBottomSheet.setDojoConfigurationListener(object : DojoConfigureBottomSheet.DojoConfigurationListener {
            override fun onDismiss() {
                if (!prefsUtil.isAPIEndpointEnabled()) {
                    showServerConfig()
                }
            }
        })
    }

    private fun updateBalance(it: Long) {
        var blockedUtxosBalanceSum = 0L

        for (i in 0..collectionsAdapter.getCollectionList().size-1){
            collectionsAdapter.setBalance(i)
        }
        collectionsAdapter.getCollectionList().forEach{collection ->
            collection.pubs.forEach { pubKeyModel ->
                val blockedUtxos1 =
                    UtxoMetaUtil.getBlockedAssociatedWithPubKey(pubKeyModel.pubKey)
                blockedUtxos1.forEach { blockedUtxo ->
                    blockedUtxosBalanceSum += blockedUtxo.amount
                }
            }
        }
        val balance = it - blockedUtxosBalanceSum

        binding.homeBalanceBtc.text = "${MonetaryUtil.getInstance().getBTCDecimalFormat(balance)} BTC"
    }

    private fun updateFiat(it: String) {
        binding.exchangeRateTxt.text = it
    }

    private fun showPubKeyBottomSheet() {
        val bottomSheetFragment = AddNewPubKeyBottomSheet(secure = prefsUtil.displaySecure!!)
        bottomSheetFragment.show(supportFragmentManager, bottomSheetFragment.tag)
    }


    private fun setUpCollectionList() {

        collectionsAdapter.setOnClickListener {
            startActivity(Intent(applicationContext, CollectionDetailsActivity::class.java).apply {
                putExtra("collection", it.id)
            })
        }

        linearLayoutManager = LinearLayoutManager(this)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL
        val decorator = RecyclerViewItemDividerDecorator(ContextCompat.getDrawable(applicationContext, R.drawable.divider_home)!!)
        binding.collectionRecyclerView.apply {
            adapter = collectionsAdapter
            layoutManager = linearLayoutManager
            itemAnimator = SlideInItemAnimator(slideFromEdge = Gravity.TOP)
            setHasFixedSize(true)
            addItemDecoration(decorator)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!SentinelState.checkedClipBoard) {
            checkClipBoard()
            SentinelState.checkedClipBoard = true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Companion.CAMERA_PERMISSION && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (connectingDojo) {
                showDojoSetUpBottomSheet()
                return
            }
            showPubKeyBottomSheet()

        } else if (requestCode == Companion.CAMERA_PERMISSION && grantResults[0] == PackageManager.PERMISSION_DENIED) {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_LONG).show()
            if (connectingDojo) {
                showDojoSetUpBottomSheet()
                return
            }
            showPubKeyBottomSheet()
        } else if (requestCode == Companion.NOTIF_PERMISSION && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Notification permissions granted.", Toast.LENGTH_SHORT).show()
        } else if (requestCode == Companion.NOTIF_PERMISSION && grantResults[0] == PackageManager.PERMISSION_DENIED) {
            Toast.makeText(this, "Notification permissions denied.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.home_options_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            R.id.home_options_broadcast -> {
                startActivity(Intent(this, BroadcastTx::class.java))
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onBackPressed() {
        MaterialAlertDialogBuilder(this)
                .setTitle(resources.getString(R.string.confirm_exit))
                .setMessage(resources.getString(R.string.ask_you_sure_exit))
                .setNegativeButton(resources.getString(R.string.no)) { _, _ ->
                }
                .setPositiveButton(resources.getString(R.string.yes)) { _, _ ->
                    TorServiceController.stopTor()
                    super.onBackPressed()
                }
                .show()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu?.let { setNetWorkMenu(it) }
        return super.onPrepareOptionsMenu(menu)
    }

    private fun setNetWorkMenu(menu: Menu) {
        val alertMenuItem: MenuItem = menu.findItem(R.id.activity_home_menu_network)
        val rootView = alertMenuItem.actionView
        val statusCircle = rootView?.findViewById<View>(R.id.home_menu_network_shape) as FrameLayout
        val shape = ContextCompat.getDrawable(applicationContext, R.drawable.circle_shape)
        shape?.setTint(ContextCompat.getColor(applicationContext, R.color.red))
        statusCircle.background = shape
        statusCircle.visibility = View.VISIBLE
        SentinelState.torStateLiveData().observe(this, Observer {
            if (it == SentinelState.TorState.ON) {
                shape?.setTint(0)
            }
            if (it == SentinelState.TorState.OFF) {
                shape?.setTint(0)
            }
            if (it == SentinelState.TorState.WAITING) {
                shape?.setTint(ContextCompat.getColor(applicationContext, R.color.warning_yellow))
            }
            statusCircle.background = shape
            statusCircle.visibility = View.VISIBLE
        })

        rootView.setOnClickListener {
            startActivity(Intent(this, NetworkActivity::class.java))
        }
    }

    fun connectSocket() {
        try {
            webSocketHandler.connect()
        } catch (ex: Exception) {
        }
    }
}
