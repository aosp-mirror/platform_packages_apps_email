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

import com.android.email.Email;
import com.android.email.R;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

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
    /**
     * URI to the email (i.e. *.eml files, and possibly *.msg files) file that's being
     */
    private Uri mFileEmailUri;

    private MessageFileViewFragment mFragment;

    @Override
    protected int getLayoutId() {
        return R.layout.message_file_view;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mFragment = (MessageFileViewFragment) findFragmentById(R.id.message_file_view_fragment);
        mFragment.setCallback(this);

        Intent intent = getIntent();
        mFileEmailUri = intent.getData();
        if (mFileEmailUri == null) {
            Log.w(Email.LOG_TAG, "Insufficient intent parameter.  Closing...");
            finish();
            return;
        }

        // TODO set title here: "Viewing XXX.eml".
    }

    @Override
    public void onResume() {
        super.onResume();
        // Note: We don't have to close the activity even if an account has been deleted,
        // unlike MessageView.
        getFragment().openMessage(mFileEmailUri);
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
}
