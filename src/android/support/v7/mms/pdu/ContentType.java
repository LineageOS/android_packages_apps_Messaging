/*
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
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

package android.support.v7.mms.pdu;

import android.webkit.MimeTypeMap;

public final class ContentType {
    public static final String THREE_GPP_EXTENSION = "3gp";
    public static final String VIDEO_MP4_EXTENSION = "mp4";
    // Default extension used when we don't know one.
    public static final String DEFAULT_EXTENSION = "dat";

    public static final int TYPE_IMAGE = 0;
    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_AUDIO = 2;
    public static final int TYPE_VCARD = 3;
    public static final int TYPE_OTHER = 4;

    public static final String ANY_TYPE          = "*/*";
    public static final String MMS_MESSAGE       = "application/vnd.wap.mms-message";
    // The phony content type for generic PDUs (e.g. ReadOrig.ind,
    // Notification.ind, Delivery.ind).
    public static final String MMS_GENERIC       = "application/vnd.wap.mms-generic";
    public static final String MMS_MULTIPART_MIXED   = "application/vnd.wap.multipart.mixed";
    public static final String MMS_MULTIPART_RELATED = "application/vnd.wap.multipart.related";
    public static final String MMS_MULTIPART_ALTERNATIVE =
            "application/vnd.wap.multipart.alternative";

    public static final String TEXT_PLAIN        = "text/plain";
    public static final String TEXT_HTML         = "text/html";
    public static final String TEXT_VCALENDAR    = "text/vCalendar";
    public static final String TEXT_VCARD        = "text/vCard";
    public static final String TEXT_X_VCALENDAR  = "text/x-vCalendar";
    public static final String TEXT_X_VCARD      = "text/x-vCard";

    public static final String IMAGE_PREFIX      = "image/";
    public static final String IMAGE_UNSPECIFIED = "image/*";
    public static final String IMAGE_JPEG        = "image/jpeg";
    public static final String IMAGE_JPG         = "image/jpg";
    public static final String IMAGE_GIF         = "image/gif";
    public static final String IMAGE_WBMP        = "image/vnd.wap.wbmp";
    public static final String IMAGE_PNG         = "image/png";
    public static final String IMAGE_X_MS_BMP    = "image/x-ms-bmp";

    public static final String AUDIO_AAC         = "audio/aac";
    public static final String AUDIO_AMR         = "audio/amr";
    public static final String AUDIO_MID         = "audio/mid";
    public static final String AUDIO_MIDI        = "audio/midi";
    public static final String AUDIO_MP3         = "audio/mp3";
    public static final String AUDIO_MP4         = "audio/mp4";
    public static final String AUDIO_X_MID       = "audio/x-mid";
    public static final String AUDIO_X_MIDI      = "audio/x-midi";
    public static final String AUDIO_X_MP3       = "audio/x-mp3";
    public static final String AUDIO_3GPP        = "audio/3gpp";
    public static final String AUDIO_X_WAV       = "audio/x-wav";
    public static final String AUDIO_OGG         = "application/ogg";

    public static final String VIDEO_UNSPECIFIED = "video/*";
    public static final String VIDEO_3GP         = "video/3gp";
    public static final String VIDEO_3GPP        = "video/3gpp";
    public static final String VIDEO_3G2         = "video/3gpp2";
    public static final String VIDEO_H263        = "video/h263";
    public static final String VIDEO_M4V         = "video/m4v";
    public static final String VIDEO_MP4         = "video/mp4";
    public static final String VIDEO_MPEG        = "video/mpeg";
    public static final String VIDEO_MPEG4       = "video/mpeg4";
    public static final String VIDEO_WEBM        = "video/webm";

    public static final String APP_SMIL          = "application/smil";
    public static final String APP_WAP_XHTML     = "application/vnd.wap.xhtml+xml";

    // This class should never be instantiated.
    private ContentType() {
    }

    // ---- THE TYPE PREDICATES ARE CASE-INSENSITIVE, AND THAT IS A DELIBERATE DOWNSTREAM FORK -----
    //
    // MIME types are case-insensitive per RFC 2045 section 5.1, so a conforming
    // peer may legitimately send "IMAGE/JPEG". These five predicates used to disagree with each
    // other about that:
    //
    //     isImageType   startsWith(IMAGE_PREFIX)                    CASE-SENSITIVE
    //     isVideoType   startsWith("video/")                        CASE-SENSITIVE
    //     isTextType    TEXT_PLAIN.equals(..)                       CASE-SENSITIVE
    //     isAudioType   startsWith("audio/") OR equalsIgnoreCase()  MIXED
    //     isVCardType   equalsIgnoreCase() x2                       CASE-INSENSITIVE
    //
    // That is a HALF-FINISHED NORMALISATION, not a design: the insensitive ones are right and the
    // sensitive ones are wrong, and this change finishes it rather than introducing a new behaviour
    // into a consistent class.
    //
    // WHY THE FILE IS EDITED RATHER THAN WRAPPED, since "leave the vendored copy alone" is the
    // obvious instinct and was the initial leaning here. Measured, both halves:
    //   - A NORMALISING WRAPPER ON OUR SIDE would have to be adopted at 70 call sites across ~20
    //     files, including PduPersister, MmsUtils and DatabaseMessages — i.e. it would touch far
    //     more of the SMS/MMS paths than this does, which is the blast radius the wrapper was meant
    //     to avoid. And it would leave these predicates still wrong for anyone who calls them.
    //   - THE "PRISTINE UPSTREAM FILE" PREMISE IS FALSE. `git log` on this path shows four
    //     downstream commits (Chris Talbot 2021, Michael W 2024 x3) on top of AOSP, and isVCardType
    //     — the case-INSENSITIVE one — is itself one of them. The package was renamed downstream
    //     (androidx.appcompat.mms -> android.support.v7.mms), and that library is dead upstream, so
    //     there is no upstream to diverge from.
    //
    // A predicate that becomes MORE accepting can only change behaviour for input it currently
    // mis-classifies: an uppercase type that today matches nothing and renders as nothing. There is
    // no path where a type doing the right thing starts doing the wrong one.
    //
    // Enforced by RccContentTypeCaseGuardTest, so a re-sync of this file that silently drops the
    // fork fails rather than passing.

    public static boolean isTextType(final String contentType) {
        return TEXT_PLAIN.equalsIgnoreCase(contentType)
                || TEXT_HTML.equalsIgnoreCase(contentType)
                || APP_WAP_XHTML.equalsIgnoreCase(contentType);
    }

    public static boolean isMediaType(final String contentType) {
        return isImageType(contentType)
                || isVideoType(contentType)
                || isAudioType(contentType)
                || isVCardType(contentType);
    }

    public static boolean isImageType(final String contentType) {
        return startsWithIgnoreCase(contentType, IMAGE_PREFIX);
    }

    public static boolean isAudioType(final String contentType) {
        return startsWithIgnoreCase(contentType, "audio/")
                || ((null != contentType) && contentType.equalsIgnoreCase(AUDIO_OGG));
    }

    public static boolean isVideoType(final String contentType) {
        return startsWithIgnoreCase(contentType, "video/");
    }

    /** {@code String.startsWith}, case-insensitively — see the note above the predicates. */
    private static boolean startsWithIgnoreCase(final String contentType, final String prefix) {
        return (null != contentType)
                && contentType.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public static boolean isVCardType(final String contentType) {
        return (null != contentType)
                && (contentType.equalsIgnoreCase(TEXT_X_VCARD)
                        || contentType.equalsIgnoreCase(TEXT_VCARD));
    }

    /**
     * If the content type is a type which can be displayed in the conversation list as a preview.
     */
    public static boolean isConversationListPreviewableType(final String contentType) {
        return ContentType.isAudioType(contentType) || ContentType.isVideoType(contentType) ||
                ContentType.isImageType(contentType) || ContentType.isVCardType(contentType);
    }

    /**
     * Given a filename, look at the extension and try and determine the mime type.
     *
     * @param fileName a filename to determine the type from, such as img1231.jpg
     * @param contentTypeDefault type to use when the content type can't be determined from the file
     *      extension. It can be null or a type such as ContentType.IMAGE_UNSPECIFIED
     * @return Content type of the extension.
     */
    public static String getContentTypeFromExtension(final String fileName,
            final String contentTypeDefault) {
        final MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
        final String extension = MimeTypeMap.getFileExtensionFromUrl(fileName);
        String contentType = mimeTypeMap.getMimeTypeFromExtension(extension);
        if (contentType == null) {
            contentType = contentTypeDefault;
        }
        return contentType;
    }

    public static String getExtensionFromMimeType(final String mimeType) {
        final MimeTypeMap mimeTypeMap = MimeTypeMap.getSingleton();
        final String extension = mimeTypeMap.getExtensionFromMimeType(mimeType);
        return extension;
    }

    /**
     * Get the common file extension for a given content type
     * @param contentType The content type
     * @return The extension without the .
     */
    public static String getExtension(final String contentType) {
        if (VIDEO_MP4.equals(contentType)) {
            return VIDEO_MP4_EXTENSION;
        } else if (VIDEO_3GPP.equals(contentType)) {
            return THREE_GPP_EXTENSION;
        } else {
            return DEFAULT_EXTENSION;
        }
    }
}
