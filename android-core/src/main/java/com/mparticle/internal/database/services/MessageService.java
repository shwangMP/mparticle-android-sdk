package com.mparticle.internal.database.services;

import android.content.ContentValues;
import android.database.Cursor;

import androidx.annotation.Nullable;

import com.google.android.gms.common.internal.Objects;
import com.mparticle.internal.Constants;
import com.mparticle.internal.Logger;
import com.mparticle.internal.MessageManager;
import com.mparticle.internal.database.MPDatabase;
import com.mparticle.internal.database.tables.MessageTable;
import com.mparticle.internal.listeners.InternalListenerManager;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class MessageService extends MessageTable {

    private final static String[] prepareSelection = new String[]{"_id", MessageTableColumns.MESSAGE, MessageTableColumns.CREATED_AT, MessageTableColumns.STATUS, MessageTableColumns.SESSION_ID, MessageTableColumns.MP_ID, MessageTableColumns.DATAPLAN_ID, MessageTableColumns.DATAPLAN_VERSION};
    private final static String prepareOrderBy =  MessageTableColumns._ID + " asc";

    private static String getSessionHistorySelection(boolean includesMpid) {
        return String.format(
                "(%s = %d) and (%s != ?) and (%s " + (includesMpid ? " = ?" : "!= ?") + ")",
                MessageTableColumns.STATUS,
                Constants.Status.UPLOADED,
                MessageTableColumns.SESSION_ID,
                MessageTableColumns.MP_ID);
    }

    public MessageService() {
    }

    public static List<ReadyMessage> getSessionHistory(MPDatabase database, String currentSessionId) {
        return getSessionHistory(database, currentSessionId, false, Constants.TEMPORARY_MPID);
    }

    static List<ReadyMessage> getSessionHistory(MPDatabase database, String currentSessionId, boolean includes, long mpid){
        String[] selectionArgs = new String[]{currentSessionId, String.valueOf(mpid)};
        Cursor readyMessagesCursor = null;
        List<ReadyMessage> readyMessages = new ArrayList<ReadyMessage>();
        try {
            readyMessagesCursor = database.query(
                    MessageTableColumns.TABLE_NAME,
                    prepareSelection,
                    getSessionHistorySelection(includes),
                    selectionArgs,
                    null,
                    null,
                    prepareOrderBy, "100");
            int messageIdIndex = readyMessagesCursor.getColumnIndex(MessageTableColumns._ID);
            int messageIndex = readyMessagesCursor.getColumnIndex(MessageTableColumns.MESSAGE);
            int sessionIdIndex = readyMessagesCursor.getColumnIndex(MessageTableColumns.SESSION_ID);
            int messageMpidIndex = readyMessagesCursor.getColumnIndex(MessageTableColumns.MP_ID);
            int dataplanIdIndex = readyMessagesCursor.getColumnIndex(MessageTableColumns.DATAPLAN_ID);
            int dataplanVersinIndex = readyMessagesCursor.getColumnIndex(MessageTableColumns.DATAPLAN_VERSION);
            while (readyMessagesCursor.moveToNext()) {
                String sessionId = readyMessagesCursor.getString(sessionIdIndex);
                int messageId = readyMessagesCursor.getInt(messageIdIndex);
                String message = readyMessagesCursor.getString(messageIndex);
                long messageMpid = readyMessagesCursor.getLong(messageMpidIndex);
                String dataplanId = readyMessagesCursor.getString(dataplanIdIndex);
                Integer dataplanVersion = null;
                if (!readyMessagesCursor.isNull(dataplanVersinIndex)) {
                    dataplanVersion = readyMessagesCursor.getInt(dataplanVersinIndex);
                }
                ReadyMessage readyMessage = new ReadyMessage(messageMpid, sessionId, messageId, message, dataplanId, dataplanVersion);
                InternalListenerManager.getListener().onCompositeObjects(readyMessagesCursor, readyMessage);
                readyMessages.add(readyMessage);
            }
        }
        finally {
            if (readyMessagesCursor != null && !readyMessagesCursor.isClosed()) {
                readyMessagesCursor.close();
            }
        }
        return readyMessages;
    }

    public static int deleteOldMessages(MPDatabase database, String currentSessionId){
        String[] selectionArgs = new String[]{currentSessionId, String.valueOf(Constants.TEMPORARY_MPID)};
        return database.delete(
                MessageTableColumns.TABLE_NAME,
                getSessionHistorySelection(false),
                selectionArgs);
    }

    /**
     * Will return all Messages for upload, except for those with MP_ID == Constants.TEMPORARY_MPID,
     * useful in non-testing context.
     */
    public static List<ReadyMessage> getMessagesForUpload(MPDatabase database) {
        return getMessagesForUpload(database, false, Constants.TEMPORARY_MPID);
    }

    static List<ReadyMessage> getMessagesForUpload(MPDatabase database, boolean includes, long mpid){
        Cursor readyMessagesCursor = null;
        List<ReadyMessage> readyMessages = new ArrayList<ReadyMessage>();
        try {
            readyMessagesCursor = database.query(
                    MessageTableColumns.TABLE_NAME,
                    null,
                    MessageTableColumns.STATUS + " != ? and " + MessageTableColumns.CREATED_AT + " < " + System.currentTimeMillis() + " and " + MessageTableColumns.MP_ID + (includes ? " = ?" : " != ?"),
                    new String[]{Integer.toString(Constants.Status.UPLOADED), String.valueOf(mpid)},
                    null,
                    null,
                    prepareOrderBy, "100");
            int messageIdIndex = readyMessagesCursor.getColumnIndex(MessageTableColumns._ID);
            int messageIndex = readyMessagesCursor.getColumnIndex(MessageTableColumns.MESSAGE);
            int sessionIdIndex = readyMessagesCursor.getColumnIndex(MessageTableColumns.SESSION_ID);
            int messageMpidIndex = readyMessagesCursor.getColumnIndex(MessageTableColumns.MP_ID);
            int dataplanIdIndex = readyMessagesCursor.getColumnIndex(MessageTableColumns.DATAPLAN_ID);
            int dataplanVersinIndex = readyMessagesCursor.getColumnIndex(MessageTableColumns.DATAPLAN_VERSION);
            while (readyMessagesCursor.moveToNext()) {
                String sessionId = readyMessagesCursor.getString(sessionIdIndex);
                int messageId = readyMessagesCursor.getInt(messageIdIndex);
                String message = readyMessagesCursor.getString(messageIndex);
                long messageMpid = readyMessagesCursor.getLong(messageMpidIndex);
                String dataplanId = readyMessagesCursor.getString(dataplanIdIndex);
                Integer dataplanVersion = null;
                if (!readyMessagesCursor.isNull(dataplanVersinIndex)) {
                    dataplanVersion = readyMessagesCursor.getInt(dataplanVersinIndex);
                }
                ReadyMessage readyMessage = new ReadyMessage(messageMpid, sessionId, messageId, message, dataplanId, dataplanVersion);
                InternalListenerManager.getListener().onCompositeObjects(readyMessagesCursor, readyMessage);
                readyMessages.add(readyMessage);
            }
        }
        finally {
            if (readyMessagesCursor != null && !readyMessagesCursor.isClosed()) {
                readyMessagesCursor.close();
            }
        }
        return readyMessages;
    }

    public static int cleanupMessages(MPDatabase database) {
        return database.delete(MessageTableColumns.TABLE_NAME, "length(" + MessageTableColumns.MESSAGE + ") > " + Constants.LIMIT_MAX_MESSAGE_SIZE, null);
    }


    /**
     * The following get*Query methods were once static fields, but in order to save on app startup time, they're
     * now created as needed.
     */
    /**
     * The beginning of the delete query used to clear the uploads table after a successful upload.
     */
    private static String getDeletableMessagesQuery() {
        return String.format(
                "(%s='NO-SESSION')",
                MessageTableColumns.SESSION_ID);
    }

    public static int markMessagesAsUploaded(MPDatabase database, int messageId) {
        String[] whereArgs = new String[]{Integer.toString(messageId), String.valueOf(Constants.TEMPORARY_MPID)};
        ContentValues contentValues = new ContentValues();
        contentValues.put(MessageTableColumns.STATUS, Constants.Status.UPLOADED);
        return database.update(MessageTableColumns.TABLE_NAME, contentValues, MessageTableColumns._ID + " <= ? and " + MessageTableColumns.MP_ID + " != ? ", whereArgs);
    }

    /**
     * Delete a message that has been uploaded in session history.
     */
    public static int deleteMessages(MPDatabase database, int messageId) {
        String[] whereArgs = new String[]{Integer.toString(messageId), String.valueOf(Constants.TEMPORARY_MPID)};
        return database.delete(MessageTableColumns.TABLE_NAME, MessageTableColumns._ID + " <= ? and " + MessageTableColumns.MP_ID + " != ?", whereArgs);
    }

    public static void insertMessage(MPDatabase db, String apiKey, MessageManager.BaseMPMessage message, long mpId, String dataplanId, Integer dataplanVersion) throws JSONException {
        ContentValues contentValues = new ContentValues();
        contentValues.put(MessageTableColumns.API_KEY, apiKey);
        contentValues.put(MessageTableColumns.CREATED_AT, message.getLong(Constants.MessageKey.TIMESTAMP));
        String sessionID = message.getSessionId();
        contentValues.put(MessageTableColumns.SESSION_ID, sessionID);
        contentValues.put(MessageTableColumns.MP_ID, mpId);
        contentValues.put(MessageTableColumns.DATAPLAN_ID, dataplanId);
        contentValues.put(MessageTableColumns.DATAPLAN_VERSION, dataplanVersion);
        if (Constants.NO_SESSION_ID.equals(sessionID)) {
            message.remove(Constants.MessageKey.SESSION_ID);
        }
        String messageString = message.toString();
        if (messageString.length() > Constants.LIMIT_MAX_MESSAGE_SIZE) {
            Logger.error("Message logged of size " + messageString.length() + " that exceeds maximum safe size of " + Constants.LIMIT_MAX_MESSAGE_SIZE + " bytes.");
            return;
        }
        contentValues.put(MessageTableColumns.MESSAGE, messageString);

        if (Constants.MessageType.FIRST_RUN.equals(message.getString(Constants.MessageKey.TYPE))) {
            // Force the first run message to be parsed immediately.
            contentValues.put(MessageTableColumns.STATUS, Constants.Status.BATCH_READY);
        } else {
            contentValues.put(MessageTableColumns.STATUS, Constants.Status.READY);
        }
        InternalListenerManager.getListener().onCompositeObjects(message, contentValues);
        db.insert(MessageTableColumns.TABLE_NAME, null, contentValues);
    }

    public static class ReadyMessage {
        private long mpid;
        private String sessionId;
        private int messageId;
        private String message;
        private String dataplanId;
        private Integer dataplanVersion;

        private ReadyMessage(long mpid, String sessionId, int messageId, String message, String dataplanId, Integer dataplanVersion) {
            this.mpid = mpid;
            this.sessionId = sessionId;
            this.messageId = messageId;
            this.message = message;
            this.dataplanId = dataplanId;
            this.dataplanVersion = dataplanVersion;
        }

        public long getMpid() {
            return mpid;
        }

        public String getSessionId() {
            return sessionId;
        }

        public int getMessageId() {
            return messageId;
        }

        public String getMessage() {
            return message;
        }

        public String getDataplanId() {
            return dataplanId;
        }

        public Integer getDataplanVersion() {
            return dataplanVersion;
        }
    }
}
