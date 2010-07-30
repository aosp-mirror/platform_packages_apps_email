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
import com.android.email.provider.EmailContent.Message;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.security.InvalidParameterException;

/**
 * A {@link MessageViewFragmentBase} subclass for file based messages. (aka EML files)
 *
 * See {@link MessageViewBase} for the class relation diagram.
 */
public class MessageFileViewFragment extends MessageViewFragmentBase {
    private Uri mFileEmailUri;

    /**
     * Loads the layout.
     *
     * This class uses the same layout as {@link MessageViewFragment}, but hides the star.
     */
    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);

        view.findViewById(R.id.favorite).setVisibility(View.GONE);
        return view;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // If we're leaving a non-attachment message, delete any/all attachment messages

        // TODO We shouldn't remove ALL attachement messages here.  Remove only the current one.
        getController().deleteAttachmentMessages();
    }

    /** Called by activities with a URI to an EML file. */
    public void openMessage(Uri fileEmailUri) {
        if (fileEmailUri == null) {
            throw new InvalidParameterException();
        }
        mFileEmailUri = fileEmailUri;
        openMessageIfStarted();
    }

    @Override
    protected boolean isMessageSpecified() {
        return mFileEmailUri != null;
    }

    @Override
    protected Message openMessageSync() {
        final Activity activity = getActivity();
        // Put up a toast; this can take a little while...
        Utility.showToast(activity, R.string.message_view_parse_message_toast);
        Message msg = getController().loadMessageFromUri(mFileEmailUri);
        if (msg == null) {
            // Indicate that the attachment couldn't be loaded
            Utility.showToast(activity, R.string.message_view_display_attachment_toast);
            return null;
        }
        return msg;
    }

    /**
     * {@inheritDoc}
     *
     * Does exactly same as the super class method, but does an extra sanity check.
     */
    @Override
    protected void reloadUiFromMessage(Message message, boolean okToFetch) {
        // EML file should never be partially loaded.
        if (message.mFlagLoaded != Message.FLAG_LOADED_COMPLETE) {
            throw new IllegalStateException();
        }
        super.reloadUiFromMessage(message, okToFetch);
    }
}
