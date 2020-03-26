package spark.product;

import com.alibaba.fastjson.JSONObject;
import conf.ConfigurationManager;
import constant.Constants;
import dao.IAreaTop3ProductDAO;
import dao.ITaskDAO;
import dao.factory.DAOFactory;
import domain.AreaTop3Product;
import domain.Task;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import scala.Tuple2;
import util.ParamUtils;
import util.SparkUtils;

import java.util.*;

/**
 * 各区域top3热门商品统计Spark作业
 *
 * 需求：根据用户指定的日期范围，统计各个区域下的最热门的top3商品
 *
 * 1、区域信息在哪里，各个城市的信息，城市是不怎么变化的，没有必要存储在hive里？MySQL，Hive和MySQL异构数据源使用，技术点
 * 2、hive用户行为数据，和mysql城市信息，join，关联之后是RDD？RDD转换DataFrame，注册临时表，技术点
 * 3、各个区域下各个商品的点击量，保留每个区域的城市列表数据？自定义UDAF函数，group_concat_distinct()
 * 4、product_id，join hive表中的商品信息，商品信息在哪里？Hive。商品的经营类型是什么？自定义UDF函数，get_json_object()，if()
 * 5、获取每个区域的点击量top3商品？开窗函数；给每个区域打上级别的标识，西北大区，经济落后，区域上的划分，C类区域；北京、上海，发达，标记A类
 * 6、Spark SQL的数据倾斜解决方案？双重group by、随机key以及扩容表（自定义UDF函数，random_key()）、内置reduce join转换为map join、shuffle并行度
 *
 * 技术方案设计：
 *
 * 1、查询task，获取日期范围，通过Spark SQL，查询user_visit_action表中的指定日期范围内的数据，过滤出，商品点击行为，click_product_id is not null；click_product_id != 'NULL'；click_product_id != 'null'；city_id，click_product_id
 * 2、使用Spark SQL从MySQL中查询出来城市信息（city_id、city_name、area），用户访问行为数据要跟城市信息进行join，city_id、city_name、area、product_id，RDD，转换成DataFrame，注册成一个临时表
 * 3、Spark SQL内置函数（case when），对area打标记（华东大区，A级，华中大区，B级，东北大区，C级，西北大区，D级），area_level
 * 4、计算出来每个区域下每个商品的点击次数，group by area, product_id；保留每个区域的城市名称列表；自定义UDAF，group_concat_distinct()函数，聚合出来一个city_names字段，area、product_id、city_names、click_count
 * 5、join商品明细表，hive（product_id、product_name、extend_info），extend_info是json类型，自定义UDF，get_json_object()函数，取出其中的product_status字段，if()函数（Spark SQL内置函数），判断，0 自营，1 第三方；（area、product_id、city_names、click_count、product_name、product_status）
 * 6、开窗函数，根据area来聚合，获取每个area下，click_count排名前3的product信息；area、area_level、product_id、city_names、click_count、product_name、product_status
 * 7、结果写入MySQL表中
 * 8、Spark SQL的数据倾斜解决方案？双重group by、随机key以及扩容表（自定义UDF函数，random_key()）、Spark SQL内置的reduce join转换为map join、提高shuffle并行度
 * 9、本地测试和生产环境的测试
 *
 * 基础数据的准备和设计
 *
 * 1、MySQL表中，要有city_info，city_id、city_name、area
 * 2、Hive表中，要有一个product_info表，product_id、product_name、extend_info
 * 3、MySQL中，结果表，task_id、area、area_level、product_id、city_names、click_count、product_name、product_status
 */
public class AreaTop3ProductSpark {

