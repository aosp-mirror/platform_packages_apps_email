package com.android.emailcommon.provider;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.support.v4.util.LongSparseArray;

import com.android.mail.utils.LogUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link EmailContent}-like class for the MessageMove table.
 */
public class MessageMove extends MessageChangeLogTable {
    /** Logging tag. */
    public static final String LOG_TAG = "MessageMove";

    /** The name for this table in the database. */
    public static final String TABLE_NAME = "MessageMove";

    /** The path for the URI for interacting with message moves. */
    public static final String PATH = "messageMove";

    /** The URI for dealing with message move data. */
    public static Uri CONTENT_URI;

    // DB columns.
    /** Column name for a foreign key into Mailbox for the folder the message is moving from. */
    public static final String SRC_FOLDER_KEY = "srcFolderKey";
    /** Column name for a foreign key into Mailbox for the folder the message is moving to. */
    public static final String DST_FOLDER_KEY = "dstFolderKey";
    /** Column name for the server-side id for srcFolderKey. */
    public static final String SRC_FOLDER_SERVER_ID = "srcFolderServerId";
    /** Column name for the server-side id for dstFolderKey. */
    public static final String DST_FOLDER_SERVER_ID = "dstFolderServerId";

    /**
     * Projection for a query to get all columns necessary for an actual move.
     */
    private static final class ProjectionMoveQuery {
        public static final int COLUMN_ID = 0;
        public static final int COLUMN_MESSAGE_KEY = 1;
        public static final int COLUMN_SERVER_ID = 2;
        public static final int COLUMN_SRC_FOLDER_KEY = 3;
        public static final int COLUMN_DST_FOLDER_KEY = 4;
        public static final int COLUMN_SRC_FOLDER_SERVER_ID = 5;
        public static final int COLUMN_DST_FOLDER_SERVER_ID = 6;

        public static final String[] PROJECTION = new String[] {
                ID, MESSAGE_KEY, SERVER_ID,
                SRC_FOLDER_KEY, DST_FOLDER_KEY,
                SRC_FOLDER_SERVER_ID, DST_FOLDER_SERVER_ID
        };
    }

    // The actual fields.
    private final long mSrcFolderKey;
    private long mDstFolderKey;
    private final String mSrcFolderServerId;
    private String mDstFolderServerId;

    private MessageMove(final long messageKey,final String serverId, final long id,
            final long srcFolderKey, final long dstFolderKey,
            final String srcFolderServerId, final String dstFolderServerId) {
        super(messageKey, serverId, id);
        mSrcFolderKey = srcFolderKey;
        mDstFolderKey = dstFolderKey;
        mSrcFolderServerId = srcFolderServerId;
        mDstFolderServerId = dstFolderServerId;
    }

    public final long getSourceFolderKey() {
        return mSrcFolderKey;
    }

    public final String getSourceFolderId() {
        return mSrcFolderServerId;
    }

    public final String getDestFolderId() {
        return mDstFolderServerId;
    }

    /**
     * Initialize static state for this class.
     */
    public static void init() {
        CONTENT_URI = EmailContent.CONTENT_URI.buildUpon().appendEncodedPath(PATH).build();
    }

