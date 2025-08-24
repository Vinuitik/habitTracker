package habitTracker.KPI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KPIServiceTest {
    
    @Mock
    private KPIRepository kpiRepository;
    
    @Mock
    private DynamicKPIDataRepository dynamicKPIDataRepository;
    
    @Mock
    private KPIHabitMappingRepository kpiHabitMappingRepository;
    
    @Mock
    private KPICollectionNameUtil collectionNameUtil;
    
    private KPIService kpiService;
    
    @BeforeEach
    void setUp() {
        kpiService = new KPIService(kpiRepository, dynamicKPIDataRepository, 
                                   kpiHabitMappingRepository, collectionNameUtil);
    }
    
    @Test
    void testCreateKPI_Success() {
        String kpiName = "Daily Steps";
        String description = "Track daily step count";
        Boolean higherIsBetter = true;
        List<Integer> habitIds = Arrays.asList(1, 2);
        String expectedCollectionName = "kpi_data_daily_steps";
        
        when(kpiRepository.existsByName(kpiName)).thenReturn(false);
        when(collectionNameUtil.toCollectionName(kpiName)).thenReturn(expectedCollectionName);
        
        KPI mockKPI = KPI.builder()
                .id("kpi123")
                .name(kpiName)
                .description(description)
                .higherIsBetter(higherIsBetter)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .active(true)
                .build();
        
        when(kpiRepository.save(any(KPI.class))).thenReturn(mockKPI);
        when(kpiHabitMappingRepository.saveAll(anyList())).thenReturn(Arrays.asList());
        
        KPIDTO result = kpiService.createKPI(kpiName, description, higherIsBetter, habitIds);
        
        assertNotNull(result);
        assertEquals(kpiName, result.getName());
        assertEquals(description, result.getDescription());
        assertEquals(higherIsBetter, result.getHigherIsBetter());
        assertEquals(habitIds, result.getLinkedHabitIds());
        
        verify(dynamicKPIDataRepository).createCollection(expectedCollectionName);
        verify(kpiRepository).save(any(KPI.class));
        verify(kpiHabitMappingRepository).saveAll(anyList());
    }
    
    @Test
    void testCreateKPI_DuplicateName() {
        String kpiName = "Daily Steps";
        
        when(kpiRepository.existsByName(kpiName)).thenReturn(true);
        
        assertThrows(IllegalArgumentException.class, () -> 
            kpiService.createKPI(kpiName, "description", true, Arrays.asList()));
        
        verify(dynamicKPIDataRepository, never()).createCollection(anyString());
        verify(kpiRepository, never()).save(any(KPI.class));
    }
    
    @Test
    void testAddKPIData_NewData() {
        String kpiName = "Daily Steps";
        LocalDate date = LocalDate.of(2024, 1, 1);
        Double value = 10000.0;
        String collectionName = "kpi_data_daily_steps";
        
        when(kpiRepository.existsByName(kpiName)).thenReturn(true);
        when(collectionNameUtil.toCollectionName(kpiName)).thenReturn(collectionName);
        when(dynamicKPIDataRepository.findByDate(date, collectionName)).thenReturn(Optional.empty());
        when(dynamicKPIDataRepository.findTopNOrderByDateDesc(30, collectionName)).thenReturn(Arrays.asList());
        
        kpiService.addKPIData(kpiName, date, value);
        
        verify(dynamicKPIDataRepository).save(any(KPIData.class), eq(collectionName));
    }
    
    @Test
    void testAddKPIData_UpdateExisting() {
        String kpiName = "Daily Steps";
        LocalDate date = LocalDate.of(2024, 1, 1);
        Double value = 12000.0;
        String collectionName = "kpi_data_daily_steps";
        
        KPIData existingData = KPIData.builder()
                .id("data123")
                .date(date)
                .value(10000.0)
                .exponentialMovingAverage(9500.0)
                .build();
        
        when(kpiRepository.existsByName(kpiName)).thenReturn(true);
        when(collectionNameUtil.toCollectionName(kpiName)).thenReturn(collectionName);
        when(dynamicKPIDataRepository.findByDate(date, collectionName)).thenReturn(Optional.of(existingData));
        when(dynamicKPIDataRepository.findTopNOrderByDateDesc(30, collectionName)).thenReturn(Arrays.asList(existingData));
        
        kpiService.addKPIData(kpiName, date, value);
        
        verify(dynamicKPIDataRepository).save(argThat(data -> 
            data.getValue().equals(value) && data.getId().equals("data123")), eq(collectionName));
    }
    
    @Test
    void testAddKPIData_KPINotExists() {
        String kpiName = "Nonexistent KPI";
        
        when(kpiRepository.existsByName(kpiName)).thenReturn(false);
        
        assertThrows(IllegalArgumentException.class, () -> 
            kpiService.addKPIData(kpiName, LocalDate.now(), 100.0));
        
        verify(dynamicKPIDataRepository, never()).save(any(KPIData.class), anyString());
    }
    
    @Test
    void testGetKPIDataForDateRange() {
        String kpiName = "Daily Steps";
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 3);
        String collectionName = "kpi_data_daily_steps";
        
        KPI mockKPI = KPI.builder()
                .name(kpiName)
                .higherIsBetter(true)
                .build();
        
        List<KPIData> mockData = Arrays.asList(
            KPIData.builder().date(LocalDate.of(2024, 1, 1)).value(1000.0).exponentialMovingAverage(950.0).build(),
            KPIData.builder().date(LocalDate.of(2024, 1, 2)).value(1100.0).exponentialMovingAverage(1000.0).build(),
            KPIData.builder().date(LocalDate.of(2024, 1, 3)).value(1200.0).exponentialMovingAverage(1100.0).build()
        );
        
        when(collectionNameUtil.toCollectionName(kpiName)).thenReturn(collectionName);
        when(dynamicKPIDataRepository.findByDateBetweenOrderByDateAsc(startDate, endDate, collectionName)).thenReturn(mockData);
        when(kpiRepository.findByName(kpiName)).thenReturn(Optional.of(mockKPI));
        
        List<KPIDataDTO> result = kpiService.getKPIDataForDateRange(kpiName, startDate, endDate);
        
        assertEquals(3, result.size());
        assertEquals(kpiName, result.get(0).getKpiName());
        assertEquals(1000.0, result.get(0).getValue());
        assertEquals(LocalDate.of(2024, 1, 1), result.get(0).getDate());
    }
    
    @Test
    void testGetKPIDataForDateRange_KPINotFound() {
        String kpiName = "Nonexistent KPI";
        String collectionName = "kpi_data_nonexistent_kpi";
        
        when(collectionNameUtil.toCollectionName(kpiName)).thenReturn(collectionName);
        when(dynamicKPIDataRepository.findByDateBetweenOrderByDateAsc(any(), any(), eq(collectionName))).thenReturn(Arrays.asList());
        when(kpiRepository.findByName(kpiName)).thenReturn(Optional.empty());
        
        List<KPIDataDTO> result = kpiService.getKPIDataForDateRange(kpiName, LocalDate.now(), LocalDate.now());
        
        assertTrue(result.isEmpty());
    }
    
    @Test
    void testDeleteKPI() {
        String kpiName = "Daily Steps";
        String collectionName = "kpi_data_daily_steps";
        
        KPI mockKPI = KPI.builder()
                .id("kpi123")
                .name(kpiName)
                .build();
        
        when(collectionNameUtil.toCollectionName(kpiName)).thenReturn(collectionName);
        when(kpiRepository.findByName(kpiName)).thenReturn(Optional.of(mockKPI));
        
        kpiService.deleteKPI(kpiName);
        
        verify(dynamicKPIDataRepository).dropCollection(collectionName);
        verify(kpiHabitMappingRepository).deleteByKpiName(kpiName);
        verify(kpiRepository).delete(mockKPI);
    }
    
    @Test
    void testGetWeeklyKPIData() {
        String kpiName = "Daily Steps";
        String collectionName = "kpi_data_daily_steps";
        
        when(collectionNameUtil.toCollectionName(kpiName)).thenReturn(collectionName);
        when(dynamicKPIDataRepository.findByDateBetweenOrderByDateAsc(any(), any(), eq(collectionName))).thenReturn(Arrays.asList());
        when(kpiRepository.findByName(kpiName)).thenReturn(Optional.of(KPI.builder().higherIsBetter(true).build()));
        
        List<KPIDataDTO> result = kpiService.getWeeklyKPIData(kpiName);
        
        assertNotNull(result);
        verify(dynamicKPIDataRepository).findByDateBetweenOrderByDateAsc(any(), any(), eq(collectionName));
    }
    
    @Test
    void testGetMonthlyKPIData() {
        String kpiName = "Daily Steps";
        String collectionName = "kpi_data_daily_steps";
        
        when(collectionNameUtil.toCollectionName(kpiName)).thenReturn(collectionName);
        when(dynamicKPIDataRepository.findByDateBetweenOrderByDateAsc(any(), any(), eq(collectionName))).thenReturn(Arrays.asList());
        when(kpiRepository.findByName(kpiName)).thenReturn(Optional.of(KPI.builder().higherIsBetter(true).build()));
        
        List<KPIDataDTO> result = kpiService.getMonthlyKPIData(kpiName);
        
        assertNotNull(result);
        verify(dynamicKPIDataRepository).findByDateBetweenOrderByDateAsc(any(), any(), eq(collectionName));
    }
}