    public static void main(String[] args) {

        //创建sparkConf
        SparkConf sparkConf = new SparkConf().setAppName("AreaTop3ProductSpark");
        SparkUtils.setMaster(sparkConf);

        //创建上下文
        JavaSparkContext sc = new JavaSparkContext(sparkConf);
        SQLContext sqlContext = SparkUtils.getSQLContext(sc.sc());
        //sqlContext.setConf("spark.sql.shuffle.partitions", "1000"); //设置shuffle并行度
        //sqlContext.setConf("spark.sql.autoBroadcastJoinThreshold", "20971520"); //reduce join转换为map join

        // 注册自定义函数
        sqlContext.udf().register("concat_long_string",new ConcatLongStringUDF(), DataTypes.StringType);
        sqlContext.udf().register("group_concat_distinct",new GroupConcatDistinctUDAF());
        sqlContext.udf().register("get_json_object",new GetJsonObjectUDF(), DataTypes.StringType);
        sqlContext.udf().register("random_prefix",new RandomPrefixUDF(), DataTypes.StringType);
        sqlContext.udf().register("remove_random_prefix",new RemoveRandomPrefixUDF(), DataTypes.StringType);

        //准备模拟数据
        SparkUtils.mockData(sc,sqlContext);

        //获取命令行传入的taskid，查询对应的任务参数
        ITaskDAO taskDAO = DAOFactory.getTaskDAO();
        Long taskid = ParamUtils.getTaskIdFromArgs(args, Constants.SPARK_LOCAL_TASKID_PRODUCT);
        Task task = taskDAO.findById(taskid);
        JSONObject taskParam = JSONObject.parseObject(task.getTaskParam());
        String startDate = ParamUtils.getParam(taskParam, Constants.PARAM_START_DATE);
        String endDate = ParamUtils.getParam(taskParam, Constants.PARAM_END_DATE);

        // 查询用户指定日期范围内的点击行为数据（city_id，在哪个城市发生的点击行为）
        // 技术点1：Hive数据源的使用
        //返回数据格式 ： <cityid,Row(city_id,product_id)>
        JavaPairRDD<Long, Row> cityid2clickActionRDD = getcityid2ClickActionRDDByDate(sqlContext, startDate, endDate);
        System.out.println("cityid2clickActionRDD: " + cityid2clickActionRDD.count());

        // 从MySQL中查询城市信息
        // 技术点2：异构数据源之MySQL的使用
        //city_id、city_name、area
        JavaPairRDD<Long, Row> cityid2cityInfoRDD = getcityid2CityInfoRDD(sqlContext);
        System.out.println("cityid2cityInfoRDD: " + cityid2cityInfoRDD.count());

        // 关联点击行为数据和城市信息数据生成点击商品基础信息临时表
        // 技术点3：将RDD转换为DataFrame，并注册临时表 tmp_click_product_basic
        //city_id，city_name,area,product_id
        generateTempClickProductBasicTable(sqlContext,cityid2clickActionRDD, cityid2cityInfoRDD);

        // 从tmp_click_product_basic获取数据并生成各区域各商品点击次数的临时表 tmp_area_product_click_count
        // area，product_id,click_count,（1:北京,2:上海。。。）
        generateTempAreaPrdocutClickCountTable(sqlContext);

        // 生成包含完整商品信息的各区域各商品点击次数的临时表
        // area, product_id, click_count, city_infos, product_name, product_status
        generateTempAreaFullProductClickCountTable(sqlContext);

        // 使用开窗函数获取各个区域内点击次数排名前3的热门商品
        //area,area_level,product_id,click_count,city_infos,product_name,product_status
        JavaRDD<Row> areaTop3ProductRDD = getAreaTop3ProductRDD(sqlContext);
        System.out.println("areaTop3ProductRDD: " + areaTop3ProductRDD.count());

        // 就这个业务需求而言，计算出来的最终数据量是比较小的,总共就不到10个区域，每个区域还是top3热门商品，总共最后数据量也就是几十个
        // 所以可以直接将数据collect()到本地,用批量插入的方式，一次性插入mysql即可
        List<Row> rows = areaTop3ProductRDD.collect();
        System.out.println("rows: " + rows.size());
        persistAreaTop3Product(taskid, rows);

        sc.close();
    }

    /**
     * 查询指定日期范围内的点击行为数据
     * @param sqlContext
     * @param startDate 起始日期
     * @param endDate 截止日期
     * @return 点击行为数据
     */
    private static JavaPairRDD<Long, Row> getcityid2ClickActionRDDByDate(SQLContext sqlContext, String startDate, String endDate) {
        // 从user_visit_action中，查询用户访问行为数据
        // 第一个限定：click_product_id，限定为不为空的访问行为，那么就代表着点击行为
        // 第二个限定：在用户指定的日期范围内的数据

        String sql =
                "SELECT "
                        + "city_id,"
                        + "click_product_id product_id "
                        + "FROM user_visit_action "
                        + "WHERE click_product_id IS NOT NULL "
                        + "AND date>='" + startDate + "' "
                        + "AND date<='" + endDate + "'";

        DataFrame clickActionDF = sqlContext.sql(sql);

        JavaRDD<Row> clickActionRDD = clickActionDF.javaRDD();

        JavaPairRDD<Long, Row> cityid2clickActionRDD = clickActionRDD.mapToPair(

                new PairFunction<Row, Long, Row>() {
                    private static final long serialVersionUID = 1L;
                    @Override
                    public Tuple2<Long, Row> call(Row row) throws Exception {
                        Long cityid = row.getLong(0);
                        return new Tuple2<Long, Row>(cityid, row);
                    }
                });

        return cityid2clickActionRDD;
    }

