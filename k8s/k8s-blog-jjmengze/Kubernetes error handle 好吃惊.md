

> https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/errors/kubernetes-errors-handle/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc

首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

换换口味今天我们来分析一下kubernetes 如何处理错误， golang 的错误处理总是令人诟病，看看开源的大专案面对这种语言本身的缺陷（算吗xD?）是怎么处理的，从中学习到一些妙招～

> 本篇只涵盖到kubernetes 错误处理的一部分，还有许多错误处理的方法等待我们去挖掘～未来会持续解析相关议题！

## errors

kubernetes 定义了一个interface ，来描述错误讯息要如何被封装，先来看看这个interface 是如何定义的。

### interface

[source code](https://github.com/kubernetes/apimachinery/blob/master/pkg/util/errors/errors.go)

```
// 聚合 error
type Aggregate interface {
	error                    //继承了golang builtin package 的 error interface
	Errors() []error         //列出聚合所有的 error
	Is(error) bool           //检查聚合的內容有沒有包含指定的 error
}

```



看完了interface 后可以来看看哪个struct 实作了这个interface

### struct

聚合的意思就是把一群东西放在一起，error 的聚合就是把error 放在一起很自然地就会想到slice ~

没错！kubernetes也是这么做的，透过error slice 把error 聚合再一起。
[source code](https://github.com/kubernetes/apimachinery/blob/master/pkg/util/errors/errors.go)

```
type aggregate []error

```



看完了资料结构后我们来看看要怎么把这个物件建立起来

### New function

传入一组error slice 并且转换成Aggregate 物件回传出去
[source code](https://github.com/kubernetes/apimachinery/blob/master/pkg/util/errors/errors.go)

```
func NewAggregate(errlist []error) Aggregate {
    // error 是空的表示没有错误要处理
	if len(errlist) == 0 {
		return nil
	}
	// In case of input error list contains nil
	var errs []error
    // 把 error list 的東西全部倒出來，检查有没有偷藏 nil
    // 沒有 nil 的加入到 errs 的 slice 中
	for _, e := range errlist {
		if e != nil {
			errs = append(errs, e)
		}
	}
    //检查转换完的 errs 是不是空的
	if len(errs) == 0 {
		return nil
	}
    //换物件型态～转成有实作 Aggregate interface 的物件
	return aggregate(errs)
}

```



### impliment

看完了aggregate 的资料结构以及如何建立一个实作Aggregate interface 的物件后，我们来接着看实作得部分。

#### Error

Error 的部分是实作golang builtin package 的error
[source code](https://github.com/kubernetes/apimachinery/blob/master/pkg/util/errors/errors.go)

```
// Error is part of the error interface.
func (agg aggregate) Error() string {
  //确认一下 error slice 长度，在 new function 的时候就确认过理论上不会出错
	if len(agg) == 0 {
		// This should never happen, really.
		return ""
	}
  //如果错误只有一个的话就回传那一个
	if len(agg) == 1 {
		return agg[0].Error()
	}
  // new 一個errs set 
	seenerrs := sets.NewString()
	result := ""
  //需要搭配下面一點的 visit function 來看～
  //visit 代表访问每一个 item
	agg.visit(func(err error) bool {
    // 印出错误
		msg := err.Error()
    // 如果errs set  有包含这个错误的话就回传 false（表示错误重复了
		if seenerrs.Has(msg) {
			return false
		}
    //把错误加入 error set
		seenerrs.Insert(msg)
    //如果错误大于一個需要用,分割错误
		if len(seenerrs) > 1 {
			result += ", "
		}
		result += msg
		return false
	})
  //只有一個错误，直接回传错误不需要加[]
	if len(seenerrs) == 1 {
		return result
	}
  //错误是一个阵列，需要加[]
	return "[" + result + "]"
}

//访问每一个错误透过传入的 function 来处理每个错误
func (agg aggregate) visit(f func(err error) bool) bool {
  // 递回全部的错误
	for _, err := range agg {
    //判断错误类型
		switch err := err.(type) {
    //如错错误是一个 aggregate 的物件的话
		case aggregate:
                    
      //表示这个错误里面包了其他错误，需要再展开递回的处理
			if match := err.visit(f); match {
				return match
			}
    //如错误是一个实作 aggregate interface 的物件的话
		case Aggregate:
      //等等会看到 Errors ，简单来说就是把错误展开成一个slice
      //检查每一个错误
			for _, nestedErr := range err.Errors() {
				if match := f(nestedErr); match {
					return match
				}
			}
    //如果是一般错误的話
		default:
      //檢查個错误
			if match := f(err); match {
				return match
			}
		}
	}

	return false
}

```



#### Is

透过golang errors packages 的is 来辅助判断aggregate slice 内有无相同的error
[source code](https://github.com/kubernetes/apimachinery/blob/master/pkg/util/errors/errors.go)

```
func (agg aggregate) Is(target error) bool {
  //需要搭配刚刚提到的visit function 来看～
  //visit 代表访问每一个 item
	return agg.visit(func(err error) bool {
    //透过errors packages 来辅助判断 aggregate slice 内有无相同的error
		return errors.Is(err, target)
	})
}

```



#### Errors

显示所有error，简单来说就是把aggregate 转换成[]error
[source code](https://github.com/kubernetes/apimachinery/blob/master/pkg/util/errors/errors.go)

```
// Errors is part of the Aggregate interface.
func (agg aggregate) Errors() []error {
	return []error(agg)
}

```



以上透过aggregate 简单的封装了error，让golang 的错误处理有较好的处理方式。

我认为kubernetes 处理error 的靖华在于下面这几个function

### Flatten

比如有一个巢状的aggregate 可以用Flatten 把aggregate 摊平。

```
aggregate{
    aggregate{
        fmt.Errorf("abc"),
            aggregate{
                fmt.Errorf("def")
            }
    }
}

```



我们想把上面这个结构摊平，就可以透过Flatten 来帮忙输出的结果会变成

```
aggregate{
    fmt.Errorf("abc"), fmt.Errorf("def")
}

```



我们来看看他怎么实作的
[source code](https://github.com/kubernetes/apimachinery/blob/master/pkg/util/errors/errors.go)

```
// Flatten takes an Aggregate, which may hold other Aggregates in arbitrary
// nesting, and flattens them all into a single Aggregate, recursively.
func Flatten(agg Aggregate) Aggregate {
  //建立一個 error slice
	result := []error{}
  //判断 Aggregate 存不存在，不存在可以直接回传
	if agg == nil {
		return nil
	}
  //把 Aggregate 展开成 errors slice  并且递回所有error
	for _, err := range agg.Errors() {
    //如果判断到 error 是 Aggregate 就要继续递回展开
		if a, ok := err.(Aggregate); ok {
      //递归展开的error结果加入到result内
			r := Flatten(a)
			if r != nil {
				result = append(result, r.Errors()...)
			}
		} else {
      //error結果加入到result內
			if err != nil {
				result = append(result, err)
			}
		}
	}
  //回传一个 Aggregate error slice
	return NewAggregate(result)
}

```



### CreateAggregateFromMessageCountMap

有些情况我们会纪录错误讯息发生的次数，我们可以透过CreateAggregateFromMessageCountMap 帮助我们把这些讯息转成Aggregate error slice 。
[source code](https://github.com/kubernetes/apimachinery/blob/master/pkg/util/errors/errors.go)

```
// MessageCountMap contains occurrence for each error message.
// 计数错误讯息出现次数
type MessageCountMap map[string]int

// CreateAggregateFromMessageCountMap converts MessageCountMap Aggregate
// 输入计数讯息错误次数的 map 转换成 Aggregate 输出
func CreateAggregateFromMessageCountMap(m MessageCountMap) Aggregate {
  //简单的判断一下输入，没有输入就不做事
	if m == nil {
		return nil
	}
  //建立一個error slice 长度为 MessageCountMap 的大小
	result := make([]error, 0, len(m))
  //遍历 MessageCountMap
	for errStr, count := range m {
		var countStr string
    //错误出现超过一次的才放入 error slice 中，并且在错误讯息中标示 错误讯息与错误次数
		if count > 1 {
			countStr = fmt.Sprintf(" (repeated %v times)", count)
		}
		result = append(result, fmt.Errorf("%v%v", errStr, countStr))
	}
  //回传一个 Aggregate error slice
	return NewAggregate(result)
}

```



### FilterOut

把错误进行过滤～有通过Matcher function 才能输出
[source code](https://github.com/kubernetes/apimachinery/blob/master/pkg/util/errors/errors.go)

```
type Matcher func(error) bool

func FilterOut(err error, fns ...Matcher) error {
  //没有输入就不需要做事直接退出
	if err == nil {
		return nil
	}
  //如果 error 有實作 Aggregate 
	if agg, ok := err.(Aggregate); ok {
    //把 error 展开成 error slice 
    //再把 Matcher 与 error slice 交给 filterErrors 处理（下面有解释～）
		return NewAggregate(filterErrors(agg.Errors(), fns...))
	}
  // 判断error 有没有通过 matcher function
	if !matchesError(err, fns...) {
		return err
	}
	return nil
}

// matchesError returns true if any Matcher returns true
//透过 matcher function 检查输入的error
func matchesError(err error, fns ...Matcher) bool {
  //递回全部的 Matcher function
	for _, fn := range fns {
    //检查所有error
		if fn(err) {
			return true
		}
	}
	return false
}

//检查errors slice 
func filterErrors(list []error, fns ...Matcher) []error {
  //建立result error slice 
	result := []error{}
  //遍历 error slice 
	for _, err := range list {
    //这里会回到 FilterOut 确认 err 是不是有实作 Aggregate 
    //如果有实作 Aggregate 还需要继续的回展开
		r := FilterOut(err, fns...)
    //递回展开后结果不是空数就可以合并到 result error slice 
		if r != nil {
			result = append(result, r)
		}
	}
	return result
}


```



### AggregateGoroutines

假设有很多function 会并发执行且我们需要收集她的error 做聚合组里的时候这个`AggregateGoroutines`function 就很好用～
[source code](https://github.com/kubernetes/apimachinery/blob/master/pkg/util/errors/errors.go)

```
// AggregateGoroutines runs the provided functions in parallel, stuffing all
// non-nil errors into the returned Aggregate.
// Returns nil if all the functions complete successfully.
func AggregateGoroutines(funcs ...func() error) Aggregate {
  //建立有 n 個 buffer 的 channel  
	errChan := make(chan error, len(funcs))
    
  //递回执行function
	for _, f := range funcs {
    //透过 goroutine 在背景执行，把执行的结果丢在 buffer channel
		go func(f func() error) {
			errChan <- f()
		}(f)
	}
  // 建立 error slice 
	errs := make([]error, 0)
  //接收全部的 channel 結果
	for i := 0; i < cap(errChan); i++ {
    //如果 error 不是 nil 就加入到 error slice 內
		if err := <-errChan; err != nil {
			errs = append(errs, err)
		}
	}
  // 将error 转换成 Aggregate 
	return NewAggregate(errs)
}

```



### Reduce

我不确定`Reduce`function 的用途….看不是很懂…
[source code]https://github.com/kubernetes/apimachinery/blob/master/pkg/util/errors/errors.go)

```
// Reduce will return err or, if err is an Aggregate and only has one item, the first item in the aggregate.
//传入一個error 
func Reduce(err error) error {
  //如過error 有实作 Aggregate 的话 ，要接着判断 errors slice 的长度
	if agg, ok := err.(Aggregate); ok && err != nil {
		switch len(agg.Errors()) {
    //如果长度是1回传第一个错误
		case 1:
			return agg.Errors()[0]
    //不然就是nil
		case 0:
			return nil
		}
	}
	return err
}

```



## 小结

kubernetes 设计了很多很棒的架构，我们可以以kubernetes 为借镜设计模式出适合公司的框架，从中还可以学到不少神奇有趣的方法。
感恩开源赞叹开源，让我学习到更多！文中如果有见解错误的或是写的不正确的还希望观看此文的大大们可以不吝啬地提出让我修正与学习！感谢～
