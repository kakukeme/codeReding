首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

本篇文章基于[Kubernetes kubelet 探测pod 的生命症状Http Get](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-http-get/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)，[Kubernetes kubelet 探测pod 的生命症状tcp socket](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-tcp-socket/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)以及[Kubernetes kubelet 探测pod 的生命症状Exec](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-exec/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)继续往上盖的违建（Ｘ），如果对于前几个章节有兴趣的小伙伴可以先到前三个章节了解一下kubernetes 中的kubelet 是如何分别透过三种手段去完成监测的。

前几个章节内容比较着重于probe 是如何做检测container 的关于物件本身是如何产生的比较没有着墨，因此本篇文章会将重点聚焦在probe 物件是如何产生的以及相关的调用链。

## probe 在哪里绝对难不倒你

我们可以先从kubelet 的资料结构中找到probeManager 、 livenessManager 、 readinessManager 、 startupManager Kubernetes 。kubelet 主要是靠以上这几种manager 去管理probe 的相关物件。
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-probe-obj/pkg/kubelet/kubelet.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// Kubelet is the main kubelet implementation.
type Kubelet struct 
	//用來控制哪些 pod 要加入到 probe manager
	// Handles container probing.
	probeManager prober.Manager

	// 用來探測 container liveness 、 readiness 、  startup prob 結果儲存
	// Manages container health check results.
	livenessManager  proberesults.Manager
    readinessManager proberesults.Manager
	startupManager   proberesults.Manager
	...
}


```



看完了kubelet 其中跟probe 相关的资料结构后我们接着来看看这些资料结构是如何生成与作用的。
等等上面那个prober.Manager 跟proberesults.Manager 到底是什么？别急我们后续会看到！
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-probe-obj/pkg/kubelet/kubelet.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
func NewMainKubelet(kubeCfg *kubeletconfiginternal.KubeletConfiguration,...)(*Kubelet, error) {

	...
	//在這裡透過 proberesults.NewManager() 生成 並且設定 kubelet 的 livenessManager readinessManager startupManager，用來儲存 container 探測 liveness 、 readiness 、  startup prob 的結果
	//後面會繼續解析 proberesults.NewManager 得實作
	klet.livenessManager = proberesults.NewManager()
	klet.readinessManager = proberesults.NewManager()
	klet.startupManager = proberesults.NewManager()
    
	...
	// kubelet 的 probemanager 則是需要組合上面提到的三種 manager 以及 runner 與 recorder 透過 prober.NewManager 新增對應的物件，用以用來控制哪些 pod 要加入到 prob manager。
	//後面會繼續解析 prober.NewManager 得實作    
	klet.probeManager = prober.NewManager(
		klet.statusManager,
		klet.livenessManager,
		klet.readinessManager,
		klet.startupManager,
		klet.runner,
		kubeDeps.Recorder)
	...
} 

```



了解完livenessManager readinessManager startupManager 以及probeManager 的物件是从哪里生成的后，我们就要来看看这四个物件作用在什么地方，以及作用在哪里。

```

//kubelet 會透過 syncLoopIteration 去"得到" container 的狀態並且作出相應的行為，
//至於怎麼跑到 syncLoopIteration 這一段的，我之後再做一篇整理，以減少文章的複雜xD
func (kl *Kubelet) syncLoopIteration(configCh <-chan kubetypes.PodUpdate, handler SyncHandler,
	syncCh <-chan time.Time, housekeepingCh <-chan time.Time, plegCh <-chan *pleg.PodLifecycleEvent) bool {
    
	select {
    
	...    

	//接收 livenessManager channel 傳來的訊號，判斷是否探測成功。若是失敗透過 handleProbeSync function 處理。
	case update := <-kl.livenessManager.Updates():
		if update.Result == proberesults.Failure {
			handleProbeSync(kl, update, handler, "liveness", "unhealthy")
		}
	...
    
	//接收 readinessManager channel 傳來的訊號，判斷是否探測成功。若是失敗透過 statusManager SetContainerReadiness function 與 handleProbeSync function 處理。
	case update := <-kl.readinessManager.Updates():
		ready := update.Result == proberesults.Success
		kl.statusManager.SetContainerReadiness(update.PodUID, update.ContainerID, ready)
		handleProbeSync(kl, update, handler, "readiness", map[bool]string{true: "ready", false: ""}[ready])    
        
	...
    
//接收 startupManager channel 傳來的訊號，判斷是否探測成功。若是失敗透過 statusManager SetContainerReadiness function 與 handleProbeSync function 處理。
	case update := <-kl.startupManager.Updates():
		started := update.Result == proberesults.Success
		kl.statusManager.SetContainerStartup(update.PodUID, update.ContainerID, started)
		handleProbeSync(kl, update, handler, "startup", map[bool]string{true: "started", false: "unhealthy"}[started])
	...
}    

```



