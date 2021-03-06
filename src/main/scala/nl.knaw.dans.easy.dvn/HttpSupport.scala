/**
 * Copyright (C) 2020 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.easy.dvn

import java.io.PrintStream
import java.net.URI
import java.nio.charset.StandardCharsets

import better.files.File
import com.google.gson.{ GsonBuilder, JsonParser }
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.io.FileUtils
import scalaj.http.{ Http, HttpResponse, MultiPart }

import scala.util.{ Failure, Success, Try }

trait HttpSupport extends DebugEnhancedLogging{
  protected val connectionTimeout: Int
  protected val readTimeout: Int
  protected val baseUrl: URI
  protected val apiToken: String
  protected val apiVersion: String
  private val gson = new GsonBuilder().setPrettyPrinting().create()

  protected def http(method: String, uri: URI, body: String = null, headers: Map[String, String] = Map.empty[String, String]): Try[HttpResponse[Array[Byte]]] = Try {
    {
      if (body == null) Http(uri.toASCIIString)
      else Http(uri.toASCIIString).postData(body)
      }.method(method)
      .headers(headers)
      .timeout(connTimeoutMs = connectionTimeout, readTimeoutMs = readTimeout)
      .asBytes
  }

  private def httpPostMulti(uri: URI, file: File, optJsonMetadata: Option[String] = None, headers: Map[String, String] = Map()): Try[HttpResponse[Array[Byte]]] = Try {
    val parts = MultiPart(data = file.byteArray, name = "file", filename = file.name, mime = "application/octet-stream") +:
      optJsonMetadata.map {
        json => List(MultiPart(data = json.getBytes(StandardCharsets.UTF_8), name = "jsonData", filename = "jsonData", mime = "application/json"))
      }.getOrElse(Nil)

      Http(uri.toASCIIString).postMulti(parts: _*)
      .timeout(connTimeoutMs = connectionTimeout, readTimeoutMs = readTimeout)
      .headers(headers)
      .asBytes
  }

  protected def postFile(subPath: String, file: File, optJsonMetadata: Option[String] = None)(expectedStatus: Int, formatResponseAsJson: Boolean = false)(implicit resultOutput: PrintStream): Try[String] = {
    for {
      uri <- uri(s"api/v${ apiVersion }/${ Option(subPath).getOrElse("") }")
      _ = debug(s"Request URL = $uri")
      response <- httpPostMulti(uri, file, optJsonMetadata, Map("X-Dataverse-key" -> apiToken))
      body <- handleResponse(response, expectedStatus)
      output <- if(formatResponseAsJson) prettyPrintJson(new String(body))
                else Try(new String(body))
      _ <- Try { resultOutput.print(output) }
    } yield s"Posted to URL: $uri"
  }

  /*
 * Helpers
 */
  protected def get(subPath: String = null, formatResponseAsJson: Boolean = true)(implicit resultOutput: PrintStream): Try[String] = {
    for {
      uri <- uri(s"api/v${ apiVersion }/${ Option(subPath).getOrElse("") }")
      _ = debug(s"Request URL = $uri")
      response <- http("GET", uri, body = null, Map("X-Dataverse-key" -> apiToken))
      body <- handleResponse(response, 200)
      output <- if(formatResponseAsJson) prettyPrintJson(new String(body))
                else Try(new String(body))
      _ <- Try { resultOutput.print(output) }
    } yield s"Retrieved URL: $uri"
  }

  protected def postJson(subPath: String = null)(expectedStatus: Int*)(body: String = null): Try[String] = {
    for {
      uri <- uri(s"api/v${ apiVersion }/${ Option(subPath).getOrElse("") }")
      _ = debug(s"Request URL = $uri")
      response <- http("POST", uri, body, Map("Content-Type" -> "application/json", "X-Dataverse-key" -> apiToken))
      _ <- handleResponse(response, expectedStatus: _*)
    } yield s"Successfully POSTed: $body"
  }

  protected def postText(subPath: String = null)(expectedStatus: Int*)(body: String = null): Try[String] = {
    for {
      uri <- uri(s"api/v${ apiVersion }/${ Option(subPath).getOrElse("") }")
      _ = debug(s"Request URL = $uri")
      response <- http("POST", uri, body, Map("Content-Type" -> "text/plain", "X-Dataverse-key" -> apiToken))
      _ <- handleResponse(response, expectedStatus: _*)
    } yield s"Successfully POSTed: $body"
  }

  protected def put(subPath: String = null)(body: String = null): Try[String] = {
    for {
      uri <- uri(s"api/v${ apiVersion }/${ Option(subPath).getOrElse("") }")
      _ = debug(s"Request URL = $uri")
      response <- http("PUT", uri, body, Map("X-Dataverse-key" -> apiToken))
      _ <- handleResponse(response, 200)
    } yield s"Successfully PUT to $uri"
  }

  protected def deletePath(subPath: String = null): Try[String] = {
    for {
      uri <- uri(s"api/v${ apiVersion }/${ Option(subPath).getOrElse("") }")
      _ = debug(s"Request URL = $uri")
      response <- http("DELETE", uri, null, Map("X-Dataverse-key" -> apiToken))
      _ <- handleResponse(response, 200)
    } yield s"Successfully DELETED: $uri"
  }

  protected def tryReadFileToString(file: File): Try[String] = Try {
    FileUtils.readFileToString(file.toJava, StandardCharsets.UTF_8)
  }

  protected def handleResponse(response: HttpResponse[Array[Byte]], expectedStatus: Int*): Try[Array[Byte]] = {
    trace(expectedStatus)
    if (!expectedStatus.contains(response.code)) Failure(CommandFailedException(response.code, response.statusLine, new String(response.body)))
    else Success(response.body)
  }

  protected def prettyPrintJson(json: String): Try[String] = Try {
    trace(())
    val parsedJson = JsonParser.parseString(json)
    gson.toJson(parsedJson)
  }

  def uri(s: String): Try[URI] = Try {
    baseUrl resolve s
  }
}
