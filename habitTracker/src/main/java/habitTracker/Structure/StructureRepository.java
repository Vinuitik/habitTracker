package habitTracker.Structure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;


@Repository
public interface StructureRepository extends MongoRepository<Structure, LocalDate> {
    List<Structure> findByDateBetween(LocalDate startDate, LocalDate endDate);
}


