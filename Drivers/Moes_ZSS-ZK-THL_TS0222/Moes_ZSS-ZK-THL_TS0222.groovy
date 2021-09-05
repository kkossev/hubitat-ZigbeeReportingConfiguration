/*
* Moes ZSS-ZK-THL illuminance, temperature, humidity sensor model TS0222 (_TYZB01_kvwjujy9) driver for Hubitat 
*
* Description:
* Proceses illuminance, temperature, humidity and battery level
*
* Information:
* https://www.moeshouse.com/collections/zigbee/products/zigbee-smart-brightness-thermometer-real-time-light-sensitive-temperature-and-humidity-detector 
*
* Credits: some code sections borrowed from WooBooung, chirpy 
*
* Licensing:
* Copyright 2021 Krassimir Kossev.
* Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
* in compliance with the License. You may obtain a copy of the License at: http://www.apache.org/licenses/LICENSE-2.0
* Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
* on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
* for the specific language governing permissions and limitations under the License.
*
* Version Control:
* 1.0.0  2021-09-05 kkossev            Initial version
*
*/
import hubitat.zigbee.zcl.DataType
import groovy.json.JsonOutput
metadata {
    definition (name: "Moes ZSS-ZK-THL sensor (TS0222)", namespace: "kkossev", author: "Krassimir Kossev", importUrl: "https://raw.githubusercontent.com/kkossev/Hubitat/main/Moes_ZSS-ZK-THL_TS0222.groovy") {
        capability "Configuration"
        capability "Battery"
        capability "Refresh"
        capability "IlluminanceMeasurement"
		capability "RelativeHumidityMeasurement"
		capability "TemperatureMeasurement"
        capability "Sensor"

        fingerprint profileId: "0104", deviceId: "0106", inClusters: "0000,0001,0400,0402,0405", outClusters: "0019", manufacturer: "_TYZB01_kvwjujy9", model: "TS0222", deviceJoinName: "Moes ZSS-ZK-THL illuminance temperature humidity sensor"
    }

    preferences {
        input ("logEnable", "bool", title: "Enable debug logging", defaultValue: false)
        input ("txtEnable", "bool", title: "Enable description text logging", defaultValue: true)
        input ("tempOffset", "number", title: "Temperature offset", description: "Select how many degrees to adjust the temperature.", range: "-100..100", displayDuringSetup: false)
        input ("humidityOffset", "number", title: "Humidity offset", description: "Enter a percentage to adjust the humidity.", range: "*..*", displayDuringSetup: false)
    }
}

