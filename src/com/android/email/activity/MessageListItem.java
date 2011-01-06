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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Paint.FontMetricsInt;
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

    // We always show two lines of subject/snippet
    private static final int MAX_SUBJECT_SNIPPET_LINES = 2;
    // Narrow mode shows sender/snippet and time/favorite stacked to save real estate; due to this,
    // it is also somewhat taller
    private static final int MODE_NARROW = 1;
    // Wide mode shows sender, snippet, time, and favorite spread out across the screen
    private static final int MODE_WIDE = 2;
    // Sentinel indicating that the view needs layout
    public static final int NEEDS_LAYOUT = -1;

    private static boolean sInit = false;
    private static final TextPaint sDefaultPaint = new TextPaint();
    private static final TextPaint sBoldPaint = new TextPaint();
    private static final TextPaint sDatePaint = new TextPaint();
    private static Bitmap sAttachmentIcon;
    private static Bitmap sInviteIcon;
    private static Bitmap sFavoriteIconOff;
    private static Bitmap sFavoriteIconOn;
    private static int sFavoriteIconWidth;
    private static Bitmap sSelectedIconOn;
    private static Bitmap sSelectedIconOff;
    private static String sSubjectSnippetDivider;

    public String mSender;
    public CharSequence mText;
    public String mSnippet;
    public String mSubject;
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
    private int mSenderSnippetWidth;
    private int mSnippetWidth;
    private int mDateFaveWidth;

    private static int sCheckboxHitWidth;
    private static int sDateIconWidthWide;
    private static int sDateIconWidthNarrow;
    private static int sFavoriteHitWidth;
    private static int sFavoritePaddingRight;
    private static int sSenderPaddingTopNarrow;
    private static int sSenderWidth;
    private static int sPaddingLarge;
    private static int sPaddingVerySmall;
    private static int sPaddingSmall;
    private static int sPaddingMedium;
    private static int sTextSize;
    private static int sItemHeightWide;
    private static int sItemHeightNarrow;
    private static int sMinimumWidthWideMode;
    private static int sColorTipWidth;
    private static int sColorTipHeight;
    private static int sColorTipRightMarginOnNarrow;
    private static int sColorTipRightMarginOnWide;

    // Note: these cannot be shared Drawables because they are selectors which have state.
    private Drawable mReadSelector;
    private Drawable mUnreadSelector;
    private Drawable mWideReadSelector;
    private Drawable mWideUnreadSelector;

    public int mSnippetLineCount = NEEDS_LAYOUT;
    private final CharSequence[] mSnippetLines = new CharSequence[MAX_SUBJECT_SNIPPET_LINES];
    private CharSequence mFormattedSender;
    private CharSequence mFormattedDate;

    private void init(Context context) {
        if (!sInit) {
            Resources r = context.getResources();
            sSubjectSnippetDivider = r.getString(R.string.message_list_subject_snippet_divider);
            sCheckboxHitWidth =
                r.getDimensionPixelSize(R.dimen.message_list_item_checkbox_hit_width);
            sFavoriteHitWidth =
                r.getDimensionPixelSize(R.dimen.message_list_item_favorite_hit_width);
            sFavoritePaddingRight =
                r.getDimensionPixelSize(R.dimen.message_list_item_favorite_padding_right);
            sSenderPaddingTopNarrow =
                r.getDimensionPixelSize(R.dimen.message_list_item_sender_padding_top_narrow);
            sDateIconWidthWide =
                r.getDimensionPixelSize(R.dimen.message_list_item_date_icon_width_wide);
            sDateIconWidthNarrow =
                r.getDimensionPixelSize(R.dimen.message_list_item_date_icon_width_narrow);
            sSenderWidth =
                r.getDimensionPixelSize(R.dimen.message_list_item_sender_width);
            sPaddingLarge =
                r.getDimensionPixelSize(R.dimen.message_list_item_padding_large);
            sPaddingMedium =
                r.getDimensionPixelSize(R.dimen.message_list_item_padding_medium);
            sPaddingSmall =
                r.getDimensionPixelSize(R.dimen.message_list_item_padding_small);
            sPaddingVerySmall =
                r.getDimensionPixelSize(R.dimen.message_list_item_padding_very_small);
            sTextSize =
                r.getDimensionPixelSize(R.dimen.message_list_item_text_size);
            sItemHeightWide =
                r.getDimensionPixelSize(R.dimen.message_list_item_height_wide);
            sItemHeightNarrow =
                r.getDimensionPixelSize(R.dimen.message_list_item_height_narrow);
            sMinimumWidthWideMode =
                r.getDimensionPixelSize(R.dimen.message_list_item_minimum_width_wide_mode);
            sColorTipWidth =
                r.getDimensionPixelSize(R.dimen.message_list_item_color_tip_width);
            sColorTipHeight =
                r.getDimensionPixelSize(R.dimen.message_list_item_color_tip_height);
            sColorTipRightMarginOnNarrow =
                r.getDimensionPixelSize(R.dimen.message_list_item_color_tip_right_margin_on_narrow);
            sColorTipRightMarginOnWide =
                r.getDimensionPixelSize(R.dimen.message_list_item_color_tip_right_margin_on_wide);

            sDefaultPaint.setTypeface(Typeface.DEFAULT);
            sDefaultPaint.setTextSize(sTextSize);
            sDefaultPaint.setAntiAlias(true);
            sDatePaint.setTypeface(Typeface.DEFAULT);
            sDatePaint.setTextSize(sTextSize - 1);
            sDatePaint.setAntiAlias(true);
            sDatePaint.setTextAlign(Align.RIGHT);
            sBoldPaint.setTypeface(Typeface.DEFAULT_BOLD);
            sBoldPaint.setTextSize(sTextSize);
            sBoldPaint.setAntiAlias(true);
            sAttachmentIcon = BitmapFactory.decodeResource(r, R.drawable.ic_badge_attachment);
            sInviteIcon = BitmapFactory.decodeResource(r, R.drawable.ic_badge_invite);
            sFavoriteIconOff =
                BitmapFactory.decodeResource(r, R.drawable.ic_star_none_holo_light);
            sFavoriteIconOn =
                BitmapFactory.decodeResource(r, R.drawable.btn_star_big_buttonless_dark_on);
            sSelectedIconOff =
                BitmapFactory.decodeResource(r, R.drawable.btn_check_off_normal_holo_light);
            sSelectedIconOn =
                BitmapFactory.decodeResource(r, R.drawable.btn_check_on_normal_holo_light);

            sFavoriteIconWidth = sFavoriteIconOff.getWidth();
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
        int mode = MODE_NARROW;
        if (width > sMinimumWidthWideMode) {
            mode = MODE_WIDE;
        }
        return mode;
    }

    private Drawable mCurentBackground = null; // Only used by updateBackground()

    /* package */ void updateBackground() {
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

        if (mMode == MODE_WIDE) {
            mDateFaveWidth = sFavoriteHitWidth + sDateIconWidthWide;
        } else {
            mDateFaveWidth = sDateIconWidthNarrow;
        }
        mSenderSnippetWidth = mViewWidth - mDateFaveWidth - sCheckboxHitWidth;

        // In wide mode, we use 3/4 for snippet and 1/4 for sender
        mSnippetWidth = mSenderSnippetWidth;
        if (mMode == MODE_WIDE) {
            mSnippetWidth = mSenderSnippetWidth - sSenderWidth - sPaddingLarge;
        }

        // Create a StaticLayout with our snippet to get the line breaks
        StaticLayout layout = new StaticLayout(mText, 0, mText.length(), sDefaultPaint,
                mSnippetWidth, Alignment.ALIGN_NORMAL, 1, 0, true);
        // Get the number of lines needed to render the whole snippet
        mSnippetLineCount = layout.getLineCount();
        // Go through our maximum number of lines, and save away what we'll end up displaying
        // for those lines
        for (int i = 0; i < MAX_SUBJECT_SNIPPET_LINES; i++) {
            int start = layout.getLineStart(i);
            if (i == MAX_SUBJECT_SNIPPET_LINES - 1) {
                int end = mText.length();
                if (start > end) continue;
                // For the final line, ellipsize the text to our width
                mSnippetLines[i] = TextUtils.ellipsize(mText.subSequence(start, end), sDefaultPaint,
                        mSnippetWidth, TruncateAt.END);
            } else {
                // Just extract from start to end
                mSnippetLines[i] = mText.subSequence(start, layout.getLineEnd(i));
            }
        }

        // Now, format the sender for its width
        TextPaint senderPaint = mRead ? sDefaultPaint : sBoldPaint;
        int senderWidth = (mMode == MODE_WIDE) ? sSenderWidth : mSenderSnippetWidth;
        // And get the ellipsized string for the calculated width
        mFormattedSender = TextUtils.ellipsize(mSender, senderPaint, senderWidth, TruncateAt.END);
        // Get a nicely formatted date string (relative to today)
        String date = DateUtils.getRelativeTimeSpanString(getContext(), mTimestamp).toString();
        // And make it fit to our size
        mFormattedDate = TextUtils.ellipsize(date, sDatePaint, sDateIconWidthWide, TruncateAt.END);
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
        updateBackground();
        super.draw(canvas);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mSnippetLineCount == NEEDS_LAYOUT) {
            calculateDrawingData();
        }
        // Snippet starts at right of checkbox
        int snippetX = sCheckboxHitWidth;
        int snippetY;
        int lineHeight = (int)sDefaultPaint.getFontSpacing() + sPaddingVerySmall;
        FontMetricsInt fontMetrics = sDefaultPaint.getFontMetricsInt();
        int ascent = fontMetrics.ascent;
        int descent = fontMetrics.descent;
        int senderY;

        if (mMode == MODE_WIDE) {
            // Get the right starting point for the snippet
            snippetX += sSenderWidth + sPaddingLarge;
            // And center the sender and snippet
            senderY = (mViewHeight - descent - ascent) / 2;
            snippetY = ((mViewHeight - (2 * lineHeight)) / 2) - ascent;
        } else {
            senderY = -ascent + sSenderPaddingTopNarrow;
            snippetY = senderY + lineHeight + sPaddingVerySmall;
        }

        // Draw the color chip
        if (mColorChipPaint != null) {
            final int rightMargin = (mMode == MODE_WIDE)
                    ? sColorTipRightMarginOnWide : sColorTipRightMarginOnNarrow;
            final int x = mViewWidth - rightMargin - sColorTipWidth;
            canvas.drawRect(x, 0, x + sColorTipWidth, sColorTipHeight, mColorChipPaint);
        }

        // Draw the checkbox
        int checkboxLeft = (sCheckboxHitWidth - sSelectedIconOff.getWidth()) / 2;
        int checkboxTop = (mViewHeight - sSelectedIconOff.getHeight()) / 2;
        canvas.drawBitmap(mAdapter.isSelected(this) ? sSelectedIconOn : sSelectedIconOff,
                checkboxLeft, checkboxTop, sDefaultPaint);

        // Draw the sender name
        canvas.drawText(mFormattedSender, 0, mFormattedSender.length(), sCheckboxHitWidth, senderY,
                mRead ? sDefaultPaint : sBoldPaint);

        // Draw each of the snippet lines
        int subjectEnd = (mSubject == null) ? 0 : mSubject.length();
        int lineStart = 0;
        TextPaint subjectPaint = mRead ? sDefaultPaint : sBoldPaint;
        for (int i = 0; i < MAX_SUBJECT_SNIPPET_LINES && i < mSnippetLineCount; i++) {
            CharSequence line = mSnippetLines[i];
            int drawX = snippetX;
            if (line != null) {
                int defaultPaintStart = 0;
                if (lineStart <= subjectEnd) {
                    int boldPaintEnd = subjectEnd - lineStart;
                    if (boldPaintEnd > line.length()) {
                        boldPaintEnd = line.length();
                    }
                    // From 0 to end, do in bold or default depending on the read flag
                    canvas.drawText(line, 0, boldPaintEnd, drawX, snippetY, subjectPaint);
                    defaultPaintStart = boldPaintEnd;
                    drawX += subjectPaint.measureText(line, 0, boldPaintEnd);
                }
                canvas.drawText(line, defaultPaintStart, line.length(), drawX, snippetY,
                        sDefaultPaint);
                snippetY += lineHeight;
                lineStart += line.length();
            }
        }

        // Draw the attachment and invite icons, if necessary
        int datePaddingRight;
        if (mMode == MODE_WIDE) {
            datePaddingRight = sFavoriteHitWidth;
        } else {
            datePaddingRight = sPaddingLarge;
        }
        int left = mViewWidth - datePaddingRight - (int)sDefaultPaint.measureText(mFormattedDate,
                0, mFormattedDate.length()) - sPaddingMedium;

        int iconTop;
        if (mHasAttachment) {
            left -= sAttachmentIcon.getWidth() + sPaddingSmall;
            if (mMode == MODE_WIDE) {
                iconTop = (mViewHeight - sAttachmentIcon.getHeight()) / 2;
            } else {
                iconTop = senderY - sAttachmentIcon.getHeight();
            }
            canvas.drawBitmap(sAttachmentIcon, left, iconTop, sDefaultPaint);
        }
        if (mHasInvite) {
            left -= sInviteIcon.getWidth() + sPaddingSmall;
            if (mMode == MODE_WIDE) {
                iconTop = (mViewHeight - sInviteIcon.getHeight()) / 2;
            } else {
                iconTop = senderY - sInviteIcon.getHeight();
            }
            canvas.drawBitmap(sInviteIcon, left, iconTop, sDefaultPaint);
        }

        // Draw the date
        canvas.drawText(mFormattedDate, 0, mFormattedDate.length(), mViewWidth - datePaddingRight,
                senderY, sDatePaint);

        // Draw the favorite icon
        int faveLeft = mViewWidth - sFavoriteIconWidth;
        if (mMode == MODE_WIDE) {
            faveLeft -= sFavoritePaddingRight;
        } else {
            faveLeft -= sPaddingLarge;
        }
        int faveTop = (mViewHeight - sFavoriteIconOff.getHeight()) / 2;
        if (mMode == MODE_NARROW) {
            faveTop += sSenderPaddingTopNarrow;
        }
        canvas.drawBitmap(mIsFavorite ? sFavoriteIconOn : sFavoriteIconOff, faveLeft, faveTop,
                sDefaultPaint);
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
        int checkRight = sCheckboxHitWidth;
        int starLeft = mViewWidth - sFavoriteHitWidth;

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