    /**
     * 使用Spark SQL从MySQL中查询城市信息
     * @param sqlContext SQLContext
     * @return
     */
    private static JavaPairRDD<Long, Row> getcityid2CityInfoRDD(SQLContext sqlContext) {

        // 构建MySQL连接配置信息（直接从配置文件中获取）
        String url = null;
        String user = null;
        String password = null;

        boolean local = ConfigurationManager.getBoolean(Constants.SPARK_LOCAL);
        if(local) {
            url = ConfigurationManager.getProperty(Constants.JDBC_URL);
            user = ConfigurationManager.getProperty(Constants.JDBC_USER);
            password = ConfigurationManager.getProperty(Constants.JDBC_PASSWORD);
        } else {
            url = ConfigurationManager.getProperty(Constants.JDBC_URL_PROD);
            user = ConfigurationManager.getProperty(Constants.JDBC_USER_PROD);
            password = ConfigurationManager.getProperty(Constants.JDBC_PASSWORD_PROD);
        }

        Map<String, String> options = new HashMap<String, String>();
        options.put("url", url);
        options.put("dbtable", "city_info");
        options.put("user", user);
        options.put("password", password);

        // 通过SQLContext去从MySQL中查询数据
        DataFrame cityInfoDF =  sqlContext.read().format("jdbc").options(options).load();

        // 返回RDD
        JavaRDD<Row> cityInfoRDD = cityInfoDF.javaRDD();

        JavaPairRDD<Long, Row> cityid2cityInfoRDD = cityInfoRDD.mapToPair(

                new PairFunction<Row, Long, Row>() {
                    private static final long serialVersionUID = 1L;
                    @Override
                    public Tuple2<Long, Row> call(Row row) throws Exception {
                        long cityid = Long.valueOf(String.valueOf(row.get(0)));
                        return new Tuple2<Long, Row>(cityid, row);
                    }
                });

        return cityid2cityInfoRDD;
    }

    /**
     * 生成点击商品基础信息临时表
     * @param sqlContext
     * @param cityid2clickActionRDD
     * @param cityid2cityInfoRDD
     */
    private static void generateTempClickProductBasicTable(SQLContext sqlContext,JavaPairRDD<Long, Row> cityid2clickActionRDD,JavaPairRDD<Long, Row> cityid2cityInfoRDD) {

        // 执行join操作，进行点击行为数据和城市数据的关联
        JavaPairRDD<Long, Tuple2<Row, Row>> joinedRDD = cityid2clickActionRDD.join(cityid2cityInfoRDD);

        // 将上面的JavaPairRDD，转换成一个JavaRDD<Row>（才能将RDD转换为DataFrame）
        JavaRDD<Row> mappedRDD = joinedRDD.map(

                new Function<Tuple2<Long,Tuple2<Row,Row>>, Row>() {
                    private static final long serialVersionUID = 1L;
                    @Override
                    public Row call(Tuple2<Long, Tuple2<Row, Row>> tuple) throws Exception {

                        long cityid = tuple._1;
                        Row clickAction = tuple._2._1;
                        Row cityInfo = tuple._2._2;
                        long productid = clickAction.getLong(1);
                        String cityName = cityInfo.getString(1);
                        String area = cityInfo.getString(2);

                        return RowFactory.create(cityid, cityName, area, productid);
                    }
                });

        // 基于JavaRDD<Row>的格式，就可以将其转换为DataFrame
        List<StructField> structFields = new ArrayList<StructField>();
        structFields.add(DataTypes.createStructField("city_id", DataTypes.LongType, true));
        structFields.add(DataTypes.createStructField("city_name", DataTypes.StringType, true));
        structFields.add(DataTypes.createStructField("area", DataTypes.StringType, true));
        structFields.add(DataTypes.createStructField("product_id", DataTypes.LongType, true));

        // 1 北京
        // 2 上海
        // 1 北京
        // group by area,product_id
        // 1:北京,2:上海

        // 两个函数
        // UDF：concat2()，将两个字段拼接起来，用指定的分隔符
        // UDAF：group_concat_distinct()，将一个分组中的多个字段值，用逗号拼接起来，同时进行去重

        StructType schema = DataTypes.createStructType(structFields);

        DataFrame df = sqlContext.createDataFrame(mappedRDD, schema);
        System.out.println("tmp_click_product_basic: " + df.count());

        // 将DataFrame中的数据，注册成临时表（tmp_click_product_basic）
        df.registerTempTable("tmp_click_product_basic");

    }

