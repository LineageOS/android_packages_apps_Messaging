package com.cyanogenmod.messaging.ui;


import android.content.Context;
import android.net.Uri;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.messaging.R;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.ui.PlainTextEditText;
import com.android.messaging.ui.conversation.SimIconView;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.UiUtils;
import com.cyanogenmod.messaging.quickmessage.ManageWakeLock;
import com.cyanogenmod.messaging.quickmessage.QuickMessage;
import com.cyanogenmod.messaging.quickmessage.QuickMessageHelper;

public class QuickMessageView extends LinearLayout implements TextWatcher {

    private static final int CHARS_REMAINING_BEFORE_COUNTER_SHOWN = 30;

    private PlainTextEditText mComposeEditText;
    private TextView mCharCounter;
    private TextView mMmsIndicator;
    private SimIconView mSelfSendIcon;
    private ImageButton mSendButton;
    private ConversationQuickMessageView mMessageText;
    private TextView mFromName;
    private TextView mTimestamp;

    public QuickMessageView(final Context context, final AttributeSet attrs) {
        super(new ContextThemeWrapper(context, R.style.ColorAccentBlueOverrideStyle), attrs);
    }

    @Override
    protected void onFinishInflate() {
        mComposeEditText = (PlainTextEditText) findViewById(
                R.id.embedded_text_editor);
        mComposeEditText.addTextChangedListener(this);
        mComposeEditText.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        InputFilter.LengthFilter lengthFilter = new InputFilter.LengthFilter(2000);
        mComposeEditText.setFilters(new InputFilter[] { lengthFilter });
        mMessageText = (ConversationQuickMessageView) findViewById(R.id.conversation_message);
        mFromName = (TextView) findViewById(R.id.fromTextView);
        mTimestamp = (TextView) findViewById(R.id.timestampTextView);
        mSelfSendIcon = (SimIconView) findViewById(R.id.self_send_icon);
        mSendButton = (ImageButton) findViewById(R.id.send_button);
        mCharCounter = (TextView) findViewById(R.id.text_counter);
        mMmsIndicator = (TextView) findViewById(R.id.mms_indicator);
    }

    public void bind(QuickMessage quickMessage, OnClickListener onClickListener,
            TextView.OnEditorActionListener onEditorActionListener) {
        mFromName.setText(quickMessage.getFromName());
        mTimestamp.setText(QuickMessageHelper.formatTimeStampString(getContext(),
                quickMessage.getTimestamp(), false));
        mComposeEditText.setText(quickMessage.getReplyText());
        mComposeEditText.setSelection(quickMessage.getReplyText().length());
        mComposeEditText.setOnEditorActionListener(onEditorActionListener);
        mSendButton.setOnClickListener(onClickListener);
        mMessageText.bind(quickMessage);
    }

    @Override
    public void afterTextChanged(Editable s) {}

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        updateVisualsOnTextChanged();

        // For performance, we will only poke the wakelock on the 1st and every 20th keystroke
        if (s.length() == 1 || s.length() % 20 == 0) {
            // If there is no active wakelock this will not do anything
            ManageWakeLock.pokeWakeLock(mContext);
        }
    }

    private void updateVisualsOnTextChanged() {
        final String messageText = mComposeEditText.getText().toString();

        final boolean hasMessageText = (TextUtils.getTrimmedLength(messageText) > 0);

        if (hasMessageText) {
            if (messageText.length() < (80
                    - CHARS_REMAINING_BEFORE_COUNTER_SHOWN)) {
                mCharCounter.setVisibility(View.GONE);
            } else {
                /**
                 * SmsMessage.calculateLength returns an int[4] with: int[0] being the number of SMS's
                 * required, int[1] the number of code units used, int[2] is the number of code units
                 * remaining until the next message. int[3] is the encoding type that should be used for the
                 * message.
                 */
                int[] params = SmsMessage.calculateLength(messageText, false);
                int msgCount = params[0];
                int remainingInCurrentMessage = params[2];

                if (msgCount > 1
                        || remainingInCurrentMessage <= CHARS_REMAINING_BEFORE_COUNTER_SHOWN) {
                    mCharCounter.setText(remainingInCurrentMessage + " / " + msgCount);
                    mCharCounter.setVisibility(View.VISIBLE);
                } else {
                    mCharCounter.setVisibility(View.GONE);
                }
            }
        }

        // Update the send message button. Self icon uri might be null if self participant data
        // and/or conversation metadata hasn't been loaded by the host.
        final Uri selfSendButtonUri = getSelfSendButtonIconUri();
        if (selfSendButtonUri != null) {
            if (hasMessageText) {
                UiUtils.revealOrHideViewWithAnimation(mSendButton, VISIBLE, null);
            } else {
                mSelfSendIcon.setImageResourceUri(selfSendButtonUri);
                UiUtils.revealOrHideViewWithAnimation(mSendButton, GONE, null);
            }
        } else {
            mSelfSendIcon.setImageResourceUri(null);
        }
    }

    private Uri getSelfSendButtonIconUri() {
        SubscriptionManager subscriptionManager = SubscriptionManager.from(getContext());
        SubscriptionInfo info = subscriptionManager.getDefaultSmsSubscriptionInfo();
        int subId = info.getSubscriptionId();
        final ParticipantData self = ParticipantData.getSelfParticipant(subId);
        return self == null ? null : AvatarUriUtil.createAvatarUri(self);
    }
}
