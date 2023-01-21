$version: "2"
namespace helios.hueapi

structure BridgeHome {
  type: String,
  @required
  id: String,
  @required
  children: ResourceIdentifierList
  @required
  services: ResourceIdentifierList
}

structure Light {
  @required
  id: String,
  on: On,
  dimming: Dimming,
  colorTemperature: ColorTemperature
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

list ResourceIdentifierList {
  member: ResourceIdentifier
}

structure ResourceIdentifier {
  @required
  rid: String,
  @required
  rtype: String
}
