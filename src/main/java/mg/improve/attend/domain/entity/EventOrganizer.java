package mg.improve.attend.domain.entity;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mg.improve.attend.domain.enums.EventRole;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventOrganizer {

    private UUID id;
    private Users user;
    private Events event;
    private EventRole eventRole;
}
