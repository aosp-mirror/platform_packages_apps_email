/*
 * Copyright (C) 2008 The Android Open Source Project
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
import com.android.email.Email;
import com.android.email.MessagingController;
import com.android.email.MessagingListener;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.mail.Address;
import com.android.email.mail.MessagingException;
import com.android.email.mail.internet.EmailHtmlUtil;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.BodyColumns;
import com.android.email.provider.EmailContent.Message;

import org.apache.commons.io.IOUtils;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Contacts;
import android.provider.Contacts.Intents;
import android.provider.Contacts.People;
import android.text.util.Regex;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageView extends Activity
        implements OnClickListener {
    private static final String EXTRA_MESSAGE_ID = "com.android.email.MessageView_message_id";
    private static final String EXTRA_ACCOUNT_ID = "com.android.email.MessageView_account_id";
    private static final String EXTRA_MAILBOX_ID = "com.android.email.MessageView_mailbox_id";
    
    private static final String[] METHODS_WITH_PRESENCE_PROJECTION = new String[] {
        People.ContactMethods._ID,  // 0
//        People.PRESENCE_STATUS,     // 1
    };
    private static final int METHODS_STATUS_COLUMN = 1;

    // Regex that matches start of img tag. '<(?i)img\s+'.
    private static final Pattern IMG_TAG_START_REGEX = Pattern.compile("<(?i)img\\s+");
    // Regex that matches Web URL protocol part as case insensitive.
    private static final Pattern WEB_URL_PROTOCOL = Pattern.compile("(?i)http|https://");

    // Support for LoadBodyTask
    private static final String[] BODY_CONTENT_PROJECTION = new String[] { 
        Body.RECORD_ID, BodyColumns.MESSAGE_KEY,
        BodyColumns.HTML_CONTENT, BodyColumns.TEXT_CONTENT
    };
    private static final int BODY_CONTENT_COLUMN_RECORD_ID = 0;
    private static final int BODY_CONTENT_COLUMN_MESSAGE_KEY = 1;
    private static final int BODY_CONTENT_COLUMN_HTML_CONTENT = 2;
    private static final int BODY_CONTENT_COLUMN_TEXT_CONTENT = 3;

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
    private ImageView mFavoriteIcon;
    private View mShowPicturesSection;
    private ImageView mSenderPresenceView;
    private ProgressDialog mProgressDialog;

    private long mAccountId;
    private Account mAccount;
    private long mMessageId;
    private long mMailboxId;
    private Message mMessage;

    private LoadMessageTask mLoadMessageTask;
    private LoadBodyTask mLoadBodyTask;
    private LoadAttachmentsTask mLoadAttachmentsTask;

    private long mLoadAttachmentId;         // the attachment being saved/viewed
    private boolean mLoadAttachmentSave;    // if true, saving - if false, viewing
    private String mLoadAttachmentName;     // the display name

    private String mFolder;
    private String mMessageUid;

    private java.text.DateFormat mDateFormat;
    private java.text.DateFormat mTimeFormat;

    private Drawable mFavoriteIconOn;
    private Drawable mFavoriteIconOff;

    private Listener mListener = new Listener();
    private MessageViewHandler mHandler = new MessageViewHandler();
    private ControllerResults mControllerCallback = new ControllerResults();

    private View mPrevious;
    private View mNext;
    private LoadPrevNextTask mLoadPrevNextTask;
    private Cursor mPrevNextCursor;

    class MessageViewHandler extends Handler {
        private static final int MSG_ATTACHMENT_PROGRESS = 2;
        private static final int MSG_SET_ATTACHMENTS_ENABLED = 4;
        private static final int MSG_SET_HEADERS = 5;
        private static final int MSG_NETWORK_ERROR = 6;
        private static final int MSG_ATTACHMENT_SAVED = 7;
        private static final int MSG_ATTACHMENT_NOT_SAVED = 8;
        private static final int MSG_SHOW_SHOW_PICTURES = 9;
        private static final int MSG_FETCHING_ATTACHMENT = 10;
        private static final int MSG_SET_SENDER_PRESENCE = 11;
        private static final int MSG_VIEW_ATTACHMENT_ERROR = 12;
        private static final int MSG_UPDATE_ATTACHMENT_ICON = 18;
        private static final int MSG_FINISH_LOAD_ATTACHMENT = 19;

        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_ATTACHMENT_PROGRESS:
                    boolean progress = (msg.arg1 != 0);
                    if (progress) {
                        mProgressDialog.setMessage(
                                getString(R.string.message_view_fetching_attachment_progress,
                                        mLoadAttachmentName));
                        mProgressDialog.show();
                    } else {
                        mProgressDialog.dismiss();
                    }
                    setProgressBarIndeterminateVisibility(progress);
                    break;
                case MSG_SET_ATTACHMENTS_ENABLED:
                    for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
                        AttachmentInfo attachment =
                            (AttachmentInfo) mAttachments.getChildAt(i).getTag();
                        attachment.viewButton.setEnabled(msg.arg1 == 1);
                        attachment.downloadButton.setEnabled(msg.arg1 == 1);
                    }
                    break;
                case MSG_SET_HEADERS:
                    String[] values = (String[]) msg.obj;
                    mSubjectView.setText(values[0]);
                    mFromView.setText(values[1]);
                    mTimeView.setText(values[2]);
                    mDateView.setText(values[3]);
                    mToView.setText(values[4]);
                    mCcView.setText(values[5]);
                    mCcContainerView.setVisibility((values[5] != null) ? View.VISIBLE : View.GONE);
                    mAttachmentIcon.setVisibility(msg.arg1 == 1 ? View.VISIBLE : View.GONE);
                    break;
                case MSG_NETWORK_ERROR:
                    Toast.makeText(MessageView.this,
                            R.string.status_network_error, Toast.LENGTH_LONG).show();
                    break;
                case MSG_ATTACHMENT_SAVED:
                    Toast.makeText(MessageView.this, String.format(
                            getString(R.string.message_view_status_attachment_saved), msg.obj),
                            Toast.LENGTH_LONG).show();
                    break;
                case MSG_ATTACHMENT_NOT_SAVED:
                    Toast.makeText(MessageView.this,
                            getString(R.string.message_view_status_attachment_not_saved),
                            Toast.LENGTH_LONG).show();
                    break;
                case MSG_SHOW_SHOW_PICTURES:
                    mShowPicturesSection.setVisibility(msg.arg1 == 1 ? View.VISIBLE : View.GONE);
                    break;
                case MSG_FETCHING_ATTACHMENT:
                    Toast.makeText(MessageView.this,
                            getString(R.string.message_view_fetching_attachment_toast),
                            Toast.LENGTH_SHORT).show();
                    break;
                case MSG_SET_SENDER_PRESENCE:
                    updateSenderPresence(msg.arg1);
                    break;
                case MSG_VIEW_ATTACHMENT_ERROR:
                    Toast.makeText(MessageView.this,
                            getString(R.string.message_view_display_attachment_toast),
                            Toast.LENGTH_SHORT).show();
                    break;
                case MSG_UPDATE_ATTACHMENT_ICON:
                    ((AttachmentInfo) mAttachments.getChildAt(msg.arg1).getTag())
                        .iconView.setImageBitmap((Bitmap) msg.obj);
                    break;
                case MSG_FINISH_LOAD_ATTACHMENT:
                    long attachmentId = (Long)msg.obj;
                    doFinishLoadAttachment(attachmentId);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }

        public void attachmentProgress(boolean progress) {
            android.os.Message msg = android.os.Message.obtain(this, MSG_ATTACHMENT_PROGRESS);
            msg.arg1 = progress ? 1 : 0;
            sendMessage(msg);
        }

        public void setAttachmentsEnabled(boolean enabled) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_SET_ATTACHMENTS_ENABLED;
            msg.arg1 = enabled ? 1 : 0;
            sendMessage(msg);
        }

        public void setHeaders(
                String subject,
                String from,
                String time,
                String date,
                String to,
                String cc,
                boolean hasAttachments) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_SET_HEADERS;
            msg.arg1 = hasAttachments ? 1 : 0;
            msg.obj = new String[] { subject, from, time, date, to, cc };
            sendMessage(msg);
        }

        public void networkError() {
            sendEmptyMessage(MSG_NETWORK_ERROR);
        }

        public void attachmentSaved(String filename) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_ATTACHMENT_SAVED;
            msg.obj = filename;
            sendMessage(msg);
        }

        public void attachmentNotSaved() {
            sendEmptyMessage(MSG_ATTACHMENT_NOT_SAVED);
        }

        public void fetchingAttachment() {
            sendEmptyMessage(MSG_FETCHING_ATTACHMENT);
        }

        public void showShowPictures(boolean show) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_SHOW_SHOW_PICTURES;
            msg.arg1 = show ? 1 : 0;
            sendMessage(msg);
        }
        
        public void setSenderPresence(int presenceIconId) {
            android.os.Message
                    .obtain(this, MSG_SET_SENDER_PRESENCE,  presenceIconId, 0)
                    .sendToTarget();
        }
        
        public void attachmentViewError() {
            sendEmptyMessage(MSG_VIEW_ATTACHMENT_ERROR);
        }

        public void updateAttachmentIcon(int pos, Bitmap icon) {
            android.os.Message msg = android.os.Message.obtain(this, MSG_UPDATE_ATTACHMENT_ICON);
            msg.arg1 = pos;
            msg.obj = icon;
            sendMessage(msg);
        }

        public void finishLoadAttachment(long attachmentId) {
            android.os.Message msg = android.os.Message.obtain(this, MSG_FINISH_LOAD_ATTACHMENT);
            msg.obj = Long.valueOf(attachmentId);
            sendMessage(msg);
        }
    }

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

    /**
     * View a specific message found in the Email provider.
     * @param messageId the message to view.
     * @param mailboxId identifies the sequence of messages used for prev/next navigation.
     */
    public static void actionView(Context context, long messageId, long mailboxId) {
        Intent i = new Intent(context, MessageView.class);
        i.putExtra(EXTRA_MESSAGE_ID, messageId);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        context.startActivity(i);
    }

    /**
     * Re-init everything needed for changing message.
     */
    private void messageChanged() {
        cancelAllTasks();
        setTitle("");
        mAttachments.setVisibility(View.GONE);
        mAttachmentIcon.setVisibility(View.GONE);

        // are the text clear below needed?
        // likely they only add flicker as the new message is displayed quickly
//         mSubjectView.setText(null);
//         mFromView.setText(null);
//         mTimeView.setText(null);
//         mDateView.setText(null);
//         mToView.setText(null);
//         mCcView.setText(null);

        // Start an AsyncTask to make a new cursor and load the message
        mLoadMessageTask = new LoadMessageTask(mMessageId);
        mLoadMessageTask.execute();
        updatePrevNextArrows(mPrevNextCursor);
    }

    private void updatePrevNextArrows(Cursor cursor) {
        if (cursor != null) {
            boolean hasPrev, hasNext;
            if (cursor.isAfterLast() || cursor.isBeforeFirst()) {
                // The cursor not being on a message means that the current message was not found.
                // While this should not happen, simply disable prev/next arrows in that case.
                hasPrev = hasNext = false;
            } else {
                hasPrev = !cursor.isFirst();
                hasNext = !cursor.isLast();
            }
            mPrevious.setVisibility(hasPrev ? View.VISIBLE : View.GONE);
            mNext.setVisibility(hasNext ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.message_view);

        mSubjectView = (TextView) findViewById(R.id.subject);
        mFromView = (TextView) findViewById(R.id.from);
        mToView = (TextView) findViewById(R.id.to);
        mCcView = (TextView) findViewById(R.id.cc);
        mCcContainerView = findViewById(R.id.cc_container);
        mDateView = (TextView) findViewById(R.id.date);
        mTimeView = (TextView) findViewById(R.id.time);
        mMessageContentView = (WebView) findViewById(R.id.message_content);
        mAttachments = (LinearLayout) findViewById(R.id.attachments);
        mAttachmentIcon = (ImageView) findViewById(R.id.attachment);
        mFavoriteIcon = (ImageView) findViewById(R.id.favorite);
        mShowPicturesSection = findViewById(R.id.show_pictures_section);
        mSenderPresenceView = (ImageView) findViewById(R.id.presence);
        mNext = findViewById(R.id.next);
        mPrevious = findViewById(R.id.previous);

        mNext.setOnClickListener(this);
        mPrevious.setOnClickListener(this);
        mFromView.setOnClickListener(this);
        mSenderPresenceView.setOnClickListener(this);
        mFavoriteIcon.setOnClickListener(this);
        findViewById(R.id.reply).setOnClickListener(this);
        findViewById(R.id.reply_all).setOnClickListener(this);
        findViewById(R.id.delete).setOnClickListener(this);
        findViewById(R.id.show_pictures).setOnClickListener(this);

        mMessageContentView.setVerticalScrollBarEnabled(false);
        mMessageContentView.getSettings().setBlockNetworkImage(true);
        mMessageContentView.getSettings().setSupportZoom(false);

        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setIndeterminate(true);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);

        mDateFormat = android.text.format.DateFormat.getDateFormat(this);   // short format
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(this);   // 12/24 date format

        mFavoriteIconOn = getResources().getDrawable(android.R.drawable.star_on);
        mFavoriteIconOff = getResources().getDrawable(android.R.drawable.star_off);

        Intent intent = getIntent();
        mMessageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1);
        mMailboxId = intent.getLongExtra(EXTRA_MAILBOX_ID, -1);
        messageChanged();
    }

