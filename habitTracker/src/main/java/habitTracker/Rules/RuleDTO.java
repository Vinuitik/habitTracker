package habitTracker.Rules;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class RuleDTO {

    private Integer id;
    private String name;
    private Integer frequency;
}
