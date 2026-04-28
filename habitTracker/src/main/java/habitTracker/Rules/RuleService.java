package habitTracker.Rules;

import java.util.List;

import org.springframework.stereotype.Service;

import habitTracker.auth.SecurityUtils;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RuleService {
    private final RuleRepository ruleRepository;

    public void addRule(Integer mainId, List<Integer> subIds) {
        String userId = SecurityUtils.getCurrentUserId();
        List<Rule> rules = subIds.stream()
            .map(subId -> Rule.builder()
            .habitOwnerId(mainId)
            .habitSubId(subId)
            .userId(userId)
            .build())
            .toList();
        ruleRepository.saveAll(rules);
    }

    public void deleteBySubId(Integer subId) {
        ruleRepository.deleteByHabitSubId(subId);
    }
    public List<Rule> getRulesByMainId(Integer mainId) {
        return ruleRepository.findByHabitOwnerId(mainId);
    }

    public List<Integer> getMainIdsBySubId(Integer subId){
        return ruleRepository.findByHabitSubId(subId).stream()
            .map(Rule::getHabitOwnerId)
            .distinct()
            .toList();
    }
}
