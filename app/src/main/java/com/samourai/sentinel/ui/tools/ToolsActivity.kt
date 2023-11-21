package com.samourai.sentinel.ui.tools

import android.os.Bundle
import android.view.Gravity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.TransitionSet
import com.samourai.sentinel.R
import com.samourai.sentinel.databinding.SettingsActivityBinding
import com.samourai.sentinel.databinding.ToolsActivityLayoutBinding
import com.samourai.sentinel.ui.SentinelActivity
import com.samourai.sentinel.ui.settings.MainSettingsFragment

class ToolsActivity : SentinelActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private lateinit var binding: ToolsActivityLayoutBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ToolsActivityLayoutBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setSupportActionBar(binding.toolbarToolsScreen)
        if (savedInstanceState == null) {
            showFragment(ToolsFragment())
        }
        title = "Tools"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun showFragment(fragment: PreferenceFragmentCompat) {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.tools, fragment)
            .commit()
    }
    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        val args = pref.extras
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader,
            pref.fragment!!
        ).apply {
            arguments = args
            setTargetFragment(caller, 0)
        }
        val transitionEnter = TransitionSet()
        val slide = Slide(Gravity.RIGHT)
        transitionEnter.addTransition(slide)
        transitionEnter.addTransition(Fade())
        val transitionExit = TransitionSet()
        transitionExit.addTransition(Slide(Gravity.LEFT))
        transitionExit.addTransition(Fade())
        fragment.enterTransition = transitionEnter
        fragment.exitTransition = transitionExit
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, fragment)
            .commit()
        title = pref.title
        return true
    }

}