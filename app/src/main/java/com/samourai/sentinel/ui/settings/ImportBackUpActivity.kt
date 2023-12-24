package com.samourai.sentinel.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialSharedAxis
import com.samourai.sentinel.R
import com.samourai.sentinel.api.APIConfig
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.databinding.ActivityImportBackUpBinding
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.home.HomeActivity
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.utils.showFloatingSnackBar
import com.samourai.sentinel.util.ExportImportUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader

class ImportBackUpActivity : SentinelActivity() {

    enum class ImportType {
        SAMOURAI,
        SENTINEL,
        SENTINEL_LEGACY
    }

    class ImportBackUpViewModel : ViewModel()

    private var payloadObject: JSONObject? = null
    private var importType = ImportType.SENTINEL
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private var requireRestart = false
    private val viewModel: ImportBackUpViewModel by viewModels()
    private lateinit var binding: ActivityImportBackUpBinding
    private val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private val apiService: ApiService by inject(ApiService::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBackUpBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setSupportActionBar(binding.toolbarImportActivity)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)


        binding.importChoosePayloadBtn.setOnClickListener {
            var intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            intent = Intent.createChooser(intent, "Choose a file")
            startActivityForResult(intent, REQUEST_FILE_CODE)
            binding.importPayloadTextView.text = ""
        }

        binding.importPastePayloadBtn.setOnClickListener {
            if (AndroidUtil.getClipBoardString(applicationContext) != null) {
                AndroidUtil.getClipBoardString(applicationContext)?.let {
                    binding.importPayloadTextView.text = ""
                    validatePayload(it)
                }
            }
        }

        binding.importStartBtn.isEnabled = false

