package com.charlyghislain.resourcewatcher.config;

import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

public enum WatchedResourceKind {
    SECRET("secret"),
    CONFIGMAP("configmap"),
    POD("pod");

    @Getter
    private String stringValue;

    WatchedResourceKind(String stringvalue) {

        this.stringValue = stringvalue;
    }

    public static Optional<WatchedResourceKind> parseName(String resourceKind) {
        return Arrays.stream(WatchedResourceKind.values())
                .filter(v -> v.getStringValue().equalsIgnoreCase(resourceKind))
                .findAny();
    }
}
