package com.android.messaging.util;

import android.content.Context;
import android.os.AsyncTask;

import com.android.messaging.datamodel.data.ConversationParticipantsData;
import com.android.messaging.datamodel.data.ParticipantData;

import org.whispersystems.whisperpush.WhisperPush;
import org.whispersystems.whisperpush.directory.Directory;

import java.util.ArrayList;
import java.util.List;

import static org.whispersystems.whisperpush.directory.Directory.STATE_ALL_CONTACTS_SECURE;
import static org.whispersystems.whisperpush.directory.Directory.STATE_ALL_CONTACTS_UNSECURE;
import static org.whispersystems.whisperpush.directory.Directory.STATE_CONTACTS_MIXED;

/**
 * Provides API for secure messaging (currently - via WhisperPush)
 */
public class SecureMessagingHelper {
    private static final int STATE_UNKNOWN = 0;

    private Context mContext;
    private WhisperPush mWhisperPush;
    private SecurityMessagingCallback mCallback;
    private int mParticipantsSecurityState = STATE_UNKNOWN;
    private boolean mIsUpdatingParticipantsSecurity;

    public interface SecurityMessagingCallback {
        void onParticipantsSecurityUpdated();
        ConversationParticipantsData getParticipantsData();
    }

    public SecureMessagingHelper(Context context, SecurityMessagingCallback callback) {
        mContext = context;
        mWhisperPush = WhisperPush.getInstance(context);
        mCallback = callback;
    }

    public void checkIfAllParticipantsSecuredAsync() {
        mIsUpdatingParticipantsSecurity = true;
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                checkIfAllParticipantsSecured();
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                super.onPostExecute(result);
                mCallback.onParticipantsSecurityUpdated();
                mIsUpdatingParticipantsSecurity = false;
            }
        }.execute();

    }

    public void checkIfAllParticipantsSecured() {
        if (mWhisperPush.isSecureMessagingActive()) {
            List<String> participantsSendDestinations = getSendDestinations();

            if (participantsSendDestinations != null) {
                mParticipantsSecurityState
                        = Directory.getInstance(mContext).isAllActiveNumbers(participantsSendDestinations);
            } else {
                mParticipantsSecurityState = STATE_UNKNOWN;
            }
        } else {
            mParticipantsSecurityState = STATE_UNKNOWN;
        }
    }

    private List<String> getSendDestinations() {
        ConversationParticipantsData participantsData = mCallback.getParticipantsData();

        if (participantsData != null) {
            List<String> participantsSendDestinations = new ArrayList<String>();

            for (ParticipantData participant : participantsData.getParticipantListExcludingSelf()){
                participantsSendDestinations.add(participant.getSendDestination());
            }

            return participantsSendDestinations;
        } else {
            return null;
        }
    }

    public boolean isUpdatingParticipantsSecurity() {
        return mIsUpdatingParticipantsSecurity;
    }

    public boolean isAllParticipantsSecured() {
        return mParticipantsSecurityState == STATE_ALL_CONTACTS_SECURE;
    }

    public boolean isParticipantsMixed() {
        return mParticipantsSecurityState == STATE_CONTACTS_MIXED;
    }
}
