/**
 *  Monoprice Z-Wave Smoke Detector
 *
 *  Author: 
 *    Weikai Zhang
 *
 *  Modified using Kevin LaFramboise (krlaframboise)'s Monoprice Z-Wave Door/Windows sensor handler
 *
 *  URL to documentation:  https://community.smartthings.com/t/release-monoprice-z-wave-plus-door-window-sensor/70478
 *    
 * 
 */
metadata {
	definition (
		name: "Monoprice Z-Wave DIY Smoke Detector", 
		namespace: "weikai", 
		author: "Weikai Zhang"
	) {
		capability "Sensor"		
        capability "Smoke Detector"
		capability "Configuration"
		capability "Battery"
		//capability "Tamper Alert"
		capability "Refresh"
		capability "Health Check"

		attribute "lastCheckin", "string"
			
		//fingerprint mfr:"0109", prod:"2001", model:"0106", deviceJoinName: "Monoprice Door/Window Sensor"
        fingerprint deviceId: "0x2001", inClusters: "0x71, 0x85, 0x80, 0x72, 0x30, 0x86, 0x84"
	}
	
	simulator { }
	
	preferences {
		input "checkinInterval", "number",
			title: "Minimum Check-in Interval (Hours)",
			defaultValue: 4,
			range: "1..167",
			displayDuringSetup: true, 
			required: false
		input "reportBatteryEvery", "number", 
			title: "Battery Reporting Interval (Hours)", 
			description: "This setting can't be less than the Minimum Check-in Interval.",
			defaultValue: 6,
			range: "1..167",
			displayDuringSetup: true, 
			required: false
            
		/*
		input "enableExternalSensor", "bool", 
			title: "Enable External Sensor?",
			description: "The Monoprice Door/Window Sensor includes terminals that allow you to attach an external sensor.",
			defaultValue: true,
			displayDuringSetup: true, 
			required: false
        
		input "autoClearTamper", "bool", 
			title: "Automatically Clear Tamper?",
			description: "The tamper detected event is raised when the device is opened.  This setting allows you to decide whether or not to have the clear event automatically raised when the device closes.",
			defaultValue: true,
			displayDuringSetup: false, 
			required: false
		*/
		input "debugOutput", "bool", 
			title: "Enable debug logging?", 
			defaultValue: true, 
			displayDuringSetup: true, 
			required: false
	}

	tiles(scale: 2) {
		multiAttributeTile(name:"smoke", type: "lighting", width: 6, height: 4, canChangeIcon: true){
			tileAttribute ("device.smoke", key: "PRIMARY_CONTROL") {
				attributeState "clear", 
					label:'clear', 
					icon:"st.alarm.smoke.clear", 
					backgroundColor:"#00a0dc"
				attributeState "detected", 
					label:'detected', 
					icon:"st.particulate.particulate.particulate", 
					backgroundColor:"#e86d13"
			}
		}
		
		valueTile("battery", "device.battery", decoration: "flat", width: 2, height: 2){
			state "battery", label:'${currentValue}% battery', unit:""
		}		
		/*
		standardTile("tampering", "device.tamper", width: 2, height: 2) {
			state "detected", label:"Tamper", backgroundColor: "#e86d13"
			state "clear", label:"No Tamper", backgroundColor: "#ffffff"			
		}
		*/
		standardTile("refresh", "device.refresh", width: 2, height: 2) {
			state "default", label: "Refresh", action: "refresh", icon:"st.secondary.refresh-icon"
		}
		
		main("smoke")
		//details(["smoke", "battery", "tampering", "refresh"])
        details(["smoke", "clear", "battery", "refresh"])
	}
}

def updated() {	
	// This method always gets called twice when preferences are saved.
	if (!isDuplicateCommand(state.lastUpdated, 3000)) {
		state.lastUpdated = new Date().time
		logTrace "updated()"
		
		//if (state.checkinInterval != settings?.checkinInterval || state.enableExternalSensor != settings?.enableExternalSensor) {
        if (state.checkinInterval != settings?.checkinInterval || !state.enableExternalSensor) {
			state.pendingChanges = true
		}
	}	
}

def configure() {	
	logTrace "configure()"
	def cmds = []
	
	if (!device.currentValue("smoke")) {
		sendEvent(createEventMap("smoke", "detected", false))
	}
	
	if (!state.isConfigured) {
		logTrace "Waiting 1 second because this is the first time being configured"
		// Give inclusion time to finish.
		cmds << "delay 1000"			
	}
	
	initializeCheckin()
		
	cmds += delayBetween([
		wakeUpIntervalSetCmd(checkinIntervalSettingSeconds),
		//externalSensorConfigSetCmd(settings?.enableExternalSensor ?: false),
        externalSensorConfigSetCmd(true),
		externalSensorConfigGetCmd(),
		batteryGetCmd()
	], 100)
		
	logDebug "Sending configuration to device."
	return cmds
}

