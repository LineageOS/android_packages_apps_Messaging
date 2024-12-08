/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024 The LineageOS Project
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

package android.support.v7.mms;

import android.os.Bundle;

/**
 * Loader for carrier dependent configuration values
 */
public interface CarrierConfigValuesLoader {
    /**
     * Get the carrier config values in a bundle
     *
     * @param subId the associated subscription ID for the carrier configuration
     * @return a bundle of all the values
     */
    Bundle get(int subId);

    // Configuration keys and default values

    /** Boolean value: if MMS is enabled */
    String CONFIG_ENABLED_MMS = "enabledMMS";
    boolean CONFIG_ENABLED_MMS_DEFAULT = true;
    /**
     * Boolean value: if transaction ID should be appended to
     * the download URL of a single segment WAP push message
     */
    String CONFIG_ENABLED_TRANS_ID = "enabledTransID";
    boolean CONFIG_ENABLED_TRANS_ID_DEFAULT = false;
    /**
     * Boolean value: if acknowledge or notify response to a download
     * should be sent to the WAP push message's download URL
     */
    String CONFIG_ENABLED_NOTIFY_WAP_MMSC = "enabledNotifyWapMMSC";
    boolean CONFIG_ENABLED_NOTIFY_WAP_MMSC_DEFAULT = false;
    /**
     * Boolean value: if phone number alias can be used
     */
    String CONFIG_ALIAS_ENABLED = "aliasEnabled";
    boolean CONFIG_ALIAS_ENABLED_DEFAULT = false;
    /**
     * Boolean value: if audio is allowed in attachment
     */
    String CONFIG_ALLOW_ATTACH_AUDIO = "allowAttachAudio";
    boolean CONFIG_ALLOW_ATTACH_AUDIO_DEFAULT = true;
    /**
     * Boolean value: if true, long sms messages are always sent as multi-part sms
     * messages, with no checked limit on the number of segments. If false, then
     * as soon as the user types a message longer than a single segment (i.e. 140 chars),
     * the message will turn into and be sent as an mms message or separate,
     * independent SMS messages (dependent on CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES flag).
     * This feature exists for carriers that don't support multi-part sms.
     */
    String CONFIG_ENABLE_MULTIPART_SMS = "enableMultipartSMS";
    boolean CONFIG_ENABLE_MULTIPART_SMS_DEFAULT = true;
    /**
     * Boolean value: if SMS delivery report is supported
     */
    String CONFIG_ENABLE_SMS_DELIVERY_REPORTS = "enableSMSDeliveryReports";
    boolean CONFIG_ENABLE_SMS_DELIVERY_REPORTS_DEFAULT = true;
    /**
     * Boolean value: if group MMS is supported
     */
    String CONFIG_ENABLE_GROUP_MMS = "enableGroupMms";
    boolean CONFIG_ENABLE_GROUP_MMS_DEFAULT = true;
    /**
     * Boolean value: if the content_disposition field of an MMS part should be parsed
     * Check wap-230-wsp-20010705-a.pdf, chapter 8.4.2.21. Most carriers support it except some.
     */
    String CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION =
            "supportMmsContentDisposition";
    boolean CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION_DEFAULT = true;
    /**
     * Boolean value: if the sms app should support a link to the system settings
     * where amber alerts are configured.
     */
    String CONFIG_CELL_BROADCAST_APP_LINKS = "config_cellBroadcastAppLinks";
    boolean CONFIG_CELL_BROADCAST_APP_LINKS_DEFAULT = true;
    /**
     * Boolean value: if multipart SMS should be sent as separate SMS messages
     */
    String CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES =
            "sendMultipartSmsAsSeparateMessages";
    boolean CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES_DEFAULT = false;
    /**
     * Boolean value: if MMS read report is supported
     */
    String CONFIG_ENABLE_MMS_READ_REPORTS = "enableMMSReadReports";
    boolean CONFIG_ENABLE_MMS_READ_REPORTS_DEFAULT = false;
    /**
     * Boolean value: if MMS delivery report is supported
     */
    String CONFIG_ENABLE_MMS_DELIVERY_REPORTS = "enableMMSDeliveryReports";
    boolean CONFIG_ENABLE_MMS_DELIVERY_REPORTS_DEFAULT = false;
    /**
     * Boolean value: if "charset" value is supported in the "Content-Type" HTTP header
     */
    String CONFIG_SUPPORT_HTTP_CHARSET_HEADER = "supportHttpCharsetHeader";
    boolean CONFIG_SUPPORT_HTTP_CHARSET_HEADER_DEFAULT = false;
    /**
     * Integer value: maximal MMS message size in bytes
     */
    String CONFIG_MAX_MESSAGE_SIZE = "maxMessageSize";
    int CONFIG_MAX_MESSAGE_SIZE_DEFAULT = 300 * 1024;
    /**
     * Integer value: maximal MMS image height in pixels
     */
    String CONFIG_MAX_IMAGE_HEIGHT = "maxImageHeight";
    int CONFIG_MAX_IMAGE_HEIGHT_DEFAULT = 480;
    /**
     * Integer value: maximal MMS image width in pixels
     */
    String CONFIG_MAX_IMAGE_WIDTH = "maxImageWidth";
    int CONFIG_MAX_IMAGE_WIDTH_DEFAULT = 640;
    /**
     * Integer value: limit on recipient list of an MMS message
     */
    String CONFIG_RECIPIENT_LIMIT = "recipientLimit";
    int CONFIG_RECIPIENT_LIMIT_DEFAULT = Integer.MAX_VALUE;
    /**
     * Integer value: HTTP socket timeout in milliseconds for MMS
     */
    String CONFIG_HTTP_SOCKET_TIMEOUT = "httpSocketTimeout";
    int CONFIG_HTTP_SOCKET_TIMEOUT_DEFAULT = 60 * 1000;
    /**
     * Integer value: minimal number of characters of an alias
     */
    String CONFIG_ALIAS_MIN_CHARS = "aliasMinChars";
    int CONFIG_ALIAS_MIN_CHARS_DEFAULT = 2;
    /**
     * Integer value: maximal number of characters of an alias
     */
    String CONFIG_ALIAS_MAX_CHARS = "aliasMaxChars";
    int CONFIG_ALIAS_MAX_CHARS_DEFAULT = 48;
    /**
     * Integer value: the threshold of number of SMS parts when an multipart SMS will be
     * converted into an MMS, e.g. if this is "4", when an multipart SMS message has 5
     * parts, then it will be sent as MMS message instead. "-1" indicates no such conversion
     * can happen.
     */
    String CONFIG_SMS_TO_MMS_TEXT_THRESHOLD = "smsToMmsTextThreshold";
    int CONFIG_SMS_TO_MMS_TEXT_THRESHOLD_DEFAULT = -1;
    /**
     * Integer value: the threshold of SMS length when it will be converted into an MMS.
     * "-1" indicates no such conversion can happen.
     */
    String CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD =
            "smsToMmsTextLengthThreshold";
    int CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD_DEFAULT = -1;
    /**
     * Integer value: maximal length in bytes of SMS message
     */
    String CONFIG_MAX_MESSAGE_TEXT_SIZE = "maxMessageTextSize";
    int CONFIG_MAX_MESSAGE_TEXT_SIZE_DEFAULT = -1;
    /**
     * Integer value: maximum number of characters allowed for mms subject
     */
    String CONFIG_MAX_SUBJECT_LENGTH = "maxSubjectLength";
    int CONFIG_MAX_SUBJECT_LENGTH_DEFAULT = 40;
    /**
     * String value: name for the user agent profile HTTP header
     */
    String CONFIG_UA_PROF_TAG_NAME = "uaProfTagName";
    String CONFIG_UA_PROF_TAG_NAME_DEFAULT = "x-wap-profile";
    /**
     * String value: additional HTTP headers for MMS HTTP requests.
     * The format is
     * header_1:header_value_1|header_2:header_value_2|...
     * Each value can contain macros.
     */
    String CONFIG_HTTP_PARAMS = "httpParams";
    String CONFIG_HTTP_PARAMS_DEFAULT = null;
    /**
     * String value: number of email gateway
     */
    String CONFIG_EMAIL_GATEWAY_NUMBER = "emailGatewayNumber";
    String CONFIG_EMAIL_GATEWAY_NUMBER_DEFAULT = null;
    /**
     * String value: suffix for the NAI HTTP header value, e.g. ":pcs"
     * (NAI is used as authentication in HTTP headers for some carriers)
     */
    String CONFIG_NAI_SUFFIX = "naiSuffix";
    String CONFIG_NAI_SUFFIX_DEFAULT = null;
    /**
     * String value: Url for user agent profile
     */
    String CONFIG_UA_PROF_URL = "uaProfUrl";
    String CONFIG_UA_PROF_URL_DEFAULT = null;
    /**
     * String value: user agent
     */
    String CONFIG_USER_AGENT = "userAgent";
    String CONFIG_USER_AGENT_DEFAULT = null;
}
