首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

本篇文章基于[Kubernetes kubelet 探测pod 的生命症状Http Get](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-http-get/?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)继续往上盖的违建（Ｘ），大部分的内容都差不多，如有看过前一篇的并且建立基础观念的朋友可以直接滑到最下面看kubernetes kubelet 如何透过exec 探测pod 的生命症状。

使用kubernetes 在建置自己的服务时，我们通常会透过kubernetes 所提供的探针`（probes）` 来探测pod 的特定服务是否正常运作。probes 主要用来进行自我修复的功能，例如今天某一只process 因为业务逻辑或是程式写错造成死锁的问题，我们就能透过probes 来重新启动pod 来恢复程式的运行。或是假设今天process 启动到真正可以提供外部存取提供服务，所花费的时间需要比较长的时候我们也会透过kubernetes 所提供的探针`（probes）` 来探测服务是不是可以提供外部使用。

综上所述probes 分成两种

1. liveness
   主要用来判断pod 是否正常运作，如果探测失败的话kubelet 会把pod 杀掉再重新建置。
2. readiness
   主要用来判断pod 是否可以提供给其他服务存取，如果探测失败的话kubelet 会把pod 从service backend 移除，这样的话其他服务就无法从service 存取到该服务。

今天主要跟大家分享是的kubernetes 怎么透过liveness probes 的exec 去探测pod 的生命状态。

## probes

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-exec/pkg/kubelet/prober/prober.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// probe probes the container.
func (pb *prober) probe(probeType probeType, pod *v1.Pod, status v1.PodStatus, container v1.Container, containerID kubecontainer.ContainerID) (results.Result, error) {
	var probeSpec *v1.Probe
	//首先判斷這次要執行探真的是哪一種類別，分別有readiness、liveness、startup
	switch probeType {
	//如果判斷是readiness就需要載入 container spec ReadinessProbe 寫的要求
	case readiness:
		probeSpec = container.ReadinessProbe
	//如果判斷是liveness就需要載入 container spec LivenessProbe 寫的要求
	case liveness:
		probeSpec = container.LivenessProbe
	//如果判斷是startup就需要載入 container spec StartupProbe 寫的要求
	case startup:
		probeSpec = container.StartupProbe
	//不是上述這三種的話 kubernetes 目前無法處理。    
	default:
		return results.Failure, fmt.Errorf("unknown probe type: %q", probeType)
	}
	
    
	ctrName := fmt.Sprintf("%s:%s", format.Pod(pod), container.Name)
	//如果 pod 裡面沒有定義 probe 的話就當作探測成功
	if probeSpec == nil {
		klog.Warningf("%s probe for %s is nil", probeType, ctrName)
		return results.Success, nil
	}
	//傳入探針型態，探針規格，pod狀態，pod spec，以及要探測哪一個 container，以及重試次次數。
	//接著會依照探測結果進行不同策略
	result, output, err := pb.runProbeWithRetries(probeType, probeSpec, pod, status, container, containerID, maxProbeRetries)
	//如果 err 不是 nil 或是 result 不是 Success 同時不是 Warning，就要進行 log 處理
	if err != nil || (result != probe.Success && result != probe.Warning) {
		// Probe failed in one way or another.
		//簡單來說就是紀錄哪個 pod 哪個 container 發生了探針探測結果 ContainerUnhealthy，以及印一下 log 。
		if err != nil {
			klog.V(1).Infof("%s probe for %q errored: %v", probeType, ctrName, err)
			pb.recordContainerEvent(pod, &container, v1.EventTypeWarning, events.ContainerUnhealthy, "%s probe errored: %v", probeType, err)
		} else { // result != probe.Success
			klog.V(1).Infof("%s probe for %q failed (%v): %s", probeType, ctrName, result, output)
			pb.recordContainerEvent(pod, &container, v1.EventTypeWarning, events.ContainerUnhealthy, "%s probe failed: %s", probeType, output)
		}
		return results.Failure, err
	}
	//如果 result 是 Warning ，簡單來說就是紀錄哪個 pod 哪個 container 發生了探針探測結果 warning，以及印一下 log 。
	if result == probe.Warning {
		pb.recordContainerEvent(pod, &container, v1.EventTypeWarning, events.ContainerProbeWarning, "%s probe warning: %s", probeType, output)
		klog.V(3).Infof("%s probe for %q succeeded with a warning: %s", probeType, ctrName, output)
	} 
	//不然就是成功，發個 log 沒什麼其他的用途。
	else {
		klog.V(3).Infof("%s probe for %q succeeded", probeType, ctrName)
	}
	//回傳一下探測結果    
	return results.Success, nil
}


