package helios

import zio.*
import zio.test.Assertion.*
import zio.test.*
import zio.test.environment.*

object HeliosSpec extends DefaultRunnableSpec:

  def spec = suite("HeliosSpec")(
    testM("targetBrightnessAndMirekValues chooses relax for midnight")(
      for
        _ <- TestSystem.putEnv("TIME_ZONE", "Europe/London")
        _ <- TestSystem.putEnv("HOME_LATITUDE", "53.423528")
        _ <- TestSystem.putEnv("HOME_LONGITUDE", "-2.2468873")
        targetBrightnessAndMirekValues <- Helios.targetBrightnessAndMirekValues
          .provideCustomLayer(
            Helios.zoneIdLayer ++ Helios.sunriseSunsetCalculatorLayer
          )
      yield assert(targetBrightnessAndMirekValues)(equalTo(Helios.relax))
    )
  )
