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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup.LayoutParams;

import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.activity.setup.AccountSetupBasics;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.MailService;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.IntentUtilities;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;

/**
 * The Welcome activity initializes the application and starts {@link EmailActivity}, or launch
 * {@link AccountSetupBasics} if no accounts are configured.
 *
 * TOOD Show "your messages are on the way" message like gmail does during the inbox lookup.
 */
public class Welcome extends Activity {
    /*
     * Commands for testing...
     *  Open 1 pane
        adb shell am start -a android.intent.action.MAIN \
            -d '"content://ui.email.android.com/view/mailbox"' \
            -e DEBUG_PANE_MODE 1

     *  Open 2 pane
        adb shell am start -a android.intent.action.MAIN \
            -d '"content://ui.email.android.com/view/mailbox"' \
            -e DEBUG_PANE_MODE 2

     *  Open an account (ID=1) in 2 pane
        adb shell am start -a android.intent.action.MAIN \
            -d '"content://ui.email.android.com/view/mailbox?ACCOUNT_ID=1"' \
            -e DEBUG_PANE_MODE 2

     *  Open a message (account id=1, mailbox id=2, message id=3)
        adb shell am start -a android.intent.action.MAIN \
            -d '"content://ui.email.android.com/view/mailbox?ACCOUNT_ID=1&MAILBOX_ID=2&MESSAGE_ID=3"' \
            -e DEBUG_PANE_MODE 2

     *  Open the combined starred on the combined view
        adb shell am start -a android.intent.action.MAIN \
            -d '"content://ui.email.android.com/view/mailbox?ACCOUNT_ID=1152921504606846976&MAILBOX_ID=-4"' \
            -e DEBUG_PANE_MODE 2
     */

    /**
     * Extra for debugging.  Set 1 to force one-pane.  Set 2 to force two-pane.
     */
    private static final String EXTRA_DEBUG_PANE_MODE = "DEBUG_PANE_MODE";

    public static final String VIEW_MAILBOX_INTENT_URL_PATH = "/view/mailbox";

    private final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    private View mWaitingForSyncView;

    private long mAccountId;
    private long mMailboxId;
    private long mMessageId;
    private String mAccountUuid;

    private MailboxFinder mInboxFinder;

    /**
     * Launch this activity.  Note:  It's assumed that this activity is only called as a means to
     * 'reset' the UI state; Because of this, it is always launched with FLAG_ACTIVITY_CLEAR_TOP,
     * which will drop any other activities on the stack (e.g. AccountFolderList or MessageList).
     */
    public static void actionStart(Activity fromActivity) {
        Intent i = IntentUtilities.createRestartAppIntent(fromActivity, Welcome.class);
        fromActivity.startActivity(i);
    }

    /**
     * Create an Intent to open email activity. If <code>accountId</code> is not -1, the
     * specified account will be automatically be opened when the activity starts.
     */
    public static Intent createOpenAccountInboxIntent(Context context, long accountId) {
        final Uri.Builder b = IntentUtilities.createActivityIntentUrlBuilder(
                VIEW_MAILBOX_INTENT_URL_PATH);
        IntentUtilities.setAccountId(b, accountId);
        return IntentUtilities.createRestartAppIntent(b.build());
    }

    /**
     * Create an Intent to open a message.
     */
    public static Intent createOpenMessageIntent(Context context, long accountId,
            long mailboxId, long messageId) {
        final Uri.Builder b = IntentUtilities.createActivityIntentUrlBuilder(
                VIEW_MAILBOX_INTENT_URL_PATH);
        IntentUtilities.setAccountId(b, accountId);
        IntentUtilities.setMailboxId(b, mailboxId);
        IntentUtilities.setMessageId(b, messageId);
        return IntentUtilities.createRestartAppIntent(b.build());
    }

    /**
     * Open account's inbox.
     */
    public static void actionOpenAccountInbox(Activity fromActivity, long accountId) {
        fromActivity.startActivity(createOpenAccountInboxIntent(fromActivity, accountId));
    }

    /**
     * Create an {@link Intent} for account shortcuts.  The returned intent stores the account's
     * UUID rather than the account ID, which will be changed after account restore.
     */
    public static Intent createAccountShortcutIntent(Context context, String uuid, long mailboxId) {
        final Uri.Builder b = IntentUtilities.createActivityIntentUrlBuilder(
                VIEW_MAILBOX_INTENT_URL_PATH);
        IntentUtilities.setAccountUuid(b, uuid);
        IntentUtilities.setMailboxId(b, mailboxId);
        return IntentUtilities.createRestartAppIntent(b.build());
    }