private initializeCheckin() {
	// Set the Health Check interval so that it can be skipped twice plus 5 minutes.
	def checkInterval = ((checkinIntervalSettingSeconds * 3) + (5 * 60))
	
	sendEvent(name: "checkInterval", value: checkInterval, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
}

// Required for HealthCheck Capability, but doesn't actually do anything because this device sleeps.
def ping() {
	logDebug "ping()"	
}

private getCheckinIntervalSetting() {
	return (settings?.checkinInterval ?: 6)
}

private getCheckinIntervalSettingSeconds() {
	return (checkinIntervalSetting * 60 * 60)
}
				
def parse(String description) {
	def result = []
	def cmd = zwave.parse(description, commandClassVersions)
	if (cmd) {
		result += zwaveEvent(cmd)
	}
	else {
		logDebug "Unable to parse description: $description"
	}
	
	if (!isDuplicateCommand(state.lastCheckinTime, 60000)) {
		state.lastCheckinTime = new Date().time	
		result << createLastCheckinEvent()
	}
	return result
}

private createLastCheckinEvent() {
	logDebug "Device Checked In"	
	return createEvent(createEventMap("lastCheckin", convertToLocalTimeString(new Date()), false))
}

def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	def result = []
	def encapCmd = cmd.encapsulatedCommand(commandClassVersions)
	if (encapCmd) {
		result += zwaveEvent(encapCmd)
	}
	else {
		log.warn "Unable to extract encapsulated cmd from $cmd"	
	}
	return result
}

private getCommandClassVersions() {
	[
		0x20: 1,  // Basic
		0x59: 1,  // AssociationGrpInfo
		0x5A: 1,  // DeviceResetLocally
		0x5E: 2,  // ZwaveplusInfo
		0x70: 1,  // Configuration
		0x71: 3,  // Alarm v1 or Notification v4
		0x72: 2,  // ManufacturerSpecific*=
		0x73: 1,  // Powerlevel
		0x7A: 2,  // FirmwareUpdateMd
		0x80: 1,  // Battery
		0x84: 2,  // WakeUp
		0x85: 2,  // Association
		0x86: 1,  // Version (2)
		0x98: 1		// Security
	]
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd) {
	logTrace "WakeUpNotification: $cmd"
	def cmds = []
	
	if (canSendConfiguration()) {
		cmds += configure()
	}
	else if (canReportBattery()) {
		cmds << batteryGetCmd()
	}
	else {
		logTrace "Skipping battery check because it was already checked within the last $reportEveryHours hours."
	}
	
	if (cmds) {
		cmds << "delay 5000"
	}
	
	cmds << wakeUpNoMoreInfoCmd()
	return response(cmds)
}

private canReportBattery() {
	def reportEveryHours = settings?.reportBatteryEvery ?: 6
	def reportEveryMS = (reportEveryHours * 60 * 60 * 1000)
		
	return (!state.lastBatteryReport || ((new Date().time) - state.lastBatteryReport > reportEveryMS)) 
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
	def val = (cmd.batteryLevel == 0xFF ? 1 : cmd.batteryLevel)
	if (val > 100) {
		val = 100
	}
	else if (val < 1) {
		val = 1
	}
	state.lastBatteryReport = new Date().time	
	[
		createEvent(createEventMap("battery", val, null, "%"))
	]
}	

def zwaveEvent(physicalgraph.zwave.commands.configurationv1.ConfigurationReport cmd) {
	logTrace "ConfigurationReport: $cmd"
	def parameterName
	switch (cmd.parameterNumber) {
		case 1:
			state.enableExternalSensor = (cmd.configurationValue[0] == 0xFF)
			logDebug "External Sensor Enabled: ${state.enableExternalSensor}"
			break
		default:	
			parameterName = "Parameter #${cmd.parameterNumber}"
	}		
	if (parameterName) {
		logDebug "${parameterName}: ${cmd.configurationValue}"
	} 
	state.isConfigured = true
	state.pendingRefresh = false
	state.pendingChanges = false
	state.checkinInterval = checkinIntervalSetting
	return []
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd) {
	logTrace "BasicReport: $cmd"	
	return []
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd) {
	logTrace "Basic Set: $cmd"	
	return []
}


