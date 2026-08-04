# Database Storage Reduction Plan

## Current Situation

| Table | Size | Rows |
|---|---|---|
| `documentimage` | ~100 GB | ~16 million |
| `documentocrresult` | ~502 GB | unknown |

### Large columns in `documentimage`

| Column | Size | Status |
|---|---|---|
| `tesseract4besthocrtext` | 46 GB | 3,041,585 rows NOT yet in `documentocrresult` |
| `layoutxml` | 491 MB | needs migration |
| `htrtext` | 461 MB | needs migration |
| `tesseractaltotext` | empty | just drop it |

---

## Recommendations

### Step 1 — Migrate `tesseract4besthocrtext` → `documentocrresult`

This is the biggest win (46 GB). Run in batches to avoid long locks.

- `Transcriber.Tesseract4` = **1**
- `TranscriptionFormat.Hocr` = **3**

```sql
-- Run repeatedly, incrementing id range until done
INSERT INTO documentocrresult (analyzed, documentimageid, result, version, uuid, transcriber, format)
SELECT tesseract4besthocranalyzed, id, tesseract4besthocrtext,
       tesseract4besthocrversion, uuid_generate_v4(), 1, 3
FROM documentimage
WHERE tesseract4besthocrtext IS NOT NULL
  AND id NOT IN (SELECT documentimageid FROM documentocrresult WHERE transcriber = 1)
  AND id BETWEEN 0 AND 500000; -- increment each batch
```

Verify migration per batch:
```sql
SELECT COUNT(*) FROM documentimage
WHERE tesseract4besthocrtext IS NOT NULL
  AND id BETWEEN 0 AND 500000;

SELECT COUNT(*) FROM documentocrresult
WHERE transcriber = 1
  AND documentimageid BETWEEN 0 AND 500000;
```

### Step 2 — Migrate `layoutxml` → `documentocrresult`

- `Transcriber.LayoutPageXML` = **6**
- `TranscriptionFormat.Page` = **2**

```sql
INSERT INTO documentocrresult (analyzed, documentimageid, result, version, uuid, transcriber, format)
SELECT layoutxmlanalyzed, id, layoutxml, layoutxmlversion,
       uuid_generate_v4(), 6, 2
FROM documentimage
WHERE layoutxml IS NOT NULL
  AND id NOT IN (SELECT documentimageid FROM documentocrresult WHERE transcriber = 6)
  AND id BETWEEN 0 AND 500000; -- increment each batch
```

### Step 3 — Migrate `htrtext` → `documentocrresult`

- `Transcriber.Loghi` = **10**
- `TranscriptionFormat.Page` = **2**

```sql
INSERT INTO documentocrresult (analyzed, documentimageid, result, version, uuid, transcriber, format)
SELECT now(), id, htrtext, null, uuid_generate_v4(), 10, 2
FROM documentimage
WHERE htrtext IS NOT NULL
  AND id NOT IN (SELECT documentimageid FROM documentocrresult WHERE transcriber = 10)
  AND id BETWEEN 0 AND 500000; -- increment each batch
```

### Step 4 — Null out and drop columns

After verifying all migrations, null out the columns in batches first (to free TOAST space before dropping):

```sql
-- Batched null-out
UPDATE documentimage
SET tesseract4besthocrtext = NULL,
    layoutxml = NULL,
    htrtext = NULL,
    tesseractaltotext = NULL
WHERE id BETWEEN 0 AND 500000; -- increment each batch

-- Then drop the columns
ALTER TABLE documentimage DROP COLUMN tesseract4besthocrtext;
ALTER TABLE documentimage DROP COLUMN tesseract4besthocrversion;
ALTER TABLE documentimage DROP COLUMN tesseract4besthocranalyzed;
ALTER TABLE documentimage DROP COLUMN tesseract4besthocrconfidence;
ALTER TABLE documentimage DROP COLUMN tesseract4bestwords;
ALTER TABLE documentimage DROP COLUMN layoutxml;
ALTER TABLE documentimage DROP COLUMN layoutxmlversion;
ALTER TABLE documentimage DROP COLUMN layoutxmlanalyzed;
ALTER TABLE documentimage DROP COLUMN htrtext;
ALTER TABLE documentimage DROP COLUMN tesseractaltotext;
```

### Step 5 — Remove fields from DocumentImage.java

Remove the following fields and their getters/setters from
`layoutds/src/main/java/nl/knaw/huc/di/images/layoutds/models/DocumentImage.java`:

- `layoutXML`, `layoutXMLVersion`, `layoutXMLAnalyzed`
- `tesseract4BestHOCRText`, `tesseract4BestHOCRVersion`, `tesseract4BestHOCRAnalyzed`,
  `tesseract4BestHOCRConfidence`, `tesseract4BestWords`
- `tesseractAltoText`
- `HTRText`

Fix any compilation errors in `DocumentImageDAO.java` and any service layers that reference
these fields. Note: `DocumentImageDAO.java` filters on `tesseract4BestHOCRAnalyzed` in
`getNullCountForColumn()` — rewrite those queries to join against `documentocrresult` instead.

### Step 6 — Deduplicate `documentocrresult`

Before or after migration, delete superseded OCR results (keep only the most recent per
`documentimageid` + `transcriber`):

```sql
DELETE FROM documentocrresult
WHERE id NOT IN (
  SELECT DISTINCT ON (documentimageid, transcriber) id
  FROM documentocrresult
  ORDER BY documentimageid, transcriber, analyzed DESC
);
```

### Step 7 — VACUUM FULL to reclaim disk space

This is the step that physically returns space to the OS. Requires a maintenance window as it
locks the tables. Use `pg_repack` for a zero-downtime alternative.

```sql
VACUUM FULL ANALYZE documentimage;
VACUUM FULL ANALYZE documentocrresult;
```

Zero-downtime alternative:
```bash
pg_repack -d documentdb -t documentimage
pg_repack -d documentdb -t documentocrresult
```

---

## Optional Further Steps

### Enable TOAST compression

Ensure large `text` columns in `documentocrresult` use `EXTENDED` storage (default, but worth
verifying):

```sql
ALTER TABLE documentocrresult ALTER COLUMN result SET STORAGE EXTENDED;
```

### Partition `documentocrresult` by date

If the table keeps growing, consider range-partitioning by `analyzed` date so old partitions
can be dropped cheaply without `DELETE` + `VACUUM`:

```sql
-- Requires converting the table to a partitioned table — plan carefully
CREATE TABLE documentocrresult_2023 PARTITION OF documentocrresult
  FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
```

### Move large blobs to external storage

Store PageXML/ALTO files on disk or object storage (S3/MinIO) and save only the file path
in `documentocrresult.remoteURL` (already a field on `DocumentOCRResult`), nulling out the
`result` column. Best for new data going forward.

---

## Summary of Expected Gains

| Action | Expected saving |
|---|---|
| Migrate + drop `tesseract4besthocrtext` | ~46 GB from `documentimage` |
| Migrate + drop `layoutxml` | ~491 MB |
| Migrate + drop `htrtext` | ~461 MB |
| Drop `tesseractaltotext` | ~0 (already empty) |
| Deduplicate `documentocrresult` | potentially large, depends on duplicate count |
| VACUUM FULL `documentimage` | reclaims dead tuple bloat |
| VACUUM FULL `documentocrresult` | reclaims dead tuple bloat |

> **Note:** `documentocrresult` will temporarily grow by ~46 GB during migration of the
> 3,041,585 unmigrated rows. Run deduplication (Step 6) to offset this growth.

