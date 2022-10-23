

## LRU

今天先从kubernetes util tool 工具里面一把抓，抓出cache 这个工具要使用这个工局前我们要先了解 `LRU` 是什么，实际上很间单，我这边简单带过LRU 是什么。

LRU 全名Least Recently Used（最近最少使用策略），顾名思义是根据使用的记录来淘汰资料，A资料被存取过那A资料在未来被存取的机率相对来高。

LRU 整体结构是依靠Doubly linked list 作为CRUD 的后盾，我们可以看一下图来了解整体结构!

![img](assets/kubernetes-utils-cache.png)

1. 插入A， 将插入的物件Ａ移动到link list 的header
2. 插入B， 将插入的物件Ｂ移动到link list 的header
3. 插入C， 将插入的物件Ｃ移动到link list 的header
4. 读取A， 将刚刚读取的物件Ａ移动到link list 的header
5. 插入D， 将插入的物件D移动到link list 的header
6. 插入G， 将插入的物件G移动到link list 的header
7. 插入F， 将插入的物件F移动到link list 的header ，超出link list 的长度，将tail 的物件踢出！

基本上就这么简单!

> tips:
> 为什么要用Doubly linked list 而不是Singly linked list?
> 因为Doubly在已知位置插入和删除的复杂度为O（1）而Singly在已知位置插入和删除的复杂度为O（n）

## simplelru

Kubernetes LRUExpireCache 简单来说就是基于`github.com/hashicorp/golang-lru`，封装成有Expire 功能的LRU。
hashicorp 的LRU 基于 `hashicorp/golang-lru/simplelru` 封装成有thread safe 功能的LRU。

所以我们要了解的地方总共有三层，第一层kubernetes LRUExpireCache 第二层hashicorp 的LRU ，第三层hashicorp/golang-lru/simplelru 。

我们先从hashicorp/golang-lru/simplelru 作为入口开始了解整个架构。

### interface

```
// LRUCache is the interface for simple LRU cache.
type LRUCache interface {
	
	Add(key, value interface{}) bool                         //向 cache add 一個 key value paire ，如果發生逐出，需要回傳 true 同時更新 lru 狀態。

	
	Get(key interface{}) (value interface{}, ok bool)        //從 cache 中透過 key 去找對應的 value 同時更新 lru 狀態。

	
	Contains(key interface{}) (ok bool)                     //確認 cache 是否存在某一個 key 

	
	Peek(key interface{}) (value interface{}, ok bool)      //回傳 key 對應的 value ，而不更新 lru 狀態。

	
	Remove(key interface{}) bool                            //透過 key 刪除 cache 中指定的 物件

	
	RemoveOldest() (interface{}, interface{}, bool)         //刪除 cache 中最舊的物件

	
	GetOldest() (interface{}, interface{}, bool)            //取得 cache 中最舊的問間

	
	Keys() []interface{}                                    //從 cache 中取得所有的 key 從最舊到最新依序排列

	
	Len() int                                               //取得 cahce 當前長度


	Purge()                                                 //清除 cahce 所有資料
}

```



看完了LRU 的interface 后我们就可以往实作层面来看看，时做一个LRU 需要什么资料结构。

### struct

```

type EvictCallback func(key interface{}, value interface{})    //用於在 LRU 在 Evict entry 時call back 給使用者知道

// 實作了none thread safhe 的固定大小 LRU cahe 
type LRU struct {
	size      int                                    //cache size
	evictList *list.List                             //lru list
	items     map[interface{}]*list.Element          //紀錄 lru key 對應到的 value 
	onEvict   EvictCallback                          //當發生LRU Evict entry 時call back 給使用者知道
}

// LRU cache 所記錄的物件型態
type entry struct {
	key   interface{}
	value interface{}
}

```



可能有人会问说为什么会有一个map 在LRU 的资料结构中呢？

原因是从map 里面找资料的速度是O(1) 的，我们可以从map 中判断key 是否存在，如果存在的话只要更新map 中key 所对应的value ，并且将本来就存在Doubly linked list 的value 移动到header 。

简单看完资料结构，我们可以看看要怎么把这个物件叫起来，里面有没有偷偷藏什么不为人知的初始化呢？

### New Function

```
// NewLRU constructs an LRU of the given size
func NewLRU(size int, onEvict EvictCallback) (*LRU, error) {
	//由於是固定 size 的 cahce 所以 size 不能小於0
	if size <= 0 {
		return nil, errors.New("Must provide a positive size")
	}
	c := &LRU{
		size:      size,
		evictList: list.New(),                                //使用 go 內建的 list 資料結構
		items:     make(map[interface{}]*list.Element),
		onEvict:   onEvict,                                   //傳入EvictCallback 當發生 Evict 的時候可以通知使用者處理
	}
	return c, nil
}

```



