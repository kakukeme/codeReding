本文所有的source code 基于kubernetes 1.21 版本（终于版更了，这部分跟1.19 没什么差别），所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

本篇文章将介绍kubelet 如何cache pod status 有助于我们后续在拆解kubelet 的部分功能，cache 的实作也相当的简单，如果对于kubernetes 其他cache 也感兴趣的朋友欢迎看看笔者之前写的文章[Kubernetes util tool 使用cache LRU 定格一瞬间](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/utils/kubernetes-utils-tool-lru/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)、[Kubernetes Indexers local cache 之美（I）](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/indexer/kubernetes-indexers-local-cache-1/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)、[Kubernetes Indexers local cache 之美（II）](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/indexer/kubernetes-indexers-local-cache-2/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)，其中包含kubernetes 使用cache 的各式情境。

## interface

Cache interface 定义了两种获取PodStatus 的方法：一种是非阻塞的Get()另外一种为阻塞GetNewerThan() 方法，分别用于不同的情境。
另外也定义了Set 以及Delete 的方法用来新增pod status 以及删除对应的pod status 以及透过UpdateTime 确保cache 的新鲜度，我们就来看看interface 实际上对应到的code 。

```
type Cache interface {
	Get(types.UID) (*PodStatus, error)
	Set(types.UID, *PodStatus, error, time.Time)
	// GetNewerThan is a blocking call that only returns the status
	// when it is newer than the given time.
	GetNewerThan(types.UID, time.Time) (*PodStatus, error)
	Delete(types.UID)
	UpdateTime(time.Time)
}

```



了解cache 定义的interface 后就来看看哪个物件实作interface 啰。

## struct

cache struct 物件实作了cache interface 。

```

type cache struct {
	
	lock sync.RWMutex                    //确保资料的一致性读多写少，使用rw锁
	
	pods map[types.UID]*data               //使用 map 储存 key 为 types.UID value为 pod status data 
	
	timestamp *time.Time                    //透过权域的timestamp来确认资料的新鲜度

	subscribers map[types.UID][]*subRecord    //透过 map 纪录哪一个 type.UID 目前是谁监听
}


//data 资料结构储存 pod status 
type data struct {
	
	status *PodStatus                        //储存 pod status 
	// Error got when trying to inspect the pod.
	err error                                //储存 pod inspect 错误
	
	modified time.Time                        //上一次 pod 被修改的时间
}


//透过 subRecord 可以回传给监听者
type subRecord struct {
	time time.Time
	ch   chan *data                            //透过 channel 回传 pod status
}

```



了解了资料结构后，我们来看一下怎么把cache 建立起来。

## New function

```
// NewCache creates a pod cache.
func NewCache() Cache {
	//简单的初始化 cache 会用到的 map
	return &cache{pods: map[types.UID]*data{}, subscribers: map[types.UID][]*subRecord{}}
}

```



接着了解一下cache 物件如何实作cache interface 的需要吧！

## impliment

Set 会设置Pod 的PodStatus 到cache 中

### set

```
func (c *cache) Set(id types.UID, status *PodStatus, err error, timestamp time.Time) {
	//防止竞争上锁
	c.lock.Lock()
	defer c.lock.Unlock()
	//最后通知所有的订阅者哪个 types.UID 发生了变化
	defer c.notify(id, timestamp)
	//设定 cache 对应的资料，透过 types.UID 对应到 PodStatus
	c.pods[id] = &data{status: status, err: err, modified: timestamp}
}

// 如果满足要求，则通知为具有给定 id 的 pod 发送通知。请注意，调用者应该获取锁。
func (c *cache) notify(id types.UID, timestamp time.Time) {
	//取得监听某个 types.UID 的 subRecord slice 物件
	//slice 的长度表示有多人在监听这个 types.UID
	list, ok := c.subscribers[id]

	//如果在 map 中找不到对应的 subRecord slice 物件表示没有人在监听
	if !ok {
		// No one to notify.
		return
	}
    
	newList := []*subRecord{}

	//取出 subRecord slice 的每个元素（subRecord）
	for i, r := range list {
		//如果该 subRecord 要的资料在 cache 内是不新鲜（透过 timestamp 进行比对。当前 cache 的 timestamp 太旧、subRecord 想要的 timestamp 较新）    
		if timestamp.Before(r.time) {
			// 那些追踪较新资料的追踪者需要保留起来，下次 cache 更新 timestamp 与资料的时候可以通知那些追踪者。
			newList = append(newList, list[i])
			continue
		}
		//反之 subRecord 追踪的元素对于 cache 来说是新鲜的资料（透过 timestamp 进行比对。当前 cache 的 timestamp 比 subRecord 想要的 timestamp 要新），就透过 channel 回传
		r.ch <- c.get(id)
		//关闭channel
		close(r.ch)
	}
	//检查 newlist 长度，若长度为零代表没有遗留追踪较新资料的观察者，可以把观察这笔资料的所有观察者清除。
	//反之更新 追踪较新资料的观察者到追踪特定 types.UID 到 map 中
	if len(newList) == 0 {
		delete(c.subscribers, id)
	} else {
		c.subscribers[id] = newList
	}
}

```



