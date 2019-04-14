/**
  * Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
  *
  * Licensed under the Apache License, Version 2.0 (the "License"). You may not
  * use this file except in compliance with the License. A copy of the License
  * is located at
  *
  *     http://aws.amazon.com/apache2.0/
  *
  * or in the "license" file accompanying this file. This file is distributed on
  * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
  * express or implied. See the License for the specific language governing
  * permissions and limitations under the License.
  *
  */

package com.amazon.deequ

import com.amazon.deequ.anomalydetection.RateOfChangeStrategy
import com.amazon.deequ.checks.{Check, CheckLevel, CheckStatus}
import com.amazon.deequ.metrics.{DoubleMetric, Entity}
import com.amazon.deequ.repository.InMemoryMetricsRepository
import com.amazon.deequ.repository.{MetricsRepository, ResultKey}
import com.amazon.deequ.runtime.spark.SparkDataset
import com.amazon.deequ.statistics._
import com.amazon.deequ.utils.CollectionUtils.SeqExtensions
import com.amazon.deequ.utils.{FixtureSupport, TempFileUtils}
import org.apache.spark.sql.DataFrame
import org.scalatest.{Matchers, WordSpec}

class VerificationSuiteTest extends WordSpec with Matchers with SparkContextSpec
  with FixtureSupport {

  "Verification Suite" should {

    "return the correct verification status regardless of the order of checks" in
      withSparkSession { sparkSession =>

        def assertStatusFor(data: DataFrame, checks: Check*)
                           (expectedStatus: CheckStatus.Value)
          : Unit = {

          val dataset = SparkDataset(data)

          val verificationSuiteStatus = VerificationSuite()
            .onData(dataset)
            .addChecks(checks)
            .run()
            .status

          assert(verificationSuiteStatus == expectedStatus)
        }

        val df = getDfCompleteAndInCompleteColumns(sparkSession)

        val checkToSucceed = Check(CheckLevel.Error, "group-1")
          .isComplete("att1")
          .hasCompleteness("att1", _ == 1.0)

        val checkToErrorOut = Check(CheckLevel.Error, "group-2-E")
          .hasCompleteness("att2", _ > 0.8)

        val checkToWarn = Check(CheckLevel.Warning, "group-2-W")
          .hasCompleteness("att2", _ > 0.8)


        assertStatusFor(df, checkToSucceed)(CheckStatus.Success)
        assertStatusFor(df, checkToErrorOut)(CheckStatus.Error)
        assertStatusFor(df, checkToWarn)(CheckStatus.Warning)


        Seq(checkToSucceed, checkToErrorOut).forEachOrder { checks =>
          assertStatusFor(df, checks: _*)(CheckStatus.Error)
        }

        Seq(checkToSucceed, checkToWarn).forEachOrder { checks =>
          assertStatusFor(df, checks: _*)(CheckStatus.Warning)
        }

        Seq(checkToWarn, checkToErrorOut).forEachOrder { checks =>
          assertStatusFor(df, checks: _*)(CheckStatus.Error)
        }

        Seq(checkToSucceed, checkToWarn, checkToErrorOut).forEachOrder { checks =>
          assertStatusFor(df, checks: _*)(CheckStatus.Error)
        }
      }

    "accept analysis config for mandatory analysis" in withSparkSession { sparkSession =>

      import sparkSession.implicits._
      val df = getDfFull(sparkSession)

      val result = {
        val checkToSucceed = Check(CheckLevel.Error, "group-1")
          .isComplete("att1") // 1.0
          .hasCompleteness("att1", _ == 1.0) // 1.0

        val analyzers = Size() :: // Analyzer that works on overall document
          Completeness("att2") ::
          Uniqueness(Seq("att2")) :: // Analyzer that works on single column
          MutualInformation(Seq("att1", "att2")) :: Nil // Analyzer that works on multi column

        VerificationSuite()
          .onData(SparkDataset(df))
          .addCheck(checkToSucceed)
          .addRequiredStatistics(analyzers)
          .run()
      }

      assert(result.status == CheckStatus.Success)

//      val analysisDf = AnalyzerContext.successMetricsAsDataFrame(sparkSession,
//        AnalyzerContext(result.metrics))
//
//      val expected = Seq(
//        ("Dataset", "*", "Size", 4.0),
//        ("Column", "att1", "Completeness", 1.0),
//        ("Column", "att2", "Completeness", 1.0),
//        ("Column", "att2", "Uniqueness", 0.25),
//        ("Mutlicolumn", "att1,att2", "MutualInformation",
//          -(0.75 * math.log(0.75) + 0.25 * math.log(0.25))))
//        .toDF("entity", "instance", "name", "value")
//
//
//      assertSameRows(analysisDf, expected)

    }

    "run the analysis even there are no constraints" in withSparkSession { sparkSession =>

      import sparkSession.implicits._
      val df = getDfFull(sparkSession)

      val result = VerificationSuite().onData(SparkDataset(df))
        .addRequiredStatistic(Size())
        .run()

      assert(result.status == CheckStatus.Success)

//          val analysisDf = AnalyzerContext.successMetricsAsDataFrame(sparkSession,
//              AnalyzerContext(result.metrics))
//
//          val expected = Seq(
//            ("Dataset", "*", "Size", 4.0)
//          ).toDF("entity", "instance", "name", "value")
//
//          assertSameRows(analysisDf, expected)

    }

//    "reuse existing results" in withMonitorableSparkSession { (sparkSession, sparkMonitor) =>
//
//        val df = getDfWithNumericValues(sparkSession)
//
//        val analyzerToTestReusingResults = Distinctness(Seq("att1", "att2"))
//
//        val verificationResult = VerificationSuite().onData(SparkDataset(df), SparkEngine(df.sparkSession))
//          .addRequiredAnalyzer(analyzerToTestReusingResults).run()
//        val analysisResult = ComputedStatistics(verificationResult.metrics)
//
//        val repository = new InMemoryMetricsRepository
//        val resultKey = ResultKey(0, Map.empty)
//        repository.save(resultKey, analysisResult)
//
//        val analyzers = analyzerToTestReusingResults :: Uniqueness(Seq("item", "att2")) :: Nil
//
//        val (separateResults, numSeparateJobs) = sparkMonitor.withMonitoringSession { stat =>
//
//
//          val results = analyzers.map { calculateSingle(_, df) }.toSet
//          (results, stat.jobCount)
//        }
//
//        val (runnerResults, numCombinedJobs) = sparkMonitor.withMonitoringSession { stat =>
//          val results = VerificationSuite().onData(SparkDataset(df), SparkEngine(df.sparkSession))
//            .useRepository(repository)
//            .reuseExistingResultsForKey(resultKey).addRequiredAnalyzers(analyzers).run()
//            .metrics.values.toSet
//
//          (results, stat.jobCount)
//        }
//
//        assert(numSeparateJobs == analyzers.length * 2)
//        assert(numCombinedJobs == 2)
//        assert(separateResults == runnerResults)
//    }

    "save results if specified" in
      withSparkSession { sparkSession =>

        val df = getDfWithNumericValues(sparkSession)

        val repository = new InMemoryMetricsRepository
        val resultKey = ResultKey(0, Map.empty)

        val analyzers = Size() :: Completeness("item") :: Nil

        val metrics = VerificationSuite().onData(SparkDataset(df))
          .useRepository(repository)
          .addRequiredStatistics(analyzers).saveOrAppendResult(resultKey).run().metrics

        val analyzerContext = ComputedStatistics(metrics)

        assert(analyzerContext == repository.loadByKey(resultKey).get)
      }

    "only append results to repository without unnecessarily overwriting existing ones" in
      withSparkSession { sparkSession =>

        val df = getDfWithNumericValues(sparkSession)

        val repository = new InMemoryMetricsRepository
        val resultKey = ResultKey(0, Map.empty)

        val analyzers = Size() :: Completeness("item") :: Nil

        val completeMetricResults = VerificationSuite().onData(SparkDataset(df))
          .useRepository(repository)
          .addRequiredStatistics(analyzers).saveOrAppendResult(resultKey).run().metrics

        val completeAnalyzerContext = ComputedStatistics(completeMetricResults)

        // Calculate and save results for first analyzer
        VerificationSuite().onData(SparkDataset(df)).useRepository(repository)
          .addRequiredStatistic(Size()).saveOrAppendResult(resultKey).run()

        // Calculate and append results for second analyzer
        VerificationSuite().onData(SparkDataset(df)).useRepository(repository)
          .addRequiredStatistic(Completeness("item"))
          .saveOrAppendResult(resultKey).run()

        assert(completeAnalyzerContext == repository.loadByKey(resultKey).get)
      }

    "if there are previous results in the repository new results should pre preferred in case of " +
      "conflicts" in withSparkSession { sparkSession =>

        val df = getDfWithNumericValues(sparkSession)

        val repository = new InMemoryMetricsRepository
        val resultKey = ResultKey(0, Map.empty)

        val analyzers = Size() :: Completeness("item") :: Nil

        val actualResult = VerificationSuite().onData(SparkDataset(df))
          .useRepository(repository)
          .addRequiredStatistics(analyzers).run()
        val expectedAnalyzerContextOnLoadByKey = ComputedStatistics(actualResult.metrics)

        val resultWhichShouldBeOverwritten = ComputedStatistics(Map(Size() -> DoubleMetric(
          Entity.Dataset, "", "", util.Try(100.0))))

        repository.save(resultKey, resultWhichShouldBeOverwritten)

        // This should overwrite the previous Size value
        VerificationSuite().onData(SparkDataset(df)).useRepository(repository)
          .addRequiredStatistics(analyzers).saveOrAppendResult(resultKey).run()

        assert(expectedAnalyzerContextOnLoadByKey == repository.loadByKey(resultKey).get)
    }

    "addAnomalyCheck should work" in withSparkSession { sparkSession =>
      evaluateWithRepositoryWithHistory { repository =>

        val df = getDfWithNRows(sparkSession, 11)
        val saveResultsWithKey = ResultKey(5, Map.empty)

        val analyzers = Completeness("item") :: Nil

        val verificationResult = VerificationSuite()
          .onData(SparkDataset(df))
          .useRepository(repository)
          .addRequiredStatistics(analyzers)
          .saveOrAppendResult(saveResultsWithKey)
          .addAnomalyCheck(
            RateOfChangeStrategy(Some(-2.0), Some(2.0)),
            Size(),
            Some(AnomalyCheckConfig(CheckLevel.Warning, "Anomaly check to fail"))
          )
          .addAnomalyCheck(
            RateOfChangeStrategy(Some(-7.0), Some(7.0)),
            Size(),
            Some(AnomalyCheckConfig(CheckLevel.Error, "Anomaly check to succeed",
              Map.empty, Some(0), Some(11)))
          )
          .addAnomalyCheck(
            RateOfChangeStrategy(Some(-7.0), Some(7.0)),
            Size()
          )
          .run()

        val checkResults = verificationResult.checkResults.toSeq

        assert(checkResults(0)._2.status == CheckStatus.Warning)
        assert(checkResults(1)._2.status == CheckStatus.Success)
        assert(checkResults(2)._2.status == CheckStatus.Success)
      }
    }

//    "write output files to specified locations" in withSparkSession { sparkSession =>
//
//      val df = getDfWithNumericValues(sparkSession)
//
//      val checkToSucceed = Check(CheckLevel.Error, "group-1")
//        .isComplete("att1") // 1.0
//        .hasCompleteness("att1", _ == 1.0) // 1.0
//
//      val tempDir = TempFileUtils.tempDir("verificationOuput")
//      val checkResultsPath = tempDir + "/check-result.json"
//      val successMetricsPath = tempDir + "/success-metrics.json"
//
//      VerificationSuite().onData(SparkDataset(df), SparkEngine(df.sparkSession))
//        .addCheck(checkToSucceed)
//        .saveCheckResultsJsonToPath(checkResultsPath)
//        .saveSuccessMetricsJsonToPath(successMetricsPath)
//        .run()
//
//      DfsUtils.readFromFileOnDfs(sparkSession, checkResultsPath) {
//        inputStream => assert(inputStream.read() > 0)
//      }
//      DfsUtils.readFromFileOnDfs(sparkSession, successMetricsPath) {
//        inputStream => assert(inputStream.read() > 0)
//      }
//    }

  }

   /** Run anomaly detection using a repository with some previous analysis results for testing */
  private[this] def evaluateWithRepositoryWithHistory(test: MetricsRepository => Unit): Unit = {

    val repository = new InMemoryMetricsRepository()

    (1 to 2).foreach { timeStamp =>
      val analyzerContext = ComputedStatistics(Map(
        Size() -> DoubleMetric(Entity.Column, "", "", util.Success(timeStamp))
      ))
      repository.save(ResultKey(timeStamp, Map("Region" -> "EU")), analyzerContext)
    }

    (3 to 4).foreach { timeStamp =>
      val analyzerContext = ComputedStatistics(Map(
        Size() -> DoubleMetric(Entity.Column, "", "", util.Success(timeStamp))
      ))
      repository.save(ResultKey(timeStamp, Map("Region" -> "NA")), analyzerContext)
    }
    test(repository)
  }

  private[this] def assertSameRows(dataframeA: DataFrame, dataframeB: DataFrame): Unit = {
    assert(dataframeA.collect().toSet == dataframeB.collect().toSet)
  }
}