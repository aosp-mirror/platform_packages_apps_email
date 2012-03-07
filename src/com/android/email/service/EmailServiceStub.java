/* Copyright (C) 2012 The Android Open Source Project
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

package com.android.email.service;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.android.email.Controller.Result;
import com.android.email.Email;
import com.android.email.LegacyConversions;
import com.android.email.NotificationController;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.provider.Utilities;
import com.android.emailcommon.AccountManagerTypes;
import com.android.emailcommon.Api;
import com.android.emailcommon.Logging;
import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.internet.MimeBodyPart;
import com.android.emailcommon.internet.MimeHeader;
import com.android.emailcommon.internet.MimeMultipart;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.FetchProfile;
import com.android.emailcommon.mail.Folder;
import com.android.emailcommon.mail.Folder.MessageRetrievalListener;
import com.android.emailcommon.mail.Folder.OpenMode;
import com.android.emailcommon.mail.Message;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.emailcommon.utility.Utility;

import java.io.IOException;
import java.util.HashSet;

/**
 * EmailServiceStub is an abstract class representing an EmailService
 *
 * This class provides legacy support for a few methods that are common to both
 * IMAP and POP3, including startSync, loadMore, loadAttachment, and sendMail
 */
public abstract class EmailServiceStub extends IEmailService.Stub implements IEmailService {

    private static final int MAILBOX_COLUMN_ID = 0;
    private static final int MAILBOX_COLUMN_SERVER_ID = 1;
    private static final int MAILBOX_COLUMN_TYPE = 2;

    /** Small projection for just the columns required for a sync. */
    private static final String[] MAILBOX_PROJECTION = new String[] {
        MailboxColumns.ID,
        MailboxColumns.SERVER_ID,
        MailboxColumns.TYPE,
    };

    private Context mContext;
    private IEmailServiceCallback.Stub mCallback;

    protected void init(Context context, IEmailServiceCallback.Stub callbackProxy) {
        mContext = context;
        mCallback = callbackProxy;
    }

