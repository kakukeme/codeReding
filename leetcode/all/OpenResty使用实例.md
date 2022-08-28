# OpenResty使用实例

## OpenResty是什么

- OpenResty是什么，官网是这样介绍的：

  > 通过 Lua 扩展 NGINX 实现的可伸缩的 Web 平台

- 的确，OpenResty可以简单的理解为Nginx + Lua，通过Lua库引入数据库访问能力，真正的让Nginx向搭建能够处理超高并发、扩展性极高的动态 Web 应用、Web 服务和动态网关这一目标迈出了重要的一步

## OpenResty的配置

- OpenResty的配置可以分为2类
  - lua脚本
  - Nginx配置文件
- 下面列举几个常见场景的Nginx配置

### 静态文件（页面）服务器配置

```nginx
server {
    listen 80;
    server_name ${hostname};
    rewrite ^(.*)$  https://${hostname}$1 permanent;
}

server {
    listen       443;
    server_name  ${hostname};

    # ssl证书文件位置(常见证书文件格式为：crt/pem)
    ssl_certificate      /etc/nginx/ssl/${hostname}.pem;
    # ssl证书key位置
    ssl_certificate_key  /etc/nginx/ssl/${hostname}.key;
    ssl_session_timeout  10m;
    ssl_protocols TLSv1 TLSv1.1 TLSv1.2;
    ssl_ciphers ECDHE-RSA-AES128-GCM-SHA256:ECDHE:ECDH:AES:HIGH:!NULL:!aNULL:!MD5:!ADH:!RC4;
    ssl_prefer_server_ciphers  on;

    root        /etc/nginx/dist;
    index index.html index.htm;
}
复制代码
```

- 将静态页面文件映射到OpenResty容器内的`/etc/nginx/dist`内即可

### 反向代理

```nginx
server {
    listen 80;
    server_name ${hostname};
    rewrite ^(.*)$  https://${hostname}$1 permanent;
}

server {
    listen          443 ssl;
    server_name     ${hostname};

    # ssl证书文件位置(常见证书文件格式为：crt/pem)
    ssl_certificate      /etc/nginx/ssl/auth-cert.pem;
    
    # ssl证书key位置
    ssl_certificate_key  /etc/nginx/ssl/auth-cert.key;
    ssl_session_timeout  10m;
    ssl_protocols TLSv1 TLSv1.1 TLSv1.2;
    ssl_ciphers ECDHE-RSA-AES128-GCM-SHA256:ECDHE:ECDH:AES:HIGH:!NULL:!aNULL:!MD5:!ADH:!RC4;
    ssl_prefer_server_ciphers  on;

    location / {
    
         proxy_set_header  Host  $host;
         proxy_set_header  X-Forwarded-Proto $scheme;
         proxy_set_header  X-Forwarded-For $host;
         proxy_set_header  Upgrade $http_upgrade;
         proxy_set_header  Connection 'Upgrade';
    		 proxy_http_version 1.1;
         proxy_set_header  X-Real-IP $remote_addr;
         
         proxy_pass    http://${target}/;
    }
}
复制代码
```

- **${target}就是反向代理的目标服务器地址或域名，注意不要丢掉后边的`/`**

### 动态路由设置

- **需求说明：根据请求参数动态转发到不同的服务器、端口**，比如`hostname/users/1/info/2` 转发到`hosname1:9200`，`hostname/users/3/info/4`转发到`hostname2:8080`

![image-20210314234123950](assets/f2cbbc0d41574eb9bcbc3a1d8fae5ed5~tplv-k3u1fbpfcp-zoom-in-crop-mark:3024:0:0:0.awebp)

- 在`/opt/openresty/lua/`目录下创建`split.lua`

  ```lua
  echo '
  --[[ 
  拆分字符串 
  e.g. /a/b/c  
  table[1] a
  table[2] b
  table[3] c
  --]]
  function split(str, pat)
      local t = {}
      local fpat = "(.-)" .. pat
      local last_end = 1
      local s, e, cap = str:find(fpat, 1)
      while s do
          if s ~= 1 or cap ~= "" then
              table.insert(t, cap)
          end
          last_end = e + 1
          s, e, cap = str:find(fpat, last_end)
      end
      if last_end <= #str then
          cap = str:sub(last_end)
          table.insert(t, cap)
      end
      return t
  end
  
  function split_path(str)
      return split(str, '[\\/]+')
  end
  ' > /opt/openresty/lua/split.lua
  复制代码
  ```

  - 此脚本用来解析请求参数

