package helios

import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

import com.luckycatlabs.sunrisesunset
import nl.vroste.rezilience.*
import org.http4s.*
import org.http4s.client.Client
import zio.*
import zio.json.*

object Helios extends ZIOAppDefault:

  override def run: URIO[Scope, ExitCode] =
    program.orDie
      .provideSome[Scope](
        bridgeApiBaseUriLayer,
        bridgeApiKeyLayer,
        zoneIdLayer,
        sunriseSunsetCalculatorLayer,
        HueApi.rateLimiterLayer,
        HueApi.clientLayer
      )
      .exitCode

  val bridgeApiBaseUriLayer = ZLayer(
    envParam("BRIDGE_IP_ADDRESS").map(bridgeIpAddress =>
      HueApi.BridgeApiBaseUri(
        Uri(
          scheme = Some(Uri.Scheme.https),
          authority = Some(Uri.Authority(host = Uri.RegName(bridgeIpAddress)))
        )
      )
    )
  )
  val bridgeApiKeyLayer = ZLayer(
    envParam("BRIDGE_API_KEY").map(HueApi.BridgeApiKey(_))
  )
  val zoneIdLayer = ZLayer(
    envParam("TIME_ZONE").map(ZoneId.of)
  )
  val sunriseSunsetCalculatorLayer = ZLayer(
    for
      homeLatitude <- envParam("HOME_LATITUDE")
      homeLongitude <- envParam("HOME_LONGITUDE")
      zoneId <- ZIO.service[ZoneId]
    yield sunrisesunset.SunriseSunsetCalculator(
      sunrisesunset.dto.Location(homeLatitude, homeLongitude),
      TimeZone.getTimeZone(zoneId)
    )
  )

  def envParam(name: String): Task[String] = System
    .env(name)
    .someOrFail(new Error(s"$name must be set."))

  val program: RIO[
    Scope & HueApi.BridgeApiBaseUri & HueApi.BridgeApiKey & ZoneId &
      sunrisesunset.SunriseSunsetCalculator & RateLimiter & Client[Task],
    Unit
  ] = for
    initialTargetBrightnessAndMirekValues <-
      decideTargetBrightnessAndMirekValues
    targetBrightnessAndMirekValuesRef <- Ref
      .make(initialTargetBrightnessAndMirekValues)
    lightsRef <- Ref.make(Map.empty[String, HueApi.Data.Light])
    _ <- (for
      targetBrightnessAndMirekValues <- decideTargetBrightnessAndMirekValues
      (targetBrightnessValue, targetMirekValue) = targetBrightnessAndMirekValues
      _ <- targetBrightnessAndMirekValuesRef.set(
        (targetBrightnessValue, targetMirekValue)
      )
      _ <- lightsRef.get.flatMap(lights =>
        updateActiveLights(
          targetBrightnessValue,
          targetMirekValue,
          lights.values
        )
      )
      // Per
      // https://discord.com/channels/629491597070827530/630498701860929559/970785884230258748:
      // Schedule.secondOfMinute evaluates the effect repeatedly during the
      // given second of each minute, which is not what we want. By sleeping for
      // a second we can ensure the effect is called only once.
      // TODO: https://github.com/zio/zio/pull/6772 fixes that, so this sleep
      // can be removed when it's released.
      _ <- Clock.sleep(1.second)
    yield ()).scheduleFork(Schedule.secondOfMinute(0))
    now <- Clock.instant
    getLightsResponse <- HueApi.getLights
    _ <- lightsRef
      .update(lights =>
        lights ++ getLightsResponse.data.map(light => (light.id, light))
      )
    // Replay from a time before the get-lights call, to ensure no gaps.
    replayFrom = now.minusSeconds(60)
    _ <- ZIO.logInfo(
      logMessage(
        message = "Replaying events",
        event = ast.Json.Obj(
          "replayFrom" -> ast.Json.Str(replayFrom.toString)
        )
      )
    )
    _ <- HueApi
      .events(
        eventId = Some(
          ServerSentEvent.EventId(s"${replayFrom.getEpochSecond}:0")
        )
      )
      .foreach {
        case update: HueApi.Event.Update =>
          ZIO.foreach(update.data) {
            case HueApi.Data.Light(id, on, dimming, colorTemperature) =>
              for
                updateEffect <- lightsRef.modify(lights =>
                  lights.get(id) match
                    case None =>
                      // If we're receiving an update for a light we don't have
                      // a record of that's fine - it's a rare possibility but
                      // could happen during startup, if the light had actually
                      // been deleted just before the get-lights call, but
                      // before the point in time that we're replaying events
                      // from. In that case it's fine to do nothing because a)
                      // we can't upsert it because we don't have all its
                      // attributes, and b) we would soon encounter the replayed
                      // delete event anyway and remove it.
                      val noopUpdateEffect = ZIO.unit
                      (noopUpdateEffect, lights)

                    case Some(light) =>
                      val updatedLight = light.copy(
                        on = on.orElse(light.on),
                        dimming = dimming.orElse(light.dimming),
                        colorTemperature =
                          colorTemperature.orElse(light.colorTemperature)
                      )
                      val updateEffect = for
                        targetBrightnessAndMirekValues <-
                          targetBrightnessAndMirekValuesRef.get
                        (targetBrightnessValue, targetMirekValue) =
                          targetBrightnessAndMirekValues
                        _ <- updateActiveLights(
                          targetBrightnessValue,
                          targetMirekValue,
                          List(updatedLight)
                        )
                      yield ()
                      (updateEffect, lights.updated(id, updatedLight))
                )
                _ <- updateEffect
              yield ()

            case _ =>
              ZIO.unit
          }

        case add: HueApi.Event.Add =>
          ZIO.foreach(add.data) {
            case HueApi.Data.Light(id, on, dimming, colorTemperature) =>
              val addedLight =
                HueApi.Data.Light(id, on, dimming, colorTemperature)
              for
                _ <- lightsRef.update(_.updated(id, addedLight))
                targetBrightnessAndMirekValues <-
                  targetBrightnessAndMirekValuesRef.get
                (targetBrightnessValue, targetMirekValue) =
                  targetBrightnessAndMirekValues
                _ <- updateActiveLights(
                  targetBrightnessValue,
                  targetMirekValue,
                  List(addedLight)
                )
              yield ()

            case _ =>
              ZIO.unit
          }

        case delete: HueApi.Event.Delete =>
          ZIO.foreach(delete.data) {
            case HueApi.Data.Light(id, _, _, _) =>
              lightsRef.update(_.removed(id))

            case _ =>
              ZIO.unit
          }

        case error: HueApi.Event.Error =>
          ZIO.fail(RuntimeException(error.toString))
      }
      .forkScoped
    _ <- ZIO.never
  yield ()

  enum TimeOfDay:
    case Night, Dawn, Day, Dusk

  def decideTargetBrightnessAndMirekValues: RIO[
    ZoneId & sunrisesunset.SunriseSunsetCalculator,
    (Double, Int)
  ] = for
    zoneId <- ZIO.service[ZoneId]
    sunriseSunsetCalculator <- ZIO
      .service[sunrisesunset.SunriseSunsetCalculator]
    instant <- Clock.instant
    today = GregorianCalendar.from(instant.atZone(zoneId))
    now <- toLocalTime(today)
    civilSunrise <- toLocalTime(
      sunriseSunsetCalculator
        .getCivilSunriseCalendarForDate(today)
    )
    officialSunrise <- toLocalTime(
      sunriseSunsetCalculator
        .getOfficialSunriseCalendarForDate(today)
    )
    officialSunset <- toLocalTime(
      sunriseSunsetCalculator
        .getOfficialSunsetCalendarForDate(today)
    )
    civilSunset <- toLocalTime(
      sunriseSunsetCalculator
        .getCivilSunsetCalendarForDate(today)
    )
    timeOfDay =
      if now.isBefore(civilSunrise) then TimeOfDay.Night
      else if now.isBefore(officialSunrise) then TimeOfDay.Dusk
      else if now.isBefore(officialSunset) then TimeOfDay.Day
      else if now.isBefore(civilSunset) then TimeOfDay.Dusk
      else TimeOfDay.Night
    _ <- ZIO.logInfo(
      logMessage(
        message = "Evaluated time of day",
        event = ast.Json.Obj(
          "now" -> ast.Json.Str(now.toString),
          "civilSunrise" -> ast.Json.Str(civilSunrise.toString),
          "officialSunrise" -> ast.Json.Str(officialSunrise.toString),
          "officialSunset" -> ast.Json.Str(officialSunset.toString),
          "civilSunset" -> ast.Json.Str(civilSunset.toString),
          "timeOfDay" -> ast.Json.Str(timeOfDay.toString)
        )
      )
    )
    targetBrightnessValueAndTargetMirekValue = timeOfDay match
      case TimeOfDay.Night => relax
      case TimeOfDay.Dawn  => energize
      case TimeOfDay.Day   => concentrate
      case TimeOfDay.Dusk  => read
  yield targetBrightnessValueAndTargetMirekValue

  def toLocalTime(calendar: Calendar): RIO[ZoneId, LocalTime] = for
    zoneId <- ZIO.service[ZoneId]
    localTime = calendar.toInstant.atZone(zoneId).toLocalTime
  yield localTime

  val energize = 100d -> 156
  val concentrate = 100d -> 233
  val read = 100d -> 346
  val relax = 56.3 -> 447

  // TODO: Review
  // https://developers.meethue.com/develop/application-design-guidance/hue-groups-rooms-and-scene-control/
  // - can we use group 0?
  def updateActiveLights(
      targetBrightnessValue: Double,
      targetMirekValue: Int,
      lights: Iterable[HueApi.Data.Light]
  ): RIO[
    HueApi.BridgeApiBaseUri & HueApi.BridgeApiKey & RateLimiter & Client[Task],
    Unit
  ] = ZIO
    .foreach(
      lights.filter(light =>
        light.on.exists(_.on) &&
          light.colorTemperature.isDefined &&
          (
            !light.dimming
              .contains(HueApi.Dimming(brightness = targetBrightnessValue)) ||
              !light.colorTemperature
                .contains(
                  HueApi.ColorTemperature(mirek = Some(targetMirekValue))
                )
          )
      )
    )(light =>
      HueApi.putLight(
        light.copy(
          dimming = Some(HueApi.Dimming(brightness = targetBrightnessValue)),
          colorTemperature =
            Some(HueApi.ColorTemperature(mirek = Some(targetMirekValue)))
        )
      )
    )
    .unit

  def logMessage(message: String, event: ast.Json): String =
    ast.Json
      .Obj(
        "message" -> ast.Json.Str(message),
        "event" -> event
      )
      .toJsonPretty
