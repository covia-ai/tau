# End-to-end check of the Windows installer: installs the msi silently as an
# administrator would, runs the installed Tau.exe with --self-test twice in a
# throwaway folder (the second run proves the store reopens), checks the
# bundled runtime was the one used, uninstalls, and checks that user data was
# left alone.
#
#   pwsh packaging/e2e-installed.ps1 target\installer\tau-<version>-windows-x64.msi
#
# Writes into $env:E2E_DIR (default target\e2e): the self-test folder, its
# report and logs, and the msiexec logs, for upload when a step fails. The
# check that %USERPROFILE%\.tau survives runs only in CI (GITHUB_ACTIONS), and
# only when none exists yet.
param([Parameter(Mandatory = $true)][string]$Msi)
$ErrorActionPreference = 'Stop'

$msiPath = (Resolve-Path $Msi).Path
$work = if ($env:E2E_DIR) { $env:E2E_DIR } else { Join-Path (Get-Location) 'target\e2e' }
New-Item -ItemType Directory -Force -Path $work | Out-Null
$work = (Resolve-Path $work).Path
$data = Join-Path $work 'self-test'
if (Test-Path $data) { Remove-Item -Recurse -Force $data }

function Fail([string]$message) {
	Write-Host "::error::$message"
	exit 1
}

function Msiexec([string[]]$arguments) {
	$p = Start-Process msiexec.exe -ArgumentList $arguments -Wait -PassThru
	return $p.ExitCode
}

$sentinel = $null
$home_tau = Join-Path $env:USERPROFILE '.tau'
if ($env:GITHUB_ACTIONS -eq 'true' -and -not (Test-Path $home_tau)) {
	New-Item -ItemType Directory -Path $home_tau | Out-Null
	$sentinel = Join-Path $home_tau 'e2e-sentinel.txt'
	Set-Content -Path $sentinel -Value 'Left by the installer end-to-end check; must survive uninstalling.'
	$sentinelHash = (Get-FileHash $sentinel).Hash
}

Write-Host '== Install: msiexec /qn'
$code = Msiexec @('/i', "`"$msiPath`"", '/qn', '/norestart', '/l*v', "`"$work\msi-install.log`"")
if ($code -ne 0) { Fail "msiexec install failed with exit code $code" }

$installDir = Join-Path $env:ProgramFiles 'Tau'
$launcher = Join-Path $installDir 'Tau.exe'
if (-not (Test-Path $launcher)) { Fail "No launcher at $launcher" }

# Some JDK on the machine (the one that built the installer) must not stand in.
Remove-Item Env:JAVA_HOME, Env:JDK_JAVA_OPTIONS, Env:JAVA_TOOL_OPTIONS, Env:TAU_CHARACTER_DIR -ErrorAction SilentlyContinue

foreach ($run in @('first', 'reopen')) {
	Write-Host "== Self-test, $run run"
	# Tau.exe has no console, so the exit code and the report file carry the result.
	$p = Start-Process -FilePath $launcher -ArgumentList @('--self-test', "`"$data`"") -WorkingDirectory $work -Wait -PassThru
	$report = Join-Path $data 'self-test-report.txt'
	if (-not (Test-Path $report)) { Fail "The $run run wrote no report (exit $($p.ExitCode))" }
	Get-Content $report | Select-Object -Last 20 | Write-Host
	if ($p.ExitCode -ne 0) { Fail "The $run run failed with exit code $($p.ExitCode)" }
}

Write-Host '== Check the report'
$lines = Get-Content $report
if (@($lines | Where-Object { $_ -like 'RESULT PASS*' }).Count -ne 2) { Fail 'Not both runs passed' }
if (-not ($lines | Where-Object { $_ -like 'PASS history:*' })) { Fail "The reopen run did not find the first run's chat" }
$runtimeLine = $lines | Where-Object { $_ -like 'PASS runtime:*' } | Select-Object -First 1
Write-Host $runtimeLine
if (-not $runtimeLine.Contains((Join-Path $installDir 'runtime'))) { Fail 'The self-test ran on another Java, not the bundled runtime' }

Write-Host '== Uninstall: msiexec /x'
$code = Msiexec @('/x', "`"$msiPath`"", '/qn', '/norestart', '/l*v', "`"$work\msi-uninstall.log`"")
if ($code -ne 0) { Fail "msiexec uninstall failed with exit code $code" }
if (Test-Path $launcher) { Fail 'Tau.exe is still there after uninstalling' }
if (-not (Test-Path $report)) { Fail "Uninstalling removed the self-test's data folder" }

if ($sentinel) {
	Write-Host '== Check .tau was left alone'
	if (-not (Test-Path $sentinel) -or (Get-FileHash $sentinel).Hash -ne $sentinelHash) { Fail 'Installing or uninstalling changed .tau' }
	Remove-Item -Recurse -Force $home_tau
}
Write-Host 'End-to-end check passed: msi'
