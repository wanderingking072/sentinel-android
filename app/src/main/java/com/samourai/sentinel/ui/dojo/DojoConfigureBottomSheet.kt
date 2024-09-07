package com.samourai.sentinel.ui.dojo

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.invertedx.hummingbird.QRScanner
import com.samourai.sentinel.R
import com.samourai.sentinel.databinding.FragmentBottomsheetViewPagerBinding
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.koin.java.KoinJavaComponent
import java.io.IOException

class DojoConfigureBottomSheet : GenericBottomSheet() {
    private var payloadPassed: String? = null;
    private val scanFragment = ScanFragment()
    private val dojoConfigureBottomSheet = DojoNodeInstructions()
    private val dojoConnectFragment = DojoConnectFragment()
    private val connectManuallyFragment = ConnectManuallyFragment()
    private var dojoConfigurationListener: DojoConfigurationListener? = null
    private val prefsUtil: PrefsUtil by KoinJavaComponent.inject(PrefsUtil::class.java);
    private var numRetries = 0

    private var payload: String = ""

    private val dojoUtil: DojoUtility by KoinJavaComponent.inject(DojoUtility::class.java);

    private var _binding: FragmentBottomsheetViewPagerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentBottomsheetViewPagerBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpViewPager()
        dojoConfigureBottomSheet.setConnectListener(View.OnClickListener {
            binding.pager.setCurrentItem(1, true)
        })
        connectManuallyFragment.setConnectListener(View.OnClickListener {
            if (connectManuallyFragment.dojoPayload == null )
                Toast.makeText(requireContext(), "Invalid payload", Toast.LENGTH_SHORT).show()
            else
                binding.pager.setCurrentItem(3, true)
        })
        scanFragment.setManualDetailsListener(View.OnClickListener {
            binding.pager.setCurrentItem(2, true)
        })
        scanFragment.setOnScanListener {
            if (dojoUtil.validate(it)) {
                payload = it
                binding.pager.setCurrentItem(3, true)
            } else {
                scanFragment.resetCamera()
                Toast.makeText(requireContext(), "Invalid payload", Toast.LENGTH_SHORT).show()
            }
        }
        //validate payload that is passed
        payloadPassed?.let {
            if (dojoUtil.validate(it)) {
                payload = it
                binding.pager.setCurrentItem(3, true)
            } else {
                scanFragment.resetCamera()
                Toast.makeText(requireContext(), "Invalid payload", Toast.LENGTH_SHORT).show()
            }
        }
        binding.pager.registerOnPageChangeCallback(pagerCallBack)
    }

    private val pagerCallBack = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            super.onPageScrolled(position, positionOffset, positionOffsetPixels)
            if (position == 3) {
                startTorAndConnect()
            }
        }
    }

    private fun startTorAndConnect() {
        if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
            dojoConnectFragment.showTorProgressSuccess()
            setDojo()
        } else {
            setUpTor()
            SentinelTorManager.start()
            prefsUtil.enableTor = true
            dojoConnectFragment.showTorProgress()
            SentinelTorManager.getTorStateLiveData().observe(this) {
                if (it.state == EnumTorState.ON) {
                    if (dojoConnectFragment.showTorProgressSuccess()) {
                        setDojo()
                    }
                }
            }
        }
    }

    private fun setDojo() {
        if (connectManuallyFragment.dojoPayload?.isNotEmpty() == true && connectManuallyFragment.dojoPayload != null)
            payload = connectManuallyFragment.dojoPayload!!
        dojoConnectFragment.showDojoProgress()
        try {
            val pairing = fromJSON<DojoPairing>(payload)
                ?: throw  Exception("Invalid payload")
            val client = OkHttpClient.Builder().proxy(SentinelTorManager.getProxy()).build()

            val requestBody = FormBody.Builder()
                .add("apikey", pairing.pairing?.apikey.toString())
                .build()

            val request = Request.Builder()
                .url(pairing.pairing!!.url!!.toString().plus("/auth/login"))
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: "{}"
                    if (response.code == 401 || (response.isSuccessful && !body.contains("authorizations"))) {
                        Log.d("DojoConfiguration", "Unauthorized: wrong API key")
                        dismissAllOrToast(false)
                    }
                    else if (response.isSuccessful) {
                        val json = JSONObject(body)
                        dojoUtil.setDojoPayload(payload)
                        prefsUtil.apiEndPointTor = pairing.pairing.url
                        prefsUtil.apiEndPoint = pairing.pairing.url
                        dojoUtil.setAuthToken(json.toString())
                        apiScope.launch {
                            dojoUtil.writePayload(pairing);
                            withContext(Dispatchers.Main) {
                                dismissAllOrToast(true)
                            }
                        }
                    }  else {
                        dismissAllOrToast(false)
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (e.message!!.contains("Unable to resolve host")) {
                        Thread.sleep(5000)
                        if (SentinelTorManager.getProxy() != null)
                            setDojo()
                        else
                            dismissAllOrToast(false, true)
                    }
                    else {
                        dismissAllOrToast(false, true)
                    }
                }
            })
        } catch (e: Exception) {
            Handler().postDelayed(Runnable {
                this@DojoConfigureBottomSheet.dojoConfigurationListener?.onDismiss()
                this@DojoConfigureBottomSheet.dismiss()
            }, 500)
            Toast.makeText(requireContext(), "Error: $e", Toast.LENGTH_LONG).show()
        }
    }

    fun dismissAllOrToast(wasResponseSuccessful: Boolean, retry: Boolean = false) {
        if (wasResponseSuccessful) {
            dojoConnectFragment.showDojoProgressSuccess()
            Handler().postDelayed(Runnable {
                this@DojoConfigureBottomSheet.dojoConfigurationListener?.onDismiss()
                this@DojoConfigureBottomSheet.dismiss()
            }, 500)
        }
        else if (retry && numRetries <= 2) {
            numRetries += 1
            Thread.sleep(5000)
            setDojo()
        }
        else {
            Handler(Looper.getMainLooper()).post {
                Handler().postDelayed(Runnable {
                    this@DojoConfigureBottomSheet.dojoConfigurationListener?.onDismiss()
                    this@DojoConfigureBottomSheet.dismiss()
                }, 500)
                if (context != null)
                    Toast.makeText(context, "Unable to connect to Dojo. Please try again", Toast.LENGTH_LONG).show()
                this.dismiss()
            }
        }
    }



    private fun setUpTor() {
        SentinelTorManager.setUp(context?.applicationContext as Application)
        if (prefsUtil.enableTor == true) {
            SentinelTorManager.start()
        }
    }

    fun setDojoConfigurationListener(dojoConfigurationListener: DojoConfigurationListener?) {
        this.dojoConfigurationListener = dojoConfigurationListener
    }

    private fun setUpViewPager() {
        val item = arrayListOf<Fragment>()
        item.add(dojoConfigureBottomSheet)
        item.add(scanFragment)
        item.add(connectManuallyFragment)
        item.add(dojoConnectFragment)
        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int {
                return item.size
            }

            override fun createFragment(position: Int): Fragment {
                return item[position];
            }

        }
        binding.pager.isUserInputEnabled = false

        //Fix for making BottomSheet same height across all the fragments
        binding.pager.visibility = View.GONE
        binding.pager.currentItem = 1
        binding.pager.currentItem = 0
        binding.pager.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        binding.pager.unregisterOnPageChangeCallback(pagerCallBack)
        super.onDestroyView()
    }



    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        this.dojoConfigurationListener?.onDismiss()
    }

    fun setPayload(dojoPayload: String?) {
        this.payloadPassed = dojoPayload
    }

    interface DojoConfigurationListener {
        fun onDismiss()
    }
}

