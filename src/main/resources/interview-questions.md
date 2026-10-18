# 面试题库

> 供 RAG 检索使用。格式约定（解析器依赖，改动前请先看 `InterviewQuestionLoader`）：
> 每条以 `### [分类][难度] 问题` 开头，紧跟着的正文到下一个 `###` 之前都是该题的参考答案。
> 分类与难度会成为 Qdrant 文档的元数据，用于后续按条件过滤。

## Java 基础

### [Java基础][简单] == 和 equals 有什么区别？
`==` 比较的是引用（基本类型比较值），`equals` 是方法，默认实现等价于 `==`。
关键差异在重写：String、Integer 等类重写了 equals，比较的是内容。
约定是重写 equals 必须同时重写 hashCode，否则放进 HashMap/HashSet 会出现「两个相等的对象落在不同桶里」，导致查不到。

### [Java基础][中等] 为什么重写 equals 必须重写 hashCode？
哈希容器先用 hashCode 定位桶，再用 equals 在桶内确认。
若两个对象 equals 相等但 hashCode 不同，它们会落进不同桶，容器就会认为「这个 key 不存在」——put 进去的值再也 get 不出来。
反过来 hashCode 相同而 equals 不等是允许的（哈希冲突），只是会退化成链表。

### [Java基础][中等] String、StringBuilder、StringBuffer 怎么选？
String 不可变，每次拼接都产生新对象，循环里拼接是性能陷阱。
StringBuilder 可变、非线程安全，单线程拼接首选。
StringBuffer 方法加了 synchronized，线程安全但开销更大，现在基本只在需要同步的老代码里见到。
另外编译器会把简单的 `a + b` 优化成 StringBuilder，但**循环体内的拼接不会**被这样优化。

### [Java基础][简单] 接口和抽象类怎么选？
抽象类表达「is-a」，可以有状态和构造器，单继承。
接口表达「can-do」，无状态（Java 8 后可有 default 方法），可多实现。
实践中优先接口：它不占用唯一的继承名额，也更利于测试时替换实现。

## Java 集合

### [Java集合][简单] ArrayList 和 LinkedList 的区别？
ArrayList 基于数组，随机访问 O(1)，中间插入删除要搬移元素 O(n)。
LinkedList 基于双向链表，头尾操作 O(1)，但随机访问要遍历 O(n)。
实际上 LinkedList 因为缓存不友好和节点开销，实践中很少真正更快；绝大多数场景用 ArrayList。

### [Java集合][中等] HashMap 的底层结构和扩容过程？
数组 + 链表 + 红黑树。默认容量 16，负载因子 0.75。
put 时先算 hash 定位桶：桶为空直接放；冲突则挂链表；链表长度达到 8 且数组容量 ≥ 64 时转红黑树（否则先扩容）。
元素数超过 容量×0.75 时扩容为两倍，并重新分配元素。扩容代价高，能预估大小时应指定初始容量。

### [Java集合][中等] HashMap 为什么线程不安全？
并发 put 可能同时扩容，导致元素丢失或链表成环（JDK 7 会死循环）。
即使 JDK 8 修复了成环，并发 put 仍可能互相覆盖、size 计数不准。
并发场景用 ConcurrentHashMap，它用 CAS + synchronized 锁单个桶，而不是锁整表。

### [Java集合][简单] ConcurrentHashMap 在 JDK 8 里怎么做并发控制？
放弃分段锁，改为 CAS 插入空桶 + synchronized 锁住单个桶的头节点。
锁粒度从「一段」降到「一个桶」，并发度大幅提高。
size 用类似 LongAdder 的分片计数，避免每次都做全局同步。

## Java 并发

### [Java并发][中等] 线程池的核心参数和任务提交流程？
核心参数：corePoolSize、maximumPoolSize、workQueue、keepAliveTime、线程工厂、拒绝策略。
提交顺序：核心线程未满 → 创建核心线程；核心满了 → 入队；队列满了 → 创建非核心线程直到 max；再满 → 触发拒绝策略。
最常见的坑是用无界队列（如 LinkedBlockingQueue 不指定容量），队列永远填不满，maximumPoolSize 形同虚设。

