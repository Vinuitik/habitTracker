package habitTracker.KPI;

import habitTracker.auth.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KPIService {
    
    private final KPIRepository kpiRepository;
    private final DynamicKPIDataRepository dynamicKPIDataRepository;
    private final KPIHabitMappingRepository kpiHabitMappingRepository;
    private final KPICollectionNameUtil collectionNameUtil;
    
    @Transactional
    public KPIDTO createKPI(String name, String description, Boolean higherIsBetter, List<Integer> habitIds,
                             Boolean autoFillEnabled, Double defaultValue) {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId != null ? kpiRepository.existsByNameAndUserId(name, userId) : kpiRepository.existsByName(name)) {
            throw new IllegalArgumentException("KPI with name '" + name + "' already exists");
        }
        if (Boolean.TRUE.equals(autoFillEnabled) && defaultValue == null) {
            throw new IllegalArgumentException("defaultValue is required when autoFillEnabled is true");
        }

        KPI kpi = KPI.builder()
                .name(name)
                .description(description)
                .higherIsBetter(higherIsBetter)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .active(true)
                .userId(userId)
                .autoFillEnabled(Boolean.TRUE.equals(autoFillEnabled))
                .defaultValue(Boolean.TRUE.equals(autoFillEnabled) ? defaultValue : null)
                .build();

        KPI savedKPI = kpiRepository.save(kpi);

        // Create collection for this KPI's data, keyed by the KPI's own id (see
        // KPICollectionNameUtil) so same-named KPIs from different users never collide.
        String collectionName = collectionNameUtil.toCollectionName(savedKPI.getId());
        dynamicKPIDataRepository.createCollection(collectionName);
        
        // Create habit mappings
        if (habitIds != null && !habitIds.isEmpty()) {
            String finalUserId = userId;
            List<KPIHabitMapping> mappings = habitIds.stream()
                    .map(habitId -> KPIHabitMapping.builder()
                            .kpiName(name)
                            .habitId(habitId)
                            .userId(finalUserId)
                            .build())
                    .collect(Collectors.toList());
            kpiHabitMappingRepository.saveAll(mappings);
        }
        
        return convertToDTO(savedKPI, habitIds);
    }
    
    public List<KPIDTO> getAllActiveKPIs() {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return List.of();
        List<KPI> kpis = kpiRepository.findByActiveAndUserId(true, userId);
        return kpis.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    public Optional<KPIDTO> getKPIByName(String name) {
        String userId = SecurityUtils.getCurrentUserId();
        return kpiRepository.findByNameAndUserId(name, userId)
                .map(this::convertToDTO);
    }
    
    @Transactional
    public void addKPIData(String kpiName, LocalDate date, Double value) {
        String userId = SecurityUtils.getCurrentUserId();
        KPI kpi = kpiRepository.findByNameAndUserId(kpiName, userId)
                .orElseThrow(() -> new IllegalArgumentException("KPI with name '" + kpiName + "' does not exist"));

        // A manually-entered value always wins and clears any prior auto-filled flag.
        saveKPIDataPoint(kpi, date, value, false);
    }

    /**
     * Cron entry point (habitTracker.updater.KPIDefaultFillService): fills in the KPI's
     * defaultValue for the given date if, and only if, no data point already exists for it.
     * Takes an already-resolved KPI (the cron scans all users' KPIs directly via MongoTemplate,
     * the same way HabitUpdateService does for habits) rather than going through
     * SecurityUtils.getCurrentUserId(), since there is no authenticated request on a cron thread.
     * Writes only into this KPI's own collection (kpi.getId()) — never touches another user's data.
     */
    @Transactional
    public boolean fillDefaultIfMissing(KPI kpi, LocalDate date) {
        if (!Boolean.TRUE.equals(kpi.getAutoFillEnabled()) || kpi.getDefaultValue() == null) {
            return false;
        }
        String collectionName = collectionNameUtil.toCollectionName(kpi.getId());
        if (dynamicKPIDataRepository.findByDate(date, collectionName).isPresent()) {
            return false; // already has a value (manual or previously auto-filled) — never overwrite
        }
        saveKPIDataPoint(kpi, date, kpi.getDefaultValue(), true);
        return true;
    }

    @Transactional
    public KPIDTO updateDefaultFillSettings(String kpiName, Boolean autoFillEnabled, Double defaultValue) {
        String userId = SecurityUtils.getCurrentUserId();
        KPI kpi = kpiRepository.findByNameAndUserId(kpiName, userId)
                .orElseThrow(() -> new IllegalArgumentException("KPI with name '" + kpiName + "' does not exist"));
        if (Boolean.TRUE.equals(autoFillEnabled) && defaultValue == null) {
            throw new IllegalArgumentException("defaultValue is required when autoFillEnabled is true");
        }

        kpi.setAutoFillEnabled(Boolean.TRUE.equals(autoFillEnabled));
        kpi.setDefaultValue(Boolean.TRUE.equals(autoFillEnabled) ? defaultValue : null);
        kpi.setUpdatedAt(LocalDateTime.now());
        KPI saved = kpiRepository.save(kpi);
        return convertToDTO(saved);
    }

    private void saveKPIDataPoint(KPI kpi, LocalDate date, Double value, boolean autoFilled) {
        String collectionName = collectionNameUtil.toCollectionName(kpi.getId());

        Optional<KPIData> existingData = dynamicKPIDataRepository.findByDate(date, collectionName);

        KPIData kpiData;
        if (existingData.isPresent()) {
            kpiData = existingData.get();
            kpiData.setValue(value);
        } else {
            kpiData = KPIData.builder()
                    .date(date)
                    .value(value)
                    .build();
        }
        kpiData.setAutoFilled(autoFilled);

        Double ema = calculateEMA(collectionName, value);
        kpiData.setExponentialMovingAverage(ema);

        dynamicKPIDataRepository.save(kpiData, collectionName);
    }

    public List<KPIDataDTO> getKPIDataForDateRange(String kpiName, LocalDate startDate, LocalDate endDate) {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return new ArrayList<>();
        Optional<KPI> kpi = kpiRepository.findByNameAndUserId(kpiName, userId);

        if (kpi.isEmpty()) {
            return new ArrayList<>();
        }

        String collectionName = collectionNameUtil.toCollectionName(kpi.get().getId());
        List<KPIData> data = dynamicKPIDataRepository.findByDateBetweenOrderByDateAsc(startDate, endDate, collectionName);
        boolean higherIsBetter = kpi.get().getHigherIsBetter();

        return data.stream()
                .map(d -> convertToDataDTO(d, kpiName, higherIsBetter))
                .collect(Collectors.toList());
    }
    
    public List<KPIDataDTO> getWeeklyKPIData(String kpiName) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(7);
        return getKPIDataForDateRange(kpiName, startDate, endDate);
    }

    public List<KPIDataDTO> getMonthlyKPIData(String kpiName) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(30);
        return getKPIDataForDateRange(kpiName, startDate, endDate);
    }

    public List<KPIDataDTO> getAllTimeKPIData(String kpiName) {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return new ArrayList<>();
        Optional<KPI> kpi = kpiRepository.findByNameAndUserId(kpiName, userId);
        if (kpi.isEmpty()) return new ArrayList<>();

        String collectionName = collectionNameUtil.toCollectionName(kpi.get().getId());
        List<KPIData> data = dynamicKPIDataRepository.findAllOrderByDateAsc(collectionName);
        boolean higherIsBetter = kpi.get().getHigherIsBetter();
        return data.stream()
                .map(d -> convertToDataDTO(d, kpiName, higherIsBetter))
                .collect(Collectors.toList());
    }

    public List<KPIDTO> getKPIsByHabitId(Integer habitId) {
        String userId = SecurityUtils.getCurrentUserId();
        if (userId == null) return new ArrayList<>();
        List<KPIHabitMapping> mappings = kpiHabitMappingRepository.findByHabitIdAndUserId(habitId, userId);
        if (mappings == null || mappings.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> kpiNames = mappings.stream()
                .map(KPIHabitMapping::getKpiName)
                .collect(Collectors.toList());

        // Fetch this user's KPIs matching those names in a single call
        List<KPI> kpis = kpiRepository.findByNameIn(kpiNames).stream()
                .filter(k -> userId.equals(k.getUserId()))
                .collect(Collectors.toList());
        Map<String, KPI> kpiByName = kpis.stream()
                .collect(Collectors.toMap(KPI::getName, k -> k));

        // Preserve original order from kpiNames, filter out missing KPIs
        return kpiNames.stream()
                .map(kpiByName::get)
                .filter(Objects::nonNull)
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteKPI(String name) {
        String userId = SecurityUtils.getCurrentUserId();
        KPI kpi = kpiRepository.findByNameAndUserId(name, userId)
                .orElseThrow(() -> new IllegalArgumentException("KPI with name '" + name + "' does not exist"));
        String collectionName = collectionNameUtil.toCollectionName(kpi.getId());
        dynamicKPIDataRepository.dropCollection(collectionName);
        kpiHabitMappingRepository.deleteByKpiNameAndUserId(name, userId);
        kpiRepository.delete(kpi);
    }

    @Transactional
    public void updateKPIHabitMappings(String kpiName, List<Integer> habitIds) {
        String userId = SecurityUtils.getCurrentUserId();
        // Remove existing mappings (scoped to this user's own mappings for this KPI name)
        kpiHabitMappingRepository.deleteByKpiNameAndUserId(kpiName, userId);

        // Add new mappings
        if (habitIds != null && !habitIds.isEmpty()) {
            List<KPIHabitMapping> mappings = habitIds.stream()
                    .map(habitId -> KPIHabitMapping.builder()
                            .kpiName(kpiName)
                            .habitId(habitId)
                            .userId(userId)
                            .build())
                    .collect(Collectors.toList());
            kpiHabitMappingRepository.saveAll(mappings);
        }
    }

    private Double calculateEMA(String collectionName, Double currentValue) {
        List<KPIData> historicalData = dynamicKPIDataRepository.findTopNOrderByDateDesc(30, collectionName);
        
        if (historicalData.isEmpty()) {
            return currentValue; // First data point
        }
        
        // Use 14-day EMA (smoothing factor = 2/(N+1) = 2/15 ≈ 0.133)
        double smoothingFactor = 2.0 / 15.0;
        
        // Find the most recent EMA
        Double previousEMA = historicalData.get(0).getExponentialMovingAverage();
        if (previousEMA == null) {
            previousEMA = currentValue;
        }
        
        // EMA = (CurrentValue * SmoothingFactor) + (PreviousEMA * (1 - SmoothingFactor))
        return (currentValue * smoothingFactor) + (previousEMA * (1 - smoothingFactor));
    }
    
    private KPIDTO convertToDTO(KPI kpi) {
        List<KPIHabitMapping> mappings = kpiHabitMappingRepository.findByKpiNameAndUserId(kpi.getName(), kpi.getUserId());
        List<Integer> habitIds = mappings.stream()
                .map(KPIHabitMapping::getHabitId)
                .collect(Collectors.toList());
        
        return convertToDTO(kpi, habitIds);
    }
    
    private KPIDTO convertToDTO(KPI kpi, List<Integer> habitIds) {
        return KPIDTO.builder()
                .id(kpi.getId())
                .name(kpi.getName())
                .description(kpi.getDescription())
                .higherIsBetter(kpi.getHigherIsBetter())
                .createdAt(kpi.getCreatedAt())
                .updatedAt(kpi.getUpdatedAt())
                .active(kpi.getActive())
                .linkedHabitIds(habitIds != null ? habitIds : new ArrayList<>())
                .autoFillEnabled(Boolean.TRUE.equals(kpi.getAutoFillEnabled()))
                .defaultValue(kpi.getDefaultValue())
                .build();
    }
    
    private KPIDataDTO convertToDataDTO(KPIData data, String kpiName, boolean higherIsBetter) {
        String trendDirection = "stable";
        String colorIntensity = "low";
        
        if (data.getExponentialMovingAverage() != null && data.getValue() != null) {
            double diff = data.getValue() - data.getExponentialMovingAverage();
            double percentChange = Math.abs(diff / data.getExponentialMovingAverage()) * 100;
            
            // Determine trend direction
            if (diff > 0) {
                trendDirection = "up";
            } else if (diff < 0) {
                trendDirection = "down";
            }
            
            // Determine color intensity based on percentage change
            if (percentChange > 10) {
                colorIntensity = "high";
            } else if (percentChange > 5) {
                colorIntensity = "medium";
            }
        }
        
        return KPIDataDTO.builder()
                .id(data.getId())
                .kpiName(kpiName)
                .date(data.getDate())
                .value(data.getValue())
                .exponentialMovingAverage(data.getExponentialMovingAverage())
                .trendDirection(trendDirection)
                .colorIntensity(colorIntensity)
                .higherIsBetter(higherIsBetter)
                .autoFilled(Boolean.TRUE.equals(data.getAutoFilled()))
                .build();
    }
}
