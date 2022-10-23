

https://blog-jjmengze-website.translate.goog/posts/kubernetes/resourcelock/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc



当系统中一个元件有多个副本，这些副本同时要竞争成为领导人会透过许多不同的方法，例如zk(zookeeper), raft, redis…等等，这些方法都有一个共同的特色谁先抢到谁就当领导人。

那如果我的元件执行在Kubernetes 丛集里，有没有什么方式可以不依靠第三方系统让我们也能做到分散式资源锁呢？

答案是有的，可以透过Kubernetes的ResourceLock来达成，ResourceLock 大致上可以分为`endpoints`, `configmaps`,`leases`三种。

如果我们有手动搭建过HA(High Availability)环境的Kubernetes的话，会发现Controller Manger 与Scheduler

> todo

这是因为Controller Manger以及scheduler 这两个元件采用Lease 又或是称为ResourceLock 的机制来从所有的instance 会去竞争一个资源锁，只有竞争到资源锁的instance 有能力去继续动作，没有竞争到的则是继续等待资源锁的释放，只有持有资源锁的instance 发生故障没有renew 资源锁，其他instacne 才会继续竞争资源锁并接手原来的工作。

底下会透过Kubernetes controller manger source code 来简单的介绍Kubernetes ResourceLock。

## resourcelock

在Controller Manger source code 中有使用到`resourcelock.New`，`resourcelock.New`使用到工厂模式在new的过程中会产出我们指定的`resourcelock`。

```
// Lock required for leader election
	rl, err := resourcelock.New(
    //在config会指定说要新增哪一种resource lock
    c.ComponentConfig.Generic.LeaderElection.ResourceLock,
		//在config会指定说resource lock要绑在哪一个namespace
		c.ComponentConfig.Generic.LeaderElection.ResourceNamespace,
    //在config会指定说resource lock的名称
		c.ComponentConfig.Generic.LeaderElection.ResourceName,
    /带入建立configmap,endpoint client object
		c.LeaderElectionClient.CoreV1(),
    //带入建立leases client object
		c.LeaderElectionClient.CoordinationV1(),
    //Resource lock 內容
		resourcelock.ResourceLockConfig{
      //Resource lock 內容 ID 目前会以hostname_uuid
			Identity:      id,
      //EventRecorder 会纪录 resource lock 事件
			EventRecorder: c.EventRecorder,
		})
	if err != nil {
		klog.Fatalf("error creating lock: %v", err)
	}

```



configmaplock,endpointlock,leaselock,MultiLock这几个需要实作以下这个interface。
我们来简单看一下这些是什么功能

1. Get
   从ConfigMap、enpoint 或lease 的Annotation 拿到LeaderElectionRecord 的资料。
2. Create
   建立LeaderElectionRecord 的资料到ConfigMap、enpoint 或lease 的Annotation。
3. Update
   更新现有的LeaderElectionRecord 资料到ConfigMap、enpoint 或lease 的Annotation。
4. RecordEvent
   当加入LeaderElectionRecord 触发纪录的event 。
5. Identity
   拿到resource lock 的id 。
6. Describe
   拿到resource name/resource meta-data name

```
type Interface interface {
	// Get returns the LeaderElectionRecord
	Get(ctx context.Context) (*LeaderElectionRecord, []byte, error)

	// Create attempts to create a LeaderElectionRecord
	Create(ctx context.Context, ler LeaderElectionRecord) error

	// Update will update and existing LeaderElectionRecord
	Update(ctx context.Context, ler LeaderElectionRecord) error

	// RecordEvent is used to record events
	RecordEvent(string)

	// Identity will return the locks Identity
	Identity() string

	// Describe is used to convert details on current resource lock
	// into a string
	Describe() string
}

```



## configmap lock/lease lock 物件

这边举两个例子ConfigmapLock与LeaseLock，xxxMeta主要纪录namespace name以及resource name。
Client 主要是用来操作Kubernetes configmap / endpoint 的client api。
LockConfig 主要操作Kubernetes Lease 的Client api。
cm 操作Kubernetes configmap 实际物件，不会透露给使用者。

```
type ConfigMapLock struct {
	// ConfigMapMeta should contain a Name and a Namespace of a
	// ConfigMapMeta object that the LeaderElector will attempt to lead.
	ConfigMapMeta metav1.ObjectMeta
	Client        corev1client.ConfigMapsGetter
	LockConfig    ResourceLockConfig
	cm            *v1.ConfigMap
}

...

type LeaseLock struct {
	// LeaseMeta should contain a Name and a Namespace of a
	// LeaseMeta object that the LeaderElector will attempt to lead.
	LeaseMeta  metav1.ObjectMeta
	Client     coordinationv1client.LeasesGetter
	LockConfig ResourceLockConfig
	lease      *coordinationv1.Lease
}
...

```



## configmap lock/lease lock 工厂

