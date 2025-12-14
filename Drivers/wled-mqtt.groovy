// Hubitat driver for controlling WLED via MQTT
//
// Requirements:
// - Hubitat hub
// - MQTT broker (e.g., Mosquitto)
// - WLED devices configured for MQTT (see https://kno.wled.ge/interfaces/mqtt/)
// - This driver
//
// Features:
// - Switch capability to turn all or a specific WLED device on/off via MQTT
// - Optionally control a single WLED device by name (set in preferences)
// - Status updates from WLED MQTT status topic
// - Automatic reconnect and keepalive for MQTT
//
// 2025-12-14 - v1 - Jean van Caloen WLED MQTT switch driver

metadata {
  definition(name: "WLED MQTT Switch", namespace: "jvcaloen", author: "Jean") {
    capability "Switch"
    capability "Initialize"
    capability "Refresh"
    capability "Configuration" // Enables built-in Configure button

    command "resetAttributes"

    attribute "status", "string"
    attribute "lastSuccessfulUpdate", "string"
    attribute "presence", "string"
  }
  preferences {
    input "mqttUrl", "text", title: "MQTT URI", required: true
    input "mqttUsername", "text", title: "MQTT Username"
    input "mqttPassword", "text", title: "MQTT Password"
    input "wledDevice", "text", title: "WLED Device Name (optional, for single device control)", required: false
  }
}

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
  unschedule()
  sendEvent(name: "status", value: "initializing")
  sendEvent(name: "presence", value: "unknown")
  try { interfaces.mqtt.disconnect() } catch (e) { log.warn "MQTT disconnect failed: ${e}" }
  def clientId = "wled-${device.id}"
  try {
    interfaces.mqtt.connect(mqttUrl, clientId, mqttUsername, mqttPassword)
    interfaces.mqtt.unsubscribe("wled/#")
    if (wledDevice?.trim()) {
      interfaces.mqtt.subscribe("wled/${wledDevice.trim()}/status")
      interfaces.mqtt.subscribe("wled/${wledDevice.trim()}/lwt")
    } else {
      interfaces.mqtt.subscribe("wled/+/status")
      interfaces.mqtt.subscribe("wled/+/lwt")
    }
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
    interfaces.mqtt.connect(mqttUrl, "wled-${device.id}", mqttUsername, mqttPassword)
    interfaces.mqtt.unsubscribe("wled/#")
    if (wledDevice?.trim()) {
      interfaces.mqtt.subscribe("wled/${wledDevice.trim()}/status")
      interfaces.mqtt.subscribe("wled/${wledDevice.trim()}/lwt")
    } else {
      interfaces.mqtt.subscribe("wled/+/status")
      interfaces.mqtt.subscribe("wled/+/lwt")
    }
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
    sendEvent(name: "switch", value: "off")
  } else if (message == "Connected") {
    sendEvent(name: "status", value: "connected")
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
  def topic = mapped.topic
  def payload = mapped.payload?.toLowerCase()
  def isPresence = false
  def isOnline = false
  def isStatus = false
  // Handle status (on/off) for switch
  if (topic == getWledTopic("status")) {
    def value = (payload == "on") ? "on" : "off"
    sendEvent(name: "switch", value: value)
    sendEvent(name: "lastSuccessfulUpdate", value: new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
    isStatus = true
  }
  // Handle presence via LWT (Last Will and Testament) or status
  if (wledDevice?.trim()) {
    // Single device: check its lwt or status
    if (topic == "wled/${wledDevice.trim()}/lwt") {
      isPresence = true
      isOnline = (payload == "online")
    } else if (topic == "wled/${wledDevice.trim()}/status") {
      isPresence = true
      isOnline = (payload == "on" || payload == "off")
    }
  } else {
    // All devices: if any device is online, set present
    if (topic ==~ /wled\/[^\\/]+\/lwt/) {
      if (payload == "online") {
        state.anyWledOnline = true
        isPresence = true
        isOnline = true
      } else if (payload == "offline") {
        // Check if any other device is still online
        state.anyWledOnline = false // will be set true if another online comes in
        isPresence = true
        isOnline = false
      }
    } else if (topic ==~ /wled\/[^\\/]+\/status/ && (payload == "on" || payload == "off")) {
      state.anyWledOnline = true
      isPresence = true
      isOnline = true
    }
  }
  // Set presence attribute
  if (isPresence) {
    if (wledDevice?.trim()) {
      sendEvent(name: "presence", value: isOnline ? "present" : "not present")
    } else {
      sendEvent(name: "presence", value: (state.anyWledOnline ? "present" : "not present"))
    }
  }
  if (!isStatus && !isPresence) {
    log.warn "Unhandled topic: ${mapped.topic}"
  }
  keepalive()
}

// Switch on/off all WLEDs or a specific device via MQTT
def on() {
  log.info "Turning WLED ON via MQTT (all or specific device)"
  def topic = getWledTopic("control")
  interfaces.mqtt.publish(topic, "ON")
  sendEvent(name: "switch", value: "on")
}

def off() {
  log.info "Turning WLED OFF via MQTT (all or specific device)"
  def topic = getWledTopic("control")
  interfaces.mqtt.publish(topic, "OFF")
  sendEvent(name: "switch", value: "off")
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
  sendEvent(name: "status", value: "")
  sendEvent(name: "switch", value: "off")
  sendEvent(name: "lastSuccessfulUpdate", value: "")
}

def configure() {
  log.warn "Configure button pressed"
  resetAttributes()
}

// Helper to get the correct WLED topic for status or control
def getWledTopic(type) {
  // If a device is specified, use its topic, else use the all-devices topic
  def base = wledDevice?.trim() ? "wled/${wledDevice.trim()}" : "wled/all"
  if (type == "status") return "${base}/status"
  if (type == "control") return "${base}"
  return base
}
