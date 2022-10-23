首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

## SafeWaitGroup

> 这一段在先前一版的文章中理解错误，在明天将补上新版，请大家见谅。

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/utils/kubernetes-utils-tool-wiat/staging/src/k8s.io/apimachinery/pkg/util/waitgroup/waitgroup.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// SafeWaitGroup must not be copied after first use.
type SafeWaitGroup struct {
	wg sync.WaitGroup
	mu sync.RWMutex
	// wait indicate whether Wait is called, if true,
	// then any Add with positive delta will return error.
	wait bool
}
...

```



## wiat

在kubernetes 中比起package waitgroup 从source code 中更长看到使用者使用package wait ，在这一小节中会看一些范例了解kubernetes 中哪里有用到这一个package。

```
// package group 很簡單就是嵌入了一個 go startand librart 的 sync.WaitGroup
type Group struct {
	wg sync.WaitGroup
}

// StartWithChannel 可以透過 stopCh 關閉在這個 goroutine group 中的啟動function。
func (g *Group) StartWithChannel(stopCh <-chan struct{}, f func(stopCh <-chan struct{})) {
	g.Start(func() {
		f(stopCh)
	})
}

// StartWithContext 可以透過 Context 關閉在這個 goroutine group 中的啟動 function。
func (g *Group) StartWithContext(ctx context.Context, f func(context.Context)) {
	g.Start(func() {
		f(ctx)
	})
}

// 這裡就是對 sync.WaitGroup 進行簡單的封裝，上述兩種 StartWithContext 與 StartWithChannel
// 都會呼叫 start function 增加 WaitGroup 的 wg 與 執行 goroutine
func (g *Group) Start(f func()) {
	g.wg.Add(1)
	go func() {
		defer g.wg.Done()
		f()
	}()
}

// 透過 Wait function 等待所有透過 goroutine group 啟動的 function 執行完畢也就是 執行 defer g.wg.Done()
func (g *Group) Wait() {
	g.wg.Wait()
}

```



### example

分成两个范例来看，第一个是单纯跑Group 的Start function 。

这个function 里面在做什么我们先不深入研究，把焦点放在Group 的Start function 与Wait function 就好。

可以看到一开始透过匿名函数使用for 回圈经由 `wg.Start` 启动了n 个goroutine ，接着卡在stop channel 。

后续若是收到stop channel 的话可以简单地理解成工作要收工了，因此后面再用一个for 回圈把所有listeners 的add channel 关闭，最后透过 `wg.Wait()` 等待所有channel 把关闭工作完成才退出function 。

wg.Start 与wg.wait 整体来说使用上不复杂可以配合多种情境，例如范例所示范的开启多个worker 等待worker 完成。

> Tips 至于为什么不用StartWithChannel 来处理范例中的listener.run listener.pop ，好问题我也不知道xDDD
> [source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/utils/kubernetes-utils-tool-wiat/staging/src/k8s.io/client-go/tools/cache/shared_informer.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
func (p *sharedProcessor) run(stopCh <-chan struct{}) {
	func() {
		p.listenersLock.RLock()
		defer p.listenersLock.RUnlock()
		for _, listener := range p.listeners {
			p.wg.Start(listener.run)
			p.wg.Start(listener.pop)
		}
		p.listenersStarted = true
	}()
	<-stopCh
	p.listenersLock.RLock()
	defer p.listenersLock.RUnlock()
	for _, listener := range p.listeners {
		close(listener.addCh) // Tell .pop() to stop. .pop() will tell .run() to stop
	}
	p.wg.Wait() // Wait for all .pop() and .run() to stop
}

```



接着来看另外一个范例wg.StartWithChannel ，范例这个function 里面在做什么我们先不深入研究，把焦点放在Group 的StartWithChannel function 与Wait function 就好。

可以看到一开始透过Run function 离开时第一个触发的是close processor Stop Channel。

接着触发的defer 是wg.Wait() 这里就会等待所有的透过wait.Group 启动的goroutine 完成才结束工作。

可以简单地理解成广播说下课，要等所有同学把课本收好才可以离开，而老师就站在前面看着大家收拾。就可以把wg 想像成老师负责监督大家只要有人没收好东西就不准最下一步，processorStopCh 可以想成学校的广播，当广播发号司令同学就要做某某事情。
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/utils/kubernetes-utils-tool-wiat/staging/src/k8s.io/client-go/tools/cache/shared_informer.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
func (s *sharedIndexInformer) Run(stopCh <-chan struct{}) {
	defer utilruntime.HandleCrash()
    ...
    var wg wait.Group
	defer wg.Wait()              // Wait for Processor to stop
	defer close(processorStopCh) // Tell Processor to stop
	wg.StartWithChannel(processorStopCh, s.cacheMutationDetector.Run)
	wg.StartWithChannel(processorStopCh, s.processor.run)
    ...
}

```



## 小结

简单来说 `package wait` 只是对go startand librart 进行简单的封装，带来的效益就是让大多数的开发者可以沿用这这个封装好的package ，因为要达到某一个功能实作的方式有很多种，有了适当的封装能让开发风格更统一。文章中若有出现错误的见解希望各位在观看文章的大大们可以指出哪里有问题，让我学习改进，谢谢。
