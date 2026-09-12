#!/usr/bin/env pwsh
# Scenario tests for the one-click startup libraries.
# Zero external dependencies: stub CLIs are injected via a temp PATH and every
# scenario runs in a fresh pwsh child so host state can never leak in.
# Run: pwsh -NoProfile -File e2e/startup-e2e.ps1
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$EnvSetupLib = Join-Path $ProjectRoot "scripts/lib/env-setup.ps1"
$PreflightLib = Join-Path $ProjectRoot "scripts/lib/preflight.ps1"
$RuntimeLib = Join-Path $ProjectRoot "scripts/lib/container-runtime.ps1"

Get-ChildItem ([System.IO.Path]::GetTempPath()) -Directory -Filter "act-startup-*" -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

$StubDir = Join-Path ([System.IO.Path]::GetTempPath()) ("act-startup-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $StubDir | Out-Null

$failures = 0
# Untyped $Cond on purpose: ($out -match "...") yields an Object[] for multi-line
# output, and PowerShell 7.6 refuses to bind an array to a [bool] parameter. The
# explicit [bool] cast below applies the usual single-item unwrap rules.
function Assert-True($Name, $Cond, $Detail) {
    if ([bool]$Cond) {
        Write-Host "  PASS: $Name" -ForegroundColor Green
    } else {
        $script:failures++
        Write-Host "  FAIL: $Name ($Detail)" -ForegroundColor Red
    }
}

# Runs a snippet in a fresh pwsh process and returns its combined output.
# Never throws: a broken scenario comes back as text so it fails its own assertion
# instead of aborting the whole run. -PathPrepend / -PathReplace make the child's
# PATH hermetic, which is required by the tool-presence scenarios in tasks 3, 6, 7, 8.
function Invoke-Snippet {
    param(
        [Parameter(Mandatory)][string]$Snippet,
        # Files written into a fresh temp dir that is prepended to the child PATH.
        [hashtable]$Stubs = @{},
        # Extra dir prepended after the stub dir; also kept when -PathReplace is set.
        [string]$PathPrepend,
        # Replaces the child PATH; the stub dir and -PathPrepend stay in front of it.
        [string]$PathReplace
    )
    try {
        $dir = Join-Path $StubDir ([guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Path $dir | Out-Null
        foreach ($name in $Stubs.Keys) {
            Set-Content -Path (Join-Path $dir $name) -Value $Stubs[$name]
        }
        $entries = @()
        if ($Stubs.Count -gt 0) { $entries += $dir }
        if ($PathPrepend) { $entries += $PathPrepend }
        $prelude = ''
        if ($PathReplace) {
            $prelude = "`$env:PATH = '" + ((@($entries) + @($PathReplace)) -join ';') + "'`n"
        } elseif ($entries.Count -gt 0) {
            $prelude = "`$env:PATH = '" + ($entries -join ';') + ";' + `$env:PATH`n"
        }
        $file = Join-Path $dir "scenario.ps1"
        Set-Content -Path $file -Value ($prelude + $Snippet)
        return (pwsh -NoProfile -File $file 2>&1 | Out-String)
    } catch {
        return "SNIPPET-ERROR: $($_.Exception.Message)"
    }
}

# Writes a fake podman CLI whose behaviour is driven by FAKE_PODMAN_* variables set
# inside the scenario, and which appends every invocation to FAKE_PODMAN_LOG.
# Recognised variables: FAKE_PODMAN_SOCKET (printed for `info`), FAKE_PODMAN_MACHINE
# ('running' or anything else), FAKE_PODMAN_SERVER ('running'), FAKE_PODMAN_IMAGE
# ('present'), FAKE_PODMAN_LOG (file path). `compose` and `build` always succeed.
function New-FakePodman([string]$Dir) {
    New-Item -ItemType Directory -Path $Dir -Force | Out-Null
    $stub = @'
# No param() block on purpose: a [Parameter()] attribute would make this an advanced
# script, and PowerShell would then bind dash options to common parameters - silently
# swallowing "-d" (bound as -Debug) and erroring on ambiguous ones like "-p". $args
# keeps every token literal.
$joined = ($args -join ' ')
if ($env:FAKE_PODMAN_LOG) { Add-Content -Path $env:FAKE_PODMAN_LOG -Value $joined }
switch -Regex ($joined) {
    '^info'          { Write-Output $env:FAKE_PODMAN_SOCKET; exit 0 }
    '^machine list'  { if ($env:FAKE_PODMAN_MACHINE -eq 'running') { Write-Output 'podman-machine-default* wsl 1 day ago Currently running' } else { Write-Output 'podman-machine-default* wsl 1 day ago Never' }; exit 0 }
    '^machine start' { if ($env:FAKE_PODMAN_LOG) { Add-Content -Path $env:FAKE_PODMAN_LOG -Value 'MACHINE-START' }; exit 0 }
    '^ps'            { if ($env:FAKE_PODMAN_SERVER -eq 'running') { Write-Output 'aria-opensandbox' }; exit 0 }
    '^image exists'  { if ($env:FAKE_PODMAN_IMAGE -eq 'present') { exit 0 } else { exit 1 } }
    default          { exit 0 }
}
'@
    Set-Content -Path (Join-Path $Dir 'podman.ps1') -Value $stub
}

$FakePodmanDir = Join-Path $StubDir "fakepodman"
New-FakePodman $FakePodmanDir
# A PATH containing only a java forwarder: lets the shim's `java` resolve while `mvn` stays
# invisible, which is the condition the launcher hits on a machine without Maven.
$JavaOnlyDir = Join-Path $StubDir "javaonly"
New-Item -ItemType Directory -Path $JavaOnlyDir -Force | Out-Null
$javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
if ($javaExe) {
    Set-Content -Path (Join-Path $JavaOnlyDir "java.cmd") `
        -Value "@echo off`r`n`"$javaExe`" %*`r`n" -NoNewline
}
$EmptyPathDir = Join-Path $StubDir "emptypath"
New-Item -ItemType Directory -Path $EmptyPathDir -Force | Out-Null

# Starts a throwaway HTTP/1.1 server in a background job that answers every request
# with 200 + $ContentType + $Body, and returns @{ Port; Job; PortFile } once the job has
# published the port it bound. Four details are load-bearing:
#   * the job binds port 0 itself and publishes the port through a file - a port
#     reserved in the parent cannot be bound by the job ("Only one usage of each
#     socket address..."), and releasing it first would race with strangers;
#   * the request is read only up to the blank line that ends the headers, because
#     HttpClient keeps the connection open and Peek() would block past the client's
#     timeout, so the server would never answer in time;
#   * the content type is a parameter: application/json exercises the plain-text body
#     path, while the Spring actuator vendor media type reproduces the byte[] body;
#   * the accept loop polls Pending(), which keeps the job interruptible - a job
#     parked in a blocking AcceptTcpClient() makes Stop-Job hang for minutes.
function Start-FakeHttpServer {
    param(
        [Parameter(Mandatory)][string]$ContentType,
        [Parameter(Mandatory)][string]$Body
    )
    $portFile = Join-Path $StubDir ("fake-http-port-" + [guid]::NewGuid().ToString("N") + ".txt")
    $job = Start-Job -ScriptBlock {
        param($portFile, $contentType, $body)
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        Set-Content -Path $portFile -Value ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
        while ($true) {
            if (-not $listener.Pending()) { Start-Sleep -Milliseconds 20; continue }
            try {
                $client = $listener.AcceptTcpClient()
                $stream = $client.GetStream()
                $reader = New-Object System.IO.StreamReader($stream)
                $line = $reader.ReadLine()
                while ($line -ne '' -and $null -ne $line) { $line = $reader.ReadLine() }
                $resp = "HTTP/1.1 200 OK`r`nContent-Type: $contentType`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n$body"
                $bytes = [System.Text.Encoding]::ASCII.GetBytes($resp)
                $stream.Write($bytes, 0, $bytes.Length)
                $stream.Flush()
                $client.Close()
            } catch {
                # A client that vanishes mid-request must not take the server down.
            }
        }
    } -ArgumentList $portFile, $ContentType, $Body
    $port = $null
    for ($i = 0; $i -lt 100 -and -not $port; $i++) {
        Start-Sleep -Milliseconds 100
        if (Test-Path $portFile) {
            # The file can be observed between creation and flush, so a blank read is retried
            # instead of turning into "cannot call a method on a null-valued expression".
            $raw = Get-Content $portFile -Raw
            if ("$raw".Trim() -match '^\d+$') { $port = [int]"$raw".Trim() }
        }
    }
    return [pscustomobject]@{ Port = $port; Job = $job; PortFile = $portFile }
}

# Tears down a server returned by Start-FakeHttpServer, port file included.
function Stop-FakeHttpServer($Server) {
    Stop-Job $Server.Job -ErrorAction SilentlyContinue
    Remove-Job $Server.Job -Force -ErrorAction SilentlyContinue
    Remove-Item $Server.PortFile -Force -ErrorAction SilentlyContinue
}

# Starts a loopback listener that accepts a connection and never answers it, so a health
# poll burns its whole request timeout instead of being refused in milliseconds. A refused
# port cannot show whether one request outlived the caller's budget - it answers too fast.
# The returned shape matches Start-FakeHttpServer, so Stop-FakeHttpServer tears it down.
function Start-FakeHangingServer {
    $portFile = Join-Path $StubDir ("fake-hang-port-" + [guid]::NewGuid().ToString("N") + ".txt")
    $job = Start-Job -ScriptBlock {
        param($portFile)
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        Set-Content -Path $portFile -Value ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
        while ($true) {
            if (-not $listener.Pending()) { Start-Sleep -Milliseconds 20; continue }
            # Accepted on purpose, never answered: this is the hang under test. Pending()
            # keeps the job interruptible, exactly as in Start-FakeHttpServer.
            $listener.AcceptTcpClient() | Out-Null
        }
    } -ArgumentList $portFile
    $port = $null
    for ($i = 0; $i -lt 100 -and -not $port; $i++) {
        Start-Sleep -Milliseconds 100
        if (Test-Path $portFile) {
            # The file can be observed between creation and flush, so a blank read is retried
            # instead of turning into "cannot call a method on a null-valued expression".
            $raw = Get-Content $portFile -Raw
            if ("$raw".Trim() -match '^\d+$') { $port = [int]"$raw".Trim() }
        }
    }
    return [pscustomobject]@{ Port = $port; Job = $job; PortFile = $portFile }
}

# Holds a loopback port open in a *separate process* and returns @{ Port; Process; PortFile }.
# Separate, and not a job, on purpose: the assertion these scenarios make is that a port
# guard which never got consent leaves the holder alive, so the listener must be killable
# and its PID addressable from here. The holder parks for 300s, far beyond any scenario.
function Start-PortHolder {
    $holder = Join-Path $StubDir ("port-holder-" + [guid]::NewGuid().ToString("N") + ".ps1")
    $portFile = "$holder.port"
    # -File with a generated path keeps the child command line free of quoting hazards.
    Set-Content -Path $holder -Value @"
`$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
`$listener.Start()
Set-Content -Path '$portFile' -Value ([System.Net.IPEndPoint]`$listener.LocalEndpoint).Port
Start-Sleep -Seconds 300
"@
    $proc = Start-Process pwsh -ArgumentList @('-NoProfile', '-File', $holder) -NoNewWindow -PassThru
    $port = $null
    for ($i = 0; $i -lt 100 -and -not $port; $i++) {
        Start-Sleep -Milliseconds 100
        if (Test-Path $portFile) {
            $raw = Get-Content $portFile -Raw
            if ("$raw".Trim() -match '^\d+$') { $port = [int]"$raw".Trim() }
        }
    }
    return [pscustomobject]@{ Port = $port; Process = $proc; PortFile = $portFile }
}

function Stop-PortHolder($Holder) {
    Stop-Process -Id $Holder.Process.Id -Force -ErrorAction SilentlyContinue
    Remove-Item $Holder.PortFile -Force -ErrorAction SilentlyContinue
}

# Returns a loopback port that nothing is listening on, by binding one and releasing it.
# A released port can in theory be taken by a stranger before the scenario runs, but the
# scenario only needs the launcher to see those ports free, and a taken one fails loudly.
function Get-FreeLoopbackPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    return $port
}

try {
    Write-Host "Test-LlmKeyValid scenarios:" -ForegroundColor Cyan

    $out = Invoke-Snippet @"
. '$EnvSetupLib'
Write-Output ("RESULT real=" + (Test-LlmKeyValid 'sk-test-not-a-real-key-0000'))
Write-Output ("RESULT empty=" + (Test-LlmKeyValid ''))
Write-Output ("RESULT placeholder=" + (Test-LlmKeyValid 'your-api-key-here'))
Write-Output ("RESULT short=" + (Test-LlmKeyValid 'sk-1'))
Write-Output ("RESULT whitespace=" + (Test-LlmKeyValid '   '))
Write-Output ("RESULT exact8=" + (Test-LlmKeyValid 'sk-abcde'))
Write-Output ("RESULT skx=" + (Test-LlmKeyValid 'sk-xxxxxxxxx'))
"@
    Assert-True "real key is valid" ($out -match "RESULT real=True") $out
    Assert-True "empty key is invalid" ($out -match "RESULT empty=False") $out
    Assert-True "placeholder key is invalid" ($out -match "RESULT placeholder=False") $out
    Assert-True "too-short key is invalid" ($out -match "RESULT short=False") $out
    Assert-True "whitespace-only key is invalid" ($out -match "RESULT whitespace=False") $out
    Assert-True "an 8-character key is accepted" ($out -match "RESULT exact8=True") $out
    Assert-True "the sk-x placeholder is rejected" ($out -match "RESULT skx=False") $out

    Write-Host "Ensure-EnvFile scenarios:" -ForegroundColor Cyan

    $out = Invoke-Snippet @"
. '$EnvSetupLib'
`$root = Join-Path '$StubDir' 'fresh'
New-Item -ItemType Directory -Path `$root -Force | Out-Null
`$r = Ensure-EnvFile -ProjectRoot `$root -SandboxSocket '/run/user/1000/podman/podman.sock' -NonInteractive -Preset deepseek -ApiKey 'sk-test-not-a-real-key-0000'
`$text = Get-Content (Join-Path `$root '.env') -Raw
Write-Output ("RESULT created=" + `$r.Created)
Write-Output ("RESULT hasKey=" + (`$text -match 'LLM_API_KEY=sk-test-not-a-real-key-0000'))
Write-Output ("RESULT hasBase=" + (`$text -match 'LLM_BASE_URL=https://api.deepseek.com'))
Write-Output ("RESULT hasRuntime=" + (`$text -match 'CONTAINER_RUNTIME=podman'))
Write-Output ("RESULT hasSocket=" + (`$text -match 'SANDBOX_SOCKET=/run/user/1000/podman/podman.sock'))
"@
    Assert-True "creates .env with all required keys" (($out -match "RESULT created=True") -and ($out -match "RESULT hasKey=True") -and ($out -match "RESULT hasBase=True") -and ($out -match "RESULT hasRuntime=True") -and ($out -match "RESULT hasSocket=True")) $out

    $out = Invoke-Snippet @"
. '$EnvSetupLib'
`$root = Join-Path '$StubDir' 'existing'
New-Item -ItemType Directory -Path `$root -Force | Out-Null
Set-Content -Path (Join-Path `$root '.env') -Value "LLM_API_KEY=sk-existing123456`nLLM_BASE_URL=https://api.openai.com/v1`nLLM_MODEL=my-model`n"
`$r = Ensure-EnvFile -ProjectRoot `$root -SandboxSocket '/run/user/1000/podman/podman.sock' -NonInteractive
`$text = Get-Content (Join-Path `$root '.env') -Raw
Write-Output ("RESULT added=" + (`$r.AddedKeys -join ','))
Write-Output ("RESULT keptKey=" + (`$text -match 'LLM_API_KEY=sk-existing123456'))
Write-Output ("RESULT keptModel=" + (`$text -match 'LLM_MODEL=my-model'))
Write-Output ("RESULT addedRuntime=" + (`$text -match 'CONTAINER_RUNTIME=podman'))
Write-Output ("RESULT overwroteModel=" + (`$text -match 'LLM_MODEL=deepseek-v4-flash'))
"@
    Assert-True "adds only missing keys to an existing .env" (($out -match "RESULT added=CONTAINER_RUNTIME,SANDBOX_SOCKET") -and ($out -match "RESULT keptKey=True") -and ($out -match "RESULT keptModel=True") -and ($out -match "RESULT addedRuntime=True")) $out
    Assert-True "never overwrites an existing value" ($out -match "RESULT overwroteModel=False") $out

    $out = Invoke-Snippet @"
. '$EnvSetupLib'
`$root = Join-Path '$StubDir' 'blankvalue'
New-Item -ItemType Directory -Path `$root -Force | Out-Null
Set-Content -Path (Join-Path `$root '.env') -Value "LLM_API_KEY=`nLLM_BASE_URL=https://api.deepseek.com`nLLM_MODEL=m`n"
`$r = Ensure-EnvFile -ProjectRoot `$root -SandboxSocket '/run/user/1000/podman/podman.sock' -NonInteractive -ApiKey 'sk-real-key-123456'
`$text = Get-Content (Join-Path `$root '.env') -Raw
Write-Output ("RESULT added=" + (`$r.AddedKeys -join ','))
Write-Output ("RESULT keyLineCount=" + ([regex]::Matches(`$text, '(?m)^LLM_API_KEY=').Count))
"@
    Assert-True "a blank-valued key counts as present (no duplicate append)" (($out -match "RESULT added=CONTAINER_RUNTIME,SANDBOX_SOCKET") -and ($out -match "RESULT keyLineCount=1")) $out

    $out = Invoke-Snippet @"
. '$EnvSetupLib'
`$root = Join-Path '$StubDir' 'dockerrt'
New-Item -ItemType Directory -Path `$root -Force | Out-Null
Ensure-EnvFile -ProjectRoot `$root -SandboxSocket '/var/run/docker.sock' -ContainerRuntime docker -NonInteractive -ApiKey 'sk-real-key-123456' | Out-Null
`$text = Get-Content (Join-Path `$root '.env') -Raw
Write-Output ("RESULT docker=" + (`$text -match 'CONTAINER_RUNTIME=docker'))
Write-Output ("RESULT socket=" + (`$text -match 'SANDBOX_SOCKET=/var/run/docker.sock'))
"@
    Assert-True "records the runtime the launcher resolved" (($out -match "RESULT docker=True") -and ($out -match "RESULT socket=True")) $out

    $out = Invoke-Snippet @"
. '$EnvSetupLib'
. '$RuntimeLib'
`$root = Join-Path '$StubDir' 'paddedkey'
New-Item -ItemType Directory -Path `$root -Force | Out-Null
Set-Content -Path (Join-Path `$root '.env') -Value "LLM_API_KEY=sk-aaaaaaaaaaaa`nLLM_MODEL = padded-model`n"
Ensure-EnvFile -ProjectRoot `$root -SandboxSocket '/run/user/1000/podman/podman.sock' -NonInteractive | Out-Null
Load-DotEnv `$root
Write-Output ("RESULT model=" + `$env:LLM_MODEL)
"@
    Assert-True "a space-padded key is repaired rather than trusted" ($out -match "RESULT model=deepseek-v4-flash") $out

    $out = Invoke-Snippet @"
. '$EnvSetupLib'
`$ErrorActionPreference = 'Stop'
`$root = Join-Path '$StubDir' 'emptyenv'
New-Item -ItemType Directory -Path `$root -Force | Out-Null
Set-Content -Path (Join-Path `$root '.env') -Value '' -NoNewline
`$r = Ensure-EnvFile -ProjectRoot `$root -SandboxSocket '/run/user/1000/podman/podman.sock' -NonInteractive -ApiKey 'sk-test-not-a-real-key-0000'
`$text = Get-Content (Join-Path `$root '.env') -Raw
Write-Output ("RESULT added=" + (`$r.AddedKeys -join ','))
Write-Output ("RESULT hasKey=" + (`$text -match 'LLM_API_KEY=sk-test-not-a-real-key-0000'))
"@
    # `Get-Content -Raw` is $null for a 0-byte file, so calling .TrimEnd() on it is fatal - but
    # only under the launcher's own $ErrorActionPreference = 'Stop' (set above). Left at the
    # default the same call is merely reported and the script muddles through with $content
    # still null, which would hide the defect behind a passing assertion.
    Assert-True "a 0-byte .env is treated as having no keys" `
        (($out -match "RESULT added=LLM_API_KEY,LLM_BASE_URL,LLM_MODEL,CONTAINER_RUNTIME,SANDBOX_SOCKET") -and ($out -match "RESULT hasKey=True")) $out

    Write-Host "Initialize-MavenShim scenarios:" -ForegroundColor Cyan

    $out = Invoke-Snippet @"
. '$PreflightLib'
`$root = Join-Path '$StubDir' 'mvnshim'
New-Item -ItemType Directory -Path (Join-Path `$root '.mvn/wrapper') -Force | Out-Null
Set-Content -Path (Join-Path `$root '.mvn/wrapper/maven-wrapper.jar') -Value 'stub'
`$dir = Initialize-MavenShim -ProjectRoot `$root -RunDir (Join-Path `$root '.run')
Write-Output ("RESULT dirSet=" + [bool]`$dir)
Write-Output ("RESULT shimExists=" + (Test-Path (Join-Path `$dir 'mvn.cmd')))
Write-Output ("RESULT shimContent=" + ((Get-Content (Join-Path `$dir 'mvn.cmd') -Raw) -match 'org.apache.maven.wrapper.MavenWrapperMain'))
"@ -PathReplace $EmptyPathDir
    Assert-True "writes a wrapper shim when the jar exists" (($out -match "RESULT dirSet=True") -and ($out -match "RESULT shimExists=True") -and ($out -match "RESULT shimContent=True")) $out

    $out = Invoke-Snippet @"
. '$PreflightLib'
`$root = Join-Path '$StubDir' 'nomvn'
New-Item -ItemType Directory -Path `$root -Force | Out-Null
`$dir2 = Initialize-MavenShim -ProjectRoot `$root -RunDir (Join-Path `$root '.run')
Write-Output ("RESULT noJar=" + [bool]`$dir2)
"@ -PathReplace $EmptyPathDir
    Assert-True "returns nothing when neither mvn nor the wrapper jar exists" ($out -match "RESULT noJar=False") $out

    $out = Invoke-Snippet @"
. '$PreflightLib'
`$real = Join-Path '$ProjectRoot' 'agent-control-tower'
`$dir = Initialize-MavenShim -ProjectRoot `$real -RunDir (Join-Path '$StubDir' 'shimexec')
`$text = Get-Content (Join-Path `$dir 'mvn.cmd') -Raw
Write-Output ("RESULT multiModule=" + (`$text -match 'multiModuleProjectDirectory'))
Write-Output ("RESULT wrapperMain=" + (`$text -match 'MavenWrapperMain'))
"@ -PathReplace $EmptyPathDir
    Assert-True "the shim sets multiModuleProjectDirectory" (($out -match "RESULT multiModule=True") -and ($out -match "RESULT wrapperMain=True")) $out

    $out = Invoke-Snippet @"
. '$PreflightLib'
`$real = Join-Path '$ProjectRoot' 'agent-control-tower'
`$dir = Initialize-MavenShim -ProjectRoot `$real -RunDir (Join-Path '$StubDir' 'shimrun')
`$banner = & (Join-Path `$dir 'mvn.cmd') -v 2>&1 | Out-String
Write-Output ("RESULT exit=" + `$LASTEXITCODE)
Write-Output ("RESULT maven=" + (`$banner -match 'Apache Maven'))
"@ -PathReplace $JavaOnlyDir
    Assert-True "the shim actually boots Maven" (($out -match "RESULT exit=0") -and ($out -match "RESULT maven=True")) $out

    Write-Host "Get-PortHolder scenarios:" -ForegroundColor Cyan

    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $busyPort = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    try {
        $out = Invoke-Snippet @"
. '$PreflightLib'
`$holder = Get-PortHolder -Port $busyPort
Write-Output ("RESULT busy=" + [bool]`$holder)
Write-Output ("RESULT pid=" + `$holder.Pid)
`$free = Get-PortHolder -Port 1
Write-Output ("RESULT free=" + [bool]`$free)
"@
        Assert-True "reports a holder for a busy port" ($out -match "RESULT busy=True") $out
        Assert-True "reports no holder for a free port" ($out -match "RESULT free=False") $out
    } finally {
        $listener.Stop()
    }

    Write-Host "Wait-HttpHealthy scenarios:" -ForegroundColor Cyan

    # The fake health endpoint must serve from its own process: Invoke-Snippet blocks
    # the parent until the child exits, so an in-process listener could never accept
    # while the snippet runs. Start-FakeHttpServer owns the socket plumbing.
    $jsonServer = Start-FakeHttpServer -ContentType 'application/json' -Body '{"status":"UP"}'
    try {
        $out = Invoke-Snippet @"
. '$PreflightLib'
Write-Output ("RESULT up=" + (Wait-HttpHealthy -Url 'http://127.0.0.1:$($jsonServer.Port)/actuator/health' -TimeoutSeconds 20 -ExpectBodyMatch '"status"\s*:\s*"UP"'))
Write-Output ("RESULT wrongBody=" + (Wait-HttpHealthy -Url 'http://127.0.0.1:$($jsonServer.Port)/actuator/health' -TimeoutSeconds 2 -ExpectBodyMatch 'NEVER_MATCHES'))
Write-Output ("RESULT unreachable=" + (Wait-HttpHealthy -Url 'http://127.0.0.1:1/health' -TimeoutSeconds 2))
"@
        Assert-True "returns true when the body matches" ($out -match "RESULT up=True") $out
        Assert-True "returns false when the body never matches" ($out -match "RESULT wrongBody=False") $out
        Assert-True "returns false when unreachable" ($out -match "RESULT unreachable=False") $out
    } finally {
        Stop-FakeHttpServer $jsonServer
    }

    # Spring Boot's actuator answers with a vendor media type
    # (application/vnd.spring-boot.actuator.v3+json) that PowerShell does not decode, so
    # $resp.Content is a byte[] and -match on it silently never matches. The timeout is
    # deliberately short (5s) and the elapsed time is asserted, so passing-by-expiry -
    # the actual bug - cannot masquerade as a pass.
    $vendorServer = Start-FakeHttpServer -ContentType 'application/vnd.spring-boot.actuator.v3+json' -Body '{"status":"UP"}'
    try {
        $out = Invoke-Snippet @"
. '$PreflightLib'
`$sw = [System.Diagnostics.Stopwatch]::StartNew()
`$up = Wait-HttpHealthy -Url 'http://127.0.0.1:$($vendorServer.Port)/actuator/health' -TimeoutSeconds 5 -ExpectBodyMatch '"status"\s*:\s*"UP"'
`$sw.Stop()
Write-Output ("RESULT vendorUp=" + `$up)
Write-Output ("RESULT elapsed=" + [math]::Round(`$sw.Elapsed.TotalSeconds, 2))
"@
        Assert-True "matches a body delivered under the actuator vendor media type" (($out -match "RESULT vendorUp=True") -and ($out -match "RESULT elapsed=[0-4]\.")) $out
    } finally {
        Stop-FakeHttpServer $vendorServer
    }

    Write-Host "Wait-HttpHealthy budget scenarios:" -ForegroundColor Cyan

    # Reviewed defect: -TimeoutSeconds 2 took 5.6s because each attempt used the hardcoded
    # -TimeoutSec 5 and the 500ms sleep ran past the deadline, so the caller's bound was no
    # bound at all. The endpoint hangs, which is the only way to expose that.
    $hangServer = Start-FakeHangingServer
    try {
        $out = Invoke-Snippet @"
. '$PreflightLib'
`$sw = [System.Diagnostics.Stopwatch]::StartNew()
`$down = Wait-HttpHealthy -Url 'http://127.0.0.1:$($hangServer.Port)/actuator/health' -TimeoutSeconds 2
`$sw.Stop()
Write-Output ("RESULT down=" + `$down)
Write-Output ("RESULT elapsed=" + [math]::Round(`$sw.Elapsed.TotalSeconds, 2))
"@
        Assert-True "a single health request never outlives the remaining budget" (($out -match "RESULT down=False") -and ($out -match "RESULT elapsed=[0-2]\.")) $out
    } finally {
        Stop-FakeHttpServer $hangServer
    }

    Write-Host "Sandbox socket and podman machine scenarios:" -ForegroundColor Cyan

    $out = Invoke-Snippet @"
. '$RuntimeLib'
`$env:FAKE_PODMAN_SOCKET = ''
Write-Output ("RESULT none=" + (Get-SandboxSocketPath))
"@ -PathPrepend $FakePodmanDir
    Assert-True "returns nothing when the engine reports no socket" ($out -match "RESULT none=") $out

    $out = Invoke-Snippet @"
. '$RuntimeLib'
`$env:FAKE_PODMAN_SOCKET = 'unix:///run/user/1000/podman/podman.sock'
Write-Output ("RESULT socket=" + (Get-SandboxSocketPath))
"@ -PathPrepend $FakePodmanDir
    Assert-True "resolves the socket from podman info" ($out -match "RESULT socket=/run/user/1000/podman/podman.sock") $out

    $calls = Join-Path $StubDir 'machine-calls.txt'
    $out = Invoke-Snippet @"
. '$RuntimeLib'
`$env:FAKE_PODMAN_MACHINE = 'stopped'
`$env:FAKE_PODMAN_LOG = '$calls'
Write-Output ("RESULT started=" + (Start-PodmanMachineIfNeeded))
"@ -PathPrepend $FakePodmanDir
    Assert-True "starts a stopped podman machine" ($out -match "RESULT started=True") $out
    Assert-True "the machine start was actually issued" ((Get-Content $calls -Raw) -match 'MACHINE-START') $out

    Write-Host "Ensure-OpenSandboxServer / image scenarios:" -ForegroundColor Cyan

    $calls = Join-Path $StubDir 'ensure-calls.txt'
    Remove-Item $calls -ErrorAction SilentlyContinue
    $out = Invoke-Snippet @"
. '$RuntimeLib'
`$env:FAKE_PODMAN_SERVER = ''
`$env:FAKE_PODMAN_IMAGE = ''
`$env:FAKE_PODMAN_LOG = '$calls'
Ensure-OpenSandboxServer -Runtime podman -ProjectRoot '$ProjectRoot'
Ensure-OpencodeSandboxImage -Runtime podman -ProjectRoot '$ProjectRoot'
Write-Output "RESULT done"
"@ -PathPrepend $FakePodmanDir
    $log = if (Test-Path $calls) { Get-Content $calls -Raw } else { '' }
    Assert-True "starts the sandbox server via compose" ($log -match 'compose up -d opensandbox-server') $log
    Assert-True "builds the sandbox image when absent" ($log -match 'build -t aria-conductor/opencode-sandbox:1.1') $log

    Write-Host "start.ps1 -DryRun scenarios:" -ForegroundColor Cyan

    $dryRoot = Join-Path $StubDir "dryrun"
    New-Item -ItemType Directory -Path $dryRoot -Force | Out-Null
    Set-Content -Path (Join-Path $dryRoot '.env') -Value "LLM_API_KEY=sk-test1234567890`n"
    $out = Invoke-Snippet @"
`$env:FAKE_PODMAN_SOCKET = 'unix:///run/user/1000/podman/podman.sock'
& '$ProjectRoot\scripts\start.ps1' -DryRun -NonInteractive -ProjectRoot '$dryRoot'
Write-Output ("RESULT exit=" + `$LASTEXITCODE)
"@ -PathPrepend $FakePodmanDir
    Assert-True "-DryRun exits 0" ($out -match "RESULT exit=0") $out
    Assert-True "-DryRun prints the mode block" (($out -match "Topology\s*:\s*local-dev") -and ($out -match "Provider\s*:\s*opencode") -and ($out -match "Runtime\s*:\s*podman")) $out
    Assert-True "-DryRun leaves the existing .env untouched" ((Get-Content (Join-Path $dryRoot '.env') -Raw) -match 'LLM_API_KEY=sk-test1234567890') $out
    Assert-True "-DryRun writes no .run state" (-not (Test-Path (Join-Path $dryRoot '.run'))) $out

    Write-Host "start.ps1 fresh-checkout -DryRun scenario:" -ForegroundColor Cyan

    # The help text promises -DryRun mutates nothing, so on a checkout with no .env it must
    # neither write one nor fail the key validation it cannot possibly pass.
    $freshRoot = Join-Path $StubDir "dryrun-fresh"
    New-Item -ItemType Directory -Path $freshRoot -Force | Out-Null
    # A nested child, not `& start.ps1`: only a separate process reports the launcher's own
    # exit code. Invoked in-process, a failure leaves $LASTEXITCODE at whatever the last
    # native call set and the exit-code assertion would pass no matter what the run did.
    $out = Invoke-Snippet @"
`$env:FAKE_PODMAN_SOCKET = 'unix:///run/user/1000/podman/podman.sock'
Write-Output (pwsh -NoProfile -File '$ProjectRoot\scripts\start.ps1' -DryRun -NonInteractive -ProjectRoot '$freshRoot' 2>&1 | Out-String)
Write-Output ("RESULT exit=" + `$LASTEXITCODE)
"@ -PathPrepend $FakePodmanDir
    Assert-True "-DryRun succeeds on a checkout without .env" ($out -match "RESULT exit=0") $out
    Assert-True "-DryRun says it would create .env" ($out -match 'would create \.env') $out
    Assert-True "-DryRun on a fresh checkout writes no .env" (-not (Test-Path (Join-Path $freshRoot '.env'))) $out

    Write-Host "start.ps1 -DryRun sparse .env scenario:" -ForegroundColor Cyan

    # A sparse .env is what proves "Nothing is mutated": a real run appends the missing keys,
    # so any write at all shows up as different bytes. Hashing the whole file also catches a
    # rewrite that happens to keep every key readable.
    $sparseRoot = Join-Path $StubDir "dryrun-sparse"
    New-Item -ItemType Directory -Path $sparseRoot -Force | Out-Null
    Set-Content -Path (Join-Path $sparseRoot '.env') -Value 'LLM_API_KEY=sk-test-not-a-real-key-0000' -NoNewline
    $sparseEnv = Join-Path $sparseRoot '.env'
    $before = (Get-FileHash $sparseEnv -Algorithm SHA256).Hash
    $out = Invoke-Snippet @"
`$env:FAKE_PODMAN_SOCKET = 'unix:///run/user/1000/podman/podman.sock'
Write-Output (pwsh -NoProfile -File '$ProjectRoot\scripts\start.ps1' -DryRun -NonInteractive -ProjectRoot '$sparseRoot' 2>&1 | Out-String)
Write-Output ("RESULT exit=" + `$LASTEXITCODE)
"@ -PathPrepend $FakePodmanDir
    $after = (Get-FileHash $sparseEnv -Algorithm SHA256).Hash
    Assert-True "-DryRun exits 0 on a sparse .env" ($out -match "RESULT exit=0") $out
    Assert-True "-DryRun reports the keys it would add" ($out -match 'would add to \.env:') $out
    Assert-True "-DryRun leaves a sparse .env byte-identical" ($before -eq $after) "before=$before after=$after $out"

    Write-Host "start.ps1 port-consent scenario:" -ForegroundColor Cyan

    # Regression guard for the fail-open consent prompt. With stdin not connected the
    # harness's child gets AutomationNull from Read-Host, and `AutomationNull -notmatch
    # '^(?i)y'` evaluates to nothing at all (falsy), so a guard written that way falls
    # through to taskkill without ever asking. Every port the launcher pre-checks is held
    # here, by this harness, so whichever way the run goes it can only kill our own holders
    # and can never touch a real backend, frontend or sandbox.
    $consentRoot = Join-Path $StubDir "consent"
    New-Item -ItemType Directory -Path $consentRoot -Force | Out-Null
    $backendHolder = Start-PortHolder
    $sandboxHolder = Start-PortHolder
    $frontendHolder = Start-PortHolder
    Assert-True "the harness holds a port for the consent scenario" `
        ([bool]$backendHolder.Port -and [bool]$sandboxHolder.Port -and [bool]$frontendHolder.Port) "a holder published no port"
    if ($backendHolder.Port -and $sandboxHolder.Port -and $frontendHolder.Port) {
        Set-Content -Path (Join-Path $consentRoot '.env') -Value (@(
                'LLM_API_KEY=sk-test-not-a-real-key-0000',
                "BACKEND_PORT=$($backendHolder.Port)",
                "OPENSANDBOX_PORT=$($sandboxHolder.Port)",
                "VITE_PORT=$($frontendHolder.Port)",
                ''
            ) -join "`n")
        # A FILE at .run makes the launcher abort at phase 5, because Start-Process cannot
        # redirect into `<file>/backend.log`. That keeps a run whose guard failed open from
        # starting a real backend/frontend on the host. It is inert once the guard works -
        # the guard throws in phase 4, long before .run is touched.
        Set-Content -Path (Join-Path $consentRoot '.run') -Value 'blocker'
        $out = Invoke-Snippet @"
`$env:FAKE_PODMAN_SOCKET = 'unix:///run/user/1000/podman/podman.sock'
try { & '$ProjectRoot\scripts\start.ps1' -ProjectRoot '$consentRoot' } catch { Write-Output ("RESULT threw=" + `$_.Exception.Message) }
Write-Output ("RESULT exit=" + `$LASTEXITCODE)
"@ -PathPrepend $FakePodmanDir
        Assert-True "an unanswered port prompt aborts instead of killing" ($out -match 'Aborted') $out
        Assert-True "no port holder is killed without consent" `
            (([bool](Get-Process -Id $backendHolder.Process.Id -ErrorAction SilentlyContinue)) -and
            ([bool](Get-Process -Id $sandboxHolder.Process.Id -ErrorAction SilentlyContinue)) -and
            ([bool](Get-Process -Id $frontendHolder.Process.Id -ErrorAction SilentlyContinue))) $out
    }
    Stop-PortHolder $backendHolder
    Stop-PortHolder $sandboxHolder
    Stop-PortHolder $frontendHolder

    Write-Host "start.ps1 sandbox-port scenario:" -ForegroundColor Cyan

    # Ensure-OpenSandboxServer owns the sandbox port, so the port pre-check must not report our
    # own `aria-opensandbox` port forward as a conflict and offer to kill it. FAKE_PODMAN_SERVER
    # reports the container as already running, which is the restart case that used to trip it.
    $sandboxRoot = Join-Path $StubDir "sandboxport"
    New-Item -ItemType Directory -Path $sandboxRoot -Force | Out-Null
    $sandboxHolder2 = Start-PortHolder
    Assert-True "the harness holds the sandbox port for the restart scenario" ([bool]$sandboxHolder2.Port) "holder published no port"
    if ($sandboxHolder2.Port) {
        Set-Content -Path (Join-Path $sandboxRoot '.env') -Value (@(
                'LLM_API_KEY=sk-test-not-a-real-key-0000',
                "BACKEND_PORT=$(Get-FreeLoopbackPort)",
                "VITE_PORT=$(Get-FreeLoopbackPort)",
                "OPENSANDBOX_PORT=$($sandboxHolder2.Port)",
                ''
            ) -join "`n")
        # Same phase-5 blocker as the consent scenario: this run is supposed to get *past* the
        # port pre-check, and it must not launch a real backend to prove that.
        Set-Content -Path (Join-Path $sandboxRoot '.run') -Value 'blocker'
        $out = Invoke-Snippet @"
`$env:FAKE_PODMAN_SERVER = 'running'
`$env:FAKE_PODMAN_SOCKET = 'unix:///run/user/1000/podman/podman.sock'
try { & '$ProjectRoot\scripts\start.ps1' -ProjectRoot '$sandboxRoot' } catch { Write-Output ("RESULT threw=" + `$_.Exception.Message) }
Write-Output ("RESULT exit=" + `$LASTEXITCODE)
"@ -PathPrepend $FakePodmanDir
        Assert-True "the sandbox port is not treated as a conflict" `
            (($out -notmatch "port $($sandboxHolder2.Port) is held by") -and ($out -match '\[5/8\]')) $out
        Assert-True "the sandbox listener survives the port check" `
            ([bool](Get-Process -Id $sandboxHolder2.Process.Id -ErrorAction SilentlyContinue)) $out
    }
    Stop-PortHolder $sandboxHolder2

    Write-Host "start.ps1 docker-runtime socket scenario:" -ForegroundColor Cyan

    # A podman socket must never be pinned next to CONTAINER_RUNTIME=docker. podman is on PATH
    # here (and answers `info`), so a flow that resolves the socket unconditionally writes the
    # podman path into .env - the mixed pair this scenario forbids. The placeholder key stops
    # the run in phase 2, after .env has been written, which is the write under test.
    $dockerRoot = Join-Path $StubDir "dockerruntime"
    New-Item -ItemType Directory -Path $dockerRoot -Force | Out-Null
    Set-Content -Path (Join-Path $dockerRoot '.env') -Value "LLM_API_KEY=your-api-key-here`nCONTAINER_RUNTIME=docker`n"
    $out = Invoke-Snippet @"
`$env:FAKE_PODMAN_SOCKET = 'unix:///run/user/1000/podman/podman.sock'
try { & '$ProjectRoot\scripts\start.ps1' -NonInteractive -ProjectRoot '$dockerRoot' } catch { Write-Output ("RESULT threw=" + `$_.Exception.Message) }
`$text = Get-Content (Join-Path '$dockerRoot' '.env') -Raw
Write-Output ("RESULT podmanSocket=" + (`$text -match 'podman\.sock'))
Write-Output ("RESULT dockerSocket=" + (`$text -match 'SANDBOX_SOCKET=/var/run/docker\.sock'))
"@ -Stubs @{ 'docker.ps1' = "exit 0`n" } -PathPrepend $FakePodmanDir
    Assert-True "the docker path pins docker's socket, never a podman one" `
        (($out -match "RESULT podmanSocket=False") -and ($out -match "RESULT dockerSocket=True")) $out

    Write-Host "start.ps1 stopped-podman-machine scenario:" -ForegroundColor Cyan

    # A stopped podman machine makes `podman info` fail, and the launcher used to read that as
    # "podman is unusable", pick docker and write CONTAINER_RUNTIME=docker into .env, where it
    # stuck. The stub below fails `info` until `machine start` has run, and a working docker
    # stub is on PATH, so the wrong choice would resolve cleanly and be recorded silently
    # instead of erroring out - which is what makes this scenario able to fail.
    $stoppedMachinePodman = @'
# No param() block, and $args rather than named parameters, for the same reason as the
# shared fake podman: dash options would otherwise bind to PowerShell common parameters.
$joined = ($args -join ' ')
$marker = $env:FAKE_PODMAN_MARKER
switch -Regex ($joined) {
    '^info' {
        # The engine is unreachable until the machine has been started.
        if ($marker -and (Test-Path $marker)) { Write-Output $env:FAKE_PODMAN_SOCKET; exit 0 }
        exit 1
    }
    '^machine list' { Write-Output 'podman-machine-default* wsl 1 day ago Never'; exit 0 }
    'machine start' { if ($marker) { Set-Content -Path $marker -Value 'started' }; exit 0 }
    default         { exit 0 }
}
'@
    $stoppedRoot = Join-Path $StubDir "stoppedmachine"
    New-Item -ItemType Directory -Path $stoppedRoot -Force | Out-Null
    $machineMarker = Join-Path $StubDir "machine-started.txt"
    Remove-Item $machineMarker -ErrorAction SilentlyContinue
    Set-Content -Path (Join-Path $stoppedRoot '.env') -Value (@(
            'LLM_API_KEY=sk-test-not-a-real-key-0000',
            "BACKEND_PORT=$(Get-FreeLoopbackPort)",
            "VITE_PORT=$(Get-FreeLoopbackPort)",
            ''
        ) -join "`n")
    # Same phase-5 blocker: once the runtime is chosen correctly this run has nothing left to
    # prove, and it must not launch a real backend on the host.
    Set-Content -Path (Join-Path $stoppedRoot '.run') -Value 'blocker'
    $out = Invoke-Snippet @"
`$env:FAKE_PODMAN_SOCKET = 'unix:///run/user/1000/podman/podman.sock'
`$env:FAKE_PODMAN_MARKER = '$machineMarker'
try { & '$ProjectRoot\scripts\start.ps1' -ProjectRoot '$stoppedRoot' } catch { Write-Output ("RESULT threw=" + `$_.Exception.Message) }
`$text = Get-Content (Join-Path '$stoppedRoot' '.env') -Raw
Write-Output ("RESULT podman=" + (`$text -match 'CONTAINER_RUNTIME=podman'))
Write-Output ("RESULT docker=" + (`$text -match 'CONTAINER_RUNTIME=docker'))
"@ -Stubs @{ 'podman.ps1' = $stoppedMachinePodman; 'docker.ps1' = "exit 0`n" }
    Assert-True "a stopped podman machine is started, not swapped for docker" `
        (($out -match "RESULT podman=True") -and ($out -match "RESULT docker=False")) $out
    Assert-True "the machine start was issued before the runtime was chosen" (Test-Path $machineMarker) $out

    Write-Host "start.ps1 -Mode compose scenarios:" -ForegroundColor Cyan

    $composeCalls = Join-Path $StubDir 'compose-calls.txt'
    Remove-Item $composeCalls -ErrorAction SilentlyContinue
    $composeRoot = Join-Path $StubDir 'composeroot'
    New-Item -ItemType Directory -Path $composeRoot -Force | Out-Null
    Set-Content -Path (Join-Path $composeRoot '.env') -Value "LLM_API_KEY=sk-test1234567890`n"
    $out = Invoke-Snippet @"
`$env:FAKE_PODMAN_LOG = '$composeCalls'
`$env:FAKE_PODMAN_SOCKET = 'unix:///run/user/1000/podman/podman.sock'
& '$ProjectRoot\scripts\start.ps1' -Mode compose -NonInteractive -ProjectRoot '$composeRoot'
Write-Output ("RESULT exit=" + `$LASTEXITCODE)
"@ -PathPrepend $FakePodmanDir
    $composeLog = if (Test-Path $composeCalls) { Get-Content $composeCalls -Raw } else { '' }
    Assert-True "-Mode compose brings up the compose stack" ($composeLog -match 'compose up -d --build') $out
    Assert-True "-Mode compose warns that opencode is unusable there" ($out -match 'opencode provider is NOT usable') $out
    Assert-True "-Mode compose exits 0" ($out -match "RESULT exit=0") $out

    Write-Host "stop.ps1 scenarios:" -ForegroundColor Cyan

    $stopRoot = Join-Path $StubDir "stop"
    $stopRun = Join-Path $stopRoot ".run"
    New-Item -ItemType Directory -Path $stopRun -Force | Out-Null
    $victim = Start-Process pwsh -ArgumentList @('-NoProfile', '-Command', 'Start-Sleep 300') -PassThru
    Set-Content -Path (Join-Path $stopRun 'backend.pid') -Value $victim.Id
    $out = Invoke-Snippet @"
& '$ProjectRoot\scripts\stop.ps1' -ProjectRoot '$stopRoot' -SkipContainers
Write-Output ("RESULT exit=" + `$LASTEXITCODE)
Write-Output ("RESULT runDirGone=" + (-not (Test-Path '$stopRun')))
"@
    Start-Sleep -Seconds 2
    Assert-True "stop.ps1 exits 0" ($out -match "RESULT exit=0") $out
    Assert-True "stop.ps1 terminates the recorded processes" (-not (Get-Process -Id $victim.Id -ErrorAction SilentlyContinue)) $out
    Assert-True "stop.ps1 removes .run" ($out -match "RESULT runDirGone=True") $out
} finally {
    Remove-Item $StubDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ""
if ($failures -gt 0) {
    Write-Host "$failures scenario(s) FAILED" -ForegroundColor Red
    exit 1
}
Write-Host "All scenarios PASSED" -ForegroundColor Green
exit 0
