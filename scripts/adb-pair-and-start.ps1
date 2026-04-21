param(
    [string]$Serial,
    [string]$PairingCode,
    [string]$Host = "127.0.0.1",
    [string]$DeviceIp,
    [Nullable[int]]$PairingPort = $null,
    [Nullable[int]]$ConnectPort = $null,
    [int]$DiscoveryTimeoutMs = 15000,
    [int]$StartTimeoutMs = 10000,
    [switch]$EnableTcpip5555,
    [bool]$AutoConnectPcAdb = $true,
    [switch]$PairOnly,
    [switch]$StartOnly,
    [switch]$PairNotifyStart,
    [switch]$PairNotifyStop,
    [switch]$NoWaitForServer
)

$ErrorActionPreference = "Stop"

$ProviderUri = "content://moe.shizuku.privileged.api.shizuku"

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$IgnoreSerial
    )

    $adbArgs = @()
    if (-not $IgnoreSerial.IsPresent -and $Serial) {
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

    return $Text -match "ok=true"
}

function Get-BundleValue {
    param(
        [string]$Text,
        [string]$Key
    )

    if ($Text -match "$Key=([^,}\]]+)") {
        return $Matches[1].Trim()
    }
    return $null
}

function Resolve-DeviceIp {
    param([Nullable[int]]$ResolvedConnectPort = $null)

    if (-not [string]::IsNullOrWhiteSpace($DeviceIp)) {
        return $DeviceIp
    }

    if (-not [string]::IsNullOrWhiteSpace($Serial) -and $Serial -match "^((?:\d{1,3}\.){3}\d{1,3})(:\d+)?$") {
        return $Matches[1]
    }

    $mdns = Invoke-Adb -Arguments @("mdns", "services") -IgnoreSerial
    if ($mdns.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($mdns.Output)) {
        return $null
    }

    $entries = @()
    foreach ($line in ($mdns.Output -split "`r?`n")) {
        if ($line -match "_adb-tls-connect\._tcp\s+((?:\d{1,3}\.){3}\d{1,3}):(\d+)") {
            $entries += [pscustomobject]@{
                Ip = $Matches[1]
                Port = [int]$Matches[2]
            }
        }
    }

    if ($entries.Count -eq 0) {
        return $null
    }

    if ($ResolvedConnectPort.HasValue) {
        $matchByPort = $entries | Where-Object { $_.Port -eq $ResolvedConnectPort.Value } | Select-Object -First 1
        if ($matchByPort) {
            return $matchByPort.Ip
        }
    }

    $ips = @($entries | ForEach-Object { $_.Ip } | Sort-Object -Unique)
    if ($ips.Count -eq 1) {
        return $ips[0]
    }

    return $null
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
    enable_tcpip_5555 = $EnableTcpip5555.IsPresent
}

if ($PairingPort.HasValue) {
    $extras["pairing_port"] = $PairingPort.Value
}

if ($ConnectPort.HasValue) {
    $extras["connect_port"] = $ConnectPort.Value
}

if ($PairOnly.IsPresent -and $StartOnly.IsPresent) {
    throw "PairOnly and StartOnly cannot be used together."
}

if ($PairNotifyStart.IsPresent -and $PairNotifyStop.IsPresent) {
    throw "PairNotifyStart and PairNotifyStop cannot be used together."
}

$modeSwitchCount = @($PairOnly.IsPresent, $StartOnly.IsPresent, $PairNotifyStart.IsPresent, $PairNotifyStop.IsPresent) | Where-Object { $_ } | Measure-Object | Select-Object -ExpandProperty Count
if ($modeSwitchCount -gt 1) {
    throw "PairOnly, StartOnly, PairNotifyStart and PairNotifyStop are mutually exclusive."
}

$method = if ($PairNotifyStart.IsPresent) {
    "adbPairingNotifyStart"
} elseif ($PairNotifyStop.IsPresent) {
    "adbPairingNotifyStop"
} elseif ($PairOnly.IsPresent) {
    "adbPair"
} elseif ($StartOnly.IsPresent) {
    "adbStart"
} else {
    "adbPairAndStart"
}

if (($method -eq "adbPair" -or $method -eq "adbPairAndStart") -and [string]::IsNullOrWhiteSpace($PairingCode)) {
    $PairingCode = Read-Host "Enter the 6-digit pairing code shown in Wireless debugging"
}

$argValue = if ($method -eq "adbPair" -or $method -eq "adbPairAndStart") { $PairingCode } else { $null }

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

if ($AutoConnectPcAdb -and ($method -eq "adbStart" -or $method -eq "adbPairAndStart")) {
    $resolvedConnectPort = $null
    $connectPortText = Get-BundleValue -Text $result.Output -Key "connect_port"
    if (-not [string]::IsNullOrWhiteSpace($connectPortText)) {
        $port = 0
        if ([int]::TryParse($connectPortText, [ref]$port)) {
            $resolvedConnectPort = $port
        }
    } elseif ($ConnectPort.HasValue) {
        $resolvedConnectPort = $ConnectPort.Value
    }

    if ($null -eq $resolvedConnectPort) {
        throw "Cannot determine connect_port from provider result. Use -ConnectPort to set it explicitly."
    }

    $resolvedDeviceIp = Resolve-DeviceIp -ResolvedConnectPort $resolvedConnectPort
    if ([string]::IsNullOrWhiteSpace($resolvedDeviceIp)) {
        throw "Cannot determine device IP for PC adb connect. Use -DeviceIp (for example: -DeviceIp 192.168.3.17)."
    }

    $pcConnectResult = Invoke-Adb -Arguments @("connect", "$resolvedDeviceIp`:$resolvedConnectPort") -IgnoreSerial
    Show-Result -Title "PC ADB Connect" -Result $pcConnectResult

    if ($pcConnectResult.ExitCode -ne 0) {
        throw "adb connect failed with exit code $($pcConnectResult.ExitCode)."
    }
}

Write-Host ""
Write-Host "Done." -ForegroundColor Green
