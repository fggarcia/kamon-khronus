/*
 * =========================================================================================
 * Copyright © 2013-2016 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.khronus

import com.despegar.khronus.jclient.KhronusClient
import com.typesafe.config.Config
import kamon.metric.{Bucket, MetricDistribution, MetricValue, TickSnapshot}
import kamon.{Kamon, MetricReporter}
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

class KhronusReporter extends MetricReporter {
  private val logger = LoggerFactory.getLogger(classOf[KhronusReporter])
  private var khronusClientOpt: Option[KhronusClient] = None

  logger.info("Starting the Kamon(Khronus) module")

  private val SPAN_PROCESSING = "span.processing-time"
  private val OPERATION = "operation"

  override def reportTickSnapshot(snapshot: TickSnapshot): Unit = {
    pushCounter(snapshot.metrics.counters)
    pushGauge(snapshot.metrics.gauges)
    pushHistogram(snapshot.metrics.histograms ++ snapshot.metrics.minMaxCounters)
  }

  def pushHistogram(histograms: Seq[MetricDistribution]): Unit = {
    for {
      kc <- khronusClientOpt
      metric <- histograms
      bucket <- metric.distribution.bucketsIterator
    } {
      kc.recordTime(getDistributionMetricName(metric), bucket.value)
    }
  }

  def pushCounter(counters: Seq[MetricValue]): Unit = {
    for {
      kc <- khronusClientOpt
      counter <- counters
    } {
      kc.incrementCounter(counter.name, counter.value)
    }
  }

  def pushGauge(gauges : Seq[MetricValue]): Unit = {
    for {
      kc <- khronusClientOpt
      gauge <- gauges
    } {
      kc.recordGauge(gauge.name, gauge.value)
    }
  }

  override def start(): Unit = {
    configureClient(Kamon.config())
  }

  override def stop(): Unit = {}

  override def reconfigure(config: Config): Unit = {
    configureClient(config)
  }

  protected def configureClient(config: Config): Unit = {
    val khronusConfig = config.getConfig("kamon.khronus")

    val kc = for {
      config ← Try(khronusConfig)
      host ← Try(config.getString("host"))
      appName ← Try(config.getString("app-name"))
      interval ← Try(config.getLong("interval"))
      measures ← Try(config.getInt("max-measures"))
      endpoint ← Try(config.getString("endpoint"))
      kc ← Try(new KhronusClient.Builder()
        .withApplicationName(appName)
        .withSendIntervalMillis(interval)
        .withMaximumMeasures(measures)
        .withHosts(host)
        .withEndpoint(endpoint)
        .build)
    } yield kc

    kc match {
      case Success(client) =>
        khronusClientOpt = Some(client)
      case Failure(ex) =>
        logger.error(s"Khronus metrics reporting inoperative: {}", ex)
        khronusClientOpt = None
    }
  }

  def getDistributionMetricName(metric: MetricDistribution): String = {
    if (metric.name.equalsIgnoreCase(SPAN_PROCESSING))
      metric.tags.getOrElse(OPERATION, "")
    else
      metric.name
  }
}
