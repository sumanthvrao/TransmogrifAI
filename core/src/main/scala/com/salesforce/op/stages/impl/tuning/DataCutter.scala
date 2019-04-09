/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.stages.impl.tuning

import com.salesforce.op.UID
import com.salesforce.op.stages.impl.selector.ModelSelectorNames
import org.apache.spark.ml.attribute.NominalAttribute
import org.apache.spark.ml.param._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{Metadata, MetadataBuilder}
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import org.slf4j.LoggerFactory

import scala.util.{Success, Try}

case object DataCutter {

  /**
   * Creates instance that will split data into training and test set filtering out any labels that don't
   * meet the minimum fraction cutoff or fall in the top N labels specified
   *
   * @param seed                set for the random split
   * @param reserveTestFraction fraction of the data used for test
   * @param maxLabelCategories  maximum number of label categories to include
   * @param minLabelFraction    minimum fraction of total labels that a category must have to be included
   * @return data splitter
   */
  def apply(
    seed: Long = SplitterParamsDefault.seedDefault,
    reserveTestFraction: Double = SplitterParamsDefault.ReserveTestFractionDefault,
    maxLabelCategories: Int = SplitterParamsDefault.MaxLabelCategoriesDefault,
    minLabelFraction: Double = SplitterParamsDefault.MinLabelFractionDefault
  ): DataCutter = {
    new DataCutter()
      .setSeed(seed)
      .setReserveTestFraction(reserveTestFraction)
      .setMaxLabelCategories(maxLabelCategories)
      .setMinLabelFraction(minLabelFraction)
  }
}

/**
 * Instance that will make a holdout set and prepare the data for multiclass modeling
 * Creates instance that will split data into training and test set filtering out any labels that don't
 * meet the minimum fraction cutoff or fall in the top N labels specified.
 *
 * @param uid
 */
class DataCutter(uid: String = UID[DataCutter]) extends Splitter(uid = uid) with DataCutterParams {

  @transient private lazy val log = LoggerFactory.getLogger(this.getClass)

  /**
   * Function to set parameters before passing into the validation step
   * eg - do data balancing or dropping based on the labels
   *
   * @param data
   * @return Parameters set in examining data
   */
  override def preValidationPrepare(data: Dataset[Row], labelColNameOpt: Option[String]): Option[SplitterSummary] = {
    val labelColName = labelColNameOpt.getOrElse(data.columns(0))
    val labelColIdx = data.columns.indexOf(labelColName)

    if (Try(getLabelsToKeep).toOption.isEmpty) {
      val labelCounts = data.groupBy(labelColName).count().persist()
      val res = estimate(labelCounts)
      labelCounts.unpersist()
      setLabels(res.labelsKept.distinct, res.labelsDropped.distinct, res.labelsDroppedTotal)
    }

    val newSummary = summary
      .collect {
        case dcs: DataCutterSummary if dcs.preparedDF.isDefined => dcs
      }
      .getOrElse {
        val labelMetaArr = getLabelsFromMetadata(data)
        val labelSet = getLabelsToKeep.toSet

        // discount unseen label
        val integralLabelSet = 0.until(labelMetaArr.length - 1)
          .map(_.toDouble)
          .toSet

        val dataPrep = if (integralLabelSet == labelSet) {
          log.info("Label sets identical in dataset metadata and datacutter, skipping label trim")
          data
        } else {
          log.info(s"Dropping rows with columns not in $labelSet")

          // Update metadata that spark.ml Classifier is using tp determine the number of classes
          //
          val na = NominalAttribute.defaultAttr.withName(labelColName)
          val metadataNA = if (labelMetaArr.isEmpty) {
            na.withNumValues(labelSet.size)
          } else {
            na.withValues(labelMetaArr.take(labelSet.size))
          }

          // filter low cardinality labels out of the dataframe to reduce the volume and  to keep
          // it in sync with the new metadata.
          //
          data
            .filter(r => labelSet.contains(r.getDouble(labelColIdx)))
            .withColumn(labelColName, data
              .col(labelColName)
              .as("_", metadataNA.toMetadata))
        }

        DataCutterSummary(
          labelsKept = getLabelsToKeep,
          labelsDropped = getLabelsToDrop,
          labelsDroppedTotal = getLabelsDroppedTotal,
          preparedDF = Option(dataPrep)
        )
      }

    summary = Option(newSummary)
    summary
  }


  def getLabelsFromMetadata(data: DataFrame): Array[String] =
    Try {
      val labelSF = data.schema.head
      val labelColMetadata = labelSF.metadata
      log.info(s"Raw label column metadata: $labelColMetadata")
      labelColMetadata
        .getMetadata("ml_attr")
        .getStringArray("vals")
    }
    .recoverWith {
      case nonFatal =>
        log.warn("Recovering non-fatal exception", nonFatal)
        Success(Array.empty[String])
    }
    .getOrElse(Array.empty[String])


  /**
   * Removes labels that should not be used in modeling
   *
   * @param data first column must be the label as a double
   * @return Training set test set
   */
  override def validationPrepare(data: Dataset[Row], labelColNameOpt: Option[String]): Dataset[Row] = {
    super.validationPrepare(data)
    summary.flatMap(_.asInstanceOf[DataCutterSummary].preparedDF)
      .getOrElse(sys.error("No prepared dataframe available!"))
  }

