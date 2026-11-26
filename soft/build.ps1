$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot
$work=Join-Path $project 'build/soft'
$sdk=$env:ANDROID_SDK_ROOT
if(!$sdk){$sdk=$env:ANDROID_HOME}
if(!$sdk){$sdk=Join-Path $env:LOCALAPPDATA 'Android/Sdk'}
$jdk=$env:SALTPLAYER_JDK
if(!$jdk){$jdk=Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'}
$android="$sdk/platforms/android-36/android.jar"
$toolsDir="$sdk/build-tools/35.0.0"
$vlc="$work/vlc"
New-Item -ItemType Directory -Path $work -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
if(!(Test-Path "$vlc/classes.jar")){
    $aar=Join-Path $work 'libvlc-3.7.8.aar'
    $checksumPath=Join-Path $PSScriptRoot 'libs/SHA256.txt'
    $expected=((Get-Content -LiteralPath $checksumPath -Raw).Trim() -split '\s+')[0]
    if(!(Test-Path -LiteralPath $aar)){
        $url='https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.8/libvlc-all-3.7.8.aar'
        $download="$aar.download"
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $download
            if((Get-FileHash -LiteralPath $download).Hash -ne $expected){
                throw 'Downloaded LibVLC AAR failed its SHA-256 check.'
            }
            Move-Item -LiteralPath $download -Destination $aar
        } finally {
            if(Test-Path -LiteralPath $download){Remove-Item -LiteralPath $download}
        }
    }
    if((Get-FileHash -LiteralPath $aar).Hash -ne $expected){
        throw "Cached LibVLC AAR failed its SHA-256 check. Remove $aar and build again."
    }
    [IO.Compression.ZipFile]::ExtractToDirectory($aar,$vlc)
}
$out="$project/dist/NewSalt-Soft-0.3.7.1-soft.2.apk"
function run([string]$program,[string[]]$arguments){ & $program @arguments; if($LASTEXITCODE){throw "$program failed ($LASTEXITCODE)"} }
New-Item -Type Directory -Force "$work/classes","$work/gen","$work/dex","$project/dist" | Out-Null
Get-ChildItem -LiteralPath "$work/classes" -Filter '*.class' -Recurse -File | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
run "$toolsDir/aapt.exe" @('package','-f','-m','-J',"$work/gen",'-M',"$PSScriptRoot/AndroidManifest.xml",'--extra-packages','org.videolan','-S',"$PSScriptRoot/res",'-S',"$vlc/res",'-A',"$PSScriptRoot/assets",'-I',$android,'-F',"$work/base.apk")
$java=@(Get-ChildItem "$PSScriptRoot/src","$PSScriptRoot/vendor","$work/gen" -Filter '*.java' -Recurse | ForEach-Object FullName)
run "$jdk/bin/javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',"$toolsDir/core-lambda-stubs.jar;$android",'-cp',"$vlc/classes.jar",'-d',"$work/classes")+$java)
run "$jdk/bin/jar.exe" @('cf',"$work/code.jar",'-C',"$work/classes",'.')
$dependencyJars=@(Get-ChildItem "$PSScriptRoot/libs" -Filter '*.jar' | Where-Object {$_.Name -notlike '*sources*'} | ForEach-Object FullName)
run "$jdk/bin/java.exe" (@('-cp',"$toolsDir/lib/d8.jar",'com.android.tools.r8.D8','--min-api','23','--lib',$android,'--output',"$work/dex","$work/code.jar","$vlc/classes.jar")+$dependencyJars)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open("$work/base.apk",'Update')
try{[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$work/dex/classes.dex",'classes.dex') | Out-Null
foreach($abi in @('armeabi-v7a','arm64-v8a')){Get-ChildItem "$vlc/jni/$abi" -Filter '*.so' | ForEach-Object {[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$_.FullName,"lib/$abi/$($_.Name)") | Out-Null}}
}finally{$zip.Dispose()}
run "$toolsDir/zipalign.exe" @('-f','4',"$work/base.apk","$work/aligned.apk")
if(!(Test-Path "$work/signing.p12") -and (Test-Path "$project/build/car/signing.p12")){Copy-Item "$project/build/car/signing.p12" "$work/signing.p12"}
if(!(Test-Path "$work/signing.p12")){run "$jdk/bin/keytool.exe" @('-genkeypair','-keystore',"$work/signing.p12",'-storepass','android','-keypass','android','-alias','new-salt-test','-keyalg','RSA','-keysize','3072','-validity','7300','-dname','CN=New Salt Local Test')}
run "$jdk/bin/java.exe" @('-jar',"$toolsDir/lib/apksigner.jar",'sign','--ks',"$work/signing.p12",'--ks-pass','pass:android','--v4-signing-enabled','false','--out',$out,"$work/aligned.apk")
run "$jdk/bin/java.exe" @('-jar',"$toolsDir/lib/apksigner.jar",'verify','--verbose','--min-sdk-version','23',$out)
