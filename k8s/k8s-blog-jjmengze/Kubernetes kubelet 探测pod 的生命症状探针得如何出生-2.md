首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

希望阅读文章的朋友可以先去看前一篇[Kubernetes kubelet 探测pod 的生命症状探针得如何出生-1
](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-livenessmanager/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)，了解一下前后文的关系比较好理解本篇要说明的重点。

另外想要了解kubernetes probe 有哪些型态以及底层是如何实作的可以参考以下三篇文章[Kubernetes kubelet 探测pod 的生命症状Http Get](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-http-get/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)，[Kubernetes kubelet 探测pod 的生命症状tcp socket](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-tcp-socket/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)以及[Kubernetes kubelet 探测pod 的生命症状Exec](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-exec/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)，可以从这三篇文章了解kubernetes probe 如何提供tcp socket、 exec 以及http get 三种方法的基本操作。

上一篇文章[Kubernetes kubelet 探测pod 的生命症状探针得如何出生-1
](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-livenessmanager/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)，livenessManager readinessManager startupManager 以及probeManager 的物件是从哪里生成的，用在什么地方，以及作用在哪里。因此本篇文章会将重点聚焦在这些物件之后的相关的调用链。

这边来回顾一下proberesults.Manager 是怎么产生的
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-probe-obj2/pkg/kubelet/kubelet.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// Kubelet is the main kubelet implementation.
type Kubelet struct 
	//用來控制哪些 pod 要加入到 probe manager
	// Handles container probing.
	probeManager prober.Manager

	// 用來探測 container liveness 、 readiness 、  startup probe 結果儲存
	// Manages container health check results.
	livenessManager  proberesults.Manager
	readinessManager proberesults.Manager
	startupManager   proberesults.Manager
	...
}

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



