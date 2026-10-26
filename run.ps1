param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MavenArgs
)

$ErrorActionPreference = "Stop"

$projectRoot = $PSScriptRoot
$runtimeHome = Join-Path $projectRoot ".runtime-home"
$mavenUserHome = Join-Path $projectRoot ".mvn-home"
$mavenRepository = Join-Path $projectRoot ".maven-cache"
$mavenWrapper = Join-Path $projectRoot "mvnw.cmd"

$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$previousMavenOpts = $env:MAVEN_OPTS
$previousMavenUserHome = $env:MAVEN_USER_HOME

try {
    New-Item -ItemType Directory -Path $runtimeHome, $mavenUserHome, $mavenRepository -Force | Out-Null

    $homeOption = '-Duser.home="' + $runtimeHome + '"'
    $env:JAVA_TOOL_OPTIONS = (@($previousJavaToolOptions, $homeOption) | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    }) -join " "

    $repositoryOption = '-Dmaven.repo.local="' + $mavenRepository + '"'
    $env:MAVEN_OPTS = (@($previousMavenOpts, $repositoryOption) | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    }) -join " "

    $env:MAVEN_USER_HOME = $mavenUserHome

    if (-not $MavenArgs -or $MavenArgs.Count -eq 0) {
        $MavenArgs = @("javafx:run")
    }

    & $mavenWrapper @MavenArgs
    $launchExitCode = $LASTEXITCODE
}
finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    $env:MAVEN_OPTS = $previousMavenOpts
    $env:MAVEN_USER_HOME = $previousMavenUserHome
}

exit $launchExitCode
