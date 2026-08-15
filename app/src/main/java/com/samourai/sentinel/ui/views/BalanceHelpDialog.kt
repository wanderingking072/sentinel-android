package com.samourai.sentinel.ui.views

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.sentinel.R
import com.samourai.sentinel.ui.dojo.DojoBayConstants

/**
 * "If your balance isn't showing up, rescan your xpub" help - shown once
 * right after a first-time Dojo connection during initial setup, and again
 * on demand from Settings > Troubleshooting & Debug. Kept in one place so
 * both call sites show the exact same message.
 */
object BalanceHelpDialog {

    fun show(context: Context) {
        val message = context.getString(R.string.balance_query_help_message, DojoBayConstants.BASE_URL)
        val spannable = SpannableString(message)
        val linkStart = message.indexOf(DojoBayConstants.BASE_URL)
        if (linkStart >= 0) {
            spannable.setSpan(
                URLSpan(DojoBayConstants.BASE_URL),
                linkStart,
                linkStart + DojoBayConstants.BASE_URL.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val messageView = TextView(context).apply {
            setText(spannable, TextView.BufferType.SPANNABLE)
            movementMethod = LinkMovementMethod.getInstance()
            setTextIsSelectable(true)
            setLinkTextColor(ContextCompat.getColor(context, R.color.green_ui_2))
            setTextColor(ContextCompat.getColor(context, R.color.white))
            val paddingHorizontal = (24 * context.resources.displayMetrics.density).toInt()
            val paddingVertical = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.balance_query_help_title))
            .setView(messageView)
            .setPositiveButton(context.getString(R.string.ok)) { dialog, _ -> dialog.dismiss() }
            .setNeutralButton(context.getString(R.string.copy_dojo_bay_link)) { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Dojo Bay", DojoBayConstants.BASE_URL))
                Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
