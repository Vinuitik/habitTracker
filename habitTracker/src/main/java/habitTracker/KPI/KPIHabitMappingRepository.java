package habitTracker.KPI;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KPIHabitMappingRepository extends MongoRepository<KPIHabitMapping, String> {
    List<KPIHabitMapping> findByKpiName(String kpiName);
    List<KPIHabitMapping> findByKpiNameAndUserId(String kpiName, String userId);
    List<KPIHabitMapping> findByHabitId(Integer habitId);
    List<KPIHabitMapping> findByHabitIdAndUserId(Integer habitId, String userId);
    void deleteByKpiName(String kpiName);
    void deleteByKpiNameAndUserId(String kpiName, String userId);
    void deleteByHabitId(Integer habitId);
    void deleteByKpiNameAndHabitId(String kpiName, Integer habitId);
}
