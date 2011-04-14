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
import com.android.emailcommon.provider.EmailContent.Mailbox;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.RelativeLayout;
import android.widget.TextView;

public class MailboxListItem extends RelativeLayout {
    // Colors used for drop targets
    private static Integer sDropUnavailableFgColor;
    private static Integer sDropAvailableBgColor;
    private static Integer sTextPrimaryColor;
    private static Integer sTextSecondaryColor;

    public long mMailboxId;
    public Integer mMailboxType;
    /** If {@code true} this item can be used as a drop target. Otherwise, drop is prohibited. */
    public boolean mIsValidDropTarget;
    /** If {@code true} this item can be navigated to. Otherwise, it can just be selected. */
    public boolean mIsNavigable;
    public MailboxesAdapter mAdapter;

    private Drawable mBackground;
    private TextView mLabelName;
    private TextView mLabelCount;

    public MailboxListItem(Context context) {
        super(context);
    }

    public MailboxListItem(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MailboxListItem(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBackground = getBackground();
        if (sDropAvailableBgColor == null) {
            Resources res = getResources();
            sDropAvailableBgColor = res.getColor(R.color.mailbox_drop_available_bg_color);
            sDropUnavailableFgColor = res.getColor(R.color.mailbox_drop_unavailable_fg_color);
            sTextPrimaryColor = res.getColor(R.color.text_primary_color);
            sTextSecondaryColor = res.getColor(R.color.text_secondary_color);
        }
        mLabelName = (TextView)findViewById(R.id.mailbox_name);
        mLabelCount = (TextView)findViewById(R.id.message_count);
    }

    /**
     * Whether or not this mailbox item is a drop target. Only valid mailboxes or those
     * not forbidden by the system (see {@link Mailbox#INVALID_DROP_TARGETS}) will return
     * {@code true}.
     */
    public boolean isDropTarget(long itemMailbox) {
        return mIsValidDropTarget && (itemMailbox != mMailboxId);
    }

    /**
     * Returns whether or not this item can be navigated to.
     */
    public boolean isNavigable() {
        return mIsNavigable;
    }

    public void setDropTargetBackground(boolean dragInProgress, long itemMailbox) {
        int labelNameColor = sTextPrimaryColor;
        int labelCountColor = sTextSecondaryColor;
        if (dragInProgress) {
            if (isDropTarget(itemMailbox)) {
                setBackgroundColor(sDropAvailableBgColor);
            } else {
                labelNameColor = sDropUnavailableFgColor;
                labelCountColor = sDropUnavailableFgColor;
            }
        } else {
            setBackgroundDrawable(mBackground);
        }
        mLabelName.setTextColor(labelNameColor);
        mLabelCount.setTextColor(labelCountColor);
    }
}
