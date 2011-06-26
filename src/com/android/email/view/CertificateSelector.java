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

import com.android.email.R;
import com.android.email.activity.UiUtilities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;
import android.security.KeyChain;
import android.security.KeyChainAliasCallback;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A simple view that can be used to select a certificate from the system {@link KeyChain}.
 *
 * Host activities must register themselves view {@link #setActivity} for this selector to work,
 * since it requires firing system {@link Intent}s.
 */
public class CertificateSelector extends LinearLayout implements
        OnClickListener, KeyChainAliasCallback {

    /** Button to select or remove the certificate. */
    private Button mSelectButton;
    private TextView mAliasText;

    /** The host activity. */
    private Activity mActivity;


    public CertificateSelector(Context context) {
        super(context);
    }
    public CertificateSelector(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public CertificateSelector(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setActivity(Activity activity) {
        mActivity = activity;
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
        mAliasText.setText(alias);
        mAliasText.setVisibility((alias == null) ? View.GONE : View.VISIBLE);
        mSelectButton.setText(getResources().getString(
                (alias == null)
                ? R.string.account_setup_exchange_use_certificate
                : R.string.account_setup_exchange_remove_certificate));
    }

    public boolean hasCertificate() {
        return mAliasText.getVisibility() == View.VISIBLE;
    }

    /**
     * Gets the alias for the currently selected certificate, or null if one is not selected.
     */
    public String getCertificate() {
        return hasCertificate() ? mAliasText.getText().toString() : null;
    }


    @Override
    public void onClick(View target) {
        if (target == mSelectButton && mActivity != null) {
            if (hasCertificate()) {
                // Handle the click on the button when it says "Remove"
                setCertificate(null);

            } else {
                // We don't restrict the chooser for certificate types since 95% of the time the
                // user will probably only have one certificate installed and it'll be the right
                // "type". Just let them fail and select a different one if it doesn't match.
                KeyChain.choosePrivateKeyAlias(
                        mActivity, this,
                        null /* keytypes */, null /* issuers */,
                        null /* host */, -1 /* port */,
                        null /* alias */);
            }
        }
    }

    // KeyChainAliasCallback
    @Override
    public void alias(String alias) {
        if (alias != null) {
            setCertificate(alias);
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
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

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
