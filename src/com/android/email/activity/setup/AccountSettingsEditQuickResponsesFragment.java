/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.email.activity.setup;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.QuickResponse;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.mail.utils.LogUtils;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.LoaderManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Loader;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

/**
 * Lists quick responses associated with the specified email account. Allows users to create,
 * edit, and delete quick responses. Owning activity must:
 * <ul>
 *   <li>Launch this fragment using startPreferencePanel().</li>
 *   <li>Provide an Account as an argument named "account". This account's quick responses
 *   will be read and potentially modified.</li>
 * </ul>
 *
 * <p>This fragment is run as a preference panel from AccountSettings.</p>
 */
public class AccountSettingsEditQuickResponsesFragment extends Fragment {
    private Account mAccount;
    private SimpleCursorAdapter mAdapter;

    private static final String BUNDLE_KEY_ACTIVITY_TITLE
            = "AccountSettingsEditQuickResponsesFragment.title";

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        // startPreferencePanel launches this fragment with the right title initially, but
        // if the device is rotated we must set the title ourselves
        if (savedInstanceState != null) {
            getActivity().setTitle(savedInstanceState.getString(BUNDLE_KEY_ACTIVITY_TITLE));
        }

        mAdapter = new SimpleCursorAdapter(getActivity(), R.layout.quick_response_item, null,
                new String [] {EmailContent.QuickResponseColumns.TEXT},
                new int [] {R.id.quick_response_text}, 0);

        final ListView listView = UiUtilities.getView(getView(),
                R.id.account_settings_quick_responses_list);
        listView.setAdapter(mAdapter);

        getLoaderManager().initLoader(0, null, new LoaderManager.LoaderCallbacks<Cursor>() {
            @Override
            public Loader<Cursor> onCreateLoader(int id, Bundle args) {
                final Uri baseUri = Uri.parse(EmailContent.CONTENT_URI + "/quickresponse/account");
                final Uri uri = ContentUris.withAppendedId(baseUri, mAccount.getId());
                return new CursorLoader(getActivity(), uri, EmailContent.QUICK_RESPONSE_PROJECTION,
                        null, null, null);
            }

            @Override
            public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
                mAdapter.swapCursor(data);
            }

            @Override
            public void onLoaderReset(Loader<Cursor> loader) {
                mAdapter.swapCursor(null);
            }
        });
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putString(BUNDLE_KEY_ACTIVITY_TITLE, (String) getActivity().getTitle());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSettingsEditQuickResponsesFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        mAccount = args.getParcelable("account");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSettingsEditQuickResponsesFragment onCreateView");
        }
        final View view = inflater.inflate(R.layout.account_settings_edit_quick_responses_fragment,
                container, false);

        final ListView listView = UiUtilities.getView(view,
                R.id.account_settings_quick_responses_list);
        final TextView emptyView =
                UiUtilities.getView((ViewGroup) listView.getParent(), R.id.empty_view);
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final Cursor c = (Cursor) listView.getItemAtPosition(position);
                final int quickResponseId = c.getInt(EmailContent.QUICK_RESPONSE_COLUMN_ID);
                final String quickResponseText =
                        c.getString(EmailContent.QUICK_RESPONSE_COLUMN_TEXT);
                final Uri baseUri = Uri.parse(EmailContent.CONTENT_URI + "/quickresponse");
                final Uri uri = ContentUris.withAppendedId(baseUri, quickResponseId);
                EditQuickResponseDialog.newInstance(quickResponseText, uri, mAccount.getId(), false)
                        .show(getFragmentManager(), null);
            }
        });
        final View createNewView =
                UiUtilities.getView((ViewGroup) listView.getParent(), R.id.create_new);
        createNewView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                final Uri baseUri = Uri.parse(EmailContent.CONTENT_URI + "/quickresponse");
                EditQuickResponseDialog.newInstance(null, baseUri, mAccount.getId(), true)
                        .show(getFragmentManager(), null);
            }
        });
        return view;
    }
}
