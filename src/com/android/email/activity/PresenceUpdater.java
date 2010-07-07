/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.email.activity;

import com.android.email.R;
import com.android.email.Utility;

import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.StatusUpdates;

import java.util.ArrayList;

/**
 * Class to check presence for email addresses and update icons.
 *
 * In this class, "presence status" is represented by an {@link Integer}, which can take one of:
 * {@link StatusUpdates#OFFLINE},
 * {@link StatusUpdates#INVISIBLE},
 * {@link StatusUpdates#AWAY},
 * {@link StatusUpdates#IDLE},
 * {@link StatusUpdates#DO_NOT_DISTURB},
 * {@link StatusUpdates#AVAILABLE},
 * or null for unknown.
 */
public class PresenceUpdater {
    /* package */ static final String[] PRESENCE_STATUS_PROJECTION =
            new String[] { Contacts.CONTACT_PRESENCE };

    private final Context mContext;

    /** List of running {@link PresenceCheckTask}.  Used in {@link #cancelAll()}. */
    private final ArrayList<PresenceCheckTask> mTaskList = new ArrayList<PresenceCheckTask>();

    /** Callback called when {@link #checkPresence} is done. */
    public interface Callback {
        public void onPresenceResult(String emailAddress, Integer presenceStatus);
    }

    public PresenceUpdater(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Start a task to check presence.  Call {@code Callback#onPresenceResult} when done.
     *
     * Must be called on the UI thread, as it creates an AsyncTask.
     */
    public void checkPresence(String emailAddress, Callback callback) {
        PresenceCheckTask task = new PresenceCheckTask(emailAddress, callback);
        task.execute();
        synchronized (mTaskList) {
            mTaskList.add(task);
        }
    }

    private void removeTaskFromList(PresenceCheckTask task) {
        synchronized (mTaskList) {
            mTaskList.remove(task);
        }
    }

    /**
     * Cancel all running tasks.
     */
    public void cancelAll() {
        synchronized (mTaskList) {
            try {
                for (PresenceCheckTask task : mTaskList) {
                    Utility.cancelTaskInterrupt(task);
                }
            } finally {
                mTaskList.clear();
            }
        }
    }

    /**
     * @return the resourece ID for the presence icon for {@code presenceStatus}.
     */
    /* package */ static int getPresenceIconResourceId(Integer presenceStatus) {
        return (presenceStatus == null) ? R.drawable.presence_inactive
                : StatusUpdates.getPresenceIconResourceId(presenceStatus);
    }

    /**
     * The actual method to get presence status from the contacts provider.
     * Called on a worker thread.
     *
     * Extracted from {@link PresenceCheckTask} for testing.
     *
     * @return presence status
     */
    /* package */ Integer getPresenceStatus(String emailAddress) {
        Cursor cursor = openPresenceCheckCursor(emailAddress);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    return cursor.getInt(0);
                }
            } finally {
                cursor.close();
            }
        }
        return null; // Unknown
    }

    /**
     * Open cursor for presence.
     *
     * Unit tests override this to inject a mock cursor.
     */
    /* package */ Cursor openPresenceCheckCursor(String emailAddress) {
        return mContext.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                PRESENCE_STATUS_PROJECTION,
                CommonDataKinds.Email.DATA + "=?", new String[] { emailAddress }, null);
    }

    private class PresenceCheckTask extends AsyncTask<Void, Void, Integer> {
        private final String mEmailAddress;
        private final Callback mCallback;

        public PresenceCheckTask(String emailAddress, Callback callback) {
            mEmailAddress = emailAddress;
            mCallback = callback;
        }

        @Override
        protected Integer doInBackground(Void... params) {
            return getPresenceStatus(mEmailAddress);
        }

        @Override
        protected void onCancelled() {
            removeTaskFromList(this);
        }

        @Override
        protected void onPostExecute(Integer status) {
            try {
                if (isCancelled()) {
                    return;
                }
                mCallback.onPresenceResult(mEmailAddress, status);
            } finally {
                removeTaskFromList(this);
            }
        }
    }

    /* package */ int getTaskListSizeForTest() {
        return mTaskList.size();
    }
}
