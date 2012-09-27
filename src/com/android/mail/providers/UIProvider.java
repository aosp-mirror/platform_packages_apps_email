/*******************************************************************************
 *      Copyright (C) 2011 Google Inc.
 *      Licensed to The Android Open Source Project.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 *******************************************************************************/

package com.android.mail.providers;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import com.android.common.contacts.DataUsageStatUpdater;

import java.util.ArrayList;

public class UIProvider {
    public static final String EMAIL_SEPARATOR = ",";
    public static final long INVALID_CONVERSATION_ID = -1;
    public static final long INVALID_MESSAGE_ID = -1;

    /**
     * Values for the current state of a Folder/Account; note that it's possible that more than one
     * sync is in progress
     */
    public static final class SyncStatus {
        // No sync in progress
        public static final int NO_SYNC = 0;
        // A user-requested sync/refresh is in progress
        public static final int USER_REFRESH = 1<<0;
        // A user-requested query is in progress
        public static final int LIVE_QUERY = 1<<1;
        // A user request for additional results is in progress
        public static final int USER_MORE_RESULTS = 1<<2;
        // A background sync is in progress
        public static final int BACKGROUND_SYNC = 1<<3;
        // An initial sync is needed for this Account/Folder to be used
        public static final int INITIAL_SYNC_NEEDED = 1<<4;
        // Manual sync is required
        public static final int MANUAL_SYNC_REQUIRED = 1<<5;
    }

    /**
     * Values for the result of the last attempted sync of a Folder/Account
     */
    public static final class LastSyncResult {
        // The sync completed successfully
        public static final int SUCCESS = 0;
        // The sync wasn't completed due to a connection error
        public static final int CONNECTION_ERROR = 1;
        // The sync wasn't completed due to an authentication error
        public static final int AUTH_ERROR = 2;
        // The sync wasn't completed due to a security error
        public static final int SECURITY_ERROR = 3;
        // The sync wasn't completed due to a low memory condition
        public static final int STORAGE_ERROR = 4;
        // The sync wasn't completed due to an internal error/exception
        public static final int INTERNAL_ERROR = 5;
    }

    // The actual content provider should define its own authority
    public static final String AUTHORITY = "com.android.mail.providers";

    public static final String ACCOUNT_LIST_TYPE =
            "vnd.android.cursor.dir/vnd.com.android.mail.account";
    public static final String ACCOUNT_TYPE =
            "vnd.android.cursor.item/vnd.com.android.mail.account";

    /**
     * Query parameter key that can be used to control the behavior of list queries.  The value
     * must be a serialized {@link ListParams} object.  UIProvider implementations are not
     * required to respect this query parameter
     */
    public static final String LIST_PARAMS_QUERY_PARAMETER = "listParams";

    public static final String[] ACCOUNTS_PROJECTION = {
            BaseColumns._ID,
            AccountColumns.NAME,
            AccountColumns.PROVIDER_VERSION,
            AccountColumns.URI,
            AccountColumns.CAPABILITIES,
            AccountColumns.FOLDER_LIST_URI,
            AccountColumns.SEARCH_URI,
            AccountColumns.ACCOUNT_FROM_ADDRESSES,
            AccountColumns.SAVE_DRAFT_URI,
            AccountColumns.SEND_MAIL_URI,
            AccountColumns.EXPUNGE_MESSAGE_URI,
            AccountColumns.UNDO_URI,
            AccountColumns.SETTINGS_INTENT_URI,
            AccountColumns.SYNC_STATUS,
            AccountColumns.HELP_INTENT_URI,
            AccountColumns.SEND_FEEDBACK_INTENT_URI,
            AccountColumns.COMPOSE_URI,
            AccountColumns.MIME_TYPE,
            AccountColumns.RECENT_FOLDER_LIST_URI,
            AccountColumns.SettingsColumns.SIGNATURE,
            AccountColumns.SettingsColumns.AUTO_ADVANCE,
            AccountColumns.SettingsColumns.MESSAGE_TEXT_SIZE,
            AccountColumns.SettingsColumns.SNAP_HEADERS,
            AccountColumns.SettingsColumns.REPLY_BEHAVIOR,
            AccountColumns.SettingsColumns.HIDE_CHECKBOXES,
            AccountColumns.SettingsColumns.CONFIRM_DELETE,
            AccountColumns.SettingsColumns.CONFIRM_ARCHIVE,
            AccountColumns.SettingsColumns.CONFIRM_SEND,
            AccountColumns.SettingsColumns.DEFAULT_INBOX,
            AccountColumns.SettingsColumns.FORCE_REPLY_FROM_DEFAULT
    };

    public static final int ACCOUNT_ID_COLUMN = 0;
    public static final int ACCOUNT_NAME_COLUMN = 1;
    public static final int ACCOUNT_PROVIDER_VERISON_COLUMN = 2;
    public static final int ACCOUNT_URI_COLUMN = 3;
    public static final int ACCOUNT_CAPABILITIES_COLUMN = 4;
    public static final int ACCOUNT_FOLDER_LIST_URI_COLUMN = 5;
    public static final int ACCOUNT_SEARCH_URI_COLUMN = 6;
    public static final int ACCOUNT_FROM_ADDRESSES_COLUMN = 7;
    public static final int ACCOUNT_SAVE_DRAFT_URI_COLUMN = 8;
    public static final int ACCOUNT_SEND_MESSAGE_URI_COLUMN = 9;
    public static final int ACCOUNT_EXPUNGE_MESSAGE_URI_COLUMN = 10;
    public static final int ACCOUNT_UNDO_URI_COLUMN = 11;
    public static final int ACCOUNT_SETTINGS_INTENT_URI_COLUMN = 12;
    public static final int ACCOUNT_SYNC_STATUS_COLUMN = 13;
    public static final int ACCOUNT_HELP_INTENT_URI_COLUMN = 14;
    public static final int ACCOUNT_SEND_FEEDBACK_INTENT_URI_COLUMN = 15;
    public static final int ACCOUNT_COMPOSE_INTENT_URI_COLUMN = 16;
    public static final int ACCOUNT_MIME_TYPE_COLUMN = 17;
    public static final int ACCOUNT_RECENT_FOLDER_LIST_URI_COLUMN = 18;

