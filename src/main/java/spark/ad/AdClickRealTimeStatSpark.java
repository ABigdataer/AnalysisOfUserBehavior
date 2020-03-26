package spark.ad;

import conf.ConfigurationManager;
import constant.Constants;
import dao.*;
import dao.factory.DAOFactory;
import domain.AdUserClickCount;
import kafka.serializer.StringDecoder;
import model.AdBlacklist;
import model.AdClickTrend;
import model.AdProvinceTop3;
import model.AdStat;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.hive.HiveContext;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.*;
import org.apache.spark.streaming.kafka.KafkaUtils;
import com.google.common.base.Optional;
import scala.Tuple2;
import util.DateUtils;
import util.SparkUtils;

import java.util.*;

/**
 * 广告点击流量实时统计分析
 *
 * 需求分析：
 *
 * 1、实现实时的动态黑名单机制：将每天对某个广告点击超过100次的用户拉黑
 * 2、基于黑名单的非法广告点击流量过滤机制：
 * 3、每天各省各城市各广告的点击流量实时统计：
 * 4、统计每天各省top3热门广告
 * 5、统计各广告最近1小时内的点击量趋势：各广告最近1小时内各分钟的点击量
 * 6、使用高性能方式将实时统计结果写入MySQL
 * 7、实现实时计算程序的HA高可用性（Spark Streaming HA方案）
 * 8、实现实时计算程序的性能调优（Spark Streaming Performence Tuning方案）
 *
 * 技术方案设计：
 *
 * 1、实时计算各batch中的每天各用户对各广告的点击次数
 * 2、使用高性能方式将每天各用户对各广告的点击次数写入MySQL中（更新）
 * 3、使用filter过滤出每天对某个广告点击超过100次的黑名单用户，并写入MySQL中
 * 4、使用transform操作，对每个batch RDD进行处理，都动态加载MySQL中的黑名单生成RDD，然后进行join后，过滤掉batch RDD中的黑名单用户的广告点击行为
 * 5、使用updateStateByKey操作，实时计算每天各省各城市各广告的点击量，并时候更新到MySQL
 * 6、使用transform结合Spark SQL，统计每天各省份top3热门广告：首先以每天各省各城市各广告的点击量数据作为基础，首先统计出每天各省份各广告的点击量；然后启动一个异步子线程，使用Spark SQL动态将数据RDD转换为DataFrame后，注册为临时表；最后使用Spark SQL开窗函数，统计出各省份top3热门的广告，并更新到MySQL中
 * 7、使用window操作，对最近1小时滑动窗口内的数据，计算出各广告各分钟的点击量，并更新到MySQL中
 * 8、实现实时计算程序的HA高可用性
 * 9、对实时计算程序进行性能调优
 *
 * 数据设计
 *
 * CREATE TABLE `ad_user_click_count` (
 *   `date` varchar(30) DEFAULT NULL,
 *   `user_id` int(11) DEFAULT NULL,
 *   `ad_id` int(11) DEFAULT NULL,
 *   `click_count` int(11) DEFAULT NULL
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8
 *
 * CREATE TABLE `ad_blacklist` (
 *   `user_id` int(11) DEFAULT NULL
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8
 *
 * CREATE TABLE `ad_stat` (
 *   `date` varchar(30) DEFAULT NULL,
 *   `province` varchar(100) DEFAULT NULL,
 *   `city` varchar(100) DEFAULT NULL,
 *   `ad_id` int(11) DEFAULT NULL,
 *   `click_count` int(11) DEFAULT NULL
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8
 *
 * CREATE TABLE `ad_province_top3` (
 *   `date` varchar(30) DEFAULT NULL,
 *   `province` varchar(100) DEFAULT NULL,
 *   `ad_id` int(11) DEFAULT NULL,
 *   `click_count` int(11) DEFAULT NULL
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8
 *
 * CREATE TABLE `ad_click_trend` (
 *   `date` varchar(30) DEFAULT NULL,
 *   `ad_id` int(11) DEFAULT NULL,
 *   `minute` varchar(30) DEFAULT NULL,
 *   `click_count` int(11) DEFAULT NULL
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8
 */
public class AdClickRealTimeStatSpark {