看完了怎么建立一个LRU 的物件之后我们来看看他怎么实作LRU 的interface 吧！

### Add

```
// Add adds a value to the cache.  Returns true if an eviction occurred.
func (c *LRU) Add(key, value interface{}) (evicted bool) {
	// Check for existing item
	//檢查物件是否存在於 map 中，若是存在的話我們需要把 LRU 中 物件所在的位置移動到 list 的 header 
	//並且更新 物件 的值
	if ent, ok := c.items[key]; ok {    
		c.evictList.MoveToFront(ent)
		ent.Value.(*entry).value = value
		return false
	}

	//若是物件不存在於 map 當中，我們需要先建立物件在存放在 LRU 的 entry
	//並請把 entry 推送到 LRU list 的 header
	//最後更新 map 下次物件進來的時候可以直接從 map 判斷物件是否存在過，若是存在就直接更新 LRU list 
	ent := &entry{key, value}
	entry := c.evictList.PushFront(ent)
	c.items[key] = entry
        
	//更新完 LRU 後需要判斷 LRU 的長度是否超過預設值，超過的話就需要刪除 LRU 
    // list 最後一個 entry 並且更新對應的 map (過程都在c.removeOldest()，晚點會看到)
	evict := c.evictList.Len() > c.size
	
	if evict {
		c.removeOldest()
	}
	return evict
}

```



### Get

```

// Get looks up a key's value from the cache.
func (c *LRU) Get(key interface{}) (value interface{}, ok bool) {
    // 會先從 map 搜尋物件是否存在，不存在就不用拿拉xD
	if ent, ok := c.items[key]; ok {
            // 根據 LRU 演算法最近使用過的物件要推到 list 的 header 
		c.evictList.MoveToFront(ent)
		return ent.Value.(*entry).value, true
	}
	return
}

```



### Contains

```

// Contains checks if a key is in the cache, without updating the recent-ness
// or deleting it for being stale.
func (c *LRU) Contains(key interface{}) (ok bool) {
	_, ok = c.items[key]             //從 lru map 中查看有沒有我們指定的 key 
	return ok
}

```



### Peek

```
// Peek returns the key value (or undefined if not found) without updating
// the "recently used"-ness of the key.
func (c *LRU) Peek(key interface{}) (value interface{}, ok bool) {
	var ent *list.Element                            //先建立一個 element 等著承裝 LRU 的資料
	if ent, ok = c.items[key]; ok {                  //從 LRU map 中找到對應的資料後回傳
		return ent.Value.(*entry).value, true
	}
	return nil, ok
}

```



### Remove

```
// Remove removes the provided key from the cache, returning if the
// key was contained.
func (c *LRU) Remove(key interface{}) (present bool) {
    // 會先從 map 搜尋物件是否存在，不存在就不用拿拉xD
	if ent, ok := c.items[key]; ok {
            //  直接從 LRU list 與 map 移除物件(過程都在c.removeElement()，晚點會看到)
		c.removeElement(ent)
		return true
	}
	return false
}

```



### RemoveOldest

```
// RemoveOldest removes the oldest item from the cache.
func (c *LRU) RemoveOldest() (key interface{}, value interface{}, ok bool) {
	ent := c.evictList.Back()                      //取得LRU list 最最舊的資料
	//如果有找到資料就刪除最舊的資料
	if ent != nil {                           
		c.removeElement(ent)
		kv := ent.Value.(*entry)
		return kv.key, kv.value, true
	}
	return nil, nil, false
}

```



### GetOldest

```
// GetOldest returns the oldest entry
func (c *LRU) GetOldest() (key interface{}, value interface{}, ok bool) {
	ent := c.evictList.Back()                        //取得LRU list 最最舊的資料
	//如果有找到資料就回傳
	if ent != nil {
		kv := ent.Value.(*entry)
		return kv.key, kv.value, true
	}
	return nil, nil, false
}

```



### Keys

```
// Keys returns a slice of the keys in the cache, from oldest to newest.
func (c *LRU) Keys() []interface{} {
	//先建立一個 slice 等等用來 copy LRU 資料用
	keys := make([]interface{}, len(c.items))
	i := 0
	// 會從 LRU list 最後一個 entry 開始往前找，直到往前找到nil為止
	// 過程中會把 entry 的 key 列出來放置到 keys 的 slice 中
	// 但我有個疑問，不是有 map 嗎？直接拿 map 應該會比 LRU list 一個一格找要快吧？
	for ent := c.evictList.Back(); ent != nil; ent = ent.Prev() {
		keys[i] = ent.Value.(*entry).key
		i++
	}
	return keys
}

```



