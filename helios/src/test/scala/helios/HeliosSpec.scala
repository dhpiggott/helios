package helios

import zio.*
import zio.test.Assertion.*
import zio.test.*

import java.time.Instant

object HeliosSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Throwable] =
    import Helios.TargetDimmingAndColorTemperature.*
    suite("HeliosSpec")(
      suite("January")(
        test("2022-01-23T00:00:00.00+00:00", Relax),
        test("2022-01-23T01:00:00.00+00:00", Relax),
        test("2022-01-23T02:00:00.00+00:00", Relax),
        test("2022-01-23T03:00:00.00+00:00", Relax),
        test("2022-01-23T04:00:00.00+00:00", Relax),
        test("2022-01-23T05:00:00.00+00:00", Relax),
        test("2022-01-23T06:00:00.00+00:00", Energize),
        test("2022-01-23T07:00:00.00+00:00", Energize),
        test("2022-01-23T08:00:00.00+00:00", Energize),
        test("2022-01-23T09:00:00.00+00:00", Concentrate),
        test("2022-01-23T10:00:00.00+00:00", Concentrate),
        test("2022-01-23T11:00:00.00+00:00", Concentrate),
        test("2022-01-23T12:00:00.00+00:00", Concentrate),
        test("2022-01-23T13:00:00.00+00:00", Concentrate),
        test("2022-01-23T14:00:00.00+00:00", Concentrate),
        test("2022-01-23T15:00:00.00+00:00", Concentrate),
        test("2022-01-23T16:00:00.00+00:00", Concentrate),
        test("2022-01-23T17:00:00.00+00:00", Read),
        test("2022-01-23T18:00:00.00+00:00", Read),
        test("2022-01-23T19:00:00.00+00:00", Read),
        test("2022-01-23T20:00:00.00+00:00", Read),
        test("2022-01-23T21:00:00.00+00:00", Read),
        test("2022-01-23T22:00:00.00+00:00", Relax),
        test("2022-01-23T23:00:00.00+00:00", Relax)
      ),
      suite("April")(
        test("2022-04-23T00:00:00.00+01:00", Relax),
        test("2022-04-23T01:00:00.00+01:00", Relax),
        test("2022-04-23T02:00:00.00+01:00", Relax),
        test("2022-04-23T03:00:00.00+01:00", Relax),
        test("2022-04-23T04:00:00.00+01:00", Relax),
        test("2022-04-23T05:00:00.00+01:00", Relax),
        test("2022-04-23T06:00:00.00+01:00", Concentrate),
        test("2022-04-23T07:00:00.00+01:00", Concentrate),
        test("2022-04-23T08:00:00.00+01:00", Concentrate),
        test("2022-04-23T09:00:00.00+01:00", Concentrate),
        test("2022-04-23T10:00:00.00+01:00", Concentrate),
        test("2022-04-23T11:00:00.00+01:00", Concentrate),
        test("2022-04-23T12:00:00.00+01:00", Concentrate),
        test("2022-04-23T13:00:00.00+01:00", Concentrate),
        test("2022-04-23T14:00:00.00+01:00", Concentrate),
        test("2022-04-23T15:00:00.00+01:00", Concentrate),
        test("2022-04-23T16:00:00.00+01:00", Concentrate),
        test("2022-04-23T17:00:00.00+01:00", Concentrate),
        test("2022-04-23T18:00:00.00+01:00", Concentrate),
        test("2022-04-23T19:00:00.00+01:00", Concentrate),
        test("2022-04-23T20:00:00.00+01:00", Concentrate),
        test("2022-04-23T21:00:00.00+01:00", Read),
        test("2022-04-23T22:00:00.00+01:00", Relax),
        test("2022-04-23T23:00:00.00+01:00", Relax)
      ),
      suite("July")(
        test("2022-07-23T00:00:00.00+01:00", Relax),
        test("2022-07-23T01:00:00.00+01:00", Relax),
        test("2022-07-23T02:00:00.00+01:00", Relax),
        test("2022-07-23T03:00:00.00+01:00", Relax),
        test("2022-07-23T04:00:00.00+01:00", Relax),
        test("2022-07-23T05:00:00.00+01:00", Energize),
        test("2022-07-23T06:00:00.00+01:00", Concentrate),
        test("2022-07-23T07:00:00.00+01:00", Concentrate),
        test("2022-07-23T08:00:00.00+01:00", Concentrate),
        test("2022-07-23T09:00:00.00+01:00", Concentrate),
        test("2022-07-23T10:00:00.00+01:00", Concentrate),
        test("2022-07-23T11:00:00.00+01:00", Concentrate),
        test("2022-07-23T12:00:00.00+01:00", Concentrate),
        test("2022-07-23T13:00:00.00+01:00", Concentrate),
        test("2022-07-23T14:00:00.00+01:00", Concentrate),
        test("2022-07-23T15:00:00.00+01:00", Concentrate),
        test("2022-07-23T16:00:00.00+01:00", Concentrate),
        test("2022-07-23T17:00:00.00+01:00", Concentrate),
        test("2022-07-23T18:00:00.00+01:00", Concentrate),
        test("2022-07-23T19:00:00.00+01:00", Concentrate),
        test("2022-07-23T20:00:00.00+01:00", Concentrate),
        test("2022-07-23T21:00:00.00+01:00", Concentrate),
        test("2022-07-23T22:00:00.00+01:00", Read),
        test("2022-07-23T23:00:00.00+01:00", Relax)
      ),
      suite("October")(
        test("2022-10-23T00:00:00.00+01:00", Relax),
        test("2022-10-23T01:00:00.00+01:00", Relax),
        test("2022-10-23T02:00:00.00+01:00", Relax),
        test("2022-10-23T03:00:00.00+01:00", Relax),
        test("2022-10-23T04:00:00.00+01:00", Relax),
        test("2022-10-23T05:00:00.00+01:00", Relax),
        test("2022-10-23T06:00:00.00+01:00", Energize),
        test("2022-10-23T07:00:00.00+01:00", Energize),
        test("2022-10-23T08:00:00.00+01:00", Concentrate),
        test("2022-10-23T09:00:00.00+01:00", Concentrate),
        test("2022-10-23T10:00:00.00+01:00", Concentrate),
        test("2022-10-23T11:00:00.00+01:00", Concentrate),
        test("2022-10-23T12:00:00.00+01:00", Concentrate),
        test("2022-10-23T13:00:00.00+01:00", Concentrate),
        test("2022-10-23T14:00:00.00+01:00", Concentrate),
        test("2022-10-23T15:00:00.00+01:00", Concentrate),
        test("2022-10-23T16:00:00.00+01:00", Concentrate),
        test("2022-10-23T17:00:00.00+01:00", Concentrate),
        test("2022-10-23T18:00:00.00+01:00", Read),
        test("2022-10-23T19:00:00.00+01:00", Read),
        test("2022-10-23T20:00:00.00+01:00", Read),
        test("2022-10-23T21:00:00.00+01:00", Read),
        test("2022-10-23T22:00:00.00+01:00", Relax),
        test("2022-10-23T23:00:00.00+01:00", Relax)
      )
    ) @@ TestAspect.silentLogging

  def test(
      instant: String,
      expectedValue: Helios.TargetDimmingAndColorTemperature
  ): Spec[Any, Throwable] =
    test(
      s"decideTargetBrightnessAndMirekValues chooses ${expectedValue} for $instant"
    )(
      for
        _ <- TestSystem.putEnv("TIME_ZONE", "Europe/London")
        _ <- TestSystem.putEnv("HOME_LATITUDE", "53.423528")
        _ <- TestSystem.putEnv("HOME_LONGITUDE", "-2.2468873")
        _ <- TestSystem.putEnv("WAKE_TIME", "06:00")
        _ <- TestSystem.putEnv("SLEEP_TIME", "22:00")
        _ <- TestClock.setTime(Instant.parse(instant))
        targetBrightnessAndMirekValues <-
          Helios.decideTargetDimmingAndColorTemperature.provide(
            Helios.zoneIdLayer,
            Helios.sunriseSunsetCalculatorLayer,
            Helios.wakeTimeLayer,
            Helios.sleepTimeLayer
          )
      yield assert(targetBrightnessAndMirekValues)(equalTo(expectedValue))
    )
