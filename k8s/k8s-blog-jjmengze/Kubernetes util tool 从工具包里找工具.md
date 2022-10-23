### Kubernetes util tool 从工具包里找工具



> https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/utils/kubernetes-util-tool/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc



首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

kubernetes 中封装了非常多的基本的library 例如clock , channel , waits …等等，我们从source code 学习kubernetes 的行为时常常会遇到这个library 跟基本的library 非常像但是又觉得跟基本的library 使用起来有点不一样，本篇文章会把我目前看到被封装过的library 进行整理。

## utils tool

1. utilruntime

- 主要处理runtime crash 的错误捕捉

1. wait.Group

- 主要处理goroutine 的生命周期管理

1. signals

- 主要处理linux signal 的讯号捕捉

1. sets

- 主要处理set 类型的资料结构

1. rand

- 主要处理go 语言中random 的生成

1. cache

- 主要处理常用的cache 方法，例如LRU 以及expiring

1. diff

- 主要用来比较string 或是object 的异同处

之后若是在阅读kubernetes source code 有看到跟utils tool 相关的library 再补上来。

## 小结

Kubernetes 为了开发者方便以及统一各个开发者使用基础library 的使用方式，对这些基础的进行了封装形成了utils tool。

我们若要了解kubernetes 的运作原理，认识这些utils tool 是不可避免的，后续会花几个章节整理一下尚属提到的几个utils tool，若是描述的不正确或是理解有错希望观看本文的大大吗可以不吝啬提出，感谢。

