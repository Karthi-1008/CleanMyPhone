package com.cleanmyphone.launcher;

import android.graphics.drawable.Drawable;

public class AppModel implements Comparable<AppModel> {
    private final String label;
    private final String packageName;
    private final String activityName;
    private final Drawable icon;

    public AppModel(String label, String packageName, String activityName, Drawable icon) {
        this.label = label != null ? label : "";
        this.packageName = packageName;
        this.activityName = activityName;
        this.icon = icon;
    }

    public String getLabel() {
        return label;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getActivityName() {
        return activityName;
    }

    public Drawable getIcon() {
        return icon;
    }

    @Override
    public int compareTo(AppModel other) {
        return this.label.compareToIgnoreCase(other.label);
    }
}