    public static final int ACCOUNT_SETTINGS_SIGNATURE_COLUMN = 19;
    public static final int ACCOUNT_SETTINGS_AUTO_ADVANCE_COLUMN = 20;
    public static final int ACCOUNT_SETTINGS_MESSAGE_TEXT_SIZE_COLUMN = 21;
    public static final int ACCOUNT_SETTINGS_SNAP_HEADERS_COLUMN = 21;
    public static final int ACCOUNT_SETTINGS_REPLY_BEHAVIOR_COLUMN = 23;
    public static final int ACCOUNT_SETTINGS_HIDE_CHECKBOXES_COLUMN = 24;
    public static final int ACCOUNT_SETTINGS_CONFIRM_DELETE_COLUMN = 25;
    public static final int ACCOUNT_SETTINGS_CONFIRM_ARCHIVE_COLUMN = 26;
    public static final int ACCOUNT_SETTINGS_CONFIRM_SEND_COLUMN = 27;
    public static final int ACCOUNT_SETTINGS_DEFAULT_INBOX_COLUMN = 28;
    public static final int ACCOUNT_SETTINGS_FORCE_REPLY_FROM_DEFAULT_COLUMN = 29;


    public static final class AccountCapabilities {
        /**
         * Whether folders can be synchronized back to the server.
         */
        public static final int SYNCABLE_FOLDERS = 0x0001;
        /**
         * Whether the server allows reporting spam back.
         */
        public static final int REPORT_SPAM = 0x0002;
        /**
         * Whether the server supports a concept of Archive: removing mail from the Inbox but
         * keeping it around.
         */
        public static final int ARCHIVE = 0x0004;
        /**
         * Whether the server will stop notifying on updates to this thread? This requires
         * THREADED_CONVERSATIONS to be true, otherwise it should be ignored.
         */
        public static final int MUTE = 0x0008;
        /**
         * Whether the server supports searching over all messages. This requires SYNCABLE_FOLDERS
         * to be true, otherwise it should be ignored.
         */
        public static final int SERVER_SEARCH = 0x0010;
        /**
         * Whether the server supports constraining search to a single folder. Requires
         * SYNCABLE_FOLDERS, otherwise it should be ignored.
         */
        public static final int FOLDER_SERVER_SEARCH = 0x0020;
        /**
         * Whether the server sends us sanitized HTML (guaranteed to not contain malicious HTML).
         */
        public static final int SANITIZED_HTML = 0x0040;
        /**
         * Whether the server allows synchronization of draft messages. This does NOT require
         * SYNCABLE_FOLDERS to be set.
         */
        public static final int DRAFT_SYNCHRONIZATION = 0x0080;
        /**
         * Does the server allow the user to compose mails (and reply) using addresses other than
         * their account name? For instance, GMail allows users to set FROM addresses that are
         * different from account@gmail.com address. For instance, user@gmail.com could have another
         * FROM: address like user@android.com. If the user has enabled multiple FROM address, he
         * can compose (and reply) using either address.
         */
        public static final int MULTIPLE_FROM_ADDRESSES = 0x0100;
        /**
         * Whether the server allows the original message to be included in the reply by setting a
         * flag on the reply. If we can avoid including the entire previous message, we save on
         * bandwidth (replies are shorter).
         */
        public static final int SMART_REPLY = 0x0200;
        /**
         * Does this account support searching locally, on the device? This requires the backend
         * storage to support a mechanism for searching.
         */
        public static final int LOCAL_SEARCH = 0x0400;
        /**
         * Whether the server supports a notion of threaded conversations: where replies to messages
         * are tagged to keep conversations grouped. This could be full threading (each message
         * lists its parent) or conversation-level threading (each message lists one conversation
         * which it belongs to)
         */
        public static final int THREADED_CONVERSATIONS = 0x0800;
        /**
         * Whether the server supports allowing a conversation to be in multiple folders. (Or allows
         * multiple folders on a single conversation)
         */
        public static final int MULTIPLE_FOLDERS_PER_CONV = 0x1000;
        /**
         * Whether the provider supports undoing operations. If it doesn't, never show the undo bar.
         */
        public static final int UNDO = 0x2000;
        /**
         * Whether the account provides help content.
         */
        public static final int HELP_CONTENT = 0x4000;
        /**
         * Whether the account provides a way to send feedback content.
         */
        public static final int SEND_FEEDBACK = 0x8000;
        /**
         * Whether the account provides a mechanism for marking conversations as important.
         */
        public static final int MARK_IMPORTANT = 0x10000;
        /**
         * Whether initial conversation queries should use a limit parameter
         */
        public static final int INITIAL_CONVERSATION_LIMIT = 0x20000;
        /**
         * Whether the account cannot be used for sending
         */
        public static final int SENDING_UNAVAILABLE = 0x40000;
    }

    public static final class AccountColumns {
        /**
         * This string column contains the human visible name for the account.
         */
        public static final String NAME = "name";

        /**
         * This integer contains the type of the account: Google versus non google. This is not
         * returned by the UIProvider, rather this is a notion in the system.
         */
        public static final String TYPE = "type";

        /**
         * This integer column returns the version of the UI provider schema from which this
         * account provider will return results.
         */
        public static final String PROVIDER_VERSION = "providerVersion";

        /**
         * This string column contains the uri to directly access the information for this account.
         */
        public static final String URI = "accountUri";

        /**
         * This integer column contains a bit field of the possible capabilities that this account
         * supports.
         */
        public static final String CAPABILITIES = "capabilities";

        /**
         * This string column contains the content provider uri to return the
         * list of top level folders for this account.
         */
        public static final String FOLDER_LIST_URI = "folderListUri";

        /**
         * This string column contains the content provider uri that can be queried for search
         * results.
         * The supported query parameters are limited to those listed
         * in {@link SearchQueryParameters}
         * The cursor returned from this query is expected have one row, where the columnm are a
         * subset of the columns specified in {@link FolderColumns}
         */
        public static final String SEARCH_URI = "searchUri";

        /**
         * This string column contains a json array of json objects representing
         * custom from addresses for this account or null if there are none.
         */
        public static final String ACCOUNT_FROM_ADDRESSES = "accountFromAddresses";

        /**
         * This string column contains the content provider uri that can be used to save (insert)
         * new draft messages for this account. NOTE: This might be better to
         * be an update operation on the messageUri.
         */
        public static final String SAVE_DRAFT_URI = "saveDraftUri";

        /**
         * This string column contains the content provider uri that can be used to send
         * a message for this account.
         * NOTE: This might be better to be an update operation on the messageUri.
         */
        public static final String SEND_MAIL_URI = "sendMailUri";

        /**
         * This string column contains the content provider uri that can be used
         * to expunge a message from this account. NOTE: This might be better to
         * be an update operation on the messageUri.
         * When {@link android.content.ContentResolver#update()} is called with this uri, the
         * {@link ContentValues} object is expected to have {@link BaseColumns._ID} specified with
         * the local message id of the message.
         */
        public static final String EXPUNGE_MESSAGE_URI = "expungeMessageUri";

        /**
         * This string column contains the content provider uri that can be used
         * to undo the last committed action.
         */
        public static final String UNDO_URI = "undoUri";

