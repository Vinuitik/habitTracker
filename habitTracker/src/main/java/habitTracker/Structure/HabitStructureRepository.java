package habitTracker.Structure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HabitStructureRepository extends MongoRepository<HabitStructure, String> {
    List<HabitStructure> findByStructureDate(LocalDate date);
    List<HabitStructure> findByStructureDateBetween(LocalDate startDate, LocalDate endDate);
    List<HabitStructure> findByHabitId(Integer habitName);
    Optional<HabitStructure> findByHabitIdAndStructureDate(Integer habitName, LocalDate date);
    void deleteByHabitIdAndStructureDate(Integer habitName, LocalDate date);
    boolean existsByHabitIdAndStructureDate(Integer habitName, LocalDate date);
}