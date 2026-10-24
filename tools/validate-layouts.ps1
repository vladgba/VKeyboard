param([string]$LayoutDir = 'app/src/main/assets')
Push-Location ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')))
try {
    & ./gradlew.bat :app:validateLayouts "-PlayoutDir=$LayoutDir" --console=plain
    if ($LASTEXITCODE) { throw 'Layout validation failed' }
} finally { Pop-Location }
