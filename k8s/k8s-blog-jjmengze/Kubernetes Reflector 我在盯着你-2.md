首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

几本上这一块相当的复杂与庞大，有些部分我认为不需要深入理解其运作的机制，我们就来看看一个Reflector 是透过哪里零件组装起来的吧！

上一篇[Kubernetes Reflector 我在盯着你（ I ）](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/redlector/reflector-2/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)一文章有提到client go 的范例建立informer 的过程，在 `NewIndexerInformer` 的过程中最后回传的是一个 `indexer` 以及一个实作`Controller`interface 的物件，本篇会展开讨论Controller 到底是什么

先来回顾一下client go 建立controller 的范例

```
indexer, informer := cache.NewIndexerInformer(podListWatcher, &v1.Pod{}, 0, cache.ResourceEventHandlerFuncs{
    ...
}, cache.Indexers{})

func NewIndexerInformer(
	lw ListerWatcher,
	objType runtime.Object,
	resyncPeriod time.Duration,
	h ResourceEventHandler,
	indexers Indexers,
) (Indexer, Controller) {
	// This will hold the client state, as we know it.
	clientState := NewIndexer(DeletionHandlingMetaNamespaceKeyFunc, indexers)

	return clientState, newInformer(lw, objType, resyncPeriod, h, clientState)
}

```



