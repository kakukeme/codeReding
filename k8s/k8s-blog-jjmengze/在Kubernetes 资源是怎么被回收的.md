是由kubernetes master node 上的 `kube-controller-manger` 中的 `garbage-collection controller` 进行回收管理的。

我们先不管kubernetes 底层是怎么回收这些垃圾物件的，先把焦点放在我们要怎么设定yaml档，毕竟我们是yaml工程师嘛(笑)，本篇文章会用进行几个实验来观察各种kubernetes 回收策略是如何进行的。

当我们删除物件时，可以指定该物件底下关联的子物件是否也跟着自动删除。

- 自动删除附属的行为也称为级联删除（Cascading Deletion）

Kubernetes 中有两种 `Cascading Deletion ` 删除分别是：

- 后台（Background） 模式
- 前台（Foreground） 模式。

## Foreground

### 条件

1. 物件的 `metadata.finalizers` 被设定为`foregroundDeletion`
2. 物件处于` deletion in progress` 状态（deletionTimestamp被建立）

### 行为

1. 需要等到物件所有的关联的子物件被删除完之后，才可以删除该物件
2. 如何确定关联的子物件的父亲是谁？
   - 透过子物件的 `ownerReferences` 来确定

## Background

kubernetes`立刻` `馬上`删除物件，`garbage-collection controller`会在后台(背景)删除该物件的子物件。

除了在背景删除子物件的行为外还有一种是不删除子物件，让子物件变成孤儿(Orphan)。

## 实验propagation Policy (Foreground)

### deploy

**部署测试的nginx deployment**

```
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-deployment
  labels:
    app: nginx
spec:
  replicas: 3
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
      - name: nginx
        image: nginx:1.14.2
        ports:
        - containerPort: 80
EOF
deployment.apps/nginx-deployment created

```



#### 状态

**取的deployment ReplicaSet 以及pod的状态**

```

kubectl get deploy,pod
NAME                               READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/nginx-deployment   3/3     3            3           4m44s

NAME                                    READY   STATUS    RESTARTS   AGE
pod/nginx-deployment-6b474476c4-nbtkw   1/1     Running   0          4m44s
pod/nginx-deployment-6b474476c4-nkbrb   1/1     Running   0          4m44s
pod/nginx-deployment-6b474476c4-zh5g7   1/1     Running   0          4m44s

```



**取得ReplicaSet与pod的ownerReferences**用来确定物件之间的关系

```
kubectl get rs nginx-deployment-6b474476c4 -o go-template --template={{.metadata.ownerReferences}}
[map[
apiVersion:apps/v1 
blockOwnerDeletion:true 
controller:true 
kind:Deployment 
name:nginx-deployment 
uid:597d36f5-968a-4025-8621-b24f17f7f3a6]]

kubectl get pod nginx-deployment-6b474476c4-nbtkw  -o go-template --template={{.metadata.ownerReferences}}
[map[
apiVersion:apps/v1 
blockOwnerDeletion:true 
controller:true 
kind:ReplicaSet 
name:nginx-deployment-6b474476c4 
uid:97fb6974-6882-4459-b6b0-b39357a7650b]]

```



### destroy Foreground

透过指定的删除模式来删除物件，这里是透过`Foreground` 的方式删除物件。

```bash=
curl -X DELETE curl -X DELETE localhost:8080/apis/apps/v1/namespaces/default/deployments/nginx-deployment \
  -d '{"kind":"DeleteOptions","apiVersion":"v1","propagationPolicy":"Foreground"}' \
  -H "Content-Type: application/json"
```

#### 状态

**取的deployment 以及pod的状态**观察删除状态。

从这个状态可以看到所有的pod 都在Terminating 的状态， ReplicaSet 以及deployment 都没有先移除。

```bash
kubectl get pod,deploy,rs
NAME                                    READY   STATUS        RESTARTS   AGE
pod/nginx-deployment-6b474476c4-nbtkw   0/1     Terminating   0          133m
pod/nginx-deployment-6b474476c4-nkbrb   0/1     Terminating   0          133m
pod/nginx-deployment-6b474476c4-zh5g7   0/1     Terminating   0          133m

NAME                               READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/nginx-deployment   0/3     0            0           133m

NAME                                          DESIRED   CURRENT   READY   AGE
replicaset.apps/nginx-deployment-6b474476c4   3         0         0       133m

```



#### 比较差异

比较删除前后的差异

##### Deployment

**Deployment状态的差异**

从diff 的状态可以看出来，在metadata 的部分修改了 `finalizers` 并且指定了 `foregroundDeletion` 的模式表示使用foreground 删除模式，另外新增了 `deletionTimestamp` 的时间确定了物件的删除时间。

