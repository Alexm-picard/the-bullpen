package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.thebullpen.baseball.api.dto.ApiError;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link OpenApiConfig} error-envelope customizer so the generated {@code /v3/api-docs}
 * keeps documenting the real non-2xx surface (which C-33's Schemathesis conformance checks lean
 * on), and guards the hand-built {@code ApiError} schema against silent drift from the {@code
 * ApiError} record.
 */
class OpenApiConfigTest {

  private static void customise(OpenAPI openApi) {
    new OpenApiConfig().sharedErrorResponsesCustomizer().customise(openApi);
  }

  @Test
  void customizerRegistersApiErrorComponentAndAttachesSharedErrors() {
    OpenAPI openApi = new OpenAPI();
    openApi.setPaths(new Paths().addPathItem("/x", new PathItem().post(new Operation())));

    customise(openApi);

    assertThat(openApi.getComponents().getSchemas()).containsKey("ApiError");
    ApiResponses responses = openApi.getPaths().get("/x").getPost().getResponses();
    assertThat(responses.keySet()).contains("400", "404", "405", "415", "429", "503");
    for (String code : List.of("400", "429", "503")) {
      Schema<?> schema = responses.get(code).getContent().get("application/json").getSchema();
      assertThat(schema.get$ref()).isEqualTo("#/components/schemas/ApiError");
    }
  }

  @Test
  void customizerDoesNotOverwriteAnExplicitResponse() {
    OpenAPI openApi = new OpenAPI();
    Operation op = new Operation();
    ApiResponses declared = new ApiResponses();
    declared.addApiResponse("400", new ApiResponse().description("custom 400"));
    op.setResponses(declared);
    openApi.setPaths(new Paths().addPathItem("/x", new PathItem().get(op)));

    customise(openApi);

    ApiResponses out = openApi.getPaths().get("/x").getGet().getResponses();
    assertThat(out.get("400").getDescription()).isEqualTo("custom 400"); // explicit wins
    assertThat(out.keySet()).contains("404", "429", "503"); // the rest are still added
  }

  @Test
  void customizerIsNullSafeOnAPathlessSpec() {
    OpenAPI openApi = new OpenAPI(); // no paths, no components
    customise(openApi);
    assertThat(openApi.getComponents().getSchemas()).containsKey("ApiError");
  }

  /**
   * Drift guard: the hand-built {@code apiErrorSchema()} reconstructs the envelope by hand (to
   * avoid a config->api import and to carry field descriptions). If a field is ever added to {@code
   * ApiError.Body} or {@code ApiError.FieldError} without updating the schema, this fails.
   */
  @Test
  void apiErrorSchemaShapeMatchesTheRecord() {
    OpenAPI openApi = new OpenAPI();
    customise(openApi);

    Schema<?> apiError = openApi.getComponents().getSchemas().get("ApiError");
    Schema<?> body = apiError.getProperties().get("error");
    assertThat(body.getProperties().keySet()).isEqualTo(recordComponentNames(ApiError.Body.class));

    Schema<?> fieldError = body.getProperties().get("details").getItems();
    assertThat(fieldError.getProperties().keySet())
        .isEqualTo(recordComponentNames(ApiError.FieldError.class));
  }

  private static Set<String> recordComponentNames(Class<?> record) {
    return Arrays.stream(record.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(Collectors.toSet());
  }
}
