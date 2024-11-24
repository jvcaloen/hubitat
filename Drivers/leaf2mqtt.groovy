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
//

metadata {
   definition (name: "Nissan Leaf Battery", namespace: "jvcaloen", author: "Jean") {
       capability "Battery"
       capability "Outlet"
       capability "PresenceSensor" // present means connected to mqtt
       capability "Initialize" // button to (re)connect to mqtt
       capability "Refresh"   // provide button to disconnect from mqtt - client status doesn't seem to trigger here :( 
       
       attribute "charging", "string"
       
       singleThreaded: true // preventing more than one overlapping wake of the driver code for the same device
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
    interfaces.mqtt.connect(mqttUrl, "hubitat-leaf-driver", mqttUsername, mqttPassword)
    interfaces.mqtt.subscribe("leaf/battery/percentage")
    interfaces.mqtt.subscribe("leaf/battery/lastUpdatedDateTimeUtc")
    interfaces.mqtt.subscribe("leaf/battery/charging")
    keepalive()
    
    if (!interfaces.mqtt.isConnected()) { 
        runIn(60, "initialize") 
        log.debug "not connected, initialize runIn 60s planned"
    }
    log.debug "initialized()"
}

def parse(String message) {
    // log.debug "parse() received: ${message}"
    mapped = interfaces.mqtt.parseMessage(message)
    log.debug "parse() mapped: ${mapped}"
    if (mapped.topic == "leaf/battery/percentage") { 
        sendEvent(name:"battery", value: mapped.payload, unit: "%")
    }
    if (mapped.topic == "leaf/battery/lastUpdatedDateTimeUtc") {
        state.lastUpdatedDateTimeUtc = toDateTime(mapped.payload)
    }
    if (mapped.topic == "leaf/battery/charging") {
        sendEvent(name:"charging", value: mapped.payload)
        sendEvent(name:"switch", value: mapped.payload)
    }
    keepalive()
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
        sendEvent(name:"presence", value: "present", description: message, isStateChange: true)
    }
    
    else if (message == "Status: Disconnected via disconnect()"){
        log.debug "mqttClientStatus(): change to not present"
        sendEvent(name:"presence", value: "not present", description: message, isStateChange: true)
    }
    else 
    {
        
        if(!(interfaces.mqtt.isConnected())) {
            log.debug "mqttClientStatus(): change to not present"
            sendEvent(name:"presence", value: "not present", description: message, isStateChange: true)
         
            // pause + reconnect 
            runIn(60, "initialize")
            log.debug "initialize planned in 60s"
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
        interfaces.mqtt.publish("hubitat-leaf-driver/keepalive", (String)now())
        runIn(300, "keepalive")
    }
}