    @Override
    public Bundle validate(HostAuth hostauth) throws RemoteException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void startSync(long mailboxId, boolean userRequest) throws RemoteException {
        Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, mailboxId);
        if (mailbox == null) return;
        Account account = Account.restoreAccountWithId(mContext, mailbox.mAccountKey);
        if (account == null) return;
        android.accounts.Account acct = new android.accounts.Account(account.mEmailAddress,
                AccountManagerTypes.TYPE_POP_IMAP);
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        ContentResolver.requestSync(acct, EmailContent.AUTHORITY, extras);
    }

    @Override
    public void stopSync(long mailboxId) throws RemoteException {
        // Not required
    }

    @Override
    public void loadMore(long messageId) throws RemoteException {
        // Load a message for view...
        try {
            // 1. Resample the message, in case it disappeared or synced while
            // this command was in queue
            EmailContent.Message message =
                EmailContent.Message.restoreMessageWithId(mContext, messageId);
            if (message == null) {
                mCallback.loadMessageStatus(messageId,
                        EmailServiceStatus.MESSAGE_NOT_FOUND, 0);
                return;
            }
            if (message.mFlagLoaded == EmailContent.Message.FLAG_LOADED_COMPLETE) {
                // We should NEVER get here
                mCallback.loadMessageStatus(messageId, 0, 100);
                return;
            }

            // 2. Open the remote folder.
            // TODO combine with common code in loadAttachment
            Account account = Account.restoreAccountWithId(mContext, message.mAccountKey);
            Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, message.mMailboxKey);
            if (account == null || mailbox == null) {
                //mListeners.loadMessageForViewFailed(messageId, "null account or mailbox");
                return;
            }
            TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(mContext, account));

            Store remoteStore = Store.getInstance(account, mContext);
            String remoteServerId = mailbox.mServerId;
            // If this is a search result, use the protocolSearchInfo field to get the
            // correct remote location
            if (!TextUtils.isEmpty(message.mProtocolSearchInfo)) {
                remoteServerId = message.mProtocolSearchInfo;
            }
            Folder remoteFolder = remoteStore.getFolder(remoteServerId);
            remoteFolder.open(OpenMode.READ_WRITE);

            // 3. Set up to download the entire message
            Message remoteMessage = remoteFolder.getMessage(message.mServerId);
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.BODY);
            remoteFolder.fetch(new Message[] { remoteMessage }, fp, null);

            // 4. Write to provider
            Utilities.copyOneMessageToProvider(mContext, remoteMessage, account, mailbox,
                    EmailContent.Message.FLAG_LOADED_COMPLETE);

            // 5. Notify UI
            mCallback.loadMessageStatus(messageId, 0, 100);

        } catch (MessagingException me) {
            if (Logging.LOGD) Log.v(Logging.LOG_TAG, "", me);
            mCallback.loadMessageStatus(messageId, EmailServiceStatus.REMOTE_EXCEPTION, 0);
        } catch (RuntimeException rte) {
            mCallback.loadMessageStatus(messageId, EmailServiceStatus.REMOTE_EXCEPTION, 0);
        }
    }

    private void doProgressCallback(long messageId, long attachmentId, int progress) {
        try {
            mCallback.loadAttachmentStatus(messageId, attachmentId,
                    EmailServiceStatus.IN_PROGRESS, progress);
        } catch (RemoteException e) {
            // No danger if the client is no longer around
        }
    }

    @Override
    public void loadAttachment(long attachmentId, boolean background) throws RemoteException {
        try {
            //1. Check if the attachment is already here and return early in that case
            Attachment attachment =
                Attachment.restoreAttachmentWithId(mContext, attachmentId);
            if (attachment == null) {
                mCallback.loadAttachmentStatus(0, attachmentId,
                        EmailServiceStatus.ATTACHMENT_NOT_FOUND, 0);
                return;
            }
            long messageId = attachment.mMessageKey;

            EmailContent.Message message =
                    EmailContent.Message.restoreMessageWithId(mContext, attachment.mMessageKey);
            if (message == null) {
                mCallback.loadAttachmentStatus(messageId, attachmentId,
                        EmailServiceStatus.MESSAGE_NOT_FOUND, 0);
            }

            // If the message is loaded, just report that we're finished
            if (Utility.attachmentExists(mContext, attachment)) {
                mCallback.loadAttachmentStatus(messageId, attachmentId, EmailServiceStatus.SUCCESS,
                        0);
                return;
            }

            // Say we're starting...
            doProgressCallback(messageId, attachmentId, 0);

            // 2. Open the remote folder.
            // TODO all of these could be narrower projections
            Account account = Account.restoreAccountWithId(mContext, message.mAccountKey);
            Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, message.mMailboxKey);

            if (account == null || mailbox == null) {
                // If the account/mailbox are gone, just report success; the UI handles this
                mCallback.loadAttachmentStatus(messageId, attachmentId,
                        EmailServiceStatus.SUCCESS, 0);
                return;
            }
            TrafficStats.setThreadStatsTag(
                    TrafficFlags.getAttachmentFlags(mContext, account));

            Store remoteStore = Store.getInstance(account, mContext);
            Folder remoteFolder = remoteStore.getFolder(mailbox.mServerId);
            remoteFolder.open(OpenMode.READ_WRITE);

            // 3. Generate a shell message in which to retrieve the attachment,
            // and a shell BodyPart for the attachment.  Then glue them together.
            Message storeMessage = remoteFolder.createMessage(message.mServerId);
            MimeBodyPart storePart = new MimeBodyPart();
            storePart.setSize((int)attachment.mSize);
            storePart.setHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA,
                    attachment.mLocation);
            storePart.setHeader(MimeHeader.HEADER_CONTENT_TYPE,
                    String.format("%s;\n name=\"%s\"",
                    attachment.mMimeType,
                    attachment.mFileName));
            // TODO is this always true for attachments?  I think we dropped the
            // true encoding along the way
            storePart.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING, "base64");

            MimeMultipart multipart = new MimeMultipart();
            multipart.setSubType("mixed");
            multipart.addBodyPart(storePart);

            storeMessage.setHeader(MimeHeader.HEADER_CONTENT_TYPE, "multipart/mixed");
            storeMessage.setBody(multipart);

            // 4. Now ask for the attachment to be fetched
            FetchProfile fp = new FetchProfile();
            fp.add(storePart);
            remoteFolder.fetch(new Message[] { storeMessage }, fp,
                    new MessageRetrievalListenerBridge(messageId, attachmentId));

            // If we failed to load the attachment, throw an Exception here, so that
            // AttachmentDownloadService knows that we failed
            if (storePart.getBody() == null) {
                throw new MessagingException("Attachment not loaded.");
            }

            // 5. Save the downloaded file and update the attachment as necessary
            LegacyConversions.saveAttachmentBody(mContext, storePart, attachment,
                    message.mAccountKey);

            // 6. Report success
            mCallback.loadAttachmentStatus(messageId, attachmentId, EmailServiceStatus.SUCCESS, 0);
        }
        catch (MessagingException me) {
            if (Logging.LOGD) Log.v(Logging.LOG_TAG, "", me);
            // TODO: Fix this up; consider the best approach
            mCallback.loadAttachmentStatus(0, attachmentId, EmailServiceStatus.CONNECTION_ERROR, 0);
        } catch (IOException ioe) {
            Log.e(Logging.LOG_TAG, "Error while storing attachment." + ioe.toString());
        }

    }

    /**
     * Bridge to intercept {@link MessageRetrievalListener#loadAttachmentProgress} and
     * pass down to {@link Result}.
     */
    public class MessageRetrievalListenerBridge implements MessageRetrievalListener {
        private final long mMessageId;
        private final long mAttachmentId;

        public MessageRetrievalListenerBridge(long messageId, long attachmentId) {
            mMessageId = messageId;
            mAttachmentId = attachmentId;
        }

        @Override
        public void loadAttachmentProgress(int progress) {
            doProgressCallback(mMessageId, mAttachmentId, progress);
        }

        @Override
        public void messageRetrieved(com.android.emailcommon.mail.Message message) {
        }
    }

    // TODO: Implement callback
    @Override
    public void updateFolderList(long accountId) throws RemoteException {
        Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) return;
        TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(mContext, account));
        Cursor localFolderCursor = null;
        try {
            // Step 1: Get remote mailboxes
            Store store = Store.getInstance(account, mContext);
            Folder[] remoteFolders = store.updateFolders();
            HashSet<String> remoteFolderNames = new HashSet<String>();
            for (int i = 0, count = remoteFolders.length; i < count; i++) {
                remoteFolderNames.add(remoteFolders[i].getName());
            }

            // Step 2: Get local mailboxes
            localFolderCursor = mContext.getContentResolver().query(
                    Mailbox.CONTENT_URI,
                    MAILBOX_PROJECTION,
                    EmailContent.MailboxColumns.ACCOUNT_KEY + "=?",
                    new String[] { String.valueOf(account.mId) },
                    null);

            // Step 3: Remove any local mailbox not on the remote list
            while (localFolderCursor.moveToNext()) {
                String mailboxPath = localFolderCursor.getString(MAILBOX_COLUMN_SERVER_ID);
                // Short circuit if we have a remote mailbox with the same name
                if (remoteFolderNames.contains(mailboxPath)) {
                    continue;
                }

                int mailboxType = localFolderCursor.getInt(MAILBOX_COLUMN_TYPE);
                long mailboxId = localFolderCursor.getLong(MAILBOX_COLUMN_ID);
                switch (mailboxType) {
                    case Mailbox.TYPE_INBOX:
                    case Mailbox.TYPE_DRAFTS:
                    case Mailbox.TYPE_OUTBOX:
                    case Mailbox.TYPE_SENT:
                    case Mailbox.TYPE_TRASH:
                    case Mailbox.TYPE_SEARCH:
                        // Never, ever delete special mailboxes
                        break;
                    default:
                        // Drop all attachment files related to this mailbox
                        AttachmentUtilities.deleteAllMailboxAttachmentFiles(
                                mContext, accountId, mailboxId);
                        // Delete the mailbox; database triggers take care of related
                        // Message, Body and Attachment records
                        Uri uri = ContentUris.withAppendedId(
                                Mailbox.CONTENT_URI, mailboxId);
                        mContext.getContentResolver().delete(uri, null, null);
                        break;
                }
            }
            //mListeners.listFoldersFinished(accountId);
        } catch (Exception e) {
            //mListeners.listFoldersFailed(accountId, e.toString());
        } finally {
            if (localFolderCursor != null) {
                localFolderCursor.close();
            }
        }
    }

    @Override
    public boolean createFolder(long accountId, String name) throws RemoteException {
        // Not required
        return false;
    }

    @Override
    public boolean deleteFolder(long accountId, String name) throws RemoteException {
        // Not required
        return false;
    }

    @Override
    public boolean renameFolder(long accountId, String oldName, String newName)
            throws RemoteException {
        // Not required
        return false;
    }

    @Override
    public void setCallback(IEmailServiceCallback cb) throws RemoteException {
        // Not required
    }

    @Override
    public void setLogging(int on) throws RemoteException {
        // Not required
    }

    @Override
    public void hostChanged(long accountId) throws RemoteException {
        // Not required
    }

    @Override
    public Bundle autoDiscover(String userName, String password) throws RemoteException {
        // Not required
       return null;
    }

    @Override
    public void sendMeetingResponse(long messageId, int response) throws RemoteException {
        // Not required
    }

    @Override
    public void deleteAccountPIMData(long accountId) throws RemoteException {
        MailService.reconcilePopImapAccountsSync(mContext);
    }

    @Override
    public int getApiLevel() throws RemoteException {
        return Api.LEVEL;
    }

    @Override
    public int searchMessages(long accountId, SearchParams params, long destMailboxId)
            throws RemoteException {
        // Not required
        return 0;
    }

    @Override
    public void sendMail(long accountId) throws RemoteException {
        Account account = Account.restoreAccountWithId(mContext, accountId);
        TrafficStats.setThreadStatsTag(TrafficFlags.getSmtpFlags(mContext, account));
        NotificationController nc = NotificationController.getInstance(mContext);
        // 1.  Loop through all messages in the account's outbox
        long outboxId = Mailbox.findMailboxOfType(mContext, account.mId, Mailbox.TYPE_OUTBOX);
        if (outboxId == Mailbox.NO_MAILBOX) {
            return;
        }
        ContentResolver resolver = mContext.getContentResolver();
        Cursor c = resolver.query(EmailContent.Message.CONTENT_URI,
                EmailContent.Message.ID_COLUMN_PROJECTION,
                EmailContent.Message.MAILBOX_KEY + "=?", new String[] { Long.toString(outboxId) },
                null);
        try {
            // 2.  exit early
            if (c.getCount() <= 0) {
                return;
            }
            Sender sender = Sender.getInstance(mContext, account);
            Store remoteStore = Store.getInstance(account, mContext);
            boolean requireMoveMessageToSentFolder = remoteStore.requireCopyMessageToSentFolder();
            ContentValues moveToSentValues = null;
            if (requireMoveMessageToSentFolder) {
                Mailbox sentFolder =
                    Mailbox.restoreMailboxOfType(mContext, accountId, Mailbox.TYPE_SENT);
                moveToSentValues = new ContentValues();
                moveToSentValues.put(MessageColumns.MAILBOX_KEY, sentFolder.mId);
            }

            // 3.  loop through the available messages and send them
            while (c.moveToNext()) {
                long messageId = -1;
                try {
                    messageId = c.getLong(0);
                    // Don't send messages with unloaded attachments
                    if (Utility.hasUnloadedAttachments(mContext, messageId)) {
                        if (Email.DEBUG) {
                            Log.d(Logging.LOG_TAG, "Can't send #" + messageId +
                                    "; unloaded attachments");
                        }
                        continue;
                    }
                    sender.sendMessage(messageId);
                } catch (MessagingException me) {
                    // report error for this message, but keep trying others
                    if (me instanceof AuthenticationFailedException) {
                        nc.showLoginFailedNotification(account.mId);
                    }
                    continue;
                }
                // 4. move to sent, or delete
                Uri syncedUri =
                    ContentUris.withAppendedId(EmailContent.Message.SYNCED_CONTENT_URI, messageId);
                if (requireMoveMessageToSentFolder) {
                    // If this is a forwarded message and it has attachments, delete them, as they
                    // duplicate information found elsewhere (on the server).  This saves storage.
                    EmailContent.Message msg =
                        EmailContent.Message.restoreMessageWithId(mContext, messageId);
                    if (msg != null &&
                            ((msg.mFlags & EmailContent.Message.FLAG_TYPE_FORWARD) != 0)) {
                        AttachmentUtilities.deleteAllAttachmentFiles(mContext, account.mId,
                                messageId);
                    }
                    resolver.update(syncedUri, moveToSentValues, null, null);
                } else {
                    AttachmentUtilities.deleteAllAttachmentFiles(mContext, account.mId,
                            messageId);
                    Uri uri =
                        ContentUris.withAppendedId(EmailContent.Message.CONTENT_URI, messageId);
                    resolver.delete(uri, null, null);
                    resolver.delete(syncedUri, null, null);
                }
            }
            nc.cancelLoginFailedNotification(account.mId);
        } catch (MessagingException me) {
            if (me instanceof AuthenticationFailedException) {
                nc.showLoginFailedNotification(account.mId);
            }
        } finally {
            c.close();
        }
    }
}
