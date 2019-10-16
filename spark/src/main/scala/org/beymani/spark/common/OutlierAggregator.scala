/*
 * beymani-spark: Outlier and anamoly detection 
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */


package org.beymani.spark.common

import org.chombo.spark.common.JobConfiguration
import org.apache.spark.SparkContext
import scala.collection.JavaConverters._
import org.chombo.util.BasicUtils
import org.chombo.spark.common.Record
import org.chombo.util.BaseAttribute
import com.typesafe.config.Config
import org.chombo.spark.common.GeneralUtility
import org.beymani.util.DataStreamSchema
import scala.collection.mutable.ArrayBuffer

/**
* Aggregates outlier as per data stream hierarchy
* @author pranab
*
*/
object OutlierAggregator extends JobConfiguration with GeneralUtility {
   /**
    * @param args
    * @return
    */
   def main(args: Array[String])  {
	   val appName = "principalComponentPredictor"
	   val Array(inputPath: String, outputPath: String, configFile: String) = getCommandLineArgs(args, 3)
	   val config = createConfig(configFile)
	   val sparkConf = createSparkConf(appName, config, false)
	   val sparkCntxt = new SparkContext(sparkConf)
	   val appConfig = config.getConfig(appName)
	   
	   //configurations
	   val fieldDelimIn = getStringParamOrElse(appConfig, "field.delim.in", ",")
	   val fieldDelimOut = getStringParamOrElse(appConfig, "field.delim.out", ",")
	   val typeFieldOrd = getMandatoryIntParam( appConfig, "type.field.ordinal", "missing type field ordinal") 
	   val idFieldOrd = getMandatoryIntParam( appConfig, "id.field.ordinal", "missing type field ordinal") 
	   val seqFieldOrd = getMandatoryIntParam( appConfig, "seq.field.ordinal", "missing sequence field ordinal") 
	   val quantFieldOrd = getMandatoryIntParam( appConfig, "quant.field.ordinal", "missing quant field ordinal") 
	   val streamSchmeFilePath = getMandatoryStringParam( appConfig, "stream.schmaFilePath", "missing data stream schema file path") 
	   val precision = getIntParamOrElse(appConfig, "output.precision", 3)
	   val debugOn = appConfig.getBoolean("debug.on")
	   val saveOutput = appConfig.getBoolean("save.output")
	   
	   val streamSchema = DataStreamSchema.loadDataStreamSchema(streamSchmeFilePath)
	   
	   val data = sparkCntxt.textFile(inputPath)
	   val parentData = data.map(line => {
		   val items = BasicUtils.getTrimmedFields(line, fieldDelimIn)
		   val keyRec = Record(2)
		   keyRec.addString(items(typeFieldOrd))
		   keyRec.addLong(items(seqFieldOrd).toLong)
		   (keyRec, line)
	   }).groupByKey.flatMap(r => {
	     val values = r._2.toArray.map(v => BasicUtils.getTrimmedFields(v, fieldDelimIn))
	     val recLen = values(0).length
	     val id = values(0)(idFieldOrd)
	     val strType = r._1.getString(0)
	     val timeStamp = r._1.getLong(1)
	     val parentStream = streamSchema.findParent(strType, id)
	     val singleton = parentStream.isSingleton
	     
	     val parRecs = ArrayBuffer[String]()
	     if (singleton) {
	         //same parent all instances
	    	 val outliers = values.filter(v => v(recLen - 1).equals("O"))
		     val (avValue, avScore, tag) = 
		     if (outliers.length > 0) {
		       val avScore = getColumnAverage(outliers, recLen - 2)
		       val avValue = getColumnAverage(outliers, quantFieldOrd)
		       (avValue, avScore, "O")
		     } else {
		       val avScore = getColumnAverage(values, recLen - 2)
		       val avValue = getColumnAverage(values, quantFieldOrd)
		       (avValue, avScore, "N")
		     }
		     parRecs += formatOutput(parentStream.getType, parentStream.getId, timeStamp, avValue, 
		         avScore, tag, fieldDelimOut, precision)
	     } else {
	       values.groupBy(v => {
	         (v(0), v(1))
	       }).foreach(r => {
	         val key = r._1
	         val parentStream = streamSchema.findParent(key._1, key._2)
	         val values = r._2
	         val outliers = values.filter(v => v(recLen - 1).equals("O"))
	         val (avValue, avScore, tag) = 
	         if (outliers.length > 0) {
	           val avScore = getColumnAverage(outliers, recLen - 2)
		       val avValue = getColumnAverage(outliers, quantFieldOrd)
		       (avValue, avScore, "O")
	         } else {
		       val avScore = getColumnAverage(values, recLen - 2)
		       val avValue = getColumnAverage(values, quantFieldOrd)
		       (avValue, avScore, "N")
	         }
		     parRecs += formatOutput(parentStream.getType, parentStream.getId, timeStamp, avValue, 
		         avScore, tag, fieldDelimOut, precision)
	       })
	     }
	     parRecs
	   })

	  if (debugOn) {
	     parentData.collect.slice(0,50).foreach(s => println(s))
	  }
	   
	  if (saveOutput) {
	     parentData.saveAsTextFile(outputPath)
	  }
   }
   
  /**
  * @param fields
  * @param fieldOrdinals
  * @param rec
  * @param defKey
  */
  def formatOutput(recType:String, id:String, timeStamp:Long, value:Double, score:Double, tag:String, 
       fieldDelimOut:String, precision:Int) : String = {
     val strBld = new StringBuilder(recType)
	 strBld.append(fieldDelimOut).append(id).append(fieldDelimOut).append(timeStamp).
	   append(fieldDelimOut).append(BasicUtils.formatDouble(value, precision)).
	   append(fieldDelimOut).append(BasicUtils.formatDouble(score, precision)).
	   append(fieldDelimOut).append(tag)
     strBld.toString
   }

}