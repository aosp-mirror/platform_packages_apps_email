/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
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

package com.android.email.provider;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;

import com.android.email.R;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.mail.providers.Folder;
import com.android.mail.utils.LogUtils;

public class FolderPickerActivity extends Activity implements FolderPickerCallback {
    private static final String TAG = "FolderPickerActivity";
    public static final String MAILBOX_TYPE_EXTRA = "mailbox_type";

    private long mAccountId;
    private int mMailboxType;
    private AccountObserver mAccountObserver;
    private String mAccountName;
    private boolean mInSetup = true;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        Intent i = getIntent();
        Uri uri = i.getData();
        int headerId;
        final com.android.mail.providers.Account uiAccount;
        // If we've gotten a Uri, then this is a call from the UI in response to setupIntentUri
        // in an account (meaning the account requires setup)
        if (uri != null) {
            String id = uri.getQueryParameter("account");
            if (id == null) {
                LogUtils.w(TAG, "No account # in Uri?");
                finish();
                return;
            }
            try {
                mAccountId = Long.parseLong(id);
            } catch (NumberFormatException e) {
                LogUtils.w(TAG, "Invalid account # in Uri?");
                finish();
                return;
            }
            // We act a bit differently if we're coming to set up the trash after account creation
            mInSetup = !i.hasExtra(MAILBOX_TYPE_EXTRA);
            mMailboxType = i.getIntExtra(MAILBOX_TYPE_EXTRA, Mailbox.TYPE_TRASH);
            long trashMailboxId = Mailbox.findMailboxOfType(this, mAccountId, Mailbox.TYPE_TRASH);
            // If we already have a trash mailbox, we're done (if in setup; a race?)
            if (trashMailboxId != Mailbox.NO_MAILBOX && mInSetup) {
                LogUtils.w(TAG, "Trash folder already exists");
                finish();
                return;
            }
            Account account = Account.restoreAccountWithId(this, mAccountId);
            if (account == null) {
                LogUtils.w(TAG, "No account?");
                finish();
            } else {
                mAccountName = account.mDisplayName;
                // Two possibilities here; either we have our folder list, or we don't
                if ((account.mFlags & Account.FLAGS_INITIAL_FOLDER_LIST_LOADED) != 0) {
                    // If we've got them, just start up the picker dialog
                    startPickerForAccount();
                } else {
                    // Otherwise, wait for the folders to show up
                    waitForFolders();
                }
            }
        } else {
            // In this case, we're coming from Settings
            uiAccount = i.getParcelableExtra(EmailProvider.PICKER_UI_ACCOUNT);
            mAccountName = uiAccount.getDisplayName();
            mAccountId = Long.parseLong(uiAccount.uri.getLastPathSegment());
            mMailboxType = i.getIntExtra(EmailProvider.PICKER_MAILBOX_TYPE, -1);
            headerId = i.getIntExtra(EmailProvider.PICKER_HEADER_ID, 0);
            if (headerId == 0) {
                finish();
                return;
            }
            startPicker(uiAccount.folderListUri, headerId);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Clean up
        if (mAccountObserver != null) {
            getContentResolver().unregisterContentObserver(mAccountObserver);
            mAccountObserver = null;
        }
        if (mWaitingForFoldersDialog != null) {
            mWaitingForFoldersDialog.dismiss();
            mWaitingForFoldersDialog = null;
        }
    }

    private class AccountObserver extends ContentObserver {
        private final Context mContext;

        public AccountObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
        }

        @Override
        public void onChange(boolean selfChange) {
            Account account = Account.restoreAccountWithId(mContext, mAccountId);
            // All we care about is whether the folder list is now loaded
            if ((account.mFlags & Account.FLAGS_INITIAL_FOLDER_LIST_LOADED) != 0 &&
                    (mAccountObserver != null)) {
                mContext.getContentResolver().unregisterContentObserver(mAccountObserver);
                mAccountObserver = null;
                // Bring down the ProgressDialog and show the picker
                if (mWaitingForFoldersDialog != null) {
                    mWaitingForFoldersDialog.dismiss();
                    mWaitingForFoldersDialog = null;
                }
                startPickerForAccount();
            }
        }
    }

    private ProgressDialog mWaitingForFoldersDialog;

    private void waitForFolders() {
        /// Show "Waiting for folders..." dialog
        mWaitingForFoldersDialog = new ProgressDialog(this);
        mWaitingForFoldersDialog.setIndeterminate(true);
        mWaitingForFoldersDialog.setMessage(getString(R.string.account_waiting_for_folders_msg));
        mWaitingForFoldersDialog.show();

        // Listen for account changes
        mAccountObserver = new AccountObserver(this, new Handler());
        Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, mAccountId);
        getContentResolver().registerContentObserver(uri, false, mAccountObserver);
    }

    private void startPickerForAccount() {
        int headerId = R.string.trash_folder_selection_title;
        Uri uri = Uri.parse("content://" + EmailContent.AUTHORITY + "/uifullfolders/" + mAccountId);
        startPicker(uri, headerId);
    }

    private void startPicker(Uri uri, int headerId) {
        String header = getString(headerId, mAccountName);
        FolderPickerDialog dialog =
                new FolderPickerDialog(this, uri, this, header, !mInSetup);
        dialog.show();
    }

    @Override
    public void select(Folder folder) {
        String folderId = folder.folderUri.fullUri.getLastPathSegment();
        Long id = Long.parseLong(folderId);
        ContentValues values = new ContentValues();

        // If we already have a mailbox of this type, change it back to generic mail type
        Mailbox ofType = Mailbox.restoreMailboxOfType(this, mAccountId, mMailboxType);
        if (ofType != null) {
            values.put(MailboxColumns.TYPE, Mailbox.TYPE_MAIL);
            getContentResolver().update(
                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, ofType.mId), values,
                    null, null);
        }

        // Change this mailbox to be of the desired type
        Mailbox mailbox = Mailbox.restoreMailboxWithId(this, id);
        if (mailbox != null) {
            values.put(MailboxColumns.TYPE, mMailboxType);
            getContentResolver().update(
                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailbox.mId), values,
                    null, null);
            values.clear();
            // Touch the account so that UI won't bring up this picker again
            Account account = Account.restoreAccountWithId(this, mAccountId);
            values.put(AccountColumns.FLAGS, account.mFlags);
            getContentResolver().update(
                    ContentUris.withAppendedId(Account.CONTENT_URI, account.mId), values,
                    null, null);
        }
        finish();
    }

    @Override
    public void cancel() {
        finish();
    }
}
