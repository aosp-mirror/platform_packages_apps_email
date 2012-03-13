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

import com.android.email.Email;
import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.QuickResponse;
import com.android.emailcommon.utility.EmailAsyncTask;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
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
public class AccountSettingsEditQuickResponsesFragment extends Fragment
        implements OnClickListener {
    private ListView mQuickResponsesView;
    private Account mAccount;
    private Context mContext;
    private EmailAsyncTask.Tracker mTaskTracker;

    private static final String BUNDLE_KEY_ACTIVITY_TITLE
            = "AccountSettingsEditQuickResponsesFragment.title";

    // Helper class to place a TextView alongside "Delete" icon in the ListView
    // displaying the QuickResponses
    private static class ArrayAdapterWithButtons extends ArrayAdapter<QuickResponse> {
        private QuickResponse[] mQuickResponses;
        private final long mAccountId;
        private final Context mContext;
        private final FragmentManager mFragmentManager;

        private OnClickListener mOnEditListener = new OnClickListener() {
            @Override
            public void onClick(View view) {
                    QuickResponse quickResponse = (QuickResponse) (view.getTag());
                    EditQuickResponseDialog
                            .newInstance(quickResponse, mAccountId)
                            .show(mFragmentManager, null);
            }
        };

        private OnClickListener mOnDeleteListener = new OnClickListener() {
            @Override
            public void onClick(View view) {
                final QuickResponse quickResponse = (QuickResponse) view.getTag();

                // Delete the QuickResponse from the database. Content watchers used to
                // update the ListView of QuickResponses upon deletion.
                EmailAsyncTask.runAsyncParallel(new Runnable() {
                    @Override
                    public void run() {
                        EmailContent.delete(mContext, quickResponse.getBaseUri(),
                                quickResponse.getId());
                    }
                });
            }
        };

        private static final int resourceId = R.layout.quick_response_item;
        private static final int textViewId = R.id.quick_response_text;

        /**
         * Instantiates the custom ArrayAdapter, allowing editing and deletion of QuickResponses.
         * @param context - context of owning activity
         * @param quickResponses - the QuickResponses to represent in the ListView.
         * @param fragmentManager - fragmentManager to which an EditQuickResponseDialog will
         * attach itself.
         * @param accountId - accountId of the QuickResponses
         */
        public ArrayAdapterWithButtons(
                Context context, QuickResponse[] quickResponses,
                FragmentManager fragmentManager, long accountId) {
            super(context, resourceId, textViewId, quickResponses);
            mQuickResponses = quickResponses;
            mAccountId = accountId;
            mContext = context;
            mFragmentManager = fragmentManager;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            convertView = super.getView(position, convertView, parent);
            convertView.setTag(mQuickResponses[position]);
            convertView.setOnClickListener(mOnEditListener);

            ImageView deleteIcon = (ImageView) convertView.findViewById(R.id.delete_icon);
            deleteIcon.setTag(mQuickResponses[position]);
            deleteIcon.setOnClickListener(mOnDeleteListener);

            return convertView;
        }
    }

    /**
     *  Finds existing QuickResponses for the specified account and attaches the contents to
     *  a ListView. Optionally allows for editing and deleting of QuickResposnes from ListView.
     */
    public static class QuickResponseFinder extends EmailAsyncTask<Void, Void, QuickResponse[]> {
        private final long mAccountId;
        private final ListView mQuickResponsesView;
        private final Context mContext;
        private final FragmentManager mFragmentManager;
        private final OnItemClickListener mListener;
        private final boolean mIsEditable;

        /**
         * Finds all QuickResponses for the given account. Creates either a standard ListView
         * with a caller-implemented listener or one with a custom adapter that allows deleting
         * and editing of QuickResponses via EditQuickResponseDialog.
         *
         * @param tracker - tracks the finding and listing of QuickResponses. Should be canceled
         * onDestroy() or when the results are no longer needed.
         * @param accountId - id of the account whose QuickResponses are to be returned
         * @param quickResponsesView - ListView to which an ArrayAdapter with the QuickResponses
         * will be attached.
         * @param context - context of the owning activity
         * @param fragmentManager - required when isEditable is true so that an EditQuickResponse
         * dialog may properly attach itself. Unused when isEditable is false.
         * @param listener - optional when isEditable is true, unused when false.
         * @param isEditable - specifies whether the ListView will allow for user editing of
         * QuickResponses
         */
        public QuickResponseFinder(EmailAsyncTask.Tracker tracker, long accountId,
                ListView quickResponsesView, Context context, FragmentManager fragmentManager,
                OnItemClickListener listener, boolean isEditable) {
            super(tracker);
            mAccountId = accountId;
            mQuickResponsesView = quickResponsesView;
            mContext = context;
            mFragmentManager = fragmentManager;
            mListener = listener;
            mIsEditable = isEditable;
        }

        @Override
        protected QuickResponse[] doInBackground(Void... params) {
            QuickResponse[] quickResponses = QuickResponse.restoreQuickResponsesWithAccountId(
                    mContext, mAccountId);
            return quickResponses;
        }

        @Override
        protected void onSuccess(QuickResponse[] quickResponseItems) {
            ArrayAdapter<QuickResponse> adapter;
            if (mIsEditable) {
                    adapter = new ArrayAdapterWithButtons(
                    mContext,
                    quickResponseItems,
                    mFragmentManager,
                    mAccountId);
            } else {
                adapter = new ArrayAdapter<QuickResponse>(
                        mContext,
                        R.layout.insert_quick_response,
                        quickResponseItems
                        );
                mQuickResponsesView.setOnItemClickListener(mListener);
            }
            mQuickResponsesView.setAdapter(adapter);
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        // startPreferencePanel launches this fragment with the right title initially, but
        // if the device is rotate we must set the title ourselves
        if (savedInstanceState != null) {
            getActivity().setTitle(savedInstanceState.getString(BUNDLE_KEY_ACTIVITY_TITLE));
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putString(BUNDLE_KEY_ACTIVITY_TITLE, (String) getActivity().getTitle());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSettingsEditQuickResponsesFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        mAccount = args.getParcelable("account");
        mTaskTracker = new EmailAsyncTask.Tracker();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSettingsEditQuickResponsesFragment onCreateView");
        }
        int layoutId = R.layout.account_settings_edit_quick_responses_fragment;
        View view = inflater.inflate(layoutId, container, false);
        mContext = getActivity();

        mQuickResponsesView = UiUtilities.getView(view,
                R.id.account_settings_quick_responses_list);
        TextView emptyView = (TextView)
                UiUtilities.getView(((ViewGroup) mQuickResponsesView.getParent()), R.id.empty_view);
        mQuickResponsesView.setEmptyView(emptyView);

        new QuickResponseFinder(mTaskTracker, mAccount.mId, mQuickResponsesView,
                mContext, getFragmentManager(), null, true)
                .executeParallel();

        this.getActivity().getContentResolver().registerContentObserver(
                QuickResponse.CONTENT_URI, false, new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        new QuickResponseFinder(mTaskTracker, mAccount.mId, mQuickResponsesView,
                                mContext, getFragmentManager(), null, true)
                                .executeParallel();
                    }
                });

        UiUtilities.getView(view, R.id.create_new).setOnClickListener(this);

        return view;
    }

    @Override
    public void onDestroy() {
        mTaskTracker.cancellAllInterrupt();
        super.onDestroy();
    }

    /**
     * Implements OnClickListener
     */
    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.create_new) {
            EditQuickResponseDialog
                    .newInstance(null, mAccount.mId)
                    .show(getFragmentManager(), null);
        }
    }
}