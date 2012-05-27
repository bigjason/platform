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
package com.precog
package pandora

import common.VectorCase
import common.kafka._
import common.security._

import daze._
import daze.memoization._
import daze.util._

import pandora._

import quirrel._
import quirrel.emitter._
import quirrel.parser._
import quirrel.typer._

import yggdrasil._
import yggdrasil.actor._
import yggdrasil.metadata._
import yggdrasil.serialization._
import muspelheim._

import com.precog.util.FilesystemFileOps

import org.specs2.mutable._
  
import akka.dispatch.Await
import akka.util.Duration

import java.io.File

import scalaz._
import scalaz.effect.IO

import org.streum.configrity.Configuration
import org.streum.configrity.io.BlockFormat

import akka.actor.ActorSystem
import akka.dispatch.ExecutionContext

class PlatformSpecs extends ParseEvalStackSpecs { platformSpecs =>
  trait Storage extends StandaloneActorEcosystem[IterableDataset] with ActorYggShard[IterableDataset] with LevelDBProjectionsActorModule {
    type YggConfig = platformSpecs.YggConfig
    lazy val yggConfig = platformSpecs.yggConfig
    val metadataStorage = new FileMetadataStorage(yggConfig.dataDir, new FilesystemFileOps {})
    lazy val accessControl = new UnlimitedAccessControl()(ExecutionContext.defaultExecutionContext(actorSystem))
  }
  
  object storage extends Storage

  override def startup() {
    // start storage shard 
    Await.result(storage.actorsStart, controlTimeout)
  }
  
  override def shutdown() {
    // stop storage shard
    Await.result(storage.actorsStop, controlTimeout)
    
    actorSystem.shutdown()
  }
}
