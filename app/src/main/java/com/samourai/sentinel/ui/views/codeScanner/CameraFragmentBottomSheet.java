package com.samourai.sentinel.ui.views.codeScanner;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.samourai.sentinel.R;
import com.samourai.sentinel.util.PrefsUtil;


public class CameraFragmentBottomSheet extends BottomSheetDialogFragment {
    private CodeScanner mCodeScanner;

    private ListenQRScan listenQRScan;


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottomsheet_camera, null);
        CodeScannerView scannerView = view.findViewById(R.id.scanner_view);
        mCodeScanner = new CodeScanner(getActivity().getApplicationContext(), scannerView);
        mCodeScanner.setAutoFocusEnabled(true);
        mCodeScanner.setDecodeCallback(result -> getActivity().runOnUiThread(() -> {
            if (this.listenQRScan != null) {
                this.listenQRScan.onDetect(result.getText());
            }
        }));
        scannerView.setOnClickListener(view1 -> mCodeScanner.startPreview());
        return view;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        if (PrefsUtil.getInstance(requireContext()).getValue("displaySecure", true))
            dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        return dialog;
    }
    @Override
    public void onResume() {
        super.onResume();
        mCodeScanner.startPreview();
    }

    public void setQrCodeScanLisenter(ListenQRScan listenQRScan) {
        this.listenQRScan = listenQRScan;
    }

    @Override
    public void onPause() {
        mCodeScanner.releaseResources();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mCodeScanner.releaseResources();
        super.onDestroy();
    }

    public interface ListenQRScan {
        void onDetect(String code);
    }

    @Override
    public void onStart() {
        super.onStart();

        View view = getView();
        if (view != null) {
            view.post(() -> {
                View parent = (View) view.getParent();
                CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) (parent).getLayoutParams();
                CoordinatorLayout.Behavior behavior = params.getBehavior();
                BottomSheetBehavior bottomSheetBehavior = (BottomSheetBehavior) behavior;
                if (bottomSheetBehavior != null) {
                    bottomSheetBehavior.setPeekHeight(view.getMeasuredHeight());
                }

            });
        }
    }
}