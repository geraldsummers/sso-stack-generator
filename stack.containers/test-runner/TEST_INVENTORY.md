# Test Inventory

Generated from source globs so test-suite ownership and cleanup scope are visible in one place.

Totals: 181 files, 989 discovered test/suite entries.

## Cleanup model

- Playwright global setup/teardown owns managed Keycloak users, auth artifacts, and matching Jupyter notebook containers.
- `withManagedBrowserUser` owns isolated browser users and removes matching Jupyter containers in a `finally` block.
- Kotlin managed tests now expose `TestContext.cleanup { ... }` for per-test finalizers; cleanup failures are reported as test failures.
- Agent workspace direct Docker containers/volumes are also guarded by a JVM shutdown hook keyed to their per-suite tenant id.
- Disposable recovery-drill database containers and volumes are removed by shell `trap cleanup EXIT` blocks.

## Screenshot acceptance standard

- Public and optional browser-visible modules require real screenshot artifacts.
- Private modules are excluded by default; non-browser/API-only modules must be explicitly classified instead of silently skipped.
- A screenshot artifact is acceptable only when it is captured from the real running app/module and shows realistic synthetic data or meaningful authenticated operational state.
- File emission alone does not pass. Inspect artifacts for fake placeholders, login redirects, blank panes, setup wizards, empty states, wrong routes, errors, and unusable crops.
- If a required screenshot fails because auth, data seeding, app config, or server behavior blocks it, patch the Playwright harness and/or the owning server-side source/config.
- Do not demote required modules, substitute fake screenshots, or move to unrelated work while required screenshots are missing or fake.
- The visual suite should maintain a coverage matrix that maps every required public/optional module to its screenshot artifact and evidence assertion.

## Playwright browser specs

Playwright E2E/visual/browser tests run by playwright.config.ts.

- Files: 41
- Test/suite entries: 89

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/alertmanager.spec.ts`
- Alertmanager - Access with forward auth

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/grafana.spec.ts`
- Grafana - Logs home + Loki datasource

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/homeassistant.spec.ts`
- Home Assistant - Access with forward auth

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/homepage.spec.ts`
- Portal - Access with forward auth

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/jupyterhub.spec.ts`
- JupyterHub - Spawn notebook with forward auth

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/kopia.spec.ts`
- Kopia - Access with forward auth

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/non-browser-api-endpoints.spec.ts`
- Non-browser API endpoints
- Element bootstrap endpoint stays app-facing
- Seafile API endpoint does not redirect to browser SSO
- Vaultwarden API endpoint does not redirect to browser SSO
- Mastodon app API endpoint does not redirect to browser SSO
- Home Assistant API endpoint does not redirect to browser SSO

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/ntfy.spec.ts`
- Ntfy - Access with forward auth

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/onboarding-self-service.spec.ts`
- Onboarding start page is reachable without an existing stack session

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/onboarding.spec.ts`
- Onboarding points users to Keycloak required actions

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/pipeline.spec.ts`
- Pipeline Monitor - Access with forward auth

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/prometheus.spec.ts`
- Prometheus - Access with forward auth

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/seafile.spec.ts`
- Seafile - Access with forward auth

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/search.spec.ts`
- OpenSearch - Access with forward auth

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/session.spec.ts`
- Session works across multiple forward-auth services

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/vault.spec.ts`
- Vault - Access with forward auth

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/vaultwarden-boundary.spec.ts`
- Vaultwarden - Access with forward auth

### `stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/workspaces.spec.ts`
- Workspaces - authenticated users can create and delete a workspace

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/bookstack.spec.ts`
- BookStack - OIDC login flow

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/element-call-livekit.spec.ts`
- Element Call MatrixRTC uses internal LiveKit for two-party audio media

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/element.spec.ts`
- Element MatrixRTC discovery is internal LiveKit only
- Element (Matrix Web) - OIDC login flow

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/forgejo.spec.ts`
- Forgejo - OIDC login flow

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/grafana.spec.ts`
- Grafana - OIDC login flow

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/jellyfin-external-api.spec.ts`
- Jellyfin external Caddy route exposes native-client discovery without edge auth
- Jellyfin external Caddy route blocks password login for native clients
- Jellyfin external Caddy route supports SSO app and authenticated API access

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/jellyfin.spec.ts`
- Jellyfin - OIDC login streams Simpsons video with visible frames

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/keycloak.spec.ts`
- Keycloak OIDC smoke
- serves the webservices realm discovery document
- protects the low-risk whoami route through the Keycloak auth gateway

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/mastodon.spec.ts`
- Mastodon - OIDC login flow
- Mastodon - federated media images render with real pixels
- Mastodon - federated preview card images render with real pixels
- Mastodon - federated profile avatars render with real pixels
- Mastodon - CSP keeps local media origin available

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/matrix-authentication-service.spec.ts`
- Matrix Authentication Service compatibility
- serves legacy-compatible login through MAS after delegated auth migration
- advertises native Matrix OIDC delegated auth metadata
- serves MAS health and discovery on the dedicated auth host

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/matrix-media-integrity.spec.ts`
- Matrix external media route preserves mobile voice attachment bytes across two devices

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/planka.spec.ts`
- Planka - OIDC login flow

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/session.spec.ts`
- OIDC session works across multiple services

### `stack.containers/test-runner/playwright-tests/tests/deep/oidc/vaultwarden.spec.ts`
- Vaultwarden - OIDC login flow

### `stack.containers/test-runner/playwright-tests/tests/fast/app-smoke.spec.ts`
- App Smoke
- Authenticated routes render for one isolated managed user session

### `stack.containers/test-runner/playwright-tests/tests/fast/route-contract.spec.ts`
- Route Contract
- catalog covers every Caddy host explicitly
- MatrixRTC root is boundary-safe and does not redirect to Keycloak

### `stack.containers/test-runner/playwright-tests/tests/fast/sso-session.spec.ts`
- Shared Session SSO

### `stack.containers/test-runner/playwright-tests/tests/fast/workspaces-boundary.spec.ts`
- Workspaces Boundary
- OIDC discovery is public and anonymous API access is redirected to Keycloak

### `stack.containers/test-runner/playwright-tests/tests/visual/caddy-ui-coverage.spec.ts`
- Caddy UI Visual Coverage
- Explicit Visual Snapshots
- every browser UI route exposed by Caddy has screenshot coverage
- Apex portal snapshot

### `stack.containers/test-runner/playwright-tests/tests/visual/portal-role-dashboards.spec.ts`
- portal role dashboards
- renders every role dashboard with integrated widgets and exports screenshots

### `stack.containers/test-runner/playwright-tests/tests/visual/progression-dashboard.spec.ts`
- Progression Dashboard Visual Fidelity
- fresh workspace keeps one clear next action and no raw internals
- BookStack ownership state shows evidence gaps without unlocking access reward
- mobile layout keeps controls readable and cards stacked
- ownership path drawer is available without foregrounding expert commands
- restore-proven state foregrounds real capability proof and next shell step

