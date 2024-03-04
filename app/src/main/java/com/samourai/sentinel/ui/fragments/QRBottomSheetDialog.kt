package com.samourai.sentinel.ui.fragments;

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.transition.TransitionManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.imageview.ShapeableImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.client.android.Contents
import com.google.zxing.client.android.encode.QRCodeEncoder
import com.samourai.sentinel.R
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.ui.views.GenericBottomSheet
import com.samourai.sentinel.util.AppUtil
import java.io.File
import java.io.FileOutputStream

class QRBottomSheetDialog(val qrData: String, val title: String? = "", val clipboardLabel: String? = "",  val secure: Boolean = false, val collection: PubKeyCollection? = null) : GenericBottomSheet(secure = secure) {

    override fun getTheme(): Int = R.style.AppTheme_BottomSheet_Theme

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = BottomSheetDialog(
        requireContext(),
        theme
    )


    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_qr_code_bottomsheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val qrDialogCopyToClipBoard = view.findViewById<TextView>(R.id.qrDialogCopyToClipBoard);
        val shareQrButton = view.findViewById<TextView>(R.id.shareQrButton);
        val leftArrow = view.findViewById<ImageButton>(R.id.leftButton);
        val rightArrow = view.findViewById<ImageButton>(R.id.rightButton);
        val qrToolbar = view.findViewById<MaterialToolbar>(R.id.qrToolbar);
        val qrTextView = view.findViewById<TextView>(R.id.qrTextView);
        val qRImage = view.findViewById<ShapeableImageView>(R.id.imgQrCode);

        dialog?.window?.navigationBarColor = ContextCompat.getColor(requireContext(), R.color.grey_homeActivity)

        if (collection != null && collection.isImportFromWallet) {
            val layoutParams = qRImage.layoutParams as ConstraintLayout.LayoutParams
            val newMarginEnd = (80 * resources.displayMetrics.density).toInt()
            layoutParams.marginEnd = newMarginEnd
            qRImage.layoutParams = layoutParams

            leftArrow.visibility = View.VISIBLE
            rightArrow.visibility = View.VISIBLE
        }

        title?.let {
            if (collection!!.isImportFromWallet && it.equals("Deposit"))
                qrToolbar.title = "Deposit BIP84"
            else
                qrToolbar.title = it
        }

        rightArrow.setOnClickListener {
            if (qrToolbar.title.equals("Deposit BIP84")) {
                qrToolbar.title = "Deposit BIP49"
                qrTextView.text = collection!!.pubs[5].pubKey
                setQR(view, collection.pubs[5].pubKey)
            }
            else if (qrToolbar.title.equals("Deposit BIP49")) {
                qrToolbar.title = "Deposit BIP44"
                qrTextView.text = collection!!.pubs[4].pubKey
                setQR(view, collection.pubs[4].pubKey)
            }
            else if (qrToolbar.title.equals("Deposit BIP44")) {
                qrToolbar.title = "Deposit BIP84"
                qrTextView.text = collection!!.pubs[0].pubKey
                setQR(view, collection.pubs[0].pubKey)
            }
        }

        leftArrow.setOnClickListener {
            if (qrToolbar.title.equals("Deposit BIP84")) {
                qrToolbar.title = "Deposit BIP44"
                qrTextView.text = collection!!.pubs[4].pubKey
                setQR(view, collection.pubs[4].pubKey)
            }
            else if (qrToolbar.title.equals("Deposit BIP49")) {
                qrToolbar.title = "Deposit BIP84"
                qrTextView.text = collection!!.pubs[0].pubKey
                setQR(view, collection.pubs[0].pubKey)
            }
            else if (qrToolbar.title.equals("Deposit BIP44")) {
                qrToolbar.title = "Deposit BIP49"
                qrTextView.text = collection!!.pubs[5].pubKey
                setQR(view, collection.pubs[5].pubKey)
            }
        }

        qrToolbar.setNavigationOnClickListener {
            this.dismiss()
        }

        qrTextView.setOnClickListener {
            TransitionManager.beginDelayedTransition(qrTextView.rootView as ViewGroup)
            if (qrTextView.maxLines == 2) {
                qrTextView.maxLines = 10
            } else {
                qrTextView.maxLines = 2
            }
        }
        qrTextView.text = qrData

        setQR(view, qrData)

        qrDialogCopyToClipBoard.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(clipboardLabel ?: title, qrTextView.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            this.dismiss()
        }
        shareQrButton.setOnClickListener {
            val strFileName = AppUtil.getInstance(requireContext()).receiveQRFilename
            val file = File(strFileName)
            if (!file.exists()) {
                try {
                    file.createNewFile()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
                }
            }

            try {
                val fos = FileOutputStream(file);
                file.setReadable(true, false)
                val bitmap = (qRImage.drawable as BitmapDrawable).bitmap
                bitmap.compress(Bitmap.CompressFormat.PNG, 0, fos)
                val intent = Intent()
                intent.action = Intent.ACTION_SEND
                intent.type = "image/png"
                if (Build.VERSION.SDK_INT >= 24) {
                    //From API 24 sending FIle on intent ,require custom file provider
                    intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                            requireContext(),
                            requireContext()
                                    .packageName + ".provider", file))
                } else {
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
                }
                startActivity(Intent.createChooser(intent, clipboardLabel));

            } catch (ex: Exception) {
                Toast.makeText(requireContext(), ex.message, Toast.LENGTH_SHORT).show()
            }
            dismiss()
        }
    }

    fun setQR(view: View, qrData: String) {
        val qRImage = view.findViewById<ShapeableImageView>(R.id.imgQrCode);
        val radius = resources.getDimension(R.dimen.spacing_large)

        var bitmap: Bitmap? = null
        val qrCodeEncoder = QRCodeEncoder(qrData, null, Contents.Type.TEXT, BarcodeFormat.QR_CODE.toString(), 500)
        try {
            bitmap = qrCodeEncoder.encodeAsBitmap()
        } catch (e: WriterException) {
            e.printStackTrace()
        }

        qRImage.shapeAppearanceModel = qRImage.shapeAppearanceModel
            .toBuilder()
            .setAllCornerSizes(radius)
            .build()
        qRImage.setImageBitmap(bitmap)
    }


}