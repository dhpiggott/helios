$version: "2"

namespace helios.hue_api

use alloy#simpleRestJson

@httpApiKeyAuth(name: "hue-application-key", in: "header")
@simpleRestJson
service HueEventApi {
  operations: [
    GetEvents
  ]
}

@documentation("Per https://developers.meethue.com/develop/hue-api-v2/core-concepts/#events.")
@readonly
@http(method: "GET", uri: "/eventstream/clip/v2", code: 200)
operation GetEvents {
  output := {
    @httpPayload
    events: Event
  }
}

@streaming
union Event {
  update: Update
  add: Add
  delete: Delete
  error: Error
}

structure Update {
  @required
  id: String,
  @required
  creationtime: Timestamp
  @required
  data: DataList
}

structure Add {
  @required
  id: String,
  @required
  creationtime: Timestamp
  @required
  data: DataList
}

structure Delete {
  @required
  id: String,
  @required
  creationtime: Timestamp
  @required
  data: DataList
}

structure Error {
  @required
  id: String,
  @required
  creationtime: Timestamp
  // TODO: Model errors
}

list DataList {
  member: Data
}

union Data {
  light: Light
  // TODO
}
