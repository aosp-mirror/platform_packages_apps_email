/*
 * Copyright (C) 2008-2009 Marc Blank
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

package com.android.exchange.adapter;

import com.android.email.provider.EmailProvider;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.exchange.Eas;
import com.android.exchange.EasSyncService;
import com.android.exchange.MockParserStream;
import com.android.exchange.SyncManager;

import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parse the result of a FolderSync command
 *
 * Handles the addition, deletion, and changes to folders in the user's Exchange account.
 **/

public class FolderSyncParser extends AbstractSyncParser {

    public static final String TAG = "FolderSyncParser";

    // These are defined by the EAS protocol
    public static final int USER_FOLDER_TYPE = 1;
    public static final int INBOX_TYPE = 2;
    public static final int DRAFTS_TYPE = 3;
    public static final int DELETED_TYPE = 4;
    public static final int SENT_TYPE = 5;
    public static final int OUTBOX_TYPE = 6;
    public static final int TASKS_TYPE = 7;
    public static final int CALENDAR_TYPE = 8;
    public static final int CONTACTS_TYPE = 9;
    public static final int NOTES_TYPE = 10;
    public static final int JOURNAL_TYPE = 11;
    public static final int USER_MAILBOX_TYPE = 12;

    public static final List<Integer> mValidFolderTypes = Arrays.asList(INBOX_TYPE, DRAFTS_TYPE,
            DELETED_TYPE, SENT_TYPE, OUTBOX_TYPE, USER_MAILBOX_TYPE, CALENDAR_TYPE, CONTACTS_TYPE);

    public static final String ALL_BUT_ACCOUNT_MAILBOX = MailboxColumns.ACCOUNT_KEY + "=? and " +
        MailboxColumns.TYPE + "!=" + Mailbox.TYPE_EAS_ACCOUNT_MAILBOX;

   private static final String WHERE_SERVER_ID_AND_ACCOUNT = MailboxColumns.SERVER_ID + "=? and " +
        MailboxColumns.ACCOUNT_KEY + "=?";

    private static final String WHERE_DISPLAY_NAME_AND_ACCOUNT = MailboxColumns.DISPLAY_NAME +
        "=? and " + MailboxColumns.ACCOUNT_KEY + "=?";

    private static final String WHERE_PARENT_SERVER_ID_AND_ACCOUNT =
        MailboxColumns.PARENT_SERVER_ID +"=? and " + MailboxColumns.ACCOUNT_KEY + "=?";

    private static final String[] MAILBOX_ID_COLUMNS_PROJECTION =
        new String[] {MailboxColumns.ID, MailboxColumns.SERVER_ID};

    private long mAccountId;
    private String mAccountIdAsString;
    private MockParserStream mMock = null;
    private String[] mBindArguments = new String[2];

    public FolderSyncParser(InputStream in, EasSyncService service) throws IOException {
        super(in, service);
        mAccountId = mAccount.mId;
        mAccountIdAsString = Long.toString(mAccountId);
        if (in instanceof MockParserStream) {
            mMock = (MockParserStream)in;
        }
    }