        /**
         * Uri for EDIT intent that will cause the settings screens for this account type to be
         * shown.
         * Optionally, extra values from {@link EditSettingsExtras} can be used to indicate
         * which settings the user wants to edit.
         * TODO: When we want to support a heterogeneous set of account types, this value may need
         * to be moved to a global content provider.
         */
        public static String SETTINGS_INTENT_URI = "accountSettingsIntentUri";

        /**
         * Uri for VIEW intent that will cause the help screens for this account type to be
         * shown.
         * TODO: When we want to support a heterogeneous set of account types, this value may need
         * to be moved to a global content provider.
         */
        public static String HELP_INTENT_URI = "helpIntentUri";

        /**
         * Uri for VIEW intent that will cause the send feedback for this account type to be
         * shown.
         * TODO: When we want to support a heterogeneous set of account types, this value may need
         * to be moved to a global content provider.
         */
        public static String SEND_FEEDBACK_INTENT_URI = "sendFeedbackIntentUri";

        /**
         * This int column contains the current sync status of the account (the logical AND of the
         * sync status of folders in this account)
         */
        public static final String SYNC_STATUS = "syncStatus";
        /**
         * Uri for VIEW intent that will cause the compose screens for this type
         * of account to be shown.
         */
        public static final String COMPOSE_URI = "composeUri";
        /**
         * Mime-type defining this account.
         */
        public static final String MIME_TYPE = "mimeType";
        /**
         * URI for location of recent folders viewed on this account.
         */
        public static final String RECENT_FOLDER_LIST_URI = "recentFolderListUri";

        public static final class SettingsColumns {
            /**
             * String column containing the contents of the signature for this account.  If no
             * signature has been specified, the value will be null.
             */
            public static final String SIGNATURE = "signature";

            /**
             * Integer column containing the user's specified auto-advance policy.  This value will
             * be one of the values in {@link UIProvider.AutoAdvance}
             */
            public static final String AUTO_ADVANCE = "auto_advance";

            /**
             * Integer column containing the user's specified message text size preference.  This
             * value will be one of the values in {@link UIProvider.MessageTextSize}
             */
            public static final String MESSAGE_TEXT_SIZE = "message_text_size";

            /**
             * Integer column contaning the user's specified snap header preference.  This value
             * will be one of the values in {@link UIProvider.SnapHeaderValue}
             */
            public static final String SNAP_HEADERS = "snap_headers";

            /**
             * Integer column containing the user's specified default reply behavior.  This value
             * will be one of the values in {@link UIProvider.DefaultReplyBehavior}
             */
            public static final String REPLY_BEHAVIOR = "reply_behavior";

            /**
             * Integer column containing the user's specified checkbox preference. A
             * non zero value means to hide checkboxes.
             */
            public static final String HIDE_CHECKBOXES = "hide_checkboxes";

            /**
             * Integer column containing the user's specified confirm delete preference value.
             * A non zero value indicates that the user has indicated that a confirmation should
             * be shown when a delete action is performed.
             */
            public static final String CONFIRM_DELETE = "confirm_delete";

            /**
             * Integer column containing the user's specified confirm archive preference value.
             * A non zero value indicates that the user has indicated that a confirmation should
             * be shown when an archive action is performed.
             */
            public static final String CONFIRM_ARCHIVE = "confirm_archive";

            /**
             * Integer column containing the user's specified confirm send preference value.
             * A non zero value indicates that the user has indicated that a confirmation should
             * be shown when a send action is performed.
             */
            public static final String CONFIRM_SEND = "confirm_send";
            /**
             * String folder containing the serialized default inbox folder for an account.
             */
            public static final String DEFAULT_INBOX = "default_inbox";
            /**
             * Integer column containing a non zero value if replies should always be sent from
             * a default address instead of a recipient.
             */
            public static String FORCE_REPLY_FROM_DEFAULT = "force_reply_from_default";
        }
    }

    public static final class SearchQueryParameters {
        /**
         * Parameter used to specify the search query.
         */
        public static final String QUERY = "query";

        /**
         * If specified, the query results will be limited to this folder.
         */
        public static final String FOLDER = "folder";

        private SearchQueryParameters() {}
    }

    public static final class ConversationListQueryParameters {
        public static final String DEFAULT_LIMIT = "50";
        /**
         * Parameter used to limit the number of rows returned by a conversation list query
         */
        public static final String LIMIT = "limit";

        /**
         * Parameter used to control whether the this query a remote server.
         */
        public static final String USE_NETWORK = "use_network";

        private ConversationListQueryParameters() {}
    }

    // We define a "folder" as anything that contains a list of conversations.
    public static final String FOLDER_LIST_TYPE =
            "vnd.android.cursor.dir/vnd.com.android.mail.folder";
    public static final String FOLDER_TYPE =
            "vnd.android.cursor.item/vnd.com.android.mail.folder";

    public static final String[] FOLDERS_PROJECTION = {
        BaseColumns._ID,
        FolderColumns.URI,
        FolderColumns.NAME,
        FolderColumns.HAS_CHILDREN,
        FolderColumns.CAPABILITIES,
        FolderColumns.SYNC_WINDOW,
        FolderColumns.CONVERSATION_LIST_URI,
        FolderColumns.CHILD_FOLDERS_LIST_URI,
        FolderColumns.UNREAD_COUNT,
        FolderColumns.TOTAL_COUNT,
        FolderColumns.REFRESH_URI,
        FolderColumns.SYNC_STATUS,
        FolderColumns.LAST_SYNC_RESULT,
        FolderColumns.TYPE,
        FolderColumns.ICON_RES_ID,
        FolderColumns.BG_COLOR,
        FolderColumns.FG_COLOR,
        FolderColumns.LOAD_MORE_URI
    };

    public static final int FOLDER_ID_COLUMN = 0;
    public static final int FOLDER_URI_COLUMN = 1;
    public static final int FOLDER_NAME_COLUMN = 2;
    public static final int FOLDER_HAS_CHILDREN_COLUMN = 3;
    public static final int FOLDER_CAPABILITIES_COLUMN = 4;
    public static final int FOLDER_SYNC_WINDOW_COLUMN = 5;
    public static final int FOLDER_CONVERSATION_LIST_URI_COLUMN = 6;
    public static final int FOLDER_CHILD_FOLDERS_LIST_COLUMN = 7;
    public static final int FOLDER_UNREAD_COUNT_COLUMN = 8;
    public static final int FOLDER_TOTAL_COUNT_COLUMN = 9;
    public static final int FOLDER_REFRESH_URI_COLUMN = 10;
    public static final int FOLDER_SYNC_STATUS_COLUMN = 11;
    public static final int FOLDER_LAST_SYNC_RESULT_COLUMN = 12;
    public static final int FOLDER_TYPE_COLUMN = 13;
    public static final int FOLDER_ICON_RES_ID_COLUMN = 14;
    public static final int FOLDER_BG_COLOR_COLUMN = 15;
    public static final int FOLDER_FG_COLOR_COLUMN = 16;
    public static final int FOLDER_LOAD_MORE_URI_COLUMN = 17;

