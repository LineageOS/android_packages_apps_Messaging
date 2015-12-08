package com.android.messaging.metrics;

class EventStatistic {
    private String category;
    private String action;
    private String label;
    private int value;

    public EventStatistic(String category, String action, String label, int value) {
        this.category = category;
        this.action = action;
        this.label = label;
        this.value = value;
    }

    public String getCategory() {
        return category;
    }

    public String getLabel() {
        return label;
    }

    public String getAction() {
        return action;
    }

    public int getValue() {
        return value;
    }

    public static class Builder {
        private String category;
        private String action;
        private String label;
        private int value;

        public Builder setCategory(String category) {
            this.category = category;
            return this;
        }

        public Builder setAction(String action) {
            this.action = action;
            return this;
        }

        public Builder setLabel(String label) {
            this.label = label;
            return this;
        }

        public Builder setValue(int value) {
            this.value = value;
            return this;
        }

        public EventStatistic build() {
            return new EventStatistic(category, action, label, value);
        }
    }
}
