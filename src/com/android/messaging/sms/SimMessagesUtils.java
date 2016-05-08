/*
 * Copyright (C) 2016 The CyanogenMod Project
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
package com.android.messaging.sms;

import android.content.Context;
import android.net.Uri;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import com.android.internal.telephony.EncodeException;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.cdma.sms.BearerData;
import com.android.internal.telephony.cdma.sms.CdmaSmsAddress;
import com.android.internal.telephony.cdma.sms.UserData;
import com.android.messaging.datamodel.data.ConversationMessageData;
import com.android.messaging.datamodel.data.ConversationParticipantsData;
import com.android.messaging.R;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;

import static android.telephony.SmsMessage.ENCODING_16BIT;
import static android.telephony.SmsMessage.ENCODING_7BIT;
import static android.telephony.SmsMessage.ENCODING_UNKNOWN;
import static android.telephony.SmsMessage.MAX_USER_DATA_BYTES;
import static android.telephony.SmsMessage.MAX_USER_DATA_SEPTETS;

public class SimMessagesUtils {

    private static final String TAG = SimMessagesUtils.class.getSimpleName();
    public static final int SUB_INVALID = -1;  //  for single card product
    public static final int SUB1 = 0;  // for DSDS product of slot one
    public static final int SUB2 = 1;  // for DSDS product of slot two

    public static final Uri ICC_URI = Uri.parse("content://sms/icc");
    public static final Uri ICC1_URI = Uri.parse("content://sms/icc1");
    public static final Uri ICC2_URI = Uri.parse("content://sms/icc2");
    private static final int TIMESTAMP_LENGTH = 7;  // See TS 23.040 9.2.3.11
    public static String WAPPUSH = "Browser Information"; // Wap push key

    /**
     * Return the icc uri according to subscription
     */
    public static Uri getIccUriBySlot(int slot) {
        switch (slot) {
            case SUB1:
                return ICC1_URI;
            case SUB2:
                return ICC2_URI;
            default:
                return ICC_URI;
        }
    }

    public static boolean isMultiSimEnabledMms() {
        return TelephonyManager.getDefault().isMultiSimEnabled();
    }

    /**
     * Return whether it has card no matter in DSDS or not
     */
    public static boolean hasIccCard() {
        return TelephonyManager.getDefault().hasIccCard();
    }

    /**
     * Return whether the card in the given slot is activated
     */
    public static boolean isIccCardActivated(int slot) {
        TelephonyManager tm = TelephonyManager.getDefault();
        final int simState = tm.getSimState(slot);
        return (simState != TelephonyManager.SIM_STATE_ABSENT)
                && (simState != TelephonyManager.SIM_STATE_UNKNOWN);
    }

    public static boolean copyToSim(ConversationMessageData messageData,
            ConversationParticipantsData participants, int subId) {
        String address;
        address = (participants.getOtherParticipant() == null) ?
                null :
                participants.getOtherParticipant().getDisplayDestination();
        if(TextUtils.isEmpty(address)) {
            return false;
        }
        if (SimMessagesUtils.isWapPushNumber(address)) {
            String[] number = address.split(":");
            address = number[0];
        }

        String text = messageData.getText();
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        long timestamp = messageData.getReceivedTimeStamp() != 0 ?
                messageData.getReceivedTimeStamp() :
                System.currentTimeMillis();

        SmsManager sm = SmsManager.getDefault();
        ArrayList<String> messages = sm.divideMessage(text);

        boolean ret = true;
        for (String message : messages) {
            byte pdu[] = null;
            int status;
            if (messageData.getIsIncoming()) {
                pdu = SimMessagesUtils.getDeliveryPdu(null, address,
                        message, timestamp, subId);
                status = SmsManager.STATUS_ON_ICC_READ;
            } else {
                pdu = SmsMessage.getSubmitPdu(null, address, message,
                        false, subId).encodedMessage;
                status = SmsManager.STATUS_ON_ICC_SENT;
            }
            ret &= TelephonyManager.getDefault().isMultiSimEnabled()
                    ? SmsManager.getSmsManagerForSubscriptionId(subId)
                    .copyMessageToIcc(null, pdu, status)
                    : sm.copyMessageToIcc(null, pdu, status);
            if (!ret) {
                break;
            }
        }
        return ret;
    }

    private static boolean isCDMAPhone(int subscription) {
        int activePhone = isMultiSimEnabledMms()
                ? TelephonyManager.getDefault().getCurrentPhoneType(subscription)
                : TelephonyManager.getDefault().getPhoneType();
        return activePhone == TelephonyManager.PHONE_TYPE_CDMA;
    }

    public static byte[] getDeliveryPdu(String scAddress, String destinationAddress, String message,
            long date, int subscription) {
        if (isCDMAPhone(subscription)) {
            return getCDMADeliveryPdu(scAddress, destinationAddress, message, date);
        } else {
            return getDeliveryPdu(scAddress, destinationAddress, message, date, null,
                    ENCODING_UNKNOWN);
        }
    }

    public static byte[] getCDMADeliveryPdu(String scAddress, String destinationAddress,
            String message, long date) {
        // Perform null parameter checks.
        if (message == null || destinationAddress == null) {
            Log.d(TAG, "getCDMADeliveryPdu,message =null");
            return null;
        }

        // according to submit pdu encoding as written in privateGetSubmitPdu

        // MTI = SMS-DELIVERY, UDHI = header != null
        byte[] header = null;
        byte mtiByte = (byte) (0x00 | (header != null ? 0x40 : 0x00));
        ByteArrayOutputStream headerStream = getDeliveryPduHeader(destinationAddress, mtiByte);

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream(MAX_USER_DATA_BYTES + 40);

        DataOutputStream dos = new DataOutputStream(byteStream);
        // int status,Status of message. See TS 27.005 3.1, "<stat>"

        /* 0 = "REC UNREAD" */
        /* 1 = "REC READ" */
        /* 2 = "STO UNSENT" */
        /* 3 = "STO SENT" */

        try {
            // int uTeleserviceID;
            int uTeleserviceID = 0; //.TELESERVICE_CT_WAP;// int
            dos.writeInt(uTeleserviceID);

            // unsigned char bIsServicePresent
            byte bIsServicePresent = 0;// byte
            dos.writeInt(bIsServicePresent);

            // uServicecategory
            int uServicecategory = 0;// int
            dos.writeInt(uServicecategory);

            // RIL_CDMA_SMS_Address
            // digit_mode
            // number_mode
            // number_type
            // number_plan
            // number_of_digits
            // digits[]
            CdmaSmsAddress destAddr = CdmaSmsAddress.parse(PhoneNumberUtils
                    .cdmaCheckAndProcessPlusCode(destinationAddress));
            if (destAddr == null)
                return null;
            dos.writeByte(destAddr.digitMode);// int
            dos.writeByte(destAddr.numberMode);// int
            dos.writeByte(destAddr.ton);// int
            dos.writeByte(destAddr.numberPlan);// int
            dos.writeByte(destAddr.numberOfDigits);// byte
            dos.write(destAddr.origBytes, 0, destAddr.origBytes.length); // digits

            // RIL_CDMA_SMS_Subaddress
            // Subaddress is not supported.
            dos.writeByte(0); // subaddressType int
            dos.writeByte(0); // subaddr_odd byte
            dos.writeByte(0); // subaddr_nbr_of_digits byte

            SmsHeader smsHeader = new SmsHeader().fromByteArray(headerStream.toByteArray());
            UserData uData = new UserData();
            uData.payloadStr = message;
            // uData.userDataHeader = smsHeader;
            uData.msgEncodingSet = true;
            uData.msgEncoding = UserData.ENCODING_UNICODE_16;

            BearerData bearerData = new BearerData();
            bearerData.messageType = BearerData.MESSAGE_TYPE_DELIVER;

            bearerData.deliveryAckReq = false;
            bearerData.userAckReq = false;
            bearerData.readAckReq = false;
            bearerData.reportReq = false;

            bearerData.userData = uData;

            byte[] encodedBearerData = BearerData.encode(bearerData);
            if (null != encodedBearerData) {
                // bearer data len
                dos.writeByte(encodedBearerData.length);// int
                Log.d(TAG, "encodedBearerData length=" + encodedBearerData.length);

                // aBearerData
                dos.write(encodedBearerData, 0, encodedBearerData.length);
            } else {
                dos.writeByte(0);
            }

        } catch (IOException e) {
            Log.e(TAG, "Error writing dos", e);
        } finally {
            try {
                if (null != byteStream) {
                    byteStream.close();
                }

                if (null != dos) {
                    dos.close();
                }

                if (null != headerStream) {
                    headerStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error close dos", e);
            }
        }

        return byteStream.toByteArray();
    }

    /**
     * Generate a Delivery PDU byte array. see getSubmitPdu for reference.
     */
    public static byte[] getDeliveryPdu(String scAddress, String destinationAddress, String message,
            long date, byte[] header, int encoding) {
        // Perform null parameter checks.
        if (message == null || destinationAddress == null) {
            return null;
        }

        // MTI = SMS-DELIVERY, UDHI = header != null
        byte mtiByte = (byte)(0x00 | (header != null ? 0x40 : 0x00));
        ByteArrayOutputStream bo = getDeliveryPduHeader(destinationAddress, mtiByte);
        // User Data (and length)
        byte[] userData;
        if (encoding == ENCODING_UNKNOWN) {
            // First, try encoding it with the GSM alphabet
            encoding = ENCODING_7BIT;
        }
        try {
            if (encoding == ENCODING_7BIT) {
                userData = GsmAlphabet.stringToGsm7BitPackedWithHeader(message, header, 0, 0);
            } else { //assume UCS-2
                try {
                    userData = encodeUCS2(message, header);
                } catch (UnsupportedEncodingException uex) {
                    Log.e("GSM", "Implausible UnsupportedEncodingException ",
                            uex);
                    return null;
                }
            }
        } catch (EncodeException ex) {
            // Encoding to the 7-bit alphabet failed. Let's see if we can
            // encode it as a UCS-2 encoded message
            try {
                userData = encodeUCS2(message, header);
                encoding = ENCODING_16BIT;
            } catch (UnsupportedEncodingException uex) {
                Log.e("GSM", "Implausible UnsupportedEncodingException ",
                        uex);
                return null;
            }
        }

        if (encoding == ENCODING_7BIT) {
            if ((0xff & userData[0]) > MAX_USER_DATA_SEPTETS) {
                // Message too long
                return null;
            }
            bo.write(0x00);
        } else { //assume UCS-2
            if ((0xff & userData[0]) > MAX_USER_DATA_BYTES) {
                // Message too long
                return null;
            }
            // TP-Data-Coding-Scheme
            // Class 3, UCS-2 encoding, uncompressed
            bo.write(0x0b);
        }
        byte[] timestamp = getTimestamp(date);
        bo.write(timestamp, 0, timestamp.length);

        bo.write(userData, 0, userData.length);
        return bo.toByteArray();
    }

    private static ByteArrayOutputStream getDeliveryPduHeader(
            String destinationAddress, byte mtiByte) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(
                MAX_USER_DATA_BYTES + 40);
        bo.write(mtiByte);

        byte[] daBytes;
        daBytes = PhoneNumberUtils.networkPortionToCalledPartyBCD(destinationAddress);

        // destination address length in BCD digits, ignoring TON byte and pad
        // TODO Should be better.
        bo.write((daBytes.length - 1) * 2
                - ((daBytes[daBytes.length - 1] & 0xf0) == 0xf0 ? 1 : 0));

        // destination address
        bo.write(daBytes, 0, daBytes.length);

        // TP-Protocol-Identifier
        bo.write(0);
        return bo;
    }

    private static byte[] getTimestamp(long time) {
        // See TS 23.040 9.2.3.11
        byte[] timestamp = new byte[TIMESTAMP_LENGTH];
        SimpleDateFormat sdf = new SimpleDateFormat("yyMMddkkmmss:Z", Locale.US);
        String[] date = sdf.format(time).split(":");
        // generate timezone value
        String timezone = date[date.length - 1];
        String signMark = timezone.substring(0, 1);
        int hour = Integer.parseInt(timezone.substring(1, 3));
        int min = Integer.parseInt(timezone.substring(3));
        int timezoneValue = hour * 4 + min / 15;
        // append timezone value to date[0] (time string)
        String timestampStr = date[0] + timezoneValue;

        int digitCount = 0;
        for (int i = 0; i < timestampStr.length(); i++) {
            char c = timestampStr.charAt(i);
            int shift = ((digitCount & 0x01) == 1) ? 4 : 0;
            timestamp[(digitCount >> 1)] |= (byte)((charToBCD(c) & 0x0F) << shift);
            digitCount++;
        }

        if (signMark.equals("-")) {
            timestamp[timestamp.length - 1] = (byte) (timestamp[timestamp.length - 1] | 0x08);
        }

        return timestamp;
    }

    private static byte[] encodeUCS2(String message, byte[] header)
            throws UnsupportedEncodingException {
        byte[] userData, textPart;
        textPart = message.getBytes("utf-16be");

        if (header != null) {
            // Need 1 byte for UDHL
            userData = new byte[header.length + textPart.length + 1];

            userData[0] = (byte)header.length;
            System.arraycopy(header, 0, userData, 1, header.length);
            System.arraycopy(textPart, 0, userData, header.length + 1, textPart.length);
        }
        else {
            userData = textPart;
        }
        byte[] ret = new byte[userData.length+1];
        ret[0] = (byte) (userData.length & 0xff );
        System.arraycopy(userData, 0, ret, 1, userData.length);
        return ret;
    }

    private static int charToBCD(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        } else {
            throw new RuntimeException ("invalid char for BCD " + c);
        }
    }

    /**
     * Returns true if the address passed in is a Browser wap push MMS address.
     */
    public static boolean isWapPushNumber(String address) {
        if (TextUtils.isEmpty(address)) {
            return false;
        } else {
            return address.contains(WAPPUSH);
        }
    }

    /**
     * Return the sim name of subscription.
     */
    public static String getMultiSimName(Context context, int slot) {
        if (slot >= TelephonyManager.getDefault().getPhoneCount() || slot < 0) {
            return null;
        }
        //String multiSimName = Settings.System.getString(context.getContentResolver(),
        //        MULTI_SIM_NAME + (subscription + 1));
        //if (multiSimName == null) {
        if (slot == SUB1) {
            return context.getString(R.string.slot1);
        } else if (slot == SUB2) {
            return context.getString(R.string.slot2);
        }
        //}
        return context.getString(R.string.slot1);
    }

    /**
     * Return the activated card number
     */
    public static int getActivatedIccCardCount() {
        TelephonyManager tm = TelephonyManager.getDefault();
        int phoneCount = tm.getPhoneCount();
        int count = 0;
        for (int i = 0; i < phoneCount; i++) {
            if (isIccCardActivated(i)) {
                count++;
            }
        }
        return count;
    }
}