    public static void main(String[] args) {

        // 构建Spark Streaming上下文
        SparkConf sparkConf = new SparkConf().setAppName("AdClickRealTimeStatSpark");
//				.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer");//启动kyro序列化
//				.set("spark.default.parallelism", "1000");//调节并行度
//				.set("spark.streaming.blockInterval", "50");//增加block数量，增加每个batch rdd的partition数量，增加处理并行度
//				.set("spark.streaming.receiver.writeAheadLog.enable", "true");//启动WAL日志预写机制
        SparkUtils.setMaster(sparkConf);
       /**
        * spark streaming的上下文是构建JavaStreamingContext对象,而不是像之前的JavaSparkContext、SQLContext/HiveContext
        *传入的第一个参数，和之前的spark上下文一样，也是SparkConf对象；第二个参数则不太一样,第二个参数是spark streaming类型作业比较有特色的一个参数
        *实时处理batch的interval
        *spark streaming，每隔一小段时间，会去收集一次数据源（kafka）中的数据，做成一个batch,每次都是处理一个batch中的数据
        *通常来说，batch interval，就是指每隔多少时间收集一次数据源中的数据，然后进行处理,一般spark streaming的应用，都是设置数秒到数十秒（很少会超过1分钟）
        */
        JavaStreamingContext jssc = new JavaStreamingContext(sparkConf, Durations.seconds(5));
        JavaSparkContext sc = new JavaSparkContext(sparkConf);

        //对于updateStateByKey、window等有状态的操作，自动进行checkpoint，必须设置checkpoint目录
        //checkpoint，相当于是会把数据保留一份在容错的文件系统中，一旦内存中的数据丢失掉；那么就可以直接从文件系统中读取数据；不需要重新进行计算
        jssc.checkpoint("hdfs://cdh:9090/checkpoint");

       /**
        *  创建针对Kafka数据来源的输入DStream（离线流，代表了一个源源不断的数据来源，抽象），选用kafka direct api（很多好处，包括自己内部自适应调整每次接收数据量的特性，等等）
        */
        // 构建kafka参数map，主要要放置的要连接的kafka集群的地址（broker集群的地址列表）
        HashMap<String, String> kafkaParams = new HashMap<>();
        kafkaParams.put("metadata.broker.list",ConfigurationManager.getProperty(Constants.KAFKA_METADATA_BROKER_LIST));
        // 构建topic set
        String kafkaTopics = ConfigurationManager.getProperty(Constants.KAFKA_TOPICS);
        String[] kafkaTopicsSplited = kafkaTopics.split(",");
        Set<String> topics = new HashSet<String>();
            for(String kafkaTopic : kafkaTopicsSplited) {
                topics.add(kafkaTopic);
            }

        // 基于kafka direct api模式，构建出了针对kafka集群中指定topic的输入DStream
        // 两个值，val1，val2；val1没有什么特殊的意义；val2中包含了kafka topic中的一条一条的实时日志数据
        JavaPairInputDStream<String, String> adRealTimeLogDStream = KafkaUtils.createDirectStream(
                jssc,//Spark实时处理上下文
                String.class,//val1
                String.class,//val2
                StringDecoder.class,//val1解码类型
                StringDecoder.class,//val2解码类型
                kafkaParams,//参数,集群列表 kafka.metadata.broker.list=173.37.9.42:9092,173.37.9.43:9092,173.37.9.44:9092
                topics//主题 kafka.topics=AdRealTimeLog
        );

//		adRealTimeLogDStream.repartition(1000);

        // 1、根据动态黑名单进行数据过滤
        JavaPairDStream<String, String> filteredAdRealTimeLogDStream = filterByBlacklist(adRealTimeLogDStream);

        // 2、动态生成黑名单
        generateDynamicBlacklist(filteredAdRealTimeLogDStream);

        // 业务功能一：计算广告点击流量实时统计结果（yyyyMMdd_province_city_adid,clickCount）
        // 最粗
        JavaPairDStream<String, Long> adRealTimeStatDStream = calculateRealTimeStat(filteredAdRealTimeLogDStream);

        // 业务功能二：实时统计每天每个省份top3热门广告
        // 统计的稍微细一些了
        calculateProvinceTop3Ad(adRealTimeStatDStream);

        // 业务功能三：实时统计每天每个广告在最近1小时的滑动窗口内的点击趋势（每分钟的点击量）
        // 统计的非常细了
        // 我们每次都可以看到每个广告，最近一小时内，每分钟的点击量
        // 每支广告的点击趋势
        calculateAdClickCountByWindow(adRealTimeLogDStream);

        // 构建完spark streaming上下文之后，记得要进行上下文的启动、等待执行结束、关闭
        jssc.start();
        jssc.awaitTermination();
        jssc.close();

    }

    //提交程序需要填加参数
    //spark-submit
    //--deploy-mode cluster
    //--supervise
    @SuppressWarnings("unused")
    private static void testDriverHA() {

        final String checkpointDir = "hdfs://192.168.1.105:9090/streaming_checkpoint";

        JavaStreamingContextFactory contextFactory = new JavaStreamingContextFactory() {

            @Override
            public JavaStreamingContext create() {

                SparkConf conf = new SparkConf().setMaster("local[2]").setAppName("AdClickRealTimeStatSpark");

                JavaStreamingContext jssc = new JavaStreamingContext(conf, Durations.seconds(5));
                jssc.checkpoint(checkpointDir);

                Map<String, String> kafkaParams = new HashMap<String, String>();
                kafkaParams.put(Constants.KAFKA_METADATA_BROKER_LIST, ConfigurationManager.getProperty(Constants.KAFKA_METADATA_BROKER_LIST));
                String kafkaTopics = ConfigurationManager.getProperty(Constants.KAFKA_TOPICS);
                String[] kafkaTopicsSplited = kafkaTopics.split(",");
                Set<String> topics = new HashSet<String>();
                for(String kafkaTopic : kafkaTopicsSplited) {
                    topics.add(kafkaTopic);
                }

                JavaPairInputDStream<String, String> adRealTimeLogDStream = KafkaUtils.createDirectStream(
                        jssc,
                        String.class,
                        String.class,
                        StringDecoder.class,
                        StringDecoder.class,
                        kafkaParams,
                        topics);

                JavaPairDStream<String, String> filteredAdRealTimeLogDStream = filterByBlacklist(adRealTimeLogDStream);
                generateDynamicBlacklist(filteredAdRealTimeLogDStream);
                JavaPairDStream<String, Long> adRealTimeStatDStream = calculateRealTimeStat(filteredAdRealTimeLogDStream);
                calculateProvinceTop3Ad(adRealTimeStatDStream);
                calculateAdClickCountByWindow(adRealTimeLogDStream);

                return jssc;
            }
        };
        JavaStreamingContext context = JavaStreamingContext.getOrCreate(checkpointDir, contextFactory);
        context.start();
        context.awaitTermination();
    }