### Len

```
// Len returns the number of items in the cache.
func (c *LRU) Len() int {
	return c.evictList.Len()            //回傳 LRU list 長度
}

```



### removeOldest/removeElement

```
// removeOldest removes the oldest item from the cache.
func (c *LRU) removeOldest() {
	ent := c.evictList.Back()                    //從 LRU list  中取得最舊的資料
    
	//如果剛剛有找到存在的資料話就刪除物件
	if ent != nil {
		c.removeElement(ent)
	}
}

// removeElement is used to remove a given list element from the cache
func (c *LRU) removeElement(e *list.Element) {
	c.evictList.Remove(e)                        //刪除 LRU list 的資料
	kv := e.Value.(*entry)                       //把list.Element轉成entry
	delete(c.items, kv.key)                      //透過 entry key 刪除 LRU map 對應的 物件
	if c.onEvict != nil {
		c.onEvict(kv.key, kv.value)              //因為有物件被刪除了需要通知使用者
	}
}

```



看完了 `simplelru` 表示我们可以往下一个地方迈进，那就是 `hashicorp/golang-lru` 的实作，这边不难我们很快地看过去。

## hashicorp/golang-lru

先来看`hashicorp/golang-lru`的资料结构

### struct

```
// Cache is a thread-safe fixed size LRU cache.
type Cache struct {
	lru  simplelru.LRUCache           //組合了 simple lru 的功能
	lock sync.RWMutex                 //多了讀寫鎖
}

```



其实很简单xDD 单纯多了读写锁而已，接着来看怎么建构起这个物件的。

### New function

```
// New creates an LRU of the given size.
func New(size int) (*Cache, error) {
	return NewWithEvict(size, nil)                //最簡單的方法就是直接就入  cahce size ，不指定 Evict call  back function 
}

// NewWithEvict constructs a fixed size cache with the given eviction
// callback.
func NewWithEvict(size int, onEvicted func(key interface{}, value interface{})) (*Cache, error) {
	lru, err := simplelru.NewLRU(size,
	    simplelru.EvictCallback(onEvicted))       //這邊就是有指定 Evict call  back function  
                                                        //當發生 Evict 事件時會透過 call back function 通知調用者。
	if err != nil {
		return nil, err
	}
	c := &Cache{
		lru: lru,
	}
	return c, nil
}

```



看完了怎么新增这个物件后我们来看看，加了读写锁有什么改变吧

### RWMutex impliemnt

这个部分相单的简单，我只举几个作为例子有兴趣的营有可以到github 看其他的实作。

```
// Purge is used to completely clear the cache.
func (c *Cache) Purge() {
	c.lock.Lock()                        //全部移除前上鎖
	c.lru.Purge()                        //lru 移除
	c.lock.Unlock()                      //移除完解鎖
}

// Add adds a value to the cache.  Returns true if an eviction occurred.
func (c *Cache) Add(key, value interface{}) (evicted bool) {
	c.lock.Lock()                        //加入資料前上鎖
	evicted = c.lru.Add(key, value)      //呼叫 simple lru add function 
	c.lock.Unlock()                      //加完資料解鎖
	return evicted
}

// Get looks up a key's value from the cache.
func (c *Cache) Get(key interface{}) (value interface{}, ok bool) {
	c.lock.Lock()                        //拿資料前上鎖
	value, ok = c.lru.Get(key)           //呼叫 simple lru get function 
	c.lock.Unlock()                      //拿完資料解鎖
	return value, ok
}

//這裡可能有人會問  Get 這個 function 不是拿資料嗎？為什麼用 lock 而不是 Rlock????
//別忘了 LRU 在拿資料的時候會更新資料的熱度，讓最近取得過的資料移到 LRU 的第一位！

// Contains checks if a key is in the cache, without updating the
// recent-ness or deleting it for being stale.
func (c *Cache) Contains(key interface{}) bool {
	c.lock.RLock()                        //單純拿資料，不會動到其他資料用 rlock
	containKey := c.lru.Contains(key)     //呼叫 simple lru contain function
	c.lock.RUnlock()                      //拿完資料解鎖
	return containKey
}


```



几本上就做了简单的封装也没有做什么特别的处理，其他的function 可以到hashicorp/golang-lru 的github 去查看看。

