# CSV transaction import

Authenticated ACTIVE users can open **Transactions → Importer un relevé CSV**. Import is independent of bank connectivity and uses the existing canonical transaction model.

## First supported format

UTF-8 CSV (optional BOM), unique headers, comma/semicolon/tab separators, ISO `YYYY-MM-DD` or French `DD/MM/YYYY` dates, dot/comma decimals, at most four fractional digits, 2 MiB and 2,000 operations. Thousands separators, Excel files and future posted dates are rejected. Headers and quoted multiline fields are parsed by Apache Commons CSV 1.14.1.

Date, description and amount must map to distinct columns. Direction and currency are optional. Without direction, negative amounts mean DEBIT and positive amounts mean CREDIT. Explicit direction accepts DEBIT/CREDIT, rejecting negative CREDIT. Stored magnitudes are positive, scale 4; the currency must exactly match the account. Nothing is rounded or converted. Mapping proposals use bounded header rules, without AI.

## API

- `POST /api/imports/accounts`: JSON `{ "name": "Mes relevés", "currency": "TND", "currentBalance": "4200.0000" }`. Creates an owned manual account and an explicitly MANUAL balance snapshot. Repeating the same name (case-insensitive) and currency reuses that account; its balance is never silently replaced. The balance is user-declared for today and is not derived from an incomplete statement.
- `POST /api/imports/csv/preview`: multipart `file` plus JSON part `request`.
- `POST /api/imports/csv/{jobId}/confirm`: the same multipart file and request. All rows must pass validation. Confirmation expires after 30 minutes unless already completed.

Example JSON part:

```json
{
  "accountId": "owned-account-uuid",
  "delimiter": "SEMICOLON",
  "dateFormat": "DAY_MONTH_YEAR",
  "decimalSeparator": "COMMA",
  "mapping": {
    "dateColumn": "Date",
    "descriptionColumn": "Libellé",
    "amountColumn": "Montant",
    "directionColumn": null,
    "currencyColumn": null
  }
}
```

The first preview may omit `mapping`. It returns columns, proposed mapping, resolved formats, counts, up to ten normalized rows, up to twenty safe row errors and `canImport`. Changing the mapping requires another preview. Confirmation returns `jobId`, `accountId`, `status`, `rowCount`, `importedCount`, `duplicateCount` and `alreadyImported`.

## Persistence and safety

No migration is added: V6's `source_file`, `import_job`, `import_mapping`, `column_mapping` and safe rejected-row records are reused. No raw file is retained on disk or in the database; `storage_reference=not-retained:sha256:…` and file metadata explicitly describe that policy. The browser resends the original bytes, whose SHA-256 must match the preview.

Transactions use `FILE_IMPORT`, an owned account, the original file and logical record number (header is record 1), with no bank status or bank identifiers. An owner-row lock, atomic confirmation and the existing unique file-row constraint prevent concurrent or repeated confirmation from duplicating data. Matching content in distinct rows remains distinct. A completed mapping cannot be silently replaced. Audit events contain no file contents or financial values.

Bank balances and manual balances are unchanged by importing transactions. Imported operations are available to the existing transaction, category, budget and score queries. They begin uncategorized and use normal correction/review workflows afterward.

**Limitation:** byte-identical files are replay-safe for a given account. Different overlapping files, or files duplicating bank-synchronized transactions, are not automatically deduplicated; the UI requires explicit review and warns about overlap. Account-balance editing, Excel support, additional encodings, separate debit/credit amount columns and secure retained file storage are future increments.
