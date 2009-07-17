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
import com.android.exchange.Eas;
import com.android.exchange.EasSyncService;
import com.android.exchange.EmailContent.Attachment;
import com.android.exchange.EmailContent.Mailbox;
import com.android.exchange.EmailContent.Message;
import com.android.exchange.EmailContent.MessageColumns;
import com.android.exchange.EmailContent.SyncColumns;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;
import android.webkit.MimeTypeMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Sync adapter for EAS email
 *
 */
public class EasEmailSyncAdapter extends EasSyncAdapter {

    private static boolean DEBUG_LOGGING = false;  // DON'T CHECK THIS IN SET TO TRUE

    private static final int UPDATES_READ_COLUMN = 0;
    private static final int UPDATES_MAILBOX_KEY_COLUMN = 1;
    private static final int UPDATES_SERVER_ID_COLUMN = 2;
    private static final String[] UPDATES_PROJECTION =
        {MessageColumns.FLAG_READ, MessageColumns.MAILBOX_KEY, SyncColumns.SERVER_ID};

    String[] bindArguments = new String[2];

    ArrayList<Long> mDeletedIdList = new ArrayList<Long>();
    ArrayList<Long> mUpdatedIdList = new ArrayList<Long>();

    public EasEmailSyncAdapter(Mailbox mailbox) {
        super(mailbox);
    }

    @Override
    public boolean parse(ByteArrayInputStream is, EasSyncService service) throws IOException {
        EasEmailSyncParser p = new EasEmailSyncParser(is, service);
        return p.parse();
    }
    
    public class EasEmailSyncParser extends EasContentParser {

        private static final String WHERE_SERVER_ID_AND_MAILBOX_KEY = 
            SyncColumns.SERVER_ID + "=? and " + MessageColumns.MAILBOX_KEY + "=?";

        private String mMailboxIdAsString;

        public EasEmailSyncParser(InputStream in, EasSyncService service) throws IOException {
            super(in, service);
            mMailboxIdAsString = Long.toString(mMailbox.mId);
            if (DEBUG_LOGGING) {
                setDebug(true);
            }
        }

        public void wipe() {
            mContentResolver.delete(Message.CONTENT_URI,
                    Message.MAILBOX_KEY + "=" + mMailbox.mId, null);
            mContentResolver.delete(Message.DELETED_CONTENT_URI,
                    Message.MAILBOX_KEY + "=" + mMailbox.mId, null);
            mContentResolver.delete(Message.UPDATED_CONTENT_URI,
                    Message.MAILBOX_KEY + "=" + mMailbox.mId, null);
        }

        public void addData (Message msg) throws IOException {
            String to = "";
            String from = "";
            String cc = "";
            String replyTo = "";
            int size = 0;

            ArrayList<Attachment> atts = new ArrayList<Attachment>();

            while (nextTag(EasTags.SYNC_APPLICATION_DATA) != END) {
                switch (tag) {
                    case EasTags.EMAIL_ATTACHMENTS:
                        attachmentsParser(atts, msg);
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
                        cal.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date
                                .substring(5, 7)) - 1, Integer.parseInt(date.substring(8, 10)),
                                Integer.parseInt(date.substring(11, 13)), Integer.parseInt(date
                                        .substring(14, 16)), Integer.parseInt(date
                                                .substring(17, 19)));
                        cal.setTimeZone(TimeZone.getTimeZone("GMT"));
                        msg.mTimeStamp = cal.getTimeInMillis();
                        break;
                    case EasTags.EMAIL_SUBJECT:
                        msg.mSubject = getValue();
                        break;
                    case EasTags.EMAIL_READ:
                        msg.mFlagRead = getValueInt() == 1;
                        break;
                    case EasTags.BASE_BODY:
                        bodyParser(msg);
                        break;
                    case EasTags.EMAIL_BODY:
                        msg.mTextInfo = "X;X;8;" + size; // location;encoding;charset;size
                        msg.mText = getValue();
                        // For now...
                        msg.mPreview = "Fake preview"; // Messages.previewFromText(body);
                        break;
                    default:
                        skipTag();
                }
            }

