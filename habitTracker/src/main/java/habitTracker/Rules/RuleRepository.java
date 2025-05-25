package habitTracker.Rules;

import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;


@Repository
public interface RuleRepository extends MongoRepository<Rule, String> {
    void deleteByHabitSubId(Integer subId);
    List<Rule> findByHabitOwnerId(Integer mainId);
    List<Rule> findByHabitSubId(Integer subId);
}
