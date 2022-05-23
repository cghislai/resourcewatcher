package com.charlyghislain.resourcewatcher;

import com.charlyghislain.resourcewatcher.config.ResourceWatcherConfig;
import com.charlyghislain.resourcewatcher.config.WatchedResource;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.ControllerManager;
import io.kubernetes.client.extended.controller.DefaultControllerWatch;
import io.kubernetes.client.extended.controller.LeaderElectingController;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.builder.ControllerManagerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.event.legacy.EventBroadcaster;
import io.kubernetes.client.extended.event.legacy.LegacyEventBroadcaster;
import io.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.kubernetes.client.extended.leaderelection.LeaderElector;
import io.kubernetes.client.extended.leaderelection.resourcelock.EndpointsLock;
import io.kubernetes.client.extended.workqueue.WorkQueue;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1EventSource;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import io.kubernetes.client.util.CallGeneratorParams;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.Yaml;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class ResourceWatcher {

    public final static Logger LOG = Logger.getLogger(ResourceWatcher.class.getSimpleName());

    public static void main(String[] args) {
        LOG.fine("Starting ResourceWatcher");
        String configFilePath = Optional.ofNullable(System.getenv("RESOURCE_WATCHER_CONFIG_PATH"))
                .filter(s -> !s.isBlank())
                .orElse("/var/run/config/resourcewatcher.yaml");
        LOG.fine("Config file path: " + configFilePath);

        Path configPath = Paths.get(configFilePath);
        if (!Files.exists(configPath)) {
            LOG.severe("No config found at " + configPath);
            System.exit(1);
            return;
        }

        ResourceWatcherConfig config;
        try {
            config = ResourceWatcherConfigFactory.fromYamlFile(configPath);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Config at " + configPath + " cannot be read: " + e.getMessage(), e);
            System.exit(1);
            return;
        }

        boolean debug = Optional.ofNullable(config.getDebug()).orElse(false);
        tryReadLoggingConfig(debug);
        if (debug) {
            String configString = Yaml.dump(config);
            LOG.log(Level.INFO, "Configuration: \n" + configString);
        }

        ApiClient apiClient;
        try {
            apiClient = ClientBuilder.cluster().build();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Unable to create lubernetes cluter client: " + e.getMessage(), e);
            System.exit(1);
            return;
        }
        if (debug) {
            apiClient.setDebugging(true);
        }

//        OkHttpClient httpClient = apiClient.getHttpClient().newBuilder().readTimeout(0, TimeUnit.SECONDS).build();
//        apiClient.setHttpClient(httpClient);
        Configuration.setDefaultApiClient(apiClient);

        CoreV1Api coreV1Api = new CoreV1Api(apiClient);
        AppsV1Api appsV1Api = new AppsV1Api(apiClient);

        // instantiating an informer-factory, and there should be only one informer-factory
        // globally.
        SharedInformerFactory informerFactory = new SharedInformerFactory();
        EventBroadcaster eventBroadcaster = new LegacyEventBroadcaster(coreV1Api);
        ControllerManagerBuilder controllerManagerBuilder = ControllerBuilder.controllerManagerBuilder(informerFactory);

        for (WatchedResource watchedResource : config.getWatchedResourceList()) {
            String resourceLabel = watchedResource.getKind() + " in namespace " + watchedResource.getNamespace();
            LOG.fine("Creating controller for watched resource " + resourceLabel);
            try {
                Controller controller = createController(coreV1Api, appsV1Api, informerFactory, eventBroadcaster, watchedResource);
                controllerManagerBuilder.addController(controller);
                LOG.fine(" - added controller " + controller);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Unable to create controller for " + resourceLabel + " : " + e.getMessage(), e);
            }
        }

        informerFactory.startAllRegisteredInformers();
        ControllerManager controllerManager = controllerManagerBuilder.build();

        String controllerNamespace = config.getNamespace();
        String leaseName = config.getLeaseName();
        LeaderElectionConfig leaderElectionConfig = new LeaderElectionConfig(
                new EndpointsLock(controllerNamespace, leaseName, "resourcewatcher"),
                Duration.ofMillis(10000),
                Duration.ofMillis(8000),
                Duration.ofMillis(5000)
        );
        LeaderElectingController leaderElectingController = new LeaderElectingController(
                new LeaderElector(leaderElectionConfig),
                controllerManager
        );
        leaderElectingController.run();

        LOG.fine("ResourceWatcher completed");
    }

    private static Controller createController(CoreV1Api coreV1Api, AppsV1Api appsV1Api, SharedInformerFactory informerFactory, EventBroadcaster eventBroadcaster,
                                               WatchedResource watchedResource) throws Exception {
        SharedIndexInformer<? extends KubernetesObject> nodeInformer = createNodeInformer(informerFactory, watchedResource, coreV1Api);

        WatchedResourceReconcilier<? extends KubernetesObject> reconcilier = new WatchedResourceReconcilier<>(
                coreV1Api, appsV1Api, watchedResource, nodeInformer,
                eventBroadcaster.newRecorder(new V1EventSource().host("localhost").component("resource-watcher")));

        Function<WorkQueue<Request>, DefaultControllerWatch<? extends KubernetesObject>> resourceControllerFactory = getResourceControllerFactory(watchedResource);
        Controller controller = ControllerBuilder.defaultBuilder(informerFactory)
                .watch(resourceControllerFactory::apply)
                .withReconciler(reconcilier) // required, set the actual reconciler
                .withName("resource-watcher-controller") // optional, set name for controller
                .withWorkerCount(2) // optional, set worker thread count
                .withReadyFunc(nodeInformer::hasSynced) // optional, only starts controller when the
                // cache has synced up
                .build();
        return controller;
    }

    private static Function<WorkQueue<Request>, DefaultControllerWatch<? extends KubernetesObject>> getResourceControllerFactory(WatchedResource watchedResource) throws Exception {
        String resourceKind = watchedResource.getKind();
        switch (resourceKind.toLowerCase(Locale.ROOT)) {
            case "secret": {
                return (WorkQueue<Request> workQueue) -> buildSecretController(watchedResource, workQueue);
            }
            case "configmap": {
                return (WorkQueue<Request> workQueue) -> buildConfigMapController(watchedResource, workQueue);
            }
            case "pod": {
                return (WorkQueue<Request> workQueue) -> buildPodController(watchedResource, workQueue);
            }
            default:
                throw new Exception("Unsupported resource to watch: " + resourceKind);
        }
    }

    private static DefaultControllerWatch<V1Pod> buildPodController(WatchedResource watchedResource, WorkQueue<Request> workQueue) {
        return ControllerBuilder.controllerWatchBuilder(V1Pod.class, workQueue)
                .withWorkQueueKeyFunc((V1Pod node) -> new Request(node.getMetadata().getNamespace(), node.getMetadata().getName())) // optional, default to
                .withOnAddFilter((V1Pod createdNode) -> checkObjectWatchedOnAdd(createdNode, watchedResource))
                .withOnUpdateFilter((V1Pod oldNode, V1Pod newNode) -> checkObjectWatchedOnUpdate(oldNode, watchedResource))
                .withOnDeleteFilter((V1Pod deletedNode, Boolean stateUnknown) -> checkObjectWatchedOnDelete(deletedNode, watchedResource))
                .build();
    }

    private static DefaultControllerWatch<V1Secret> buildSecretController(WatchedResource watchedResource, WorkQueue<Request> workQueue) {
        return ControllerBuilder.controllerWatchBuilder(V1Secret.class, workQueue)
                .withWorkQueueKeyFunc((V1Secret node) -> new Request(node.getMetadata().getNamespace(), node.getMetadata().getName())) // optional, default to
                .withOnAddFilter((V1Secret createdNode) -> checkObjectWatchedOnAdd(createdNode, watchedResource))
                .withOnUpdateFilter((V1Secret oldNode, V1Secret newNode) -> checkObjectWatchedOnUpdate(oldNode, watchedResource))
                .withOnDeleteFilter((V1Secret deletedNode, Boolean stateUnknown) -> checkObjectWatchedOnDelete(deletedNode, watchedResource))
                .build();
    }

    private static DefaultControllerWatch<V1ConfigMap> buildConfigMapController(WatchedResource watchedResource, WorkQueue<Request> workQueue) {
        return ControllerBuilder.controllerWatchBuilder(V1ConfigMap.class, workQueue)
                .withWorkQueueKeyFunc((V1ConfigMap node) -> new Request(node.getMetadata().getNamespace(), node.getMetadata().getName())) // optional, default to
                .withOnAddFilter((V1ConfigMap createdNode) -> checkObjectWatchedOnAdd(createdNode, watchedResource))
                .withOnUpdateFilter((V1ConfigMap oldNode, V1ConfigMap newNode) -> checkObjectWatchedOnUpdate(oldNode, watchedResource))
                .withOnDeleteFilter((V1ConfigMap deletedNode, Boolean stateUnknown) -> checkObjectWatchedOnDelete(deletedNode, watchedResource))
                .build();
    }

    private static SharedIndexInformer<? extends KubernetesObject> createNodeInformer(SharedInformerFactory informerFactory, WatchedResource watchedResource, CoreV1Api coreV1Api) throws Exception {
        String kind = watchedResource.getKind();
        String namespace = watchedResource.getNamespace();
        String fieldSelector = String.join(",", watchedResource.getFieldSelectors());
        String labelSelector = String.join(",", watchedResource.getLabelSelectors());
        switch (kind.toLowerCase(Locale.ROOT)) {
            case "pod": {
                return informerFactory.sharedIndexInformerFor((CallGeneratorParams params) -> coreV1Api.listNamespacedPodCall(
                                namespace,
                                null,
                                null,
                                null,
                                fieldSelector,
                                labelSelector,
                                null,
                                params.resourceVersion,
                                null,
                                params.timeoutSeconds,
                                params.watch,
                                null),
                        V1Pod.class,
                        V1PodList.class);
            }
            case "secret": {
                return informerFactory.sharedIndexInformerFor((CallGeneratorParams params) -> coreV1Api.listNamespacedSecretCall(
                                namespace,
                                null,
                                null,
                                null,
                                fieldSelector,
                                labelSelector,
                                null,
                                params.resourceVersion,
                                null,
                                params.timeoutSeconds,
                                params.watch,
                                null),
                        V1Secret.class,
                        V1SecretList.class);
            }
            case "configmap": {
                return informerFactory.sharedIndexInformerFor((CallGeneratorParams params) -> coreV1Api.listNamespacedConfigMapCall(
                                namespace,
                                null,
                                null,
                                null,
                                fieldSelector,
                                labelSelector,
                                null,
                                params.resourceVersion,
                                null,
                                params.timeoutSeconds,
                                params.watch,
                                null),
                        V1ConfigMap.class,
                        V1ConfigMapList.class);
            }
            default:
                throw new Exception("Unsupported resource to watch: " + kind);

        }
    }

    private static boolean checkObjectWatchedOnAdd(KubernetesObject kubernetesObject, WatchedResource config) {
        boolean watchAdd = config.isWatchAdd();
        if (!watchAdd) {
            return false;
        }
        return checkObjectWatched(kubernetesObject, config);
    }


    private static boolean checkObjectWatchedOnUpdate(KubernetesObject kubernetesObject, WatchedResource config) {
        boolean watchUpdate = config.isWatchUpdate();
        if (!watchUpdate) {
            return false;
        }
        return checkObjectWatched(kubernetesObject, config);
    }

    private static boolean checkObjectWatchedOnDelete(KubernetesObject kubernetesObject, WatchedResource config) {
        boolean watchDelete = config.isWatchDelete();
        if (!watchDelete) {
            return false;
        }
        return checkObjectWatched(kubernetesObject, config);
    }

    private static boolean checkObjectWatched(KubernetesObject kubernetesObject, WatchedResource config) {
        return true;
    }

    private static void tryReadLoggingConfig(boolean debug) {
        try {
            String loggingPropertiesFileName = debug ? "logging.debug.properties" : "logging.properties";
            InputStream logPropsFile = ResourceWatcher.class.getClassLoader().getResourceAsStream(loggingPropertiesFileName);
            LogManager.getLogManager().readConfiguration(logPropsFile);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Unable to load logging config");
        }
    }
}
