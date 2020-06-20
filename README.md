# AnalysisOfUserBehavior
## 基于Saprk的用户行为分析系统
### 一、项目介绍
&emsp;&emsp;本项目主要用于互联网电商企业中使用Spark技术开发的大数据统计分析平台，对电商网站的各种用户行为（访问行为、购物行为、广告点击行为等）进行复杂的分析。用统计分析出来的数据辅助公司中的PM（产品经理）、数据分析师以及管理人员分析现有产品的情况，并根据用户行为分析结果持续改进产品的设计，以及调整公司的战略和业务。最终达到用大数据技术来帮助提升公司的业绩、营业额以及市场占有率的目标。

&emsp;&emsp;项目主要采用Spark，使用了Spark技术生态栈中最常用的三个技术框架，Spark Core、Spark SQL和Spark Streaming，进行离线计算和实时计算业务模块的开发。实现了包括用户访问session分析、页面单跳转化率统计、热门商品离线统计、广告流量实时统计4个业务模块。

&emsp;&emsp;通过合理的将实际业务模块进行技术整合与改造，该项目完全涵盖了Spark Core、Spark SQL和Spark Streaming这三个技术框架中，几乎所有的功能点、知识点以及性能优化点！重点讲解了实际企业项目中积累下来的宝贵的性能调优、troubleshooting以及数据倾斜等知识和技术，涵盖了项目开发全流程，包括需求分析、方案设计、数据设计、编码实现、测试以及性能调优等环节，全面还原真实大数据项目的开发流程。

&emsp;&emsp;在访问电商网站时，我们的一些访问行为会产生相应的埋点日志（例如点击、搜索、下单、购买等），这些埋点日志会被发送给电商的后台服务器，大数据部门会根据这些埋点日志中的数据分析用户的访问行为，并得出一系列的统计指标，借助这些统计指标指导电商平台中的商品推荐、广告推送和网站优化等工作。

&emsp;&emsp;上报到服务器的埋点日志数据会经过数据采集、过滤、存储、分析、可视化这一完整流程，电商平台通过对海量用户行为数据的分析，可以对用户建立精准的用户画像，同时，对于用户行为的分析，也可以帮助电商网站找到网站的优化思路，从而在海量用户数据的基础上对网站进行改进和完善。

### 二、模块简介

&emsp;&emsp;1、用户访问session分析：该模块主要是对用户访问session进行统计分析，包括session的聚合指标计算、按时间比例随机抽取session、获取每天点击、下单和购买排名前10的品类、并获取top10品类的点击量排名前10的session。该模块可以让产品经理、数据分析师以及企业管理层形象地看到各种条件下的具体用户行为以及统计指标，从而对公司的产品设计以及业务发展战略做出调整。主要使用Spark Core实现。

&emsp;&emsp;2、页面单跳转化率统计：该模块主要是计算关键页面之间的单步跳转转化率，涉及到页面切片算法以及页面流匹配算法。该模块可以让产品经理、数据分析师以及企业管理层看到各个关键页面之间的转化率，从而对网页布局，进行更好的优化设计。主要使用Spark Core实现。

&emsp;&emsp;3、热门商品离线统计：该模块主要实现每天统计出各个区域的top3热门商品。然后使用Oozie进行离线统计任务的定时调度；使用Zeppeline进行数据可视化的报表展示。该模块可以让企业管理层看到公司售卖的商品的整体情况，从而对公司的商品相关的战略进行调整。主要使用Spark SQL实现。

&emsp;&emsp;4、广告流量实时统计：该模块负责实时统计公司的广告流量，包括广告展现流量和广告点击流量。实现动态黑名单机制，以及黑名单过滤；实现滑动窗口内的各城市的广告展现流量和广告点击流量的统计；实现每个区域每个广告的点击流量实时统计；实现每个区域top3点击量的广告的统计。主要使用Spark Streaming实现。

### 三、开发环境

&emsp;&emsp;1、CentOS 6.6

&emsp;&emsp;2、CDH 5.3.6

&emsp;&emsp;3、Spark 

&emsp;&emsp;4、Hadoop 

&emsp;&emsp;5、ZooKeeper 

&emsp;&emsp;6、Kafka

&emsp;&emsp;7、Flume

&emsp;&emsp;8、Java 1.8（Scala 2.11）

