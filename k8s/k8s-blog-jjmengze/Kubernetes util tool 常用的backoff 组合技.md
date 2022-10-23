先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

本篇文章仍然跟前几章节 [使用Backoff 指数大涨](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/utils/kubernetes-utils-tool-wait-exponential/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc) 以及 [使用Backoff 抖了一下](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/utils/kubernetes-utils-tool-wait-jitterbackoff/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc) 有着密切的关系，继续把这条任务解完，以下文章需要前两章的知识作为铺垫若有不了解的地方可以透过连结回到前两章复习相关概念，废话不多说就直接开始吧！

## BackoffUntil

这个BackoffUntil function 做的事情很简单，就是透过相隔backoff manager 所给定的时间触发使用者所指定的function ，若是stop channel 被关闭了就结束整个BackoffUntil function 的生命周期，来看在kubernetes是如何实作的吧。
[source code](https://github.com/kubernetes/kubernetes/blob/v1.19.4/staging/src/k8s.io/apimachinery/pkg/util/wait/wait.go)

```
// BackoffUntil loops until stop channel is closed, run f every duration given by BackoffManager.
//
// If sliding is true, the period is computed after f runs. If it is false then
// period includes the runtime for f.
func BackoffUntil(f func(), backoff BackoffManager, sliding bool, stopCh <-chan struct{}) {
	var t clock.Timer  //先定义一个空的 timer 等等用来接 backoffmanger 算出的 timer
	for {    					 //无限循环
		select {
		case <-stopCh:   //当收到 stop channel 时关闭整个 BackoffUntil function 的生命周期
			return
		default:         //防止 channel卡住
		}
		//如果 sliding 不开启的话，会先从 BackoffManger 算出 timer
		//仔细看下面的话会发现 timer 的时间会包含执行使用者所指定的 function 后才等待 timer 的 ticker
		if !sliding {                            
			t = backoff.Backoff()
		}
        
		//这里的做法就是却跑每次执行使用者所指定的 Function 后都会跑入 defer确保panic 处理
		func() {
			defer runtime.HandleCrash()
			f()
		}()

		//那一个if !sliding 有点类似 ，差别在于这里是执行完使用者所指定的 function 后才算出 timer ， timer 等待时间不包含执行使用者的 function
		if sliding {
			t = backoff.Backoff()
		}
		//在 golang select channel 有个小问题
		//當 select A channel , B channel , C channel 時
		//A B C channel 都刚刚好都有讯号同时到达那 select channel 会选哪一个呢？
		//在 golang 的世界中答案是随机的， A B C channel 哪一个都有可能被选到xD
		
		//当然 kubernetes 的开发者们一定也知道这个问题，在这里就有了相应的注解
		//我这里就保留原始的注解，整段注解的大意大概是如果 stop channel 与 ticker channel 同时到达
		//因为golnag select chennel 机制刚好选中 ticker channel 那会造成使用者指定的 function 多跑一次，这样是不符合预期的行为。
		//因此在for loop 的一开始会判断 stop channel 是否有讯号
		//用来防止 stop channel 与 ticker channel 同时到达并且golang select channel 刚好选中 ticker 的问题

		// NOTE: b/c there is no priority selection in golang
		// it is possible for this to race, meaning we could
		// trigger t.C and stopCh, and t.C select falls through.
		// In order to mitigate we re-check stopCh at the beginning
		// of every loop to prevent extra executions of f().
		select {
		case <-stopCh:
			return
		case <-t.C():
		}
	}
}

```



#### example

范例简单的带一下怎么使用这个function ，我们把重点看在wait.BackoffUntil 就好其他就先不要管，用法十分简单直接来看code。

下面的source code 可能会有些小伙伴觉得很熟悉，对这里就是之前花了满大一个篇幅在介绍的kubenretes controler ，我们可以再复习一下相关的用法！

```
func (r *Reflector) Run(stopCh <-chan struct{}) {
	klog.V(2).Infof("Starting reflector %s (%s) from %s", r.expectedTypeName, r.resyncPeriod, r.name)
	// wait.BackoffUntil 要求输入一个 function 后 wait.BackoffUntil 会间隔 backoffManager 所算出的时间
	// 周期性的执行输入的 function ，如果接收到 stopCh 的讯号就退出
	// 在这里输入的 function 就是 Reflector 的 listAndWatch 了
  // 复习一下 listwatch 负责观测 kubernetes 资源例如 pod , configmap ,secret .e.t.c
	wait.BackoffUntil(func() {
		if err := r.ListAndWatch(stopCh); err != nil {
			r.watchErrorHandler(r, err)
		}
	}, r.backoffManager, true, stopCh)
	klog.V(2).Infof("Stopping reflector %s (%s) from %s", r.expectedTypeName, r.resyncPeriod, r.name)
}

```



### JitterUntil

刚刚看了BackoffUntil function 主要是透过backoff manger 计算出ticker 时间，并且依据是否收到stop channel 作为是否要结束BackoffUntil function 的生命周期。

在kubernetes 中开发者通常不会直接使用BackoffUntil function 而是使用jitter 让所要执行的function 在backoff manger 计算时多考虑jitter 的抖动，实作上也相当简单，一来Backoff Manger 的实作抽换在这里抽换成[NewJitteredBackoffManager](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/utils/kubernetes-utils-tool-wait-jitterbackoff/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)。
[source code](https://github.com/kubernetes/kubernetes/blob/v1.19.4/staging/src/k8s.io/apimachinery/pkg/util/wait/wait.go)

```
func JitterUntil(f func(), period time.Duration, jitterFactor float64, sliding bool, stopCh <-chan struct{}) {
	  //BackoffUntil 使用方法在上一小节有说明过，还不了解的朋友请向上滑动复习
    //NewJitteredBackoffManager 在前两个章节有详细说明
    //所以这里的 timer 计算实作是由 JitteredBackoffManager 实作的，其余的用法都是一样
	BackoffUntil(f, NewJitteredBackoffManager(period, jitterFactor, &clock.RealClock{}), sliding, stopCh)
}

```



#### exmaple

范例简单的带一下怎么使用这个function ，我们把重点看在wait.JitterUntil 就好其他就先不要管，用法十分简单直接来看code。

范例是一个LeaderElector 的acquire 去定时的取得kubernetes 上的资源锁，细节在本篇不去探讨有兴趣的小伙伴我在早期的文章[kubernetes 分散式资源锁](https://blog-jjmengze-website.translate.goog/posts/kubernetes/resourcelock/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc) 有分享过怎么在kubernetes 内实作一个分散是资源锁，有兴趣的可以去看看。
[source code](https://github.com/kubernetes/kubernetes/blob/v1.19.4/staging/src/k8s.io/client-go/tools/leaderelection/leaderelection.go)

```
func (le *LeaderElector) acquire(ctx context.Context) bool {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	succeeded := false
	desc := le.config.Lock.Describe()
	klog.Infof("attempting to acquire leader lease %v...", desc)
	// 这里直接指定一个 function ，在这里是一个匿名函数做的事情大概是尝试取得/更新物件
	// 从输入的几个参数来看就是每 le.config.RetryPeriod 执行一次 尝试取得/更新物件的匿名函数
	// 间隔时间的抖动因子由 JitterFactor 决定 ，这里就决定了最大的间隔时间了
	// 这个 function Sliding （Sliding = true）表示 backofftime 包含了执行使用者自订的 function 时间。
	// 以及最后 context.done 的部份决定了，这个 wait.JitterUntil function 的生命周期跟着整个 process 而不是某一个 channel 讯号。
	wait.JitterUntil(func() {
		succeeded = le.tryAcquireOrRenew(ctx)
		le.maybeReportTransition()
		if !succeeded {
			klog.V(4).Infof("failed to acquire lease %v", desc)
			return
		}
		le.config.Lock.RecordEvent("became leader")
		le.metrics.leaderOn(le.config.Name)
		klog.Infof("successfully acquired lease %v", desc)
		cancel()
	}, le.config.RetryPeriod, JitterFactor, true, ctx.Done())
	return succeeded
}

```



### NonSlidingUntil

NoSlidingUntil function 是对JitterUntil function 进行简单的封装，我们很简单的带过去，主要就是把Sliding 的功能强制关掉，忘记Sliding是什么的我们简单复习一下。

当禁用Sliding （Sliding = false）表示backofftime 不包含了执行使用者自订的function 时间。
[source code](https://github.com/kubernetes/kubernetes/blob/v1.19.4/staging/src/k8s.io/apimachinery/pkg/util/wait/wait.go)

```
func NonSlidingUntil(f func(), period time.Duration, stopCh <-chan struct{}) {
	JitterUntil(f, period, 0.0, false, stopCh)
}

```



#### exmaple

范例简单的带一下怎么使用这个function ，我们把重点看在wait.NonSlidingUntil 就好其他就先不要管，用法十分简单直接来看code。

范例是一个cloud provider 的RouteController 去定时的执行reconcileNodeRoutes 也就是同步节点上的路由资讯，细节我们不去深入研究把焦点摆在怎么使用这个工具就好。
[source code](https://github.com/kubernetes/kubernetes/blob/v1.19.4/staging/src/k8s.io/cloud-provider/controllers/route/route_controller.go)

```
func (rc *RouteController) Run(stopCh <-chan struct{}, syncPeriod time.Duration) {
	defer utilruntime.HandleCrash()

	klog.Info("Starting route controller")
	defer klog.Info("Shutting down route controller")
	...
    // 这里透过 goroutine 直接指定一个 function ，在这里就是 rc.reconcileNodeRoutes() 并且判断执行的结果
    // 若是有错误就 log 出相关的错误讯息
	// 从输入的几个参数来看就是每 syncPeriod 执行一次 rc.reconcileNodeRoutes() ，如果 stop channel 收到讯息那 就会结束 wait.Until 的生命周期
    //使用 wait.NonSlidingUntil 这个 function 表示表示 backofftime 不包含了执行使用者自订的 function 时间。
	go wait.NonSlidingUntil(func() {
		if err := rc.reconcileNodeRoutes(); err != nil {
			klog.Errorf("Couldn't reconcile node routes: %v", err)
		}
	}, syncPeriod, stopCh)

	<-stopCh
}

```



### Until

Unti function 也是对JitterUntil function 进行简单的封装，我们很简单的带过去，主要就是把Sliding 的功能强制开启，忘记Sliding 是什么的我们简单复习一下。

当启用Sliding （Sliding = true）表示backofftime 包含了执行使用者自订的function 时间。
[source code](https://github.com/kubernetes/kubernetes/blob/v1.19.4/staging/src/k8s.io/apimachinery/pkg/util/wait/wait.go)

```
func Until(f func(), period time.Duration, stopCh <-chan struct{}) {
	JitterUntil(f, period, 0.0, true, stopCh)
}

```



#### example

范例简单的带一下怎么使用这个function ，我们把重点看在wait.Until 就好其他就先不要管，用法十分简单直接来看code。

范例是一个CRDFinalizer 去启动指定的work 让他工作，
[source code](https://github.com/kubernetes/kubernetes/blob/v1.19.4/staging/src/k8s.io/apiextensions-apiserver/pkg/controller/finalizer/crd_finalizer.go)

```
func (c *CRDFinalizer) Run(workers int, stopCh <-chan struct{}) {
	defer utilruntime.HandleCrash()
	defer c.queue.ShutDown()

	klog.Infof("Starting CRDFinalizer")
	defer klog.Infof("Shutting down CRDFinalizer")

	if !cache.WaitForCacheSync(stopCh, c.crdSynced) {
		return
	}
	// 这里通过 goroutine 直接指定一个 function ，在这里就是 c.runWorker
	// 从输入的几个参数来看就是每秒执行一次 c.runWorker ，如果 stop channel 收到讯息那 就会结束 wait.Until 的生命周期
	for i := 0; i < workers; i++ {
		go wait.Until(c.runWorker, time.Second, stopCh)
	}

	<-stopCh
}

```



### Forever

Forever function 是对Until 进行简单的封装，简单来看就是执行一个永远不会中止且定时触发的function。
[source code](https://github.com/kubernetes/kubernetes/blob/v1.19.4/staging/src/k8s.io/apimachinery/pkg/util/wait/wait.go)

```
var NeverStop <-chan struct{} = make(chan struct{})
func Forever(f func(), period time.Duration) {
	Until(f, period, NeverStop)
}

```



#### example

[source code](https://github.com/kubernetes/kubernetes/blob/v1.19.4/staging/src/k8s.io/apiserver/pkg/server/healthz/healthz.go)

范例简单的带一下怎么使用这个function ，我们把重点看在wait.Forever 就好其他就先不要管，用法十分简单直接来看code。

```
func (l *log) Check(_ *http.Request) error {
	l.startOnce.Do(func() {
		l.lastVerified.Store(time.Now())
		// 这里通过 goroutine 直接指定一个 function  
		// function 的内容看说来就是把 log flush以及 做时间的储存。
		// 并且这个 function 每一分钟执行一次。       
		go wait.Forever(func() {
			klog.Flush()
			l.lastVerified.Store(time.Now())
		}, time.Minute)
	})

	lastVerified := l.lastVerified.Load().(time.Time)
	if time.Since(lastVerified) < (2 * time.Minute) {
		return nil
	}
	return fmt.Errorf("logging blocked")
}

```



### JitterUntilWithContext

这就表示定时会呼叫使用者指定的function ，若是外面有人将context cancel 掉，那就会结束wait.JitterUntilWithContext 的生命周期。基本上复用了 `JitterUntil` 让使用者可以自行决定Sliding 与jitterFactor 。

```
func JitterUntilWithContext(ctx context.Context, f func(context.Context), period time.Duration, jitterFactor float64, sliding bool) {
	JitterUntil(func() { f(ctx) }, period, jitterFactor, sliding, ctx.Done())
}

```



#### example

范例简单的带一下怎么使用这个function ，我们把重点看在wait.JitterUntilWithContext 就好其他就先不要管，用法十分简单直接来看code。

在这格function 透过 `context.WithCancel(context.Background())` 启动了一个context，接着拿到endpoint slice 透过for 回圈递回每个endpoint ，透过goroutine 呼叫wait.JitterUntilWithContext 并且带入检查endpoint status 的function ，当启用Sliding （Sliding = true）表示backofftime 包含了执行使用者自订的function 时间。

这就表示定时会呼叫指定的function ，这个例子就是检查endpoint status 。若是外面有人将context cancel 掉，那就会结束wait.JitterUntilWithContext 的生命周期。

```
func startDBSizeMonitorPerEndpoint(client *clientv3.Client, interval time.Duration) (func(), error) {
	...

	ctx, cancel := context.WithCancel(context.Background())
	for _, ep := range client.Endpoints() {
		if _, found := dbMetricsMonitors[ep]; found {
			continue
		}
		dbMetricsMonitors[ep] = struct{}{}
		endpoint := ep
		klog.V(4).Infof("Start monitoring storage db size metric for endpoint %s with polling interval %v", endpoint, interval)
		go wait.JitterUntilWithContext(ctx, func(context.Context) {
			epStatus, err := client.Maintenance.Status(ctx, endpoint)
			if err != nil {
				klog.V(4).Infof("Failed to get storage db size for ep %s: %v", endpoint, err)
				metrics.UpdateEtcdDbSize(endpoint, -1)
			} else {
				metrics.UpdateEtcdDbSize(endpoint, epStatus.DbSize)
			}
		}, interval, dbMetricsMonitorJitter, true)
	}

	return func() {
		cancel()
	}, nil
}

```



### UntilWithContext

这就表示定时会呼叫使用者指定的function ，若是外面有人将context cancel 掉，那就会结束wait.UntilWithContext 的生命周期。基本上复用了 `JitterUntilWithContext` 差别在每此重新呼叫function 的间隔时间有没有抖动而已。

```
func UntilWithContext(ctx context.Context, f func(context.Context), period time.Duration) {
	JitterUntilWithContext(ctx, f, period, 0.0, true)
}

```



#### exmaple

很可惜kubernetes 内没有使用这个function 我们可以透过test code 来观摩怎么使用这个function 。

> 题外话我觉得这个测试写得真不错，没想过可以这样测试！！

```
func TestUntilWithContext(t *testing.T) {
	//建立 with cancel 的 context
	ctx, cancel := context.WithCancel(context.TODO())
	//直接结束 context 的生命週期
	cancel()
	//这时候在透过 UntilWithContext 呼叫我们自定义的 function
	//记得....因为 context 生命周期已经死掉了照理来说不会触发我们自订的 function 。    
	UntilWithContext(ctx, func(context.Context) {
		t.Fatal("should not have been invoked")
	}, 0)
	
    
	//建立 with cancel 的 context
	ctx, cancel = context.WithCancel(context.TODO())
	//建立取消的  channel 
	called := make(chan struct{})
	//启动一个 go routine ，主要透过异步的经由 UntilWithContext 定时呼叫自定义的 function
	//因为 main thread 会卡在 <-called，当执行到 goroutine 时会送讯号到  called  channel，让外部收到
	go func() {
		UntilWithContext(ctx, func(context.Context) {
			called <- struct{}{}
		}, 0)
		//发送完讯号后就关闭 channel
		close(called)
	}()
	//收到 goroutine才会往下继续执行
	<-called
  //关闭 context 的生命周期让 UntilWithContext  不再执行我们自定义的 function
	cancel()
	//收到关闭  channel 的讯号
	<-called
}

```



## 小结

Kubernetes 内好用工具非常非常多让我们不需要造轮子，但是开发者要记住的一个铁则是尽信书不如无书，前人为我们铺好的路我们需要了解是怎么铺路的，有没有更好的工法。承袭着前人的智慧将问题以更快速且更好的方式解决。

上述分享的内容中间可能会有错误的见解希望各位在观看文章的大大们可以指出哪里有问题，让我学习改进，谢谢。
