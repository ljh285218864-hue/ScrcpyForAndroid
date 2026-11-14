package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

public class XiaomiSettingsSupportTest {
    @Test public void detectsXiaomiRedmiPocoDevices() {
        assertTrue(XiaomiSettingsSupport.isXiaomiFamily("Xiaomi", "Xiaomi"));
        assertTrue(XiaomiSettingsSupport.isXiaomiFamily("Xiaomi", "Redmi"));
        assertTrue(XiaomiSettingsSupport.isXiaomiFamily("Xiaomi", "POCO"));
        assertTrue(XiaomiSettingsSupport.isXiaomiFamily("unknown", "redmi note"));
        assertTrue(XiaomiSettingsSupport.isXiaomiFamily(null, "POCO"));
        assertTrue(XiaomiSettingsSupport.isXiaomiFamily(" xiaomi ", null));
    }

    @Test public void doesNotRedirectOtherVendors() {
        assertFalse(XiaomiSettingsSupport.isXiaomiFamily("OPPO", "OPPO"));
        assertFalse(XiaomiSettingsSupport.isXiaomiFamily("vivo", "vivo"));
        assertFalse(XiaomiSettingsSupport.isXiaomiFamily("Samsung", "Samsung"));
        assertFalse(XiaomiSettingsSupport.isXiaomiFamily(null, null));
        assertFalse(XiaomiSettingsSupport.isXiaomiFamily("xiaomix", "notredmi"));
    }
}
