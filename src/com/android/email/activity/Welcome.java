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

import com.android.email.AccountBackupRestore;
import com.android.email.Email;
import com.android.email.ExchangeUtils;
import com.android.email.activity.setup.AccountSetupBasics;
import com.android.email.service.MailService;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Mailbox;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;

/**
 * The Welcome activity initializes the application and decides what Activity
 * the user should start with.
 *
 * This class knows which activity should be launched under the current configuration (screen size)
 * and the number of accounts configured.  So if you want to open an account or a mailbox,
 * you should alawys do so via its static methods, such as {@link #actionOpenAccountInbox}.
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
            -e DEBUG_PANE_MODE 2 \

     */

    /**
     * Extra for debugging.  Set 1 to force one-pane.  Set 2 to force two-pane.
     */
    private static final String EXTRA_DEBUG_PANE_MODE = "DEBUG_PANE_MODE";

    private static final String VIEW_MAILBOX_INTENT_URL_PATH = "/view/mailbox";

    /**
     * @return true if the two-pane activity should be used on the current configuration.
     */
    public static boolean useTwoPane(Context context) {
        final int screenLayout = context.getResources().getConfiguration().screenLayout;
        return (screenLayout & Configuration.SCREENLAYOUT_SIZE_XLARGE) != 0;
    }

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
     * Parse the {@link #EXTRA_DEBUG_PANE_MODE} extra and return 1 or 2, if it's set to "1" or "2".
     * Return 0 otherwise.
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

        // Reset the "accounts changed" notification, now that we're here
        Email.setNotifyUiAccountsChanged(false);

        // Restore accounts, if it has not happened already
        // NOTE:  This is blocking, which it should not be (in the UI thread)
        // We're going to live with this for the short term and replace with something
        // smarter.  Long-term fix:  Move this, and most of the code below, to an AsyncTask
        // and do the DB work in a thread.  Then post handler to finish() as appropriate.
        AccountBackupRestore.restoreAccountsIfNeeded(this);

        // Because the app could be reloaded (for debugging, etc.), we need to make sure that
        // ExchangeService gets a chance to start.  There is no harm to starting it if it has
        // already been started
        // When the service starts, it reconciles EAS accounts.
        // TODO More completely separate ExchangeService from Email app
        ExchangeUtils.startExchangeService(this);

        final Intent intent = getIntent();
        final long accountId = IntentUtilities.getAccountIdFromIntent(intent);
        final long mailboxId = IntentUtilities.getMailboxIdFromIntent(intent);
        final long messageId = IntentUtilities.getMessageIdFromIntent(intent);
        final int debugPaneMode = getDebugPaneMode(getIntent());
        new MainActivityLauncher(this, accountId, mailboxId, messageId, debugPaneMode).execute();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    /**
     * Open an account with the Activity appropriate to the current configuration.
     * If there's no accounts set up, open the "add account" screen.
     *
     * if {@code account} is -1, open the default account.
     */
    private static class MainActivityLauncher extends AsyncTask<Void, Void, Void> {
        private final Activity mFromActivity;
        private final int mDebugPaneMode;
        private final long mAccountId;
        private final long mMailboxId;
        private final long mMessageId;

        public MainActivityLauncher(Activity fromActivity, long accountId, long mailboxId,
                long messageId, int debugPaneMode) {
            mFromActivity = fromActivity;
            mAccountId = accountId;
            mMailboxId = mailboxId;
            mMessageId = messageId;
            mDebugPaneMode = debugPaneMode;
        }

        private boolean isMailboxSelected() {
            return mMailboxId != -1;
        }

        private boolean isMessageSelected() {
            return mMessageId != -1;
        }

        @Override
        protected Void doInBackground(Void... params) {
            // Reconcile POP/IMAP accounts.  EAS accounts are taken care of by ExchangeService.
            MailService.reconcilePopImapAccountsSync(mFromActivity);

            final int numAccount =
                    EmailContent.count(mFromActivity, EmailContent.Account.CONTENT_URI);
            if (numAccount == 0) {
                AccountSetupBasics.actionNewAccount(mFromActivity);
            } else {
                long accountId = mAccountId;
                if (accountId == -1 || !Account.isValidId(mFromActivity, accountId)) {
                    accountId = EmailContent.Account.getDefaultAccountId(mFromActivity);
                }

                final boolean useTwoPane = (mDebugPaneMode == 2)
                        || (useTwoPane(mFromActivity) && mDebugPaneMode == 0);

                if (useTwoPane) {
                    if (isMessageSelected()) {
                        MessageListXL.actionOpenMessage(mFromActivity, accountId, mMailboxId,
                                mMessageId);
                    } else if (isMailboxSelected()) {
                        MessageListXL.actionOpenMailbox(mFromActivity, accountId, mMailboxId);
                    } else {
                        MessageListXL.actionOpenAccount(mFromActivity, accountId);
                    }
                } else {
                    if (isMessageSelected()) {
                        MessageView.actionView(mFromActivity, mMessageId, mMailboxId);
                    } else if (isMailboxSelected()) {
                        MessageList.actionHandleMailbox(mFromActivity, mMailboxId);
                    } else {
                        MessageList.actionHandleAccount(
                                mFromActivity, accountId, Mailbox.TYPE_INBOX);
                    }
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mFromActivity.finish();
        }
    }
}
