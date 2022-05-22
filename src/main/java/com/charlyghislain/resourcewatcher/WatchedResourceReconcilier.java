package com.charlyghislain.resourcewatcher;

import com.charlyghislain.resourcewatcher.config.ResourceActionSpec;
import com.charlyghislain.resourcewatcher.config.ResourceActionType;
import com.charlyghislain.resourcewatcher.config.WatchedResource;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.extended.event.legacy.EventRecorder;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.Yaml;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

public class WatchedResourceReconcilier<T extends KubernetesObject> implements Reconciler {

    private CoreV1Api coreV1Api;
    private WatchedResource resourceWatcherConfig;
    private SharedIndexInformer<? extends KubernetesObject> indexInformer;
    private final Lister<? extends KubernetesObject> lister;
    private final EventRecorder eventRecorder;

    public WatchedResourceReconcilier(CoreV1Api coreV1Api, WatchedResource watchedResource,
                                      SharedIndexInformer<? extends KubernetesObject> informer,
                                      EventRecorder recorder) {
        this.coreV1Api = coreV1Api;
        this.resourceWatcherConfig = watchedResource;
        this.indexInformer = informer;
        this.eventRecorder = recorder;
        this.lister = new Lister<>(informer.getIndexer());
    }

    @Override
    public Result reconcile(Request request) {
        KubernetesObject indexedObject = lister.get(request.getName());
        if (indexedObject == null) {
            ResourceWatcher.LOG.warning("Resource not found in index: " + request.getName() + " in namespace " + request.getNamespace());
            return new Result(false);
        }

        V1ObjectMeta resourceMetadata = indexedObject.getMetadata();
        String resourceName = resourceMetadata.getName();
        String resourceVersion = resourceMetadata.getResourceVersion();

        ResourceWatcher.LOG.fine("Reconciling " + resourceName + " at " + resourceVersion);
        List<ResourceActionSpec> actionList = resourceWatcherConfig.getActionList();

        Map<ResourceActionSpec, Boolean> resultList = new HashMap<>();
        for (ResourceActionSpec actionSpec : actionList) {
            Boolean success = executeAction(indexedObject, actionSpec);
            resultList.put(actionSpec, success);
        }

        return new Result(false);
    }

    private Boolean executeAction(KubernetesObject kubernetesObject, ResourceActionSpec actionSpec) {
        ResourceActionType actionType = actionSpec.getActionType();
        V1ObjectMeta resourceMetadata = kubernetesObject.getMetadata();
        String resourceName = resourceMetadata.getName();
        String resourceVersion = resourceMetadata.getResourceVersion();
        ResourceWatcher.LOG.fine(" - executing action " + actionType + " for " + resourceName + " " + resourceVersion);

        try {
            switch (actionType) {
                case ANNOTATE -> {
                    executeAnnotateResourceAction(actionSpec);
                    return true;
                }
                default -> throw new IllegalArgumentException("Action not supported: " + actionType);
            }
        } catch (Exception e) {
            ResourceWatcher.LOG.log(Level.SEVERE, "Unable to execute action " + actionSpec + " on " + resourceName + " " + kubernetesObject, e);
            return false;
        }
    }

    private void executeAnnotateResourceAction(ResourceActionSpec actionSpec) throws Exception {
        String annotatedKind = actionSpec.getAnnotatedResourceKind();
        String annotatedResourceNamespace = actionSpec.getAnnotatedResourceNamespace();
        List<String> annotatedResourceFieldSelectors = actionSpec.getAnnotatedResourceFieldSelectors();
        List<String> annotatedResourceLabelsSelectors = actionSpec.getAnnotatedResourceLabelsSelectors();

        switch (annotatedKind.toLowerCase(Locale.ROOT)) {
            case "pod": {
                V1PodList v1PodList = coreV1Api.listNamespacedPod(annotatedResourceNamespace, null, null, null,
                        String.join(",", annotatedResourceFieldSelectors),
                        String.join(",", annotatedResourceLabelsSelectors),
                        null, null, null, null, false);
                if (v1PodList.getItems().isEmpty()) {
                    throw new Exception("No pod found");
                }
                for (V1Pod pod : v1PodList.getItems()) {
                    annotatePod(pod, actionSpec);
                }
            }
        }
    }

    //easiest way (for devloper) is to just export and import
    public static <T> T deepCopy(T rd) {
        Class<T> resourceClass = (Class<T>) rd.getClass();
        return Yaml.loadAs(Yaml.dump(rd), resourceClass);
    }

    private void annotatePod(V1Pod pod, ResourceActionSpec actionSpec) throws Exception {
        V1Pod updatedPod = deepCopy(pod);
        String annotationName = actionSpec.getAnnotatedResourceAnnotationName();
        String annotationValue = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now());
        Map<String, String> annotations = new HashMap<>(pod.getMetadata().getAnnotations());
        annotations.put(annotationName, annotationValue);
        updatedPod.getMetadata().setAnnotations(annotations);

        String podName = pod.getMetadata().getName();
        String podNamespace = pod.getMetadata().getNamespace();
        try {
            coreV1Api.replaceNamespacedPod(podName, podNamespace, pod, null, null, null);
            ResourceWatcher.LOG.fine("Updated pod annotation on " + podName + " in namesapce " + podNamespace);
        } catch (ApiException e) {
            throw new Exception("Unable to update annotations on pod " + podName + " in namespace " + podNamespace, e);
        }
    }
}
