/* Copyright (C) 2010 The Android Open Source Project.
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

package com.android.exchange.provider;

import com.android.email.Email;
import com.android.email.EmailAddressAdapter;
import com.android.email.R;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.HostAuth;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

/**
 * Email Address adapter that performs asynchronous GAL lookups.
 */
public class GalEmailAddressAdapter extends EmailAddressAdapter {
    // DO NOT CHECK IN SET TO TRUE
    private static final boolean DEBUG_GAL_LOG = false;

    // Don't run GAL query until there are 3 characters typed
    private static final int MINIMUM_GAL_CONSTRAINT_LENGTH = 3;

    private Activity mActivity;
    private Account mAccount;
    private boolean mAccountHasGal;
    private String mAccountEmailDomain;
    private LayoutInflater mInflater;

    // Local variables to track status of the search
    private int mSeparatorDisplayCount;
    private int mSeparatorTotalCount;

    public GalEmailAddressAdapter(Activity activity) {
        super(activity);
        mActivity = activity;
        mAccount = null;
        mAccountHasGal = false;
        mInflater = (LayoutInflater) activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    /**
     * Set the account ID when known.  Not used for generic contacts lookup;  Use when
     * linking lookup to specific account.
     */
    @Override
    public void setAccount(Account account) {
        mAccount = account;
        mAccountHasGal = false;
        int finalSplit = mAccount.mEmailAddress.lastIndexOf('@');
        mAccountEmailDomain = mAccount.mEmailAddress.substring(finalSplit + 1);
    }

    /**
     * Sniff the provided account and if it's EAS, record "mAccounthHasGal".  If not,
     * clear mAccount so we just ignore it.
     */
    private void checkGalAccount(Account account) {
        HostAuth ha = HostAuth.restoreHostAuthWithId(mActivity, account.mHostAuthKeyRecv);
        if (ha != null) {
            if ("eas".equalsIgnoreCase(ha.mProtocol)) {
                mAccountHasGal = true;
                return;
            }
        }
        // for any reason, we could not identify a GAL account, so clear mAccount
        // and we'll never check this again
        mAccount = null;
        mAccountHasGal = false;
    }

    @Override
    public Cursor runQueryOnBackgroundThread(final CharSequence constraint) {
        // One time (and not in the UI thread) - check the account and see if it support GAL
        // If not, clear it so we never bother again
        if (mAccount != null && mAccountHasGal == false) {
            checkGalAccount(mAccount);
        }

        // Get the cursor from ContactsProvider, and set up to exit immediately, returning it
        Cursor contactsCursor = super.runQueryOnBackgroundThread(constraint);
        // If we don't have a GAL  account or we don't have a constraint that's long enough,
        // just return the raw contactsCursor
        if (!mAccountHasGal || constraint == null) {
            return contactsCursor;
        }
        final String constraintString = constraint.toString().trim();
        if (constraintString.length() < MINIMUM_GAL_CONSTRAINT_LENGTH) {
            return contactsCursor;
        }

        // Strategy for handling dynamic GAL lookup.
        //  1. Create cursor that we can use now (and update later)
        //  2. Return it immediately
        //  3. Spawn a thread that will update the cursor when results arrive or search fails

        final MatrixCursor matrixCursor = new MatrixCursor(ExchangeProvider.GAL_PROJECTION);
        final MyMergeCursor mergedResultCursor =
            new MyMergeCursor(new Cursor[] {contactsCursor, matrixCursor});
        mergedResultCursor.setSeparatorPosition(contactsCursor.getCount());
        mSeparatorDisplayCount = -1;
        mSeparatorTotalCount = -1;
        new Thread(new Runnable() {
            public void run() {
                // Uri format is account/constraint
                Uri galUri =
                    ExchangeProvider.GAL_URI.buildUpon()
                        .appendPath(Long.toString(mAccount.mId))
                        .appendPath(constraintString).build();
                if (DEBUG_GAL_LOG) {
                    Log.d(Email.LOG_TAG, "Query: " + galUri);
                }
                // Use ExchangeProvider to get the results of the GAL query
                final Cursor galCursor =
                    mContentResolver.query(galUri, ExchangeProvider.GAL_PROJECTION,
                            null, null, null);
                // There are three result cases to handle here.
                //  1. matrixCursor is closed - this means the UI no longer cares about us
                //  2. gal cursor is null or empty - remove separator and exit
                //  3. gal cursor has results - update separator and add results to matrix cursor

                // Case 1: The merged cursor has already been dropped, (e.g. results superceded)
                if (mergedResultCursor.isClosed()) {
                    if (DEBUG_GAL_LOG) {
                        Log.d(Email.LOG_TAG, "Drop result (cursor closed, bg thread)");
                    }
                    return;
                }

                // Cases 2 & 3 have UI aspects, so do them in the UI thread
                mActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        // Case 1:  (final re-check):  Merged cursor already dropped
                        if (mergedResultCursor.isClosed()) {
                            if (DEBUG_GAL_LOG) {
                                Log.d(Email.LOG_TAG, "Drop result (cursor closed, ui thread)");
                            }
                            return;
                        }

                        // Case 2:  Gal cursor is null or empty
                        if (galCursor == null || galCursor.getCount() == 0) {
                            if (DEBUG_GAL_LOG) {
                                Log.d(Email.LOG_TAG, "Drop empty result");
                            }
                            mergedResultCursor.setSeparatorPosition(ListView.INVALID_POSITION);
                            GalEmailAddressAdapter.this.notifyDataSetChanged();
                            return;
                        }

                        // Case 3: Real results
                        galCursor.moveToPosition(-1);
                        while (galCursor.moveToNext()) {
                            MatrixCursor.RowBuilder rb = matrixCursor.newRow();
                            rb.add(galCursor.getLong(ExchangeProvider.GAL_COLUMN_ID));
                            rb.add(galCursor.getString(ExchangeProvider.GAL_COLUMN_DISPLAYNAME));
                            rb.add(galCursor.getString(ExchangeProvider.GAL_COLUMN_DATA));
                        }
                        // Replace the separator text with "totals"
                        mSeparatorDisplayCount = galCursor.getCount();
                        mSeparatorTotalCount =
                            galCursor.getExtras().getInt(ExchangeProvider.EXTRAS_TOTAL_RESULTS);
                        // Notify UI that the cursor changed
                        if (DEBUG_GAL_LOG) {
                            Log.d(Email.LOG_TAG, "Notify result, added=" + mSeparatorDisplayCount);
                        }
                        GalEmailAddressAdapter.this.notifyDataSetChanged();
                    }});
            }}).start();
        return mergedResultCursor;
    }

    /*
     * The following series of overrides insert the separator between contacts & GAL contacts
     * TODO: extract most of this into a CursorAdapter superclass, and share with AccountFolderList
     */

    /**
     * Get the separator position, which is tucked into the cursor to deal with threading.
     * Result is invalid for any other cursor types (e.g. the raw contacts cursor)
     */
    private int getSeparatorPosition() {
        Cursor c = this.getCursor();
        if (c instanceof MyMergeCursor) {
            return ((MyMergeCursor)c).getSeparatorPosition();
        } else {
            return ListView.INVALID_POSITION;
        }
    }

    /**
     * Prevents the separator view from recycling into the other views
     */
    @Override
    public int getItemViewType(int position) {
        if (position == getSeparatorPosition()) {
            return IGNORE_ITEM_VIEW_TYPE;
        }
        return super.getItemViewType(position);
    }

    /**
     * Injects the separator view when required
     */
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        // The base class's getView() checks for mDataValid at the beginning, but we don't have
        // to do that, because if the cursor is invalid getCount() returns 0, in which case this
        // method wouldn't get called.

        // Handle the separator here - create & bind
        if (position == getSeparatorPosition()) {
            View separator;
            separator = mInflater.inflate(R.layout.recipient_dropdown_separator, parent, false);
            TextView text1 = (TextView) separator.findViewById(R.id.text1);
            View progress = separator.findViewById(R.id.progress);
            String bannerText;
            if (mSeparatorDisplayCount == -1) {
                // Display "Searching <account>..."
                bannerText = mContext.getString(R.string.gal_searching_fmt, mAccountEmailDomain);
                progress.setVisibility(View.VISIBLE);
            } else {
                if (mSeparatorDisplayCount == mSeparatorTotalCount) {
                    // Display "x results from <account>"
                    bannerText = mContext.getResources().getQuantityString(
                            R.plurals.gal_completed_fmt, mSeparatorDisplayCount,
                            mSeparatorDisplayCount, mAccountEmailDomain);
                } else {
                    // Display "First x results from <account>"
                    bannerText = mContext.getString(R.string.gal_completed_limited_fmt,
                            mSeparatorDisplayCount, mAccountEmailDomain);
                }
                progress.setVisibility(View.GONE);
            }
            text1.setText(bannerText);
            return separator;
        }
        return super.getView(getRealPosition(position), convertView, parent);
    }

    /**
     * Forces navigation to skip over the separator
     */
    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    /**
     * Forces navigation to skip over the separator
     */
    @Override
    public boolean isEnabled(int position) {
        return position != getSeparatorPosition();
    }

    /**
     * Adjusts list count to include separator
     */
    @Override
    public int getCount() {
        int count = super.getCount();
        if (getSeparatorPosition() != ListView.INVALID_POSITION) {
            // Increment for separator, if we have anything to show.
            count += 1;
        }
        return count;
    }

    /**
     * Converts list position to cursor position
     */
    private int getRealPosition(int pos) {
        int separatorPosition = getSeparatorPosition();
        if (separatorPosition == ListView.INVALID_POSITION) {
            // No separator, identity map
            return pos;
        } else if (pos <= separatorPosition) {
            // Before or at the separator, identity map
            return pos;
        } else {
            // After the separator, remove 1 from the pos to get the real underlying pos
            return pos - 1;
        }
    }

    /**
     * Returns the item using external position numbering (no separator)
     */
    @Override
    public Object getItem(int pos) {
        return super.getItem(getRealPosition(pos));
    }

    /**
     * Returns the item id using external position numbering (no separator)
     */
    @Override
    public long getItemId(int pos) {
        if (pos == getSeparatorPosition()) {
            return View.NO_ID;
        }
        return super.getItemId(getRealPosition(pos));
    }

    /**
     * Lightweight override of MergeCursor.  Synchronizes "mClosed" / "isClosed()" so we
     * can safely check if it has been closed, in the threading jumble of our adapter.
     * Also holds the separator position, so it can be tracked with the cursor itself and avoid
     * errors when multiple cursors are in flight.
     */
    private static class MyMergeCursor extends MergeCursor {

        private int mSeparatorPosition;

        public MyMergeCursor(Cursor[] cursors) {
            super(cursors);
            mClosed = false;
            mSeparatorPosition = ListView.INVALID_POSITION;
        }

        @Override
        public synchronized void close() {
            super.close();
            if (DEBUG_GAL_LOG) {
                Log.d(Email.LOG_TAG, "Closing MyMergeCursor");
            }
        }

        @Override
        public synchronized boolean isClosed() {
            return super.isClosed();
        }

        void setSeparatorPosition(int newPos) {
            mSeparatorPosition = newPos;
        }

        int getSeparatorPosition() {
            return mSeparatorPosition;
        }
    }
}
