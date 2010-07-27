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
 */
public abstract class MessageViewBase extends Activity implements MessageViewFragment.Callback {
    private ProgressDialog mFetchAttachmentProgressDialog;
    private MessageViewFragment mMessageViewFragment;
    private Controller mController;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.message_view);

        mMessageViewFragment = (MessageViewFragment) findFragmentById(R.id.message_view_fragment);
        mMessageViewFragment.setCallback(this);

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

    protected MessageViewFragment getFragment() {
        return mMessageViewFragment;
    }

    /**
     * @return the account id for the current message, or -1 if there's no account associated with.
     * (i.e. when opening an EML file.)
     */
    protected abstract long getAccountId();

    /**
     * {@inheritDoc}
     *
     * This is intended to mirror the operation of the original
     * (see android.webkit.CallbackProxy) with one addition of intent flags
     * "FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET".  This improves behavior when sublaunching
     * other apps via embedded URI's.
     *
     * We also use this hook to catch "mailto:" links and handle them locally.
     */
    @Override
    public boolean onUrlInMessageClicked(String url) {
        // hijack mailto: uri's and handle locally
        if (url != null && url.toLowerCase().startsWith("mailto:")) {
            // If it's showing an EML file, we pass -1 as the account id, and MessageCompose
            // uses the default account.  If there's no accounts set up, MessageCompose will close
            // itself.
            long senderAccountId = getAccountId();

            // TODO if MessageCompose implements the account selector, we'll be able to just pass -1
            // as the account id.
            return MessageCompose.actionCompose(MessageViewBase.this, url, senderAccountId);
        }

        // Handle most uri's via intent launch
        boolean result = false;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.putExtra(Browser.EXTRA_APPLICATION_ID, getPackageName());
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        try {
            startActivity(intent);
            result = true;
        } catch (ActivityNotFoundException ex) {
            // No applications can handle it.  Ignore.
        }
        return result;
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

    @Override
    public abstract void onRespondedToInvite(int response);

    @Override
    public abstract void onCalendarLinkClicked(long epochEventStartTime);

    @Override
    public abstract void onMessageSetUnread();
}
