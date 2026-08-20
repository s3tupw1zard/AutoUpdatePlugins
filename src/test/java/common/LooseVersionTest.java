package common;

import org.junit.Test;

import static org.junit.Assert.*;

public class LooseVersionTest {
    @Test
    public void parsesCommonPluginVersions() {
        LooseVersion v = LooseVersion.parse("v2.3.1");
        assertTrue(v.known);
        assertEquals(2, v.major);
        assertEquals(3, v.minor);
        assertEquals(1, v.patch);
        assertEquals("snapshot", LooseVersion.parse("2.3.1-SNAPSHOT").qualifier);
        assertEquals("beta", LooseVersion.parse("Plugin-1.21.8-b1.jar").qualifier);
        assertFalse(LooseVersion.parse("release-abc123").known);
        assertEquals(0, LooseVersion.parse("2.3").patch);
    }

    @Test
    public void comparesNumbersAndQualifiers() {
        assertTrue(LooseVersion.parse("2.3.2").compareTo(LooseVersion.parse("2.3.1")) > 0);
        assertEquals(0, LooseVersion.parse("2.3.2").compareTo(LooseVersion.parse("v2.3.2")));
        assertTrue(LooseVersion.parse("2.3.2").compareTo(LooseVersion.parse("2.3.2-RC1")) > 0);
        assertTrue(LooseVersion.parse("2.3.2-beta2").compareTo(LooseVersion.parse("2.3.2-beta1")) > 0);
        assertTrue(LooseVersion.parse("3.0.0").compareTo(LooseVersion.parse("2.9.9")) > 0);
    }
}
