还记得前一章讨论Controller 的时候，我们保留了一个Reflector ， Reflector 将会在本篇中会揭开它神秘的面纱，就让我来解剖这个元件吧！

## Reflector

上一章节可以看到Reflector 的初始化是在Controller 的Run Function ，我们来复习一次Run Function 。

### Controller——Run

Run function 是Controller 最主要的function

```
// Run begins processing items, and will continue until a value is sent down stopCh or it is closed.
// It's an error to call Run more than once.
// Run blocks; call via go.
func (c *controller) Run(stopCh <-chan struct{}) {
    //錯誤處理，有機會再來談，先不理他
	defer utilruntime.HandleCrash()
    
    //當收到stop 訊號關閉 delta fifo queue
	go func() {
		<-stopCh
            //關閉 delta fifo queue 的細節我們前幾章節在討論 delta fifo queue 已經談過，不了解的小夥伴可以回去複習
		c.config.Queue.Close()
	}()
    
    //建立一個 Reflector ，今天的重點會著重於 Reflector內部的實作
	r := NewReflector(
		c.config.ListerWatcher,            //傳入資源監控器
		c.config.ObjectType,               //傳入欲監視的物件型態
		c.config.Queue,                    //傳入delta fifo  queue
		c.config.FullResyncPeriod,         //設定多久要sync一次
	)
    
    //Reflector 設定 resync 時間
	r.ShouldResync = c.config.ShouldResync
    
    //Reflector 設定list watch 的 chunk size.
	r.WatchListPageSize = c.config.WatchListPageSize
    
    //Reflector 設定時間
	r.clock = c.clock
    
    //Reflector 套用錯誤處理
	if c.config.WatchErrorHandler != nil {
		r.watchErrorHandler = c.config.WatchErrorHandler
	}

    // 不知道為什麼 controller 綁定 reflector 的時候要加鎖
	c.reflectorMutex.Lock()
    // controller 綁定 Reflector
	c.reflector = r
	c.reflectorMutex.Unlock()

    //kubernetes wait.Group 預計未來還會拉出來再講一篇
    //簡單來就是被這個 wg 管理的 thread 全部都 done 了之後才會退出 wait 
	var wg wait.Group

    //這個 function 會啟動一個 thread 並且在裡面呼叫 剛剛建立的 reflector.run 並且傳入 stop channel 
    //stop channel用來終止 thread 
	wg.StartWithChannel(stopCh, r.Run)
    
    //規律性的呼叫 processLoop()，若是收到 stop channel 的訊號就退出
    //processLoop()在上一篇有討論過，不了解的朋友可以回到上一章節看
	wait.Until(c.processLoop, time.Second, stopCh)
    
    //等待所有 wait.Group 的 thread done 才能離開，不然會一直卡在這裡～
	wg.Wait()
}

```



Anyway ,总之Controller 在执行Run Function 的时候初始化了Reflector ，这时候我们要去看看NewReflector function 会不会偷藏了其他初始化， ＧＯ ！

### New function

刚刚我们看到Controller 透过Run function ，并且执行 `NewReflector()` 去建立一个Reflector ，可以从下面的source code 看到实际上会透过NewNamedReflector 去建立Reflector，我们就把重点把在 `NewNamedReflector` 吧！

```
func NewReflector(lw ListerWatcher, expectedType interface{}, store Store, resyncPeriod time.Duration) *Reflector {
	return NewNamedReflector(
    naming.GetNameFromCallsite(internalPackages...),        //Reflector log 要印出的名稱
    lw,                                                    //資源監控器
    expectedType,                                          //預期資源監控器要拿到物件
    store,                                                 //delta fifo queue
    resyncPeriod)                                          //多久要 sync 一次
}

```



