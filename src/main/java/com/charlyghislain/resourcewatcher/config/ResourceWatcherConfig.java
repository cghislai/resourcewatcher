package com.charlyghislain.resourcewatcher.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ResourceWatcherConfig {

    private List<WatchedResource> watchedResourceList = new ArrayList<>();

    private Boolean debug;

}
