# Optional local explanations and receipt vision

Both features are disabled by default and reach host Ollama only through the core
backend and internal FastAPI service. They never use external web search. Existing
optional merchant web enrichment remains a separate policy.

## Local setup

Configure non-secret values in the private Compose environment:

```text
RECOMMENDATION_EXPLAINER_ENABLED=true
RECEIPT_VISION_ENABLED=true
OLLAMA_BASE_URL=http://host.docker.internal:11434
OLLAMA_REASONING_MODEL=<installed compatible reasoning model>
OLLAMA_VISION_MODEL=qwen2.5vl:3b
AI_ASSISTANCE_CONNECT_TIMEOUT=2s
AI_ASSISTANCE_READ_TIMEOUT=35s
```

First check `ollama --version`, `ollama list` and available memory. Qwen2.5-VL
requires a compatible runtime (at least 0.7.0). The 3B quantized download is about
3.2 GB; runtime memory is larger. After approving the download, use
`ollama pull qwen2.5vl:3b` and benchmark a synthetic receipt. Models are never
downloaded automatically. Keep vision experimental if quality or latency fails.

```powershell
docker compose --env-file .env config --quiet
docker compose up -d --no-deps --build ai-service backend frontend
```

FastAPI local assistance defaults to 30 seconds and accepts
`OLLAMA_ASSISTANCE_TIMEOUT_SECONDS` up to 50 seconds for cold local vision.
Keep `AI_ASSISTANCE_READ_TIMEOUT` above that limit (for example 55s for a 50s
local budget). The default remains 2/35 seconds. No retries are made.
Forecast retains its 5-second default; merchant recognition retains 90 seconds.
Both Java AI clients use HTTP/1.1 to avoid unsupported clear-text HTTP/2 upgrades
at the internal FastAPI server. Web-provider connection drops return the existing
unavailable fallback, without retries or raw-response logging.
Pillow validates image content/dimensions and strips metadata entirely in memory.

## Small-GPU performance controls

- `OLLAMA_EMBEDDING_CPU_ONLY=true` places the existing embedding model on CPU,
  leaving GPU offload to reasoning/vision. No machine-wide Ollama settings change.
- `OLLAMA_EMBEDDING_CONTEXT_SIZE=1024` bounds the short embedding inputs. Reasoning
  and receipts retain a 4096-token context; merchant generation is capped at 512
  tokens and assistance at 256. Output validation remains strict.
- Only public catalog vectors are cached, keyed by catalog content and embedding
  configuration. User merchant text and model responses are not cached.
- One process-wide admission slot covers local embedding, merchant reasoning and
  assistance calls. Busy inference returns the existing safe fallback instead of
  building an unbounded queue. Forecast and health remain independent.
- `MERCHANT_BATCH_TIMEOUT_SECONDS` defaults to 80 and cannot exceed 80 seconds,
  below the backend's 90-second budget. Each network stage consumes the remaining
  budget, without automatic retries. Exhausted batches return one safe result per
  request key. A cancelled worker cannot write database data; its current call
  retains a bounded socket timeout and cannot start another expired stage.

These controls do not guarantee OCR accuracy or fixed latency on every image.
Keep the vision feature experimental and retain human review and deterministic
fallback behavior.

## Explanation contract

Internal `POST /api/v1/recommendations/explain` accepts only:

```json
{"contractVersion":"local-financial-assistance-v1","ruleCode":"SAVINGS_MARGIN","factCode":"SAVINGS_RATE"}
```

No amount, identity, account ID or transaction text is sent. The model explains a
controlled finding in French. Strict JSON/factor references are validated; numbers,
unknown fields, prose/thinking envelopes and prohibited promises/product advice
are rejected. Backend facts remain displayed separately. Schema validity alone
does not prove semantic accuracy: review a curated set of explanations before
enabling the demonstration. Failure returns original deterministic wording.
Inference runs outside DB transactions; prompts/model thinking are never logged.

## Receipt preview

