# url-shortener-service

Core URL shortener product. See `specs/001-core-url-shortener/` for the requirements and
design this module implements.

## Run

```
mvn -pl url-shortener-service -am spring-boot:run
```

Starts on `http://localhost:8080`. In-memory H2 database (data does not persist across
restarts); console at `http://localhost:8080/h2-console` (JDBC URL
`jdbc:h2:mem:urlshortener`, user `sa`, no password).

OpenAPI/Swagger UI: `http://localhost:8080/swagger-ui.html`.

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/urls` | Shorten a URL. Body: `{"longUrl": "...", "expiresAt"?: "ISO-8601"}` |
| `GET` | `/api/urls/{code}` | Fetch metadata for a short code (no redirect) |
| `GET` | `/{code}` | Resolve and `302` redirect to the original URL |

## Example

```bash
curl -s -X POST http://localhost:8080/api/urls \
  -H 'Content-Type: application/json' \
  -d '{"longUrl":"https://example.com/some/very/long/path"}'
# => {"shortCode":"0004","shortUrl":"http://localhost:8080/0004", ...}

curl -i http://localhost:8080/0004   # 302 redirect
```

## Test

```
mvn -pl url-shortener-service -am test
```

22 tests: unit tests for Base62 encoding and URL validation, service-layer tests against a
mocked repository, and `@SpringBootTest`/MockMvc integration tests for both controllers
against a real (in-memory) H2 instance.
