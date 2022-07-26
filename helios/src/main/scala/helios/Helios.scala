package helios

import com.luckycatlabs.sunrisesunset
import nl.vroste.rezilience.*
import org.http4s.*
import org.http4s.client.Client
import zio.*
import zio.json.*

import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

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

  val bridgeApiBaseUriLayer: TaskLayer[HueApi.BridgeApiBaseUri] =
    ZLayer.fromZIO(
      envParam("BRIDGE_IP_ADDRESS").map(bridgeIpAddress =>
        HueApi.BridgeApiBaseUri(
          Uri(
            scheme = Some(Uri.Scheme.https),
            authority = Some(Uri.Authority(host = Uri.RegName(bridgeIpAddress)))
          )
        )
      )
    )
  val bridgeApiKeyLayer: TaskLayer[HueApi.BridgeApiKey] = ZLayer.fromZIO(
    envParam("BRIDGE_API_KEY").map(HueApi.BridgeApiKey(_))
  )
  val zoneIdLayer: TaskLayer[ZoneId] = ZLayer.fromZIO(
    envParam("TIME_ZONE").map(ZoneId.of)
  )
  val sunriseSunsetCalculatorLayer
      : RLayer[ZoneId, sunrisesunset.SunriseSunsetCalculator] = ZLayer.fromZIO(
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
    getBridgeHomeResponse <- HueApi.getBridgeHome
    group0ResourceIdentifier <- getBridgeHomeResponse.data match
      case List(bridgeHome) =>
        bridgeHome.services match
          case List(service) => ZIO.succeed(service)
          case _ =>
            ZIO.fail(
              RuntimeException(
                s"Expected exactly one grouped light for the bridge home but found ${bridgeHome.services.length}"
              )
            )
      case _ =>
        ZIO.fail(
          RuntimeException(
            s"Expected exactly one bridge home but found ${getBridgeHomeResponse.data.length}"
          )
        )
    _ <- ZIO.logInfo(
      logMessage(
        message = "Found group 0",
        event = ast.Json.Obj(
          "id" -> ast.Json.Str(group0ResourceIdentifier.rid)
        )
      )
    )
    targetDimmingAndColorTemperature <- decideTargetDimmingAndColorTemperature
    targetDimmingAndColorTemperatureRef <- Ref.make(
      targetDimmingAndColorTemperature
    )
    _ <- updateActiveLights(
      group0ResourceIdentifier,
      currentDimming = None,
      currentColorTemperature = None,
      targetDimmingAndColorTemperature = targetDimmingAndColorTemperature
    )
    _ <- (for
      currentDimmingAndColorTemperature <-
        targetDimmingAndColorTemperatureRef.get
      (currentDimming, currentColorTemperature) =
        currentDimmingAndColorTemperature
      targetDimmingAndColorTemperature <- decideTargetDimmingAndColorTemperature
      _ <- targetDimmingAndColorTemperatureRef.set(
        targetDimmingAndColorTemperature
      )
      _ <- updateActiveLights(
        group0ResourceIdentifier,
        currentDimming = Some(currentDimming),
        currentColorTemperature = Some(currentColorTemperature),
        targetDimmingAndColorTemperature = targetDimmingAndColorTemperature
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
    _ <- ZIO.logInfo(
      logMessage(
        message = "Streaming events",
        event = ast.Json.Null
      )
    )
    _ <- HueApi
      .events()
      .foreach {
        case update: HueApi.Event.Update =>
          ZIO.foreach(update.data) {
            case HueApi.Data.Light(_, _, dimming, colorTemperature) =>
              for
                targetDimmingAndColorTemperature <-
                  targetDimmingAndColorTemperatureRef.get
                _ <- updateActiveLights(
                  group0ResourceIdentifier,
                  currentDimming = dimming,
                  currentColorTemperature = colorTemperature,
                  targetDimmingAndColorTemperature
                )
              yield ()

            case _ =>
              ZIO.unit
          }

        case add: HueApi.Event.Add =>
          ZIO.foreach(add.data) {
            case HueApi.Data.Light(_, _, dimming, colorTemperature) =>
              for
                targetDimmingAndColorTemperature <-
                  targetDimmingAndColorTemperatureRef.get
                _ <- updateActiveLights(
                  group0ResourceIdentifier,
                  currentDimming = dimming,
                  currentColorTemperature = colorTemperature,
                  targetDimmingAndColorTemperature
                )
              yield ()

            case _ =>
              ZIO.unit
          }

        case _: HueApi.Event.Delete =>
          ZIO.unit

        case error: HueApi.Event.Error =>
          ZIO.fail(RuntimeException(error.toString))
      }
      .forkScoped
    _ <- ZIO.never
  yield ()

  enum TimeOfDay:
    case Night, Dawn, Day, Dusk

  def decideTargetDimmingAndColorTemperature: RIO[
    ZoneId & sunrisesunset.SunriseSunsetCalculator,
    (HueApi.Dimming, HueApi.ColorTemperature)
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
    targetDimmingAndColorTemperature = timeOfDay match
      case TimeOfDay.Night => relax
      case TimeOfDay.Dawn  => energize
      case TimeOfDay.Day   => concentrate
      case TimeOfDay.Dusk  => read
  yield targetDimmingAndColorTemperature

  def toLocalTime(calendar: Calendar): RIO[ZoneId, LocalTime] = for
    zoneId <- ZIO.service[ZoneId]
    localTime = calendar.toInstant.atZone(zoneId).toLocalTime
  yield localTime

  val energize =
    HueApi.Dimming(brightness = 100d) ->
      HueApi.ColorTemperature(mirek = Some(156))
  val concentrate =
    HueApi.Dimming(brightness = 100d) ->
      HueApi.ColorTemperature(mirek = Some(233))
  val read =
    HueApi.Dimming(brightness = 100d) ->
      HueApi.ColorTemperature(mirek = Some(346))
  val relax =
    HueApi.Dimming(brightness = 56.3d) ->
      HueApi.ColorTemperature(mirek = Some(447))

  def updateActiveLights(
      group0ResourceIdentifier: HueApi.ResourceIdentifier,
      currentDimming: Option[HueApi.Dimming],
      currentColorTemperature: Option[HueApi.ColorTemperature],
      targetDimmingAndColorTemperature: (
          HueApi.Dimming,
          HueApi.ColorTemperature
      )
  ) =
    val (targetDimming, targetColorTemperature) =
      targetDimmingAndColorTemperature
    ZIO.when(
      currentDimming != Some(targetDimming) ||
        currentColorTemperature != Some(targetColorTemperature)
    )(
      HueApi
        .putGroupedLight(
          HueApi.Data.GroupedLight(
            id = group0ResourceIdentifier.rid,
            on = None,
            dimming =
              if currentDimming != targetDimming then Some(targetDimming)
              else None,
            colorTemperature =
              if currentColorTemperature != targetColorTemperature
              then Some(targetColorTemperature)
              else None
          )
        )
        .unit
    )

  def logMessage(message: String, event: ast.Json): String =
    ast.Json
      .Obj(
        "message" -> ast.Json.Str(message),
        "event" -> event
      )
      .toJsonPretty
