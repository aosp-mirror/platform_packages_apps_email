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

import android.app.Activity;
import android.os.Bundle;

/**
 * TODO Now that MessageView is gone, we can merge it with {@link MessageFileView}.
 */
public abstract class MessageViewBase extends Activity implements MessageViewFragmentBase.Callback {
    private Controller mController;

    protected abstract int getLayoutId();

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(getLayoutId());

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
    public void onMessageViewShown(int mailboxType) {
    }

    @Override
    public void onMessageViewGone() {
    }

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
    public void onLoadMessageError(String errorMessage) {
        onLoadMessageFinished();
    }

    @Override
    public void onMessageNotExists() { // Probably meessage deleted.
        finish();
    }
}
