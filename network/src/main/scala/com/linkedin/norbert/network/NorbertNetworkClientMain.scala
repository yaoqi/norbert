/*
 * Copyright 2009-2010 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linkedin.norbert.network

import client.loadbalancer.LoadBalancerFactoryComponent
import client.NetworkClient
import netty.{NettyNetworkClient, NetworkClientConfig}
import org.jboss.netty.logging.{Log4JLoggerFactory, InternalLoggerFactory}
import com.linkedin.norbert.protos.NorbertProtos
import java.util.concurrent.{ExecutionException, TimeoutException, TimeUnit}
import com.linkedin.norbert.cluster.{ClusterClient, ClusterShutdownException, Node}

object NorbertNetworkClientMain {
  InternalLoggerFactory.setDefaultFactory(new Log4JLoggerFactory)

  def main(args: Array[String]) {
    val cc = ClusterClient("nimbus", "localhost:2181", 30000)
    cc.start

    val config = new NetworkClientConfig
    config.clusterClient = cc
    
    val nc = new NettyNetworkClient(config) with LoadBalancerFactoryComponent {
      val loadBalancerFactory = new LoadBalancerFactory {
        def newLoadBalancer(nodes: Seq[Node]) = new LoadBalancer {
          def nextNode = null
        }
      }
    }

    nc.start
    nc.registerRequest(NorbertProtos.Ping.getDefaultInstance, NorbertProtos.PingResponse.getDefaultInstance)

    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run = {
        nc.shutdown
      }
    })

    loop(nc, cc)
  }

  def loop(nc: NetworkClient, cc: ClusterClient) {
    print("> ")
    var line = Console.in.readLine.trim
    while (line != null) {
      try {
        if (line.length > 0) processCommand(nc, cc, line)
      } catch {
        case ex: ClusterShutdownException => throw ex
        case ex: Exception => println("Error: %s".format(ex))
      }

      print("> ")
      line = Console.in.readLine.trim
    }
  }

  def processCommand(nc: NetworkClient, cc: ClusterClient, line: String) {
    val command :: args = line.split(" ").toList.map(_.trim).filter(_.length > 0)

    command match {
      case "nodes" =>
        val nodes = cc.nodes
        if (nodes.length > 0) println(nodes.mkString("\n")) else println("The cluster has no nodes")

      case "join" =>
        if (args.length < 4) {
          println("Error: Invalid syntax: join nodeId url partition1 partition2...")
        } else {
          val nodeId :: url :: partitions = args
          cc.addNode(nodeId.toInt, url, partitions.map(_.toInt).toArray)
          println("Joined Norbert cluster")
        }

      case "leave" =>
        if (args.length < 1) {
          println("Invalid syntax: leave nodeId")
        } else {
          cc.removeNode(args.head.toInt)
          println("Left Norbert cluster")
        }

      case "ping" =>
        if (args.length < 1) {
          println("Invalid syntax: ping nodeId")
        } else {
          val node = cc.nodeWithId(args.head.toInt)
          node match {
            case Some(n) =>
              val future = nc.sendMessageToNode(NorbertProtos.Ping.newBuilder.setTimestamp(System.currentTimeMillis).build, n)
              try {
                val response = future.get(500, TimeUnit.MILLISECONDS).asInstanceOf[NorbertProtos.PingResponse]
                println("Ping took %dms".format(System.currentTimeMillis - response.getTimestamp))
              } catch {
                case ex: TimeoutException => println("Ping timed out")
                case ex: ExecutionException => println("Error: %s".format(ex.getCause))
              }

            case None => println("No node with id: %d".format(args.head.toInt))
          }
        }

      case "exit" => System.exit(0)

      case "quit" => System.exit(0)

      case msg => "Unknown command: " + msg

    }
  }
}
