package habitTracker.KPI;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KPIHabitMappingRepository extends MongoRepository<KPIHabitMapping, String> {
    List<KPIHabitMapping> findByKpiName(String kpiName);
    List<KPIHabitMapping> findByHabitId(Integer habitId);
    void deleteByKpiName(String kpiName);
    void deleteByHabitId(Integer habitId);
    void deleteByKpiNameAndHabitId(String kpiName, Integer habitId);
}
