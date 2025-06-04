package updater.services;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import updater.models.HabitStructure;

import java.time.LocalDate;

@Service
public class HabitStructureManager {
    
    private final MongoTemplate mongoTemplate;

    public HabitStructureManager(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void createHabitStructure(Integer habitId, LocalDate date) {
        HabitStructure habitStructure = HabitStructure.builder()
                .habitId(habitId)
                .structureDate(date)
                .completed(false)
                .build();
        
        System.out.println("Creating habit structure: " + habitStructure);
        mongoTemplate.save(habitStructure);
    }
}