package helios

import java.nio.charset.StandardCharsets
import java.time.Instant

import cats.effect.Concurrent
import fs2.io.net.tls.TLSContext
import nl.vroste.rezilience.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.client.dsl.*
import org.http4s.client.middleware.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.*
import org.typelevel.ci.*
import zio.*
import zio.interop.catz.*
import zio.json.*
import zio.stream.*
import zio.stream.interop.fs2z.*

object HueApi extends Http4sClientDsl[Task]:

  final case class BridgeApiBaseUri(value: Uri)
  final case class BridgeApiKey(value: String)

  final case class On(on: Boolean)
  object On:
    implicit val codec: JsonCodec[On] = DeriveJsonCodec.gen[On]

  final case class Dimming(brightness: Double)
  object Dimming:
    implicit val codec: JsonCodec[Dimming] = DeriveJsonCodec.gen[Dimming]

  final case class ColorTemperature(mirek: Option[Int])
  object ColorTemperature:
    implicit val codec: JsonCodec[ColorTemperature] =
      DeriveJsonCodec.gen[ColorTemperature]

  final case class GetResourceResponse[A <: Data](
      errors: List[Error],
      data: List[A]
  )
  object GetResourceResponse:
    implicit def codec[A <: Data: JsonCodec]
        : JsonCodec[GetResourceResponse[A]] =
      DeriveJsonCodec.gen[GetResourceResponse[A]]

  final case class PutResourceResponse(
      errors: List[Error],
      data: List[PutResourceResponse.ResourceIdentifierPut]
  )
  object PutResourceResponse:
    implicit val codec: JsonCodec[PutResourceResponse] =
      DeriveJsonCodec.gen[PutResourceResponse]

    final case class ResourceIdentifierPut(rid: String, rtype: String)
    object ResourceIdentifierPut:
      implicit val codec: JsonCodec[ResourceIdentifierPut] =
        DeriveJsonCodec.gen[ResourceIdentifierPut]

  final case class Errors(errors: List[Error])
  object Errors:
    implicit val codec: JsonCodec[Errors] = DeriveJsonCodec.gen[Errors]

  final case class Error(description: String)
  object Error:
    implicit val codec: JsonCodec[Error] = DeriveJsonCodec.gen[Error]

  @jsonDiscriminator("type") sealed abstract class Data
  object Data:
    implicit val codec: JsonCodec[Data] = DeriveJsonCodec.gen[Data]
    @jsonHint("light") final case class Light(
        id: String,
        on: Option[On],
        dimming: Option[Dimming],
        @jsonField("color_temperature") colorTemperature: Option[
          ColorTemperature
        ]
    ) extends Data
    object Light:
      implicit val codec: JsonCodec[Light] = DeriveJsonCodec.gen[Light]
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
    @jsonHint("zgp_connectivity") final case class ZgpConnectivity(id: String)
        extends Data
    @jsonHint("motion") final case class Motion(id: String) extends Data
    @jsonHint("temperature") final case class Temperature(id: String)
        extends Data
    @jsonHint("light_level") final case class LightLevel(id: String)
        extends Data
    @jsonHint("button") final case class Button(id: String) extends Data
    @jsonHint("behavior_script") final case class BehaviorScript(id: String)
        extends Data
    @jsonHint("behavior_instance") final case class BehaviorInstance(id: String)
        extends Data
    @jsonHint("geofence_client") final case class GeofenceClient(id: String)
        extends Data
    @jsonHint("geolocation") final case class Geolocation(id: String)
        extends Data
    @jsonHint(
      "entertainment_configuration"
    ) final case class EntertainmentConfiguration(id: String) extends Data

  @jsonDiscriminator("type") sealed abstract class Event
  object Event:
    implicit val codec: JsonCodec[Event] = DeriveJsonCodec.gen[Event]
    @jsonHint("update") final case class Update(
        id: String,
        creationtime: Instant,
        data: List[HueApi.Data]
    ) extends Event
    @jsonHint("add") final case class Add(
        id: String,
        creationtime: Instant,
        data: List[HueApi.Data]
    ) extends Event
    @jsonHint("delete") final case class Delete(
        id: String,
        creationtime: Instant,
        data: List[HueApi.Data]
    ) extends Event
    @jsonHint("error") final case class Error(
        id: String,
        creationtime: Instant,
        errors: List[HueApi.Error]
    ) extends Event

  // Per https://developers.meethue.com/develop/hue-api-v2/core-concepts/#events
  def events(
      eventId: Option[ServerSentEvent.EventId] = None,
      retry: Option[Duration] = None
  ): ZStream[
    BridgeApiBaseUri & BridgeApiKey & Client[Task],
    Throwable,
    Event
  ] =
    (for
      bridgeApiBaseUri <- ZStream.service[BridgeApiBaseUri]
      bridgeApiKey <- ZStream.service[BridgeApiKey]
      client <- ZStream.service[Client[Task]]
      request = GET(
        bridgeApiBaseUri.value / "eventstream" / "clip" / "v2",
        Header.Raw(ci"hue-application-key", bridgeApiKey.value),
        Accept(MediaType.`text/event-stream`)
      ).putHeaders(eventId.map(`Last-Event-Id`(_)))
      _ <- ZStream.fromZIO(retry.fold(ZIO.unit)(Clock.sleep(_)))
      eventIdRef <- ZStream.fromZIO(Ref.make(eventId))
      retryRef <- ZStream.fromZIO(Ref.make(retry))
      event <- client
        .stream(request)
        .flatMap(_.body)
        .through(ServerSentEvent.decoder)
        .toZStream()
        .tap(serverSentEvent =>
          for
            _ <- Console.printLine(s"read: ${serverSentEvent.renderString}")
            _ <- eventIdRef.set(serverSentEvent.id)
            _ <- retryRef.set(serverSentEvent.retry.map(Duration.fromScala))
          yield ()
        )
        .flatMap(_.data match
          case None =>
            ZStream.empty

          case Some(data) =>
            ZStream.fromIterableZIO(
              ZIO.fromEither(
                data
                  .fromJson[List[Event]]
                  .left
                  .map(MalformedMessageBodyFailure(_, cause = None))
              )
            )
        )
        .catchAll(error =>
          for
            _ <- ZStream
              .fromZIO(Console.printError(s"Stream error: ${error.getMessage}"))
            eventId <- ZStream.fromZIO(eventIdRef.get)
            retry <- ZStream.fromZIO(retryRef.get)
            event <- events(eventId, retry)
          yield event
        )
    yield event).tap(event =>
      Console.printLine(s"decoded event:\n${event.toJsonPretty}")
    )

  implicit def jsonOf[F[_]: Concurrent, A: JsonDecoder]: EntityDecoder[F, A] =
    EntityDecoder.decodeBy[F, A](MediaType.application.json)(media =>
      EntityDecoder
        .collectBinary(media)
        .subflatMap(chunk =>
          val string = String(chunk.toArray, StandardCharsets.UTF_8)
          if string.nonEmpty then
            string.fromJson.left
              .map(MalformedMessageBodyFailure(_, cause = None))
          else Left(MalformedMessageBodyFailure("Invalid JSON: empty body"))
        )
    )

  implicit def jsonEncoderOf[F[_], A: JsonEncoder]: EntityEncoder[F, A] =
    EntityEncoder
      .stringEncoder[F]
      .contramap[A](_.toJson)
      .withContentType(`Content-Type`(MediaType.application.json))

  // Per
  // https://developers.meethue.com/develop/hue-api-v2/api-reference/#resource_light_get
  def getLights: RIO[
    BridgeApiBaseUri & BridgeApiKey & Client[Task],
    GetResourceResponse[Data.Light]
  ] =
    (for
      bridgeApiBaseUri <- ZIO.service[BridgeApiBaseUri]
      bridgeApiKey <- ZIO.service[BridgeApiKey]
      client <- ZIO.service[Client[Task]]
      response <- client
        .run(
          GET(
            bridgeApiBaseUri.value / "clip" / "v2" / "resource" / "light",
            Header.Raw(ci"hue-application-key", bridgeApiKey.value)
          )
        )
        .use(readResponse[GetResourceResponse[Data.Light]])
    yield response).tap(response =>
      Console.printLine(s"decoded response:\n${response.toJsonPretty}")
    )

  // Per
  // https://developers.meethue.com/develop/hue-api-v2/api-reference/#resource_light_put
  def putLight(light: Data.Light): RIO[
    BridgeApiBaseUri & BridgeApiKey & RateLimiter & Client[Task],
    PutResourceResponse
  ] =
    (for
      bridgeApiBaseUri <- ZIO.service[BridgeApiBaseUri]
      bridgeApiKey <- ZIO.service[BridgeApiKey]
      rateLimiter <- ZIO.service[RateLimiter]
      client <- ZIO.service[Client[Task]]
      response <- rateLimiter(
        client
          .run(
            PUT(
              light,
              bridgeApiBaseUri.value / "clip" / "v2" / "resource" / "light" / light.id,
              Header.Raw(ci"hue-application-key", bridgeApiKey.value)
            )
          )
          .use(readResponse[PutResourceResponse])
      )
    yield response).tap(response =>
      Console.printLine(s"decoded response:\n${response.toJsonPretty}")
    )

  def readResponse[A: JsonDecoder](response: Response[Task]): Task[A] =
    if response.status.isSuccess then
      EntityDecoder[Task, A]
        .decode(response, strict = true)
        .value
        .absolve
    else
      EntityDecoder[Task, Errors]
        .decode(response, strict = true)
        .value
        .absolve
        .map(errors => RuntimeException(errors.toString))
        .merge
        .flip

  val clientLayer = ZLayer(
    for
      // For bridges that have been updated with the Hue Bridge Root CA per
      // https://developers.meethue.com/develop/application-design-guidance/using-https/#Hue%20Bridge%20Root%20CA
      // we could create a less permissive SSLContext that would only trust
      // that. But that would only work for newer bridges. To support older
      // bridges, per
      // https://developers.meethue.com/develop/application-design-guidance/using-https/#Self-signed%20certificates
      // we have to just trust all certs. It's either that or pinning, which
      // would be more config that we don't really need, because a typical
      // setup is a Raspberry Pi sat right next to the Bridge - so a MITM
      // isn't a likely attack.
      tlsContext <- TLSContext.Builder.forAsync[Task].insecure
      client <- EmberClientBuilder
        .default[Task]
        .withTLSContext(tlsContext)
        // Per
        // https://developers.meethue.com/develop/application-design-guidance/using-https/#Common%20name%20validation.
        // we could define a function that maps the bridge ID to its IP
        // address, but that would require passing in the bridge ID as
        // additional config. But we don't verify the certificate anyway, so
        // there's little value going out of our way to make hostname
        // validation work.
        .withCheckEndpointAuthentication(false)
        .withIdleConnectionTime(Duration.Infinity.asScala)
        .withTimeout(Duration.Infinity.asScala)
        .build
        .toScopedZIO
        .map(client =>
          Logger.colored(
            logHeaders = true,
            logBody = true,
            logAction = Some(Console.printLine(_))
          )(client)
        )
    yield client
  )

  // Per
  // https://developers.meethue.com/develop/hue-api-v2/core-concepts/#limitations:
  // > We can’t send commands to the lights too fast. If you stick to around 10
  // > commands per second to the /light resource as maximum you should be fine.
  // > For /grouped_light commands you should keep to a maximum of 1 per second.
  // > The REST API should not be used to send a continuous stream of fast light
  // > updates for an extended period of time, for that use case you should use
  // > the dedicated Hue Entertainment Streaming API.
  //
  // Per
  // https://developers.meethue.com/develop/application-design-guidance/hue-system-performance/:
  // > As a general guideline we always recommend to our developers to stay at
  // > roughly 10 commands per second to the /lights resource with a 100ms gap
  // > between each API call. For /groups commands you should keep to a maximum
  // > of 1 per second. It is however always recommended to take into
  // > consideration the above information and to of course stress test your
  // > app/system to find the optimal values for your application. For updating
  // > multiple lights at a high update rate for more than just a few seconds,
  // > the dedicated Entertainment Streaming API must be used instead of the
  // > REST API.
  val rateLimiterLayer = ZLayer(
    RateLimiter.make(max = 1, interval = 100.milliseconds)
  )
