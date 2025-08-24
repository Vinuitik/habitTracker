package habitTracker.KPI;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataMongoTest
@ActiveProfiles("test")
class DynamicKPIDataRepositoryTest {
    
    @Autowired
    private MongoTemplate mongoTemplate;
    
    private DynamicKPIDataRepository repository;
    
    private static final String TEST_COLLECTION = "test_kpi_data_steps";
    
    @BeforeEach
    void setUp() {
        repository = new DynamicKPIDataRepository(mongoTemplate);
        repository.createCollection(TEST_COLLECTION);
    }
    
    @AfterEach
    void tearDown() {
        repository.dropCollection(TEST_COLLECTION);
    }
    
    @Test
    void testCreateAndDropCollection() {
        String testCollection = "test_collection_temp";
        
        assertFalse(repository.collectionExists(testCollection));
        
        repository.createCollection(testCollection);
        assertTrue(repository.collectionExists(testCollection));
        
        repository.dropCollection(testCollection);
        assertFalse(repository.collectionExists(testCollection));
    }
    
    @Test
    void testSaveAndFind() {
        LocalDate testDate = LocalDate.of(2024, 1, 1);
        KPIData data = KPIData.builder()
                .date(testDate)
                .value(100.0)
                .exponentialMovingAverage(95.0)
                .build();
        
        KPIData saved = repository.save(data, TEST_COLLECTION);
        assertNotNull(saved.getId());
        
        Optional<KPIData> found = repository.findByDate(testDate, TEST_COLLECTION);
        assertTrue(found.isPresent());
        assertEquals(testDate, found.get().getDate());
        assertEquals(100.0, found.get().getValue());
        assertEquals(95.0, found.get().getExponentialMovingAverage());
    }
    
    @Test
    void testFindByDateRange() {
        // Create test data for multiple dates
        LocalDate date1 = LocalDate.of(2024, 1, 1);
        LocalDate date2 = LocalDate.of(2024, 1, 2);
        LocalDate date3 = LocalDate.of(2024, 1, 3);
        LocalDate date4 = LocalDate.of(2024, 1, 5); // Gap in dates
        
        repository.save(createTestData(date1, 100.0), TEST_COLLECTION);
        repository.save(createTestData(date2, 110.0), TEST_COLLECTION);
        repository.save(createTestData(date3, 120.0), TEST_COLLECTION);
        repository.save(createTestData(date4, 130.0), TEST_COLLECTION);
        
        // Test date range query
        List<KPIData> results = repository.findByDateBetweenOrderByDateAsc(
                LocalDate.of(2024, 1, 1), 
                LocalDate.of(2024, 1, 3), 
                TEST_COLLECTION
        );
        
        assertEquals(3, results.size());
        assertEquals(date1, results.get(0).getDate());
        assertEquals(date2, results.get(1).getDate());
        assertEquals(date3, results.get(2).getDate());
        
        // Values should be in ascending order by date
        assertEquals(100.0, results.get(0).getValue());
        assertEquals(110.0, results.get(1).getValue());
        assertEquals(120.0, results.get(2).getValue());
    }
    
    @Test
    void testFindAllOrderByDateAsc() {
        LocalDate date1 = LocalDate.of(2024, 1, 3);
        LocalDate date2 = LocalDate.of(2024, 1, 1);
        LocalDate date3 = LocalDate.of(2024, 1, 2);
        
        // Save in random order
        repository.save(createTestData(date1, 300.0), TEST_COLLECTION);
        repository.save(createTestData(date2, 100.0), TEST_COLLECTION);
        repository.save(createTestData(date3, 200.0), TEST_COLLECTION);
        
        List<KPIData> results = repository.findAllOrderByDateAsc(TEST_COLLECTION);
        
        assertEquals(3, results.size());
        // Should be ordered by date ascending
        assertEquals(date2, results.get(0).getDate()); // Jan 1
        assertEquals(date3, results.get(1).getDate()); // Jan 2
        assertEquals(date1, results.get(2).getDate()); // Jan 3
    }
    
    @Test
    void testFindTopNOrderByDateDesc() {
        // Create 5 data points
        for (int i = 1; i <= 5; i++) {
            LocalDate date = LocalDate.of(2024, 1, i);
            repository.save(createTestData(date, i * 10.0), TEST_COLLECTION);
        }
        
        // Get top 3
        List<KPIData> results = repository.findTopNOrderByDateDesc(3, TEST_COLLECTION);
        
        assertEquals(3, results.size());
        // Should be ordered by date descending (most recent first)
        assertEquals(LocalDate.of(2024, 1, 5), results.get(0).getDate());
        assertEquals(LocalDate.of(2024, 1, 4), results.get(1).getDate());
        assertEquals(LocalDate.of(2024, 1, 3), results.get(2).getDate());
    }
    
    @Test
    void testDeleteByDate() {
        LocalDate date1 = LocalDate.of(2024, 1, 1);
        LocalDate date2 = LocalDate.of(2024, 1, 2);
        
        repository.save(createTestData(date1, 100.0), TEST_COLLECTION);
        repository.save(createTestData(date2, 200.0), TEST_COLLECTION);
        
        assertEquals(2, repository.count(TEST_COLLECTION));
        
        repository.deleteByDate(date1, TEST_COLLECTION);
        
        assertEquals(1, repository.count(TEST_COLLECTION));
        assertFalse(repository.findByDate(date1, TEST_COLLECTION).isPresent());
        assertTrue(repository.findByDate(date2, TEST_COLLECTION).isPresent());
    }
    
    @Test
    void testDeleteAll() {
        repository.save(createTestData(LocalDate.of(2024, 1, 1), 100.0), TEST_COLLECTION);
        repository.save(createTestData(LocalDate.of(2024, 1, 2), 200.0), TEST_COLLECTION);
        
        assertEquals(2, repository.count(TEST_COLLECTION));
        
        repository.deleteAll(TEST_COLLECTION);
        
        assertEquals(0, repository.count(TEST_COLLECTION));
    }
    
    @Test
    void testCount() {
        assertEquals(0, repository.count(TEST_COLLECTION));
        
        repository.save(createTestData(LocalDate.of(2024, 1, 1), 100.0), TEST_COLLECTION);
        assertEquals(1, repository.count(TEST_COLLECTION));
        
        repository.save(createTestData(LocalDate.of(2024, 1, 2), 200.0), TEST_COLLECTION);
        assertEquals(2, repository.count(TEST_COLLECTION));
    }
    
    @Test
    void testUpdateExistingData() {
        LocalDate testDate = LocalDate.of(2024, 1, 1);
        KPIData original = createTestData(testDate, 100.0);
        
        KPIData saved = repository.save(original, TEST_COLLECTION);
        String originalId = saved.getId();
        
        // Update the value
        saved.setValue(150.0);
        saved.setExponentialMovingAverage(140.0);
        
        KPIData updated = repository.save(saved, TEST_COLLECTION);
        
        assertEquals(originalId, updated.getId()); // Same ID
        assertEquals(150.0, updated.getValue());
        assertEquals(140.0, updated.getExponentialMovingAverage());
        
        // Verify in database
        Optional<KPIData> found = repository.findByDate(testDate, TEST_COLLECTION);
        assertTrue(found.isPresent());
        assertEquals(150.0, found.get().getValue());
        assertEquals(140.0, found.get().getExponentialMovingAverage());
    }
    
    private KPIData createTestData(LocalDate date, Double value) {
        return KPIData.builder()
                .date(date)
                .value(value)
                .exponentialMovingAverage(value * 0.95) // Simple EMA calculation for testing
                .build();
    }
}
