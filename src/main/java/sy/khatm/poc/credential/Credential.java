package sy.khatm.poc.credential;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credential")
public class Credential {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String ref;              // human-readable reference, e.g. CRJ-2026-000341-c1

    @Column(name = "schema_code", nullable = false)
    private String schemaCode;       // e.g. CriminalRecordExtract/v1

    @Column(name = "holder_ref")
    private String holderRef;        // pseudonymous holder id

    @Column(name = "signed_jwt", columnDefinition = "text", nullable = false)
    private String signedJwt;        // the compact JWS carried by QR/NFC

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    @Column(name = "max_uses", nullable = false)
    private int maxUses;

    @Column(name = "uses_remaining", nullable = false)
    private int usesRemaining;

    @Column(name = "status_idx")
    private int statusIdx;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // --- getters / setters ---
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getRef() { return ref; }
    public void setRef(String ref) { this.ref = ref; }
    public String getSchemaCode() { return schemaCode; }
    public void setSchemaCode(String schemaCode) { this.schemaCode = schemaCode; }
    public String getHolderRef() { return holderRef; }
    public void setHolderRef(String holderRef) { this.holderRef = holderRef; }
    public String getSignedJwt() { return signedJwt; }
    public void setSignedJwt(String signedJwt) { this.signedJwt = signedJwt; }
    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }
    public Instant getValidTo() { return validTo; }
    public void setValidTo(Instant validTo) { this.validTo = validTo; }
    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }
    public int getUsesRemaining() { return usesRemaining; }
    public void setUsesRemaining(int usesRemaining) { this.usesRemaining = usesRemaining; }
    public int getStatusIdx() { return statusIdx; }
    public void setStatusIdx(int statusIdx) { this.statusIdx = statusIdx; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
