package habitTracker.Rules;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import org.bson.types.ObjectId;


@Data 
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
@Document(collection = "rules")
public class Rule { // this will store one to many relationship with habit - meaning that we represet here what habits are grouped, and by which habit
    @Id
    private String id;
    private Integer habitOwnerId; // id of the habit to which this rule belongs
    private Integer habitSubId;
}
