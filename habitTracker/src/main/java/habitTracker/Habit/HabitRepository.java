package habitTracker.Habit;

import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

@Repository
public interface HabitRepository extends MongoRepository<Habit, Integer> {
    List<Habit> findByStartDateBetween(LocalDate startDate, LocalDate endDate);
    List<Habit> findByEndDateBetween(LocalDate startDate, LocalDate endDate);
    List<Habit> findByStartDateLessThanEqualAndEndDateGreaterThanEqual(LocalDate startDate, LocalDate endDate);
    List<Habit> findByStartDateLessThanEqualAndEndDateGreaterThanEqualAndFrequency(LocalDate startDate, LocalDate endDate, Integer frequency);
    List<Habit> findByName(String name);
    List<Habit> findByCurDate(LocalDate curDate);
}
