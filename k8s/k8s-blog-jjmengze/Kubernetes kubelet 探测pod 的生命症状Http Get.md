首先本文所有的source code 基于kubernetes 1.19 版本，所有source code 为了版面的整洁会精简掉部分log 相关的程式码，仅保留核心逻辑，如果有见解错误的地方，还麻烦观看本文的大大们提出，感谢！

使用kubernetes 在建置自己的服务时，我们通常会透过kubernetes 所提供的探针`（probes）` 来探测pod 的特定服务是否正常运作。probes 主要用来进行自我修复的功能，例如今天某一只process 因为业务逻辑或是程式写错造成死锁的问题，我们就能透过probes 来重新启动pod 来恢复程式的运行。或是假设今天process 启动到真正可以提供外部存取提供服务，所花费的时间需要比较长的时候我们也会透过kubernetes 所提供的探针`（probes）` 来探测服务是不是可以提供外部使用。

综上所述probes 分成两种

1. liveness
   主要用来判断pod 是否正常运作，如果探测失败的话kubelet 会把pod 杀掉再重新建置。
2. readiness
   主要用来判断pod 是否可以提供给其他服务存取，如果探测失败的话kubelet 会把pod 从service backend 移除，这样的话其他服务就无法从service 存取到该服务。

今天主要跟大家分享是的kubernetes 怎么透过Http Get 去探测pod 的生命状态。

## probes