```
// NewNamedReflector same as NewReflector, but with a specified name for logging

//傳入 log 要印出的名稱
//傳入監控物件變化的觀察者
//傳入要觀察的物件資料型態
//傳入本地儲存器(local storage)
//傳入多久要同步一次

func NewNamedReflector(name string, lw ListerWatcher, expectedType interface{}, store Store, resyncPeriod time.Duration) *Reflector {
	realClock := &clock.RealClock{}
	r := &Reflector{
		name:          name,                //log 要印出的名稱
		listerWatcher: lw,                  //監控物件變化的觀察者
		store:         store,               //要觀察的物件資料型態
        
            //BackoffManager 主要設計減少 upstream 不健康期間的負載。
            //這裡實作有點複雜我們只要先知道，透過 BackoffManager 管理 listwatch 多久要觸發一次
		backoffManager:         wait.NewExponentialBackoffManager(800*time.Millisecond, 30*time.Second, 2*time.Minute, 2.0, 1.0, realClock),
        
            //當 listerwatcher list發生問題時
            //透過 initConnBackoffManager 管理多久後再重新 list 一次
		initConnBackoffManager: wait.NewExponentialBackoffManager(800*time.Millisecond, 30*time.Second, 2*time.Minute, 2.0, 1.0, realClock),
        
		resyncPeriod:           resyncPeriod,    //多久要 sync 一次
		clock:                  realClock,       //給測試用的時鐘
        
            //當lister watcher watcher 階段發生錯誤的時候錯誤處理
		watchErrorHandler:      WatchErrorHandler(DefaultWatchErrorHandler),
	}
    //設定 refleactor 預期要觀察的物件
	r.setExpectedType(expectedType)
	return r
}

//本篇不討論反射機制這裡我們只了解透過反射我們設定了 Refector 預期要看的資源
//設定 Reflector 想要看的資源（e.g. pod configmap）
func (r *Reflector) setExpectedType(expectedType interface{}) {
        //透過反射先設定 （e.g. pod configmap）
	r.expectedType = reflect.TypeOf(expectedType)
    
        //如果有問題就設定為預設值
	if r.expectedType == nil {
		r.expectedTypeName = defaultExpectedTypeName
		return
	}
    
        //不太了解為什麼要這樣設計，特地要把 expectedType 轉 string 存在 r.expectedTypeName
        //直接拿r.expectedType.string()不好用嗎？
	r.expectedTypeName = r.expectedType.String()

        
        
        //檢查expected type 的GVK (group version kind 未來我還會再開一篇討論 GVK )
	if obj, ok := expectedType.(*unstructured.Unstructured); ok {
		// Use gvk to check that watch event objects are of the desired type.
		gvk := obj.GroupVersionKind()
		if gvk.Empty() {
			klog.V(4).Infof("Reflector from %s configured with expectedType of *unstructured.Unstructured with empty GroupVersionKind.", r.name)
			return
		}
                //設定reflator 想要看到GVK
		r.expectedGVK = &gvk
                //不太了解為什麼要這樣設計，特地要把 GVK 轉 string 存在 r.expectedTypeName，直接拿r.expectedGVK.string()不好用嗎？
		r.expectedTypeName = gvk.String()
	}
}

```



看完了如何建立一个Reflector 后我们来看看Reflector 的资料结构长怎么样

### Struct

