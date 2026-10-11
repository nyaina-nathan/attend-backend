package mg.improve.attend.domain.entity;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mg.improve.attend.domain.enums.InvitationStatus;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invitation {

    private UUID id;
    private Users sender;
    private Users receiver;
    private Events event;

    @Builder.Default
    private InvitationStatus invitationStatus = InvitationStatus.PENDING;
}
