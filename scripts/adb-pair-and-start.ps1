param(
    [string]$Serial,
    [string]$PairingCode,
    [string]$Host = "127.0.0.1",
    [Nullable[int]]$PairingPort = $null,
    [Nullable[int]]$ConnectPort = $null,
    [int]$DiscoveryTimeoutMs = 15000,
    [int]$StartTimeoutMs = 10000,
    [switch]$PairOnly,
    [switch]$StartOnly,
    [switch]$NoWaitForServer
)

$ErrorActionPreference = "Stop"

$ProviderUri = "content://moe.shizuku.privileged.api.shizuku"

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $adbArgs = @()
    if ($Serial) {
        $adbArgs += @("-s", $Serial)
    }
    $adbArgs += $Arguments

    $output = & adb @adbArgs 2>&1
    $exitCode = $LASTEXITCODE

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = ($output -join [Environment]::NewLine).Trim()
        Command = "adb " + ($adbArgs -join " ")
    }
}

function Invoke-ProviderCall {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Method,
        [string]$ArgValue,
        [hashtable]$Extras
    )

    $shellArgs = @(
        "shell", "content", "call",
        "--user", "0",
        "--uri", $ProviderUri,
        "--method", $Method
    )

    if ($null -ne $ArgValue -and $ArgValue -ne "") {
        $shellArgs += @("--arg", $ArgValue)
    }

    foreach ($key in $Extras.Keys) {
        $value = $Extras[$key]
        if ($null -eq $value -or $value -eq "") {
            continue
        }

        $type = switch ($value.GetType().Name) {
            "Boolean" { "b" }
            "Int32" { "i" }
            "Int64" { "l" }
            default { "s" }
        }

        $normalized = if ($value -is [bool]) {
            if ($value) { "true" } else { "false" }
        } else {
            [string]$value
        }

        $shellArgs += @("--extra", "${key}:${type}:${normalized}")
    }

    return Invoke-Adb -Arguments $shellArgs
}

function Test-BundleOk {
    param([string]$Text)

    return $Text -match "(^|[,{ ])ok=true([,}\]]|$)"
}

function Show-Result {
    param(
        [string]$Title,
        [pscustomobject]$Result
    )

    Write-Host ""
    Write-Host "[$Title]" -ForegroundColor Cyan
    Write-Host $Result.Command -ForegroundColor DarkGray

    if ($Result.Output) {
        Write-Host $Result.Output
    }
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb not found in PATH."
}

$extras = @{
    host = $Host
    discovery_timeout_ms = $DiscoveryTimeoutMs
    start_timeout_ms = $StartTimeoutMs
    wait_for_server = (-not $NoWaitForServer.IsPresent)
}

if ($PairingPort.HasValue) {
    $extras["pairing_port"] = $PairingPort.Value
}

if ($ConnectPort.HasValue) {
    $extras["connect_port"] = $ConnectPort.Value
}

if (-not $StartOnly.IsPresent -and [string]::IsNullOrWhiteSpace($PairingCode)) {
    $PairingCode = Read-Host "Enter the 6-digit pairing code shown in Wireless debugging"
}

if ($PairOnly.IsPresent -and $StartOnly.IsPresent) {
    throw "PairOnly and StartOnly cannot be used together."
}

$method = if ($PairOnly.IsPresent) {
    "adbPair"
} elseif ($StartOnly.IsPresent) {
    "adbStart"
} else {
    "adbPairAndStart"
}

$argValue = if ($method -eq "adbStart") { $null } else { $PairingCode }

Write-Host "Method: $method" -ForegroundColor Green
Write-Host "Provider URI: $ProviderUri" -ForegroundColor DarkGray

$result = Invoke-ProviderCall -Method $method -ArgValue $argValue -Extras $extras
Show-Result -Title "Provider Result" -Result $result

if ($result.ExitCode -ne 0) {
    throw "adb content call failed with exit code $($result.ExitCode)."
}

if (-not (Test-BundleOk -Text $result.Output)) {
    throw "Provider returned a non-success result."
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green
