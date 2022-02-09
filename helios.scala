//> using scala "3.1.1"

//> using lib "dev.zio::zio:1.0.13"
//> using lib "dev.zio::zio-interop-cats:3.2.9.1"
//> using lib "dev.zio::zio-json:0.2.0-M3"
//> using lib "org.http4s::http4s-dsl:0.23.10"
//> using lib "org.http4s::http4s-blaze-client:0.23.10"
//> using lib "org.slf4j:slf4j-simple:1.7.35"

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.TimeoutException
import javax.net.ssl.*
import scala.collection.mutable.ArrayBuffer
import cats.effect.Concurrent
import org.http4s.*
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.client.Client
import org.http4s.client.dsl.*
import org.http4s.dsl.io.*
import org.http4s.headers.*
import org.http4s.implicits.*
import org.typelevel.ci.*
import zio.*
import zio.blocking.*
import zio.clock.*
import zio.console.*
import zio.duration.*
import zio.interop.catz.*
import zio.json.*
import zio.stream.*
import zio.stream.interop.fs2z.*
import zio.system.*

// Now:
// Function to calculate desired colour temp for a given time of day
// Function to re-calculate desired colour temp for current time of day every N minutes
//
// Resources:
// https://developers.meethue.com/develop/hue-api-v2/core-concepts/
// https://developers.meethue.com/develop/hue-api-v2/api-reference/#resource_light_get

