package me.syari.discord.entity.api

import com.google.gson.JsonObject
import me.syari.discord.entity.Mentionable
import me.syari.discord.util.json.JsonUtil.asStringOrNull
import me.syari.discord.util.json.JsonUtil.getOrNull

data class Member internal constructor(
    val name: String,
    val id: Long,
    val isBot: Boolean,
    val nickName: String?
): Mentionable {
    val displayName
        get() = nickName ?: name

    override val asMentionDisplay: String
        get() = "@$displayName"

    override val asMentionRegex: Regex
        get() = "<@!?$id>".toRegex()

    internal companion object {
        fun from(memberJson: JsonObject, userJson: JsonObject): Member {
            val user = User.from(userJson)
            val name = user.name
            val id = user.id
            val isBot = user.isBot
            val nickName = memberJson.getOrNull("nick")?.asStringOrNull
            return Member(name, id, isBot, nickName)
        }
    }
}