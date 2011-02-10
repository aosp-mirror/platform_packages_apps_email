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

import com.android.email.AttachmentInfo;
import com.android.email.Controller;
import com.android.email.ControllerResultUiThreadWrapper;
import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.Throttle;
import com.android.email.Utility;
import com.android.email.mail.internet.EmailHtmlUtil;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.service.AttachmentDownloadService;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.utility.AttachmentUtilities;

import org.apache.commons.io.IOUtils;

import android.app.Activity;
import android.app.Fragment;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.ContactsContract;
import android.provider.ContactsContract.QuickContact;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Formatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// TODO Better handling of config changes.
// - Restore "Show pictures" state, scroll position and current tab
// - Retain the content; don't kick 3 async tasks every time

/**
 * Base class for {@link MessageViewFragment} and {@link MessageFileViewFragment}.
 *
 * See {@link MessageViewBase} for the class relation diagram.
 */
public abstract class MessageViewFragmentBase extends Fragment implements View.OnClickListener {
    private static final int PHOTO_LOADER_ID = 1;
    private Context mContext;

    // Regex that matches start of img tag. '<(?i)img\s+'.
    private static final Pattern IMG_TAG_START_REGEX = Pattern.compile("<(?i)img\\s+");
    // Regex that matches Web URL protocol part as case insensitive.
    private static final Pattern WEB_URL_PROTOCOL = Pattern.compile("(?i)http|https://");

    private static int PREVIEW_ICON_WIDTH = 62;
    private static int PREVIEW_ICON_HEIGHT = 62;

    private TextView mSubjectView;
    private TextView mFromNameView;
    private TextView mFromAddressView;
    private TextView mDateTimeView;
    private TextView mAddressesView;
    private WebView mMessageContentView;
    private LinearLayout mAttachments;
    private View mTabSection;
    private ImageView mFromBadge;
    private ImageView mSenderPresenceView;
    private View mMainView;
    private View mLoadingProgress;
    private Button mShowDetailsButton;

    private TextView mMessageTab;
    private TextView mAttachmentTab;
    private TextView mInviteTab;
    // It is not really a tab, but looks like one of them.
    private TextView mShowPicturesTab;

    private View mAttachmentsScroll;
    private View mInviteScroll;

    private long mAccountId = -1;
    private long mMessageId = -1;
    private Message mMessage;

    private LoadMessageTask mLoadMessageTask;
    private ReloadMessageTask mReloadMessageTask;
    private LoadBodyTask mLoadBodyTask;
    private LoadAttachmentsTask mLoadAttachmentsTask;

    private Controller mController;
    private ControllerResultUiThreadWrapper<ControllerResults> mControllerCallback;

    // contains the HTML body. Is used by LoadAttachmentTask to display inline images.
    // is null most of the time, is used transiently to pass info to LoadAttachementTask
    private String mHtmlTextRaw;

    // contains the HTML content as set in WebView.
    private String mHtmlTextWebView;

    private boolean mResumed;
    private boolean mLoadWhenResumed;

    private boolean mIsMessageLoadedForTest;

    private MessageObserver mMessageObserver;

    private static final int CONTACT_STATUS_STATE_UNLOADED = 0;
    private static final int CONTACT_STATUS_STATE_UNLOADED_TRIGGERED = 1;
    private static final int CONTACT_STATUS_STATE_LOADED = 2;

    private int mContactStatusState;
    private Uri mQuickContactLookupUri;

    /** Flag for {@link #mTabFlags}: Message has attachment(s) */
    protected static final int TAB_FLAGS_HAS_ATTACHMENT = 1;

    /**
     * Flag for {@link #mTabFlags}: Message contains invite.  This flag is only set by
     * {@link MessageViewFragment}.
     */
    protected static final int TAB_FLAGS_HAS_INVITE = 2;

    /** Flag for {@link #mTabFlags}: Message contains pictures */
    protected static final int TAB_FLAGS_HAS_PICTURES = 4;

    /** Flag for {@link #mTabFlags}: "Show pictures" has already been pressed */
    protected static final int TAB_FLAGS_PICTURE_LOADED = 8;

    /**
     * Flags to control the tabs.
     * @see #updateTabFlags(int)
     */
    private int mTabFlags;

    /** # of attachments in the current message */
    private int mAttachmentCount;

    // Use (random) large values, to avoid confusion with TAB_FLAGS_*
    protected static final int TAB_MESSAGE = 101;
    protected static final int TAB_INVITE = 102;
    protected static final int TAB_ATTACHMENT = 103;

    /**
     * Currently visible tab.  Any of {@link #TAB_MESSAGE}, {@link #TAB_INVITE} or
     * {@link #TAB_ATTACHMENT}.
     *
     * Note we don't retain this value through configuration changes, as restoring the current tab
     * would be clumsy with the current implementation where we load Message/Body/Attachments
     * separately.  (e.g. # of attachments can't be obtained quickly enough to update the UI
     * after screen rotation.)
     */
    private int mCurrentTab;

    /**
     * Zoom scales for webview.  Values correspond to {@link Preferences#TEXT_ZOOM_TINY}..
     * {@link Preferences#TEXT_ZOOM_HUGE}.
     */
    private static final float[] ZOOM_SCALE_ARRAY = new float[] {0.8f, 0.9f, 1.0f, 1.2f, 1.5f};

