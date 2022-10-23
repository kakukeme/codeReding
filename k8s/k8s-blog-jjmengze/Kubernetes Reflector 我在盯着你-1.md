首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

几本上这一块相当的复杂与庞大，有些部分我认为不需要深入理解其运作的机制，我们就来看看一个Reflector 是透过哪里零件组装起来的吧！

## Informer

实际上我不知道这里到底要叫 `Reflector` 又或是`Informer`，anyway 我想要了解这么庞大的东西要先从范例入手，我从client go 的范例可以看到 `Reflector/Informer` 的一些蛛丝马迹。
[source code](https://blog.jjmengze.website/posts/kubernetes/source-code/controller/redlector/reflector-1/staging/src/k8s.io/client-go/examples/workqueue/main.go)

```

    // 先不管他底層是如何實作的，這裡就是會監聽kubernetes api server 
    // 監控 pod 的變化，會帶一些條件例如： 要監聽的 namespace ，label等等
	podListWatcher := cache.NewListWatchFromClient(clientset.CoreV1().RESTClient(), "pods", v1.NamespaceDefault, fields.Everything())
    
    // 傳入監控pod的物件、pod的資料結構，resync的時間
    // 處理事件的控制function以及儲存資料的地方(local cache)
        indexer, informer := cache.NewIndexerInformer(podListWatcher, &v1.Pod{}, 0, cache.ResourceEventHandlerFuncs{
		...
        ,
	}, cache.Indexers{})    
    
    
    //建立 infomer 物件
func NewIndexerInformer(
	lw ListerWatcher,
	objType runtime.Object,
	resyncPeriod time.Duration,
	h ResourceEventHandler,
	indexers Indexers,
) (Indexer, Controller) {
	// 建立一個 indexer 作為local cache用
	clientState := NewIndexer(DeletionHandlingMetaNamespaceKeyFunc, indexers)
        //回傳 indexer 以及 實作 controller interface 的物件（之後會講到先不用管他）
	return clientState, newInformer(lw, objType, resyncPeriod, h, clientState)
}

    //基本上就是封裝成實作 controller interface 的物件（之後會講到先不用管他）
func newInformer(
	lw ListerWatcher,
	objType runtime.Object,
	resyncPeriod time.Duration,
	h ResourceEventHandler,
	clientState Store,
) Controller {

    ...
    return New(cfg)
}

```



我认为client go 这个范例很好，很清楚明白地看出informer 需要什么物件分别是

1. ListerWatcher
2. runtime.Object
3. ResourceEventHandler
4. Store

像是Store interface 我们已经看过了，就不再多提，我们先从 `ListerWatcher` 来看看这家伙是什么玩意。

## ListerWatcher

从刚刚的范例来看在建立 `Informer` 的时候传入了一个 `podListWatcher` 这是由

`cache.NewListWatchFromClient(clientset.CoreV1().RESTClient(), "pods", v1.NamespaceDefault, fields.Everything())`这个function 建立起来的，这个function 回传了实作`ListWatch`interface 的物件，我们先从这个interface 来看吧！

### interface

这个interface 很简单就是列出(list)要观测的object 以及追踪(watch)要观测的obejct 。
[source code](https://blog.jjmengze.website/posts/kubernetes/source-code/controller/redlector/reflector-1/staging/src/k8s.io/client-go/tools/cache/listwatch.go)

```
// ListerWatcher 組合了 Lister 跟 Watcher 接下去看 這兩個interface負責什麼
type ListerWatcher interface {
	Lister
	Watcher
}

// Lister is any object that knows how to perform an initial list.
type Lister interface {
	// 根據 ListOptions 來決定要列出哪些物件
	List(options metav1.ListOptions) (runtime.Object, error)
}

// Watcher is any object that knows how to start a watch on a resource.
type Watcher interface {
	// 根據 ListOptions 來決定要跟蹤哪些物件
	Watch(options metav1.ListOptions) (watch.Interface, error)
}


```



看完了ListerWatcher 相关的Interface 后我们来看一下实作ListerWatcher 的资料结构吧！

### Struct

先打预防针不是每一个ListerWatcher 都是透过以下方式实作的，本章节只讨论client go 范例的调用链，我猜其他实作的方式也差不多吧？（有时间再来研究其他的listwatch 怎么做）
[source code](https://blog.jjmengze.website/posts/kubernetes/source-code/controller/redlector/reflector-1/staging/src/k8s.io/client-go/tools/cache/listwatch.go)

```
// 定義實作 Lister 的 struct
type ListFunc func(options metav1.ListOptions) (runtime.Object, error)

// 定義實作 Watcher 的 struct
type WatchFunc func(options metav1.ListOptions) (watch.Interface, error)


// ListWatch knows how to list and watch a set of apiserver resources.  It satisfies the ListerWatcher interface.
// It is a convenience function for users of NewReflector, etc.
// ListFunc and WatchFunc must not be nil
type ListWatch struct {
	ListFunc  ListFunc
	WatchFunc WatchFunc
	// DisableChunking requests no chunking for this list watcher.
	DisableChunking bool
}

```



### New Function

我们来看一下 `cache.NewListWatchFromClient(clientset.CoreV1().RESTClient(), "pods", v1.NamespaceDefault, fields.Everything())`这个function 做了什么
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/redlector/reflector-1/staging/src/k8s.io/client-go/tools/cache/listwatch.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// NewListWatchFromClient通過指定的 client ， resource ，namespace
//  和 fieldsSelector 建立一個ListWatch。
func NewListWatchFromClient(c Getter, resource string, namespace string, fieldSelector fields.Selector) *ListWatch {
    //封裝 fieldSelector 為 func(options *metav1.ListOptions)
	optionsModifier := func(options *metav1.ListOptions) {
		options.FieldSelector = fieldSelector.String()
	}
    //建立一個ListWatch
	return NewFilteredListWatchFromClient(c, resource, namespace, optionsModifier)
}

// NewFilteredListWatchFromClient 跟上面那個很類似不過他是接收 optionsModifier 而不是 fieldsSelector
func NewFilteredListWatchFromClient(c Getter, resource string, namespace string, optionsModifier func(options *metav1.ListOptions)) *ListWatch {
    //建立一個實作 lister  的物件
	listFunc := func(options metav1.ListOptions) (runtime.Object, error) {
            //這個手法滿厲害的，就是在其他地方使用listFunc(ListOptions)時候
            //把傳入的 ListOptions 透過 optionsModifier function 處理
		optionsModifier(&options)
               //這裡就是 kubernetes client 請求 kubernetes api server 的方法
		return c.Get().
			Namespace(namespace).
			Resource(resource).
			VersionedParams(&options, metav1.ParameterCodec).
			Do(context.TODO()).
			Get()
	}
    //建立一個實作 Watcher  的物件
	watchFunc := func(options metav1.ListOptions) (watch.Interface, error) {
		options.Watch = true
            //這個手法滿厲害的，就是在其他地方使用watchFunc(ListOptions)時候
            //把傳入的 ListOptions 透過 optionsModifier function 處理
		optionsModifier(&options)
               //這裡就是 kubernetes client 請求 kubernetes api server 的方法
		return c.Get().
			Namespace(namespace).
			Resource(resource).
			VersionedParams(&options, metav1.ParameterCodec).
			Watch(context.TODO())
	}
    //建立實作 ListWatcher 的物件
	return &ListWatch{ListFunc: listFunc, WatchFunc: watchFunc}
}

```



### impliment

#### List

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/redlector/reflector-1/staging/src/k8s.io/client-go/tools/cache/listwatch.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// 委託給剛剛的 object 列出物件的情況
func (lw *ListWatch) List(options metav1.ListOptions) (runtime.Object, error) {
	// ListWatch is used in Reflector, which already supports pagination.
	// Don't paginate here to avoid duplication.
	return lw.ListFunc(options)
}

```



#### Watch

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/controller/redlector/reflector-1/staging/src/k8s.io/client-go/tools/cache/listwatch.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// 委託給剛剛的 object 監控物件的情況
func (lw *ListWatch) Watch(options metav1.ListOptions) (watch.Interface, error) {
	return lw.WatchFunc(options)
}
```



到这里就是一个最基本的listwatch ，接下来要看看要怎么组合这个功能到 `Reflector/Informer` 内。

## 小结

kubernetes Reflector 中的listwatch 的部分就先看到这里，下一章节要接着看本篇最一开始提到的 `controller interface` 部分，kubernetes 底层设计的非常精美，透过阅读程式码的方式提升自己对kubernetes 的了解，若文章有错的部分希望大大们指出，谢谢！
