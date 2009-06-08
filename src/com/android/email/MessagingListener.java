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

package com.android.email;

import com.android.email.mail.Folder;
import com.android.email.mail.Message;
import com.android.email.mail.Part;
import com.android.email.provider.EmailStore;

import android.content.Context;

/**
 * Defines the interface that MessagingController will use to callback to requesters. This class
 * is defined as non-abstract so that someone who wants to receive only a few messages can
 * do so without implementing the entire interface. It is highly recommended that users of
 * this interface use the @Override annotation in their implementations to avoid being caught by
 * changes in this class.
 */
public class MessagingListener {
    public void listFoldersStarted(EmailStore.Account account) {
    }

    public void listFolders(EmailStore.Account account, Folder[] folders) {
    }

    public void listFoldersFailed(EmailStore.Account account, String message) {
    }

    public void listFoldersFinished(EmailStore.Account account) {
    }

    public void listLocalMessagesStarted(EmailStore.Account account, String folder) {
    }

    public void listLocalMessages(EmailStore.Account account, String folder, Message[] messages) {
    }

    public void listLocalMessagesFailed(EmailStore.Account account, String folder, String message) {
    }

    public void listLocalMessagesFinished(EmailStore.Account account, String folder) {
    }

    public void synchronizeMailboxStarted(EmailStore.Account account, String folder) {
    }

    public void synchronizeMailboxNewMessage(EmailStore.Account account, String folder,
            Message message) {
    }

    public void synchronizeMailboxRemovedMessage(EmailStore.Account account, String folder,
            Message message) {
    }

    public void synchronizeMailboxFinished(EmailStore.Account account, String folder,
            int totalMessagesInMailbox, int numNewMessages) {
    }

    public void synchronizeMailboxFailed(EmailStore.Account account, String folder, Exception e) {
    }

    public void loadMessageForViewStarted(EmailStore.Account account, String folder, String uid) {
    }

    public void loadMessageForViewHeadersAvailable(EmailStore.Account account, String folder,
            String uid, Message message) {
    }

    public void loadMessageForViewBodyAvailable(EmailStore.Account account, String folder,
            String uid, Message message) {
    }

    public void loadMessageForViewFinished(EmailStore.Account account, String folder, String uid,
            Message message) {
    }

    public void loadMessageForViewFailed(EmailStore.Account account, String folder, String uid,
            String message) {
    }

    public void checkMailStarted(Context context, EmailStore.Account account) {
    }

    public void checkMailFinished(Context context, EmailStore.Account account) {
    }

    public void checkMailFailed(Context context, EmailStore.Account account, String reason) {
    }

    public void sendPendingMessagesCompleted(EmailStore.Account account) {
    }

    public void sendPendingMessagesFailed(EmailStore.Account account, Exception reason) {
    }

    public void sendPendingMessageFailed(EmailStore.Account account, Message message,
            Exception reason) {
    }

    public void emptyTrashCompleted(EmailStore.Account account) {
    }

    public void messageUidChanged(EmailStore.Account account, String folder,
            String oldUid, String newUid) {
    }

    public void loadAttachmentStarted(
            EmailStore.Account account,
            Message message,
            Part part,
            Object tag,
            boolean requiresDownload)
    {
    }

    public void loadAttachmentFinished(
            EmailStore.Account account,
            Message message,
            Part part,
            Object tag)
    {
    }

    public void loadAttachmentFailed(
            EmailStore.Account account,
            Message message,
            Part part,
            Object tag,
            String reason)
    {
    }

    /**
     * General notification messages subclasses can override to be notified that the controller
     * has completed a command. This is useful for turning off progress indicators that may have
     * been left over from previous commands.
     * @param moreCommandsToRun True if the controller will continue on to another command
     * immediately.
     */
    public void controllerCommandCompleted(boolean moreCommandsToRun) {

    }
}