def zwaveEvent(physicalgraph.zwave.commands.notificationv3.NotificationReport cmd) {
	def result = []	
	logTrace "NotificationReport: $cmd"
	if (cmd.notificationType == 0x06) {
		result += handlesmokeEvent(cmd.event)
	}
    /*
	else if (cmd.notificationType == 0x07) {		
		result += handleTamperEvent(cmd.event)
	}
    */
	return result
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
	logDebug "Unhanded Command: $cmd"
	return []
}

private handlesmokeEvent(event) {
	def result = []
	def val
	if (event == 0xFF || event == 0x16) {
		val = "detected"
	}
	else if(event == 0 || event == 0x17) {
		val = "clear"
	}
	if (val) {
		result << createEvent(createEventMap("smoke", val))
	}
	return result
}
/*
private handleTamperEvent(event) {
	def result = []
	def val
	if (event == 0x03) {
		val = "detected"
	}
	else if (event == 0) {
		if (settings?.autoClearTamper) {
			val = "clear"
		}
		else {
			logDebug "Tamper is Clear"
		}
	}
	if (val) {
		result << createEvent(createEventMap("tamper", val))
	}
	return result
}
*/
// Resets the tamper attribute to clear and requests the device to be refreshed.
def refresh() {	
	/*
	if (device.currentValue("tamper") != "clear") {
		sendEvent(createEventMap("tamper", "clear", false))
	}
	else {
		logDebug "The configuration and attributes will be refresh the next time the device wakes up.  If you want this to happen immediately, open the back cover of the device, wait until the red light turns solid, and then put the cover back on."
		state.pendingRefresh = true
	}
    */
    
    logDebug "The configuration and attributes will be refresh the next time the device wakes up.  If you want this to happen immediately, open the back cover of the device, wait until the red light turns solid, and then put the cover back on."
	state.pendingRefresh = true
}

def createEventMap(eventName, newVal, displayed=null, unit=null) {
	if (displayed == null) {
		displayed = (device.currentValue(eventName) != newVal)
	}
	if (displayed) {
		logDebug "${eventName.capitalize()} is ${newVal}"
	}
	def eventMap = [
		name: eventName, 
		value: newVal, 
		displayed: displayed,
		isStateChange: true
	]
	if (unit) {
		eventMap.unit = unit
	}
	return eventMap
}

private wakeUpIntervalSetCmd(val) {
	logTrace "wakeUpIntervalSetCmd(${val})"
	return secureCmd(zwave.wakeUpV2.wakeUpIntervalSet(seconds:val, nodeid:zwaveHubNodeId))
}

private wakeUpNoMoreInfoCmd() {
	return secureCmd(zwave.wakeUpV2.wakeUpNoMoreInformation())
}

private batteryGetCmd() {
	logTrace "Requesting battery report"
	return secureCmd(zwave.batteryV1.batteryGet())
}

private externalSensorConfigGetCmd() {
	return configGetCmd(1)
}

private externalSensorConfigSetCmd(isEnabled) {
	return configSetCmd(1, 1, (isEnabled ? 0xFF : 0x00))
}

private configSetCmd(paramNumber, valSize, val) {	
	logTrace "Setting configuration param #${paramNumber} to ${val}"
	return secureCmd(zwave.configurationV1.configurationSet(parameterNumber: paramNumber, size: valSize, configurationValue: [val]))
}

private configGetCmd(paramNumber) {
	logTrace "Requesting configuration report for param #${paramNumber}"
	return secureCmd(zwave.configurationV1.configurationGet(parameterNumber: paramNumber))
}

private secureCmd(cmd) {
	if (zwaveInfo?.zw?.contains("s") || ("0x98" in device.rawDescription?.split(" "))) {
		return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	}
	else {
		return cmd.format()
	}	
}

private canSendConfiguration() {
	return (!state.isConfigured || state.pendingRefresh != false	|| state.pendingChanges != false)
}

private convertToLocalTimeString(dt) {
	def timeZoneId = location?.timeZone?.ID
	if (timeZoneId) {
		return dt.format("MM/dd/yyyy hh:mm:ss a", TimeZone.getTimeZone(timeZoneId))
	}
	else {
		return "$dt"
	}	
}

private isDuplicateCommand(lastExecuted, allowedMil) {
	!lastExecuted ? false : (lastExecuted + allowedMil > new Date().time) 
}

private logDebug(msg) {
	if (settings?.debugOutput || settings?.debugOutput == null) {
		log.debug "$msg"
	}
}

private logTrace(msg) {
	// log.trace "$msg"
}