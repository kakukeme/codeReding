首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

本篇文章是基于上一篇 `Kubernetes util tool 使用 Backoff 抖了一下`的拓展，主要会看到kubernetes 除了透过 `Jittered Backoff Manager Impl` 实作了 `BackoffManager` 之外，还额外实作了另外一种 `Exponential Backoff Manager Impl`命名方式也十分java xD。

我们也先复习一下上一张有提到的`BackoffManager`，先以一个情境来描述为什么要backoff ！
当client 发送请求给server 如果有可重试类型的失败那我们就会重新发起请求，but!
这里有个问题如果说我们写的client 一次打出去的请求有上百个甚至上千个呢？client 的重试可能会把server 打挂，所以需要设计一个 `backoff` 让重试的请求每次退后一点，每次退后一点（这里的退后可以想像成重试时间垃长一点）。

```
// The BackoffManager is supposed to be called in a single-threaded environment.
type BackoffManager interface {
Backoff() clock.Timer
}

```



是不是非常的简单的，实作 `BackoffManager` 的物件只要实作Backoff() clock.Timer 就好了～

我们来看一下今天要讲的主题exponentialBackoffManagerImpl 的资料结构吧！

### struct

| `1 2 3 4 5 6 7 8 ` | `type exponentialBackoffManagerImpl struct { backoff              *Backoff            //用來計算 back off 延遲時間，等等會看到Backoff物件是如何定義的。 backoffTimer         clock.Timer         //用 backoff 計算出的延遲時間來建立 timer  lastBackoffStart     time.Time           //上一次觸發back off 是什麼時候 initialBackoff       time.Duration       //初始化 back off 基礎延遲時間 backoffResetDuration time.Duration       //設定 back off 多久沒被觸發要重置的時間 clock                clock.Clock         //傳入現在時間 } ` |
| ------------------ | ------------------------------------------------------------ |
|                    |                                                              |

其中比较疑虑的参数应该是backoff 竟然是一个物件，那…到底是长圆的还是扁的？就让我们来瞧瞧吧！

### Backoff

```
// exponential 指数的
type exponentialBackoffManagerImpl struct {
	backoff              *Backoff            //用來計算 back off 延遲時間，等等會看到Backoff物件是如何定義的。
	backoffTimer         clock.Timer         //用 backoff 計算出的延遲時間來建立 timer 
	lastBackoffStart     time.Time           //上一次觸發back off 是什麼時候
	initialBackoff       time.Duration       //初始化 back off 基礎延遲時間
	backoffResetDuration time.Duration       //設定 back off 多久沒被觸發要重置的時間
	clock                clock.Clock         //傳入現在時間
}

```



看完了资料结构就要接着看Backoff 的实作啰，这部分有点小复杂!

#### step

这里我看了满久的，基本上要有几个情境才能看得出来他的应用，我们先来看程式码等等再来看使用情境。

```
func (b *Backoff) Step() time.Duration {

	//如果 step 用完或是超出 duration 超出 cap (底下會看到為什麼)都會進到這個判斷式
	//另外有設定 jitter 的話就會丟給 jitter 運算延遲時間
	//不然都是回傳 Backoff 上一次計算好的延遲時間
	if b.Steps < 1 {
		if b.Jitter > 0 {
			return Jitter(b.Duration, b.Jitter)
		}
		return b.Duration
	}
	//每次使用過都要減少 step
	b.Steps--
	
	//採用 Backoff 上一次計算好的 Duration 數值
	duration := b.Duration

	//如果有設定延遲倍數因子的話，延遲時間就要乘上倍數因子
	//另外如果有設定 cap 數值的話計算完的延遲時間要額外判斷是否超過 cap
	//超過 cap 就以 cap 為 下一次的延遲時間，並且將 step 歸 0
	if b.Factor != 0 {
		b.Duration = time.Duration(float64(b.Duration) * b.Factor)
		if b.Cap > 0 && b.Duration > b.Cap {
			b.Duration = b.Cap
			b.Steps = 0
		}
	}
    
    //如果有設定 jitter 的話，需要 透過 jitter 計算本次延遲時間

	if b.Jitter > 0 {
		duration = Jitter(duration, b.Jitter)
	}
	return duration
}

```



