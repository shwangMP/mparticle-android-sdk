package com.mparticle.internal;

import android.content.Context;
import android.os.Looper;
import android.os.Message;

import com.mparticle.MParticle;
import com.mparticle.internal.Constants.MessageKey;
import com.mparticle.internal.Constants.MessageType;
import com.mparticle.internal.database.services.MParticleDBManager;
import com.mparticle.internal.database.tables.SessionTable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/* package-private */ class MessageHandler extends BaseHandler {

    private final Context mContext;

    MParticleDBManager mMParticleDBManager;

    public static final int STORE_MESSAGE = 0;
    public static final int UPDATE_SESSION_ATTRIBUTES = 1;
    public static final int UPDATE_SESSION_END = 2;
    public static final int CREATE_SESSION_END_MESSAGE = 3;
    public static final int END_ORPHAN_SESSIONS = 4;
    public static final int STORE_BREADCRUMB = 5;
    public static final int STORE_REPORTING_MESSAGE_LIST = 9;
    public static final int REMOVE_USER_ATTRIBUTE = 10;
    public static final int SET_USER_ATTRIBUTE = 11;
    public static final int INCREMENT_USER_ATTRIBUTE = 12;
    public static final int INSTALL_REFERRER_UPDATED = 13;
    public static final int CLEAR_MESSAGES_FOR_UPLOAD = 14;
    public static final int STORE_ALIAS_MESSAGE = 15;

    private final MessageManagerCallbacks mMessageManagerCallbacks;
    String mDataplanId;
    Integer mDataplanVersion;

    /**
     * for unit testing only
     */
    MessageHandler(MessageManagerCallbacks messageManager, Context context, MParticleDBManager dbManager, String dataplanId, Integer dataplanVersion) {
        mMessageManagerCallbacks = messageManager;
        mContext = context;
        mMParticleDBManager = dbManager;
        mDataplanId = dataplanId;
        mDataplanVersion = dataplanVersion;
    }

    public MessageHandler(Looper looper, MessageManagerCallbacks messageManager, Context context, MParticleDBManager dbManager, String dataplanId, Integer dataplanVersion) {
        super(looper);
        mMessageManagerCallbacks = messageManager;
        mContext = context;
        mMParticleDBManager = dbManager;
        mDataplanId = dataplanId;
        mDataplanVersion = dataplanVersion;
    }

    boolean databaseAvailable() {
        try {
            return mMParticleDBManager.getDatabase() != null;
        } catch (Exception ex) {
            Logger.error("Database unavailable.");
            return false;
        }
    }

    @Override
    public void handleMessageImpl(Message msg) {
        try {
            if (!databaseAvailable()) {
                return;
            }
            mMessageManagerCallbacks.delayedStart();
        }catch (Exception e) {
            Logger.verbose(e.toString());
        }
        switch (msg.what) {
            case STORE_MESSAGE:
                try {
                    MessageManager.BaseMPMessage message = (MessageManager.BaseMPMessage) msg.obj;
                    message.put(MessageKey.STATE_INFO_KEY, MessageManager.getStateInfo());
                    String messageType = message.getString(MessageKey.TYPE);
                    // Handle the special case of session-start by creating the
                    // session record first.
                    if (MessageType.SESSION_START.equals(messageType)) {
                        dbInsertSession(message);
                    }else{
                        mMParticleDBManager.updateSessionEndTime(message.getSessionId(), message.getLong(MessageKey.TIMESTAMP), 0);
                        message.put(Constants.MessageKey.ID, UUID.randomUUID().toString());
                    }
                    if (MessageType.ERROR.equals(messageType)){
                        mMParticleDBManager.appendBreadcrumbs(message);
                    }
                    try {
                        mMParticleDBManager.insertMessage(mMessageManagerCallbacks.getApiKey(), message, mDataplanId, mDataplanVersion);
                    } catch (MParticleApiClientImpl.MPNoConfigException e) {
                        Logger.error("Unable to process uploads, API key and/or API Secret are missing.");
                        return;
                    }
                    mMessageManagerCallbacks.checkForTrigger(message);

                } catch (Exception e) {
                    Logger.error(e, "Error saving message to mParticle DB.");
                }
                break;
            case INSTALL_REFERRER_UPDATED:
                try {
                    mMParticleDBManager.updateSessionInstallReferrer((String) msg.obj, mMessageManagerCallbacks.getDeviceAttributes().getAppInfo(mContext, true));
                }catch (Exception e) {
                    Logger.error(e, "Error updating session attributes in mParticle DB.");
                }
                break;
            case UPDATE_SESSION_ATTRIBUTES:
                try {
                    JSONObject sessionAttributes = (JSONObject) msg.obj;
                    String sessionId = sessionAttributes.getString(MessageKey.SESSION_ID);
                    String attributes = sessionAttributes.getString(MessageKey.ATTRIBUTES);
                    mMParticleDBManager.updateSessionAttributes(sessionId, attributes);
                } catch (Exception e) {
                    Logger.error(e, "Error updating session attributes in mParticle DB.");
                }
                break;
            case UPDATE_SESSION_END:
                try {
                    InternalSession session = (InternalSession) msg.obj;
                    mMParticleDBManager.updateSessionEndTime(session.mSessionID, session.mLastEventTime, session.getForegroundTime());
                } catch (Exception e) {
                    Logger.error(e, "Error updating session end time in mParticle DB.");
                }
                break;
            case CREATE_SESSION_END_MESSAGE:
                try {
                    Map.Entry<String, Set<Long>> entry = (Map.Entry<String, Set<Long>>) msg.obj;
                    MessageManager.BaseMPMessage endMessage = null;
                    String sessionId = entry.getKey();
                   try {
                       endMessage = mMParticleDBManager.getSessionForSessionEndMessage(sessionId, ((MessageManager)mMessageManagerCallbacks).getLocation(), entry.getValue());
                   }catch (JSONException jse){
                       Logger.warning("Failed to create mParticle session end message.");
                   }
                   if (endMessage != null) {
                       try {
                           Logger.verbose("Creating session end message for session ID: " + sessionId);
                           mMParticleDBManager.insertMessage(mMessageManagerCallbacks.getApiKey(), endMessage, mDataplanId, mDataplanVersion);
                           mMParticleDBManager.updateSessionStatus(sessionId, SessionTable.SessionStatus.CLOSED);
                       } catch (MParticleApiClientImpl.MPNoConfigException e) {
                           Logger.error("Unable to process uploads, API key and/or API Secret are missing.");
                           return;
                       }

                    } else {
                        Logger.error("Error creating session end, no entry for sessionId in mParticle DB.");
                    }
                    //1 means this came from ending the session
                    if (msg.arg1 == 1){
                        mMessageManagerCallbacks.endUploadLoop();
                    }
                } catch (Exception e) {
                    Logger.error(e, "Error creating session end message in mParticle DB.");
                }finally {

                }
                break;
            case END_ORPHAN_SESSIONS:
                try {
                    Logger.verbose("Ending orphaned sessions.");
                    // Find left-over sessions that exist during startup and end them.
                    Long mpid = (Long)msg.obj;
                    List<String> sessionIds = mMParticleDBManager.getOrphanSessionIds(mMessageManagerCallbacks.getApiKey());
                    for (String sessionId: sessionIds) {
                        Map.Entry<String, Set<Long>> entry = new HashMap.SimpleEntry<String, Set<Long>>(sessionId, Collections.singleton(mpid));
                        sendMessage(obtainMessage(MessageHandler.CREATE_SESSION_END_MESSAGE, 0, 0, entry));
                    }
                } catch (MParticleApiClientImpl.MPNoConfigException ex) {
                    Logger.error("Unable to process initialization, API key and or API Secret is missing.");
                } catch (Exception e) {
                    Logger.error(e, "Error processing initialization in mParticle DB.");
                }
                break;
            case STORE_BREADCRUMB:
                try {
                    MessageManager.BaseMPMessage message = (MessageManager.BaseMPMessage) msg.obj;
                    message.put(Constants.MessageKey.ID, UUID.randomUUID().toString());
                    try {
                        mMParticleDBManager.insertBreadcrumb(message, mMessageManagerCallbacks.getApiKey());
                    } catch (MParticleApiClientImpl.MPNoConfigException ex) {
                        Logger.error("Unable to process uploads, API key and/or API Secret are missing.");
                    }
                } catch (Exception e) {
                    Logger.error(e, "Error saving breadcrumb to mParticle DB.");
                }
                break;
            case STORE_REPORTING_MESSAGE_LIST:
                try{
                    MessageManager.ReportingMpidMessage reportingMessages = (MessageManager.ReportingMpidMessage)msg.obj;
                    mMParticleDBManager.insertReportingMessages((List<JsonReportingMessage>)reportingMessages.reportingMessages, reportingMessages.mpid);
                }catch (Exception e) {
                    Logger.verbose(e, "Error while inserting reporting messages: ", e.toString());
                }
                break;
            case REMOVE_USER_ATTRIBUTE:
                try {
                    mMParticleDBManager.removeUserAttribute((MParticleDBManager.UserAttributeRemoval)msg.obj, mMessageManagerCallbacks);
                }catch (Exception e) {
                    Logger.error(e, "Error while removing user attribute: ", e.toString());
                }
                break;
            case SET_USER_ATTRIBUTE:
                try {
                    setUserAttributes((MParticleDBManager.UserAttributeResponse)msg.obj);
                } catch (Exception e) {
                    Logger.error(e, "Error while setting user attribute: ", e.toString());
                }
                break;
            case INCREMENT_USER_ATTRIBUTE:
                try {
                    Map.Entry<String, Long> obj = (Map.Entry<String, Long>)msg.obj;
                    incrementUserAttribute(obj.getKey(), msg.arg1, obj.getValue());
                } catch (Exception e) {
                    Logger.error(e, "Error while incrementing user attribute: ", e.toString());
                }
                break;
            case CLEAR_MESSAGES_FOR_UPLOAD:
                mMessageManagerCallbacks.messagesClearedForUpload();
                break;
            case STORE_ALIAS_MESSAGE:
                try {
                    MessageManager.MPAliasMessage aliasMessage = (MessageManager.MPAliasMessage) msg.obj;
                    mMParticleDBManager.insertAliasRequest(mMessageManagerCallbacks.getApiKey(), aliasMessage);
                    if (MParticle.getInstance() != null) {
                        MParticle.getInstance().upload();
                    }
                } catch (MParticleApiClientImpl.MPNoConfigException ex) {
                    Logger.error("Unable to Alias Request, API key and or API Secret is missing");
                } catch (Exception ex) {
                    Logger.error("Error sending Alias Request");
                }
                break;
        }
    }

    void setUserAttributes(MParticleDBManager.UserAttributeResponse response) {
        List<MParticleDBManager.AttributionChange> attributionChanges = mMParticleDBManager.setUserAttribute(response);
        for (MParticleDBManager.AttributionChange attributionChange : attributionChanges) {
            logUserAttributeChanged(attributionChange);
        }
    }

    private void incrementUserAttribute(String key, int incrementValue, long mpId) {
        TreeMap<String, String> userAttributes = mMParticleDBManager.getUserAttributeSingles(mpId);

        if (!userAttributes.containsKey(key)) {
            TreeMap<String, List<String>> userAttributeList = mMParticleDBManager.getUserAttributeLists(mpId);
            if (userAttributeList.containsKey(key)) {
                Logger.error("Error while attempting to increment user attribute - existing attribute is a list, which can't be incremented.");
                return;
            }
        }
        String newValue = null;
        String currentValue = userAttributes.get(key);
        if (currentValue == null) {
            newValue = Integer.toString(incrementValue);
        } else {
            try {
                newValue = Integer.toString(Integer.parseInt(currentValue) + incrementValue);
            }catch (NumberFormatException nfe) {
                Logger.error("Error while attempting to increment user attribute - existing attribute is not a number.");
                return;
            }
        }
        MParticleDBManager.UserAttributeResponse wrapper = new MParticleDBManager.UserAttributeResponse();
        wrapper.attributeSingles = new HashMap<String, String>(1);
        wrapper.attributeSingles.put(key, newValue);
        wrapper.mpId = mpId;
        List<MParticleDBManager.AttributionChange> attributionChanges = mMParticleDBManager.setUserAttribute(wrapper);
        for (MParticleDBManager.AttributionChange attributeChange: attributionChanges) {
            logUserAttributeChanged(attributeChange);
        }
        if (MParticle.getInstance() != null && MParticle.getInstance().Internal().getKitManager() != null) {
            MParticle.getInstance().Internal().getKitManager().incrementUserAttribute(key, incrementValue, newValue, mpId);
        }
    }

    private void dbInsertSession(MessageManager.BaseMPMessage message) throws JSONException {
        try {
            DeviceAttributes deviceAttributes =  mMessageManagerCallbacks.getDeviceAttributes();
            mMParticleDBManager.insertSession(message, mMessageManagerCallbacks.getApiKey(), deviceAttributes.getAppInfo(mContext), deviceAttributes.getDeviceInfo(mContext));
        } catch (MParticleApiClientImpl.MPNoConfigException ex) {
            Logger.error("Unable to process uploads, API key and/or API Secret are missing.");
        }
    }

    private void logUserAttributeChanged(MParticleDBManager.AttributionChange attributionChange) {
        mMessageManagerCallbacks.logUserAttributeChangeMessage(
                attributionChange.getKey(),
                attributionChange.getNewValue(),
                attributionChange.getOldValue(),
                attributionChange.isDeleted(),
                attributionChange.isNewAttribute(),
                attributionChange.getTime(),
                attributionChange.getMpId());
    }

}