    public static final class FolderType {
        public static final int DEFAULT = 0;
        public static final int INBOX = 1;
        public static final int DRAFT = 2;
        public static final int OUTBOX = 3;
        public static final int SENT = 4;
        public static final int TRASH = 5;
        public static final int SPAM = 6;
        public static final int STARRED = 7;
    }

    public static final class FolderCapabilities {
        public static final int SYNCABLE = 0x0001;
        public static final int PARENT = 0x0002;
        public static final int CAN_HOLD_MAIL = 0x0004;
        public static final int CAN_ACCEPT_MOVED_MESSAGES = 0x0008;
         /**
         * For accounts that support archive, this will indicate that this folder supports
         * the archive functionality.
         */
        public static final int ARCHIVE = 0x0010;

        /**
         * For accounts that support report spam, this will indicate that this folder supports
         * the report spam functionality.
         */
        public static final int REPORT_SPAM = 0x0020;

        /**
         * For accounts that support mute, this will indicate if a mute is performed from within
         * this folder, the action is destructive.
         */
        public static final int DESTRUCTIVE_MUTE = 0x0040;

        /**
         * Indicates that a folder supports settings (sync lookback, etc.)
         */
        public static final int SUPPORTS_SETTINGS = 0x0080;
        /**
         * All the messages in this folder are important.
         */
        public static final int ONLY_IMPORTANT = 0x0100;
    }

    public static final class FolderColumns {
        /**
         * This string column contains the uri of the folder.
         */
        public static final String URI = "folderUri";
        /**
         * This string column contains the human visible name for the folder.
         */
        public static final String NAME = "name";
        /**
         * This int column represents the capabilities of the folder specified by
         * FolderCapabilities flags.
         */
        public static String CAPABILITIES = "capabilities";
        /**
         * This int column represents whether or not this folder has any
         * child folders.
         */
        public static String HAS_CHILDREN = "hasChildren";
        /**
         * This int column represents how large the sync window is.
         */
        public static String SYNC_WINDOW = "syncWindow";
        /**
         * This string column contains the content provider uri to return the
         * list of conversations for this folder.
         */
        public static final String CONVERSATION_LIST_URI = "conversationListUri";
        /**
         * This string column contains the content provider uri to return the
         * list of child folders of this folder.
         */
        public static final String CHILD_FOLDERS_LIST_URI = "childFoldersListUri";

        public static final String UNREAD_COUNT = "unreadCount";

        public static final String TOTAL_COUNT = "totalCount";
        /**
         * This string column contains the content provider uri to force a
         * refresh of this folder.
         */
        public static final  String REFRESH_URI = "refreshUri";
        /**
         * This int column contains current sync status of the folder; some combination of the
         * SyncStatus bits defined above
         */
        public static final String SYNC_STATUS  = "syncStatus";
        /**
         * This int column contains the sync status of the last sync attempt; one of the
         * LastSyncStatus values defined above
         */
        public static final String LAST_SYNC_RESULT  = "lastSyncResult";
        /**
         * This long column contains the icon res id for this folder, or 0 if there is none.
         */
        public static final String ICON_RES_ID = "iconResId";
        /**
         * This int column contains the type of the folder. Zero is default.
         */
        public static final String TYPE = "type";
        /**
         * String representing the integer background color associated with this
         * folder, or null.
         */
        public static final String BG_COLOR = "bgColor";
        /**
         * String representing the integer of the foreground color associated
         * with this folder, or null.
         */
        public static final String FG_COLOR = "fgColor";
        /**
         * String with the content provider Uri used to request more items in the folder, or null.
         */
        public static final String LOAD_MORE_URI = "loadMoreUri";
        public FolderColumns() {}
    }

    // We define a "folder" as anything that contains a list of conversations.
    public static final String CONVERSATION_LIST_TYPE =
            "vnd.android.cursor.dir/vnd.com.android.mail.conversation";
    public static final String CONVERSATION_TYPE =
            "vnd.android.cursor.item/vnd.com.android.mail.conversation";


    public static final String[] CONVERSATION_PROJECTION = {
        BaseColumns._ID,
        ConversationColumns.URI,
        ConversationColumns.MESSAGE_LIST_URI,
        ConversationColumns.SUBJECT,
        ConversationColumns.SNIPPET,
        ConversationColumns.SENDER_INFO,
        ConversationColumns.DATE_RECEIVED_MS,
        ConversationColumns.HAS_ATTACHMENTS,
        ConversationColumns.NUM_MESSAGES,
        ConversationColumns.NUM_DRAFTS,
        ConversationColumns.SENDING_STATE,
        ConversationColumns.PRIORITY,
        ConversationColumns.READ,
        ConversationColumns.STARRED,
        ConversationColumns.FOLDER_LIST,
        ConversationColumns.RAW_FOLDERS,
        ConversationColumns.FLAGS,
        ConversationColumns.PERSONAL_LEVEL,
        ConversationColumns.SPAM,
        ConversationColumns.MUTED
    };

    // These column indexes only work when the caller uses the
    // default CONVERSATION_PROJECTION defined above.
    public static final int CONVERSATION_ID_COLUMN = 0;
    public static final int CONVERSATION_URI_COLUMN = 1;
    public static final int CONVERSATION_MESSAGE_LIST_URI_COLUMN = 2;
    public static final int CONVERSATION_SUBJECT_COLUMN = 3;
    public static final int CONVERSATION_SNIPPET_COLUMN = 4;
    public static final int CONVERSATION_SENDER_INFO_COLUMN = 5;
    public static final int CONVERSATION_DATE_RECEIVED_MS_COLUMN = 6;
    public static final int CONVERSATION_HAS_ATTACHMENTS_COLUMN = 7;
    public static final int CONVERSATION_NUM_MESSAGES_COLUMN = 8;
    public static final int CONVERSATION_NUM_DRAFTS_COLUMN = 9;
    public static final int CONVERSATION_SENDING_STATE_COLUMN = 10;
    public static final int CONVERSATION_PRIORITY_COLUMN = 11;
    public static final int CONVERSATION_READ_COLUMN = 12;
    public static final int CONVERSATION_STARRED_COLUMN = 13;
    public static final int CONVERSATION_FOLDER_LIST_COLUMN = 14;
    public static final int CONVERSATION_RAW_FOLDERS_COLUMN = 15;
    public static final int CONVERSATION_FLAGS_COLUMN = 16;
    public static final int CONVERSATION_PERSONAL_LEVEL_COLUMN = 17;
    public static final int CONVERSATION_IS_SPAM_COLUMN = 18;
    public static final int CONVERSATION_MUTED_COLUMN = 19;

