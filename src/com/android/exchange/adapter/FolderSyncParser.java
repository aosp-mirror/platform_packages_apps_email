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

import com.android.email.Utility;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailProvider;
import com.android.exchange.Eas;
import com.android.exchange.ExchangeService;
import com.android.exchange.MockParserStream;

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
import java.util.HashMap;
import java.util.List;

/**
 * Parse the result of a FolderSync command
 *
 * Handles the addition, deletion, and changes to folders in the user's Exchange account.
 **/

public class FolderSyncParser extends AbstractSyncParser {

    public static final String TAG = "FolderSyncParser";

    // These are defined by the EAS protocol
    public static final int USER_GENERIC_TYPE = 1;
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

    // Maximum user mailboxes we will handle (to prevent binder overload when sending to provider)
    public final static int MAX_USER_MAILBOXES = 1000;

    // EAS types that we are willing to consider valid folders for EAS sync
    public static final List<Integer> VALID_EAS_FOLDER_TYPES = Arrays.asList(INBOX_TYPE,
            DRAFTS_TYPE, DELETED_TYPE, SENT_TYPE, OUTBOX_TYPE, USER_MAILBOX_TYPE, CALENDAR_TYPE,
            CONTACTS_TYPE, USER_GENERIC_TYPE);

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
    private ArrayList<ContentProviderOperation> mOperations =
        new ArrayList<ContentProviderOperation>();