## proberesults.Manager

我们首先来看刚刚出现在kubelet struct 里面livenessManager 、 readinessManager 以及startupManager 共同的型态 `proberesults.Manager` 到底是什么吧！

### Interface

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-probe-obj/pkg/kubelet/prober/results/results_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// Manager interface 定義了一些行為，讓實作的物件可以透過 container id 將 pod 的結果儲存起來，並且透過 channel 獲取探測的結果。
type Manager interface {
	// 透過 container id 從 實作者身上得到 result 結果
	Get(kubecontainer.ContainerID) (Result, bool)
	// 透過 container id 設定 pod 探測的結果。實作者需要把結果儲存起來。
	Set(kubecontainer.ContainerID, Result, *v1.Pod)
	// 透過 container id 移除時實作者身上的對應的資料
	Remove(kubecontainer.ContainerID)
	// 透過 channel 接受 pod 探測的結果。
	// NOTE: The current implementation only supports a single updates channel.
	Updates() <-chan Update
}

```



从以上的程式码我们可以看到prober.Manager 是一个interface 他定义了实作物件必须要可以透过container id 将pod 的结果储存起来，并且透过channel 获取探测的结果。

### Struct

我们接着来了解实作prober.Manager interface 的物件manager
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-probe-obj/pkg/kubelet/prober/results/results_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// 實作 manager interface 的物件
type manager struct {
	// 用來保證 map 的一致性的鎖
	sync.RWMutex
	// 使用 map 儲存 container ID 與 probe Result
	cache map[kubecontainer.ContainerID]Result
	// 透過 channel 回傳探針執行結果，透過 Update 物件包起來。
	updates chan Update
}

// 透過 channel 要回傳給 kubelet 的 probe 結果之資料結構以 update 物件將結果包起來。
type Update struct {
	//哪一個 container id 的 探測結果
	ContainerID kubecontainer.ContainerID
	//探測結果
	Result      Result
	//哪一個 pod id 的探測結果
	PodUID      types.UID
}

// 探測得結果，以 int 表示
type Result int

const (
	// golang 處理這種 enum 通常會 iota - 1 表示 Unknown 狀態，避免零值產生的錯誤
	Unknown Result = iota - 1

	// prob 成功回傳以 0 做為代表
	Success

	// prob 成功回傳以 1 做為代表
	Failure
)

//編譯時期確保 manager 物件有實作 manger interface
var _ Manager = &manager{}

```



好，看完了manager struct 结构以及相关的物件长怎么样后，我们还需要了解这个物件是怎么生成的。

### New function

```
// Manager 的 new function 回傳一個空的 manger 物件
func NewManager() Manager {
	//回傳初始化過後的 manager 物件
	return &manager{
		cache:   make(map[kubecontainer.ContainerID]Result),        
		updates: make(chan Update, 20),
	}
}

```



### impliment

manger 物件实作了prober.Manager interface ，我们来看每一个function 实作的内容吧。

```
//傳入 container 從 map 中取的儲存的 prob result 
func (m *manager) Get(id kubecontainer.ContainerID) (Result, bool) {
	//防止競爭加入 rw 鎖
	m.RLock()
	defer m.RUnlock()
	//從 map 中透過 container id 取得對應的 probe 結果
	result, found := m.cache[id]
	return result, found
}

//設定哪一個 container id 的 prob 探測結果(由外部傳入 probe 結果)，manager 單純作為 cache 用。
func (m *manager) Set(id kubecontainer.ContainerID, result Result, pod *v1.Pod) {
	//判斷本次 prob 結果是否與上次相符，若是有不一樣的地方存入 map 。並且包裝成 update 物件傳入 update channel 
	if m.setInternal(id, result) {
		m.updates <- Update{id, result, pod.UID}
	}
}

// 判斷本次 probe 結果是否與上次相符，若是有不一樣的地方存入 map ，並且回傳 true。告知使用者本次 prob 的結果跟上次不一樣。
func (m *manager) setInternal(id kubecontainer.ContainerID, result Result) bool {
	//防止競爭加入 rw 鎖
	m.Lock()
	defer m.Unlock()
	//需要判定本次處理 result 的跟上次 map 中儲存的處理結果是否一致。    
	//若是一致就不進行任何動作，並且回傳 false
    
	//若是不一致就需要將本次處理的結過存到 map 中，並且回傳 true
	prev, exists := m.cache[id]
	if !exists || prev != result {
		m.cache[id] = result
		return true
	}
	return false
}


//透過 container id 將 map 中對應的資料刪除
func (m *manager) Remove(id kubecontainer.ContainerID) {
	//防止競爭加入 rw 鎖
	m.Lock()
	defer m.Unlock()
	//刪除 map 對應資料
	delete(m.cache, id)
}

//回傳update channel
func (m *manager) Updates() <-chan Update {
	return m.updates
}


```



