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

package com.android.messaging.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.Interpolator;
import android.view.animation.ScaleAnimation;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.ui.SnackBar;
import com.android.messaging.ui.SnackBar.Placement;
import com.android.messaging.ui.SnackBarInteraction;
import com.android.messaging.ui.SnackBarManager;
import com.android.messaging.ui.UIIntents;

import java.util.List;

public class UiUtils {
    /** MediaPicker transition duration in ms */
    public static final int MEDIAPICKER_TRANSITION_DURATION =
            getApplicationContext().getResources().getInteger(
                    R.integer.mediapicker_transition_duration);
    /** Compose transition duration in ms */
    public static final int COMPOSE_TRANSITION_DURATION =
            getApplicationContext().getResources().getInteger(
                    R.integer.compose_transition_duration);
    /** Generic duration for revealing/hiding a view */
    public static final int REVEAL_ANIMATION_DURATION =
            getApplicationContext().getResources().getInteger(
                    R.integer.reveal_view_animation_duration);

    public static final Interpolator DEFAULT_INTERPOLATOR = new CubicBezierInterpolator(
            0.4f, 0.0f, 0.2f, 1.0f);

    public static final Interpolator EASE_IN_INTERPOLATOR = new CubicBezierInterpolator(
            0.4f, 0.0f, 0.8f, 0.5f);

    public static final Interpolator EASE_OUT_INTERPOLATOR = new CubicBezierInterpolator(
            0.0f, 0.0f, 0.2f, 1f);

    /** Show a simple toast at the bottom */
    public static void showToastAtBottom(final int messageId) {
        UiUtils.showToastAtBottom(getApplicationContext().getString(messageId));
    }

