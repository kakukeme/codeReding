首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

本章节将探讨在kubernetes 中每个node 是如何得知kubernetes 资源的变化，这里的资源需要特别说明一下是指configmap 与secret ，我们都知道当configmap 或是secret 发生变化的时候只要重启pod （没有设定hot reload 的情况）就能得到最新的资料，这中间生了什么事情node 怎么得知configmap / secret 发生变化了？

我们先把焦点移到`Manager`interface 吧！

## interface

这个interface 主要定义了几个方法这些方法，这些方法都是针对pod 的资源我们就来看看定义了什么吧。

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cachebasedmanager/pkg/kubelet/util/manager/cache_based_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
type Manager interface {
	// 通過 pod namespace 和 pod name 就能獲得相應的 kubernetes 物件
	GetObject(namespace, name string) (runtime.Object, error)

	// Register function 主要是我們給這個 function 一個 pod spec 實作的物件需要產生對應的 reflector 
	//以 pod 中用到 configmap 為例，RegisterPod 就需要產生 pod 內用到所得有的 configmap reflector  
	RegisterPod(pod *v1.Pod)

	// UnregisterPod function 定義了當給定一個 pod spec  實作的物件必須把 pod 物件所用到 reflector 都消滅掉
	// 當 pod 被刪除時， pod 中用到 configmap 為例，UnregisterPod 就需要 pod 內用到所得有的 configmap reflector  都移除
	UnregisterPod(pod *v1.Pod)
}

```



我们大概了解这个interface 定义的方向后，就需要去了解哪个物件实作这个interface 啰！

## cacheBasedManager

实作manger interface 的是 `cacheBasedManager` 这个物件，我们看这物件定义了什么吧！
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cachebasedmanager/pkg/kubelet/util/manager/cache_based_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```

type cacheBasedManager struct {
	objectStore          Store						//主要用來儲存 reflector 觀測到的物件狀態
	getReferencedObjects func(*v1.Pod) sets.String			//主要用來找到 pod 內所有關聯的欄位，例如 configmap 就有可能出現在 envform configMapKeyRef 、 envform configMapRef 或是 volumes configMap  

	lock           sync.Mutex						//可能有大量的 pod 同時塞入我們必須保證狀態的一致性
	registeredPods map[objectKey]*v1.Pod					//主要用來儲存哪個 pod 已經在觀測名單了
}

```



了解了 `cacheBasedManager` 的属性之后就可以来了解怎么新增出这个物件。

## new function

new function 其实也很简单，就是使用者传什么这里接收什么，没有偷做事。

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cachebasedmanager/pkg/kubelet/util/manager/cache_based_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
func NewCacheBasedManager(objectStore Store, getReferencedObjects func(*v1.Pod) sets.String) Manager {
	return &cacheBasedManager{
		objectStore:          objectStore,
		getReferencedObjects: getReferencedObjects,
		registeredPods:       make(map[objectKey]*v1.Pod),
	}
}

```



快速地了解怎么建立起 `cacheBasedManager` 这个物件之后，我们接着要来了解实作层面做了什么吧！

## impliment

### GetObject

这一个实作其实也很简单就是把请求委托给objectStore 去拿到对应的物件， objectStore 的详细实作我想保留在下一章节再介绍（会失焦QQ），简单来说就是一个储存空间可以从这个储存空间拿到物件最新的状态，这个状态会是runtime object 需要经过二次转换成对应的物，例如： configmap secret 等等。
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cachebasedmanager/pkg/kubelet/util/manager/cache_based_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
func (c *cacheBasedManager) GetObject(namespace, name string) (runtime.Object, error) {
	return c.objectStore.Get(namespace, name)
}