    /**
     * Get the final moves that we want to upsync to the server, setting the status in the DB for
     * all rows to {@link #STATUS_PROCESSING} that are being updated and to {@link #STATUS_FAILED}
     * for any old updates.
     * Messages whose sequence of pending moves results in a no-op (i.e. the message has been moved
     * back to its original folder) have their moves cleared from the DB without any upsync.
     * @param context A {@link Context}.
     * @param accountId The account we want to update.
     * @return The final moves to send to the server, or null if there are none.
     */
    public static List<MessageMove> getMoves(final Context context, final long accountId) {
        final ContentResolver cr = context.getContentResolver();
        final Cursor c = getCursor(cr, CONTENT_URI, ProjectionMoveQuery.PROJECTION, accountId);
        if (c == null) {
            return null;
        }

        // Collapse any rows in the cursor that are acting on the same message. We know the cursor
        // returned by getRowsToProcess is ordered from oldest to newest, and we use this fact to
        // get the original and final folder for the message.
        LongSparseArray<MessageMove> movesMap = new LongSparseArray();
        try {
            while (c.moveToNext()) {
                final long id = c.getLong(ProjectionMoveQuery.COLUMN_ID);
                final long messageKey = c.getLong(ProjectionMoveQuery.COLUMN_MESSAGE_KEY);
                final String serverId = c.getString(ProjectionMoveQuery.COLUMN_SERVER_ID);
                final long srcFolderKey = c.getLong(ProjectionMoveQuery.COLUMN_SRC_FOLDER_KEY);
                final long dstFolderKey = c.getLong(ProjectionMoveQuery.COLUMN_DST_FOLDER_KEY);
                final String srcFolderServerId =
                        c.getString(ProjectionMoveQuery.COLUMN_SRC_FOLDER_SERVER_ID);
                final String dstFolderServerId =
                        c.getString(ProjectionMoveQuery.COLUMN_DST_FOLDER_SERVER_ID);
                final MessageMove existingMove = movesMap.get(messageKey);
                if (existingMove != null) {
                    if (existingMove.mLastId >= id) {
                        LogUtils.w(LOG_TAG, "Moves were not in ascending id order");
                    }
                    if (!existingMove.mDstFolderServerId.equals(srcFolderServerId) ||
                            existingMove.mDstFolderKey != srcFolderKey) {
                        LogUtils.w(LOG_TAG, "existing move's dst not same as this move's src");
                    }
                    existingMove.mDstFolderKey = dstFolderKey;
                    existingMove.mDstFolderServerId = dstFolderServerId;
                    existingMove.mLastId = id;
                } else {
                    movesMap.put(messageKey, new MessageMove(messageKey, serverId, id,
                            srcFolderKey, dstFolderKey, srcFolderServerId, dstFolderServerId));
                }
            }
        } finally {
            c.close();
        }

        // Prune any no-op moves (i.e. messages that have been moved back to the initial folder).
        final int moveCount = movesMap.size();
        final long[] unmovedMessages = new long[moveCount];
        int unmovedMessagesCount = 0;
        final ArrayList<MessageMove> moves = new ArrayList(moveCount);
        for (int i = 0; i < movesMap.size(); ++i) {
            final MessageMove move = movesMap.valueAt(i);
            if (move.mSrcFolderKey == move.mDstFolderKey) {
                unmovedMessages[unmovedMessagesCount] = move.mMessageKey;
                ++unmovedMessagesCount;
            } else {
                moves.add(move);
            }
        }
        if (unmovedMessagesCount != 0) {
            deleteRowsForMessages(cr, CONTENT_URI, unmovedMessages, unmovedMessagesCount);
        }
        if (moves.isEmpty()) {
            return null;
        }
        return moves;
    }

    /**
     * Clean up the table to reflect a successful set of upsyncs.
     * @param cr A {@link ContentResolver}
     * @param messageKeys The messages to update.
     * @param count The number of messages.
     */
    public static void upsyncSuccessful(final ContentResolver cr, final long[] messageKeys,
            final int count) {
        deleteRowsForMessages(cr, CONTENT_URI, messageKeys, count);
    }

    /**
     * Clean up the table to reflect upsyncs that need to be retried.
     * @param cr A {@link ContentResolver}
     * @param messageKeys The messages to update.
     * @param count The number of messages.
     */
    public static void upsyncRetry(final ContentResolver cr, final long[] messageKeys,
            final int count) {
        retryMessages(cr, CONTENT_URI, messageKeys, count);
    }

    /**
     * Clean up the table to reflect upsyncs that failed and need to be reverted.
     * @param cr A {@link ContentResolver}
     * @param messageKeys The messages to update.
     * @param count The number of messages.
     */
    public static void upsyncFail(final ContentResolver cr, final long[] messageKeys,
            final int count) {
        failMessages(cr, CONTENT_URI, messageKeys, count);
    }
}
