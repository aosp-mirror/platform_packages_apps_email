/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.android.email.LegacyConversions;
import com.android.emailcommon.Logging;
import com.android.emailcommon.internet.MimeBodyPart;
import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.mail.Message;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.mail.Part;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.ConversionUtilities;
import com.android.mail.providers.Attachment;

import java.io.IOException;
import java.util.ArrayList;

public class Utilities {
    /**
     * Copy one downloaded message (which may have partially-loaded sections)
     * into a newly created EmailProvider Message, given the account and mailbox
     *
     * @param message the remote message we've just downloaded
     * @param account the account it will be stored into
     * @param folder the mailbox it will be stored into
     * @param loadStatus when complete, the message will be marked with this status (e.g.
     *        EmailContent.Message.LOADED)
     */
    public static void copyOneMessageToProvider(Context context, Message message, Account account,
            Mailbox folder, int loadStatus) {
        EmailContent.Message localMessage = null;
        Cursor c = null;
        try {
            c = context.getContentResolver().query(
                    EmailContent.Message.CONTENT_URI,
                    EmailContent.Message.CONTENT_PROJECTION,
                    EmailContent.MessageColumns.ACCOUNT_KEY + "=?" +
                            " AND " + MessageColumns.MAILBOX_KEY + "=?" +
                            " AND " + SyncColumns.SERVER_ID + "=?",
                            new String[] {
                            String.valueOf(account.mId),
                            String.valueOf(folder.mId),
                            String.valueOf(message.getUid())
                    },
                    null);
            if (c == null) {
                return;
            } else if (c.moveToNext()) {
                localMessage = EmailContent.getContent(c, EmailContent.Message.class);
            } else {
                localMessage = new EmailContent.Message();
            }
            localMessage.mMailboxKey = folder.mId;
            localMessage.mAccountKey = account.mId;
            copyOneMessageToProvider(context, message, localMessage, loadStatus);
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * Copy one downloaded message (which may have partially-loaded sections)
     * into an already-created EmailProvider Message
     *
     * @param message the remote message we've just downloaded
     * @param localMessage the EmailProvider Message, already created
     * @param loadStatus when complete, the message will be marked with this status (e.g.
     *        EmailContent.Message.LOADED)
     * @param context the context to be used for EmailProvider
     */
    public static void copyOneMessageToProvider(Context context, Message message,
            EmailContent.Message localMessage, int loadStatus) {
        try {

            EmailContent.Body body = null;
            if (localMessage.mId != EmailContent.Message.NO_MESSAGE) {
                body = EmailContent.Body.restoreBodyWithMessageId(context, localMessage.mId);
            }
            if (body == null) {
                body = new EmailContent.Body();
            }
            try {
                // Copy the fields that are available into the message object
                LegacyConversions.updateMessageFields(localMessage, message,
                        localMessage.mAccountKey, localMessage.mMailboxKey);

                // Now process body parts & attachments
                ArrayList<Part> viewables = new ArrayList<Part>();
                ArrayList<Part> attachments = new ArrayList<Part>();
                MimeUtility.collectParts(message, viewables, attachments);

                ConversionUtilities.updateBodyFields(body, localMessage, viewables);

                // Commit the message & body to the local store immediately
                saveOrUpdate(localMessage, context);
                body.mMessageKey = localMessage.mId;
                saveOrUpdate(body, context);

                // process (and save) attachments
                if (loadStatus != EmailContent.Message.FLAG_LOADED_UNKNOWN) {
                    LegacyConversions.updateAttachments(context, localMessage, attachments);
                } else {
                    EmailContent.Attachment att = new EmailContent.Attachment();
                    // STOPSHIP: Redo UI or localize
                    att.mFileName = "Click to load entire message";
                    att.mSize = message.getSize();
                    att.mMimeType = "text/plain";
                    att.mMessageKey = localMessage.mId;
                    att.mAccountKey = localMessage.mAccountKey;
                    att.save(context);
                    localMessage.mFlagAttachment = true;
                }

                // One last update of message with two updated flags
                localMessage.mFlagLoaded = loadStatus;

                ContentValues cv = new ContentValues();
                cv.put(EmailContent.MessageColumns.FLAG_ATTACHMENT, localMessage.mFlagAttachment);
                cv.put(EmailContent.MessageColumns.FLAG_LOADED, localMessage.mFlagLoaded);
                Uri uri = ContentUris.withAppendedId(EmailContent.Message.CONTENT_URI,
                        localMessage.mId);
                context.getContentResolver().update(uri, cv, null, null);

            } catch (MessagingException me) {
                Log.e(Logging.LOG_TAG, "Error while copying downloaded message." + me);
            }

        } catch (RuntimeException rte) {
            Log.e(Logging.LOG_TAG, "Error while storing downloaded message." + rte.toString());
        } catch (IOException ioe) {
            Log.e(Logging.LOG_TAG, "Error while storing attachment." + ioe.toString());
        }
    }

    public static void saveOrUpdate(EmailContent content, Context context) {
        if (content.isSaved()) {
            content.update(context, content.toContentValues());
        } else {
            content.save(context);
        }
    }

}