### `stack.containers/test-runner/playwright-tests/tests/visual/remaining-module-screenshots.spec.ts`
- Remaining real screenshot coverage
- Authenticated browser UI captures
- Seeded runtime/API captures
- Legacy authenticated browser UI captures
- qBittorrent WebUI screenshot
- Jupyter Notebook/JupyterLab screenshot
- Prometheus populated query screenshot
- ntfy seeded topic screenshot
- Donetick seeded routine screenshot
- ERPNext seeded customer screenshot
- Forgejo seeded repository screenshot
- Keycloak account profile screenshot
- Home Assistant authenticated dashboard screenshot
- Onboarding authenticated status screenshot
- Progression populated dashboard screenshot
- Vaultwarden authenticated vault screenshot
- JupyterHub screenshot
- non-browser modules are classified without generated screenshot cards

### `stack.containers/test-runner/playwright-tests/tests/visual/smoke-visual.spec.ts`
- Visual Smoke
- Authenticated snapshots

## Jest TypeScript unit tests

Node/Jest unit tests for Playwright harness helpers.

- Files: 9
- Test/suite entries: 93

### `stack.containers/test-runner/playwright-tests/tests/unit/browser-route-driver.test.ts`
- browser-route-driver
- isBookStackTransientOidcErrorState
- detects BookStack callback error pages by content
- detects BookStack callback error pages by callback URL
- does not flag normal authenticated BookStack pages
- assertAnonymousContract
- retries transient SSL failures and validates a public page contract
- accepts forward-auth routes that land on the Keycloak boundary
- accepts service-login routes that redirect to auth when explicitly allowed
- waits for service-login pages to settle before matching anonymous content
- re-navigates blank service-login pages until content renders
- fails service-login routes that redirect to auth when the contract forbids it
- validates canonical redirects that terminate on another public page
- validates canonical redirects that terminate on Keycloak
- assertSmokeContract
- validates public smoke routes
- logs into forward-auth smoke routes when they initially land on Keycloak
- skips OIDC login when the authenticated page is already ready
- completes service-led OIDC login flows with a consent screen
- recovers the transient BookStack OIDC callback error and continues to the smoke page
- rejects unsupported smoke route kinds
- captureVisualSnapshot
- reuses the smoke flow and writes a screenshot into the visual output directory

### `stack.containers/test-runner/playwright-tests/tests/unit/identity-provider.test.ts`
- identity-provider helper
- defaults to the Keycloak boundary and session artifact
- recognizes the current Keycloak login and consent boundaries
- exposes a Keycloak boundary without changing the Keycloak default
- resolves the provider id from explicit input

### `stack.containers/test-runner/playwright-tests/tests/unit/keycloak-client.test.ts`
- KeycloakClient
- builds managed browser users without required-action migration state
- creates clients from the runtime environment
- creates a managed user through the Keycloak Admin API
- only cleans up managed playwright users
- generates Planka-compatible managed usernames

### `stack.containers/test-runner/playwright-tests/tests/unit/oidc-login-page.test.ts`
- OIDCLoginPage.clickOIDCButton
- clicks a visible OIDC button and records the Keycloak redirect
- uses Keycloak as the default OIDC button label
- uses the sign-in hop when the OIDC button is not initially visible
- tolerates sign-in hop load-state timeouts and still retries the OIDC button
- falls back to an href-based OIDC link when no named button is available
- falls back through thrown visibility checks before using the href-based OIDC link
- throws when no OIDC entry point can be found
- treats href fallback visibility errors as a missing OIDC entry point
- fails when an OIDC click does not redirect to Keycloak by default
- allows a missing auth redirect only when explicitly requested
- OIDCLoginPage.handleConsentScreen
- clicks the consent button and waits for redirect back to the relying party
- tolerates consent redirect waits timing out after the accept click
- treats in-flight post-consent redirects as success
- still fails when consent button is missing and no redirect occurs
- logs the no-consent path when the flow is already complete

### `stack.containers/test-runner/playwright-tests/tests/unit/playwright-auth-success-signals.test.ts`
- Playwright authenticated success signals
- does not use redirects or final URLs as authenticated success assertions

### `stack.containers/test-runner/playwright-tests/tests/unit/route-catalog.test.ts`
- route-catalog
- catalogues every Caddy host exactly once
- keeps non-browser and orphaned routes out of smoke and visual suites
- classifies MatrixRTC as a non-UI LiveKit and JWT API route
- builds route URLs for apex, www, and service hosts
- builds route URL patterns for apex and subdomains
- https://datamancy.net/
- https://www.datamancy.net/login
- https://grafana.datamancy.net/dashboards
- https://grafana.example.net/dashboards
- finds known routes and throws for unknown hosts
- uses Forgejo account settings as the stable authenticated smoke surface
- Account\nFull name\nEmail Address
- requires service-specific evidence for OIDC service login pages
- uses stable visual targets for SOGo and Donetick
- Calendar | webservices Mail
- Loading... This is taking longer than usual.
- All Tasks\nArchived\nThings\nLabels
- reads host inventory from an explicit file and strips comments
- reports uncatalogued hosts from the configured inventory file
- throws when no caddy host inventory can be found

### `stack.containers/test-runner/playwright-tests/tests/unit/stack-urls.test.ts`
- stack-urls
- uses the default domain when DOMAIN is unset
- builds URLs against the configured live domain
- leaves already-correct or unrelated URLs unchanged
- rewrites regex patterns against the configured live domain while preserving flags
- https://grafana.datamancy.net/api
- https://grafana.webservices.net/api

### `stack.containers/test-runner/playwright-tests/tests/unit/telemetry.test.ts`
- telemetry
- logs rich page telemetry across populated page sections
- logs empty sections without warnings when no matching elements exist
- covers default attribute fallbacks and short class rendering
- warns and finishes cleanly when telemetry extraction throws at the top level
- saves page HTML snapshots and creates the snapshot directory on demand
- logs only document network traffic when network logging is enabled
- redacts token-like URL parameters from telemetry output
- redacts token-like URL parameters from bare hash callback fragments
- uses the default empty prefix for network logging

### `stack.containers/test-runner/playwright-tests/tests/unit/visual-suite-script.test.ts`
- visual suite script
- references only existing Playwright spec files

## Gradle Kotlin/JUnit tests

Source-local JVM unit tests run through Gradle module test tasks.

- Files: 78
- Test/suite entries: 527

### `stack.kotlin/chatgpt-connector/src/test/kotlin/org/webservices/chatgptconnector/ConnectorStoreTest.kt`
- tokenLifecycleAndAccountCloseRevokesTokens
- tokenTtlIsBounded

### `stack.kotlin/chatgpt-connector/src/test/kotlin/org/webservices/chatgptconnector/KeycloakAdminClientTest.kt`
- missing admin credentials fail closed
- create user checks keycloak responses and returns looked up id
- create user surfaces keycloak create failures
- disable user checks keycloak response

### `stack.kotlin/chatgpt-connector/src/test/kotlin/org/webservices/chatgptconnector/MainTest.kt`
- root page serves usable account and token management ui
- api routes trust only forwarded identity and enforce token ownership
- mcp endpoint requires connector bearer token and applies token scopes

### `stack.kotlin/chatgpt-connector/src/test/kotlin/org/webservices/chatgptconnector/McpFacadeTest.kt`
- initializeAndToolsList