class DojoNodeInstructions : Fragment() {

    private var connectOnClickListener: View.OnClickListener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.bottomsheet_dojo_configure_instruction, container, false);
    }

    fun setConnectListener(listener: View.OnClickListener) {
        connectOnClickListener = listener
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.connect_dojo).setOnClickListener { view ->
            connectOnClickListener?.onClick(view)
        }
    }


}


class DojoConnectFragment : Fragment() {


    private lateinit var checkImageTor: ImageView
    private lateinit var checkImageDojo: ImageView
    private lateinit var progressTor: ProgressBar
    private lateinit var progressDojo: ProgressBar

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.bottomsheet_dojo_connection, container, false)
        progressTor = view.findViewById(R.id.progressTor)
        checkImageTor = view.findViewById(R.id.checkImageTor)

        checkImageDojo = view.findViewById(R.id.checkImageDojo)
        progressDojo = view.findViewById(R.id.progressDojo)

        return view
    }


    fun showTorProgress() {
        progressTor.visibility = View.VISIBLE
        checkImageTor.visibility = View.INVISIBLE
        progressTor.animate()
                .alpha(1f)
                .setDuration(600)
                .start()
    }

    @Synchronized
    fun showTorProgressSuccess() : Boolean {
        if (checkImageTor.visibility != View.VISIBLE) {
            progressTor.visibility = View.INVISIBLE
            checkImageTor.visibility = View.VISIBLE
            return true
        }
        return false
    }

    fun showDojoProgress() {
        progressDojo.visibility = View.VISIBLE
        checkImageDojo.visibility = View.INVISIBLE
    }

    fun showDojoProgressSuccess() {
        progressDojo.visibility = View.INVISIBLE
        checkImageDojo.visibility = View.VISIBLE
    }
}

