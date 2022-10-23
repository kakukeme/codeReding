首先本文所以source code 基于kubernetes 1.19 版本，所有source code 的为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

## kubernetes work queue

Kubernetes controller/operator 是一个非常精彩的设计模式，在了解Kubernetes controller/operator 怎么撰写之前，了解kubernetes work queue的实作模式是非常重要的，下面引用了[How to Create a Kubernetes Custom Controller Using client-go](https://itnext.io/how-to-create-a-kubernetes-custom-controller-using-client-go-f36a7a7536cc)的controller 架构图可以看到在sharedindexinformer 内有引用到这个元件，这个元件实际被定义在kubernetes 的client-go library 中。



![img](assets/kubernetes-controller-arch-6431997.png)


图片来源： [How to Create a Kubernetes Custom Controller Using client-go](https://itnext.io/how-to-create-a-kubernetes-custom-controller-using-client-go-f36a7a7536cc)



Kubernetes 为什么要实践一个work queue 呢？就我们所知kubernetes 是用go 撰写应该可以使用channel 的机制直接将物件送给要用的元件(thread)啊，原因其实非常简单，go channel 的设计功能非常单一无法满足kubernetes 所要的场景，例如带有延迟时间物件queue 就需要根据延迟时间排序，例如限制物件取出速度的queue 。

刚刚提到了两种queue ，kubernetes 实作上称为rate limiters queue 以及delaying queue ，此外还有一种通用的common queue ，本篇文章会先从common queue 开始探讨。

## common queue

kubernetes source code 设计得非常精美，我们可以先从interface 定义了哪些方法来推敲**实作(实现)**这个interface 的物件可能有什么功能。

### interface

[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/queue.go)

```
type Interface interface {
	Add(item interface{})     //向 queue 送出 interface 類型的物件
	Len() int                 //拿到目前儲存在 queue 中的物件的數量
	Get() (item interface{}, shutdown bool) //拿到 queue 中第 0 個 item，另外 queue 是否已經關閉
	Done(item interface{})    //告知 queue 某個 item 已經處理完成
	ShutDown()                //關閉 queue
	ShuttingDown() bool       //查詢 queue 是否關閉
}

```



看完了抽象的定义之后，必须要回过来看common queue 实际物件定义了哪些属性

### struct

[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/queue.go)

```
// Type is a work queue (see the package comment).
type Type struct {
	// queue defines the order in which we will work on items. Every
	// element of queue should be in the dirty set and not in the
	// processing set.
	queue []t    // queue 的 item 用 slice 储存，item 的类型为 t 等等会看到 t 是什么

	// dirty defines all of the items that need to be processed.
	dirty set    // 用来判断我们哪些 item 还没处理，是一个 set 的集合

	// Things that are currently being processed are in the processing set.
	// These things may be simultaneously in the dirty set. When we finish
	// processing something and remove it from this set, we'll check if
	// it's in the dirty set, and if so, add it to the queue.
	processing set    // 储存我们现在哪些 item 正在处理，是一个 set 的集合。

	cond *sync.Cond    //同步锁，用来通知其他thread可以取item

	shuttingDown bool    //标记 queue是否关闭

	metrics queueMetrics    // metric不解释

	unfinishedWorkUpdatePeriod time.Duration // 给 metric 用确认 queue 是否还活着
	clock                      clock.Clock    // 给 metric 用确认 queue 是否还活
}

```



刚刚上面有一个疑点那就type t 到底是什么，以及type set 到底是什么。
我们先来看看type t 的结构
[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/queue.go)

```
type t interface{}    //其实也没什么好说的，就是一个泛形表示什么类型的物件我都能吃 
```



接下来看看type set 的结构
[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/queue.go)

```

type empty struct{}
type set map[t]empty    // type set 就是一个 map ，key 是一个泛型物件 值就是空结构

// set 资料结构的，判断有没有 t 类型的物件
func (s set) has(item t) bool {    
	_, exists := s[item]
	return exists
}

// set 资料结构的，插入 t 类型的物件
func (s set) insert(item t) {
	s[item] = empty{}
}

// set 资料结构的，删除 t 类型的物件
func (s set) delete(item t) {
	delete(s, item)
}
```



看完了资料结构我们接着来看common work queue 实作的方法，与初始化方法。（看到common work queue的型态为Type让我一直搞混….）

### new function

[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/queue.go)

```
//透过 NewQueue function 建立一个common work queue。
//其中需要带入 clock , metric , updatePeriod 参数
func newQueue(c clock.Clock, metrics queueMetrics, updatePeriod time.Duration) *Type {
	t := &Type{
		clock:                      c,
		dirty:                      set{},
		processing:                 set{},
		cond:                       sync.NewCond(&sync.Mutex{}),
    //metric物件
		metrics:                    metrics,
    //定期检测时间
		unfinishedWorkUpdatePeriod: updatePeriod,
	}
  // 启动一个 thread 检测 queue 是否关闭,并且定期回报 metric
	go t.updateUnfinishedWorkLoop()
	return t
}

const defaultUnfinishedWorkUpdatePeriod = 500 * time.Millisecond

// 不同的封装方式，不提 （设定 metric 的 name 为空）
func New() *Type {
	return NewNamed("")
}

// 不同的封裝方式，提供 name （设定 metric 的 name ）
func NewNamed(name string) *Type {
	rc := clock.RealClock{}
	return newQueue(
		rc,
		globalMetricsFactory.newQueueMetrics(name, rc),
		defaultUnfinishedWorkUpdatePeriod,
	)
}

//
func (q *Type) updateUnfinishedWorkLoop() {
  //设置了一个定时器
	t := q.clock.NewTicker(q.unfinishedWorkUpdatePeriod)
  //在function结束的时候停止计时器
	defer t.Stop()
  //当受到计时器讯号时
	for range t.C() {
		if !func() bool {
      // Lock 锁
			q.cond.L.Lock()
      // 当离开时解锁
			defer q.cond.L.Unlock()
      // 判断 queue 是否关闭（没有关闭）
			if !q.shuttingDown {
        //告诉 metric work queue 还没关闭
				q.metrics.updateUnfinishedWork()
        // 回传继续等在 计时器讯号
				return true
			}
      // queue 关闭了 跳出整个 updateUnfinishedWorkLoop() function
			return false

		}() {
      // queue 关闭了 跳出整个 updateUnfinishedWorkLoop() function
			return
		}
	}
}

```



### implement function

看完了初始化common work queue function 后接下来看看核心的功能。
[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/queue.go)

#### Add

```
// Add marks item as needing processing.
func (q *Type) Add(item interface{}) {
  // lock锁
	q.cond.L.Lock()
  // 离开时解锁
	defer q.cond.L.Unlock()
  // 如果queue 关闭就离开
	if q.shuttingDown {
		return
	}
  // 如果 dirty set 里面已经有了表示物件已经储存过但还没被处理那就离开
	if q.dirty.has(item) {
		return
	}
  // metric 不解释
	q.metrics.add(item)
  // 放入 dirty set ，表示物件等待处理
	q.dirty.insert(item)
  // 物件如果在 processing set 就退出
	if q.processing.has(item) {
		return
	}
  //加入queue
	q.queue = append(q.queue, item)
  //告知其他thread可以來取物件了
	q.cond.Signal()
}

```



这边看似很简单实际上有点复杂，有几种状态需要特别用图片来解释。

1. 如果queue 关闭了就不能放入物件
2. 如果dirty set 内有这个物件，表示谁都还没处理过物件，那这个物件就不能放到dirty 里面里面
   - 换句话说diry set 表示物件放进去queue 过但还没有被处理
3. 如果processing set 内有这个物件表示，有人正在处理这个物件，新进来的就先丢到dirty set 里面吧

其中2 , 3 较难理解，我透过图片来帮助我们了解状况。

![img](assets/dtSGaSG.jpg)

基本上2 , 3 的逻辑如图所示，比较有问题的应该是在processing set 有东西的时候，还要放入item 为什么不会放到queue 里面去呢？这会在后面的分析中看到！

#### Get

[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/queue.go)

```
// Get blocks until it can return an item to be processed. If shutdown = true,
// the caller should end their goroutine. You must call Done with item when you
// have finished processing it.
func (q *Type) Get() (item interface{}, shutdown bool) {
  // lock 锁
	q.cond.L.Lock()
  // 离开function 还回lock
	defer q.cond.L.Unlock()
  // 如果 queue 长度=0代表 queue 中没东西，而且 queue 状态为启动的状态，这里会开始睡觉等别人通知说可以来取货再醒来
	for len(q.queue) == 0 && !q.shuttingDown {
		q.cond.Wait()
	}
  //如果醒来后发现 queue 中长度为0而且 queue 状态为关闭的那就退出告诉 caller 说 queue 已经关闭了
	if len(q.queue) == 0 {
		// We must be shutting down.
		return nil, true
	}
    
  //取出 queue 列队中第一个元素，并且让后面的元素往前推进一个
	item, q.queue = q.queue[0], q.queue[1:]
    
  //metric不解釋
	q.metrics.get(item)

  // 把拿出來item丟入processing set 里面，表示正在处理。
	q.processing.insert(item)
  // dirty set 刪除 item 表示有人已经把 item 拿走，准許再把相同的 item 放進入
	q.dirty.delete(item)
  // 回传 queue 列队中第一个元素，并且告知使用者 queue 有没有关闭
	return item, false
}

```



这里Get 的逻辑十分简单，使用者透过Get 就能拿到queue 里面第一笔资料，若是queue 中没有资料会pedding 等到有人通知来拿资料才会醒来，此外若是Get function 告诉使用者说queue 已经关闭了，那么使用者应该把处理这段的thread 关掉。

#### Done

这边是告诉common work queue 说我们已经做完了，可以把processing set 所标记的资料删除啰，那我们就深入source code 来看看kubernetes 是怎么处理的吧！

```
// Done marks item as done processing, and if it has been marked as dirty again
// while it was being processed, it will be re-added to the queue for
// re-processing.
func (q *Type) Done(item interface{}) {

	// lock 锁
	q.cond.L.Lock()
    
  // 离开function 还回lock
	defer q.cond.L.Unlock()

  //metric不進行說明
	q.metrics.done(item)

  //从processing set 刪除，表示我們已經做完了！
	q.processing.delete(item)
  //如果 dirty set 里面还有的话我们就把他加入 queue 中让他回去排队等待处理
	if q.dirty.has(item) {
		q.queue = append(q.queue, item)
    // 告诉其他thread该醒了，来拿新的item啰！
		q.cond.Signal()
	}
}

```



流程大致上如下图所示
![img](assets/VH93kup.jpg)

#### Len

主要回传给使用者，现在queue 长度为多少。

```
// Len returns the current queue length, for informational purposes only. You
// shouldn't e.g. gate a call to Add() or Get() on Len() being a particular
// value, that can't be synchronized properly.
func (q *Type) Len() int {
  // lock 锁
	q.cond.L.Lock()
    
  // 离开function 还回lock
	defer q.cond.L.Unlock()
    
  //回传目前 queue 的长度
	return len(q.queue)
}

```



#### ShutDown

关闭queue ， queue 不再接受add() ，使用者只能从queue 中取值直到取完为止。

```
// ShutDown will cause q to ignore all new items added to it. As soon as the
// worker goroutines have drained the existing items in the queue, they will be
// instructed to exit.
func (q *Type) ShutDown() {
	// lock 锁
  q.cond.L.Lock()
    
  // 离开function 还回lock
	defer q.cond.L.Unlock()
    
  //设定queue为关闭状态
	q.shuttingDown = true
    
  //告知所有要取 queue 的人说，我要关了，快来取值。
	q.cond.Broadcast()
}

```



#### ShuttingDown

查询当前queue 是否关闭

```
func (q *Type) ShuttingDown() bool {
  // lock 锁
	q.cond.L.Lock()
    
  // 离开function 还回lock
	defer q.cond.L.Unlock()

  // 回传queue是否关闭
	return q.shuttingDown
}

```



## 小结

本章讲述了kubernetes common work queue 的底层实作方法，接下来还会有两篇介绍基于common work queue的rate limiters queue 以及delaying queue ，从中我们可以了解kubernetes controller 监听到etcd 变化的物件后如何把变化的物件丢入queue 中等待其他人取出并处理，相关业务逻辑，如果文中有错希望大家不吝啬提出，让我们互相交流学习。