&emsp;&emsp;9、IDEA

### 四、开发语言选择说明

&emsp;&emsp;本课程的编码实现采用Java。

&emsp;&emsp;1、因为Java语言具有高度的稳定性，语法简洁，更容易理解。

&emsp;&emsp;2、最重要的一点是，Java并不只是一门编程语言，而是一个生态体系！使用Java开发复杂的大型Spark工程项目，可以让Spark与Redis、Memcaced、Kafka、Solr、MongoDB、HBase、MySQL等第三方技术进行整合使用，因为Java就是一个生态系统，这些第三方的技术无一例外，全部都会包含了Java的API，可以无缝与Spark项目进行整合使用。

&emsp;&emsp;3、Java是目前最主流的语言，绝大多数公司里都有一批Java工程师。使用Java开发Spark工程，在项目进行交接、迁移、维护、新人加入时，只要懂得Java的人，都能快速接手和上手Spark开发和项目。更利于项目的交接与维护。

&emsp;&emsp;对于Scala仅仅会在部分重要技术点的使用，比如自定义Accumulator、二次排序等，用Scala辅助讲解一下如何实现。

&emsp;&emsp;1、Scala的高级语法复杂，学习曲线非常陡峭，不利于学习，容易造成迷惑。

&emsp;&emsp;2、Scala仅仅只是一门编程语言，而没有达到技术生态的程度。当Spark要与第三方技术，比如Redis、HBase等配合使用时，就只能混合使用Java。此时就会造成一个项目两种语言混编，可维护性与可扩展性大幅度降低。

&emsp;&emsp;3、Scala目前远远没有达到普及的程度，会的人很少，在进行项目交接时，如果是Scala的项目，交接过程会很痛苦，甚至导致项目出现问题。

### 五、日志数据采集

&emsp;&emsp;**数据从哪里来？**

    互联网行业：网站、app、系统（交易系统。。）
    传统行业：电信，人们的上网、打电话、发短信等等数据
    数据源：网站、app都要往我们的后台去发送请求，获取数据，执行业务逻辑；app获取要展现的商品数据；发送请求到后台进行交易和结账
    
&emsp;&emsp;网站/app会发送请求到后台服务器，通常会由Nginx接收请求，并进行转发，在面向大量用户，高并发（每秒访问量过万）的情况下，通常都不会直接是用Tomcat来接收请求，这种时候通常都是用Nginx来接收请求，并且后端接入Tomcat集群/Jetty集群，来进行高并发访问下的负载均衡。Nginx或者Tomcat进行适当配置之后，所有请求的数据都会作为log存储起来；接收请求的后台系统（J2EE、PHP、Ruby On Rails），也可以按照你的规范，每接收一个请求，或者每执行一个业务逻辑，就往日志文件里面打一条log。日志文件（通常由我们预先设定的特殊的格式）通常每天一份，因为有多个web服务器，所以可能有多份日志文件。
    
&emsp;&emsp;可以通过一个日志转移工具，比如用linux的crontab定时调度一个shell脚本/python脚本，或者自己用java开发一个后台服务，用quartz这样的框架进行定时调度，负责将当天的所有日志的数据都给采集起来，进行合并和处理等操作，然后作为一份日志文件，给转移到flume agent正在监控的目录中，flume agent启动起来以后，可以实时的监控linux系统上面的某一个目录，看其中是否有新的文件进来。只要发现有新的日志文件进来，那么flume就会走后续的channel和sink。通常来说，sink都会配置为HDFS，flume负责将每天的一份log文件，传输到HDFS上，Hadoop HDFS中的原始的日志数据，会经过数据清洗。为什么要进行数据清洗？因为我们的数据中可能有很多是不符合预期的脏数据。， 把HDFS中的清洗后的数据，给导入到Hive的某个表中。这里可以使用动态分区，Hive使用分区表，每个分区放一天的数据。Spark/Hdoop/Storm，大数据平台/系统，可能都会使用Hive中的数据仓库内部的表。

&emsp;&emsp;实时数据，通常都是从分布式消息队列集群中读取的，比如Kafka；实时数据实时的写入到消息队列中，比如Kafka，然后由后端的实时数据处理程序（Storm、Spark Streaming），实时从Kafka中读取数据，log日志，然后进行实时的计算和处理。

&emsp;&emsp;如下是项目的日志采集架构：