```diff
--- a/dpeloy.yaml
+++ b/dpeloy.yaml
-  generation: 1
+  deletionGracePeriodSeconds: 0
+  deletionTimestamp: "2020-08-10T08:22:55Z"
+  finalizers:
+  - foregroundDeletion
+  generation: 2
   namespace: default
-  resourceVersion: "1480766"
+  resourceVersion: "1499040"

...
 status:
-  availableReplicas: 3
   conditions:
-  - lastTransitionTime: "2020-08-10T06:10:18Z"
-    lastUpdateTime: "2020-08-10T06:10:18Z"
-    message: Deployment has minimum availability.
-    reason: MinimumReplicasAvailable
-    status: "True"
-    type: Available

...
-  observedGeneration: 1
-  readyReplicas: 3
-  replicas: 3
-  updatedReplicas: 3
+  - lastTransitionTime: "2020-08-10T08:22:55Z"
+    lastUpdateTime: "2020-08-10T08:22:55Z"
+    message: Deployment does not have minimum availability.
+    reason: MinimumReplicasUnavailable
+    status: "False"
+    type: Available
+  observedGeneration: 2
+  unavailableReplicas: 3


```



##### ReplicaSet

观察ReplicaSet的变化也是确定了删除的时间 `deletionTimestamp` 以及修改时间`time`，以及是透过 `foregroundDeletion` 的策略进行删除。

在状态栏的地方也能看出现在状态replicas的数量被缩减为0个。

```diff
--- a/rs.yaml
+++ b/rs.yaml
   creationTimestamp: "2020-08-10T06:09:12Z"
-  generation: 1
+  deletionGracePeriodSeconds: 0
+  deletionTimestamp: "2020-08-10T08:22:55Z"
+  finalizers:
+  - foregroundDeletion
+  generation: 2
...
-    time: "2020-08-10T06:10:18Z"
+    time: "2020-08-10T08:22:55Z"
...
   ownerReferences:
@@ -86,7 +87,7 @@ metadata:
     kind: Deployment
     name: nginx-deployment
     uid: 597d36f5-968a-4025-8621-b24f17f7f3a6
-  resourceVersion: "1480765"
+  resourceVersion: "1499039"
...
       terminationGracePeriodSeconds: 30
 status:
-  availableReplicas: 3
-  fullyLabeledReplicas: 3
-  observedGeneration: 1
-  readyReplicas: 3
-  replicas: 3
+  observedGeneration: 2
+  replicas: 0

```



##### Pod

pod 的部分也想当的简单，因为没有子物件所以只要确定删除时间 `deletionTimestamp` 以及修改时间`time`。

pod的状态会被修改成 `pending` 以及相关的资源都会被移除例如: pod ip。

```diff
--- a/pod.yaml
+++ b/pod.yaml
   creationTimestamp: "2020-08-10T06:09:12Z"
+  deletionGracePeriodSeconds: 30
+  deletionTimestamp: "2020-08-10T08:23:25Z"
...
-    time: "2020-08-10T06:10:16Z"
+    time: "2020-08-10T08:22:57Z"
     uid: 97fb6974-6882-4459-b6b0-b39357a7650b
-  resourceVersion: "1480754"
+  resourceVersion: "1499048"
status: "True"
     type: Initialized
   - lastProbeTime: null
-    lastTransitionTime: "2020-08-10T06:10:16Z"
-    status: "True"
+    lastTransitionTime: "2020-08-10T08:22:57Z"
+    message: 'containers with unready status: [nginx]'
+    reason: ContainersNotReady
+    status: "False"
     type: Ready
   - lastProbeTime: null
-    lastTransitionTime: "2020-08-10T06:10:16Z"
-    status: "True"
+    lastTransitionTime: "2020-08-10T08:22:57Z"
+    message: 'containers with unready status: [nginx]'
+    reason: ContainersNotReady
+    status: "False"
...
   containerStatuses:
-  - containerID: docker://f20bbce2b58ac42426b61fc21e3f1a61938a51a4dc30277f50e9ac7aea88aa3d
-    image: nginx:1.14.2
-    imageID: docker-pullable://nginx@sha256:f7988fb6c02e0ce69257d9bd9cf37ae20a60f1df7563c3a2a6abe24160306b8d
+  - image: nginx:1.14.2
+    imageID: ""
     lastState: {}
     name: nginx
-    ready: true
+    ready: false
     restartCount: 0
-    started: true
+    started: false
     state:
-      running:
-        startedAt: "2020-08-10T06:10:16Z"
+      waiting:
+        reason: ContainerCreating
   hostIP: 172.18.0.5
-  phase: Running
-  podIP: 10.32.0.5
-  podIPs:
-  - ip: 10.32.0.5
+  phase: Pending

```



## 实验propagationPolicy (Background)

### deploy

**部署测试的nginx deployment**

