$ErrorActionPreference='Stop'
$sdk='C:/Users/87363/AppData/Local/Android/Sdk'
$jdk='C:/Program Files/Android/Android Studio/jbr'
$bt="$sdk/build-tools/35.0.0"
$jar="$sdk/platforms/android-36/android.jar"
function run($exe,$arguments){& $exe @arguments;if($LASTEXITCODE){throw "Test build failed"}}
New-Item -Type Directory -Force "$PSScriptRoot/classes","$PSScriptRoot/dex" | Out-Null
run "$bt/aapt.exe" @('package','-f','-M',"$PSScriptRoot/AndroidManifest.xml",'-I',$jar,'-A',"$PSScriptRoot/fixtures",'-F',"$PSScriptRoot/base.apk")
$sources=@(Get-ChildItem $PSScriptRoot -Filter '*.java' | ForEach-Object FullName)
run "$jdk/bin/javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',"$bt/core-lambda-stubs.jar;$jar",'-cp',"$PSScriptRoot/../../build/chiliplayer/classes",'-d',"$PSScriptRoot/classes")+$sources)
run "$jdk/bin/jar.exe" @('cf',"$PSScriptRoot/code.jar",'-C',"$PSScriptRoot/classes",'.')
run "$jdk/bin/java.exe" @('-cp',"$bt/lib/d8.jar",'com.android.tools.r8.D8','--min-api','23','--lib',$jar,'--classpath',"$PSScriptRoot/../../build/chiliplayer/code.jar",'--output',"$PSScriptRoot/dex","$PSScriptRoot/code.jar")
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open("$PSScriptRoot/base.apk",'Update')
try{[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$PSScriptRoot/dex/classes.dex",'classes.dex')|Out-Null}finally{$zip.Dispose()}
run "$jdk/bin/java.exe" @('-jar',"$bt/lib/apksigner.jar",'sign','--ks',"$PSScriptRoot/../../build/chiliplayer/signing.p12",'--ks-pass','pass:android','--out',"$PSScriptRoot/test.apk","$PSScriptRoot/base.apk")