kubernetes lock resource 透过工厂模式建立不同的物件
一开始一次性建立`EndpointsLock`, `ConfigMapLock`,`LeaseLock`再由lockType去选择要回传什么给使用者。例如type 输入endpoints 最后回传给使用者的就是endpointslock ，type 输入configmaps 最后回传使用者configmaplock 。

```
// Manufacture will create a lock of a given type according to the input parameters
func New(lockType string, ns string, name string, coreClient corev1.CoreV1Interface, coordinationClient coordinationv1.CoordinationV1Interface, rlc ResourceLockConfig) (Interface, error) {
	endpointsLock := &EndpointsLock{
		EndpointsMeta: metav1.ObjectMeta{
			Namespace: ns,
			Name:      name,
		},
		Client:     coreClient,
		LockConfig: rlc,
	}
	configmapLock := &ConfigMapLock{
		ConfigMapMeta: metav1.ObjectMeta{
			Namespace: ns,
			Name:      name,
		},
		Client:     coreClient,
		LockConfig: rlc,
	}
	leaseLock := &LeaseLock{
		LeaseMeta: metav1.ObjectMeta{
			Namespace: ns,
			Name:      name,
		},
		Client:     coordinationClient,
		LockConfig: rlc,
	}
	switch lockType {
	case EndpointsResourceLock:
		return endpointsLock, nil
	case ConfigMapsResourceLock:
		return configmapLock, nil
	case LeasesResourceLock:
		return leaseLock, nil
	case EndpointsLeasesResourceLock:
		return &MultiLock{
			Primary:   endpointsLock,
			Secondary: leaseLock,
		}, nil
	case ConfigMapsLeasesResourceLock:
		return &MultiLock{
			Primary:   configmapLock,
			Secondary: leaseLock,
		}, nil
	default:
		return nil, fmt.Errorf("Invalid lock-type %s", lockType)
	}
}


```



## resource lock 怎么使用

在kubernetes controller manger code 里面使用了leaderelection package 的RunOrDie package 这个方法会在后面解析。

这边简单说明一下怎么使用RunOrDie ，非常简单。

1. lock
   在上面定义的resource lock 会在这里被使用，例如上面定义了，例如上面定义了configmap resource lock 会在这里被注入（`可能`会被create）。

2. leaseduration
   要占领这个resource lock 多久

3. renewdeadline
   多久要刷新一次resource lock

4. retryperiod
   多久要尝试建立resource lock

5. callback

   - OnStartedLeading:
     当成功拿到resource lock 后要执行的动作
   - OnStoppedLeading:
     拿取resource lock 失败后要执行的动作

6. watchdog
   健康检查相关的Object

7. name

   > todo

```

// Try and become the leader and start cloud controller manager loops
//使用 leaderelection package 里的 RunOrDie function 会按照你设定的 LeaseDuration, RenewDeadline , RetryPeriod 三个时间尝试成为领导人。
	leaderelection.RunOrDie(context.TODO(), 
            leaderelection.LeaderElectionConfig{
		Lock:          rl,
		LeaseDuration: c.ComponentConfig.Generic.LeaderElection.LeaseDuration.Duration,
		RenewDeadline: c.ComponentConfig.Generic.LeaderElection.RenewDeadline.Duration,
		RetryPeriod:   c.ComponentConfig.Generic.LeaderElection.RetryPeriod.Duration,
		Callbacks: leaderelection.LeaderCallbacks{
      // 在 controller manger 里面就是执行一个run 的function 当作controller 的进入点
      // 也可以理解为controller manger 程式的进入点
			OnStartedLeading: run,
			OnStoppedLeading: func() {
				klog.Fatalf("leaderelection lost")
			},
		},
		WatchDog: electionChecker,
		Name:     "cloud-controller-manager",
	})

```



## RunOrDie背后的机制

对于使用者很方便的RunOrDie，我们只要简单的使用这个function 就可以定期的帮我们去竞争resource lock 势必要去理解RunOrDie 背后的机制。

```
// RunOrDie starts a client with the provided config or panics if the config
// fails to validate. RunOrDie blocks until leader election loop is
// stopped by ctx or it has stopped holding the leader lease
func RunOrDie(ctx context.Context, lec LeaderElectionConfig) {
  //这里很简单的去new一个LeaderElector物件，主要是检查使用者输入的config有没有问题。
	le, err := NewLeaderElector(lec)
	if err != nil {
		panic(err)
	}
  //还不是很清楚watch dog的作用
	if lec.WatchDog != nil {
		lec.WatchDog.SetLeaderElection(le)
	}
  //主要在这个run function 等等会来解密这个function 做了什么事
	le.Run(ctx)
}

```



### LeaderElector run 解密

刚刚有提到RunOrDie 背后的机制主要是透过LeaderElector 物件底下的run 方法去执行的，底下就开始讲解LeaderElector run做什么吧！

大致上主要是执行建立以及续约resourcelock ，当没拿到resourcelock 就对退出，拿到resourcelock就执行续约。

