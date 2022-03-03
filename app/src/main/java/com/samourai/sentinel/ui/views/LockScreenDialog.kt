package com.samourai.sentinel.ui.views

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.util.Log
import android.view.*
import android.view.animation.CycleInterpolator
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.samourai.sentinel.R
import com.samourai.sentinel.core.access.AccessFactory
import com.samourai.sentinel.databinding.FragmentLockScreenBinding
import com.samourai.sentinel.ui.utils.PrefsUtil
import org.koin.java.KoinJavaComponent.inject


/**
 * sentinel-android
 *
 * @author Sarath
 */
class LockScreenDialog(private val cancelable: Boolean = false, private val lockScreenMessage: String = "") : DialogFragment() {
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private var userInput = StringBuilder()
    private var strPassphrase = ""
    private var onConfirm: ((String) -> Unit)? = null

    private var _binding: FragmentLockScreenBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentLockScreenBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding.pinEntryView.setConfirmClickListener {
            onConfirm.let { it?.invoke(userInput.toString()) }
        }
        binding.lockScreenText.text = lockScreenMessage
        prefsUtil.scramblePin?.let { binding.pinEntryView.setScramble(it) }
        prefsUtil.haptics?.let { binding.pinEntryView.isHapticFeedbackEnabled = it }
        binding.pinEntryView.setEntryListener { key, _ ->
            if (userInput.length <= AccessFactory.MAX_PIN_LENGTH - 1) {
                hapticFeedBack()
                userInput = userInput.append(key)
                if (userInput.length >= AccessFactory.MIN_PIN_LENGTH) {
                    binding.pinEntryView.showCheckButton()
                } else {
                    binding.pinEntryView.hideCheckButton()
                }
                setPinMaskView()
            }
        }
        binding.pinEntryView.setClearListener { clearType ->
            if (clearType === PinEntryView.KeyClearTypes.CLEAR) {
                if (userInput.isNotEmpty()) userInput = java.lang.StringBuilder(userInput.substring(0, userInput.length - 1))
                if (userInput.length >= AccessFactory.MIN_PIN_LENGTH) {
                    binding.pinEntryView.showCheckButton()
                } else {
                    binding.pinEntryView.hideCheckButton()
                }
            } else {
                strPassphrase = ""
                userInput = java.lang.StringBuilder()
                binding.pinEntryMaskLayout.removeAllViews()
                binding.pinEntryView.hideCheckButton()
            }
            setPinMaskView()
        }
    }

    fun setOnPinEntered(callback: ((String) -> Unit)) {
        this.onConfirm = callback
    }

    private fun hapticFeedBack() {
        if(binding.pinEntryView.isHapticFeedbackEnabled) {
            val vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(44, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(44)
            }
        }
    }

    private fun setPinMaskView() {
        if (userInput.length > binding.pinEntryMaskLayout.childCount && userInput.isNotEmpty()) {
            val image = ImageView(requireContext())
            image.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.circle_dot_white))
            image.drawable.setColorFilter(Color.WHITE, PorterDuff.Mode.ADD)
            val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(8, 0, 8, 0)
            TransitionManager.beginDelayedTransition(binding.pinEntryMaskLayout, ChangeBounds().setDuration(50))
            binding.pinEntryMaskLayout.addView(image, params)
        } else {
            if (binding.pinEntryMaskLayout.childCount != 0) {
                TransitionManager.beginDelayedTransition(binding.pinEntryMaskLayout, ChangeBounds().setDuration(200))
                binding.pinEntryMaskLayout.removeViewAt(binding.pinEntryMaskLayout.childCount - 1)
            }
        }

    }

    fun showError() {
        binding.pinEntryMaskLayout.removeAllViews()
        userInput.delete(0, userInput.length)
        binding.pinEntryView.hideCheckButton()
        val errorShake = TranslateAnimation(0F, 12F, 0F, 0F)
        errorShake.duration = 420
        errorShake.interpolator = CycleInterpolator(4F)
        binding.pinEntryMaskLayout.startAnimation(errorShake)
        if (prefsUtil.haptics!!)
            binding.pinEntryMaskLayout.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}