package sy.khatm.platform.credential.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sy.khatm.platform.credential.domain.ClaimCode;

/**
 * Repository for {@link ClaimCode} entities.
 *
 * <p>Module-private — only {@code CredentialService} may use this.
 */
public interface ClaimCodeRepository extends JpaRepository<ClaimCode, UUID> {}
