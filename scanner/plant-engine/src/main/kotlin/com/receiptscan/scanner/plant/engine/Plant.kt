package com.receiptscan.scanner.plant.engine

/** One species in the model's label map. */
data class SpeciesLabel(val index: Int, val scientificName: String, val commonName: String?)

data class PlantCandidate(val label: SpeciesLabel, val score: Float)

sealed interface PlantOutcome {
    /** "Likely matches" — shown without percentages, ordered best first. */
    data class Matches(val candidates: List<PlantCandidate>, val suggestAnotherPhoto: Boolean) : PlantOutcome
    data object NoMatch : PlantOutcome
}

/**
 * Post-processing for a specialist species classifier. Background/unknown classes are dropped,
 * and low-confidence outputs become "no match" instead of a guess.
 */
object PlantPostprocess {
    private val nonSpecies = setOf("background", "unknown", "none", "other", "")

    fun topK(scores: FloatArray, labels: List<SpeciesLabel>, k: Int = 3, minScore: Float = 0.15f, clearLead: Float = 0.5f): PlantOutcome {
        require(scores.size == labels.size) { "Label map has ${labels.size} entries but the model returned ${scores.size} scores" }
        return fromTop(labels.indices.map { PlantCandidate(labels[it], scores[it]) }, k, minScore, clearLead)
    }

    /** Same rules for runtimes that already return only the top categories. */
    fun fromTop(candidates: List<PlantCandidate>, k: Int = 3, minScore: Float = 0.15f, clearLead: Float = 0.5f): PlantOutcome {
        val ranked = candidates
            .filter { it.label.scientificName.trim().lowercase() !in nonSpecies }
            .sortedByDescending { it.score }
            .take(k)
            .filter { it.score >= minScore }
        if (ranked.isEmpty()) return PlantOutcome.NoMatch
        // Ask for another photo when the best match is not clearly ahead.
        val unclear = ranked.first().score < clearLead || (ranked.size > 1 && ranked[0].score - ranked[1].score < 0.15f)
        return PlantOutcome.Matches(ranked, unclear)
    }

    /** Parses "index,scientific name[,common name]" or one-name-per-line label maps. */
    fun parseLabelMap(text: String): List<SpeciesLabel> = text.lines().filter { it.isNotBlank() }.mapIndexed { i, line ->
        val parts = line.split(',').map { it.trim() }
        if (parts.size >= 2 && parts[0].toIntOrNull() != null) SpeciesLabel(parts[0].toInt(), parts[1], parts.getOrNull(2)?.ifEmpty { null })
        else SpeciesLabel(i, line.trim(), null)
    }

    const val SAFETY_NOTE = "Matches can be wrong. Never use them to decide whether a plant is safe to eat or touch."
}
