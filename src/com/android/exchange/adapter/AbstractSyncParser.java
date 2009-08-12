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

import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.exchange.EasSyncService;
import com.android.exchange.SyncManager;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

/**
 * Base class for the Email and PIM sync parsers
 * Handles the basic flow of syncKeys, looping to get more data, handling errors, etc.
 * Each subclass must implement a handful of methods that relate specifically to the data type
 *
 */
public abstract class AbstractSyncParser extends Parser {

    EasSyncService mService;
    Mailbox mMailbox;
    Account mAccount;
    Context mContext;
    ContentResolver mContentResolver;

    public AbstractSyncParser(InputStream in, EasSyncService _service) throws IOException {
        super(in);
        mService = _service;
        mContext = mService.mContext;
        mContentResolver = mContext.getContentResolver();
        mMailbox = mService.mMailbox;
        mAccount = mService.mAccount;
    }

    /**
     * Read, parse, and act on incoming commands from the Exchange server
     * @throws IOException if the connection is broken
     */
    public abstract void commandsParser() throws IOException;

    /**
     * Read, parse, and act on server responses
     * Email doesn't have any, so this isn't yet implemented anywhere.  It will become abstract,
     * in the near future, however.
     * @throws IOException
     */
    public void responsesParser() throws IOException {
        // Placeholder until needed; will become an abstract method
    }

    /**
     * Delete all records of this class in this account
     */
    public abstract void wipe();

    /**
     * Loop through the top-level structure coming from the Exchange server
     * Sync keys and the more available flag are handled here, whereas specific data parsing
     * is handled by abstract methods implemented for each data class (e.g. Email, Contacts, etc.)
     */
    @Override
    public boolean parse() throws IOException {
        int status;
        boolean moreAvailable = false;
        int interval = mMailbox.mSyncInterval;

        // If we're not at the top of the xml tree, throw an exception
        if (nextTag(START_DOCUMENT) != Tags.SYNC_SYNC) {
            throw new IOException();
        }
        // Loop here through the remaining xml
        while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
            if (tag == Tags.SYNC_COLLECTION || tag == Tags.SYNC_COLLECTIONS) {
                // Ignore these tags, since we've only got one collection syncing in this loop
            } else if (tag == Tags.SYNC_STATUS) {
                // Status = 1 is success; everything else is a failure
                status = getValueInt();
                if (status != 1) {
                    mService.errorLog("Sync failed: " + status);
                    // Status = 3 means invalid sync key
                    if (status == 3) {
                        // Must delete all of the data and start over with syncKey of "0"
                        mMailbox.mSyncKey = "0";
                        // Make this a push box through the first sync
                        // TODO Make frequency conditional on user settings!
                        mMailbox.mSyncInterval = Mailbox.CHECK_INTERVAL_PUSH;
                        mService.errorLog("Bad sync key; RESET and delete contacts");
                        wipe();
                        // Indicate there's more so that we'll start syncing again
                        moreAvailable = true;
                    } else if (status == 8) {
                        // This is Bad; it means the server doesn't recognize the serverId it
                        // sent us.  What's needed is a refresh of the folder list.
                        SyncManager.reloadFolderList(mContext, mAccount.mId, true);
                    }
                    // TODO Look at other error codes and consider what's to be done
                }
            } else if (tag == Tags.SYNC_COMMANDS) {
                commandsParser();
            } else if (tag == Tags.SYNC_RESPONSES) {
                responsesParser();
            } else if (tag == Tags.SYNC_MORE_AVAILABLE) {
                moreAvailable = true;
            } else if (tag == Tags.SYNC_SYNC_KEY) {
                if (mMailbox.mSyncKey.equals("0")) {
                    moreAvailable = true;
                }
                String newKey = getValue();
                mService.userLog("Parsed key for " + mMailbox.mDisplayName + ": " + newKey);
                mMailbox.mSyncKey = newKey;
                // If we were pushing (i.e. auto-start), now we'll become ping-triggered
                if (mMailbox.mSyncInterval == Mailbox.CHECK_INTERVAL_PUSH) {
                    mMailbox.mSyncInterval = Mailbox.CHECK_INTERVAL_PING;
                }
           } else {
                skipTag();
           }
        }

        // If the sync interval has changed, or if no commands were parsed save the change
        if (mMailbox.mSyncInterval != interval || mService.mChangeCount == 0) {
            synchronized (mService.getSynchronizer()) {
                if (!mService.isStopped()) {
                    // Make sure we save away the new syncFrequency
                    ContentValues cv = new ContentValues();
                    if (mService.mChangeCount == 0) {
                        cv.put(MailboxColumns.SYNC_KEY, mMailbox.mSyncKey);
                    }
                    cv.put(MailboxColumns.SYNC_INTERVAL, mMailbox.mSyncInterval);
                    mMailbox.update(mContext, cv);
                }
            }
        // If this box has backed off of push, and there were changes, try to change back to
        // ping; it seems to help at times
        } else if (mService.mChangeCount > 0 &&
                mAccount.mSyncInterval == Account.CHECK_INTERVAL_PUSH &&
                mMailbox.mSyncInterval > 0) {
            synchronized (mService.getSynchronizer()) {
                if (!mService.isStopped()) {
                    ContentValues cv = new ContentValues();
                    cv.put(MailboxColumns.SYNC_INTERVAL, Mailbox.CHECK_INTERVAL_PING);
                    mMailbox.update(mContext, cv);
                    mService.userLog("Changes found to ping loop mailbox " + mMailbox.mDisplayName +
                            ": switch back to ping.");
                }
            }
        }

        // Let the caller know that there's more to do
        return moreAvailable;
    }


}