### `stack.kotlin/content-publisher/src/test/kotlin/org/webservices/pipeline/sinks/BookStackSinkTest.kt`
- test healthCheck returns true on successful API call
- test healthCheck returns false on API error
- test write creates new book if not exists
- test write reuses existing book
- test write creates page without chapter
- test write backfills cover for existing book without cover
- test write updates existing book description when outdated
- test write updates existing page on second write via cache
- test write includes tags in page creation
- test writeBatch writes multiple documents
- test authentication header is set correctly
- test sink name is correct
- test handles HTML content correctly
- test caching reduces API calls for same book
- test retries on 429 rate limit
- test retries on 503 server error
- test fails after max retries
- test does not retry on 400 bad request
- test exponential backoff increases delay

### `stack.kotlin/content-publisher/src/test/kotlin/org/webservices/pipeline/sinks/QdrantPublicationSyncTest.kt`
- markPublished pushes canonical publication payload to qdrant

### `stack.kotlin/content-publisher/src/test/kotlin/org/webservices/pipeline/workers/BookStackWriterTest.kt`
- syncExistingPublishedDocuments handles empty missing success and failure flows
- start writes allowed documents and syncs page URLs
- start marks failed writes and skips documents above retry limit
- reconciliation repairs unresolved publication rows
- reconciliation resets stale published BookStack URLs
- reconciliation refreshes changed published BookStack URLs
- reconciliation rotates valid published BookStack URLs
- toBookStackDocument builds source specific documents
- formatting helpers render markdown like content and metadata
- oversized BookStack content is excerpted

### `stack.kotlin/embedding-worker/src/test/kotlin/org/webservices/pipeline/embedding/EmbeddingSchedulerTest.kt`
- scheduler initializes with correct parameters
- getStats returns configuration values
- getStatsBySource calls staging store
- scheduler handles empty stats gracefully
- scheduler configuration is immutable after creation
- partitionDocsForEmbeddingRequests respects request byte ceiling
- processDocumentBatch splits oversized batches before falling back to singles
- runIteration defers background work when cpu fallback is reserved
- runIteration uses throttled cpu limits when provided

### `stack.kotlin/embedding-worker/src/test/kotlin/org/webservices/pipeline/embedding/InferenceControllerStatusClientTest.kt`
- fetchEmbeddingTarget reads embedding target from controller status
- provider falls back to conservative cpu reserve when controller is unavailable

### `stack.kotlin/embedding-worker/src/test/kotlin/org/webservices/pipeline/mock/ComprehensivePipelineTest.kt`
- pipeline should process 1000 items efficiently
- pipeline should handle embedding failures gracefully
- pipeline should deduplicate across runs
- chunking should create multiple vectors per item
- empty source should not crash pipeline
- metadata should include chunk information
- items with identical IDs should be deduplicated

### `stack.kotlin/embedding-worker/src/test/kotlin/org/webservices/pipeline/processors/EmbedderTest.kt`
- test successful embedding request
- test successful batched embedding request
- test batched embedding size mismatch throws
- test non-finite embedding values fail instead of being sanitized
- test retry on 429 rate limit
- test retry on 503 service unavailable
- test exponential backoff timing
- test max retries exhaustion throws exception
- test non-retryable error does not retry
- test text truncation for max tokens
- test telemetry stats tracking
- test jitter is applied to backoff
- test retryable exception types
- test empty response handling
- test malformed JSON response handling
- test all retryable status codes

### `stack.kotlin/gpu-bootstrap-arbiter/src/test/kotlin/org/webservices/gpubootstraparbiter/GpuBootstrapArbiterServiceTest.kt`
- keeps embedding priority when backlog is high
- switches to llm priority once backlog is low
- holds llm priority while backlog sits inside hysteresis band
- returns to embedding priority when backlog rises again
- signal failure does not prevent live target status from updating
- can hold embedding priority until initial pulls complete when configured
- holds current mode when monitor is degraded

### `stack.kotlin/gpu-bootstrap-arbiter/src/test/kotlin/org/webservices/gpubootstraparbiter/MainTest.kt`
- streamedRequestBody streams large payloads without prebuffering
- cpu llm backend uses models endpoint for health
- mutating and internal api endpoints fail closed when token is missing outside explicit dev or test mode
- mutating and internal api endpoints require configured token

### `stack.kotlin/gpu-workload-monitor/src/test/kotlin/org/webservices/gpuworkloadmonitor/GpuWorkloadMonitorServiceTest.kt`
- uses readiness and evidence to build workload signal
- falls back to evidence when readiness is unavailable
- reports degraded when neither evidence nor readiness is available

### `stack.kotlin/inference-controller/src/test/kotlin/org/webservices/inferencecontroller/InferenceControllerServiceTest.kt`
- cpu default keeps cpu backends selected
- gpu llm mode starts gpu llm and stops gpu embedding
- gpu embedding mode starts gpu embedding and stops gpu llm
- reports not ready while selected targets are unhealthy

### `stack.kotlin/inference-controller/src/test/kotlin/org/webservices/inferencecontroller/MainTest.kt`
- api status fails closed when token is missing outside explicit dev or test mode
- api status may run without token when explicit unauthenticated mode is enabled
- api status requires configured internal token

### `stack.kotlin/inference-gateway/src/test/kotlin/org/webservices/inferencegateway/InferenceGatewayServiceTest.kt`
- reports ready when controller exposes healthy targets
- resolves target base url from controller selection
- resolves direct target without consulting controller
- routes requested gpu model to healthy gpu backend even when controller defaults to cpu llm
- keeps selected llm when requested model is generic
- lists healthy llm backends with selected backend first
- reports unavailable when controller cannot be reached

