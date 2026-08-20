package common;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class VersionPolicyTest {
    @After
    public void reset() {
        UpdateOptions.unknownVersionPolicy = "allow";
        UpdateOptions.allowSameVersionSnapshotUpdates = true;
        UpdateOptions.allowSameVersionReleaseHashUpdates = false;
    }

    @Test
    public void enforcesTransitionBoundaries() {
        assertTrue(VersionPolicy.PATCH.evaluate("2.3.1", "2.3.2").allowed);
        assertFalse(VersionPolicy.PATCH.evaluate("2.3.2", "2.4.0").allowed);
        assertTrue(VersionPolicy.SAME_MAJOR.evaluate("2.3.2", "2.4.0").allowed);
        assertFalse(VersionPolicy.SAME_MAJOR.evaluate("2.3.2", "3.0.0").allowed);
        assertTrue(VersionPolicy.ANY.evaluate("2.3.2", "3.0.0").allowed);
        assertFalse(VersionPolicy.NONE.evaluate("2.3.2", "2.3.3").allowed);
    }

    @Test
    public void handlesUnknownVersionsExplicitly() {
        UpdateOptions.unknownVersionPolicy = "block";
        assertFalse(VersionPolicy.PATCH.evaluate("custom", "2.3.0").allowed);
        UpdateOptions.unknownVersionPolicy = "allow";
        assertTrue(VersionPolicy.PATCH.evaluate("custom", "2.3.0").allowed);
    }

    @Test
    public void supportsSnapshotMetadataRefresh() {
        assertTrue(VersionPolicy.PATCH.evaluate("2.3.2-SNAPSHOT", "2.3.2-SNAPSHOT", true).allowed);
        assertFalse(VersionPolicy.PATCH.evaluate("2.3.2", "2.3.2", true).allowed);
    }

    @Test
    public void parsesLegacyOnlyMinorAsPatch() {
        EntryOptions options = EntryOptions.parse("https://x.test?onlyMinor=true", null);
        assertEquals(VersionPolicy.PATCH, VersionPolicy.from(options));
    }
}
