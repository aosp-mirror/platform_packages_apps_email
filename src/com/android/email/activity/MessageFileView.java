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
import com.android.emailcommon.utility.Utility;

import android.app.ActionBar;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;

/**
 * Activity to show file-based messages.  (i.e. *.eml files, and possibly *.msg files).
 *
 * <p>This class has very limited feature set compared to {@link MessageView}, that is:
 * <ul>
 *   <li>No action buttons (can't reply, forward or delete)
 *   <li>No favorite starring.
 *   <li>No navigating around (no older/newer buttons)
 * </ul>
 *
 * See {@link MessageViewBase} for the class relation diagram.
 */
public class MessageFileView extends MessageViewBase {

    private ActionBar mActionBar;

    /**
     * URI to the email (i.e. *.eml files, and possibly *.msg files) file that's being
     */
    private Uri mFileEmailUri;

    private MessageFileViewFragment mFragment;

    private LoadFilenameTask mLoadFilenameTask;

    @Override
    protected int getLayoutId() {
        return R.layout.message_file_view;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mActionBar = getActionBar();
        mActionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_HOME_AS_UP
                | ActionBar.DISPLAY_SHOW_TITLE);

        mFragment = (MessageFileViewFragment) getFragmentManager().findFragmentById(
                R.id.message_file_view_fragment);
        mFragment.setCallback(this);

        Intent intent = getIntent();
        mFileEmailUri = intent.getData();
        if (mFileEmailUri == null) {
            Log.w(Logging.LOG_TAG, "Insufficient intent parameter.  Closing...");
            finish();
            return;
        }

        // Load message.
        getFragment().openMessage(mFileEmailUri);

        // Set title.
        mLoadFilenameTask = new LoadFilenameTask(mFileEmailUri);
        mLoadFilenameTask.execute();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Utility.cancelTaskInterrupt(mLoadFilenameTask);
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

    /** @return always -1, as no accounts are associated with EML files. */
    @Override
    protected long getAccountId() {
        return -1;
    }

    // Note the return type is a subclass of that of the super class method.
    @Override
    protected MessageFileViewFragment getFragment() {
        return mFragment;
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
    private class LoadFilenameTask extends AsyncTask<Void, Void, String> {
        private final Uri mContentUri;

        public LoadFilenameTask(Uri contentUri) {
            mContentUri = contentUri;
        }

        @Override
        protected String doInBackground(Void... params) {
            return Utility.getContentFileName(MessageFileView.this, mContentUri);
        }

        @Override
        protected void onPostExecute(String filename) {
            if (filename == null || isCancelled()) {
                return;
            }
            setTitle(filename);
        }
    }
}
