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
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;

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
    private static Integer sDropTrashBgColor;

    /**
     * Owner account ID for the mailbox, {@link Account#ACCOUNT_ID_COMBINED_VIEW} for a combined
     * mailbox, or the ID for the current account, if it's an account row.
     */
    public long mAccountId;

    /**
     * ID for the current mailbox, or {@link Mailbox#NO_MAILBOX} if it's an account row.
     */
    public long mMailboxId;
    public Integer mMailboxType;
    /** If {@code true} this item can be used as a drop target. Otherwise, drop is prohibited. */
    public boolean mIsValidDropTarget;
    /** If {@code true} this item can be navigated to. Otherwise, it can just be selected. */
    public boolean mIsNavigable;
    public MailboxFragmentAdapter mAdapter;

    private Drawable mBackground;
    private TextView mLabelName;
    private TextView mLabelCount;

    /**
     * Drawable for an active item for D&D.  Note the drawable has state, so we can't share it
     * between items.
     * DO NOT use this directly; use {@link #getDropActiveBgDrawable()} instead, as it's lazily-
     * initialized.
     */
    private Drawable mDropActiveBgDrawable;

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
            sDropTrashBgColor = res.getColor(R.color.mailbox_drop_destructive_bg_color);
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
    public boolean isDropTarget(long itemMailboxId) {
        return mIsValidDropTarget && (itemMailboxId != mMailboxId);
    }

    /**
     * Returns whether or not this item can be navigated to.
     */
    public boolean isNavigable() {
        return mIsNavigable;
    }

    private Drawable getDropActiveBgDrawable() {
        if (mDropActiveBgDrawable == null) {
            mDropActiveBgDrawable =
                getContext().getResources().getDrawable(R.drawable.list_activated_holo);
        }
        return mDropActiveBgDrawable;
    }

    @Override
    public void setBackgroundDrawable(Drawable d) {
        // Don't override with the same instance.
        // If we don't do the check, something bad will happen to the fade-out animation for
        // the selected to non-selected transition.  (Looks like if you re-set the same
        // StateListDrawable instance, it'll get confused.)
        if (d != getBackground()) {
            super.setBackgroundDrawable(d);
        }
    }

    /**
     * Set the "trash" drop target background.
     */
    public void setDropTrashBackground() {
        setBackgroundColor(sDropTrashBgColor);
    }

    /**
     * Set the "active" drop target background.  (Used for the items that the user is hovering over)
     */
    public void setDropActiveBackground() {
        setBackgroundDrawable(getDropActiveBgDrawable());
    }

    public void setDropTargetBackground(boolean dragInProgress, long itemMailbox) {
        int labelNameColor = sTextPrimaryColor;
        int labelCountColor = sTextSecondaryColor;

        boolean isBackgroundSet = false;
        if (dragInProgress) {
            if (isDropTarget(itemMailbox)) {
                setBackgroundColor(sDropAvailableBgColor);
                isBackgroundSet = true;
            } else {
                labelNameColor = sDropUnavailableFgColor;
                labelCountColor = sDropUnavailableFgColor;
            }
        }
        if (!isBackgroundSet) {
            // Drag not in progress, or it's not a drop target.
            setBackgroundDrawable(mBackground);
        }
        mLabelName.setTextColor(labelNameColor);
        mLabelCount.setTextColor(labelCountColor);
    }
}
