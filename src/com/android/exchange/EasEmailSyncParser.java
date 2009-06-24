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

package com.android.exchange;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import com.android.email.provider.EmailProvider;
import com.android.exchange.EmailContent.Account;
import com.android.exchange.EmailContent.Attachment;
import com.android.exchange.EmailContent.Mailbox;
import com.android.exchange.EmailContent.Message;
import com.android.exchange.EmailContent.MessageColumns;
import com.android.exchange.EmailContent.SyncColumns;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;
import android.util.Log;

public class EasEmailSyncParser extends EasParser {

    private static final String TAG = "EmailSyncParser";

    private Account mAccount;
    private EasService mService;
    private ContentResolver mContentResolver;
    private Context mContext;
    private Mailbox mMailbox;
    protected boolean mMoreAvailable = false;
    String[] bindArgument = new String[1];

    public EasEmailSyncParser(InputStream in, EasService service) throws IOException {
        super(in);
        mService = service;
        mContext = service.mContext;
        mMailbox = service.mMailbox;
        mAccount = service.mAccount;
        //setDebug(true);
        mContentResolver = mContext.getContentResolver();
    }

    public void parse() throws IOException {
        int status;
        if (nextTag(START_DOCUMENT) != EasTags.SYNC_SYNC)
            throw new IOException();
        while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
            if (tag == EasTags.SYNC_COLLECTION || tag == EasTags.SYNC_COLLECTIONS) {
                // Ignore
            } else if (tag == EasTags.SYNC_STATUS) {
                status = getValueInt();
                if (status != 1) {
                    System.err.println("Sync failed: " + status);
                    if (status == 3) {
                        // TODO Bad sync key.  Must delete everything and start over...?
                        mMailbox.mSyncKey = "0";
                        Log.w(TAG, "Bad sync key; RESET and delete mailbox contents");
                        mContext.getContentResolver()
                        .delete(Message.CONTENT_URI, 
                                Message.MAILBOX_KEY + "=" + mMailbox.mId, null);
                        mMoreAvailable = true;
                    }
                }
            } else if (tag == EasTags.SYNC_COMMANDS) {
                commandsParser();
            } else if (tag == EasTags.SYNC_RESPONSES) {
                skipTag();
            } else if (tag == EasTags.SYNC_MORE_AVAILABLE) {
                mMoreAvailable = true;
            } else if (tag == EasTags.SYNC_SYNC_KEY) {
                if (mMailbox.mSyncKey.equals("0"))
                    mMoreAvailable = true;
                mMailbox.mSyncKey = getValue();
            } else
                skipTag();
        }
        mMailbox.saveOrUpdate(mContext);
    }

    public void addParser(ArrayList<Message> emails) throws IOException {
        Message msg = new Message();
        String to = "";
        String from = "";
        String cc = "";
        String replyTo = "";
        int size = 0;
        msg.mAccountKey = mAccount.mId;
        msg.mMailboxKey = mMailbox.mId;
        msg.mFlagLoaded = Message.LOADED;

        ArrayList<Attachment> atts = new ArrayList<Attachment>();
        boolean inData = false;

        while (nextTag(EasTags.SYNC_ADD) != END) {
            switch (tag) {
                case EasTags.SYNC_SERVER_ID:   // same as EasTags.EMAIL_BODY_SIZE
                    if (!inData) {
                        msg.mServerId = getValue();
                    } else {
                        size = Integer.parseInt(getValue());
                    }
                    break;
                case EasTags.SYNC_APPLICATION_DATA:
                    inData = true;
                    break;
                case EasTags.EMAIL_ATTACHMENTS:
                    break;
                case EasTags.EMAIL_ATTACHMENT:
                    attachmentParser(atts, msg);
                    break;
                case EasTags.EMAIL_TO:
                    to = getValue();
                    break;
                case EasTags.EMAIL_FROM:
                    from = getValue();
                    String sender = from;
                    int q = from.indexOf('\"');
                    if (q >= 0) {
                        int qq = from.indexOf('\"', q + 1);
                        if (qq > 0) {
                            sender = from.substring(q + 1, qq);
                        }
                    }
                    msg.mDisplayName = sender;
                    break;
                case EasTags.EMAIL_CC:
                    cc = getValue();
                    break;
                case EasTags.EMAIL_REPLY_TO:
                    replyTo = getValue();
                    break;
                case EasTags.EMAIL_DATE_RECEIVED:
                    String date = getValue();
                    // 2009-02-11T18:03:03.627Z
                    GregorianCalendar cal = new GregorianCalendar();
                    cal.set(Integer.parseInt(date.substring(0, 4)), 
                            Integer.parseInt(date.substring(5, 7)) - 1, 
                            Integer.parseInt(date.substring(8, 10)), 
                            Integer.parseInt(date.substring(11, 13)), 
                            Integer.parseInt(date.substring(14, 16)), 
                            Integer.parseInt(date.substring(17, 19)));
                    cal.setTimeZone(TimeZone.getTimeZone("GMT"));
                    msg.mTimeStamp = cal.getTimeInMillis();
                    break;
                case EasTags.EMAIL_DISPLAY_TO:
                    break;
                case EasTags.EMAIL_SUBJECT:
                    msg.mSubject = getValue();
                    break;
                case EasTags.EMAIL_IMPORTANCE:
                    break;
                case EasTags.EMAIL_READ:
                    msg.mFlagRead = getValueInt() == 1;
                    break;
                case EasTags.EMAIL_BODY:
                    msg.mTextInfo = "X;X;8;" + size; // location;encoding;charset;size
                    msg.mText = getValue();
                    // For now...
                    msg.mPreview = "Fake preview"; //Messages.previewFromText(body);
                    break;
                case EasTags.EMAIL_MESSAGE_CLASS:
                    break;
                default:
                    skipTag();
            }
        }

        // Tell the provider that this is synced back
        msg.mServerVersion = mMailbox.mSyncKey;

        msg.mTo = to;
        msg.mFrom = from;
        msg.mCc = cc;
        msg.mReplyTo = replyTo;
        if (atts.size() > 0) {
            msg.mAttachments = atts;
        }
        emails.add(msg);
    }

    public void attachmentParser(ArrayList<Attachment> atts, Message msg) 
    throws IOException {
        String fileName = null;
        String length = null;
        String lvl = null;

        while (nextTag(EasTags.EMAIL_ATTACHMENT) != END) {
            switch (tag) {
                case EasTags.EMAIL_DISPLAY_NAME:
                    fileName = getValue();
                    break;
                case EasTags.EMAIL_ATT_NAME:
                    lvl = getValue();
                    break;
                case EasTags.EMAIL_ATT_SIZE:
                    length = getValue();
                    break;
                default:
                    skipTag();
            }
        }

        if (fileName != null && length != null && lvl != null) {
            Attachment att = new Attachment();
            att.mEncoding = "base64";
            att.mSize = Long.parseLong(length);
            att.mFileName = fileName;
            atts.add(att);
            msg.mFlagAttachment = true;
        }
    }

    public void deleteParser(ArrayList<Long> deletes) throws IOException {
        while (nextTag(EasTags.SYNC_DELETE) != END) {
            switch (tag) {
                case EasTags.SYNC_SERVER_ID:
                    String serverId = getValue();
                    Cursor c = mContentResolver.query(Message.CONTENT_URI, 
                            Message.ID_COLUMN_PROJECTION, 
                            SyncColumns.SERVER_ID + "=" + serverId, null, null);
                    try {
                        if (c.moveToFirst()) {
                            mService.log("Deleting " + serverId);
                            deletes.add(c.getLong(Message.ID_COLUMNS_ID_COLUMN));
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

    public void changeParser(ArrayList<Long> changes) throws IOException {
        String serverId = null;
        boolean oldRead = false;
        boolean read = true;
        long id = 0;
        while (nextTag(EasTags.SYNC_CHANGE) != END) {
            switch (tag) {
                case EasTags.SYNC_SERVER_ID:
                    serverId = getValue();
                    bindArgument[0] = serverId;
                    Cursor c = mContentResolver.query(Message.CONTENT_URI, 
                            Message.LIST_PROJECTION, 
                            SyncColumns.SERVER_ID + "=?", bindArgument, null);
                    try {
                        if (c.moveToFirst()) {
                            mService.log("Changing " + serverId);
                            oldRead = c.getInt(Message.LIST_READ_COLUMN) == Message.READ;
                            id = c.getLong(Message.LIST_ID_COLUMN);
                        }
                    } finally {
                        c.close();
                    }
                    break;
                case EasTags.EMAIL_READ:
                    read = getValueInt() == 1;
                    break;
                case EasTags.SYNC_APPLICATION_DATA:
                    break;
                default:
                    skipTag();
            }
        }
        if (oldRead != read) {
            changes.add(id);
        }
    }

    public void commandsParser() throws IOException {
        ArrayList<Message> newEmails = new ArrayList<Message>();
        ArrayList<Long> deletedEmails = new ArrayList<Long>();
        ArrayList<Long> changedEmails = new ArrayList<Long>();

        while (nextTag(EasTags.SYNC_COMMANDS) != END) {
            if (tag == EasTags.SYNC_ADD) {
                addParser(newEmails);
            } else if (tag == EasTags.SYNC_DELETE) {
                deleteParser(deletedEmails);
            } else if (tag == EasTags.SYNC_CHANGE) {
                changeParser(changedEmails);
            } else
                skipTag();
        }

        // Use a batch operation to handle the changes
        // TODO Notifications
        // TODO Store message bodies
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
        for (Message content: newEmails) {
            content.addSaveOps(ops);
        }
        for (Long id: deletedEmails) {
            ops.add(ContentProviderOperation
                    .newDelete(ContentUris.withAppendedId(Message.CONTENT_URI, id)).build());
        }
        if (!changedEmails.isEmpty()) {
            ContentValues cv = new ContentValues();
            // TODO Handle proper priority
            // Set this as the correct state (assuming server wins)
            cv.put(SyncColumns.DIRTY_COUNT, 0);
            cv.put(MessageColumns.FLAG_READ, true);
            for (Long id: changedEmails) {
                // For now, don't handle read->unread
                ops.add(ContentProviderOperation.newUpdate(ContentUris
                        .withAppendedId(Message.CONTENT_URI, id)).withValues(cv).build());
            }
        }
        ops.add(ContentProviderOperation.newUpdate(ContentUris
                .withAppendedId(Mailbox.CONTENT_URI, mMailbox.mId))
                .withValues(mMailbox.toContentValues()).build());

        try {
            ContentProviderResult[] results = mService.mContext.getContentResolver()
            .applyBatch(EmailProvider.EMAIL_AUTHORITY, ops);
            for (ContentProviderResult result: results) {
                if (result.uri == null) {
                    Log.v(TAG, "Null result in ContentProviderResult!");
                }
            }
        } catch (RemoteException e) {
            // There is nothing to be done here; fail by returning null
        } catch (OperationApplicationException e) {
            // There is nothing to be done here; fail by returning null
        }

        Log.v(TAG, "Mailbox EOS syncKey now: " + mMailbox.mSyncKey);
    }
}
