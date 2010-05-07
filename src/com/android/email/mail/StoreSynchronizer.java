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

package com.android.email.mail;

import com.android.email.MessagingListener;
import com.android.email.provider.EmailContent;
import com.android.email.GroupMessagingListener;

import android.content.Context;

/**
 * This interface allows a store to define a completely different synchronizer algorithm,
 * as necessary.
 */
public interface StoreSynchronizer {
    
    /**
     * An object of this class is returned by SynchronizeMessagesSynchronous to report
     * the results of the sync run.
     */
    public static class SyncResults {
        /**
         * The total # of messages in the folder
         */
        public int mTotalMessages;
        /**
         * The # of new messages in the folder
         */
        public int mNewMessages;
        
        public SyncResults(int totalMessages, int newMessages) {
            mTotalMessages = totalMessages;
            mNewMessages = newMessages;
        }
    }
    
    /**
     * The job of this method is to synchronize messages between a remote folder and the
     * corresponding local folder.
     * 
     * The following callbacks should be called during this operation:
     *  {@link MessagingListener#synchronizeMailboxNewMessage(Account, String, Message)}
     *  {@link MessagingListener#synchronizeMailboxRemovedMessage(Account, String, Message)}
     *  
     * Callbacks (through listeners) *must* be synchronized on the listeners object, e.g.
     *   synchronized (listeners) {
     *       for(MessagingListener listener : listeners) {
     *           listener.synchronizeMailboxNewMessage(account, folder, message);
     *       }
     *   }
     *
     * @param account The account to synchronize
     * @param folder The folder to synchronize
     * @param listeners callbacks to make during sync operation
     * @param context if needed for making system calls
     * @return an object describing the sync results
     */
    public SyncResults SynchronizeMessagesSynchronous(
            EmailContent.Account account, EmailContent.Mailbox folder,
            GroupMessagingListener listeners, Context context) throws MessagingException;
    
}
