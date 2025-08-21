package com.futanium.box.model

import com.google.gson.annotations.SerializedName

// DTO com os nomes exatos do JSON
data class GameDto(
    @SerializedName("championship") val championship: String?,
    @SerializedName("championship_image_url") val championshipImageUrl: String?,
    @SerializedName("home_team") val homeTeam: String?,
    @SerializedName("visiting_team") val visitingTeam: String?,
    @SerializedName("home_team_image_url") val homeTeamImageUrl: String?,
    @SerializedName("visiting_team_image_url") val visitingTeamImageUrl: String?,
    @SerializedName("start_time") val startTime: String?,
    @SerializedName("end_time") val endTime: String?,
    @SerializedName("is_live") val isLive: Boolean?,
    @SerializedName("is_finished") val isFinished: Boolean?,
    @SerializedName("buttons") val buttons: List<Any>?
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