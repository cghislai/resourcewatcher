package com.charlyghislain.resourcewatcher.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class WatchedResource {

    private String kind = "";
    private String namespace = "";

    private List<String> labelSelectors = new ArrayList<>();
    private List<String> fieldSelectors = new ArrayList<>();

    private boolean watchAdd = true;
    private boolean watchUpdate = true;
    private boolean watchDelete = false;

    private List<ResourceActionSpec> actionList;

}
