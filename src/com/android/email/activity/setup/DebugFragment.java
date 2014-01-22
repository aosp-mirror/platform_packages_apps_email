/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.email.activity.setup;

import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;

import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Logging;
import com.android.mail.utils.LogUtils;

public class DebugFragment extends Fragment implements OnCheckedChangeListener,
        View.OnClickListener {
    private Preferences mPreferences;

    // Public no-args constructor needed for fragment re-instantiation
    public DebugFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSetupBasicsFragment onCreateView");
        }
        View view = inflater.inflate(R.layout.debug, container, false);

        Context context = getActivity();
        mPreferences = Preferences.getPreferences(context);

        final CheckBox enableDebugLoggingView = UiUtilities.getView(view, R.id.debug_logging);
        enableDebugLoggingView.setChecked(MailActivityEmail.DEBUG);

        final CheckBox enableVerboseLoggingView = UiUtilities.getView(view, R.id.verbose_logging);
        final CheckBox enableFileLoggingView = UiUtilities.getView(view, R.id.file_logging);

        // Note:  To prevent recursion while presetting checkboxes, assign all listeners last
        enableDebugLoggingView.setOnCheckedChangeListener(this);

        if (EmailServiceUtils.areRemoteServicesInstalled(context)) {
            enableVerboseLoggingView.setChecked(MailActivityEmail.DEBUG_VERBOSE);
            enableFileLoggingView.setChecked(MailActivityEmail.DEBUG_FILE);
            enableVerboseLoggingView.setOnCheckedChangeListener(this);
            enableFileLoggingView.setOnCheckedChangeListener(this);
        } else {
            enableVerboseLoggingView.setVisibility(View.GONE);
            enableFileLoggingView.setVisibility(View.GONE);
        }

        UiUtilities.getView(view, R.id.clear_webview_cache).setOnClickListener(this);

        final CheckBox enableStrictModeView =
                UiUtilities.getView(view, R.id.debug_enable_strict_mode);
        enableStrictModeView.setChecked(mPreferences.getEnableStrictMode());
        enableStrictModeView.setOnCheckedChangeListener(this);

        return view;
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch (buttonView.getId()) {
            case R.id.debug_logging:
                mPreferences.setEnableDebugLogging(isChecked);
                MailActivityEmail.DEBUG = isChecked;
                MailActivityEmail.DEBUG_EXCHANGE = isChecked;
                break;
            case R.id.verbose_logging:
                mPreferences.setEnableExchangeLogging(isChecked);
                MailActivityEmail.DEBUG_VERBOSE = isChecked;
                break;
            case R.id.file_logging:
                mPreferences.setEnableExchangeFileLogging(isChecked);
                MailActivityEmail.DEBUG_FILE = isChecked;
                break;
            case R.id.debug_enable_strict_mode:
                mPreferences.setEnableStrictMode(isChecked);
                MailActivityEmail.enableStrictMode(isChecked);
                break;
        }

        MailActivityEmail.updateLoggingFlags(getActivity());
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.clear_webview_cache:
                clearWebViewCache();
                break;
        }
    }

    private void clearWebViewCache() {
        WebView webview = new WebView(getActivity());
        try {
            webview.clearCache(true);
            LogUtils.w(Logging.LOG_TAG, "Cleard WebView cache.");
        } finally {
            webview.destroy();
        }
    }
}