```



### RegisterPod

这个实作的function 比较复杂一点，需要透过getReferencedObjects function 拆解pod spec 取出对应的资料，这个getReferencedObjects 可以抽换成get configmap ReferencedObjects 的或是secret ReferencedObjects 的。

以configmap ReferencedObjects 来说就可以会从pod spec 拆解envform configMapKeyRef 、 envform configMapRef 或是volumes configMap 得到pod spec 中configmap 对应的名称，透过这些名称我们将建立相应的reflector 。

最后将pod 的namespace 与pod name 作为key 储存在registeredPods ，表示kubelet 已经监控这个物件了。

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cachebasedmanager/pkg/kubelet/util/manager/cache_based_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
func (c *cacheBasedManager) RegisterPod(pod *v1.Pod) {
	//當 kubernetes assign 一個 pod 到 node 上的時候
	//kubelet 會取得 pod 的相關資訊，getReferencedObjects 會將 pod spec 庖丁解牛
	//檢查裡面有沒有我們要的欄位，目前追 code 只看到 configmap 與 secret 有用到，等等看範例會比較容易理解
	names := c.getReferencedObjects(pod)
	//防止競爭加鎖
	c.lock.Lock()
	defer c.lock.Unlock()
    
	//針對 pod 內每一個用到的物件，例如 configmap 、 secret 建立一個對應的 reflector 用以觀察物件的變化
	for name := range names {
		c.objectStore.AddReference(pod.Namespace, name)
	}
    
	//以 pod namespace 與 pod name 作為 key 儲存在 registeredPods 的 map 中 value  會儲存  pod  的資訊
	//用來之後判斷 pod 更新哪些關聯到 reference 要更新或是刪除。
	var prev *v1.Pod
	key := objectKey{namespace: pod.Namespace, name: pod.Name}
	prev = c.registeredPods[key]
	c.registeredPods[key] = pod
    
	// 如果發生某一個狀況，例如 pod 更新了
	// registeredPods map 中會包含舊的 pod spec 也就是說 prev 的會有資料
	
	
	//因為 pod 更新的時候有可能某些的資料已經不需要觀測了，例如 configmap 欄位沒用到了
	//因此在這裡會判斷是否存在舊 pod 資料若是有的話會透過線透過舊資料的 pod name 與 namespace 作為 objectKey
	//以這個 objectkey 透過 getReferencedObjects function 找到舊 pod  secret、configmap 用到的欄位
	
    
	//最後透過 objectStore.DeleteReference 更新關聯到的 reference ，為什麼要更新呢？
	//因為 pod 更新的時候有可能某些的資料已經不需要觀測了，例如 configmap  、 Secret 欄位沒用到了需要把沒用到 reference 刪掉。
	if prev != nil {
		for name := range c.getReferencedObjects(prev) {
			c.objectStore.DeleteReference(prev.Namespace, name)
		}
	}
}

```



要理解这里的code 我认为要搭配test code 来互相配合会比较好理解，简单来说就是当有pod 来注册，会将部分资讯提取出来并且生成对应的`Reflector`。

底下为test code 部分，重点在于cacheBasedManager 的getReferencedObjects 参数在做什么，总结一句话的话就是过滤pod spec 中的栏位，例如撷取pod 中的configmap 栏位或是secret 栏位，我们直接来看code 吧。

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cachebasedmanager/pkg/kubelet/util/manager/cache_based_manager_test.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
//這個 function 等等會用來作爲 cacheBasedManager getReferencedObjects()
//當 cacheBasedManager 呼叫 getReferencedObjects() 時會使用這一個 function 計算 pod 內用到 secret 的地方。
func getSecretNames(pod *v1.Pod) sets.String {
	//建立一個 set 
	result := sets.NewString()
	//下面會看到實作，這裡就簡單的解釋一下這個 function 會遞迴解析  pod spec 中所有用到 secret 的欄位
	//有用到的就全部透過 call back function 傳回來用 set 儲存。
	podutil.VisitPodSecretNames(pod, func(name string) bool {
		result.Insert(name)
		return true
	})
	//回傳 set 
	return result
}

//傳入 store 物件用來儲存 kubernetes 物件狀態並且使用 getSecretNames function 作為  getReferencedObjects
//建立一個實作 Manger 的 cacheBasedManager物件
func newCacheBasedSecretManager(store Store) Manager {
	return NewCacheBasedManager(store, getSecretNames)
}