```



`runProbeWithRetries`主要传入探针型态，探针规格，pod状态，pod spec，以及要探测哪一个container，接着透过runProbe function 去执行探测。

```
// runProbeWithRetries tries to probe the container in a finite loop, it returns the last result
// if it never succeeds.
func (pb *prober) runProbeWithRetries(probeType probeType, p *v1.Probe, pod *v1.Pod, status v1.PodStatus, container v1.Container, containerID kubecontainer.ContainerID, retries int) (probe.Result, string, error) {
	//探針錯誤訊息
	var err error
	//探針結果
	var result probe.Result
	//探針結果
	var output string
	//若是失敗需要探測的總次數
	for i := 0; i < retries; i++ {
		//開始探測，帶入探針型態，探針規格，pod狀態，pod spec，以及要探測哪一個 container。    
		result, output, err = pb.runProbe(probeType, p, pod, status, container, containerID)
		//如果探測成功直接回傳
		if err == nil {
			return result, output, nil
		}
	}
	//如果探測失敗達到重試次數
	return result, output, err
}

```



`runProbe`function 主要是执行探针探测的动作。

```
func (pb *prober) runProbe(probeType probeType, p *v1.Probe, pod *v1.Pod, status v1.PodStatus, container v1.Container, containerID kubecontainer.ContainerID) (probe.Result, string, error) {
	//設定太測多久會 timeout
	timeout := time.Duration(p.TimeoutSeconds) * time.Second
    
	//如果 pod 有設定 exec 的話，就會透過 pb.exec.Probe 進行探測，今天主要討論這一塊。
	if p.Exec != nil {
		//先打個要執行 exec 的 log     
		klog.V(4).Infof("Exec-Probe Pod: %v, Container: %v, Command: %v", pod.Name, container.Name, p.Exec.Command)
        
		//實際執行 exec prob 本篇主要討論的對象
		command := kubecontainer.ExpandContainerCommandOnlyStatic(p.Exec.Command, container.Env)
        
		//回傳執行結果
		return pb.exec.Probe(pb.newExecInContainer(container, containerID, command, timeout))
	}
    
    
	//如果 pod 有設定 HTTPGet 的話，就會透過 pb.HTTPGet.Probe 進行探測,上一篇[Kubernetes kubelet 探測 pod 的生命症狀 Http Get](https://blog.jjmengze.website/posts/kubernetes/source-code/kubelet/prob/kubernetes-kubelet-http-get/)主要在討論這一塊。
	if p.HTTPGet != nil {
    
    
		//先把  HTTPGet.Scheme 轉成小寫，一般來說就是 http 或是 https
		scheme := strings.ToLower(string(p.HTTPGet.Scheme))
        
		//取出目標 host 位置
		host := p.HTTPGet.Host
		//如果目標 host 位置為空，預設用 pod 本身的 ip
		if host == "" {
			host = status.PodIP
		}
        
        //取出 pod 裡面指定 prob 的 port 號，有可能有人寫成 port: "http"或是寫成 port: 80 又或是 port : "80"
        //因此不能做簡單的提取
		port, err := extractPort(p.HTTPGet.Port, container)
		if err != nil {
			return probe.Unknown, "", err
		}
        
		//取出目標探測目標位置的路徑
		path := p.HTTPGet.Path
        
		klog.V(4).Infof("HTTP-Probe Host: %v://%v, Port: %v, Path: %v", scheme, host, port, path)
        
		//把 scheme 、 host 、 port 、 path 組成  url 物件
		url := formatURL(scheme, host, port, path)
        
		//填充這次要探測的 http header
		headers := buildHeader(p.HTTPGet.HTTPHeaders)
		klog.V(4).Infof("HTTP-Probe Headers: %v", headers)
        
		//本次要探測的型態，依照不同的探測型態去進行探測。
		switch probeType {
		//若為 liveness 就透過 liveness Probe function 去檢測
		case liveness:
			return pb.livenessHTTP.Probe(url, headers, timeout)
            
		//若為 startupHTTP 就透過 startupHTTP Probe function 去檢測
		case startup:
			return pb.startupHTTP.Probe(url, headers, timeout)
            
		//若為 readinessHTTP 就透過 readinessHTTP Probe function 去檢測
		default:
			return pb.readinessHTTP.Probe(url, headers, timeout)
		}
	}
	//如果有 pod 定義 tcp socket 的話，就會透過 pb.tcp.Probe 進行探測，這一塊未來會再討論。
	if p.TCPSocket != nil {
    
		//取出 pod 裡面指定 prob 的 port 號，有可能有人寫成 port: "http"或是寫成 port: 80 又或是 port : "80"
		//因此不能做簡單的提取
		port, err := extractPort(p.TCPSocket.Port, container)
		if err != nil {
			return probe.Unknown, "", err
		}
        
		//取出目標 host 的位置
		host := p.TCPSocket.Host
		//如果目標 host 位置為空，預設用 pod 本身的 ip
		if host == "" {
			host = status.PodIP
		}
        
		klog.V(4).Infof("TCP-Probe Host: %v, Port: %v, Timeout: %v", host, port, timeout)
        //實際執行 tcp prob 的部分這一塊未來會再討論。
		return pb.tcp.Probe(host, port, timeout)
	}
	klog.Warningf("Failed to find probe builder for container: %v", container)
    
	//不屬於以上三種的 kubernetes 目前不支援呦，所以會還傳結果probe.Unknown，以及不支援 probe 的錯誤。
	return probe.Unknown, "", fmt.Errorf("missing probe handler for %s:%s", format.Pod(pod), container.Name)
}

