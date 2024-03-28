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
import com.google.android.material.shape.ShapeAppearanceModel
import com.samourai.sentinel.R
import com.samourai.sentinel.api.APIConfig
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.databinding.ActivityHomeBinding
import com.samourai.sentinel.service.WebSocketHandler
import com.samourai.sentinel.service.WebSocketService
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.adapters.CollectionsAdapter
import com.samourai.sentinel.ui.collectionDetails.CollectionDetailsActivity
import com.samourai.sentinel.ui.dojo.DojoConfigureBottomSheet
import com.samourai.sentinel.ui.fragments.AddNewPubKeyBottomSheet
import com.samourai.sentinel.ui.settings.NetworkActivity
import com.samourai.sentinel.ui.settings.SettingsActivity
import com.samourai.sentinel.ui.tools.ToolsActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.RecyclerViewItemDividerDecorator
import com.samourai.sentinel.ui.utils.SlideInItemAnimator
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.confirm
import com.samourai.sentinel.util.AppUtil
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.TimeOutUtil
import com.samourai.sentinel.util.UtxoMetaUtil
import com.samourai.sentinel.widgets.popUpMenu.popupMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject


class HomeActivity : SentinelActivity() {

    private lateinit var linearLayoutManager: LinearLayoutManager
    private val collectionsAdapter = CollectionsAdapter()
    private val webSocketHandler: WebSocketHandler by inject(WebSocketHandler::class.java)
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private var connectingDojo = false
    private lateinit var binding: ActivityHomeBinding
    private val model: HomeViewModel by viewModels()
    private var balance = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        val view = binding.root
        window.statusBarColor = ContextCompat.getColor(this, R.color.mpm_black)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.grey_homeActivity)
        setContentView(view)
        setSwipeBackEnable(false)
        setSupportActionBar(binding.toolbarHome)

        title = ""

        binding.toolbarIcon.setOnClickListener {
            showToolOptions(it)
        }

        val model: HomeViewModel by viewModels()
        UtxoMetaUtil.read()

        if (SentinelState.isTorRequired() && SentinelTorManager.getTorState().state == EnumTorState.OFF) {
            SentinelTorManager.start()
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

        binding.fab.setBackgroundResource(R.drawable.background_gradient);

        binding.fab.setOnClickListener {
            connectingDojo = false
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

        model.loading().observe(this) {
            binding.swipeRefreshCollection.isRefreshing = it.contains(true) || it.isNotEmpty()
        }
        model.getErrorMessage().observe(this) {
            if (it != "null" &&  SentinelTorManager.getTorState().state != EnumTorState.STARTING) {
                if (!it.lowercase().startsWith("unable to resolve host")
                    && !it.lowercase().contains("standalonecoroutine was cancelled")) {
                    this@HomeActivity.showFloatingSnackBar(
                        binding.fab,
                        text = "No data connection available. Please enable data"
                    )
                }
            }
        }

        if (intent != null) {
            if (intent.hasExtra("forceRefresh") && intent.getBooleanExtra("forceRefresh", true)) {
                model.fetchBalance()
            }
        }

        binding.swipeRefreshCollection.setOnRefreshListener {
            binding.swipeRefreshCollection.isRefreshing = false
            if (SentinelState.isTorRequired()) {
                if (SentinelTorManager.getTorState().state == EnumTorState.STARTING) {
                    this.showFloatingSnackBar(binding.fab, anchorView = binding.fab.id,
                            text = "Tor is bootstrapping! please wait and try again")
                }
                if (SentinelTorManager.getTorState().state == EnumTorState.OFF) {
                    this.showFloatingSnackBar(binding.fab,
                            text="Please wait while Tor is turning on")
                    SentinelTorManager.start()
                    prefsUtil.enableTor = true
                }
                if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
                    model.fetchBalance()
                }
            } else {
                model.fetchBalance()
            }
        }

        fetch(model)

        if (SentinelState.isTorRequired()) {
            SentinelTorManager.getTorStateLiveData().observe(this, {
                if (it.state == EnumTorState.ON)
                    WebSocketService.start(applicationContext)
            })
        } else {
            WebSocketService.start(applicationContext)
        }

        checkClipBoard()
    }

    private fun showToolOptions(it: View) {
        val toolWindowSize = applicationContext.resources.displayMetrics.density * 220;
        val popupMenu = popupMenu {
            fixedContentWidthInPx = toolWindowSize.toInt()
            style = R.style.Theme_Samourai_Widget_MPM_Menu_Dark
            section {
                item {
                    label = "Sentinel"
                    iconDrawable = ContextCompat.getDrawable(applicationContext, R.drawable.icon_innergradient)
                    iconSize = 34
                    labelColor = ContextCompat.getColor(applicationContext, R.color.white)
                    disableTint = true
                    iconShapeAppearanceModel = ShapeAppearanceModel().toBuilder()
                        .setAllCornerSizes(resources.getDimension(R.dimen.qr_image_corner_radius))
                        .build()
                    isTitle = true
                }
                item {
                    label = "\tTools"
                    icon = R.drawable.ic_tools
                    iconSize = 18
                    hasNestedItems
                    callback = {
                        val intent = Intent(this@HomeActivity, ToolsActivity::class.java)
                        startActivity(intent)
                    }
                }

            }
            section {
                item {
                    label = "\tSettings"
                    icon = R.drawable.ic_cog
                    iconSize = 18
                    callback = {
                        TimeOutUtil.getInstance().updatePin()
                        val intent = Intent(this@HomeActivity, SettingsActivity::class.java)
                        startActivity(intent)
                    }
                }
                item {
                    label = "\tExit"
                    iconSize = 18
                    iconColor = ContextCompat.getColor(this@HomeActivity, R.color.mpm_red)
                    labelColor = ContextCompat.getColor(this@HomeActivity, R.color.mpm_red)
                    icon = R.drawable.ic_baseline_power_settings_new_24
                    callback = {
                        this@HomeActivity.onBackPressed()
                    }
                }
            }
        }
        popupMenu.show(this@HomeActivity, it)
    }
    private fun fetch(model: HomeViewModel) {
        if (!SentinelState.isRecentlySynced()) {
            if (SentinelState.isTorRequired() && SentinelTorManager.getTorState().state == EnumTorState.ON) {
                model.fetchBalance()
            } else {
                SentinelTorManager.getTorStateLiveData().observe(this, {
                    if (it.state == EnumTorState.ON) {
                        GlobalScope.launch {
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
                                        SentinelTorManager.start()
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
        binding.homeBalanceBtc.text = "${MonetaryUtil.getInstance().getBTCDecimalFormat(it)} BTC"
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
        val decorator = RecyclerViewItemDividerDecorator(ContextCompat.getDrawable(applicationContext, R.drawable.divider_tx)!!)
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
                    SentinelTorManager.stop()
                    super.onBackPressed()
                }
                .show()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.let { setNetWorkMenu(it) }
        return super.onPrepareOptionsMenu(menu)
    }

    private fun setNetWorkMenu(menu: Menu) {
        val alertMenuItem: MenuItem = menu.findItem(R.id.activity_home_menu_network)
        val rootView = alertMenuItem.actionView
        val statusCircle = rootView?.findViewById<View>(R.id.home_menu_network_shape) as FrameLayout
        val shape = ContextCompat.getDrawable(applicationContext, R.drawable.circle_shape)
        shape?.setTint(0)
        statusCircle.background = shape
        statusCircle.visibility = View.VISIBLE
        SentinelTorManager.getTorStateLiveData().observe(this, Observer {
            if (it.state == EnumTorState.ON) {
                shape?.setTint(0)
            }
            if (it.state == EnumTorState.OFF) {
                shape?.setTint(0)
            }
            if (it.state == EnumTorState.STARTING) {
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
