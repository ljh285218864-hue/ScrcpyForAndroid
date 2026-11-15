package org.client.scrcpy;

import org.junit.Test;
import static org.junit.Assert.*;

/** Regression coverage for system QR service matching and ADB connect discovery. */
public class SystemAdbQrPairingSessionTest {

    @Test public void detectsExactSystemQrPairingService() {
        String output = "List of discovered mdns services\n"
                + "studio-A1B2C3D4E5 _adb-tls-pairing._tcp 192.168.1.17:41629\n"
                + "adb-DEVICE _adb-tls-connect._tcp 192.168.1.17:38019\n";
        LanDeviceDiscovery.Device result =
                SystemAdbQrPairingSession.findMatchingAdbMdnsService(
                        output, "studio-A1B2C3D4E5");
        assertNotNull(result);
        assertEquals("192.168.1.17", result.address);
        assertEquals(41629, result.pairingPort);
    }

    @Test public void refusesWrongQrServiceAndPublicAddress() {
        String output = "studio-WRONG1234 _adb-tls-pairing._tcp 192.168.1.17:41629\n"
                + "studio-A1B2C3D4E5 _adb-tls-pairing._tcp 8.8.8.8:33333\n";
        assertNull(SystemAdbQrPairingSession.findMatchingAdbMdnsService(
                output, "studio-A1B2C3D4E5"));
    }

    @Test public void rejectsNonPairingServices() {
        String output = "studio-A1B2C3D4E5 _adb-tls-connect._tcp 192.168.1.17:38019\n";
        assertNull(SystemAdbQrPairingSession.findMatchingAdbMdnsService(
                output, "studio-A1B2C3D4E5"));
    }

    @Test public void extractsOnlyTrustedIpConnectPort() {
        String output = "List of discovered mdns services\n"
                + "adb-OTHER _adb-tls-connect._tcp 192.168.1.18:5555\n"
                + "adb-TARGET _adb-tls-connect._tcp 192.168.1.17:38019\n";
        assertEquals(38019, SystemAdbQrPairingSession.findAdbConnectPort(
                output, "192.168.1.17"));
        assertEquals(0, SystemAdbQrPairingSession.findAdbConnectPort(
                output, "192.168.1.19"));
    }

    @Test public void generatesAndroidStudioCompatibleQrCode() {
        String service = PairQrCode.newSystemQrService();
        String password = PairQrCode.newSystemQrPassword();
        String payload = PairQrCode.createSystemQrPayload(service, password);
        assertTrue(payload.startsWith("WIFI:T:ADB;S:studio-"));
        assertTrue(payload.contains(";P:" + password + ";;"));
        assertTrue(service.matches("studio-[A-Za-z0-9]{10}"));
    }

    @Test public void ignoresInvalidPorts() {
        String output = "studio-A1B2C3D4E5 _adb-tls-pairing._tcp 192.168.1.17:65536\n";
        assertNull(SystemAdbQrPairingSession.findMatchingAdbMdnsService(
                output, "studio-A1B2C3D4E5"));
    }
}