class ConnectManuallyFragment : Fragment() {
    private var connectOnClickListener: View.OnClickListener? = null
    private var onionText : TextInputEditText? = null
    private var apiText : TextInputEditText? = null
    private var connectButton: MaterialButton? = null

    var dojoPayload : String? = null


    fun setConnectListener(listener: View.OnClickListener) {
        connectOnClickListener = listener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.activity_set_dojo_manually, container, false)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        onionText = view.findViewById(R.id.setUpWalletAddressInput)
        apiText = view.findViewById(R.id.setUpWalletApiKeyInput)
        connectButton = view.findViewById(R.id.setUpWalletConnectDojo)

        connectButton?.setOnClickListener {
            if (onionText?.text?.isBlank() == true || apiText?.text?.isBlank() == true)
                dojoPayload = null
            else
                dojoPayload = "{\n" +
                        "\"pairing\": {\n" +
                        "\"type\": \"dojo.api\",\n" +
                        "\"version\": \"1.19.0\",\n" +
                        "\"apikey\": \"${apiText?.text}\",\n" +
                        "\"url\": \"${onionText?.text}\"\n" +
                        "}\n" +
                        "}"
            connectOnClickListener?.onClick(view)
        }
    }
}


class ScanFragment : Fragment() {

    private var mCodeScanner: QRScanner? = null
    private val appContext: Context by KoinJavaComponent.inject(Context::class.java);
    private var onScan: (scanData: String) -> Unit = {}
    private var manualDetailsListener: View.OnClickListener? = null


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.content_scan_layout, container, false);
    }

    fun setManualDetailsListener(listener: View.OnClickListener) {
        manualDetailsListener = listener
    }

    fun setOnScanListener(callback: (scanData: String) -> Unit) {
        this.onScan = callback
    }

    fun resetCamera() {
        this.mCodeScanner?.stopScanner()
        this.mCodeScanner?.startScanner()
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mCodeScanner = view.findViewById(R.id.scannerViewXpub);
        mCodeScanner?.setLifeCycleOwner(this)
        view.findViewById<TextView>(R.id.scanInstructions).text = getString(R.string.dojo_scan_instruction)
        view.findViewById<TextView>(R.id.scanInstructions).textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        mCodeScanner?.setQRDecodeListener {
            GlobalScope.launch(Dispatchers.Main) {
                mCodeScanner?.stopScanner()
                onScan(it)
            }
        }

        view.findViewById<Button>(R.id.pastePubKey).setOnClickListener {
            manualDetailsListener?.onClick(view)
        }

    }


    override fun onResume() {
        super.onResume()
        if (AndroidUtil.isPermissionGranted(Manifest.permission.CAMERA, appContext)) {
            mCodeScanner?.startScanner()
        }
    }

}
