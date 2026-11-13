package dev.fughetta.dshcompose.domain.presets

class PresetBundle(
    val id: String,
    val name: String,
    val description: String,
    val plugins: String,
    val order: Long?,
    val existingPackage: String? = null,
) {
    init {
        require(validPresetId(id))
    }
}
