/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.querio.ingest.util

import scala.collection.mutable.ListBuffer

import java.util.Properties

import com.querio.ingest.api._
import com.querio.ingest.service._

import blueeyes.concurrent.Future

import blueeyes.json.JsonAST._

import blueeyes.core.http.MimeTypes._
import blueeyes.core.data.BijectionsChunkJson._
import blueeyes.core.http.HttpResponse
import blueeyes.core.service.HttpClient
import blueeyes.core.service.engines.HttpClientXLightWeb

abstract class IngestProducer extends RealisticIngestMessage {
  
  def main(args: Array[String]) {
    val messages = if(args.length >= 1) args(0).toInt else 1000
    val delay = if(args.length >= 2) args(1).toInt else 100
    val threadCount = if(args.length >= 3) args(2).toInt else 1

    val start = System.nanoTime

    val threads = 0.until(threadCount).map(_ => new Thread() {
      override def run() {
        0.until(messages).foreach { i =>
          if(i % 10 == 0) println("Sending: " + i)
          send(genEvent.sample.get)
          if(delay > 0) {
            Thread.sleep(delay)
        }
      }
    }})

    threads.foreach(_.start)
    threads.foreach(_.join)

    println("Time: %.02f".format((System.nanoTime - start) / 1000000000.0))
  }

  def send(event: Event): Unit
}

object WebappIngestProducer extends IngestProducer {

  val base = "http://localhost:30050/vfs/"
  val client = new HttpClientXLightWeb 

  def send(event: Event) {
    val f: Future[HttpResponse[JValue]] = client.path(base)
                                                .query("tokenId", event.tokens(0))
                                                .contentType(application/json)
                                                .post[JValue](event.path)(event.content)
    while(!f.isDone) {}
    if(f.isCanceled) {
      println("Error tracking data: " + f.error)
    }
  }
  
}

object DirectIngestProducer extends IngestProducer {
 
  val testTopic = "test-topic-0"
  lazy val store = kafkaStore(testTopic)

  def send(event: Event) {
    store.save(genEvent.sample.get)
  }

  def kafkaStore(topic: String): EventStore = {
    val props = new Properties()
    props.put("zk.connect", "127.0.0.1:2181")
    props.put("serializer.class", "com.querio.ingest.api.IngestMessageCodec")
    
    val messageSenderMap = Map() + (MailboxAddress(0L) -> new KafkaMessageSender(topic, props))
    
    val defaultAddresses = List(MailboxAddress(0))

   

    val qz = QuerioZookeeper.testQuerioZookeeper("127.0.0.1:2181")
    val producerId = qz.acquireProducerId
    qz.close
    new DefaultEventStore(producerId,
                          new ConstantEventRouter(defaultAddresses),
                          new MappedMessageSenders(messageSenderMap))
  }
}
