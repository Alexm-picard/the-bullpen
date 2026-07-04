package net.thebullpen.baseball.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * OpenAPI enrichment (Wave C, C-32). springdoc autogenerates the raw spec from the controllers;
 * this class adds two things on top:
 *
 * <ul>
 *   <li>Top-level {@link Info} (title / version / description) so {@code /swagger-ui} and {@code
 *       /v3/api-docs} identify the service.
 *   <li>An {@link OpenApiCustomizer} that registers the shared {@code ApiError} envelope as a
 *       schema component and attaches the standard error responses to EVERY operation - so the
 *       contract documents what {@code ApiErrorAdvice} + {@code RateLimitFilter} actually return,
 *       without a per-method {@code @ApiResponse} on all ~40 endpoints. This is what makes the
 *       Schemathesis status-code + response-schema conformance checks meaningful (C-33).
 * </ul>
 *
 * <p>The declared status set mirrors the real non-2xx surface: {@code ApiErrorAdvice} maps 400 (bad
 * input), 404 (no such resource), 405 (method), 415 (media type), 503 (model unavailable); {@code
 * RateLimitFilter} writes 429 outside MVC. All six carry the identical {@code ApiError} body, so
 * one shared component covers them. 500 is deliberately NOT declared - the contract asserts the
 * service never returns one, and Schemathesis' {@code not_a_server_error} check enforces that
 * independently.
 *
 * <p>Scoped to the {@code api} profile (the only profile that serves the public HTTP surface).
 */
@Configuration
@Profile("api")
public class OpenApiConfig {

  private static final String APPLICATION_JSON = "application/json";
  private static final String API_ERROR_SCHEMA = "ApiError";
  private static final String API_ERROR_REF = "#/components/schemas/" + API_ERROR_SCHEMA;

  /** Standard error responses (HTTP status -> human description) attached to every operation. */
  private static final String[][] SHARED_ERRORS = {
    {"400", "Invalid request: validation failed, malformed JSON, or a bad path/query value."},
    {"404", "No such resource."},
    {"405", "HTTP method not allowed for this path."},
    {"415", "Unsupported request media type - send application/json."},
    {"429", "Rate limit exceeded - back off and retry after the Retry-After interval."},
    {"503", "The model or a required dependency is temporarily unavailable."},
  };

  @Bean
  public OpenAPI bullpenOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("The Bullpen API")
                .version("v1")
                .description(
                    "Self-hosted baseball analytics: calibrated pitch-outcome and batted-ball"
                        + " predictions served in-process via ONNX Runtime, wrapped in a model"
                        + " registry with A/B routing and drift detection. Public read + predict"
                        + " endpoints are rate-limited; /v1/admin/** requires ADMIN basic auth."));
  }

  /**
   * Registers the {@code ApiError} component and attaches the shared error responses to every
   * operation that does not already declare them (an explicit per-operation {@code @ApiResponse}
   * always wins).
   */
  @Bean
  public OpenApiCustomizer sharedErrorResponsesCustomizer() {
    return openApi -> {
      Components components = openApi.getComponents();
      if (components == null) {
        components = new Components();
        openApi.setComponents(components);
      }
      components.addSchemas(API_ERROR_SCHEMA, apiErrorSchema());

      if (openApi.getPaths() == null) {
        return;
      }
      openApi
          .getPaths()
          .values()
          .forEach(pathItem -> pathItem.readOperations().forEach(OpenApiConfig::addSharedErrors));
    };
  }

  private static void addSharedErrors(Operation operation) {
    ApiResponses responses = operation.getResponses();
    if (responses == null) {
      responses = new ApiResponses();
      operation.setResponses(responses);
    }
    for (String[] error : SHARED_ERRORS) {
      String code = error[0];
      if (responses.containsKey(code)) {
        continue; // an explicit @ApiResponse on the method wins
      }
      responses.addApiResponse(
          code,
          new ApiResponse()
              .description(error[1])
              .content(
                  new Content()
                      .addMediaType(
                          APPLICATION_JSON,
                          new MediaType().schema(new Schema<>().$ref(API_ERROR_REF)))));
    }
  }

  /**
   * The {@code ApiError} envelope shape - matches {@code api.dto.ApiError} (record + nested Body).
   */
  private static Schema<?> apiErrorSchema() {
    // Statement-style mutation on typed locals keeps the fluent addProperty chain from producing an
    // unchecked raw-Schema -> Schema<Object> conversion.
    ObjectSchema fieldError = new ObjectSchema();
    fieldError.addProperty("field", new StringSchema().description("Request field that failed."));
    fieldError.addProperty("message", new StringSchema().description("Why it failed."));

    ArraySchema details = new ArraySchema();
    details.items(fieldError);
    details.description("Field-level errors; populated when code=validation_failed.");

    ObjectSchema body = new ObjectSchema();
    body.addProperty(
        "code",
        new StringSchema()
            .description("Machine-readable error code, e.g. validation_failed.")
            .example("validation_failed"));
    body.addProperty("message", new StringSchema().description("Human-readable message."));
    body.addProperty(
        "correlationId", new StringSchema().description("Request correlation id for log tracing."));
    body.addProperty("details", details);

    ObjectSchema root = new ObjectSchema();
    root.description("Canonical error envelope returned by every non-2xx response.");
    root.addProperty("error", body);
    return root;
  }
}