### [Java并发][中等] volatile 解决了什么问题？
两个语义：可见性（写立即刷回主内存，读从主内存读）和禁止指令重排序。
它**不保证原子性**：`i++` 用 volatile 修饰依然是线程不安全的。
典型用途是状态标志位和双重检查锁单例中的实例引用。

### [Java并发][困难] synchronized 和 ReentrantLock 怎么选？
synchronized 是 JVM 内置关键字，自动释放锁，JDK 6 后有偏向锁/轻量级锁优化，性能已不差。
ReentrantLock 是 API 层面的，支持可中断获取、超时获取、公平锁、多个 Condition。
需要这些高级能力时才用它，否则优先 synchronized——代码更短，也不会忘记在 finally 里解锁。

### [Java并发][中等] ThreadLocal 为什么会内存泄漏？
ThreadLocalMap 的 key 是弱引用（ThreadLocal 本身），value 是强引用。
ThreadLocal 被回收后 key 变 null，但 value 还被 Thread 持有；线程池里线程长期存活，这些 value 就一直不释放。
所以**用完必须 remove**，尤其在用线程池的场景。

## JVM

### [JVM][中等] JVM 的内存区域怎么划分？
线程私有：程序计数器、虚拟机栈、本地方法栈。
线程共享：堆、方法区（JDK 8 后由元空间实现，用的是本地内存而非堆）。
堆是 GC 主战场，通常再分新生代（Eden + 两个 Survivor）和老年代。

### [JVM][中等] 什么对象会进入老年代？
三种情况：对象年龄达到阈值（默认 15 次 GC）后晋升；Survivor 中同年龄对象总大小超过一半时提前晋升；大对象直接进老年代。
另外动态年龄判断也常被问到：不是严格等到 15，而是看同龄对象总和是否超过 Survivor 的一半。

### [JVM][困难] 常见的 GC 收集器怎么选？
Serial 单线程，适合客户端小堆。
Parallel 吞吐优先，适合后台批处理。
CMS 以低停顿为目标但已废弃。
G1 是 JDK 9 起的默认，可预测停顿、分 Region 回收，适合大多数服务端场景。
ZGC/Shenandoah 面向超大堆和极低停顿。

### [JVM][中等] 怎么排查内存泄漏或频繁 Full GC？
先用 jps 找到进程，jstat -gcutil 看各代占用和 GC 频率，确认是泄漏还是单纯堆小。
再 jmap -dump 导出堆快照，用 MAT 分析支配树，找出占比异常的对象及其 GC Root 引用链。
线上更推荐先看 GC 日志，它成本最低且信息足够定位方向。

## Spring

### [Spring][中等] Spring Bean 的生命周期？
实例化 → 属性注入 → Aware 接口回调 → BeanPostProcessor 前置处理 → @PostConstruct / InitializingBean → BeanPostProcessor 后置处理（AOP 代理在这里生成）→ 使用 → @PreDestroy / DisposableBean。
最常被追问的点是：**AOP 代理是在后置处理阶段生成的**，所以类内部方法自调用不走代理，事务会失效。

### [Spring][中等] @Transactional 什么时候会失效？
常见五种：方法不是 public；类内部自调用（不走代理）；异常被 catch 没抛出；抛出的是受检异常（默认只回滚 RuntimeException 和 Error）；多线程中调用。
还有一种是数据库引擎不支持事务（比如 MyISAM）。

### [Spring][简单] 构造器注入和字段注入怎么选？
构造器注入能保证依赖不为 null、字段可声明为 final、循环依赖在启动时就暴露。
字段注入用起来短，但隐藏依赖、无法 final、测试时只能靠反射塞值。
Spring 官方从 4.3 起推荐构造器注入；单一构造器时连 @Autowired 都可以省。

### [Spring][中等] Spring 如何解决循环依赖？
用三级缓存：singletonObjects（成品）、earlySingletonObjects（半成品）、singletonFactories（工厂）。
A 依赖 B、B 依赖 A 时，A 提前把工厂放进三级缓存，B 创建时能拿到 A 的早期引用。
注意它只能解决**单例 + setter/字段注入**的循环依赖；构造器注入的循环依赖无法解决，会直接启动失败。

## Spring Boot

