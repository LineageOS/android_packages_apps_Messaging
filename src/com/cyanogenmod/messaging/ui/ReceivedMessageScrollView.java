package com.cyanogenmod.messaging.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

import com.android.messaging.R;

/**
 * Custom ScrollView that only draws a bottom fading edge
 */
public class ReceivedMessageScrollView extends ScrollView {

    private int mFadingEdgeColor;

    public ReceivedMessageScrollView(Context context) {
        super(context);
        init();
    }

    public ReceivedMessageScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ReceivedMessageScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public ReceivedMessageScrollView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        mFadingEdgeColor = getContext().getResources().getColor(R.color.qm_fading_edge_color);
    }

    @Override
    protected float getTopFadingEdgeStrength() {
        // don't draw a top fading edge
        return 0;
    }

    @Override
    public int getSolidColor() {
        return mFadingEdgeColor;
    }
}
