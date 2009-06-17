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

import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;
import com.android.email.provider.EmailContent;

import android.content.Context;

import java.util.HashSet;

/**
 * New central controller/dispatcher for Email activities that may require remote operations.
 * Handles disambiguating between legacy MessagingController operations and newer provider/sync
 * based code.
 */
public class Controller {

    static Controller sInstance;
    private Context mContext;
    private MessagingController mLegacyController;
    private HashSet<Result> mListeners = new HashSet<Result>();
    
    protected Controller(Context _context) {
        mContext = _context;
        mLegacyController = MessagingController.getInstance(mContext);
    }

    /**
     * Gets or creates the singleton instance of Controller. Application is used to
     * provide a Context to classes that need it.
     * @param _context
     */
    public synchronized static Controller getInstance(Context _context) {
        if (sInstance == null) {
            sInstance = new Controller(_context);
        }
        return sInstance;
    }
    
    /**
     * Any UI code that wishes for callback results (on async ops) should register their callback
     * here (typically from onResume()).  Unregistered callbacks will never be called, to prevent
     * problems when the command completes and the activity has already paused or finished.
     * @param listener The callback that may be used in action methods
     */
    public void addResultCallback(Result listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
        }
    }

    /**
     * Any UI code that no longer wishes for callback results (on async ops) should unregister
     * their callback here (typically from onPause()).  Unregistered callbacks will never be called,
     * to prevent problems when the command completes and the activity has already paused or
     * finished.
     * @param listener The callback that may no longer be used
     */
    public void removeResultCallback(Result listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }
    
    private boolean isActiveResultCallback(Result listener) {
        synchronized (mListeners) {
            return mListeners.contains(listener);
        }
    }

    /**
     * Request a remote update of mailboxes for an account.
     * 
     * TODO: Implement (if any) for non-MessagingController
     * TODO: Probably the right way is to create a fake "service" for MessagingController ops
     */
    public void updateMailboxList(final EmailContent.Account account, final Result callback) {
        
        // 1. determine if we can use MessagingController for this
        boolean legacyController = isMessagingController(account);
        
        // 2. if not...?
        // TODO: for now, just pretend "it worked"
        if (!legacyController) {
            if (callback != null) {
                callback.onResult(null, account.mId, -1, -1);
            }
            return;
        }
        
        // 3. if so, make the call
        new Thread() {
            @Override
            public void run() {
                MessagingListener listener = new LegacyListener(callback);
                mLegacyController.addListener(listener);
                mLegacyController.listFolders(account, listener);
            }
        }.start();
    }
    
    /**
     * Simple helper to determine if legacy MessagingController should be used
     */
    private boolean isMessagingController(EmailContent.Account account) {
        Store.StoreInfo info =
            Store.StoreInfo.getStoreInfo(account.getStoreUri(mContext), mContext);
        String scheme = info.mScheme;
        
        return ("pop3".equals(scheme) || "imap".equals(scheme));
    }
    
    /**
     * Simple callback for synchronous commands.  For many commands, this can be largely ignored
     * and the result is observed via provider cursors.  The callback will *not* necessarily be
     * made from the UI thread, so you may need further handlers to safely make UI updates.
     */
    public interface Result {
        
        /**
         * Callback for operations affecting an account, mailbox, or message
         * 
         * @param result If null, the operation completed without error
         * @param accountKey The account being operated on
         * @param mailboxKey The mailbox being operated on, or -1 if account-wide
         * @param messageKey The message being operated on, or -1 if mailbox- or account- wide.
         */
        public void onResult(MessagingException result,
                long accountKey, long mailboxKey, long messageKey);
    }
    
    /**
     * Support for receiving callbacks from MessagingController and dealing with UI going
     * out of scope.
     */
    private class LegacyListener extends MessagingListener {
        Result mResultCallback;
        
        public LegacyListener(Result callback) {
            mResultCallback = callback;
        }
        
        @Override
        public void listFoldersFailed(EmailContent.Account account, String message) {
            if (mResultCallback != null && isActiveResultCallback(mResultCallback)) {
                mResultCallback.onResult(new MessagingException(message), account.mId, -1, -1);
            }
            mLegacyController.removeListener(this);
        }

        @Override
        public void listFoldersFinished(EmailContent.Account account) {
            if (mResultCallback != null && isActiveResultCallback(mResultCallback)) {
                mResultCallback.onResult(null, account.mId, -1, -1);
            }
            mLegacyController.removeListener(this);
        }
    }
    

}
