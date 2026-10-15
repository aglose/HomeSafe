package com.meticulouscreations.homesafe.text

/**
 * A [TextLoader] that writes each resource as its key with its arguments, `key(arg, arg)`, and
 * joins [UiText.Joined] parts around ` <separator> `. Tests check what was worded with it on
 * every target, including Android host tests, where the real resources can't be read.
 */
internal val KeyedTextLoader = TextLoader { it.keyed() }

internal fun UiText.keyed(): String = when (this) {
    is UiText.Verbatim -> value
    is UiText.Resource -> if (args.isEmpty()) res.key else "${res.key}(${args.joinToString { it.keyedArg() }})"
    is UiText.Plural -> "${res.key}[$quantity](${args.joinToString { it.keyedArg() }})"
    is UiText.Joined -> parts.joinToString(" <${separator.keyed()}> ") { it.keyed() }
}

private fun Any.keyedArg(): String = if (this is UiText) keyed() else toString()
