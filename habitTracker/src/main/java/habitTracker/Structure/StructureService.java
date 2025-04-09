package habitTracker.Structure;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StructureService {

    private final StructureRepository structureRepository;

    public Structure getLatestStructure() {
        return structureRepository.findFirstByOrderByDateDesc()
                .orElse(new Structure(LocalDate.now(), null));
    }
}