- 在`/opt/openresty/lua/`目录下创建`query_redis.lua`

  ```lua
  echo ' 
  -- redis结果解析,导入redis.parser脚本
  local parser = require "redis.parser"
  
  -- ngx.var.uri只包含路径参数，不包含主机与端口
  -- 调用worker启动时引入的lua脚本中提供的函数
  local parameters = split_path(ngx.var.uri)
  
  -- 访问的是根路径
  if(#parameters == 0) then
     ngx.exit(ngx.HTTP_FORBIDDEN)
  end
  
  -- 拆分出查询参数
  user_id = parameters[2]
  info_id = parameters[4]
  
  ngx.log(ngx.EMERG, "user_id--->", user_id)
  ngx.log(ngx.EMERG, "info_id--->", info_id)
  
  -- 组合参数
  key = "DYNA"
  id = user_id .. "_" .. info_id
  
  -- 向redis查询
  res = ngx.location.capture(
             "/redis", { args = { key = key, id = id } }
  )
  
  -- 查询失败
  if res.status ~= 200 then
             ngx.log(ngx.ERR, "redis server returned bad status: ",
                 res.status)
             ngx.exit(res.status)
  end
  
  -- 结果为空
  if not res.body then
             ngx.log(ngx.ERR, "redis returned empty body")
             ngx.exit(500)
  end
  
  -- raw tcp response from redis server
  -- 共2条返回所以应该使用parse_replies(res.body, 2) 
  -- e.g.
  -- OK
  -- 172.17.144.4:8080
  ngx.log(ngx.EMERG, "raw response ----->", res.body)
  
  local results = parser.parse_replies(res.body, 2)
  for i, result in ipairs(results) do
  if i == 2 then
        server = result[1]
        typ = result[2]
    end
  end
  
  -- 检查结果类型
  if typ ~= parser.BULK_REPLY or not server then
             ngx.exit(500)
  end
  
  -- 返回value为空
  if server == "" then
             server = "default.com"
  end
  
  ngx.var.target = server
  ngx.log(ngx.EMERG, "key--->", key)
  ngx.log(ngx.EMERG, "id--->", id)
  ngx.log(ngx.EMERG, "service--->", server)
  '   > /opt/openresty/lua/query_redis.lua
  复制代码
  ```

  - 本脚本根据解析得到的请求参数`${user_id}_${info_id}`查询Redis中对应的target即代理目标
  - 上述的lua脚本中，假设Redis存储着以`DYNA`为key的hash表，hash表的key是由用户请求中解析出的user_id和info_id使用`_`组合而成，对应的value就是要转发到的目标target

- 在`/opt/openresty/conf.d/`目录下创建`dynamicRouter.conf`

  ```bash
  echo '
  # 启用主进程后，在每次Nginx工作进程启动时运行指定的Lua代码
  init_worker_by_lua_file /usr/local/openresty/nginx/lua/split.lua;
  server {
     listen       443;
     server_name  ${hostname};
     # redis交互库是openresty的内置的库
     location = /redis {
         # Specifies that a given location can only be used for internal requests
         internal;
         redis2_query auth ${redis_password};
         # 解析请求参数
         set_unescape_uri $id $arg_id;
         set_unescape_uri $key $arg_key;
         # 执行redis查询请求
         redis2_query hget $key $id;
         # 查询请求转发到指定的redis_server
         redis2_pass redis:6379;
     }
  
     location / {
        # 设置一个内嵌脚本的共享变量
        set $target '';  
        # 引入内嵌脚本
        access_by_lua_file /usr/local/openresty/nginx/lua/query_redis.lua;
        resolver 8.8.8.8;
        # 进行请求转发（反向代理）
        proxy_set_header  Host  $host;
        proxy_set_header  X-Forwarded-For $host;
        # 如果客户端请求升级，将代理WebSocket
        proxy_set_header  Upgrade $http_upgrade;
        proxy_set_header  Connection 'Upgrade';
        proxy_set_header  X-Forwarded-Proto $scheme;
        proxy_set_header  X-Real-IP $remote_addr;
        proxy_http_version 1.1;
        # 最后的斜杠勿丢 
        proxy_pass http://$target/;
     }
  }
   > /opt/openresty/conf.d/dynamicRouter.conf
  复制代码
  ```

  - `${hostname}`是Openresty所在的网关服务器域名
  - `${redis_password}`是redis访问的密码