最后要进到重头戏，`kubernetes LRUExpireCache `好拉说白一点也只是封装hashicorp/golang-lru ，废话就不多说了直接来看kubernetes 如何封装的吧！

## Kubernetes LRUExpireCache

### Struct

资料结构的部分相当简单，比刚刚看到的 `hashicorp/golang-lru` 多加了clock 以及sync Mutex ，不过为什么不复用 `hashicorp/golang-lru` 就好了。

```
// LRUExpireCache is a cache that ensures the mostly recently accessed keys are returned with
// a ttl beyond which keys are forcibly expired.
type LRUExpireCache struct {
	// clock is used to obtain the current time
	clock Clock                    //用來定時驅除 LRU 內的物件

	cache *lru.Cache               //用來定時驅除 LRU 內的物件
	lock  sync.Mutex               //多了鎖
}

```



放入的物件除了key 所代表的value 之外还多加了expireTime 来看， cache 是否过期。

```
type cacheEntry struct {
	value      interface{}
	expireTime time.Time
}

```



简单看完了资料结构后我们直接来看怎么建立这个物件

### New Function

```
// NewLRUExpireCache creates an expiring cache with the given size
func NewLRUExpireCache(maxSize int) *LRUExpireCache {
    //套入 kubernetes 的 real clock ，有機會再來看 clock ，這裡就當作他是 times就好了
	return NewLRUExpireCacheWithClock(maxSize, realClock{})
}

// NewLRUExpireCacheWithClock creates an expiring cache with the given size, using the specified clock to obtain the current time.
func NewLRUExpireCacheWithClock(maxSize int, clock Clock) *LRUExpireCache {
	cache, err := lru.New(maxSize)                            //這裡直接重用了 hashicorp/golang-lru ，建立一個 cache
	if err != nil {
		// if called with an invalid size
		panic(err)
	}
	return &LRUExpireCache{clock: clock, cache: cache}
}

```



看完了如何建立 `LRUExpireCache` 我们再接着来看一下细节怎么实作的。

### Add

```
func (c *LRUExpireCache) Add(key interface{}, value interface{}, ttl time.Duration) {
	c.lock.Lock()                                                  //不重用hashicorp/golang-lru 的 lock 真難受xD
	defer c.lock.Unlock()
	c.cache.Add(key, &cacheEntry{value, c.clock.Now().Add(ttl)})   //重用hashicorp/golang-lru 的 add 放入的物件多了 ttl 的過期時間。
}

```



### Get

```
func (c *LRUExpireCache) Get(key interface{}) (interface{}, bool) {
	c.lock.Lock()                                        
	defer c.lock.Unlock()
	e, ok := c.cache.Get(key)                    //重用hashicorp/golang-lru 的 get 取得物件
	//沒東西的話直接回傳就好
	if !ok {                    
		return nil, false
	}
	//需要額外判斷物件 ttl 是否過期，若是過期需要移出 LRU 並回傳使用者 找不到
	if c.clock.Now().After(e.(*cacheEntry).expireTime) {
		c.cache.Remove(key)
		return nil, false
	}
	//沒過期直接回傳
	return e.(*cacheEntry).value, true
}


// Get looks up a key's value from the cache.
func (c *Cache) Get(key interface{}) (value interface{}, ok bool) {
	c.lock.Lock()
	value, ok = c.lru.Get(key)                //重用hashicorp/golang-lru 的 get 取得物件
	c.lock.Unlock()
	return value, ok
}
```



### Remove

```
// Remove removes the specified key from the cache if it exists
func (c *LRUExpireCache) Remove(key interface{}) {
	c.lock.Lock()
	defer c.lock.Unlock()
	c.cache.Remove(key)                    //重用hashicorp/golang-lru 的 remove 移除物件
}
```



### Keys

```
// Keys returns all the keys in the cache, even if they are expired. Subsequent calls to
// get may return not found. It returns all keys from oldest to newest.
func (c *LRUExpireCache) Keys() []interface{} {
	c.lock.Lock()
	defer c.lock.Unlock()
	return c.cache.Keys()                //重用hashicorp/golang-lru 的 keys 取得所有 keys
}

```



## 小结

kubernetes utils 里面有许多有趣的工具，今天分享的工具关于LRU 的cache 比较简单也非常容易上手，基于github.com/hashicorp/golang-lru ，封装成有Expire 功能的LRU 以及基本的LRU 由hashicorp/golang-lru/simplelru 实作，若是文中有错误的见解希望各位在观看文章的大大们可以指出哪里有问题，让我学习改进，谢谢。