  /**
   * Estimate the labels to keep and update metadata
   *
   * @param labelCounts
   * @return Set of labels to keep & to drop
   */
  private[op] def estimate(labelCounts: DataFrame): DataCutterSummary = {
    val numDroppedToRecord = 10

    val minLabelFract = getMinLabelFraction
    val maxLabels = getMaxLabelCategories

    val labelCol = labelCounts.columns(0)
    val countCol = labelCounts.columns(1)

    val numLabels = labelCounts.count()
    val totalValues = labelCounts.agg(sum(countCol)).first().getLong(0).toDouble

    val labelsKeptDF = labelCounts
      .filter(r => r.getLong(1).toDouble / totalValues >= minLabelFract)
      .sort(col(countCol).desc, col(labelCol))
      .limit(maxLabels)
      .persist()

    val labelsKept = labelsKeptDF.collect().map(_.getDouble(0))

    val labelsDroppedDF = labelCounts
      .except(labelsKeptDF)
      .sort(col(countCol).desc, col(labelCol))
      .limit(numDroppedToRecord)

    labelsKeptDF.unpersist()

    val labelsDropped = labelsDroppedDF.collect().map(_.getDouble(0))
    val labelsDroppedTotal = numLabels - labelsKept.length

    if (labelsKept.nonEmpty) {
      log.info(s"DataCutter is keeping labels: ${labelsKept.mkString}" +
        s" and dropping labels: ${labelsDropped.mkString}")
    } else {
      throw new RuntimeException(s"DataCutter dropped all labels with param settings:" +
        s" minLabelFraction = $minLabelFract, maxLabelCategories = $maxLabels. \n" +
        s"Label counts were: ${labelCounts.collect().toSeq}")
    }
    DataCutterSummary(labelsKept.toSeq, labelsDropped.toSeq, labelsDroppedTotal.toLong)
  }

  override def copy(extra: ParamMap): DataCutter = {
    val copy = new DataCutter(uid)
    copyValues(copy, extra)
  }
}

private[impl] trait DataCutterParams extends Params {

  final val maxLabelCategories = new IntParam(this, "maxLabelCategories",
    "maximum number of label categories for multiclass classification",
    ParamValidators.inRange(lowerBound = 1, upperBound = 1 << 30, lowerInclusive = false, upperInclusive = true)
  )
  setDefault(maxLabelCategories, SplitterParamsDefault.MaxLabelCategoriesDefault)

  def setMaxLabelCategories(value: Int): this.type = set(maxLabelCategories, value)

  def getMaxLabelCategories: Int = $(maxLabelCategories)

  final val minLabelFraction = new DoubleParam(this, "minLabelFraction",
    "minimum fraction of the data a label category must have", ParamValidators.inRange(
      lowerBound = 0.0, upperBound = 0.5, lowerInclusive = true, upperInclusive = false
    )
  )
  setDefault(minLabelFraction, SplitterParamsDefault.MinLabelFractionDefault)

  def setMinLabelFraction(value: Double): this.type = set(minLabelFraction, value)

  def getMinLabelFraction: Double = $(minLabelFraction)

  private[op] final val labelsToKeep = new DoubleArrayParam(this, "labelsToKeep",
    "labels to keep when applying the data cutter")

  private[op] def setLabels(keep: Seq[Double], dropTop10: Seq[Double], labelsDropped: Long): this.type = {
    set(labelsToKeep, keep.toArray)
      .set(labelsToDrop, dropTop10.toArray)
      .set(labelsDroppedTotal, labelsDropped)
  }

  private[op] def getLabelsToKeep: Array[Double] = $(labelsToKeep)

  private[op] final val labelsToDrop = new DoubleArrayParam(this, "labelsDropped",
    "the top of the labels to drop when applying the data cutter")

  private[op] def getLabelsToDrop: Array[Double] = $(labelsToDrop)

  private[op] final val labelsDroppedTotal = new LongParam(this, "labelsDroppedTotal",
    "the number of labels dropped")

  private[op] def getLabelsDroppedTotal: Long = $(labelsDroppedTotal)
}

/**
 * Summary of results for data cutter
 *
 * @param labelsKept    labels retained
 * @param labelsDropped labels dropped by data cutter
 */
case class DataCutterSummary
(
  labelsKept: Seq[Double],
  labelsDropped: Seq[Double],
  labelsDroppedTotal: Long,
  preparedDF: Option[DataFrame] = None
) extends SplitterSummary {

  /**
   * Converts to [[Metadata]]
   *
   * @param skipUnsupported skip unsupported values
   * @throws RuntimeException in case of unsupported value type
   * @return [[Metadata]] metadata
   */
  def toMetadata(skipUnsupported: Boolean): Metadata = {
    new MetadataBuilder()
      .putString(SplitterSummary.ClassName, this.getClass.getName)
      .putDoubleArray(ModelSelectorNames.LabelsKept, labelsKept.toArray)
      .putDoubleArray(ModelSelectorNames.LabelsDropped, labelsDropped.toArray)
      .putLong(ModelSelectorNames.LabelsDroppedTotal, labelsDroppedTotal)
      .build()
  }

}