### get

输入type id 取的某一个pod status 当前状态，要注意这是一个nonblock 操作。

```
func (c *cache) Get(id types.UID) (*PodStatus, error) {
	//防止竞争上锁
	c.lock.RLock()
	defer c.lock.RUnlock()
    
	//透过 types.UID 取得对应的 pod status
	d := c.get(id)
	return d.status, d.err
}

func (c *cache) get(id types.UID) *data {
	//从 map 中透过 types.UID 找到对应的资料
	d, ok := c.pods[id]
	//如果没有找到的话就建立一个预设的资料回传		
	if !ok {
		return makeDefaultData(id)
	}
    //如果有找到直接回传 map 中对应的资料	
    return d
}
//建立一个空资料的 pod status
func makeDefaultData(id types.UID) *data {
	return &data{status: &PodStatus{ID: id}, err: nil}
}

```



### GetNewerThan

透过GetNewerThan function 我们可以传入types.UID 以及minTime (用来对比新鲜度)可以得到对应的pod status ，要注意它是一个block 操作(直到取的pod status 为止)。

```
func (c *cache) GetNewerThan(id types.UID, minTime time.Time) (*PodStatus, error) {
	//得到某一个 types.UID 对应的资料变化的 channel
	ch := c.subscribe(id, minTime)
	//从 channel 取得资料变化    
	d := <-ch
	//回传资料状态，以及错误讯息 
	return d.status, d.err
}

func (c *cache) subscribe(id types.UID, timestamp time.Time) chan *data {
	//建立 channel 以供后续传输资料变化
	ch := make(chan *data, 1)
	//防止竞争上锁
	c.lock.Lock()
	defer c.lock.Unlock()
    
	//透过 id 以及 timestamp 查询 cache 中对应的资料 ，若当时 cache 的新限度满足客户端的需求，就能早到资料
	d := c.getIfNewerThan(id, timestamp)
	//如果找得到资料就回传给 channel    
	if d != nil {
		ch <- d
		return ch
	}
	// 找不到资料就会加入 cache 某一 types.UID 的 subscribers 行列，等到有资料后可以透过 channel 通知使用者。
	c.subscribers[id] = append(c.subscribers[id], &subRecord{time: timestamp, ch: ch})
    
	//回传channel以供后续资料传输使用 
	return ch
}

func (c *cache) getIfNewerThan(id types.UID, minTime time.Time) *data {
	//透过 type uid 从 map 取处得对应的 pod status 
	d, ok := c.pods[id]

	//透过 timestamp 检查 cache 是否准备好提供服务，以及透过 cache timestamp 对比输入物件的 timestamp 检查当前的 cache 新鲜度
	//情境1:假设没有 cache 没有设定timestamp就代表无法比对新鲜度。 globalTimestampIsNewer 为 false
	//情境2:假设 cache 最后一次捕捉到的资料是 10:00 的资料，使用者要求 11:00 的资料，表示 cache 不新鲜。 globalTimestampIsNewer 为 false
	//情境3:假设 cache 最后一次捕捉到的资料是 11:00 的资料，使用者要求 10:00 的资料，表示 cache 目前储存的资料是新鲜的。 globalTimestampIsNewer 为 true
	globalTimestampIsNewer := (c.timestamp != nil && c.timestamp.After(minTime))

	//判断 cache 中是否有对应的 pod 以及 cache 新不新鲜
	//如果 cache 没有对应的 pod 但是 cache 的保存资料是新鲜的 ，就回传一个 default 的资料
	if !ok && globalTimestampIsNewer {
		return makeDefaultData(id)
	}
	
	//如果 cache 有对应的 pod 同时 cache 保存的新鲜度是足够的话就回传资料
	if ok && (d.modified.After(minTime) || globalTimestampIsNewer) {
		return d
	}

	//cache 不存在以及 cache 保存的不够新鲜度回传 nil
	return nil
}

```



### Delete

某个podstatus 已经不需要了，所以透过delete function 清理掉。

```
func (c *cache) Delete(id types.UID) {
	//防止竞争加锁
	c.lock.Lock()
	defer c.lock.Unlock()
	//透过 types.UID 删除 map 中对应的资料
	delete(c.pods, id)
}

```



### UpdateTime

更改cache 的timestamp 并通知所有的订阅者，可以更新pod status 给订阅者。

```
func (c *cache) UpdateTime(timestamp time.Time) {
	c.lock.Lock()
	defer c.lock.Unlock()
	c.timestamp = &timestamp
	// Notify all the subscribers if the condition is met.
	for id := range c.subscribers {
		c.notify(id, *c.timestamp)
	}
}

```



## 小结

kubelet 使用cache 来储存pod status 的状态，并且透过timestamp 确保cache 的新鲜度，若是cache 更新timestamp 也会通知subscribers 订阅的pod status 当前的状态。
此外也提供两种获取PodStatus 的方法：一种是非阻塞的Get()另外一种为阻塞GetNewerThan() 方法，分别用于不同的情境。

本篇文章虽然篇幅不长知识量也不大，但作为后续分析系统每个元件都是重要的一份子呢xD，如果文中有错希望大家不吝啬提出，让我们互相交流学习。