    public static final class ConversationSendingState {
        public static final int OTHER = 0;
        public static final int SENDING = 1;
        public static final int SENT = 2;
        public static final int SEND_ERROR = -1;
    }

    public static final class ConversationPriority {
        public static final int DEFAULT = 0;
        public static final int IMPORTANT = 1;
        public static final int LOW = 0;
        public static final int HIGH = 1;
    }

    public static final class ConversationPersonalLevel {
        public static final int NOT_TO_ME = 0;
        public static final int TO_ME_AND_OTHERS = 1;
        public static final int ONLY_TO_ME = 2;
    }

    public static final class ConversationFlags {
        public static final int REPLIED = 1<<2;
        public static final int FORWARDED = 1<<3;
        public static final int CALENDAR_INVITE = 1<<4;
    }

    public static final class ConversationColumns {
        public static final String URI = "conversationUri";
        /**
         * This string column contains the content provider uri to return the
         * list of messages for this conversation.
         * The cursor returned by this query can return a {@link android.os.Bundle}
         * from a call to {@link android.database.Cursor#getExtras()}.  This Bundle may have
         * values with keys listed in {@link CursorExtraKeys}
         */
        public static final String MESSAGE_LIST_URI = "messageListUri";
        /**
         * This string column contains the subject string for a conversation.
         */
        public static final String SUBJECT = "subject";
        /**
         * This string column contains the snippet string for a conversation.
         */
        public static final String SNIPPET = "snippet";
        /**
         * This string column contains the sender info string for a
         * conversation.
         */
        public static final String SENDER_INFO = "senderInfo";
        /**
         * This long column contains the time in ms of the latest update to a
         * conversation.
         */
        public static final String DATE_RECEIVED_MS = "dateReceivedMs";

        /**
         * This boolean column contains whether any messages in this conversation
         * have attachments.
         */
        public static final String HAS_ATTACHMENTS = "hasAttachments";

        /**
         * This int column contains the number of messages in this conversation.
         * For unthreaded, this will always be 1.
         */
        public static String NUM_MESSAGES = "numMessages";

        /**
         * This int column contains the number of drafts associated with this
         * conversation.
         */
        public static String NUM_DRAFTS = "numDrafts";

        /**
         * This int column contains the state of drafts and replies associated
         * with this conversation. Use ConversationSendingState to interpret
         * this field.
         */
        public static String SENDING_STATE = "sendingState";

        /**
         * This int column contains the priority of this conversation. Use
         * ConversationPriority to interpret this field.
         */
        public static String PRIORITY = "priority";

        /**
         * This int column indicates whether the conversation has been read
         */
        public static String READ = "read";

        /**
         * This int column indicates whether the conversation has been starred
         */
        public static String STARRED = "starred";

        /**
         * This string column contains a csv of all folder uris associated with this
         * conversation
         */
        public static final String FOLDER_LIST = "folderList";

        /**
         * This string column contains a serialized list of all folders
         * separated by a Folder.FOLDER_SEPARATOR that are associated with this
         * conversation. The folders should be only those that the provider
         * wants to have displayed.
         */
        public static final String RAW_FOLDERS = "rawFolders";
        public static final String FLAGS = "conversationFlags";
        public static final String PERSONAL_LEVEL = "personalLevel";

        /**
         * This int column indicates whether the conversation is marked spam.
         */
        public static final String SPAM = "spam";

        /**
         * This int column indicates whether the conversation was muted.
         */
        public static final String MUTED = "muted";

        private ConversationColumns() {
        }
    }

    public static final class ConversationCursorCommand {

        public static final String COMMAND_RESPONSE_OK = "ok";
        public static final String COMMAND_RESPONSE_FAILED = "failed";

        /**
         * This bundle key has a boolean value: true to allow cursor network access (whether this
         * is true by default is up to the provider), false to temporarily disable network access.
         * <p>
         * A provider that implements this command should include this key in its response with a
         * value of {@link #COMMAND_RESPONSE_OK} or {@link #COMMAND_RESPONSE_FAILED}.
         */
        public static final String COMMAND_KEY_ALLOW_NETWORK_ACCESS = "allowNetwork";

        /**
         * This bundle key has a boolean value: true to indicate that this cursor has been shown
         * to the user.
         */
        public static final String COMMAND_KEY_SET_VISIBILITY = "setVisibility";

        private ConversationCursorCommand() {}
    }

    /**
     * List of operations that can can be performed on a conversation. These operations are applied
     * with {@link ContentProvider#update(Uri, ContentValues, String, String[])}
     * where the conversation uri is specified, and the ContentValues specifies the operation to
     * be performed.
     * <p/>
     * The operation to be performed is specified in the ContentValues by
     * the {@link ConversationOperations#OPERATION_KEY}
     * <p/>
     * Note not all UI providers will support these operations.  {@link AccountCapabilities} can
     * be used to determine which operations are supported.
     */
    public static final class ConversationOperations {
        /**
         * ContentValues key used to specify the operation to be performed
         */
        public static final String OPERATION_KEY = "operation";

        /**
         * Archive operation
         */
        public static final String ARCHIVE = "archive";

        /**
         * Mute operation
         */
        public static final String MUTE = "mute";

        /**
         * Report spam operation
         */
        public static final String REPORT_SPAM = "report_spam";

        private ConversationOperations() {
        }
    }

    public static final class DraftType {
        public static final int NOT_A_DRAFT = 0;
        public static final int COMPOSE = 1;
        public static final int REPLY = 2;
        public static final int REPLY_ALL = 3;
        public static final int FORWARD = 4;

        private DraftType() {}
    }

    public static final String[] MESSAGE_PROJECTION = {
        BaseColumns._ID,
        MessageColumns.SERVER_ID,
        MessageColumns.URI,
        MessageColumns.CONVERSATION_ID,
        MessageColumns.SUBJECT,
        MessageColumns.SNIPPET,
        MessageColumns.FROM,
        MessageColumns.TO,
        MessageColumns.CC,
        MessageColumns.BCC,
        MessageColumns.REPLY_TO,
        MessageColumns.DATE_RECEIVED_MS,
        MessageColumns.BODY_HTML,
        MessageColumns.BODY_TEXT,
        MessageColumns.EMBEDS_EXTERNAL_RESOURCES,
        MessageColumns.REF_MESSAGE_ID,
        MessageColumns.DRAFT_TYPE,
        MessageColumns.APPEND_REF_MESSAGE_CONTENT,
        MessageColumns.HAS_ATTACHMENTS,
        MessageColumns.ATTACHMENT_LIST_URI,
        MessageColumns.MESSAGE_FLAGS,
        MessageColumns.JOINED_ATTACHMENT_INFOS,
        MessageColumns.SAVE_MESSAGE_URI,
        MessageColumns.SEND_MESSAGE_URI,
        MessageColumns.ALWAYS_SHOW_IMAGES,
        MessageColumns.READ,
        MessageColumns.STARRED,
        MessageColumns.QUOTE_START_POS,
        MessageColumns.ATTACHMENTS,
        MessageColumns.CUSTOM_FROM_ADDRESS,
        MessageColumns.MESSAGE_ACCOUNT_URI,
        MessageColumns.EVENT_INTENT_URI
    };

