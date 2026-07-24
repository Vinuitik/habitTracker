package habitTracker.KPI;

import habitTracker.auth.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
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

    private static final String USER_ID = "user-1";

    @Mock
    private KPIRepository kpiRepository;

    @Mock
    private DynamicKPIDataRepository dynamicKPIDataRepository;

    @Mock
    private KPIHabitMappingRepository kpiHabitMappingRepository;

    @Mock
    private KPICollectionNameUtil collectionNameUtil;

    private KPIService kpiService;
    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUp() {
        kpiService = new KPIService(kpiRepository, dynamicKPIDataRepository,
                                   kpiHabitMappingRepository, collectionNameUtil);
        securityUtils = mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    @Test
    void testCreateKPI_Success() {
        String kpiName = "Daily Steps";
        String description = "Track daily step count";
        Boolean higherIsBetter = true;
        List<Integer> habitIds = Arrays.asList(1, 2);
        String kpiId = "kpi123";
        String expectedCollectionName = "kpi_data_kpi123";

        when(kpiRepository.existsByNameAndUserId(kpiName, USER_ID)).thenReturn(false);
        when(collectionNameUtil.toCollectionName(kpiId)).thenReturn(expectedCollectionName);

        KPI mockKPI = KPI.builder()
                .id(kpiId)
                .name(kpiName)
                .description(description)
                .higherIsBetter(higherIsBetter)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .active(true)
                .userId(USER_ID)
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

        when(kpiRepository.existsByNameAndUserId(kpiName, USER_ID)).thenReturn(true);

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
        String kpiId = "kpi123";
        String collectionName = "kpi_data_kpi123";
        KPI mockKPI = KPI.builder().id(kpiId).name(kpiName).userId(USER_ID).build();

        when(kpiRepository.findByNameAndUserId(kpiName, USER_ID)).thenReturn(Optional.of(mockKPI));
        when(collectionNameUtil.toCollectionName(kpiId)).thenReturn(collectionName);
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
        String kpiId = "kpi123";
        String collectionName = "kpi_data_kpi123";
        KPI mockKPI = KPI.builder().id(kpiId).name(kpiName).userId(USER_ID).build();

        KPIData existingData = KPIData.builder()
                .id("data123")
                .date(date)
                .value(10000.0)
                .exponentialMovingAverage(9500.0)
                .build();

        when(kpiRepository.findByNameAndUserId(kpiName, USER_ID)).thenReturn(Optional.of(mockKPI));
        when(collectionNameUtil.toCollectionName(kpiId)).thenReturn(collectionName);
        when(dynamicKPIDataRepository.findByDate(date, collectionName)).thenReturn(Optional.of(existingData));
        when(dynamicKPIDataRepository.findTopNOrderByDateDesc(30, collectionName)).thenReturn(Arrays.asList(existingData));

        kpiService.addKPIData(kpiName, date, value);

        verify(dynamicKPIDataRepository).save(argThat(data ->
            data.getValue().equals(value) && data.getId().equals("data123")), eq(collectionName));
    }

    @Test
    void testAddKPIData_KPINotExists() {
        String kpiName = "Nonexistent KPI";

        when(kpiRepository.findByNameAndUserId(kpiName, USER_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
            kpiService.addKPIData(kpiName, LocalDate.now(), 100.0));

        verify(dynamicKPIDataRepository, never()).save(any(KPIData.class), anyString());
    }

    @Test
    void testAddKPIData_DoesNotTouchAnotherUsersKPIWithSameName() {
        // The KPI repo lookup is scoped to (name, USER_ID); another user's same-named KPI
        // must never resolve here, even though findByName(kpiName) alone would find it.
        String kpiName = "Weight";
        when(kpiRepository.findByNameAndUserId(kpiName, USER_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
            kpiService.addKPIData(kpiName, LocalDate.now(), 1.0));

        verify(kpiRepository, never()).findByName(anyString());
    }

    @Test
    void testGetKPIDataForDateRange() {
        String kpiName = "Daily Steps";
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        LocalDate endDate = LocalDate.of(2024, 1, 3);
        String kpiId = "kpi123";
        String collectionName = "kpi_data_kpi123";

        KPI mockKPI = KPI.builder()
                .id(kpiId)
                .name(kpiName)
                .higherIsBetter(true)
                .userId(USER_ID)
                .build();

        List<KPIData> mockData = Arrays.asList(
            KPIData.builder().date(LocalDate.of(2024, 1, 1)).value(1000.0).exponentialMovingAverage(950.0).build(),
            KPIData.builder().date(LocalDate.of(2024, 1, 2)).value(1100.0).exponentialMovingAverage(1000.0).build(),
            KPIData.builder().date(LocalDate.of(2024, 1, 3)).value(1200.0).exponentialMovingAverage(1100.0).build()
        );

        when(kpiRepository.findByNameAndUserId(kpiName, USER_ID)).thenReturn(Optional.of(mockKPI));
        when(collectionNameUtil.toCollectionName(kpiId)).thenReturn(collectionName);
        when(dynamicKPIDataRepository.findByDateBetweenOrderByDateAsc(startDate, endDate, collectionName)).thenReturn(mockData);

        List<KPIDataDTO> result = kpiService.getKPIDataForDateRange(kpiName, startDate, endDate);

        assertEquals(3, result.size());
        assertEquals(kpiName, result.get(0).getKpiName());
        assertEquals(1000.0, result.get(0).getValue());
        assertEquals(LocalDate.of(2024, 1, 1), result.get(0).getDate());
    }

    @Test
    void testGetKPIDataForDateRange_KPINotFound() {
        String kpiName = "Nonexistent KPI";

        when(kpiRepository.findByNameAndUserId(kpiName, USER_ID)).thenReturn(Optional.empty());

        List<KPIDataDTO> result = kpiService.getKPIDataForDateRange(kpiName, LocalDate.now(), LocalDate.now());

        assertTrue(result.isEmpty());
        verify(dynamicKPIDataRepository, never()).findByDateBetweenOrderByDateAsc(any(), any(), anyString());
    }

    @Test
    void testDeleteKPI() {
        String kpiName = "Daily Steps";
        String kpiId = "kpi123";
        String collectionName = "kpi_data_kpi123";

        KPI mockKPI = KPI.builder()
                .id(kpiId)
                .name(kpiName)
                .userId(USER_ID)
                .build();

        when(kpiRepository.findByNameAndUserId(kpiName, USER_ID)).thenReturn(Optional.of(mockKPI));
        when(collectionNameUtil.toCollectionName(kpiId)).thenReturn(collectionName);

        kpiService.deleteKPI(kpiName);

        verify(dynamicKPIDataRepository).dropCollection(collectionName);
        verify(kpiHabitMappingRepository).deleteByKpiNameAndUserId(kpiName, USER_ID);
        verify(kpiRepository).delete(mockKPI);
    }

    @Test
    void testDeleteKPI_DoesNotDeleteAnotherUsersKPIWithSameName() {
        String kpiName = "Weight";
        when(kpiRepository.findByNameAndUserId(kpiName, USER_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> kpiService.deleteKPI(kpiName));

        verify(dynamicKPIDataRepository, never()).dropCollection(anyString());
        verify(kpiHabitMappingRepository, never()).deleteByKpiName(anyString());
        verify(kpiRepository, never()).delete(any(KPI.class));
    }

    @Test
    void testGetWeeklyKPIData() {
        String kpiName = "Daily Steps";
        String kpiId = "kpi123";
        String collectionName = "kpi_data_kpi123";
        KPI mockKPI = KPI.builder().id(kpiId).higherIsBetter(true).userId(USER_ID).build();

        when(kpiRepository.findByNameAndUserId(kpiName, USER_ID)).thenReturn(Optional.of(mockKPI));
        when(collectionNameUtil.toCollectionName(kpiId)).thenReturn(collectionName);
        when(dynamicKPIDataRepository.findByDateBetweenOrderByDateAsc(any(), any(), eq(collectionName))).thenReturn(Arrays.asList());

        List<KPIDataDTO> result = kpiService.getWeeklyKPIData(kpiName);

        assertNotNull(result);
        verify(dynamicKPIDataRepository).findByDateBetweenOrderByDateAsc(any(), any(), eq(collectionName));
    }

    @Test
    void testGetMonthlyKPIData() {
        String kpiName = "Daily Steps";
        String kpiId = "kpi123";
        String collectionName = "kpi_data_kpi123";
        KPI mockKPI = KPI.builder().id(kpiId).higherIsBetter(true).userId(USER_ID).build();

        when(kpiRepository.findByNameAndUserId(kpiName, USER_ID)).thenReturn(Optional.of(mockKPI));
        when(collectionNameUtil.toCollectionName(kpiId)).thenReturn(collectionName);
        when(dynamicKPIDataRepository.findByDateBetweenOrderByDateAsc(any(), any(), eq(collectionName))).thenReturn(Arrays.asList());

        List<KPIDataDTO> result = kpiService.getMonthlyKPIData(kpiName);

        assertNotNull(result);
        verify(dynamicKPIDataRepository).findByDateBetweenOrderByDateAsc(any(), any(), eq(collectionName));
    }
}
