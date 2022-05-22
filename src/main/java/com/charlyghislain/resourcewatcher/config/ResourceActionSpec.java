package com.charlyghislain.resourcewatcher.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ResourceActionSpec {

    private ResourceActionType actionType;

    // Annotate another resource with timestampt
    private String annotatedResourceNamespace;
    private AnnotatedResourceKind annotatedResourceKind;
    private List<String> annotatedResourceFieldSelectors = new ArrayList<>();
    private List<String> annotatedResourceLabelsSelectors = new ArrayList<>();
    private String annotatedResourceAnnotationName = "com.charlyghislain.resourcewatcher.timestamp";

}