### `stack.kotlin/inference-gateway/src/test/kotlin/org/webservices/inferencegateway/MainTest.kt`
- llm responses requests are translated to chat completions upstream with correlation headers
- llm cpu direct route rewrites shared model alias to cpu local alias
- llm gpu direct route proxies models without controller
- proxy routes fail closed when internal token is not configured outside explicit dev or test mode
- proxy routes require configured internal token
- llm responses route gpu model requests to gpu backend even when controller defaults to cpu
- llm responses rewrite stale model ids to the selected backend default model
- llm streaming responses are synthesized from upstream chat completion usage
- llm models endpoint aggregates healthy cpu and gpu catalogs

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/PipelineStatePathResolutionTest.kt`
- metadata path defaults to persistent volume
- dedup path defaults to persistent volume
- env overrides metadata and dedup paths
- system properties override defaults when env absent

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/PipelineStateRecoveryTest.kt`
- recover persisted pipeline state repairs missing success metadata from staged evidence

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/integration/RealDataIntegrationTest.kt`
- RSS source should fetch and validate real articles
- CVE source should fetch and validate real vulnerabilities
- Debian Wiki should fetch and validate pages if available
- Arch Wiki should fetch and validate from XML dump
- Linux Docs should scan and validate man pages
- all sources produce vector-storage-ready data

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/monitoring/MonitoringServerTest.kt`
- routes require api key and expose readiness queue and status data
- queue endpoints report unavailable when no staging store is configured
- readiness treats persisted success as initial pull complete after restart
- queue endpoints report timeout when staging queries time out
- readiness falls back to cheap evidence query when aggregate stats time out
- readiness falls back when cached snapshot exists but source stats are unavailable

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/monitoring/ProgressReporterTest.kt`
- start records latest staging and bookstack stats after one interval
- start keeps running when stats fetch throws

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/sentiment/GatewayRssSentimentClientTest.kt`
- infer parses chat completion payload and clamps score and confidence
- infer returns null for http errors blank responses and malformed payloads
- private parsing helpers derive labels escape json and normalize endpoints

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/sentiment/RssSentimentAnalyzerTest.kt`
- analyze captures bullish bitcoin signal
- analyze captures bearish cashtag signal
- analyze emits regional risk sentiment for regional crypto context
- analyze ignores non-first chunks
- analyze fails closed when model has no score

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/sources/CveSourceTest.kt`
- test CveEntry toText formatting
- test CveEntry contentHash is consistent
- test CveEntry contentHash changes with modification date
- test CveEntry handles null baseScore
- test CveEntry truncates long product lists

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/sources/OpenAustralianLegalCorpusSourceTest.kt`
- test AustralianLegalDocument toText formatting
- test AustralianLegalDocument toText handles empty citation
- test AustralianLegalDocument contentHash uses id
- test OpenAustralianLegalCorpusSource parses Parquet file
- test OpenAustralianLegalCorpusSource filters by jurisdiction
- test OpenAustralianLegalCorpusSource filters by type
- test OpenAustralianLegalCorpusSource filters short documents
- test OpenAustralianLegalCorpusSource respects maxDocuments limit

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/sources/WikiSourceTest.kt`
- test Debian wiki source initialization
- test Arch wiki source initialization
- test WikiType enum values
- test WikiPage data class
- test WikiPage toText formatting
- test WikiPage contentHash is unique
- test WikiPage long content truncation indicator
- test WikiPage with empty categories
- test WikiPage with many categories shows only first 5
- test source with categories parameter
- test source with empty categories uses recent pages
- test max pages limit
- test WikiPage ID format for different wiki types

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/sources/standardized/OpenAustralianLegalCorpusStandardizedSourceTest.kt`
- test source name is australian_laws
- test resync strategy is Monthly
- test backfill strategy is LegalDatabase
- test chunking is enabled
- test chunker configuration
- test AustralianLegalDocumentChunkable wraps document correctly
- test AustralianLegalDocumentChunkable metadata includes all fields
- test fetchForRun with INITIAL_PULL metadata
- test fetchForRun with RESYNC metadata
- test source accepts jurisdiction filter
- test source accepts document type filter
- test source accepts maxDocuments limit
- test source with all filters combined

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/sources/standardized/OpenDotaMatchStandardizedSourceTest.kt`
- fetches public match summaries as searchable documents
- optionally fetches match detail JSON

### `stack.kotlin/knowledge-ingestion/src/test/kotlin/org/webservices/pipeline/sources/standardized/PoeNinjaPriceStandardizedSourceTest.kt`
- fetches currency and item overview prices
- limits entries per type

### `stack.kotlin/pipeline-common/src/test/kotlin/org/webservices/pipeline/config/ConfigTest.kt`
- fromEnv creates config with default values
- fromEnv parses RSS configuration from environment
- fromEnv parses CVE configuration from environment
- fromEnv normalizes blank optional secrets to null
- fromEnv parses Binance configuration from environment
- fromEnv parses OpenDota configuration from environment
- fromEnv parses poe ninja configuration from environment
- fromEnv parses Wikipedia configuration from environment
- fromEnv parses Australian Laws configuration from environment
- fromEnv parses Linux Docs configuration from environment
- fromEnv parses Wiki configuration from environment
- fromEnv parses embedding configuration from environment
- fromEnv parses Qdrant configuration from environment
- fromEnv parses PostgreSQL configuration from environment
- fromEnv parses BookStack configuration from environment
- load reads valid YAML file
- load returns defaults when file not found
- load returns defaults when file is invalid YAML
- default config has sensible values
- EmbeddingConfig has correct BGE-M3 specifications
- RssConfig validates feed URLs format
- WikiConfig filters blank categories
- PostgresConfig handles empty password
- BookStackConfig disabled by default without credentials
- config uses bounded default CVE backfill and unlimited defaults for bulk sources

### `stack.kotlin/pipeline-common/src/test/kotlin/org/webservices/pipeline/core/StandardizedSourceIntegrationTest.kt`
- standardized source should implement all required methods
- chunkable items should implement required interface
- source should differentiate initial pull vs resync
- chunked source should provide chunker
- source strategies should provide descriptions

### `stack.kotlin/pipeline-common/src/test/kotlin/org/webservices/pipeline/mock/BackfillStrategyTest.kt`
- RssHistory should calculate correct backfill start
- WikiDumpAndWatch should return epoch for full dump
- WikipediaDump should return epoch
- CveDatabase should handle initial vs resync
- FullDatasetDownload should return epoch
- FilesystemScan should return epoch
- LegalDatabase should calculate from start year
- NoBackfill should return current time
- strategies should provide descriptive strings
- shouldBackfill should return correct values

### `stack.kotlin/pipeline-common/src/test/kotlin/org/webservices/pipeline/mock/SourceSchedulerTest.kt`
- should execute initial pull on startup
- DailyAt should calculate correct delay
- Hourly should calculate correct delay
- Weekly should calculate correct delay
- Monthly should calculate correct delay
- FixedInterval should calculate correct delay
- should provide correct strategy descriptions

### `stack.kotlin/pipeline-common/src/test/kotlin/org/webservices/pipeline/mock/StandardizedRunnerTest.kt`
- should process items without chunking
- should skip duplicate items
- should process items with chunking
- should record success metrics

### `stack.kotlin/pipeline-common/src/test/kotlin/org/webservices/pipeline/monitoring/SourceRuntimeTrackerTest.kt`
- register and snapshot initialize default awaiting state
- tracker records successful run lifecycle and counters
- tracker detects stalled active runs and records failures

### `stack.kotlin/pipeline-common/src/test/kotlin/org/webservices/pipeline/processors/ChunkerTest.kt`
- should not chunk text smaller than max tokens
- should chunk long text with overlap
- should chunk based on tokens not characters
- should handle text with newlines
- should create embedding-optimized chunker for BGE-M3
- should create embedding-optimized chunker for custom token limit
- should set chunk metadata correctly
- should not create tiny chunks
- should generate correct descriptions
- should handle real-world text with punctuation
- should handle BGE-M3 sized chunks efficiently

### `stack.kotlin/pipeline-common/src/test/kotlin/org/webservices/pipeline/processors/TokenCounterTest.kt`
- countTokens - short text
- countTokens - longer text
- countTokens - CJK text has higher token count
- truncateToTokens - text under limit
- truncateToTokens - text over limit
- truncateToTokens - exactly at limit
- chunkByTokens - short text no chunking needed
- chunkByTokens - long text requires chunking
- chunkByTokens - with overlap
- chunkByTokens - all text is preserved
- countTokens - empty string
- truncateToTokens - empty string
- chunkByTokens - empty string
- countTokens - BGE-M3 realistic case
- chunkByTokens - BGE-M3 8192 token limit

