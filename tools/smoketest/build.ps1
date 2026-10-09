# Builds and signs the ticket #2 probe APK without Gradle. Output: build/smoke.apk
$ErrorActionPreference = 'Stop'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = Join-Path $sdk 'build-tools\36.1.0'
$jar = Join-Path $sdk 'platforms\android-36\android.jar'
$jdk = 'C:\Program Files\Android\Android Studio\jbr\bin'
$here = $PSScriptRoot
$b = Join-Path $here 'build'
Remove-Item $b -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$b\classes" | Out-Null

& "$jdk\javac.exe" -source 11 -target 11 -classpath $jar -d "$b\classes" "$here\src\SmokeActivity.java"
$classes = Get-ChildItem "$b\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --lib $jar --min-api 33 --output $b @classes
& "$bt\aapt2.exe" link -o "$b\unsigned.apk" -I $jar --manifest "$here\AndroidManifest.xml" --min-sdk-version 33 --target-sdk-version 36
Push-Location $b
& "$jdk\jar.exe" uf unsigned.apk classes.dex
Pop-Location
& "$bt\zipalign.exe" -f 4 "$b\unsigned.apk" "$b\aligned.apk"
$ks = Join-Path $b 'debug.keystore'
& "$jdk\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=smoke' | Out-Null
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out "$b\smoke.apk" "$b\aligned.apk"
Write-Host "built $b\smoke.apk"
