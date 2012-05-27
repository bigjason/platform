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
package com.precog.yggdrasil
package actor

import metadata._

import akka.actor._
import akka.dispatch._
import akka.util._
import akka.util.duration._
import akka.pattern.ask
import akka.pattern.gracefulStop

import com.precog.util._
import com.precog.common._
//import com.precog.common.kafka._

import com.weiglewilczek.slf4s.Logging

import java.net.InetAddress

import blueeyes.json.JsonAST._

trait ActorEcosystem {
  def actorSystem: ActorSystem

  val ingestActor: ActorRef
  val ingestSupervisor: ActorRef
  val metadataActor: ActorRef
  val projectionsActor: ActorRef

  def actorsStart: Future[Unit]
  def actorsStop: Future[Unit]

  def status: Future[JArray]
}

trait ActorEcosystemConfig extends BaseConfig {
  def statusTimeout: Long = config[Long]("actors.status.timeout", 30000)
  def metadataTimeout: Timeout = config[Long]("actors.metadata.timeout", 30) seconds
  implicit def stopTimeout: Timeout = config[Long]("actors.stop.timeout", 300) seconds

  def metadataSyncPeriod: Duration = config[Int]("actors.metadata.sync_minutes", 5) minutes
  def batchStoreDelay: Duration    = config[Long]("actors.store.idle_millis", 1000) millis
  def batchShutdownCheckInterval: Duration = config[Int]("actors.store.shutdown_check_seconds", 1) seconds
}

trait BaseActorEcosystem[Dataset[_]] extends ActorEcosystem with ProjectionsActorModule[Dataset] with YggConfigComponent with Logging {
  type YggConfig <: ActorEcosystemConfig

  protected implicit lazy val executionContext =
    ExecutionContext.defaultExecutionContext(actorSystem)
  
  protected val logPrefix: String

  protected def actorsWithStatus: List[ActorRef]

  protected val shardId: String
  
  protected val checkpointCoordination: CheckpointCoordination

  protected val metadataStorage: MetadataStorage

  lazy val ingestSupervisor =
    actorSystem.actorOf(Props(new IngestSupervisor(ingestActor, projectionsActor, new SingleColumnProjectionRoutingTable,
                                                   yggConfig.batchStoreDelay, actorSystem.scheduler, yggConfig.batchShutdownCheckInterval)), "router")

  //
  // Public actors
  //
  
  lazy val metadataActor =
    actorSystem.actorOf(Props(new MetadataActor(shardId, metadataStorage, checkpointCoordination)), "metadata")
  
  lazy val projectionsActor =
    actorSystem.actorOf(Props(newProjectionsActor(metadataActor, yggConfig.metadataTimeout)), "projections")

  def actorsStart = Future[Unit] {
    // TODO: reconsider?
    logger.info("Starting actor ecosystem")
  }

  def status: Future[JArray] = {
    implicit val to = Timeout(yggConfig.statusTimeout)

    for (statusResponses <- Future.sequence { actorsWithStatus map { actor => (actor ? Status).mapTo[JValue] } }) 
    yield JArray(statusResponses)
  }

  protected def actorStop(actor: ActorRef, name: String): Future[Unit] = { 
    for {
      _ <- Future(logger.debug(logPrefix + " Stopping " + name + " actor"))
      b <- gracefulStop(actor, yggConfig.stopTimeout.duration)(actorSystem) 
    } yield {
      logger.debug(logPrefix + " Stop call for " + name + " actor returned " + b)  
    }   
  } recover { 
    case e => logger.error("Error stopping " + name + " actor", e)  
  }   

  def actorsStop: Future[Unit] = {
    import yggConfig.stopTimeout

    for {
      _  <- Future(logger.info(logPrefix + " Stopping"))
      _  <- actorStop(ingestSupervisor, "router")
      _  <- actorsStopInternal
      _  <- Future {
              logger.debug(logPrefix + " Stopping actor system")
              actorSystem.shutdown
              logger.info(logPrefix + " Stopped")
            } recover { 
              case e => logger.error(logPrefix + " Error stopping actor system", e)
            }
    } yield ()
  }

  protected def actorsStopInternal: Future[Unit]
}