/*
        MessagingController.getInstance(getApplication()).addListener(mListener);
        new Thread() {
            @Override
            public void run() {
                // TODO this is a spot that should be eventually handled by a MessagingController
                // thread pool. We want it in a thread but it can't be blocked by the normal
                // synchronization stuff in MC.
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                MessagingController.getInstance(getApplication()).loadMessageForView(
                        mAccount,
                        mFolder,
                        mMessageUid,
                        mListener);
            }
        }.start();
*/

    @Override
    public void onResume() {
        super.onResume();
        Controller.getInstance(getApplication()).addResultCallback(mControllerCallback);
        MessagingController.getInstance(getApplication()).addListener(mListener);
        if (mMessage != null) {
            startPresenceCheck();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        MessagingController.getInstance(getApplication()).removeListener(mListener);
        Controller.getInstance(getApplication()).removeResultCallback(mControllerCallback);
    }

    private static void cancelTask(AsyncTask task) {
        if (task != null && task.getStatus() != AsyncTask.Status.FINISHED) {
            task.cancel(true);
        }
    }

    private void cancelAllTasks() {
        cancelTask(mLoadMessageTask);
        mLoadMessageTask = null;
        cancelTask(mLoadBodyTask);
        mLoadBodyTask = null;
        cancelTask(mLoadAttachmentsTask);
        mLoadAttachmentsTask = null;
        cancelTask(mLoadPrevNextTask);
        mLoadPrevNextTask = null;
    }

    /**
     * We override onDestroy to make sure that the WebView gets explicitly destroyed.
     * Otherwise it can leak native references.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelAllTasks();
        // This is synchronized because the listener accesses mMessageContentView from its thread
        synchronized (this) {
            mMessageContentView.destroy();
            mMessageContentView = null;
        }
        if (mPrevNextCursor != null) {
            mPrevNextCursor.close();
            mPrevNextCursor = null;
        }
    }

    private void onDelete() {
        if (mMessage != null) {
            Controller.getInstance(getApplication()).deleteMessage(
                    mMessageId, mMessage.mAccountKey);
            Toast.makeText(this, R.string.message_deleted_toast, Toast.LENGTH_SHORT).show();

            if (onPrevious()) {
                return;
            }
            if (onNext()) {
                return;
            }
            finish();
        }
    }
    
    private void onClickSender() {
        if (mMessage != null) {
            Address senderEmail = Address.unpackFirst(mMessage.mFrom);
            if (senderEmail != null) {
                Uri contactUri = Uri.fromParts("mailto", senderEmail.getAddress(), null);

                Intent contactIntent = new Intent(Contacts.Intents.SHOW_OR_CREATE_CONTACT);
                contactIntent.setData(contactUri);

                // Pass along full E-mail string for possible create dialog  
                contactIntent.putExtra(Contacts.Intents.EXTRA_CREATE_DESCRIPTION,
                                       senderEmail.toString());

                // Only provide personal name hint if we have one
                String senderPersonal = senderEmail.getPersonal();
                if (senderPersonal != null) {
                    contactIntent.putExtra(Intents.Insert.NAME, senderPersonal);
                }

                startActivity(contactIntent);
            }
        }
    }

    /**
     * Toggle favorite status and write back to provider
     */
    private void onClickFavorite() {
        if (mMessage != null) {
            // Update UI
            boolean newFavorite = ! mMessage.mFlagFavorite;
            mFavoriteIcon.setImageDrawable(newFavorite ? mFavoriteIconOn : mFavoriteIconOff);

            // Update provider
            mMessage.mFlagFavorite = newFavorite;
            Controller.getInstance(getApplication()).setMessageFavorite(mMessageId, newFavorite);
        }
    }

    private void onReply() {
        if (mMessage != null) {
            MessageCompose.actionReply(this, mMessage.mId, false);
            finish();
        }
    }

    private void onReplyAll() {
        if (mMessage != null) {
            MessageCompose.actionReply(this, mMessage.mId, true);
            finish();
        }
    }

    private void onForward() {
        if (mMessage != null) {
            MessageCompose.actionForward(this, mMessage.mId);
            finish();
        }
    }

    private boolean onNext() {
        if (mPrevNextCursor != null && mPrevNextCursor.moveToNext()) {
            mMessageId = mPrevNextCursor.getLong(0);
            messageChanged();
            return true;
        } else {
            return false;
        }
    }

    private boolean onPrevious() {
        if (mPrevNextCursor != null && mPrevNextCursor.moveToPrevious()) {
            mMessageId = mPrevNextCursor.getLong(0);
            messageChanged();
            return true;
        } else {
            return false;
        }
    }

    private void onMarkAsRead(boolean isRead) {
        if (mMessage != null && mMessage.mFlagRead != isRead) {
            mMessage.mFlagRead = isRead;
            Controller.getInstance(getApplication()).setMessageRead(mMessageId, isRead);
        }
    }

    /**
     * Creates a unique file in the given directory by appending a hyphen
     * and a number to the given filename.
     * @param directory
     * @param filename
     * @return a new File object, or null if one could not be created
     */
    /* package */ static File createUniqueFile(File directory, String filename) {
        File file = new File(directory, filename);
        if (!file.exists()) {
            return file;
        }
        // Get the extension of the file, if any.
        int index = filename.lastIndexOf('.');
        String format;
        if (index != -1) {
            String name = filename.substring(0, index);
            String extension = filename.substring(index);
            format = name + "-%d" + extension;
        }
        else {
            format = filename + "-%d";
        }
        for (int i = 2; i < Integer.MAX_VALUE; i++) {
            file = new File(directory, String.format(format, i));
            if (!file.exists()) {
                return file;
            }
        }
        return null;
    }

    private void onDownloadAttachment(AttachmentInfo attachment) {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            /*
             * Abort early if there's no place to save the attachment. We don't want to spend
             * the time downloading it and then abort.
             */
            Toast.makeText(this,
                    getString(R.string.message_view_status_attachment_not_saved),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        mLoadAttachmentId = attachment.attachmentId;
        mLoadAttachmentSave = true;
        mLoadAttachmentName = attachment.name;

        Controller.getInstance(getApplication()).loadAttachment(attachment.attachmentId,
                mMessageId, mMessage.mMailboxKey, mAccountId, mControllerCallback);
    }

    private void onViewAttachment(AttachmentInfo attachment) {
        mLoadAttachmentId = attachment.attachmentId;
        mLoadAttachmentSave = false;
        mLoadAttachmentName = attachment.name;

        Controller.getInstance(getApplication()).loadAttachment(attachment.attachmentId,
                mMessageId, mMessage.mMailboxKey, mAccountId, mControllerCallback);
    }

    private void onShowPictures() {
        if (mMessage != null) {
            mMessageContentView.getSettings().setBlockNetworkImage(false);
            mShowPicturesSection.setVisibility(View.GONE);
        }
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.from:
            case R.id.presence:
                onClickSender();
                break;
            case R.id.favorite:
                onClickFavorite();
                break;
            case R.id.reply:
                onReply();
                break;
            case R.id.reply_all:
                onReplyAll();
                break;
            case R.id.delete:
                onDelete();
                break;
            case R.id.next:
                onNext();
                break;
            case R.id.previous:
                onPrevious();
                break;
            case R.id.download:
                onDownloadAttachment((AttachmentInfo) view.getTag());
                break;
            case R.id.view:
                onViewAttachment((AttachmentInfo) view.getTag());
                break;
            case R.id.show_pictures:
                onShowPictures();
                break;
        }
    }
    
   @Override
    public boolean onOptionsItemSelected(MenuItem item) {
       boolean handled = handleMenuItem(item.getItemId());
       if (!handled) {
           handled = super.onOptionsItemSelected(item);
       }
       return handled;
   }

   /**
    * This is the core functionality of onOptionsItemSelected() but broken out and exposed
    * for testing purposes (because it's annoying to mock a MenuItem).
    * 
    * @param menuItemId id that was clicked
    * @return true if handled here
    */
   /* package */ boolean handleMenuItem(int menuItemId) {
       switch (menuItemId) {
           case R.id.delete:
               onDelete();
               break;
           case R.id.reply:
               onReply();
               break;
           case R.id.reply_all:
               onReplyAll();
               break;
           case R.id.forward:
               onForward();
               break;
           case R.id.mark_as_unread:
               onMarkAsRead(false);
               finish();
               break;
           default:
               return false;
       }
       return true;
   }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.message_view_option, menu);
        return true;
    }

    private Bitmap getPreviewIcon(AttachmentInfo attachment) {
        try {
            return BitmapFactory.decodeStream(
                    getContentResolver().openInputStream(
                            AttachmentProvider.getAttachmentThumbnailUri(
                                    mAccountId, attachment.attachmentId,
                                    62,
                                    62)));
        }
        catch (Exception e) {
            /*
             * We don't care what happened, we just return null for the preview icon.
             */
            return null;
        }
    }

    /*
     * Formats the given size as a String in bytes, kB, MB or GB with a single digit
     * of precision. Ex: 12,315,000 = 12.3 MB
     */
    public static String formatSize(float size) {
        long kb = 1024;
        long mb = (kb * 1024);
        long gb  = (mb * 1024);
        if (size < kb) {
            return String.format("%d bytes", (int) size);
        }
        else if (size < mb) {
            return String.format("%.1f kB", size / kb);
        }
        else if (size < gb) {
            return String.format("%.1f MB", size / mb);
        }
        else {
            return String.format("%.1f GB", size / gb);
        }
    }

    private void updateAttachmentThumbnail(long attachmentId) {
        for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
            AttachmentInfo attachment = (AttachmentInfo) mAttachments.getChildAt(i).getTag();
            if (attachment.attachmentId == attachmentId) {
                Bitmap previewIcon = getPreviewIcon(attachment);
                if (previewIcon != null) {
                    mHandler.updateAttachmentIcon(i, previewIcon);
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
        attachmentInfo.contentType = attachment.mMimeType;
        attachmentInfo.name = attachment.mFileName;
        attachmentInfo.attachmentId = attachment.mId;

        // TODO: remove this when EAS writes mime types
        if (attachmentInfo.contentType == null || attachmentInfo.contentType.length() == 0) {
            attachmentInfo.contentType = "application/octet-stream";
        }

        LayoutInflater inflater = getLayoutInflater();
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
        attachmentInfoView.setText(formatSize(attachmentInfo.size));

        Bitmap previewIcon = getPreviewIcon(attachmentInfo);
        if (previewIcon != null) {
            attachmentIcon.setImageBitmap(previewIcon);
        }

        mAttachments.addView(view);
        mAttachments.setVisibility(View.VISIBLE);
    }
    
    /**
     * Launch a thread (because of cross-process DB lookup) to check presence of the sender of the
     * message.  When that thread completes, update the UI.
     * 
     * This must only be called when mMessage is null (it will hide presence indications) or when
     * mMessage has already seen its headers loaded.
     * 
     * Note:  This is just a polling operation.  A more advanced solution would be to keep the
     * cursor open and respond to presence status updates (in the form of content change
     * notifications).  However, because presence changes fairly slowly compared to the duration
     * of viewing a single message, a simple poll at message load (and onResume) should be
     * sufficient.
     */
    private void startPresenceCheck() {
        String email = null;        
        if (mMessage != null) {
            Address sender = Address.unpackFirst(mMessage.mFrom);
            email = sender != null ? sender.getAddress() : null;
        }

        if (email == null) {
            mHandler.setSenderPresence(0);
            return;
        }
        final String senderEmail = email;

/*
        new Thread() {
            @Override
            public void run() {
                Cursor methodsCursor = getContentResolver().query(
                        Uri.withAppendedPath(Contacts.ContactMethods.CONTENT_URI, "with_presence"),
                        METHODS_WITH_PRESENCE_PROJECTION,
                        Contacts.ContactMethods.DATA + "=?",
                        new String[]{ senderEmail },
                        null);

                int presenceIcon = 0;

                if (methodsCursor != null) {
                    if (methodsCursor.moveToFirst() && 
                            !methodsCursor.isNull(METHODS_STATUS_COLUMN)) {
//                        presenceIcon = Presence.getPresenceIconResourceId(
//                                methodsCursor.getInt(METHODS_STATUS_COLUMN));
                    }
                    methodsCursor.close();
                }

                mHandler.setSenderPresence(presenceIcon);
            }
        }.start();
*/
    }
    
    /**
     * Update the actual UI.  Must be called from main thread (or handler)
     * @param presenceIconId the presence of the sender, 0 for "unknown"
     */
    private void updateSenderPresence(int presenceIconId) {
        if (presenceIconId == 0) {
            // This is a placeholder used for "unknown" presence, including signed off,
            // no presence relationship.
            presenceIconId = R.drawable.presence_inactive;
        }
        mSenderPresenceView.setImageResource(presenceIconId);
    }
    

    /**
     * This task finds out the messageId for the previous and next message
     * in the order given by mailboxId as used in MessageList.
     *
     * It generates the same cursor as the one used in MessageList (but with an id-only projection),
     * scans through it until finds the current messageId, and takes the previous and next ids.
     */
    private class LoadPrevNextTask extends AsyncTask<Void, Void, Cursor> {
        private long mLocalMailboxId;

        public LoadPrevNextTask(long mailboxId) {
            mLocalMailboxId = mailboxId;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            String selection =
                Utility.buildMailboxIdSelection(getContentResolver(), mLocalMailboxId);
            Cursor c = MessageView.this.managedQuery(EmailContent.Message.CONTENT_URI,
                    EmailContent.ID_PROJECTION,
                    selection, null,
                    EmailContent.MessageColumns.TIMESTAMP + " DESC");
            return c;
        }

        @Override
        protected void onPostExecute(Cursor cursor) {
            // position the cursor on the current message
            while (cursor.moveToNext() && cursor.getLong(0) != mMessageId);
            mPrevNextCursor = cursor;
            updatePrevNextArrows(mPrevNextCursor);
        }
    }

    /**
     * Async task for loading a single message outside of the UI thread
     * 
     * TODO: start and stop progress indicator
     * TODO: set up to listen for additional updates (cursor data changes)
     */
    private class LoadMessageTask extends AsyncTask<Void, Void, Cursor> {
        
        private long mId;
        
        /**
         * Special constructor to cache some local info
         */
        public LoadMessageTask(long messageId) {
            mId = messageId;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            return MessageView.this.managedQuery(
                    Message.CONTENT_URI,
                    Message.CONTENT_PROJECTION,
                    Message.RECORD_ID + "=?",
                    new String[] {
                            String.valueOf(mId)
                            }, 
                    null);
        }
        
        @Override
        protected void onPostExecute(Cursor cursor) {
            if (cursor.moveToFirst()) {
                reloadUiFromCursor(cursor);
            } else {
                // toast?  why would this fail?
            }
        }
    }
    
    /**
     * Async task for loading a single message body outside of the UI thread
     * 
     * TODO: smarter loading of html vs. text
     */
    private class LoadBodyTask extends AsyncTask<Void, Void, Cursor> {
        
        private long mId;
        
        /**
         * Special constructor to cache some local info
         */
        public LoadBodyTask(long messageId) {
            mId = messageId;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            return MessageView.this.managedQuery(
                    Body.CONTENT_URI,
                    BODY_CONTENT_PROJECTION,
                    Body.MESSAGE_KEY + "=?",
                    new String[] {
                            String.valueOf(mId)
                            }, 
                    null);
        }
        
        @Override
        protected void onPostExecute(Cursor cursor) {
            if (cursor.moveToFirst()) {
                reloadBodyFromCursor(cursor);
            } else {
                // toast?  why would this fail?
                reloadBodyFromCursor(null);     // hack to force text for display
            }

            // At this point it's fair to mark the message as "read"
            onMarkAsRead(true);
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
            return Attachment.restoreAttachmentsWithMessageId(MessageView.this, messageIds[0]);
        }

        @Override
        protected void onPostExecute(Attachment[] attachments) {
            for (Attachment attachment : attachments) {
                addAttachment(attachment);
            }
        }
    }

    /**
     * Reload the UI from a provider cursor.  This must only be called from the UI thread.
     * 
     * @param cursor A cursor loaded from EmailStore.Message.
     * 
     * TODO: attachments
     * TODO: trigger presence check
     */
    private void reloadUiFromCursor(Cursor cursor) {
        Message message = new Message().restore(cursor);
        mMessage = message;
        mAccountId = message.mAccountKey;
        if (mMailboxId == -1) {
            mMailboxId = message.mMailboxKey;
        }
        // only start LoadPrevNextTask once
        if (mPrevNextCursor == null) {
            mLoadPrevNextTask = new LoadPrevNextTask(mMailboxId);
            mLoadPrevNextTask.execute();
        }

        mSubjectView.setText(message.mSubject);
        mFromView.setText(Address.toFriendly(Address.unpack(message.mFrom)));
        Date date = new Date(message.mTimeStamp);
        mTimeView.setText(mTimeFormat.format(date));
        mDateView.setText(Utility.isDateToday(date) ? null : mDateFormat.format(date));
        mToView.setText(Address.toFriendly(Address.unpack(message.mTo)));
        mCcView.setText(Address.toFriendly(Address.unpack(message.mCc)));
        mCcContainerView.setVisibility((message.mCc != null) ? View.VISIBLE : View.GONE);
        mAttachmentIcon.setVisibility(message.mAttachments != null ? View.VISIBLE : View.GONE);
        mFavoriteIcon.setImageDrawable(message.mFlagFavorite ? mFavoriteIconOn : mFavoriteIconOff);

        // TODO: Handle partially-loaded email, as follows:
        // 1. Check value of message.mFlagLoaded
        // 2. If != LOADED, ask controller to load it
        // 3. Controller callback (after loaded) should trigger LoadBodyTask & LoadAttachmentsTask
        // 4. Else start the loader tasks right away (message already loaded)

        // Ask for body
        mLoadBodyTask = new LoadBodyTask(message.mId);
        mLoadBodyTask.execute();

        // Ask for attachments
        mLoadAttachmentsTask = new LoadAttachmentsTask();
        mLoadAttachmentsTask.execute(message.mId);
    }

    /**
     * Reload the body from the provider cursor.  This must only be called from the UI thread.
     * 
     * @param cursor
     * 
     * TODO deal with html vs text and many other issues
     */
    private void reloadBodyFromCursor(Cursor cursor) {
        // TODO Remove this hack that forces some text to test the code
        String html = null;
        String text = null;
        if (cursor != null) {
            // First try HTML; we'll show that if it exists...
            html = cursor.getString(BODY_CONTENT_COLUMN_HTML_CONTENT);
            if (html == null) {
                text = cursor.getString(BODY_CONTENT_COLUMN_TEXT_CONTENT);
            }
        }

        if (html == null) {
            // This code is stolen from Listener.loadMessageForViewBodyAvailable
            // And also escape special character, such as "<>&",
            // to HTML escape sequence.
            if (text == null) {
                text = "";
            }
            text = EmailHtmlUtil.escapeCharacterToDisplay(text);

            /*
             * Linkify the plain text and convert it to HTML by replacing
             * \r?\n with <br> and adding a html/body wrapper.
             */
            StringBuffer sb = new StringBuffer("<html><body>");
            if (text != null) {
                Matcher m = Regex.WEB_URL_PATTERN.matcher(text);
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
                            // Regex.WEB_URL_PATTERN matches URL without protocol part,
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
            // TODO Clean this up later
            // For example, enable the view images button
            text = html;
        }
       
        if (mMessageContentView != null) {
            mMessageContentView.loadDataWithBaseURL("email://", text, "text/html", "utf-8", null);
        }
    }

    /**
     * Controller results listener.  This completely replaces MessagingListener
     */
    class ControllerResults implements Controller.Result {

        public void loadAttachmentCallback(MessagingException result, long messageId,
                long attachmentId, int progress) {
            if (messageId == MessageView.this.mMessageId) {
                if (result == null) {
                    switch (progress) {
                        case 0:
                            mHandler.setAttachmentsEnabled(false);
                            mHandler.attachmentProgress(true);
                            mHandler.fetchingAttachment();
                            break;
                        case 100:
                            mHandler.setAttachmentsEnabled(true);
                            mHandler.attachmentProgress(false);
                            updateAttachmentThumbnail(attachmentId);
                            mHandler.finishLoadAttachment(attachmentId);
                            break;
                        default:
                            // do nothing - we don't have a progress bar at this time
                            break;
                    }
                } else {
                    mHandler.setAttachmentsEnabled(true);
                    mHandler.attachmentProgress(false);
                    mHandler.networkError();
                }
            }
        }

        public void updateMailboxCallback(MessagingException result, long accountId,
                long mailboxId, int progress, int numNewMessages) {
        }

        public void updateMailboxListCallback(MessagingException result, long accountId,
                int progress) {
        }

        public void serviceCheckMailCallback(MessagingException result, long accountId,
                long mailboxId, int progress, long tag) {
        }

        public void sendMailCallback(MessagingException result, long accountId, long messageId,
                int progress) {
        }
    }

    /**
     * MessagingListener is the traditional form of callback used by the Email application; remote
     * services (like Exchange) will use mServiceListener
     *
     * TODO: All of this needs to move to Controller
     */
    class Listener extends MessagingListener {
        @Override
        public void loadMessageForViewHeadersAvailable(Account account, String folder,
                String uid, final com.android.email.mail.Message message) {
//             MessageView.this.mOldMessage = message;
//             try {
//                 String subjectText = message.getSubject();
//                 String fromText = Address.toFriendly(message.getFrom());
//                 Date sentDate = message.getSentDate();
//                 String timeText = mTimeFormat.format(sentDate);
//                 String dateText = Utility.isDateToday(sentDate) ? null : 
//                         mDateFormat.format(sentDate);
//                 String toText = Address.toFriendly(message.getRecipients(RecipientType.TO));
//                 String ccText = Address.toFriendly(message.getRecipients(RecipientType.CC));
//                 boolean hasAttachments = ((LocalMessage) message).getAttachmentCount() > 0;
//                 mHandler.setHeaders(subjectText,
//                         fromText,
//                         timeText,
//                         dateText,
//                         toText,
//                         ccText,
//                         hasAttachments);
//                 startPresenceCheck();
//             }
//             catch (MessagingException me) {
//                 if (Email.LOGD) {
//                     Log.v(Email.LOG_TAG, "loadMessageForViewHeadersAvailable", me);
//                 }
//             }
        }

        @Override
        public void loadMessageForViewBodyAvailable(Account account, String folder,
                String uid, com.android.email.mail.Message message) {
//             MessageView.this.mOldMessage = message;
//             try {
//                 Part part = MimeUtility.findFirstPartByMimeType(mOldMessage, "text/html");
//                 if (part == null) {
//                     part = MimeUtility.findFirstPartByMimeType(mOldMessage, "text/plain");
//                 }
//                 if (part != null) {
//                     String text = MimeUtility.getTextFromPart(part);
//                     if (part.getMimeType().equalsIgnoreCase("text/html")) {
//                         text = EmailHtmlUtil.resolveInlineImage(
//                                 getContentResolver(), mAccount.mId, text, mOldMessage, 0);
//                     } else {
//                         // And also escape special character, such as "<>&",
//                         // to HTML escape sequence.
//                         text = EmailHtmlUtil.escapeCharacterToDisplay(text);

//                         /*
//                          * Linkify the plain text and convert it to HTML by replacing
//                          * \r?\n with <br> and adding a html/body wrapper.
//                          */
//                         StringBuffer sb = new StringBuffer("<html><body>");
//                         if (text != null) {
//                             Matcher m = Regex.WEB_URL_PATTERN.matcher(text);
//                             while (m.find()) {
//                                 int start = m.start();
//                                 /*
//                                  * WEB_URL_PATTERN may match domain part of email address. To detect
//                                  * this false match, the character just before the matched string
//                                  * should not be '@'. 
//                                  */
//                                 if (start == 0 || text.charAt(start - 1) != '@') {
//                                     String url = m.group();
//                                     Matcher proto = WEB_URL_PROTOCOL.matcher(url);
//                                     String link;
//                                     if (proto.find()) {
//                                         // Work around to force URL protocol part be lower case,
//                                         // since WebView could follow only lower case protocol link.
//                                         link = proto.group().toLowerCase()
//                                             + url.substring(proto.end());
//                                     } else {
//                                         // Regex.WEB_URL_PATTERN matches URL without protocol part,
//                                         // so added default protocol to link.
//                                         link = "http://" + url;
//                                     }
//                                     String href = String.format("<a href=\"%s\">%s</a>", link, url);
//                                     m.appendReplacement(sb, href);
//                                 }
//                                 else {
//                                     m.appendReplacement(sb, "$0");
//                                 }
//                             }
//                             m.appendTail(sb);
//                         }
//                         sb.append("</body></html>");
//                         text = sb.toString();
//                     }

//                     /*
//                      * TODO consider how to get background images and a million other things
//                      * that HTML allows.
//                      */
//                     // Check if text contains img tag.
//                     if (IMG_TAG_START_REGEX.matcher(text).find()) {
//                         mHandler.showShowPictures(true);
//                     }

//                     loadMessageContentText(text);
//                 }
//                 else {
//                     loadMessageContentUrl("file:///android_asset/empty.html");
//                 }
// //                renderAttachments(mOldMessage, 0);
//             }
//             catch (Exception e) {
//                 if (Email.LOGD) {
//                     Log.v(Email.LOG_TAG, "loadMessageForViewBodyAvailable", e);
//                 }
//             }
        }

        @Override
        public void loadMessageForViewFailed(Account account, String folder, String uid,
                final String message) {
            mHandler.post(new Runnable() {
                public void run() {
                    setProgressBarIndeterminateVisibility(false);
                    mHandler.networkError();
                    loadMessageContentUrl("file:///android_asset/empty.html");
                }
            });
        }

        @Override
        public void loadMessageForViewFinished(Account account, String folder,
                String uid, com.android.email.mail.Message message) {
            mHandler.post(new Runnable() {
                public void run() {
                    setProgressBarIndeterminateVisibility(false);
                }
            });
        }

        @Override
        public void loadMessageForViewStarted(Account account, String folder, String uid)
                {
            mHandler.post(new Runnable() {
                public void run() {
                    loadMessageContentUrl("file:///android_asset/loading.html");
                    setProgressBarIndeterminateVisibility(true);
                }
            });
        }
        
//        @Override
//        public void loadAttachmentStarted(Account account, com.android.email.mail.Message message,
//                Part part, Object tag, boolean requiresDownload) {
//            mHandler.setAttachmentsEnabled(false);
//            Object[] params = (Object[]) tag;
////            mHandler.progress(true, ((AttachmentInfo) params[1]).name);
//            if (requiresDownload) {
//                mHandler.fetchingAttachment();
//            }
//        }

//        @Override
//        public void loadAttachmentFinished(Account account, com.android.email.mail.Message message,
//                Part part, Object tag) {
//            mHandler.setAttachmentsEnabled(true);
//            mHandler.progress(false, null);
//            updateAttachmentThumbnail(part);
//
//            Object[] params = (Object[]) tag;
//            boolean download = (Boolean) params[0];
//            AttachmentInfo attachment = (AttachmentInfo) params[1];
//
//            if (download) {
//                try {
//                    File file = createUniqueFile(Environment.getExternalStorageDirectory(),
//                            attachment.name);
//                    Uri uri = AttachmentProvider.resolveAttachmentIdToContentUri(
//                            getContentResolver(), AttachmentProvider.getAttachmentUri(
//                                    mAccount, attachment.part.getAttachmentId()));
//                    InputStream in = getContentResolver().openInputStream(uri);
//                    OutputStream out = new FileOutputStream(file);
//                    IOUtils.copy(in, out);
//                    out.flush();
//                    out.close();
//                    in.close();
//                    mHandler.attachmentSaved(file.getName());
//                    new MediaScannerNotifier(MessageView.this, file, mHandler);
//                }
//                catch (IOException ioe) {
//                    mHandler.attachmentNotSaved();
//                }
//            }
//            else {
//                try {
//                    Uri uri = AttachmentProvider.resolveAttachmentIdToContentUri(
//                            getContentResolver(), AttachmentProvider.getAttachmentUri(
//                                    mAccount, attachment.part.getAttachmentId()));
//                    Intent intent = new Intent(Intent.ACTION_VIEW);
//                    intent.setData(uri);
//                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
//                    startActivity(intent);
//                } catch (ActivityNotFoundException e) {
//                    mHandler.attachmentViewError();
//                    // TODO: Add a proper warning message (and lots of upstream cleanup to prevent
//                    // it from happening) in the next release.
//                }
//            }
//        }

//        @Override
//        public void loadAttachmentFailed(Account account, com.android.email.mail.Message message, 
//                Part part, Object tag, String reason) {
//            mHandler.setAttachmentsEnabled(true);
////            mHandler.progress(false, null);
//            mHandler.networkError();
//        }
        
        /**
         * Safely load a URL for mMessageContentView, or drop it if the view is gone
         * TODO this really should be moved into a handler message, avoiding the need
         * for this synchronized() section
         */
        private void loadMessageContentUrl(String fileName) {
            synchronized (MessageView.this) {
                if (mMessageContentView != null) {
                    mMessageContentView.loadUrl(fileName);
                }
            }
        }
        
        /**
         * Safely load text for mMessageContentView, or drop it if the view is gone
         * TODO this really should be moved into a handler message, avoiding the need
         * for this synchronized() section
         */
//         private void loadMessageContentText(String text) {
//             synchronized (MessageView.this) {
//                 if (mMessageContentView != null) {
//                     mMessageContentView.loadDataWithBaseURL("email://", text, "text/html",
//                             "utf-8", null);
//                 }
//             }
//         }
    }

    /**
     * Back in the UI thread, handle the final steps of downloading an attachment (view or save).
     *
     * @param save If true, save to SD card.  If false, send view intent
     * @param attachmentId the attachment that was just downloaded
     */
    private void doFinishLoadAttachment(long attachmentId) {
        // If the result does't line up, just skip it - we handle one at a time.
        if (attachmentId != mLoadAttachmentId) {
            return;
        }
        Attachment attachment =
            Attachment.restoreAttachmentWithId(MessageView.this, attachmentId);
        Uri attachmentUri = AttachmentProvider.getAttachmentUri(mAccountId, attachment.mId);
        Uri contentUri =
            AttachmentProvider.resolveAttachmentIdToContentUri(getContentResolver(), attachmentUri);

        if (mLoadAttachmentSave) {
            try {
                File file = createUniqueFile(Environment.getExternalStorageDirectory(),
                        attachment.mFileName);
                InputStream in = getContentResolver().openInputStream(contentUri);
                OutputStream out = new FileOutputStream(file);
                IOUtils.copy(in, out);
                out.flush();
                out.close();
                in.close();

                Toast.makeText(MessageView.this, String.format(
                        getString(R.string.message_view_status_attachment_saved), file.getName()),
                        Toast.LENGTH_LONG).show();

                new MediaScannerNotifier(this, file, mHandler);
            } catch (IOException ioe) {
                Toast.makeText(MessageView.this,
                        getString(R.string.message_view_status_attachment_not_saved),
                        Toast.LENGTH_LONG).show();
            }
        } else {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(contentUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                mHandler.attachmentViewError();
                // TODO: Add a proper warning message (and lots of upstream cleanup to prevent
                // it from happening) in the next release.
            }
        }
    }

    /**
     * This notifier is created after an attachment completes downloaded.  It attaches to the
     * media scanner and waits to handle the completion of the scan.  At that point it tries
     * to start an ACTION_VIEW activity for the attachment.
    */
    private static class MediaScannerNotifier implements MediaScannerConnectionClient {
        private Context mContext;
        private MediaScannerConnection mConnection;
        private File mFile;
        MessageViewHandler mHandler;

        public MediaScannerNotifier(Context context, File file, MessageViewHandler handler) {
            mContext = context;
            mFile = file;
            mHandler = handler;
            mConnection = new MediaScannerConnection(context, this);
            mConnection.connect();
        }

        public void onMediaScannerConnected() {
            mConnection.scanFile(mFile.getAbsolutePath(), null);
        }

        public void onScanCompleted(String path, Uri uri) {
            try {
                if (uri != null) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(uri);
                    mContext.startActivity(intent);
                }
            } catch (ActivityNotFoundException e) {
                mHandler.attachmentViewError(); 
                // TODO: Add a proper warning message (and lots of upstream cleanup to prevent 
                // it from happening) in the next release.
            } finally {
                mConnection.disconnect();
                mContext = null;
                mHandler = null;
            }
        }
    }
}
