package habitTracker.Rules;

import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;


@Repository
public interface RuleRepository extends MongoRepository<Rule, Integer> {
    List<Rule> findByStartDateBetween(LocalDate startDate, LocalDate endDate);
    List<Rule> findByEndDateBetween(LocalDate startDate, LocalDate endDate);
    List<Rule> findByStartDateLessThanEqualAndEndDateGreaterThanEqual(LocalDate startDate, LocalDate endDate);
    List<Rule> findByStartDateLessThanEqualAndEndDateGreaterThanEqualAndFrequency(LocalDate startDate, LocalDate endDate, Integer frequency);
    List<Rule> findByName(String name);

}