    /**
     * 根据黑名单进行过滤
     * @param adRealTimeLogDStream
     * @return
     */
    private static JavaPairDStream<String, String> filterByBlacklist(JavaPairInputDStream<String, String> adRealTimeLogDStream) {

        // 刚刚接受到原始的用户点击行为日志之后，根据mysql中的动态黑名单，进行实时的黑名单过滤（黑名单用户的点击行为，直接过滤掉，不要了）
        // 使用transform算子（将dstream中的每个batch RDD进行处理，转换为任意的其他RDD，功能很强大）
        JavaPairDStream<String, String> filteredAdRealTimeLogDStream = adRealTimeLogDStream.transformToPair(

                new Function<JavaPairRDD<String,String>, JavaPairRDD<String,String>>() {

                    private static final long serialVersionUID = 1L;

                    @SuppressWarnings("resource")
                    @Override
                    public JavaPairRDD<String, String> call(JavaPairRDD<String, String> rdd) throws Exception {

                        // 首先，从mysql中查询所有黑名单用户，将其转换为一个rdd
                        IAdBlacklistDAO adBlacklistDAO = DAOFactory.getAdBlacklistDAO();
                        List<AdBlacklist> adBlacklists = adBlacklistDAO.findAll();

                        List<Tuple2<Long, Boolean>> tuples = new ArrayList<Tuple2<Long, Boolean>>();
                        for(AdBlacklist adBlacklist : adBlacklists) {
                            tuples.add(new Tuple2<Long, Boolean>(adBlacklist.getUserid(), true));
                        }

                        //通过rdd.context()获得SparkContext
                        JavaSparkContext sc = new JavaSparkContext(rdd.context());
                        JavaPairRDD<Long, Boolean> blacklistRDD = sc.parallelizePairs(tuples);//并行化

                        //将原始数据rdd映射成<userid, tuple2<string, string(timestamp province city userid adid)>>
                        JavaPairRDD<Long, Tuple2<String, String>> mappedRDD = rdd.mapToPair(
                            new PairFunction<Tuple2<String,String>, Long, Tuple2<String, String>>() {

                            private static final long serialVersionUID = 1L;

                            //tuple => timestamp province city userid adid
                            @Override
                            public Tuple2<Long, Tuple2<String, String>> call(Tuple2<String, String> tuple) throws Exception {
                                String log = tuple._2;
                                String[] logSplited = log.split(" ");
                                long userid = Long.valueOf(logSplited[3]);
                                return new Tuple2<Long, Tuple2<String, String>>(userid, tuple);
                            }
                        });

                        // 将原始日志数据rdd，与黑名单rdd，进行左外连接
                        // 如果说原始日志的userid，没有在对应的黑名单中，join不到，左外连接
                        // 用inner join，内连接，会导致数据丢失
                        JavaPairRDD<Long, Tuple2<Tuple2<String, String>, Optional<Boolean>>> joinedRDD = mappedRDD.leftOuterJoin(blacklistRDD);

                        JavaPairRDD<Long, Tuple2<Tuple2<String, String>, Optional<Boolean>>> filteredRDD = joinedRDD.filter(

                                new Function<Tuple2<Long,Tuple2<Tuple2<String,String>,Optional<Boolean>>>, Boolean>() {

                                    private static final long serialVersionUID = 1L;

                                    @Override
                                    public Boolean call(Tuple2<Long, Tuple2<Tuple2<String, String>, Optional<Boolean>>> tuple) throws Exception {

                                        Optional<Boolean> optional = tuple._2._2;

                                        // 如果这个值存在，那么说明原始日志中的userid，join到了某个黑名单用户
                                        if(optional.isPresent() && optional.get()) {
                                            return false;
                                        }
                                        return true;
                                    }
                                });

                        //返回过滤后的原始数据类型 tuple => timestamp province city userid adid
                        JavaPairRDD<String, String> resultRDD = filteredRDD.mapToPair(
                                new PairFunction<Tuple2<Long,Tuple2<Tuple2<String,String>,Optional<Boolean>>>, String, String>() {
                                    private static final long serialVersionUID = 1L;
                                    @Override
                                    public Tuple2<String, String> call(Tuple2<Long, Tuple2<Tuple2<String, String>, Optional<Boolean>>> tuple) throws Exception {
                                        return tuple._2._1;
                                    }
                                });
                        return resultRDD;
                        }
                        });
        return filteredAdRealTimeLogDStream;
    }