            msg.mTo = to;
            msg.mFrom = from;
            msg.mCc = cc;
            msg.mReplyTo = replyTo;
            if (atts.size() > 0) {
                msg.mAttachments = atts;
            }

        }
        
        private void addParser(ArrayList<Message> emails) throws IOException {
            Message msg = new Message();
            msg.mAccountKey = mAccount.mId;
            msg.mMailboxKey = mMailbox.mId;
            msg.mFlagLoaded = Message.LOADED;

            while (nextTag(EasTags.SYNC_ADD) != END) {
                switch (tag) {
                    case EasTags.SYNC_SERVER_ID: 
                        msg.mServerId = getValue();
                        break;
                    case EasTags.SYNC_APPLICATION_DATA:
                        addData(msg);
                        break;
                    default:
                        skipTag();
                }
            }

            // Tell the provider that this is synced back
            msg.mServerVersion = mMailbox.mSyncKey;
            emails.add(msg);
        }

        private void bodyParser(Message msg) throws IOException {
            String bodyType = Eas.BODY_PREFERENCE_TEXT;
            String body = "";
            while (nextTag(EasTags.EMAIL_BODY) != END) {
                switch (tag) {
                    case EasTags.BASE_TYPE:
                        bodyType = getValue();
                        break;
                    case EasTags.BASE_DATA:
                        body = getValue();
                        break;
                    default:
                        skipTag();
                }
            }
            // We always ask for TEXT or HTML; there's no third option
            String info = "X;X;8;" + body.length();
            if (bodyType.equals(Eas.BODY_PREFERENCE_HTML)) {
                msg.mHtmlInfo = info;
                msg.mHtml = body;
            } else {
                msg.mTextInfo = info;
                msg.mText = body;
            }
        }

        private void attachmentParser(ArrayList<Attachment> atts, Message msg) throws IOException {
            String fileName = null;
            String length = null;
            String location = null;

            while (nextTag(EasTags.EMAIL_ATTACHMENT) != END) {
                switch (tag) {
                    case EasTags.EMAIL_DISPLAY_NAME:
                        fileName = getValue();
                        break;
                    case EasTags.EMAIL_ATT_NAME:
                        location = getValue();
                        break;
                    case EasTags.EMAIL_ATT_SIZE:
                        length = getValue();
                        break;
                    default:
                        skipTag();
                }
            }

            if (fileName != null && length != null && location != null) {
                Attachment att = new Attachment();
                att.mEncoding = "base64";
                att.mSize = Long.parseLong(length);
                att.mFileName = fileName;
                att.mLocation = location;
                att.mMimeType = getMimeTypeFromFileName(fileName);
                atts.add(att);
                msg.mFlagAttachment = true;
            }
        }

        /**
         * Try to determine a mime type from a file name, defaulting to application/x, where x
         * is either the extension or (if none) octet-stream
         * At the moment, this is somewhat lame, since many file types aren't recognized
         * @param fileName the file name to ponder
         * @return
         */
        // Note: The MimeTypeMap method currently uses a very limited set of mime types
        // A bug has been filed against this issue.
        public String getMimeTypeFromFileName(String fileName) {
            String mimeType;
            int lastDot = fileName.lastIndexOf('.');
            String extension = null;
            if (lastDot > 0 && lastDot < fileName.length() - 1) {
                extension = fileName.substring(lastDot + 1);
            }
            if (extension == null) {
                // A reasonable default for now.
                mimeType = "application/octet-stream";
            } else {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mimeType == null) {
                    mimeType = "application/" + extension;
                }
            }
            return mimeType;
        }

        private void attachmentsParser(ArrayList<Attachment> atts, Message msg) throws IOException {
            while (nextTag(EasTags.EMAIL_ATTACHMENTS) != END) {
                switch (tag) {
                    case EasTags.EMAIL_ATTACHMENT:
                        attachmentParser(atts, msg);
                        break;
                    default:
                        skipTag();
                }
            }
        }

        private Cursor getServerIdCursor(String serverId, String[] projection) {
            bindArguments[0] = serverId;
            bindArguments[1] = mMailboxIdAsString;
            return mContentResolver.query(Message.CONTENT_URI, projection,
                    WHERE_SERVER_ID_AND_MAILBOX_KEY, bindArguments, null);
        }

        private void deleteParser(ArrayList<Long> deletes) throws IOException {
            while (nextTag(EasTags.SYNC_DELETE) != END) {
                switch (tag) {
                    case EasTags.SYNC_SERVER_ID:
                        String serverId = getValue();
                        // Find the message in this mailbox with the given serverId
                        Cursor c = getServerIdCursor(serverId, Message.ID_COLUMN_PROJECTION);
                        try {
                            if (c.moveToFirst()) {
                                mService.userLog("Deleting " + serverId);
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

        class ServerChange {
            long id;
            boolean read;

            ServerChange(long _id, boolean _read) {
                id = _id;
                read = _read;
            }
        }

        private void changeParser(ArrayList<ServerChange> changes) throws IOException {
            String serverId = null;
            boolean oldRead = false;
            boolean read = true;
            long id = 0;
            while (nextTag(EasTags.SYNC_CHANGE) != END) {
                switch (tag) {
                    case EasTags.SYNC_SERVER_ID:
                        serverId = getValue();
                        Cursor c = getServerIdCursor(serverId, Message.LIST_PROJECTION);
                        try {
                            if (c.moveToFirst()) {
                                mService.userLog("Changing " + serverId);
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
                changes.add(new ServerChange(id, read));
            }
        }

        /* (non-Javadoc)
         * @see com.android.exchange.adapter.EasContentParser#commandsParser()
         */
        public void commandsParser() throws IOException {
            ArrayList<Message> newEmails = new ArrayList<Message>();
            ArrayList<Long> deletedEmails = new ArrayList<Long>();
            ArrayList<ServerChange> changedEmails = new ArrayList<ServerChange>();

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
            // TODO New mail notifications?  Who looks for these?
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            for (Message content : newEmails) {
                content.addSaveOps(ops);
            }
            for (Long id : deletedEmails) {
                ops.add(ContentProviderOperation.newDelete(
                        ContentUris.withAppendedId(Message.CONTENT_URI, id)).build());
            }
            if (!changedEmails.isEmpty()) {
                // Server wins in a conflict...
                for (ServerChange change : changedEmails) {
                    // For now, don't handle read->unread
                    ContentValues cv = new ContentValues();
                    cv.put(MessageColumns.FLAG_READ, change.read);
                    ops.add(ContentProviderOperation.newUpdate(
                            ContentUris.withAppendedId(Message.CONTENT_URI, change.id))
                                .withValues(cv)
                                .build());
                }
            }
            ops.add(ContentProviderOperation.newUpdate(
                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, mMailbox.mId)).withValues(
                    mMailbox.toContentValues()).build());

            addCleanupOps(ops);
            
            try {
                mService.mContext.getContentResolver()
                        .applyBatch(EmailProvider.EMAIL_AUTHORITY, ops);
            } catch (RemoteException e) {
                // There is nothing to be done here; fail by returning null
            } catch (OperationApplicationException e) {
                // There is nothing to be done here; fail by returning null
            }

            mService.userLog(mMailbox.mDisplayName + " SyncKey saved as: " + mMailbox.mSyncKey);
        }

    }

    @Override
    public String getCollectionName() {
        return "Email";
    }

    private void addCleanupOps(ArrayList<ContentProviderOperation> ops) {
        // If we've sent local deletions, clear out the deleted table
        for (Long id: mDeletedIdList) {
            ops.add(ContentProviderOperation.newDelete(
                    ContentUris.withAppendedId(Message.DELETED_CONTENT_URI, id)).build());
        }
        // And same with the updates
        for (Long id: mUpdatedIdList) {
            ops.add(ContentProviderOperation.newDelete(
                    ContentUris.withAppendedId(Message.UPDATED_CONTENT_URI, id)).build());
        }
    }

    @Override
    public void cleanup(EasSyncService service) {
        if (!mDeletedIdList.isEmpty() || !mUpdatedIdList.isEmpty()) {
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            addCleanupOps(ops);
            try {
                service.mContext.getContentResolver()
                    .applyBatch(EmailProvider.EMAIL_AUTHORITY, ops);
            } catch (RemoteException e) {
                // There is nothing to be done here; fail by returning null
            } catch (OperationApplicationException e) {
                // There is nothing to be done here; fail by returning null
            }
        }
    }

    @Override
    public boolean sendLocalChanges(EasSerializer s, EasSyncService service) throws IOException {
        Context context = service.mContext;
        ContentResolver cr = context.getContentResolver();

        // Find any of our deleted items
        Cursor c = cr.query(Message.DELETED_CONTENT_URI, Message.LIST_PROJECTION,
                MessageColumns.MAILBOX_KEY + '=' + mMailbox.mId, null, null);
        boolean first = true;
        // We keep track of the list of deleted item id's so that we can remove them from the
        // deleted table after the server receives our command
        mDeletedIdList.clear();
        try {
            while (c.moveToNext()) {
                if (first) {
                    s.start("Commands");
                    first = false;
                }
                // Send the command to delete this message
                s.start("Delete")
                    .data("ServerId", c.getString(Message.LIST_SERVER_ID_COLUMN))
                    .end("Delete");
                mDeletedIdList.add(c.getLong(Message.LIST_ID_COLUMN));
            }
        } finally {
            c.close();
        }

        // Find our trash mailbox, since deletions will have been moved there...
        long trashMailboxId =
            Mailbox.findMailboxOfType(context, mMailbox.mAccountKey, Mailbox.TYPE_TRASH);

        // Do the same now for updated items
        c = cr.query(Message.UPDATED_CONTENT_URI, Message.LIST_PROJECTION,
                MessageColumns.MAILBOX_KEY + '=' + mMailbox.mId, null, null);

        // We keep track of the list of updated item id's as we did above with deleted items
        mUpdatedIdList.clear();
        try {
            while (c.moveToNext()) {
                long id = c.getLong(Message.LIST_ID_COLUMN);
                // Say we've handled this update
                mUpdatedIdList.add(id);
                // We have the id of the changed item.  But first, we have to find out its current
                // state, since the updated table saves the opriginal state
                Cursor currentCursor = cr.query(ContentUris.withAppendedId(Message.CONTENT_URI, id),
                        UPDATES_PROJECTION, null, null, null);
                try {
                    // If this item no longer exists (shouldn't be possible), just move along
                    if (!currentCursor.moveToFirst()) {
                         continue;
                    }

                    // If the message is now in the trash folder, it has been deleted by the user
                    if (currentCursor.getLong(UPDATES_MAILBOX_KEY_COLUMN) == trashMailboxId) {
                         if (first) {
                            s.start("Commands");
                            first = false;
                        }
                        // Send the command to delete this message
                        s.start("Delete")
                            .data("ServerId", currentCursor.getString(UPDATES_SERVER_ID_COLUMN))
                            .end("Delete");
                        continue;
                    }

                    int read = currentCursor.getInt(UPDATES_READ_COLUMN);
                    if (read == c.getInt(Message.LIST_READ_COLUMN)) {
                        // The read state hasn't really changed, so move on...
                        continue;
                    }
                    if (first) {
                        s.start("Commands");
                        first = false;
                    }
                    // Send the change to "read".  We'll do "flagged" here eventually as well
                    // TODO Add support for flags here (EAS 12.0 and above)
                    s.start("Change")
                        .data("ServerId", c.getString(Message.LIST_SERVER_ID_COLUMN))
                        .start("ApplicationData")
                        .data("Read", Integer.toString(read))
                        .end("ApplicationData")
                        .end("Change");
                } finally {
                    currentCursor.close();
                }
            }
        } finally {
            c.close();
        }

        if (!first) {
            s.end("Commands");
        }
        return false;
    }
}
