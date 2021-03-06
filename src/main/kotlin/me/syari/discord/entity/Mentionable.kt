package me.syari.discord.entity

internal interface Mentionable {
    val asMentionRegex: Regex

    val asMentionDisplay: String

    companion object {
        fun String.replaceAll(mentionable: Iterable<Mentionable>): String {
            var result = this
            mentionable.forEach {
                result = result.replace(it.asMentionRegex, it.asMentionDisplay)
            }
            return result
        }
    }
}