    /**
     * 生成各区域各商品点击次数临时表
     * @param sqlContext
     * area，product_id,click_count,（1:北京,2:上海。。。）
     */
    private static void generateTempAreaPrdocutClickCountTable(SQLContext sqlContext) {

        // 按照area和product_id两个字段进行分组
        // 计算出各区域各商品的点击次数
        // 可以获取到每个area下的每个product_id的城市信息拼接起来的串

//        String sql =
//                "SELECT "
//                        + "area,"
//                        + "product_id,"
//                        + "count(*) click_count, "
//                        + "group_concat_distinct(concat_long_string(city_id,city_name,':')) city_infos "
//                        + "FROM tmp_click_product_basic "
//                        + "GROUP BY area,product_id ";


        // 双重group by 数据倾斜解决方案
		String sql =
				"SELECT "
					+ "product_id_area,"
					+ "count(click_count) click_count,"
					+ "group_concat_distinct(city_infos) city_infos "
				+ "FROM ( "
					+ "SELECT "
						+ "remove_random_prefix(product_id_area) product_id_area,"
						+ "click_count,"
						+ "city_infos "
					+ "FROM ( "
						+ "SELECT "
							+ "product_id_area,"
							+ "count(*) click_count,"
							+ "group_concat_distinct(concat_long_string(city_id,city_name,':')) city_infos "
						+ "FROM ( "
							+ "SELECT "
								+ "random_prefix(concat_long_string(product_id,area,':'), 10) product_id_area,"
								+ "city_id,"
								+ "city_name "
							+ "FROM tmp_click_product_basic "
						+ ") t1 GROUP BY product_id_area "
					+ ") t2 "
				+ ") t3 GROUP BY product_id_area ";

        // 使用Spark SQL执行这条SQL语句
        DataFrame df = sqlContext.sql(sql);

        System.out.println("tmp_area_product_click_count: " + df.count());

        // 再次将查询出来的数据注册为一个临时表
        // 各区域各商品的点击次数（以及额外的城市列表）
        df.registerTempTable("tmp_area_product_click_count");
    }

    /**
     * 生成区域商品点击次数临时表（包含了商品的完整信息）
     * @param sqlContext
     */
    private static void generateTempAreaFullProductClickCountTable(SQLContext sqlContext) {

        // 将之前得到的各区域各商品点击次数表tmp_area_product_click_count   area，product_id,click_count,（1:北京,2:上海。。。）
        // 去关联商品信息表product_info， product_id，product_name和product_status
        // product_status要特殊处理，0，1，分别代表了自营和第三方的商品，放在了一个json串里面
        // get_json_object()函数，可以从json串中获取指定的字段的值
        // if()函数，判断，如果product_status是0，那么就是自营商品；如果是1，那么就是第三方商品
        // area, product_id, click_count, city_infos, product_name, product_status

        // 为什么要费时费力，计算出来商品经营类型
        // 你拿到到了某个区域top3热门的商品，那么其实这个商品是自营的，还是第三方的
        // 其实是很重要的一件事

        // 技术点：内置if函数的使用
//        String sql =
//                "SELECT "
//                        + "tapcc.area,"
//                        + "tapcc.product_id,"
//                        + "tapcc.click_count,"
//                        + "tapcc.city_infos,"
//                        + "pi.product_name,"
//                        + "if(get_json_object(pi.extend_info,'product_status')='0','Self','Third Party') product_status "
//                        + "FROM tmp_area_product_click_count tapcc "
//                        + "JOIN product_info pi ON tapcc.product_id=pi.product_id ";


//          随机key与扩容表解决数据倾斜问题
        	JavaRDD<Row> rdd = sqlContext.sql("select * from product_info").javaRDD();
        	//扩容
		    JavaRDD<Row> flattedRDD = rdd.flatMap(new FlatMapFunction<Row, Row>() {
			        private static final long serialVersionUID = 1L;
			        @Override
			        public Iterable<Row> call(Row row) throws Exception {
				        List<Row> list = new ArrayList<Row>();
				        for(int i = 0; i < 10; i ++)
				        {
					        long productid = row.getLong(0);
					        String _productid = i + "_" + productid;

					        Row _row = RowFactory.create(_productid, row.get(1), row.get(2));
					        list.add(_row);
				        }
				    return list;}
		    });

		    StructType _schema =
                DataTypes.createStructType(Arrays.asList(
                DataTypes.createStructField("product_id", DataTypes.StringType, true),
				DataTypes.createStructField("product_name", DataTypes.StringType, true),
				DataTypes.createStructField("product_status", DataTypes.StringType, true)));

		    DataFrame _df = sqlContext.createDataFrame(flattedRDD, _schema);
		    _df.registerTempTable("tmp_product_info");

		    String sql =
				"SELECT "
					+ "tapcc.area,"
					+ "remove_random_prefix(tapcc.product_id) product_id,"
					+ "tapcc.click_count,"
					+ "tapcc.city_infos,"
					+ "pi.product_name,"
					+ "if(get_json_object(pi.extend_info,'product_status')=0,'自营商品','第三方商品') product_status "
				+ "FROM ("
					+ "SELECT "
						+ "area,"
						+ "random_prefix(product_id, 10) product_id,"
						+ "click_count,"
						+ "city_infos "
					+ "FROM tmp_area_product_click_count "
				+ ") tapcc "
				+ "JOIN tmp_product_info pi ON tapcc.product_id=pi.product_id ";

        DataFrame df = sqlContext.sql(sql);

        System.out.println("tmp_area_fullprod_click_count: " + df.count());

        df.registerTempTable("tmp_area_fullprod_click_count");
    }

