首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

## cache

重新复习一次前一章[Kubernetes Indexers localcache 之美（I）](https://blog.jjmengze.website/posts/kubernetes/source-code/controller/indexer/kubernetes-indexers-local-cache-2/)提到的cache 相关的内容

### struct

先来看他的cache 的资料结构，`cache`组合了ThreadSafeStore 以及KeyFunc

```
// `*cache` implements Indexer in terms of a ThreadSafeStore and an
// associated KeyFunc.
type cache struct {
	// cacheStorage bears the burden of thread safety for the cache
	cacheStorage ThreadSafeStore    //一个 thread safe 的 interface 
	// keyFunc is used to make the key for objects stored in and retrieved from items, and
	// should be deterministic.
	keyFunc KeyFunc                //计算object key的方法，稍后会解释
}

```



复习完资料结构后，看看如何新增一个`有實作 Store Interface` 的cache 物件。

### new function

分别有两种function

- 第一种为传入KeyFunc 也就是只传入Object Key 如何计算告诉cache object key 如何计算，其他的Indexers 以及Indices 使用预设的。
- 第二种为传入KeyFunc 以及Indexers ，也就是告诉cache Object Key 如何计算方法以及储存KeyFunc 的Indexers 采用哪一种。

```
// NewStore returns a Store implemented simply with a map and a lock.
func NewStore(keyFunc KeyFunc) Store {
	return &cache{
		cacheStorage: NewThreadSafeStore(Indexers{}, Indices{}),
		keyFunc:      keyFunc,
	}
}

// NewIndexer returns an Indexer implemented simply with a map and a lock.
func NewIndexer(keyFunc KeyFunc, indexers Indexers) Indexer {
	return &cache{
		cacheStorage: NewThreadSafeStore(indexers, Indices{}),
		keyFunc:      keyFunc,
	}
}

```



我们先来看看cache 实作了哪些方法再来探讨怎么使用cache

### impliment

这里的Function 几乎依赖ThreadSafeStore 的实作，如果不了解ThreadSafeStore 做了什么的朋友可以回到前一章[Kubernetes Indexers localcache 之美（I）](https://blog.jjmengze.website/posts/kubernetes/source-code/controller/indexer/kubernetes-indexers-local-cache-1/)复习一下！

#### Add

```
// Add inserts an item into the cache.
func (c *cache) Add(obj interface{}) error {
  //透过 key function 计算出 object 所对应的 key
	key, err := c.keyFunc(obj)
	if err != nil {
		return KeyError{obj, err}
	}
  //委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore Add function 这里的细节可以去上章节复习。
  //简单来说就更新 local cache 的物件以及更新 indices 以及 index
	c.cacheStorage.Add(key, obj)
	return nil
}

```



#### Update

```
// Update sets an item in the cache to its updated state.
func (c *cache) Update(obj interface{}) error {
  //透过 key function 计算出 object 所对应的 key
	key, err := c.keyFunc(obj)
	if err != nil {
		return KeyError{obj, err}
	}
   //委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore Update function 这里的细节可以去上章节复习。
  //简单来说就更新 local cache 的物件以及更新 indices 以及 index
	c.cacheStorage.Update(key, obj)
	return nil
}

```



#### Delete

```
// Delete removes an item from the cache.
func (c *cache) Delete(obj interface{}) error {
  //透过 key function 计算出 object 所对应的 key
	key, err := c.keyFunc(obj)
	if err != nil {
		return KeyError{obj, err}
	}

	//委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore Delete 这里的细节可以去上章节复习。
  //简单来说就刪除 local cache 的物件以及更新 indices 以及 index
	c.cacheStorage.Delete(key)
	return nil
}

```



#### List

```
// List returns a list of all the items.
// List is completely threadsafe as long as you treat all items as immutable.
func (c *cache) List() []interface{} {
  //委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore List 这里的细节可以去上章节复习。
  //简单来说就刪除 local cache  所有 Object 
	return c.cacheStorage.List()
}

```



#### ListKeys

```
// ListKeys returns a list of all the keys of the objects currently
// in the cache.
func (c *cache) ListKeys() []string {

  //委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore ListKeys 这里的细节可以去上章节复习。
  //简单来说就列出 local cache  所有 Object   
	return c.cacheStorage.ListKeys()
}

```



#### GetIndexers

```
// GetIndexers returns the indexers of cache
func (c *cache) GetIndexers() Indexers {

  //委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore GetIndexers 这里的细节可以去上章节复习。
  //简单来说就列出 local cache  所有 Indexer   
	return c.cacheStorage.GetIndexers()
}

```



#### Index

```
// Index returns a list of items that match on the index function
// Index is thread-safe so long as you treat all items as immutable
func (c *cache) Index(indexName string, obj interface{}) ([]interface{}, error) {

  //委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore Index 这里的细节可以去上章节复习。
  //简单来说就是透过以知道的index name 以及 Object 合作帮忙分类列出 loacl cache 相关的 Object    
	return c.cacheStorage.Index(indexName, obj)
}

```



#### List

```
func (c *cache) IndexKeys(indexName, indexKey string) ([]string, error) {

  //委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore IndexKeys 这里的细节可以去上章节复习。
  //简单来说就是透过已知道的index name 以及 indexkey 合作帮忙分类列出 loacl cache 相关的 Object key   
	return c.cacheStorage.IndexKeys(indexName, indexKey)
}

```



#### List

```
// ListIndexFuncValues returns the list of generated values of an Index func
func (c *cache) ListIndexFuncValues(indexName string) []string {

  //委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore ListIndexFuncValues 这里的细节可以去上章节复习。
  //简单来说就是透过已知道 index name 帮忙分类列出 loacl cache 上负责计算Object key 的名字也就是 indexed function name     
	return c.cacheStorage.ListIndexFuncValues(indexName)
}

```



#### ByIndex

```
func (c *cache) ByIndex(indexName, indexKey string) ([]interface{}, error) {

  //委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore ByIndex 这里的细节可以去上章节复习。
  //简单来说就是透过已知道的index name 以及 indexkey 合作帮忙分类列出 loacl cache 相关的 Object   
	return c.cacheStorage.ByIndex(indexName, indexKey)
}

```



#### AddIndexers

```
func (c *cache) AddIndexers(newIndexers Indexers) error {
  
  //委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore AddIndexers 这里的细节可以去上章节复习。
  //简单来说就是新增一个 Indexer    
	return c.cacheStorage.AddIndexers(newIndexers)
}

```



#### Get

```
// Get returns the requested item, or sets exists=false.
// Get is completely threadsafe as long as you treat all items as immutable.
func (c *cache) Get(obj interface{}) (item interface{}, exists bool, err error) {
  //透過 key function 計算出 Object 所代表的 object key
	key, err := c.keyFunc(obj)
	if err != nil {
		return nil, false, KeyError{obj, err}
	}
  //使用該 Object key  來取得 Object 
	return c.GetByKey(key)
}

```



#### GetByKey

```
// GetByKey returns the request item, or exists=false.
// GetByKey is completely threadsafe as long as you treat all items as immutable.
func (c *cache) GetByKey(key string) (item interface{}, exists bool, err error) {

  //委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore Get 这里的细节可以去上章节复习。
  //简单来说就是使用 Object key 从 local stoarge 取得相关的 Object     
	item, exists = c.cacheStorage.Get(key)
	return item, exists, nil
}

```



#### ReplaceList

```
// Replace will delete the contents of 'c', using instead the given list.
// 'c' takes ownership of the list, you should not reference the list again
// after calling this function.
func (c *cache) Replace(list []interface{}, resourceVersion string) error {
  //传入一堆 object 以及这批物件的版本
  //建立一个 item slice 进行递回计算他们的Object key
	items := make(map[string]interface{}, len(list))
	for _, item := range list {
		key, err := c.keyFunc(item)
		if err != nil {
			return KeyError{item, err}
		}
		items[key] = item
	}
  
  //委任给 cacheStorage 也就是 threadSafeStore 去做处理，threadSafeStore Replace 这里的细节可以去上章节复习。
  //简单来说就是使用 这一坨 Object 直接对 原有的进行取代     
	c.cacheStorage.Replace(items, resourceVersion)
	return nil
}

```



#### Resync

```
// Resync is meaningless for one of these
func (c *cache) Resync() error {
    //沒做事xD
	return nil
}

```



快速地看完cache 实作了什么，绝大多数的function 都是依赖着更加底层的threadSafeStore 去完成，有了上一章节的逻辑整理cache 实作的部分很快地就可以扫过去!
接着让我来看看外面怎么来使用cache 帮忙储存以及取出资料吧！

### how to use

client go 官方有范例其中有一段用到cache 的初始化function `NewIndexer`，我们先来看看怎么用，以及他传入什么参数。
[source code](https://github.com/kubernetes/client-go/blob/master/examples/workqueue/main.go%23L177)

```
//cache package 中的 NewIndexerInformer是我們要关注的重点
//传入的参数我们先不要管那么多后续的章节会慢慢的颇析到
indexer, informer := cache.NewIndexerInformer(
    podListWatcher,
    &v1.Pod{},
    0,
    cache.ResourceEventHandlerFuncs{
    	...
    },
    cache.Indexers{})

```



上段source code 中提到的cache package 中的NewIndexerInformer 是我们要关注的重点是一个重要的function ，我们先来看看这个function 里面写了什么吧

```
//传入数值目前不是很重要，我们需要把重要放到 NewIndexer 来
//可以看到 NewIndexer 传入了 DeletionHandlingMetaNamespaceKeyFunc 以及 cache package 中預設的 indexers。
//表示所有的物件都会通过 DeletionHandlingMetaNamespaceKeyFunc 來計算Object key 並且存放在 local cache 中。
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



我们再来看看其他用法，虽然这个方法在Kubernetes 中非常少被用到但值得我们去了解。

```
//传入数值目前不是很重要，我们需要把重要放到 NewStore 來
//一样这边传入DeletionHandlingMetaNamespaceKeyFunc 来处理 Object  Key 的计算
func NewInformer(
	lw ListerWatcher,
	objType runtime.Object,
	resyncPeriod time.Duration,
	h ResourceEventHandler,
) (Store, Controller) {
	// This will hold the client state, as we know it.
	clientState := NewStore(DeletionHandlingMetaNamespaceKeyFunc)

	return clientState, newInformer(lw, objType, resyncPeriod, h, clientState)
}

```



## 小结

大家可能比较不了解的地方在于Object key 计算的方法，我在Kubernetes source code 中挖掘了很久找到一个实作keyFunc 的function ，不确定还有没有其他实作keyFunc 的function 。

1. MetaNamespaceIndexFunc
   [source code](https://github.com/kubernetes/client-go/blob/master/tools/cache/index.go%23L86)

```
// MetaNamespaceIndexFunc is a default index function that indexes based on an object's namespace
func MetaNamespaceIndexFunc(obj interface{}) ([]string, error) {
	meta, err := meta.Accessor(obj)
	if err != nil {
		return []string{""}, fmt.Errorf("object has no meta: %v", err)
	}
	return []string{meta.GetNamespace()}, nil
}

```



我就不展开来细看里面的实作，不就大致上能看出从Object 拿到metadata 中的namespace ，以namespace 作为indexed name 非常简单

了解了 `cache` 的实作以及底层的 `threadSafeMap` 与如何计算Object key 的KeyFunc 后，下一篇我想探讨 `DeltaFIFO` 的区块， 可以看到 `DeltaFiFo`如何跟 `cache` 进行资料的交换，文章若写错误的地方还请各位大大提出，感谢！
