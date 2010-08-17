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

import com.android.email.Controller;
import com.android.email.R;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Browser;

/**
 * Base class for {@link MessageView} and {@link MessageFileView}.
 *
 * Class relation diagram:
 * <pre>
 * (activities)                       (fragments)
 * MessageViewBase                    MessageViewFragmentBase
 *   |                                  |     (with nested interface Callback)
 *   |                                  |
 *   |-- MessageFileView  -- owns -->   |-- MessageFileViewFragment : For EML files.
 *   |                                  |     (with nested interface Callback, which implements
 *   |                                  |      MessageViewFragmentBase.Callback)
 *   |                                  |
 *   |-- MessageView      -- owns -->   |-- MessageViewFragment     : For regular messages
 *
 * MessageView is basically same as MessageFileView, but has more operations, such as "delete",
 * "forward", "reply", etc.
 *
 * Similarly, MessageViewFragment has more operations than MessageFileViewFragment does, such as
 * "mark unread", "respond to invite", etc.  Also its Callback interface has more method than
 * MessageViewFragmentBase.Callback does, for the extra operations.
 * </pre>
 */
public abstract class MessageViewBase extends Activity implements MessageViewFragmentBase.Callback {
    private ProgressDialog mFetchAttachmentProgressDialog;
    private Controller mController;

    protected abstract int getLayoutId();

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(getLayoutId());

        // TODO Turn it into a "managed" dialog?
        // Managed dialogs survive activity re-creation.  (e.g. orientation change)
        mFetchAttachmentProgressDialog = new ProgressDialog(this);
        mFetchAttachmentProgressDialog.setIndeterminate(true);
        mFetchAttachmentProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);

        mController = Controller.getInstance(getApplication());
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    protected Controller getController() {
        return mController;
    }

    protected abstract MessageViewFragmentBase getFragment();

    /**
     * @return the account id for the current message, or -1 if there's no account associated with.
     * (i.e. when opening an EML file.)
     */
    protected abstract long getAccountId();

    @Override
    public boolean onUrlInMessageClicked(String url) {
        // If it's showing an EML file, we pass -1 as the account id, and MessageCompose
        // uses the default account.  If there's no accounts set up, MessageCompose will close
        // itself.
        return ActivityHelper.openUrlInMessage(this, url, getAccountId());
    }

    @Override
    public void onLoadMessageStarted() {
        setProgressBarIndeterminateVisibility(true);
    }

    @Override
    public void onLoadMessageFinished() {
        setProgressBarIndeterminateVisibility(false);
    }

    @Override
    public void onLoadMessageError() {
        onLoadMessageFinished();
    }

    @Override
    public void onFetchAttachmentStarted(String attachmentName) {
        mFetchAttachmentProgressDialog.setMessage(
                getString(R.string.message_view_fetching_attachment_progress,
                        attachmentName));
        mFetchAttachmentProgressDialog.show();
        setProgressBarIndeterminateVisibility(true);
    }

    @Override
    public void onFetchAttachmentFinished() {
        mFetchAttachmentProgressDialog.dismiss();
        setProgressBarIndeterminateVisibility(false);
    }

    @Override
    public void onFetchAttachmentError() {
        onFetchAttachmentFinished();
    }

    @Override
    public void onMessageNotExists() { // Probably meessage deleted.
        finish();
    }
}
