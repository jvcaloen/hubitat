// Hubitat driver for reading the battery status from Nissan Leaf 
// this could be easily adapted to any vehicle that is compatible with the komereon platform and leaf2mqtt
//
// Requirements
// - Hubitat hub (duh ;) )
// - mqtt bus (eg. mosquitto container https://hub.docker.com/_/eclipse-mosquitto/)
// - leaf2mqtt container running (see https://github.com/kamiKAC/leaf2mqtt) - leave default "leaf" main topic
// - this driver
// - enter mqtt details in 
//
// Welcome to copy and use at your own risk. 
//
// 2024-07-03 - v1 - Jean van Caloen simple battery percentage & last update time in state
// 2024-11-24 - v2 - Jean van Caloen added reconnect and keepalive to avoid mqtt connection dropping
// 2025-09-21 - v3 - Jean van Caloen updated mqtt reconnect logic and status reporting

metadata {
  definition(name: "Nissan Leaf Battery", namespace: "jvcaloen", author: "Jean") {
    capability "Battery"
    capability "Outlet"
    capability "PresenceSensor"
    capability "Initialize"
    capability "Refresh"

    attribute "charging", "string"
    attribute "status", "string"
    attribute "lastSuccessfulBatteryUpdate", "string"
  }
  preferences {
    input "mqttUrl", "text", title: "MQTT URI (e.g. tcp://10.0.0.1:1883)", required: true
    input "mqttUsername", "text", title: "MQTT Username"
    input "mqttPassword", "text", title: "MQTT Password"
  }
}

def installed() {
  initialize()
}

def updated() {
  initialize()
}

def initialize() {
  unschedule()
  try {
    interfaces.mqtt.disconnect()
  } catch (e) {
    log.warn "MQTT disconnect failed: ${e}"
  }

  def clientId = "leaf-${device.id}"
  try {
    interfaces.mqtt.connect(mqttUrl, clientId, mqttUsername, mqttPassword)
    interfaces.mqtt.subscribe("leaf/#")
    sendEvent(name: "status", value: "connecting")
  } catch (e) {
    log.error "MQTT connect error: ${e}"
    scheduleReconnect(1)
  }
}

def refresh() {
  interfaces.mqtt.disconnect()
  sendEvent(name: "status", value: "disconnected")
  sendEvent(name: "presence", value: "not present")
}

def mqttClientStatus(message) {
  log.debug "MQTT status: ${message}"
  if (message == "Connection lost") {
    sendEvent(name: "status", value: "disconnected")
    sendEvent(name: "presence", value: "not present")
    sendEvent(name: "battery", value: -1, unit: "%")
    scheduleReconnect((state.reconnectAttempt ?: 1))
  } else if (message == "Connected") {
    sendEvent(name: "status", value: "connected")
    sendEvent(name: "presence", value: "present")
    state.reconnectAttempt = 0
  }
}

def scheduleReconnect(attempt) {
  def delay = Math.min(60 * attempt, 600)
  log.warn "Scheduling reconnect in ${delay}s (attempt ${attempt})"
  runIn(delay, "retryInitialize")
  state.reconnectAttempt = attempt + 1
}

def retryInitialize() {
  if (!interfaces.mqtt.isConnected()) {
    log.warn "Retrying MQTT connection..."
    initialize()
  } else {
    log.info "MQTT already connected"
    state.reconnectAttempt = 0
  }
}

def parse(String msg) {
  def parts = msg.split(" ", 2)
  def topic = parts[0].trim()
  def payload = parts.length > 1 ? parts[1].trim() : ""

  switch (topic) {
    case "leaf/battery/percentage":
      def value = payload.isNumber() ? payload.toInteger() : -1
      sendEvent(name: "battery", value: value, unit: "%")
      state.lastSuccessfulBatteryUpdate = now()
      sendEvent(name: "lastSuccessfulBatteryUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
      break

    case "leaf/charging":
      sendEvent(name: "charging", value: payload)
      break

    default:
      log.debug "Unhandled topic: ${topic} -> ${payload}"
  }
}

def keepalive() {
  def maxAge = 15 * 60 * 1000
  if (state.lastSuccessfulBatteryUpdate && (now() - state.lastSuccessfulBatteryUpdate > maxAge)) {
    log.warn "Battery data stale, setting battery to -1%"
    sendEvent(name: "battery", value: -1, unit: "%")
  }
  if (!interfaces.mqtt.isConnected()) {
    log.warn "MQTT disconnected in keepalive, triggering reconnect"
    scheduleReconnect((state.reconnectAttempt ?: 1))
  }
}
