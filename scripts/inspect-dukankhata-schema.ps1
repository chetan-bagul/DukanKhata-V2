[CmdletBinding()]
param(
    [string]$OutputRoot = (Join-Path $env:LOCALAPPDATA 'DukanKhata\SchemaInspection')
)

$ErrorActionPreference = 'Stop'

$pgDump = 'C:\Program Files\PostgreSQL\18\bin\pg_dump.exe'
$psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'

if ([string]::IsNullOrWhiteSpace($env:READ_ONLY_DATABASE_URL)) {
    throw 'READ_ONLY_DATABASE_URL is required. No connection was attempted.'
}
if ([string]::IsNullOrWhiteSpace($env:PGPASSFILE)) {
    throw 'PGPASSFILE is required. No connection was attempted.'
}
if (-not (Test-Path -LiteralPath $pgDump -PathType Leaf)) {
    throw 'PostgreSQL 18 pg_dump.exe was not found at the expected path.'
}
if (-not (Test-Path -LiteralPath $psql -PathType Leaf)) {
    throw 'PostgreSQL 18 psql.exe was not found at the expected path.'
}
if (-not (Test-Path -LiteralPath $env:PGPASSFILE -PathType Leaf)) {
    throw 'The file specified by PGPASSFILE does not exist. No connection was attempted.'
}
if (-not [string]::IsNullOrWhiteSpace($env:PGPASSWORD)) {
    throw 'Unset PGPASSWORD; this script requires password-file authentication through PGPASSFILE.'
}

$databaseUri = $null
try {
    $databaseUri = [Uri]$env:READ_ONLY_DATABASE_URL
} catch {
    throw 'READ_ONLY_DATABASE_URL must be a valid PostgreSQL URI. Its value was not displayed.'
}
if ($databaseUri.Scheme -notin @('postgres', 'postgresql') -or
    [string]::IsNullOrWhiteSpace($databaseUri.Host) -or
    [string]::IsNullOrWhiteSpace($databaseUri.AbsolutePath.Trim('/'))) {
    throw 'READ_ONLY_DATABASE_URL must include a PostgreSQL host and database name. Its value was not displayed.'
}
if ($databaseUri.UserInfo.Contains(':')) {
    throw 'Do not include a password in READ_ONLY_DATABASE_URL. Store credentials only in PGPASSFILE.'
}
$databaseUser = [Uri]::UnescapeDataString($databaseUri.UserInfo)
if ([string]::IsNullOrWhiteSpace($databaseUser)) {
    throw 'READ_ONLY_DATABASE_URL must identify the authorized read-only database user.'
}

$databaseName = [Uri]::UnescapeDataString($databaseUri.AbsolutePath.Trim('/'))
$sslMode = 'require'
if (-not [string]::IsNullOrWhiteSpace($databaseUri.Query)) {
    foreach ($queryPair in $databaseUri.Query.TrimStart('?').Split('&')) {
        $parts = $queryPair.Split('=', 2)
        if ([Uri]::UnescapeDataString($parts[0]) -eq 'sslmode' -and $parts.Length -eq 2) {
            $sslMode = [Uri]::UnescapeDataString($parts[1])
        }
    }
}
if ($sslMode -notin @('disable', 'allow', 'prefer', 'require', 'verify-ca', 'verify-full')) {
    throw 'READ_ONLY_DATABASE_URL contains an unsupported sslmode value.'
}

$outputDirectory = Join-Path $OutputRoot ("dukankhata-schema-inspection-{0}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

$environmentNames = @('PGHOST', 'PGPORT', 'PGDATABASE', 'PGUSER', 'PGPASSFILE', 'PGPASSWORD', 'PGSSLMODE', 'PGOPTIONS', 'PGAPPNAME')
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $env:PGHOST = $databaseUri.Host
    $env:PGPORT = if ($databaseUri.IsDefaultPort) { '5432' } else { [string]$databaseUri.Port }
    $env:PGDATABASE = $databaseName
    $env:PGUSER = $databaseUser
    $env:PGPASSFILE = (Resolve-Path -LiteralPath $env:PGPASSFILE).Path
    $env:PGPASSWORD = $null
    $env:PGSSLMODE = $sslMode
    $env:PGOPTIONS = '-c default_transaction_read_only=on'
    $env:PGAPPNAME = 'DukanKhataSchemaInspection'

    $readOnlyCheckSql = @'
BEGIN READ ONLY;
SELECT CASE WHEN
    current_setting('transaction_read_only') = 'on'
    AND NOT r.rolsuper
    AND NOT r.rolcreatedb
    AND NOT r.rolcreaterole
    AND NOT r.rolreplication
    AND NOT has_database_privilege(current_user, current_database(), 'CREATE')
    AND NOT has_schema_privilege(current_user, 'public', 'CREATE')
    AND NOT EXISTS (
        SELECT 1
        FROM (VALUES
            ('public.customer_ledger_entries'),
            ('public.daily_closings'),
            ('public.flyway_schema_history')
        ) AS targets(relation_name)
        WHERE to_regclass(targets.relation_name) IS NOT NULL
          AND (
              has_table_privilege(current_user, to_regclass(targets.relation_name), 'INSERT')
              OR has_table_privilege(current_user, to_regclass(targets.relation_name), 'UPDATE')
              OR has_table_privilege(current_user, to_regclass(targets.relation_name), 'DELETE')
              OR has_table_privilege(current_user, to_regclass(targets.relation_name), 'TRUNCATE')
              OR has_table_privilege(current_user, to_regclass(targets.relation_name), 'REFERENCES')
              OR has_table_privilege(current_user, to_regclass(targets.relation_name), 'TRIGGER')
              OR has_any_column_privilege(current_user, to_regclass(targets.relation_name), 'INSERT')
              OR has_any_column_privilege(current_user, to_regclass(targets.relation_name), 'UPDATE')
              OR has_any_column_privilege(current_user, to_regclass(targets.relation_name), 'REFERENCES')
          )
    )
    THEN 'READ_ONLY_VERIFIED'
    ELSE 'READ_ONLY_CHECK_FAILED'
END
FROM pg_roles r
WHERE r.rolname = current_user;
COMMIT;
'@
    $readOnlyResult = & $psql -X -w -v ON_ERROR_STOP=1 -A -t -q -c $readOnlyCheckSql 2>$null
    $psqlExitCode = $LASTEXITCODE
    if ($psqlExitCode -ne 0 -or ($readOnlyResult -join '').Trim() -ne 'READ_ONLY_VERIFIED') {
        throw 'Read-only connection/privilege verification failed. No schema export was performed.'
    }

    $tableStatusFile = Join-Path $outputDirectory 'table-status.csv'
    $tableStatusSql = @'
BEGIN READ ONLY;
SELECT table_name,
       CASE WHEN to_regclass(format('public.%I', table_name)) IS NULL THEN 'MISSING' ELSE 'PRESENT' END AS status
FROM (VALUES ('customer_ledger_entries'), ('daily_closings')) AS required_tables(table_name)
ORDER BY table_name;
COMMIT;
'@
    & $psql -X -w -v ON_ERROR_STOP=1 -q --csv --output=$tableStatusFile -c $tableStatusSql 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'The read-only table-status query failed. Diagnostic output was suppressed to protect connection details.'
    }

    $columnsFile = Join-Path $outputDirectory 'column-definitions.csv'
    $columnsSql = @'
