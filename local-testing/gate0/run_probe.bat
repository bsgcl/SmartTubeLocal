@echo off
set JH=C:\SmartTube\.tooling\jdk-17.0.20.1+1
set G=%USERPROFILE%\.gradle\caches\modules-2\files-2.1
set CP=C:\SmartTube-stage3\local-testing\gate0\classes
set CP=%CP%;C:\SmartTube\.tooling\android-sdk\platforms\android-35\android.jar
set CP=%CP%;%G%\com.hierynomus\smbj\0.14.0\59cb9bc643fa44144700cc3ba479c3f3555f515c\smbj-0.14.0.jar
set CP=%CP%;%G%\com.hierynomus\asn-one\0.6.0\7f6141541e0a4d3ae0b02c901c2c1e9a772eb3d7\asn-one-0.6.0.jar
set CP=%CP%;%G%\org.bouncycastle\bcprov-jdk15on\1.70\4636a0d01f74acaf28082fb62b317f1080118371\bcprov-jdk15on-1.70.jar
set CP=%CP%;%G%\org.bouncycastle\bcpkix-jdk15on\1.67\5f48020a2a60a8d6bcbecceca23529d225b28efb\bcpkix-jdk15on-1.67.jar
set CP=%CP%;%G%\org.slf4j\slf4j-api\1.7.30\b5a4b6d16ab13e34a88fae84c35cd5d68cac922c\slf4j-api-1.7.30.jar
set CP=%CP%;C:\SmartTube-stage3\local-testing\gate0\gson-2.10.1.jar
set CP=%CP%;C:\SmartTube-stage3\local-testing\gate0\mbassador-1.3.2.jar
"%JH%\bin\java.exe" -cp "%CP%" Gate0LiveProbe
