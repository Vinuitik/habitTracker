package habitTracker.Structure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface HabitStructureRepository extends MongoRepository<HabitStructure, String> {
    List<HabitStructure> findByStructureDate(LocalDate date);
    List<HabitStructure> findByStructureDateAndUserId(LocalDate date, String userId);
    List<HabitStructure> findByStructureDateBetween(LocalDate startDate, LocalDate endDate);
    List<HabitStructure> findByStructureDateBetweenAndUserId(LocalDate startDate, LocalDate endDate, String userId);
    List<HabitStructure> findByHabitId(Integer habitName);
    Optional<HabitStructure> findByHabitIdAndStructureDate(Integer habitName, LocalDate date);
    void deleteByHabitIdAndStructureDate(Integer habitName, LocalDate date);
    boolean existsByHabitIdAndStructureDate(Integer habitName, LocalDate date);
    // Grace-window resolution: is there a completed occurrence anywhere in [start, end] (inclusive bounds)?
    // NOTE: hand-written because Spring Data Mongo's derived "Between" keyword compiles to strict
    // $gt/$lt (breaking single-day windows where start == end), and chaining separate
    // GreaterThanEqual/LessThanEqual keywords on the same field isn't supported by the query builder.
    @Query(value = "{ 'habitId': ?0, 'completed': ?1, 'structureDate': { $gte: ?2, $lte: ?3 } }", exists = true)
    boolean existsByHabitIdAndCompletedInWindow(Integer habitId, Boolean completed,
                                                 LocalDate startDate, LocalDate endDate);
}