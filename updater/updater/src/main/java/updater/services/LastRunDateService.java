package updater.services;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Service;
import org.bson.Document;
import updater.models.LastRunDate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

@Service
public class LastRunDateService {
    
    private final MongoTemplate mongoTemplate;

    public LastRunDateService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public LocalDate getLastRunDate() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.group().max("_id").as("date")
        );
        AggregationResults<Document> result = mongoTemplate.aggregate(
                aggregation, "last_run_date", Document.class);
        
        Document resultDoc = result.getUniqueMappedResult();
        
        if (resultDoc != null && resultDoc.get("date") != null) {
            Date dbDate = resultDoc.get("date", Date.class);
            if (dbDate != null) {
                return dbDate.toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
            }
        }
        return null;
    }

    public boolean hasRunToday() {
        LocalDate lastRunDate = getLastRunDate();
        LocalDate today = LocalDate.now();
        
        System.out.println("Last run date: " + lastRunDate);
        System.out.println("Today's date: " + today);
        
        return lastRunDate != null && lastRunDate.isEqual(today);
    }

    public void markRunToday() {
        mongoTemplate.save(new LastRunDate(LocalDate.now()));
    }
}