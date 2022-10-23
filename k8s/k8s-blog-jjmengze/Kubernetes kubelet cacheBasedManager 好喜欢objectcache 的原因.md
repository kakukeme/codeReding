首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

在上一篇[Kubernetes kubelet cacheBasedManager 现在才知道](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/kubernetes-kubelet/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc) 留了一个伏笔，就是 `objectStore` 到底是什么？

在 `cacheBasedManager` 的GetObject 、 RegisterPod 以及UnregisterPod 这三个function 都有用到，想必 `objectStore` 一定是个狠角色吧，本篇将承接上一篇文章继续探讨kubernetes kubelet 怎么监控configmap/secret 的底层实作，焦点会聚焦在 `objectStore` 的实作上。

## cacheBasedManager

我们先来回顾一下 `cacheBasedManager` 的资料结构定义，可以看到 `objectStore` 的资料型态是`Store`，那这个型态是什么？

```
type cacheBasedManager struct {
	objectStore          Store				//主要用來儲存 reflector 觀測到的物件狀態
	getReferencedObjects func(*v1.Pod) sets.String	//主要用來找到 pod 內所有關聯的欄位，例如 configmap 就有可能出現在 envform configMapKeyRef 、 envform configMapRef 或是 volumes configMap  

	lock           sync.Mutex				//可能有大量的 pod 同時塞入我們必須保證狀態的一致性
	registeredPods map[objectKey]*v1.Pod			//主要用來儲存哪個 pod 已經在觀測名單了
}

```



是之前讲的[Kubernetes Indexers local cache](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/indexer/kubernetes-indexers-local-cache-1/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)的store interface 吗？恩….这里并不是之前提到的local store ，实际的定义我们往下看一下！

> tip: controller/operator 中reflector 用到的是cache.store， import 的package 不一样呦！

## Store

### interface

这里的Store 并不是给之前我们再讨论controller/operator 时候给indexer 用的，这里的store 主要是给cache base manager 建立，删除以及取得kubernets 物件的reference 。

