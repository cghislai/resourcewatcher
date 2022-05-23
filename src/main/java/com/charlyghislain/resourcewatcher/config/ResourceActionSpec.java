package com.charlyghislain.resourcewatcher.config;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
public class ResourceActionSpec {

    @ToString.Include
    private ResourceActionType actionType;

    // Annotate another resource with timestampt
    @ToString.Include
    private String annotatedResourceNamespace;
    @ToString.Include
    private AnnotatedResourceKind annotatedResourceKind;
    private List<String> annotatedResourceFieldSelectors = new ArrayList<>();
    private List<String> annotatedResourceLabelsSelectors = new ArrayList<>();
    private String annotatedResourceAnnotationName = "com.charlyghislain.resourcewatcher.timestamp";

}
