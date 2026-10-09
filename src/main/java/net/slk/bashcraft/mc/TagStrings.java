package net.slk.bashcraft.mc;

import net.minecraft.nbt.CompoundTag;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A live view of a compound's string entries: reads copy nothing, and writes change only the key they name, so other
 * data a datapack keeps in the same compound (numbers, compounds) is left alone.
 */
final class TagStrings extends AbstractMap<String, String> {
    private final CompoundTag tag;

    TagStrings(CompoundTag tag) {
        this.tag = tag;
    }

    @Override
    public String get(Object key) {
        return key instanceof String name ? tag.getString(name).orElse(null) : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String name && tag.contains(name);
    }

    @Override
    public int size() {
        return tag.size();
    }

    @Override
    public String put(String key, String value) {
        String old = get(key);
        tag.putString(key, value);
        return old;
    }

    @Override
    public String remove(Object key) {
        String old = get(key);
        if (key instanceof String name) {
            tag.remove(name);
        }
        return old;
    }

    @Override
    public Set<Map.Entry<String, String>> entrySet() {
        Map<String, String> strings = new LinkedHashMap<>();
        for (String key : tag.keySet()) {
            tag.getString(key).ifPresent(value -> strings.put(key, value));
        }
        return strings.entrySet();
    }
}