```



针对上述用到的function 进行一些补充～

```
//仔細觀察參數的話，第一個輸入的 param 型態為 intstr.IntOrString，這個型態是什麼東西呢？
//依照文件的註解為IntOrString是可以含有 int32 或  string 的類型。在 JSON / YAML marshalling and unmarshalling 時使用，簡單來說使用者可以傳入 string 或是 int 的型態進來。
func extractPort(param intstr.IntOrString, container v1.Container) (int, error) {
	port := -1
	var err error
	//第一步我們需要先去解析傳入的 port 是什麼型態來做對應的解析。
	switch param.Type {
	//如果是 INT 的話，就把 port 以 int 的方式對出
	case intstr.Int:
		port = param.IntValue()
	//如果是 INT 的話，就把 port 以 int 的方式對出
	case intstr.String:
		//通過名稱查找 container 中的 Port。
		if port, err = findPortByName(container, param.StrVal); err != nil {
			// 覺得註解很有趣，保留下來，最後一搏，嘗試將 string 轉成 int 有可能使用者定義 port : "8080"，試試看這樣可不可以轉成功
			// Last ditch effort - maybe it was an int stored as string?
			if port, err = strconv.Atoi(param.StrVal); err != nil {
				return port, err
			}
		}
	// Type 無法處理
	default:
		return port, fmt.Errorf("intOrString had no kind: %+v", param)
	}
	// port 在 0 ~ 65536 這個區間內才有效    
	if port > 0 && port < 65536 {
		return port, nil
	}
	//回傳解析的 port 為多少
	return port, fmt.Errorf("invalid port number: %v", port)
}

