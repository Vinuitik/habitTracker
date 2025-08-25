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

    public void createHabitStructure(Integer habitId, LocalDate date, boolean completed) {
        HabitStructure habitStructure = HabitStructure.builder()
                .habitId(habitId)
                .structureDate(date)
                .completed(completed)
                .build();
        
        System.out.println("Creating habit structure: " + habitStructure);
        mongoTemplate.save(habitStructure);
    }
}