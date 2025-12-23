// Hubitat driver for reading the entry logs of ESP-RFID mqtt feed
// 
//
// Requirements
// - Hubitat hub (duh ;) )
// - mqtt bus (eg. mosquitto container https://hub.docker.com/_/eclipse-mosquitto/)
// - esp-rfid access control
// - this driver
// - enter mqtt details in the device preferences
//
// Welcome to copy and use at your own risk. 
//
// 2024-07-04 - v1 - Jean van Caloen simple event generation from access logs
//

metadata {
   definition (name: "ESP-RFID mqtt access", namespace: "jvcaloen", author: "Jean") {
      capability "PresenceSensor" // present means connected to mqtt
      capability "Initialize" // button to (re)connect to mqtt
      capability "Refresh"   // provide button to disconnect from mqtt - client status doesn't seem to trigger here :( 
       
      attribute "access", "string"
      attribute "lastAccessTimeUtc", "string"
   }

   preferences {
       input "mqttUrl", "text", title: "MQTT bus URI ie tcp://10.0.0.1:1883", required: true
       input "mqttUsername", "text", title: "MQTT user name"
       input "mqttPassword", "text", title: "MQTT password"
   }
}

def initialize() {
    state.clear()
    // Connect to MQTT broker - doc: https://docs2.hubitat.com/en/developer/interfaces/mqtt-interface
    interfaces.mqtt.connect(mqttUrl, "hubitat-esp-rfid", mqttUsername, mqttPassword)
    interfaces.mqtt.subscribe("rfid-voordeur/sync")
    interfaces.mqtt.subscribe("rfid-voordeur/send")
    keepalive()
    log.debug "initialized()"
}

def parse(String message) {
    // log.debug "parse() received: ${message}"
    mapped = interfaces.mqtt.parseMessage(message)
    //log.debug "parse() mapped: ${mapped}"
    if (mapped.topic == "rfid-voordeur/send") { 
        mappedLog = parseJson(mapped.payload)
        sendEvent(name: mappedLog.type, value: mappedLog.username, isStateChange: true)
        state.lastMessage = "${mappedLog.type}: ${mappedLog.username}"
        state.lastUsername = mappedLog.username
        long timeInSeconds = mappedLog.time
        state.lastAccessTimeUtc = new Date(timeInSeconds * 1000)
        sendEvent(name: lastAccessTimeUtc, value: state.lastAccessTimeUtc)
    }
    if (mapped.topic == "rfid-voordeur/sync") {
        long timeInSeconds = parseJson(mapped.payload).time
        state.lastUpdatedDateTimeUtc = new Date(timeInSeconds * 1000)
    }
}

def deviceNotification(String text) {
    log.debug "deviceNotification(): ${text}"
}

def installed() {
    if (!interfaces.mqtt.isConnected()) { initialize() }
    log.debug "installed()"
}

def updated() {
   if (!interfaces.mqtt.isConnected()) { initialize() }
   log.debug "updated()"
}

def mqttClientStatus(String message) {
    log.debug "mqttClientStatus() message: ${message}"

    if (message == "Status: Connection succeeded") { 
        log.debug "mqttClientStatus(): change to present"
        sendEvent(name:"presence", value: "present", isStateChange: true)
    }
    else if (message != "Status: Connection succeeded") { 
        log.debug "mqttClientStatus(): change to not present"
        sendEvent(name:"presence", value: "not present", isStateChange: true)
        if(!interfaces.mqtt.isConnected()) { 
            runIn(60, "initialize")
            log.debug "initialize runIn 60s planned"
        }
    }
}

def refresh() {
    log.debug "refresh(): disconnect from mqtt"
    disconnect()
}

def disconnect() {
    log.debug "disconnect()"
    interfaces.mqtt.disconnect()
    mqttClientStatus("Status: Disconnected via disconnect()");
}

void keepalive() {
    if (!interfaces.mqtt.isConnected()) { initialize() }
    if (interfaces.mqtt.isConnected()) {
        interfaces.mqtt.publish("hubitat-esp-rfid/keepalive", (String)now())
    }
    runIn(300, "keepalive")
}