//上面有看到透過 param.IntValue() 把 intstr.IntOrString 為 int type 的轉換成 int 是透過這個方法
func (intstr *IntOrString) IntValue() int {
	//應該不會跑到這個方法，外面已經判斷過了，可能多一層做保障？
	if intstr.Type == String {
		i, _ := strconv.Atoi(intstr.StrVal)
		return i
	}
	return int(intstr.IntVal)
}

// 通過名稱查找 container 中的 Port。
func findPortByName(container v1.Container, portName string) (int, error) {
	//透過傳入的 container port 透過迴圈找尋 port 名稱對應到的實際 port 號，以 int 的方式回傳。
	for _, port := range container.Ports {
		if port.Name == portName {
			return int(port.ContainerPort), nil
		}
	}
	return 0, fmt.Errorf("port %s not found", portName)
}



// formatURL 格式化 args 中的 URL。
func formatURL(scheme string, host string, port int, path string) *url.URL {
	// 透過 url package 的 parse function 將 url 去解析。
	u, err := url.Parse(path)
	//不知道這個錯誤什麼時候會出現...先保留註解，求大大幫看xD
	// Something is busted with the path, but it's too late to reject it. Pass it along as is.
	if err != nil {
		u = &url.URL{
			Path: path,
		}
	}
	// url 加上 scheme
	u.Scheme = scheme
    // url host 加上 host:port 
	u.Host = net.JoinHostPort(host, strconv.Itoa(port))
	return u
}

//把 pod spec prob header 加入到 prob 的請求中。
func buildHeader(headerList []v1.HTTPHeader) http.Header {
	//建立一個 head slice 
	headers := make(http.Header)
	//把 pod spec prob header 透過 for rnge 的方式加入到 prob 的請求中。
	for _, header := range headerList {
		headers[header.Name] = append(headers[header.Name], header.Value)
	}
	return headers
}

```



### exec

kubernetes worker 上的kubelet 会定期发送一个HTTP request 给pod 内的container ，如果HTTP status code 回传成功（400> code >= 200)，判断目前container 是否正常运作运作，若是不在这个status code 范围就会把pod 删掉。

[pods/probe/exec-liveness.yaml](https://translate.google.com/website?sl=zh-TW&tl=zh-CN&hl=zh-CN&prev=search&u=https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/%23define-a-liveness-command)
范例是撷取自kubernetes 官方网站，撰写一个yaml 档送给kubernetes 告诉kubernetes 帮忙启动一个pod 并且建立一个livenessProbe ， livenessProbe 会透过exec 方法去判断cat /tmp/healthy 执行指令是否执行成功。

```
apiVersion: v1
kind: Pod
metadata:
  labels:
    test: liveness
  name: liveness-exec
spec:
  containers:
  - name: liveness
    image: k8s.gcr.io/busybox
    args:
    - /bin/sh
    - -c
    - touch /tmp/healthy; sleep 30; rm -rf /tmp/healthy; sleep 600
    livenessProbe:
      exec:
        command:
        - cat
        - /tmp/healthy
      initialDelaySeconds: 5
      periodSeconds: 5

