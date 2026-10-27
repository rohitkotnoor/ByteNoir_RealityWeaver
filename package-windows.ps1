param(
    [ValidateSet("app-image", "exe", "msi")]
    [string] $PackageType = "app-image"
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
$targetDirectory = Join-Path $projectRoot "target"
$inputDirectory = Join-Path $targetDirectory "jpackage-input"
$outputDirectory = Join-Path $targetDirectory "dist"
$runtimeHome = Join-Path $projectRoot ".runtime-home"
$mavenUserHome = Join-Path $projectRoot ".mvn-home"
$mavenRepository = Join-Path $projectRoot ".maven-cache"
$mavenWrapper = Join-Path $projectRoot "mvnw.cmd"
$appJarName = "realityweaver-1.0.0-SNAPSHOT.jar"
$appJar = Join-Path $targetDirectory $appJarName

if ($PackageType -ne "app-image") {
    $candle = Get-Command candle.exe -ErrorAction SilentlyContinue
    $light = Get-Command light.exe -ErrorAction SilentlyContinue
    if (-not $candle -or -not $light) {
        throw "Creating a Windows installer requires WiX Toolset 3.x (candle.exe and light.exe on PATH). Use -PackageType app-image for a portable app bundle without WiX."
    }
}

$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    $javaSettings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
    $homeMatch = [regex]::Match($javaSettings, "java\.home\s*=\s*(.+)")
    if ($homeMatch.Success) {
        $javaHome = $homeMatch.Groups[1].Value.Trim()
    }
}
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw "A JDK 21 installation is required. Set JAVA_HOME to its folder."
}
$jpackage = Join-Path $javaHome "bin\jpackage.exe"
if (-not (Test-Path -LiteralPath $jpackage -PathType Leaf)) {
    throw "jpackage.exe was not found under '$javaHome'. Set JAVA_HOME to a full JDK 21 installation."
}

New-Item -ItemType Directory -Path $runtimeHome, $mavenUserHome, $mavenRepository -Force | Out-Null
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$previousMavenOpts = $env:MAVEN_OPTS
$previousMavenUserHome = $env:MAVEN_USER_HOME
try {
    $homeOption = '-Duser.home="' + $runtimeHome + '"'
    $env:JAVA_TOOL_OPTIONS = (@($previousJavaToolOptions, $homeOption) | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    }) -join " "
    $repositoryOption = '-Dmaven.repo.local="' + $mavenRepository + '"'
    $env:MAVEN_OPTS = (@($previousMavenOpts, $repositoryOption) | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    }) -join " "
    $env:MAVEN_USER_HOME = $mavenUserHome

    & $mavenWrapper clean package
    if ($LASTEXITCODE -ne 0) {
        throw "The Maven build or tests failed. The Windows package was not created."
    }

    if (-not (Test-Path -LiteralPath $appJar -PathType Leaf)) {
        throw "The Maven build did not produce $appJarName."
    }
    Copy-Item -LiteralPath $appJar -Destination (Join-Path $inputDirectory $appJarName) -Force

    $resolvedRoot = [IO.Path]::GetFullPath($projectRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $resolvedOutput = [IO.Path]::GetFullPath($outputDirectory)
    if (-not $resolvedOutput.StartsWith($resolvedRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolvedOutput) -ne "dist") {
        throw "Refusing to replace an output folder outside the project's target\dist directory."
    }
    if (Test-Path -LiteralPath $resolvedOutput) {
        Remove-Item -LiteralPath $resolvedOutput -Recurse -Force
    }
    New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null

    $arguments = @(
        "--type", $PackageType,
        "--dest", $resolvedOutput,
        "--name", "RealityWeaver",
        "--app-version", "1.0.0",
        "--vendor", "Softablitz",
        "--description", "Wallpaper discovery, collections, and desktop automation",
        "--module-path", $inputDirectory,
        "--module", "com.realityweaver/com.realityweaver.RealityWeaverApplication"
    )
    if ($PackageType -ne "app-image") {
        $arguments += @(
            "--win-menu",
            "--win-menu-group", "Reality Weaver",
            "--win-shortcut",
            "--win-per-user-install",
            "--win-upgrade-uuid", "5c093d85-33a4-48a3-8ef0-4bce7f4aa65e"
        )
    }

    & $jpackage @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed to create the Windows $PackageType."
    }

    if ($PackageType -eq "app-image") {
        Write-Output "Portable application: $(Join-Path $resolvedOutput 'RealityWeaver\RealityWeaver.exe')"
    } else {
        Write-Output "Windows installer output: $resolvedOutput"
    }
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    $env:MAVEN_OPTS = $previousMavenOpts
    $env:MAVEN_USER_HOME = $previousMavenUserHome
}
