package com.charlyghislain.resourcewatcher;

import com.charlyghislain.resourcewatcher.config.ResourceWatcherConfig;
import com.charlyghislain.resourcewatcher.config.WatchedResource;
import com.charlyghislain.resourcewatcher.config.WatchedResourceKind;
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
import io.kubernetes.client.extended.workqueue.DefaultRateLimitingQueue;
import io.kubernetes.client.extended.workqueue.WorkQueue;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1EventSource;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import io.kubernetes.client.util.CallGeneratorParams;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ResourceWatcher {

    public final static Logger LOG = Logger.getLogger(ResourceWatcher.class.getSimpleName());
    public static final String COMPONENT_NAME = "resource-watcher";

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

        // Cannot debug client using watch.
//        if (debug) {
//            apiClient.CsetDebugging(true);
//        }

        // Needs unlimited read timeout to watch - handled by the advanced api client it appears
        Configuration.setDefaultApiClient(apiClient);

        CoreV1Api coreV1Api = new CoreV1Api(apiClient);
        AppsV1Api appsV1Api = new AppsV1Api(apiClient);

        EventBroadcaster eventBroadcaster = new LegacyEventBroadcaster(coreV1Api);
        SharedInformerFactory informerFactory = new SharedInformerFactory();
        ControllerManagerBuilder controllerManagerBuilder = ControllerBuilder.controllerManagerBuilder(informerFactory);

        List<WatchedResource> watchedResourceList = config.getWatchedResourceList();
        // We need a single cache (informers) for each api type
        // We probably need to list everything at the informer level, and use the controller filters to match the watched resource or not
        // We must require service account role to be ClusterRole bound to the relevant namespace to list resources across namespaces.

        Map<WatchedResourceKind, List<WatchedResource>> watchedResourceKindListMap = watchedResourceList.stream()
                .collect(Collectors.groupingBy(w -> {
                    WatchedResourceKind watchedResourceKind = WatchedResourceKind.parseName(w.getKind())
                            .orElseThrow(() -> new RuntimeException("Unhandled watched resource kind: " + w.getKind()));
                    return watchedResourceKind;
                }));

        List<SharedIndexInformer<? extends KubernetesObject>> allInformers = new ArrayList<>();
        for (WatchedResource watchedResource : watchedResourceList) {
            String resourceKindName = watchedResource.getKind();
            WatchedResourceKind watchedResourceKind = WatchedResourceKind.parseName(resourceKindName)
                    .orElse(null);
            if (watchedResourceKind == null) {
                LOG.log(Level.SEVERE, "Ignoring unhandled watched resource kind: " + resourceKindName);
                continue;
            }
            String resourceLabel = watchedResource.getKind() + " in namespace " + watchedResource.getNamespace();

            // We create an informer for each of our watched resources. We prefilled the global cache with cluster-wide informers
            SharedIndexInformer<? extends KubernetesObject> indexInformer = createNewWatchedResourceIndexer(
                    informerFactory, eventBroadcaster, coreV1Api, appsV1Api, watchedResourceKind, watchedResource
            );
            allInformers.add(indexInformer);
            LOG.fine("Created watcher for " + resourceLabel);

            try {
                Controller controller = createController(coreV1Api, appsV1Api, indexInformer, eventBroadcaster, watchedResourceKind, watchedResource);
                controllerManagerBuilder.addController(controller);
                LOG.fine("Created controller for " + resourceLabel);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Unable to create controller for " + resourceLabel + " : " + e.getMessage(), e);
                throw new RuntimeException(e);
            }

        }

        // Start all informer manually, as only 1 per api type is stored in cache
        ExecutorService executorService = Executors.newCachedThreadPool();
        allInformers.forEach(i -> executorService.submit(i::run));
//        informerFactory.startAllRegisteredInformers();
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
        allInformers.forEach(SharedInformer::stop);
        executorService.shutdown();
    }


    private static <T extends KubernetesObject> Controller createController(CoreV1Api coreV1Api, AppsV1Api appsV1Api,
                                                                            SharedIndexInformer<T> indexInformer,
                                                                            EventBroadcaster eventBroadcaster,
                                                                            WatchedResourceKind watchedResourceKind,
                                                                            WatchedResource watchedResource) {
        String namespace = watchedResource.getNamespace();
        String kind = watchedResource.getKind();
        String randomString = StringUtils.getRandomAlphanumericString(8);
        String controllerName = MessageFormat.format("resource-watcher-{0}-{1}-{2}", namespace, kind, randomString);

        WatchedResourceReconcilier<T> reconcilier = new WatchedResourceReconcilier<>(
                coreV1Api, appsV1Api, watchedResource, indexInformer,
                eventBroadcaster.newRecorder(new V1EventSource().host("localhost").component(COMPONENT_NAME)));

        DefaultRateLimitingQueue<Request> rateLimitingQueue = new DefaultRateLimitingQueue<>(Executors.newSingleThreadExecutor());
        // We want to use the passed in instance which might not be the one cached
        Controller controller = ControllerBuilder.defaultBuilder(null)
                .withName(controllerName)
                .withReconciler(reconcilier)
                .withWorkQueue(rateLimitingQueue)
                .withWorkerCount(2) // optional, set worker thread count
                .withReadyFunc(indexInformer::hasSynced) // optional, only starts controller when the
                // cache has synced up
                .build();
        // Mimick ControlerBuilder::watch to use our informer rather than the gloabl one for this resource kind
        DefaultControllerWatch<T> controllerWatch = (DefaultControllerWatch<T>) createWatchedResourceControllerWatch(rateLimitingQueue, watchedResourceKind, watchedResource, indexInformer);
        indexInformer.addEventHandlerWithResyncPeriod(controllerWatch.getResourceEventHandler(), controllerWatch.getResyncPeriod().toMillis());

        return controller;
    }

    private static DefaultControllerWatch<? extends KubernetesObject> createWatchedResourceControllerWatch(
            WorkQueue<Request> requestWorkQueue, WatchedResourceKind watchedResourceKind, WatchedResource watchedResource,
            SharedIndexInformer<? extends KubernetesObject> indexInformer) {
        switch (watchedResourceKind) {
            case SECRET: {
                return buildSecretController(watchedResource, requestWorkQueue, (SharedIndexInformer<V1Secret>) indexInformer);
            }
            case CONFIGMAP: {
                return buildConfigMapController(watchedResource, requestWorkQueue, (SharedIndexInformer<V1ConfigMap>) indexInformer);
            }
            case POD: {
                return buildPodController(watchedResource, requestWorkQueue, (SharedIndexInformer<V1Pod>) indexInformer);
            }
            default:
                throw new IllegalArgumentException("Unsupported resource to watch: " + watchedResourceKind);
        }
    }

    private static DefaultControllerWatch<V1Pod> buildPodController(WatchedResource watchedResource, WorkQueue<Request> workQueue, SharedIndexInformer<V1Pod> indexInformer) {
        return ControllerBuilder.controllerWatchBuilder(V1Pod.class, workQueue)
                .withWorkQueueKeyFunc((V1Pod node) -> new Request(node.getMetadata().getNamespace(), node.getMetadata().getName())) // optional, default to
                .withOnAddFilter((V1Pod createdNode) -> checkObjectWatchedOnAdd(createdNode, watchedResource, indexInformer))
                .withOnUpdateFilter((V1Pod oldNode, V1Pod newNode) -> checkObjectWatchedOnUpdate(oldNode, watchedResource, indexInformer))
                .withOnDeleteFilter((V1Pod deletedNode, Boolean stateUnknown) -> checkObjectWatchedOnDelete(deletedNode, watchedResource, indexInformer))
                .build();
    }

    private static DefaultControllerWatch<V1Secret> buildSecretController(WatchedResource watchedResource, WorkQueue<Request> workQueue, SharedIndexInformer<V1Secret> indexInformer) {
        return ControllerBuilder.controllerWatchBuilder(V1Secret.class, workQueue)
                .withWorkQueueKeyFunc((V1Secret node) -> new Request(node.getMetadata().getNamespace(), node.getMetadata().getName())) // optional, default to
                .withOnAddFilter((V1Secret createdNode) -> checkObjectWatchedOnAdd(createdNode, watchedResource, indexInformer))
                .withOnUpdateFilter((V1Secret oldNode, V1Secret newNode) -> checkObjectWatchedOnUpdate(oldNode, watchedResource, indexInformer))
                .withOnDeleteFilter((V1Secret deletedNode, Boolean stateUnknown) -> checkObjectWatchedOnDelete(deletedNode, watchedResource, indexInformer))
                .build();
    }

    private static DefaultControllerWatch<V1ConfigMap> buildConfigMapController(WatchedResource watchedResource, WorkQueue<Request> workQueue, SharedIndexInformer<V1ConfigMap> indexInformer) {
        return ControllerBuilder.controllerWatchBuilder(V1ConfigMap.class, workQueue)
                .withWorkQueueKeyFunc((V1ConfigMap node) -> new Request(node.getMetadata().getNamespace(), node.getMetadata().getName())) // optional, default to
                .withOnAddFilter((V1ConfigMap createdNode) -> checkObjectWatchedOnAdd(createdNode, watchedResource, indexInformer))
                .withOnUpdateFilter((V1ConfigMap oldNode, V1ConfigMap newNode) -> checkObjectWatchedOnUpdate(oldNode, watchedResource, indexInformer))
                .withOnDeleteFilter((V1ConfigMap deletedNode, Boolean stateUnknown) -> checkObjectWatchedOnDelete(deletedNode, watchedResource, indexInformer))
                .build();
    }

    private static <T extends KubernetesObject> SharedIndexInformer<T> createNewWatchedResourceIndexer(
            SharedInformerFactory informerFactory, EventBroadcaster eventBroadcaster,
            CoreV1Api coreV1Api, AppsV1Api appsV1Api,
            WatchedResourceKind watchedResourceKind, WatchedResource watchedResource) {
        SharedIndexInformer<T> indexInformer = (SharedIndexInformer<T>) createSharedIndexInformer(watchedResourceKind, watchedResource, informerFactory, coreV1Api);

        WatchedResourceReconcilier<T> reconcilier = new WatchedResourceReconcilier<>(
                coreV1Api, appsV1Api, watchedResource, indexInformer,
                eventBroadcaster.newRecorder(new V1EventSource().host("localhost").component(COMPONENT_NAME)));


        return indexInformer;
    }

    private static SharedIndexInformer<? extends KubernetesObject> createSharedIndexInformer(WatchedResourceKind resourceKind, WatchedResource watchedResource, SharedInformerFactory informerFactory, CoreV1Api coreV1Api) {
        String namespace = watchedResource.getNamespace();
        String fieldSelector = String.join(",", watchedResource.getFieldSelectors());
        String labelSelector = String.join(",", watchedResource.getLabelSelectors());
        switch (resourceKind) {
            case POD: {
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
            case SECRET: {
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
            case CONFIGMAP: {
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
                throw new RuntimeException("Unsupported resource to watch: " + resourceKind);

        }
    }

    private static <T extends KubernetesObject> boolean checkObjectWatchedOnAdd(T kubernetesObject, WatchedResource config, SharedIndexInformer<T> indexInformer) {
        boolean watchAdd = config.isWatchAdd();
        if (!watchAdd) {
            return false;
        }
        return checkObjectWatched(kubernetesObject, config, indexInformer);
    }


    private static <T extends KubernetesObject> boolean checkObjectWatchedOnUpdate(T kubernetesObject, WatchedResource config, SharedIndexInformer<T> indexInformer) {
        boolean watchUpdate = config.isWatchUpdate();
        if (!watchUpdate) {
            return false;
        }
        return checkObjectWatched(kubernetesObject, config, indexInformer);
    }

    private static <T extends KubernetesObject> boolean checkObjectWatchedOnDelete(T kubernetesObject, WatchedResource config, SharedIndexInformer<T> indexInformer) {
        boolean watchDelete = config.isWatchDelete();
        if (!watchDelete) {
            return false;
        }
        return checkObjectWatched(kubernetesObject, config, indexInformer);
    }

    private static <T extends KubernetesObject> boolean checkObjectWatched(T kubernetesObject, WatchedResource config, SharedIndexInformer<T> indexInformer) {
        V1ObjectMeta metadata = kubernetesObject.getMetadata();
        String namespace = config.getNamespace();
        if (!namespace.equalsIgnoreCase(metadata.getNamespace())) {
            return false;
        }

        List<String> labelSelectors = config.getLabelSelectors();
        Map<String, String> labels = metadata.getLabels();


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
