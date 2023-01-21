$version: "2"

namespace helios.hue_api

use alloy#simpleRestJson

@httpApiKeyAuth(name: "hue-application-key", in: "header")
@simpleRestJson
service HueRestApi {
  operations: [
    GetBridgeHome,
    PutGroupedLight
  ]
}

@documentation("Per https://developers.meethue.com/develop/hue-api-v2/api-reference/#resource_bridge_home_get.")
@readonly
@http(method: "GET", uri: "/clip/v2/resource/bridge_home", code: 200)
// TODO: Model errors
operation GetBridgeHome {
  output := {
    @required
    data: BridgeHomes
  }
}

@documentation("Per https://developers.meethue.com/develop/hue-api-v2/api-reference/#resource_grouped_light_put.")
@idempotent
@http(method: "PUT", uri: "/clip/v2/resource/grouped_light/{id}", code: 200)
// TODO: Model errors
operation PutGroupedLight {
  input := {
    @required
    @httpLabel
    id: String,
    on: On,
    dimming: Dimming,
    @jsonName("color_temperature")
    colorTemperature: ColorTemperature
  },
  output := {
    @required
    data: ResourceIdentifierList
  }
}
