/*
 * Copyright (C) 2009 The Android Open Source Project
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
import android.view.MotionEvent;
import android.widget.RelativeLayout;

/**
 * This custom View is the list item for the MessageList activity, and serves two purposes:
 * 1.  It's a container to store message metadata (e.g. the ids of the message, mailbox, & account)
 * 2.  It handles internal clicks such as the checkbox or the favorite star
 */
public class MessageListItem extends RelativeLayout {

    public long mMessageId;
    public long mMailboxId;
    public long mAccountId;
    public boolean mRead;
    public boolean mFavorite;

    private MessagesAdapter mAdapter;

    private boolean mDownEvent;
    private boolean mCachedViewPositions;
    private int mStarLeft;

    // Padding to increase clickable areas on right of each list item
    private final static float STAR_PAD = 10.0F;

    public MessageListItem(Context context) {
        super(context);
    }

    public MessageListItem(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MessageListItem(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * Called by the adapter at bindView() time
     *
     * @param adapter the adapter that creates this view
     */
    public void bindViewInit(MessagesAdapter adapter) {
        mAdapter = adapter;
        mCachedViewPositions = false;
    }

    /**
     * Overriding this method allows us to "catch" clicks in the checkbox or star
     * and process them accordingly.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = false;
        int touchX = (int) event.getX();

        if (!mCachedViewPositions) {
            float paddingScale = getContext().getResources().getDisplayMetrics().density;
            int starPadding = (int) ((STAR_PAD * paddingScale) + 0.5);
            mStarLeft = findViewById(R.id.favorite).getLeft() - starPadding;
            mCachedViewPositions = true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDownEvent = true;
                if (touchX > mStarLeft) {
                    handled = true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                mDownEvent = false;
                break;

            case MotionEvent.ACTION_UP:
                if (mDownEvent) {
                    if (touchX > mStarLeft) {
                        mFavorite = !mFavorite;
                        mAdapter.updateFavorite(this, mFavorite);
                        handled = true;
                    }
                }
                break;
        }

        if (handled) {
            postInvalidate();
        } else {
            handled = super.onTouchEvent(event);
        }

        return handled;
    }
}
