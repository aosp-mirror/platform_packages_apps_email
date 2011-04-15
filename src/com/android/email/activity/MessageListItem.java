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
import com.android.emailcommon.utility.TextUtilities;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout.Alignment;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.text.format.DateUtils;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * This custom View is the list item for the MessageList activity, and serves two purposes:
 * 1.  It's a container to store message metadata (e.g. the ids of the message, mailbox, & account)
 * 2.  It handles internal clicks such as the checkbox or the favorite star
 */
public class MessageListItem extends View {
    // Note: messagesAdapter directly fiddles with these fields.
    /* package */ long mMessageId;
    /* package */ long mMailboxId;
    /* package */ long mAccountId;

    private MessagesAdapter mAdapter;
    private MessageListItemCoordinates mCoordinates;
    private Context mContext;

    private boolean mDownEvent;

    public static final String MESSAGE_LIST_ITEMS_CLIP_LABEL =
        "com.android.email.MESSAGE_LIST_ITEMS";

    public MessageListItem(Context context) {
        super(context);
        init(context);
    }

    public MessageListItem(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public MessageListItem(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    // Narrow mode shows sender/snippet and time/favorite stacked to save real estate; due to this,
    // it is also somewhat taller
    private static final int MODE_NARROW = MessageListItemCoordinates.NARROW_MODE;
    // Wide mode shows sender, snippet, time, and favorite spread out across the screen
    private static final int MODE_WIDE = MessageListItemCoordinates.WIDE_MODE;
    // Sentinel indicating that the view needs layout
    public static final int NEEDS_LAYOUT = -1;

    private static boolean sInit = false;
    private static final TextPaint sDefaultPaint = new TextPaint();
    private static final TextPaint sBoldPaint = new TextPaint();
    private static final TextPaint sDatePaint = new TextPaint();
    private static final TextPaint sHighlightPaint = new TextPaint();
    private static Bitmap sAttachmentIcon;
    private static Bitmap sInviteIcon;
    private static Bitmap sFavoriteIconOff;
    private static Bitmap sFavoriteIconOn;
    private static Bitmap sSelectedIconOn;
    private static Bitmap sSelectedIconOff;
    private static String sSubjectSnippetDivider;

    public String mSender;
    public CharSequence mText;
    public CharSequence mSnippet;
    public String mSubject;
    private StaticLayout mSubjectLayout;
    public boolean mRead;
    public long mTimestamp;
    public boolean mHasAttachment = false;
    public boolean mHasInvite = true;
    public boolean mIsFavorite = false;
    /** {@link Paint} for account color chips.  null if no chips should be drawn.  */
    public Paint mColorChipPaint;

    private int mMode = -1;

    private int mViewWidth = 0;
    private int mViewHeight = 0;

    private static int sTextSize;
    private static int sItemHeightWide;
    private static int sItemHeightNarrow;

    // Note: these cannot be shared Drawables because they are selectors which have state.
    private Drawable mReadSelector;
    private Drawable mUnreadSelector;
    private Drawable mWideReadSelector;
    private Drawable mWideUnreadSelector;

    public int mSnippetLineCount = NEEDS_LAYOUT;
    private CharSequence mFormattedSender;
    private CharSequence mFormattedDate;

    private void init(Context context) {
        mContext = context;
        if (!sInit) {
            Resources r = context.getResources();
            sSubjectSnippetDivider = r.getString(R.string.message_list_subject_snippet_divider);
            sTextSize =
                r.getDimensionPixelSize(R.dimen.message_list_item_text_size);
            sItemHeightWide =
                r.getDimensionPixelSize(R.dimen.message_list_item_height_wide);
            sItemHeightNarrow =
                r.getDimensionPixelSize(R.dimen.message_list_item_height_narrow);

            sDefaultPaint.setTypeface(Typeface.DEFAULT);
            sDefaultPaint.setTextSize(sTextSize);
            sDefaultPaint.setAntiAlias(true);
            sDatePaint.setTypeface(Typeface.DEFAULT);
            sDatePaint.setAntiAlias(true);
            sBoldPaint.setTextSize(sTextSize);
            sBoldPaint.setTypeface(Typeface.DEFAULT_BOLD);
            sBoldPaint.setAntiAlias(true);
            sHighlightPaint.setColor(TextUtilities.HIGHLIGHT_COLOR_INT);
            sAttachmentIcon = BitmapFactory.decodeResource(r, R.drawable.ic_badge_attachment);
            sInviteIcon = BitmapFactory.decodeResource(r, R.drawable.ic_badge_invite);
            sFavoriteIconOff =
                BitmapFactory.decodeResource(r, R.drawable.btn_star_off_normal_email_holo_light);
            sFavoriteIconOn =
                BitmapFactory.decodeResource(r, R.drawable.btn_star_on_normal_email_holo_light);
            sSelectedIconOff =
                BitmapFactory.decodeResource(r, R.drawable.btn_check_off_normal_holo_light);
            sSelectedIconOn =
                BitmapFactory.decodeResource(r, R.drawable.btn_check_on_normal_holo_light);

            sInit = true;
        }
    }

    /**
     * Determine the mode of this view (WIDE or NORMAL)
     *
     * @param width The width of the view
     * @return The mode of the view
     */
    private int getViewMode(int width) {
        return MessageListItemCoordinates.getMode(mContext, width);
    }

    private Drawable mCurentBackground = null; // Only used by updateBackground()

    private void updateBackground() {
        final Drawable newBackground;
        if (mRead) {
            if (mMode == MODE_WIDE) {
                if (mWideReadSelector == null) {
                    mWideReadSelector = getContext().getResources()
                            .getDrawable(R.drawable.message_list_wide_read_selector);
                }
                newBackground = mWideReadSelector;
            } else {
                if (mReadSelector == null) {
                    mReadSelector = getContext().getResources()
                            .getDrawable(R.drawable.message_list_read_selector);
                }
                newBackground = mReadSelector;
            }
        } else {
            if (mMode == MODE_WIDE) {
                if (mWideUnreadSelector == null) {
                    mWideUnreadSelector = getContext().getResources()
                            .getDrawable(R.drawable.message_list_wide_unread_selector);
                }
                newBackground = mWideUnreadSelector;
            } else {
                if (mUnreadSelector == null) {
                    mUnreadSelector = getContext().getResources()
                            .getDrawable(R.drawable.message_list_unread_selector);
                }
                newBackground = mUnreadSelector;
            }
        }
        if (newBackground != mCurentBackground) {
            // setBackgroundDrawable is a heavy operation.  Only call it when really needed.
            setBackgroundDrawable(newBackground);
            mCurentBackground = newBackground;
        }
    }

    private void calculateDrawingData() {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        boolean hasSubject = false;
        if (!TextUtils.isEmpty(mSubject)) {
            SpannableString ss = new SpannableString(mSubject);
            ss.setSpan(new StyleSpan(mRead ? Typeface.NORMAL : Typeface.BOLD), 0, ss.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.append(ss);
            hasSubject = true;
        }
        if (!TextUtils.isEmpty(mSnippet)) {
            if (hasSubject) {
                ssb.append(sSubjectSnippetDivider);
            }
            ssb.append(mSnippet);
        }
        mText = ssb;

        sDefaultPaint.setTextSize(mCoordinates.subjectFontSize);
        mSubjectLayout = new StaticLayout(mText, sDefaultPaint,
                mCoordinates.subjectWidth, Alignment.ALIGN_NORMAL, 1, 0, true);
        if (mCoordinates.subjectLineCount < mSubjectLayout.getLineCount()) {
            // TODO: ellipsize.
            int end = mSubjectLayout.getLineEnd(mCoordinates.subjectLineCount - 1);
            mSubjectLayout = new StaticLayout(mText.subSequence(0, end),
                    sDefaultPaint, mCoordinates.subjectWidth, Alignment.ALIGN_NORMAL, 1, 0, true);
        }

        // Now, format the sender for its width
        TextPaint senderPaint = mRead ? sDefaultPaint : sBoldPaint;
        int senderWidth = mCoordinates.sendersWidth;
        // And get the ellipsized string for the calculated width
        if (TextUtils.isEmpty(mSender)) {
            mFormattedSender = "";
        } else {
            mFormattedSender = TextUtils.ellipsize(mSender, senderPaint, senderWidth,
                    TruncateAt.END);
        }
        // Get a nicely formatted date string (relative to today)
        mFormattedDate = DateUtils.getRelativeTimeSpanString(getContext(), mTimestamp).toString();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (widthMeasureSpec != 0 || mViewWidth == 0) {
            mViewWidth = MeasureSpec.getSize(widthMeasureSpec);
            int mode = getViewMode(mViewWidth);
            if (mode != mMode) {
                // If the mode has changed, set the snippet line count to indicate layout required
                mMode = mode;
                mSnippetLineCount = NEEDS_LAYOUT;
            }
            mViewHeight = measureHeight(heightMeasureSpec, mMode);
        }
        setMeasuredDimension(mViewWidth, mViewHeight);
    }

    /**
     * Determine the height of this view
     *
     * @param measureSpec A measureSpec packed into an int
     * @param mode The current mode of this view
     * @return The height of the view, honoring constraints from measureSpec
     */
    private int measureHeight(int measureSpec, int mode) {
        int result = 0;
        int specMode = MeasureSpec.getMode(measureSpec);
        int specSize = MeasureSpec.getSize(measureSpec);

        if (specMode == MeasureSpec.EXACTLY) {
            // We were told how big to be
            result = specSize;
        } else {
            // Measure the text
            if (mMode == MODE_WIDE) {
                result = sItemHeightWide;
            } else {
                result = sItemHeightNarrow;
            }
            if (specMode == MeasureSpec.AT_MOST) {
                // Respect AT_MOST value if that was what is called for by
                // measureSpec
                result = Math.min(result, specSize);
            }
        }
        return result;
    }

    @Override
    public void draw(Canvas canvas) {
        // Update the background, before View.draw() draws it.
        setSelected(mAdapter.isSelected(this));
        updateBackground();
        super.draw(canvas);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        mCoordinates = MessageListItemCoordinates.forWidth(mContext, mViewWidth);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mSnippetLineCount == NEEDS_LAYOUT) {
            calculateDrawingData();
        }

        // Draw the color chip indicating the mailbox this belongs to
        if (mColorChipPaint != null) {
            canvas.drawRect(
                    mCoordinates.chipX, mCoordinates.chipY,
                    mCoordinates.chipX + mCoordinates.chipWidth,
                    mCoordinates.chipY + mCoordinates.chipHeight,
                    mColorChipPaint);
        }

        // Draw the checkbox
        canvas.drawBitmap(mAdapter.isSelected(this) ? sSelectedIconOn : sSelectedIconOff,
                mCoordinates.checkmarkX, mCoordinates.checkmarkY, sDefaultPaint);

        // Draw the sender name
        canvas.drawText(mFormattedSender, 0, mFormattedSender.length(),
                mCoordinates.sendersX, mCoordinates.sendersY - mCoordinates.sendersAscent,
                mRead ? sDefaultPaint : sBoldPaint);

        // Subject and snippet.
        sDefaultPaint.setTextSize(mCoordinates.subjectFontSize);
        sDefaultPaint.setTypeface(Typeface.DEFAULT);
        canvas.save();
        canvas.translate(
                mCoordinates.subjectX,
                mCoordinates.subjectY);
        mSubjectLayout.draw(canvas);
        canvas.restore();

        // Draw the date
        sDatePaint.setTextSize(mCoordinates.dateFontSize);
        int dateX = mCoordinates.dateXEnd
                - (int) sDatePaint.measureText(mFormattedDate, 0, mFormattedDate.length());

        canvas.drawText(mFormattedDate, 0, mFormattedDate.length(),
                dateX, mCoordinates.dateY - mCoordinates.dateAscent, sDatePaint);

        // Draw the favorite icon
        canvas.drawBitmap(mIsFavorite ? sFavoriteIconOn : sFavoriteIconOff,
                mCoordinates.starX, mCoordinates.starY, sDefaultPaint);

        // TODO: deal with the icon layouts better from the coordinate class so that this logic
        // doesn't have to exist.
        // Draw the attachment and invite icons, if necessary.
        int iconsLeft = dateX;
        if (mHasAttachment) {
            iconsLeft = iconsLeft - sAttachmentIcon.getWidth();
            canvas.drawBitmap(sAttachmentIcon, iconsLeft, mCoordinates.paperclipY, sDefaultPaint);
        }
        if (mHasInvite) {
            iconsLeft -= sInviteIcon.getWidth();
            canvas.drawBitmap(sInviteIcon, iconsLeft, mCoordinates.paperclipY, sDefaultPaint);
        }

    }

    /**
     * Called by the adapter at bindView() time
     *
     * @param adapter the adapter that creates this view
     */
    public void bindViewInit(MessagesAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * Overriding this method allows us to "catch" clicks in the checkbox or star
     * and process them accordingly.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = false;
        int touchX = (int) event.getX();
        int checkRight = mCoordinates.checkmarkWidthIncludingMargins;
        int starLeft = mViewWidth - mCoordinates.starWidthIncludingMargins;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (touchX < checkRight || touchX > starLeft) {
                    mDownEvent = true;
                    if ((touchX < checkRight) || (touchX > starLeft)) {
                        handled = true;
                    }
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                mDownEvent = false;
                break;

            case MotionEvent.ACTION_UP:
                if (mDownEvent) {
                    if (touchX < checkRight) {
                        mAdapter.toggleSelected(this);
                        handled = true;
                    } else if (touchX > starLeft) {
                        mIsFavorite = !mIsFavorite;
                        mAdapter.updateFavorite(this, mIsFavorite);
                        handled = true;
                    }
                }
                break;
        }

        if (handled) {
            invalidate();
        } else {
            handled = super.onTouchEvent(event);
        }

        return handled;
    }
}
