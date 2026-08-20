package spigot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AupGuiPaginationTest {

    @Test
    public void statusColorsMapAcrossLegacyAndModernMaterialNames() {
        assertEquals("LIME_WOOL", AupGui.modernWoolName((short) 5));
        assertEquals("RED_WOOL", AupGui.modernWoolName((short) 14));
        assertEquals("RED_WOOL", AupGui.modernWoolName((short) 1));
        assertEquals("YELLOW_WOOL", AupGui.modernWoolName((short) 4));
        assertEquals("LIGHT_BLUE_WOOL", AupGui.modernWoolName((short) 3));
        assertEquals(14, AupGui.legacyWoolData((short) 1));
    }
    @Test
    public void calculatesStablePageBounds() {
        assertEquals(1, AupGui.totalPages(0));
        assertEquals(1, AupGui.totalPages(45));
        assertEquals(2, AupGui.totalPages(46));
        assertEquals(3, AupGui.totalPages(135));

        assertEquals(1, AupGui.clampPage(-5, 3));
        assertEquals(2, AupGui.clampPage(2, 3));
        assertEquals(3, AupGui.clampPage(99, 3));
        assertEquals(1, AupGui.clampPage(4, 0));
    }
}
