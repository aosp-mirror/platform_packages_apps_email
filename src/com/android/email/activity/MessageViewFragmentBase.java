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

import com.android.email.Controller;
import com.android.email.ControllerResultUiThreadWrapper;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.mail.Address;
import com.android.email.mail.MessagingException;
import com.android.email.mail.internet.EmailHtmlUtil;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.Message;

import org.apache.commons.io.IOUtils;

import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.QuickContact;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// TODO Restore "Show pictures" state and scroll position on rotation.

/**
 * Base class for {@link MessageViewFragment} and {@link MessageFileViewFragment}.
 *
 * See {@link MessageViewBase} for the class relation diagram.
 */
public abstract class MessageViewFragmentBase extends Fragment implements View.OnClickListener {
    private Context mContext;

    // Regex that matches start of img tag. '<(?i)img\s+'.
    private static final Pattern IMG_TAG_START_REGEX = Pattern.compile("<(?i)img\\s+");
    // Regex that matches Web URL protocol part as case insensitive.
    private static final Pattern WEB_URL_PROTOCOL = Pattern.compile("(?i)http|https://");

    private static int PREVIEW_ICON_WIDTH = 62;
    private static int PREVIEW_ICON_HEIGHT = 62;

    private TextView mSubjectView;
    private TextView mFromView;
    private TextView mDateView;
    private TextView mTimeView;
    private TextView mToView;
    private TextView mCcView;
    private View mCcContainerView;
    private WebView mMessageContentView;
    private LinearLayout mAttachments;
    private ImageView mAttachmentIcon;
    private View mShowPicturesSection;
    private ImageView mSenderPresenceView;
    private View mScrollView;

    private long mAccountId = -1;
    private long mMessageId = -1;
    private Message mMessage;

    private LoadMessageTask mLoadMessageTask;
    private LoadBodyTask mLoadBodyTask;
    private LoadAttachmentsTask mLoadAttachmentsTask;
    private PresenceUpdater mPresenceUpdater;

    private long mLoadAttachmentId;         // the attachment being saved/viewed
    private boolean mLoadAttachmentSave;    // if true, saving - if false, viewing
    private String mLoadAttachmentName;     // the display name

    private java.text.DateFormat mDateFormat;
    private java.text.DateFormat mTimeFormat;

    private Controller mController;
    private ControllerResultUiThreadWrapper<ControllerResults> mControllerCallback;

    // contains the HTML body. Is used by LoadAttachmentTask to display inline images.
    // is null most of the time, is used transiently to pass info to LoadAttachementTask
    private String mHtmlTextRaw;

    // contains the HTML content as set in WebView.
    private String mHtmlTextWebView;

    private boolean mStarted;

    private boolean mIsMessageLoadedForTest;

    /**
     * Encapsulates known information about a single attachment.
     */
    private static class AttachmentInfo {
        public String name;
        public String contentType;
        public long size;
        public long attachmentId;
        public Button viewButton;
        public Button downloadButton;
        public ImageView iconView;
    }

    public interface Callback {
        /**
         * Called when a link in a message is clicked.
         *
         * @param url link url that's clicked.
         * @return true if handled, false otherwise.
         */
        public boolean onUrlInMessageClicked(String url);

        /** Called when the message specified doesn't exist. */
        public void onMessageNotExists();

        /** Called when it starts loading a message. */
        public void onLoadMessageStarted();

        /** Called when it successfully finishes loading a message. */
        public void onLoadMessageFinished();

        /** Called when an error occurred during loading a message. */
        public void onLoadMessageError();

        /** Called when it starts loading an attachment. */
        public void onFetchAttachmentStarted(String attachmentName);

        /** Called when it successfully finishes loading an attachment. */
        public void onFetchAttachmentFinished();

        /** Called when an error occurred during loading an attachment. */
        public void onFetchAttachmentError();
    }

    public static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();

        @Override
        public void onFetchAttachmentError() {
        }