回顾完proberesults.Manager 是怎么产生的，我们就接着来了解一下上篇未讲完的内容吧。
上一个章节中我们有谈到 `proberesults.Manager` 是一个interface 他定义了许多方法[ref](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-livenessmanager/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc#interface)，我们在kubelet 的 `syncLoopIteration` 阶段可以发现这边会呼叫 `proberesults.Manager` 的update function 等待channel 把资料送来[ref](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-livenessmanager/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc#proberesultsmanager-觸發的時機)，就没有看到其他地方有呼叫 `proberesults.Manager` 所定义的其他方法了。

我们循着线索找到prober.Manager 是由三种manager 分别是container liveness 、 readiness 、 startup 组合而成的（他们的型态都为`proberesults.Manager`）。

所以说 `proberesults.Manager` 定义的其他function 会在 `prober.Manager` 里面被调用啰？是没错！所以我们需要进一步的来分析 `prober.Manager` 是什么。

## prober.Manager

我们了解完livenessManager 、 readinessManager 以及startupManager 之后，需要进一分析三个组再一起变成的probeManager 到底是什么东西，我们先来看他的型态`prober.Manager`。

### interface

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-probe-obj2/pkg/kubelet/prober/prober_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
type Manager interface {
	// 為每個 container probe 創建新的 probe worker，在建立 pod 時呼叫。
	AddPod(pod *v1.Pod)

	// 為刪除已存在的 container probe worker，在移除 pod 時呼叫。
	RemovePod(pod *v1.Pod)

	// CleanupPods handles cleaning up pods which should no longer be running.
	// It takes a map of "desired pods" which should not be cleaned up.
	CleanupPods(desiredPods map[types.UID]sets.Empty)

	// UpdatePodStatus modifies the given PodStatus with the appropriate Ready state for each
	// container based on container running status, cached probe results and worker states.
	UpdatePodStatus(types.UID, *v1.PodStatus)
}

```



### struct

了解完 `prober.Manager` 所定义的function 之后我们要来看实作的物件是谁啰！

```
type manager struct {
	// 用來記錄哪個 container 對應哪一個 probe worker
	workers map[probeKey]*worker
	// 防止存取 worker 競爭
	workerLock sync.RWMutex

	// 提供 pod IP 和 container ID
	statusManager status.Manager

	// 用來存取 readiness probe 的結果
	readinessManager results.Manager

	// 用來存取 liveness probe 的結果
	livenessManager results.Manager

	// 用來存取 startup probe 的結果
	startupManager results.Manager

	// probe 實作層，可以參考[Kubernetes kubelet 探測 pod 的生命症狀 Http Get](https://blog.jjmengze.website/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-http-get/)，[Kubernetes kubelet 探測 pod 的生命症狀 tcp socket](https://blog.jjmengze.website/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-tcp-socket/)以及[Kubernetes kubelet 探測 pod 的生命症狀 Exec](https://blog.jjmengze.website/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-exec/)
	prober *prober

	start time.Time
}

// probekey 包含 probe ，pod id 、 container name 、 以及目前在 probe 型態，用來識別 worker ，也可以視為 worker 唯一辨別方式。
type probeKey struct {
	podUID        types.UID
	containerName string
	probeType     probeType
}


//這個部分可以回顧[Kubernetes kubelet 探測 pod 的生命症狀 Http Get](https://blog.jjmengze.website/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-http-get/)，[Kubernetes kubelet 探測 pod 的生命症狀 tcp socket](https://blog.jjmengze.website/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-tcp-socket/)以及[Kubernetes kubelet 探測 pod 的生命症狀 Exec](https://blog.jjmengze.website/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-exec/)
type prober struct {
	//注入 probe exec 實作對象
	exec execprobe.Prober
	//注入 http get 實作對象
	readinessHTTP httpprobe.Prober
	livenessHTTP  httpprobe.Prober
	startupHTTP   httpprobe.Prober
	//注入 tcp socket probe 實作對象
	tcp           tcpprobe.Prober
	//注入 CRI 控制器用來對 container 下達命令
	runner        kubecontainer.CommandRunner

	//注入事件紀錄收集器
	recorder record.EventRecorder
}

```



### New function

看完了资料结构是如何定义后我们来看怎么把这个物件建立起来。

```
func NewManager(
	statusManager status.Manager,
	livenessManager results.Manager,
	readinessManager results.Manager,
	startupManager results.Manager,
	runner kubecontainer.CommandRunner,
	recorder record.EventRecorder) Manager {

	//建立實際上各種執行 probe 的物件（Http get、Tcp socket、Exec）
	prober := newProber(runner, recorder)
    //剩下的是把輸入的物件進行簡單的組合
	return &manager{
		statusManager:    statusManager,
		prober:           prober,
		readinessManager: readinessManager,
		livenessManager:  livenessManager,
		startupManager:   startupManager,
		workers:          make(map[probeKey]*worker),
		start:            clock.RealClock{}.Now(),
	}
}

```



### AddPod

```

//當 kubelet 接收到有 pod 到加入到這個節點時，會觸發 HandlePodAdditions function ，並且傳入有哪些 pod 要加入。
func (kl *Kubelet) HandlePodAdditions(pods []*v1.Pod) {

	...
	// for 迴圈地回所有要加入的 pod 	
	for _, pod := range pods {
    
		...
		//加入到 probeManager 中
		kl.probeManager.AddPod(pod)
        
		...
	}
}

```



处理pod 是否需要probe ，如果有需要则建立对应的probe worker （worker 后面我们会看到是什么，这边先知道有这个事情发生即可）

```
func (m *manager) AddPod(pod *v1.Pod) {
	//確保 map 一致性上鎖
	m.workerLock.Lock()
	defer m.workerLock.Unlock()

	//以傳入 pod uid 組合成 probeKey 物件
	key := probeKey{podUID: pod.UID}

	//遞迴 pod spec container 欄位
	for _, c := range pod.Spec.Containers {
		//設定 probeKey containerName 為 container name
		key.containerName = c.Name

		//如果 pod spec container 有設定 StartupProbe 的話
		if c.StartupProbe != nil {
			//將 key probeType 設定成 startup
			key.probeType = startup
            
			//透過 worker map 檢查是否曾經處理過相同的 key ，如果有表示已經有 worker 在處理了，直接略過不理。
			if _, ok := m.workers[key]; ok {
				klog.ErrorS(nil, "Startup probe already exists for container",
					"pod", klog.KObj(pod), "containerName", c.Name)
				return
			}
			
			//建立新的 worker，並且傳入 pod 資訊、 container 資訊以及 manager 本身，後續會看到 worker 到底是什麼這邊先了解有這個步驟就好。
			w := newWorker(m, startup, pod, c)
			
			//將 key 與對應的 worker 設定到 map 中 
			m.workers[key] = w
            
			//啟動 worker
			go w.run()
		}
        
		//如果 pod spec container 有設定 ReadinessProbe 的話
		if c.ReadinessProbe != nil {
			//將 key probeType 設定成 readiness
			key.probeType = readiness
            
			//透過 map 檢查是否曾經處理過相同的 key ，如果有表示已經處理過了。不再處理。
			if _, ok := m.workers[key]; ok {
				klog.ErrorS(nil, "Readiness probe already exists for container",
					"pod", klog.KObj(pod), "containerName", c.Name)
				return
			}
            
			//建立新的 worker並且傳入 pod 資訊、 container 資訊以及 manager 本身，後續會看到 worker 到底是什麼這邊先了解有這個步驟就好。
			w := newWorker(m, readiness, pod, c)
            
			//將 key 與對應的 worker 設定到 map 中 
			m.workers[key] = w
            
			//啟動 worker
			go w.run()
		}
        
		//如果 pod spec container 有設定 LivenessProbe 的話
		if c.LivenessProbe != nil {
			//將 key probeType 設定成 readiness
			key.probeType = liveness
            
			//透過 map 檢查是否曾經處理過相同的 key ，如果有表示已經處理過了。不再處理。
			if _, ok := m.workers[key]; ok {
				klog.ErrorS(nil, "Liveness probe already exists for container",
					"pod", klog.KObj(pod), "containerName", c.Name)
				return
			}
            
			//建立新的 worker並且傳入 pod 資訊、 container 資訊以及 manager 本身，後續會看到 worker 到底是什麼這邊先了解有這個步驟就好。
			w := newWorker(m, liveness, pod, c)
            
			//將 key 與對應的 worker 設定到 map 中 
			m.workers[key] = w
            
			//啟動 worker
			go w.run()
		}
	}
}

```



### RemovePod

```
// 當 kubelet 接收到有哪些 pod 要從這個節點移除，會觸發 HandlePodRemoves function ，並且傳入有哪些 pod 要從節點移除。
func (kl *Kubelet) HandlePodRemoves(pods []*v1.Pod) {
	start := kl.clock.Now()
	for _, pod := range pods {
		
		kl.probeManager.RemovePod(pod)
	}
}

```



```
//當 pod 被移除的時候會觸發
func (m *manager) RemovePod(pod *v1.Pod) {
	//確保 map 一致性上鎖
	m.workerLock.RLock()
	defer m.workerLock.RUnlock()

	//以 pod uid 組合成 probeKey 物件
	key := probeKey{podUID: pod.UID}
    
	//遞迴 pod spec container 欄位
	for _, c := range pod.Spec.Containers {
    
		//設定 probeKey containerName 為 container name
		key.containerName = c.Name
        
		//因為需要找到 map 裡面 key 對應 worker，現在 probeKey 物件已經有 podUID containerName 現在還缺少 probeType
		//我們需要用 for 迴圈跑 readiness, liveness, startup 組合 probeKey 物件物件
		//再透過組合出來的 probeKey 從 map 中找找看有沒有對應的 worker
		for _, probeType := range [...]probeType{readiness, liveness, startup} {
			
            //設定 probeType 為 readiness 或是 liveness 或是 startup
			key.probeType = probeType
            
			//再透過組合出來的 probeKey 從 map 中找找看有沒有對應的 worker，接著關閉 worker， 後續會看到 worker 到底是什麼這邊先了解有這個步驟就好。
			if worker, ok := m.workers[key]; ok {
				worker.stop()
			}
		}
	}
}

```



### UpdatePodStatus

根据container 运行状态、probe 结果，针对每个container 进行适当状态修改。

```
// 給定 pod 狀態，為 pod 建立最終的 API pod 狀態。這裡的狀態可以想像成 pod spec 最後的 pod status 欄位。
func (kl *Kubelet) generateAPIPodStatus(pod *v1.Pod, podStatus *kubecontainer.PodStatus) v1.PodStatus {
	...
	spec := &pod.Spec
    
	...
	//透過pod status 
	kl.probeManager.UpdatePodStatus(pod.UID, podStatus)
    
	...
	return *s
} 

```



```
func (m *manager) UpdatePodStatus(podUID types.UID, podStatus *v1.PodStatus) {
	//透過傳入的 pod status 遞迴所有的 ContainerStatuses
	for i, c := range podStatus.ContainerStatuses {
		var started bool

		//看看 container 是否已經開始執行，已經開始執行的話需要判斷 startup probe 是否成功。
		if c.State.Running == nil {
			
			//如果還沒開始執行設定為 false
			started = false
            
		// 傳入的 container id 透過 kubecontainer.ParseContainerID function 轉成 containerId 物件
		// 透過 startupManager get function 傳入 containerId 物件得到 startup 探測結果。
		} else if result, ok := m.startupManager.Get(kubecontainer.ParseContainerID(c.ContainerID)); ok {
        
			// 如果 startup 探測成功的話就設定 started 為 true   
			started = result == results.Success
		} else {
        
			// 透過 pod id 、container name 與 startup 從 getWorker 拿到 worker
			_, exists := m.getWorker(podUID, c.Name, startup)
            
			//如果找不到 worker ，就當作探測成功，因為沒有 startup worker 
			started = !exists
		}

		//依照幾種情況來修正 container 狀態
		//State.Runnin 判斷 container 是否啟動
		//startupManager.Get 探測 container startup 狀態
		//getWorker 判斷是否有 worker ，若沒有worker 表示沒有 startup probe
		//依照上述情況設定設定 Container 的 Statuses
		podStatus.ContainerStatuses[i].Started = &started

		// 若是確認 container 已經啟動
		if started {
			var ready bool
            
			// 再次確認 container 是否有啟動
			if c.State.Running == nil {
				ready = false
                
			// 如果 container 有啟動繼續透過 傳入的 container id 透過 kubecontainer.ParseContainerID function 轉成 containerId 物件
			// readinessManager.Get  傳入 containerId 物件得到 readiness 探測結果。
			} else if result, ok := m.readinessManager.Get(kubecontainer.ParseContainerID(c.ContainerID)); ok {

				// 如果 readiness 探測成功的話就設定 started 為 true
				ready = result == results.Success
			} else {
            
				// 透過 pod id 、container name 與 readiness 從 getWorker 拿到 readiness worker 
				w, exists := m.getWorker(podUID, c.Name, readiness)
                
				//如果找不到 worker ，就當作探測 readinessProbe 成功
				ready = !exists // no readinessProbe -> always ready
				
				//如果有找到我們需要進一步判斷 worker 狀態
				if exists {
					// 手動觸發探測下次就可以得知結果
					select {
					case w.manualTriggerCh <- struct{}{}:
					default: // Non-blocking.
						klog.InfoS("Failed to trigger a manual run", "probe", w.probeType.String())
					}
				}
			}
			//依照幾種情況來修正 container 狀態
			//State.Runnin 判斷 container 是否啟動
			//readinessManager.Get 探測 container readiness 狀態
			//getWorker 判斷是否有 worker 
			//依照上述情況設定設定 Container 的 Statuses
			podStatus.ContainerStatuses[i].Ready = ready
		}
	}
    
	// 如果 init container 為成功退出或就把 init container status ready 設定為成功。
	for i, c := range podStatus.InitContainerStatuses {
		//預設 ready 為 false
		var ready bool
		
		//如果 init container 狀態為 Terminated 並且退出狀態碼為 0 ，就把 ready 設定為 true
		if c.State.Terminated != nil && c.State.Terminated.ExitCode == 0 {
			ready = true
		}
		// init container status ready 設定為成功。
		podStatus.InitContainerStatuses[i].Ready = ready
	}
}

```



### CleanupPods

执行一系列清理工作，包括终止pod worker、杀死不需要的pod 以及删除orphaned 的volume/pod directories。

```
// NOTE: This function is executed by the main sync loop, so it
// should not contain any blocking calls.
func (kl *Kubelet) HandlePodCleanups() error {
	...
	//這裡有點複雜我們先當作 desiredPods 就是那些已經不存在的 pod 就好
	//Stop the workers for no-longer existing pods.
	kl.probeManager.CleanupPods(desiredPods)
	...    
	return nil
    }

```



```
func (m *manager) CleanupPods(desiredPods map[types.UID]sets.Empty) {
	//確保 map 一致性上鎖
	m.workerLock.RLock()
	defer m.workerLock.RUnlock()
	//遞迴 manager 所有 worker 找到 預期要消失的 pod uid ，透過 worker stop 結束 probe 作業。
	for key, worker := range m.workers {
		if _, ok := desiredPods[key.podUID]; !ok {
			worker.stop()
		}
	}
}

```



### support

```
//透過 getworker 我們可以傳入 pod uid 、 container name 以及 probeType 獲得 probe worker
func (m *manager) getWorker(podUID types.UID, containerName string, probeType probeType) (*worker, bool) {
	//確保 map 一致性上鎖
	m.workerLock.RLock()
	defer m.workerLock.RUnlock()

	//透過 pod uid 以及 container name 還有 probetype 組合成 probeKey 
	//再從 map 找到對應的 worker
	worker, ok := m.workers[probeKey{podUID, containerName, probeType}]
	return worker, ok
}

// 當不再需要某一個 probe worker 時候我們可以呼叫 removeWorker 傳入 pod uid 、 container name 以及 probeType 刪除 probe worker。
func (m *manager) removeWorker(podUID types.UID, containerName string, probeType probeType) {
	//確保 map 一致性上鎖
	m.workerLock.Lock()
	defer m.workerLock.Unlock()

	//透過 pod uid 以及 container name 還有 probetype 組合成 probeKey 
	//再從 map 刪除對應的 worker
	delete(m.workers, probeKey{podUID, containerName, probeType})
}

// workerCount 返回 probe worker 的總數，測試用。
func (m *manager) workerCount() int {
	//確保 map 一致性上鎖
	m.workerLock.RLock()
	defer m.workerLock.RUnlock()
    
	//看看目前現在有多少的 worker
	return len(m.workers)
}

```



### 整理一下

还记得之前为了找到在 `proberesults.Manager` 定义的却没被用到的这三个function 吗？我们现在来看看都在哪里用上了吧！

```
	// 透過 container id 從 實作者身上得到 result 結果
	Get(kubecontainer.ContainerID) (Result, bool)
	// 透過 container id 設定 pod 探測的結果。實作者需要把結果儲存起來。
	Set(kubecontainer.ContainerID, Result, *v1.Pod)
	// 透過 container id 移除時實作者身上的對應的資料
	Remove(kubecontainer.ContainerID)

```



#### Get

透过container id 从实作者身上得到result 结果
[UpdatePodStatus](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-probe-obj2/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc##UpdatePodStatus)

```
func (m *manager) UpdatePodStatus(podUID types.UID, podStatus *v1.PodStatus) {
	//透過傳入的 pod status 遞迴所有的 ContainerStatuses
	for i, c := range podStatus.ContainerStatuses {
		...
		} else if result, ok := m.startupManager.Get(kubecontainer.ParseContainerID(c.ContainerID)); ok {
        
			// 如果 startup 探測成功的話就設定 started 為 true   
			started = result == results.Success
		} 

```



其他的好像都还没用上，没关系等等我们还会看到！

接着来谈谈prober.Manager 的实作者manager 一直有用到的probe worker 到底是什么勒～

### worker

这里的worker 做的事情就是定期地去执行probe 的工作，一个worker 只会只能一种工作startup 、 readliness 或是liveness。

#### struct

```
type worker struct {
	// 停止 worker 的 channel
	stopCh chan struct{}

	// 手動觸發 probe 的 channel
	manualTriggerCh chan struct{}

	// pod spec 
	pod *v1.Pod

	// container spec
	container v1.Container

	// container probe spec
	spec *v1.Probe

	// worker 目前執行什麼種類的 probe（liveness, readiness or startup）
	probeType probeType

	// 一開始 worker 處於什麼狀態（Unknown、Success、Failure）
	initialValue results.Result

	// 用來儲存 worker probe 後的結果
	resultsManager results.Manager
    
	//worker 會用到上層 manager 一些方法    
	probeManager   *manager

	// worker 處理的 container id 
	containerID kubecontainer.ContainerID
	// worker 的最後一次探測結果。
	lastResult results.Result
	// worker probe 連續返回多少次相同的結果。
	resultRun int

	// 如果有設定，worker在 probe 探測時會略過本次探測。
	onHold bool

	// promethus metric 紀錄 probe metric 用
	proberResultsSuccessfulMetricLabels metrics.Labels
	proberResultsFailedMetricLabels     metrics.Labels
	proberResultsUnknownMetricLabels    metrics.Labels
}

```



#### new function

```
// 建立 work 去檢測 container 狀態
func newWorker(
	m *manager,
	probeType probeType,
	pod *v1.Pod,
	container v1.Container) *worker {

	//初始化 worker 
	w := &worker{
		//stop channel     呼叫的時候不會有 block （其實還是會拉xD），用來停止worker用
		stopCh:          make(chan struct{}, 1), 
		//manualTriggerCh  呼叫的時候不會有 block （其實還是會拉xD），用來觸發 worker probe 用
		manualTriggerCh: make(chan struct{}, 1), 
		//傳入 pod spec        
		pod:             pod,
		//要 prob 的 container 
		container:       container,
		//要 prob 的型態，有可能是liveness, readiness or startup
		probeType:       probeType,
		//傳入 manager 因為 worker 會操作 manger 物件
		probeManager:    m,
	}
    
	//判定 probeType
	switch probeType {
	//如果是 readiness
	//設定 worker spec 為 container 的 readiness 區塊
	//設定 worker 的 resultsManager 為外部 manger 的 readinessManager ，用以做 readiness probe
	//設定初始狀態為 Failure ，因為 readiness 為 faill 的話就不會把 pod 加到服務（ service ）上。
	case readiness:
		w.spec = container.ReadinessProbe
		w.resultsManager = m.readinessManager
		w.initialValue = results.Failure
	//如果是 liveness
	//設定 worker spec 為 container 的 LivenessProbe 區塊
	//設定 worker 的 resultsManager 為外部 manger 的 livenessManager ，用以做 livenessManager probe
	//設定初始狀態為Success ，因為 liveness 為 Success 的話一開始就不會把 pod 刪掉/重啟。
	case liveness:
		w.spec = container.LivenessProbe
		w.resultsManager = m.livenessManager
		w.initialValue = results.Success
	//如果是 startup
	//設定 worker spec 為 container 的 startup 區塊
	//設定 worker 的 resultsManager 為外部 manger 的 startupManager ，用以做 startup probe
	//設定初始狀態為Unknown 
	case startup:
		w.spec = container.StartupProbe
		w.resultsManager = m.startupManager
		w.initialValue = results.Unknown
	}

	//設定 promethus metric 結構
	basicMetricLabels := metrics.Labels{
		//worker 在 prob type    
		"probe_type": w.probeType.String(),
		//worker prob 的 container 對象
		"container":  w.container.Name,
		//worker prob 的 pod 對象
		"pod":        w.pod.Name,
		//worker prob 的 namespace 
		"namespace":  w.pod.Namespace,
		//worker prob 的 pod id 對象
		"pod_uid":    string(w.pod.UID),
	}

    //透過 deepCopyPrometheusLabels 把 basicMetricLabels 複製一份並且	
    //設定 worker proberResultsSuccessfulMetricLabels 
    //proberResultsSuccessfulMetricLabels 這邊 metric label 設定成 probeResultSuccessful
	w.proberResultsSuccessfulMetricLabels = deepCopyPrometheusLabels(basicMetricLabels)
	w.proberResultsSuccessfulMetricLabels["result"] = probeResultSuccessful

    //透過 deepCopyPrometheusLabels 把 basicMetricLabels 複製一份並且	
    //設定 worker proberResultsFailedMetricLabels 
    //proberResultsFailedMetricLabels 這邊 metric label 設定成 probeResultSuccessful
	w.proberResultsFailedMetricLabels = deepCopyPrometheusLabels(basicMetricLabels)
	w.proberResultsFailedMetricLabels["result"] = probeResultFailed

    //透過 deepCopyPrometheusLabels 把 basicMetricLabels 複製一份並且	
    //設定 worker proberResultsUnknownMetricLabels 
    //proberResultsUnknownMetricLabels 這邊 metric label 設定成 probeResultSuccessful
	w.proberResultsUnknownMetricLabels = deepCopyPrometheusLabels(basicMetricLabels)
	w.proberResultsUnknownMetricLabels["result"] = probeResultUnknown

	//回傳worker
	return w
}

```



#### run

worker 开始定时执行probe 作业

```
// run periodically probes the container.
func (w *worker) run() {
	//透過 pod spec 設定 probe 的間隔時間 
	probeTickerPeriod := time.Duration(w.spec.PeriodSeconds) * time.Second

	//我猜的...不確定為什麼會這樣設計，如果有知道的大大希望能不吝嗇告知
	//依照現在的時間點減去 kubelet 的啟動時間，得到 kubelet 存活的時間。
	//如果 PeriodSeconds 大於 kubelet 存活的時間的話，讓worker 睡一下等等在 probe 。
	//我猜可能是因為kubelet 還沒完全準備好（吧？）
	if probeTickerPeriod > time.Since(w.probeManager.start) {
		time.Sleep(time.Duration(rand.Float64() * float64(probeTickerPeriod)))
	}

	//設定 ticker 多久要觸發一次
	probeTicker := time.NewTicker(probeTickerPeriod)

	//clean up function 
	defer func() {
		// 關閉ticker
		probeTicker.Stop()
		//如果 container id 還在的話就移除 resultsManager 跟這個 container 有關的資料
		if !w.containerID.IsEmpty() {
			w.resultsManager.Remove(w.containerID)
		}
		
		//移除map中紀錄的 worker 資訊
		w.probeManager.removeWorker(w.pod.UID, w.container.Name, w.probeType)
		//metric 不在記錄這個 worker 所發生的 metric 
		ProberResults.Delete(w.proberResultsSuccessfulMetricLabels)
		ProberResults.Delete(w.proberResultsFailedMetricLabels)
		ProberResults.Delete(w.proberResultsUnknownMetricLabels)
	}()

probeLoop:
	//每次 prob 完成後如果 probe 結果為 true ，需要等待 probeTicker ，或是 manualTriggerCh 再進行下一次的 probe trigger
	//如果是收到 stopCh 或是 probe 結果為 flase 則關閉 worker 。
	for w.doProbe() {
		// Wait for next probe tick.
		select {
		case <-w.stopCh:
			break probeLoop
		case <-probeTicker.C:
		case <-w.manualTriggerCh:
			// continue
		}
	}
}

//用來關閉 worker，設計成 Non-blocking的，簡單來看就是往 stop channel 送 stop 訊號。
func (w *worker) stop() {
	select {
	case w.stopCh <- struct{}{}:
	default: // Non-blocking.
	}
}

// probe container 並且回傳 probe 結果。如果 probe 過程中有錯會回傳 false 呼叫者需要關閉 worker。
func (w *worker) doProbe() (keepGoing bool) {
	//無腦回復狀態 panic 。
	defer func() { recover() }() 
	// runtime.HandleCrash 紀錄一下而已。
	defer runtime.HandleCrash(func(_ interface{}) { keepGoing = true })

	//透過 statusManager 跟著 UID 拿到 pod status（statusManager 在這裡不太重要，知道可以拿到 pod status 就好了）
	status, ok := w.probeManager.statusManager.GetPodStatus(w.pod.UID)
	if !ok {
		// Pod 尚未創建，或者已被刪除。
		klog.V(3).InfoS("No status for pod", "pod", klog.KObj(w.pod))
		return true
	}

	// 如果 pod 處於 PodFailed 跟 PodSucceeded 狀態，這個 worker 就可以關閉了
	if status.Phase == v1.PodFailed || status.Phase == v1.PodSucceeded {
		klog.V(3).InfoS("Pod is terminated, exiting probe worker",
			"pod", klog.KObj(w.pod), "phase", status.Phase)
		return false
	}

	//遞迴 container status ，判斷所有的 container status 對應到輸入的 ccontainer name 回傳 status 狀態
	//如果找不到對應的 container status 就等待下一輪
	c, ok := podutil.GetContainerStatus(status.ContainerStatuses, w.container.Name)
	if !ok || len(c.ContainerID) == 0 {
		// 容器尚未創建，或者已被刪除。
		klog.V(3).InfoS("Probe target container not found",
			"pod", klog.KObj(w.pod), "containerName", w.container.Name)
		return true // Wait for more information.
	}

	//判斷 worker 負責的 container id 是不是跟 status 的 container 可以對上，如果對不上這種狀況可能發生在 container 被刪除或是 container 改變了。
	if w.containerID.String() != c.ContainerID {
		//如果 container id 不是空的    
		if !w.containerID.IsEmpty() {
			//從 resultsManager 刪除關於 worker container 的結果        
			w.resultsManager.Remove(w.containerID)
		}
		//設定新的 container id         
		w.containerID = kubecontainer.ParseContainerID(c.ContainerID)
        //設定 resultsManager 要接收新的 worker container id ，並且給他初始化的 value 與 pod  spec （這裡要注意根據 probe 形式不同他們初始化得數值也不一樣 例如readiness :faill  liveness :Success  startup :Unknown）
        w.resultsManager.Set(w.containerID, w.initialValue, w.pod)
		// 因為有新的 container 我們繼續 prob 流程
		w.onHold = false
	}

	//判斷是否要繼續 probe 流程
	if w.onHold {
		// Worker is on hold until there is a new container.
		return true
	}

	//判斷 container 的狀態是否正在Running，如果不是正在 Running 有可能在做waiting 、有可能在 Terminated 。
	if c.State.Running == nil {
		klog.V(3).InfoS("Non-running container probed",
			"pod", klog.KObj(w.pod), "containerName", w.container.Name)
		//如果 container id 不是空的   
		if !w.containerID.IsEmpty() {
			//從 resultsManager 刪除關於 worker container 的結果        
			w.resultsManager.Set(w.containerID, results.Failure, w.pod)
		}
		// 如果 RestartPolicy 為 不重新啟動，則中止 worker。
		return c.State.Terminated == nil ||
			w.pod.Spec.RestartPolicy != v1.RestartPolicyNever
	}


	// 這邊我們要先了解一點！！！非長重要如果不了解建議先看之前我這篇文章[學習Kubernetes Garbage Collection機制](https://blog.jjmengze.website/posts/kubernetes/kubernetes-garbage-collection/)
	// 簡單來說，pod 被刪掉有可能先出現 DeletionTimestamp 的狀態
	// 在這個狀態之下 pod 會在 BackGround 狀態被回收
	
    
	// 可以把它想像成處於 Deletion 狀態的 pod ，且有設定 probe liveness 或是 startup，透過 resultsManager 設定成 probe 成功，不然會把 container 刪掉(重啟)。
	// 最後停止 worker ，因為 pod 已經要被刪掉了 worker 就沒用處囉。
	if w.pod.ObjectMeta.DeletionTimestamp != nil && (w.probeType == liveness || w.probeType == startup) {
		klog.V(3).InfoS("Pod deletion requested, setting probe result to success",
			"probeType", w.probeType, "pod", klog.KObj(w.pod), "containerName", w.container.Name)
		if w.probeType == startup {
			klog.InfoS("Pod deletion requested before container has fully started",
				"pod", klog.KObj(w.pod), "containerName", w.container.Name)
		}
		// Set a last result to ensure quiet shutdown.
		w.resultsManager.Set(w.containerID, results.Success, w.pod)
		// Stop probing at this point.
		return false
	}
    
    

	// 判斷 probe 的初始 Delay 時間是否到了，如果還沒到就需要等下一次觸發
	if int32(time.Since(c.State.Running.StartedAt.Time).Seconds()) < w.spec.InitialDelaySeconds {
		return true
	}

	//判斷 (過去) startup probe 是否已經成功，如果已經成功就可以關閉 startup worker，其他種類的 woker 保留。
	//如果是還沒 startup probe 失敗 其他 probe 都不用談直接退回去重新等待下一次觸發。
	if c.Started != nil && *c.Started {
		// Stop probing for startup once container has started.
		if w.probeType == startup {
			return false
		}
	} else {
		// Disable other probes until container has started.
		if w.probeType != startup {
			return true
		}
	}

	
	//實際執行各種 probe 的地方，如果 probe 有 error 直接停止 worker 
	//這裡依賴之前注入的 probeManager 的 probe 實作，藉由我們丟入的 probe 型態決定要怎麼執行 probe 。
	result, err := w.probeManager.prober.probe(w.probeType, w.pod, status, w.container, w.containerID)
	if err != nil {
		// Prober error, throw away the result.
		return true
	}

	//如果 probe 沒有 error 透過 ProberResults.With 去觸發 metric 以提供後續監控服務
	switch result {
	case results.Success:
		ProberResults.With(w.proberResultsSuccessfulMetricLabels).Inc()
	case results.Failure:
		ProberResults.With(w.proberResultsFailedMetricLabels).Inc()
	default:
		ProberResults.With(w.proberResultsUnknownMetricLabels).Inc()
	}

	//用來判斷同一個 probe 結果，並且計算執行 probe 次數用
	if w.lastResult == result {
		w.resultRun++
	} else {
		w.lastResult = result
		w.resultRun = 1
	}

	//如果 probe 錯誤或是成功 低於閥值就直接安排下一次的 probe
	if (result == results.Failure && w.resultRun < int(w.spec.FailureThreshold)) ||
		(result == results.Success && w.resultRun < int(w.spec.SuccessThreshold)) {
		// Success or failure is below threshold - leave the probe state unchanged.
		return true
	}

	//透過 resultsManager 設定哪個 container 的 prob 結果是什麼，給外面的人做事（重啟、刪除之類的）
	w.resultsManager.Set(w.containerID, result, w.pod)

	//如果 worker 型態為 （ liveness 或是 startup ）並且本次 prob 結果為失敗。 container 會重啟所以需要把 resultRun 重置
	//並且設定 onHold=true ，因為 container 重啟了。前面要重新獲取 container id 不需要。
	if (w.probeType == liveness || w.probeType == startup) && result == results.Failure {
		w.onHold = true
		w.resultRun = 0
	}

	//worker繼續 執行
	return true
}

```



#### 整理一下

还记得之前为了找到在 `proberesults.Manager` 定义的却没被用到的这三个function 吗？我们现在来看看是不是都用上了！

```
	// 透過 container id 從 實作者身上得到 result 結果
	Get(kubecontainer.ContainerID) (Result, bool)
	// 透過 container id 設定 pod 探測的結果。實作者需要把結果儲存起來。
	Set(kubecontainer.ContainerID, Result, *v1.Pod)
	// 透過 container id 移除時實作者身上的對應的資料
	Remove(kubecontainer.ContainerID)

```



##### Set

[Worker - run](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-probe-obj2/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc#####run)
透过container id 设定pod 探测的结果。实作者需要把结果储存起来。

```
func (w *worker) doProbe() (keepGoing bool) {
	...
    
	if w.containerID.String() != c.ContainerID {
		if !w.containerID.IsEmpty() {
			w.resultsManager.Remove(w.containerID)
		}
		w.containerID = kubecontainer.ParseContainerID(c.ContainerID)
		w.resultsManager.Set(w.containerID, w.initialValue, w.pod)
		// We've got a new container; resume probing.
		w.onHold = false
	}
    
	result, err := w.probeManager.prober.probe(w.probeType, w.pod, status, w.container, w.containerID)
	...
    
	w.resultsManager.Set(w.containerID, result, w.pod)
    
	...

```



##### Remove

[Worker - run](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-probe-obj2/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc#####run)
透过container id 移除时实作者身上的对应的资料

```
func (w *worker) run() {
	...
    
	probeTicker := time.NewTicker(probeTickerPeriod)

	defer func() {
		// Clean up.
		probeTicker.Stop()
		if !w.containerID.IsEmpty() {
			w.resultsManager.Remove(w.containerID)
		}

		w.probeManager.removeWorker(w.pod.UID, w.container.Name, w.probeType)
		...
	}()
	...
}   
func (w *worker) doProbe() (keepGoing bool) {
	...
   
	if w.containerID.String() != c.ContainerID {
		if !w.containerID.IsEmpty() {
			w.resultsManager.Remove(w.containerID)
		}
		w.containerID = kubecontainer.ParseContainerID(c.ContainerID)
		w.resultsManager.Set(w.containerID, w.initialValue, w.pod)
		// We've got a new container; resume probing.
		w.onHold = false
	}
    
	...

```



## 小结

总结一下kubernetes kubelet 如何做probe 的这几篇文章，我先从最常使用到的tcp socket、 exec 以及http get 三种不同probe 的基本操作相关文章分别是[Kubernetes kubelet 探测pod 的生命症状Http Get](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-http-get/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)，[Kubernetes kubelet 探测pod 的生命症状tcp socket](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-tcp-socket/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)以及[Kubernetes kubelet 探测pod 的生命症状Exec](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-exec/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)，可以从这三篇文章了解kubernetes probe 底层是如何实作这三种probe 的，由于文章中没有探讨probe 的物件是如何生成的。

因此在接下来的[Kubernetes kubelet 探测pod 的生命症状探针得如何出生-1](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-probe-obj/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)的文章中，探讨了probe 到底怎么诞生的。首先我们先观察proberesults.Manager 经过分析之后得知原来是用来储存container liveness 、 readiness 以及startup 的probe 结果。

在kubelet syncLoopIteration 的生命中周期中尝试获取proberesults.Manager 的结果，再依照结果执行不同的动作。

------

当我们分析完proberesults.Manager 发现有许多方法没被呼叫，因此我们顺藤摸瓜找到prober.Manager 主要都本篇文章分析的对象。经过本篇文章包丁解牛后发现原来prober.Manager 是用来管理container 是否要加入probe 以及什么时候要移除probe 。

其中我们发现了prober.Manager 的实作对象透过worker 的方式，将container 身上的probe 任务分配到`不同的`worker 上执行，使得管理与实作的职责分离。

------

以上为kubernetes kubelet 如何做probe 的简易分析，文章中若有出现错误的见解希望各位在观看文章的大大们可以指出哪里有问题，让我学习改进，谢谢。