如果对controller/operator 的store interface 有兴趣可以回去复习一下这一篇[Kubernetes Indexers local cache 之美](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/indexer/kubernetes-indexers-local-cache-1/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc#interface)，希望对你有帮助。

好，回过头来看kubelet 中所定义的store interface 到底是什么吧！
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cacheobject/pkg/kubelet/util/manager/manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
type Store interface {
	// 使用者將 pod namespace 與 pod name 透過 addreference 實作的物件應該要增加 reference  以及建立對應的 reflector。
	AddReference(namespace, name string)
    
    
	// 上一篇我們有談到當沒有任何物件使用 reference 時應該要呼叫 DeleteReference，實作的物件應該要幫我們把對應的 reflector 處理掉
	DeleteReference(namespace, name string)
    
    
	// 當  cache base manager 要 kubernetes 物件資料時，會把物件的  namespace 與  name 帶入這個 function ，這裡會幫忙把物件取出。
	Get(namespace, name string) (runtime.Object, error)
}

```



单看定义仅能大概了解store interface 要做的事情，我们还需要深入挖掘底层的实作let's do it !

### struct

从source code 上面的注解来看，可以知道这个objectCache 是一个透过独立watcher 传播物件的local cache ，那到底是什么意思呢？

这里先卖个关子先看code 整理一下思绪会比较清楚

```
// objectCache is a local cache of objects propagated via individual watches.
type objectCache struct {
	listObject    listObjectFunc				//列出 kubernetes 物件的過濾方法
	watchObject   watchObjectFunc				//監控 kubernetes 物件的過濾方法
	newObject     newObjectFunc				//reflector 預期要得到的物件型態為何的方法
	isImmutable   isImmutableFunc				//如何判斷觀測的 kubernetes 物件是否屬於不可變的
	groupResource schema.GroupResource			//kubernetes 的GVK

	lock  sync.RWMutex					//讀寫所會用讀寫鎖主要因為讀多寫少（除非一直在加 pod 對應的 reflector）
    
	//上一篇有提過，cacheBasedManager 會透過解析用到物件名稱 secret/configmap name 以及 namespace 作為 Objectkey 傳入
	//並且生成對應的 reflector 
	items map[objectKey]*objectCacheItem			//用來儲存 kubelet configmap/secret 各自對應的 reflector 
}

```



上面你可以能会有一个疑问，`objectCacheItem`到底是什么？总不能我说他是储存各自对应的reflector 他就是吧xD

#### objectCacheItem

我们接着来看为什么 `objectCacheItem` 为什么要储存各自对应的reflector

1. 在objectCache struct 定义了一个map，Key 为objectKey（resource name 加上namespac），Value 为objectCacheItem。
   - 表示xxx-configmap对应xxx-objectCacheItem。
   - 表示yyy-configmap对应yyy-objectCacheItem。

- 换句话说，也可以看成
  - 表示aaa-secret对应aaa-objectCacheItem。
  - 表示bbb-secret对应aaa-objectCacheItem。

1. 如果有nginxA 以及nginxB 两个pod 同时都有xxx-configmap该怎么办？
   - xxx-configmap 对应xxx-objectCacheItem ， xxx-objectCacheItem 要记录有两个人refference 到。
2. 承上如果nginx B 不再关注这个xxx-configmap 要如何处理？
   - xxx-configmap 对应xxx-objectCacheItem ， xxx-objectCacheItem 要记录现在有只有一个人refference 到。
3. 承上如果nginx A 也不再关注这个xxx-configmap 要如何处理？
   - xxx-configmap 对应xxx-objectCacheItem ， xxx-objectCacheItem 要记录现在有没有人refference 到。
   - xxx-objectCacheItem 没有人reference 到的话，需要把reflector 关闭减少资源的浪费该如何

带着以上的方向我们来看 `objectCacheItem` 如何实作的吧！

##### struct

```
// objectCacheItem is a single item stored in objectCache.
type objectCacheItem struct {
	refCount  int						//用來紀錄有多少人引用這個物件，當沒有人引用的時候就可以把 reflector 關掉了
	store     cache.Store					//用來儲存 kubernetes 物件的 local stroage（這裡的 stroe 就是 controller/operaoter 會用到的 store 囉）
	hasSynced func() (bool, error)				//用來確認 local stroage 有沒有同步

	lock   sync.Mutex					//用來防止stop channel重複被呼叫
	stopCh chan struct{}					//用來傳遞關閉 reflector 的 channel 
}

```



当object cache item 的refCount 归零，换句话说就是没有人引用这个reflector 了，就可以把对应的reflector 关闭。

```
func (i *objectCacheItem) stop() bool {
	//避免競爭上鎖
	i.lock.Lock()
	defer i.lock.Unlock()

	//當使用者呼叫關閉 objectCacheItem.stop 時，第一次會進  default 的 select case 。
	//關閉 stop channel ，此時 stop channel 會發出訊號給關聯的 reflector 關閉對 kubernetes resource 的追蹤。
	select {
	case <-i.stopCh:
		// This means that channel is already closed.
		return false
	default:
		close(i.stopCh)
		return true
	}
}

```



### new function

透过NewObjectCache function 产出符合store interface 的物件也就是会建立一个ObjectCache 物件，这里会把一些function 带入，例如要监控什么物件他的list 条件是什么、他的watch 条件是什么以及GVS 是什么。

```
// NewObjectCache returns a new watch-based instance of Store interface.
func NewObjectCache(
	listObject listObjectFunc,				//使用者會傳入要怎麼列出物件
	watchObject watchObjectFunc,				//使用者會傳入要怎麼監控物件
	newObject newObjectFunc,				//reflector 要用的 object store是哪一個
	isImmutable isImmutableFunc,				//使用者會傳入怎麼判斷這個物件是不是 Immutable
	groupResource schema.GroupResource) Store {		//使用者會傳入物件的GVS
	//最後 new fucntion 回傳實作 store interface 的 objectCache 物件
	return &objectCache{
		listObject:    listObject,
		watchObject:   watchObject,
		newObject:     newObject,
		isImmutable:   isImmutable,
		groupResource: groupResource,
		items:         make(map[objectKey]*objectCacheItem),
	}
}

```



### impliment

看完了objectCache 物件的参数定义与如何new 出物件后，接着就可以来了解实作的部分啰！

#### AddReference

还记得在cacheBasedManager 的RegisterPod 的过程吗？忘记的话我们简单的复习一下

简单来说就是当kubernetes assign 一个pod 到node 上的时候，会透过cacheBasedManager 帮我们把pod 内用到secret/configmap 的地方选出来接着递回的呼叫objectStore.AddReference 帮我们建立对应的reflector 观察kubernetes 物件的变化。
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cacheobject/pkg/kubelet/util/manager/cache_based_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
func (c *cacheBasedManager) RegisterPod(pod *v1.Pod) {
	//當 kubernetes assign 一個 pod 到 node 上的時候
	//kubelet 會取得 pod 的相關資訊，getReferencedObjects 會將 pod spec 庖丁解牛
	//檢查裡面有沒有我們要的欄位，目前追 code 只看到 configmap 與 secret 有用到
	names := c.getReferencedObjects(pod)
	//防止競爭加鎖
	c.lock.Lock()
	defer c.lock.Unlock()
    
	//針對 pod 內每一個用到的物件，例如 configmap 、 secret 建立一個對應的 reflector 用以觀察物件的變化
	for name := range names {
		c.objectStore.AddReference(pod.Namespace, name)
	}

```



cacheBasedManager 主要透过AddReference 这个function 来新增reflector 关联的数量，若是第一次建立的出现的reflector 就要建立忙建立对应的reflector ～
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cacheobject/pkg/kubelet/util/manager/watch_based_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
func (c *objectCache) AddReference(namespace, name string) {
	//透過使用者傳入的 namespace 以及物件的名稱 封裝為 objectKey 作為唯一值
	key := objectKey{namespace: namespace, name: name}

	//加鎖避免操作時產生競爭
	c.lock.Lock()
	defer c.lock.Unlock()

	//此時會把剛剛封裝好的 objectKey 作為 key 放入 items map 儲存
	//若發現 items map 沒有資料的話需要幫這個 objectKey 建立對應的 newReflector
	
	item, exists := c.items[key]
	if !exists {
		item = c.newReflector(namespace, name)
		c.items[key] = item
	}
	//最後再讓 item.refCount++ 的原因在於要記錄有多少物件共同使用這個 reflector 。
    
	item.refCount++
}

//來看看 newReflector 做了什麼吧
func (c *objectCache) newReflector(namespace, name string) *objectCacheItem {
	//先建立一個 fieldSelector 用來過濾 kubernetes 物件
	fieldSelector := fields.Set{"metadata.name": name}.AsSelector().String()
	
    
    
	//用 NewObjectCache 時建立的 list watch function 
	//並且透過剛剛建立的 fieldSelector 過濾 kubernetes 物件
	listFunc := func(options metav1.ListOptions) (runtime.Object, error) {
		options.FieldSelector = fieldSelector
		return c.listObject(namespace, options)
	}
    
	watchFunc := func(options metav1.ListOptions) (watch.Interface, error) {
		options.FieldSelector = fieldSelector
		return c.watchObject(namespace, options)
	}
    
	//這裡的 stroe 之前有介紹過，詳細內容可以參考 https://blog.jjmengze.website/posts/kubernetes/source-code/controller/indexer/kubernetes-indexers-local-cache-1/
	//簡單來說就是一個以 index 去儲存的 local storage     
	store := c.newStore()
    
    
	//建立一個 Reflector 詳細的內容也可以參考之前對 controller / Reflector 的分析  https://blog.jjmengze.website/posts/kubernetes/source-code/controller/redlector/reflector-1/
	//簡單來就是建立對 kubernetes 物件的監聽，透過剛剛的 list watch function 去過濾 kubernetes 物件
	reflector := cache.NewNamedReflector(
		fmt.Sprintf("object-%q/%q", namespace, name),
		&cache.ListWatch{ListFunc: listFunc, WatchFunc: watchFunc},
		c.newObject(),
		store,
		0,
	)
	//建立一個 stop channel 用來終止 Reflector
	stopCh := make(chan struct{})
    
	//啟動 reflector 去獲取對應的資源    
	go reflector.Run(stopCh)
    
	//最後將上面產出的物件封裝成 objectCacheItem ，  object chache item 目前使用的 stoe 為 MetaNamespaceKeyFunc stoe 
	// 目前使用這個 reflector 的物件為 0 個 ，可以透過 stop channel 關閉 reflector 。
	return &objectCacheItem{
		refCount:  0,
		store:     store,
		hasSynced: func() (bool, error) { return reflector.LastSyncResourceVersion() != "", nil },
		stopCh:    stopCh,
	}
}

//這裡的 stroe 之前有介紹過，詳細內容可以參考 https://blog.jjmengze.website/posts/kubernetes/source-code/controller/indexer/kubernetes-indexers-local-cache-1/
//簡單來說就是一個以 index 去儲存的 local storage     
func (c *objectCache) newStore() cache.Store {
	//object key 計算方法用預設的 MetaNamespaceKeyFunc
	return cache.NewStore(cache.MetaNamespaceKeyFunc)
}

```



题外话我满喜欢，上述source code 中这一段处理方式。

```
func (c *objectCache) newReflector(namespace, name string) *objectCacheItem {

	...
    
	listFunc := func(options metav1.ListOptions) (runtime.Object, error) {
		options.FieldSelector = fieldSelector
		return c.listObject(namespace, options)
	}
    
	reflector := cache.NewNamedReflector(
		fmt.Sprintf("object-%q/%q", namespace, name),
		&cache.ListWatch{ListFunc: listFunc, WatchFunc: 
        
        ...

```



还记得我们在 `newObjectCache` 的时候有把list object function 带入吗？那一个list object function 到这里才派上用， reflector 在用的时候会像是这样的呼叫练。

```
當 reflector 呼叫 list function 會先經由-------->newReflector裡面的listFunc<加了 field selector >進行處理


newReflector裡面的listFunc<加了 field selector >處理完後會再丟給--------> 使用者定義的 list  watcher function 做最後的處理。
```



#### DeleteReference

透过这个function 减少reflector 关联到的物件数量，当reflector 没有跟其他物件有所关联时需要停止reflector ~

我们先来复习一下cacheBasedManager 是在哪一段呼叫objectCache 的DeleteReference function 如何解除关联的。

cacheBasedManager 主要透过UnregisterPod 这个function 来删除reflector 关联的数量。

```
func (c *cacheBasedManager) UnregisterPod(pod *v1.Pod) {
	var prev *v1.Pod
	//以 pod spc 中的 namespace 與 pod name 封裝為 object key     
	key := objectKey{namespace: pod.Namespace, name: pod.Name}
	//防止競爭加鎖
	c.lock.Lock()
	defer c.lock.Unlock()
    //透過 pod spec 中的 namespace 與 pod name 作為 key 取得 registeredPods  map 中對應的資料
	prev = c.registeredPods[key]
    
    
	//透過  pod spec 中的 namespace 與 pod name 作為 key 刪除 registeredPods  map 中對應的資料
	delete(c.registeredPods, key)
    
	//如果有資料的話，要刪除對應的 `Reflector` ，這裡也是用到 getReferencedObjects 去解析 pod spec 的每個欄位
	if prev != nil {
		for name := range c.getReferencedObjects(prev) {
			c.objectStore.DeleteReference(prev.Namespace, name)
		}
	}
}

```



cacheBasedManager 主要也是透过getReferencedObjects 拿到pod sepc 中有的物件交由objectCache 删除关联的reflector ，接着来看实际上怎么删除的。

```
func (c *objectCache) DeleteReference(namespace, name string) {
	//透過 namespace 與 name 建立一個 object key 用以後續刪除對應的 reference
	key := objectKey{namespace: namespace, name: name}


	//有可能同時多個 thread  在操作所以要上鎖避免競爭
	c.lock.Lock()
	defer c.lock.Unlock()
    
	//從 object cache 中的 map 透過 object key 找尋對應的物件
	//map 中存的是 objectCacheItem ， object cache item 會記錄當前有多少 reflector 
	
	if item, ok := c.items[key]; ok {
		//這個 function 是要刪除 Reference 所以要減少 object cache item 所管理的數量 
		item.refCount--
		//如果 object cache item 已經沒有管理任何 reference 那就需要把最底層的 reflector 停掉
		//並且透過 object key 從 object cache 中的 map 移除自己
		if item.refCount == 0 {
			// Stop the underlying reflector.
			item.stop()
			delete(c.items, key)
		}
	}
}

```



#### Get

一样我们先来看cacheBasedManager 是如何使用的，这里非常简单直接把想要拿到kubernetes 物件名称与namespace 交给objectStore 去处理。

```
func (c *cacheBasedManager) GetObject(namespace, name string) (runtime.Object, error) {
	return c.objectStore.Get(namespace, name)
}

```



我们就接着来看objectStore 是怎么处理的Get 的实作。

```
func (c *objectCache) Get(namespace, name string) (runtime.Object, error) {
	//透過 namespace 與物件名稱 name 建立一個 object key 用以後續從 map 中查詢對應的 reference
	key := objectKey{namespace: namespace, name: name}
    
    
	//有可能同時多個 thread  在進行 get 操作所以要上鎖避免競爭
	c.lock.RLock()
	//先把資料讀出來就可以解鎖囉
	item, exists := c.items[key]
	c.lock.RUnlock()


	//如果透過 object key 找不到對應的資料表示...阿就還沒加過 reference xD
	if !exists {
		return nil, fmt.Errorf("object %q/%q not registered", namespace, name)
	}
    
    
	//會立刻嘗試檢查 reflector 是不是已經同步了，關於怎麼確認是不是已經同步可以參考本篇文章 https://blog.jjmengze.website/posts/kubernetes/source-code/controller/deltafifo/kubernetes-delta-fifo-queue/#impliment
	if err := wait.PollImmediate(10*time.Millisecond, time.Second, item.hasSynced); err != nil {
		return nil, fmt.Errorf("failed to sync %s cache: %v", c.groupResource.String(), err)
	}


	//透過 object key 取得 object 怎麼取得的可以參考之前的文章，會從reflector 的 storage 透過  object  key 把物件取出。
	//這裡有個小 tip 就是我們 reflector 再把物件放入 store 的時候是透過 indexed function 計算後放入 store 的
	//所以我們再取出的時候一樣要先透過 indexed function 算出物件的位置。
	obj, exists, err := item.store.GetByKey(c.key(namespace, name))
	if err != nil {
		return nil, err
	}
	if !exists {
		return nil, apierrors.NewNotFound(c.groupResource, name)
	}
    
    
    
	//因為 storage 儲存的是 interface 什麼東西都可以放進去，我們要先判物件是不是     runtime.Object 型態。
	if object, ok := obj.(runtime.Object); ok {
		// If the returned object is immutable, stop the reflector.
		//
		// NOTE: we may potentially not even start the reflector if the object is
		// already immutable. However, given that:
		// - we want to also handle the case when object is marked as immutable later
		// - Secrets and ConfigMaps are periodically fetched by volumemanager anyway
		// - doing that wouldn't provide visible scalability/performance gain - we
		//   already have it from here
		// - doing that would require significant refactoring to reflector
		// we limit ourselves to just quickly stop the reflector here.
		
		//會先檢查有沒有開啟 FeatureGate  ImmutableEphemeralVolumes 
		//以及物件是否有明確標注處於 immutable = true ，若是有這兩種情況同時存在
		//就停止監控 kubernetes 物件，因為物件已經處於 immutable 狀態
		if utilfeature.DefaultFeatureGate.Enabled(features.ImmutableEphemeralVolumes) && c.isImmutable(object) {
			if item.stop() {
				klog.V(4).Infof("Stopped watching for changes of %q/%q - object is immutable", namespace, name)
			}
		}
		return object, nil
	}
	return nil, fmt.Errorf("unexpected object type: %v", obj)
}

// 因為我們 storage 用的 index function 是 MetaNamespaceKeyFunc 所以我們要從 storage 把 object 拿出來的時候要符合 MetaNamespaceKeyFunc 的格式。
func (c *objectCache) key(namespace, name string) string {
	if len(namespace) > 0 {
		return namespace + "/" + name
	}
	return name
}

```



#### 回收伏笔

上面再说明 `objectCache` 时有卖一个关子，从注解来看 `objectCache` 是一个透过独立watcher 传播物件的local cache 。

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cacheobject/pkg/kubelet/util/manager/watch_based_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// objectCache is a local cache of objects propagated via individual watches.
type objectCache struct {
	listObject    listObjectFunc				//列出 kubernetes 物件的過濾方法
	watchObject   watchObjectFunc				//監控 kubernetes 物件的過濾方法
    ...

```



这里终于可以回收为什么 `objectCache` 是一个透过独立watcher 传播物件的local cache 了！

1. listObjectFunc 与watchObjectFunc

   在执行newReflector function 其实是把传入的listObjectFunc 与watchObjectFunc 加了fieldSelector 用来过滤kubernetes 物件。

   对应到的code 是以下这一段

   soure code

   ```
   func (c *objectCache) newReflector(namespace, name string) *objectCacheItem {
   //先建立一個 fieldSelector 用來過濾 kubernetes 物件
   fieldSelector := fields.Set{"metadata.name": name}.AsSelector().String()
   
   //用 NewObjectCache 時建立的 list watch function 
   //並且透過剛剛建立的 fieldSelector 過濾 kubernetes 物件
   listFunc := func(options metav1.ListOptions) (runtime.Object, error) {
   	options.FieldSelector = fieldSelector
   	return c.listObject(namespace, options)
   }
   
   watchFunc := func(options metav1.ListOptions) (watch.Interface, error) {
   	options.FieldSelector = fieldSelector
   	return c.watchObject(namespace, options)
   }
   
   ```

   

2. newObjectFunc

   在执行newReflector function 是把传入的newObjectFunc 交给reflector package 的NewNamedReflector function 去处理。

   对应到的code 是以下这一段。

   soure code

   ```
   func (c *objectCache) newReflector(namespace, name string) *objectCacheItem {
   ...
   reflector := cache.NewNamedReflector(
   	fmt.Sprintf("object-%q/%q", namespace, name),
   	&cache.ListWatch{ListFunc: listFunc, WatchFunc: watchFunc},
   	c.newObject(),
   	store,
   	0,
   )
   ...
   
   ```

   

3. isImmutableFunc

   最后要讨论的就是在Get function 会把isImmutableFunc 作为判断object 是否为Immutable 物件的方法。

   对应到的code 是以下这一段。

   soure code

   ```
   if utilfeature.DefaultFeatureGate.Enabled(features.ImmutableEphemeralVolumes) && c.isImmutable(object) {
   		if item.stop() {
   			klog.V(4).Infof("Stopped watching for changes of %q/%q - object is immutable", namespace, name)
   		}
   	}
   	return object, nil
   } 
   
   ```

   

## 小结

终于到了做结论的地方了，这章节比较繁琐涉及到多个物件如何共用objectCache ，这里就牵扯到以下概念的实作。

- 共用objectCache 需要纪录多少人（pod 中使用configmap/secret 的各个栏位）使用objectCacheItem
  - 透过map 的key 去纪录对应的使用者是谁， value 去纪录objectCacheItem
  - 第一次建立的时候需要建立objectCacheItem 并且启动reflector
  - 当没有人使用的时候需要回收objectCacheItem
    - 透过stop channel 关闭reflector
- 依赖reflector 的资源监听
  - 需注入list watch function 告知reflector 如何过滤
  - 需注入obejct store 告知reflector 在哪里储存
  - 需注入GVK 确保reflector 的资料正确性（我不确定xD）
- 监听的resource 是否处于Immutable 状态
  - 需要注入isImmutable function 用来确认监听的resource 是否处于Immutable 状态

以上的实作是为了前一章节提到 `cacheBasedManager` 解析完pod spec 得到了secret/configmap 的栏位后，后需要有人监听个栏位对应的kubernetes 资源的变化同时可能会有多个pod spec 使用到相同的secret/configmap 资源所 `objectCache` 以需要额外的处理。

下一章节会开始进入kubernetres configmap 与`cacheBasedManager`、`objectCache`之间的爱恨情仇，文章中若有出现错误的见解希望各位在观看文章的大大们可以指出哪里有问题，让我学习改进，谢谢。
