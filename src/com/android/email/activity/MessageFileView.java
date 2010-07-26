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

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

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
 * TODO Test it!
 */
public class MessageFileView extends MessageViewBase {
    /**
     * URI to the email (i.e. *.eml files, and possibly *.msg files) file that's being
     */
    private Uri mFileEmailUri;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Intent intent = getIntent();
        mFileEmailUri = intent.getData();

        // TODO set title here: "Viewing XXX.eml".

        // Hide all bottom buttons.
        findViewById(R.id.button_panel).setVisibility(View.GONE);
        findViewById(R.id.favorite).setVisibility(View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Note: We don't have to close it even if an account has been deleted, unlike MessageView.
        getFragment().openMessage(mFileEmailUri);
    }

    /** @return always -1, as there's no account associated with EML files. */
    @Override
    protected long getAccountId() {
        return -1;
    }

    // Note EML files can have ICS (calendar invitation) files, but we don't treat them as
    // invitations at this point.  (Only exchange provider sets the FLAG_INCOMING_MEETING_INVITE
    // flag.)  At any rate, it'd be weird to respond to an invitation in an EML that might not
    // be addressed to you...

    // TODO Remove these callbacks below, when breaking down the fragment.
    // MessageViewFragment for email files shouldn't have these callbacks.

    @Override
    public void onRespondedToInvite(int response) {
        // EML files shouldn't have calender response buttons.
        throw new RuntimeException();
    }

    @Override
    public void onCalendarLinkClicked(long epochEventStartTime) {
        // EML files shouldn't have the "View in calender" button.
        throw new RuntimeException();
    }

    @Override
    public void onMessageSetUnread() {
        // EML files shouldn't have the "mark unread" button.
        throw new RuntimeException();
    }
}