    /**
     * 生成动态黑名单
     * @param adRealTimeLogDStream
     */
    private static void generateDynamicBlacklist(JavaPairDStream<String, String> adRealTimeLogDStream) {

        // 一条一条的实时日志 timestamp province city userid adid
        //                  某个时间点 某个省份 某个城市 某个用户 某个广告
        // 计算出每5个秒内的数据中，每天每个用户每个广告的点击量
        // 通过对原始实时日志的处理,将日志的格式处理成<yyyyMMdd_userid_adid, 1L>格式
        JavaPairDStream<String, Long> dailyUserAdClickDStream = adRealTimeLogDStream.mapToPair(
                new PairFunction<Tuple2<String,String>, String, Long>() {
                    private static final long serialVersionUID = 1L;
                    @Override
                    public Tuple2<String, Long> call(Tuple2<String, String> tuple) throws Exception {
                        // 从tuple中获取到每一条原始的实时日志
                        String log = tuple._2;
                        String[] logSplited = log.split(" ");
                        // 提取出日期（yyyyMMdd）、userid、adid
                        String timestamp = logSplited[0];
                        Date date = new Date(Long.valueOf(timestamp));
                        String datekey = DateUtils.formatDateKey(date);
                        long userid = Long.valueOf(logSplited[3]);
                        long adid = Long.valueOf(logSplited[4]);
                        // 拼接key
                        String key = datekey + "_" + userid + "_" + adid;
                        return new Tuple2<String, Long>(key, 1L);
                    }
                });

        // 针对处理后的日志格式，执行reduceByKey算子即可,（每个batch中）每天每个用户对每个广告的点击量
        // 到这里为止，获取到了什么数据呢？
        // 源源不断的，每个5s的batch中，当天每个用户对每支广告的点击次数
        // <yyyyMMdd_userid_adid, clickCount>
        JavaPairDStream<String, Long> dailyUserAdClickCountDStream = dailyUserAdClickDStream.reduceByKey(
                new Function2<Long, Long, Long>() {
                    private static final long serialVersionUID = 1L;
                    @Override
                    public Long call(Long v1, Long v2) throws Exception {
                        return v1 + v2;
                    }
                });

        /**
         * 使用高性能方式将实时计算结果导入MySQL
         *
         * 对于实时计算程序的mysql插入，有两种pattern（模式）
         *
         * 1、比较挫：每次插入前，先查询，看看有没有数据，如果有，则执行insert语句；如果没有，则执行update语句；好处在于，每个key就对应一条记录；
         * 坏处在于，本来对一个分区的数据就是一条insert batch，现在很麻烦，还得先执行select语句，再决定是insert还是update。
         * j2ee系统，查询某个key的时候，就直接查询指定的key就好。
         * 2、稍微好一点：每次插入记录，你就插入就好，但是呢，需要在mysql库中，给每一个表，都加一个时间戳（timestamp），对于同一个key，
         * 5秒一个batch，每隔5秒中就有一个记录插入进去。相当于在mysql中维护了一个key的多个版本。
         * j2ee系统，查询某个key的时候，还得限定是要order by timestamp desc limit 1，查询最新时间版本的数据
         * 通过mysql来用这种方式，不是很好，很不方便后面j2ee系统的使用
         * 不用mysql；用hbase（timestamp的多个版本，而且它不却分insert和update，统一就是去对某个行键rowkey去做更新）
         */

        /**
         * Spark Streaming foreachRDD的正确使用方式
         *
         * 误区一：在driver上创建连接对象（比如网络连接或数据库连接）
         *
         * 如果在driver上创建连接对象，然后在RDD的算子函数内使用连接对象，那么就意味着需要将连接对象序列化后从driver传递到worker上。而连接对象（比如Connection对象）通常来说是不支持序列化的，此时通常会报序列化的异常（serialization errors）。因此连接对象必须在worker上创建，不要在driver上创建。
         *
         * dstream.foreachRDD
         * { rdd =>
         *   val connection = createNewConnection()  // 在driver上执行
         *   rdd.foreach { record =>
         *     connection.send(record) // 在worker上执行
         *   }
         * }
         *
         * 误区二：为每一条记录都创建一个连接对象
         *
         * dstream.foreachRDD
         * { rdd =>
         *   rdd.foreach { record =>
         *     val connection = createNewConnection()
         *     connection.send(record)
         *     connection.close()
         *   }
         * }
         *
         * 通常来说，连接对象的创建和销毁都是很消耗时间的。因此频繁地创建和销毁连接对象，可能会导致降低spark作业的整体性能和吞吐量。
         *
         * 正确做法一：为每个RDD分区创建一个连接对象
         *
         * dstream.foreachRDD
         * { rdd =>
         *   rdd.foreachPartition { partitionOfRecords =>
         *     val connection = createNewConnection()
         *     partitionOfRecords.foreach(record => connection.send(record))
         *     connection.close()
         *   }
         * }
         *
         * 比较正确的做法是：对DStream中的RDD，调用foreachPartition，对RDD中每个分区创建一个连接对象，使用一个连接对象将一个分区内的数据都写入底层MySQL中。这样可以大大减少创建的连接对象的数量。
         *
         * 正确做法二：为每个RDD分区使用一个连接池中的连接对象
         *
         * dstream.foreachRDD
         * { rdd =>
         *   rdd.foreachPartition { partitionOfRecords =>
         *     // 静态连接池，同时连接是懒创建的
         *     val connection = ConnectionPool.getConnection()
         *     partitionOfRecords.foreach(record => connection.send(record))
         *     ConnectionPool.returnConnection(connection)  // 用完以后将连接返回给连接池，进行复用
         *   }
         * }
         */
        dailyUserAdClickCountDStream.foreachRDD(

            new Function<JavaPairRDD<String,Long>, Void>() {
            private static final long serialVersionUID = 1L;
            @Override
            public Void call(JavaPairRDD<String, Long> rdd) throws Exception {

                rdd.foreachPartition(

                        new VoidFunction<Iterator<Tuple2<String,Long>>>() {

                            private static final long serialVersionUID = 1L;

                            @Override
                            public void call(Iterator<Tuple2<String, Long>> iterator) throws Exception {

                                // 对每个分区的数据就去获取一次连接对象
                                // 每次都是从连接池中获取，而不是每次都创建
                                // 写数据库操作，性能已经提到最高了
                                List<AdUserClickCount> adUserClickCounts = new ArrayList<AdUserClickCount>();

                                while(iterator.hasNext()) {

                                    Tuple2<String, Long> tuple = iterator.next();
                                    String[] keySplited = tuple._1.split("_");
                                    String date = DateUtils.formatDate(DateUtils.parseDateKey(keySplited[0]));
                                    // yyyy-MM-dd
                                    long userid = Long.valueOf(keySplited[1]);
                                    long adid = Long.valueOf(keySplited[2]);
                                    long clickCount = tuple._2;

                                    AdUserClickCount adUserClickCount = new AdUserClickCount();
                                    adUserClickCount.setDate(date);
                                    adUserClickCount.setUserid(userid);
                                    adUserClickCount.setAdid(adid);
                                    adUserClickCount.setClickCount(clickCount);

                                    adUserClickCounts.add(adUserClickCount);
                                }

                                IAdUserClickCountDAO adUserClickCountDAO = DAOFactory.getAdUserClickCountDAO();
                                adUserClickCountDAO.updateBatch(adUserClickCounts);

                            }
                        });
                return null;
            }
        });

        // 现在在mysql里面，已经有了累计的每天各用户对各广告的点击量
        // 遍历每个batch中的所有记录，对每条记录都要去查询一下，这一天这个用户对这个广告的累计点击量是多少
        // 从mysql中查询,查询出来的结果，如果你发现某个用户某天对某个广告的点击量已经大于等于100了
        // 那么就判定这个用户就是黑名单用户，就写入mysql的表中，持久化

        // 对batch中的数据，去查询mysql中的点击次数，使用哪个DStream呢？ dailyUserAdClickCountDStream
        // 为什么用这个batch？因为这个batch是聚合过的数据，已经按照yyyyMMdd_userid_adid进行过聚合了
        // 比如原始数据可能是一个batch有一万条，聚合过后可能只有五千条
        // 所以选用这个聚合后的dstream，既可以满足需求，而且可以尽量减少要处理的数据量
        JavaPairDStream<String, Long> blacklistDStream = dailyUserAdClickCountDStream.filter(

                new Function<Tuple2<String,Long>, Boolean>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Boolean call(Tuple2<String, Long> tuple) throws Exception {

                        String key = tuple._1;
                        String[] keySplited = key.split("_");

                        // yyyyMMdd -> yyyy-MM-dd
                        String date = DateUtils.formatDate(DateUtils.parseDateKey(keySplited[0]));
                        long userid = Long.valueOf(keySplited[1]);
                        long adid = Long.valueOf(keySplited[2]);

                        // 从mysql中查询指定日期指定用户对指定广告的点击量
                        IAdUserClickCountDAO adUserClickCountDAO = DAOFactory.getAdUserClickCountDAO();
                        int clickCount = adUserClickCountDAO.findClickCountByMultiKey(date, userid, adid);

                        // 判断，如果点击量大于等于100，ok，那么不好意思，你就是黑名单用户
                        // 那么就拉入黑名单，返回true
                        if(clickCount >= 100) {
                            return true;
                        }
                        // 反之，如果点击量小于100的，那么就暂时不要管它了
                        return false;
                    }
                });

