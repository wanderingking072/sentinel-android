package com.samourai.sentinel.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.samourai.sentinel.R
import com.samourai.sentinel.api.APIConfig
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.dojo.DojoConfigureBottomSheet
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.ui.views.confirm
import io.matthewnelson.topl_service.TorServiceController
import org.koin.java.KoinJavaComponent.inject

class NetworkActivity : SentinelActivity() {
    
    var torRenewBtn: TextView? = null
    var torConnectionStatus: TextView? = null
    var dojoConnectionStatus: TextView? = null
    var torButton: Button? = null
    var dojoButton: Button? = null
    var torConnectionIcon: ImageView? = null
    var dojoConnectionIcon: ImageView? = null
    var activeColor = 0
    var disabledColor = 0
    var waiting = 0
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);
    private val dojoUtility: DojoUtility by inject(DojoUtility::class.java);

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network)
        setSupportActionBar(findViewById(R.id.toolbarCollectionDetails))
        if (supportActionBar != null) {
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        }
        activeColor = ContextCompat.getColor(this, R.color.green_ui_2)
        disabledColor = ContextCompat.getColor(this, R.color.disabledRed)
        waiting = ContextCompat.getColor(this, R.color.warning_yellow)
        torButton = findViewById(R.id.networking_tor_btn)
        torRenewBtn = findViewById(R.id.networking_tor_renew)
        dojoButton = findViewById(R.id.networking_dojo_btn)
        torConnectionIcon = findViewById(R.id.network_tor_status_icon)
        torConnectionStatus = findViewById(R.id.network_tor_status)
        dojoConnectionIcon = findViewById(R.id.network_dojo_status_icon)
        dojoConnectionStatus = findViewById(R.id.network_dojo_status)
        torRenewBtn?.setOnClickListener {
            TorServiceController.newIdentity()
            this.showFloatingSnackBar(findViewById(R.id.toolbarCollectionDetails), text = "Tor identity has been renewed")
        }
        SentinelState.torStateLiveData().observe(this, {
            setTorConnectionState(it)
        })
        dojoButton?.setOnClickListener {
            if (dojoUtility.isDojoEnabled()) {
                confirm(label = "Remove dojo ? ", positiveText = "Remove", negativeText = "Cancel") {
                    if (it)
                        removeDojo()
                }
            } else {
                if (!AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, applicationContext))
                    this.askCameraPermission()
                else
                    showDojoSetUpBottomSheet()
            }
        }
        torButton!!.setOnClickListener {
            if (SentinelState.isTorStarted()) {
                if (!dojoUtility.isDojoEnabled()) {
                    TorServiceController.stopTor()
                    prefsUtil.enableTor = false
                }
                else
                    this.showFloatingSnackBar(torButton!!.rootView, text = "You wont be able to disable tor if dojo is enabled")
            } else {
                TorServiceController.startTor()
                prefsUtil.enableTor = true
            }

        }
        setDojoStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == Companion.CAMERA_PERMISSION && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showDojoSetUpBottomSheet()
        } else {
            if (requestCode == Companion.CAMERA_PERMISSION && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_LONG).show()
                showDojoSetUpBottomSheet()
            }
        }
    }

    private fun removeDojo() {
        dojoUtility.clearDojo()
        setDojoStatus()
        if (prefsUtil.apiEndPoint.isNullOrEmpty()) {
            this.confirm(label = "Choose server",
                    positiveText = "Connect to Dojo",
                    negativeText = "Connect to Samourai’s server",
                    isCancelable = false,
                    onConfirm = { confirm ->
                        if (confirm) {
                            showDojoSetUpBottomSheet()
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

    fun setDojoStatus() {
        if (dojoUtility.isDojoEnabled()) {
            dojoConnectionStatus?.text = getString(R.string.Enabled)
            dojoButton?.text = getString(R.string.disable)
            dojoConnectionIcon!!.setColorFilter(activeColor)
        } else {
            dojoConnectionStatus?.text = getString(R.string.disabled)
            dojoButton?.text = getString(R.string.enable)
            dojoConnectionIcon!!.setColorFilter(disabledColor)
        }
    }

    private fun setTorConnectionState(torState: SentinelState.TorState) {
        runOnUiThread {
            when (torState) {

                SentinelState.TorState.ON -> {
                    torButton!!.text = getString(R.string.disable)
                    torButton!!.isEnabled = true
                    torConnectionIcon!!.setColorFilter(activeColor)
                    torConnectionStatus!!.text = getString(R.string.Enabled)
                    torRenewBtn!!.visibility = View.VISIBLE
                }
                SentinelState.TorState.WAITING -> {
                    torRenewBtn!!.visibility = View.INVISIBLE
                    torButton!!.text = getString(R.string.loading)
                    torButton!!.isEnabled = false
                    torConnectionIcon!!.setColorFilter(waiting)
                    torConnectionStatus!!.text = getString(R.string.tor_initializing)
                }
                else -> {
                    torRenewBtn!!.visibility = View.INVISIBLE
                    torButton!!.text = getString(R.string.enable)
                    torButton!!.isEnabled = true
                    torConnectionIcon!!.setColorFilter(disabledColor)
                    torConnectionStatus!!.text = getString(R.string.disabled)
                }
            }
        }
    }

    private fun showDojoSetUpBottomSheet() {
        val dojoConfigureBottomSheet = DojoConfigureBottomSheet()
        dojoConfigureBottomSheet.show(supportFragmentManager, dojoConfigureBottomSheet.tag)
        dojoConfigureBottomSheet.setDojoConfigurationListener(object : DojoConfigureBottomSheet.DojoConfigurationListener {
            override fun onDismiss() {
                //Update dojo status
                setDojoStatus()
                if (!prefsUtil.isAPIEndpointEnabled()) {
                    removeDojo()
                }
            }
        })
    }


    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
