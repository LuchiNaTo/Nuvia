package com.nuvio.app.features.player

fun <T> findPreferredTrackIndex(
    tracks: List<T>,
    targets: List<String>,
    language: (T) -> String?,
): Int {
    if (targets.isEmpty()) return -1
    for (target in targets) {
        val matchIndex = tracks.indexOfFirst { track ->
            languageMatchesPreference(
                trackLanguage = language(track),
                targetLanguage = target,
            )
        }
        if (matchIndex >= 0) {
            return matchIndex
        }
    }
    return -1
}

fun findPreferredSubtitleTrackIndex(
    tracks: List<SubtitleTrack>,
    targets: List<String>,
): Int {
    if (targets.isEmpty()) return -1

    for ((targetPosition, target) in targets.withIndex()) {
        val normalizedTarget = normalizeLanguageCode(target) ?: continue
        if (normalizedTarget == SubtitleLanguageOption.FORCED) {
            val forcedIndex = tracks.indexOfFirst { it.isForced }
            if (forcedIndex >= 0) return forcedIndex
            if (targetPosition == 0) return -1
            continue
        }

        val matchIndex = tracks.indexOfFirst { track ->
            languageMatchesPreference(
                trackLanguage = track.language,
                targetLanguage = normalizedTarget,
            )
        }
        if (matchIndex >= 0) return matchIndex
    }

    return -1
}
