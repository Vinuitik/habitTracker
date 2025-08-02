package habitTracker.KPI;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KPIService {
    
    private final KPIRepository kpiRepository;
    private final KPIDataRepository kpiDataRepository;
    private final KPIHabitMappingRepository kpiHabitMappingRepository;
    
    @Transactional
    public KPIDTO createKPI(String name, String description, Boolean higherIsBetter, List<Integer> habitIds) {
        if (kpiRepository.existsByName(name)) {
            throw new IllegalArgumentException("KPI with name '" + name + "' already exists");
        }
        
        KPI kpi = KPI.builder()
                .name(name)
                .description(description)
                .higherIsBetter(higherIsBetter)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .active(true)
                .build();
        
        KPI savedKPI = kpiRepository.save(kpi);
        
        // Create habit mappings
        if (habitIds != null && !habitIds.isEmpty()) {
            List<KPIHabitMapping> mappings = habitIds.stream()
                    .map(habitId -> KPIHabitMapping.builder()
                            .kpiName(name)
                            .habitId(habitId)
                            .build())
                    .collect(Collectors.toList());
            kpiHabitMappingRepository.saveAll(mappings);
        }
        
        return convertToDTO(savedKPI, habitIds);
    }
    
    public List<KPIDTO> getAllActiveKPIs() {
        List<KPI> kpis = kpiRepository.findByActive(true);
        return kpis.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    public Optional<KPIDTO> getKPIByName(String name) {
        return kpiRepository.findByName(name)
                .map(this::convertToDTO);
    }
    
    @Transactional
    public void addKPIData(String kpiName, LocalDate date, Double value) {
        // Check if KPI exists
        if (!kpiRepository.existsByName(kpiName)) {
            throw new IllegalArgumentException("KPI with name '" + kpiName + "' does not exist");
        }
        
        // Check if data for this date already exists
        Optional<KPIData> existingData = kpiDataRepository.findByKpiNameAndDate(kpiName, date);
        
        KPIData kpiData;
        if (existingData.isPresent()) {
            kpiData = existingData.get();
            kpiData.setValue(value);
        } else {
            kpiData = KPIData.builder()
                    .kpiName(kpiName)
                    .date(date)
                    .value(value)
                    .build();
        }
        
        // Calculate EMA
        Double ema = calculateEMA(kpiName, value, date);
        kpiData.setExponentialMovingAverage(ema);
        
        kpiDataRepository.save(kpiData);
    }
    
    public List<KPIDataDTO> getKPIDataForDateRange(String kpiName, LocalDate startDate, LocalDate endDate) {
        List<KPIData> data = kpiDataRepository.findByKpiNameAndDateBetweenOrderByDateAsc(kpiName, startDate, endDate);
        Optional<KPI> kpi = kpiRepository.findByName(kpiName);
        
        if (kpi.isEmpty()) {
            return new ArrayList<>();
        }
        
        boolean higherIsBetter = kpi.get().getHigherIsBetter();
        
        return data.stream()
                .map(d -> convertToDataDTO(d, higherIsBetter))
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
    
    public List<KPIDTO> getKPIsByHabitId(Integer habitId) {
        List<KPIHabitMapping> mappings = kpiHabitMappingRepository.findByHabitId(habitId);
        List<String> kpiNames = mappings.stream()
                .map(KPIHabitMapping::getKpiName)
                .collect(Collectors.toList());
        
        return kpiNames.stream()
                .map(name -> kpiRepository.findByName(name))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    @Transactional
    public void deleteKPI(String name) {
        kpiDataRepository.deleteByKpiName(name);
        kpiHabitMappingRepository.deleteByKpiName(name);
        kpiRepository.findByName(name).ifPresent(kpiRepository::delete);
    }
    
    @Transactional
    public void updateKPIHabitMappings(String kpiName, List<Integer> habitIds) {
        // Remove existing mappings
        kpiHabitMappingRepository.deleteByKpiName(kpiName);
        
        // Add new mappings
        if (habitIds != null && !habitIds.isEmpty()) {
            List<KPIHabitMapping> mappings = habitIds.stream()
                    .map(habitId -> KPIHabitMapping.builder()
                            .kpiName(kpiName)
                            .habitId(habitId)
                            .build())
                    .collect(Collectors.toList());
            kpiHabitMappingRepository.saveAll(mappings);
        }
    }
    
    private Double calculateEMA(String kpiName, Double currentValue, LocalDate currentDate) {
        List<KPIData> historicalData = kpiDataRepository.findTop30ByKpiNameOrderByDateDesc(kpiName);
        
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
        List<KPIHabitMapping> mappings = kpiHabitMappingRepository.findByKpiName(kpi.getName());
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
                .build();
    }
    
    private KPIDataDTO convertToDataDTO(KPIData data, boolean higherIsBetter) {
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
                .kpiName(data.getKpiName())
                .date(data.getDate())
                .value(data.getValue())
                .exponentialMovingAverage(data.getExponentialMovingAverage())
                .trendDirection(trendDirection)
                .colorIntensity(colorIntensity)
                .build();
    }
}
