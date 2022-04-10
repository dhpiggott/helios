package helios

import java.time.ZoneId
import java.util.GregorianCalendar
import java.util.TimeZone

import com.luckycatlabs.sunrisesunset
import nl.vroste.rezilience.*
import org.http4s.*
import org.http4s.client.Client
import zio.*
import zio.blocking.*
import zio.clock.*
import zio.console.*
import zio.json.*
import zio.system.*

// Resources:
// https://github.com/wpietri/sunrise/tree/master/src/main/scala/light
object Helios extends App:

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    program.orDie
      .provideCustomLayer(
        bridgeApiBaseUriLayer ++ bridgeApiKeyLayer ++ zoneIdLayer ++ sunriseSunsetCalculatorLayer ++ HueApi.rateLimiterLayer ++ HueApi.clientLayer
      )
      .useForever
      .exitCode

  val bridgeApiBaseUriLayer = env("BRIDGE_IP_ADDRESS")
    .flatMap(IO.fromOption(_))
    .orElseFail("BRIDGE_IP_ADDRESS must be set.")
    .map(bridgeIpAddress =>
      HueApi.BridgeApiBaseUri(
        Uri(
          scheme = Some(Uri.Scheme.https),
          authority = Some(Uri.Authority(host = Uri.RegName(bridgeIpAddress)))
        )
      )
    )
    .toLayer
  val bridgeApiKeyLayer = env("BRIDGE_API_KEY")
    .flatMap(IO.fromOption(_))
    .orElseFail("BRIDGE_API_KEY must be set.")
    .map(HueApi.BridgeApiKey(_))
    .toLayer
  val zoneIdLayer = env("TIME_ZONE")
    .flatMap(IO.fromOption(_))
    .orElseFail("TIME_ZONE must be set.")
    .map(ZoneId.of(_))
    .toLayer
  val sunriseSunsetCalculatorLayer = System.any ++ zoneIdLayer >>> (for
    homeLatitude <- env("HOME_LATITUDE")
      .flatMap(IO.fromOption(_))
      .orElseFail("HOME_LATITUDE must be set.")
    homeLongitude <- env("HOME_LONGITUDE")
      .flatMap(IO.fromOption(_))
      .orElseFail("HOME_LONGITUDE must be set.")
    zoneId <- RIO.service[ZoneId]
  yield sunrisesunset.SunriseSunsetCalculator(
    sunrisesunset.dto.Location(homeLatitude, homeLongitude),
    TimeZone.getTimeZone(zoneId)
  )).toLayer

  val program: RManaged[
    Blocking & Clock & Console & Has[HueApi.BridgeApiBaseUri] &
      Has[HueApi.BridgeApiKey] & Has[ZoneId] &
      Has[sunrisesunset.SunriseSunsetCalculator] & Has[RateLimiter] &
      Has[Client[Task]],
    Unit
  ] = for
    initialTargetBrightnessAndMirekValues <-
      decideTargetBrightnessAndMirekValues.toManaged_
    targetBrightnessAndMirekValuesRef <- ZRef
      .make(initialTargetBrightnessAndMirekValues)
      .toManaged_
    lightsRef <- ZRef.make(Map.empty[String, HueApi.Light]).toManaged_
    _ <- (for
      (targetBrightnessValue, targetMirekValue) <-
        decideTargetBrightnessAndMirekValues
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
    yield ()).repeat(Schedule.secondOfMinute(0)).forkManaged
    now <- instant.toManaged_
    getLightsResponse <- HueApi.getLights.toManaged_
    _ <- lightsRef
      .update(lights =>
        lights ++ getLightsResponse.data.map(light => (light.id, light))
      )
      .toManaged_
    // Replay from a time before the get-lights call, to ensure no gaps.
    replayFrom = now.minusSeconds(60)
    _ <- putStrLn(s"replaying events from: $now").toManaged_
    _ <- HueApi
      .events(
        eventId = Some(
          ServerSentEvent.EventId(s"${replayFrom.getEpochSecond}:0")
        )
      )
      .tap(event => putStrLn(s"received event:\n${event.toJsonPretty}"))
      .foreach {
        case update: HueApi.Event.Update =>
          RIO.foreach(update.data) {
            case HueApi.Event.Data.Light(id, on, dimming, colorTemperature) =>
              for
                updateEffect <- lightsRef.modify(lights =>
                  lights.get(id) match
                    case None =>
                      // If we're receiving an update for a light we don't have a
                      // record of that's fine - it's a rare possibility but could
                      // happen during startup, if the light had actually been
                      // deleted just before the get-lights call, but before the
                      // point in time that we're replaying events from. In that
                      // case it's fine to do nothing because a) we can't upsert
                      // it because we don't have all its attributes, and b) we
                      // would soon encounter the replayed delete event anyway and
                      // remove it.
                      val noopUpdateEffect = UIO.unit
                      (noopUpdateEffect, lights)

                    case Some(light) =>
                      val updatedLight = light.copy(
                        on = on.orElse(light.on),
                        dimming = dimming.orElse(light.dimming),
                        colorTemperature =
                          colorTemperature.orElse(light.colorTemperature)
                      )
                      val updateEffect = for
                        (targetBrightnessValue, targetMirekValue) <-
                          targetBrightnessAndMirekValuesRef.get
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
              UIO.unit
          }

        case add: HueApi.Event.Add =>
          RIO.foreach(add.data) {
            case HueApi.Event.Data.Light(id, on, dimming, colorTemperature) =>
              val addedLight = HueApi.Light(id, on, dimming, colorTemperature)
              for
                _ <- lightsRef.update(_.updated(id, addedLight))
                (targetBrightnessValue, targetMirekValue) <-
                  targetBrightnessAndMirekValuesRef.get
                _ <- updateActiveLights(
                  targetBrightnessValue,
                  targetMirekValue,
                  List(addedLight)
                )
              yield ()

            case _ =>
              UIO.unit
          }

        case delete: HueApi.Event.Delete =>
          RIO.foreach(delete.data) {
            case HueApi.Event.Data.Light(id, _, _, _) =>
              lightsRef.update(_.removed(id))

            case _ =>
              UIO.unit
          }

        case error: HueApi.Event.Error =>
          Task.fail(RuntimeException(error.toString))
      }
      .forkManaged
  yield ()

  def decideTargetBrightnessAndMirekValues: RIO[
    Clock & Console & Has[ZoneId] & Has[sunrisesunset.SunriseSunsetCalculator],
    (Double, Int)
  ] = for
    zoneId <- RIO.service[ZoneId]
    sunriseSunsetCalculator <- RIO
      .service[sunrisesunset.SunriseSunsetCalculator]
    now <- instant.map(_.atZone(zoneId))
    today = GregorianCalendar.from(now)
    civilSunrise = sunriseSunsetCalculator
      .getCivilSunriseCalendarForDate(today)
      .toInstant
      .atZone(zoneId)
    officialSunrise = sunriseSunsetCalculator
      .getOfficialSunriseCalendarForDate(today)
      .toInstant
      .atZone(zoneId)
    officialSunset = sunriseSunsetCalculator
      .getOfficialSunsetCalendarForDate(today)
      .toInstant
      .atZone(zoneId)
    civilSunset = sunriseSunsetCalculator
      .getCivilSunsetCalendarForDate(today)
      .toInstant
      .atZone(zoneId)
    _ <- putStrLn(s"now:                $now")
    _ <- putStrLn(s"  civil sunrise:    $civilSunrise")
    _ <- putStrLn(s"  official sunrise: $officialSunrise")
    _ <- putStrLn(s"  official sunset:  $officialSunset")
    _ <- putStrLn(s"  civil sunset:     $civilSunset")
    targetBrightnessValueAndTargetMirekValue <-
      if now.isBefore(civilSunrise) then
        putStrLn("  time of day: night, before-dawn - selecting relax").as(
          relax
        )
      else if now.isBefore(officialSunrise) then
        putStrLn("  time of day: dawn - selecting energize").as(energize)
      else if now.isBefore(officialSunset) then
        putStrLn("  time of day: day - selecting concentrate").as(concentrate)
      else if now.isBefore(civilSunset) then
        putStrLn("  time of day: dusk - selecting read").as(read)
      else
        putStrLn("  time of day: night, after-dusk - selecting relax").as(relax)
  yield targetBrightnessValueAndTargetMirekValue

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
      lights: Iterable[HueApi.Light]
  ): RIO[
    Blocking & Has[HueApi.BridgeApiBaseUri] & Has[HueApi.BridgeApiKey] &
      Has[RateLimiter] & Has[Client[Task]],
    Unit
  ] = RIO
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