```

//Reflector 預期解析的物件沒有設定的話就使用這個名字
const defaultExpectedTypeName = "<unspecified>"

// Reflector watches a specified resource and causes all changes to be reflected in the given store.
type Reflector struct {
	
	name string                                //定義 Reflector 的名稱

	
	expectedTypeName string                    //一般來說會設定成GVK.String（）的名稱
	
    
	expectedType reflect.Type                //Reflector 要解析的物件
                                                //當 expectedType 跟 watcher 監控的物件一樣時才會放到 delta fifo queue
	
    
	expectedGVK *schema.GroupVersionKind    //Reflector 要解析的物件的GVK
                                                //當 expectedType 跟 watcher 監控的物件一樣時才會放到 delta fifo queue
                                                
                                                

	store Store                            // 存放物件變化的 delta FIFO Queue
	
	listerWatcher ListerWatcher            //用以監控與列出指定的資源


	
	backoffManager wait.BackoffManager        //透過BackoffManager 管理 listwatch 多久要觸發一次
	
    
	initConnBackoffManager wait.BackoffManager    //當 listerwatcher list發生問題時
                                                        //透過 initConnBackoffManager 管理多久後再重新 list 一次

       resyncPeriod time.Duration                        //設定多久 delta fifo queue 要 reqync 一次的時間
	
    
	ShouldResync func() bool                           //定期會呼叫ShouldResync確認 delta fifo queue 是否同步了
	
    
	clock clock.Clock                                  //給測試用的
	
    
	paginatedResult bool                              //如果 listerwatcher list 的結果是有做分頁的話，該數值標記為true。
	
	
	lastSyncResourceVersion string                    //lister watcher觀測到到物件版本會記錄在這
	
    
	isLastSyncResourceVersionUnavailable bool        //當有"expired" 或是 "too large resource version"出現的時候
                                                        //會在標記在這裡
	
    
	lastSyncResourceVersionMutex sync.RWMutex        //對版本的讀寫鎖
	
    
	WatchListPageSize int64                            //在 lister watcher list 資源時用來做分頁大小切割的參數
	
    
	watchErrorHandler WatchErrorHandler                //當 lister watcher 斷開連結時會透過這個 function 處理
}

```



看完了资料结构我们就可以进入到一下个阶段Reflector 到底做了什么，就让我们继续把Reflector 摊开来看！

### impliment

#### Run

还记得谁呼叫了Reflector 的Run Function 吗？

> 是在Controller 的Run Function 喔，复习下controller 的run function 做了什么事情吧（controller 的实作上一张有介绍过，不了解的地方可以回头复习看看）

```
func (c *controller) Run(stopCh <-chan struct{}) {
	...
	r := NewReflector(
		c.config.ListerWatcher,
		c.config.ObjectType,
		c.config.Queue,
		c.config.FullResyncPeriod,
	)
	...

	var wg wait.Group

	wg.StartWithChannel(stopCh, r.Run)

	wait.Until(c.processLoop, time.Second, stopCh)
	wg.Wait()
}

```



Reflector 被建立起后从这个Run Function 开始他的一生，我们来看看Reflector Run Fucntion 到底做了什么吧！

```
// Run repeatedly uses the reflector's ListAndWatch to fetch all the
// objects and subsequent deltas.
// Run will exit when stopCh is closed.
func (r *Reflector) Run(stopCh <-chan struct{}) {
    //這裡先不管 wait.BackoffUntil 底層是如何實作的，未來再開戰場來講這個
    //我們只要知道 wait.BackoffUntil 會週期性(每隔backoffManager)的呼叫 傳入的 function 
    //如果接收到 stopCh 的訊號就退出
	wait.BackoffUntil(
        func() {
		if err := r.ListAndWatch(stopCh); err != nil {
			r.watchErrorHandler(r, err)
		}
	}, r.backoffManager, true, stopCh)

}

```



在Reflector Run Function 会定期的执行ListAndWatch ，当收到Stop Channel 发过来的讯号的时候才会结束我们接着看ListAndWatch 做了什么。

#### ListAndWatch

list and watch 会先列出资源所有的item 然后以最大的resource version 开始进行watch 的动作。

