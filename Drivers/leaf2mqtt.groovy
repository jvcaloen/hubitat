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
// 2025-09-21 - v3 - Jean van Caloen updated mqtt reconnect logic and status reporting + refactor(copilot)

metadata {
  definition(name: "Nissan Leaf Battery", namespace: "jvcaloen", author: "Jean") {
    capability "Battery"
    capability "Outlet"
    capability "PresenceSensor"
    capability "Initialize"
    capability "Refresh"
    capability "Configuration" // Enables built-in Configure button

    command "resetAttributes"

    attribute "charging", "string"
    attribute "status", "string"
    attribute "lastSuccessfulBatteryUpdate", "string"
  }
  preferences {
    input "mqttUrl", "text", title: "MQTT URI", required: true
    input "mqttUsername", "text", title: "MQTT Username"
    input "mqttPassword", "text", title: "MQTT Password"
  }
}

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
  unschedule()
  sendEvent(name: "status", value: "initializing")
  sendEvent(name: "presence", value: "unknown")
  try { interfaces.mqtt.disconnect() } catch (e) { log.warn "MQTT disconnect failed: ${e}" }
  def clientId = "leaf-${device.id}"
  try {
    interfaces.mqtt.connect(mqttUrl, clientId, mqttUsername, mqttPassword)
    interfaces.mqtt.unsubscribe("leaf/#")
    interfaces.mqtt.subscribe("leaf/battery/percentage")
    interfaces.mqtt.subscribe("leaf/battery/charging")
    interfaces.mqtt.subscribe("leaf/battery/lastUpdatedDateTimeUtc")
    mqttClientStatus("Connected")
    keepalive()
  } catch (e) {
    log.error "MQTT connect error: ${e}"
    mqttClientStatus("Connection lost")
    scheduleReconnect(1)
  }
}

def refresh() {
  log.info "Manual refresh triggered"
  try {
    interfaces.mqtt.disconnect()
    interfaces.mqtt.connect(mqttUrl, "leaf-${device.id}", mqttUsername, mqttPassword)
    interfaces.mqtt.unsubscribe("leaf/#")
    interfaces.mqtt.subscribe("leaf/battery/percentage")
    interfaces.mqtt.subscribe("leaf/battery/charging")
    interfaces.mqtt.subscribe("leaf/battery/lastUpdatedDateTimeUtc")
    mqttClientStatus("Connected")
    keepalive()
  } catch (e) {
    log.error "Refresh failed: ${e}"
    mqttClientStatus("Connection lost")
    scheduleReconnect(1)
  }
}

def mqttClientStatus(message) {
  log.debug "MQTT status: ${message}"
  if (message == "Connection lost") {
    sendEvent(name: "status", value: "disconnected")
    sendEvent(name: "presence", value: "not present")
    sendEvent(name: "battery", value: -1, unit: "%")
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

def parse(String message) {
  def mapped = interfaces.mqtt.parseMessage(message)
  log.debug "parse() topic: ${mapped.topic}, payload: ${mapped.payload}"

  switch (mapped.topic) {
    case "leaf/battery/percentage":
      sendEvent(name: "battery", value: mapped.payload, unit: "%")
      sendEvent(name: "lastSuccessfulBatteryUpdate", value: new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
      break
    case "leaf/battery/charging":
      sendEvent(name: "charging", value: mapped.payload)
      sendEvent(name: "switch", value: mapped.payload)
      break
    case "leaf/battery/lastUpdatedDateTimeUtc":
      sendEvent(name: "lastSuccessfulBatteryUpdate", value: mapped.payload)
      break
    default:
      log.warn "Unhandled topic: ${mapped.topic}"
  }

  keepalive()
}

def keepalive() {
  if (!interfaces.mqtt.isConnected()) {
    mqttClientStatus("Connection lost")
    scheduleReconnect((state.reconnectAttempt ?: 1))
  } else {
    runIn(300, "keepalive")
  }
}

def resetAttributes() {
  log.warn "Resetting declared attributes..."
  sendEvent(name: "charging", value: "")
  sendEvent(name: "status", value: "")
  sendEvent(name: "presence", value: "")
  sendEvent(name: "lastSuccessfulBatteryUpdate", value: "")
  sendEvent(name: "battery", value: -1, unit: "%")
}

def configure() {
  log.warn "Configure button pressed — clearing topic_"
  sendEvent(name: "topic_", value: "")
}
