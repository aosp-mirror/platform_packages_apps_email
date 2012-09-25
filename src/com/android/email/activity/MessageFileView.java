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
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;

import android.app.ActionBar;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;

/**
 * Activity to show file-based messages.  (i.e. *.eml files, and possibly *.msg files).
 */
public class MessageFileView extends Activity implements MessageViewFragmentBase.Callback {
    private ActionBar mActionBar;

    private MessageFileViewFragment mFragment;

    private final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.message_file_view);

        mActionBar = getActionBar();
        mActionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_HOME_AS_UP
                | ActionBar.DISPLAY_SHOW_TITLE);

        mFragment = (MessageFileViewFragment) getFragmentManager().findFragmentById(
                R.id.message_file_view_fragment);
        mFragment.setCallback(this);

        final Uri fileEmailUri = getIntent().getData();
        if (fileEmailUri == null) {
            Log.w(Logging.LOG_TAG, "Insufficient intent parameter.  Closing...");
            finish();
            return;
        }

        mFragment.setFileUri(fileEmailUri);

        // Set title.
        new LoadFilenameTask(fileEmailUri).executeParallel();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mTaskTracker.cancellAllInterrupt();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed(); // Treat as "back".
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Set the activity title.  ("Viewing FILENAME")
     */
    private void setTitle(String filename) {
        mActionBar.setTitle(getString(R.string.eml_view_title, filename));
    }

    /**
     * Load the filename of the EML, and update the activity title.
     */
    private class LoadFilenameTask extends EmailAsyncTask<Void, Void, String> {
        private final Uri mContentUri;

        public LoadFilenameTask(Uri contentUri) {
            super(mTaskTracker);
            mContentUri = contentUri;
        }

        @Override
        protected String doInBackground(Void... params) {
            return Utility.getContentFileName(MessageFileView.this, mContentUri);
        }

        @Override
        protected void onSuccess(String filename) {
            if (filename == null) {
                return;
            }
            setTitle(filename);
        }
    }

    @Override
    public boolean onUrlInMessageClicked(String url) {
        // EML files don't have the "owner" account, so use the default account as the sender.
        return ActivityHelper.openUrlInMessage(this, url, Account.NO_ACCOUNT);
    }

    @Override
    public void onMessageNotExists() { // Probably meessage deleted.
        finish();
    }

    @Override
    public void onLoadMessageStarted() {
        // Not important for EMLs
    }

    @Override
    public void onLoadMessageFinished() {
        // Not important for EMLs
    }

    @Override
    public void onLoadMessageError(String errorMessage) {
        // Not important for EMLs
    }

    @VisibleForTesting
    MessageFileViewFragment getFragment() {
        return mFragment;
    }
}
