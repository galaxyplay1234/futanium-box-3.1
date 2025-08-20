package com.futanium.box.model

data class Game(
    val championship: String,
    val time: String,             // "21:30"
    val homeName: String,
    val homeLogo: String?,        // url (pode ser null)
    val awayName: String,
    val awayLogo: String?         // url (pode ser null)
)