    /**
     * If the {@link #EXTRA_DEBUG_PANE_MODE} extra is "1" or "2", return 1 or 2 respectively.
     * Otherwise return 0.
     *
     * @see UiUtilities#setDebugPaneMode(int)
     * @see UiUtilities#useTwoPane(Context)
     */
    private static int getDebugPaneMode(Intent i) {
        Bundle extras = i.getExtras();
        if (extras != null) {
            String s = extras.getString(EXTRA_DEBUG_PANE_MODE);
            if ("1".equals(s)) {
                return 1;
            } else if ("2".equals(s)) {
                return 2;
            }
        }
        return 0;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        ActivityHelper.debugSetWindowFlags(this);

        // Because the app could be reloaded (for debugging, etc.), we need to make sure that
        // ExchangeService gets a chance to start.  There is no harm to starting it if it has
        // already been started
        // When the service starts, it reconciles EAS accounts.
        // TODO More completely separate ExchangeService from Email app
        EmailServiceUtils.startExchangeService(this);

        // Extract parameters from the intent.
        final Intent intent = getIntent();
        mAccountId = IntentUtilities.getAccountIdFromIntent(intent);
        mMailboxId = IntentUtilities.getMailboxIdFromIntent(intent);
        mMessageId = IntentUtilities.getMessageIdFromIntent(intent);
        mAccountUuid = IntentUtilities.getAccountUuidFromIntent(intent);
        UiUtilities.setDebugPaneMode(getDebugPaneMode(intent));

        // Reconcile POP/IMAP accounts.  EAS accounts are taken care of by ExchangeService.
        EmailAsyncTask.runAsyncParallel(new Runnable() {
            @Override
            public void run() {
                // Reconciling can be heavy - so do it in the background.
                if (MailService.hasMismatchInPopImapAccounts(Welcome.this)) {
                    MailService.reconcilePopImapAccountsSync(Welcome.this);
                }
                Welcome.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        resolveAccount();
                    }});
            }
        });

        // Reset the "accounts changed" notification, now that we're here
        Email.setNotifyUiAccountsChanged(false);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Only create the menu if we had to stop and show a loading spinner - otherwise
        // this is a transient activity with no UI.
        if (mInboxFinder == null) {
            return super.onCreateOptionsMenu(menu);
        }

        getMenuInflater().inflate(R.menu.welcome, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.account_settings) {
            AccountSettings.actionSettings(this, mAccountId);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStop() {
        // Cancel all running tasks.
        // (If it's stopping for configuration changes, we just re-do everything on the new
        // instance)
        stopInboxLookup();
        mTaskTracker.cancellAllInterrupt();

        super.onStop();

        if (!isChangingConfigurations()) {
            // This means the user opened some other app.
            // Just close self and not launch EmailActivity.
            if (Email.DEBUG && Logging.DEBUG_LIFECYCLE) {
                Log.d(Logging.LOG_TAG, "Welcome: Closing self...");
            }
            finish();
        }
    }

    /**
     * {@inheritDoc}
     *
     * When launching an activity from {@link Welcome}, we always want to set
     * {@link Intent#FLAG_ACTIVITY_FORWARD_RESULT}.
     */
    @Override
    public void startActivity(Intent intent) {
        intent.setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        super.startActivity(intent);
    }

    /**
     * Stop inbox lookup.  This MSUT be called on the UI thread.
     */
    private void stopInboxLookup() {
        if (mInboxFinder != null) {
            mInboxFinder.cancel();
            mInboxFinder = null;
        }
    }

    /**
     * Start inbox lookup.  This MSUT be called on the UI thread.
     */
    private void startInboxLookup() {
        Log.i(Logging.LOG_TAG, "Inbox not found.  Starting mailbox finder...");
        stopInboxLookup(); // Stop if already running -- it shouldn't be but just in case.
        mInboxFinder = new MailboxFinder(this, mAccountId, Mailbox.TYPE_INBOX,
                mMailboxFinderCallback);
        mInboxFinder.startLookup();

        // Show "your email will appear shortly" message.
        mWaitingForSyncView = LayoutInflater.from(this).inflate(
                R.layout.waiting_for_sync_message, null);
        addContentView(mWaitingForSyncView, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        invalidateOptionsMenu();
    }

    /**
     * Determine which account to open with the given account ID and UUID.
     *
     * @return ID of the account to use.
     */
    @VisibleForTesting
    static long resolveAccountId(Context context, long inputAccountId, String inputUuid) {
        final long accountId;

        if (!TextUtils.isEmpty(inputUuid)) {
            // If a UUID is specified, try to use it.
            // If the UUID is invalid, accountId will be NO_ACCOUNT.
            accountId = Account.getAccountIdFromUuid(context, inputUuid);

        } else if (inputAccountId != Account.NO_ACCOUNT) {
            // If a valid account ID is specified, just use it.
            if (inputAccountId == Account.ACCOUNT_ID_COMBINED_VIEW
                    || Account.isValidId(context, inputAccountId)) {
                accountId = inputAccountId;
            } else {
                accountId = Account.NO_ACCOUNT;
            }
        } else {
            // Neither an accountID or a UUID is specified.
            // Use the last account used, falling back to the default.
            long lastUsedId = Preferences.getPreferences(context).getLastUsedAccountId();
            if (lastUsedId != Account.NO_ACCOUNT) {
                if (!Account.isValidId(context, lastUsedId)) {
                    // The last account that was used has since been deleted.
                    lastUsedId = Account.NO_ACCOUNT;
                    Preferences.getPreferences(context).setLastUsedAccountId(Account.NO_ACCOUNT);
                }
            }
            accountId = (lastUsedId == Account.NO_ACCOUNT)
                    ? Account.getDefaultAccountId(context)
                    : lastUsedId;
        }
        if (accountId != Account.NO_ACCOUNT) {
            // Okay, the given account is valid.
            return accountId;
        } else {
            // No, it's invalid.  Show the warning toast and use the default.
            Utility.showToast(context, R.string.toast_account_not_found);
            return Account.getDefaultAccountId(context);
        }
    }

    /**
     * Determine which account to use according to the number of accounts already set up,
     * {@link #mAccountId} and {@link #mAccountUuid}.
     *
     * <pre>
     * 1. If there's no account configured, start account setup.
     * 2. Otherwise detemine which account to open with {@link #resolveAccountId} and
     *   2a. If the account doesn't have inbox yet, start inbox finder.
     *   2b. Otherwise open the main activity.
     * </pre>
     */
    private void resolveAccount() {
        final int numAccount = EmailContent.count(this, Account.CONTENT_URI);
        if (numAccount == 0) {
            AccountSetupBasics.actionNewAccount(this);
            finish();
            return;
        } else {
            mAccountId = resolveAccountId(this, mAccountId, mAccountUuid);
            if (Account.isNormalAccount(mAccountId) &&
                    Mailbox.findMailboxOfType(this, mAccountId, Mailbox.TYPE_INBOX)
                            == Mailbox.NO_MAILBOX) {
                startInboxLookup();
                return;
            }
        }
        startEmailActivity();
    }

    /**
     * Start {@link EmailActivity} using {@link #mAccountId}, {@link #mMailboxId} and
     * {@link #mMessageId}.
     */
    private void startEmailActivity() {
        final Intent i;
        if (mMessageId != Message.NO_MESSAGE) {
            i = EmailActivity.createOpenMessageIntent(this, mAccountId, mMailboxId, mMessageId);
        } else if (mMailboxId != Mailbox.NO_MAILBOX) {
            i = EmailActivity.createOpenMailboxIntent(this, mAccountId, mMailboxId);
        } else {
            i = EmailActivity.createOpenAccountIntent(this, mAccountId);
        }
        startActivity(i);
        finish();
    }

    private final MailboxFinder.Callback mMailboxFinderCallback = new MailboxFinder.Callback() {
        // This MUST be called from callback methods.
        private void cleanUp() {
            mInboxFinder = null;
        }

        @Override
        public void onAccountNotFound() {
            cleanUp();
            // Account removed?  Clear the IDs and restart the task.  Which will result in either
            // a) show account setup if there's really no accounts  or b) open the default account.

            mAccountId = Account.NO_ACCOUNT;
            mMailboxId = Mailbox.NO_MAILBOX;
            mMessageId = Message.NO_MESSAGE;
            mAccountUuid = null;

            // Restart the account resolution.
            resolveAccount();
        }

        @Override
        public void onMailboxNotFound(long accountId) {
            // Just do the same thing as "account not found".
            onAccountNotFound();
        }

        @Override
        public void onAccountSecurityHold(long accountId) {
            cleanUp();

            ActivityHelper.showSecurityHoldDialog(Welcome.this, accountId);
            finish();
        }

        @Override
        public void onMailboxFound(long accountId, long mailboxId) {
            cleanUp();

            // Okay the account has Inbox now.  Start the main activity.
            startEmailActivity();
        }
    };
}
