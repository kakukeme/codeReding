首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

本篇文章将介绍隐藏在kubelet 的WorkQueue ，后续在拆解kubelet 的部分功能会用到WorkQueue ，该Worker Queue 仅为简单的queue 实作，如果对kubernetes 其他较为复杂的Queue 实作有兴趣的朋友欢迎参考笔者早期的三篇文章分别是[Kubernetes RateLimite work queue 设计真d不错](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/queue/rating/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)、[Kubernetes delaying work queue 设计真d不错](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/queue/delaying/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)以及[Kubernetes common work queue 设计真d不错](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/queue/common/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)。

## WorkQueue

WorkQueue interface 定义一系列的方法，实作的物件需要透过时间戳timestamp 记对type.UID 进行排队，并且可以取的目前在Queue 中的所有work 。

```
type WorkQueue interface {
	// 回传所有准备好的 type.UID 。
	GetWork() []types.UID
	// 插入新 types.UID 到 queue 中
	Enqueue(item types.UID, delay time.Duration)
}

```



### struct

basicWorkQueue 实作了WorkQueue 我们来看一下他的资料结构。

```
var _ WorkQueue = &basicWorkQueue{}

type basicWorkQueue struct {
  clock clock.Clock					//用来记录当前时间
	lock  sync.Mutex					//避免map中的物件竞争
	queue map[types.UID]time.Time		//用来储存所有工作以及延迟时间
}

```



New function 也非常简单，简单的建立map 以及透过使用者传入clock 当作WorkQueue 当前的时间。

```
// NewBasicWorkQueue returns a new basic WorkQueue with the provided clock
func NewBasicWorkQueue(clock clock.Clock) WorkQueue {
	queue := make(map[types.UID]time.Time)
	return &basicWorkQueue{queue: queue, clock: clock}
}

```



### implement

```
func (q *basicWorkQueue) GetWork() []types.UID {
	//防止竞争加锁
	q.lock.Lock()
	defer q.lock.Unlock()

	//取得目前时间
	now := q.clock.Now()
	var items []types.UID
	//递回取得每个 type UID    
	for k, v := range q.queue {
		//判断每个 type UID 延迟时间是否已经到了，如果延迟时间到了从 map 中移除并且加入已经延迟时间已到的 slice 中。
		if v.Before(now) {
			items = append(items, k)
			delete(q.queue, k)
		}
	}
	//回传已延迟完的工作 
	return items
}

func (q *basicWorkQueue) Enqueue(item types.UID, delay time.Duration) {
  //防止竞争加锁
	q.lock.Lock()
	defer q.lock.Unlock()
  // type UID 设定要延迟多久才开始工作
	q.queue[item] = q.clock.Now().Add(delay)
}


```



### test case

篇幅感觉太短了xD，来介绍一下这个function 的测试案例。

```
func TestGetWork(t *testing.T) {
	//建立 basicWorkQueue 以及当假的 clock 
	q, clock := newTestBasicWorkQueue()
	//queue加入 foo1 延迟时间 -1 分钟    
	q.Enqueue(types.UID("foo1"), -1*time.Minute)
	//queue加入 foo2 延迟时间 -1 分钟
	q.Enqueue(types.UID("foo2"), -1*time.Minute)
	//queue加入 foo3 延迟时间 1 分钟
	q.Enqueue(types.UID("foo3"), 1*time.Minute)
	//queue加入 foo4 延迟时间 - 分钟
	q.Enqueue(types.UID("foo4"), 1*time.Minute)

	//預期取的可以從 queue 中拿到 "foo1" "foo2"
	expected := []types.UID{types.UID("foo1"), types.UID("foo2")}
    
    
    
  //比对预期得结果与实际的结果	， GetWork 从 queue 中拿资料，会取的延迟时间到的资料
	//因为 foo1 foo2 延迟时间为 -1 分钟，所以 getwork 可以拿到 foo1 foo2 的资料
	compareResults(t, expected, q.GetWork())

	//比对预期得结果与实际的结果	，此时 foo1 foo2 已经从 queue 中 pop 出去了
	//再去拿资料的时候由于其他资料还没到延迟时间所以 get work 会是空的
	compareResults(t, []types.UID{}, q.GetWork())
    
    
	//将时间 mock 往后调整一个小时
	clock.Step(time.Hour)
    
	//预期取的可以从 queue 中拿到 "foo3" "foo4"
	expected = []types.UID{types.UID("foo3"), types.UID("foo4")}
    
	//此时 time 往后调整一个小时了
	//比对预期得结果与实际的结果	， GetWork 从 queue 中拿资料，会取的延迟时间到的资料
	//因为 foo3 foo4 延迟时间为 1 分钟，所以 getwork 可以拿到 foo3 foo4 的资料
	compareResults(t, expected, q.GetWork())
    
	//比对预期得结果与实际的结果	，此时 foo3 foo4 已经从 queue 中 pop 出去了
	//再去拿资料的时候queue已经空了所以 get work 时会拿到空的资料
	compareResults(t, []types.UID{}, q.GetWork())
}


//建立 queue 以及假的 clock (可以 mock 用)
func newTestBasicWorkQueue() (*basicWorkQueue, *clock.FakeClock) {
	//建立假的 clock 可以 mock 用
	fakeClock := clock.NewFakeClock(time.Now())

	//建立 basicWorkQueue 物件设定 fake clock
	wq := &basicWorkQueue{
		clock: fakeClock,
		queue: make(map[types.UID]time.Time),
	}
	return wq, fakeClock
}


/比对预期得结果与实际的结果
func compareResults(t *testing.T, expected, actual []types.UID) {
	//建立一个 set 
	expectedSet := sets.NewString()
    
	//将预期的答案放入 set
	for _, u := range expected {
		expectedSet.Insert(string(u))
	}
	
	//用来储存实际上queue回传资料用的set 
	actualSet := sets.NewString()
	//将实际上queue回传资料放入 set
	for _, u := range actual {
		actualSet.Insert(string(u))
	}
    
    
	//判断 预期的答案 与实际的答案是否一致。  
	if !expectedSet.Equal(actualSet) {
		t.Errorf("Expected %#v, got %#v", expectedSet.List(), actualSet.List())
	}
}


```



## 小结

kubelet 中所采用的WorkQueue 实作上非常的简单，把需要延迟行为的type UID 物件放入queue 中，取出时再判断queue 中哪些延迟时间已经到达整成slice ，再回传给使用者。本篇文章虽然篇幅不长知识量也不大，但作为后续分析系统每个元件都是重要的一份子呢xD，如果文中有错希望大家不吝啬提出，让我们互相交流学习。