```
// ListAndWatch first lists all items and get the resource version at the moment of call,
// and then use the resource version to watch.
// It returns error if ListAndWatch didn't even try to initialize watch.
func (r *Reflector) ListAndWatch(stopCh <-chan struct{}) error {
	
	var resourceVersion string
    //簡單來說就是一開始把 lsit 的過濾條件 resource version 設定成 0
	options := metav1.ListOptions{ResourceVersion: r.relistResourceVersion()}

	if err := func() error {
	    // 承裝 lister watcher 列出的物件
		var list runtime.Object
            // 確定 list 結果是否有分頁
		var paginatedResult bool
		var err error
        
            //監聽 list 事件是否完成
		listCh := make(chan struct{}, 1)
            //監聽 list 事件是否有 error
		panicCh := make(chan interface{}, 1)
        
            //啟動 thread 執行 list 動作
		go func() {
                    //捕捉錯誤 ，發給監聽錯誤的 channel 
			defer func() {
				if r := recover(); r != nil {
					panicCh <- r
				}
			}()
			
            
		    //主要是建立一個 ListWatcher 的分頁處理器
			pager := pager.New(pager.SimplePageFunc(func(opts metav1.ListOptions) (runtime.Object, error) {
				return r.listerWatcher.List(opts)
			}))
            
            
                    //設定pager相關的參數
			switch {
                        //設定chunk size
			case r.WatchListPageSize != 0:
				pager.PageSize = r.WatchListPageSize
                
                    //若是 list 的結果有分頁的話
			case r.paginatedResult:
	
				
                    // 當有同時設定ResourceVersion且ResourceVersion!=0的時候 
			case options.ResourceVersion != "" && options.ResourceVersion != "0":
				
                            //不啟用分頁
				pager.PageSize = 0
			}

                    //透過 pager.List 檢索 (list) 出指定的資源，並透過 options 過濾<過程很複雜...有機會再來看>
                    //我們會得到 list 結果型態是 runtime.Object
                    //並且拿到回傳的資料是否有做分頁以及相關錯誤
			list, paginatedResult, err = pager.List(context.Background(), options)
            
                    //處理一些已知的錯誤 例如 StatusReasonExpired , StatusReasonGone 等等，緣由可以看一下原 source code 的註解（歷史因素）
			if isExpiredError(err) || isTooLargeResourceVersionError(err) {
                            //標記有出現過 StatusReasonExpired , StatusReasonGone 等等的錯誤
				r.setIsLastSyncResourceVersionUnavailable(true)
				
                            //簡單來說就是退回第零版再重新list一次
				list, paginatedResult, err = pager.List(context.Background(), metav1.ListOptions{ResourceVersion: r.relistResourceVersion()})
			}
            
                    //表示資源檢索(list)完成，透過 channel 發送訊號
			close(listCh)
		}()
        
        
            //阻塞操作，等 list 完成或是觸發 panic error ，或者接收到 stop channel 的訊號終止
		select {
		case <-stopCh:
			return nil
		case r := <-panicCh:
			panic(r)
		case <-listCh:
		}
		if err != nil {
			return fmt.Errorf("failed to list %v: %v", r.expectedTypeName, err)
		}

        
                //如果 resource 為 0 並且 list 結果的 paginatedResult 也表示資料有分頁
                //就要標記 Reflector 的結果是有分頁的
                
		if options.ResourceVersion == "0" && paginatedResult {
			r.paginatedResult = true
		}


            //標記 list 成功
		r.setIsLastSyncResourceVersionUnavailable(false) // list was successful

            //把 list 出的結果轉換成實作 List 的物件（這邊很繞牽扯到apimachinery），先了解意思就好
		listMetaInterface, err := meta.ListAccessor(list)
		if err != nil {
			return fmt.Errorf("unable to understand list result %#v: %v", list, err)
		}
        
            //取得 list 資料內 metadata  的 resourceVersion ，得知當前版本
		resourceVersion = listMetaInterface.GetResourceVersion()
        
            //把檢索出來的物件取出 items 的欄位會得到[]runtime.Object，例如裡面就是存 [podA{},podB{},e.t.c]
		items, err := meta.ExtractList(list)
		if err != nil {
			return fmt.Errorf("unable to understand list result %#v (%v)", list, err)
		}
		
            //同步到DeltaFIFO內，下面會看到如何同步的不急
		if err := r.syncWith(items, resourceVersion); err != nil {
			return fmt.Errorf("unable to sync list result: %v", err)
		}
		
            //設定從etcd同步過來的最新的版本
		r.setLastSyncResourceVersion(resourceVersion)
		    
		return nil
	}(); err != nil {
		return err
	}
    
    //////////////////以上為 lister watcher lister 的過程，也就是說 reflector 會先完成 list 的工作！


    //處理 delta fifo queue 同步錯誤用的channel，非阻塞
	resyncerrc := make(chan error, 1)
    //watcher 處理結束用的channel
	cancelCh := make(chan struct{})
	defer close(cancelCh)
    
	go func() {
        //建立同步用的channel，時間到會從 channel 發出訊號
		resyncCh, cleanup := r.resyncChan()
        
        
		defer func() {
			cleanup() //關閉同步用的channel
		}()
        
		for {
                    //等待同步訊號，stop channel 或是 cancel channel 都是用來監聽關閉的訊號
                    // resyncCh 則是會被定時觸發
			select {
			case <-resyncCh:
			case <-stopCh:
				return
			case <-cancelCh:
				return
			}
                    // ShouldResync 是一個 function
                    //用來用來確定 Delta FIFO  Queue 是否已經同步
			if r.ShouldResync == nil || r.ShouldResync() {
                        //執行 Delta FIFO Queue 的 resync 
                        //不了解的小夥伴可以到之前的章節複習
				if err := r.store.Resync(); err != nil {
                            //若是 Delta FIFO Queue 的 resync  執行 就丟到外面 channel 這裡不處理
					resyncerrc <- err
					return
				}
			}
                    //關閉同步用的channel
			cleanup()
                    //綁定新的同步用的channel
			resyncCh, cleanup = r.resyncChan()
		}
	}()
    
    
    
    
    ////////////////以上這一小段是定時確認 Delta FIFO QUEUE 同步過程是否有問題


    
	for {
		// give the stopCh a chance to stop the loop, even in case of continue statements further down on errors
                //stop channel 收到訊號表示外部要關閉 reflactor  直接退出
		select {
		case <-stopCh:
			return nil
                // 讓 for 迴圈不會卡住
		default:
		}
                //watch timeout 時間 minWatchTimeout 為 300 秒
		timeoutSeconds := int64(minWatchTimeout.Seconds() * (rand.Float64() + 1.0))
        
                //設定 watch 過濾條件
		options = metav1.ListOptions{
			ResourceVersion: resourceVersion,        //watch 某一個版本以上的 resource 
			
			
			TimeoutSeconds: &timeoutSeconds,        //設定watch timeout 時間
			
			
			AllowWatchBookmarks: true,            //用以降低 api server 附載用的...有空再展開來看為什麼可以降低附載
		}

		// start the clock before sending the request, since some proxies won't flush headers until after the first watch event is sent
		start := r.clock.Now()
        
                //透過 watcher 監控指定的資源，並且透過指定過濾條件進行過濾
		w, err := r.listerWatcher.Watch(options)
		if err != nil {
                    //簡單來說當遇到ConnectionRefused時候會透過initConnBackoffManager，來停等一下
                    //停等之後再重新 watch 試試看
			if utilnet.IsConnectionRefused(err) {
				<-r.initConnBackoffManager.Backoff().C()
				continue
			}
			return err
		}
        
                //處理 watcher 監控到的資源，下面會看到實作的方法
		if err := r.watchHandler(start, w, &resourceVersion, resyncerrc, stopCh); err != nil {
        
                    //判斷一下錯誤...但沒有特別處理，
			if err != errorStopRequested {
				switch {
				case isExpiredError(err):			
					klog.V(4).Infof("%s: watch of %v closed with: %v", r.name, r.expectedTypeName, err)
				default:
					klog.Warningf("%s: watch of %v ended with: %v", r.name, r.expectedTypeName, err)
				}
			}
			return nil
		}
	}
}


// 回傳一個定時器的 channel ，以及關閉定時器的方法
func (r *Reflector) resyncChan() (<-chan time.Time, func() bool) {
	if r.resyncPeriod == 0 {
		return neverExitWatch, func() bool { return false }
	}
	
    
	t := r.clock.NewTimer(r.resyncPeriod)
	return t.C(), t.Stop
}


// 透過給定的物件與版本號替換掉 delta fifo queue的資料
func (r *Reflector) syncWith(items []runtime.Object, resourceVersion string) error {
        //複製一份 list 列出來的所有物件 到 found 
	found := make([]interface{}, 0, len(items))
	for _, item := range items {
		found = append(found, item)
	}
        // delta fifo queue 進行替換
	return r.store.Replace(found, resourceVersion)
}


```



