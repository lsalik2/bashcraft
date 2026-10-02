package net.slk.bashcraft.mc;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

class TransparentHooksTest {
    private static MethodNode method(String name) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    @Test
    void mergedHandlerWithoutACallIsNotApplied() {
        // An injection that matched nothing still merges its handler into the target.
        ClassNode target = new ClassNode();
        target.methods.add(method("performCommand"));
        target.methods.add(method("handler$zza000$bashcraft$transparentChain"));
        assertFalse(TransparentHooks.callsHandler(target));
    }

    @Test
    void injectedCallIsApplied() {
        ClassNode target = new ClassNode();
        MethodNode performCommand = method("performCommand");
        performCommand.instructions.insert(new MethodInsnNode(Opcodes.INVOKESPECIAL, "net/minecraft/commands/Commands",
                "handler$zza000$bashcraft$transparentChain", "()V"));
        target.methods.add(performCommand);
        target.methods.add(method("handler$zza000$bashcraft$transparentChain"));
        assertTrue(TransparentHooks.callsHandler(target));
    }
}