        @Override
        public void onFetchAttachmentFinished() {
        }

        @Override
        public void onFetchAttachmentStarted(String attachmentName) {
        }

        @Override
        public void onLoadMessageError() {
        }

        @Override
        public void onLoadMessageFinished() {
        }

        @Override
        public void onLoadMessageStarted() {
        }

        @Override
        public void onMessageNotExists() {
        }

        @Override
        public boolean onUrlInMessageClicked(String url) {
            return false;
        }
    }

    private Callback mCallback = EmptyCallback.INSTANCE;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        mContext = getActivity().getApplicationContext();

        mControllerCallback = new ControllerResultUiThreadWrapper<ControllerResults>(
                new Handler(), new ControllerResults());

        mPresenceUpdater = new PresenceUpdater(mContext);
        mDateFormat = android.text.format.DateFormat.getDateFormat(mContext); // short format
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(mContext); // 12/24 date format

        mController = Controller.getInstance(mContext);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onCreateView");
        }
        final View view = inflater.inflate(R.layout.message_view_fragment, container, false);

        mSubjectView = (TextView) view.findViewById(R.id.subject);
        mFromView = (TextView) view.findViewById(R.id.from);
        mToView = (TextView) view.findViewById(R.id.to);
        mCcView = (TextView) view.findViewById(R.id.cc);
        mCcContainerView = view.findViewById(R.id.cc_container);
        mDateView = (TextView) view.findViewById(R.id.date);
        mTimeView = (TextView) view.findViewById(R.id.time);
        mMessageContentView = (WebView) view.findViewById(R.id.message_content);
        mAttachments = (LinearLayout) view.findViewById(R.id.attachments);
        mAttachmentIcon = (ImageView) view.findViewById(R.id.attachment);
        mShowPicturesSection = view.findViewById(R.id.show_pictures_section);
        mSenderPresenceView = (ImageView) view.findViewById(R.id.presence);
        mScrollView = view.findViewById(R.id.scrollview);

        mFromView.setOnClickListener(this);
        mSenderPresenceView.setOnClickListener(this);
        view.findViewById(R.id.show_pictures).setOnClickListener(this);

        mMessageContentView.setVerticalScrollBarEnabled(false);
        mMessageContentView.getSettings().setBlockNetworkLoads(true);
        mMessageContentView.getSettings().setSupportZoom(false);
        mMessageContentView.setWebViewClient(new CustomWebViewClient());
        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
        mController.addResultCallback(mControllerCallback);
    }

    @Override
    public void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onStart");
        }
        super.onStart();
        mStarted = true;
        if (isMessageSpecified()) {
            openMessageIfStarted();
        }
    }

    @Override
    public void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onResume");
        }
        super.onResume();
    }

    @Override
    public void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onPause");
        }
        super.onPause();
    }

    @Override
    public void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onStop");
        }
        mStarted = false;
        super.onStop();
    }

    @Override
    public void onDestroy() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onDestroy");
        }
        mController.removeResultCallback(mControllerCallback);
        cancelAllTasks();
        mMessageContentView.destroy();
        mMessageContentView = null;
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
    }

    public void setCallback(Callback callback) {
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
    }

    private void cancelAllTasks() {
        Utility.cancelTaskInterrupt(mLoadMessageTask);
        mLoadMessageTask = null;
        Utility.cancelTaskInterrupt(mLoadBodyTask);
        mLoadBodyTask = null;
        Utility.cancelTaskInterrupt(mLoadAttachmentsTask);
        mLoadAttachmentsTask = null;
        if (mPresenceUpdater != null) {
            mPresenceUpdater.cancelAll();
        }
    }

    /**
     * Subclass returns true if which message to open is already specified by the activity.
     */
    protected abstract boolean isMessageSpecified();

    protected final Controller getController() {
        return mController;
    }

    protected final Callback getCallback() {
        return mCallback;
    }

    protected final Message getMessage() {
        return mMessage;
    }

    protected final boolean isMessageOpen() {
        return mMessage != null;
    }

    /**
     * Returns the account id of the current message, or -1 if unknown (message not open yet, or
     * viewing an EML message).
     */
    public long getAccountId() {
        return mAccountId;
    }

    protected void openMessageIfStarted() {
        if (!mStarted) {
            return;
        }
        cancelAllTasks();
        if (mMessageContentView != null) {
            mMessageContentView.getSettings().setBlockNetworkLoads(true);
            mMessageContentView.scrollTo(0, 0);
            mMessageContentView.loadUrl("file:///android_asset/empty.html");
        }
        mScrollView.scrollTo(0, 0);
        mAttachments.removeAllViews();
        mAttachments.setVisibility(View.GONE);
        mAttachmentIcon.setVisibility(View.GONE);
        mLoadMessageTask = new LoadMessageTask(true);
        mLoadMessageTask.execute();
    }

    /**
     * Handle clicks on sender, which shows {@link QuickContact} or prompts to add
     * the sender as a contact.
     *
     * TODO Move DB lookup to a worker thread.
     */
    private void onClickSender() {
        final Address senderEmail = Address.unpackFirst(mMessage.mFrom);
        if (senderEmail == null) return;

        // First perform lookup query to find existing contact
        final ContentResolver resolver = mContext.getContentResolver();
        final String address = senderEmail.getAddress();
        final Uri dataUri = Uri.withAppendedPath(CommonDataKinds.Email.CONTENT_FILTER_URI,
                Uri.encode(address));
        final Uri lookupUri = ContactsContract.Data.getContactLookupUri(resolver, dataUri);

        if (lookupUri != null) {
            // Found matching contact, trigger QuickContact
            QuickContact.showQuickContact(mContext, mSenderPresenceView, lookupUri,
                    QuickContact.MODE_LARGE, null);
        } else {
            // No matching contact, ask user to create one
            final Uri mailUri = Uri.fromParts("mailto", address, null);
            final Intent intent = new Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT,
                    mailUri);

            // Pass along full E-mail string for possible create dialog
            intent.putExtra(ContactsContract.Intents.EXTRA_CREATE_DESCRIPTION,
                    senderEmail.toString());

            // Only provide personal name hint if we have one
            final String senderPersonal = senderEmail.getPersonal();
            if (!TextUtils.isEmpty(senderPersonal)) {
                intent.putExtra(ContactsContract.Intents.Insert.NAME, senderPersonal);
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);

            startActivity(intent);
        }
    }

    private void onDownloadAttachment(AttachmentInfo attachment) {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            /*
             * Abort early if there's no place to save the attachment. We don't want to spend
             * the time downloading it and then abort.
             */
            Utility.showToast(getActivity(), R.string.message_view_status_attachment_not_saved);
            return;
        }
        startLoadAttachment(attachment, true);
    }

    private void onViewAttachment(AttachmentInfo attachment) {
        startLoadAttachment(attachment, false);
    }

    private void startLoadAttachment(AttachmentInfo attachment, boolean save) {
        mLoadAttachmentId = attachment.attachmentId;
        mLoadAttachmentSave = save;
        mLoadAttachmentName = attachment.name;

        mController.loadAttachment(attachment.attachmentId, mMessageId, mMessage.mMailboxKey,
                mAccountId);
    }

    /**
     * Called by ControllerResults. Handle the final steps of downloading an attachment
     * (view or save).
     *
     * @param attachmentId the attachment that was just downloaded
     */
    private void doFinishLoadAttachment(long attachmentId) {
        // If the result does't line up, just skip it - we handle one at a time.
        if (attachmentId != mLoadAttachmentId) {
            return;
        }
        Attachment attachment = Attachment.restoreAttachmentWithId(mContext, attachmentId);
        Uri attachmentUri = AttachmentProvider.getAttachmentUri(mAccountId, attachment.mId);
        Uri contentUri = AttachmentProvider.resolveAttachmentIdToContentUri(
                mContext.getContentResolver(), attachmentUri);

        if (mLoadAttachmentSave) {
            try {
                File file = Utility.createUniqueFile(Environment.getExternalStorageDirectory(),
                        attachment.mFileName);
                InputStream in = mContext.getContentResolver().openInputStream(contentUri);
                OutputStream out = new FileOutputStream(file);
                IOUtils.copy(in, out);
                out.flush();
                out.close();
                in.close();

                Utility.showToast(getActivity(), String.format(
                        mContext.getString(R.string.message_view_status_attachment_saved),
                        file.getName()));

                MediaOpener.scanAndOpen(getActivity(), file);
            } catch (IOException ioe) {
                Utility.showToast(getActivity(), R.string.message_view_status_attachment_not_saved);
            }
        } else {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(contentUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Utility.showToast(getActivity(), R.string.message_view_display_attachment_toast);
                // TODO: Add a proper warning message (and lots of upstream cleanup to prevent
                // it from happening) in the next release.
            }
        }
    }

    private void onShowPicturesInHtml() {
        if (mMessageContentView != null) {
            mMessageContentView.getSettings().setBlockNetworkLoads(false);
            if (mHtmlTextWebView != null) {
                mMessageContentView.loadDataWithBaseURL("email://", mHtmlTextWebView,
                                                        "text/html", "utf-8", null);
            }
        }
        mShowPicturesSection.setVisibility(View.GONE);
    }

    @Override
    public void onClick(View view) {
        if (!isMessageOpen()) {
            return; // Ignore.
        }
        switch (view.getId()) {
            case R.id.from:
            case R.id.presence:
                onClickSender();
                break;
            case R.id.download:
                onDownloadAttachment((AttachmentInfo) view.getTag());
                break;
            case R.id.view:
                onViewAttachment((AttachmentInfo) view.getTag());
                break;
            case R.id.show_pictures:
                onShowPicturesInHtml();
                break;
        }
    }

    /**
     * Start checking presence of the sender of the message.
     *
     * Note:  This is just a polling operation.  A more advanced solution would be to keep the
     * cursor open and respond to presence status updates (in the form of content change
     * notifications).  However, because presence changes fairly slowly compared to the duration
     * of viewing a single message, a simple poll at message load (and onResume) should be
     * sufficient.
     */
    private void startPresenceCheck() {
        // Set "unknown" presence icon.
        mSenderPresenceView.setImageResource(PresenceUpdater.getPresenceIconResourceId(null));
        if (mMessage != null) {
            Address sender = Address.unpackFirst(mMessage.mFrom);
            if (sender != null) {
                String email = sender.getAddress();
                if (email != null) {
                    mPresenceUpdater.checkPresence(email, new PresenceUpdater.Callback() {
                        @Override
                        public void onPresenceResult(String emailAddress, Integer presenceStatus) {
                            mSenderPresenceView.setImageResource(
                                    PresenceUpdater.getPresenceIconResourceId(presenceStatus));
                        }
                    });
                }
            }
        }
    }

    /**
     * Called on a worker thread by {@link LoadMessageTask} to load a message in a subclass specific
     * way.
     */
    protected abstract Message openMessageSync();

    /**
     * Async task for loading a single message outside of the UI thread
     */
    private class LoadMessageTask extends AsyncTask<Void, Void, Message> {

        private final boolean mOkToFetch;

        /**
         * Special constructor to cache some local info
         */
        public LoadMessageTask(boolean okToFetch) {
            mOkToFetch = okToFetch;
        }

        @Override
        protected Message doInBackground(Void... params) {
            return openMessageSync();
        }

        @Override
        protected void onPostExecute(Message message) {
            /* doInBackground() may return null result (due to restoreMessageWithId())
             * and in that situation we want to Activity.finish().
             *
             * OTOH we don't want to Activity.finish() for isCancelled() because this
             * would introduce a surprise side-effect to task cancellation: every task
             * cancelation would also result in finish().
             *
             * Right now LoadMesageTask is cancelled not only from onDestroy(),
             * and it would be a bug to also finish() the activity in that situation.
             */
            if (isCancelled()) {
                return;
            }
            if (message == null) {
                mCallback.onMessageNotExists();
                return;
            }
            mMessageId = message.mId;

            reloadUiFromMessage(message, mOkToFetch);
            startPresenceCheck();
        }
    }

    /**
     * Called when the message body is loaded.
     */
    protected void onPostLoadBody() {
    }

    /**
     * Async task for loading a single message body outside of the UI thread
     */
    private class LoadBodyTask extends AsyncTask<Void, Void, String[]> {

        private long mId;
        private boolean mErrorLoadingMessageBody;

        /**
         * Special constructor to cache some local info
         */
        public LoadBodyTask(long messageId) {
            mId = messageId;
        }

        @Override
        protected String[] doInBackground(Void... params) {
            try {
                String text = null;
                String html = Body.restoreBodyHtmlWithMessageId(mContext, mId);
                if (html == null) {
                    text = Body.restoreBodyTextWithMessageId(mContext, mId);
                }
                return new String[] { text, html };
            } catch (RuntimeException re) {
                // This catches SQLiteException as well as other RTE's we've seen from the
                // database calls, such as IllegalStateException
                Log.d(Email.LOG_TAG, "Exception while loading message body: " + re.toString());
                mErrorLoadingMessageBody = true;
                return new String[] { null, null };
            }
        }

        @Override
        protected void onPostExecute(String[] results) {
            if (results == null) {
                if (mErrorLoadingMessageBody) {
                    Utility.showToast(getActivity(), R.string.error_loading_message_body);
                }
                return;
            }
            reloadUiFromBody(results[0], results[1]);    // text, html
            onPostLoadBody();
        }
    }

    /**
     * Async task for loading attachments
     *
     * Note:  This really should only be called when the message load is complete - or, we should
     * leave open a listener so the attachments can fill in as they are discovered.  In either case,
     * this implementation is incomplete, as it will fail to refresh properly if the message is
     * partially loaded at this time.
     */
    private class LoadAttachmentsTask extends AsyncTask<Long, Void, Attachment[]> {
        @Override
        protected Attachment[] doInBackground(Long... messageIds) {
            return Attachment.restoreAttachmentsWithMessageId(mContext, messageIds[0]);
        }

        @Override
        protected void onPostExecute(Attachment[] attachments) {
            if (attachments == null) {
                return;
            }
            boolean htmlChanged = false;
            for (Attachment attachment : attachments) {
                if (mHtmlTextRaw != null && attachment.mContentId != null
                        && attachment.mContentUri != null) {
                    // for html body, replace CID for inline images
                    // Regexp which matches ' src="cid:contentId"'.
                    String contentIdRe =
                        "\\s+(?i)src=\"cid(?-i):\\Q" + attachment.mContentId + "\\E\"";
                    String srcContentUri = " src=\"" + attachment.mContentUri + "\"";
                    mHtmlTextRaw = mHtmlTextRaw.replaceAll(contentIdRe, srcContentUri);
                    htmlChanged = true;
                } else {
                    addAttachment(attachment);
                }
            }
            mHtmlTextWebView = mHtmlTextRaw;
            mHtmlTextRaw = null;
            if (htmlChanged && mMessageContentView != null) {
                mMessageContentView.loadDataWithBaseURL("email://", mHtmlTextWebView,
                                                        "text/html", "utf-8", null);
            }
        }
    }

    private Bitmap getPreviewIcon(AttachmentInfo attachment) {
        try {
            return BitmapFactory.decodeStream(
                    mContext.getContentResolver().openInputStream(
                            AttachmentProvider.getAttachmentThumbnailUri(
                                    mAccountId, attachment.attachmentId,
                                    PREVIEW_ICON_WIDTH,
                                    PREVIEW_ICON_HEIGHT)));
        } catch (Exception e) {
            // We don't care what happened, we just return null for the preview icon.
            return null;
        }
    }

    private void updateAttachmentThumbnail(long attachmentId) {
        for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
            AttachmentInfo attachment = (AttachmentInfo) mAttachments.getChildAt(i).getTag();
            if (attachment.attachmentId == attachmentId) {
                Bitmap previewIcon = getPreviewIcon(attachment);
                if (previewIcon != null) {
                    attachment.iconView.setImageBitmap(previewIcon);
                }
                return;
            }
        }
    }

    /**
     * Copy data from a cursor-refreshed attachment into the UI.  Called from UI thread.
     *
     * @param attachment A single attachment loaded from the provider
     */
    private void addAttachment(Attachment attachment) {
        AttachmentInfo attachmentInfo = new AttachmentInfo();
        attachmentInfo.size = attachment.mSize;
        attachmentInfo.contentType =
                AttachmentProvider.inferMimeType(attachment.mFileName, attachment.mMimeType);
        attachmentInfo.name = attachment.mFileName;
        attachmentInfo.attachmentId = attachment.mId;

        LayoutInflater inflater = getActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.message_view_attachment, null);

        TextView attachmentName = (TextView)view.findViewById(R.id.attachment_name);
        TextView attachmentInfoView = (TextView)view.findViewById(R.id.attachment_info);
        ImageView attachmentIcon = (ImageView)view.findViewById(R.id.attachment_icon);
        Button attachmentView = (Button)view.findViewById(R.id.view);
        Button attachmentDownload = (Button)view.findViewById(R.id.download);

        if ((!MimeUtility.mimeTypeMatches(attachmentInfo.contentType,
                Email.ACCEPTABLE_ATTACHMENT_VIEW_TYPES))
                || (MimeUtility.mimeTypeMatches(attachmentInfo.contentType,
                        Email.UNACCEPTABLE_ATTACHMENT_VIEW_TYPES))) {
            attachmentView.setVisibility(View.GONE);
        }
        if ((!MimeUtility.mimeTypeMatches(attachmentInfo.contentType,
                Email.ACCEPTABLE_ATTACHMENT_DOWNLOAD_TYPES))
                || (MimeUtility.mimeTypeMatches(attachmentInfo.contentType,
                        Email.UNACCEPTABLE_ATTACHMENT_DOWNLOAD_TYPES))) {
            attachmentDownload.setVisibility(View.GONE);
        }

        if (attachmentInfo.size > Email.MAX_ATTACHMENT_DOWNLOAD_SIZE) {
            attachmentView.setVisibility(View.GONE);
            attachmentDownload.setVisibility(View.GONE);
        }

        attachmentInfo.viewButton = attachmentView;
        attachmentInfo.downloadButton = attachmentDownload;
        attachmentInfo.iconView = attachmentIcon;

        view.setTag(attachmentInfo);
        attachmentView.setOnClickListener(this);
        attachmentView.setTag(attachmentInfo);
        attachmentDownload.setOnClickListener(this);
        attachmentDownload.setTag(attachmentInfo);

        attachmentName.setText(attachmentInfo.name);
        attachmentInfoView.setText(Utility.formatSize(mContext, attachmentInfo.size));

        Bitmap previewIcon = getPreviewIcon(attachmentInfo);
        if (previewIcon != null) {
            attachmentIcon.setImageBitmap(previewIcon);
        }

        mAttachments.addView(view);
        mAttachments.setVisibility(View.VISIBLE);
    }

    /**
     * Reload the UI from a provider cursor.  This must only be called from the UI thread.
     *
     * @param message A copy of the message loaded from the database
     * @param okToFetch If true, and message is not fully loaded, it's OK to fetch from
     * the network.  Use false to prevent looping here.
     *
     * TODO: trigger presence check
     */
    protected void reloadUiFromMessage(Message message, boolean okToFetch) {
        mMessage = message;
        mAccountId = message.mAccountKey;

        mSubjectView.setText(message.mSubject);
        mFromView.setText(Address.toFriendly(Address.unpack(message.mFrom)));
        Date date = new Date(message.mTimeStamp);
        mTimeView.setText(mTimeFormat.format(date));
        mDateView.setText(Utility.isDateToday(date) ? null : mDateFormat.format(date));
        mToView.setText(Address.toFriendly(Address.unpack(message.mTo)));
        String friendlyCc = Address.toFriendly(Address.unpack(message.mCc));
        mCcView.setText(friendlyCc);
        mCcContainerView.setVisibility((friendlyCc != null) ? View.VISIBLE : View.GONE);
        mAttachmentIcon.setVisibility(message.mAttachments != null ? View.VISIBLE : View.GONE);

        // Handle partially-loaded email, as follows:
        // 1. Check value of message.mFlagLoaded
        // 2. If != LOADED, ask controller to load it
        // 3. Controller callback (after loaded) should trigger LoadBodyTask & LoadAttachmentsTask
        // 4. Else start the loader tasks right away (message already loaded)
        if (okToFetch && message.mFlagLoaded != Message.FLAG_LOADED_COMPLETE) {
            mControllerCallback.getWrappee().setWaitForLoadMessageId(message.mId);
            mController.loadMessageForView(message.mId);
        } else {
            mControllerCallback.getWrappee().setWaitForLoadMessageId(-1);
            // Ask for body
            mLoadBodyTask = new LoadBodyTask(message.mId);
            mLoadBodyTask.execute();
        }
    }

    /**
     * Reload the body from the provider cursor.  This must only be called from the UI thread.
     *
     * @param bodyText text part
     * @param bodyHtml html part
     *
     * TODO deal with html vs text and many other issues <- WHAT DOES IT MEAN??
     */
    private void reloadUiFromBody(String bodyText, String bodyHtml) {
        String text = null;
        mHtmlTextRaw = null;
        boolean hasImages = false;

        if (bodyHtml == null) {
            text = bodyText;
            /*
             * Convert the plain text to HTML
             */
            StringBuffer sb = new StringBuffer("<html><body>");
            if (text != null) {
                // Escape any inadvertent HTML in the text message
                text = EmailHtmlUtil.escapeCharacterToDisplay(text);
                // Find any embedded URL's and linkify
                Matcher m = Patterns.WEB_URL.matcher(text);
                while (m.find()) {
                    int start = m.start();
                    /*
                     * WEB_URL_PATTERN may match domain part of email address. To detect
                     * this false match, the character just before the matched string
                     * should not be '@'.
                     */
                    if (start == 0 || text.charAt(start - 1) != '@') {
                        String url = m.group();
                        Matcher proto = WEB_URL_PROTOCOL.matcher(url);
                        String link;
                        if (proto.find()) {
                            // This is work around to force URL protocol part be lower case,
                            // because WebView could follow only lower case protocol link.
                            link = proto.group().toLowerCase() + url.substring(proto.end());
                        } else {
                            // Patterns.WEB_URL matches URL without protocol part,
                            // so added default protocol to link.
                            link = "http://" + url;
                        }
                        String href = String.format("<a href=\"%s\">%s</a>", link, url);
                        m.appendReplacement(sb, href);
                    }
                    else {
                        m.appendReplacement(sb, "$0");
                    }
                }
                m.appendTail(sb);
            }
            sb.append("</body></html>");
            text = sb.toString();
        } else {
            text = bodyHtml;
            mHtmlTextRaw = bodyHtml;
            hasImages = IMG_TAG_START_REGEX.matcher(text).find();
        }

        // TODO this is not really accurate.
        // - Images aren't the only network resources.  (e.g. CSS)
        // - If images are attached to the email and small enough, we download them at once,
        //   and won't need network access when they're shown.
        mShowPicturesSection.setVisibility(hasImages ? View.VISIBLE : View.GONE);
        if (mMessageContentView != null) {
            mMessageContentView.loadDataWithBaseURL("email://", text, "text/html", "utf-8", null);
        }

        // Ask for attachments after body
        mLoadAttachmentsTask = new LoadAttachmentsTask();
        mLoadAttachmentsTask.execute(mMessage.mId);

        mIsMessageLoadedForTest = true;
    }

    /**
     * Overrides for WebView behaviors.
     */
    private class CustomWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return mCallback.onUrlInMessageClicked(url);
        }
    }

    /**
     * Controller results listener.  We wrap it with {@link ControllerResultUiThreadWrapper},
     * so all methods are called on the UI thread.
     */
    private class ControllerResults extends Controller.Result {
        private long mWaitForLoadMessageId;

        public void setWaitForLoadMessageId(long messageId) {
            mWaitForLoadMessageId = messageId;
        }

        @Override
        public void loadMessageForViewCallback(MessagingException result, long messageId,
                int progress) {
            if (messageId != mWaitForLoadMessageId) {
                // We are not waiting for this message to load, so exit quickly
                return;
            }
            if (result == null) {
                switch (progress) {
                    case 0:
                        mCallback.onLoadMessageStarted();
                        loadBodyContent("file:///android_asset/loading.html");
                        break;
                    case 100:
                        mWaitForLoadMessageId = -1;
                        mCallback.onLoadMessageFinished();
                        // reload UI and reload everything else too
                        // pass false to LoadMessageTask to prevent looping here
                        cancelAllTasks();
                        mLoadMessageTask = new LoadMessageTask(false);
                        mLoadMessageTask.execute();
                        break;
                    default:
                        // do nothing - we don't have a progress bar at this time
                        break;
                }
            } else {
                mWaitForLoadMessageId = -1;
                mCallback.onLoadMessageError();
                Utility.showToast(getActivity(), R.string.status_network_error);
                loadBodyContent("file:///android_asset/empty.html");
            }
        }

        private void loadBodyContent(String uri) {
            if (mMessageContentView != null) {
                mMessageContentView.loadUrl(uri);
            }
        }

        @Override
        public void loadAttachmentCallback(MessagingException result, long messageId,
                long attachmentId, int progress) {
            if (messageId == mMessageId) {
                if (result == null) {
                    switch (progress) {
                        case 0:
                            enableAttachments(false);
                            mCallback.onFetchAttachmentStarted(mLoadAttachmentName);
                            Utility.showToast(getActivity(),
                                    R.string.message_view_fetching_attachment_toast);
                            break;
                        case 100:
                            enableAttachments(true);
                            mCallback.onFetchAttachmentFinished();
                            updateAttachmentThumbnail(attachmentId);
                            doFinishLoadAttachment(attachmentId);
                            break;
                        default:
                            // do nothing - we don't have a progress bar at this time
                            break;
                    }
                } else {
                    enableAttachments(true);
                    mCallback.onFetchAttachmentError();
                    Utility.showToast(getActivity(), R.string.status_network_error);
                }
            }
        }

        private void enableAttachments(boolean enable) {
            for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
                AttachmentInfo attachment = (AttachmentInfo) mAttachments.getChildAt(i).getTag();
                attachment.viewButton.setEnabled(enable);
                attachment.downloadButton.setEnabled(enable);
            }
        }

        @Override
        public void updateMailboxCallback(MessagingException result, long accountId,
                long mailboxId, int progress, int numNewMessages) {
            if (result != null || progress == 100) {
                Email.updateMailboxRefreshTime(mailboxId);
            }
        }
    }

    public boolean isMessageLoadedForTest() {
        return mIsMessageLoadedForTest;
    }

    public void clearIsMessageLoadedForTest() {
        mIsMessageLoadedForTest = true;
    }
}