```
// Run starts the leader election loop. Run will not return
// before leader election loop is stopped by ctx or it has
// stopped holding the leader lease
func (le *LeaderElector) Run(ctx context.Context) {
  //kubernetes 内置处理 crash 的方法本篇不讨论。
	defer runtime.HandleCrash()
  //当使用者触发了 context cancel 时会呼叫 OnStoppedLeading 方法。
	defer func() {
		le.config.Callbacks.OnStoppedLeading()
	}()
  //在这里会试图取得会是创建 resource lock,没有拿到资源锁的话就会卡在这个function里面喔！
	if !le.acquire(ctx) {
		return // ctx signalled done
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
  //启动一个goroutine去执行controller manager的进入点运行函数
	go le.config.Callbacks.OnStartedLeading(ctx)
  //执行资源锁的续约动作。
	le.renew(ctx)
}

```



### LeaderElector acquire

这边会简单的说明一下获取resource lock 的流程，这里主要各个process会尝试向kubernetes 要resource lock 如果不成功就再尝试看看（尝试到process死掉或是拿到resource lock为止）。

```
// acquire loops calling tryAcquireOrRenew and returns true immediately when tryAcquireOrRenew succeeds.
// Returns false if ctx signals done.
func (le *LeaderElector) acquire(ctx context.Context) bool {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	succeeded := false
	desc := le.config.Lock.Describe()
	klog.Infof("attempting to acquire leader lease %v...", desc)
  // kubernetes 封装了一个类似的函数比较，细节不去讨论。
  // 我们只要知道他是一个定时触发的功能就好了。
	wait.JitterUntil(func() {
    //尝试获取资源锁或续约资源锁
		succeeded = le.tryAcquireOrRenew(ctx)
    //不知道实际用途，追踪代码感觉是有人定义的Callbacks.OnNewLeader的话会回调领导者换了。
		le.maybeReportTransition()
    // 获取资源锁失败，会等下一次到了再尝试获取租约。
		if !succeeded {
			klog.V(4).Infof("failed to acquire lease %v", desc)
			return
		}
    //获取租约成功记录事件
		le.config.Lock.RecordEvent("became leader")
    //获取租约成功记录事件
		le.metrics.leaderOn(le.config.Name)
    //获取租约成功记录事件
		klog.Infof("successfully acquired lease %v", desc)
    //获取成功后就不用再跑timmer，（这个Timmer用意就是让没有拿到资源锁的人可以定时去拿拿看）
		cancel()
	}, le.config.RetryPeriod, JitterFactor, true, ctx.Done())
	return succeeded
}

```



### LeaderElector renew

LeaderElector renew 的行为可能我是说可能有一点点复杂，对其他大大们来说可能不会xD。

当通过一关LeaderElector acquire 的考验process 已经拿到resource lock 接下来只要不断的更新租约就好了～

```
// renew loops calling tryAcquireOrRenew and returns immediately when tryAcquireOrRenew fails or ctx signals done.
func (le *LeaderElector) renew(ctx context.Context) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
  // kubernetes封装了一个类似的比较，细节不去讨论。
  // 可能有人会问跟wait.Jiter直到有什么别的，这会另外开战场然后去讨论（先挖洞给自己跳）
	wait.Until(func() {
		timeoutCtx, timeoutCancel := context.WithCancel(ctx)
		defer timeoutCancel()
    // PollImmediateUntil 会等 ConditionFunc 执行完毕
    // 若是 ConditionFunc 执行失败(是false)等 RetryPeriod 时间在执行一次。
    // 如果 ConditionFunc 执行的结果是错误或错误的话就不会重新尝试 ConditionFunc 。
		err := wait.PollImmediateUntil(le.config.RetryPeriod, func() (bool, error) {
      // ConditionFunc
      // 尝试建立资源锁或更新资源锁租约
			return le.tryAcquireOrRenew(timeoutCtx), nil
		}, timeoutCtx.Done())
    //不知道实际用途，追踪代码感觉是有人定义的Callbacks.OnNewLeader的话会回调领导者换了。
		le.maybeReportTransition()
    // 拿到 resource lock 以configmaplock 为例 desc等于 configmaplock namespace/configmap name
		desc := le.config.Lock.Describe()
    // 这里代表更新了租约成功就跳出去了，等待下一次定时的呼叫。
		if err == nil {
			klog.V(5).Infof("successfully renewed lease %v", desc)
			return
		}
    // 租约更新失败记录
		le.config.Lock.RecordEvent("stopped leading")
    // 租约更新失败记录
		le.metrics.leaderOff(le.config.Name)
    // 租约更新失败记录
		klog.Infof("failed to renew lease %v: %v", desc, err)
    // 再也不通过定期更新租约
		cancel()
	}, le.config.RetryPeriod, ctx.Done())

	// if we hold the lease, give it up
	if le.config.ReleaseOnCancel {
		le.release()
	}
}

```



## 小结

本篇非常简单的带过source code 了解了在有kubernetes 的环境之下如何透过kubernetes 已经有的资源完成一个分散式锁，如果有任何疑问或内文有错的地方欢迎提出跟我起讨论！
