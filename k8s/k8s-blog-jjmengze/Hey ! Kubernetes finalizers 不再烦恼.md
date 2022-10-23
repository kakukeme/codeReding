## 前言

透过原理来了解事情的因果关系可能会太复杂，但作为一个软体工程师理解背后如何实践以及为什么会有这样的东西出现（历史缘由）是非常重要的。
本篇文章将会记录finalizers 的背后原理以及一些source code ，这是使用者在操作Kubernetes 常常会看到的一个栏位，好像有听过但又不太了解的东西xD

## 观察细节

finalizers 定义于Kubernetes 的metadata的栏位

```
...
// ObjectMeta is metadata that all persisted resources must have, which includes all objects
// users must create.
type ObjectMeta struct {
...
	// Populated by the system when a graceful deletion is requested.
	// Read-only.
	// More info: https://git.k8s.io/community/contributors/devel/sig-architecture/api-conventions.md#metadata
	// +optional
	DeletionTimestamp *Time `json:"deletionTimestamp,omitempty" protobuf:"bytes,9,opt,name=deletionTimestamp"`

	// Number of seconds allowed for this object to gracefully terminate before
	// it will be removed from the system. Only set when deletionTimestamp is also set.
	// May only be shortened.
	// Read-only.
	// +optional
	DeletionGracePeriodSeconds *int64 `json:"deletionGracePeriodSeconds,omitempty" protobuf:"varint,10,opt,name=deletionGracePeriodSeconds"`

	// Must be empty before the object is deleted from the registry. Each entry
	// is an identifier for the responsible component that will remove the entry
	// from the list. If the deletionTimestamp of the object is non-nil, entries
	// in this list can only be removed.
	// Finalizers may be processed and removed in any order.  Order is NOT enforced
	// because it introduces significant risk of stuck finalizers.
	// finalizers is a shared field, any actor with permission can reorder it.
	// If the finalizer list is processed in order, then this can lead to a situation
	// in which the component responsible for the first finalizer in the list is
	// waiting for a signal (field value, external system, or other) produced by a
	// component responsible for a finalizer later in the list, resulting in a deadlock.
	// Without enforced ordering finalizers are free to order amongst themselves and
	// are not vulnerable to ordering changes in the list.
	// +optional
	// +patchStrategy=merge
	Finalizers []string `json:"finalizers,omitempty" patchStrategy:"merge" protobuf:"bytes,14,rep,name=finalizers"`
    ...

```

从注解中我们大概可以了解这个栏位要表达的意义,我把它整理成比较容易阅读的方式（可能只有我觉得godoc的// 注解换行很烦xD

> Must be empty before the object is deleted from the registry. Each entry is an identifier for the responsible component that will remove the entry from the list. If the deletionTimestamp of the object is non-nil, entries in this list can only be removed. Finalizers may be processed and removed in any order. Order is NOT enforced because it introduces significant risk of stuck finalizers. finalizers is a shared field, any actor with permission can reorder it. If the finalizer list is processed in order, then this can lead to a situation in which the component responsible for the first finalizer in the list is waiting for a signal (field value, external system, or other) produced by a component responsible for a finalizer later in the list, resulting in a deadlock.Without enforced ordering finalizers are free to order amongst themselves and are not vulnerable to ordering changes in the list.

## 大胆假设

我认为上述的文字简单来说有三个重点(以下的顺序不重要)

1. `If the deletionTimestamp of the object is non-nil, entries in this list can only be removed.`
   简单来说当 `deletionTimestamp` 不是nil 的时候需要先删除Finalizers 内的条目
2. `Finalizers may be processed and removed in any order`
   Finalizers 条目的删除顺序不是固定的
3. `Must be empty before the object is deleted from the registry.`
   再删除这个物件前Finalizers 条目必须是空的

从上述注解的我们大概可以推测几件事情，第一当使用者删除Kubernetes 物件时，GC 回收机制需要检查Finalizers是否为空，第二其他物件可以任意删除Finalizers 栏位，前提是deletionTimestamp 栏位不为nil。

