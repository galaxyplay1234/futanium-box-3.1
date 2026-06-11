package com.futanium.box.model

import com.google.gson.annotations.SerializedName

// DTO com os nomes exatos do JSON
data class Game(
    val championship: String? = null,
    val championshipImageUrl: String? = null,
    val homeName: String? = null,
    val homeLogo: String? = null,
    val awayName: String? = null,
    val awayLogo: String? = null,
    val time: String? = null,
    val isLive: Boolean? = null,
    val isFinished: Boolean? = null,

    // Futebol na TV
    val liveScore: String? = null,
    val liveMinute: String? = null,
    val liveFinished: Boolean = false,

    val buttons: List<Any>? = null
)

// Modelo que o Adapter usa (sem header/background)
data class Game(
    val championship: String? = null,
    val championshipImageUrl: String? = null,
    val homeName: String? = null,
    val homeLogo: String? = null,
    val awayName: String? = null,
    val awayLogo: String? = null,
    val time: String? = null,
    val isLive: Boolean? = null,
    val isFinished: Boolean? = null,
    val buttons: List<Any>? = null   // <- valor padrão evita "No value passed"
)

// conversão prática DTO -> UI model
fun GameDto.toGame(): Game = Game(
    championship = championship,
    time = startTime,
    homeName = homeTeam,
    awayName = visitingTeam,
    homeLogo = homeTeamImageUrl,
    awayLogo = visitingTeamImageUrl,
    isLive = isLive == true,
    isFinished = isFinished == true,
    championshipImageUrl = championshipImageUrl
)