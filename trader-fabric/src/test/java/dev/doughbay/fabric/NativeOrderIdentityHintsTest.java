package dev.doughbay.fabric;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeOrderIdentityHintsTest {
    private static final String ID="12ab3456-7890-1234-1234-123456789abc";
    @Test void explicitlyNamedOrderFieldsCanBeComparedWithoutRetainingTheirValues() {
        var root=new CompoundTag();var values=new CompoundTag();root.put("PublicBukkitValues",values);
        values.putString("minecraft:order_uuid",ID);values.putLong("minecraft:wi",123456789);
        values.putString("player_uuid",ID);values.putString("order_token",ID);values.putInt("requested",1024);
        var hashes=NativeOrderIdentityHints.fields(root);
        assertEquals(1,hashes.size());String hash=hashes.get("PublicBukkitValues/minecraft:order_uuid");
        assertEquals(64,hash.length());assertFalse(hashes.toString().contains(ID));
        assertFalse(hashes.toString().contains("123456789"));
        assertEquals(java.util.List.of(hash),NativeOrderIdentityHints.text(Component.literal("Order ID: "+ID)));
    }
    @Test void hoverHintsIgnoreArbitrarySecretsAndWindowFieldsAreNotOrderIdentity() {
        var component=Component.literal("private-player").withStyle(s->s.withHoverEvent(new HoverEvent.ShowText(
                Component.literal("Order UUID: "+ID+"\napi_token private-secret"))));
        var hashes=NativeOrderIdentityHints.text(component);
        assertEquals(1,hashes.size());assertEquals(64,hashes.getFirst().length());
        assertFalse(hashes.toString().contains("private"));
        var tag=new CompoundTag();tag.putLong("wi",12345);tag.putString("order_id","private-secret");
        assertTrue(NativeOrderIdentityHints.fields(tag).isEmpty());
    }
}
