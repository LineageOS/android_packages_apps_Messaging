package com.cyanogenmod.messaging.quickmessage;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

public class NotificationInfo implements Parcelable {
    public String mSenderName;
    public String mSenderNumber;
    public String mMessage;
    public long mTimeMillis;
    public String mConversationId;
    public Uri mSenderContactUri;
    public Uri mSenderAvatarUri;
    public long mSenderContactId;
    public String mSelfParticipantId;
    public Uri mSelfAvatarUri;
    public int mSubId;
    public String mSenderNormalizedDestination;

    public NotificationInfo(String senderName, String senderNumber, Uri senderContactUri,
            Uri senderAvatarUri, long senderContactId, String message, long timeMillis,
            String conversationId, String selfParticipantId, Uri selfAvatarUri, int subId,
            String senderNormalizedDestination) {
        mSenderName = senderName;
        mSenderNumber = senderNumber;
        mSenderContactUri = senderContactUri;
        mMessage = message;
        mTimeMillis = timeMillis;
        mConversationId = conversationId;
        mSenderAvatarUri = senderAvatarUri;
        mSenderContactId = senderContactId;
        mSelfParticipantId = selfParticipantId;
        mSelfAvatarUri = selfAvatarUri;
        mSubId = subId;
        mSenderNormalizedDestination = senderNormalizedDestination;
    }

    public NotificationInfo(String senderName, String senderNumber, Uri senderContactUri,
            Uri senderAvatarUri, long senderContactId, CharSequence message, long timeMillis,
            String conversationId, String selfParticipantId, Uri selfAvatarUri, int subId,
            String senderNormalizedDestination) {
        this(senderName, senderNumber, senderContactUri, senderAvatarUri, senderContactId,
                TextUtils.isEmpty(message) ? null : message.toString(), timeMillis,
                conversationId, selfParticipantId, selfAvatarUri,
                subId, senderNormalizedDestination);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel arg0, int arg1) {
        arg0.writeString(mSenderName);
        arg0.writeString(mSenderNumber);
        if (mSenderContactUri == null) {
            arg0.writeInt(0);
        } else {
            arg0.writeInt(1);
            mSenderContactUri.writeToParcel(arg0, arg1);
        }
        if (mSenderAvatarUri == null) {
            arg0.writeInt(0);
        } else {
            arg0.writeInt(1);
            mSenderAvatarUri.writeToParcel(arg0, arg1);
        }
        if (mSelfAvatarUri == null) {
            arg0.writeInt(0);
        } else {
            arg0.writeInt(1);
            mSelfAvatarUri.writeToParcel(arg0, arg1);
        }
        arg0.writeLong(mSenderContactId);
        arg0.writeString(mMessage);
        arg0.writeLong(mTimeMillis);
        arg0.writeString(mConversationId);
        arg0.writeString(mSelfParticipantId);
        arg0.writeInt(mSubId);
        arg0.writeString(mSenderNormalizedDestination);
    }

    public NotificationInfo(Parcel in) {
        mSenderName = in.readString();
        mSenderNumber = in.readString();
        if (in.readInt() != 0) {
            mSenderContactUri = Uri.CREATOR.createFromParcel(in);
        }
        if (in.readInt() != 0) {
            mSenderAvatarUri = Uri.CREATOR.createFromParcel(in);
        }
        if (in.readInt() != 0) {
            mSelfAvatarUri = Uri.CREATOR.createFromParcel(in);
        }
        mSenderContactId = in.readLong();
        mMessage = in.readString();
        mTimeMillis = in.readLong();
        mConversationId = in.readString();
        mSelfParticipantId = in.readString();
        mSubId = in.readInt();
        mSenderNormalizedDestination = in.readString();
    }

    public static final Parcelable.Creator<NotificationInfo> CREATOR =
            new Parcelable.Creator<NotificationInfo>() {
        public NotificationInfo createFromParcel(Parcel in) {
            return new NotificationInfo(in);
        }

        public NotificationInfo[] newArray(int size) {
            return new NotificationInfo[size];
        }
    };
}