从上述的source code中可以看到透过`NewIndexerInformer`function 最后会回传indexer 以及controller ， indexer 在先前的章节已经讨论过，还不了解的小伙伴可以到本篇文章[Kubernetes Indexers local cache 之美（I）](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/indexer/kubernetes-indexers-local-cache-1/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc#indexer)复习相关知识，以下会针对controller 的实作进行琢磨。

## Controller

我们先从Controller 定义了什么行为开始探讨

### interface

```
// Controller is a low-level controller that is parameterized by a
// Config and used in sharedIndexInformer.
type Controller interface {
	// Run does two things.  One is to construct and run a Reflector
	// to pump objects/notifications from the Config's ListerWatcher
	// to the Config's Queue and possibly invoke the occasional Resync
	// on that Queue.  The other is to repeatedly Pop from the Queue
	// and process with the Config's ProcessFunc.  Both of these
	// continue until `stopCh` is closed.
	Run(stopCh <-chan struct{})           //執行一個函數，並且透過stopchannel來決定是否跳出

	// HasSynced delegates to the Config's Queue
	HasSynced() bool                    //檢查觀測到 object 是不是同步到 indexer 了

	// LastSyncResourceVersion delegates to the Reflector when there
	// is one, otherwise returns the empty string
	LastSyncResourceVersion() string  //觀測到最新的 object version
}

```



从上面的行为应该是看不出来controller 要做什么吧！没关系我们继续顺藤摸瓜，看看这葫芦里卖的是什么药。

### new function

我们透过new function 来了解实作controller interface 的物件需要什么参数！

```
//傳入的參數我們在上一章節有看過，這邊再來複習一次
//傳入監控物件變化的觀察者
//傳入要觀察的物件資料型態
//傳入多久要同步一次
//傳入事件處理器
//傳入本地儲存器(local storage)
func newInformer(
	lw ListerWatcher,
	objType runtime.Object,
	resyncPeriod time.Duration,
	h ResourceEventHandler,
	clientState Store,
) Controller {
    
    //建立delta fifo queue ，之前章節有探討過delta fifo queue
    //不了解的讀者可以回去複習一次
	fifo := NewDeltaFIFOWithOptions(DeltaFIFOOptions{
		KnownObjects:          clientState,
		EmitDeltaTypeReplaced: true,
	})
    
    //建立controller config的設定檔
	cfg := &Config{
		Queue:            fifo,                //使用delta fifo queue
               ListerWatcher:    lw,                  //使用觀測哪個物件的 listwatch(e.g. pod configmap e.t.c)
		ObjectType:       objType,             //觀測物件的資料型態(e.g. pod configmap e.t.c)
		FullResyncPeriod: resyncPeriod,        //多久要重同步一次
		RetryOnError:     false,                //錯誤是否要重試

		Process: func(obj interface{}) error {    //事件處理器
			// from oldest to newest
			...
		},
	}
	return New(cfg)                                //建立一個實作 controller interface 的物件
}


// New makes a new Controller from the given Config.
func New(c *Config) Controller {
	ctlr := &controller{                //建立一個實作 controller 的物件
		config: *c,
		clock:  &clock.RealClock{},
	}
	return ctlr
}

```



从上面看起来就是放入了一些设定（`deltafifo`, `listerwatch`, `objecttype`）接着把实作controller interface 的物件产出，那实作controller interface 的物件资料结构长怎么样呢？我们接着来看！

### struct

```
// `*controller` implements Controller
type controller struct {
	config         Config                //contrller 相關設定
	reflector      *Reflector            //下一章節會展開來看，本章節暫時用不到
	reflectorMutex sync.RWMutex          //Reflector 讀寫鎖，本章節暫時用不到
	clock          clock.Clock           //同步用
}

// Config contains all the settings for one of these low-level controllers.
type Config struct {
	
	Queue                        //這裡要設定 controller 用的 DeltaFIFO Queue
                                    //前幾個章節有詳細的說明,不了解的朋友可以回去複習。

	
	ListerWatcher                //前一個章節有帶到，實作監視以及列出特定的資源的物件

	
	Process ProcessFunc            //當物件從 DeltaFIFO Queue 彈出，處理事件的function

	// ObjectType is an example object of the type this controller is
	// expected to handle.  Only the type needs to be right, except
	// that when that is `unstructured.Unstructured` the object's
	// `"apiVersion"` and `"kind"` must also be right.
        ObjectType runtime.Object        //這個我認為很難理解，要告訴controller即將到來的物件是什麼例如 pod , deployment e.t.c.

	
	FullResyncPeriod time.Duration    //多久要 resync 一次

	
	ShouldResync ShouldResyncFunc    //reflector會定期透過ShouldResync function來確定是否重新同步queue，

	
        RetryOnError bool                //如果為true，則Process（）返回錯誤時，需要requeue object。
                                        //看註解這是有爭議的，有些開發者認為要拉到更該高的層次決掉 error的處理方式

	
    
	WatchErrorHandler WatchErrorHandler    //每當ListAndWatch斷開連接並出現錯誤時會呼叫這個 function 處理。

	
    
	WatchListPageSize int64                //初始化時設定list watch 的 chunk size.
}

//reflector會定期透過ShouldResync function來確定是否重新同步queue，
type ShouldResyncFunc func() bool

//每當ListAndWatch斷開連接並出現錯誤時調用。
type WatchErrorHandler func(r *Reflector, err error)

```



Config 资料结构内有些资料结构是提供给reflector 做使用，关于reflector 的细节我想在未来的章节在展开来讨论，本篇会专注于controller 会用到的参数。

从上面的资料结构大致上可以看出来一个controller 需要这几个东西

1. DeltaFIFO
   负责将观测到的资料放入FIFO queue，并且标记变化量(Add , Delete , Update etc)
   会把资料存进localcache (indexer)
2. ProcessFunc
   处理DeltaFIFO 的事件变化，例如当Delta Pop 出一个Add 事件，会由ProcessFunc 处理， Pop 出Update 会由ProcessFunc 处理依此类推。
3. runtime.Object
   Controller 知道等等pop出来的是什么物件要如何反序列化
4. ListerWatcher
   列出与监控某一个物件

看到这里我们还要了解controller 底层实作了什么，怎么把上面提到的这四个元素组合再一起使用。

### impliment

Controller 实作以下几个function ，我们一个一个来看！

#### Run

Run function 是最主要的function

```
// Run begins processing items, and will continue until a value is sent down stopCh or it is closed.
// It's an error to call Run more than once.
// Run blocks; call via go.
func (c *controller) Run(stopCh <-chan struct{}) {
    //錯誤處理，有機會再來談，先不理他
	defer utilruntime.HandleCrash()
    
    //當收到stop 訊號關閉 delta fifof queue
	go func() {
		<-stopCh
		c.config.Queue.Close()
	}()
    
    //建立一個 Reflector ，下一章節會展開討論 Reflector 先不理他
	r := NewReflector(
		c.config.ListerWatcher,
		c.config.ObjectType,
		c.config.Queue,
		c.config.FullResyncPeriod,
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

    // todo 我不知道為什麼 controller 綁定 reflector 的時候要加鎖
	c.reflectorMutex.Lock()
    // controller 綁定 Reflector
	c.reflector = r
	c.reflectorMutex.Unlock()

    //wait.Group 預計未來還會拉出來再講一篇
    //簡單來就是被這個 wg 管理的 thread 全部都 done 了之後才會退出 wait 
	var wg wait.Group

    //這個function 會啟動一個 thread 並且在裡面呼叫 剛剛建立的 reflector.run 並且傳入 stop channel 
    //stop channel用來終止 thread 
	wg.StartWithChannel(stopCh, r.Run)
    
    //規律性的呼叫processLoop()，若是收到 stop channel 的訊號就退出
	wait.Until(c.processLoop, time.Second, stopCh)
    
    //等待所有 wait.Group 的 thread done 才能離開，不然會一直卡在這裡～
	wg.Wait()
}


//會被wait.Until 規律性的呼叫
func (c *controller) processLoop() {
	for {
            //從 deltafifo pop 出物件，
            //pop 出的事件會交給 config.Process function 處理
		obj, err := c.config.Queue.Pop(PopProcessFunc(c.config.Process))
		if err != nil {
                //如果 deltafifo queue關閉就退出
			if err == ErrFIFOClosed {
				return
			}
                //如果處理發生錯誤就重新加回 delta fifo queue 中
			if c.config.RetryOnError {
				// This is the safe way to re-enqueue.
				c.config.Queue.AddIfNotPresent(obj)
			}
		}
	}
}

```



#### HasSynced

```
// Returns true once this controller has completed an initial resource listing
//簡單來說就是看Delta FIFO 是不是把資料同步完了
func (c *controller) HasSynced() bool {
    //委任給Delta FIFO QUEUE的HasSynced()
    //不了解的部分可以到前面的章節看一下 Delta FIFO Queue 是怎麼做的
	return c.config.Queue.HasSynced()
}

```



#### LastSyncResourceVersion

透过reflector 得到资源最新的版本

```
func (c *controller) LastSyncResourceVersion() string {
	c.reflectorMutex.RLock()
	defer c.reflectorMutex.RUnlock()
    // 透過 reflector  得到資源最新的版本 下一章節會看到！
	if c.reflector == nil {
		return ""
	}
	return c.reflector.LastSyncResourceVersion()
}

```



## 小结

有了前面几个章节讲述的背景知识如Delta FIFO Queue 、 Indexer 等等作为铺垫，让我们在看Controller 的时候变得相当的容易， Controller 目前还有reflector 这个重要的元件还不清楚他的底层是如何实作的。

下一个篇章将会针对Controller 的reflector 元件进行解析，文中若有解释错误的地方欢迎各位大大们提出讨论，感谢！
