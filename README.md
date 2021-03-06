## 简介
ThreadLocal的作用是提供线程内的局部变量，在多线程环境下访问时能保证各个线程内的ThreadLocal变量各自独立。在异步场景下，由于出现线程切换的问题，例如主线程切换到子线程，会导致线程ThreadLocal上下文丢失。DiscoveryAgent通过Java Agent方式解决这些痛点

涵盖所有Java框架的异步场景，解决如下7个异步场景下丢失线程ThreadLocal上下文的问题
- `@`Async
- Hystrix Thread Pool Isolation
- Runnable
- Callable
- Single Thread
- Thread Pool
- SLF4J MDC

## 目录
- [简介](#简介)
- [异步跨线程Agent](#异步跨线程Agent)
    - [插件获取](#插件获取)
    - [插件使用](#插件使用)
    - [插件扩展](#插件扩展)

## 异步跨线程Agent

### 插件使用
- discovery-agent-starter-`$`{discovery.version}.jar为Agent引导启动程序，JVM启动时进行加载；discovery-agent/plugin目录包含discovery-agent-starter-plugin-strategy-`$`{discovery.version}.jar为Nepxion Discovery自带的实现方案，业务系统可以自定义plugin，解决业务自己定义的上下文跨线程传递
- 通过如下-javaagent启动，基本格式，如下

```
-javaagent:/discovery-agent/discovery-agent-starter-${discovery.agent.version}.jar -Dthread.scan.packages=com.abc;com.xyz
```

例如

```
-javaagent:C:/opt/discovery-agent/discovery-agent-starter-${discovery.agent.version}.jar -Dthread.scan.packages=reactor.core.publisher;org.springframework.aop.interceptor;com.netflix.hystrix;com.nepxion.discovery.guide.service.feign
```

参数说明
- /discovery-agent：Agent所在的目录，需要对应到实际的目录上
- `-D`thread.scan.packages：Runnable，Callable对象所在的扫描目录，该目录下的Runnable，Callable对象都会被装饰。该目录最好精细和准确，这样可以减少被装饰的对象数，提高性能，目录如果有多个，用“;”分隔。参数定义为
    - WebFlux Reactor异步场景下的扫描目录对应为reactor.core.publisher，可以解决基于Reactor的Spring Cloud LoadBalancer异步负载均衡下的上下文传递
    - `@`Async场景下的扫描目录对应为org.springframework.aop.interceptor
    - Hystrix线程池隔离场景下的扫描目录对应为com.netflix.hystrix
    - 线程，线程池的扫描目录对应为自定义Runnable，Callable对象所在类的目录
    - 上述扫描路径如果含有“;”，可能会在某些操作系统中无法被识别，请用`""`进行引入，例如，-Dthread.scan.packages="com.abc;com.xyz"
- `-D`thread.request.decorator.enabled：异步调用场景下在服务端的Request请求的装饰，当主线程先于子线程执行完的时候，Request会被Destory，导致Header仍旧拿不到，开启装饰，就可以确保拿到。默认为开启，根据实践经验，大多数场景下，需要开启该开关
- `-D`thread.mdc.enabled：SLF4J MDC日志输出到异步子线程。默认关闭，如果需要，则开启该开关

[](http://nepxion.gitee.io/docs/icon-doc/tip.png) 提醒：对于扫描目录，请务必根据实际场景做配置，例如，不存在WebFlux Reactor的异步场景，就不必把reactor.core.publisher配置到扫描目录中

### 插件扩展
- 根据规范开发一个插件，插件提供了钩子函数，在某个类被加载的时候，可以注册一个事件到线程上下文切换事件当中，实现业务自定义ThreadLocal的跨线程传递
- plugin目录为放置需要在线程切换时进行ThreadLocal传递的自定义插件。业务自定义插件开发完后，放入到plugin目录下即可

具体步骤介绍，如下

① SDK侧工作

- 新建ThreadLocal上下文类

```java
public class MyContext {
    private static final ThreadLocal<MyContext> THREAD_LOCAL = new ThreadLocal<MyContext>() {
        @Override
        protected MyContext initialValue() {
            return new MyContext();
        }
    };

    public static MyContext getCurrentContext() {
        return THREAD_LOCAL.get();
    }

    public static void clearCurrentContext() {
        THREAD_LOCAL.remove();
    }

    private Map<String, String> attributes = new HashMap<>();

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }
}
```

② Agent侧工作

- 新建一个模块，引入如下依赖

```xml
<dependency>
    <groupId>com.nepxion</groupId>
    <artifactId>discovery-agent-starter</artifactId>
    <version>${discovery.agent.version}</version>
    <scope>provided</scope>
</dependency>
```

- 新建一个ThreadLocalHook类继承AbstractThreadLocalHook

```java
public class MyContextHook extends AbstractThreadLocalHook {
    @Override
    public Object create() {
        // 从主线程的ThreadLocal里获取并返回上下文对象
        return MyContext.getCurrentContext().getAttributes();
    }

    @Override
    public void before(Object object) {
        // 把create方法里获取到的上下文对象放置到子线程的ThreadLocal里
        if (object instanceof Map) {
            MyContext.getCurrentContext().setAttributes((Map<String, String>) object);
        }
    }

    @Override
    public void after() {
        // 线程结束，销毁上下文对象
        MyContext.clearCurrentContext();
    }
}
```

- 新建一个Plugin类继承AbstractPlugin

```java
public class MyContextPlugin extends AbstractPlugin {
    private Boolean threadMyPluginEnabled = Boolean.valueOf(System.getProperty("thread.myplugin.enabled", "false"));

    @Override
    protected String getMatcherClassName() {
        // 返回存储ThreadLocal对象的类名，由于插件是可以插拔的，所以必须是字符串形式，不允许是显式引入类
        return "com.nepxion.discovery.example.sdk.MyContext";
    }

    @Override
    protected String getHookClassName() {
        // 返回ThreadLocalHook类名
        return MyContextHook.class.getName();
    }

    @Override
    protected boolean isEnabled() {
        // 通过外部-Dthread.myplugin.enabled=true/false的运行参数来控制当前Plugin是否生效。该方法在父类中定义的返回值为true，即缺省为生效
        return threadMyPluginEnabled;
    }
}
```

- 定义SPI扩展，在src/main/resources/META-INF/services目录下定义SPI文件

名称为固定如下格式
```
com.nepxion.discovery.agent.plugin.Plugin
```
内容为Plugin类的全路径
```
com.nepxion.discovery.example.agent.MyContextPlugin
```

- 执行Maven编译，把编译后的包放在discovery-agent/plugin目录下

- 给服务增加启动参数并启动，如下

```
-javaagent:C:/opt/discovery-agent/discovery-agent-starter-${discovery.agent.version}.jar -Dthread.scan.packages=com.nepxion.discovery.example.application -Dthread.myplugin.enabled=true
```

③ Application侧工作

- 执行MyApplication，它模拟在主线程ThreadLocal放入Map数据，子线程通过DiscoveryAgent获取到该Map数据，并打印出来

```java
@SpringBootApplication
@RestController
public class MyApplication {
    private static final Logger LOG = LoggerFactory.getLogger(MyApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);

        invoke();
    }

    public static void invoke() {
        RestTemplate restTemplate = new RestTemplate();

        for (int i = 1; i <= 10; i++) {
            restTemplate.getForEntity("http://localhost:8080/index/" + i, String.class).getBody();
        }
    }

    @GetMapping("/index/{value}")
    public String index(@PathVariable(value = "value") String value) throws InterruptedException {
        Map<String, String> attributes = new HashMap<String, String>();
        attributes.put(value, "MyContext");

        MyContext.getCurrentContext().setAttributes(attributes);

        LOG.info("【主】线程ThreadLocal：{}", MyContext.getCurrentContext().getAttributes());

        new Thread(new Runnable() {
            @Override
            public void run() {
                LOG.info("【子】线程ThreadLocal：{}", MyContext.getCurrentContext().getAttributes());

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                LOG.info("Sleep 5秒之后，【子】线程ThreadLocal：{} ", MyContext.getCurrentContext().getAttributes());
            }
        }).start();

        return "";
    }
}
```

输出结果，如下
```
2020-11-09 00:08:14.330  INFO 16588 --- [nio-8080-exec-1] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{1=MyContext}
2020-11-09 00:08:14.381  INFO 16588 --- [       Thread-4] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{1=MyContext}
2020-11-09 00:08:14.402  INFO 16588 --- [nio-8080-exec-2] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{2=MyContext}
2020-11-09 00:08:14.403  INFO 16588 --- [       Thread-5] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{2=MyContext}
2020-11-09 00:08:14.405  INFO 16588 --- [nio-8080-exec-3] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{3=MyContext}
2020-11-09 00:08:14.406  INFO 16588 --- [       Thread-6] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{3=MyContext}
2020-11-09 00:08:14.414  INFO 16588 --- [nio-8080-exec-4] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{4=MyContext}
2020-11-09 00:08:14.414  INFO 16588 --- [       Thread-7] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{4=MyContext}
2020-11-09 00:08:14.417  INFO 16588 --- [nio-8080-exec-5] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{5=MyContext}
2020-11-09 00:08:14.418  INFO 16588 --- [       Thread-8] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{5=MyContext}
2020-11-09 00:08:14.421  INFO 16588 --- [nio-8080-exec-6] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{6=MyContext}
2020-11-09 00:08:14.422  INFO 16588 --- [       Thread-9] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{6=MyContext}
2020-11-09 00:08:14.424  INFO 16588 --- [nio-8080-exec-7] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{7=MyContext}
2020-11-09 00:08:14.425  INFO 16588 --- [      Thread-10] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{7=MyContext}
2020-11-09 00:08:14.427  INFO 16588 --- [nio-8080-exec-8] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{8=MyContext}
2020-11-09 00:08:14.428  INFO 16588 --- [      Thread-11] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{8=MyContext}
2020-11-09 00:08:14.430  INFO 16588 --- [nio-8080-exec-9] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{9=MyContext}
2020-11-09 00:08:14.431  INFO 16588 --- [      Thread-12] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{9=MyContext}
2020-11-09 00:08:14.433  INFO 16588 --- [io-8080-exec-10] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{10=MyContext}
2020-11-09 00:08:14.434  INFO 16588 --- [      Thread-13] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{10=MyContext}
2020-11-09 00:08:19.382  INFO 16588 --- [       Thread-4] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{1=MyContext} 
2020-11-09 00:08:19.404  INFO 16588 --- [       Thread-5] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{2=MyContext} 
2020-11-09 00:08:19.406  INFO 16588 --- [       Thread-6] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{3=MyContext} 
2020-11-09 00:08:19.416  INFO 16588 --- [       Thread-7] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{4=MyContext} 
2020-11-09 00:08:19.418  INFO 16588 --- [       Thread-8] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{5=MyContext} 
2020-11-09 00:08:19.422  INFO 16588 --- [       Thread-9] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{6=MyContext} 
2020-11-09 00:08:19.425  INFO 16588 --- [      Thread-10] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{7=MyContext} 
2020-11-09 00:08:19.428  INFO 16588 --- [      Thread-11] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{8=MyContext} 
2020-11-09 00:08:19.432  INFO 16588 --- [      Thread-12] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{9=MyContext} 
2020-11-09 00:08:19.434  INFO 16588 --- [      Thread-13] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{10=MyContext} 
```

如果不加异步Agent，则输出结果，如下，可以发现在子线程中ThreadLocal上下文全部都丢失
```
2020-11-09 00:01:40.133  INFO 16692 --- [nio-8080-exec-1] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{1=MyContext}
2020-11-09 00:01:40.135  INFO 16692 --- [       Thread-8] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{}
2020-11-09 00:01:40.158  INFO 16692 --- [nio-8080-exec-2] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{2=MyContext}
2020-11-09 00:01:40.159  INFO 16692 --- [       Thread-9] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{}
2020-11-09 00:01:40.162  INFO 16692 --- [nio-8080-exec-3] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{3=MyContext}
2020-11-09 00:01:40.163  INFO 16692 --- [      Thread-10] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{}
2020-11-09 00:01:40.170  INFO 16692 --- [nio-8080-exec-5] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{4=MyContext}
2020-11-09 00:01:40.170  INFO 16692 --- [      Thread-11] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{}
2020-11-09 00:01:40.173  INFO 16692 --- [nio-8080-exec-4] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{5=MyContext}
2020-11-09 00:01:40.174  INFO 16692 --- [      Thread-12] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{}
2020-11-09 00:01:40.176  INFO 16692 --- [nio-8080-exec-6] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{6=MyContext}
2020-11-09 00:01:40.177  INFO 16692 --- [      Thread-13] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{}
2020-11-09 00:01:40.179  INFO 16692 --- [nio-8080-exec-8] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{7=MyContext}
2020-11-09 00:01:40.180  INFO 16692 --- [      Thread-14] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{}
2020-11-09 00:01:40.182  INFO 16692 --- [nio-8080-exec-7] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{8=MyContext}
2020-11-09 00:01:40.182  INFO 16692 --- [      Thread-15] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{}
2020-11-09 00:01:40.185  INFO 16692 --- [nio-8080-exec-9] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{9=MyContext}
2020-11-09 00:01:40.186  INFO 16692 --- [      Thread-16] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{}
2020-11-09 00:01:40.188  INFO 16692 --- [io-8080-exec-10] c.n.d.example.application.MyApplication  : 【主】线程ThreadLocal：{10=MyContext}
2020-11-09 00:01:40.189  INFO 16692 --- [      Thread-17] c.n.d.example.application.MyApplication  : 【子】线程ThreadLocal：{}
2020-11-09 00:01:45.136  INFO 16692 --- [       Thread-8] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{} 
2020-11-09 00:01:45.160  INFO 16692 --- [       Thread-9] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{} 
2020-11-09 00:01:45.163  INFO 16692 --- [      Thread-10] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{} 
2020-11-09 00:01:45.171  INFO 16692 --- [      Thread-11] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{} 
2020-11-09 00:01:45.174  INFO 16692 --- [      Thread-12] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{} 
2020-11-09 00:01:45.177  INFO 16692 --- [      Thread-13] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{} 
2020-11-09 00:01:45.181  INFO 16692 --- [      Thread-14] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{} 
2020-11-09 00:01:45.183  INFO 16692 --- [      Thread-15] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{} 
2020-11-09 00:01:45.187  INFO 16692 --- [      Thread-16] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{} 
2020-11-09 00:01:45.190  INFO 16692 --- [      Thread-17] c.n.d.example.application.MyApplication  : Sleep 5秒之后，【子】线程ThreadLocal：{} 
```

完整示例，请参考[https://github.com/Nepxion/DiscoveryAgent/tree/master/discovery-agent-example](https://github.com/Nepxion/DiscoveryAgent/tree/master/discovery-agent-example)
上述自定义插件的方式，即可解决使用者在线程切换时丢失ThreadLocal上下文的问题

### 附录
相比较原始Discovery Agent，该版本去掉nepxion的相关依赖，可以直接使用~