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

import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.MailService;
import com.android.emailcommon.Logging;

import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.TextView;

public class DebugFragment extends Fragment implements OnCheckedChangeListener,
        View.OnClickListener {
    private TextView mVersionView;
    private CheckBox mEnableDebugLoggingView;
    private CheckBox mEnableExchangeLoggingView;
    private CheckBox mEnableExchangeFileLoggingView;
    private CheckBox mInhibitGraphicsAccelerationView;
    private CheckBox mForceOneMinuteRefreshView;
    private CheckBox mEnableStrictModeView;

    private Preferences mPreferences;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupBasicsFragment onCreateView");
        }
        View view = inflater.inflate(R.layout.debug, container, false);

        Context context = getActivity();
        mPreferences = Preferences.getPreferences(context);

        mVersionView = (TextView) UiUtilities.getView(view, R.id.version);
        mVersionView.setText(String.format(context.getString(R.string.debug_version_fmt).toString(),
                context.getString(R.string.build_number)));

        mEnableDebugLoggingView = (CheckBox) UiUtilities.getView(view, R.id.debug_logging);
        mEnableDebugLoggingView.setChecked(Email.DEBUG);

        mEnableExchangeLoggingView = (CheckBox) UiUtilities.getView(view, R.id.exchange_logging);
        mEnableExchangeFileLoggingView =
            (CheckBox) UiUtilities.getView(view, R.id.exchange_file_logging);

        // Note:  To prevent recursion while presetting checkboxes, assign all listeners last
        mEnableDebugLoggingView.setOnCheckedChangeListener(this);

        boolean exchangeAvailable = EmailServiceUtils.isExchangeAvailable(context);
        if (exchangeAvailable) {
            mEnableExchangeLoggingView.setChecked(Email.DEBUG_EXCHANGE_VERBOSE);
            mEnableExchangeFileLoggingView.setChecked(Email.DEBUG_EXCHANGE_FILE);
            mEnableExchangeLoggingView.setOnCheckedChangeListener(this);
            mEnableExchangeFileLoggingView.setOnCheckedChangeListener(this);
        } else {
            mEnableExchangeLoggingView.setVisibility(View.GONE);
            mEnableExchangeFileLoggingView.setVisibility(View.GONE);
        }

        UiUtilities.getView(view, R.id.clear_webview_cache).setOnClickListener(this);

        mInhibitGraphicsAccelerationView = (CheckBox)
                UiUtilities.getView(view, R.id.debug_disable_graphics_acceleration);
        mInhibitGraphicsAccelerationView.setChecked(Email.sDebugInhibitGraphicsAcceleration);
        mInhibitGraphicsAccelerationView.setOnCheckedChangeListener(this);

        mForceOneMinuteRefreshView = (CheckBox)
                UiUtilities.getView(view, R.id.debug_force_one_minute_refresh);
        mForceOneMinuteRefreshView.setChecked(mPreferences.getForceOneMinuteRefresh());
        mForceOneMinuteRefreshView.setOnCheckedChangeListener(this);

        mEnableStrictModeView = (CheckBox)
                UiUtilities.getView(view, R.id.debug_enable_strict_mode);
        mEnableStrictModeView.setChecked(mPreferences.getEnableStrictMode());
        mEnableStrictModeView.setOnCheckedChangeListener(this);

        return view;
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch (buttonView.getId()) {
            case R.id.debug_logging:
                mPreferences.setEnableDebugLogging(isChecked);
                Email.DEBUG = isChecked;
                Email.DEBUG_EXCHANGE = isChecked;
                break;
             case R.id.exchange_logging:
                mPreferences.setEnableExchangeLogging(isChecked);
                Email.DEBUG_EXCHANGE_VERBOSE = isChecked;
                break;
            case R.id.exchange_file_logging:
                mPreferences.setEnableExchangeFileLogging(isChecked);
                Email.DEBUG_EXCHANGE_FILE = isChecked;
                break;
           case R.id.debug_disable_graphics_acceleration:
                Email.sDebugInhibitGraphicsAcceleration = isChecked;
                mPreferences.setInhibitGraphicsAcceleration(isChecked);
                break;
            case R.id.debug_force_one_minute_refresh:
                mPreferences.setForceOneMinuteRefresh(isChecked);
                MailService.actionReschedule(getActivity());
                break;
            case R.id.debug_enable_strict_mode:
                mPreferences.setEnableStrictMode(isChecked);
                Email.enableStrictMode(isChecked);
                break;
        }

        Email.updateLoggingFlags(getActivity());
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
            Log.w(Logging.LOG_TAG, "Cleard WebView cache.");
        } finally {
            webview.destroy();
        }
    }
}
