package com.charlyghislain.resourcewatcher;

import com.charlyghislain.resourcewatcher.config.AnnotatedResourceKind;
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
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentList;
import io.kubernetes.client.openapi.models.V1DeploymentSpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.util.Yaml;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class WatchedResourceReconcilier<T extends KubernetesObject> implements Reconciler {

    private AppsV1Api appsV1Api;
    private CoreV1Api coreV1Api;
    private WatchedResource resourceWatcherConfig;
    private SharedIndexInformer<? extends KubernetesObject> indexInformer;
    private final EventRecorder eventRecorder;

    public WatchedResourceReconcilier(CoreV1Api coreV1Api, AppsV1Api appsV1Api,
                                      WatchedResource watchedResource,
                                      SharedIndexInformer<? extends KubernetesObject> informer,
                                      EventRecorder recorder) {
        this.coreV1Api = coreV1Api;
        this.appsV1Api = appsV1Api;
        this.resourceWatcherConfig = watchedResource;
        this.indexInformer = informer;
        this.eventRecorder = recorder;
    }

    @Override
    public Result reconcile(Request request) {
        Lister<? extends KubernetesObject> lister;
        if (request.getNamespace() == null) {
            lister = new Lister<>(this.indexInformer.getIndexer());
        } else {
            lister = new Lister<>(this.indexInformer.getIndexer(), request.getNamespace());
        }

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
            if (actionType == ResourceActionType.ANNOTATE_WITH_TIMESTAMP) {
                executeAnnotateResourceAction(actionSpec);
                return true;
            }
            throw new IllegalArgumentException("Action not supported: " + actionType);
        } catch (Exception e) {
            ResourceWatcher.LOG.log(Level.SEVERE, "Unable to execute action " + actionSpec + " on " + resourceName + " " + kubernetesObject, e);
            return false;
        }
    }

    private void executeAnnotateResourceAction(ResourceActionSpec actionSpec) throws Exception {
        AnnotatedResourceKind annotatedKind = actionSpec.getAnnotatedResourceKind();
        String annotatedResourceNamespace = actionSpec.getAnnotatedResourceNamespace();
        List<String> annotatedResourceFieldSelectors = actionSpec.getAnnotatedResourceFieldSelectors();
        List<String> annotatedResourceLabelsSelectors = actionSpec.getAnnotatedResourceLabelsSelectors();

        switch (annotatedKind) {
            case DEPLOYMENT_POD_TEMPLATE: {
                V1DeploymentList v1DeploymentList1 = appsV1Api.listNamespacedDeployment(annotatedResourceNamespace, null, null, null,
                        String.join(",", annotatedResourceFieldSelectors),
                        String.join(",", annotatedResourceLabelsSelectors),
                        null, null, null, null, null
                );

                if (v1DeploymentList1.getItems().isEmpty()) {
                    throw new Exception("No deployment found");
                }
                for (V1Deployment deployment : v1DeploymentList1.getItems()) {
                    annotateDeploymentPodSpec(deployment, actionSpec);
                }
            }
        }
    }

    private void annotateDeploymentPodSpec(V1Deployment deployment, ResourceActionSpec actionSpec) throws Exception {
        V1Deployment updatedDeployment = deepCopy(deployment);
        String annotationName = actionSpec.getAnnotatedResourceAnnotationName();
        String annotationValue = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now());

        V1DeploymentSpec spec = updatedDeployment.getSpec();
        V1PodTemplateSpec podTemplateSpec = spec.getTemplate();
        V1ObjectMeta podTemplateSpecMetadata = podTemplateSpec.getMetadata();
        HashMap<String, String> newPodannotations = new HashMap<>(podTemplateSpecMetadata.getAnnotations());
        newPodannotations.put(annotationName, annotationValue);
        podTemplateSpecMetadata.setAnnotations(newPodannotations);

        String deploymentName = deployment.getMetadata().getName();
        String deploymentNamespace = deployment.getMetadata().getNamespace();
        try {
            appsV1Api.replaceNamespacedDeployment(deploymentName, deploymentNamespace, updatedDeployment, null, null, null);
            ResourceWatcher.LOG.fine("Updated pod spec annotations on deployment " + deploymentName + " in namesapce " + deploymentNamespace);
        } catch (ApiException e) {
            throw new Exception("Unable to update pod spec annotations on deployment " + deploymentName + " in namespace " + deploymentNamespace, e);
        }
    }

    //easiest way (for devloper) is to just export and import
    public static <T> T deepCopy(T rd) {
        Class<T> resourceClass = (Class<T>) rd.getClass();
        return Yaml.loadAs(Yaml.dump(rd), resourceClass);
    }

}