```



我们就以这个范例kubelet livenessProbe 的exec 底层是如何实现的吧，先从触发点来看

```
func (pb *prober) runProbe(probeType probeType, p *v1.Probe, pod *v1.Pod, status v1.PodStatus, container v1.Container, containerID kubecontainer.ContainerID) (probe.Result, string, error) {
...
	//如果 pod 有設定 exec 的話，就會透過 pb.exec.Probe 進行探測
	if p.Exec != nil {
		//印一下 log 看要執行甚麼指令，執行指令的 pod 與 container 是哪一個
		klog.V(4).InfoS("Exec-Probe runProbe", "pod", klog.KObj(pod), "containerName", container.Name, "execCommand", p.Exec.Command)
		//把 command 與 container 的環境變數進行整理        
		command := kubecontainer.ExpandContainerCommandOnlyStatic(p.Exec.Command, container.Env)
		//整理完的 command 與 container 環境變數透過帶入 container id 去告知 pb.exec.Probe function 哪個 container 要執行什麼指令。
		return pb.exec.Probe(pb.newExecInContainer(container, containerID, command, timeout))
	}
	//如果有設定 http get 的話，上一篇主要在討論這一塊    
	if p.HTTPGet != nil {
    ...

```



如果探针型态为exec 的话，就会透过（非常非常非常外面注入的）exec 物件去处理探针，至于怎么注入的…之后再找时间整理xD

我们来看一下怎模透过ExpandContainerCommandOnlyStatic 这个function 整理env 环境变数与cmd 指令吧～
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-exec/pkg/kubelet/container/helpers.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```

// ExpandContainerCommandOnlyStatic substitutes only static environment variable values from the
// container environment definitions. This does *not* include valueFrom substitutions.
// TODO: callers should use ExpandContainerCommandAndArgs with a fully resolved list of environment.
func ExpandContainerCommandOnlyStatic(containerCommand []string, envs []v1.EnvVar) (command []string) {
	//v1EnvVarsToMap function 將 container []v1.EnvVar 環境變數轉換成 map[string]string 形式
    //接著把 map[string]string 透過 expansion.MappingFuncFor 封裝成一個function 
	mapping := expansion.MappingFuncFor(v1EnvVarsToMap(envs))
	//先判斷 containerCommand 是否為零，若是不為 0 的話需要進行整理
	if len(containerCommand) != 0 {
		//透過迴圈把數入的 command 透過 expansion.Expand 一個一個處理
		for _, cmd := range containerCommand {
			command = append(command, expansion.Expand(cmd, mapping))
		}
	}
	return command
}
//v1EnvVarsToMap 將 container []v1.EnvVar 環境變數轉換成 map[string]string 形式
func v1EnvVarsToMap(envs []v1.EnvVar) map[string]string {
	//建立map[string]string ，並且遞迴 envs 將環境變數以存入map。
	result := map[string]string{}
	for _, env := range envs {
		result[env.Name] = env.Value
	}

	return result
}
//傳入 map[string]string 形式的環境變數回傳一個 function ，這個 function 會檢查傳入的 string 
//是否有包含環境變數的 key ，如果有的話直接回傳包含的環境變數，若是沒有需要透過 syntaxWrap function 封裝一層
func MappingFuncFor(context ...map[string]string) func(string) string {
	return func(input string) string {
		//透過 for 迴圈把環境變數全部跑一次
		for _, vars := range context {
			//檢查 map 是否有對應 key 拿出對應的 value 回傳
			val, ok := vars[input]
			if ok {
				return val
			}
		}

		return syntaxWrap(input)
	}
}

const (
	operator        = '$'
	referenceOpener = '('
	referenceCloser = ')'
)
// syntaxWrap function 回傳 shell 語法所需要的字串，例如 cat /etc/resolv.conf，會透過這個方法封裝成$(cat /etc/resolv.conf)
func syntaxWrap(input string) string {
	return string(operator) + string(referenceOpener) + input + string(referenceCloser)
}

// 透過 Expand function 把 cmd 所含的環境變提取出來
// 我這邊假設三種情境（有點複雜只能透過這樣，呈現給大家，請見諒）
// 我們都假設環境變數都存在
//情境A input : ls -al $(VAR_A)
//情境B input : kubectl apply -f $(VAR_A) -f $(VAR_B)
//情境C input : kubectl apply -f $(VAR_A)-1
func Expand(input string, mapping func(string) string) string {
	//先建立一個bytes.Buffer
	var buf bytes.Buffer
	//checkpoint從0開始
	checkpoint := 0
	//開始遞迴 input 情境
	// Ａ 的 input ls -al $(VAR_A)
	// B input : kubectl apply -f $(VAR_A) -f $(VAR_B)
	// C input : kubectl apply -f $(VAR_A)-1
	for cursor := 0; cursor < len(input); cursor++ {
		//case A input : ls -al $(VAR_A)
		//前面的ls -al 都不會處理，直到遇到第一個 $ 才會開始處理，這時候 cursor 是 7 也就是指到 input 的 $ 位置上。
		if input[cursor] == operator && cursor+1 < len(input) {
			// 從 check point 到 cursor 之間的數值寫到 buf 中
			//case A input : ls -al $(VAR_A)
            // 這時 check point 為 0 到 cursor 7 之間的數值寫到 buf 中，也就是 "ls -al " 會寫到 buf
			buf.WriteString(input[checkpoint:cursor])

			// Attempt to read the variable name as defined by the
			// syntax from the input string
			
			//用以判斷輸入的字串是否有包含環境變數，如果有環境變數的話回傳
			//advance 是為了環境變數之後還有其他指令，下次可以從該index開始找環境變數           
			//case A input : ls -al $(VAR_A)
			//read 為 環境變數例如 VAR_A
			//isVar 為 true 表示為環境變數
			//advance 為 從 (VAR_A) 的 ( 算到 ) +1 的 index (index 從0 開始算) ，case A 例子為 7 。
			
			read, isVar, advance := tryReadVariableName(input[cursor+1:])
			//如果有包含變數的話需要從環境變數中找到對應的value，並且寫入buffer
			if isVar {
				//從外部傳來的 mapping function 找到對應的環境變數，如果忘記的朋友可以上去複習一下 MappingFuncFor
				//case A input : ls -al $(VAR_A)
				//在這個情境下我們 透過 mapping function 找 VAR_A 對應的環境變數，我們這裡假設為example.yaml
				buf.WriteString(mapping(read))
			} else {
				// 如果不包含環境變數的話接寫入 buffer
				buf.WriteString(read)
			}

			//把 cursor 指標往前移到剛剛判斷是否為環境變數的地方，因為要從剛剛判斷環境變數的地方繼續跑
			//case A input : ls -al $(VAR_A)
			//在這個情境下 cursor 會移動到 14 的位置上
			cursor += advance

			//check point 往前移動
			//case A input : ls -al $(VAR_A)
			//在這個情境下 cursor 會移動到 15 的位置上
			checkpoint = cursor + 1
		}
	}

	//把 buffer 與剩下的 cmd 全部倒入並且回傳
	//case A input : ls -al $(VAR_A)
	//在這個情境下會回傳剛剛寫入到 buffer 的東東要全部倒出來 ls -al example.yaml
	return buf.String() + input[checkpoint:]
}


//雖然離得有點遠這一段是在 runProbe function 中被呼叫的，忘記的可以回去複習。

//回傳一個實作 exec.Cmd interface 的物件，這邊實作的對象是 execInContainer struct，並且帶入run function 為 pb.runner.RunInContainer，等等下面會來看
//exec.Cmd interface  以及實作 execInContainer 的部分
func (pb *prober) newExecInContainer(container v1.Container, containerID kubecontainer.ContainerID, cmd []string, timeout time.Duration) exec.Cmd {
	// exec.Cmd interface 被 execInContainer 實作，我們等等來看 execInContainer 底層的實作。
	// execInContainer 帶入 run function ，這裡的 function 採用的是 pb.runner.RunInContainer(containerID, cmd, timeout)
	return &execInContainer{run: func() ([]byte, error) {
		return pb.runner.RunInContainer(containerID, cmd, timeout)
	}}
}

// RunInContainer synchronously executes the command in the container, and returns the output.
func (m *kubeGenericRuntimeManager) RunInContainer(id kubecontainer.ContainerID, cmd []string, timeout time.Duration) ([]byte, error) {
	stdout, stderr, err := m.runtimeService.ExecSync(id.ID, cmd, timeout)
	// NOTE(tallclair): This does not correctly interleave stdout & stderr, but should be sufficient
	// for logging purposes. A combined output option will need to be added to the ExecSyncRequest
	// if more precise output ordering is ever required.
	return append(stdout, stderr...), err
} 

```



[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-exec/exec/exec.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)
Cmd 是一个interface，它提供了一个与os/exec 中的Cmd 非常相似的API。

```
type Cmd interface {
	// Run runs the command to the completion.
	Run() error
	// CombinedOutput runs the command and returns its combined standard output
	// and standard error. This follows the pattern of package os/exec.
	CombinedOutput() ([]byte, error)
	// Output runs the command and returns standard output, but not standard err
	Output() ([]byte, error)
	SetDir(dir string)
	SetStdin(in io.Reader)
	SetStdout(out io.Writer)
	SetStderr(out io.Writer)
	SetEnv(env []string)

	// StdoutPipe and StderrPipe for getting the process' Stdout and Stderr as
	// Readers
	StdoutPipe() (io.ReadCloser, error)
	StderrPipe() (io.ReadCloser, error)

	// Start and Wait are for running a process non-blocking
	Start() error
	Wait() error

	// Stops the command by sending SIGTERM. It is not guaranteed the
	// process will stop before this function returns. If the process is not
	// responding, an internal timer function will send a SIGKILL to force
	// terminate after 10 seconds.
	Stop()
}

```



我们来看一下实作Cmd interface 的结构体，这个struct 就是上面exec prob 呼叫的，由于interface 有许多signature 以下范例我们只看prob exec 会用到的。

```
type execInContainer struct {
	// run function 就是在 container 中執行命令。執行結束會回傳 stdout 和 stderr 。如果執行 function 發生錯誤，透過 error 回傳。
	run    func() ([]byte, error)
	writer io.Writer
}
```



前面讲了很多相关的东西只为了组出实作exec.cmd interface 的物件，execProber 的prob function 用，我们就来看看底层是怎么处理。

```
func (pr execProber) Probe(e exec.Cmd) (probe.Result, string, error) {
	//建立一個 bytes buffer
	var dataBuffer bytes.Buffer
	writer := ioutils.LimitWriter(&dataBuffer, maxReadLength)

	//設定cmd的 stderr 以及 stdout 為 LimitWriter 的 writer
	e.SetStderr(writer)
	e.SetStdout(writer)
    
	//開始執行cmd    
	err := e.Start()
	//判斷 cmd 執行錯誤，若是錯誤為 nil 就執行 cmd wait ，雖然 wait 沒有做任何事情。（不清楚為什麼要這樣設計，跪求大大提點）
	if err == nil {
		err = e.Wait()
	}

	//從 buffer 中取回 byte
	data := dataBuffer.Bytes()

	//印 log 看執行會傳什麼 response 
	klog.V(4).Infof("Exec probe response: %q", string(data))
    

	//如果 cmd 執行錯誤的話
	if err != nil {
		//先把 error 轉成  exec.ExitError  
		exit, ok := err.(exec.ExitError)
		//如果轉換換成功的話 ，進一步判斷ExitStatus是否為正常退出
		//也就是 ExitStatus = 0 ，如果 ExitStatus 不為 0 就當作失敗。
		if ok {
			if exit.ExitStatus() == 0 {
				return probe.Success, string(data), nil
			}
            
			return probe.Failure, string(data), nil
		}
		//轉換失敗當做未知錯誤來處理
		return probe.Unknown, "", err
	}
    //如果 cmd 執行沒有錯誤的話 直接回傳 success 處理
	return probe.Success, string(data), nil
}

```



## 小结

以上为kubelet 探测pod 的生命症状- exec 简易分析，简单来说kubernetes worker node 上的kubelet process 会有一只worker 的thread 建立一个探针，该worker 会把pod prob spec 解析出来并建立对应的探针，本篇以prob 为exec 为例。

我们看到了exec 执行的结果回传为0 的话就当作当作成功，其他结果都回报Failure ，下一章节将会针对kubelet 如何透过TCP Prob 探测pod 的生命症状。

文章中若有出现错误的见解希望各位在观看文章的大大们可以指出哪里有问题，让我学习改进，谢谢。
