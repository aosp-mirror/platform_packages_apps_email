package com.android.emailcommon.provider;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * {@link EmailContent}-like base class for change log tables.
 * Accounts that upsync message changes require a change log to track local changes between upsyncs.
 * A single instance of this class (or subclass) represents one change to upsync to the server.
 * This object may actually correspond to multiple rows in the table.
 * This class (and subclasses) also contains constants for the table columns and values stored in
 * the DB. The base class contains the ones common to all change logs.
 */
public abstract class MessageChangeLogTable {

    // DB columns. Note that this class (and subclasses) use some denormalized columns
    // (e.g. accountKey) for simplicity at query time and debugging ease.
    /** Column name for the row key; this is an autoincrement key. */
    public static final String ID = "_id";
    /** Column name for a foreign key into Message for the message that's moving. */
    public static final String MESSAGE_KEY = "messageKey";
    /** Column name for the server-side id for messageKey. */
    public static final String SERVER_ID = "messageServerId";
    /** Column name for a foreign key into Account for the message that's moving. */
    public static final String ACCOUNT_KEY = "accountKey";
    /** Column name for a status value indicating where we are with processing this move request. */
    public static final String STATUS = "status";

    // Status values.
    /** Status value indicating this move has not yet been unpsynced. */
    public static final int STATUS_NONE = 0;
    public static final String STATUS_NONE_STRING = String.valueOf(STATUS_NONE);
    /** Status value indicating this move is being upsynced right now. */
    public static final int STATUS_PROCESSING = 1;
    public static final String STATUS_PROCESSING_STRING = String.valueOf(STATUS_PROCESSING);
    /** Status value indicating this move failed to upsync. */
    public static final int STATUS_FAILED = 2;
    public static final String STATUS_FAILED_STRING = String.valueOf(STATUS_FAILED);

    /** Selection string for querying this table. */
    private static final String SELECTION_BY_ACCOUNT_KEY_AND_STATUS =
            ACCOUNT_KEY + "=? and " + STATUS + "=?";

    /** Selection string prefix for deleting moves for a set of messages. */
    private static final String SELECTION_BY_MESSAGE_KEYS_PREFIX = MESSAGE_KEY + " in (";

    protected final long mMessageKey;
    protected final String mServerId;
    protected long mLastId;

    protected MessageChangeLogTable(final long messageKey, final String serverId, final long id) {
        mMessageKey = messageKey;
        mServerId = serverId;
        mLastId = id;
    }

    public final long getMessageId() {
        return mMessageKey;
    }

    public final String getServerId() {
        return mServerId;
    }

    /**
     * Update status of all change entries for an account:
     * - {@link #STATUS_NONE} -> {@link #STATUS_PROCESSING}
     * - {@link #STATUS_PROCESSING} -> {@link #STATUS_FAILED}
     * @param cr A {@link ContentResolver}.
     * @param uri The content uri for this table.
     * @param accountId The account we want to update.
     * @return The number of change entries that are now in {@link #STATUS_PROCESSING}.
     */
    private static int startProcessing(final ContentResolver cr, final Uri uri,
            final String accountId) {
        final String[] args = new String[2];
        args[0] = accountId;
        final ContentValues cv = new ContentValues(1);

        // First mark anything that's still processing as failed.
        args[1] = STATUS_PROCESSING_STRING;
        cv.put(STATUS, STATUS_FAILED);
        cr.update(uri, cv, SELECTION_BY_ACCOUNT_KEY_AND_STATUS, args);

        // Now mark all unprocessed messages as processing.
        args[1] = STATUS_NONE_STRING;
        cv.put(STATUS, STATUS_PROCESSING);
        return cr.update(uri, cv, SELECTION_BY_ACCOUNT_KEY_AND_STATUS, args);
    }