大胆假设这个现象了，那就应该小心求证事实。

## 小心求证

我已最近我在玩耍的argo cd 进行球证对象，大部分处理物件的逻辑都会落在controller 的reconcile 阶段里，只要在reconcile 搜寻 `Finalizer` 应该可以发现点什么。

```
func (ctrl *ApplicationController) processProjectQueueItem() (processNext bool) {
    if origProj.DeletionTimestamp != nil && origProj.HasFinalizer() {
		if err := ctrl.finalizeProjectDeletion(origProj.DeepCopy()); err != nil {
			log.Warnf("Failed to finalize project deletion: %v", err)
		}
	}
	return
    ...
}

func (ctrl *ApplicationController) finalizeProjectDeletion(proj *appv1.AppProject) error {
	apps, err := ctrl.appLister.Applications(ctrl.namespace).List(labels.Everything())
	if err != nil {
		return err
	}
	appsCount := 0
	for i := range apps {
		if apps[i].Spec.GetProject() == proj.Name {
			appsCount++
			break
		}
	}
	if appsCount == 0 {
		return ctrl.removeProjectFinalizer(proj)
	} else {
		log.Infof("Cannot remove project '%s' finalizer as is referenced by %d applications", proj.Name, appsCount)
	}
	return nil
}

func (ctrl *ApplicationController) removeProjectFinalizer(proj *appv1.AppProject) error {
	proj.RemoveFinalizer()
	var patch []byte
	patch, _ = json.Marshal(map[string]interface{}{
		"metadata": map[string]interface{}{
			"finalizers": proj.Finalizers,
		},
	})
	_, err := ctrl.applicationClientset.ArgoprojV1alpha1().AppProjects(ctrl.namespace).Patch(context.Background(), proj.Name, types.MergePatchType, patch, metav1.PatchOptions{})
	return err
}

func (proj AppProject) HasFinalizer() bool {
	return getFinalizerIndex(proj.ObjectMeta, common.ResourcesFinalizerName) > -1
}

// getFinalizerIndex returns finalizer index in the list of object finalizers or -1 if finalizer does not exist
func getFinalizerIndex(meta metav1.ObjectMeta, name string) int {
	for i, finalizer := range meta.Finalizers {
		if finalizer == name {
			return i
		}
	}
	return -1
}

func (proj *AppProject) RemoveFinalizer() {
	setFinalizer(&proj.ObjectMeta, common.ResourcesFinalizerName, false)
}

// setFinalizer adds or removes finalizer with the specified name
func setFinalizer(meta *metav1.ObjectMeta, name string, exist bool) {
	index := getFinalizerIndex(*meta, name)
	if exist != (index > -1) {
		if index > -1 {
			meta.Finalizers[index] = meta.Finalizers[len(meta.Finalizers)-1]
			meta.Finalizers = meta.Finalizers[:len(meta.Finalizers)-1]
		} else {
			meta.Finalizers = append(meta.Finalizers, name)
		}
	}
}

```



我把主要的判断逻辑抓出来，可以看到大致上的逻辑有几项

1. `processProjectQueueItem` function 里面会判断 `Application` 物件的 `DeletionTimestamp` 以及Finalizers 内有没有我们要关注的`key`。

2. 检查`Applications namespaces` 下面的其他的相关物件，并且计算物件的总数。

   1. 如果>0
      - 直接回传，因为相关的资源还没情理干净
   2. 如果=0
   - 移除Finalizers 我们要关注的`key`。

这边可以看到几个之前提过的重点

1. 简单来说当 `deletionTimestamp` 不是nil 的时候需要先删除Finalizers 内的条目
2. `Finalizers may be processed and removed in any order`
   Finalizers 条目的删除顺序不是固定的

## 结语

类似像Finalizers 这种的小螺丝都能起到这么大的作用，我们应该更静下心来学时事务的本质，不只要会操作它更要理解背后的原理是什么。
