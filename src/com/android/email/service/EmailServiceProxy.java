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

package com.android.email.service;

import com.android.email.mail.MessagingException;
import com.android.exchange.IEmailService;
import com.android.exchange.IEmailServiceCallback;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * The EmailServiceProxy class provides a simple interface for the UI to call into the various
 * EmailService classes (e.g. SyncManager for EAS).  It wraps the service connect/disconnect
 * process so that the caller need not be concerned with it.
 *
 * Use the class like this:
 *   new EmailServiceClass(context, class).loadAttachment(attachmentId, callback)
 *
 * Methods without a return value return immediately (i.e. are asynchronous); methods with a
 * return value wait for a result from the Service (i.e. they should not be called from the UI
 * thread) with a default timeout of 30 seconds (settable)
 *
 * An EmailServiceProxy object cannot be reused (trying to do so generates a RemoteException)
 */

public class EmailServiceProxy implements IEmailService {
    private static final boolean DEBUG_PROXY = false; // DO NOT CHECK THIS IN SET TO TRUE
    private static final String TAG = "EmailServiceProxy";

    private Context mContext;
    private Class<?> mClass;
    private Runnable mRunnable;
    private ServiceConnection mSyncManagerConnection = new EmailServiceConnection ();
    private IEmailService mService = null;
    private Object mReturn = null;
    private int mTimeout = 30;
    private boolean mDead = false;

    public EmailServiceProxy(Context _context, Class<?> _class) {
        mContext = _context;
        mClass = _class;
    }

    class EmailServiceConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = IEmailService.Stub.asInterface(binder);
            if (DEBUG_PROXY) {
                Log.v(TAG, "Service " + mClass.getSimpleName() + " connected");
            }
            runTask();
        }

        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG_PROXY) {
                Log.v(TAG, "Service " + mClass.getSimpleName() + " disconnected");
            }
        }
    }

    public EmailServiceProxy setTimeout(int secs) {
        mTimeout = secs;
        return this;
    }

    private void runTask() {
        Thread thread = new Thread(mRunnable);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
        }
        mContext.unbindService(mSyncManagerConnection);
        mDead = true;
        synchronized(mSyncManagerConnection) {
            if (DEBUG_PROXY) {
                Log.v(TAG, "Service task completed; disconnecting");
            }
            mSyncManagerConnection.notify();
        }
    }

    private void setTask(Runnable runnable) throws RemoteException {
        if (mDead) {
            throw new RemoteException();
        }
        mRunnable = runnable;
        if (DEBUG_PROXY) {
            Log.v(TAG, "Service " + mClass.getSimpleName() + " bind requested");
        }
        mContext.bindService(new Intent(mContext, mClass), mSyncManagerConnection,
                Context.BIND_AUTO_CREATE);
    }

    public void waitForCompletion() {
        synchronized (mSyncManagerConnection) {
            long time = System.currentTimeMillis();
            try {
                if (DEBUG_PROXY) {
                    Log.v(TAG, "Waiting for task to complete...");
                }
                mSyncManagerConnection.wait(mTimeout * 1000L);
            } catch (InterruptedException e) {
                // Can be ignored safely
            }
            if (DEBUG_PROXY) {
                Log.v(TAG, "Wait finished in " + (System.currentTimeMillis() - time) + "ms");
            }
        }
    }

    public boolean createFolder(long accountId, String name) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean deleteFolder(long accountId, String name) throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean renameFolder(long accountId, String oldName, String newName)
            throws RemoteException {
        // TODO Auto-generated method stub
        return false;
    }

    public void loadAttachment(final long attachmentId, final IEmailServiceCallback cb)
            throws RemoteException {
        setTask(new Runnable () {
            public void run() {
                try {
                    mService.loadAttachment(attachmentId, cb);
                } catch (RemoteException e) {
                }
            }
        });
        Log.v(TAG, "loadAttachment finished");
    }

    public void loadMore(long messageId, IEmailServiceCallback cb) throws RemoteException {
        // TODO Auto-generated method stub
    }

    public void startSync(long mailboxId) throws RemoteException {
        // TODO Auto-generated method stub
    }

    public void stopSync(long mailboxId) throws RemoteException {
        // TODO Auto-generated method stub
    }

    public void updateFolderList(long accountId) throws RemoteException {
        // TODO Auto-generated method stub
    }

    public int validate(final String protocol, final String host, final String userName,
            final String password, final int port, final boolean ssl) throws RemoteException {
        setTask(new Runnable () {
            public void run() {
                try {
                    mReturn = mService.validate(protocol, host, userName, password, port, ssl);
                } catch (RemoteException e) {
                }
            }
        });
        waitForCompletion();
        if (mReturn == null) {
            return MessagingException.UNSPECIFIED_EXCEPTION;
        } else {
            Log.v(TAG, "validate returns " + mReturn);
            return (Integer)mReturn;
        }
    }

    public IBinder asBinder() {
        return null;
    }
}
