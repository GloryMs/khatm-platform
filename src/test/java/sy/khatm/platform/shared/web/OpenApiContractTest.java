package sy.khatm.platform.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import sy.khatm.platform.rbac.domain.ApiKeyOwnerType;
import sy.khatm.platform.rbac.domain.ApiKeyService;

/**
 * KH-1.6-early — the published API contract (spec brief Part 3): {@code docs/api/openapi.json} is
 * generated from the running application's own {@code /v3/api-docs} and never hand-edited, the same
 * self-serve philosophy as {@code ErrorCodesDocGenerationTest} (KH-0.6a): recompute, compare
 * against the committed file, and fail with the exact content to paste in if they differ.
 *
 * <p><b>Generation mechanism:</b> a context-booting test (this class), not the
 * springdoc-openapi-maven-plugin — that plugin needs a real listening instance of the app during
 * the build, which in this codebase means Testcontainers-backed Postgres/Redis/keystore wiring that
 * only exists inside the Spring context {@code @DynamicPropertySource} already builds for tests;
 * standing up an equivalent outside the test lifecycle (a separate Maven plugin execution) would
 * duplicate that wiring for no benefit. Recorded per the session brief's "record which was used."
 *
 * <p><b>Canonicalization:</b> springdoc's internal path/schema ordering is not a documented
 * guarantee, so the fetched document is recursively re-serialized with object keys sorted
 * alphabetically before comparison — this test cares whether the contract's *content* drifted, not
 * whether springdoc happened to walk its internal maps in a different order.
 *
 * <p><b>Test-only paths excluded:</b> {@link TestBoomController}'s {@code /api/v1/_test/boom} is a
 * real {@code @RestController} mapping (needed to provoke {@code GlobalExceptionHandler}'s
 * catch-all in {@code ErrorEnvelopeAndI18nTest}), so it genuinely appears in {@code /v3/api-docs}
 * when this suite's context boots under the {@code test} profile — but it is never shipped (it
 * lives entirely under {@code src/test/java}) and must never leak into the published contract or
 * the coverage count below. Both are filtered to {@code /api/v1/_test/**} paths removed.
 */
class OpenApiContractTest extends ErrorEnvelopeTestSupport {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path REPO_ROOT = Path.of("").toAbsolutePath();
  private static final Path CONTRACT_FILE = REPO_ROOT.resolve("docs/api/openapi.json");
  private static final Path SRC_MAIN = REPO_ROOT.resolve("src/main/java");
  private static final String TEST_ONLY_PATH_PREFIX = "/api/v1/_test/";
  private static final Pattern MAPPING_ANNOTATION =
      Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\b");
  private static final Set<String> HTTP_METHOD_KEYS =
      Set.of("get", "post", "put", "delete", "patch", "head", "options", "trace");

  @Autowired private TestRestTemplate rest;
  @Autowired private ApiKeyService apiKeyService;

  @Test
  void everyRestControllerMapping_hasAMatchingOperationInApiDocs() throws IOException {
    long sourceMappingCount = countSourceMappingAnnotations();
    long apiDocsOperationCount = countApiDocsOperations(fetchApiDocs());

    assertThat(apiDocsOperationCount)
        .as(
            "the generated api-docs has %d operation(s) but the source tree declares %d"
                + " @RestController mapping annotation(s) (excluding the test-only %s* fixture"
                + " from both counts) — every endpoint must carry full OpenAPI annotations"
                + " (KH-1.6-early Part 2)",
            apiDocsOperationCount, sourceMappingCount, TEST_ONLY_PATH_PREFIX)
        .isEqualTo(sourceMappingCount);
  }

