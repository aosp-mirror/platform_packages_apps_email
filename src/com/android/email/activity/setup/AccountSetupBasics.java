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

import com.android.email.EmailAddressValidator;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.VendorPolicyLoader;
import com.android.email.activity.Debug;
import com.android.email.activity.MessageList;
import com.android.email.activity.setup.AccountSettingsUtils.Provider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Prompts the user for the email address and password. Also prompts for
 * "Use this account as default" if this is the 2nd+ account being set up.
 * Attempts to lookup default settings for the domain the user specified. If the
 * domain is known the settings are handed off to the AccountSetupCheckSettings
 * activity. If no settings are found the settings are handed off to the
 * AccountSetupAccountType activity.
 */
public class AccountSetupBasics extends AccountSetupActivity
        implements OnClickListener, TextWatcher {
    private final static boolean ENTER_DEBUG_SCREEN = true;

    private final static int DIALOG_NOTE = 1;
    private final static int DIALOG_DUPLICATE_ACCOUNT = 2;

    private final static String STATE_KEY_PROVIDER =
        "com.android.email.AccountSetupBasics.provider";

    // NOTE: If you change this value, confirm that the new interval exists in arrays.xml
    private final static int DEFAULT_ACCOUNT_CHECK_INTERVAL = 15;

    private EditText mEmailView;
    private EditText mPasswordView;
    private CheckBox mDefaultView;
    private Button mNextButton;
    private Button mManualSetupButton;

    private Provider mProvider;
    private String mDuplicateAccountName;

    private EmailAddressValidator mEmailValidator = new EmailAddressValidator();

    public static void actionNewAccount(Activity fromActivity) {
        SetupData.init(SetupData.FLOW_MODE_NORMAL);
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupBasics.class));
    }

    public static void actionNewAccountWithCredentials(Activity fromActivity,
            String username, String password, int accountFlowMode) {
        SetupData.init(accountFlowMode, username, password);
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupBasics.class));
    }

    /**
     * This generates setup data that can be used to start a self-contained account creation flow
     * for exchange accounts.
     */
    public static Intent actionSetupExchangeIntent(Context context) {
        SetupData.init(SetupData.FLOW_MODE_ACCOUNT_MANAGER_EAS);
        return new Intent(context, AccountSetupBasics.class);
    }

    /**
     * This generates setup data that can be used to start a self-contained account creation flow
     * for pop/imap accounts.
     */
    public static Intent actionSetupPopImapIntent(Context context) {
        SetupData.init(SetupData.FLOW_MODE_ACCOUNT_MAANGER_POP_IMAP);
        return new Intent(context, AccountSetupBasics.class);
    }

    public static void actionAccountCreateFinishedAccountFlow(Activity fromActivity) {
        Intent i= new Intent(fromActivity, AccountSetupBasics.class);
        // If we're in the "account flow" (from AccountManager), we want to return to the caller
        // (in the settings app)
        SetupData.init(SetupData.FLOW_MODE_RETURN_TO_CALLER);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        fromActivity.startActivity(i);
    }

    public static void actionAccountCreateFinished(final Activity fromActivity,
            final long accountId) {
        Utility.runAsync(new Runnable() {
           public void run() {
               Intent i = new Intent(fromActivity, AccountSetupBasics.class);
               // If we're not in the "account flow" (from AccountManager), we want to show the
               // message list for the new inbox
               Account account = Account.restoreAccountWithId(fromActivity, accountId);
               SetupData.init(SetupData.FLOW_MODE_RETURN_TO_MESSAGE_LIST, account);
               i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
               fromActivity.startActivity(i);
            }});
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int flowMode = SetupData.getFlowMode();
        if (flowMode == SetupData.FLOW_MODE_RETURN_TO_CALLER) {
            // Return to the caller who initiated account creation
            finish();
            return;
        } else if (flowMode == SetupData.FLOW_MODE_RETURN_TO_MESSAGE_LIST) {
            Account account = SetupData.getAccount();
            if (account != null && account.mId >= 0) {
                // Show the message list for the new account
                MessageList.actionHandleAccount(this, account.mId, Mailbox.TYPE_INBOX);
                finish();
                return;
            }
        }

        setContentView(R.layout.account_setup_basics);

        mEmailView = (EditText)findViewById(R.id.account_email);
        mPasswordView = (EditText)findViewById(R.id.account_password);
        mDefaultView = (CheckBox)findViewById(R.id.account_default);
        mNextButton = (Button)findViewById(R.id.next);
        mManualSetupButton = (Button)findViewById(R.id.manual_setup);

        mNextButton.setOnClickListener(this);
        mManualSetupButton.setOnClickListener(this);

        mEmailView.addTextChangedListener(this);
        mPasswordView.addTextChangedListener(this);

        // Find out how many accounts we have, and if there one or more, then we have a choice
        // about being default or not.
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    EmailContent.Account.CONTENT_URI,
                    EmailContent.Account.ID_PROJECTION,
                    null, null, null);
            if (c != null && c.getCount() > 0) {
                mDefaultView.setVisibility(View.VISIBLE);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }

        if (flowMode == SetupData.FLOW_MODE_ACCOUNT_MANAGER_EAS) {
            // No need for manual button -> next is appropriate
            mManualSetupButton.setVisibility(View.GONE);
            // Swap welcome text for EAS-specific text
            TextView welcomeView = (TextView) findViewById(R.id.instructions);
            final boolean alternateStrings =
                VendorPolicyLoader.getInstance(this).useAlternateExchangeStrings();
            setTitle(alternateStrings
                    ? R.string.account_setup_basics_exchange_title_alternate
                            : R.string.account_setup_basics_exchange_title);
            welcomeView.setText(alternateStrings
                    ? R.string.accounts_welcome_exchange_alternate
                            : R.string.accounts_welcome_exchange);
        }

        if (SetupData.getUsername() != null) {
            mEmailView.setText(SetupData.getUsername());
        }
        if (SetupData.getPassword() != null) {
            mPasswordView.setText(SetupData.getPassword());
        }
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_KEY_PROVIDER)) {
            mProvider = (Provider)savedInstanceState.getSerializable(STATE_KEY_PROVIDER);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        validateFields();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mProvider != null) {
            outState.putSerializable(STATE_KEY_PROVIDER, mProvider);
        }
    }

    public void afterTextChanged(Editable s) {
        validateFields();
    }

    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    private void validateFields() {
        boolean valid = Utility.isTextViewNotEmpty(mEmailView)
                && Utility.isTextViewNotEmpty(mPasswordView)
                && mEmailValidator.isValid(mEmailView.getText().toString().trim());
        mNextButton.setEnabled(valid);
        mManualSetupButton.setEnabled(valid);
        /*
         * Dim the next button's icon to 50% if the button is disabled.
         * TODO this can probably be done with a stateful drawable. Check into it.
         * android:state_enabled
         */
        Utility.setCompoundDrawablesAlpha(mNextButton, mNextButton.isEnabled() ? 255 : 128);
    }

    private String getOwnerName() {
        String name = null;
/* TODO figure out another way to get the owner name
        String projection[] = {
            ContactMethods.NAME
        };
        Cursor c = getContentResolver().query(
                Uri.withAppendedPath(Contacts.People.CONTENT_URI, "owner"), projection, null, null,
                null);
        if (c != null) {
            if (c.moveToFirst()) {
                name = c.getString(0);
            }
            c.close();
        }
*/

        if (name == null || name.length() == 0) {
            long defaultId = Account.getDefaultAccountId(this);
            if (defaultId != -1) {
                Account account = Account.restoreAccountWithId(this, defaultId);
                if (account != null) {
                    name = account.getSenderName();
                }
            }
        }
        return name;
    }

    @Override
    public Dialog onCreateDialog(int id) {
        if (id == DIALOG_NOTE) {
            if (mProvider != null && mProvider.note != null) {
                return new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(android.R.string.dialog_alert_title)
                .setMessage(mProvider.note)
                .setPositiveButton(
                        getString(R.string.okay_action),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                finishAutoSetup();
                            }
                        })
                        .setNegativeButton(
                                getString(R.string.cancel_action),
                                null)
                                .create();
            }
        } else if (id == DIALOG_DUPLICATE_ACCOUNT) {
            return new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(R.string.account_duplicate_dlg_title)
            .setMessage(getString(R.string.account_duplicate_dlg_message_fmt,
                    mDuplicateAccountName))
                    .setPositiveButton(R.string.okay_action,
                            new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dismissDialog(DIALOG_DUPLICATE_ACCOUNT);
                        }
                    })
                    .create();
        }
        return null;
    }

    /**
     * Update a cached dialog with current values (e.g. account name)
     */
    @Override
    public void onPrepareDialog(int id, Dialog dialog) {
        switch (id) {
            case DIALOG_NOTE:
                if (mProvider != null && mProvider.note != null) {
                    AlertDialog alert = (AlertDialog) dialog;
                    alert.setMessage(mProvider.note);
                }
                break;
            case DIALOG_DUPLICATE_ACCOUNT:
                if (mDuplicateAccountName != null) {
                    AlertDialog alert = (AlertDialog) dialog;
                    alert.setMessage(getString(R.string.account_duplicate_dlg_message_fmt,
                            mDuplicateAccountName));
                }
                break;
        }
    }

    private void finishAutoSetup() {
        String email = mEmailView.getText().toString().trim();
        String password = mPasswordView.getText().toString().trim();
        String[] emailParts = email.split("@");
        String user = emailParts[0];
        String domain = emailParts[1];
        URI incomingUri = null;
        URI outgoingUri = null;
        try {
            String incomingUsername = mProvider.incomingUsernameTemplate;
            incomingUsername = incomingUsername.replaceAll("\\$email", email);
            incomingUsername = incomingUsername.replaceAll("\\$user", user);
            incomingUsername = incomingUsername.replaceAll("\\$domain", domain);

            URI incomingUriTemplate = mProvider.incomingUriTemplate;
            incomingUri = new URI(incomingUriTemplate.getScheme(), incomingUsername + ":"
                    + password, incomingUriTemplate.getHost(), incomingUriTemplate.getPort(),
                    incomingUriTemplate.getPath(), null, null);

            String outgoingUsername = mProvider.outgoingUsernameTemplate;
            outgoingUsername = outgoingUsername.replaceAll("\\$email", email);
            outgoingUsername = outgoingUsername.replaceAll("\\$user", user);
            outgoingUsername = outgoingUsername.replaceAll("\\$domain", domain);

            URI outgoingUriTemplate = mProvider.outgoingUriTemplate;
            outgoingUri = new URI(outgoingUriTemplate.getScheme(), outgoingUsername + ":"
                    + password, outgoingUriTemplate.getHost(), outgoingUriTemplate.getPort(),
                    outgoingUriTemplate.getPath(), null, null);

            // Stop here if the login credentials duplicate an existing account
            Account account = Utility.findExistingAccount(this, -1,
                    incomingUri.getHost(), incomingUsername);
            if (account != null) {
                mDuplicateAccountName = account.mDisplayName;
                this.showDialog(DIALOG_DUPLICATE_ACCOUNT);
                return;
            }

        } catch (URISyntaxException use) {
            /*
             * If there is some problem with the URI we give up and go on to
             * manual setup.  Technically speaking, AutoDiscover is OK here, since user clicked
             * "Next" to get here.  This would never happen in practice because we don't expect
             * to find any EAS accounts in the providers list.
             */
            onManualSetup(true);
            return;
        }

        Account account = SetupData.getAccount();
        account.setSenderName(getOwnerName());
        account.setEmailAddress(email);
        account.setStoreUri(this, incomingUri.toString());
        account.setSenderUri(this, outgoingUri.toString());
/* TODO figure out the best way to implement this concept
        mAccount.setDraftsFolderName(getString(R.string.special_mailbox_name_drafts));
        mAccount.setTrashFolderName(getString(R.string.special_mailbox_name_trash));
        mAccount.setOutboxFolderName(getString(R.string.special_mailbox_name_outbox));
        mAccount.setSentFolderName(getString(R.string.special_mailbox_name_sent));
*/
        if (incomingUri.toString().startsWith("imap")) {
            // Delete policy must be set explicitly, because IMAP does not provide a UI selection
            // for it. This logic needs to be followed in the auto setup flow as well.
            account.setDeletePolicy(EmailContent.Account.DELETE_POLICY_ON_DELETE);
        }
        account.setSyncInterval(DEFAULT_ACCOUNT_CHECK_INTERVAL);
        AccountSetupCheckSettings.actionCheckSettings(this,
                SetupData.CHECK_INCOMING | SetupData.CHECK_OUTGOING);
    }

    private void onNext() {
        // If this is EAS flow, don't try to find a provider for the domain!
        if (SetupData.getFlowMode() != SetupData.FLOW_MODE_ACCOUNT_MANAGER_EAS) {
            String email = mEmailView.getText().toString().trim();
            String[] emailParts = email.split("@");
            String domain = emailParts[1].trim();
            mProvider = AccountSettingsUtils.findProviderForDomain(this, domain);
            if (mProvider != null) {
                if (mProvider.note != null) {
                    showDialog(DIALOG_NOTE);
                } else {
                    finishAutoSetup();
                }
                return;
            }
        }
        // Can't use auto setup (although EAS accounts may still be able to AutoDiscover)
        onManualSetup(true);
    }

    /**
     * This is used in automatic setup mode to jump directly down to the names screen.
     *
     * NOTE:  With this organization, it is *not* possible to auto-create an exchange account,
     * because certain necessary actions happen during AccountSetupOptions (which we are
     * skipping here).
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            Account account = SetupData.getAccount();
            String email = account.getEmailAddress();
            account.setDisplayName(email);
            boolean isDefault = mDefaultView.isChecked();
            account.setDefaultAccount(isDefault);
            SetupData.setDefault(isDefault);
            AccountSetupOptions.actionOptions(this);
            finish();
        }
    }

    /**
     * @param allowAutoDiscover - true if the user clicked 'next' and (if the account is EAS)
     * it's OK to use autodiscover.  false to prevent autodiscover and go straight to manual setup.
     * Ignored for IMAP & POP accounts.
     */
    private void onManualSetup(boolean allowAutoDiscover) {
        String email = mEmailView.getText().toString().trim();
        String password = mPasswordView.getText().toString().trim();
        String[] emailParts = email.split("@");
        String user = emailParts[0].trim();
        String domain = emailParts[1].trim();

        // Alternate entry to the debug options screen (for devices without a physical keyboard:
        //  Username: d@d.d
        //  Password: debug
        if (ENTER_DEBUG_SCREEN && "d@d.d".equals(email) && "debug".equals(password)) {
            mEmailView.setText("");
            mPasswordView.setText("");
            Debug.actionShow(this);
            return;
        }

        Account account = SetupData.getAccount();
        account.setSenderName(getOwnerName());
        account.setEmailAddress(email);
        try {
            URI uri = new URI("placeholder", user + ":" + password, domain, -1, null, null, null);
            account.setStoreUri(this, uri.toString());
            account.setSenderUri(this, uri.toString());
        } catch (URISyntaxException use) {
            // If we can't set up the URL, don't continue - account setup pages will fail too
            Toast.makeText(this, R.string.account_setup_username_password_toast, Toast.LENGTH_LONG)
                    .show();
            account = null;
            return;
        }
/* TODO figure out the best way to implement this concept
        mAccount.setDraftsFolderName(getString(R.string.special_mailbox_name_drafts));
        mAccount.setTrashFolderName(getString(R.string.special_mailbox_name_trash));
        mAccount.setOutboxFolderName(getString(R.string.special_mailbox_name_outbox));
        mAccount.setSentFolderName(getString(R.string.special_mailbox_name_sent));
*/
        account.setSyncInterval(DEFAULT_ACCOUNT_CHECK_INTERVAL);

        AccountSetupAccountType.actionSelectAccountType(this);
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.next:
                onNext();
                break;
            case R.id.manual_setup:
                // no AutoDiscover - user clicked "manual"
                onManualSetup(false);
                break;
        }
    }
}
