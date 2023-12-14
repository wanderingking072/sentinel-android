package com.samourai.sentinel.ui.tools

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.samourai.sentinel.R
import com.samourai.sentinel.ui.broadcast.BroadcastTx
import com.samourai.sentinel.ui.tools.sweep.SweepPrivKeyFragment

class ToolsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.tools_preferences, rootKey)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        findPreference<Preference>("sweep")
            ?.setOnPreferenceClickListener {
                val bottomSheetFragment = SweepPrivKeyFragment()
                bottomSheetFragment.show(parentFragmentManager, bottomSheetFragment.tag)
                true
            }
        findPreference<Preference>("broadcastTx")
            ?.setOnPreferenceClickListener {
                startActivity(Intent(requireActivity(), BroadcastTx::class.java))
                true
            }
    }

}