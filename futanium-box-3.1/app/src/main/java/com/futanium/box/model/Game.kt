package com.futanium.box   // <-- deixe o seu package atual aqui

import com.google.gson.annotations.SerializedName

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