def parse(String description) {
    if (logEnable) log.debug "description: $description"

    Map descMap = zigbee.parseDescriptionAsMap(description)

    if ( true ) {
        if (descMap.clusterInt == 0x0001 && descMap.commandInt != 0x07 && descMap?.value) {
            if (descMap.attrInt == 0x0021) {
                getBatteryPercentageResult(Integer.parseInt(descMap.value,16))
            } else {
                getBatteryResult(Integer.parseInt(descMap.value, 16))
            }
        } 
        // humidity
        else if (descMap.cluster == "0405" && descMap.attrId == "0000") {
				def rawValue = Integer.parseInt(descMap.value,16) / 100
                rawValue = rawValue + ((humidityOffset ?: 0) as float)
                rawValue =  ((float)rawValue).trunc(1)
            
				if (rawValue > 100 || rawValue < 0){
					log.warn "$device.displayName Ignored humidity value: $rawValue"
				} else {
					sendEvent("name": "humidity", "value": rawValue, "unit": "%", isStateChange: true)
					if (txtEnable) log.info "$device.displayName humidity changed to $rawValue"
				}
			}
        // illuminance
		else if (descMap.cluster == "0400" && descMap.attrId == "0000") {
				def rawEncoding = Integer.parseInt(descMap.encoding, 16)
				def rawLux = Integer.parseInt(descMap.value,16)
				def lux = rawLux > 0 ? Math.round(Math.pow(10,(rawLux/10000))) : 0
				if (getDataValue("model") == "lumi.sensor_motion.aq2") lux = rawLux
				sendEvent("name": "illuminance", "value": lux, "unit": "lux", isStateChange: true)
				if (txtEnable) log.info "$device.displayName illuminance changed to $lux"
			}
        // temperature
		else if (descMap.cluster == "0402" && descMap.attrId == "0000") {
				def rawValue = hexStrToSignedInt(descMap.value) / 100
                rawValue = rawValue +  ((tempOffset ?: 0) as float)
                rawValue =  Math.round((rawValue - 0.05) * 10) / 10

				def Scale = location.temperatureScale
				if (Scale == "F") rawValue = (rawValue * 1.8) + 32
				if (temperatureOffset == null) temperatureOffset = "0"
				def offsetrawValue = (rawValue  + Float.valueOf(temperatureOffset))
				rawValue = offsetrawValue
				if (rawValue > 200 || rawValue < -200){
					log.warn "$device.displayName Ignored temperature value: $rawValue\u00B0"+Scale
				} else {
					sendEvent("name": "temperature", "value": rawValue, "unit": "\u00B0"+Scale, isStateChange: true)
					if (txtEnable) log.info "$device.displayName temperature changed to $rawValue\u00B0"+Scale
				}
			}
        
        else if (descMap?.clusterInt == 0x0402 && descMap.commandInt == 0x07) {
            if (descMap.data[0] == "00") {
                if (logEnable) log.debug "TEMP REPORTING CONFIG RESPONSE: $descMap"
                sendEvent(name: "checkInterval", value: 60 * 12, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
            } else {
                log.warn "TEMP REPORTING CONFIG FAILED- error code: ${descMap.data[0]}"
            }
        }
        else {
            if (logEnable) log.warn "UNPROCESSED descMap: ${descMap}"
            Map map = zigbee.getEvent(description)
            if (logEnable) log.debug "zigbee.getEvent: ${map}"
        }
    }
   // refresh()
}



def installed() {
	if (logEnable) log.debug "installed"
    configure()
}

def updated() {
	if (logEnable) log.debug "updated"
    configure()
}

def getBatteryPercentageResult(rawValue) {
    if (logEnable) log.debug "Battery Percentage rawValue = ${rawValue} -> ${rawValue / 2}%"
    def result = [:]

    if (0 <= rawValue && rawValue <= 200) {
        result.name = 'battery'
        result.translatable = true
        result.value = Math.round(rawValue / 2)
        result.descriptionText = "${device.displayName} battery was ${result.value}%"
        result.isStateChange = true
        sendEvent(result)
    }

    return result
}

private Map getBatteryResult(rawValue) {
    if (logEnable) log.debug 'Battery'
    def linkText = getLinkText(device)

    def result = [:]

    def volts = rawValue / 10
    if (!(rawValue == 0 || rawValue == 255)) {
        def minVolts = 2.1
        def maxVolts = 3.0
        def pct = (volts - minVolts) / (maxVolts - minVolts)
        def roundedPct = Math.round(pct * 100)
        if (roundedPct <= 0)
        roundedPct = 1
        result.value = Math.min(100, roundedPct)
        result.descriptionText = "${linkText} battery was ${result.value}%"
        result.name = 'battery'
        result.isStateChange = true
        sendEvent(result)
    }
    return result
}

def refresh() {
    if (logEnable) log.debug "refreshing Moes ZSS-ZK-THL battery status"
     return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021)+
        zigbee.readAttribute(0x0402, 0x0000)+
        zigbee.readAttribute(0x0405, 0x0000) + 
        zigbee.readAttribute(0x0400, 0x0000)
}

def configure() {
    if (logEnable) log.debug "Configuring Moes ZSS-ZK-THL Reporting and Bindings"
    
    return refresh() +
        zigbee.configureReporting(0x0405, 0x0000, DataType.UINT16, 3000, 3600, 1*100) +
        zigbee.configureReporting(0x0402, 0x0000, DataType.INT16, 3000, 3600, 0x1) +
        zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 3000, 3600, 0x1) +
        zigbee.configureReporting(0x0400, 0x0000, 0x21, 3000, 3600, 0x15)
}

private logDebug(msg) {
	if (settings?.logEnable) log.debug "${msg}"
}