Step function 大概会包含以下四种情境，需要搭配着code 阅读会比较好理解一点。

以下情境全部b.Duration 保持0.5 s

1. 仅设定step
   - step 设定4 次
     程式流程会怎么样呢？

第一次近来来step – (step=3)，duration := b.Duration <0.5>，回传duration 0.5 s。

第二次近来来step – (step=2)，duration := b.Duration <0.5>，回传duration 0.5 s。

第三次近来来step – (step=1)，duration := b.Duration <0.5>，回传duration 0.5 s。

第四次近来来step – (step=0)，duration := b.Duration <0.5>，回传duration 0.5 s。

------

1. 仅设定step 与factor
   - step 设定4 次
   - factor 设定2
     程式流程会怎么样呢？

- 第一次近来来
  step – (step=3)
  duration := b.Duration <0.5>
  b.Duration = b.Duration<0.5> * factor <2>
  回传duration 0.5 s。
- 第二次近来来
  step – (step=2)
  duration := b.Duration <1>
  b.Duration = b.Duration<1> * factor <2>
  回传duration 1 s。
- 第三次近来来
  step – (step=1)
  duration := b.Duration <2>
  b.Duration = b.Duration<2> * factor <2>
  回传duration 2 s。
- 第四次近来来
  step – (step=0)
  duration := b.Duration <2>
  b.Duration = b.Duration<4> * factor <2>
  回传duration 4 s。

------

1. 设定step 、 factor 与Cap
   - step 设定4 次
   - factor 设定 2
   - Cap 设定2 s
     程式流程会怎么样呢？

- 第一次近来
  step – (step=3)
  duration := b.Duration <0.5>
  b.Duration = b.Duration<0.5> * factor <2>
  回传duration 0.5 s。
- 第二次近来
  step – (step=2)
  duration := b.Duration <1>
  b.Duration = b.Duration<1> * factor <2>
  回传duration 1 s。
- 第三次近来
  step – (step=1)
  duration := b.Duration <2>
  b.Duration = b.Duration<2> * factor <2>
  b.Duration = b.Cap <2>
  b.Step = 0
  回传duration 2 s。
- 第四次近来
  duration := b.Duration <2>
  回传duration 2 s。

------

1. 设定step 、 factor 、 Cap 与jitter
   - step 设定4 次
   - factor 设定 2
   - Cap 设定2 s
   - jitter 设定1 s

程式流程会怎么样呢？

- 第一次近来
  step – (step=3)
  duration := b.Duration <0.5>
  b.Duration = b.Duration<0.5> * factor <2>
  假设jitter 回传的结果为0.6
  回传duration 0.6 s。
- 第二次近来
  step – (step=2)
  duration := b.Duration <1>
  b.Duration = b.Duration<1> * factor <2>
  假设jitter 回传的结果为1.3
  回传duration 1.3 s。
- 第三次近来
  step – (step=1)
  duration := b.Duration <2>
  b.Duration = b.Duration<2> * factor <2>
  b.Duration = b.Cap <2>
  b.Step = 0
  假设jitter 回传的结果为2.6
  回传duration 2.6 s。
- 第四次近来
  假设jitter 回传的结果为4.1
  回传duration 4.1 s。

------

看完比较神秘的backoff 物件后要回来理解要如何把Exponential Backoff Manager 建立出来，观察他的new function 有没有偷藏什么！

### new function

在这之前先再次复习一下exponentialBackoffManagerImpl 的资料结构

