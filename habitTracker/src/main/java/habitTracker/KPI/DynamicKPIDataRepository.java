package habitTracker.KPI;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DynamicKPIDataRepository {
    
    private final MongoTemplate mongoTemplate;
    
    /**
     * Save KPI data to the specified collection
     */
    public KPIData save(KPIData kpiData, String collectionName) {
        return mongoTemplate.save(kpiData, collectionName);
    }
    
    /**
     * Find KPI data by date range, ordered by date ascending
     */
    public List<KPIData> findByDateBetweenOrderByDateAsc(LocalDate startDate, LocalDate endDate, String collectionName) {
        Query query = new Query(Criteria.where("date").gte(startDate).lte(endDate))
                .with(Sort.by(Sort.Direction.ASC, "date"));
        return mongoTemplate.find(query, KPIData.class, collectionName);
    }
    
    /**
     * Find all KPI data ordered by date ascending
     */
    public List<KPIData> findAllOrderByDateAsc(String collectionName) {
        Query query = new Query().with(Sort.by(Sort.Direction.ASC, "date"));
        return mongoTemplate.find(query, KPIData.class, collectionName);
    }
    
    /**
     * Find KPI data by specific date
     */
    public Optional<KPIData> findByDate(LocalDate date, String collectionName) {
        Query query = new Query(Criteria.where("date").is(date));
        KPIData result = mongoTemplate.findOne(query, KPIData.class, collectionName);
        return Optional.ofNullable(result);
    }
    
    /**
     * Find top N records ordered by date descending (for EMA calculation)
     */
    public List<KPIData> findTopNOrderByDateDesc(int limit, String collectionName) {
        Query query = new Query()
                .with(Sort.by(Sort.Direction.DESC, "date"))
                .limit(limit);
        return mongoTemplate.find(query, KPIData.class, collectionName);
    }
    
    /**
     * Delete KPI data by date
     */
    public void deleteByDate(LocalDate date, String collectionName) {
        Query query = new Query(Criteria.where("date").is(date));
        mongoTemplate.remove(query, KPIData.class, collectionName);
    }
    
    /**
     * Delete all data in the collection
     */
    public void deleteAll(String collectionName) {
        mongoTemplate.remove(new Query(), KPIData.class, collectionName);
    }
    
    /**
     * Create a new collection for KPI data
     */
    public void createCollection(String collectionName) {
        if (!mongoTemplate.collectionExists(collectionName)) {
            mongoTemplate.createCollection(collectionName);
        }
    }
    
    /**
     * Drop a collection
     */
    public void dropCollection(String collectionName) {
        if (mongoTemplate.collectionExists(collectionName)) {
            mongoTemplate.dropCollection(collectionName);
        }
    }
    
    /**
     * Check if collection exists
     */
    public boolean collectionExists(String collectionName) {
        return mongoTemplate.collectionExists(collectionName);
    }
    
    /**
     * Count documents in collection
     */
    public long count(String collectionName) {
        return mongoTemplate.count(new Query(), collectionName);
    }
}