### `stack.kotlin/pipeline-common/src/test/kotlin/org/webservices/pipeline/storage/DeduplicationStoreTest.kt`
- test isSeen returns false for new items
- test markSeen and isSeen
- test checkAndMark returns false for new items
- test checkAndMark returns true for existing items
- test flush persists data to disk
- test persistence with metadata
- test size returns correct count
- test clear removes all entries
- test handles many items
- test concurrent access to same hash
- test empty metadata is handled

### `stack.kotlin/pipeline-common/src/test/kotlin/org/webservices/pipeline/storage/DocumentStagingStoreTest.kt`
- StagedDocument data class has correct properties
- StagedDocument supports chunking metadata
- EmbeddingStatus enum has all required states
- stageBatch should insert documents into database
- getPendingBatch should return pending documents
- updateStatus should change document status
- getStats should return correct counts
- getStatsWithQueryTimeout should return correct counts
- getStatsBySourceWithQueryTimeout should filter by source
- getStatsBySourcesWithQueryTimeout should aggregate per source
- getBookStackStatsBySourcesWithQueryTimeout should aggregate publication state
- StagedDocument handles special characters
- stageBatch handles empty list gracefully

### `stack.kotlin/pipeline-common/src/test/kotlin/org/webservices/pipeline/storage/SourceMetadataTest.kt`
- test load returns default metadata for non-existent source
- test save and load metadata
- test recordSuccess updates metadata correctly
- test recordSuccess accumulates totals
- test recordSuccess resets consecutive failures
- test recordFailure increments consecutive failures
- test recordFailure updates lastAttemptedRun
- test listAll returns all sources
- test listAll returns empty list when no sources exist
- test checkpoint data is preserved across updates
- test empty checkpoint data is handled
- test metadata persists across store instances

### `stack.kotlin/progression/src/test/kotlin/org/webservices/progression/ProgressionEngineTest.kt`
- task remains available until evidence is verified
- reward unlocks from verified claim
- scanner parses compose services from generated compose
- scanner parses Caddy hosts with rendered domain placeholders
- state store merges actual facts without losing claims
- declared progression catalog covers every sovereign compute phase
- every declared progression task has evidence commands and a dashboard surface
- declared progression tasks transition independently as evidence arrives

### `stack.kotlin/search-service/src/test/kotlin/org/webservices/searchservice/SearchGatewayTest.kt`
- SearchResult inferContentType identifies articles
- SearchResult inferContentType identifies wikipedia
- SearchResult inferContentType identifies CVE
- SearchResult inferContentType identifies legal
- SearchResult inferContentType identifies code
- SearchResult inferContentType defaults to document
- SearchResult inferContentType identifies grafana presentation targets
- SearchResult inferCapabilities identifies time series grafana results
- SearchResult inferCapabilities identifies BookStack results as interactive
- SearchResult inferCapabilities for URL with hasRichContent
- SearchResult inferCapabilities for URL without URL
- SearchResult inferCapabilities includes humanFriendly for articles
- SearchResult inferCapabilities includes agentFriendly for code
- SearchResult inferCapabilities treats vulnerabilities as human-usable structured links
- SearchResult data class holds correct values
- SearchResult handles empty metadata
- SearchResult handles special characters in fields
- redactSearchQueryForLog omits raw query text
- validateSearchRequest rejects empty collection list
- validateSearchRequest rejects unsafe collection names
- validateSearchRequest rejects excessive collection fanout
- validateSearchRequest allows wildcard and safe collection names

### `stack.kotlin/search-service/src/test/kotlin/org/webservices/searchservice/SearchGatewayVectorTest.kt`
- search rejects unknown mode before invoking backends
- vector search parses qdrant payload and forwards api key
- vector search exposes qdrant point id when document id payload is absent
- vector search fails when every qdrant collection errors
- vector search fails when embedding service returns empty payload
- vector search retries embedding overload before succeeding
- vector search surfaces embedding overload as IO failure after retries
- vector search with zero limit short circuits qdrant request
- hybrid search returns vector results when full text backend fails
- hybrid search with limit one still queries each backend with a usable limit
- bm25 and fulltext modes fail when every postgres collection fails
- preferred URL helpers honor priority and skipped bookstack rules
- presentation target and direct metadata rules are detected
- identity key prefers stable identifiers and classifiers handle edge cases
- derive grafana URL supports explicit values path and dashboard metadata
- metadata enrichment derives wikipedia URLs and fallback titles
- merge results falls back through candidate and existing URLs
- rerank deduplicates matches merges metadata and boosts exact title match

### `stack.kotlin/test-manager/src/test/kotlin/org/webservices/testmanager/MainTest.kt`
- serves health summary and run endpoints
- returns structured bad requests and not found responses
- rejects missing api key

### `stack.kotlin/test-manager/src/test/kotlin/org/webservices/testmanager/config/TestManagerConfigTest.kt`
- loadConfig uses defaults when environment is absent or invalid
- loadConfig honors explicit environment overrides

### `stack.kotlin/test-manager/src/test/kotlin/org/webservices/testmanager/service/TestManagerServiceTest.kt`
- overview and queueManualRun use manifests state and fingerprints
- getRunLog prefers file contents and falls back to tail
- getRunLog refuses paths outside log root and redacts sensitive output
- start creates data directories
- private helpers cover blocker state fingerprint and trimming logic
- releaseInfo supports json and legacy key value formats
- evaluateSuitesOnce and executeRun cover scheduler paths

