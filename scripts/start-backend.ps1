#!/usr/bin/env pwsh
param(
    [string]$Profile = "h2",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$BackendDir = Join-Path $ProjectRoot "agent-control-tower"

# Prerequisites check
function Test-Command($cmd, $hint) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Write-Error "$cmd is not installed. $hint"
        exit 1
    }
}

Test-Command "java" "Install JDK 21: https://adoptium.net/"
Test-Command "mvn" "Install Maven 3.9+: https://maven.apache.org/"

Write-Host "Starting Aria Conductor backend..." -ForegroundColor Cyan
Write-Host "  Profile: $Profile"
Write-Host "  Port: 8080"

Set-Location $BackendDir

$jarPath = "act-app\target\act-app-0.1.0-SNAPSHOT.jar"
if (-not $SkipBuild -and -not (Test-Path $jarPath)) {
    Write-Host "Building..." -ForegroundColor Yellow
    mvn clean install -DskipTests -B
    if ($LASTEXITCODE -ne 0) { Write-Error "Build failed"; exit 1 }
}

Write-Host "Launching Spring Boot..." -ForegroundColor Green
mvn spring-boot:run -pl act-app "-Dspring-boot.run.profiles=$Profile" "-Dspring-boot.run.jvmArguments=--enable-preview"