BEGIN READ ONLY;
SELECT table_catalog, table_schema, table_name, column_name, ordinal_position,
       data_type, udt_name, character_maximum_length,
       numeric_precision, numeric_scale, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('customer_ledger_entries', 'daily_closings')
ORDER BY table_name, ordinal_position;
COMMIT;
'@
    & $psql -X -w -v ON_ERROR_STOP=1 -q --csv --output=$columnsFile -c $columnsSql 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'The read-only column-definition query failed. Diagnostic output was suppressed to protect connection details.'
    }

    $flywayStatusFile = Join-Path $outputDirectory 'flyway-history-status.txt'
    $historyExistsSql = @'
BEGIN READ ONLY;
SELECT CASE WHEN to_regclass('public.flyway_schema_history') IS NULL THEN 'ABSENT' ELSE 'PRESENT' END;
COMMIT;
'@
    $historyStatus = & $psql -X -w -v ON_ERROR_STOP=1 -A -t -q -c $historyExistsSql 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'The read-only Flyway-history existence check failed. Diagnostic output was suppressed to protect connection details.'
    }
    $historyStatus = ($historyStatus -join '').Trim()
    Set-Content -LiteralPath $flywayStatusFile -Value "public.flyway_schema_history: $historyStatus" -Encoding UTF8

    if ($historyStatus -eq 'PRESENT') {
        $historyFile = Join-Path $outputDirectory 'flyway-schema-history.csv'
        $historySql = @'
BEGIN READ ONLY;
SELECT installed_rank, version, description, script, checksum, success
FROM public.flyway_schema_history
ORDER BY installed_rank;
COMMIT;
'@
        & $psql -X -w -v ON_ERROR_STOP=1 -q --csv --output=$historyFile -c $historySql 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw 'The read-only Flyway-history export failed. Diagnostic output was suppressed to protect connection details.'
        }
    } elseif ($historyStatus -eq 'ABSENT') {
        Set-Content -LiteralPath (Join-Path $outputDirectory 'flyway-schema-history-absent.txt') `
            -Value 'public.flyway_schema_history does not exist; no table was created.' -Encoding UTF8
    } else {
        throw 'The Flyway-history existence query returned an unexpected result.'
    }

    $tableStatus = Import-Csv -LiteralPath $tableStatusFile
    $existingTables = @($tableStatus | Where-Object { $_.status -eq 'PRESENT' } | ForEach-Object { "public.$($_.table_name)" })
    $schemaFile = Join-Path $outputDirectory 'relevant-schema-only.sql'
    if ($existingTables.Count -gt 0) {
        $dumpArguments = @('--no-password', '--schema-only', '--no-owner', '--no-privileges', '--quote-all-identifiers', "--file=$schemaFile")
        foreach ($table in $existingTables) {
            $dumpArguments += "--table=$table"
        }
        & $pgDump @dumpArguments 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw 'The schema-only pg_dump failed. Diagnostic output was suppressed to protect connection details.'
        }
    } else {
        Set-Content -LiteralPath $schemaFile -Value '-- Neither requested public table exists; see table-status.csv.' -Encoding UTF8
    }

    Write-Output 'Read-only schema inspection completed. No row data was exported.'
    Write-Output "Output directory: $outputDirectory"
    Write-Output "Schema-only snapshot: $schemaFile"
    Write-Output "Table status: $tableStatusFile"
    Write-Output "Column definitions: $columnsFile"
    Write-Output "Flyway history status: $flywayStatusFile"
    if ($historyStatus -eq 'PRESENT') {
        Write-Output "Flyway history: $(Join-Path $outputDirectory 'flyway-schema-history.csv')"
    } else {
        Write-Output "Flyway absence record: $(Join-Path $outputDirectory 'flyway-schema-history-absent.txt')"
    }
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