    public FolderSyncParser(InputStream in, AbstractSyncAdapter adapter) throws IOException {
        super(in, adapter);
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
        boolean resetFolders = false;
        if (nextTag(START_DOCUMENT) != Tags.FOLDER_FOLDER_SYNC)
            throw new EasParserException();
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
                        ExchangeService.stopNonAccountMailboxSyncsForAccount(mAccountId);
                        res = true;
                        resetFolders = true;
                    } else {
                        // Other errors are at the server, so let's throw an error that will
                        // cause this sync to be retried at a later time
                        mService.errorLog("Throwing IOException; will retry later");
                        throw new EasParserException("Folder status error");
                    }
                }
            } else if (tag == Tags.FOLDER_SYNC_KEY) {
                mAccount.mSyncKey = getValue();
                userLog("New Account SyncKey: ", mAccount.mSyncKey);
            } else if (tag == Tags.FOLDER_CHANGES) {
                changesParser(mOperations);
            } else
                skipTag();
        }
        synchronized (mService.getSynchronizer()) {
            if (!mService.isStopped() || resetFolders) {
                commit();
                userLog("Leaving FolderSyncParser with Account syncKey=", mAccount.mSyncKey);
            }
        }
        return res;
    }

    private Cursor getServerIdCursor(String serverId) {
        mBindArguments[0] = serverId;
        mBindArguments[1] = mAccountIdAsString;
        return mContentResolver.query(Mailbox.CONTENT_URI, EmailContent.ID_PROJECTION,
                WHERE_SERVER_ID_AND_ACCOUNT, mBindArguments, null);
    }

    public void deleteParser(ArrayList<ContentProviderOperation> ops) throws IOException {
        while (nextTag(Tags.FOLDER_DELETE) != END) {
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
                            AttachmentProvider.deleteAllMailboxAttachmentFiles(mContext,
                                    mAccountId, mMailbox.mId);
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

    public Mailbox addParser() throws IOException {
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

        if (VALID_EAS_FOLDER_TYPES.contains(type)) {
            Mailbox mailbox = new Mailbox();
            mailbox.mDisplayName = name;
            mailbox.mServerId = serverId;
            mailbox.mAccountKey = mAccountId;
            mailbox.mType = Mailbox.TYPE_MAIL;
            // Note that all mailboxes default to checking "never" (i.e. manual sync only)
            // We set specific intervals for inbox, contacts, and (eventually) calendar
            mailbox.mSyncInterval = Mailbox.CHECK_INTERVAL_NEVER;
            switch (type) {
                case INBOX_TYPE:
                    mailbox.mType = Mailbox.TYPE_INBOX;
                    mailbox.mSyncInterval = mAccount.mSyncInterval;
                    break;
                case CONTACTS_TYPE:
                    mailbox.mType = Mailbox.TYPE_CONTACTS;
                    mailbox.mSyncInterval = mAccount.mSyncInterval;
                    break;
                case OUTBOX_TYPE:
                    // TYPE_OUTBOX mailboxes are known by ExchangeService to sync whenever they
                    // aren't empty.  The value of mSyncFrequency is ignored for this kind of
                    // mailbox.
                    mailbox.mType = Mailbox.TYPE_OUTBOX;
                    break;
                case SENT_TYPE:
                    mailbox.mType = Mailbox.TYPE_SENT;
                    break;
                case DRAFTS_TYPE:
                    mailbox.mType = Mailbox.TYPE_DRAFTS;
                    break;
                case DELETED_TYPE:
                    mailbox.mType = Mailbox.TYPE_TRASH;
                    break;
                case CALENDAR_TYPE:
                    mailbox.mType = Mailbox.TYPE_CALENDAR;
                    mailbox.mSyncInterval = mAccount.mSyncInterval;
                    break;
                case USER_GENERIC_TYPE:
                    mailbox.mType = Mailbox.TYPE_UNKNOWN;
                    break;
            }

            // Make boxes like Contacts and Calendar invisible in the folder list
            mailbox.mFlagVisible = (mailbox.mType < Mailbox.TYPE_NOT_EMAIL);

            if (!parentId.equals("0")) {
                mailbox.mParentServerId = parentId;
            }

            return mailbox;
        }
        return null;
    }

    /**
     * Determine whether a given mailbox holds mail, rather than other data.  We do this by first
     * checking the type of the mailbox (if it's a known good type, great; if it's a known bad
     * type, return false).  If it's unknown, we check the parent, first by trying to find it in
     * the current set of newly synced items, and then by looking it up in EmailProvider.  If
     * we can find the parent, we use the same rules to determine if it holds mail; if it does,
     * then its children do as well, so that's a go.
     *
     * @param mailbox the mailbox we're checking
     * @param mailboxMap a HashMap relating server id's of mailboxes in the current sync set to
     * the corresponding mailbox structures
     * @return whether or not the mailbox contains email (rather than PIM or unknown data)
     */
    /*package*/ boolean isValidMailFolder(Mailbox mailbox, HashMap<String, Mailbox> mailboxMap) {
        int folderType = mailbox.mType;
        // Automatically accept our email types
        if (folderType < Mailbox.TYPE_NOT_EMAIL) return true;
        // Automatically reject everything else but "unknown"
        if (folderType != Mailbox.TYPE_UNKNOWN) return false;
        // If this is TYPE_UNKNOWN, check the parent
        Mailbox parent = mailboxMap.get(mailbox.mParentServerId);
        // If the parent is in the map, then check it out; if not, it could be an existing saved
        // Mailbox, so we'll have to query the database
        if (parent == null) {
            mBindArguments[0] = Long.toString(mAccount.mId);
            mBindArguments[1] = mailbox.mParentServerId;
            long parentId = Utility.getFirstRowInt(mContext, Mailbox.CONTENT_URI,
                    EmailContent.ID_PROJECTION,
                    MailboxColumns.ACCOUNT_KEY + "=? AND " + MailboxColumns.SERVER_ID + "=?",
                    mBindArguments, null, EmailContent.ID_PROJECTION_COLUMN, -1);
            if (parentId != -1) {
                // Get the parent from the database
                parent = Mailbox.restoreMailboxWithId(mContext, parentId);
                if (parent == null) return false;
            } else {
                return false;
            }
        }
        return isValidMailFolder(parent, mailboxMap);
    }

    public void updateParser(ArrayList<ContentProviderOperation> ops) throws IOException {
        String serverId = null;
        String displayName = null;
        String parentId = null;
        while (nextTag(Tags.FOLDER_UPDATE) != END) {
            switch (tag) {
                case Tags.FOLDER_SERVER_ID:
                    serverId = getValue();
                    break;
                case Tags.FOLDER_DISPLAY_NAME:
                    displayName = getValue();
                    break;
                case Tags.FOLDER_PARENT_ID:
                    parentId = getValue();
                    break;
                default:
                    skipTag();
                    break;
            }
        }
        // We'll make a change if one of parentId or displayName are specified
        // serverId is required, but let's be careful just the same
        if (serverId != null && (displayName != null || parentId != null)) {
            Cursor c = getServerIdCursor(serverId);
            try {
                // If we find the mailbox (using serverId), make the change
                if (c.moveToFirst()) {
                    userLog("Updating ", serverId);
                    ContentValues cv = new ContentValues();
                    if (displayName != null) {
                        cv.put(Mailbox.DISPLAY_NAME, displayName);
                    }
                    if (parentId != null) {
                        cv.put(Mailbox.PARENT_SERVER_ID, parentId);
                    }
                    ops.add(ContentProviderOperation.newUpdate(
                            ContentUris.withAppendedId(Mailbox.CONTENT_URI,
                                    c.getLong(0))).withValues(cv).build());
                }
            } finally {
                c.close();
            }
        }
    }

    public void changesParser(ArrayList<ContentProviderOperation> ops) throws IOException {
        // Mailboxes that we known contain email
        ArrayList<Mailbox> validMailboxes = new ArrayList<Mailbox>();
        // Mailboxes that we're unsure about
        ArrayList<Mailbox> userMailboxes = new ArrayList<Mailbox>();
        // Maps folder serverId to mailbox type
        HashMap<String, Mailbox> mailboxMap = new HashMap<String, Mailbox>();

        int userMailboxCount = 0;
        while (nextTag(Tags.FOLDER_CHANGES) != END) {
            if (tag == Tags.FOLDER_ADD) {
                Mailbox mailbox = addParser();
                if (mailbox != null) {
                    // Save away the type of this folder
                    mailboxMap.put(mailbox.mServerId, mailbox);
                    // And add the mailbox to the proper list
                    if (type == USER_MAILBOX_TYPE) {
                        // Limit user mailboxes to 1000
                        if (++userMailboxCount < MAX_USER_MAILBOXES) {
                            userMailboxes.add(mailbox);
                        }
                        // TODO: Consider ways to avoid having to truncate the list of mailboxes
                    } else {
                        validMailboxes.add(mailbox);
                    }
                }
            } else if (tag == Tags.FOLDER_DELETE) {
                deleteParser(ops);
            } else if (tag == Tags.FOLDER_UPDATE) {
                updateParser(ops);
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

        // Go through the generic user mailboxes; we'll call them valid if any parent is valid
        for (Mailbox m: userMailboxes) {
            if (isValidMailFolder(m, mailboxMap)) {
                m.mType = Mailbox.TYPE_MAIL;
                validMailboxes.add(m);
            } else {
                userLog("Rejecting unknown type mailbox: " + m.mDisplayName);
            }
        }

        for (Mailbox m: validMailboxes) {
            userLog("Adding mailbox: ", m.mDisplayName);
            ops.add(ContentProviderOperation
                    .newInsert(Mailbox.CONTENT_URI).withValues(m.toContentValues()).build());
        }
    }

    /**
     * Not needed for FolderSync parsing; everything is done within changesParser
     */
    @Override
    public void commandsParser() throws IOException {
    }

    /**
     * Commit all changes from this sync (sync key, adds, updates, and deletes)
     */
    @Override
    public void commit() throws IOException {
        // Commit the sync key
        ContentValues cv = new ContentValues();
        cv.put(AccountColumns.SYNC_KEY, mAccount.mSyncKey);
        mOperations.add(ContentProviderOperation.newUpdate(
                ContentUris.withAppendedId(Mailbox.CONTENT_URI, mAccount.mId))
                .withValues(cv)
                .build());

        userLog("Applying ", mOperations.size(), " mailbox operations.");

        // Execute the batch
        try {
            mContentResolver.applyBatch(EmailProvider.EMAIL_AUTHORITY, mOperations);
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

    @Override
    public void responsesParser() throws IOException {
    }

}
