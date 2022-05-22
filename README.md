# Resource watcher

Watches for resources and events in a kubernetes cluster, and trigger some actions.

Use cases:

- Restart a deployment when a secret has been updated.
  For instance, a tls secret managed by cert-manager might need a pod to restart when updated

## Example config file

Config consists of a list of resources to watch, each with its actions list.
The list of resources kind supported is limited.

The list of actions to execute is also limited: currently it only allows annotating another resource with a timestamp (actionTYpe = ANNOTATE).
This can be used to annotate a pod and trigger redeployment. The list of annotated resource kinds supported is limited as well.

See src/main/resources for an up to date example

```yaml
debug: true

watchedResourceList:
  - kind: Secret
    namespace: ns0
    labelSelectors: [ ]
    fieldSelectors:
      - "metadata.name=mysecret"
    watchAdd: false
    watchUpdate: true
    watchDelete: false

    actionList:
      # Annotating a pod template will trigger a rollout of the deployment
      - actionType: ANNOTATE_WITH_TIMESTAMP
        annotatedResourceNamespace: ns0
        annotatedResourceKind: DEPLOYMENT_POD_TEMPLATE
        annotatedResourceFieldSelectors:
          - "metadata.name=mydeployment"
        annotatedResourceLabelsSelectors:
          - app=my-app
```

The config file location is read from system env variable "RESOURCE_WATCHER_CONFIG_PATH", or "/var/run/config/resourcewatcher.yaml".


