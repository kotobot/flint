/*
 *  Copyright 2015-2016 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.twosigma.flint.timeseries.summarize.summarizer

import com.twosigma.flint.rdd.function.summarize.summarizer.{ ZScoreState, ZScoreSummarizer => ZSSummarizer }
import com.twosigma.flint.timeseries.Schema
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.types._

case class ZScoreSummarizerFactory(column: String, excludeCurrentObservation: Boolean) extends SummarizerFactory {
  override def apply(inputSchema: StructType): ZScoreSummarizer =
    ZScoreSummarizer(inputSchema, alias, column, excludeCurrentObservation)
}

case class ZScoreSummarizer(
  override val inputSchema: StructType,
  override val alias: Option[String],
  column: String,
  excludeCurrentObservation: Boolean
) extends Summarizer {
  private val columnIndex = inputSchema.fieldIndex(column)

  override type T = Double
  override type U = ZScoreState
  override type V = Double
  override val summarizer = ZSSummarizer(excludeCurrentObservation)
  override val schema = Schema.of(s"${column}_zScore" -> DoubleType)

  override def toT(r: GenericInternalRow): T = r.getDouble(columnIndex)

  override def fromV(v: V): GenericInternalRow = new GenericInternalRow(Array[Any](v))
}
