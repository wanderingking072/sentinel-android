package com.samourai.sentinel.ui.dojo

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.samourai.sentinel.R
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.ui.views.GenericBottomSheet
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

/**
 * Lists the community Dojos published by Dojo Bay (fetched live, over Tor) and
 * lets the user either pair directly to one or copy its pairing JSON.
 *
 * Only ever shown after the caller has put the user through
 * [DojoConfigureBottomSheet]'s privacy warning dialog - this sheet itself assumes
 * that consent has already been given.
 */
class CommunityDojoListBottomSheet(
    private val onDojoSelected: (String) -> Unit
) : GenericBottomSheet() {

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var retryButton: MaterialButton

    private var fetchInFlight = false

    private val adapter = CommunityDojoAdapter(
        onSelect = { node ->
            val json = node.pairingPayloadJson()
            if (json == null) {
                Toast.makeText(requireContext(), "Invalid pairing details for this Dojo", Toast.LENGTH_SHORT).show()
            } else {
                onDojoSelected(json)
                dismiss()
            }
        },
        onCopy = { node -> copyPairingJson(node) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottomsheet_community_dojo_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.communityDojoToolbar)
        recyclerView = view.findViewById(R.id.communityDojoRecyclerView)
        progressBar = view.findViewById(R.id.communityDojoProgress)
        statusText = view.findViewById(R.id.communityDojoStatusText)
        retryButton = view.findViewById(R.id.communityDojoRetryButton)

        toolbar.setNavigationOnClickListener { dismiss() }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        retryButton.setOnClickListener { connectTorThenFetch() }

        connectTorThenFetch()
    }

    private fun connectTorThenFetch() {
        showLoading(getString(R.string.community_dojo_connecting_tor))
        if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
            fetchDirectory()
            return
        }
        SentinelTorManager.setUp(requireContext().applicationContext as Application)
        SentinelTorManager.start()
        prefsUtil.enableTor = true
        SentinelTorManager.getTorStateLiveData().observe(viewLifecycleOwner) { state ->
            if (state.state == EnumTorState.ON) {
                fetchDirectory()
            }
        }
    }

    private fun fetchDirectory() {
        if (fetchInFlight) return
        fetchInFlight = true
        showLoading(getString(R.string.community_dojo_fetching))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nodes = CommunityDojoRepository.fetchDirectory()
                val network = if (prefsUtil.testnet == true) "testnet" else "mainnet"
                val filtered = nodes
                    .filter { it.network.equals(network, ignoreCase = true) }
                    .sortedWith(
                        compareByDescending<CommunityDojoNode> { it.isOnline }
                            .thenBy { it.name.orEmpty().lowercase() }
                    )
                fetchInFlight = false
                if (filtered.isEmpty()) {
                    showMessage(getString(R.string.community_dojo_empty, network), canRetry = true)
                } else {
                    showList(filtered)
                }
            } catch (e: Exception) {
                fetchInFlight = false
                showMessage(
                    getString(R.string.community_dojo_error, e.message ?: e.toString()),
                    canRetry = true
                )
            }
        }
    }

    private fun showLoading(message: String) {
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = message
        retryButton.visibility = View.GONE
        recyclerView.visibility = View.GONE
    }

    private fun showMessage(message: String, canRetry: Boolean) {
        progressBar.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = message
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        recyclerView.visibility = View.GONE
    }

    private fun showList(nodes: List<CommunityDojoNode>) {
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE
        retryButton.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        adapter.submitList(nodes)
    }

    private fun copyPairingJson(node: CommunityDojoNode) {
        val json = node.pairingPayloadJson()
        if (json == null) {
            Toast.makeText(requireContext(), "No pairing JSON available for this Dojo", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Dojo pairing payload", json))
        Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
}
