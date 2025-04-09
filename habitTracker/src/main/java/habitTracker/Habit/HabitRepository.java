package habitTracker.Habit;

import org.springframework.stereotype.Repository;

import java.time.LocalDate;

import org.springframework.data.mongodb.repository.MongoRepository;

@Repository
public interface HabitRepository extends MongoRepository<Habit, String> {

}
