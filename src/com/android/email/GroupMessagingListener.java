/*
 * Copyright (C) 2009 The Android Open Source Project
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

import com.android.email.provider.EmailContent;
import com.android.email.mail.Message;
import com.android.email.mail.Part;
import android.content.Context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public class GroupMessagingListener extends MessagingListener {
    /* The synchronization of the methods in this class
       is not needed because we use ConcurrentHashMap.
       
       Nevertheless, let's keep the "synchronized" for a while in the case
       we may want to change the implementation to use something else
       than ConcurrentHashMap.
    */

    private ConcurrentHashMap<MessagingListener, Object> mListenersMap =
        new ConcurrentHashMap<MessagingListener, Object>();

    private Set<MessagingListener> mListeners = mListenersMap.keySet();

    synchronized public void addListener(MessagingListener listener) {
        // we use "this" as a dummy non-null value
        mListenersMap.put(listener, this);
    }

    synchronized public void removeListener(MessagingListener listener) {
        mListenersMap.remove(listener);
    }

    synchronized public boolean isActiveListener(MessagingListener listener) {
        return mListenersMap.contains(listener);
    }

    @Override
    synchronized public void listFoldersStarted(EmailContent.Account account) {
        for (MessagingListener l : mListeners) {
            l.listFoldersStarted(account);
        }
    }

    @Override
    synchronized public void listFoldersFailed(EmailContent.Account account, String message) {
        for (MessagingListener l : mListeners) {
            l.listFoldersFailed(account, message);
        }
    }

    @Override
    synchronized public void listFoldersFinished(EmailContent.Account account) {
        for (MessagingListener l : mListeners) {
            l.listFoldersFinished(account);
        }
    }

    @Override
    synchronized public void synchronizeMailboxStarted(EmailContent.Account account, 
                                                       EmailContent.Mailbox folder) {
        for (MessagingListener l : mListeners) {
            l.synchronizeMailboxStarted(account, folder);
        }
    }

    @Override
    synchronized public void synchronizeMailboxFinished(EmailContent.Account account,
            EmailContent.Mailbox folder, int totalMessagesInMailbox, int numNewMessages) {
        for (MessagingListener l : mListeners) {
            l.synchronizeMailboxFinished(account, folder, totalMessagesInMailbox, numNewMessages);
        }
    }

    @Override
    synchronized public void synchronizeMailboxFailed(EmailContent.Account account, 
                                                      EmailContent.Mailbox folder,
                                                      Exception e) {
        for (MessagingListener l : mListeners) {
            l.synchronizeMailboxFailed(account, folder, e);
        }
    }

    @Override
    synchronized public void loadMessageForViewStarted(EmailContent.Account account, String folder,
                                                       String uid) {
        for (MessagingListener l : mListeners) {
            l.loadMessageForViewStarted(account, folder, uid);
        }
    }

    @Override
    synchronized public void loadMessageForViewHeadersAvailable(EmailContent.Account account,
            String folder, String uid, Message message) {
        for (MessagingListener l : mListeners) {
            l.loadMessageForViewHeadersAvailable(account, folder, uid, message);
        }
    }

    @Override
    synchronized public void loadMessageForViewBodyAvailable(EmailContent.Account account,
            String folder, String uid, Message message) {
        for (MessagingListener l : mListeners) {
            l.loadMessageForViewBodyAvailable(account, folder, uid, message);
        }
    }

    @Override
    synchronized public void loadMessageForViewFinished(EmailContent.Account account,
            String folder, String uid, Message message) {
        for (MessagingListener l : mListeners) {
            l.loadMessageForViewFinished(account, folder, uid, message);
        }
    }

    @Override
    synchronized public void loadMessageForViewFailed(EmailContent.Account account, String folder,
            String uid, String message) {
        for (MessagingListener l : mListeners) {
            l.loadMessageForViewFailed(account, folder, uid, message);
        }
    }

    @Override
    synchronized public void checkMailStarted(Context context, EmailContent.Account account) {
        for (MessagingListener l : mListeners) {
            l.checkMailStarted(context, account);
        }
    }

    @Override
    synchronized public void checkMailFinished(Context context, EmailContent.Account account) {
        for (MessagingListener l : mListeners) {
            l.checkMailFinished(context, account);
        }
    }

    @Override
    synchronized public void checkMailFailed(Context context, EmailContent.Account account,
            String reason) {
        // TODO
    }

    @Override
    synchronized public void sendPendingMessagesCompleted(EmailContent.Account account) {
        for (MessagingListener l : mListeners) {
            l.sendPendingMessagesCompleted(account);
        }
    }

    @Override
    synchronized public void sendPendingMessagesFailed(EmailContent.Account account,
            Exception reason) {
        for (MessagingListener l : mListeners) {
            l.sendPendingMessagesFailed(account, reason);
        }
    }

    @Override
    synchronized public void sendPendingMessageFailed(EmailContent.Account account,
            Message message, Exception reason) {
        for (MessagingListener l : mListeners) {
            l.sendPendingMessageFailed(account, message, reason);
        }
    }

    @Override
    synchronized public void emptyTrashCompleted(EmailContent.Account account) {
        for (MessagingListener l : mListeners) {
            l.emptyTrashCompleted(account);
        }
    }

    @Override
    synchronized public void messageUidChanged(EmailContent.Account account, String folder,
            String oldUid, String newUid) {
        for (MessagingListener l : mListeners) {
            l.messageUidChanged(account, folder, oldUid, newUid);
        }
    }

    @Override
    synchronized public void loadAttachmentStarted(
            EmailContent.Account account,
            Message message,
            Part part,
            Object tag,
            boolean requiresDownload) {
        for (MessagingListener l : mListeners) {
            l.loadAttachmentStarted(account, message, part, tag, requiresDownload);
        }
    }

    @Override
    synchronized public void loadAttachmentFinished(
            EmailContent.Account account,
            Message message,
            Part part,
            Object tag) {
        for (MessagingListener l : mListeners) {
            l.loadAttachmentFinished(account, message, part, tag);
        }
    }

    @Override
    synchronized public void loadAttachmentFailed(
            EmailContent.Account account,
            Message message,
            Part part,
            Object tag,
            String reason) {
        for (MessagingListener l : mListeners) {
            l.loadAttachmentFailed(account, message, part, tag, reason);
        }
    }

    @Override
    synchronized public void controllerCommandCompleted(boolean moreCommandsToRun) {
        for (MessagingListener l : mListeners) {
            l.controllerCommandCompleted(moreCommandsToRun);
        }
    }
}