到此简单的分析完实作了proberesults.Manager interface 的manger 物件，接着我们要来看看manger 物件在kubelet 中怎么被使用的的。

等等！不是还有一个没有分析吗？probeManager 他的型态不都是prober.Manager 吗？怎么不一起说说呢？这个部分我认为先了解livenessManager readinessManager startupManager 怎么用之后我们再来看

### proberesults.Manager 触发的时机

这边虽然我们开始在 `syncLoopIteration` 小节已经看过，我想想再提一次，原来livenessManager readinessManager startupManager 三个物件在 `syncLoopIteration` 时候会用到。

```
func (kl *Kubelet) syncLoopIteration(configCh <-chan kubetypes.PodUpdate, handler SyncHandler,
	syncCh <-chan time.Time, housekeepingCh <-chan time.Time, plegCh <-chan *pleg.PodLifecycleEvent) bool {
    
	select {
    
	...    

	//接收 livenessManager channel 傳來的訊號，判斷是否探測成功。若是失敗透過 handleProbeSync function 處理。
	case update := <-kl.livenessManager.Updates():
		if update.Result == proberesults.Failure {
			handleProbeSync(kl, update, handler, "liveness", "unhealthy")
		}
	...
    
	//接收 readinessManager channel 傳來的訊號，判斷是否探測成功。若是失敗透過 statusManager SetContainerReadiness function 與 handleProbeSync function 處理。
	case update := <-kl.readinessManager.Updates():
		ready := update.Result == proberesults.Success
		kl.statusManager.SetContainerReadiness(update.PodUID, update.ContainerID, ready)
		handleProbeSync(kl, update, handler, "readiness", map[bool]string{true: "ready", false: ""}[ready])    
        
	...
    
//接收 startupManager channel 傳來的訊號，判斷是否探測成功。若是失敗透過 statusManager SetContainerReadiness function 與 handleProbeSync function 處理。
	case update := <-kl.startupManager.Updates():
		started := update.Result == proberesults.Success
		kl.statusManager.SetContainerStartup(update.PodUID, update.ContainerID, started)
		handleProbeSync(kl, update, handler, "startup", map[bool]string{true: "started", false: "unhealthy"}[started])
	...
}    

```



只有Updates() function 被用到这样吗？刚刚看到interface 定义了一堆东西，怎么都没用到呢？
还记得 `probeManager` 怎么生成的吗？

在 `prob 在哪裏絕對難不倒你` 章节有看过 `probeManager` 是由其他三种manager 组合再一起生成的，那魔鬼一定就藏在这里面。

```
	...
	// kubelet 的 probemanager 則是需要組合上面提到的三種 manager 以及 runner 與 recorder 透過 prober.NewManager 新增對應的物件，用以用來控制哪些 pod 要加入到 prob manager。
	//後面會繼續解析 prober.NewManager 實作    
	klet.probeManager = prober.NewManager(
		klet.statusManager,
		klet.livenessManager,
		klet.readinessManager,
		klet.startupManager,
		klet.runner,
		kubeDeps.Recorder)
	...

```



所以说其他function 会在probeManager 里面被调用啰？是没错！所以我们需要进一步的来分析 `probeManager` 是什么。

## 小结

前几个章节内容比较着重于probe 是如何做检测container 的关于物件本身是如何产生的比较没有着墨，因此本篇文章会将重点聚焦在probe 物件是如何产生的以及相关的调用链。

由上文分析所得知kubernetes 的kubelet 资料结构中包含了livenessManager、readinessManager 以及startupManager 他们的型态皆为 `proberesults.Manager` 主要负责储存liveness 、 readiness 以及startup 的probe 结果。
目前只看到kubelet 在 `syncLoopIteration` 的阶段会透过 `proberesults.Manager`的Updates function 取出probe 的变化，并且依照便会进行不同行为。

这里我保留了一个伏笔在下篇文章做揭晓，`proberesults.Manager`的其他function 去哪了？
kubelet 资料结构中型态为 `probeManager` 的probeManager 到底是什么跟livenessManager、readinessManager 以及startupManager 又有什么关西呢？
