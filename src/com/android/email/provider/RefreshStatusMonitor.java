package com.android.email.provider;

import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.StorageLowState;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.text.format.DateUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * This class implements a singleton that monitors a mailbox refresh activated by the user.
 * The refresh requests a sync but sometimes the sync doesn't happen till much later. This class
 * checks if a sync has been started for a specific mailbox. It checks for no network connectivity
 * and low storage conditions which prevent a sync and notifies the the caller using a callback.
 * If no sync is started after a certain timeout, it gives up and notifies the caller.
 */
public class RefreshStatusMonitor {
    private static final String TAG = LogTag.getLogTag();

    private static final int REMOVE_REFRESH_STATUS_DELAY_MS = 250;
    public static final long REMOVE_REFRESH_TIMEOUT_MS = DateUtils.MINUTE_IN_MILLIS;
    private static final int MAX_RETRY =
            (int) (REMOVE_REFRESH_TIMEOUT_MS / REMOVE_REFRESH_STATUS_DELAY_MS);

    private static RefreshStatusMonitor sInstance = null;
    private final Handler mHandler;
    private boolean mIsStorageLow = false;
    private final Map<Long, Boolean> mMailboxSync = new HashMap<Long, Boolean>();

    private final Context mContext;

    public static RefreshStatusMonitor getInstance(Context context) {
        synchronized (RefreshStatusMonitor.class) {
            if (sInstance == null) {
                sInstance = new RefreshStatusMonitor(context.getApplicationContext());
            }
        }
        return sInstance;
    }

    private RefreshStatusMonitor(Context context) {
        mContext = context;
        mHandler = new Handler(mContext.getMainLooper());
        StorageLowState.registerHandler(new StorageLowState
                    .LowStorageHandler() {
                @Override
                public void onStorageLow() {
                    mIsStorageLow = true;
                }

                @Override
                public void onStorageOk() {
                    mIsStorageLow = false;
                }
            });
    }

    public void monitorRefreshStatus(long mailboxId, Callback callback) {
        synchronized (mMailboxSync) {
            if (!mMailboxSync.containsKey(mailboxId))
                mMailboxSync.put(mailboxId, false);
                mHandler.postDelayed(
                        new RemoveRefreshStatusRunnable(mailboxId, callback),
                        REMOVE_REFRESH_STATUS_DELAY_MS);
        }
    }

    public void setSyncStarted(long mailboxId) {
        synchronized (mMailboxSync) {
            // only if we're tracking this mailbox
            if (mMailboxSync.containsKey(mailboxId)) {
                LogUtils.d(TAG, "RefreshStatusMonitor: setSyncStarted: mailboxId=%d", mailboxId);
                mMailboxSync.put(mailboxId, true);
            }
        }
    }

    private boolean isConnected() {
        final ConnectivityManager connectivityManager =
                ((ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE));
        final NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        return (networkInfo != null) && networkInfo.isConnected();
    }

    private class RemoveRefreshStatusRunnable implements Runnable {
        private final long mMailboxId;
        private final Callback mCallback;

        private int mNumRetries = 0;


        RemoveRefreshStatusRunnable(long mailboxId, Callback callback) {
            mMailboxId = mailboxId;
            mCallback = callback;
        }

        @Override
        public void run() {
            synchronized (mMailboxSync) {
                final Boolean isSyncRunning = mMailboxSync.get(mMailboxId);
                if (Boolean.FALSE.equals(isSyncRunning)) {
                    if (mIsStorageLow) {
                        LogUtils.d(TAG, "RefreshStatusMonitor: mailboxId=%d LOW STORAGE",
                                mMailboxId);
                        // The device storage is low and sync will never succeed.
                        mCallback.onRefreshCompleted(
                                mMailboxId, UIProvider.LastSyncResult.STORAGE_ERROR);
                        mMailboxSync.remove(mMailboxId);
                    } else if (!isConnected()) {
                        LogUtils.d(TAG, "RefreshStatusMonitor: mailboxId=%d NOT CONNECTED",
                                mMailboxId);
                        // The device is not connected to the Internet. A sync will never succeed.
                        mCallback.onRefreshCompleted(
                                mMailboxId, UIProvider.LastSyncResult.CONNECTION_ERROR);
                        mMailboxSync.remove(mMailboxId);
                    } else {
                        // The device is connected to the Internet. It might take a short while for
                        // the sync manager to initiate our sync, so let's post this runnable again
                        // and hope that we have started syncing by then.
                        mNumRetries++;
                        LogUtils.d(TAG, "RefreshStatusMonitor: mailboxId=%d Retry %d",
                                mMailboxId, mNumRetries);
                        if (mNumRetries > MAX_RETRY) {
                            LogUtils.d(TAG, "RefreshStatusMonitor: mailboxId=%d TIMEOUT",
                                    mMailboxId);
                            // Hide the sync status bar if it's been a while since sync was
                            // requested and still hasn't started.
                            mMailboxSync.remove(mMailboxId);
                            mCallback.onTimeout(mMailboxId);
                            // TODO: Displaying a user friendly message in addition.
                        } else {
                            mHandler.postDelayed(this, REMOVE_REFRESH_STATUS_DELAY_MS);
                        }
                    }
                } else {
                    // Some sync is currently in progress. We're done
                    LogUtils.d(TAG, "RefreshStatusMonitor: mailboxId=%d SYNC DETECTED", mMailboxId);
                    // it's not quite a success yet, the sync just started but we need to clear the
                    // error so the retry bar goes away.
                    mCallback.onRefreshCompleted(
                            mMailboxId, UIProvider.LastSyncResult.SUCCESS);
                    mMailboxSync.remove(mMailboxId);
                }
            }
        }
    }

    public interface Callback {
        void onRefreshCompleted(long mailboxId, int result);
        void onTimeout(long mailboxId);
    }
}