object Helios extends App with Http4sClientDsl[Task]:

  final case class BridgeApiBaseUri(value: Uri)

  final case class BridgeApiKey(value: String)

  sealed abstract class ServerSentEvent
  object ServerSentEvent:

    final case class Comment(text: String) extends ServerSentEvent

    final case class Event(
        data: String,
        `type`: Option[String],
        id: Option[String],
        retry: Option[Int]
    ) extends ServerSentEvent

    // From https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream:
    // comment       = colon *any-char end-of-line
    final val CommentText = """:(.*)""".r
    // From https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream:
    // field         = 1*name-char [ colon [ space ] *any-char ] end-of-line
    final val FieldKeyAndValue = """([^:]+): ?(.*)""".r
    final val DataKey = "data"
    final val EventKey = "event"
    final val IdKey = "id"
    final val RetryKey = "retry"

    object RetryValue:
      def unapply(s: String): Option[Int] =
        try Some(s.toInt)
        catch case _: NumberFormatException => None

    final case class Buffers(
        data: Option[String] = None,
        `type`: Option[String] = None,
        id: Option[String] = None,
        retry: Option[Int] = None
    )

    val decode: Transducer[Nothing, Byte, ServerSentEvent] =
      ZTransducer.utf8Decode >>> ZTransducer.splitLines >>> ZTransducer[
        Any,
        Nothing,
        String,
        ServerSentEvent
      ](
        ZRef
          .makeManaged(Buffers())
          .map(stateRef => {
            case None =>
              stateRef.modify { buffers =>
                (
                  buffers.data match
                    case None => Chunk.empty
                    case Some(data) =>
                      Chunk(
                        ServerSentEvent.Event(
                          data,
                          buffers.`type`,
                          buffers.id,
                          buffers.retry
                        )
                      )
                  ,
                  buffers.copy(data = None, `type` = None)
                )
              }

            case Some(lines) =>
              stateRef.modify { buffers =>
                val dispatch = ArrayBuffer[ServerSentEvent]()
                var updatedBuffers = buffers
                // Per https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation
                lines.foreach {
                  case "" =>
                    updatedBuffers.data match
                      case None =>
                        updatedBuffers = updatedBuffers.copy(`type` = None)
                      case Some(data) =>
                        dispatch += ServerSentEvent.Event(
                          if (data.endsWith("\n"))
                            data.substring(0, data.length - 1)
                          else
                            data,
                          updatedBuffers.`type`,
                          updatedBuffers.id,
                          updatedBuffers.retry
                        )
                        updatedBuffers =
                          updatedBuffers.copy(data = None, `type` = None)

                  case CommentText(text) =>
                    dispatch += ServerSentEvent.Comment(text)

                  case FieldKeyAndValue(DataKey, data) if data.nonEmpty =>
                    updatedBuffers = updatedBuffers
                      .copy(data =
                        Some(updatedBuffers.data.getOrElse("") + data + "\n")
                      )

                  case FieldKeyAndValue(EventKey, t) if t.nonEmpty =>
                    updatedBuffers = updatedBuffers.copy(`type` = Some(t))

                  case FieldKeyAndValue(IdKey, id) =>
                    updatedBuffers = updatedBuffers.copy(id = Some(id))

                  case FieldKeyAndValue(RetryKey, RetryValue(retry)) =>
                    updatedBuffers = updatedBuffers.copy(retry = Some(retry))

                  case _ =>
                    ()
                }
                (
                  Chunk.fromArray(dispatch.toArray),
                  updatedBuffers
                )
              }
          })
      )

  final case class Metadata(name: String)
  object Metadata:
    implicit val codec: JsonCodec[Metadata] = DeriveJsonCodec.gen[Metadata]

  final case class On(on: Boolean)
  object On:
    implicit val codec: JsonCodec[On] = DeriveJsonCodec.gen[On]

  final case class ColorTemperature(mirek: Option[Int])
  object ColorTemperature:
    implicit val codec: JsonCodec[ColorTemperature] =
      DeriveJsonCodec.gen[ColorTemperature]

  final case class Light(
      id: String,
      metadata: Metadata,
      on: On,
      @jsonField("color_temperature") colorTemperature: Option[ColorTemperature]
  )
  object Light:
    implicit val codec: JsonCodec[Light] = DeriveJsonCodec.gen[Light]

  final case class ResourceIdentifierPut(rid: String, rtype: String)
  object ResourceIdentifierPut:
    implicit val decoder: JsonDecoder[ResourceIdentifierPut] =
      DeriveJsonDecoder.gen[ResourceIdentifierPut]

  final case class Error(description: String)
  object Error:
    implicit val decoder: JsonDecoder[Error] = DeriveJsonDecoder.gen[Error]

  @jsonDiscriminator("type") sealed abstract class Event
  object Event:
    @jsonHint("update") final case class Update(
        id: String,
        creationtime: Instant,
        data: List[Update.Data]
    ) extends Event
    object Update:
      @jsonDiscriminator("type") sealed abstract class Data
      object Data:
        implicit val decoder: JsonDecoder[Data] = DeriveJsonDecoder.gen[Data]
        @jsonHint("light") final case class Light(
            id: String,
            on: Option[On],
            @jsonField("color_temperature") colorTemperature: Option[
              ColorTemperature
            ]
        ) extends Data
        @jsonHint("room") final case class Room(id: String) extends Data
        @jsonHint("zone") final case class Zone(id: String) extends Data
        @jsonHint("bridge_home") final case class BridgeHome(id: String)
            extends Data
        @jsonHint("grouped_light") final case class GroupedLight(id: String)
            extends Data
        @jsonHint("device") final case class Device(id: String) extends Data
        @jsonHint("bridge") final case class Bridge(id: String) extends Data
        @jsonHint("device_power") final case class DevicePower(id: String)
            extends Data
        @jsonHint("zigbee_connectivity") final case class ZigbeeConnectivity(
            id: String
        ) extends Data
        @jsonHint("zgp_connectivity") final case class ZgpConnectivity(
            id: String
        ) extends Data
        @jsonHint("motion") final case class Motion(id: String) extends Data
        @jsonHint("temperature") final case class Temperature(id: String)
            extends Data
        @jsonHint("light_level") final case class LightLevel(id: String)
            extends Data
        @jsonHint("button") final case class Button(id: String) extends Data
        @jsonHint("behavior_script") final case class BehaviorScript(id: String)
            extends Data
        @jsonHint("behavior_instance") final case class BehaviorInstance(
            id: String
        ) extends Data
        @jsonHint("geofence_client") final case class GeofenceClient(id: String)
            extends Data
        @jsonHint("geolocation") final case class Geolocation(id: String)
            extends Data
        @jsonHint(
          "entertainment_configuration"
        ) final case class EntertainmentConfiguration(id: String) extends Data

    // TODO: Read data
    @jsonHint("add") final case class Add(id: String, creationtime: Instant)
        extends Event

    // TODO: Read data
    @jsonHint("delete") final case class Delete(
        id: String,
        creationtime: Instant
    ) extends Event

    // TODO: Read data
    @jsonHint("error") final case class Error(id: String, creationtime: Instant)
        extends Event

    implicit val decoder: JsonDecoder[Event] = DeriveJsonDecoder.gen[Event]

  def events(
      // TODO: Use
      lastEventId: Option[String] = None
  ): ZStream[Clock with Blocking with Has[BridgeApiBaseUri] with Has[
    BridgeApiKey
  ] with Has[
    Client[Task]
  ], Throwable, Event] =
    (for
      bridgeApiBaseUri <- ZStream.service[BridgeApiBaseUri]
      bridgeApiKey <- ZStream.service[BridgeApiKey]
      client <- ZStream.service[Client[Task]]
      request = GET(
        bridgeApiBaseUri.value / "eventstream" / "clip" / "v2",
        Header.Raw(ci"hue-application-key", bridgeApiKey.value),
        Accept(MediaType.`text/event-stream`)
      )
      serverSentEvents <- client
        .stream(request)
        .flatMap(_.body)
        .toZStream()
        .transduce(ServerSentEvent.decode)
        .flatMap {
          // TODO: Stash last ID and retry value to use after timeout
          case ServerSentEvent.Event(data, t, id, retry) =>
            ZStream.fromIterableM(
              Task.fromEither(
                data
                  .fromJson[List[Event]]
                  .left
                  .map(MalformedMessageBodyFailure(_, cause = None))
              )
            )
          case _ =>
            ZStream.empty
        }
    yield serverSentEvents).catchSome { case _: TimeoutException =>
      events()
    }

  implicit def jsonOf[F[_]: Concurrent, A: JsonDecoder]: EntityDecoder[F, A] =
    EntityDecoder.decodeBy[F, A](MediaType.application.json)(media =>
      EntityDecoder
        .collectBinary(media)
        .subflatMap(chunk =>
          val string = String(chunk.toArray, StandardCharsets.UTF_8)
          if (string.nonEmpty)
            string.fromJson.left
              .map(MalformedMessageBodyFailure(_, cause = None))
          else
            Left(MalformedMessageBodyFailure("Invalid JSON: empty body"))
        )
    )

  implicit def jsonEncoderOf[F[_], A: JsonEncoder]: EntityEncoder[F, A] =
    EntityEncoder
      .stringEncoder[F]
      .contramap[A](_.toJson)
      .withContentType(`Content-Type`(MediaType.application.json))

  final case class GetLightsResponse(errors: List[Error], data: List[Light])
  object GetLightsResponse:
    implicit val decoder: JsonDecoder[GetLightsResponse] =
      DeriveJsonDecoder.gen[GetLightsResponse]

  def getLights: RIO[Has[BridgeApiBaseUri] with Has[BridgeApiKey] with Has[
    Client[Task]
  ], GetLightsResponse] =
    for
      bridgeApiBaseUri <- RIO.service[BridgeApiBaseUri]
      bridgeApiKey <- RIO.service[BridgeApiKey]
      client <- RIO.service[Client[Task]]
      request = GET(
        bridgeApiBaseUri.value / "clip" / "v2" / "resource" / "light",
        Header.Raw(ci"hue-application-key", bridgeApiKey.value)
      )
      response <- client.expect[GetLightsResponse](request)
    yield response

  final case class PutLightResponse(
      errors: List[Error],
      data: List[ResourceIdentifierPut]
  )
  object PutLightResponse:
    implicit val decoder: JsonDecoder[PutLightResponse] =
      DeriveJsonDecoder.gen[PutLightResponse]

  def putLight(
      light: Light
  ): RIO[Has[BridgeApiBaseUri] with Has[BridgeApiKey] with Has[
    Client[Task]
  ], PutLightResponse] =
    for
      bridgeApiBaseUri <- RIO.service[BridgeApiBaseUri]
      bridgeApiKey <- RIO.service[BridgeApiKey]
      client <- RIO.service[Client[Task]]
      request = PUT(
        light,
        bridgeApiBaseUri.value / "clip" / "v2" / "resource" / "light" / light.id,
        Header.Raw(ci"hue-application-key", bridgeApiKey.value)
      )
      response <- client.expect[PutLightResponse](request)
    yield response

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    program.orDie
      .provideCustomLayer(
        bridgeApiBaseUriLayer ++ bridgeApiKeyLayer ++ clientLayer
      )
      .exitCode

  val program: RIO[Blocking with Clock with Console with Has[
    BridgeApiBaseUri
  ] with Has[
    BridgeApiKey
  ] with Has[Client[Task]], Unit] = for
    lightsRef <- ZRef.make(Map.empty[String, Light])
    getLightsResponse <- getLights
    _ <- lightsRef
      .updateAndGet(
        _ ++ getLightsResponse.data.map(light => (light.id, light))
      )
      .flatMap(printState)
    // TODO
    _ <- lightsRef.get.flatMap(updateActiveLights)
    _ <- events().foreach {
      case Event.Update(_, _, data) =>
        RIO.foreach(data) {
          case Event.Update.Data.Light(id, on, colorTemperature) =>
            lightsRef
              .updateAndGet(lights =>
                lights.get(id) match
                  case None =>
                    // If we're receiving an update for a light we don't have a
                    // record of that's fine - it's a rare possibility but can
                    // happen during startup before we've initilised.
                    lights

                  case Some(light) =>
                    lights.updated(
                      id,
                      light.copy(
                        on = on.getOrElse(light.on),
                        colorTemperature =
                          colorTemperature.orElse(light.colorTemperature)
                      )
                    )
              )
              .flatMap(printState)

          case _ =>
            UIO.unit
        }

      case _: Event.Add =>
        // TODO
        UIO.unit

      case _: Event.Delete =>
        // TODO
        UIO.unit

      case _: Event.Error =>
        // TODO
        UIO.unit
    }
  yield ()

  def updateActiveLights(
      lights: Map[String, Light]
  ): RIO[Blocking with Clock with Has[
    BridgeApiBaseUri
  ] with Has[
    BridgeApiKey
  ] with Has[Client[Task]], Unit] =
    RIO
      .foreach(lights.values.filter(_.on.on))(light =>
        putLight(
          light.copy(
            colorTemperature = Some(ColorTemperature(mirek = Some(230)))
          )
        )
      )
      .unit

  def printState(lights: Map[String, Light]): RIO[Console, Unit] =
    RIO.foreach(lights.values)(light => putStrLn(light.toJson)).unit

  val bridgeApiBaseUriLayer = env("BRIDGE_IP")
    .flatMap(IO.fromOption(_))
    .orElseFail("BRIDGE_IP must be set.")
    .map(bridgeIp =>
      BridgeApiBaseUri(
        Uri(
          scheme = Some(Uri.Scheme.https),
          authority = Some(Uri.Authority(host = Uri.RegName(bridgeIp)))
        )
      )
    )
    .toLayer

  val bridgeApiKeyLayer = env("BRIDGE_API_KEY")
    .flatMap(IO.fromOption(_))
    .orElseFail("BRIDGE_API_KEY must be set.")
    .map(BridgeApiKey(_))
    .toLayer

  val sslContext = SSLContext.getInstance("SSL")
  sslContext.init(
    null,
    Array[TrustManager](new X509TrustManager:
      override def getAcceptedIssuers()
          : Array[java.security.cert.X509Certificate] = null
      override def checkClientTrusted(
          chain: Array[X509Certificate],
          authType: String
      ): Unit = ()
      override def checkServerTrusted(
          chain: Array[X509Certificate],
          authType: String
      ): Unit = ()
    ),
    SecureRandom()
  )
  val clientLayer = RIO
    .runtime[Blocking with Clock]
    .toManaged_
    .flatMap(implicit runtime =>
      BlazeClientBuilder[Task]
        .withExecutionContext(runtime.platform.executor.asEC)
        .withSslContext(sslContext)
        .withCheckEndpointAuthentication(false)
        .withIdleTimeout(Duration.Infinity.asScala)
        .withRequestTimeout(Duration.Infinity.asScala)
        .resource
        .toManagedZIO
    )
    .toLayer
