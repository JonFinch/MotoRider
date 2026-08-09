package com.motorider.models

enum class WarningType { SPEED_EXCEEDED, GPS_LOST, OFF_ROUTE, ROAD_CLOSED }

data class NavigationWarning(
    val type: WarningType,
    val message: String,
    val severity: WarningSeverity
)

enum class WarningSeverity { INFO, WARNING, CRITICAL }