        binding.importStartBtn.setOnClickListener {
            if (binding.importPasswordInput.text?.length == 0) {
                binding.importPasswordInput.error = "Please type payload password"
            } else {
                decryptPayload()

            }
        }
        showImportButton(true)
    }

    private fun importAllXpubs() {
        apiScope.launch {
            repository.pubKeyCollections.forEach {
                it.pubs.forEach {
                    apiService.importXpub(it.pubKey, "bip${it.getPurpose()}")
                }
            }
        }
    }

    private fun showImportButton(hide: Boolean) {
        val sharedAxis = MaterialSharedAxis(MaterialSharedAxis.Y, !hide)
        TransitionManager.beginDelayedTransition(binding.importStartBtn.rootView as ViewGroup, sharedAxis)
        binding.importStartBtn.isEnabled = !hide
        binding.importStartBtn.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun decryptPayload() {
        when (importType) {
            ImportType.SENTINEL -> {
                viewModel.viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val payload = ExportImportUtil().decryptSentinel(
                                payloadObject.toString(),
                                binding.importPasswordInput.text.toString()
                        )
                        withContext(Dispatchers.Main) {
                            binding.importPasswordInputLayout.visibility = View.INVISIBLE
                            binding.importSentinelBackUpLayout.visibility = View.VISIBLE
                            binding.importCollections.text =
                                    "${binding.importCollections.text} (${payload.first?.size})"
                        }
                        if (binding.importCollections.isChecked) {
                            payload.first?.let {
                                ExportImportUtil().startImportCollections(
                                        it,
                                        binding.importClearExisting.isChecked
                                )
                            }
                        }
                        if (binding.importPrefs.isChecked) {
                            payload.second.let { ExportImportUtil().importPrefs(it) }
                        }
                        if (binding.importDojo.isChecked) {
                            if ((payload.second.getString("apiEndPointTor").equals(APIConfig.SAMOURAI_API_TOR)
                                    && payload.second.getString("apiEndPoint").equals(APIConfig.SAMOURAI_API))
                                ||
                                (payload.second.getString("apiEndPointTor").equals(APIConfig.SAMOURAI_API_TOR_TESTNET)
                                        && payload.second.getString("apiEndPoint").equals(APIConfig.SAMOURAI_API_TESTNET))) {

                            }
                            else {
                                SentinelTorManager.start()
                                prefsUtil.enableTor = true
                                payload.third?.let { ExportImportUtil().importDojo(it) }
                                prefsUtil.apiEndPointTor = payload.second.getString("apiEndPointTor")
                                prefsUtil.apiEndPoint = payload.second.getString("apiEndPoint")
                            }
                        }
                        else {
                            importAllXpubs()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        throw CancellationException(e.message)
                    }
                }.invokeOnCompletion {
                    if (it == null || it.toString().contains("Unable to resolve host")) {
                        requireRestart = true
                        showFloatingSnackBar(
                                binding.importPastePayloadBtn, "Successfully imported",
                                anchorView = binding.importStartBtn.id,
                                actionText = "restart"
                        )
                    } else {
                        Timber.e(it)
                        showFloatingSnackBar(
                                binding.importPastePayloadBtn,
                                "Unable to decrypt. Wrong password",
                                anchorView = binding.importStartBtn.id
                        )
                    }
                }
            }
            ImportType.SENTINEL_LEGACY -> {
                viewModel.viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val payload = ExportImportUtil().decryptSentinelLegacy(
                                payloadObject.toString(),
                                binding.importPasswordInput.text.toString()
                        )
                        val pubKeys = payload.first
                        if (pubKeys.isNotEmpty()) {
                            val collection = PubKeyCollection()
                            collection.pubs = pubKeys
                            collection.collectionLabel = "Sentinel Import"
                            ExportImportUtil().startImportCollections(
                                    arrayListOf(collection),
                                    false
                            )
                        } else {
                            throw  CancellationException("0 public keys found")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        throw CancellationException(e.message)
                    }
                }
                        .invokeOnCompletion {
                            if (it == null) {
                                requireRestart = true
                                showFloatingSnackBar(
                                        binding.importPastePayloadBtn, "Successfully imported",
                                        anchorView = binding.importStartBtn.id,
                                        actionText = "restart"
                                )
                            } else {
                                showFloatingSnackBar(
                                        binding.importPastePayloadBtn,
                                        "Error: ${it.message}",
                                        anchorView = binding.importStartBtn.id
                                )
                            }
                        }
            }
            else -> {
                showFloatingSnackBar(binding.importPastePayloadBtn, "Please choose a valid Sentinel backup file")
                //IMPORT FROM SAMOURAI BACKUP FILES
                val payload = ExportImportUtil().decryptAndParseSamouraiPayload(
                        payloadObject.toString(),
                        binding.importPasswordInput.text.toString()
                )
                viewModel.viewModelScope.launch(Dispatchers.IO) {
                    ExportImportUtil().startImportCollections(arrayListOf(payload), false)
                }.invokeOnCompletion {
                    if (it == null) {
                        requireRestart = true
                        showFloatingSnackBar(
                                binding.importPastePayloadBtn,
                                "Successfully imported",
                                actionClick = { restart() },
                                actionText = "restart"
                        )
                    } else {
                        showFloatingSnackBar(binding.importPastePayloadBtn, "Error: ${it.message}")
                    }
                }
            }
        }
    }

    private fun restart() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        overridePendingTransition(R.anim.fade_in, R.anim.bottom_sheet_slide_out)
        finish()
    }

    /**
     * Validates backup payloads
     * Method uses coroutines to parse json
     */
    private fun validatePayload(string: String) {
        viewModel.viewModelScope.launch(Dispatchers.Default) {
            try {
                val json = JSONObject(string)
                withContext(Dispatchers.Main) {
                    binding.importPayloadTextView.text = "${binding.importPayloadTextView.text}${json.toString(2)}"
                    if (json.has("external") && json.has("payload")) {
                        payloadObject = json
                        importType = ImportType.SAMOURAI
                        showImportButton(false)
                    } else if (json.has("time") && json.has("payload")) {
                        payloadObject = json
                        importType = ImportType.SENTINEL
                        showImportButton(false)
                        binding.importSentinelBackUpLayout.visibility = View.VISIBLE
                    } else if (json.has("payload")) {
                        payloadObject = json
                        importType = ImportType.SENTINEL_LEGACY
                        showImportButton(false)
                    } else {
                        showImportButton(false)
                        showFloatingSnackBar(binding.importStartBtn, text = "Invalid payload")
                    }
                }
            } catch (e: Exception) {
                throw  CancellationException((e.message))
            }
        }.invokeOnCompletion {
            if (it != null) {
                Timber.e(it)
            }
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (data != null && data.data != null && data.data!!.path != null && requestCode == REQUEST_FILE_CODE) {
            val job =
                    viewModel.viewModelScope.launch(Dispatchers.Main) {
                        try {
                            val inputStream = contentResolver.openInputStream(data.data!!)
                            val reader = BufferedReader(InputStreamReader(inputStream))
                            val size = inputStream?.available()
                            if (size != null) {
                                if (size > 5e+6) {
                                    throw  IOException("File size is too large to open")
                                }
                            }
                            var string = ""
                            string = reader.buffered().readText()
                            withContext(Dispatchers.Main) {
                                validatePayload(string)
                            }
                        } catch (fn: FileNotFoundException) {
                            fn.printStackTrace()
                            throw CancellationException((fn.message))
                        } catch (ioe: IOException) {
                            ioe.printStackTrace()
                            throw CancellationException((ioe.message))
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                            throw CancellationException((ex.message))
                        }
                    }
            job.invokeOnCompletion {
                if (it != null) {
                    this.showFloatingSnackBar(binding.importPastePayloadBtn, "Error ${it.message}")
                }
            }
        }
    }

    override fun onBackPressed() {
        if (requireRestart) {
            startActivity(Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            overridePendingTransition(R.anim.fade_in, R.anim.bottom_sheet_slide_out)
            finish()
        } else
            super.onBackPressed()
    }

    companion object {
        const val REQUEST_FILE_CODE = 44
    }

}