//模擬 pod 內有的 secret 欄位
type secretsToAttach struct {
	imagePullSecretNames []string
	containerEnvSecrets  []envSecrets
}

func TestCacheInvalidation(t *testing.T) {
	...
	// 這裡的 store 就先把它當作 一個儲存物件狀態的地方就好 就好
	store := newSecretStore(fakeClient, fakeClock, noObjectTTL, time.Minute)
	//建立一個實作 Manger 的 cacheBasedManager 物件
	manager := newCacheBasedSecretManager(store)
    
	// 模擬一個 pod 有 secret 的地方，這裡模擬 pod spec 中友 image pull secret 以及 env secret  
	s1 := secretsToAttach{
		imagePullSecretNames: []string{"s1"},
		containerEnvSecrets: []envSecrets{
			{envVarNames: []string{"s1"}, envFromNames: []string{"s10"}},
			{envVarNames: []string{"s2"}},
		},
	}
	//分成兩階段來看 podWithSecrets("ns1", "name1", s1) 這裡就是建立一個 pod spec 
	// pod 名稱為 name1 , namespace 為 ns1 最後把  剛剛模擬 secret 的地方放入 pod spec 
	// 第二階段為將 pod spec 註冊到 cacheBasedManager 中，這裡就可以參考上面提到的 RegisterPod 時會針對每個欄位建立對應的 Reference 
	manager.RegisterPod(podWithSecrets("ns1", "name1", s1))
	// Fetch both secrets - this should trigger get operations.
	// 以下就不屬於本文要討論的範疇，還是簡單的過水一下
	// 這裡直接觸發這裡直接觸發 store get 表示 store 拿到這個物件的變化
	// 分別拿到 namespace ns1 的 s1 變化 s10 變化以及 s2的變化
	store.Get("ns1", "s1")
	store.Get("ns1", "s10")
	store.Get("ns1", "s2")
	//最後透過 kubernetes mock 出來的 client 看看是否觀察到三次的變化。
	actions := fakeClient.Actions()
	assert.Equal(t, 3, len(actions), "unexpected actions: %#v", actions)
	// 清除 mock client 的變化，也就是重新計署的意思    
	fakeClient.ClearActions()    
    ...
}

