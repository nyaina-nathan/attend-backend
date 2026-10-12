package mg.improve.attend.domain.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mg.improve.attend.domain.enums.EventStatus;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Events {

    private UUID id;
    private String title;
    private String description;
    private Instant startTime;
    private Instant endTime;
    private boolean isPublic;
    private String location;
    private String priceUnit;
    private EventStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
