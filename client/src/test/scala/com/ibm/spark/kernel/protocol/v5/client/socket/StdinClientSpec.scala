/*
 * Copyright 2015 IBM Corp.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.ibm.spark.kernel.protocol.v5.client.socket

import akka.actor.{ActorRef, Props, ActorSystem}
import akka.testkit.{TestProbe, ImplicitSender, TestKit}
import akka.zeromq.ZMQMessage
import com.ibm.spark.kernel.protocol.v5._
import com.ibm.spark.kernel.protocol.v5.client.socket.StdinClient.ResponseFunction
import com.ibm.spark.kernel.protocol.v5.content.{InputReply, InputRequest, ClearOutput, ExecuteRequest}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSpecLike, Matchers}
import com.ibm.spark.kernel.protocol.v5.client.Utilities._
import play.api.libs.json.Json
import scala.concurrent.duration._

import org.mockito.Mockito._
import org.mockito.Matchers._

class StdinClientSpec extends TestKit(ActorSystem("StdinActorSpec"))
  with ImplicitSender with FunSpecLike with Matchers with MockitoSugar
  with BeforeAndAfter
{
  private val TestReplyString = "some value"
  private val TestResponseFunc: ResponseFunction = (_, _) => TestReplyString

  private var mockSocketFactory: SocketFactory = _
  private var socketProbe: TestProbe = _
  private var stdinClient: ActorRef = _

  before {
    socketProbe = TestProbe()
    mockSocketFactory = mock[SocketFactory]
    doReturn(socketProbe.ref).when(mockSocketFactory)
      .StdinClient(any[ActorSystem], any[ActorRef])

    stdinClient = system.actorOf(Props(
      classOf[StdinClient], mockSocketFactory
    ))

    // Set the response function for our client socket
    stdinClient ! TestResponseFunc
  }

  describe("StdinClient") {
    describe("#receive") {
      it("should update the response function if receiving a new one") {
        val expected = "some other value"
        val replacementFunc: ResponseFunction = (_, _) => expected

        // Update the function
        stdinClient ! replacementFunc

        val inputRequestMessage: ZMQMessage = KMBuilder()
          .withHeader(InputRequest.toTypeString)
          .withContentString(InputRequest("", false))
          .build

        stdinClient ! inputRequestMessage

        socketProbe.expectMsgPF() {
          case zmqMessage: ZMQMessage =>
            val kernelMessage: KernelMessage = zmqMessage
            val inputReply =
              Json.parse(kernelMessage.contentString).as[InputReply]
            inputReply.value should be (expected)
        }
      }

      it("should do nothing if the incoming message is not an input_request") {
        val notInputRequestMessage: ZMQMessage = KMBuilder()
          .withHeader(ClearOutput.toTypeString)
          .build

        stdinClient ! notInputRequestMessage

        socketProbe.expectNoMsg(300.milliseconds)
      }

      it("should respond with an input_reply if the incoming message is " +
        "an input_request") {
        val inputRequestMessage: ZMQMessage = KMBuilder()
          .withHeader(InputRequest.toTypeString)
          .withContentString(InputRequest("", false))
          .build

        stdinClient ! inputRequestMessage

        socketProbe.expectMsgPF() {
          case zmqMessage: ZMQMessage =>
            val kernelMessage: KernelMessage = zmqMessage
            val messageType = kernelMessage.header.msg_type
            messageType should be (InputReply.toTypeString)
        }
      }

      it("should use the result from the response function if the incoming " +
        "message is an input_request") {
        val inputRequestMessage: ZMQMessage = KMBuilder()
          .withHeader(InputRequest.toTypeString)
          .withContentString(InputRequest("", false))
          .build

        stdinClient ! inputRequestMessage

        socketProbe.expectMsgPF() {
          case zmqMessage: ZMQMessage =>
            val kernelMessage: KernelMessage = zmqMessage
            val inputReply =
              Json.parse(kernelMessage.contentString).as[InputReply]
            inputReply.value should be (TestReplyString)
        }
      }
    }
  }
}