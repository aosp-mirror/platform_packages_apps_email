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

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A View that is shown at the bottom of {@link MessageViewFragment} and contains buttons such
 * as "(move to) newer".
 *
 * This class is meant to hide layout differences between portrait and landscape, if any.
 * e.g. We might combine some of the buttons when we have small real estate.
 */
public class MessageCommandButtonView extends LinearLayout implements View.OnClickListener {
    /**
     * If false, we don't want to show anything, in which case all fields holding a view
     * (e.g. {@link #mMoveToNewerButton}) are null.
     */
    private boolean mShowPanel;

    private View mMoveToNewerButton;
    private View mMoveToOlderButton;
    private TextView mMessagePosition;

    private Callback mCallback = EmptyCallback.INSTANCE;

    public interface Callback {
        public void onMoveToNewer();
        public void onMoveToOlder();
    }

    private static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onMoveToNewer() {}
        @Override public void onMoveToOlder() {}
    }

    public MessageCommandButtonView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public MessageCommandButtonView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MessageCommandButtonView(Context context) {
        super(context);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mMoveToNewerButton = findViewById(R.id.move_to_newer_button);
        if (mMoveToNewerButton == null) {
            mShowPanel = false;
            return;
        }
        mShowPanel = true;
        mMoveToOlderButton = findViewById(R.id.move_to_older_button);
        mMessagePosition = (TextView) findViewById(R.id.message_position);

        mMoveToNewerButton.setOnClickListener(this);
        mMoveToOlderButton.setOnClickListener(this);
    }

    public void setCallback(Callback callback) {
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
    }

    public void enableNavigationButtons(boolean enableMoveToNewer, boolean enableMoveToOlder,
            int currentPosition, int countMessages) {
        if (!mShowPanel) {
            return;
        }
        mMoveToNewerButton.setEnabled(enableMoveToNewer);
        mMoveToOlderButton.setEnabled(enableMoveToOlder);

        // Show "POSITION of TOTAL"
        final String positionOfCount;
        if (countMessages == 0) {
            positionOfCount = "";
        } else {
            positionOfCount = getContext().getResources().getString(R.string.position_of_count,
                (currentPosition + 1), countMessages);
        }
        mMessagePosition.setText(positionOfCount);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.move_to_newer_button:
                mCallback.onMoveToNewer();
                break;
            case R.id.move_to_older_button:
                mCallback.onMoveToOlder();
                break;
        }
    }
}
