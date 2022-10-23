首先本文所以source code 基于kubernetes 1.19 版本，所有source code 的为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

## kubernetes work queue

在前一篇kubernetes common work queue 设计真d不错一文中分享kubernetes work queue最基础的实作方式，再复习一次！Kubernetes 为什么要实践一个work queue 呢？

就我们所知kubernetes 是用go 撰写应该可以使用channel 的机制直接将物件送给要用的元件(thread)啊，原因其实非常简单，go channel 的设计功能非常单一无法满足kubernetes 所要的场景，例如带有延迟时间物件需要根据延迟时间排序的queue ，例如限制物件取出速度的queue 。

![img](assets/kubernetes-controller-arch-20221022194539363.png)

图片来源：[How to Create a Kubernetes Custom Controller Using client-go](https://itnext.io/how-to-create-a-kubernetes-custom-controller-using-client-go-f36a7a7536cc)

上图引用了[How to Create a Kubernetes Custom Controller Using client-go](https://itnext.io/how-to-create-a-kubernetes-custom-controller-using-client-go-f36a7a7536cc)的controller 架构图可以看到在sharedindexinformer 内有引用到这个元件，这个元件实际被定义在kubernetes 的client-go library 中。

在第五步骤与第六步骤之间透过queue 不只解偶了上下层的耦合关系同时Queue 有达到了消峰填谷的作用，当观察的物件一直送资料进来不会因为我们业务逻辑处理得太慢而卡住，资料会保留在queue 中直到被取出。

之前有提到了两种queue ，分别是rate limiters queue 以及delaying queue ，上一章节介绍完Kubernetes 通用的common queue ，本篇文章会从delaying queue 开始探讨。

## delaying queue

kubernetes source code 设计得非常精美，我们可以先从interface 定义了哪些方法来推敲实作这个interface 的物件可能有什么功能。

### interface

[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/delaying_queue.go)

```
// DelayingInterface is an Interface that can Add an item at a later time. This makes it easier to
// requeue items after failures without ending up in a hot-loop.
// 原生的注解写得非常棒了，大致上意思为一个物件处理失败，如果很快地物件在被处理一次失败的可能性还是很高会造成
// hot-loop，所以让物件等待一下在排队进入 queue 就是 delaying queue 的用意
type DelayingInterface interface {
	Interface    //嵌入了common work queue的interface，delaying queue 也是common queue 的一中
	
	AddAfter(item interface{}, duration time.Duration)    //表示物件需要等待多久才能被放入 queue 中
}

```



看完了抽象的定义之后，必须要回过来看delaying queue 实际物件定义了哪些属性

### struct

[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/delaying_queue.go)

```
// delayingType wraps an Interface and provides delayed re-enquing
type delayingType struct {
	Interface    //嵌入了一個common queue

	// clock tracks time for delayed firing
	clock clock.Clock    //用来比对物件延迟时间

	// stopCh lets us signal a shutdown to the waiting loop
	stopCh chan struct{}    //异步退出用
	// stopOnce guarantees we only signal shutdown a single time
	stopOnce sync.Once    //异步退出用，保证退出只会被呼叫一次

	// heartbeat ensures we wait no more than maxWait before firing
	heartbeat clock.Ticker    //定时器，定时唤醒thread处理物件

	// waitingForAddCh is a buffered channel that feeds waitingForAdd
	waitingForAddCh chan *waitFor //用以添加延迟物件的channel

	// metrics counts the number of retries
	metrics retryMetrics    //用以纪录重试的metric
}

```



刚刚上面有一个疑点那就type waitFor 到底是什么
我们先来看看type waitFor 的结构
[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/delaying_queue.go)

```
// waitFor holds the data to add and the time it should be added
// 如果需要延迟的物件都会被转换成这个类型
type waitFor struct {
	data    t            // t 在common queue介绍过，为一个泛行表示什么都接受的物件
	readyAt time.Time    //在什么时间加入到queue中的
	// index in the priority queue (heap)
	index int            // index会用在后面的排序，延迟时间较小的排前面（用heap排序）
}

```



#### heap 排序

由于放入dealying queue 的物件，有的可能要延迟1s 有的可能要延迟2ms等等， dealying queue 如何保证延迟较小的物件先放入queue 呢？

delaying queue 透过heap 进行排序，底下让展开排序的实作方式。

[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/delaying_queue.go)

```

// waitForPriorityQueue implements a priority queue for waitFor items.
//
// waitForPriorityQueue implements heap.Interface. The item occurring next in
// time (i.e., the item with the smallest readyAt) is at the root (index 0).
// Peek returns this minimum item at index 0. Pop returns the minimum item after
// it has been removed from the queue and placed at index Len()-1 by
// container/heap. Push adds an item at index Len(), and container/heap
// percolates it into the correct location.
type waitForPriorityQueue []*waitFor    //waitForPriorityQueue 这个类型实现了 heap interface ，排序的对象为 waitFor

//实现heap interface 的len方法，取出heap当前的长度。
func (pq waitForPriorityQueue) Len() int {
	return len(pq)
}

//实现 heap interface 的 Less 方法，确认在 waitForPriorityQueue 的第 i 个元素是否比第 j 个元素小
//若是第 i 个元素比第 j 个元素小就交换，因为我们希望，因为我们希望越小的排越前面。
func (pq waitForPriorityQueue) Less(i, j int) bool {
	return pq[i].readyAt.Before(pq[j].readyAt) // 比的是时间
}

//实作 heap interface 的 swap ，实作 i j 交换
func (pq waitForPriorityQueue) Swap(i, j int) {
	pq[i], pq[j] = pq[j], pq[i]
	pq[i].index = i
	pq[j].index = j
}

//实作 heap interface 的 Push ，向 heap 添加物件
func (pq *waitForPriorityQueue) Push(x interface{}) {
	n := len(*pq)
	item := x.(*waitFor)
	item.index = n              //新加入的物件会记录当前自己的位置
	*pq = append(*pq, item)     //新加入的物件排到heap的最后面
}

//实作 heap interface 的 Pop ，从 heap 的尾巴弹出最后一个物件。
func (pq *waitForPriorityQueue) Pop() interface{} {
	n := len(*pq)
	item := (*pq)[n-1] 
	item.index = -1
	*pq = (*pq)[0:(n - 1)]    //缩小heap，移除最后一个物件
	return item
}

//回传heap第一个物件，延迟时间最短的那一个物件
func (pq waitForPriorityQueue) Peek() interface{} {
	return pq[0]
}

```



看完了资料结构我们接着来看delaying queue 实作的方法，与初始化方法！

### new function

[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/delaying_queue.go)

```
//给心跳用最久10秒delaying queue会检查一次
const maxWait = 10 * time.Second

//传入clock与common queue以及纪录这个物件metric的名字，这是一个不公开的方法，有其他封装好的 new function 可以用
func newDelayingQueue(clock clock.Clock, q Interface, name string) *delayingType {
	ret := &delayingType{
		Interface:       q,
		clock:           clock,
		heartbeat:       clock.NewTicker(maxWait),
		stopCh:          make(chan struct{}),
		waitingForAddCh: make(chan *waitFor, 1000),
		metrics:         newRetryMetrics(name),
	}
  // 启动一个 thread 检测有没有 wiatfor 物件在等待进入 queue，稍后会展开分析。
	go ret.waitingLoop()
	return ret
}

// 不同的封裝方式，不提 （设定 metric 的 name 为空）
func NewDelayingQueue() DelayingInterface {
	return NewDelayingQueueWithCustomClock(clock.RealClock{}, "")
}

//不同的封裝方式，不提 （设定 metric 的 name 为空，可传入自己实作的 common queue ）
func NewDelayingQueueWithCustomQueue(q Interface, name string) DelayingInterface {
	return newDelayingQueue(clock.RealClock{}, q, name)
}

//不同的封裝方式，不提 （可传入 metric 的 name ）
func NewNamedDelayingQueue(name string) DelayingInterface {
	return NewDelayingQueueWithCustomClock(clock.RealClock{}, name)
}

//不同的封裝方式，不提 （可传入 metric 的 name 以及 clock ）
func NewDelayingQueueWithCustomClock(clock clock.Clock, name string) DelayingInterface {
  // NewNamed 是前一章节提到建立 common queue的方法
	return newDelayingQueue(clock, NewNamed(name), name)
}


```



### implement function

看完了初始化delaying queue function 后接下来看看核心的功能。
[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/delaying_queue.go)

#### AddAfter

使用者要放入有延迟的物件需要呼叫这个function，带入要延迟的物件，以及该物件要延迟多久。

```
// AddAfter adds the given item to the work queue after the given delay
func (q *delayingType) AddAfter(item interface{}, duration time.Duration) {
	// don't add if we're already shutting down
	if q.ShuttingDown() {    //如果queue关闭了就不能放入
		return
	}

	q.metrics.retry()        //metric不解釋

	// immediately add things with no delay
	if duration <= 0 {      //如果延迟时间小于等于0表示不用延迟
		q.Add(item)           //直接丟入common queue中
		return
	}

	select {
	case <-q.stopCh:            //因为可能会组塞在 waitingForAddCh 透过 stop 保证退出？
		// unblock if ShutDown() is called
  // 要延迟的物件会封装成 waitFor 型态并且方入 channel 等待处理
	case q.waitingForAddCh <- &waitFor{data: item, readyAt: q.clock.Now().Add(duration)}:    
	}
}

```



#### waitingLoop

这边是delaying queue的主要逻辑，执行检查waitingForAddCh channel 有没有延迟物件，取出延迟物件看延迟时间是达到选择加入Heap 或是queue，以及接收心跳包。

```
// waitingLoop runs until the workqueue is shutdown and keeps a check on the list of items to be added.
func (q *delayingType) waitingLoop() {
	defer utilruntime.HandleCrash()

	// Make a placeholder channel to use when there are no items in our list
	never := make(<-chan time.Time)      //我不是很定他的用意...可以看到 nerver channel 又换一个别名表示

	// Make a timer that expires when the item at the head of the waiting queue is ready
	var nextReadyAtTimer clock.Timer                //当 heap 吐出一个延迟物件时透过这个 timer 延迟

	waitingForQueue := &waitForPriorityQueue{}     // heap 物件
	heap.Init(waitingForQueue)                    // heap初始化

	waitingEntryByData := map[t]*waitFor{}        //用来防止同一个物件重复放入，如果有重复的物件就更新延迟时间
    
    
	for {
    //如果queue关闭就离开
		if q.Interface.ShuttingDown() {
			return
		}
    //标记现在时间
		now := q.clock.Now()

		// 如果在 heap 裡面有東西
		for waitingForQueue.Len() > 0 {
      //拿出第一個在 heap 的物件
			entry := waitingForQueue.Peek().(*waitFor)
      //如果现在时间还没达到物件要等待的时间就退出
			if entry.readyAt.After(now) {
				break
			}
      //如果现在时间达到物件要等到的时间，将物件从heap弹出
			entry = heap.Pop(waitingForQueue).(*waitFor)
      //加到queue中
			q.Add(entry.data)
      //刪除set存储的物件
			delete(waitingEntryByData, entry.data)
		}

		// Set up a wait for the first item's readyAt (if one exists)
		nextReadyAt := never        //在上面有提到过nerver channel 只换成这个名字，不知道用意为何
    // 如果在 heap 裡面有東西
		if waitingForQueue.Len() > 0 {
      //  若是前一个物件的计时器有残留物就清除前一个物件的计时器
			if nextReadyAtTimer != nil {
				nextReadyAtTimer.Stop()
			}
      //拿出第一个在 heap 的物件
			entry := waitingForQueue.Peek().(*waitFor)
      //看物件延迟多久
			nextReadyAtTimer = q.clock.NewTimer(entry.readyAt.Sub(now))
      //当物件延迟时间到了发通知
			nextReadyAt = nextReadyAtTimer.C()
		}

		select {
    // queue关闭
		case <-q.stopCh:
			return
    // 定时被心跳唤醒
		case <-q.heartbeat.C():
		// continue the loop, which will add ready items
    // 当收到物件延迟时间到了发通知
		case <-nextReadyAt:
		// continue the loop, which will add ready items
    // 当有人放需要延迟的物件进queue中
		case waitEntry := <-q.waitingForAddCh:
      //如果新放入的物件还没超过延迟时间
			if waitEntry.readyAt.After(q.clock.Now()) {
        //放入heap中
				insert(waitingForQueue, waitingEntryByData, waitEntry)
			} else {
        //已经到了延迟时间直接放入queue
				q.Add(waitEntry.data)
			}

      //一次取光用
			drained := false
			for !drained {
				select {
        // 一次把延迟物件的channel取干净
				case waitEntry := <-q.waitingForAddCh:
          //如果新放入的物件还没超过延迟时间
					if waitEntry.readyAt.After(q.clock.Now()) {
            //放入heap中
						insert(waitingForQueue, waitingEntryByData, waitEntry)
					} else {
            //已经到了延迟时间直接放入queue
						q.Add(waitEntry.data)
					}
				default:
          // 保证会退出这个取光的loop
					drained = true
				}
			}
		}
	}
}

```



#### insert

在waitingLoop 有使用到insert 这个function ，他的实作也相当的简单，简单的来说就是把waitfor 的物件放到Heap 中，我们来看看他是如何实作的。

```
// insert adds the entry to the priority queue, or updates the readyAt if it already exists in the queue
func insert(q *waitForPriorityQueue, knownEntries map[t]*waitFor, entry *waitFor) {
    //先判断加入的物件有没有重复的
    existing, exists := knownEntries[entry.data]
    //若是有重复的话
    if exists {
    	// 跟之前放入的物件比较哪个延迟时间比较短
    	// 若是现在要放入的物件比较短的话就更新 set 中的物件延迟时间
        if existing.readyAt.After(entry.readyAt) {
            existing.readyAt = entry.readyAt
            heap.Fix(q, existing.index)
        }
        return
    }
    //如果 set 没有重复的话就直接加到 heap 中，以及使用 set 纪录 heap 有这个物件。
    heap.Push(q, entry)
    knownEntries[entry.data] = entry
}

```



## 小结

本章讲述了kubernetes delaying work queue 的底层实作方法，接下来还会有几篇介绍基于common work queue 的rate limiters work queue 以及其他类型的work queue ，从中我们可以了解kubernetes controller 监听到etcd 变化的物件后如何把变化的物件丢入queue 中等待其他人取出并处理，相关业务逻辑，如果文中有错希望大家不吝啬提出，让我们互相交流学习。
