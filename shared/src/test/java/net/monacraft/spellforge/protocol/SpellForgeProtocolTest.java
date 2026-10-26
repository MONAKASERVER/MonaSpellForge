package net.monacraft.spellforge.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpellForgeProtocolTest {
    @Test void roundTripsFrame() throws Exception {
        var frame = new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.SAVE_REQ, UUID.randomUUID(), "token", UUID.randomUUID(), 42, "{\"x\":1}");
        assertEquals(frame, SpellForgeProtocol.decode(SpellForgeProtocol.encode(frame)));
    }

    @Test void rejectsOversizedPacket() {
        assertThrows(IOException.class, () -> SpellForgeProtocol.decode(new byte[SpellForgeProtocol.MAX_PACKET_BYTES + 1]));
    }

    @Test void editorDataWasAppendedWithoutChangingExistingOrdinals() throws Exception {
        assertEquals(2, SpellForgeProtocol.Type.OPEN_EDITOR.ordinal());
        assertEquals(11, SpellForgeProtocol.Type.PING.ordinal());
        assertEquals(12, SpellForgeProtocol.Type.EDITOR_DATA.ordinal());
        var frame = new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.EDITOR_DATA, UUID.randomUUID(),
                "token", UUID.randomUUID(), 7, "{\"fragments\":[]}");
        assertEquals(frame, SpellForgeProtocol.decode(SpellForgeProtocol.encode(frame)));
    }
}