    /** Separates attachment info parts in strings in a message. */
    @Deprecated
    public static final String MESSAGE_ATTACHMENT_INFO_SEPARATOR = "\n";
    public static final String MESSAGE_LIST_TYPE =
            "vnd.android.cursor.dir/vnd.com.android.mail.message";
    public static final String MESSAGE_TYPE =
            "vnd.android.cursor.item/vnd.com.android.mail.message";

    public static final int MESSAGE_ID_COLUMN = 0;
    public static final int MESSAGE_SERVER_ID_COLUMN = 1;
    public static final int MESSAGE_URI_COLUMN = 2;
    public static final int MESSAGE_CONVERSATION_URI_COLUMN = 3;
    public static final int MESSAGE_SUBJECT_COLUMN = 4;
    public static final int MESSAGE_SNIPPET_COLUMN = 5;
    public static final int MESSAGE_FROM_COLUMN = 6;
    public static final int MESSAGE_TO_COLUMN = 7;
    public static final int MESSAGE_CC_COLUMN = 8;
    public static final int MESSAGE_BCC_COLUMN = 9;
    public static final int MESSAGE_REPLY_TO_COLUMN = 10;
    public static final int MESSAGE_DATE_RECEIVED_MS_COLUMN = 11;
    public static final int MESSAGE_BODY_HTML_COLUMN = 12;
    public static final int MESSAGE_BODY_TEXT_COLUMN = 13;
    public static final int MESSAGE_EMBEDS_EXTERNAL_RESOURCES_COLUMN = 14;
    public static final int MESSAGE_REF_MESSAGE_ID_COLUMN = 15;
    public static final int MESSAGE_DRAFT_TYPE_COLUMN = 16;
    public static final int MESSAGE_APPEND_REF_MESSAGE_CONTENT_COLUMN = 17;
    public static final int MESSAGE_HAS_ATTACHMENTS_COLUMN = 18;
    public static final int MESSAGE_ATTACHMENT_LIST_URI_COLUMN = 19;
    public static final int MESSAGE_FLAGS_COLUMN = 20;
    public static final int MESSAGE_JOINED_ATTACHMENT_INFOS_COLUMN = 21;
    public static final int MESSAGE_SAVE_URI_COLUMN = 22;
    public static final int MESSAGE_SEND_URI_COLUMN = 23;
    public static final int MESSAGE_ALWAYS_SHOW_IMAGES_COLUMN = 24;
    public static final int MESSAGE_READ_COLUMN = 25;
    public static final int MESSAGE_STARRED_COLUMN = 26;
    public static final int QUOTED_TEXT_OFFSET_COLUMN = 27;
    public static final int MESSAGE_ATTACHMENTS_COLUMN = 28;
    public static final int MESSAGE_CUSTOM_FROM_ADDRESS_COLUMN = 29;
    public static final int MESSAGE_ACCOUNT_URI_COLUMN = 30;
    public static final int MESSAGE_EVENT_INTENT_COLUMN = 31;


    public static final class CursorStatus {
        // The cursor is actively loading more data
        public static final int LOADING =      1 << 0;

        // The cursor is currently not loading more data, but more data may be available
        public static final int LOADED =       1 << 1;

        // An error occured while loading data
        public static final int ERROR =        1 << 2;

        // The cursor is loaded, and there will be no more data
        public static final int COMPLETE =     1 << 3;
    }


    public static final class CursorExtraKeys {
        /**
         * This integer column contains the staus of the message cursor.  The value will be
         * one defined in {@link CursorStatus}.
         */
        public static final String EXTRA_STATUS = "status";

        /**
         * Used for finding the cause of an error.
         * TODO: define these values
         */
        public static final String EXTRA_ERROR = "error";

    }

    public static final class AccountCursorExtraKeys {
        /**
         * This integer column contains the staus of the account cursor.  The value will be
         * 1 if all accounts have been fully loaded or 0 if the account list hasn't been fully
         * initialized
         */
        public static final String ACCOUNTS_LOADED = "accounts_loaded";
    }


    public static final class MessageFlags {
        public static final int REPLIED =       1 << 2;
        public static final int FORWARDED =     1 << 3;
        public static final int CALENDAR_INVITE =     1 << 4;
    }

    public static final class MessageColumns {
        /**
         * This string column contains a content provider URI that points to this single message.
         */
        public static final String URI = "messageUri";
        /**
         * This string column contains a server-assigned ID for this message.
         */
        public static final String SERVER_ID = "serverMessageId";
        public static final String CONVERSATION_ID = "conversationId";
        /**
         * This string column contains the subject of a message.
         */
        public static final String SUBJECT = "subject";
        /**
         * This string column contains a snippet of the message body.
         */
        public static final String SNIPPET = "snippet";
        /**
         * This string column contains the single email address (and optionally name) of the sender.
         */
        public static final String FROM = "fromAddress";
        /**
         * This string column contains a comma-delimited list of "To:" recipient email addresses.
         */
        public static final String TO = "toAddresses";
        /**
         * This string column contains a comma-delimited list of "CC:" recipient email addresses.
         */
        public static final String CC = "ccAddresses";
        /**
         * This string column contains a comma-delimited list of "BCC:" recipient email addresses.
         * This value will be null for incoming messages.
         */
        public static final String BCC = "bccAddresses";
        /**
         * This string column contains the single email address (and optionally name) of the
         * sender's reply-to address.
         */
        public static final String REPLY_TO = "replyToAddress";
        /**
         * This long column contains the timestamp (in millis) of receipt of the message.
         */
        public static final String DATE_RECEIVED_MS = "dateReceivedMs";
        /**
         * This string column contains the HTML form of the message body, if available. If not,
         * a provider must populate BODY_TEXT.
         */
        public static final String BODY_HTML = "bodyHtml";
        /**
         * This string column contains the plaintext form of the message body, if HTML is not
         * otherwise available. If HTML is available, this value should be left empty (null).
         */
        public static final String BODY_TEXT = "bodyText";
        public static final String EMBEDS_EXTERNAL_RESOURCES = "bodyEmbedsExternalResources";
        /**
         * This string column contains an opaque string used by the sendMessage api.
         */
        public static final String REF_MESSAGE_ID = "refMessageId";
        /**
         * This integer column contains the type of this draft, or zero (0) if this message is not a
         * draft. See {@link DraftType} for possible values.
         */
        public static final String DRAFT_TYPE = "draftType";
        /**
         * This boolean column indicates whether an outgoing message should trigger special quoted
         * text processing upon send. The value should default to zero (0) for protocols that do
         * not support or require this flag, and for all incoming messages.
         */
        public static final String APPEND_REF_MESSAGE_CONTENT = "appendRefMessageContent";
        /**
         * This boolean column indicates whether a message has attachments. The list of attachments
         * can be retrieved using the URI in {@link MessageColumns#ATTACHMENT_LIST_URI}.
         */
        public static final String HAS_ATTACHMENTS = "hasAttachments";
        /**
         * This string column contains the content provider URI for the list of
         * attachments associated with this message.
         */
        public static final String ATTACHMENT_LIST_URI = "attachmentListUri";
        /**
         * This long column is a bit field of flags defined in {@link MessageFlags}.
         */
        public static final String MESSAGE_FLAGS = "messageFlags";
        /**
         * This string column contains a specially formatted string representing all
         * attachments that we added to a message that is being sent or saved.
         *
         * TODO: remove this and use {@link #ATTACHMENTS} instead
         */
        @Deprecated
        public static final String JOINED_ATTACHMENT_INFOS = "joinedAttachmentInfos";
        /**
         * This string column contains the content provider URI for saving this
         * message.
         */
        public static final String SAVE_MESSAGE_URI = "saveMessageUri";
        /**
         * This string column contains content provider URI for sending this
         * message.
         */
        public static final String SEND_MESSAGE_URI = "sendMessageUri";

