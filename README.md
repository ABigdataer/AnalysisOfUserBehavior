# AnalysisOfUserBehavior
### 基于Saprk的用户行为分析系统

&emsp;&emsp;在访问电商网站时，我们的一些访问行为会产生相应的埋点日志（例如点击、搜索、下单、购买等），这些埋点日志会被发送给电商的后台服务器，大数据部门会根据这些埋点日志中的数据分析用户的访问行为，并得出一系列的统计指标，借助这些统计指标指导电商平台中的商品推荐、广告推送和网站优化等工作。

&emsp;&emsp;上报到服务器的埋点日志数据会经过数据采集、过滤、存储、分析、可视化这一完整流程，电商平台通过对海量用户行为数据的分析，可以对用户建立精准的用户画像，同时，对于用户行为的分析，也可以帮助电商网站找到网站的优化思路，从而在海量用户数据的基础上对网站进行改进和完善。

&emsp;&emsp;如下是项目的日志采集架构：

![Spark用户行为分析日志采集图](https://img-blog.csdnimg.cn/20200326095628677.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L3FxXzQwNjQwMjI4,size_16,color_FFFFFF,t_70)

&emsp;&emsp;如下是项目的部分需求图示：

![Spark用户行为分析项目需求](https://img-blog.csdnimg.cn/20200326100023617.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L3FxXzQwNjQwMjI4,size_16,color_FFFFFF,t_70)
