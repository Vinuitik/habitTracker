package habitTracker.KPI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KPICollectionNameUtilTest {

    private KPICollectionNameUtil util;

    @BeforeEach
    void setUp() {
        util = new KPICollectionNameUtil();
    }

    @Test
    void testToCollectionName_UsesRawId() {
        assertEquals("kpi_data_507f1f77bcf86cd799439011", util.toCollectionName("507f1f77bcf86cd799439011"));
    }

    @Test
    void testToCollectionName_SanitizesUnexpectedCharacters() {
        // Defensive only: Mongo ids are already collection-name-safe, but don't trust it blindly.
        assertEquals("kpi_data_a_b", util.toCollectionName("a/b"));
    }

    @Test
    void testToCollectionName_NullAndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> util.toCollectionName(null));
        assertThrows(IllegalArgumentException.class, () -> util.toCollectionName(""));
        assertThrows(IllegalArgumentException.class, () -> util.toCollectionName("   "));
    }

    // Regression test for the bug this class exists to prevent: two different KPIs that happen to
    // share a display name (allowed — KPI's unique index is {name, userId}, not name alone) must
    // never resolve to the same data collection, or one user's KPI values leak into another's.
    @Test
    void testToCollectionName_SameNameDifferentIds_NeverCollide() {
        String userAKpiId = "507f1f77bcf86cd799439011";
        String userBKpiId = "6a5f3e1c9a1b2c3d4e5f6789";
        assertNotEquals(util.toCollectionName(userAKpiId), util.toCollectionName(userBKpiId));
    }
}