```
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-deployment
  labels:
    app: nginx
spec:
  replicas: 3
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
      - name: nginx
        image: nginx:1.14.2
        ports:
        - containerPort: 80
EOF
deployment.apps/nginx-deployment created

```



#### 状态

**取的Deployment ReplicaSet 以及Pod的状态**

```
kubectl get deploy,rs,pod
NAME                               READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/nginx-deployment   3/3     3            3           55s

NAME                                          DESIRED   CURRENT   READY   AGE
replicaset.apps/nginx-deployment-6b474476c4   3         3         3       55s

NAME                                    READY   STATUS    RESTARTS   AGE
pod/nginx-deployment-6b474476c4-4hjx8   1/1     Running   0          55s
pod/nginx-deployment-6b474476c4-6qvvg   1/1     Running   0          55s
pod/nginx-deployment-6b474476c4-plsn7   1/1     Running   0          55s

```



**取得ReplicaSet与pod的ownerReferences**用来确定物件之间的关系

```
kubectl get replicaset.apps/nginx-deployment-6b474476c4  -o go-template --template={{.metadata.ownerReferences}}
[map[
apiVersion:apps/v1
blockOwnerDeletion:true
controller:true
kind:Deployment 
name:nginx-deployment 
uid:8a2be904-c306-426c-881e-c6914415c5fe]]


kubectl get pod nginx-deployment-6b474476c4-6qvvg  -o go-template --template={{.metadata.ownerReferences}}
[map[
apiVersion:apps/v1 
blockOwnerDeletion:true 
controller:true
kind:ReplicaSet 
name:nginx-deployment-6b474476c4
uid:565540ad-fbf1-4d74-8841-f0475e12a200]]

```



### destroy background

```
curl -X DELETE localhost:8080/apis/apps/v1/namespaces/default/deployments/nginx-deployment \
  -d '{"kind":"DeleteOptions","apiVersion":"v1","propagationPolicy":"Background"}' \
  -H "Content-Type: application/json"


```



#### 状态

**取的deployment 以及pod的状态**

从这个状态可以看到所有的pod 都在Terminating 的状态，但是replicaset 以及deployment 都`先被移除`。

```
kubectl get pod,deploy,rs
NAME                                    READY   STATUS        RESTARTS   AGE
pod/nginx-deployment-6b474476c4-4hjx8   0/1     Terminating   0          31m
pod/nginx-deployment-6b474476c4-6qvvg   0/1     Terminating   0          31m
pod/nginx-deployment-6b474476c4-plsn7   0/1     Terminating   0          31m

```



#### 比较差异

##### deployment

**deployment状态的差异**

可以看到deployment 直接被杀掉了。

```diff
--- a/dpeloy.yaml
+++ b/dpeloy.yaml
-apiVersion: apps/v1
-kind: Deployment
-metadata:
-  annotations:
-    deployment.kubernetes.io/revision: "1"
-    kubectl.kubernetes.io/last-applied-configuration: |
-      {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"annotations":{},"labels":{"app":"nginx"},"name":"nginx-deployment","namespace":"default"},"spec":{"replicas":3,"selector":{"matchLabels":{"app":"nginx"}},"template":{"metadata":{"labels":{"app":"nginx"}},"spec":{"containers":[{"image":"nginx:1.14.2","name":"nginx","ports":[{"containerPort":80}]}]}}}}
-  creationTimestamp: "2020-08-10T10:05:25Z"
-  generation: 1
-  labels:
-    app: nginx
-  managedFields:
-  - apiVersion: apps/v1
...
...

```



##### replicaset

可以看到replicaset 也是直接被杀掉了。

```
--- a/rs.yaml
+++ b/rs.yaml
-apiVersion: apps/v1
-kind: ReplicaSet
-metadata:
-  annotations:
-    deployment.kubernetes.io/desired-replicas: "3"
-    deployment.kubernetes.io/max-replicas: "4"
-    deployment.kubernetes.io/revision: "1"
-  creationTimestamp: "2020-08-10T10:05:25Z"
-  generation: 1
-  labels:
-    app: nginx
-    pod-template-hash: 6b474476c4
-  managedFields:
-  - apiVersion: apps/v1
-    fieldsType: FieldsV1
-    fieldsV1:
-      f:metadata:
-        f:annotations:
-          .: {}
-          f:deployment.kubernetes.io/desired-replicas: {}
-          f:deployment.kubernetes.io/max-replicas: {}
-          f:deployment.kubernetes.io/revision: {}
...
...

```



##### pod

可以看到pod 是缓慢的回收， 可以看到被设定了移除的时间以及相关状态。

