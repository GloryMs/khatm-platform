package sy.khatm.platform.key.domain;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import javax.security.auth.DestroyFailedException;
import javax.security.auth.Destroyable;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import sy.khatm.platform.key.api.PublicKeyHandle;
import sy.khatm.platform.key.api.SignResult;

/**
 * {@link KeyProvider} backed by a single PKCS#12 keystore file on disk (spec FS-0.5 §3).
 *
 * <p>The keystore alias for each key equals its {@code kid}. Private key material never leaves this
 * class: it is read from the keystore only for the duration of a {@link #sign} call and, when the
 * JCA provider supports it, destroyed ({@link Destroyable}) immediately afterward — it is never
 * copied into {@code issuer_key}, logged, or returned to a caller.
 *
 * <p>The keystore passphrase comes from {@code khatm.keys.soft.passphrase} (env-sourced in real
 * deployments — {@code KHATM_KEYS_PASSPHRASE}). Outside the {@code local} profile, a missing
 * passphrase fails startup immediately; a wrong passphrase against an <em>existing</em> keystore
 * file also fails startup immediately — this class never falls back to silently creating a fresh
 * keystore over one it could not open, which would orphan every previously issued signature.
 *
 * <p>PKCS#12 requires a certificate chain alongside every private key entry ({@link
 * KeyStore#setKeyEntry(String, java.security.Key, char[], Certificate[])} rejects a null/empty
 * chain). Since verification here is always done via standard JCA/Nimbus EC key objects — never via
 * the X.509 chain itself — a minimal self-signed certificate is minted per key purely to satisfy
 * that container-format requirement.
 *
 * <p><b>Cross-process reload-on-miss (KH-2.3a, spec FS-2.3 D2):</b> {@code khatm-api} and {@code
 * khatm-worker} (ADR-09's two roles) are separate JVMs sharing one keystore <em>file</em> (one
 * Docker volume) but each holds its own in-memory {@link KeyStore}, loaded once at {@link #init()}.
 * A rotation performed by one process persists the new key to the shared file but does nothing to
 * the other process's already-loaded copy — {@link #sign} and {@link #publicKey} both reload from
 * disk once and retry on a miss (never on every call) precisely so the other process picks up a
 * rotation it didn't itself perform without needing to restart. Found and fixed via this session's
 * own live compose walkthrough — {@code khatm-worker}'s status-list resign failed with {@code
 * JOSEException: No such key in keystore} for the newly-rotated key until this fix.
 *
 * <p><b>Always registered (KH-2.3b, spec FS-2.3 D5/D6):</b> unlike before this session, this bean
 * is no longer gated behind {@code khatm.keys.provider} — {@link KeyLifecycleService} now holds
 * every registered {@link KeyProvider} in a name-keyed map and routes each key to whichever
 * provider actually created it (spec V3's per-tenant column), so SOFT must stay available even on a
 * deployment where a tenant has since migrated onto {@code VaultTransitProvider}: every key it
 * created before that migration is still {@code SOFT} and must still resolve/verify.
 */
@Component("SOFT")
class SoftKeyProvider implements KeyProvider {

  private static final Logger log = LoggerFactory.getLogger(SoftKeyProvider.class);
  private static final String KEYSTORE_TYPE = "PKCS12";
  private static final String EC_CURVE = "secp256r1";
  private static final Duration CERT_VALIDITY = Duration.ofDays(3650);

  private final Path keystorePath;
  private final char[] passphrase;
  private final Object lock = new Object();
  private KeyStore keyStore;

  SoftKeyProvider(
      @Value("${khatm.keys.soft.keystore-path}") String keystorePath,
      @Value("${khatm.keys.soft.passphrase:}") String passphrase,
      Environment env) {
    this.keystorePath = Path.of(keystorePath);
    if ((passphrase == null || passphrase.isBlank())
        && !env.acceptsProfiles(Profiles.of("local"))) {
      throw new IllegalStateException(
          "khatm.keys.soft.passphrase is required outside the 'local' profile — set the "
              + "KHATM_KEYS_PASSPHRASE environment variable. Refusing to start with no keystore "
              + "passphrase.");
    }
    this.passphrase = passphrase == null ? new char[0] : passphrase.toCharArray();
  }

  @PostConstruct
  void init() {
    synchronized (lock) {
      this.keyStore = loadOrCreate();
    }
  }

  private KeyStore loadOrCreate() {
    boolean exists = Files.exists(keystorePath);
    try {
      KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
      if (exists) {
        try (InputStream in = Files.newInputStream(keystorePath)) {
          ks.load(in, passphrase);
        } catch (IOException e) {
          // Deliberately fatal, never silently recreated: a wrong passphrase (or a corrupted
          // file) here would otherwise cause us to overwrite a keystore holding the private
          // half of every previously issued signature — see DoD #5.
          throw new IllegalStateException(
              "Failed to open existing keystore at "
                  + keystorePath
                  + " — check khatm.keys.soft.passphrase. Refusing to overwrite it.",
              e);
        }
      } else {
        Path parent = keystorePath.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        ks.load(null, passphrase);
        log.info("No keystore found at {} — created a new empty PKCS#12 keystore.", keystorePath);
      }
      return ks;
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalStateException(
          "Unable to initialize PKCS#12 keystore at " + keystorePath, e);
    }
  }

