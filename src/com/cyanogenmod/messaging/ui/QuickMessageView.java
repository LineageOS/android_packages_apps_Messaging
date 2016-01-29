package com.cyanogenmod.messaging.ui;


import android.content.Context;
import android.content.res.Resources;
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
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.messaging.BugleApplication;
import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.ui.PlainTextEditText;
import com.android.messaging.ui.conversation.SimIconView;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.ContactUtil;
import com.android.messaging.util.UiUtils;
import com.cyanogen.lookup.phonenumber.response.LookupResponse;
import com.cyanogenmod.messaging.lookup.LookupProviderManager;
import com.cyanogenmod.messaging.quickmessage.ManageWakeLock;
import com.cyanogenmod.messaging.quickmessage.QuickMessage;
import com.cyanogenmod.messaging.quickmessage.QuickMessageHelper;

public class QuickMessageView extends LinearLayout implements TextWatcher,
        LookupProviderManager.LookupProviderListener {

    private static final String LOG_TAG = "QuickMessageView";

    private boolean DEBUG = false;

    private static final int CHARS_REMAINING_BEFORE_COUNTER_SHOWN = 30;

    private PlainTextEditText mComposeEditText;
    private TextView mCharCounter;
    private SimIconView mSelfSendIcon;
    private ImageButton mSendButton;
    private ConversationQuickMessageView mMessageText;
    private TextView mFromName;
    private TextView mTimestamp;
    private TextView mMessageCounter;

    private QuickMessage mQuickMessage;

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
        mMessageCounter = (TextView) findViewById(R.id.message_counter);
    }

    public void bind(QuickMessage quickMessage, OnClickListener onClickListener,
            TextView.OnEditorActionListener onEditorActionListener, int messageNumber,
            int messageCount) {
        mQuickMessage = quickMessage;
        mFromName.setText(quickMessage.getFromName());
        mTimestamp.setText(QuickMessageHelper.formatTimeStampString(getContext(),
                quickMessage.getTimestamp(), false));
        mComposeEditText.setText(quickMessage.getReplyText());
        mComposeEditText.setSelection(quickMessage.getReplyText().length());
        mComposeEditText.setOnEditorActionListener(onEditorActionListener);
        mSendButton.setOnClickListener(onClickListener);
        mMessageText.bind(quickMessage);
        if (quickMessage.hasSelfAvatarUri()) {
            mSelfSendIcon.setImageResourceUri(quickMessage.getSelfAvatarUri());
        } else {
            mSelfSendIcon.setImageResourceUri(null);
        }
        updateMessageCounter(messageNumber, messageCount);
        if (!ContactUtil.isValidContactId(mQuickMessage.getFromContactId())) {
            BugleApplication.getLookupProviderClient().addLookupProviderListener(
                    mQuickMessage.getSenderNormalizedDestination(), this);
            BugleApplication.getLookupProviderClient().lookupInfoForPhoneNumber(
                    mQuickMessage.getSenderNormalizedDestination());
        }
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

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (!ContactUtil.isValidContactId(mQuickMessage.getFromContactId())) {
            BugleApplication.getLookupProviderClient().removeLookupProviderListener(
                    mQuickMessage.getSenderNormalizedDestination(), this);
        }
    }

    /**
     * Update the page indicator counter to show the currently selected visible page number
     */
    private void updateMessageCounter(int messageNumber, int messageCount) {
        int current = messageNumber + 1;

        if (messageCount > 1) {
            mMessageCounter.setVisibility(View.VISIBLE);
            mMessageCounter.setText(
                    Factory.get().getApplicationContext().getString(R.string.message_counter,
                            current, messageCount));
        } else {
            mMessageCounter.setVisibility(View.INVISIBLE);
        }

        if (DEBUG) {
            Log.d(LOG_TAG, "updateMessageCounter() called, counter text set to "
                    + current + " of " + messageCount);
        }
    }

    private void updateVisualsOnTextChanged() {
        final String messageText = mComposeEditText.getText().toString();

        final boolean hasMessageText = (TextUtils.getTrimmedLength(messageText) > 0);
        if (mQuickMessage != null && hasMessageText) {
            mQuickMessage.saveReplyText();
        }
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
                    mCharCounter.setVisibility(View.INVISIBLE);
                }
            }
        }

        if (hasMessageText) {
            UiUtils.revealOrHideViewWithAnimation(mSendButton, VISIBLE, null);
        } else {
            UiUtils.revealOrHideViewWithAnimation(mSendButton, GONE, null);
        }

    }

    @Override
    public void onNewInfoAvailable(LookupResponse response) {
        if (response != null) {
            if (mQuickMessage.getSenderNormalizedDestination() != null && mQuickMessage
                    .getSenderNormalizedDestination().equals(response.mNumber)) {
                mFromName.setText(response.mName);
                mMessageText.onLookupResponse(response);
            }
        }
    }
}
