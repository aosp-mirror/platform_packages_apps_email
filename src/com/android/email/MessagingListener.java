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

import android.content.Context;

import com.android.email.mail.Folder;
import com.android.email.mail.Message;
import com.android.email.mail.Part;

/**
 * Defines the interface that MessagingController will use to callback to requesters. This class
 * is defined as non-abstract so that someone who wants to receive only a few messages can
 * do so without implementing the entire interface. It is highly recommended that users of
 * this interface use the @Override annotation in their implementations to avoid being caught by
 * changes in this class.
 */
public class MessagingListener {
    public void listFoldersStarted(Account account) {
    }

    public void listFolders(Account account, Folder[] folders) {
    }

    public void listFoldersFailed(Account account, String message) {
    }

    public void listFoldersFinished(Account account) {
    }

    public void listLocalMessagesStarted(Account account, String folder) {
    }

    public void listLocalMessages(Account account, String folder, Message[] messages) {
    }

    public void listLocalMessagesFailed(Account account, String folder, String message) {
    }

    public void listLocalMessagesFinished(Account account, String folder) {
    }

    public void synchronizeMailboxStarted(Account account, String folder) {
    }

    public void synchronizeMailboxNewMessage(Account account, String folder, Message message) {
    }

    public void synchronizeMailboxRemovedMessage(Account account, String folder,Message message) {
    }

    public void synchronizeMailboxFinished(Account account, String folder,
            int totalMessagesInMailbox, int numNewMessages) {
    }

    public void synchronizeMailboxFailed(Account account, String folder, Exception e) {
    }

    public void loadMessageForViewStarted(Account account, String folder, String uid) {
    }

    public void loadMessageForViewHeadersAvailable(Account account, String folder, String uid,
            Message message) {
    }

    public void loadMessageForViewBodyAvailable(Account account, String folder, String uid,
            Message message) {
    }

    public void loadMessageForViewFinished(Account account, String folder, String uid,
            Message message) {
    }

    public void loadMessageForViewFailed(Account account, String folder, String uid, String message) {
    }

    public void checkMailStarted(Context context, Account account) {
    }

    public void checkMailFinished(Context context, Account account) {
    }

    public void checkMailFailed(Context context, Account account, String reason) {
    }

    public void sendPendingMessagesCompleted(Account account) {
    }

    public void emptyTrashCompleted(Account account) {
    }

    public void messageUidChanged(Account account, String folder, String oldUid, String newUid) {

    }

    public void loadAttachmentStarted(
            Account account,
            Message message,
            Part part,
            Object tag,
            boolean requiresDownload)
    {
    }

    public void loadAttachmentFinished(
            Account account,
            Message message,
            Part part,
            Object tag)
    {
    }

    public void loadAttachmentFailed(
            Account account,
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
