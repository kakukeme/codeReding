
![img](assets/Kubernetes-Scheduler-context.png)


ref: [kubernetes scheduler](https://kubernetes.io/docs/concepts/scheduling-eviction/scheduling-framework/)



从上图可以看到会经过几个步骤分别是排成（scheduing）绑定(binding)，每个步骤又分成好几个Task 每一个Task 由一到多个plugins组合而成。

下面将简单叙述各个Task 的作用，后续章节讲到plugins时会比较了解在做什么。

先是排成(scheduing)

- Sort
  - 用于对scheduler queue 中的Pod 进行排序。一次只能启用一个sort plugins 。
- PreFilter
  - 用来进行预先检查pod 的需要满足的条件，若是处理失败则离开调度周期，重新进入scheduler queue 。
- Filter
  - 用来对节点进行过滤，只会留下满足pod 执行条件的节点。
- PreScore
  - 将Filter 阶段所产出的节点进行预评分工作，若是处理失败则离开调度周期，重新进入scheduler queue。
- Score
  - 将Filter 阶段所产出的节点进行评分
- NormalizeScore
  - 进行分数的正规化处理。
- Reserve
  - 这时Pod处于保留状态，它将在绑定周期结束时触发
    - 失败: Unreserve plugins
    - 成功: PostBind插件。
- Permit
  - Pod的调度周期结束时，做最后的把关用来Permit、deny或是wait这次的调度。

最后进行绑定(binding)

- PreBind
  - 在Pod真正绑定前设定相关的volume并将其安装在目标节点，若是处理失败则离开绑定周期，重新进入scheduler queue。
- Bind
  - 将Pod绑定到节点。
- PostBind
  - 成功绑定Pod后，用来清理相关的资源。

## 结语

接下来将会简单介绍一下每个阶段的plugins 在做什么，以便我们了解整个framwork 的架构。
