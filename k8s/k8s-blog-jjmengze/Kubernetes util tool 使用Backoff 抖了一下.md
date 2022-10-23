# Kubernetes util tool 使用Backoff 抖了一下



首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

> 有朋友私讯我为什么关于kubernetes controller 的`Reflector 我在盯著你 （ III ）`文章还没出
> 因为笔者最近事情有点多加上是系列文的最后几篇，Reflector 有些部分还不是非常了解，既然要dig controller/operator 的实作就不想轻易的草率带过。再麻烦各位再等等了，感谢！

## Jittered Backoff Manager Impl

今天要来探讨的是kubernetes`Jittered Backoff Manager Impl`这个名称非常java ，就名称来看就是抖动的 `Backoff Manage` 实作，既然是 `Backoff Manage` 的实作的话，我们就要先来看一下 `Backoff Manage` 是个什么样的狠角色。

### BackoffManager

先从名称来推敲应该是退后管理器，那什么是退后管理器。
我用一个简单的范例说明，通常client 发送请求给server 如果有可重试类型的失败那我们就会重新发起请求，but!
这里有个问题client 有上百上千个呢？client 的重试可能会把server 打挂，所以需要设计一个 `backoff` 让重试的请求每次退后一点，每次退后一点（这里的退后可以想像成重试时间垃长一点）

```
// The BackoffManager is supposed to be called in a single-threaded environment.
type BackoffManager interface {
	Backoff() clock.Timer                //回傳一個 clock timer 
}
```

是不是非常的简单的，实作 `BackoffManager` 的物件只要实作Backoff() clock.Timer 就好了～

我们来看一下今天要讲的主题jitteredBackoffManagerImpl 的资料结构吧！

### struct

```
type jitteredBackoffManagerImpl struct {
	clock        clock.Clock                //給定 clock ，這一個實作應該用不到xD
	duration     time.Duration              //
	jitter       float64
	backoffTimer clock.Timer
}

```



看完了jitteredBackoffManagerImpl 的资料结构后我们要来看一下怎么初始化这个物件，看看有没有什么东西偷偷藏在里面。

### new function

```
func NewJitteredBackoffManager(duration time.Duration, jitter float64, c clock.Clock) BackoffManager {
	return &jitteredBackoffManagerImpl{
		clock:        c,                     //給定 clock ，這一個實作應該用不到xD
		duration:     duration,              //最少要延遲多久
		jitter:       jitter,                //給定抖動範圍
		backoffTimer: nil,                   //一開始不需要初始化 backoffTimer 會由 使用者呼叫 backoff 時經由計算後再賦值      
	}    
}
```



看完了如何初始化就是进入 `Jittered Backoff Manager Impl` 如何实作`BackoffManager`!

#### Backoff

```
func (j *jitteredBackoffManagerImpl) Backoff() clock.Timer {
	backoff := j.getNextBackoff()                     //進來的時候就先計算出 backoff 時間，等等會看到怎麼做的。
	if j.backoffTimer == nil {                        //如果當前沒有 backoff timer 就要賦值
	                                                  //賦與的數就是剛剛get next back off 曲的的時間
		j.backoffTimer = j.clock.NewTimer(backoff)
	} else {
		j.backoffTimer.Reset(backoff)             //如果存在的話需要刷新 backoff 時間
	}
	return j.backoffTimer
}



func (j *jitteredBackoffManagerImpl) getNextBackoff() time.Duration {
	jitteredPeriod := j.duration                       
	if j.jitter > 0.0 {
		jitteredPeriod = Jitter(j.duration, j.jitter)  //等等會看到jitter 在做什麼，這邊只要了解輸入基礎延遲時間跟抖動範圍
		                                               //我們就能到最後要延遲的時間
	}
	return jitteredPeriod
}

```



### Jitter

负责抖动延迟时间的运算

```
// Jitter returns a time.Duration between duration and duration + maxFactor * duration.
//
// This allows clients to avoid converging on periodic behavior. If maxFactor
// is 0.0, a suggested default value will be chosen.
func Jitter(duration time.Duration, maxFactor float64) time.Duration {
	//抖動因子參數（抖動範圍）可以自行調整，但不能小於等於0
	if maxFactor <= 0.0 {
		maxFactor = 1.0
	}
    
    //計算方式也很間單 基礎的延遲時間 + 隨機時間*抖動因子*基礎的延遲時間 
	wait := duration + time.Duration(rand.Float64()*maxFactor*float64(duration))
	return wait
}

```



### how to use

由于kubernetes 没有直接使用`jitteredBackoffManagerImpl`，只能看一下测试是怎么写的。

```
func TestJitterBackoffManagerWithRealClock(t *testing.T) {
	//定义抖动的参数，基础延迟时间为1*time.Millisecond 抖动范围为0 (这里等等计算的时候会设定成1)
	backoffMgr := NewJitteredBackoffManager(1*time.Millisecond, 0, &clock.RealClock{})
    
	//测试跑五次，如果抖动的延迟数值小于基础延迟时间就是测试失败
	for i := 0; i < 5; i++ {
		start := time.Now()
		//还记得Backoff()回传一个 clock.Timer 吗？
		//他就像是一个闹钟，当设定的时间到闹钟就会响，我们就可以从 channel 被唤醒
		<-backoffMgr.Backoff().C()
		passed := time.Now().Sub(start)
		if passed < 1*time.Millisecond {
			t.Errorf("backoff should be at least 1ms, but got %s", passed.String())
		}
	}
}

```



## 小结

kubernetes utils 里面有许多有趣的工具，像是今天分享就属于wiat utils tool 其中一小部分， wait tool 中还有许多有趣的小工具，下一篇会继续探讨wait 中其他的Backoff 实作，若是文中有错误的见解希望各位在观看文章的大大们可以指出哪里有问题，让我学习改进，谢谢。
