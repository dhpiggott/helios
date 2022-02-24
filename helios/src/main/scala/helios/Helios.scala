package helios

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.*

import cats.effect.Concurrent
import org.http4s.*
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.client.Client
import org.http4s.client.dsl.*
import org.http4s.dsl.io.*
import org.http4s.headers.*
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

  final case class Metadata(name: String)
  object Metadata:
    implicit val codec: JsonCodec[Metadata] = DeriveJsonCodec.gen[Metadata]

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

  final case class Light(
      id: String,
      metadata: Metadata,
      on: On,
      dimming: Option[Dimming],
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
            dimming: Option[Dimming],
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

  // Per https://developers.meethue.com/develop/hue-api-v2/core-concepts/#events
  def events(
      eventId: Option[ServerSentEvent.EventId] = None,
      retry: Option[Duration] = None
  ): ZStream[
    Blocking & Clock & Console & Has[BridgeApiBaseUri] & Has[BridgeApiKey] &
      Has[Client[Task]],
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
      _ <- ZStream.fromEffect(retry.fold(UIO.unit)(sleep(_)))
      eventIdRef <- ZStream.fromEffect(ZRef.make(eventId))
      retryRef <- ZStream.fromEffect(ZRef.make(retry))
      event <- client
        .stream(request)
        .flatMap(_.body)
        .through(ServerSentEvent.decoder)
        .toZStream()
        .tap(serverSentEvent =>
          for
            _ <- eventIdRef.set(serverSentEvent.id)
            _ <- retryRef.set(serverSentEvent.retry.map(Duration.fromScala))
          yield ()
        )
        .flatMap(_.data match
          case None =>
            ZStream.empty

          case Some(data) =>
            ZStream.fromIterableM(
              Task.fromEither(
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
              .fromEffect(putStrErr(s"Stream error: ${error.getMessage}"))
            eventId <- ZStream.fromEffect(eventIdRef.get)
            retry <- ZStream.fromEffect(retryRef.get)
            event <- events(eventId, retry)
          yield event
        )
    yield event)

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

  final case class GetLightsResponse(errors: List[Error], data: List[Light])
  object GetLightsResponse:
    implicit val decoder: JsonDecoder[GetLightsResponse] =
      DeriveJsonDecoder.gen[GetLightsResponse]

  // Per https://developers.meethue.com/develop/hue-api-v2/api-reference/#resource_light_get
  def getLights: RIO[
    Has[BridgeApiBaseUri] & Has[BridgeApiKey] & Has[Client[Task]],
    GetLightsResponse
  ] =
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

  // Per https://developers.meethue.com/develop/hue-api-v2/api-reference/#resource_light_put
  def putLight(light: Light): RIO[
    Has[BridgeApiBaseUri] & Has[BridgeApiKey] & Has[Client[Task]],
    PutLightResponse
  ] =
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
      .useForever
      .exitCode

  val energize = 100d -> 156
  val concentrate = 100d -> 233
  val read = 100d -> 346
  val relax = 56.3 -> 447

  val program: RManaged[
    Blocking & Clock & Console & Has[BridgeApiBaseUri] & Has[BridgeApiKey] &
      Has[Client[Task]],
    Unit
  ] = for
    // TODO
    targetBrightnessValueAndMirekValueRef <- ZRef.make((concentrate)).toManaged_
    lightsRef <- ZRefM.make(Map.empty[String, Light]).toManaged_
    getLightsResponse <- getLights.toManaged_
    _ <- lightsRef
      .update(lights =>
        UIO(lights ++ getLightsResponse.data.map(light => (light.id, light)))
      )
      .toManaged_
    _ <- lightsRef.get.flatMap(printState).toManaged_
    _ <- events().foreach {
      case update: Event.Update =>
        RIO.foreach(update.data) {
          case Event.Update.Data.Light(id, on, dimming, colorTemperature) =>
            lightsRef.update(lights =>
              lights.get(id) match
                case None =>
                  // If we're receiving an update for a light we don't have a
                  // record of that's fine - it's a rare possibility but can
                  // happen during startup before we'vee initilised.
                  UIO(lights)

                case Some(light) =>
                  for
                    updatedLight <- UIO(
                      light.copy(
                        on = on.getOrElse(light.on),
                        dimming = dimming.orElse(light.dimming),
                        colorTemperature =
                          colorTemperature.orElse(light.colorTemperature)
                      )
                    )
                    updatedLights = Map(updatedLight.id -> updatedLight)
                    _ <- printState(updatedLights)
                    (targetBrightnessValue, targetMirekValue) <-
                      targetBrightnessValueAndMirekValueRef.get
                    _ <- updateActiveLights(
                      updatedLights,
                      targetBrightnessValue,
                      targetMirekValue
                    )
                  yield lights.updated(id, updatedLight)
            )

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
    }.forkManaged
    _ <- (for
      now <- localDateTime
      (targetBrightnessValue, targetMirekValue) = now.getHour match
        case hour if hour >= 7 && hour < 10  => energize
        case hour if hour >= 10 && hour < 17 => concentrate
        case hour if hour >= 17 && hour < 22 => read
        case _                               => relax
      _ <- putStrLn(
        s"$now: (targetBrightnessValue, targetMirekValue)=${(targetBrightnessValue, targetMirekValue)}"
      )
      targetBrightnessValueAndMirekValue <-
        targetBrightnessValueAndMirekValueRef.set(
          (targetBrightnessValue, targetMirekValue)
        )
      _ <- lightsRef.get
        .flatMap(updateActiveLights(_, targetBrightnessValue, targetMirekValue))
    yield ()).repeat(Schedule.secondOfMinute(0)).forkManaged
  yield ()

  // TODO: Review
  // https://developers.meethue.com/develop/application-design-guidance/hue-groups-rooms-and-scene-control/
  // - can we use group 0?
  def updateActiveLights(
      lights: Map[String, Light],
      targetBrightnessValue: Double,
      targetMirekValue: Int
  ): RIO[
    Blocking & Clock & Has[BridgeApiBaseUri] & Has[BridgeApiKey] &
      Has[Client[Task]],
    Unit
  ] = RIO
    .foreach(
      lights.values.filter(light =>
        light.on.on &&
          light.colorTemperature.isDefined &&
          (
            !light.dimming
              .contains(Dimming(brightness = targetBrightnessValue)) ||
              !light.colorTemperature
                .contains(ColorTemperature(mirek = Some(targetMirekValue)))
          )
      )
    )(light =>
      putLight(
        light.copy(
          dimming = Some(Dimming(brightness = targetBrightnessValue)),
          colorTemperature =
            Some(ColorTemperature(mirek = Some(targetMirekValue)))
        )
      ) *>
        // Per
        // https://developers.meethue.com/develop/hue-api-v2/core-concepts/#limitations:
        // > We can’t send commands to the lights too fast. If you stick to
        // > around 10 commands per second to the /light resource as maximum you
        // > should be fine.
        //
        // TODO: Make caller enforce this because we don't control how fast
        // events come in.
        //
        // TODO: Review
        // https://developers.meethue.com/develop/application-design-guidance/hue-system-performance/
        sleep(100.milliseconds)
    )
    .unit

  def printState(lights: Map[String, Light]): RIO[Console, Unit] =
    RIO.foreach(lights.values)(light => putStrLn(light.toJson)).unit

  // TODO: Use https://developers.meethue.com/develop/application-design-guidance/hue-bridge-discovery/
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

  // TODO: Review existing keys (see
  // https://developers.meethue.com/develop/hue-api/7-configuration-api/#del-user-from-whitelist)
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
      // TODO: Use cert from
      // https://developers.meethue.com/develop/application-design-guidance/using-https/
      override def checkServerTrusted(
          chain: Array[X509Certificate],
          authType: String
      ): Unit = ()
    ),
    SecureRandom()
  )
  val clientLayer = RIO
    .runtime[Blocking & Clock]
    .toManaged_
    .flatMap(implicit runtime =>
      BlazeClientBuilder[Task]
        .withExecutionContext(runtime.platform.executor.asEC)
        .withSslContext(sslContext)
        // TODO: Set a custom validator to match the IP per
        // https://developers.meethue.com/develop/application-design-guidance/using-https/?
        .withCheckEndpointAuthentication(false)
        .withIdleTimeout(Duration.Infinity.asScala)
        .withRequestTimeout(Duration.Infinity.asScala)
        .resource
        .toManagedZIO
    )
    .toLayer