```
type exponentialBackoffManagerImpl struct {
	backoff              *Backoff            //用 back off 物件計算延遲時間， back off 透過 基礎延遲時間 ，延遲倍數因子 ， step ， jitter 以及 cap 等來計算出下一次要延遲多久。
	backoffTimer         clock.Timer         //用 backoff 計算出的延遲時間來建立對應的 timer 
	lastBackoffStart     time.Time           //上一次觸發back off 是什麼時候
	initialBackoff       time.Duration       //初始化 back off 基礎延遲時間
	backoffResetDuration time.Duration       //設定 back off 多久沒被觸發要重置的時間
	clock                clock.Clock         //傳入現在時間
}


```



复习完了exponentialBackoffManagerImpl 的资料结构后，我们马上来看看怎么新增这个物件吧！

```
func NewExponentialBackoffManager(initBackoff, maxBackoff, resetDuration time.Duration, backoffFactor, jitter float64, c clock.Clock) BackoffManager {
	return &exponentialBackoffManagerImpl{
		backoff: &Backoff{                          //建構 backoff 物件
			Duration: initBackoff,                        //backoff 初始延遲時間
            
			Factor:   backoffFactor,                      //backoff 延遲倍數因子
            
			Jitter:   jitter,                             //backoff 抖動數值
			
			Steps: math.MaxInt32,                         //backoff 剩下幾次預設...很多次xDDD
            
			Cap:   maxBackoff,                            //backoff 延遲最大閥值
		},
		backoffTimer:         nil,                  //這時候還不會賦值，等到有人呼叫 backoff 的時候會計算出來 
		initialBackoff:       initBackoff,          //backoff 初始延遲時間
		lastBackoffStart:     c.Now(),              //用來記住上次什麼時候呼叫 backoff 的
		backoffResetDuration: resetDuration,        //設定多久 backoff 物件需要重置
		clock:                c,                    //用來對準時間
	}
}

```



### Backoff

了解了exponentialBackoffManagerImpl 怎么新增物件后，赶紧来看实作的部分废话就不多说直接上code !

```
func (b *exponentialBackoffManagerImpl) Backoff() clock.Timer {
	//如果還沒設定 backoffTimer 那麼就從 getNextBackoff() 算一個延遲時間作為 backoff timer 
	//如果有了 backoff timer 就需要刷新 timmer
	if b.backoffTimer == nil {                        
		b.backoffTimer = b.clock.NewTimer(b.getNextBackoff())
	} else {
		b.backoffTimer.Reset(b.getNextBackoff())
	}
	return b.backoffTimer
}


func (b *exponentialBackoffManagerImpl) getNextBackoff() time.Duration {
	//如果現在時間 剪去 lastBackoffStart [上次 backoff 開始時間] 大於 backoffResetDuration [預設重置時間] 的話
	//    設定 backoff 物件的 step 為最大數值
	//    設定 backoff 物件的基礎延遲時間
	if b.clock.Now().Sub(b.lastBackoffStart) > b.backoffResetDuration {
		b.backoff.Steps = math.MaxInt32
		b.backoff.Duration = b.initialBackoff
	}
	//設定 lastBackoffStart [上次 backoff 開始時間] 為現在
	b.lastBackoffStart = b.clock.Now()
    //透過 backoff 計算需要延遲多久
	return b.backoff.Step()
}

```



exponentialBackoffManagerImpl Backoff function 的重点基本上在于backoff 物件如何计算延迟时间，以及当执行时间超过backoffResetDuration 就要将backoff 物件设定成预设的阀值。

## 小结

简单的来看这个kubernetes utils tool ，属于wait package 的一部分主要实作backoff 逻辑，防止client 端因为大量的请求打坏server ，透过上一篇提到的jitter 结合本篇的exponential Backoff 可以让user 只要轻松进行几个简单的设定例如initBackoff 基础延迟时间，backoffFactor 延迟倍数因子， jitter 抖动范围, maxBackoff 最大延迟时间以及当多久没有呼叫backoff 需要重置的resetDuration 就能获得有backoff 功能clock ，整个过程相当的简单～
