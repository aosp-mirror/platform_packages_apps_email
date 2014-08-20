/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.android.email.view;

import android.content.Context;
import android.content.res.Resources;
import android.os.Parcel;
import android.os.Parcelable;
import android.security.KeyChain;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.email.R;
import com.android.email.activity.UiUtilities;

/**
 * A simple view that can be used to select a certificate from the system {@link KeyChain}.
 *
 * Host activities must register themselves view {@link #setHostCallback} for this selector to work.
 */
public class CertificateSelector extends RelativeLayout implements OnClickListener {

    /** Button to select or remove the certificate. */
    private Button mSelectButton;
    private TextView mAliasText;

    /** The value of the cert selected, if any. Null, otherwise. */
    private String mValue;

    /** The host activity. */
    private HostCallback mHost;

    public interface HostCallback {
        void onCertificateRequested();
    }

    public CertificateSelector(Context context) {
        super(context);
    }
    public CertificateSelector(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public CertificateSelector(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setHostCallback(HostCallback host) {
        mHost = host;
    }

    public void setDelegate(String uri) {
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mAliasText = UiUtilities.getView(this, R.id.certificate_alias);
        mSelectButton = UiUtilities.getView(this, R.id.select_button);
        mSelectButton.setOnClickListener(this);
        setCertificate(null);
    }

    public void setCertificate(String alias) {
        Resources res = getResources();
        mValue = alias;
        mAliasText.setText(
                TextUtils.isEmpty(alias)
                ? res.getString(R.string.account_setup_exchange_no_certificate)
                : alias);
        mSelectButton.setText(res.getString(
                TextUtils.isEmpty(alias)
                ? R.string.account_setup_exchange_select_certificate
                : R.string.account_setup_exchange_remove_certificate));
    }

    public boolean hasCertificate() {
        return mValue != null;
    }

    /**
     * Gets the alias for the currently selected certificate, or null if one is not selected.
     */
    public String getCertificate() {
        return mValue;
    }


    @Override
    public void onClick(View target) {
        if (target == mSelectButton && mHost != null) {
            if (hasCertificate()) {
                // Handle the click on the button when it says "Remove"
                setCertificate(null);
            } else {
                mHost.onCertificateRequested();
            }
        }
    }

    @Override
    protected void onRestoreInstanceState(Parcelable parcel) {
        SavedState savedState = (SavedState) parcel;
        super.onRestoreInstanceState(savedState.getSuperState());
        setCertificate(savedState.mValue);
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        return new SavedState(super.onSaveInstanceState(), getCertificate());
    }

    public static class SavedState extends BaseSavedState {
        final String mValue;

        SavedState(Parcelable superState, String value) {
            super(superState);
            mValue = value;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeString(mValue);
        }

        @SuppressWarnings("hiding")
        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };

        private SavedState(Parcel in) {
            super(in);
            mValue = in.readString();
        }
    }
}
