# 새 버전 배포: 버전 올리기 -> 릴리스 빌드 -> GitHub Releases 업로드
# 사용법:  .\release.ps1 1.2 "변경 내용"
param(
    [Parameter(Mandatory = $true)][string]$VersionName,
    [string]$Notes = "업데이트"
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"

$gradleFile = "app\build.gradle"
$g = Get-Content $gradleFile -Raw -Encoding UTF8
$code = [int]([regex]::Match($g, 'versionCode (\d+)').Groups[1].Value)
$tag = "v$VersionName"
$prev = (gh release list --limit 1 --json tagName --jq '.[].tagName') 2>$null
if ($prev) {
    # 이전 배포가 있으면 versionCode 를 1 올린다 (워치는 이 숫자로 새 버전을 판단)
    $code++
    $g = $g -replace 'versionCode \d+', "versionCode $code"
}
$g = $g -replace "versionName '[^']*'", "versionName '$VersionName'"
[IO.File]::WriteAllText((Resolve-Path $gradleFile), $g, (New-Object Text.UTF8Encoding $false))

.\gradlew.bat assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "빌드 실패" }

$out = "build\release-upload"
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item "app\build\outputs\apk\release\app-release.apk" "$out\police-escape.apk" -Force
[IO.File]::WriteAllText("$PWD\$out\version.json", "{`"versionCode`":$code,`"versionName`":`"$VersionName`"}", (New-Object Text.UTF8Encoding $false))

gh release create $tag "$out\police-escape.apk" "$out\version.json" --title "$VersionName" --notes "$Notes" --latest
if ($LASTEXITCODE -ne 0) { throw "업로드 실패" }
Copy-Item "$out\police-escape.apk" "$env:USERPROFILE\Desktop\경찰피하기.apk" -Force
Write-Host "배포 완료: $tag (versionCode $code)"
