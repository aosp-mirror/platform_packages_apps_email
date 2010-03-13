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
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

public class GalEmailAddressAdapter extends EmailAddressAdapter {
    private static final String TAG = "GalAdapter";
    // Don't run GAL query until there are 3 characters typed
    private static final int MINIMUM_GAL_CONSTRAINT_LENGTH = 3;
    // Tag in the placeholder
    public static final String SEARCHING_TAG = "_SEARCHING_";

    Activity mActivity;
    AutoCompleteTextView mAutoCompleteTextView;
    Account mAccount;
    boolean mAccountHasGal;

    public GalEmailAddressAdapter(Activity activity, AutoCompleteTextView actv) {
        super(activity);
        mActivity = activity;
        mAutoCompleteTextView = actv;
        mAccount = null;
        mAccountHasGal = false;
    }

    /**
     * Set the account ID when known.  Not used for generic contacts lookup;  Use when
     * linking lookup to specific account.
     */
    @Override
    public void setAccount(Account account) {
        mAccount = account;
        mAccountHasGal = false;
    }

    /**
     * TODO: This should be the general purpose placeholder
     * TODO: String (possibly with account name)
     */
    public static Cursor getProgressCursor() {
        MatrixCursor c = new MatrixCursor(ExchangeProvider.GAL_PROJECTION);
        c.newRow().add(ExchangeProvider.GAL_START_ID).add("Searching ").add(SEARCHING_TAG);
        return c;
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
        // Get the cursor from ContactsProvider
        Cursor initialCursor = super.runQueryOnBackgroundThread(constraint);
        // If we don't have an account or we don't have a constraint that's long enough, just return
        if (mAccountHasGal &&
                constraint != null && constraint.length() >= MINIMUM_GAL_CONSTRAINT_LENGTH) {
            // Note that the "progress" line could (should) be implemented as a header rather than
            // as a row in the list. The current implementation is placeholder.
            MergeCursor mc =
                new MergeCursor(new Cursor[] {initialCursor, getProgressCursor()});
            // We need another copy of the original cursor for our MergeCursor
            // because changeCursor closes the original!
            // TODO: Avoid this - getting the contacts cursor twice is bad news.
            // We're probably not handling the filter UI properly.
            final Cursor contactsCursor = super.runQueryOnBackgroundThread(constraint);
            new Thread(new Runnable() {
                public void run() {
                    // Uri format is account/constraint/timestamp
                    Uri galUri =
                        ExchangeProvider.GAL_URI.buildUpon()
                            .appendPath(Long.toString(mAccount.mId))
                            .appendPath(constraint.toString())
                            .appendPath(((Long)System.currentTimeMillis()).toString()).build();
                    Log.d(TAG, "Query: " + galUri);
                    // Use ExchangeProvider to get the results of the GAL query
                    final Cursor galCursor =
                        mContentResolver.query(galUri, ExchangeProvider.GAL_PROJECTION,
                                null, null, null);
                    // A null cursor means that our query has been superceded by a later one
                    if (galCursor == null) return;
                    // We need to change cursors on the UI thread
                    mActivity.runOnUiThread(new Runnable() {
                        public void run() {
                            // Create a new cursor putting together local and GAL results and
                            // use it in the adapter
                            MergeCursor mergeCursor =
                                new MergeCursor(new Cursor[] {contactsCursor, galCursor});
                            changeCursor(mergeCursor);
                            // Call AutoCompleteTextView's onFilterComplete method with count
                            mAutoCompleteTextView.onFilterComplete(mergeCursor.getCount());
                        }});
                }}).start();
            return mc;
        }
        // Return right away with the ContactsProvider result
        return initialCursor;
    }

    // TODO - we cannot assume that contacts ID's will not overlap with GAL_START_ID
    // need to do this in a different way, based on more direct knowledge of our cursor
    @Override
    public final void bindView(View view, Context context, Cursor cursor) {
        if (cursor.getLong(ID_INDEX) == ExchangeProvider.GAL_START_ID) {
            ((TextView)view.findViewById(R.id.account)).setText(cursor.getString(NAME_INDEX));
            view.findViewById(R.id.status_divider).setVisibility(View.VISIBLE);
            view.findViewById(R.id.address).setVisibility(View.GONE);
            view.findViewById(R.id.progress).setVisibility(
                    cursor.getString(DATA_INDEX).equals(SEARCHING_TAG) ?
                            View.VISIBLE : View.GONE);
        } else {
            ((TextView)view.findViewById(R.id.text1)).setText(cursor.getString(NAME_INDEX));
            ((TextView)view.findViewById(R.id.text2)).setText(cursor.getString(DATA_INDEX));
            view.findViewById(R.id.address).setVisibility(View.VISIBLE);
            view.findViewById(R.id.status_divider).setVisibility(View.GONE);
        }
    }


}
