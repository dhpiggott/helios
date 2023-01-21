package helios

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
import smithy.api.HttpApiKeyLocations.HEADER
import smithy.api.HttpApiKeyLocations.QUERY
import smithy4s.*
import smithy4s.http4s.*
import smithy4s.kinds.*
import zio.*
import zio.interop.catz.*
import zio.json.*
import zio.stream.*
import zio.stream.interop.fs2z.*

import java.nio.charset.StandardCharsets
import java.time.Instant

object HueApi extends Http4sClientDsl[Task]:

  final case class BridgeApiBaseUri(value: Uri)
  final case class BridgeApiKey(value: String)

  final case class ResourceIdentifier(rid: String, rtype: String)
  object ResourceIdentifier:
    implicit val codec: JsonCodec[ResourceIdentifier] =
      DeriveJsonCodec.gen[ResourceIdentifier]

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

  final case class Error(description: String)
  object Error:
    implicit val codec: JsonCodec[Error] = DeriveJsonCodec.gen[Error]

  @jsonDiscriminator("type") sealed abstract class Data:
    def id: String
  object Data:
    implicit val codec: JsonCodec[Data] = DeriveJsonCodec.gen[Data]
    @jsonHint("light") final case class Light(
        override val id: String,
        on: Option[On],
        dimming: Option[Dimming],
        @jsonField("color_temperature") colorTemperature: Option[
          ColorTemperature
        ]
    ) extends Data
    object Light:
      implicit val codec: JsonCodec[Light] = DeriveJsonCodec.gen[Light]
    @jsonHint("room") final case class Room(override val id: String)
        extends Data
    @jsonHint("zone") final case class Zone(override val id: String)
        extends Data
    @jsonHint("bridge_home") final case class BridgeHome(
        override val id: String
    ) extends Data
    @jsonHint("grouped_light") final case class GroupedLight(
        override val id: String
    ) extends Data
    @jsonHint("device") final case class Device(override val id: String)
        extends Data
    @jsonHint("bridge") final case class Bridge(override val id: String)
        extends Data
    @jsonHint("device_power") final case class DevicePower(
        override val id: String
    ) extends Data
    @jsonHint("zigbee_connectivity") final case class ZigbeeConnectivity(
        override val id: String
    ) extends Data
    @jsonHint("zgp_connectivity") final case class ZgpConnectivity(
        override val id: String
    ) extends Data
    @jsonHint("motion") final case class Motion(override val id: String)
        extends Data
    @jsonHint("temperature") final case class Temperature(
        override val id: String
    ) extends Data
    @jsonHint("light_level") final case class LightLevel(
        override val id: String
    ) extends Data
    @jsonHint("button") final case class Button(override val id: String)
        extends Data
    @jsonHint("behavior_script") final case class BehaviorScript(
        override val id: String
    ) extends Data
    @jsonHint("behavior_instance") final case class BehaviorInstance(
        override val id: String
    ) extends Data
    @jsonHint("geofence_client") final case class GeofenceClient(
        override val id: String
    ) extends Data
    @jsonHint("geolocation") final case class Geolocation(
        override val id: String
    ) extends Data
    @jsonHint(
      "entertainment_configuration"
    ) final case class EntertainmentConfiguration(override val id: String)
        extends Data

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
  // TODO: Use Smithy?
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
            _ <- ZIO.logInfo(
              Helios.logMessage(
                message = "Read event",
                event = ast.Json.Str(serverSentEvent.renderString)
              )
            )
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
            _ <- ZStream.fromZIO(
              ZIO.logError(
                Helios.logMessage(
                  message = "Stream error",
                  event = ast.Json.Obj(
                    "error" -> ast.Json.Str(error.getMessage)
                  )
                )
              )
            )
            eventId <- ZStream.fromZIO(eventIdRef.get)
            retry <- ZStream.fromZIO(retryRef.get)
            event <- events(eventId, retry)
          yield event
        )
    yield event).tap(event =>
      ZIO.logInfo(
        Helios.logMessage(
          message = "Decoded event",
          event = ast.Json.Obj(
            "event" -> event.toJsonAST
              .getOrElse(throw RuntimeException("impossible"))
          )
        )
      )
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
  val rateLimiterLayer: TaskLayer[RateLimiter] = ZLayer.scoped(
    RateLimiter.make(max = 1, interval = 1.second)
  )

  val clientLayer: TaskLayer[Client[Task]] = ZLayer.scoped(
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
            logAction = Some(ZIO.logInfo(_))
          )(client)
        )
    yield client
  )

  // TODO: Log parsed requests and responses?
  val smithyClient: RLayer[
    Scope & HueApi.BridgeApiBaseUri & HueApi.BridgeApiKey & Client[Task],
    helios.hueapi.HueRestApiGen[Kind1[Task]#toKind5]
  ] = ZLayer.fromZIO(
    for
      bridgeApiBaseUriLayer <- ZIO.service[HueApi.BridgeApiBaseUri]
      apiKey <- ZIO.service[HueApi.BridgeApiKey]
      client <- ZIO.service[Client[Task]]
      hueApi <- SimpleRestJsonBuilder(helios.hueapi.HueRestApiGen)
        .client(client)
        .uri(bridgeApiBaseUriLayer.value)
        .middleware(ApiKeyAuthMiddleware(apiKey.value))
        .resource
        .toScopedZIO
    yield hueApi
  )

  object ApiKeyAuthMiddleware:
    def apply(apiKey: String): ClientEndpointMiddleware[Task] =
      new ClientEndpointMiddleware.Simple[Task]:
        def prepareWithHints(
            serviceHints: Hints,
            endpointHints: Hints
        ): Client[Task] => Client[Task] =
          serviceHints.get[smithy.api.HttpApiKeyAuth] match
            case Some(httpApiKeyAuth) =>
              endpointHints.get[smithy.api.Auth] match
                case Some(auths) if auths.value.isEmpty => identity
                case _ =>
                  inputClient =>
                    Client[Task](request =>
                      inputClient.run(
                        httpApiKeyAuth.in match
                          case HEADER =>
                            request.withHeaders(
                              Header.Raw(
                                name = CIString(httpApiKeyAuth.name.value),
                                value = httpApiKeyAuth.scheme match
                                  case None        => apiKey
                                  case Some(value) => s"${value.value} $apiKey"
                              )
                            )
                          case QUERY =>
                            request.withUri(
                              request.uri
                                .withQueryParam(
                                  key = httpApiKeyAuth.name.value,
                                  value = apiKey
                                )
                            )
                      )
                    )
            case None => identity
