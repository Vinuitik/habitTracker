package habitTracker.KPI;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface KPIDataRepository extends MongoRepository<KPIData, String> {
    List<KPIData> findByKpiNameOrderByDateAsc(String kpiName);
    List<KPIData> findByKpiNameAndDateBetweenOrderByDateAsc(String kpiName, LocalDate startDate, LocalDate endDate);
    Optional<KPIData> findByKpiNameAndDate(String kpiName, LocalDate date);
    void deleteByKpiName(String kpiName);
    List<KPIData> findTop30ByKpiNameOrderByDateDesc(String kpiName); // for EMA calculation
}