        /**
         * This integer column represents whether the user has specified that images should always
         * be shown.  The value of "1" indicates that the user has specified that images should be
         * shown, while the value of "0" indicates that the user should be prompted before loading
         * any external images.
         */
        public static final String ALWAYS_SHOW_IMAGES = "alwaysShowImages";

        /**
         * This boolean column indicates whether the message has been read
         */
        public static String READ = "read";

        /**
         * This boolean column indicates whether the message has been starred
         */
        public static String STARRED = "starred";

        /**
         * This integer column represents the offset in the message of quoted
         * text. If include_quoted_text is zero, the value contained in this
         * column is invalid.
         */
        public static final String QUOTE_START_POS = "quotedTextStartPos";

        /**
         * This string columns contains a JSON array of serialized {@link Attachment} objects.
         */
        public static final String ATTACHMENTS = "attachments";
        public static final String CUSTOM_FROM_ADDRESS = "customFrom";
        /**
         * Uri of the account associated with this message. Except in the case
         * of showing a combined view, this column is almost always empty.
         */
        public static final String MESSAGE_ACCOUNT_URI = "messageAccountUri";
        /**
         * Uri of the account associated with this message. Except in the case
         * of showing a combined view, this column is almost always empty.
         */
        public static final String EVENT_INTENT_URI = "eventIntentUri";
        private MessageColumns() {}
    }

    /**
     * List of operations that can can be performed on a message. These operations are applied
     * with {@link ContentProvider#update(Uri, ContentValues, String, String[])}
     * where the message uri is specified, and the ContentValues specifies the operation to
     * be performed, e.g. values.put(RESPOND_COLUMN, RESPOND_ACCEPT)
     * <p/>
     * Note not all UI providers will support these operations.
     */
    public static final class MessageOperations {
        /**
         * Respond to a calendar invitation
         */
        public static final String RESPOND_COLUMN = "respond";

        public static final int RESPOND_ACCEPT = 1;
        public static final int RESPOND_TENTATIVE = 2;
        public static final int RESPOND_DECLINE = 3;

        private MessageOperations() {
        }
    }

    public static final String ATTACHMENT_LIST_TYPE =
            "vnd.android.cursor.dir/vnd.com.android.mail.attachment";
    public static final String ATTACHMENT_TYPE =
            "vnd.android.cursor.item/vnd.com.android.mail.attachment";

    public static final String[] ATTACHMENT_PROJECTION = {
        AttachmentColumns.NAME,
        AttachmentColumns.SIZE,
        AttachmentColumns.URI,
        AttachmentColumns.CONTENT_TYPE,
        AttachmentColumns.STATE,
        AttachmentColumns.DESTINATION,
        AttachmentColumns.DOWNLOADED_SIZE,
        AttachmentColumns.CONTENT_URI,
        AttachmentColumns.THUMBNAIL_URI,
        AttachmentColumns.PREVIEW_INTENT
    };
    private static final String EMAIL_SEPARATOR_PATTERN = "\n";
    public static final int ATTACHMENT_NAME_COLUMN = 0;
    public static final int ATTACHMENT_SIZE_COLUMN = 1;
    public static final int ATTACHMENT_URI_COLUMN = 2;
    public static final int ATTACHMENT_CONTENT_TYPE_COLUMN = 3;
    public static final int ATTACHMENT_STATE_COLUMN = 4;
    public static final int ATTACHMENT_DESTINATION_COLUMN = 5;
    public static final int ATTACHMENT_DOWNLOADED_SIZE_COLUMN = 6;
    public static final int ATTACHMENT_CONTENT_URI_COLUMN = 7;
    public static final int ATTACHMENT_THUMBNAIL_URI_COLUMN = 8;
    public static final int ATTACHMENT_PREVIEW_INTENT_COLUMN = 9;

    /**
     * Valid states for the {@link AttachmentColumns#STATE} column.
     *
     */
    public static final class AttachmentState {
        /**
         * The full attachment is not present on device. When used as a command,
         * setting this state will tell the provider to cancel a download in
         * progress.
         * <p>
         * Valid next states: {@link #DOWNLOADING}
         */
        public static final int NOT_SAVED = 0;
        /**
         * The most recent attachment download attempt failed. The current UI
         * design does not require providers to persist this state, but
         * providers must return this state at least once after a download
         * failure occurs. This state may not be used as a command.
         * <p>
         * Valid next states: {@link #DOWNLOADING}
         */
        public static final int FAILED = 1;
        /**
         * The attachment is currently being downloaded by the provider.
         * {@link AttachmentColumns#DOWNLOADED_SIZE} should reflect the current
         * download progress while in this state. When used as a command,
         * setting this state will tell the provider to initiate a download to
         * the accompanying destination in {@link AttachmentColumns#DESTINATION}
         * .
         * <p>
         * Valid next states: {@link #NOT_SAVED}, {@link #FAILED},
         * {@link #SAVED}
         */
        public static final int DOWNLOADING = 2;
        /**
         * The attachment was successfully downloaded to the destination in
         * {@link AttachmentColumns#DESTINATION}. If a provider later detects
         * that a download is missing, it should reset the state to
         * {@link #NOT_SAVED}. This state may not be used as a command on its
         * own. To move a file from cache to external, update
         * {@link AttachmentColumns#DESTINATION}.
         * <p>
         * Valid next states: {@link #NOT_SAVED}
         */
        public static final int SAVED = 3;

