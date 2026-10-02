package net.slk.bashcraft.mc;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Whether the optional transparent-mode mixins applied; they may fail after a Minecraft update without breaking /sh. */
public final class TransparentHooks {
    private static final List<String> MIXINS = List.of(
            "net.slk.bashcraft.mixin.transparent.CommandsTransparentMixin",
            "net.slk.bashcraft.mixin.transparent.ServerFunctionLibraryMixin");
    private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();

    private TransparentHooks() {}

    public static boolean applied() {
        return APPLIED.containsAll(MIXINS);
    }

    /** Called by TransparentMixinPlugin after a transparent mixin is applied to its target class. */
    public static void recordApply(String mixinClassName, ClassNode target) {
        if (callsHandler(target)) {
            APPLIED.add(mixinClassName);
        }
    }

    /**
     * Whether some method of the target calls a bashcraft$transparent handler. The handler being present isn't
     * enough: with defaultRequire 0, an injection that matched nothing still merges its handler into the target.
     */
    static boolean callsHandler(ClassNode target) {
        for (MethodNode method : target.methods) {
            if (method.name.contains("bashcraft$transparent")) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.contains("bashcraft$transparent")) {
                    return true;
                }
            }
        }
        return false;
    }
}