  @Test
  void committedContractFile_matchesGeneratedApiDocs() throws IOException {
    String generated = canonicalPrettyJson(fetchApiDocs());
    String committed = Files.readString(CONTRACT_FILE, StandardCharsets.UTF_8);

    if (!committed.equals(generated)) {
      Path debugDump = REPO_ROOT.resolve("target/openapi-generated.json");
      Files.writeString(debugDump, generated, StandardCharsets.UTF_8);
      throw new AssertionError(
          "docs/api/openapi.json is stale relative to the running application's own"
              + " /v3/api-docs. Never hand-edit this file (CLAUDE.md work rule 1) — the freshly"
              + " generated contract was written to "
              + debugDump
              + "; replace docs/api/openapi.json's contents with that file's and commit.");
    }
  }

  private JsonNode fetchApiDocs() throws IOException {
    String rawKey = apiKeyService.create(ApiKeyOwnerType.TENANT, null, Set.of("issue")).rawKey();
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
    ResponseEntity<String> response =
        rest.exchange("/v3/api-docs", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    ObjectNode root = (ObjectNode) JSON.readTree(response.getBody());
    ObjectNode paths = (ObjectNode) root.get("paths");
    List<String> testOnlyPaths = new ArrayList<>();
    paths
        .fieldNames()
        .forEachRemaining(
            p -> {
              if (p.startsWith(TEST_ONLY_PATH_PREFIX)) {
                testOnlyPaths.add(p);
              }
            });
    testOnlyPaths.forEach(paths::remove);
    // springdoc derives "servers[0].url" from this test's own ephemeral random port — meaningless
    // for a published, environment-independent contract (console/wallet always set their own base
    // URL) and would make this comparison fail on every run for a reason that has nothing to do
    // with an actual contract change. Strip it.
    root.remove("servers");
    return root;
  }

  private static long countApiDocsOperations(JsonNode apiDocs) {
    JsonNode paths = apiDocs.get("paths");
    long count = 0;
    Iterator<String> pathNames = paths.fieldNames();
    while (pathNames.hasNext()) {
      JsonNode pathItem = paths.get(pathNames.next());
      Iterator<String> methodNames = pathItem.fieldNames();
      while (methodNames.hasNext()) {
        if (HTTP_METHOD_KEYS.contains(methodNames.next())) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * Counts {@code @GetMapping}/{@code @PostMapping}/etc. method-level annotations under {@code
   * src/main/java} — the "reflection count" the session brief asks for, implemented as a source
   * scan (same technique as {@code NoDirectAuditLogInsertTest}) rather than live reflection over
   * the booted context: reflection over {@code RequestMappingHandlerMapping} would also pick up
   * framework-internal controllers (Boot's {@code BasicErrorController}, springdoc's own {@code
   * /v3/api-docs} handler) that were never annotated and never should be, making the count
   * ambiguous about what "the codebase" means. A source scan restricted to {@code src/main/java}
   * counts exactly this platform's own endpoints — and naturally excludes {@link
   * TestBoomController}, which lives under {@code src/test/java}.
   */
  private static long countSourceMappingAnnotations() throws IOException {
    List<Path> javaFiles;
    try (Stream<Path> files = Files.walk(SRC_MAIN)) {
      javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
    }
    long count = 0;
    for (Path file : javaFiles) {
      String content = Files.readString(file, StandardCharsets.UTF_8);
      Matcher matcher = MAPPING_ANNOTATION.matcher(content);
      while (matcher.find()) {
        count++;
      }
    }
    return count;
  }

  private static String canonicalPrettyJson(JsonNode node) throws IOException {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(canonicalize(node)) + "\n";
  }

  private static JsonNode canonicalize(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = JSON.createObjectNode();
      List<String> fieldNames = new ArrayList<>();
      node.fieldNames().forEachRemaining(fieldNames::add);
      Collections.sort(fieldNames);
      for (String name : fieldNames) {
        sorted.set(name, canonicalize(node.get(name)));
      }
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode array = JSON.createArrayNode();
      for (JsonNode element : node) {
        array.add(canonicalize(element));
      }
      return array;
    }
    return node;
  }
}