### `stack.kotlin/test-manager/src/test/kotlin/org/webservices/testmanager/storage/TestManagerStoreTest.kt`
- persists suite state and queued run lifecycle
- run view mirrors run record fields

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/BookStackHardeningConfigTest.kt`
- api bookstack virtual host only proxies api paths
- public guest role is permissionless by default
- automation token is non admin bound and time limited

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/ChatGptConnectorConfigTest.kt`
- connector is included in deployable app stack with explicit dependencies and secrets
- connector route keeps mcp bearer endpoint outside keycloak redirect while protecting ui
- connector is registered in portal route catalog and visual coverage
- agent docs ingestion is wired for connector searchable corpus

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/CredentialValidationTest.kt`
- validateCredentials should accept valid credentials
- validateCredentials should reject blank username
- validateCredentials should reject blank password
- validateCredentials should reject short username
- validateCredentials should reject long username
- validateCredentials should reject short password
- validateCredentials should reject weak password 'password'
- validateCredentials should reject weak password '12345678'
- validateCredentials should reject weak password 'admin123'
- validateCredentials should reject weak password 'test1234'
- validateCredentials should accept minimum length username
- validateCredentials should accept maximum length username
- validateCredentials should accept minimum length password
- validateCredentials should accept strong password
- validateCredentials should be case-insensitive for weak passwords
- validateCredentials should accept username with numbers
- validateCredentials should accept username with underscores
- validateCredentials should accept email as username

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/DocumentationCrossCheckTest.kt`
- identity cutover doc matches restored keycloak backed services
- test command docs match granular runner surface
- workspace docs match dispatcher notebook ttyd ssh and codex token endpoints
- architecture and operations docs match procedural docs and purge implementation

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/EnhancedAuthenticationTestsTest.kt`
- enhanced auth test suite should initialize without directory helper
- enhanced auth suite should have correct test structure
- auth helper should properly validate session cookies
- test environment should have all required OIDC secrets
- test environment should distinguish dev vs prod mode
- enhanced auth tests should use proper error handling
- enhanced auth tests should validate HTTP status codes
- SQL injection test strings should be properly escaped
- test suite should properly clean up ephemeral users
- Keycloak edge auth helper should be properly initialized
- enhanced auth tests should use correct service endpoints
- test should run with keycloak helper only
- enhanced auth suite should test all critical flows

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/ForgejoAuthHardeningConfigTest.kt`
- forgejo disables local password auth and stack admin password injection
- forgejo bootstrap uses scoped temporary tokens without command line secret push
- forgejo oidc uses public issuer and repairs existing source
- forgejo runner token generation keeps token private and avoids cli injection primitives
- forgejo public edge blocks password token creation endpoint

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/GrafanaSecretLoggingConfigTest.kt`
- grafana moves database password out of environment before startup

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/HomeAssistantAuthConfigTest.kt`
- home assistant exposes keycloak edge auth through trusted frontend flow
- home assistant keycloak provider canonicalizes usernames and trusts only edge headers
- home assistant bootstrap relinks stack admin credentials to active user

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/JellyfinConfigTest.kt`
- entrypoint does not precreate system config before first database initialization
- sso plugin configuration keeps keycloak group authorization
- jellyfin reconciles configured stack admin users
- jellyfin keeps native playback defaults unless compatibility mode is enabled
- jellyfin route keeps sso boundary without playback rewriting sidecar

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/JupyterAndTunnelHardeningConfigTest.kt`
- notebook startup writes env with restrictive permissions
- isolated docker vm tunnel uses non-root runtime and locked-down container settings
- jupyterhub does not inject shared production secrets into user notebooks

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/JupyterHubConfigTest.kt`
- jupyterhub spawner uses configured notebook image
- jupyterhub remote user login handler authenticates from proxy headers directly
- jupyterhub single user servers run as non-root and pre-spawn volume permissions are fixed

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/KeycloakIdentityConfigTest.kt`
- keycloak service is a default core identity service
- keycloak realm import keeps groups and smoke client reproducible
- self service onboarding is invite backed and event marker driven
- postgres keycloak database bootstrap supports fresh and existing deployments
- retired directory user migration tooling is not bundled after one time cutover
- retired directory runtime services are removed while Keycloak backed groupware is restored
- service routes enforce keycloak group rbac at the edge

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/MastodonAuthHardeningTest.kt`
- mastodon oidc state and nonce protections remain enabled and host filtering is not globally disabled
- mastodon persists federated media cache across web and sidekiq
- mastodon recommendation bootstrap clears missing cache-backed attachment metadata

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/MatrixMailExposureHardeningTest.kt`
- public Matrix routes do not expose Synapse admin API
- apex Matrix discovery stays public for external clients
- default Matrix rooms are encrypted and allow member calls
- Element login immediately enters internal SSO
- Element client uses internal MatrixRTC without external identity widget or Jitsi providers
- MatrixRTC backend routes are internal LiveKit only
- Synapse enables MatrixRTC prerequisites
- Synapse main service runs non-root with container hardening
- mailserver does not publish plaintext IMAP and requires TLS for authentication

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/PurgeScriptConfigTest.kt`
- webservices purge covers systemd compose and labware workspace resources
- site storage purge matches hardcoded external storage roots

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/SeafileSynapseHardeningConfigTest.kt`
- synapse entrypoint fails closed when privilege drop helpers are missing
- seafile schema reset validates identifiers and api edge strips remote user headers

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/SimpleTests.kt`
- ServiceEndpoints fromEnvironment creates valid configuration
- ServiceEndpoints forLocalhost uses localhost addresses
- TestEnvironment detect returns valid environment
- DatabaseConfig generates PostgreSQL JDBC URL
- DatabaseConfig generates MariaDB JDBC URL
- HealthStatus data class works correctly
- HealthStatus handles errors
- FetchResult handles success and failure
- MariaDbResult handles query results
- TestResult sealed class has correct variants
- TestSummary calculates correctly
- PostgreSQL uses JDBC URL
- Pipeline endpoint is properly configured
- All expected pipeline collections are named correctly

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/StackDeploymentHelpersTest.kt`
- runtime root lives in user tmpfs
- docker compose command construction uses bundle root and runtime env
- docker compose ps command for readiness
- required bundle files list is correct
- workspace provisioner depends on platform prerequisites
- deploy reloads active units when rebuilt local images change
- test runner search client queries OpenSearch
- caddy can resolve isolated workspace runtime host
- forgejo runner ssh mount uses dedicated render-managed host directory
- mastodon stack targets postgres ssd across all roles
- status output parsing detects healthy status
- status output parsing detects unhealthy status

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/SupplyChainHardeningTest.kt`
- high risk image references are immutable and watchtower cannot update by default
- release packaging rejects symlinks outside the package source root
- release packaging rejects absolute and parent traversal input paths
- site manifest member resolution rejects symlink targets outside the manifest directory
- systemd renderer rejects graph names that could escape output paths or inject directives
- systemd user unit install copies rendered units instead of linking bundle files

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/TestArchitectureTest.kt`
- main exposes the repo owned Kotlin suites only
- run tests script uses the static test runner service
- docker daemon access is split between read only and controller proxies
- testdev is pinned to labware and nested docker
- security sensitive runtime boundaries are explicit
- testdev storage transform shares generated volumes by source path
- mariadb supports internal bootstrap root connections
- shipped deploy script is bundle local and renders runtime in user tmpfs
- scripts directory only exposes the current bundled command surface
- repo root only exposes build as public local deployment command
- build requires explicit site manifest and no repo site-config link exists
- deploy recreates top-level reports mount for portal after rsync delete
- test runner image uses the stable playwright uid for host bind mounts
- managed runner auth defaults to keycloak without authelia api wiring
- wait ready resolves bundled common helper and run time stays in runtime dir
- MatrixRTC services are app services and no Jitsi services are present
- compose startup gates reserve health checks for stateful prerequisites and init jobs

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/VaultwardenSsoEntryConfigTest.kt`
- vaultwarden portal entry preselects internal sso organization
- vaultwarden sso derives email from keycloak verified email claim
- embedding service is not exposed in portal visible config
- portal exposes restored keycloak backed sogo web ui

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/framework/CaddyVirtualHostTest.kt`
- https virtual host requests rewrite the request host instead of sending a conflicting host header
- http virtual host requests preserve transport target and forward the desired host explicitly

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/framework/DockerCliTest.kt`
- detects top level mutating docker commands
- detects compose mutating commands and ignores read only compose queries
- treats inspect and info commands as non mutating

### `stack.kotlin/test-runner/src/test/kotlin/org/webservices/testrunner/framework/TokenManagerTest.kt`
- test TokenManager initialization
- test token storage and retrieval
- test token clearing
- test clearAll clears all tokens
- test Seafile token acquisition
- test failed token acquisition
- test Planka token acquisition
- test Planka token acquisition blocked when OIDC enforced

### `stack.kotlin/workspace-provisioner/src/test/kotlin/org/webservices/workspaceprovisioner/AuthenticatorTest.kt`
- forward auth headers require trusted proxy secret
- bearer token auth still works when spoofed remote user header is present
- workspace agent token authenticates without OIDC roundtrip

### `stack.kotlin/workspace-provisioner/src/test/kotlin/org/webservices/workspaceprovisioner/MainTest.kt`
- health and ready endpoints return typed json payloads
- shell auth endpoint returns ttyd headers
- notebook auth endpoint returns notebook headers
- codex token endpoints are write only authenticated controls

### `stack.kotlin/workspace-provisioner/src/test/kotlin/org/webservices/workspaceprovisioner/SmallstepSshCaTest.kt`
- certificate principal is workspace scoped

### `stack.kotlin/workspace-provisioner/src/test/kotlin/org/webservices/workspaceprovisioner/WorkspaceProvisionerServiceTest.kt`
- create workspace skips host ports that are already unavailable
- create workspace retries with a new port when docker reports bind collision
- workspace summaries expose ssh notebook and ttyd access links
- codex token is written only into the workspace runtime secret file
- ssh certificate access returns host trust material for accessible workspace
- docker runtime rejects unsafe docker identifiers before shelling out
- search backend base url validation rejects credentials and non-http schemes

### `stack.kotlin/workspace-provisioner/src/test/kotlin/org/webservices/workspaceprovisioner/WorkspaceStoreTest.kt`
- workspace delegations grant access
- renew lease extends from current expiry

## Kotlin managed integration suites

Runtime stack-contract suites run by stack.containers/test-runner/run-tests.sh kt* targets.

- Files: 38
- Test/suite entries: 265

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/AgentCapabilityContractTests.kt`
- Agent Capability Contract Tests
- normalize_whitespace helper executes successfully
- normalize_whitespace helper is idempotent around clean text
- normalize_whitespace helper trims leading and trailing whitespace
- uuid_generate helper executes successfully
- normalize_whitespace rejects malformed inputs without a server error
- Unknown helper names return a client-facing error

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/AgentCapabilityTests.kt`
- No direct test entries; helper/suite registration source.

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/AgentLlmQualityTests.kt`
- No direct test entries; helper/suite registration source.

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/AgentOrchestrationTests.kt`
- No direct test entries; helper/suite registration source.

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/AgentSecurityTests.kt`
- Agent Security Boundary Tests
- Workspace API rejects unauthenticated listing
- Workspace API rejects unauthenticated creation
- Workspace OIDC discovery exposes no client secrets

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/AgentWorkspaceSuites.kt`
- Agent Workspace Base Environment Tests
- Agent Workspace Optional Profile Tests
- Agent Workspace Polyglot Fixture Tests
- Agent Workspace Aider Runtime Tests
- Owned Agent Workspace Tests
- Agent workspace source and fixture contexts are present
- Owned agent workspace contains the base toolchains
- Owned agent workspace seeds the expected home layout
- Cleanup direct agent environment resources
- Optional agent profiles are advertised
- Rust profile installs and builds the Rust fixture
- .NET profile installs and builds the C# fixture
- PHP, Ruby, and web-static profiles install and verify
- Cleanup direct agent expansion resources
- Base fixture $fixtureName builds and runs
- Cleanup direct agent fixture resources
- Aider CLI wrapper is installed in the owned workspace
- Aider can complete a real workspace repair task on the CPU LLM backend
- Aider can complete a real workspace repair task on the GPU LLM backend
- Cleanup direct agent runtime resources
- Owned agent identity metadata is available for the dispatcher
- Owned agent workspace starts through workspace-provisioner
- Owned agent workspace can build software through workspace-provisioner
- Notebook sidecar shares the owned agent home volume
- Cleanup owned agent workspace resources

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/AuthenticatedOperationsTests.kt`
- Authenticated Operations Tests
- Grafana: Acquire API key and query datasources
- Seafile: Acquire token and list libraries
- Forgejo: Acquire token and list repositories
- Planka: Validate API auth path under SSO policy
- Mastodon: Acquire OAuth token and verify credentials
- JupyterHub: Authenticate and access hub API
- Models gateway: Arbitrary Authorization header does not bypass Keycloak edge auth
- Models gateway: Authenticated browser session can access API
- Ntfy: Authenticate and access notification API
- Kopia: Authenticate and access backup UI
- OpenSearch: Authenticate and access API
- Pipeline: Authenticate and access management API
- Token manager: Store and retrieve tokens
- Token manager: Clear tokens

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/AuthenticationTests.kt`
- Authentication & Authorization Tests
- Keycloak realm discovery document is available
- Keycloak auth gateway redirects unauthenticated protected routes
- Trusted internal edge identity reaches protected Keycloak route
- Grafana requires authentication
- Vaultwarden requires authentication for API
- BookStack requires authentication for content
- Forgejo requires authentication for API
- Mastodon requires authentication for timeline
- JupyterHub requires authentication
- Seafile requires authentication for API
- Planka requires authentication for boards

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/BackupTests.kt`
- Backup Tests
- Kopia server enforces authentication
- Kopia endpoint is configured
- Kopia web UI boundary responds

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/BookStackIntegrationTests.kt`
- BookStack Integration Tests
- BookStack API is accessible when credentials are configured
- publication task records BookStack presentation metadata

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/CachingLayerTests.kt`
- Caching Layer Tests
- Valkey: Service is reachable
- Valkey: PING command responds with PONG
- Valkey: SET and GET operations work
- Valkey: TTL expiration works
- Valkey: Connection pooling works
- Valkey: Multiple concurrent connections
- Valkey: Hash operations work
- Valkey: List operations work
- Valkey: Set operations work
- Valkey: Atomic increment operations
- Memcached: Service is reachable
- Memcached: ASCII protocol SET/GET works
- Valkey: Key existence and deletion
- Valkey: Database info is available
- Valkey: Database statistics available

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/CollaborationTests.kt`
- Collaboration Tests
- Mastodon web server is healthy
- Mastodon streaming server is healthy
- Mastodon can fetch instance info
- Mastodon public timeline endpoint exists

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/CommunicationTests.kt`
- Communication Tests
- Mailserver SMTP port configuration exists
- Mailserver accepts connections on port 25
- Mailserver configuration is valid
- Mailserver endpoint is reachable via DNS
- Synapse homeserver is healthy
- Synapse federation endpoint responds
- Synapse server info is accessible
- Element web app loads
- Element can connect to homeserver
- Mastodon recommendation seeder container is running
- Mastodon native bootstrap recommendations are configured

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/DataPipelineTests.kt`
- Data Pipeline Tests
- ingestion runner is healthy
- checkpoint and publication schema exists
- bootstrap creates OpenSearch index and Qdrant collections
- bounded stack knowledge ingestion writes metadata and vectors
- publication task writes BookStack presentation metadata

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/DatabaseTests.kt`
- Database Tests
- Postgres transaction commits successfully
- Postgres connection pool is healthy
- Postgres query performance is acceptable
- Postgres foreign key constraints work
- Postgres can query system tables
- Valkey configuration is accessible
- Valkey port is standard Redis port
- Valkey endpoint is reachable
- MariaDB bookstack schema is accessible
- MariaDB query returns bookstack data

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/EmailStackTests.kt`
- Email Stack Tests
- Mailserver: SMTP port is reachable
- Mailserver: SMTP greeting is valid
- Mailserver: EHLO command accepted
- Mailserver: STARTTLS capability available
- Mailserver: Submission port (587) is accessible
- Email Stack: SMTP and IMAP ports are distinct
- Email Stack: Secure SMTP (465) availability
- Email Stack: IMAP port (143) availability
- Email Stack: Secure IMAP (993) availability

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/EnhancedAuthenticationTests.kt`
- Enhanced Authentication Tests
- Phase 1: Trusted internal edge session is explicit and secure
- Phase 1: Trusted internal edge session persists across multiple requests
- Phase 1: Untrusted identity headers are rejected
- Phase 1: Complete edge auth flow reaches protected route
- Phase 2: Keycloak OIDC discovery document contains required endpoints
- Phase 3: Caddy Keycloak edge auth redirects unauthenticated requests
- Phase 3: Authenticated request reaches protected service
- Phase 3: Users group can access allowed services
- Phase 3: Internal trusted edge path requires shared secret
- Phase 4: Single edge identity grants access to multiple services
- Phase 4: Logout clears local test session state

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/ExtendedCommunicationTests.kt`
- Extended Communication Tests
- Mastodon: Web interface is accessible
- Mastodon: API endpoint responds
- Mastodon: Streaming API is reachable
- Mastodon: Public timeline endpoint exists
- Mastodon: OAuth endpoint exists
- Mastodon: Static assets are served
- Mastodon: Federation is configured
- Mastodon: ActivityPub endpoint responds
- Mastodon: Media upload API does not allow anonymous upload
- Mastodon: cached attachment records resolve to files
- Ntfy: Topics can be created
- Ntfy: Message publishing endpoint
- Ntfy: JSON API endpoint

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/ExtendedProductivityTests.kt`
- Extended Productivity Tests
- OnlyOffice: Service is accessible
- OnlyOffice: Health check endpoint
- OnlyOffice: Document conversion API exists
- OnlyOffice: Document editing service exists
- OnlyOffice: Static resources are served
- JupyterHub: Service is accessible
- JupyterHub: Login route is protected by edge auth
- JupyterHub: API endpoint responds
- JupyterHub: User API endpoint enforces authentication
- JupyterHub: OAuth integration configured
- JupyterHub: Static assets are served
- JupyterHub: Kernel specifications endpoint
- Integration: JupyterHub + Data Pipeline analysis capability

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/FileManagementTests.kt`
- File Management Tests
- Seafile server is healthy
- Seafile web interface loads
- Seafile API endpoint responds
- OnlyOffice document server is healthy
- OnlyOffice web interface responds

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/FoundationTests.kt`
- Foundation Tests
- OpenSearch is healthy
- OpenSearch returns 404 for unknown exact document lookup
- Workspace provisioner health endpoint responds
- Workspace provisioner ready endpoint reports usable state
- Workspace provisioner OIDC discovery is publicly readable
- Workspace provisioner rejects unauthenticated workspace listing
- Embedding backend health endpoint responds
- Caddy exports its local CA bundle for dependent services

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/HomeAssistantTests.kt`
- Home Assistant Tests
- Home Assistant web interface loads
- Home Assistant API responds
- Home Assistant config endpoint exists
- Home Assistant states endpoint exists
- Home Assistant services endpoint exists
- Home Assistant events endpoint exists
- Home Assistant error log endpoint
- Home Assistant history endpoint exists
- Home Assistant logbook endpoint exists
- Home Assistant panel manifest

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/InfrastructureTests.kt`
- Infrastructure Tests
- Keycloak realm endpoint is accessible
- Keycloak OIDC discovery works
- Keycloak auth gateway redirects unauthenticated users
- Keycloak edge authentication flow works
- Keycloak auth gateway health endpoint responds
- Keycloak validates OIDC client config
- retired directory compatibility service is absent from the runtime contract

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/KnowledgeBaseTests.kt`
- Knowledge Base Tests
- Query MariaDB blocks forbidden patterns
- Semantic search executes
- Vectorization pipeline: embed → store → retrieve

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/LlmTests.kt`
- LLM Integration Tests
- LLM chat completion generates response
- LLM completion handles system prompts
- LLM streamed responses preserve usage contract
- LLM embed text returns vector

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/MicroserviceTests.kt`
- Pipeline Tests
- Pipeline: Health check
- Pipeline: List data sources
- Pipeline: Check scheduler status

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/MonitoringTests.kt`
- Monitoring Tests
- Prometheus server is healthy
- Prometheus can execute PromQL query
- Prometheus targets endpoint responds
- Grafana server is healthy
- Grafana login page loads
- Node Exporter metrics endpoint
- cAdvisor metrics endpoint
- Prometheus scraping node-exporter
- Prometheus scraping cadvisor
- Dozzle web interface accessible
- Dozzle healthcheck endpoint
- AlertManager status endpoint
- AlertManager alerts endpoint

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/PlaywrightE2ETests.kt`
- Playwright E2E Tests
- Run Playwright fast E2E suite

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/ProductivityTests.kt`
- Productivity Tests
- BookStack web interface loads
- BookStack API endpoint is accessible
- BookStack health check responds
- Forgejo git server web interface is healthy
- Forgejo web interface loads
- Forgejo API enforces authentication
- Planka board server is healthy
- Planka web app loads
- Jupyter notebook image is present for JupyterHub spawns

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/RecoveryTests.kt`
- Recovery Drill Tests
- PostgreSQL logical dump restores into a disposable container
- MariaDB logical dump restores into a disposable container

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/SearchServiceTests.kt`
- OpenSearch Retrieval Provider
- OpenSearch cluster is healthy
- knowledge index exists
- BM25 text search returns indexed knowledge
- client helper normalizes OpenSearch results

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/SecurityTests.kt`
- Security Tests
- Vaultwarden server is healthy
- Vaultwarden web vault loads

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/StackLlmCapabilityTests.kt`
- No direct test entries; helper/suite registration source.

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/TestdevProfile.kt`
- No direct test entries; helper/suite registration source.

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/TruthAssertions.kt`
- No direct test entries; helper/suite registration source.

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/UserInterfaceTests.kt`
- User Interface Tests
- JupyterHub hub API is accessible
- JupyterHub root endpoint responds

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/UtilityServicesTests.kt`
- Utility Services Tests
- Stack Portal dashboard loads
- Stack Portal serves generated module API
- Stack Portal profile API endpoint accessible
- Ntfy server is accessible
- Ntfy health endpoint responds
- Ntfy can create test topic
- Ntfy JSON API works

### `stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/WebServicesTests.kt`
- No direct test entries; helper/suite registration source.

## Shell fixture verifiers

Language/profile fixture checks invoked by agent workspace managed suites.

- Files: 15
- Test/suite entries: 15

### `stack.containers/test-runner/fixtures/agent-lang/base/c/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/base/cpp/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/base/docker-compose/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/base/go/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/base/java/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/base/js-ts/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/base/kotlin/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/base/python/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/base/shell/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/base/sql/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/optional/dotnet/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/optional/php/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/optional/ruby/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/optional/rust/verify.sh`
- verify.sh

### `stack.containers/test-runner/fixtures/agent-lang/optional/web-static/verify.sh`
- verify.sh
