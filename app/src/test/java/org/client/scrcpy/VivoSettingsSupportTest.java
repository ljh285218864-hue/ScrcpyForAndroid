package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

public class VivoSettingsSupportTest {
    @Test public void identifiesVivoAndIqoo() {
        assertTrue(VivoSettingsSupport.isVivoFamily("vivo", "vivo"));
        assertTrue(VivoSettingsSupport.isVivoFamily("vivo", "iQOO"));
        assertTrue(VivoSettingsSupport.isVivoFamily("IQOO", null));
        assertTrue(VivoSettingsSupport.isVivoFamily(null, " iQOO "));
        assertTrue(VivoSettingsSupport.isVivoFamily("vivo mobile", "unknown"));
    }

    @Test public void doesNotRedirectOtherManufacturers() {
        assertFalse(VivoSettingsSupport.isVivoFamily("Xiaomi", "Redmi"));
        assertFalse(VivoSettingsSupport.isVivoFamily("OPPO", "OnePlus"));
        assertFalse(VivoSettingsSupport.isVivoFamily(null, null));
        assertFalse(VivoSettingsSupport.isVivoFamily("vivobook", "fakeiqoo"));
    }
}