        private AttachmentState() {}
    }

    public static final class AttachmentDestination {

        /**
         * The attachment will be or is already saved to the app-private cache partition.
         */
        public static final int CACHE = 0;
        /**
         * The attachment will be or is already saved to external shared device storage.
         */
        public static final int EXTERNAL = 1;

        private AttachmentDestination() {}
    }

    public static final class AttachmentColumns {
        /**
         * This string column is the attachment's file name, intended for display in UI. It is not
         * the full path of the file.
         */
        public static final String NAME = OpenableColumns.DISPLAY_NAME;
        /**
         * This integer column is the file size of the attachment, in bytes.
         */
        public static final String SIZE = OpenableColumns.SIZE;
        /**
         * This column is a {@link Uri} that can be queried to monitor download state and progress
         * for this individual attachment (resulting cursor has one single row for this attachment).
         */
        public static final String URI = "uri";
        /**
         * This string column is the MIME type of the attachment.
         */
        public static final String CONTENT_TYPE = "contentType";
        /**
         * This integer column is the current downloading state of the
         * attachment as defined in {@link AttachmentState}.
         * <p>
         * Providers must accept updates to {@link #URI} with new values of
         * this column to initiate or cancel downloads.
         */
        public static final String STATE = "state";
        /**
         * This integer column is the file destination for the current download
         * in progress (when {@link #STATE} is
         * {@link AttachmentState#DOWNLOADING}) or the resulting downloaded file
         * ( when {@link #STATE} is {@link AttachmentState#SAVED}), as defined
         * in {@link AttachmentDestination}. This value is undefined in any
         * other state.
         * <p>
         * Providers must accept updates to {@link #URI} with new values of
         * this column to move an existing downloaded file.
         */
        public static final String DESTINATION = "destination";
        /**
         * This integer column is the current number of bytes downloaded when
         * {@link #STATE} is {@link AttachmentState#DOWNLOADING}. This value is
         * undefined in any other state.
         */
        public static final String DOWNLOADED_SIZE = "downloadedSize";
        /**
         * This column is a {@link Uri} that points to the downloaded local file
         * when {@link #STATE} is {@link AttachmentState#SAVED}. This value is
         * undefined in any other state.
         */
        public static final String CONTENT_URI = "contentUri";
        /**
         * This column is a {@link Uri} that points to a local thumbnail file
         * for the attachment. Providers that do not support downloading
         * attachment thumbnails may leave this null.
         */
        public static final String THUMBNAIL_URI = "thumbnailUri";
        /**
         * This column is an {@link Intent} to launch a preview activity that
         * allows the user to efficiently view an attachment without having to
         * first download the entire file. Providers that do not support
         * previewing attachments may leave this null. The intent is represented
         * as a byte-array blob generated by writing an Intent to a parcel and
         * then marshaling that parcel.
         */
        public static final String PREVIEW_INTENT = "previewIntent";

        private AttachmentColumns() {}
    }

    public static int getMailMaxAttachmentSize(String account) {
        // TODO: query the account to see what the max attachment size is?
        return 5 * 1024 * 1024;
    }

    public static String getAttachmentTypeSetting() {
        // TODO: query the account to see what kinds of attachments it supports?
        return "com.google.android.gm.allowAddAnyAttachment";
    }

    public static void incrementRecipientsTimesContacted(Context context, String addressString) {
        DataUsageStatUpdater statsUpdater = new DataUsageStatUpdater(context);
        ArrayList<String> recipients = new ArrayList<String>();
        String[] addresses = TextUtils.split(addressString, EMAIL_SEPARATOR_PATTERN);
        for (String address : addresses) {
            recipients.add(address);
        }
        statsUpdater.updateWithAddress(recipients);
    }

    public static final String[] UNDO_PROJECTION = {
        ConversationColumns.MESSAGE_LIST_URI
    };
    public static final int UNDO_MESSAGE_LIST_COLUMN = 0;

    // Parameter used to indicate the sequence number for an undoable operation
    public static final String SEQUENCE_QUERY_PARAMETER = "seq";

    /**
     * Settings for auto advancing when the current conversation has been destroyed.
     */
    public static final class AutoAdvance {
        /** No setting specified. */
        public static final int UNSET = 0;
        /** Go to the older message (if available) */
        public static final int OLDER = 1;
        /** Go to the newer message (if available) */
        public static final int NEWER = 2;
        /** Go back to conversation list*/
        public static final int LIST = 3;
    }

    public static final class SnapHeaderValue {
        public static final int ALWAYS = 0;
        public static final int PORTRAIT_ONLY = 1;
        public static final int NEVER = 2;
    }

    public static final class MessageTextSize {
        public static final int TINY = -2;
        public static final int SMALL = -1;
        public static final int NORMAL = 0;
        public static final int LARGE = 1;
        public static final int HUGE = 2;
    }

    public static final class DefaultReplyBehavior {
        public static final int REPLY = 0;
        public static final int REPLY_ALL = 1;
    }

    /**
     * Action for an intent used to update/create new notifications.  The mime type of this
     * intent should be set to the mimeType of the account that is generating this notification.
     * An intent of this action is required to have the following extras:
     * {@link UpdateNotificationExtras#EXTRA_FOLDER} {@link UpdateNotificationExtras#EXTRA_ACCOUNT}
     */
    public static final String ACTION_UPDATE_NOTIFICATION =
            "com.android.mail.action.update_notification";

    public static final class UpdateNotificationExtras {
        /**
         * Parcelable extra containing a {@link Uri} to a {@link Folder}
         */
        public static final String EXTRA_FOLDER = "notification_extra_folder";

        /**
         * Parcelable extra containing a {@link Uri} to an {@link Account}
         */
        public static final String EXTRA_ACCOUNT = "notification_extra_account";

        /**
         * Integer extra containing the update unread count for the account/folder.
         * If this value is 0, the UI will not block the intent to allow code to clear notifications
         * to run.
         */
        public static final String EXTRA_UPDATED_UNREAD_COUNT = "notification_updated_unread_count";
    }

    public static final class EditSettingsExtras {
        /**
         * Parcelable extra containing account for which the user wants to
         * modify settings
         */
        public static final String EXTRA_ACCOUNT = "extra_account";

        /**
         * Parcelable extra containing folder for which the user wants to
         * modify settings
         */
        public static final String EXTRA_FOLDER = "extra_folder";

        /**
         * Boolean extra which is set true if the user wants to "manage folders"
         */
        public static final String EXTRA_MANAGE_FOLDERS = "extra_manage_folders";
    }
}