    @Override
    public boolean parse() throws IOException {
        int status;
        boolean res = false;
        if (nextTag(START_DOCUMENT) != Tags.FOLDER_FOLDER_SYNC)
            throw new IOException();
        while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
            if (tag == Tags.FOLDER_STATUS) {
                status = getValueInt();
                if (status != Eas.FOLDER_STATUS_OK) {
                    mService.errorLog("FolderSync failed: " + status);
                    if (status == Eas.FOLDER_STATUS_INVALID_KEY) {
                        mAccount.mSyncKey = "0";
                        mService.errorLog("Bad sync key; RESET and delete all folders");
                        mContentResolver.delete(Mailbox.CONTENT_URI, ALL_BUT_ACCOUNT_MAILBOX,
                                new String[] {Long.toString(mAccountId)});
                        // Stop existing syncs and reconstruct _main
                        SyncManager.folderListReloaded(mAccountId);
                        res = true;
                    } else {
                        // Other errors are at the server, so let's throw an error that will
                        // cause this sync to be retried at a later time
                        mService.errorLog("Throwing IOException; will retry later");
                        throw new IOException();
                    }
                }
            } else if (tag == Tags.FOLDER_SYNC_KEY) {
                mAccount.mSyncKey = getValue();
                userLog("New Account SyncKey: ", mAccount.mSyncKey);
            } else if (tag == Tags.FOLDER_CHANGES) {
                changesParser();
            } else
                skipTag();
        }
        synchronized (mService.getSynchronizer()) {
            if (!mService.isStopped()) {
                ContentValues cv = new ContentValues();
                cv.put(AccountColumns.SYNC_KEY, mAccount.mSyncKey);
                mAccount.update(mContext, cv);
                userLog("Leaving FolderSyncParser with Account syncKey=", mAccount.mSyncKey);
            }
        }
        return res;
    }

    private Cursor getServerIdCursor(String serverId) {
        mBindArguments[0] = serverId;
        mBindArguments[1] = mAccountIdAsString;
        return mContentResolver.query(Mailbox.CONTENT_URI, new String[] {MailboxColumns.ID},
                WHERE_SERVER_ID_AND_ACCOUNT, mBindArguments, null);
    }

    public void deleteParser(ArrayList<ContentProviderOperation> ops) throws IOException {
        while (nextTag(Tags.SYNC_DELETE) != END) {
            switch (tag) {
                case Tags.FOLDER_SERVER_ID:
                    String serverId = getValue();
                    // Find the mailbox in this account with the given serverId
                    Cursor c = getServerIdCursor(serverId);
                    try {
                        if (c.moveToFirst()) {
                            userLog("Deleting ", serverId);
                            ops.add(ContentProviderOperation.newDelete(
                                    ContentUris.withAppendedId(Mailbox.CONTENT_URI,
                                            c.getLong(0))).build());
                        }
                    } finally {
                        c.close();
                    }
                    break;
                default:
                    skipTag();
            }
        }
    }

    public void addParser(ArrayList<ContentProviderOperation> ops) throws IOException {
        String name = null;
        String serverId = null;
        String parentId = null;
        int type = 0;

        while (nextTag(Tags.FOLDER_ADD) != END) {
            switch (tag) {
                case Tags.FOLDER_DISPLAY_NAME: {
                    name = getValue();
                    break;
                }
                case Tags.FOLDER_TYPE: {
                    type = getValueInt();
                    break;
                }
                case Tags.FOLDER_PARENT_ID: {
                    parentId = getValue();
                    break;
                }
                case Tags.FOLDER_SERVER_ID: {
                    serverId = getValue();
                    break;
                }
                default:
                    skipTag();
            }
        }
        if (mValidFolderTypes.contains(type)) {
            Mailbox m = new Mailbox();
            m.mDisplayName = name;
            m.mServerId = serverId;
            m.mAccountKey = mAccountId;
            m.mType = Mailbox.TYPE_MAIL;
            m.mSyncInterval = Mailbox.CHECK_INTERVAL_NEVER;
            switch (type) {
                case INBOX_TYPE:
                    m.mSyncInterval = Mailbox.CHECK_INTERVAL_PUSH;
                    m.mType = Mailbox.TYPE_INBOX;
                    break;
                case OUTBOX_TYPE:
                    m.mSyncInterval = Mailbox.CHECK_INTERVAL_NEVER;
                    // TYPE_OUTBOX mailboxes are known by SyncManager to sync whenever they aren't
                    // empty.  The value of mSyncFrequency is ignored for this kind of mailbox.
                    m.mType = Mailbox.TYPE_OUTBOX;
                    break;
                case SENT_TYPE:
                    m.mType = Mailbox.TYPE_SENT;
                    break;
                case DRAFTS_TYPE:
                    m.mType = Mailbox.TYPE_DRAFTS;
                    break;
                case DELETED_TYPE:
                    m.mType = Mailbox.TYPE_TRASH;
                    break;
                case CALENDAR_TYPE:
                    m.mType = Mailbox.TYPE_CALENDAR;
                    // TODO This could be push, depending on settings
                    // For now, no sync, since it's not yet implemented
                    break;
                case CONTACTS_TYPE:
                    m.mType = Mailbox.TYPE_CONTACTS;
                    // TODO Frequency below should depend on settings
                    //m.mSyncInterval = Mailbox.CHECK_INTERVAL_PUSH;
                    break;
            }

            // Make boxes like Contacts and Calendar invisible in the folder list
            m.mFlagVisible = (m.mType < Mailbox.TYPE_NOT_EMAIL);

            if (!parentId.equals("0")) {
                m.mParentServerId = parentId;
            }

            userLog("Adding mailbox: ", m.mDisplayName);
            ops.add(ContentProviderOperation
                    .newInsert(Mailbox.CONTENT_URI).withValues(m.toContentValues()).build());
        }

        return;
    }

    public void changesParser() throws IOException {
        // Keep track of new boxes, deleted boxes, updated boxes
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

        while (nextTag(Tags.FOLDER_CHANGES) != END) {
            // TODO Handle FOLDER_CHANGE and FOLDER_DELETE
            if (tag == Tags.FOLDER_ADD) {
                addParser(ops);
            } else if (tag == Tags.FOLDER_DELETE) {
                deleteParser(ops);
            } else if (tag == Tags.FOLDER_COUNT) {
                getValueInt();
            } else
                skipTag();
        }

        // The mock stream is used for junit tests, so that the parsing code can be tested
        // separately from the provider code.
        // TODO Change tests to not require this; remove references to the mock stream
        if (mMock != null) {
            mMock.setResult(null);
            return;
        }

        // Create the new mailboxes in a single batch operation
        // Don't save any data if the service has been stopped
        synchronized (mService.getSynchronizer()) {
            if (!ops.isEmpty() && !mService.isStopped()) {
                userLog("Applying ", ops.size(), " mailbox operations.");

                // Then, we create an update for the account (most importantly, updating the syncKey)
                ops.add(ContentProviderOperation.newUpdate(
                        ContentUris.withAppendedId(Account.CONTENT_URI, mAccountId)).withValues(
                                mAccount.toContentValues()).build());

                // Finally, we execute the batch
                try {
                    mContentResolver.applyBatch(EmailProvider.EMAIL_AUTHORITY, ops);
                    userLog("New Account SyncKey: ", mAccount.mSyncKey);
                } catch (RemoteException e) {
                    // There is nothing to be done here; fail by returning null
                } catch (OperationApplicationException e) {
                    // There is nothing to be done here; fail by returning null
                }

                // Look for sync issues and its children and delete them
                // I'm not aware of any other way to deal with this properly
                mBindArguments[0] = "Sync Issues";
                mBindArguments[1] = mAccountIdAsString;
                Cursor c = mContentResolver.query(Mailbox.CONTENT_URI,
                        MAILBOX_ID_COLUMNS_PROJECTION, WHERE_DISPLAY_NAME_AND_ACCOUNT,
                        mBindArguments, null);
                String parentServerId = null;
                long id = 0;
                try {
                    if (c.moveToFirst()) {
                        id = c.getLong(0);
                        parentServerId = c.getString(1);
                    }
                } finally {
                    c.close();
                }
                if (parentServerId != null) {
                    mContentResolver.delete(ContentUris.withAppendedId(Mailbox.CONTENT_URI, id),
                            null, null);
                    mBindArguments[0] = parentServerId;
                    mContentResolver.delete(Mailbox.CONTENT_URI, WHERE_PARENT_SERVER_ID_AND_ACCOUNT,
                            mBindArguments);
                }
            }
        }
    }

    @Override
    public void commandsParser() throws IOException {
    }

    @Override
    public void wipe() {
    }

}
