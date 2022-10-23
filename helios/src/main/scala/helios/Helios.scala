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

  final case class WakeTime(value: LocalTime)
  final case class SleepTime(value: LocalTime)

  override def run: URIO[Scope, ExitCode] =
    program.orDie
      .provideSome[Scope](
        bridgeApiBaseUriLayer,
        bridgeApiKeyLayer,
        zoneIdLayer,
        sunriseSunsetCalculatorLayer,
        wakeTimeLayer,
        sleepTimeLayer,
        HueApi.rateLimiterLayer,
        HueApi.clientLayer
      )
      .exitCode

  val bridgeApiBaseUriLayer: TaskLayer[HueApi.BridgeApiBaseUri] =
    ZLayer.fromZIO(
      for
        bridgeIpAddressString <- envParam("BRIDGE_IP_ADDRESS")
        bridgeApiBaseUri = HueApi.BridgeApiBaseUri(
          Uri(
            scheme = Some(Uri.Scheme.https),
            authority =
              Some(Uri.Authority(host = Uri.RegName(bridgeIpAddressString)))
          )
        )
      yield bridgeApiBaseUri
    )
  val bridgeApiKeyLayer: TaskLayer[HueApi.BridgeApiKey] = ZLayer.fromZIO(
    envParam("BRIDGE_API_KEY").map(HueApi.BridgeApiKey(_))
  )
  val zoneIdLayer: TaskLayer[ZoneId] = ZLayer.fromZIO(
    for
      timeZoneString <- envParam("TIME_ZONE")
      zoneId <- ZIO.attempt(ZoneId.of(timeZoneString))
    yield zoneId
  )
  val sunriseSunsetCalculatorLayer
      : RLayer[ZoneId, sunrisesunset.SunriseSunsetCalculator] = ZLayer.fromZIO(
    for
      homeLatitude <- envParam("HOME_LATITUDE")
      homeLongitude <- envParam("HOME_LONGITUDE")
      zoneId <- ZIO.service[ZoneId]
      sunriseSunsetCalculator = sunrisesunset.SunriseSunsetCalculator(
        sunrisesunset.dto.Location(homeLatitude, homeLongitude),
        TimeZone.getTimeZone(zoneId)
      )
    yield sunriseSunsetCalculator
  )
  val wakeTimeLayer: TaskLayer[WakeTime] = ZLayer.fromZIO(
    for
      wakeTimeString <- envParam("WAKE_TIME")
      value <- ZIO.attempt(LocalTime.parse(wakeTimeString))
      wakeTime = WakeTime(value)
    yield wakeTime
  )
  val sleepTimeLayer: TaskLayer[SleepTime] = ZLayer.fromZIO(
    for
      sleepTimeString <- envParam("SLEEP_TIME")
      value <- ZIO.attempt(LocalTime.parse(sleepTimeString))
      sleepTime = SleepTime(value)
    yield sleepTime
  )

  def envParam(name: String): Task[String] = System
    .env(name)
    .someOrFail(new Error(s"$name must be set."))

  val program: RIO[
    Scope & HueApi.BridgeApiBaseUri & HueApi.BridgeApiKey & ZoneId &
      sunrisesunset.SunriseSunsetCalculator & WakeTime & SleepTime &
      RateLimiter & Client[Task],
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
      currentDimming = currentDimmingAndColorTemperature.dimming
      currentColorTemperature =
        currentDimmingAndColorTemperature.colorTemperature
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
    case BeforeMidnight, AfterMidnight, Dawn, Day, Dusk

  import TimeOfDay.*

  enum TargetCircadianRhythmState:
    case Sleep, Wake

  import TargetCircadianRhythmState.*

  enum TargetDimmingAndColorTemperature(
      targetDimming: Double,
      targetColorTemperature: Int
  ):

    case Energize extends TargetDimmingAndColorTemperature(100d, 156)
    case Concentrate extends TargetDimmingAndColorTemperature(100d, 233)
    case Read extends TargetDimmingAndColorTemperature(100d, 346)
    case Relax extends TargetDimmingAndColorTemperature(56.3d, 447)

    def dimming: HueApi.Dimming =
      HueApi.Dimming(brightness = targetDimming)

    def colorTemperature: HueApi.ColorTemperature =
      HueApi.ColorTemperature(mirek = Some(targetColorTemperature))

  import TargetDimmingAndColorTemperature.*

  def decideTargetDimmingAndColorTemperature: RIO[
    ZoneId & sunrisesunset.SunriseSunsetCalculator & WakeTime & SleepTime,
    TargetDimmingAndColorTemperature
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
      if now.isBefore(civilSunrise) then AfterMidnight
      else if now.isBefore(officialSunrise) then Dawn
      else if now.isBefore(officialSunset) then Day
      else if now.isBefore(civilSunset) then Dusk
      else BeforeMidnight
    wakeTime <- ZIO.service[WakeTime]
    sleepTime <- ZIO.service[SleepTime]
    targetCircadianRhythmState =
      if now.isBefore(wakeTime.value) then Sleep
      else if now.isBefore(sleepTime.value) then Wake
      else Sleep
    _ <- ZIO.logInfo(
      logMessage(
        message = "Evaluated time of day",
        event = ast.Json.Obj(
          "now" -> ast.Json.Str(now.toString),
          "civilSunrise" -> ast.Json.Str(civilSunrise.toString),
          "officialSunrise" -> ast.Json.Str(officialSunrise.toString),
          "officialSunset" -> ast.Json.Str(officialSunset.toString),
          "civilSunset" -> ast.Json.Str(civilSunset.toString),
          "timeOfDay" -> ast.Json.Str(timeOfDay.toString),
          "targetCircadianRhythmState" -> ast.Json.Str(
            targetCircadianRhythmState.toString
          )
        )
      )
    )
    targetDimmingAndColorTemperature = (
      timeOfDay,
      targetCircadianRhythmState
    ) match
      case (AfterMidnight, Sleep)  => Relax
      case (AfterMidnight, Wake)   => Energize
      case (Dawn, _)               => Energize
      case (Day, _)                => Concentrate
      case (Dusk, _)               => Read
      case (BeforeMidnight, Wake)  => Read
      case (BeforeMidnight, Sleep) => Relax
  yield targetDimmingAndColorTemperature

  def toLocalTime(calendar: Calendar): RIO[ZoneId, LocalTime] = for
    zoneId <- ZIO.service[ZoneId]
    localTime = calendar.toInstant.atZone(zoneId).toLocalTime
  yield localTime

  def updateActiveLights(
      group0ResourceIdentifier: HueApi.ResourceIdentifier,
      currentDimming: Option[HueApi.Dimming],
      currentColorTemperature: Option[HueApi.ColorTemperature],
      targetDimmingAndColorTemperature: TargetDimmingAndColorTemperature
  ) =
    val targetDimming = targetDimmingAndColorTemperature.dimming
    val targetColorTemperature =
      targetDimmingAndColorTemperature.colorTemperature
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
