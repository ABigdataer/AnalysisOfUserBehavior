package com.ibeifeng.sparkproject

import constant.Constants
import org.apache.spark.AccumulatorParam
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import util.StringUtils


/**
 * 用Scala自定义计数器
 */
object SessionAggrStatAccumulatorTest {
  
  def main(args: Array[String]): Unit = {
    /**
     * Scala中，自定义Accumulator
     * 使用object，直接定义一个伴生对象即可
     * 需要实现AccumulatorParam接口，并使用[]语法，定义输入输出的数据格式
     */
    object SessionAggrStatAccumulator extends AccumulatorParam[String] {
      
      /**
       * 首先要实现zero方法
       * 负责返回一个初始值
       */
      def zero(initialValue: String): String = {
        Constants.SESSION_COUNT + "=0|" + Constants.TIME_PERIOD_1s_3s + "=0|" + Constants.TIME_PERIOD_4s_6s + "=0|" + Constants.TIME_PERIOD_7s_9s + "=0|" + Constants.TIME_PERIOD_10s_30s + "=0|" + Constants.TIME_PERIOD_30s_60s + "=0|" + Constants.TIME_PERIOD_1m_3m + "=0|" + Constants.TIME_PERIOD_3m_10m + "=0|" + Constants.TIME_PERIOD_10m_30m + "=0|" + Constants.TIME_PERIOD_30m + "=0|" + Constants.STEP_PERIOD_1_3 + "=0|" + Constants.STEP_PERIOD_4_6 + "=0|" + Constants.STEP_PERIOD_7_9 + "=0|" + Constants.STEP_PERIOD_10_30 + "=0|" + Constants.STEP_PERIOD_30_60 + "=0|" + Constants.STEP_PERIOD_60 + "=0"
      }
      
      /**
       * 其次需要实现一个累加方法
       */
      def addInPlace(v1: String, v2: String): String = {
        // 如果初始化值为空，那么返回v2
        if(v1 == "") {
          v2
        } else {
          // 从现有的连接串中提取v2对应的值
          val oldValue = StringUtils.getFieldFromConcatString(v1, "\\|", v2);
          // 累加1
          val newValue = Integer.valueOf(oldValue) + 1
          // 给连接串中的v2设置新的累加后的值
          StringUtils.setFieldInConcatString(v1, "\\|", v2, String.valueOf(newValue))   
        }
      }
      
    }
    
    // 创建Spark上下文
    val conf = new SparkConf()
        .setAppName("SessionAggrStatAccumulatorTest")  
        .setMaster("local")  
    val sc = new SparkContext(conf);
        
    // 使用accumulator()()方法（curry），创建自定义的Accumulator
    val sessionAggrStatAccumulator = sc.accumulator("")(SessionAggrStatAccumulator)   
    
    // 模拟使用一把自定义的Accumulator
    val arr = Array(Constants.TIME_PERIOD_1s_3s, Constants.TIME_PERIOD_4s_6s)  
    val rdd = sc.parallelize(arr, 1)  
    rdd.foreach { sessionAggrStatAccumulator.add(_) }  
    
    println(sessionAggrStatAccumulator.value)  
  }
  
}