### [SpringBoot][中等] Spring Boot 自动配置的原理？
@SpringBootApplication 内含 @EnableAutoConfiguration，它通过 AutoConfigurationImportSelector 读取所有 jar 里 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 列出的配置类。
这些类再用 @ConditionalOnClass、@ConditionalOnMissingBean 等条件注解决定是否生效。
所以自定义 Bean 时，只要类型对得上，自动配置就会主动退让——这正是它「约定优于配置」的落点。

### [SpringBoot][简单] 怎么理解 starter？
starter 是「一组依赖 + 一套自动配置」的打包约定。
引入 `spring-boot-starter-web` 就同时拿到 Spring MVC、内嵌 Tomcat 和 Jackson，并触发对应的自动配置。
自己写 starter 时，关键在于提供 AutoConfiguration.imports 文件和一组合理的条件注解。

## MySQL

### [MySQL][中等] 为什么用 B+ 树做索引而不是 B 树或哈希？
B+ 树非叶子节点只存键、不存数据，同样大小的页能容纳更多键，树更矮、磁盘 IO 更少。
叶子节点用链表相连，天然支持范围查询和排序。
哈希索引只能等值查询，不支持范围；B 树因为数据分散在所有节点，范围扫描效率低。

### [MySQL][中等] 什么情况下索引会失效？
对索引列做函数运算或隐式类型转换（如字符串列传数字）；以 % 开头的模糊查询；违反最左前缀原则；使用 OR 且两侧不都有索引；优化器判断全表扫描更快（区分度低的列）。
最隐蔽的是隐式转换：字段是 varchar 却用数字去查，索引直接失效。

### [MySQL][困难] MVCC 是怎么实现的？
每行有隐藏的事务 ID 和回滚指针，Undo Log 串成版本链。
ReadView 记录创建时活跃的事务集合，读的时候沿版本链找到对当前事务可见的版本。
RC 隔离级别每次查询都新建 ReadView，RR 只在第一次查询时建，这就是两者「不可重复读」差异的来源。

### [MySQL][中等] 什么是回表，怎么避免？
二级索引的叶子节点只存索引列和主键，查询其他列时要拿主键回主键索引再查一次，这就是回表。
用覆盖索引可以避免：让查询涉及的列全部包含在索引里，直接从索引返回。
`explain` 的 Extra 出现 `Using index` 就说明命中了覆盖索引。

## Redis

### [Redis][中等] 缓存穿透、击穿、雪崩分别怎么解决？
穿透：查不存在的数据，请求全打到 DB。解法是缓存空值（加短过期）或布隆过滤器。
击穿：某个热点 key 过期瞬间大量请求涌入。解法是互斥锁重建或逻辑过期。
雪崩：大批 key 同时过期。解法是过期时间加随机抖动，以及多级缓存。

### [Redis][中等] Redis 的持久化方式怎么选？
RDB 是定时快照，文件小、恢复快，但可能丢最后一次快照之后的数据。
AOF 记录写命令，丢数据少（appendfsync everysec 最多丢 1 秒），但文件更大、恢复更慢。
生产常用混合持久化：AOF 重写时以 RDB 格式存全量，后续增量追加命令。

### [Redis][中等] 为什么 Redis 用单线程还这么快？
纯内存操作、避免锁竞争和上下文切换；IO 多路复用（epoll）让单线程能处理大量连接；数据结构经过专门优化。
需要说清楚的是：Redis 6.0 之后网络 IO 是多线程的，但**命令执行仍然是单线程**，所以不需要为命令加锁。

## 项目与工程

### [项目][中等] 你怎么保证一个 AI Agent 的输出是可用的结构化数据？
先把目标类型定义成强类型（Java 里用 Record），再让框架据类型生成 JSON Schema 指令，让模型按 schema 输出，最后反序列化校验。
比「在提示词里写一句请返回 JSON」可靠得多，因为约束是从类型推导出来的，字段改名会编译不过，而提示词不会。
还要兜底：模型可能漏字段或加上 Markdown 围栏，转换器要能容错，缺失字段要有默认值。

### [项目][中等] 为什么要把长耗时任务做成异步？
简历向量化、报告生成这类任务要调模型，耗时从数秒到数十秒。
同步做会让 HTTP 连接一直挂着，用户以为卡死，网关还可能超时断开。
异步化后接口立即返回「已受理」，任务在消费端重试和补偿，吞吐和稳定性都更好。
代价是要处理消息幂等和最终一致性。
