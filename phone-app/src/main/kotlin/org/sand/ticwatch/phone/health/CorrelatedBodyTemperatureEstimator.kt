package org.sand.ticwatch.phone.health

/**
 * One way of turning a wrist skin reading plus the samples around it into a core temperature
 * (Strategy). Returns null when its inputs are missing, so the next model can take over.
 */
fun interface CoreTemperatureModel {
    suspend fun coreCelsius(skin: SensorSample, context: List<SensorSample>): Double?
}

/**
 * Body (core) temperature estimated from the watch's wrist skin temperature, correlated with the
 * other sensors: no sensor measures the core directly. Each worn SKIN_TEMPERATURE reading every
 * one of [qualifiers] accepts goes to [models] in order (Chain of Responsibility: the
 * multi-sensor [DualHeatFluxCoreModel] first, the skin-only [SkinOffsetCoreModel] as fallback
 * when, e.g., an older watch build sent no ambient temperature); the first estimate is clamped
 * to [coreRange] and stamped at the skin window's end. Readings below [minWornSkin] mean a loose
 * or off-wrist watch and are skipped.
 */
class CorrelatedBodyTemperatureEstimator(
    private val models: List<CoreTemperatureModel>,
    private val qualifiers: List<SkinReadingQualifier> = emptyList(),
    private val minWornSkin: Double = 30.0,
    private val coreRange: ClosedFloatingPointRange<Double> = 35.0..41.0,
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> =
        samples
            .filter { it.type == SensorType.SKIN_TEMPERATURE && it.value >= minWornSkin }
            .filter { skin -> qualifiers.all { it.accepts(skin, samples) } }
            .mapNotNull { skin ->
                val core = models.firstNotNullOfOrNull { it.coreCelsius(skin, samples) } ?: return@mapNotNull null
                SensorSample(SensorType.BODY_TEMPERATURE, core.coerceIn(coreRange), skin.endedAt ?: skin.recordedAt)
            }
}

/** Decorator: [delegate] only estimates readings every one of [qualifiers] accepts. */
class QualifiedCoreModel(
    private val qualifiers: List<SkinReadingQualifier>,
    private val delegate: CoreTemperatureModel,
) : CoreTemperatureModel {
    override suspend fun coreCelsius(skin: SensorSample, context: List<SensorSample>): Double? =
        if (qualifiers.all { it.accepts(skin, context) }) delegate.coreCelsius(skin, context) else null
}
