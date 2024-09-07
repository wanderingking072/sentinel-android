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

        setTransparencyToIcons()

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

    private fun setTransparencyToIcons() {
        findPreference<Preference>("sweep")!!.icon.let {
            it!!.alpha = 150
        }

        findPreference<Preference>("broadcastTx")!!.icon.let {
            it!!.alpha = 150
        }
    }

}