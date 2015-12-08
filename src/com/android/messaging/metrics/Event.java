package com.android.messaging.metrics;

class Event {
    static final String DEFAULT_LABEL_NAME = "daily";

    private String category;
    private String action;

    public Event(String category, String action) {
        this.category = category;
        this.action = action;
    }

    public String getCategory() {
        return category;
    }

    public String getAction() {
        return action;
    }

    public String getLabel() {
        return DEFAULT_LABEL_NAME;
    }
}