    public interface Callback {
        /** Called when the fragment is about to show up, or show a different message. */
        public void onMessageViewShown(int mailboxType);

        /** Called when the fragment is about to be destroyed. */
        public void onMessageViewGone();

        /**
         * Called when a link in a message is clicked.
         *
         * @param url link url that's clicked.
         * @return true if handled, false otherwise.
         */
        public boolean onUrlInMessageClicked(String url);

        /**
         * Called when the message specified doesn't exist, or is deleted/moved.
         */
        public void onMessageNotExists();

        /** Called when it starts loading a message. */
        public void onLoadMessageStarted();

        /** Called when it successfully finishes loading a message. */
        public void onLoadMessageFinished();

        /** Called when an error occurred during loading a message. */
        public void onLoadMessageError(String errorMessage);
    }

    public static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onMessageViewShown(int mailboxType) {}
        @Override public void onMessageViewGone() {}
        @Override public void onLoadMessageError(String errorMessage) {}
        @Override public void onLoadMessageFinished() {}
        @Override public void onLoadMessageStarted() {}
        @Override public void onMessageNotExists() {}
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

        mController = Controller.getInstance(mContext);
        mMessageObserver = new MessageObserver(new Handler(), mContext);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onCreateView");
        }
        final View view = inflater.inflate(R.layout.message_view_fragment, container, false);

        mSubjectView = (TextView) view.findViewById(R.id.subject);
        mFromNameView = (TextView) view.findViewById(R.id.from_name);
        mFromAddressView = (TextView) view.findViewById(R.id.from_address);
        mAddressesView = (TextView) view.findViewById(R.id.addresses);
        mDateTimeView = (TextView) view.findViewById(R.id.datetime);
        mMessageContentView = (WebView) view.findViewById(R.id.message_content);
        mAttachments = (LinearLayout) view.findViewById(R.id.attachments);
        mTabSection = view.findViewById(R.id.message_tabs_section);
        mFromBadge = (ImageView) view.findViewById(R.id.badge);
        mSenderPresenceView = (ImageView) view.findViewById(R.id.presence);
        mMainView = view.findViewById(R.id.main_panel);
        mLoadingProgress = view.findViewById(R.id.loading_progress);
        mShowDetailsButton = (Button) view.findViewById(R.id.show_details);

        mFromNameView.setOnClickListener(this);
        mFromAddressView.setOnClickListener(this);
        mFromBadge.setOnClickListener(this);
        mSenderPresenceView.setOnClickListener(this);

        mMessageTab = (TextView) view.findViewById(R.id.show_message);
        mAttachmentTab = (TextView) view.findViewById(R.id.show_attachments);
        mShowPicturesTab = (TextView) view.findViewById(R.id.show_pictures);
        // Invite is only used in MessageViewFragment, but visibility is controlled here.
        mInviteTab = (TextView) view.findViewById(R.id.show_invite);

        mMessageTab.setOnClickListener(this);
        mAttachmentTab.setOnClickListener(this);
        mShowPicturesTab.setOnClickListener(this);
        mInviteTab.setOnClickListener(this);
        mShowDetailsButton.setOnClickListener(this);

        mAttachmentsScroll = view.findViewById(R.id.attachments_scroll);
        mInviteScroll = view.findViewById(R.id.invite_scroll);

        WebSettings webSettings = mMessageContentView.getSettings();
        webSettings.setBlockNetworkLoads(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
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
    }

    @Override
    public void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onResume");
        }
        super.onResume();

        mResumed = true;
        if (isMessageSpecified()) {
            if (mLoadWhenResumed) {
                loadMessageIfResumed();
            } else {
                // This means, the user comes back from other (full-screen) activities.
                // In this case we've already loaded the content, so don't load it again,
                // which results in resetting all view state, including WebView zoom/pan
                // and the current tab.
            }
        }
    }

    @Override
    public void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onPause");
        }
        mResumed = false;
        super.onPause();
    }

    @Override
    public void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onStop");
        }
        super.onStop();
    }

    @Override
    public void onDestroy() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageViewFragment onDestroy");
        }
        mCallback.onMessageViewGone();
        mController.removeResultCallback(mControllerCallback);
        clearContent();
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
        mMessageObserver.unregister();
        Utility.cancelTaskInterrupt(mLoadMessageTask);
        mLoadMessageTask = null;
        Utility.cancelTaskInterrupt(mReloadMessageTask);
        mReloadMessageTask = null;
        Utility.cancelTaskInterrupt(mLoadBodyTask);
        mLoadBodyTask = null;
        Utility.cancelTaskInterrupt(mLoadAttachmentsTask);
        mLoadAttachmentsTask = null;
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

    /**
     * Clear all the content -- should be called when the fragment is hidden.
     */
    public void clearContent() {
        cancelAllTasks();
        resetView();
    }

    protected final void loadMessageIfResumed() {
        if (!mResumed) {
            mLoadWhenResumed = true;
            return;
        }
        mLoadWhenResumed = false;
        cancelAllTasks();
        resetView();
        mLoadMessageTask = new LoadMessageTask(true);
        mLoadMessageTask.execute();
    }

    /**
     * Show/hide the content.  We hide all the content (except for the bottom buttons) when loading,
     * to avoid flicker.
     */
    private void showContent(boolean showContent, boolean showProgressWhenHidden) {
        if (mLoadingProgress == null) {
            // Phone UI doesn't have it yet.
            // TODO Add loading_progress and main_panel to the phone layout too.
        } else {
            makeVisible(mMainView, showContent);
            makeVisible(mLoadingProgress, !showContent && showProgressWhenHidden);
        }
    }

    protected void resetView() {
        showContent(false, false);
        setCurrentTab(TAB_MESSAGE);
        updateTabFlags(0);
        if (mMessageContentView != null) {
            mMessageContentView.getSettings().setBlockNetworkLoads(true);
            mMessageContentView.scrollTo(0, 0);
            mMessageContentView.clearView();

            // Dynamic configuration of WebView
            final WebSettings settings = mMessageContentView.getSettings();
            settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
            mMessageContentView.setInitialScale(getWebViewZoom());
        }
        mAttachmentsScroll.scrollTo(0, 0);
        mInviteScroll.scrollTo(0, 0);
        mAttachments.removeAllViews();
        mAttachments.setVisibility(View.GONE);
        initContactStatusViews();
    }

    /**
     * Returns the zoom scale (in percent) which is a combination of the user setting
     * (tiny, small, normal, large, huge) and the device density. The intention
     * is for the text to be physically equal in size over different density
     * screens.
     */
    private int getWebViewZoom() {
        float density = mContext.getResources().getDisplayMetrics().density;
        int zoom = Preferences.getPreferences(mContext).getTextZoom();
        return (int) (ZOOM_SCALE_ARRAY[zoom] * density * 100);
    }

    private void initContactStatusViews() {
        mContactStatusState = CONTACT_STATUS_STATE_UNLOADED;
        mQuickContactLookupUri = null;
        mSenderPresenceView.setImageResource(ContactStatusLoader.PRESENCE_UNKNOWN_RESOURCE_ID);
        showDefaultQuickContactBadgeImage();
    }

    private void showDefaultQuickContactBadgeImage() {
        mFromBadge.setImageResource(R.drawable.ic_contact_picture);
    }

    protected final void addTabFlags(int tabFlags) {
        updateTabFlags(mTabFlags | tabFlags);
    }

    private final void clearTabFlags(int tabFlags) {
        updateTabFlags(mTabFlags & ~tabFlags);
    }

    private void setAttachmentCount(int count) {
        mAttachmentCount = count;
        if (mAttachmentCount > 0) {
            addTabFlags(TAB_FLAGS_HAS_ATTACHMENT);
        } else {
            clearTabFlags(TAB_FLAGS_HAS_ATTACHMENT);
        }
    }

    private static void makeVisible(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static boolean isVisible(View v) {
        return v.getVisibility() == View.VISIBLE;
    }

    /**
     * Update the visual of the tabs.  (visibility, text, etc)
     */
    private void updateTabFlags(int tabFlags) {
        mTabFlags = tabFlags;
        boolean messageTabVisible = (tabFlags & (TAB_FLAGS_HAS_INVITE | TAB_FLAGS_HAS_ATTACHMENT))
                != 0;
        makeVisible(mMessageTab, messageTabVisible);
        makeVisible(mInviteTab, (tabFlags & TAB_FLAGS_HAS_INVITE) != 0);
        makeVisible(mAttachmentTab, (tabFlags & TAB_FLAGS_HAS_ATTACHMENT) != 0);

        final boolean hasPictures = (tabFlags & TAB_FLAGS_HAS_PICTURES) != 0;
        final boolean pictureLoaded = (tabFlags & TAB_FLAGS_PICTURE_LOADED) != 0;
        makeVisible(mShowPicturesTab, hasPictures && !pictureLoaded);

        mAttachmentTab.setText(mContext.getResources().getQuantityString(
                R.plurals.message_view_show_attachments_action,
                mAttachmentCount, mAttachmentCount));

        // Hide the entire section if no tabs are visible.
        makeVisible(mTabSection, isVisible(mMessageTab) || isVisible(mInviteTab)
                || isVisible(mAttachmentTab) || isVisible(mShowPicturesTab));
    }

    /**
     * Set the current tab.
     *
     * @param tab any of {@link #TAB_MESSAGE}, {@link #TAB_ATTACHMENT} or {@link #TAB_INVITE}.
     */
    private void setCurrentTab(int tab) {
        mCurrentTab = tab;
        if (mMessageContentView != null) {
            makeVisible(mMessageContentView, tab == TAB_MESSAGE);
        }
        mMessageTab.setSelected(tab == TAB_MESSAGE);

        makeVisible(mAttachmentsScroll, tab == TAB_ATTACHMENT);
        mAttachmentTab.setSelected(tab == TAB_ATTACHMENT);

        makeVisible(mInviteScroll, tab == TAB_INVITE);
        mInviteTab.setSelected(tab == TAB_INVITE);
    }

    /**
     * Handle clicks on sender, which shows {@link QuickContact} or prompts to add
     * the sender as a contact.
     */
    private void onClickSender() {
        final Address senderEmail = Address.unpackFirst(mMessage.mFrom);
        if (senderEmail == null) return;

        if (mContactStatusState == CONTACT_STATUS_STATE_UNLOADED) {
            // Status not loaded yet.
            mContactStatusState = CONTACT_STATUS_STATE_UNLOADED_TRIGGERED;
            return;
        }
        if (mContactStatusState == CONTACT_STATUS_STATE_UNLOADED_TRIGGERED) {
            return; // Already clicked, and waiting for the data.
        }

        if (mQuickContactLookupUri != null) {
            QuickContact.showQuickContact(mContext, mFromBadge, mQuickContactLookupUri,
                        QuickContact.MODE_LARGE, null);
        } else {
            // No matching contact, ask user to create one
            final Uri mailUri = Uri.fromParts("mailto", senderEmail.getAddress(), null);
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

    private static class ContactStatusLoaderCallbacks
            implements LoaderCallbacks<ContactStatusLoader.Result> {
        private static final String BUNDLE_EMAIL_ADDRESS = "email";
        private final MessageViewFragmentBase mFragment;

        public ContactStatusLoaderCallbacks(MessageViewFragmentBase fragment) {
            mFragment = fragment;
        }

        public static Bundle createArguments(String emailAddress) {
            Bundle b = new Bundle();
            b.putString(BUNDLE_EMAIL_ADDRESS, emailAddress);
            return b;
        }

        @Override
        public Loader<ContactStatusLoader.Result> onCreateLoader(int id, Bundle args) {
            return new ContactStatusLoader(mFragment.mContext,
                    args.getString(BUNDLE_EMAIL_ADDRESS));
        }

        @Override
        public void onLoadFinished(Loader<ContactStatusLoader.Result> loader,
                ContactStatusLoader.Result result) {
            boolean triggered =
                    (mFragment.mContactStatusState == CONTACT_STATUS_STATE_UNLOADED_TRIGGERED);
            mFragment.mContactStatusState = CONTACT_STATUS_STATE_LOADED;
            mFragment.mQuickContactLookupUri = result.mLookupUri;
            mFragment.mSenderPresenceView.setImageResource(result.mPresenceResId);
            if (result.mPhoto != null) { // photo will be null if unknown.
                mFragment.mFromBadge.setImageBitmap(result.mPhoto);
            }
            if (triggered) {
                mFragment.onClickSender();
            }
        }

        @Override
        public void onLoaderReset(Loader<ContactStatusLoader.Result> loader) {
        }
    }

    private void onSaveAttachment(MessageViewAttachmentInfo info) {
        if (!Utility.isExternalStorageMounted()) {
            /*
             * Abort early if there's no place to save the attachment. We don't want to spend
             * the time downloading it and then abort.
             */
            Utility.showToast(getActivity(), R.string.message_view_status_attachment_not_saved);
            return;
        }
        Attachment attachment = Attachment.restoreAttachmentWithId(mContext, info.mId);
        Uri attachmentUri = AttachmentUtilities.getAttachmentUri(mAccountId, attachment.mId);

        try {
            File downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            downloads.mkdirs();
            File file = Utility.createUniqueFile(downloads, attachment.mFileName);
            Uri contentUri = AttachmentUtilities.resolveAttachmentIdToContentUri(
                    mContext.getContentResolver(), attachmentUri);
            InputStream in = mContext.getContentResolver().openInputStream(contentUri);
            OutputStream out = new FileOutputStream(file);
            IOUtils.copy(in, out);
            out.flush();
            out.close();
            in.close();

            Utility.showToast(getActivity(), String.format(
                    mContext.getString(R.string.message_view_status_attachment_saved),
                    file.getName()));
            // Run the attachment through the media scanner; adds supported types to the media
            // content provider
            MediaScannerConnection.scanFile(mContext, new String[] {file.getAbsolutePath()},
                    null, null);
        } catch (IOException ioe) {
            Utility.showToast(getActivity(), R.string.message_view_status_attachment_not_saved);
        }
    }

    private void onViewAttachment(MessageViewAttachmentInfo info) {
        Intent intent = info.getAttachmentIntent(mContext, mAccountId);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Utility.showToast(getActivity(), R.string.message_view_display_attachment_toast);
        }
    }


    private void onLoadAttachment(final MessageViewAttachmentInfo attachment) {
        attachment.loadButton.setVisibility(View.GONE);
        // If there's nothing in the download queue, we'll probably start right away so wait a
        // second before showing the cancel button
        if (AttachmentDownloadService.getQueueSize() == 0) {
            // Set to invisible; if the button is still in this state one second from now, we'll
            // assume the download won't start right away, and we make the cancel button visible
            attachment.cancelButton.setVisibility(View.GONE);
            // Create the timed task that will change the button state
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) { }
                    return null;
                }
                @Override
                protected void onPostExecute(Void result) {
                    // If the timeout completes and the attachment has not loaded, show cancel
                    if (!attachment.loaded) {
                        attachment.cancelButton.setVisibility(View.VISIBLE);
                    }
                }
            }.execute();
        } else {
            attachment.cancelButton.setVisibility(View.VISIBLE);
        }
        ProgressBar bar = attachment.progressView;
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        mController.loadAttachment(attachment.mId, mMessageId, mAccountId);
    }

    private void onCancelAttachment(MessageViewAttachmentInfo attachment) {
        // Don't change button states if we couldn't cancel the download
        if (AttachmentDownloadService.cancelQueuedAttachment(attachment.mId)) {
            attachment.loadButton.setVisibility(View.VISIBLE);
            attachment.cancelButton.setVisibility(View.GONE);
            ProgressBar bar = attachment.progressView;
            bar.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * Called by ControllerResults. Show the "View" and "Save" buttons; hide "Load" and "Stop"
     *
     * @param attachmentId the attachment that was just downloaded
     */
    private void doFinishLoadAttachment(long attachmentId) {
        MessageViewAttachmentInfo info = findAttachmentInfo(attachmentId);
        if (info != null) {
            info.loaded = true;

            info.loadButton.setVisibility(View.GONE);
            info.cancelButton.setVisibility(View.GONE);

            boolean showSave = info.enableSave && !TextUtils.isEmpty(info.mName);
            boolean showView = info.enableView;
            info.saveButton.setVisibility(showSave ? View.VISIBLE : View.GONE);
            info.viewButton.setVisibility(showView ? View.VISIBLE : View.GONE);
        }
    }

    private void onShowPicturesInHtml() {
        if (mMessageContentView != null) {
            mMessageContentView.getSettings().setBlockNetworkLoads(false);
            if (mHtmlTextWebView != null) {
                mMessageContentView.loadDataWithBaseURL("email://", mHtmlTextWebView,
                                                        "text/html", "utf-8", null);
            }
            addTabFlags(TAB_FLAGS_PICTURE_LOADED);
        }
    }

    private void onShowDetails() {
        if (mMessage == null) {
            return; // shouldn't happen
        }
        String subject = mMessage.mSubject;
        String date = formatDate(mMessage.mTimeStamp, true);

        final String SEPARATOR = "\n";
        String from = Address.toString(Address.unpack(mMessage.mFrom), SEPARATOR);
        String to = Address.toString(Address.unpack(mMessage.mTo), SEPARATOR);
        String cc = Address.toString(Address.unpack(mMessage.mCc), SEPARATOR);
        String bcc = Address.toString(Address.unpack(mMessage.mBcc), SEPARATOR);
        MessageViewMessageDetailsDialog dialog = MessageViewMessageDetailsDialog.newInstance(
                getActivity(), subject, date, from, to, cc, bcc);
        dialog.show(getActivity().getFragmentManager(), null);
    }

    @Override
    public void onClick(View view) {
        if (!isMessageOpen()) {
            return; // Ignore.
        }
        switch (view.getId()) {
            case R.id.from_name:
            case R.id.from_address:
            case R.id.badge:
            case R.id.presence:
                onClickSender();
                break;
            case R.id.load:
                onLoadAttachment((MessageViewAttachmentInfo) view.getTag());
                break;
            case R.id.save:
                onSaveAttachment((MessageViewAttachmentInfo) view.getTag());
                break;
            case R.id.view:
                onViewAttachment((MessageViewAttachmentInfo) view.getTag());
                break;
            case R.id.cancel:
                onCancelAttachment((MessageViewAttachmentInfo) view.getTag());
                break;
            case R.id.show_message:
                setCurrentTab(TAB_MESSAGE);
                break;
            case R.id.show_invite:
                setCurrentTab(TAB_INVITE);
                break;
            case R.id.show_attachments:
                setCurrentTab(TAB_ATTACHMENT);
                break;
            case R.id.show_pictures:
                onShowPicturesInHtml();
                break;
            case R.id.show_details:
                onShowDetails();
                break;
        }
    }

    /**
     * Start loading contact photo and presence.
     */
    private void queryContactStatus() {
        initContactStatusViews(); // Initialize the state, just in case.

        // Find the sender email address, and start presence check.
        if (mMessage != null) {
            Address sender = Address.unpackFirst(mMessage.mFrom);
            if (sender != null) {
                String email = sender.getAddress();
                if (email != null) {
                    getLoaderManager().restartLoader(PHOTO_LOADER_ID,
                            ContactStatusLoaderCallbacks.createArguments(email),
                            new ContactStatusLoaderCallbacks(this));
                }
            }
        }
    }

    /**
     * Called by {@link LoadMessageTask} and {@link ReloadMessageTask} to load a message in a
     * subclass specific way.
     *
     * NOTE This method is called on a worker thread!  Implementations must properly synchronize
     * when accessing members.  This method may be called after or even at the same time as
     * {@link #clearContent()}.
     *
     * @param activity the parent activity.  Subclass use it as a context, and to show a toast.
     */
    protected abstract Message openMessageSync(Activity activity);

    /**
     * Async task for loading a single message outside of the UI thread
     */
    private class LoadMessageTask extends AsyncTask<Void, Void, Message> {

        private final boolean mOkToFetch;
        private int mMailboxType;

        /**
         * Special constructor to cache some local info
         */
        public LoadMessageTask(boolean okToFetch) {
            mOkToFetch = okToFetch;
        }

        @Override
        protected Message doInBackground(Void... params) {
            Activity activity = getActivity();
            Message message = null;
            if (activity != null) {
                message = openMessageSync(activity);
            }
            if (message != null) {
                mMailboxType = Mailbox.getMailboxType(mContext, message.mMailboxKey);
                if (mMailboxType == -1) {
                    message = null; // mailbox removed??
                }
            }
            return message;
        }

        @Override
        protected void onPostExecute(Message message) {
            if (isCancelled()) {
                return;
            }
            if (message == null) {
                resetView();
                mCallback.onMessageNotExists();
                return;
            }
            mMessageId = message.mId;

            reloadUiFromMessage(message, mOkToFetch);
            queryContactStatus();
            onMessageShown(mMessageId, mMailboxType);
        }
    }

    /**
     * Kicked by {@link MessageObserver}.  Reload the message and update the views.
     */
    private class ReloadMessageTask extends AsyncTask<Void, Void, Message> {
        @Override
        protected Message doInBackground(Void... params) {
            if (!isMessageSpecified()) { // just in case
                return null;
            }
            Activity activity = getActivity();
            if (activity == null) {
                return null;
            } else {
                return openMessageSync(activity);
            }
        }

        @Override
        protected void onPostExecute(Message message) {
            if (isCancelled()) {
                return;
            }
            if (message == null || message.mMailboxKey != mMessage.mMailboxKey) {
                // Message deleted or moved.
                mCallback.onMessageNotExists();
                return;
            }
            mMessage = message;
            updateHeaderView(mMessage);
        }
    }

    /**
     * Called when a message is shown to the user.
     */
    protected void onMessageShown(long messageId, int mailboxType) {
        mCallback.onMessageViewShown(mailboxType);
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
                Log.d(Email.LOG_TAG, "Exception while loading message body", re);
                mErrorLoadingMessageBody = true;
                return null;
            }
        }

        @Override
        protected void onPostExecute(String[] results) {
            if (results == null || isCancelled()) {
                if (mErrorLoadingMessageBody) {
                    Utility.showToast(getActivity(), R.string.error_loading_message_body);
                }
                resetView();
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
            try {
                if (isCancelled() || attachments == null) {
                    return;
                }
                boolean htmlChanged = false;
                int numDisplayedAttachments = 0;
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
                        numDisplayedAttachments++;
                    }
                }
                setAttachmentCount(numDisplayedAttachments);
                mHtmlTextWebView = mHtmlTextRaw;
                mHtmlTextRaw = null;
                if (htmlChanged && mMessageContentView != null) {
                    mMessageContentView.loadDataWithBaseURL("email://", mHtmlTextWebView,
                                                            "text/html", "utf-8", null);
                }
            } finally {
                showContent(true, false);
            }
        }
    }

    private Bitmap getPreviewIcon(AttachmentInfo attachment) {
        try {
            return BitmapFactory.decodeStream(
                    mContext.getContentResolver().openInputStream(
                            AttachmentUtilities.getAttachmentThumbnailUri(
                                    mAccountId, attachment.mId,
                                    PREVIEW_ICON_WIDTH,
                                    PREVIEW_ICON_HEIGHT)));
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, "Attachment preview failed with exception " + e.getMessage());
            return null;
        }
    }

    private void updateAttachmentThumbnail(long attachmentId) {
        for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
            MessageViewAttachmentInfo attachment =
                (MessageViewAttachmentInfo) mAttachments.getChildAt(i).getTag();
            if (attachment.mId == attachmentId) {
                Bitmap previewIcon = getPreviewIcon(attachment);
                if (previewIcon != null) {
                    attachment.iconView.setImageBitmap(previewIcon);
                }
                return;
            }
        }
    }

    /**
     * Subclass of AttachmentInfo which includes our views and buttons related to attachment
     * handling, as well as our determination of suitability for viewing (based on availability of
     * a viewer app) and saving (based upon the presence of external storage)
     */
    static class MessageViewAttachmentInfo extends AttachmentInfo {
        Button viewButton;
        Button saveButton;
        Button loadButton;
        Button cancelButton;
        ImageView iconView;
        ProgressBar progressView;
        boolean enableView;
        boolean enableSave;
        boolean loaded;

        private MessageViewAttachmentInfo(Context context, Attachment attachment) {
            super(context, attachment);
            enableView = mAllowView;
            enableSave = mAllowSave;
        }
    }

    /**
     * Copy data from a cursor-refreshed attachment into the UI.  Called from UI thread.
     *
     * @param attachment A single attachment loaded from the provider
     */
    private void addAttachment(Attachment attachment) {
        MessageViewAttachmentInfo attachmentInfo =
            new MessageViewAttachmentInfo(mContext, attachment);

        LayoutInflater inflater = getActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.message_view_attachment, null);

        TextView attachmentName = (TextView)view.findViewById(R.id.attachment_name);
        TextView attachmentInfoView = (TextView)view.findViewById(R.id.attachment_info);
        ImageView attachmentIcon = (ImageView)view.findViewById(R.id.attachment_icon);
        Button attachmentView = (Button)view.findViewById(R.id.view);
        Button attachmentSave = (Button)view.findViewById(R.id.save);
        Button attachmentLoad = (Button)view.findViewById(R.id.load);
        Button attachmentCancel = (Button)view.findViewById(R.id.cancel);
        ProgressBar attachmentProgress = (ProgressBar)view.findViewById(R.id.progress);

        // Check whether the attachment already exists
        if (Utility.attachmentExists(mContext, attachment)) {
            attachmentInfo.loaded = true;
        }

        // Don't enable the "save" button if we've got no place to save the file
        if (!Utility.isExternalStorageMounted()) {
            attachmentInfo.enableSave = false;
        }

        if (!attachmentInfo.enableView) {
            attachmentView.setVisibility(View.GONE);
        }
        if (!attachmentInfo.enableSave) {
            attachmentSave.setVisibility(View.GONE);
        }

        attachmentInfo.viewButton = attachmentView;
        attachmentInfo.saveButton = attachmentSave;
        attachmentInfo.loadButton = attachmentLoad;
        attachmentInfo.cancelButton = attachmentCancel;
        attachmentInfo.iconView = attachmentIcon;
        attachmentInfo.progressView = attachmentProgress;

        if (!attachmentInfo.enableView && !attachmentInfo.enableSave) {
            // This attachment may never be viewed or saved, so block everything
            attachmentProgress.setVisibility(View.GONE);
            attachmentView.setVisibility(View.GONE);
            attachmentSave.setVisibility(View.GONE);
            attachmentLoad.setVisibility(View.GONE);
            attachmentCancel.setVisibility(View.GONE);
            // TODO: Maybe show a little icon to denote blocked download
        } else if (attachmentInfo.loaded) {
            // If the attachment is loaded, show 100% progress
            // Note that for POP3 messages, the user will only see "Open" and "Save",
            // because the entire message is loaded before being shown.
            // Hide "Load", show "View" and "Save"
            attachmentProgress.setVisibility(View.VISIBLE);
            attachmentProgress.setProgress(100);
            if (attachmentInfo.enableSave) {
                attachmentSave.setVisibility(View.VISIBLE);
            }
            if (attachmentInfo.enableView) {
                attachmentView.setVisibility(View.VISIBLE);
            }
            attachmentLoad.setVisibility(View.GONE);
            attachmentCancel.setVisibility(View.GONE);

            Bitmap previewIcon = getPreviewIcon(attachmentInfo);
            if (previewIcon != null) {
                attachmentIcon.setImageBitmap(previewIcon);
            }
        } else {
            // The attachment is not loaded, so present UI to start downloading it

            // Show "Load"; hide "View" and "Save"
            attachmentSave.setVisibility(View.GONE);
            attachmentView.setVisibility(View.GONE);

            // If the attachment is queued, show the indeterminate progress bar.  From this point,.
            // any progress changes will cause this to be replaced by the normal progress bar
            if (AttachmentDownloadService.isAttachmentQueued(attachment.mId)){
                attachmentProgress.setVisibility(View.VISIBLE);
                attachmentProgress.setIndeterminate(true);
                attachmentLoad.setVisibility(View.GONE);
                attachmentCancel.setVisibility(View.VISIBLE);
            } else {
                attachmentLoad.setVisibility(View.VISIBLE);
                attachmentCancel.setVisibility(View.GONE);
            }
        }

        view.setTag(attachmentInfo);
        attachmentView.setOnClickListener(this);
        attachmentView.setTag(attachmentInfo);
        attachmentSave.setOnClickListener(this);
        attachmentSave.setTag(attachmentInfo);
        attachmentLoad.setOnClickListener(this);
        attachmentLoad.setTag(attachmentInfo);
        attachmentCancel.setOnClickListener(this);
        attachmentCancel.setTag(attachmentInfo);

        attachmentName.setText(attachmentInfo.mName);
        attachmentInfoView.setText(Utility.formatSize(mContext, attachmentInfo.mSize));

        mAttachments.addView(view);
        mAttachments.setVisibility(View.VISIBLE);
    }

    /**
     * Reload the UI from a provider cursor.  {@link LoadMessageTask#onPostExecute} calls it.
     *
     * Update the header views, and start loading the body.
     *
     * @param message A copy of the message loaded from the database
     * @param okToFetch If true, and message is not fully loaded, it's OK to fetch from
     * the network.  Use false to prevent looping here.
     */
    protected void reloadUiFromMessage(Message message, boolean okToFetch) {
        mMessage = message;
        mAccountId = message.mAccountKey;

        mMessageObserver.register(ContentUris.withAppendedId(Message.CONTENT_URI, mMessage.mId));

        updateHeaderView(mMessage);

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

    protected void updateHeaderView(Message message) {
        mSubjectView.setText(message.mSubject);
        final Address from = Address.unpackFirst(message.mFrom);

        // Set sender address/display name
        // Note we set " " for empty field, so TextView's won't get squashed.
        // Otherwise their height will be 0, which breaks the layout.
        if (from != null) {
            final String fromFriendly = from.toFriendly();
            final String fromAddress = from.getAddress();
            mFromNameView.setText(fromFriendly);
            mFromAddressView.setText(fromFriendly.equals(fromAddress) ? " " : fromAddress);
        } else {
            mFromNameView.setText(" ");
            mFromAddressView.setText(" ");
        }
        mDateTimeView.setText(formatDate(message.mTimeStamp, false));

        // To/Cc/Bcc
        final Resources res = mContext.getResources();
        final SpannableStringBuilder ssb = new SpannableStringBuilder();
        final String friendlyTo = Address.toFriendly(Address.unpack(message.mTo));
        final String friendlyCc = Address.toFriendly(Address.unpack(message.mCc));
        final String friendlyBcc = Address.toFriendly(Address.unpack(message.mBcc));

        if (!TextUtils.isEmpty(friendlyTo)) {
            Utility.appendBold(ssb, res.getString(R.string.message_view_to_label));
            ssb.append(" ");
            ssb.append(friendlyTo);
        }
        if (!TextUtils.isEmpty(friendlyCc)) {
            ssb.append("  ");
            Utility.appendBold(ssb, res.getString(R.string.message_view_cc_label));
            ssb.append(" ");
            ssb.append(friendlyCc);
        }
        if (!TextUtils.isEmpty(friendlyBcc)) {
            ssb.append("  ");
            Utility.appendBold(ssb, res.getString(R.string.message_view_bcc_label));
            ssb.append(" ");
            ssb.append(friendlyBcc);
        }
        mAddressesView.setText(ssb);
    }

    private String formatDate(long millis, boolean withYear) {
        StringBuilder sb = new StringBuilder();
        Formatter formatter = new Formatter(sb);
        DateUtils.formatDateRange(mContext, formatter, millis, millis,
                DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_ABBREV_ALL
                | DateUtils.FORMAT_SHOW_TIME
                | (withYear ? DateUtils.FORMAT_SHOW_YEAR : DateUtils.FORMAT_NO_YEAR));
        return sb.toString();
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
        if (hasImages) {
            addTabFlags(TAB_FLAGS_HAS_PICTURES);
        }
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

    private View findAttachmentView(long attachmentId) {
        for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
            View view = mAttachments.getChildAt(i);
            MessageViewAttachmentInfo attachment = (MessageViewAttachmentInfo) view.getTag();
            if (attachment.mId == attachmentId) {
                return view;
            }
        }
        return null;
    }

    private MessageViewAttachmentInfo findAttachmentInfo(long attachmentId) {
        View view = findAttachmentView(attachmentId);
        if (view != null) {
            return (MessageViewAttachmentInfo)view.getTag();
        }
        return null;
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
        public void loadMessageForViewCallback(MessagingException result, long accountId,
                long messageId, int progress) {
            if (messageId != mWaitForLoadMessageId) {
                // We are not waiting for this message to load, so exit quickly
                return;
            }
            if (result == null) {
                switch (progress) {
                    case 0:
                        mCallback.onLoadMessageStarted();
                        // Loading from network -- show the progress icon.
                        showContent(false, true);
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
                String error = mContext.getString(R.string.status_network_error);
                mCallback.onLoadMessageError(error);
                resetView();
            }
        }

        @Override
        public void loadAttachmentCallback(MessagingException result, long accountId,
                long messageId, long attachmentId, int progress) {
            if (messageId == mMessageId) {
                if (result == null) {
                    showAttachmentProgress(attachmentId, progress);
                    switch (progress) {
                        case 100:
                            updateAttachmentThumbnail(attachmentId);
                            doFinishLoadAttachment(attachmentId);
                            break;
                        default:
                            // do nothing - we don't have a progress bar at this time
                            break;
                    }
                } else {
                    MessageViewAttachmentInfo attachment = findAttachmentInfo(attachmentId);
                    if (attachment == null) {
                        // Called before LoadAttachmentsTask finishes.
                        // (Possible if you quickly close & re-open a message)
                        return;
                    }
                    attachment.cancelButton.setVisibility(View.GONE);
                    attachment.loadButton.setVisibility(View.VISIBLE);
                    attachment.progressView.setVisibility(View.INVISIBLE);

                    final String error;
                    if (result.getCause() instanceof IOException) {
                        error = mContext.getString(R.string.status_network_error);
                    } else {
                        error = mContext.getString(
                                R.string.message_view_load_attachment_failed_toast,
                                attachment.mName);
                    }
                    mCallback.onLoadMessageError(error);
                }
            }
        }

        private void showAttachmentProgress(long attachmentId, int progress) {
            MessageViewAttachmentInfo attachment = findAttachmentInfo(attachmentId);
            if (attachment != null) {
                ProgressBar bar = attachment.progressView;
                if (progress == 0) {
                    // When the download starts, we can get rid of the indeterminate bar
                    bar.setVisibility(View.VISIBLE);
                    bar.setIndeterminate(false);
                    // And we're not implementing stop of in-progress downloads
                    attachment.cancelButton.setVisibility(View.GONE);
                }
                bar.setProgress(progress);
            }
        }
    }

    /**
     * Class to detect update on the current message (e.g. toggle star).  When it gets content
     * change notifications, it kicks {@link ReloadMessageTask}.
     *
     * TODO Use the new Throttle class.
     */
    private class MessageObserver extends ContentObserver implements Runnable {
        private final Throttle mThrottle;
        private final ContentResolver mContentResolver;

        private boolean mRegistered;

        public MessageObserver(Handler handler, Context context) {
            super(handler);
            mContentResolver = context.getContentResolver();
            mThrottle = new Throttle("MessageObserver", this, handler);
        }

        public void unregister() {
            if (!mRegistered) {
                return;
            }
            mThrottle.cancelScheduledCallback();
            mContentResolver.unregisterContentObserver(this);
            mRegistered = false;
        }

        public void register(Uri notifyUri) {
            unregister();
            mContentResolver.registerContentObserver(notifyUri, true, this);
            mRegistered = true;
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }

        @Override
        public void onChange(boolean selfChange) {
            mThrottle.onEvent();
        }

        /**
         * This method is delay-called by {@link Throttle} on the UI thread.  Need to make
         * sure if the fragment is still valid.  (i.e. don't reload if clearContent() has been
         * called.)
         */
        @Override
        public void run() {
            if (!isMessageSpecified()) {
                return;
            }
            Utility.cancelTaskInterrupt(mReloadMessageTask);
            mReloadMessageTask = new ReloadMessageTask();
            mReloadMessageTask.execute();
        }
    }

    public boolean isMessageLoadedForTest() {
        return mIsMessageLoadedForTest;
    }

    public void clearIsMessageLoadedForTest() {
        mIsMessageLoadedForTest = true;
    }
}