```



[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cachebasedmanager/pkg/api/v1/pod/util.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// VisitPodSecretNames invokes the visitor function with the name of every secret
// referenced by the pod spec. If visitor returns false, visiting is short-circuited.
// Transitive references (e.g. pod -> pvc -> pv -> secret) are not visited.
// Returns true if visiting completed, false if visiting was short-circuited.
func VisitPodSecretNames(pod *v1.Pod, visitor Visitor) bool {
	//透過 for range 遞迴 pod spec 中的 ImagePullSecrets 欄位
	for _, reference := range pod.Spec.ImagePullSecrets {
            //把找到的名稱傳入 visitor function 中，還記得上面有提過的 visitor function 嗎？
            //上面 visitor function 做的事情就是將 name 存入 set 回傳 true 
		if !visitor(reference.Name) {
			return false
		}
	}
	//下面會看到實作方式這裡就簡短的解釋一下，透過 for range 遞迴 pod spec 中的 continaer 的欄位中會出現 secret 的欄位
    //若是欄位有數值就傳入 visitor function 將 name 存入 set 。
	VisitContainers(&pod.Spec, AllContainers, func(c *v1.Container, containerType ContainerType) bool {
		return visitContainerSecretNames(c, visitor)
	})
    
	//恩...我覺得在這裏定了這個沒有什麼特別的意義．．．就是等等用來承載 pod spec 中 VolumeSource 欄位的數值
    //放到 for 迴圈裡面應該也行吧？xD
	var source *v1.VolumeSource

	// 透過for 迴圈遞迴 pod spec 中的 volumes欄位，這裡可以看到各式各樣的volume 例如 Ceph Cinder Flex 
	// 這些都有可能會用到 secret 我們需要一個一個檢視，若是有找到 secret 就要傳入 visitor function 儲存在 set 中。 
	for i := range pod.Spec.Volumes {
		source = &pod.Spec.Volumes[i].VolumeSource
        //由於 volume 種類眾多我這邊只挑幾個來說明
		switch {
		// 如果VolumeSource的欄位是 Azure file 的話就需要進一步判斷
		// 底下的 secret name 欄位，若是有這個欄位就把裡面的數值傳入 visitor function 
		// 透過 visitor function 儲存在 set 中。 
		case source.AzureFile != nil:
			if len(source.AzureFile.SecretName) > 0 && !visitor(source.AzureFile.SecretName) {
				return false
			}
		// 如果 VolumeSource 的欄位是 CephFS 的話就需要進一步判斷
		// 底下的 secret name 欄位，若是有這個欄位就把裡面的數值傳入 visitor function 
		// 透過 visitor function 儲存在 set 中。 
		case source.CephFS != nil:
			if source.CephFS.SecretRef != nil && !visitor(source.CephFS.SecretRef.Name) {
				return false
			}
		    ...
            
		//其他實作方式都差不多，有興趣的小夥伴可以回 source code code  base 看看
		}
	}
	return true
}

func VisitContainers(podSpec *v1.PodSpec, mask ContainerType, visitor ContainerVisitor) bool {
	if mask&InitContainers != 0 {
		for i := range podSpec.InitContainers {
			if !visitor(&podSpec.InitContainers[i], InitContainers) {
				return false
			}
		}
	}
	if mask&Containers != 0 {
		for i := range podSpec.Containers {
			if !visitor(&podSpec.Containers[i], Containers) {
				return false
			}
		}
	}
	if mask&EphemeralContainers != 0 {
		for i := range podSpec.EphemeralContainers {
			if !visitor((*v1.Container)(&podSpec.EphemeralContainers[i].EphemeralContainerCommon), EphemeralContainers) {
				return false
			}
		}
	}
	return true
}

```



### UnregisterPod

顾名思意就是反注册pod ，那到底是反注册什么呢？在上面我们有提到 `RegisterPod` 就是递回pod spec 的每个栏位（ configmap / secret ），以及pod spec 中的namespace 与pod name 作为key 储存在registeredPods 的map。

反过来说反注册就是要递回pod spec 的每个栏位（ configmap / secret ），并且透过pod spec 中的namespace 与pod name 作为key 删除registeredPods map 中对应的资料，废话就不多说了来code 吧。

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cachebasedmanager/pkg/kubelet/util/manager/cache_based_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

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
	//上面有提過 getReferencedObjects 如果不熟悉的小夥伴可以往上滑去找找
	if prev != nil {
		for name := range c.getReferencedObjects(prev) {
			c.objectStore.DeleteReference(prev.Namespace, name)
		}
	}
}

```



## 结论

本篇文章主要我们了解了cacheBasedManager 透过Register function 将一个pod spec 物件拆解并且交由objectStore 产生对应的reflector 。以pod 中用到configmap 为例，RegisterPod 就需要产生pod 内用到所得有的configmap 并且交由objectStore 产生对应的reflector 。

cacheBasedManager 透过UnregisterPod function 将一个pod spec 把pod 物件所用到reflector 都消灭掉，以configmap 为例，UnregisterPod 就需要pod 内用到所得有的configmap reflector 协同objectStore 都移除。

cacheBasedManager 透过GetObject function 通过pod namespace 和pod name 经由objectStore 的协助我们就能从objectStore 获得相应的kubernetes 物件。

上述有提到objectStore 主要用来储存reflector 观测到的物件状态，下一篇文章会解析objectStore 的实作，看 `cacheBasedManager` 把pod 内的栏位解析完后，怎么把需要的资料整理成reflector 。

文章中若有出现错误的见解希望各位在观看文章的大大们可以指出哪里有问题，让我学习改进，谢谢。
