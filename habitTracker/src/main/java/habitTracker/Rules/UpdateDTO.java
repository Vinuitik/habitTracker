package habitTracker.Rules;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class UpdateDTO {
    private Integer mainId;
    private List<Integer> subIds;
    private Integer frequency;
    private Integer streak;
}