    /**
     * Query for all move records that are in {@link #STATUS_PROCESSING}.
     * Note that this function assumes the underlying table uses an autoincrement id key: it assumes
     * that ascending id is the same as chronological order.
     * @param cr A {@link ContentResolver}.
     * @param uri The content uri for this table.
     * @param projection The projection to use for this query.
     * @param accountId The account we want to update.
     * @return A {@link android.database.Cursor} containing all rows, in id order.
     */
    private static Cursor getRowsToProcess(final ContentResolver cr, final Uri uri,
            final String[] projection, final String accountId) {
        final String[] args = { accountId, STATUS_PROCESSING_STRING };
        return cr.query(uri, projection, SELECTION_BY_ACCOUNT_KEY_AND_STATUS, args, ID + " ASC");
    }

    /**
     * Create a selection string for all messages in a set.
     * @param messageKeys The set of messages we're interested in.
     * @param count The number of messages we're interested in.
     * @return The selection string for these messages.
     */
    private static String getSelectionForMessages(final long[] messageKeys, final int count) {
        final StringBuilder sb = new StringBuilder(SELECTION_BY_MESSAGE_KEYS_PREFIX);
        for (int i = 0; i < count; ++i) {
            if (i != 0) {
                sb.append(",");
            }
            sb.append(messageKeys[i]);
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Delete all rows for a set of messages. Used to clear no-op changes (i.e. multiple rows for
     * a message that reverts it to the original state) and after successful upsync.
     * @param cr A {@link ContentResolver}.
     * @param uri The content uri for this table.
     * @param messageKeys The messages to clear.
     * @param count The number of message keys.
     * @return The number of rows deleted from the DB.
     */
    protected static int deleteRowsForMessages(final ContentResolver cr, final Uri uri,
            final long[] messageKeys, final int count) {
        if (count == 0) {
            return 0;
        }
        return cr.delete(uri, getSelectionForMessages(messageKeys, count), null);
    }

    /**
     * Set the status value for a set of messages.
     * @param cr A {@link ContentResolver}.
     * @param uri The {@link Uri} for the update.
     * @param messageKeys The messages to update.
     * @param count The number of messageKeys.
     * @param status The new status value for the messages.
     * @return The number of rows updated.
     */
    private static int updateStatusForMessages(final ContentResolver cr, final Uri uri,
            final long[] messageKeys, final int count, final int status) {
        if (count == 0) {
            return 0;
        }
        final ContentValues cv = new ContentValues(1);
        cv.put(STATUS, status);
        return cr.update(uri, cv, getSelectionForMessages(messageKeys, count), null);
    }

    /**
     * Set a set of messages to status = retry.
     * @param cr A {@link ContentResolver}.
     * @param uri The {@link Uri} for the update.
     * @param messageKeys The messages to update.
     * @param count The number of messageKeys.
     * @return The number of rows updated.
     */
    protected static int retryMessages(final ContentResolver cr, final Uri uri,
            final long[] messageKeys, final int count) {
        return updateStatusForMessages(cr, uri, messageKeys, count, STATUS_NONE);
    }

    /**
     * Set a set of messages to status = failed.
     * @param cr A {@link ContentResolver}.
     * @param uri The {@link Uri} for the update.
     * @param messageKeys The messages to update.
     * @param count The number of messageKeys.
     * @return The number of rows updated.
     */
    protected static int failMessages(final ContentResolver cr, final Uri uri,
            final long[] messageKeys, final int count) {
        return updateStatusForMessages(cr, uri, messageKeys, count, STATUS_FAILED);
    }

    /**
     * Start processing our table and get a {@link Cursor} for the rows to process.
     * @param cr A {@link ContentResolver}.
     * @param uri The {@link Uri} for the update.
     * @param projection The projection to use for our read.
     * @param accountId The account we're interested in.
     * @return A {@link Cursor} with the change log rows we're interested in.
     */
    protected static Cursor getCursor(final ContentResolver cr, final Uri uri,
            final String[] projection, final long accountId) {
        final String accountIdString = String.valueOf(accountId);
        if (startProcessing(cr, uri, accountIdString) <= 0) {
            return null;
        }
        return getRowsToProcess(cr, uri, projection, accountIdString);
    }
}
