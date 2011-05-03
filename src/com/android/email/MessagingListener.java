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

import com.android.emailcommon.mail.MessagingException;

import android.content.Context;

import java.util.ArrayList;

/**
 * Defines the interface that MessagingController will use to callback to requesters. This class
 * is defined as non-abstract so that someone who wants to receive only a few messages can
 * do so without implementing the entire interface. It is highly recommended that users of
 * this interface use the @Override annotation in their implementations to avoid being caught by
 * changes in this class.
 */
public class MessagingListener {
    public MessagingListener() {
    }

    public void listFoldersStarted(long accountId) {
    }

    public void listFoldersFailed(long accountId, String message) {
    }

    public void listFoldersFinished(long accountId) {
    }

    public void synchronizeMailboxStarted(long accountId, long mailboxId) {
    }

    /**
     * Synchronization of the mailbox finished. The mailbox and/or message databases have been
     * updated accordingly.
     *
     * @param accountId The account that was synchronized
     * @param mailboxId The mailbox that was synchronized
     * @param totalMessagesInMailbox The total number of messages in the mailbox
     * @param numNewMessages The number of new messages
     * @param addedMessages Message IDs of messages that were added during the synchronization.
     * These are new, unread messages. Messages that were previously read are not in this list.
     */
    public void synchronizeMailboxFinished(long accountId, long mailboxId,
            int totalMessagesInMailbox, int numNewMessages, ArrayList<Long> addedMessages) {
    }

    public void synchronizeMailboxFailed(long accountId, long mailboxId, Exception e) {
    }

    public void loadMessageForViewStarted(long messageId) {
    }

    public void loadMessageForViewFinished(long messageId) {
    }

    public void loadMessageForViewFailed(long messageId, String message) {
    }

    public void checkMailStarted(Context context, long accountId, long tag) {
    }

    public void checkMailFinished(Context context, long accountId, long mailboxId, long tag) {
    }

    public void sendPendingMessagesStarted(long accountId, long messageId) {
    }

    public void sendPendingMessagesCompleted(long accountId) {
    }

    public void sendPendingMessagesFailed(long accountId, long messageId, Exception reason) {
    }

    public void messageUidChanged(long accountId, long mailboxId, String oldUid, String newUid) {
    }

    public void loadAttachmentStarted(
            long accountId,
            long messageId,
            long attachmentId,
            boolean requiresDownload) {
    }

    public void loadAttachmentFinished(
            long accountId,
            long messageId,
            long attachmentId) {
    }

    public void loadAttachmentFailed(
            long accountId,
            long messageId,
            long attachmentId,
            MessagingException me,
            boolean background) {
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