```diff
--- a/pod.yaml
+++ b/pod.yaml
-    time: "2020-08-10T10:05:25Z"
+    time: "2020-08-10T10:08:20Z"

+  deletionGracePeriodSeconds: 30
+  deletionTimestamp: "2020-08-10T10:08:20Z"
   generateName: nginx-deployment-6b474476c4-
-  resourceVersion: "1513382"
+  resourceVersion: "1513717"
...
-    lastTransitionTime: "2020-08-10T10:08:20Z"
-    status: "True"
+    lastTransitionTime: "2020-08-10T10:08:20Z"
+    message: 'containers with unready status: [nginx]'
+    reason: ContainersNotReady
+    status: "False"
     type: Ready
   - lastProbeTime: null
-    lastTransitionTime: "2020-08-10T10:08:20Z"
-    status: "True"
+    lastTransitionTime: "2020-08-10T10:08:20Z"
+    message: 'containers with unready status: [nginx]'
+    reason: ContainersNotReady
+    status: "False"
-  - containerID: docker://bb34d6af8dbe1c72c423fece3d9d797ec8a5a0b62fd82f1f46bfcf5d67157be1
-    image: nginx:1.14.2
-    imageID: docker-pullable://nginx@sha256:f7988fb6c02e0ce69257d9bd9cf37ae20a60f1df7563c3a2a6abe24160306b8d
+  - image: nginx:1.14.2
+    imageID: ""
     lastState: {}
     name: nginx
-    ready: true
+    ready: false
     restartCount: 0
-    started: true
+    started: false
     state:
-      running:
-        startedAt: "2020-08-10T10:08:20Z"
+      waiting:
+        reason: ContainerCreating
   hostIP: 172.18.0.5
-  phase: Running
-  podIP: 10.32.0.6
-  podIPs:
-  - ip: 10.32.0.6
+  phase: Pending
...

```



## 实验propagation Policy (Orphan)

### deploy

**部署测试的nginx deployment**

```
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-deployment
  labels:
    app: nginx
spec:
  replicas: 3
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
      - name: nginx
        image: nginx:1.14.2
        ports:
        - containerPort: 80
EOF
deployment.apps/nginx-deployment created

```



#### 状态

**取的deployment replicaset 以及pod的状态**

```

kubectl get deploy,pod
NAME                               READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/nginx-deployment   3/3     3            3           4m44s

NAME                                    READY   STATUS    RESTARTS   AGE
pod/nginx-deployment-6b474476c4-nbtkw   1/1     Running   0          4m44s
pod/nginx-deployment-6b474476c4-nkbrb   1/1     Running   0          4m44s
pod/nginx-deployment-6b474476c4-zh5g7   1/1     Running   0          4m44s

```



**取得replicaset与pod的ownerReferences**确定物件之间的关系

```
kubectl get rs nginx-deployment-6b474476c4 -o go-template --template={{.metadata.ownerReferences}}

[map[apiVersion:apps/v1 blockOwnerDeletion:true controller:true kind:Deployment name:nginx-deployment uid:b1d1a61d-8b51-4511-8c91-de44aaa2cdd0]]

kubectl get pod nginx-deployment-6b474476c4-nbtkw  -o go-template --template={{.metadata.ownerReferences}}

[map[apiVersion:apps/v1 blockOwnerDeletion:true controller:true kind:ReplicaSet name:nginx-deployment-6b474476c4 uid:b052f80f-d72a-4e32-a4c4-f598274f2b07]]

```



### destroy Orphan

```
curl -X DELETE localhost:8080/apis/apps/v1/namespaces/default/deployments/nginx-deployment \
  -d '{"kind":"DeleteOptions","apiVersion":"v1","propagationPolicy":"Orphan"}' \
  -H "Content-Type: application/json"

```



#### 状态

**取的deployment 以及pod的状态**

从这个状态可以看到所有的pod 都在Running 的状态，ReplicaSet 以及Pod 都没有被移除，只有Deployment 被杀掉而已。

```
kubectl get pod,deploy,rs

NAME                                    READY   STATUS    RESTARTS   AGE
pod/nginx-deployment-6b474476c4-d6wns   1/1     Running   0          12m
pod/nginx-deployment-6b474476c4-d7dtf   1/1     Running   0          12m
pod/nginx-deployment-6b474476c4-m7rbz   1/1     Running   0          12m

NAME                                          DESIRED   CURRENT   READY   AGE
replicaset.apps/nginx-deployment-6b474476c4   3         3         3       12m

```



## 小结

从上面三个实验可以看到不同的移除方式会有不同的结果

1. Front Ground
   需要等到关联的子物件被删除后才会进行清理的动作(打上`deletionTimestamp`)
2. BackGround
   先删除物件(打上`deletionTimestamp`)，再慢慢回收子物件(打上`deletionTimestamp`)

3.Orphan
直接把物件删除（打上`deletionTimestamp`），所有子物件不做任何动作。
