package habitTracker.KPI.deploy;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface KpiDeviceReadingRepository extends MongoRepository<KpiDeviceReading, String> {
    Optional<KpiDeviceReading> findByKpiIdAndDeviceIdAndDate(String kpiId, String deviceId, LocalDate date);

    List<KpiDeviceReading> findByKpiIdAndDate(String kpiId, LocalDate date);

    List<KpiDeviceReading> findByKpiIdAndDateBetweenOrderByDateAscDeviceIdAsc(String kpiId, LocalDate from, LocalDate to);
}