        // blacklistDStream
        // 里面的每个batch，其实就是都是过滤出来的已经在某天对某个广告点击量超过100的用户
        // 遍历这个DStream中的每个rdd，然后将黑名单用户增加到mysql中
        // 这里一旦增加以后，在整个这段程序的前面，会加上根据黑名单动态过滤用户的逻辑
        // 我们可以认为，一旦用户被拉入黑名单之后，以后就不会再出现在这里了
        // 所以直接插入mysql即可

        // blacklistDStream中，可能有userid是重复的，如果直接这样插入的话
        // 那么是不是会发生，插入重复的黑明单用户
        // 我们在插入前要进行去重
        // yyyyMMdd_userid_adid
        // 20151220_10001_10002 100
        // 20151220_10001_10003 100
        // 10001这个userid就重复了
        // 实际上，是要通过对DStream执行操作，对其中的rdd中的userid进行全局的去重
        JavaDStream<Long> blacklistUseridDStream = blacklistDStream.map(

                new Function<Tuple2<String,Long>, Long>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Long call(Tuple2<String, Long> tuple) throws Exception {
                        String key = tuple._1;
                        String[] keySplited = key.split("_");
                        Long userid = Long.valueOf(keySplited[1]);
                        return userid;
                    }
                });
        //去除重复的userid
        //transform算子（将dstream中的每个batch RDD进行处理，转换为任意的其他RDD，功能很强大）
        JavaDStream<Long> distinctBlacklistUseridDStream = blacklistUseridDStream.transform(
                new Function<JavaRDD<Long>, JavaRDD<Long>>() {
                    private static final long serialVersionUID = 1L;
                    @Override
                    public JavaRDD<Long> call(JavaRDD<Long> rdd) throws Exception {
                        return rdd.distinct();
                    }
                });


        distinctBlacklistUseridDStream.foreachRDD(new Function<JavaRDD<Long>, Void>() {

            private static final long serialVersionUID = 1L;

            @Override
            public Void call(JavaRDD<Long> rdd) throws Exception {

                rdd.foreachPartition(
                        new VoidFunction<Iterator<Long>>() {

                            private static final long serialVersionUID = 1L;

                            @Override
                            public void call(Iterator<Long> iterator) throws Exception {
                                List<AdBlacklist> adBlacklists = new ArrayList<AdBlacklist>();

                                while(iterator.hasNext()) {

                                    long userid = iterator.next();
                                    AdBlacklist adBlacklist = new AdBlacklist();
                                    adBlacklist.setUserid(userid);

                                    adBlacklists.add(adBlacklist);
                                }

                                IAdBlacklistDAO adBlacklistDAO = DAOFactory.getAdBlacklistDAO();
                                adBlacklistDAO.insertBatch(adBlacklists);
                            }
                        });
                return null;
            }
        });

    }

    /**
     * 计算广告点击流量实时统计
     * @param filteredAdRealTimeLogDStream
     * @return
     */
    private static JavaPairDStream<String, Long> calculateRealTimeStat(JavaPairDStream<String, String> filteredAdRealTimeLogDStream) {

        // 业务逻辑一
        // 广告点击流量实时统计
        // 上面的黑名单实际上是广告类的实时系统中，比较常见的一种基础的应用
        // 实际上，我们要实现的业务功能，不是黑名单

        // 计算每天各省各城市各广告的点击量
        // 这份数据，实时不断地更新到mysql中的，J2EE系统，是提供实时报表给用户查看的
        // j2ee系统每隔几秒钟，就从mysql中搂一次最新数据，每次都可能不一样
        // 设计出来几个维度：日期、省份、城市、广告
        // j2ee系统就可以非常的灵活
        // 用户可以看到，实时的数据，比如2015-11-01，历史数据
        // 2015-12-01，当天，可以看到当天所有的实时数据（动态改变），比如江苏省南京市
        // 广告可以进行选择（广告主、广告名称、广告类型来筛选一个出来）
        // 拿着date、province、city、adid，去mysql中查询最新的数据
        // 等等，基于这几个维度，以及这份动态改变的数据，是可以实现比较灵活的广告点击流量查看的功能的

        // date province city userid adid
        // date_province_city_adid，作为key；1作为value
        // 通过spark，直接统计出来全局的点击次数，在spark集群中保留一份；在mysql中，也保留一份
        // 我们要对原始数据进行map，映射成<date_province_city_adid,1>格式
        // 然后呢，对上述格式的数据，执行updateStateByKey算子，spark streaming特有的一种算子，在spark集群内存中，维护一份key的全局状态
        JavaPairDStream<String, Long> mappedDStream = filteredAdRealTimeLogDStream.mapToPair(

                new PairFunction<Tuple2<String,String>, String, Long>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Tuple2<String, Long> call(Tuple2<String, String> tuple) throws Exception {

                        String log = tuple._2;
                        String[] logSplited = log.split(" ");

                        String timestamp = logSplited[0];//时间戳
                        Date date = new Date(Long.valueOf(timestamp));
                        String datekey = DateUtils.formatDateKey(date);	// yyyyMMdd

                        String province = logSplited[1];//省份
                        String city = logSplited[2];//城市
                        long adid = Long.valueOf(logSplited[4]);//广告ID

                        String key = datekey + "_" + province + "_" + city + "_" + adid;

                        return new Tuple2<String, Long>(key, 1L);
                    }
                });

        // 在这个dstream中，就相当于，有每个batch rdd累加的各个key（各天各省份各城市各广告的点击次数）
        // 每次计算出最新的值，就在aggregatedDStream中的每个batch rdd中反应出来
        JavaPairDStream<String, Long> aggregatedDStream = mappedDStream.updateStateByKey(

                new Function2<List<Long>, Optional<Long>, Optional<Long>>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Optional<Long> call(List<Long> values, Optional<Long> optional) throws Exception {
                        // 举例来说
                        // 对于每个key，都会调用一次这个方法
                        // 比如key是<20151201_Jiangsu_Nanjing_10001,1>，就会来调用一次这个方法
                        // 10个
                        // values，(1,1,1,1,1,1,1,1,1,1)

                        // 首先根据optional判断，之前这个key，是否有对应的状态
                        long clickCount = 0L;

                        // 如果说，之前是存在这个状态的，那么就以之前的状态作为起点，进行值的累加
                        if(optional.isPresent()) {
                            clickCount = optional.get();
                        }

                        // values，代表了，batch rdd中，每个key对应的所有的值
                        for(Long value : values) {
                            clickCount += value;
                        }
                        return Optional.of(clickCount);
                    }
                });

        // 将计算出来的最新结果，同步一份到mysql中，以便于j2ee系统使用
        aggregatedDStream.foreachRDD(

            new Function<JavaPairRDD<String,Long>, Void>() {

            private static final long serialVersionUID = 1L;

            @Override
            public Void call(JavaPairRDD<String, Long> rdd) throws Exception {

                rdd.foreachPartition(

                    new VoidFunction<Iterator<Tuple2<String,Long>>>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public void call(Iterator<Tuple2<String, Long>> iterator) throws Exception {

                        List<AdStat> adStats = new ArrayList<AdStat>();

                        while(iterator.hasNext()) {

                            Tuple2<String, Long> tuple = iterator.next();

                            String[] keySplited = tuple._1.split("_");

                            String date = keySplited[0];
                            String province = keySplited[1];
                            String city = keySplited[2];
                            long adid = Long.valueOf(keySplited[3]);
                            long clickCount = tuple._2;

                            AdStat adStat = new AdStat();
                            adStat.setDate(date);
                            adStat.setProvince(province);
                            adStat.setCity(city);
                            adStat.setAdid(adid);
                            adStat.setClickCount(clickCount);

                            adStats.add(adStat);
                        }

                        IAdStatDAO adStatDAO = DAOFactory.getAdStatDAO();
                        adStatDAO.updateBatch(adStats);
                    }
                });
                return null;
            }
        });
        return aggregatedDStream;
    }

    /**
     * 计算每天各省份的top3热门广告
     * @param adRealTimeStatDStream
     */
    private static void calculateProvinceTop3Ad(JavaPairDStream<String, Long> adRealTimeStatDStream) {

        // adRealTimeStatDStream
        // 每一个batch rdd，都代表了最新的全量的每天各省份各城市各广告的点击量
        JavaDStream<Row> rowsDStream = adRealTimeStatDStream.transform(

                new Function<JavaPairRDD<String,Long>, JavaRDD<Row>>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public JavaRDD<Row> call(JavaPairRDD<String, Long> rdd) throws Exception {

                        // <yyyyMMdd_province_city_adid, clickCount>
                        // <yyyyMMdd_province_adid, clickCount>

                        // 计算出每天各省份各广告的点击量

                        JavaPairRDD<String, Long> mappedRDD = rdd.mapToPair(

                                new PairFunction<Tuple2<String,Long>, String, Long>() {

                                    private static final long serialVersionUID = 1L;

                                    @Override
                                    public Tuple2<String, Long> call(Tuple2<String, Long> tuple) throws Exception {

                                        String[] keySplited = tuple._1.split("_");

                                        String date = keySplited[0];
                                        String province = keySplited[1];
                                        long adid = Long.valueOf(keySplited[3]);
                                        long clickCount = tuple._2;

                                        String key = date + "_" + province + "_" + adid;

                                        return new Tuple2<String, Long>(key, clickCount);
                                    }

                                });

                        JavaPairRDD<String, Long> dailyAdClickCountByProvinceRDD = mappedRDD.reduceByKey(
                                new Function2<Long, Long, Long>() {
                                    private static final long serialVersionUID = 1L;
                                    @Override
                                    public Long call(Long v1, Long v2) throws Exception {
                                        return v1 + v2;
                                    }
                                });

                        // 将dailyAdClickCountByProvinceRDD转换为DataFrame
                        // 注册为一张临时表
                        // 使用Spark SQL，通过开窗函数，获取到各省份的top3热门广告

                        JavaRDD<Row> rowsRDD = dailyAdClickCountByProvinceRDD.map(

                                new Function<Tuple2<String,Long>, Row>() {

                                    private static final long serialVersionUID = 1L;

                                    @Override
                                    public Row call(Tuple2<String, Long> tuple) throws Exception {
                                        String[] keySplited = tuple._1.split("_");
                                        String datekey = keySplited[0];
                                        String province = keySplited[1];
                                        long adid = Long.valueOf(keySplited[2]);
                                        long clickCount = tuple._2;

                                        String date = DateUtils.formatDate(DateUtils.parseDateKey(datekey));

                                        return RowFactory.create(date, province, adid, clickCount);

                                    }
                                });

                        StructType schema = DataTypes.createStructType(
                                Arrays.asList(
                                DataTypes.createStructField("date", DataTypes.StringType, true),
                                DataTypes.createStructField("province", DataTypes.StringType, true),
                                DataTypes.createStructField("ad_id", DataTypes.LongType, true),
                                DataTypes.createStructField("click_count", DataTypes.LongType, true)));

                        HiveContext sqlContext = new HiveContext(rdd.context());

                        DataFrame dailyAdClickCountByProvinceDF = sqlContext.createDataFrame(rowsRDD, schema);

                        // 将dailyAdClickCountByProvinceDF，注册成一张临时表
                        dailyAdClickCountByProvinceDF.registerTempTable("tmp_daily_ad_click_count_by_prov");

                        // 使用Spark SQL执行SQL语句，配合开窗函数，统计出各身份top3热门的广告
                        DataFrame provinceTop3AdDF = sqlContext.sql(
                                "SELECT "
                                        + "date,"
                                        + "province,"
                                        + "ad_id,"
                                        + "click_count "
                                        + "FROM ( "
                                        + "SELECT "
                                        + "date,"
                                        + "province,"
                                        + "ad_id,"
                                        + "click_count,"
                                        + "ROW_NUMBER() OVER(PARTITION BY province ORDER BY click_count DESC) rank "
                                        + "FROM tmp_daily_ad_click_count_by_prov "
                                        + ") t "
                                        + "WHERE rank>=3"
                        );

                        return provinceTop3AdDF.javaRDD();
                    }
                });

        // rowsDStream
        // 每次都是刷新出来各个省份最热门的top3广告
        // 将其中的数据批量更新到MySQL中
        rowsDStream.foreachRDD(

            new Function<JavaRDD<Row>, Void>() {

            private static final long serialVersionUID = 1L;

            @Override
            public Void call(JavaRDD<Row> rdd) throws Exception {

                rdd.foreachPartition(

                    new VoidFunction<Iterator<Row>>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public void call(Iterator<Row> iterator) throws Exception {
                        List<AdProvinceTop3> adProvinceTop3s = new ArrayList<AdProvinceTop3>();

                        while(iterator.hasNext()) {
                            Row row = iterator.next();
                            String date = row.getString(0);
                            String province = row.getString(1);
                            long adid = row.getLong(2);
                            long clickCount = row.getLong(3);

                            AdProvinceTop3 adProvinceTop3 = new AdProvinceTop3();
                            adProvinceTop3.setDate(date);
                            adProvinceTop3.setProvince(province);
                            adProvinceTop3.setAdid(adid);
                            adProvinceTop3.setClickCount(clickCount);

                            adProvinceTop3s.add(adProvinceTop3);
                        }
                        IAdProvinceTop3DAO adProvinceTop3DAO = DAOFactory.getAdProvinceTop3DAO();
                        adProvinceTop3DAO.updateBatch(adProvinceTop3s);
                    }

                });
                return null;
            }
        });
    }

    /**
     * 计算最近1小时滑动窗口内的广告点击趋势
     * @param adRealTimeLogDStream
     */
    private static void calculateAdClickCountByWindow(JavaPairInputDStream<String, String> adRealTimeLogDStream) {

        // 映射成<yyyyMMddHHMM_adid,1L>格式
        JavaPairDStream<String, Long> pairDStream = adRealTimeLogDStream.mapToPair(

                new PairFunction<Tuple2<String,String>, String, Long>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Tuple2<String, Long> call(Tuple2<String, String> tuple) throws Exception {

                        // timestamp province city userid adid
                        String[] logSplited = tuple._2.split(" ");

                        String timeMinute = DateUtils.formatTimeMinute(new Date(Long.valueOf(logSplited[0])));
                        long adid = Long.valueOf(logSplited[4]);

                        return new Tuple2<String, Long>(timeMinute + "_" + adid, 1L);
                    }
                });

        // 过来的每个batch rdd，都会被映射成<yyyyMMddHHMM_adid,1L>的格式
        // 每次出来一个新的batch，都要获取最近1小时内的所有的batch
        // 然后根据key进行reduceByKey操作，统计出来最近一小时内的各分钟各广告的点击次数
        // 1小时滑动窗口内的广告点击趋势
        // 点图 / 折线图
        //计算得到每小时每分钟内的点击数
        JavaPairDStream<String, Long> aggrRDD = pairDStream.reduceByKeyAndWindow(
                new Function2<Long, Long, Long>() {
                    private static final long serialVersionUID = 1L;
                    @Override
                    public Long call(Long v1, Long v2) throws Exception {
                        return v1 + v2;
                    }
                }
                , Durations.minutes(60)//滑动窗口总时长
                , Durations.seconds(10));//每隔10秒执行一次滑动窗口

        // aggrRDD
        // 每次都可以拿到，最近1小时内，各分钟（yyyyMMddHHMM）各广告的点击量
        // 各广告，在最近1小时内，各分钟的点击量
        aggrRDD.foreachRDD(
            new Function<JavaPairRDD<String,Long>, Void>() {

            private static final long serialVersionUID = 1L;

            @Override
            public Void call(JavaPairRDD<String, Long> rdd) throws Exception {

                rdd.foreachPartition(
                    new VoidFunction<Iterator<Tuple2<String,Long>>>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public void call(Iterator<Tuple2<String, Long>> iterator) throws Exception {

                        List<AdClickTrend> adClickTrends = new ArrayList<AdClickTrend>();

                        while(iterator.hasNext()) {

                            Tuple2<String, Long> tuple = iterator.next();
                            String[] keySplited = tuple._1.split("_");

                            // yyyyMMddHHmm
                            String dateMinute = keySplited[0];
                            long adid = Long.valueOf(keySplited[1]);
                            long clickCount = tuple._2;

                            String date = DateUtils.formatDate(DateUtils.parseDateKey(dateMinute.substring(0, 8)));
                            String hour = dateMinute.substring(8, 10);
                            String minute = dateMinute.substring(10);

                            AdClickTrend adClickTrend = new AdClickTrend();
                            adClickTrend.setDate(date);
                            adClickTrend.setHour(hour);
                            adClickTrend.setMinute(minute);
                            adClickTrend.setAdid(adid);
                            adClickTrend.setClickCount(clickCount);

                            adClickTrends.add(adClickTrend);
                        }

                        IAdClickTrendDAO adClickTrendDAO = DAOFactory.getAdClickTrendDAO();
                        adClickTrendDAO.updateBatch(adClickTrends);
                    }

                });

                return null;
            }

        });
    }

}
