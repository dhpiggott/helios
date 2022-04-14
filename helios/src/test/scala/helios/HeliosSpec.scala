package helios

import zio.*
import zio.test.Assertion.*
import zio.test.ZIOSpecDefault
import zio.test.*

object HeliosSpec extends ZIOSpecDefault:

  def spec = suite("HeliosSpec")(
    test("decideTargetBrightnessAndMirekValues chooses relax for midnight")(
      for
        _ <- TestSystem.putEnv("TIME_ZONE", "Europe/London")
        _ <- TestSystem.putEnv("HOME_LATITUDE", "53.423528")
        _ <- TestSystem.putEnv("HOME_LONGITUDE", "-2.2468873")
        targetBrightnessAndMirekValues <-
          Helios.decideTargetBrightnessAndMirekValues
            .provideSome[Annotations & Live](
              Helios.zoneIdLayer,
              Helios.sunriseSunsetCalculatorLayer,
              TestClock.default,
              TestConsole.debug
            )
      yield assert(targetBrightnessAndMirekValues)(equalTo(Helios.relax))
    )
  )
