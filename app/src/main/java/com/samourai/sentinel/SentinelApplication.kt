package com.samourai.sentinel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.SentinelRoomDb
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import com.samourai.sentinel.service.WebSocketHandler
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.webview.ExplorerRepository
import com.samourai.sentinel.util.MonetaryUtil
import com.samourai.sentinel.util.apiScope
import com.samourai.sentinel.util.dataBaseScope
import kotlinx.coroutines.*
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy


class SentinelApplication : Application() {

    private val prefsUtil: PrefsUtil by KoinJavaComponent.inject(PrefsUtil::class.java);

    override fun onCreate() {
        super.onCreate()

        setUpChannels()
        initializeDI()
        setUpTor()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);


        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        } else {
            val cacheDir = File(this.applicationContext.cacheDir.toURI())
            if (!cacheDir.exists()) {
                cacheDir.mkdir()
            }
            Timber.plant(CrashReportingTree(cacheDir))
        }
    }

    /**
     * Koin
     */
    private fun initializeDI() {

        val appModule = module {
            single { PrefsUtil(applicationContext) }
            single { DojoUtility() }
            single { ApiService() }
            single { AccessFactory.getInstance(null) }
            single { SentinelCollectionStore() }
            single { MonetaryUtil.getInstance() }
            single { CollectionRepository() }
            single { ExchangeRateRepository() }
            single { ExplorerRepository() }
            single { FeeRepository() }
            single { TransactionsRepository() }
            single { WebSocketHandler() }
            factory { SentinelRoomDb.getDatabase(applicationContext).txDao() }
            factory { SentinelRoomDb.getDatabase(applicationContext).utxoDao() }
        }

        startKoin {
            androidContext(this@SentinelApplication)
            modules(appModule)
        }
    }


    private fun setUpTor() {
        SentinelTorManager.setUp(this)
        if (prefsUtil.enableTor == true) {
            SentinelTorManager.start()
        }
    }


    private fun setUpChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val descriptionText = getString(R.string.import_channel_description)
            val name = getString(R.string.import_channel_name)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val mChannel = NotificationChannel("IMPORT_CHANNEL", name, importance)
            mChannel.description = descriptionText
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(mChannel)

            val channel = NotificationChannel("PAYMENTS_CHANNEL", "Payments", importance)
            channel.description = "Alerts for new payments"
            channel.enableLights(true)
            channel.importance = NotificationManager.IMPORTANCE_HIGH
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onTerminate() {
        dataBaseScope.cancel()
        apiScope.cancel()
        super.onTerminate()
    }

    private class CrashReportingTree(val dir: File) : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (t == null) {
                return
            }
            if (priority == Log.VERBOSE || priority == Log.DEBUG) {
                return
            }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val file = File("${dir}/error_dump.log")
                    if (!file.exists()) {
                        file.createNewFile()
                    }
                    //Limit file size to 2 mb
                    if ((file.length() / 1024) > 2048) {
                        file.writeText("")
                    }
                    file.appendText(
                            "\n-Logged at: ${System.currentTimeMillis()}-\n" +
                                    "" +
                                    " ${t.stackTraceToString()}"
                    )
                } catch (ex: Exception) {
                    throw CancellationException(ex.message)
                }
            }
        }
    }
}