    /** Show a simple toast at the bottom */
    public static void showToastAtBottom(final String message) {
        final Toast toast = Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0);
        toast.show();
    }

    /** Show a simple toast at the default position */
    public static void showToast(final int messageId) {
        final Toast toast = Toast.makeText(getApplicationContext(),
                getApplicationContext().getString(messageId), Toast.LENGTH_LONG);
        toast.setGravity(Gravity.CENTER_HORIZONTAL, 0, 0);
        toast.show();
    }

    /** Show a simple toast at the default position */
    public static void showToast(final int pluralsMessageId, final int count) {
        final Toast toast = Toast.makeText(getApplicationContext(),
                getApplicationContext().getResources().getQuantityString(pluralsMessageId, count),
                Toast.LENGTH_LONG);
        toast.setGravity(Gravity.CENTER_HORIZONTAL, 0, 0);
        toast.show();
    }

    public static void showSnackBar(final Context context, @NonNull final View parentView,
            final String message, @Nullable final Runnable runnable, final int runnableLabel,
            @Nullable final List<SnackBarInteraction> interactions) {
        Assert.notNull(context);
        SnackBar.Action action = null;
        switch (runnableLabel) {
            case SnackBar.Action.SNACK_BAR_UNDO:
                action = SnackBar.Action.createUndoAction(runnable);
                break;
            case SnackBar.Action.SNACK_BAR_RETRY:
                action =  SnackBar.Action.createRetryAction(runnable);
                break;
            default :
                break;
        }

        showSnackBarWithCustomAction(context, parentView, message, action, interactions,
                                        null /* placement */);
    }

    public static void showSnackBar(final Context context, @NonNull final View parentView,
            final String message) {
        Assert.notNull(context);
        Assert.isTrue(!TextUtils.isEmpty(message));
        SnackBarManager.get()
            .newBuilder(parentView)
            .setText(message)
            .show();
    }

    public static void showSnackBarWithCustomAction(final Context context,
            @NonNull final View parentView,
            @NonNull final String message,
            @NonNull final SnackBar.Action action,
            @Nullable final List<SnackBarInteraction> interactions,
            @Nullable final Placement placement) {
        Assert.notNull(context);
        Assert.isTrue(!TextUtils.isEmpty(message));
        Assert.notNull(action);
        SnackBarManager.get()
            .newBuilder(parentView)
            .setText(message)
            .setAction(action)
            .withInteractions(interactions)
            .withPlacement(placement)
            .show();
    }

    /**
     * Run the given runnable once after the next layout pass of the view.
     */
    public static void doOnceAfterLayoutChange(final View view, final Runnable runnable) {
        final OnLayoutChangeListener listener = new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(final View v, final int left, final int top, final int right,
                    final int bottom, final int oldLeft, final int oldTop, final int oldRight,
                    final int oldBottom) {
                // Call the runnable outside the layout pass because very few actions are allowed in
                // the layout pass
                ThreadUtil.getMainThreadHandler().post(runnable);
                view.removeOnLayoutChangeListener(this);
            }
        };
        view.addOnLayoutChangeListener(listener);
    }

    public static boolean isLandscapeMode() {
        return Factory.get().getApplicationContext().getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    private static Context getApplicationContext() {
        return Factory.get().getApplicationContext();
    }

    public static CharSequence commaEllipsize(
            final String text,
            final TextPaint paint,
            final int width,
            final String oneMore,
            final String more) {
        CharSequence ellipsized = TextUtils.commaEllipsize(
                text,
                paint,
                width,
                oneMore,
                more);
        if (TextUtils.isEmpty(ellipsized)) {
            ellipsized = text;
        }
        return ellipsized;
    }

    /**
     * Reveals/Hides a view with a scale animation from view center.
     * @param view the view to animate
     * @param desiredVisibility desired visibility (e.g. View.GONE) for the animated view.
     * @param onFinishRunnable an optional runnable called at the end of the animation
     */
    public static void revealOrHideViewWithAnimation(final View view, final int desiredVisibility,
            @Nullable final Runnable onFinishRunnable) {
        final boolean needAnimation = view.getVisibility() != desiredVisibility;
        if (needAnimation) {
            final float fromScale = desiredVisibility == View.VISIBLE ? 0F : 1F;
            final float toScale = desiredVisibility == View.VISIBLE ? 1F : 0F;
            final ScaleAnimation showHideAnimation =
                    new ScaleAnimation(fromScale, toScale, fromScale, toScale,
                            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                            ScaleAnimation.RELATIVE_TO_SELF, 0.5f);
            showHideAnimation.setDuration(REVEAL_ANIMATION_DURATION);
            showHideAnimation.setInterpolator(DEFAULT_INTERPOLATOR);
            showHideAnimation.setAnimationListener(new AnimationListener() {
                @Override
                public void onAnimationStart(final Animation animation) {
                }

                @Override
                public void onAnimationRepeat(final Animation animation) {
                }

                @Override
                public void onAnimationEnd(final Animation animation) {
                    if (onFinishRunnable != null) {
                        // Rather than running this immediately, we post it to happen next so that
                        // the animation will be completed so that the view can be detached from
                        // it's window.  Otherwise, we may leak memory.
                        ThreadUtil.getMainThreadHandler().post(onFinishRunnable);
                    }
                }
            });
            view.clearAnimation();
            view.startAnimation(showHideAnimation);
            // We are playing a view Animation; unlike view property animations, we can commit the
            // visibility immediately instead of waiting for animation end.
            view.setVisibility(desiredVisibility);
        } else if (onFinishRunnable != null) {
            // Make sure onFinishRunnable is always executed.
            ThreadUtil.getMainThreadHandler().post(onFinishRunnable);
        }
    }

    public static Rect getMeasuredBoundsOnScreen(final View view) {
        final int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1],
                location[0] + view.getMeasuredWidth(), location[1] + view.getMeasuredHeight());
    }

    public static void setStatusBarColor(final Activity activity, final int color) {
        // To achieve the appearance of an 80% opacity blend against a black background,
        // each color channel is reduced in value by 20%.
        final int blendedRed = (int) Math.floor(0.8 * Color.red(color));
        final int blendedGreen = (int) Math.floor(0.8 * Color.green(color));
        final int blendedBlue = (int) Math.floor(0.8 * Color.blue(color));

        activity.getWindow().setStatusBarColor(
                Color.rgb(blendedRed, blendedGreen, blendedBlue));
    }

    public static boolean isRtlMode() {
        return Factory.get().getApplicationContext().getResources()
                .getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    /**
     * Check if the activity needs to be redirected to permission check
     * @return true if {@link Activity#finish()} was called because redirection was performed
     */
    public static boolean redirectToPermissionCheckIfNeeded(final Activity activity) {
        if (!OsUtil.hasRequiredPermissions()) {
            UIIntents.get().launchPermissionCheckActivity(activity);
        } else {
            // No redirect performed
            return false;
        }

        // Redirect performed
        activity.finish();
        return true;
    }

    /**
     * Called to check if all conditions are nominal and a "go" for some action, such as deleting
     * a message, that requires this app to be the default app. This is also a precondition
     * required for sending a draft.
     * @return true if all conditions are nominal and we're ready to send a message
     */
    public static boolean isReadyForAction() {
        final PhoneUtils phoneUtils = PhoneUtils.getDefault();

        // Have all the conditions been met:
        // Supports SMS?
        // Is the default sms app?
        return phoneUtils.isSmsCapable() &&
                phoneUtils.isDefaultSmsApp();
    }

    /**
     * Called to check if a message or conversation can be deleted - it needs to be the default
     * sms app
     * @return true if all conditions are nominal and we're ready to delete a message
     */
    public static boolean isReadyForDeleteAction() {
        final PhoneUtils phoneUtils = PhoneUtils.getDefault();

        // Is the default sms app?
        return phoneUtils.isDefaultSmsApp();
    }

    /**
     * Get the activity that's hosting the view, typically casting view.getContext() as an Activity
     * is sufficient, but sometimes the context is a context wrapper, in which case we need to case
     * the base context
     */
    public static Activity getActivity(final View view) {
        if (view == null) {
            return null;
        }
        return getActivity(view.getContext());
    }

    /**
     * Get the activity for the supplied context, typically casting context as an Activity
     * is sufficient, but sometimes the context is a context wrapper, in which case we need to case
     * the base context
     */
    public static Activity getActivity(final Context context) {
        if (context == null) {
            return null;
        }
        if (context instanceof Activity) {
            return (Activity) context;
        }
        if (context instanceof ContextWrapper) {
            return getActivity(((ContextWrapper) context).getBaseContext());
        }

        // We've hit a non-activity context such as an app-context
        return null;
    }

    public static RemoteViews getWidgetMissingPermissionView(final Context context) {
        return new RemoteViews(context.getPackageName(), R.layout.widget_missing_permission);
    }
}
