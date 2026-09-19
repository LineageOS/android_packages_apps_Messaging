/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.ui.conversation;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.messaging.R;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.rcs.RcsFileAttachment;

import java.util.Locale;

/**
 * A received RCS file that no media view draws, or one not downloaded yet: its name, size and
 * type, and its caption. A tap opens it, or asks the provider for it while it is pending; see
 * {@code ConversationFragment#onAttachmentClick}. A pending file the provider can no longer
 * download says so instead of offering the download.
 */
public class RcsFileAttachmentView extends LinearLayout {
    private ImageView mIcon;
    private TextView mName;
    private TextView mDetails;
    private TextView mCaption;

    public RcsFileAttachmentView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mIcon = findViewById(R.id.rcs_file_icon);
        mName = findViewById(R.id.rcs_file_name);
        mDetails = findViewById(R.id.rcs_file_details);
        mCaption = findViewById(R.id.rcs_file_caption);
    }

    /**
     * Shows {@code part}. The details line ends with the download action while {@code pending},
     * and says the file is gone when {@code unavailable}: its link expired, so a tap cannot
     * download it.
     */
    public void bind(final MessagePartData part, final boolean pending,
            final boolean unavailable, final int nameColor, final int detailsColor) {
        final String uri = part.getContentUri() == null ? null : part.getContentUri().toString();
        final String name = RcsFileAttachment.nameOf(uri);
        mName.setText(TextUtils.isEmpty(name)
                ? getResources().getString(R.string.rcs_file_unnamed) : name);

        final StringBuilder details = new StringBuilder();
        final String sep = getResources().getString(R.string.rcs_file_details_separator);
        final long size = RcsFileAttachment.sizeOf(uri);
        if (size >= 0) details.append(Formatter.formatShortFileSize(getContext(), size));
        final String type = typeLabel(name, part.getContentType());
        if (!TextUtils.isEmpty(type)) {
            if (details.length() > 0) details.append(sep);
            details.append(type);
        }
        if (unavailable) {
            if (details.length() > 0) details.append(sep);
            details.append(getResources().getString(R.string.rcs_file_unavailable));
        } else if (pending) {
            if (details.length() > 0) details.append(sep);
            details.append(getResources().getString(R.string.rcs_file_tap_to_download));
        }
        mDetails.setText(details);
        mDetails.setVisibility(details.length() > 0 ? VISIBLE : GONE);

        // A text part's text is already the message text.
        final String caption = part.isText() ? null : part.getText();
        mCaption.setText(caption);
        mCaption.setVisibility(TextUtils.isEmpty(caption) ? GONE : VISIBLE);

        mName.setTextColor(nameColor);
        mDetails.setTextColor(detailsColor);
        mCaption.setTextColor(nameColor);
        mIcon.setImageTintList(ColorStateList.valueOf(nameColor));
        setContentDescription(mName.getText() + ", " + details);
    }

    /** The file's extension, else the MIME type's, upper case; else the MIME type itself. */
    private static String typeLabel(final String name, final String mime) {
        final String ext = RcsFileAttachment.extensionLabel(name);
        if (ext != null) return ext;
        final String fromMime = mime == null ? null
                : MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (!TextUtils.isEmpty(fromMime)) return fromMime.toUpperCase(Locale.ROOT);
        return mime;
    }
}