  @Override
  public GeneratedKeyMaterial generate(String kid) {
    try {
      KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
      kpg.initialize(new ECGenParameterSpec(EC_CURVE));
      KeyPair pair = kpg.generateKeyPair();
      X509Certificate cert = selfSignedCertificate(pair, kid);

      synchronized (lock) {
        keyStore.setKeyEntry(kid, pair.getPrivate(), passphrase, new Certificate[] {cert});
        persist();
      }

      ECKey jwk = new ECKey.Builder(Curve.P_256, (ECPublicKey) pair.getPublic()).keyID(kid).build();
      return new GeneratedKeyMaterial(kid, jwk.toPublicJWK().toJSONString(), kid);
    } catch (GeneralSecurityException | IOException | OperatorCreationException e) {
      throw new IllegalStateException("Failed to generate issuer key kid=" + kid, e);
    }
  }

  @Override
  public SignResult sign(String providerRef, String kid, JWTClaimsSet claims) throws JOSEException {
    PrivateKey privateKey = loadPrivateKeyReloadingOnMiss(providerRef);
    if (privateKey == null) {
      throw new JOSEException("No such key in keystore: providerRef=" + providerRef);
    }
    try {
      JWSHeader header =
          new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).keyID(kid).build();
      SignedJWT jwt = new SignedJWT(header, claims);
      jwt.sign(new ECDSASigner((ECPrivateKey) privateKey));
      return new SignResult(kid, JWSAlgorithm.ES256.getName(), jwt.serialize());
    } finally {
      destroyBestEffort(privateKey);
    }
  }

  @Override
  public Optional<PublicKeyHandle> publicKey(String providerRef, String kid) {
    Certificate cert = loadCertificateReloadingOnMiss(providerRef);
    if (cert == null) {
      return Optional.empty();
    }
    return Optional.of(
        new PublicKeyHandle(kid, JWSAlgorithm.ES256.getName(), (ECPublicKey) cert.getPublicKey()));
  }

  /**
   * Look up {@code providerRef}'s private key, reloading the keystore from disk once and retrying
   * if it is missing from the in-memory copy (spec FS-2.3 D2) — {@code khatm-api} and {@code
   * khatm-worker} are separate JVMs sharing the same keystore <em>file</em> (one Docker volume),
   * but each holds its own in-memory {@link KeyStore} loaded once at {@link #init()}. A rotation
   * performed by one process (typically {@code khatm-api}, which serves the rotate endpoint)
   * persists the new key to the shared file but does nothing to the other process's already-loaded
   * in-memory copy — without this reload-on-miss fallback, {@code khatm-worker}'s {@code
   * StatusListPublisher} would fail every re-sign attempt for a tenant whose key was just rotated
   * by {@code khatm-api}, since its own in-memory keystore has no entry for the new {@code kid}
   * until its next restart. Reloads only on an actual miss, never on every call, so this costs
   * nothing in the far more common case where the requested key was already known.
   */
  private PrivateKey loadPrivateKeyReloadingOnMiss(String providerRef) throws JOSEException {
    synchronized (lock) {
      try {
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(providerRef, passphrase);
        if (privateKey != null) {
          return privateKey;
        }
        keyStore = loadOrCreate();
        return (PrivateKey) keyStore.getKey(providerRef, passphrase);
      } catch (GeneralSecurityException e) {
        throw new JOSEException("Unable to load private key for providerRef=" + providerRef, e);
      }
    }
  }

  /** Same reload-on-miss fallback as {@link #loadPrivateKeyReloadingOnMiss}, for certificates. */
  private Certificate loadCertificateReloadingOnMiss(String providerRef) {
    synchronized (lock) {
      try {
        Certificate cert = keyStore.getCertificate(providerRef);
        if (cert != null) {
          return cert;
        }
        keyStore = loadOrCreate();
        return keyStore.getCertificate(providerRef);
      } catch (KeyStoreException e) {
        throw new IllegalStateException(
            "Unable to read certificate for providerRef=" + providerRef, e);
      }
    }
  }

  private void persist() throws IOException, GeneralSecurityException {
    Path parent = keystorePath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (OutputStream out = Files.newOutputStream(keystorePath)) {
      keyStore.store(out, passphrase);
    }
  }

  private static X509Certificate selfSignedCertificate(KeyPair pair, String kid)
      throws GeneralSecurityException, OperatorCreationException {
    long now = System.currentTimeMillis();
    X500Name subject = new X500Name("CN=" + kid);
    BigInteger serial = BigInteger.valueOf(now);
    Date notBefore = new Date(now - Duration.ofMinutes(1).toMillis());
    Date notAfter = new Date(now + CERT_VALIDITY.toMillis());
    SubjectPublicKeyInfo pubKeyInfo =
        SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded());
    X509v3CertificateBuilder builder =
        new X509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, pubKeyInfo);
    ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate());
    X509CertificateHolder holder = builder.build(signer);
    return new JcaX509CertificateConverter().getCertificate(holder);
  }

  private static void destroyBestEffort(PrivateKey key) {
    if (key instanceof Destroyable destroyable && !destroyable.isDestroyed()) {
      try {
        destroyable.destroy();
      } catch (DestroyFailedException e) {
        // Best-effort: many JCA PrivateKey implementations (including the SunPKCS12 provider's)
        // do not support destruction. Not fatal — the reference still goes out of scope and is
        // eligible for GC; this is a defense-in-depth measure, not the only one (SEC §3).
        log.debug("Private key material could not be explicitly destroyed (provider limitation).");
      }
    }
  }
}