    /**
     * 获取各区域top3热门商品
     * @param sqlContext
     * @return
     */
    private static JavaRDD<Row> getAreaTop3ProductRDD(SQLContext sqlContext) {

        // 技术点：开窗函数

        // 使用开窗函数先进行一个子查询
        // 按照area进行分组，给每个分组内的数据，按照点击次数降序排序，打上一个组内的行号
        // 接着在外层查询中，过滤出各个组内的行号排名前3的数据
        // 其实就是咱们的各个区域下top3热门商品

        // 华北、华东、华南、华中、西北、西南、东北
        // A级：华北、华东
        // B级：华南、华中
        // C级：西北、西南
        // D级：东北

        // case when
        // 根据多个条件，不同的条件对应不同的值
        // case when then ... when then ... else ... end

        //area,area_level,product_id,click_count,city_infos,product_name,product_status
        String sql =
                "SELECT "
                        + "area,"
                        + "CASE "
                        + "WHEN area='China North' OR area='China East' THEN 'A Level' "
                        + "WHEN area='China South' OR area='China Middle' THEN 'B Level' "
                        + "WHEN area='West North' OR area='West South' THEN 'C Level' "
                        + "ELSE 'D Level' "
                        + "END area_level,"
                        + "product_id,"
                        + "click_count,"
                        + "city_infos,"
                        + "product_name,"
                        + "product_status "
                        + "FROM ("
                        + "SELECT "
                        + "area,"
                        + "product_id,"
                        + "click_count,"
                        + "city_infos,"
                        + "product_name,"
                        + "product_status,"
                        + "row_number() OVER (PARTITION BY area ORDER BY click_count DESC) rank "
                        + "FROM tmp_area_fullprod_click_count "
                        + ") t "
                        + "WHERE rank<=3";

        DataFrame df = sqlContext.sql(sql);

        return df.javaRDD();
    }

    /**
     * 将计算出来的各区域top3热门商品写入MySQL中
     * @param rows
     */
    private static void persistAreaTop3Product(long taskid, List<Row> rows) {
        List<AreaTop3Product> areaTop3Products = new ArrayList<AreaTop3Product>();

        for(Row row : rows) {
            AreaTop3Product areaTop3Product = new AreaTop3Product();
            areaTop3Product.setTaskid(taskid);
            areaTop3Product.setArea(row.getString(0));
            areaTop3Product.setAreaLevel(row.getString(1));
            areaTop3Product.setProductid(row.getLong(2));
            areaTop3Product.setClickCount(Long.valueOf(String.valueOf(row.get(3))));
            areaTop3Product.setCityInfos(row.getString(4));
            areaTop3Product.setProductName(row.getString(5));
            areaTop3Product.setProductStatus(row.getString(6));
            areaTop3Products.add(areaTop3Product);
        }

        IAreaTop3ProductDAO areTop3ProductDAO = DAOFactory.getAreaTop3ProductDAO();
        areTop3ProductDAO.insertBatch(areaTop3Products);
    }

}

