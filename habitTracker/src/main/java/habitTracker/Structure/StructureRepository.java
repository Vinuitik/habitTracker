package habitTracker.Structure;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;


@Repository
public interface StructureRepository extends MongoRepository<Structure, LocalDate> {
    Optional<Structure> findFirstByOrderByDateDesc();
}


