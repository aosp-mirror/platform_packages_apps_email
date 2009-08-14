/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.email.activity;

import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.exchange.Eas;
import com.android.exchange.utility.FileLogger;

import android.app.Activity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.CompoundButton.OnCheckedChangeListener;

public class Debug extends Activity implements OnCheckedChangeListener {
    private TextView mVersionView;
    private CheckBox mEnableDebugLoggingView;
    private CheckBox mEnableSensitiveLoggingView;
    private CheckBox mEnableExchangeLoggingView;
    private CheckBox mEnableExchangeFileLoggingView;

    private Preferences mPreferences;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.debug);

        mPreferences = Preferences.getPreferences(this);

        mVersionView = (TextView)findViewById(R.id.version);
        mEnableDebugLoggingView = (CheckBox)findViewById(R.id.debug_logging);
        mEnableSensitiveLoggingView = (CheckBox)findViewById(R.id.sensitive_logging);
        mEnableExchangeLoggingView = (CheckBox)findViewById(R.id.exchange_logging);
        mEnableExchangeFileLoggingView = (CheckBox)findViewById(R.id.exchange_file_logging);

        mEnableDebugLoggingView.setOnCheckedChangeListener(this);
        mEnableSensitiveLoggingView.setOnCheckedChangeListener(this);
        mEnableExchangeLoggingView.setOnCheckedChangeListener(this);
        mEnableExchangeFileLoggingView.setOnCheckedChangeListener(this);

        mVersionView.setText(String.format(getString(R.string.debug_version_fmt).toString(),
                getString(R.string.build_number)));

        mEnableDebugLoggingView.setChecked(Email.DEBUG);
        mEnableSensitiveLoggingView.setChecked(Email.DEBUG_SENSITIVE);
        mEnableExchangeLoggingView.setChecked(Eas.USER_LOG);
        mEnableExchangeFileLoggingView.setChecked(Eas.FILE_LOG);
    }

    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch (buttonView.getId()) {
            case R.id.debug_logging:
                Email.DEBUG = isChecked;
                mPreferences.setEnableDebugLogging(Email.DEBUG);
                break;
            case R.id.sensitive_logging:
                Email.DEBUG_SENSITIVE = isChecked;
                mPreferences.setEnableSensitiveLogging(Email.DEBUG_SENSITIVE);
                break;
            case R.id.exchange_logging:
                mPreferences.setEnableExchangeLogging(isChecked);
                break;
            case R.id.exchange_file_logging:
                mPreferences.setEnableExchangeFileLogging(isChecked);
                if (!isChecked) {
                    FileLogger.close();
                }
                break;
        }

        // Now rebuild "debug bits" and send to EAS service
        int debugLogging = mPreferences.getEnableDebugLogging() ? Eas.DEBUG_BIT : 0;
        int exchangeLogging = mPreferences.getEnableExchangeLogging() ? Eas.DEBUG_EXCHANGE_BIT : 0;
        int fileLogging = mPreferences.getEnableExchangeFileLogging() ? Eas.DEBUG_FILE_BIT : 0;
        int debugBits = debugLogging | exchangeLogging | fileLogging;

        Controller.getInstance(getApplication()).serviceLogging(debugBits);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.dump_settings) {
            Preferences.getPreferences(this).dump();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.debug_option, menu);
        return true;
    }

}