![Spark用户行为分析日志采集图](https://img-blog.csdnimg.cn/20200326095628677.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L3FxXzQwNjQwMjI4,size_16,color_FFFFFF,t_70)

&emsp;&emsp;如下是项目的数据处理流程：

![Spark用户行为分析数据处理流程](https://img-blog.csdnimg.cn/20200326101257815.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L3FxXzQwNjQwMjI4,size_16,color_FFFFFF,t_70)

&emsp;&emsp;如下是项目的部分需求图示：

![Spark用户行为分析项目需求](https://img-blog.csdnimg.cn/20200326100023617.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L3FxXzQwNjQwMjI4,size_16,color_FFFFFF,t_70)

### 六、用户访问session分析模块

&emsp;&emsp;**用户访问session介绍：**

&emsp;&emsp;用户在电商网站上，通常会有很多的点击行为，首页通常都是进入首页；然后可能点击首页上的一些商品；点击首页上的一些品类；也可能随时在搜索框里面搜索关键词；还可能将一些商品加入购物车；对购物车中的多个商品下订单；最后对订单中的多个商品进行支付。

&emsp;&emsp;用户的每一次操作，其实可以理解为一个action，比如点击、搜索、下单、支付，用户session，指的就是，从用户第一次进入首页，session就开始了。然后在一定时间范围内，直到最后操作完（可能做了几十次、甚至上百次操作）。离开网站，关闭浏览器，或者长时间没有做操作；那么session就结束了。以上用户在网站内的访问过程，就称之为一次session。简单理解，session就是某一天某一个时间段内，某个用户对网站从打开/进入，到做了大量操作到最后关闭浏览器的过程就叫做session。session实际上就是一个电商网站中最基本的数据，面向C端也就是customer--消费者，用户端的分析基本是最基本的就是面向用户访问行为/用户访问session。

&emsp;&emsp;**在实际企业项目中的使用架构：**

&emsp;&emsp;1、J2EE的平台（美观的前端页面），通过这个J2EE平台可以让使用者，提交各种各样的分析任务，其中就包括一个模块，就是用户访问session分析模块；可以指定各种各样的筛选条件，比如年龄范围、职业、城市等等。

&emsp;&emsp;2、J2EE平台接收到了执行统计分析任务的请求之后，会调用底层的封装了spark-submit的shell脚本（Runtime、Process），shell脚本进而提交我们编写的Spark作业。

&emsp;&emsp;3、Spark作业获取使用者指定的筛选参数，然后运行复杂的作业逻辑，进行该模块的统计和分析。

&emsp;&emsp;4、Spark作业统计和分析的结果，会写入MySQL中，指定的表

&emsp;&emsp;5、最后，J2EE平台，使用者可以通过前端页面（美观），以表格、图表的形式展示和查看MySQL中存储的该统计分析任务的结果数据。

&emsp;&emsp;**模块的目标：对用户访问session进行分析**

&emsp;&emsp;1、可以根据使用者指定的某些条件，筛选出指定的一些用户（有特定年龄、职业、城市）；

&emsp;&emsp;2、对这些用户在指定日期范围内发起的session，进行聚合统计，比如，统计出访问时长在0~3s的session占总session数量的比例；

&emsp;&emsp;3、按时间比例，比如一天有24个小时，其中12:00~13:00的session数量占当天总session数量的50%，当天总session数量是10000个，那么当天总共要抽取1000个session，ok，12:00~13:00的用户，就得抽取1000*50%=500。而且这500个需要随机抽取。

&emsp;&emsp;4、获取点击量、下单量和支付量都排名10的商品种类

&emsp;&emsp;5、获取top10的商品种类的点击数量排名前10的session

&emsp;&emsp;6、开发完毕了以上功能之后，需要进行大量、复杂、高端、全套的性能调优（大部分性能调优点，都是本人在实际开发过程中积累的经验，基本都是全网唯一）

&emsp;&emsp;7、十亿级数据量的troubleshooting（故障解决）的经验总结

&emsp;&emsp;8、数据倾斜的完美解决方案（全网唯一，非常高端，因为数据倾斜往往是大数据处理程序的性能杀手，很多人在遇到的时候，往往没有思路）

&emsp;&emsp;9、使用mock（模拟）的数据，对模块进行调试、运行和演示效果
















