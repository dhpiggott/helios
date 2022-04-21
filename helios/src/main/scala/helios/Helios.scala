package helios

import java.time.ZoneId
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
    System
      .env("BRIDGE_IP_ADDRESS")
      .someOrFail("BRIDGE_IP_ADDRESS must be set.")
      .map(bridgeIpAddress =>
        HueApi.BridgeApiBaseUri(
          Uri(
            scheme = Some(Uri.Scheme.https),
            authority = Some(Uri.Authority(host = Uri.RegName(bridgeIpAddress)))
          )
        )
      )
  )
  val bridgeApiKeyLayer = ZLayer(
    System
      .env("BRIDGE_API_KEY")
      .someOrFail("BRIDGE_API_KEY must be set.")
      .map(HueApi.BridgeApiKey(_))
  )
  val zoneIdLayer = ZLayer(
    System
      .env("TIME_ZONE")
      .someOrFail("TIME_ZONE must be set.")
      .map(ZoneId.of)
  )
  val sunriseSunsetCalculatorLayer = ZLayer(
    for
      homeLatitude <- System
        .env("HOME_LATITUDE")
        .someOrFail("HOME_LATITUDE must be set.")
      homeLongitude <- System
        .env("HOME_LONGITUDE")
        .someOrFail("HOME_LONGITUDE must be set.")
      zoneId <- RIO.service[ZoneId]
    yield sunrisesunset.SunriseSunsetCalculator(
      sunrisesunset.dto.Location(homeLatitude, homeLongitude),
      TimeZone.getTimeZone(zoneId)
    )
  )

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
    targetDeciderFiber <- (for
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
    yield ()).repeat(Schedule.secondOfMinute(0)).forkScoped
    now <- Clock.instant
    getLightsResponse <- HueApi.getLights
    _ <- lightsRef
      .update(lights =>
        lights ++ getLightsResponse.data.map(light => (light.id, light))
      )
    // Replay from a time before the get-lights call, to ensure no gaps.
    replayFrom = now.minusSeconds(60)
    _ <- Console.printLine(s"replaying events from: $now")
    eventHandlerFiber <- HueApi
      .events(
        eventId = Some(
          ServerSentEvent.EventId(s"${replayFrom.getEpochSecond}:0")
        )
      )
      .foreach {
        case update: HueApi.Event.Update =>
          RIO.foreach(update.data) {
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
              UIO.unit
          }

        case add: HueApi.Event.Add =>
          RIO.foreach(add.data) {
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
              UIO.unit
          }

        case delete: HueApi.Event.Delete =>
          RIO.foreach(delete.data) {
            case HueApi.Data.Light(id, _, _, _) =>
              lightsRef.update(_.removed(id))

            case _ =>
              UIO.unit
          }

        case error: HueApi.Event.Error =>
          Task.fail(RuntimeException(error.toString))
      }
      .forkScoped
    _ <- targetDeciderFiber.zip(eventHandlerFiber).join
  yield ()

  def decideTargetBrightnessAndMirekValues: RIO[
    ZoneId & sunrisesunset.SunriseSunsetCalculator,
    (Double, Int)
  ] = for
    zoneId <- RIO.service[ZoneId]
    sunriseSunsetCalculator <- RIO
      .service[sunrisesunset.SunriseSunsetCalculator]
    now <- Clock.instant.map(_.atZone(zoneId))
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
    _ <- Console.printLine(s"now:                $now")
    _ <- Console.printLine(s"  civil sunrise:    $civilSunrise")
    _ <- Console.printLine(s"  official sunrise: $officialSunrise")
    _ <- Console.printLine(s"  official sunset:  $officialSunset")
    _ <- Console.printLine(s"  civil sunset:     $civilSunset")
    targetBrightnessValueAndTargetMirekValue <-
      if now.isBefore(civilSunrise) then
        Console
          .printLine("  time of day: night, before-dawn - selecting relax")
          .as(relax)
      else if now.isBefore(officialSunrise) then
        Console
          .printLine("  time of day: dawn - selecting energize")
          .as(energize)
      else if now.isBefore(officialSunset) then
        Console
          .printLine("  time of day: day - selecting concentrate")
          .as(concentrate)
      else if now.isBefore(civilSunset) then
        Console
          .printLine("  time of day: dusk - selecting read")
          .as(read)
      else
        Console
          .printLine("  time of day: night, after-dusk - selecting relax")
          .as(relax)
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
      lights: Iterable[HueApi.Data.Light]
  ): RIO[
    HueApi.BridgeApiBaseUri & HueApi.BridgeApiKey & RateLimiter & Client[Task],
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
