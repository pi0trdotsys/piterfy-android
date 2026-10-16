package com.eddyizm.tempus.util;
import com.eddyizm.tempus.model.InternetRadioStationCache;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class PiterfyRadioCatalogTest {
    @Test public void existingStarterAndUserStationAreNotOverwritten() {
        List<InternetRadioStationCache> saved = PiterfyRadioCatalog.defaults();
        assertTrue(PiterfyRadioCatalog.missingDefaults(saved).isEmpty());
        InternetRadioStationCache custom = new InternetRadioStationCache();
        custom.setId("custom"); custom.setName("  rmf fm  "); custom.setStreamUrl("https://example.test/my-stream");
        assertFalse(PiterfyRadioCatalog.missingDefaults(Collections.singletonList(custom)).stream().anyMatch(s -> "RMF FM".equals(s.getName())));
    }
    @Test public void editedStarterIsKeptByStableIdentity() {
        InternetRadioStationCache edited = PiterfyRadioCatalog.defaults().get(0);
        edited.setName("My radio"); edited.setStreamUrl("https://example.test/edited");
        assertFalse(PiterfyRadioCatalog.missingDefaults(Collections.singletonList(edited)).stream().anyMatch(s -> s.getId().equals(edited.getId())));
    }
}
