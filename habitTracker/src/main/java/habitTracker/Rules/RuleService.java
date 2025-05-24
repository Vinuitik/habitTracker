package habitTracker.Rules;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RuleService {
    private final RuleRepository ruleRepository;
}
