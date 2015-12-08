package com.android.messaging.ui.conversation;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;

import com.android.messaging.R;
import com.android.messaging.datamodel.data.DraftMessageData;

/**
 * If conversation includes secured and unsecured participants
 * this class will inform user or apply saved action
 */
public class MixedParticipantsSecurityWarning {
    public static final String MIXED_PARTICIPANTS_SEND_ACTION = "pref_key_mixed_participants_send_as_sms";

    private MixedParticipantsSecurityWarning() {}

    public static void applySavedActionOrShowWarningDialog(Context context,
               DraftMessageData.CheckDraftTaskCallback callback, DraftMessageData data) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isSavedSendAsSms
                = prefs.getBoolean(MIXED_PARTICIPANTS_SEND_ACTION, false);

        if (isSavedSendAsSms) {
            sendMessageAsSms(callback, data);
        } else {
            showDialogMixedParticipantsWarning(context, callback, data);
        }
    }

    private static void sendMessageAsSms(DraftMessageData.CheckDraftTaskCallback callback, DraftMessageData data) {
        callback.onDraftChecked(data, DraftMessageData.CheckDraftForSendTask.RESULT_PASSED);
    }

    private static void showDialogMixedParticipantsWarning(final Context context,
                                                           final DraftMessageData.CheckDraftTaskCallback callback, final DraftMessageData data) {
        View warningView = View.inflate(context, R.layout.dialog_mixed_participants_warning, null);
        final CheckBox dontShowAgain = (CheckBox) warningView.findViewById(R.id.dont_show_again);

        final AlertDialog warning = new AlertDialog.Builder(context)
                .setTitle(context.getResources().getString(R.string.mixed_participants_warning_dialog_title))
                .setView(warningView)
                .setPositiveButton(R.string.mixed_participants_send_as_sms_action,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (dontShowAgain.isChecked()) {
                                    saveActionMixedParticipantsSendAsSms(context, true);
                                }
                                sendMessageAsSms(callback, data);
                            }
                        })
                .setNegativeButton(R.string.mixed_participants_cancel_action, null)
                .create();

        warning.show();

        Button sendAsSms = warning.getButton(DialogInterface.BUTTON_POSITIVE);
        sendAsSms.setTextColor(context.getResources().getColor(R.color.button_bar_action_button_text_color));
        Button cancel = warning.getButton(DialogInterface.BUTTON_NEGATIVE);
        cancel.setTextColor(context.getResources().getColor(R.color.dialog_btn_grey));
    }

    private static void saveActionMixedParticipantsSendAsSms(Context context, boolean sendAsSms) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putBoolean(MIXED_PARTICIPANTS_SEND_ACTION, sendAsSms)
                .apply();
    }
}
