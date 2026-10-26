package net.monacraft.spellforge.paper;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class IntegrityServiceTest {
    @Test void rejectsTamperedLevelAndOwner() {
        IntegrityService service=new IntegrityService(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        UUID instance=UUID.randomUUID(),owner=UUID.randomUUID();long issued=123456L;
        String tag=service.sign(1,instance,"FIRE",2,owner,"test",issued);
        assertTrue(service.verify(tag,1,instance,"FIRE",2,owner,"test",issued));
        assertFalse(service.verify(tag,1,instance,"FIRE",3,owner,"test",issued));
        assertFalse(service.verify(tag,1,instance,"FIRE",2,UUID.randomUUID(),"test",issued));
    }
}
