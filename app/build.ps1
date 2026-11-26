param([string]$SigningKey='')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot
$work=Join-Path $project 'build/chiliplayer'
$sdk='C:/Users/87363/AppData/Local/Android/Sdk'
$jdk='C:/Program Files/Android/Android Studio/jbr'
$android="$sdk/platforms/android-36/android.jar"
$toolsDir="$sdk/build-tools/35.0.0"
$version=Get-Content -LiteralPath "$PSScriptRoot/version.json" -Raw | ConvertFrom-Json
if($version.name -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'){throw 'Version name must use MAJOR.MINOR.PATCH'}
if($version.code -isnot [long] -and $version.code -isnot [int]){throw 'Version code must be an integer'}
if($version.code -le 17 -or $version.code -gt 2100000000){throw 'Version code must exceed legacy version 17 and fit Android limits'}
$abis=@('armeabi-v7a','arm64-v8a')
$libraries=@('libbass.so','libbass_aac.so','libbassalac.so','libbassape.so','libbassflac.so','libbassopus.so')
$nativeHashes=Get-Content -LiteralPath "$PSScriptRoot/native/SHA256.json" -Raw | ConvertFrom-Json
foreach($abi in $abis){foreach($library in $libraries){
    $relative="$abi/$library";$entry=@($nativeHashes | Where-Object path -eq $relative)
    if($entry.Count -ne 1 -or !(Test-Path -LiteralPath "$PSScriptRoot/native/$relative")){throw "Missing native library: $relative"}
    if((Get-FileHash -LiteralPath "$PSScriptRoot/native/$relative" -Algorithm SHA256).Hash -ne $entry[0].sha256){throw "Native library hash mismatch: $relative"}
}}
$out=Join-Path $project "dist/ChiliPlayer-$($version.name).apk"
function run([string]$program,[string[]]$arguments){& $program @arguments;if($LASTEXITCODE){throw "$program failed ($LASTEXITCODE)"}}
New-Item -Type Directory -Force "$work/classes","$work/gen","$work/dex","$project/dist" | Out-Null
if(!$SigningKey){
    $SigningKey="$work/signing.p12"
    if(!(Test-Path -LiteralPath $SigningKey) -and (Test-Path -LiteralPath "$project/build/car/signing.p12")){Copy-Item -LiteralPath "$project/build/car/signing.p12" -Destination $SigningKey}
}
if(!(Test-Path -LiteralPath $SigningKey)){throw 'Original signing key is required to create an upgrade-compatible APK; provide -SigningKey or build/chiliplayer/signing.p12'}
Get-ChildItem -LiteralPath "$work/classes" -Filter '*.class' -Recurse -File | ForEach-Object {Remove-Item -LiteralPath $_.FullName}
run "$toolsDir/aapt.exe" @('package','-f','-m','--version-code',"$($version.code)",'--version-name',$version.name,'-J',"$work/gen",'-M',"$PSScriptRoot/AndroidManifest.xml",'-S',"$PSScriptRoot/res",'-A',"$PSScriptRoot/assets",'-I',$android,'-F',"$work/base.apk")
$java=@(Get-ChildItem "$PSScriptRoot/src","$PSScriptRoot/vendor","$work/gen" -Filter '*.java' -Recurse -File | ForEach-Object FullName)
run "$jdk/bin/javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',"$toolsDir/core-lambda-stubs.jar;$android",'-d',"$work/classes")+$java)
run "$jdk/bin/jar.exe" @('cf',"$work/code.jar",'-C',"$work/classes",'.')
run "$jdk/bin/java.exe" @('-cp',"$toolsDir/lib/d8.jar",'com.android.tools.r8.D8','--min-api','23','--lib',$android,'--output',"$work/dex","$work/code.jar")
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open("$work/base.apk",'Update')
try{
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$work/dex/classes.dex",'classes.dex') | Out-Null
    foreach($abi in $abis){foreach($library in $libraries){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$PSScriptRoot/native/$abi/$library","lib/$abi/$library") | Out-Null}}
}finally{$zip.Dispose()}
run "$toolsDir/zipalign.exe" @('-f','4',"$work/base.apk","$work/aligned.apk")
run "$jdk/bin/java.exe" @('-jar',"$toolsDir/lib/apksigner.jar",'sign','--ks',$SigningKey,'--ks-pass','pass:android','--v4-signing-enabled','false','--out',$out,"$work/aligned.apk")
run "$jdk/bin/java.exe" @('-jar',"$toolsDir/lib/apksigner.jar",'verify','--verbose','--min-sdk-version','23',$out)
$certificate=& "$jdk/bin/java.exe" -jar "$toolsDir/lib/apksigner.jar" verify --print-certs $out
if($LASTEXITCODE -or (($certificate -join "`n") -notmatch 'certificate SHA-256 digest: 9c147bd46cae96305c66eefb076454e2d386e18dcb6e6f95eabfc6e3305e68e2')){throw 'Signing certificate does not match ChiliPlayer 0.3.7.5'}
"Built $out ($((Get-Item -LiteralPath $out).Length) bytes)"
