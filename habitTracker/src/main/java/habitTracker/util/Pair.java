package habitTracker.util;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@Data
@ToString
public class Pair<K, V> {
    private final K key;
    private final V value;
}