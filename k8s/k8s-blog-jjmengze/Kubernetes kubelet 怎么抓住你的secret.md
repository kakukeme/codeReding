首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

本篇文章跟前一章非常相似，如果还没有看过[Kubernetes kubelet 怎么抓住你的configmap](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-configmap-overlay/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)可以去参考看看！

本章节的内容同样需要先了解[Kubernetes kubelet cacheBasedManager 现在才知道](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/kubernetes-kubelet/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)以及[Kubernetes kubelet cacheBasedManager 好喜欢objectcache 的原因](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cacheobject/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)，会比较容易了解本章节的重点呦！

## manger interface

Kubernetes 先定义出取得secret 资料的行为，有以下三点。

1. 当pod spec 内写到需要secret 的时候如何注册需要的secret 。
2. 曾经pod spce 内有需要xxx secret 现在不用的时候，需要反注册secret 。
3. 能透过secret 的namespace 与name 取得对应的资料。
   [source. code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-secret-overlay/pkg/kubelet/secret/secret_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// Manager manages Kubernetes secrets. This includes retrieving
// secrets or registering/unregistering them via Pods.
type Manager interface {
	
	GetSecret(namespace, name string) (*v1.Secret, error)

	
	RegisterPod(pod *v1.Pod)

	
	UnregisterPod(pod *v1.Pod)
}

```



## Secret manager

`secretManager`这个物件实作了`manger interface`，物件里面的属性也相当的简单只有一个我们在前两章节介绍过的[Kubernetes kubelet configmap & secret 与cacheBasedManager 激情四射](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cachebasedmanager/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)，也就是说secret Manager 极度依赖manager.Manager 的实作。
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-secret-overlay/pkg/kubelet/secret/secret_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
type secretManager struct {
	manager manager.Manager				//用以處理 pod 註冊 configmap/secret ，建立 reflector 等相關操作。
}

```





> [color=#f41dc9]TIPS:
> 如果还不是很了解的manager.Manager 的实作方式，强烈建议复习一下[Kubernetes kubelet configmap & secret 与cacheBasedManager 激情四射](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cachebasedmanager/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)。

### new function

了解完`secretManager`的资料结构后，我们接着要来看看在初始化这个物件时需要什么东西吧！

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-secret-overlay/pkg/kubelet/secret/secret_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// NewWatchingSecretManager creates a manager that keeps a cache of all secrets
// necessary for registered pods.
// It implements the following logic:
// - whenever a pod is created or updated, we start individual watches for all
//   referenced objects that aren't referenced from other registered pods
// - every GetObject() returns a value from local cache propagated via watches
func NewWatchingSecretManager(kubeClient clientset.Interface) Manager {
	//建立一個 lister  watcher function ，主要用來監聽 kubernetes secret 的變化
	//list 的條件為透過 client go 的 corev1 secret list＆watch 相關的資訊。
	//實作上依賴注入的 kubernetes client interface ，這個 interface 功能非常多有機會之後再來看
	listSecret := func(namespace string, opts metav1.ListOptions) (runtime.Object, error) {
		return kubeClient.CoreV1().Secrets(namespace).List(context.TODO(), opts)
	}
	watchSecret := func(namespace string, opts metav1.ListOptions) (watch.Interface, error) {
		return kubeClient.CoreV1().Secrets(namespace).Watch(context.TODO(), opts)
	}

	//還記得之前在挖掘 kubernetes controller operator 的過程嗎？ 需要知道要觀測對象的型態
	//在這裡就是 secret
	newSecret := func() runtime.Object {
		return &v1.Secret{}
	}
	//secret 其中有個欄位是 Immutable ，當 reflector 觀察到 secret 會透過這個 function 
	// 檢查 secret 是不是 Immutable 的狀態，若是為 Immutable 的狀態就會停止該物件的 reflector 
	isImmutable := func(object runtime.Object) bool {
		if secret, ok := object.(*v1.Secret); ok {
			return secret.Immutable != nil && *secret.Immutable
		}
		return false
	}

	gr := corev1.Resource("secret")
	//回傳 secret Manger 的物件，其中 manager 的實作為 WatchBasedManager
	return &secretManager{
		manager: manager.NewWatchBasedManager(listSecret, watchSecret, newSecret, isImmutable, gr, getSecretNames),
	}

}

```



### RegisterPod/UnregisterPod

configMapManager 这里就是无脑的把任务交给manager 的RegisterPod function 或是UnregisterPod function ，里面的实作简单的提一下细节可以回去复习[Kubernetes kubelet configmap & secret 与cacheBasedManager 激情四射](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cachebasedmanager/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)。

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-secret-overlay/pkg/kubelet/secret/secret_manager.gogo?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

- RegisterPod function
  - pod spec 内用到secret 的地方会透过cacheBasedManager 去解析并且生成对应的reflector
- UnregisterPod function
  - pod spec 内用到secret 的地方会透过cacheBasedManager 去解析并且删除对应的reflector

```
func (s *secretManager) RegisterPod(pod *v1.Pod) {
	s.manager.RegisterPod(pod)
}

func (s *secretManager) UnregisterPod(pod *v1.Pod) {
	s.manager.UnregisterPod(pod)
}

```



一定有人问kubelet 如何取得secret 资料，中间过程非常非常非常的长，整个调用链生命周期也有点小杂乱，之后有时间会来整理一下kubelet 的调用链，本小节只简单的带出kubelet 的secret manager 如何注/反注册pod spec 中用到的secret 栏位。

底下这个function 会触发的时机点就先想像成, kubelet 会先把这一段时间有变化的pod 都透过这个function 丢进来。前面怎么走到这一步的先不要管他…太复杂了会迷失方向xD
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-secret-overlay/pkg/kubelet/pod/pod_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// updatePodsInternal replaces the given pods in the current state of the
// manager, updating the various indices. The caller is assumed to hold the
// lock.
func (pm *basicManager) updatePodsInternal(pods ...*v1.Pod) {
	//遞迴所有變化的 pod
	for _, pod := range pods {
		...
		//一般來說...前面已經會把 secretManager 設定好
		if pm.secretManager != nil {
			// 如果 pod 處於 Terminated 狀態就需要返註冊 pod status 
			if isPodInTerminatedState(pod) {
				pm.secretManager.UnregisterPod(pod)
			} else {
			// 如果 pod 處於其他狀態就需要註冊 pod status，開始解析用到的 secret 建立對應的 reflector 等等 
				pm.secretManager.RegisterPod(pod)
			}
		}
        ...

```



### GetSecret

只剩下一个主要的GetSecret function，只要传入namespace 以及要取得的secret name 就能得到对应的secret 物件，这边的情境略为复杂需要举几个例子来说明，我们先来看实作的部分。

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-secret-overlay/pkg/kubelet/secret/secret_manager.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
func (s *secretManager) GetSecret(namespace, name string) (*v1.Secret, error) {
	//主要是透過前幾章說的[Kubernetes kubelet configmap & secret 與 cacheBasedManager 激情四射](https://blog.jjmengze.website/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cachebasedmanager/) 
	//去取得 secret 物件的資料，這邊不了解的話可以去複習一下相關連結。
	object, err := c.manager.GetObject(namespace, name)
	if err != nil {
		return nil, err
	}
	//透過 cacheBasedManager 取得的物件需要轉成對應的型態例如 secret
	if secret, ok := object.(*v1.Secret); ok {
		return secret, nil
	}
	return nil, fmt.Errorf("unexpected object type: %v", object)
}

```



总共有三种方法可以将kubernetes 中设定好的secret 挂载到pod container 中，这三种方法分别是。

- env
  - valueFrom
    - secretRef
- envFrom
  - configMapRef
- volumes
  - secret

撰写yaml 给kubernetes 很简单那实际上kubernetes 帮我们做了什么呢？

#### env - valueFrom - secret

范例是撷取自kubernetes 官方网站，撰写一个yaml 档送给kubernetes 告诉kubernetes 帮忙启动一个pod 并且建立一个secret ，将所有secret 定义为pod 的环境变数。secret 中的key 成为Pod 中的环境变数名称，value 则为环境变数的数值。

[Use-Case: As container environment variables](https://translate.google.com/website?sl=zh-TW&tl=zh-CN&hl=zh-CN&prev=search&u=https://kubernetes.io/docs/concepts/configuration/secret/%23use-cases)

```
apiVersion: v1
kind: Secret
metadata:
  name: mysecret
type: Opaque
data:
  USER_NAME: YWRtaW4=
  PASSWORD: MWYyZDFlMmU2N2Rm

```

```
apiVersion: v1
kind: Pod
metadata:
  name: secret-test-pod
spec:
  containers:
    - name: test-container
      image: k8s.gcr.io/busybox
      command: [ "/bin/sh", "-c", "env" ]
      envFrom:
      - secretRef:
          name: mysecret
  restartPolicy: Never

```



话不多说我们先来看kubelet 怎么从api server 经过层层关卡抓到pod yaml 里面写的东西，再取得对应的secret 。
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-secret-overlay/pkg/kubelet/kubelet_pods.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
func (kl *Kubelet) makeEnvironmentVariables(pod *v1.Pod, container *v1.Container, podIP string, podIPs []string) ([]kubecontainer.EnvVar, error) {
    ..
    
    //儲存最後要變成 pod 的環境變數
	var result []kubecontainer.EnvVar
    
    //簡單來說就是如果 pod yaml 有啟用 EnableServiceLinks 的話就需要把同一個 namespaces 所有的 service 
    //對應的名稱、IP 與 port 以環境變數的方式除存在 serviceENV 中
	serviceEnv, err := kl.getServiceEnvVarMap(pod.Namespace, *pod.Spec.EnableServiceLinks)
    
	var (
		configMaps = make(map[string]*v1.ConfigMap)
		secrets    = make(map[string]*v1.Secret)
		tmpEnv     = make(map[string]string)
	)
	...
    
	// Env will override EnvFrom variables.
	// Process EnvFrom first then allow Env to replace existing values.
	//這裡會遞迴 yaml 裡面 container envform 的欄位
	for _, envFrom := range container.EnvFrom {
		//接著要來判斷 envform 裡面有沒有 SecretRef 這個欄位了
		switch {
        
		//如果有找到  SecretRef 這個欄位的話
		case envFrom.SecretRef != nil:
			//把 SecretRef 欄位內的資料結構拿出來為 s
			s := envFrom.SecretRef
            
			// 把 SecretRef 欄位內的名稱拿出來 ，這裡就是 secret 的名稱
			name := s.Name
            
			// 檢查 secret map 是否有處理過同樣名字的物件過
			secret, ok := secrets[name]
            
			//如果 map 找不到東西表示....我們要自己向 api server 要看相關的 secret 
			if !ok {
				//這裡用的又是 kubernetes client interface ，沒有這個 interface 什麼都做不了
				//所以這裡有錯的話就可以回家洗洗睡了xD
				if kl.kubeClient == nil {
					return result, fmt.Errorf("couldn't get secret %v/%v, no kubeClient defined", pod.Namespace, name)
				}
                
				//簡單來說就是有定義 SecretEnvSource.Optional = true，等等會看到要用來做什麼的
				optional := s.Optional != nil && *s.Optional
				
				//這邊就是用 secret manager 的 GetConfigMap function 去取得 secret 
				//所要求的參數也不多只要 namespace 與 secret 的 name 就能取的對應的資料
				secret, err = kl.secretManager.GetSecret(pod.Namespace, name)
                
				//如果在找的過程有出現錯的話，並且有定義 SecretEnvSource.Optional = true 那就不會直接噴 error 
				if err != nil {
					if errors.IsNotFound(err) && optional {
						// ignore error when marked optional
						continue
					}
					return result, err
				}
				//接著以 secret name 作為 key 以及 secret 物件的內容作為 value 存在 map 中
				//以供後續重複使用                
				configMaps[name] = configMap
			}
            
			
            
            
			//secret 中如果 key value 有不符合環境變數的資料會存在這個 slice 中                
			invalidKeys := []string{}
            
            
			//剛剛我們透過 secretManager.GetConfigMap 取的對應的 secret
            //我們的最終目的是取的 secret 對應的 key value 資料
            //這裡就用 for 迴圈遞迴 secret 資料的每一列
			for k, v := range secret.Data {
				
				if len(envFrom.Prefix) > 0 {
					k = envFrom.Prefix + k
				}
				//IsEnvVarName 判斷 configmap 資料的 key value 其中的 key 是否為有效的環境變變數名稱。
				if errMsgs := utilvalidation.IsEnvVarName(k); len(errMsgs) != 0 {
					//secret 中如果 key value 有不符合環境變數的資料會存在這個 slice 中 
					invalidKeys = append(invalidKeys, k)
					continue
				}
				//把沒問題的資料存在暫存的環境變數 map 
				tmpEnv[k] = v
			}

			//將有問題的 configmap 資料做整理加入一些 log 資訊讓使用者更好閱讀
			if len(invalidKeys) > 0 {
				//不知道為什麼要 sort xDDD            
				sort.Strings(invalidKeys)
                
				kl.recorder.Eventf(pod, v1.EventTypeWarning, "InvalidEnvironmentVariableNames", "Keys [%s] from the EnvFrom secret %s/%s were skipped since they are considered invalid environment variable names.", strings.Join(invalidKeys, ", "), pod.Namespace, name)
			}
           

//環境變數的正規表達式
const envVarNameFmt = "[-._a-zA-Z][-._a-zA-Z0-9]*"
var envVarNameRegexp = regexp.MustCompile("^" + envVarNameFmt + "$")

//IsEnvVarName 測試 secret 資料的 key value 其中的 key 是否為有效的環境變變數名稱。
func IsEnvVarName(value string) []string {
	var errs []string
	if !envVarNameRegexp.MatchString(value) {
		errs = append(errs, RegexError(envVarNameFmtErrMsg, envVarNameFmt, "my.env-name", "MY_ENV.NAME", "MyEnvName1"))
	}

	errs = append(errs, hasChDirPrefix(value)...)
	return errs
}

```



#### env - valueFrom - configMapKeyRef

范例是撷取自kubernetes 官方网站，撰写一个yaml 档送给kubernetes 告诉kubernetes 帮忙启动一个pod 并且建立一个configmap ，将ConfigMap 中定义的special.how 数值作为Pod 中的SPECIAL_LEVEL_KEY 环境变数。

[configmap/multiple key-value pairs.yaml](https://translate.google.com/website?sl=zh-TW&tl=zh-CN&hl=zh-CN&prev=search&u=https://kubernetes.io/examples/configmap/configmap-multikeys.yaml)

```
apiVersion: v1
kind: ConfigMap
metadata:
  name: special-config
  namespace: default
data:
  SPECIAL_LEVEL: very
  SPECIAL_TYPE: charm

```



[pods/pod-configmap-envFrom.yaml](https://translate.google.com/website?sl=zh-TW&tl=zh-CN&hl=zh-CN&prev=search&u=https://raw.githubusercontent.com/kubernetes/website/master/content/en/examples/pods/pod-configmap-envFrom.yaml)

```
apiVersion: v1
kind: Pod
metadata:
  name: dapi-test-pod
spec:
  containers:
    - name: test-container
      image: k8s.gcr.io/busybox
      command: [ "/bin/sh", "-c", "env" ]
      env:
        # Define the environment variable
        - name: SPECIAL_LEVEL_KEY
          valueFrom:
            configMapKeyRef:
              # The ConfigMap containing the value you want to assign to SPECIAL_LEVEL_KEY
              name: special-config
              # Specify the key associated with the value
              key: special.how
  restartPolicy: Never

```



话不多说我们先来看kubelet 怎么从api server 经过层层关卡抓到pod yaml 里面写的东西，再取得对应的configmap ，这边的code 是接续着`env - valueFrom - configMapRef` 那一小节的code。

```
func (kl *Kubelet) makeEnvironmentVariables(pod *v1.Pod, container *v1.Container, podIP string, podIPs []string) ([]kubecontainer.EnvVar, error) {
	...
	for _, envFrom := range container.EnvFrom
	    switch {
        
		//如果有找到  ConfigMapRef 這個欄位的話
		case envFrom.ConfigMapRef != nil:
	...
	//接續env - valueFrom - configMapRef 小節
    
    
	//遞迴 container 的Env 欄位
	for _, envVar := range container.Env {
		runtimeVal := envVar.Value
		if runtimeVal != "" {
        
			...
			//若是有定義 ValueFrom 欄位的話
		} else if envVar.ValueFrom != nil {
			// Step 1b: resolve alternate env var sources
			//需要判斷是從 from 哪一種資源，本篇文章只關心 configmap 
			switch {
			case envVar.ValueFrom.FieldRef != nil:
				...
                
			case envVar.ValueFrom.ResourceFieldRef != nil:
				...
			//如果 valueFrom 是引用 configmap 的話                
			case envVar.ValueFrom.ConfigMapKeyRef != nil:
				//先取出 ConfigMapKeyRef 為 cm
				cm := envVar.ValueFrom.ConfigMapKeyRef
				//取出 configmap 的 name                
				name := cm.Name
				//取出對應的 key 
				key := cm.Key
				//簡單來說就是有定義 ConfigMapEnvSource.Optional = true，等等會看到要用來做什麼的
				optional := cm.Optional != nil && *cm.Optional
                
                
				// 檢查 configmap map 是否有處理過同樣名字的物件過，如果有就可以直接拿出來用
				configMap, ok := configMaps[name]
            
				//如果 map 找不到東西表示....我們要自己向 api server 要看相關的 configmap 				
				if !ok {
					//這裡用的又是 kubernetes client interface ，沒有這個 interface 什麼都做不了
					//其實...這一段流程也沒拿來幹嘛...xD
					if kl.kubeClient == nil {
						return result, fmt.Errorf("couldn't get configMap %v/%v, no kubeClient defined", pod.Namespace, name)
					}
                    
					//這邊就是用 configmap manager 的 GetConfigMap function 去取得 configmap 
					//所要求的參數也不多只要 namespace 與 configmap 的 name 就能取的對應的資料
					configMap, err = kl.configMapManager.GetConfigMap(pod.Namespace, name)
                    
                    
					//如果在找的過程有出現錯的話，並且有定義 ConfigMapEnvSource.Optional = true 那就不會直接噴 error 
					if err != nil {
						if errors.IsNotFound(err) && optional {
							// ignore error when marked optional
							continue
						}
						return result, err
					}
					//接著以 configmap name 作為 key 以及 configmap 物件的內容作為 value 存在 map 中
					//以供後續重複使用
					configMaps[name] = configMap
				}
				//取出 configmap data 對應的 key 當作環境變數
				runtimeVal, ok = configMap.Data[key]
				//如果拿不到對應的 key 且 ConfigMapEnvSource.Optional = true 那就不會直接噴 error    
				if !ok {
					if optional {
						continue
					}
					return result, fmt.Errorf("couldn't find key %v in ConfigMap %v/%v", key, pod.Namespace, name)
				}
			case envVar.ValueFrom.SecretKeyRef != nil:
				...
			}
		}
        
		//還記得在env - valueFrom - configMapRef 這一小節，有提到過 serviceEnv 嗎？
		//幫大家快速複習一下
		//簡單來說就是如果 pod yaml 有啟用 EnableServiceLinks 的話就需要把同一個 namespaces 所有的 service 
		//對應的名稱、IP 與 port 以環境變數的方式存在 serviceENV 中
		//我們舉一個例子 kubernetes 通常會幫你在 default namespace 的 pod 加上一些環境變數
		//例如：KUBERNETES_PORT_443_TCP_PROTO=tcp
		//這一行環境變數就是 serviceEnv 裡面的資料，資料來源可能透過kubelet 或是 api server 
		//對應到 code 就是在env - valueFrom - configMapRef 這一小節的
		//serviceEnv, err := kl.getServiceEnvVarMap(pod.Namespace, *pod.Spec.EnableServiceLinks)
		
		
		//如果使用者就是想覆蓋這個環境變數該怎麼辦？
		//其實就直接在 yaml 直接設定一下就覆蓋過去了xD也就是下面這一行code而已拉
		delete(serviceEnv, envVar.Name)
		//設定環境變數名稱為 pod spec 中定義的 name value 為從 configmap 或是其他資源取的數值
		tmpEnv[envVar.Name] = runtimeVal
	}


```



[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-secret-overlay/pkg/kubelet/kubelet_pods.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
func (kl *Kubelet) makeEnvironmentVariables(pod *v1.Pod, container *v1.Container, podIP string, podIPs []string) ([]kubecontainer.EnvVar, error) 

	...
    
	var result []kubecontainer.EnvVar
    
	serviceEnv, err := kl.getServiceEnvVarMap(pod.Namespace, *pod.Spec.EnableServiceLinks)
	...
	var (
		configMaps = make(map[string]*v1.ConfigMap)
		secrets    = make(map[string]*v1.Secret)
		tmpEnv     = make(map[string]string)
	)
	...
    
	// 將剛剛所產生出的環境變數暫存檔加入到 result 這個 slice ，未來將作為 container 的環境變數
	for k, v := range tmpEnv {
		result = append(result, kubecontainer.EnvVar{Name: k, Value: v})
	}

	// 加入 service env 環境變數
	//簡單來說就是如果 pod yaml 有啟用 EnableServiceLinks 的話就需要把同一個 namespaces 所有的 service 
	//對應的名稱、IP 與 port 以環境變數的方式除存在 serviceENV 中
	for k, v := range serviceEnv {
		//如果 service env 的 key 暫存的環境變數不暫存這個  key 的話
        //加入 service env 的環境變數到 result 的 slice 中
		if _, present := tmpEnv[k]; !present {
			result = append(result, kubecontainer.EnvVar{Name: k, Value: v})
		}
	}
	return result, nil
}

```



## 小结

上面我们了解了kubernetes 的Manager 怎么实作并且如何取得对应的secret 资料了，基本上是依赖透过前两章内容[Kubernetes kubelet cacheBasedManager 现在才知道](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/kubernetes-kubelet/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)以及[Kubernetes kubelet cacheBasedManager 好喜欢objectcache 的原因](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/configmapsecret/kubernetes-kubelet-cacheobject/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)。

1. 透过cacheBasedManager 注册pod 的时候，将有使用到secret 的栏位提取出来建立对应的reflector ，并且将reflector 对应到objectStore 的objectCacheItem 中。

objectStore 主要用来储存reflector 观测到的物件状态（新增的情况）

- 如果有nginxA 以及nginxB 两个pod 同时都有xxx-secret 该怎么办
  - xxx-secret 对应xxx-objectCacheItem ， xxx-objectCacheItem 要记录有两个人reference 到。

1. 透过cacheBasedManager 反注册pod 的时候，将上一次pod 引用到secret 用到的栏位取出来，并且将对应的reflector 到objectStore 的objectCacheItem 进行移除。
   objectStore 主要用来储存reflector 观测到的物件状态（删除的情况）

- 如果nginx B 不再关注这个xxx-secret 要如何处理？
  - xxx-secret 对应xxx-objectCacheItem ， xxx-objectCacheItem 要记录现在有只有一个人reference 到。

1. GetSecret 我们透过namespace/name 向cacheBasedManager 的objectStore 取得对应的资料

- objectStore 会拿namespace/name 作为key 找到对应到objectCacheItem
  - objectCacheItem 内有reflector 可以把资料吐出来

以上为secret manager 的简易分析，如果对详细的步骤有疑虑的话建议先到前两章节去了解底层是如何处理的！!

文章中若有出现错误的见解希望各位在观看文章的大大们可以指出哪里有问题，让我学习改进，谢谢。
