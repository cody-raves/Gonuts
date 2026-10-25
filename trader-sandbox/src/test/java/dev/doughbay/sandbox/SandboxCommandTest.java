package dev.doughbay.sandbox;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SandboxCommandTest {
    @Test void commandTreeValidationBeforeAPlayerJoinsDoesNotRequireAServer(){
        var source=new CommandSourceStack(null,Vec3.ZERO,Vec2.ZERO,null,PermissionSet.NO_PERMISSIONS,
                "Sandbox",Component.literal("Sandbox"),null,null);
        assertTrue(TradingSandboxMod.singlePlayerCommand(source));
    }
}