[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-http-get/pkg/kubelet/prober/prober.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

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
	//如果 err 不是 nil 或是 result 不是 Success 也不是 Warning
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
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-http-get/pkg/kubelet/prober/prober.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

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
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-http-get/pkg/kubelet/prober/prober.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
func (pb *prober) runProbe(probeType probeType, p *v1.Probe, pod *v1.Pod, status v1.PodStatus, container v1.Container, containerID kubecontainer.ContainerID) (probe.Result, string, error) {
	//設定太測多久會 timeout
	timeout := time.Duration(p.TimeoutSeconds) * time.Second
    
	//如果 pod 有設定 exec 的話，就會透過 pb.exec.Probe 進行探測，這一塊未來會再討論。
	if p.Exec != nil {
		//先打個要執行 exec 的 log     
		klog.V(4).Infof("Exec-Probe Pod: %v, Container: %v, Command: %v", pod.Name, container.Name, p.Exec.Command)
        
		//實際執行 exec prob 的部分這一塊未來會再討論。
		command := kubecontainer.ExpandContainerCommandOnlyStatic(p.Exec.Command, container.Env)
        
		//回傳執行結果
		return pb.exec.Probe(pb.newExecInContainer(container, containerID, command, timeout))
	}
    
    
	//如果 pod 有設定 HTTPGet 的話，就會透過 pb.HTTPGet.Probe 進行探測，今天主要討論這一塊。
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
	//如果有 pod 定義 tc
	//p socket 的話，就會透過 pb.tcp.Probe 進行探測，這一塊未來會再討論。
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
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-http-get/pkg/kubelet/prober/prober.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

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
[source code](vendor/k8s.io/apimachinery/pkg/util/intstr/intstr.go)
func (intstr *IntOrString) IntValue() int {
	//應該不會跑到這個方法，外面已經判斷過了，可能多一層做保障？
	if intstr.Type == String {
		i, _ := strconv.Atoi(intstr.StrVal)
		return i
	}
	return int(intstr.IntVal)
}

// 通過名稱查找 container 中的 Port。
[source code](pkg/kubelet/prober/prober.go)
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
[source code](pkg/kubelet/prober/prober.go)
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
[source code](pkg/kubelet/prober/prober.go)
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



### HTTP request

kubernetes worker 上的kubelet 会定期发送一个HTTP request 给pod 内的container ，如果HTTP status code 回传成功（400> code >= 200)，判断目前container 是否正常运作运作，若是不在这个status code 范围就会把pod 删掉。

[pods/probe/http-liveness.yaml](https://translate.google.com/website?sl=zh-TW&tl=zh-CN&hl=zh-CN&prev=search&u=https://raw.githubusercontent.com/kubernetes/website/master/content/en/examples/pods/probe/http-liveness.yaml)
范例是撷取自kubernetes 官方网站，撰写一个yaml 档送给kubernetes 告诉kubernetes 帮忙启动一个pod 并且建立一个livenessProbe ， livenessProbe 会透过http get 方法去判断pod:8080/healthz 并且header 加上http header key:Custom-Header value:Awesome。

```
apiVersion: v1
kind: Pod
metadata:
  labels:
    test: liveness
  name: liveness-http
spec:
  containers:
  - name: liveness
    image: k8s.gcr.io/liveness
    args:
    - /server
    livenessProbe:
      httpGet:
        path: /healthz
        port: 8080
        httpHeaders:
        - name: Custom-Header
          value: Awesome
      initialDelaySeconds: 3
      periodSeconds: 3


```



我们就以这个范例kubelet livenessProbe 的httpGet 底层是如何实现的吧，先从触发点来看

```
func (pb *prober) runProbe(probeType probeType, p *v1.Probe, pod *v1.Pod, status v1.PodStatus, container v1.Container, containerID kubecontainer.ContainerID) (probe.Result, string, error) {
...
	//如果 pod 有設定 HTTPGet 的話，就會透過 pb.HTTPGet.Probe 進行探測
	if p.HTTPGet != nil {
    
		//先把  HTTPGet.Scheme 轉成小寫，一般來說就是 http 或是 https
		scheme := strings.ToLower(string(p.HTTPGet.Scheme))
		...
        
		//本次要探測的型態，依照不同的探測型態去進行探測。
		switch probeType {
		//若為 liveness 就透過 liveness Probe function 去檢測
		case liveness:
			return pb.livenessHTTP.Probe(url, headers, timeout)
		case startup:
		... 

```



如果probeType 是liveness ，就会透过（非常非常非常外面注入的）livenessHTTP 物件去处理探针，至于怎么注入的…之后再找时间整理xD
[source code](https://blog-jjmengze-website.translate.goog/posts/kubernetes/source-code/kubelet/probe/kubernetes-kubelet-http-get/pkg/probe/http/http.go?_x_tr_sl=zh-TW&_x_tr_tl=zh-CN&_x_tr_hl=zh-CN&_x_tr_pto=sc)

```
// Probe returns a ProbeRunner capable of running an HTTP check.
func (pr httpProber) Probe(url *url.URL, headers http.Header, timeout time.Duration) (probe.Result, string, error) {
	//會從外部帶入要 prob 的 url 物件， header timeout 等參數。
    
	//先組成一個 http client ，Transport 這邊不是很重要先不要管他。
	client := &http.Client{
		Timeout:       timeout,
		Transport:     pr.transport,
		//設定轉發策略
		CheckRedirect: redirectChecker(pr.followNonLocalRedirects),
	}
	//執行 prob 探測，傳入 http client 以及 要探測的 url 物件以及 header。
	return DoHTTPProbe(url, headers, client)
}

func redirectChecker(followNonLocalRedirects bool) func(*http.Request, []*http.Request) error {
	//使用預設的 Redirects ，預設十次   
	if followNonLocalRedirects {
		return nil 
	}

	return func(req *http.Request, via []*http.Request) error {
		// 轉發得目標不等於原來要發送的目標，也是直接噴錯。
		if req.URL.Hostname() != via[0].URL.Hostname() {
			return http.ErrUseLastResponse
		}
		// Redirect >=10 就不轉發了，直接噴錯。
		if len(via) >= 10 {
			return errors.New("stopped after 10 redirects")
		}
		return nil
	}
}

// GetHTTPInterface 用於發出 HTTP 請求的 interface ，回傳 response 跟 error ，這個 interface http client 有實作，可以能是未來可以抽換用的吧？
type GetHTTPInterface interface {
	Do(req *http.Request) (*http.Response, error)
}

// DoHTTPProbe checks if a GET request to the url succeeds.
// If the HTTP response code is successful (i.e. 400 > code >= 200), it returns Success.
// If the HTTP response code is unsuccessful or HTTP communication fails, it returns Failure.
// This is exported because some other packages may want to do direct HTTP probes.
func DoHTTPProbe(url *url.URL, headers http.Header, client GetHTTPInterface) (probe.Result, string, error) {
	//透過 http NewRequest 建立 get 方法，目標為 url 物件的網址
	req, err := http.NewRequest("GET", url.String(), nil)
	// 建立 http requeset 失敗，報錯回傳
	if err != nil {
		// Convert errors into failures to catch timeouts.
		return probe.Failure, err.Error(), nil
	}
    
	//如果 header 沒有 User-Agent 的話，就主動幫他加入 header 與 User-Agent 的 key 以及 value 為 kube-probe/<Major version>.<Minor version>
	if _, ok := headers["User-Agent"]; !ok {
		if headers == nil {
			headers = http.Header{}
		}
		// explicitly set User-Agent so it's not set to default Go value
		v := version.Get()
		headers.Set("User-Agent", fmt.Sprintf("kube-probe/%s.%s", v.Major, v.Minor))
	}
    
	//將 Header 加入 request 
	req.Header = headers
	
	//如果 header 有 Host 的話就把 header 的 host 的數值加入到 requeset 的 host 
	if headers.Get("Host") != "" {
		req.Host = headers.Get("Host")
	}
    
	//透過外面注入進來的 http client 執行 requeset 的請求
	res, err := client.Do(req)
    
	//請求失敗，報錯回傳
	if err != nil {
		// Convert errors into failures to catch timeouts.
		return probe.Failure, err.Error(), nil
	}
	
	// defer 關閉 io 的讀取
	defer res.Body.Close()
    
	//讀取 request body ，並且限制 body 長度
	b, err := utilio.ReadAtMost(res.Body, maxRespBodyLength)
	//如果有錯誤的話就直接報錯，並且判斷是否超過 body 長度限制
	if err != nil {
		if err == utilio.ErrLimitReached {
			klog.V(4).Infof("Non fatal body truncation for %s, Response: %v", url.String(), *res)
		} else {
			return probe.Failure, "", err
		}
	}
    
	//讀出來的 body byte 轉成 string
	body := string(b)
    
	//判斷 StatusCode ，若是 StatusCode 介於 200 ~ 400 之間就當作成功，但是...StatusCode 為重新導向的話 （300） ，就回報 warring 。
	if res.StatusCode >= http.StatusOK && res.StatusCode < http.StatusBadRequest {
    
		//StatusCode 為重新導向的話 （300） ，就回報 warring 。
		if res.StatusCode >= http.StatusMultipleChoices { // Redirect
			klog.V(4).Infof("Probe terminated redirects for %s, Response: %v", url.String(), *res)
			return probe.Warning, body, nil
		}
        
		//StatusCode 不為 300 的 200 ~ 400 其他狀況回報成功
		klog.V(4).Infof("Probe succeeded for %s, Response: %v", url.String(), *res)
		return probe.Success, body, nil
	}
    
	//其他不是 200 ~ 400 的狀況登回報錯誤
	klog.V(4).Infof("Probe failed for %s with request headers %v, response body: %v", url.String(), headers, body)
	return probe.Failure, fmt.Sprintf("HTTP probe failed with statuscode: %d", res.StatusCode), nil
}

// ReadAtMost 可以從 Reader 中讀取 byte 如果 body 大於 limit 的話就報錯～
func ReadAtMost(r io.Reader, limit int64) ([]byte, error) {
	limitedReader := &io.LimitedReader{R: r, N: limit}
	data, err := ioutil.ReadAll(limitedReader)
	if err != nil {
		return data, err
	}
	if limitedReader.N <= 0 {
		return data, ErrLimitReached
	}
	return data, nil
}

```



## 小结

以上为kubelet 探测pod 的生命症状Http Get 简易分析，简单来说kubernetes worker node 上的kubelet process 会有一只worker 的thread 建立一个探针，该worker 会把pod prob spec 解析出来并建立对应的探针（这一部份在后续会揭露），本篇以prob 为http get 为例。

我们看到了http status code 介于200 ~ 400 之间就当作成功，此外StatusCode 为重新导向的话（300） ，就回报warring ，下一章节将会针对kubelet 如何透过exec Prob 探测pod 的生命症状。

文章中若有出现错误的见解希望各位在观看文章的大大们可以指出哪里有问题，让我学习改进，谢谢。
