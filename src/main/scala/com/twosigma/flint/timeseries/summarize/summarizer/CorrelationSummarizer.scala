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

import com.twosigma.flint.rdd.function.summarize.summarizer.{ CorrelationOutput, CorrelationState, MultiCorrelationOutput, MultiCorrelationState, CorrelationSummarizer => CorrelationSum, MultiCorrelationSummarizer => MultiCorrelationSum }
import com.twosigma.flint.timeseries.Schema
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.types._

case class CorrelationSummarizerFactory(columnX: String, columnY: String) extends SummarizerFactory {
  override def apply(inputSchema: StructType): CorrelationSummarizer =
    CorrelationSummarizer(inputSchema, alias, columnX, columnY)
}

case class CorrelationSummarizer(
  override val inputSchema: StructType,
  override val alias: Option[String],
  columnX: String,
  columnY: String
) extends Summarizer {
  private val columnXIndex = inputSchema.fieldIndex(columnX)
  private val columnYIndex = inputSchema.fieldIndex(columnY)
  private val xToDouble = anyToDouble(inputSchema(columnXIndex).dataType)
  private val yToDouble = anyToDouble(inputSchema(columnYIndex).dataType)
  private val columnPrefix = s"${columnX}_${columnY}"
  override type T = (Double, Double)
  override type U = CorrelationState
  override type V = CorrelationOutput

  override val summarizer = CorrelationSum()

  override val schema = Schema.of(
    s"${columnPrefix}_correlation" -> DoubleType,
    s"${columnPrefix}_correlationTStat" -> DoubleType
  )

  override def toT(r: GenericInternalRow): (Double, Double) =
    (
      xToDouble(r.get(columnXIndex, inputSchema(columnXIndex).dataType)),
      yToDouble(r.get(columnYIndex, inputSchema(columnYIndex).dataType))
    )

  override def fromV(v: V): GenericInternalRow = new GenericInternalRow(Array[Any](v.correlation, v.tStat))
}

/**
 * The factory for [[MultiCorrelationSummarizer]].
 *
 * If `others` is empty, it gives a summarizer to compute all correlations for all possible pairs in `columns`.
 * Otherwise, it gives a summarizer to compute correlations between the pairs of columns where the left is one
 * of `columns` and the right is one of `others`.
 *
 * @param columns Array of column names.
 * @param others  Option of an array of column names.
 */
case class MultiCorrelationSummarizerFactory(
  columns: Array[String],
  others: Option[Array[String]]
) extends SummarizerFactory {
  override def apply(inputSchema: StructType): MultiCorrelationSummarizer = others.fold {
    val cols = columns.length
    require(columns.nonEmpty, "columns must be non-empty.")
    val pairIndexes = for (i <- 0 until cols; j <- (i + 1) until cols) yield (i, j)
    MultiCorrelationSummarizer(inputSchema, alias, columns, pairIndexes)
  } {
    case otherColumns =>
      val duplicatedColumns = columns.toSet intersect otherColumns.toSet
      require(columns.nonEmpty && otherColumns.nonEmpty, "columns and others must be non-empty.")
      require(duplicatedColumns.isEmpty, s"otherColumns has some duplicated columns ${duplicatedColumns.mkString(",")}")
      val cols = columns.length + otherColumns.length
      val pairIndexes = for (i <- columns.indices; j <- columns.length until cols) yield (i, j)
      MultiCorrelationSummarizer(inputSchema, alias, columns ++ otherColumns, pairIndexes)
  }
}

/**
 * A summarizer to compute correlations for pairs of columns in `columns` where the pairs are specified by `pairIndexes`.
 */
case class MultiCorrelationSummarizer(
  override val inputSchema: StructType,
  override val alias: Option[String],
  columns: Array[String],
  pairIndexes: IndexedSeq[(Int, Int)]
) extends Summarizer {
  private val k = columns.length
  private val columnIndexes = columns.map(inputSchema.fieldIndex)
  private val toDoubleFns = columnIndexes.map { case id => id -> anyToDouble(inputSchema(id).dataType) }.toMap
  override type T = Array[Double]
  override type U = MultiCorrelationState
  override type V = MultiCorrelationOutput
  override val summarizer = MultiCorrelationSum(k, pairIndexes)

  override val schema = Schema.of(pairIndexes.flatMap {
    case (i, j) =>
      Seq(
        s"${columns(i)}_${columns(j)}_correlation" -> DoubleType,
        s"${columns(i)}_${columns(j)}_correlationTStat" -> DoubleType
      )
  }: _*)

  override def toT(r: GenericInternalRow): Array[Double] = columnIndexes.map {
    case id => toDoubleFns(id)(r.get(id, inputSchema(id).dataType))
  }

  override def fromV(v: V): GenericInternalRow = new GenericInternalRow(
    v.outputs.flatMap { case (i, j, o) => Seq(o.correlation, o.tStat) }.toArray[Any]
  )
}