Authenticated `POST /api/receipts/extract` accepts multipart `file`: one JPEG/PNG,
at most 2 MiB and four million pixels. Response fields: `status`, nullable
`merchant`, `date`, decimal-string `amount`, nullable `currency`, user-local
`asOfDate`. Statuses: EXTRACTED, PARTIAL, UNAVAILABLE, INVALID_RESPONSE.

Internal `POST /api/v1/receipts/extract` receives only `contractVersion` and
`imageBase64`. Images are validated and re-encoded without EXIF/comments before
local inference. Originals are not retained. Receipt text is untrusted; there are
no model tools. Java validates dates, positive scale-4 amounts and currencies again.
Missing fields stay missing; no currency guessing, conversion or rounding.
Receipt schema digit patterns use explicit ASCII `[0-9]` classes supported by
Ollama's grammar compiler; date, decimal precision and extra-field validation
remain strict.

The internal vision schema transcribes `merchant`, `dateText`, `totalText`,
`totalLabel` and `currency` (all required, nullable, no extra fields). It does not
ask the model to convert dates or decimals. Python deterministically interprets
French/Tunisian numeric dates day-first (DD/MM/YYYY, with slash, dash or dot),
preserves explicitly printed ISO dates, and replaces a decimal comma with a dot
without rounding. Mixed/thousands separators and precision beyond four places
are rejected. This is an explicit French/Tunisian workflow, not automatic locale
detection; uncertain or unsupported date formats must be left missing.

Only supported final-total/payment labels can produce an amount. Subtotal,
`TOTAL NET`, VAT and item-price labels produce a missing amount for human review.
The prompt requests the final paid amount including taxes and stamp fees, never
a model-calculated sum of items. A recognized label does not prove OCR accuracy.
The external FastAPI/Java response contract is unchanged; printed intermediate
fields remain transient and are not returned, retained or logged.

Phone EXIF orientation is applied before metadata removal, and transparent PNG
backgrounds are flattened onto white. No OCR dependency, automatic deskew, crop,
model download or additional inference call is introduced.

Every result still
requires human review. A structurally valid extracted date outside 1900 through the
user-local current date is discarded rather than guessed or swapped: the backend
returns `PARTIAL` with a null date and the other validated fields. The UI asks the
user to copy the printed date, and export rejects missing/future dates until corrected
and explicitly reviewed again. Invalid money, currencies, malformed JSON/schema and
incoherent extraction statuses still fail closed. No original image is retained.

The review panel shows a browser-local image beside the editable fields (stacked
on smaller screens) and accessible field-specific warnings. Object URLs are
released when changing files or leaving the page. No browser image storage is
used. Changing a field invalidates the review checkbox again.

On Transactions, review/correct fields, confirm the absence of an existing bank
transaction, then export UTF-8 CSV: `date,description,amount,direction,currency`.
Amounts are positive scale 4; direction is DEBIT. Import through the existing CSV
preview afterwards. Extraction/export creates no transaction, account, category,
source file or prediction row. Formula-like merchant strings cannot be exported.

Different overlapping sources are not automatically deduplicated. Do not import
receipts already represented by bank transactions. PDFs, batches, line items and
automatic transaction matching are outside this prototype.

## Focused receipt validation

From `ai-service/`:

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_assistance.py tests/test_inference_efficiency.py
```

From `backend/`:

```powershell
.\mvnw.cmd "-Dtest=AssistanceServiceTests,AssistanceClientTests,RecommendationApiIntegrationTests" test
```

From `frontend/`:

```powershell
npm test -- --run src/components/ReceiptPanel.test.jsx src/services/receiptCsv.test.js
npx --no-install eslint src/components/ReceiptPanel.jsx src/components/ReceiptPanel.test.jsx src/services/receiptCsv.js src/services/receiptCsv.test.js
npm run build
```

Tests use fake inference responses and generated images, never real Ollama/web
calls. They cover day-first/ISO/leap dates, decimal preservation, final-total
labels versus VAT/subtotals, strict-invalid responses, EXIF/alpha handling,
unchanged Java wire fields, authenticated non-persistent previews, local URL
cleanup, stale responses, field warnings and mandatory review. These are
regression fixtures, not a measured OCR-accuracy benchmark across real receipts.
