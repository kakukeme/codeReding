## BucketRateLimiter

先来看看BucketRateLimiter 的UML 图，很清楚可以看得出来他实作的RateLimiter interface 已经嵌入了一个golang rate package 的Limiter(也就是固定速度qps限速器，有兴趣的朋友可以自行深入阅读golang的实作方式)

![img](assets/kubernetes-ItemBucketRateLimiter-queue.png)



### interface

kubernetes source code 设计得非常精美，我们可以先从interface 定义了哪些方法来推敲实作这个interface 的物件可能有什么功能。

[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/default_rate_limiters.go)

```
type RateLimiter interface {
	// When gets an item and gets to decide how long that item should wait
  //当一个物件放入的时候，需要回传延迟多久（可自定义规则，等等会看到）
	When(item interface{}) time.Duration
    
    
	// Forget indicates that an item is finished being retried.  Doesn't matter whether its for perm failing	    
  // or for success, we'll stop tracking it
  //当一个物件完成的时候可以，要忘记曾经延迟过（重新计算）
  Forget(item interface{})
    
    
	// NumRequeues returns back how many failures the item has had
  // 回传物件已经放入几次（重试了几次，白话一点呼叫NumRequeues几次）
	NumRequeues(item interface{}) int
}
```



看完了抽象的定义之后，必须要回过来看Bucket Rate Limiter queue 实际物件定义了哪些属性

### struct

[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/default_rate_limiters.go)

```
// BucketRateLimiter adapts a standard bucket to the workqueue ratelimiter API
type BucketRateLimiter struct {
	*rate.Limiter        //BucketRateLimiter嵌入了golang.org.x.time.rate.Limiter
                       //也就是固定速度qps限速器，有兴趣的朋友可以自行深入阅读golang的实作方式
}

```



看完了资料结构我们接着来看BucketRateLimiter 实作的方法，与初始化方法。

### new function

[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/rate_limiting_queue.go)

```
//不知道为什么要设定一个空的变数
var _ RateLimiter = &BucketRateLimiter{}

// DefaultControllerRateLimiter is a no-arg constructor for a default rate limiter for a workqueue.  It has
// both overall and per-item rate limiting.  The overall is a token bucket and the per-item is exponential
func DefaultControllerRateLimiter() RateLimiter {
  //这里使用到上一章节提到得MaxOfRateLimiter，这里以两个RateLimiter为主
  //一个是ItemExponentialFailureRateLimiter，例外一个是本篇的主角BucketRateLimiter
	return BucketRateLimiter(
    //前几篇有转们讲解有兴趣的朋友欢迎回到前几章节复习
		NewItemExponentialFailureRateLimiter(5*time.Millisecond, 1000*time.Second),
		// 10 qps, 100 bucket size.  This is only for retry speed and its only the overall factor (not per item)
    //这里设定了golang  golang.org.x.time.rate.Limiter 的吞吐速度
    // 设定了 10 个 qps 以及 100 个 bucket
		&BucketRateLimiter{Limiter: rate.NewLimiter(rate.Limit(10), 100)},
	)
}

```



### implement function

看完了初始化BucketRateLimiter 后接下来看看核心的功能。

#### When

[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/rate_limiting_queue.go)

```
func (r *BucketRateLimiter) When(item interface{}) time.Duration {
	return r.Limiter.Reserve().Delay()    // golang.org.x.time.rate.Limiter实作的这个延迟会是个固定的周期（依照qps以及bucket而定）
}

```



#### NumRequeues

当我们需要知道物件已经重试了几次可以透过NumRequeues function 得知物件重是的次数。
[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/rate_limiting_queue.go)

```
func (r *BucketRateLimiter) NumRequeues(item interface{}) int {
	return 0        //因为是固定的频率，所以我们不需要管物件重次了几次
}

```



#### Forget

当物件做完时需要重新计算放延迟时间与放入次数，需要透过Forget function完成。
[source code](https://github.com/kubernetes/client-go/blob/master/util/workqueue/rate_limiting_queue.go)

```
func (r *BucketRateLimiter) Forget(item interface{}) {
                    //因为是固定速率所以我们也不需要去管要不要重新处理物件
}

```



## 怎么使用

对于 `BucketRateLimiter` 物件而言，他只是实作了`RateLimiter`interface，使用者要怎么用这个Rate Limiter queue 呢？

上一篇有提到 `RateLimiter` 的初始化方法

```
func NewRateLimitingQueue(rateLimiter RateLimiter) RateLimitingInterface {
	return &rateLimitingType{
		DelayingInterface: NewDelayingQueue(),        //前一小节有提到过delating work queue的newfunction
		rateLimiter:       rateLimiter,               //自行实作的rateLimiter
	}
}

```



使用者可以在传入参数带入实作`RateLimiter`interface的 `ItemExponentialFailureRateLimiter` 物件

```
NewRateLimitingQueue(DefaultControllerRateLimiter())
```



表示使用者要求的Rate Limiter queue 用了 `DelayingQueue` 与`BucketRateLimiter`、`ItemExponentialFailureRateLimiter`。

1. 物件延迟时间由 `ItemExponentialFailureRateLimiter` 与 `BucketRateLimiter` 决定
2. 物件延迟的排序方式由 `DelayingQueue` 决定（之前有提过用heap加上clock来触发）
3. 存放物件的queue 由 `common queue` 决定（之前有提过用processing set 加上dirty set 合力完成）

大致上流程是这样，不清楚的地方可以回去复习之前提到过的元件

## 小结

终于把kubernetes work queue 的部分梳理完，小小一个work queue 有如此多实作细节与方式
从common work queue 如何保证下一次add 进来的物件就有被取走还没做完，以及还没被取走的事情要考虑。
以及delaying work queue 如何排序一个延迟物件，让延迟时间最短的物件排在最前面

另外RateLimiter work queue 展现了设计模式代理了delaying work queue 以及组合了rateLimiter ，让逻辑分离rateLimiter 产出物件需要延迟多久，交给delaying work queue 进行排序。

kubernetes底层设计的非常精美，透过阅读程式码的方式提升自己对kubernetes的了解，若文章有错的部分希望大大们指出，谢谢！
