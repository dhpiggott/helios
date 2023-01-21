$version: "2"

namespace helios.hue_api

list ResourceIdentifierList {
  member: ResourceIdentifier
}

structure ResourceIdentifier {
  @required
  rid: String,
  @required
  rtype: String
}

structure On {
  @required
  on: Boolean
}

structure Dimming {
  @required
  brightness: Double
}

structure ColorTemperature {
  mirek: Integer
}

list BridgeHomes {
  member: BridgeHome
}

structure Light {
  @required
  id: String,
  on: On,
  dimming: Dimming,
  @jsonName("color_temperature")
  colorTemperature: ColorTemperature
}

structure BridgeHome {
  type: String,
  @required
  id: String,
  @required
  children: ResourceIdentifierList
  @required
  services: ResourceIdentifierList
}
