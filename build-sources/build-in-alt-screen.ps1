$esc = [char]27
$exitCode = 0

try {
    [Console]::Write($esc + '[?1049h' + $esc + '[2J' + $esc + '[H')

    Write-Host 'Building Kotlin Toolchain distribution from sources...'

    & cmd.exe /d /c 'kotlin.bat --log-level=warn do buildUnpackedDistribution'
    $exitCode = $LASTEXITCODE

    if ($exitCode -ne 0) {
        Write-Host ''
        Write-Host '!!! Building from sources failed - press enter to exit !!!'
        $null = [Console]::ReadLine()
    }
}
finally {
    [Console]::Write($esc + '[?1049l')
}

exit $exitCode