#### 动态路由的使用

- 在部署OpenResty服务后，就可以通过读写Redis的方式来实现动态路由转发了

- 在shell命令行使用 docker exec命令结合redis-cli即可完成动态配置，举例如下：

  - 目的：将 /users/**userid∗∗/info/∗∗{user_id}\**/info/\**userid∗∗/info/∗∗{info_id}** 映射到 **host∗∗:∗∗{host}\**:\**host∗∗:∗∗{port}**

    ```bash
    docker exec -it or-redis /bin/bash
    redis-cli --askpass
    # 输入redis密码
    hset DYNA ${user_id}_${info_id} ${host}:${port}
    复制代码
    ```

    - 注意：**host∗∗:∗∗{host}\**:\**host∗∗:∗∗{port} 不加最后的/；不用加协议头，默认是HTTP，同样也支持WebSocket的协议升级**

- 或者使用Redis-Java API 接口完成动态路由的设置

## OpenResty的安装部署

- 本文章使用docker-compose进行OpneResty的安装部署

  ```bash
  echo 'version: "3"
  services:
    redis:
      image: redis
      restart: always
      volumes:
        - /opt/redis/redis.conf:/etc/redis/redis.conf
      command: redis-server /etc/redis/redis.conf
      ports:
        - "61379:6379"
      container_name: or-redis
    openresty:
      image: openresty/openresty
      restart: always
      depends_on:
        - redis
      container_name: openresty
      volumes:
        - /opt/openresty/ssl/:/etc/nginx/ssl/
        - /opt/openresty/conf.d/:/etc/nginx/conf.d/
        - /opt/openresty/lua/:/usr/local/openresty/nginx/lua/
        - /opt/static/:/etc/nginx/dist/
      ports:
        - "443:443"
        - "80:80"
  ' > /etc/openresty/openresty.yaml
  
  docker-compose -f /opt/openresty.yaml up -d
  复制代码
  ```

  - `/opt/openresty/ssl/` 目录是用来放域名的HTTPS证书的，当然也可以使用更方便的`Let's Encrypt`服务，可参考使用[lua-resty-auto-ssl](https://link.juejin.cn?target=https%3A%2F%2Fgithub.com%2Fauto-ssl%2Flua-resty-auto-ssl)
  - `/opt/static/`可以存放静态文件或页面

## 补充

- 如果更多的配合Docker部署的后端服务使用的话，更推荐使用Traefik，参考[Traefik学习](https://link.juejin.cn?target=https%3A%2F%2Fblog.demoli.xyz%2Farchives%2Ftraefik-xue-xi)

## 参考

- [lua-nginx-module](https://link.juejin.cn?target=https%3A%2F%2Fgithub.com%2Fopenresty%2Flua-nginx-module%23nginx-log-level-constants)
- [redis2-nginx-module](https://link.juejin.cn?target=https%3A%2F%2Fgithub.com%2Fopenresty%2Fredis2-nginx-module)
- [lua-redis-parser](https://link.juejin.cn?target=https%3A%2F%2Fgithub.com%2Fopenresty%2Flua-redis-parser)
- [nginx-logging](https://link.juejin.cn?target=https%3A%2F%2Fdocs.nginx.com%2Fnginx%2Fadmin-guide%2Fmonitoring%2Flogging%2F)


作者：DemoLi_已被占用
链接：https://juejin.cn/post/7090096772042719245
来源：稀土掘金
著作权归作者所有。商业转载请联系作者获得授权，非商业转载请注明出处。
