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
    void testToCollectionName_SimpleNames() {
        assertEquals("kpi_data_steps", util.toCollectionName("steps"));
        assertEquals("kpi_data_weight", util.toCollectionName("weight"));
        assertEquals("kpi_data_sleep", util.toCollectionName("sleep"));
    }
    
    @Test
    void testToCollectionName_WithSpaces() {
        assertEquals("kpi_data_daily_steps", util.toCollectionName("Daily Steps"));
        assertEquals("kpi_data_body_weight", util.toCollectionName("Body Weight"));
        assertEquals("kpi_data_sleep_hours", util.toCollectionName("Sleep Hours"));
    }
    
    @Test
    void testToCollectionName_WithSpecialCharacters() {
        assertEquals("kpi_data_steps_min", util.toCollectionName("Steps/min"));
        assertEquals("kpi_data_weight_kg", util.toCollectionName("Weight (kg)"));
        assertEquals("kpi_data_temp_c", util.toCollectionName("Temp °C"));
        assertEquals("kpi_data_heart_rate", util.toCollectionName("Heart-Rate"));
    }
    
    @Test
    void testToCollectionName_CaseInsensitive() {
        assertEquals("kpi_data_daily_steps", util.toCollectionName("DAILY STEPS"));
        assertEquals("kpi_data_daily_steps", util.toCollectionName("Daily Steps"));
        assertEquals("kpi_data_daily_steps", util.toCollectionName("daily steps"));
    }
    
    @Test
    void testToCollectionName_MultipleSpacesAndUnderscores() {
        assertEquals("kpi_data_daily_step_count", util.toCollectionName("Daily   Step___Count"));
        assertEquals("kpi_data_test_name", util.toCollectionName("  Test  Name  "));
    }
    
    @Test
    void testToCollectionName_VeryLongName() {
        String longName = "This is a very long KPI name that exceeds the normal length limit and should be truncated properly";
        String result = util.toCollectionName(longName);
        
        assertTrue(result.length() <= 120);
        assertTrue(result.startsWith("kpi_data_"));
        assertFalse(result.endsWith("_"));
    }
    
    @Test
    void testToCollectionName_NullAndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> util.toCollectionName(null));
        assertThrows(IllegalArgumentException.class, () -> util.toCollectionName(""));
        assertThrows(IllegalArgumentException.class, () -> util.toCollectionName("   "));
    }
    
    @Test
    void testToCollectionName_OnlySpecialCharacters() {
        assertThrows(IllegalArgumentException.class, () -> util.toCollectionName("!@#$%^&*()"));
        assertThrows(IllegalArgumentException.class, () -> util.toCollectionName("()[]{}"));
    }
    
    @Test
    void testFromCollectionName() {
        assertEquals("daily_steps", util.fromCollectionName("kpi_data_daily_steps"));
        assertEquals("weight", util.fromCollectionName("kpi_data_weight"));
        assertEquals("heart_rate", util.fromCollectionName("kpi_data_heart_rate"));
    }
    
    @Test
    void testFromCollectionName_InvalidFormat() {
        assertThrows(IllegalArgumentException.class, () -> util.fromCollectionName("invalid_collection"));
        assertThrows(IllegalArgumentException.class, () -> util.fromCollectionName("data_steps"));
        assertThrows(IllegalArgumentException.class, () -> util.fromCollectionName(null));
    }
    
    @Test
    void testIsKPIDataCollection() {
        assertTrue(util.isKPIDataCollection("kpi_data_steps"));
        assertTrue(util.isKPIDataCollection("kpi_data_daily_weight"));
        
        assertFalse(util.isKPIDataCollection("steps"));
        assertFalse(util.isKPIDataCollection("data_steps"));
        assertFalse(util.isKPIDataCollection("kpis"));
        assertFalse(util.isKPIDataCollection(null));
    }
    
    @Test
    void testRoundTrip() {
        String[] testNames = {
            "Daily Steps",
            "Body Weight (kg)",
            "Sleep Hours",
            "Heart Rate",
            "Temperature",
            "Blood Pressure"
        };
        
        for (String name : testNames) {
            String collectionName = util.toCollectionName(name);
            String extracted = util.fromCollectionName(collectionName);
            
            // Should be able to convert back to same collection name
            assertEquals(collectionName, util.toCollectionName(extracted));
        }
    }
}
