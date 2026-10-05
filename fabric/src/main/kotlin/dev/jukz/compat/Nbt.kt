// NBT reads that return Optional on newer versions (1.21.5+) and defaults on older ones. Each helper
// gives null (or an empty compound) when the key is missing, the same on every version.
package dev.jukz.compat

import net.minecraft.nbt.CompoundTag
import java.util.UUID
//? if >=1.21.11 {
/*import net.minecraft.core.UUIDUtil
*///?}

//? if >=1.21.11 {
/*fun CompoundTag.compound(key: String): CompoundTag = getCompoundOrEmpty(key)
fun CompoundTag.string(key: String): String? = getString(key).orElse(null)
fun CompoundTag.long(key: String): Long? = getLong(key).orElse(null)
fun CompoundTag.int(key: String): Int? = getInt(key).orElse(null)
fun CompoundTag.uuid(key: String): UUID? = read(key, UUIDUtil.CODEC).orElse(null)
fun CompoundTag.putUuid(key: String, value: UUID) = store(key, UUIDUtil.CODEC, value)
*///?} else {
fun CompoundTag.compound(key: String): CompoundTag = getCompound(key)
fun CompoundTag.string(key: String): String? = if (contains(key)) getString(key) else null
fun CompoundTag.long(key: String): Long? = if (contains(key)) getLong(key) else null
fun CompoundTag.int(key: String): Int? = if (contains(key)) getInt(key) else null
fun CompoundTag.uuid(key: String): UUID? = if (hasUUID(key)) getUUID(key) else null
fun CompoundTag.putUuid(key: String, value: UUID) = putUUID(key, value)
//?}