- list 的过程可以参考下面的流程图

  ![img](123assets/refleactor-list.png)

  

- watch 的过程可以参考下面的流程图

  ![img](123assets/reflector-watcher.png)

  

Reflector 的Run function 使用到`apimachinery`package 的相关方法，主要是对runtime object 的物件进行处理但`apimachinery`package 是一个相当复杂的东西…使得上述再分析Run Function 的细节时不够清楚与透彻，以及像是 `paginatedResult` 与 `chunkSize` 等不是说明的很清楚未来有机会的应该会再开一篇来讨论一下相关的机制。

> 掩面…..还需要多努力

#### watchHandler

我们在Run function 有看到再处理watch 事件的时候呼叫了watchHandler ，我们需要看一下watchHandler 做了什么事情！

```
// watchHandler watches w and keeps *resourceVersion up to date.
func (r *Reflector) watchHandler(start time.Time, w watch.Interface, resourceVersion *string, errc chan error, stopCh <-chan struct{}) error {
        //事件計數器
	eventCount := 0

	// Stopping the watcher should be idempotent and if we return from this function there's no way
	// we're coming back in with the same watch interface.
    
    //離開時結束對資源的監控
	defer w.Stop()


//標記，當遇到某些情況時，可以跳到這個標記再重新進入 for 迴圈
loop:
	for {
		select {
        
                //收到關閉訊號，就跳出並帶有結束的錯誤訊息
		case <-stopCh:
			return errorStopRequested
            
                //收到其他thread傳來的錯誤訊息，跳出並回報錯誤
		case err := <-errc:
			return err
            
                //收到 watcher 傳來的事件變動通知
		case event, ok := <-w.ResultChan():
                
                    //channel 有分關閉跟非關閉，若是 watcher 關閉的話需要再重新跑一次繼續監聽
			if !ok {
				break loop
			}
            
                    //解析 watcher 事件（ watcher 產生 error）
			if event.Type == watch.Error {
				return apierrors.FromObject(event.Object)
			}
            
            
                    //解析 watcher 事件 ，透過反射確定 watcher 監控的是我們指定的資源
			if r.expectedType != nil {
				if e, a := r.expectedType, reflect.TypeOf(event.Object); e != a {
					utilruntime.HandleError(fmt.Errorf("%s: expected type %v, but watch event object had type %v", r.name, e, a))
					continue
				}
			}
            
                    //解析 watcher 事件 ，確定 watcher 觀察的事件是我們指定的 GVK (Group Version Kind)
			if r.expectedGVK != nil {
				if e, a := *r.expectedGVK, event.Object.GetObjectKind().GroupVersionKind(); e != a {
					utilruntime.HandleError(fmt.Errorf("%s: expected gvk %v, but watch event object had gvk %v", r.name, e, a))
					continue
				}
			}
            
                    //跟上面 ListAndWatch 提到的 meta.Accessor 是一樣的功能
                    //總之就是拿到事件的 metaData
			meta, err := meta.Accessor(event.Object)
			if err != nil {
				utilruntime.HandleError(fmt.Errorf("%s: unable to understand watch event %#v", r.name, event))
				continue
			}
            
                    //從 meta data 中取得對應的資源版本，之後 watch 就是要 watch 比這個版本還要新的資源
			newResourceVersion := meta.GetResourceVersion()
                    
                    //判斷事件的型態
			switch event.Type {
            
                    //當型態為 Add 的時候觸發 delta FIFO Queue 的 add 行為
			case watch.Added:
				err := r.store.Add(event.Object)
				if err != nil {
					utilruntime.HandleError(fmt.Errorf("%s: unable to add watch event object (%#v) to store: %v", r.name, event.Object, err))
				}
                
                        //當型態為 Modified 的時候觸發 delta FIFO Queue 的 Update 行為
			case watch.Modified:
				err := r.store.Update(event.Object)
				if err != nil {
					utilruntime.HandleError(fmt.Errorf("%s: unable to update watch event object (%#v) to store: %v", r.name, event.Object, err))
				}
                
                
                
                        //當型態為 Deleted 的時候觸發 delta FIFO Queue 的 Deleted 行為
			case watch.Deleted:
				
				err := r.store.Delete(event.Object)
				if err != nil {
					utilruntime.HandleError(fmt.Errorf("%s: unable to delete watch event object (%#v) from store: %v", r.name, event.Object, err))
				}
                
                        //不清楚為什麼bookmark 可以降低使用附載...待深入瞭解後補上(ＴＯＤＯ)
			case watch.Bookmark:
				// A `Bookmark` means watch has synced here, just update the resourceVersion
                
                        //當回傳的事件不是以上幾種的話，當然就要報錯
			default:
				utilruntime.HandleError(fmt.Errorf("%s: unable to understand watch event %#v", r.name, event))
			}
            
                        //紀錄當前事件版本號
			*resourceVersion = newResourceVersion
			r.setLastSyncResourceVersion(newResourceVersion)
			if rvu, ok := r.store.(ResourceVersionUpdater); ok {
				rvu.UpdateResourceVersion(newResourceVersion)
			}
                        //計數事件發生次數
			eventCount++
		}
	}

        //當 watch 回應的時間非常短且沒任何事件，表示這是一個異常現象
        //這裡 kubernetes 設計了一個超時機制～
	watchDuration := r.clock.Since(start)
	if watchDuration < 1*time.Second && eventCount == 0 {
		return fmt.Errorf("very short watch: %s: Unexpected watch close - watch lasted less than a second and no items received", r.name)
	}
	klog.V(4).Infof("%s: Watch close - %v total %v items received", r.name, r.expectedTypeName, eventCount)
	return nil
}

func (r *Reflector) setLastSyncResourceVersion(v string) {
        //同步鎖防止競爭
	r.lastSyncResourceVersionMutex.Lock()
	defer r.lastSyncResourceVersionMutex.Unlock()
        //紀錄reflector目前最新資源版本號
	r.lastSyncResourceVersion = v
}

```



