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

package com.android.email.activity.setup;

import com.android.email.R;
import com.android.email.Utility;
import com.android.email.activity.MessageList;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.AccountColumns;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.text.method.TextKeyListener.Capitalize;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;

public class AccountSetupNames extends Activity implements OnClickListener {
    private static final String EXTRA_ACCOUNT_ID = "accountId";
    private static final String EXTRA_EAS_FLOW = "easFlow";

    private EditText mDescription;
    private EditText mName;
    private EmailContent.Account mAccount;
    private Button mDoneButton;

    public static void actionSetNames(Activity fromActivity, long accountId, boolean easFlowMode) {
        Intent i = new Intent(fromActivity, AccountSetupNames.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(EXTRA_EAS_FLOW, easFlowMode);
        fromActivity.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_names);
        mDescription = (EditText)findViewById(R.id.account_description);
        mName = (EditText)findViewById(R.id.account_name);
        mDoneButton = (Button)findViewById(R.id.done);
        mDoneButton.setOnClickListener(this);

        TextWatcher validationTextWatcher = new TextWatcher() {
            public void afterTextChanged(Editable s) {
                validateFields();
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        };
        mName.addTextChangedListener(validationTextWatcher);
        
        mName.setKeyListener(TextKeyListener.getInstance(false, Capitalize.WORDS));

        long accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, -1);
        mAccount = EmailContent.Account.restoreAccountWithId(this, accountId);

        /*
         * Since this field is considered optional, we don't set this here. If
         * the user fills in a value we'll reset the current value, otherwise we
         * just leave the saved value alone.
         */
        // mDescription.setText(mAccount.getDescription());
        if (mAccount.getSenderName() != null) {
            mName.setText(mAccount.getSenderName());
        }
        if (!Utility.requiredFieldValid(mName)) {
            mDoneButton.setEnabled(false);
        }
    }

    /**
     * TODO: Validator should also trim the name string before checking it.
     */
    private void validateFields() {
        mDoneButton.setEnabled(Utility.requiredFieldValid(mName));
        Utility.setCompoundDrawablesAlpha(mDoneButton, mDoneButton.isEnabled() ? 255 : 128);
    }

    /**
     * After having a chance to input the display names, we normally jump directly to the
     * inbox for the new account.  However if we're in EAS flow mode (externally-launched
     * account creation) we simply "pop" here which should return us to the Accounts activities.
     *
     * TODO: Validator should also trim the description string before checking it.
     */
    private void onNext() {
        if (Utility.requiredFieldValid(mDescription)) {
            mAccount.setDisplayName(mDescription.getText().toString());
        }
        String name = mName.getText().toString();
        mAccount.setSenderName(name);
        ContentValues cv = new ContentValues();
        cv.put(AccountColumns.DISPLAY_NAME, mAccount.getDisplayName());
        cv.put(AccountColumns.SENDER_NAME, name);
        mAccount.update(this, cv);

        // Exit or dispatch per flow mode
        if (getIntent().getBooleanExtra(EXTRA_EAS_FLOW, false)) {
            // do nothing - just pop off the activity stack
        } else {
            MessageList.actionHandleAccount(this, mAccount.mId, EmailContent.Mailbox.TYPE_INBOX);
        }
        finish();
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.done:
                onNext();
                break;
        }
    }
}
