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
import java.util.Arrays;
import java.util.List;

import com.android.email.provider.EmailProvider;
import com.android.email.provider.EmailContent;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
//import android.content.Context;
import android.content.OperationApplicationException;
import android.os.RemoteException;
import android.util.Log;

public class EasFolderSyncParser extends EasParser {

    public static final String TAG = "FolderSyncParser";

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

    public static final List<Integer> mMailFolderTypes = 
        Arrays.asList(INBOX_TYPE,DRAFTS_TYPE,DELETED_TYPE,SENT_TYPE,OUTBOX_TYPE,USER_MAILBOX_TYPE);

    private EmailContent.Account mAccount;
    private EasService mService;
    //private Context mContext;
    private MockParserStream mMock = null;

    public EasFolderSyncParser(InputStream in, EasService service) throws IOException {
        super(in);
        mService = service;
        mAccount = service.mAccount;
        //mContext = service.mContext;
        if (in instanceof MockParserStream) {
            mMock = (MockParserStream)in;
        }
    }

    public void parse() throws IOException {
        //captureOn();
        int status;
        if (nextTag(START_DOCUMENT) != EasTags.FOLDER_FOLDER_SYNC)
            throw new IOException();
        while (nextTag(START_DOCUMENT) != END_DOCUMENT) {
            if (tag == EasTags.FOLDER_STATUS) {
                status = getValueInt();
                if (status != 1) {
                    System.err.println("FolderSync failed: " + status);
                }
            } else if (tag == EasTags.FOLDER_SYNC_KEY) {
                mAccount.mSyncKey = getValue();
            } else if (tag == EasTags.FOLDER_CHANGES) {
                changesParser();
            } else
                skipTag();
        }
        //captureOff(mContext, "FolderSyncParser.txt");
    }

    public void addParser(ArrayList<EmailContent.Mailbox> boxes) throws IOException {
        String name = null;
        String serverId = null;
        String parentId = null;
        int type = 0;

        while (nextTag(EasTags.FOLDER_ADD) != END) {
            switch (tag) {
                case EasTags.FOLDER_DISPLAY_NAME: {
                    name = getValue();
                    break;
                } 
                case EasTags.FOLDER_TYPE: {
                    type = getValueInt();
                    break;
                } 
                case EasTags.FOLDER_PARENT_ID: {
                    parentId = getValue();
                    break;
                } 
                case EasTags.FOLDER_SERVER_ID: {
                    serverId = getValue();
                    break;
                } 
                default:
                    skipTag();
            }
        }
        if (mMailFolderTypes.contains(type)) {
            EmailContent.Mailbox m = new EmailContent.Mailbox();
            m.mDisplayName = name;
            m.mServerId = serverId;
            m.mAccountKey = mAccount.mId;
            if (type == INBOX_TYPE) {
                m.mSyncFrequency = EmailContent.Account.CHECK_INTERVAL_PUSH;
                m.mType = EmailContent.Mailbox.TYPE_INBOX;
            } else if (type == OUTBOX_TYPE) {
                //m.mSyncFrequency = MailService.OUTBOX_FREQUENCY;
                m.mSyncFrequency = EmailContent.Account.CHECK_INTERVAL_NEVER;
                m.mType = EmailContent.Mailbox.TYPE_OUTBOX;
            } else {
                if (type == SENT_TYPE) {
                    m.mType = EmailContent.Mailbox.TYPE_SENT;
                } else if (type == DRAFTS_TYPE) {
                    m.mType = EmailContent.Mailbox.TYPE_DRAFTS;
                } else if (type == DELETED_TYPE) {
                    m.mType = EmailContent.Mailbox.TYPE_TRASH;
                }
                m.mSyncFrequency = EmailContent.Account.CHECK_INTERVAL_NEVER;
            }

            if (!parentId.equals("0")) {
                m.mParentServerId = parentId;
            }
            Log.v(TAG, "Adding mailbox: " + m.mDisplayName);
            boxes.add(m);
        }

        return;
    }

    public void changesParser() throws IOException {
        // Keep track of new boxes, deleted boxes, updated boxes
        ArrayList<EmailContent.Mailbox> newBoxes = new ArrayList<EmailContent.Mailbox>();

        while (nextTag(EasTags.FOLDER_CHANGES) != END) {
            if (tag == EasTags.FOLDER_ADD) {
                addParser(newBoxes);
            } else if (tag == EasTags.FOLDER_COUNT) {
                getValueInt();
            } else
                skipTag();
        }

        for (EmailContent.Mailbox m: newBoxes) {
            String parent = m.mParentServerId;
            if (parent != null) {
                // Wrong except first time!  Need to check existing boxes!
                //**PROVIDER
                m.mFlagVisible = true; //false;
                for (EmailContent.Mailbox mm: newBoxes) {
                    if (mm.mServerId.equals(parent)) {
                        //mm.parent = true;
                    }
                }
            }
        }

        if (mMock != null) {
            mMock.setResult(newBoxes);
            return;
        }

        if (!newBoxes.isEmpty()) {
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            for (EmailContent.Mailbox content: newBoxes) {
                ContentProviderOperation.Builder b =  ContentProviderOperation
                .newInsert(EmailContent.Mailbox.CONTENT_URI);
                b.withValues(content.toContentValues());
                ops.add(b.build());
            }
            ops.add(ContentProviderOperation.newUpdate(ContentUris
                    .withAppendedId(EmailContent.Account.CONTENT_URI, mAccount.mId))
                    .withValues(mAccount.toContentValues()).build());

            try {
                ContentProviderResult[] results = mService.mContext.getContentResolver()
                .applyBatch(EmailProvider.EMAIL_AUTHORITY, ops);
                for (ContentProviderResult result: results) {
                    if (result.uri == null) {
                        return;
                    }
                }
                Log.v(TAG, "New syncKey: " + mAccount.mSyncKey);
            } catch (RemoteException e) {
                // There is nothing to be done here; fail by returning null
            } catch (OperationApplicationException e) {
                // There is nothing to be done here; fail by returning null
            }
        }
    }

}