### 整理思路

大多数的朋友读到这里可能还是会一头问号到底reflector 跟controller 之间是什么关系，之间又有什么爱恨情仇？

我想还是透过一些图示说明加深印象可能会比较好一点

![img](assets/kubernetes-controller-reflector.png)

## 小结

对Kubernetes Reflector 做一下总结， 在Controller 把Reflector 建立出来后利用Reflector.Run 执行list 、 watch 以及向delta fifo enqueue 的动作。

其中list 是透过apiserver 的client 列出所有的物件例如pod,configmagp 等等(版本为0以后的物件全部列出来) ， list 出来的物件透过delta fifof 的replace function 同步到DeltaFIFO 中，最后纪录list 出来的最新版本号，
这个版本号会在watch 的步骤中用用到。

接着开启一个thread 定期的执行Delta FIFO 的Resync ，这里要注意的是如果没有设定ShouldResync 就不会执行定时做Resync，对同步还不熟悉的小伙伴可以看我之前分享的[Kubernetes DeltaFIFO 承上启下](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/deltafifo/kubernetes-delta-fifo-queue/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc#resync)。

最后透过kubernetes apiserver 的client watch kubernetes 物件资源的变化，监控的某一版本后的物件资源变化，一旦监控的kubernetes 物件资源发生变化例如add 、 update 、 delete 的变化， watcher 就会根据观测到kubernetes 物件资源变化的类型( add 、 update 、 delete ) 呼叫DeltaFIFO 的对应的function ，例如新增加一个物件就会触发delta fifo 的add function ，接着产生一个相应的Delta 并且丢入到delta fifo queue 中，同时更新当前资源的版本，watch 更新的资源版本。

我认为这边有点复杂牵扯到 `apimachinery` 反序列化的过程，中间可能会有错误的见解希望各位在观看文章的大大们可以指出哪里有问题，让我学习改进，谢谢。
