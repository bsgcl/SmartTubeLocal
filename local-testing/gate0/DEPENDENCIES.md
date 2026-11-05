# Gate 0 live probe dependencies

The two dependency JARs (gson-2.10.1.jar, mbassador-1.3.2.jar) were removed from
version control. Fetch them from Maven Central if missing:

- gson-2.10.1.jar:   https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar
- mbassador-1.3.2.jar: https://repo1.maven.org/maven2/net.engio/mbassador/1.3.2/mbassador-1.3.2.jar

Other jars (smbj, asn-one, bouncycastle, slf4j) are resolved from the Gradle cache
under %USERPROFILE%\.gradle\caches\modules-2\files-2.1 as referenced by run_probe2.bat.
JDK: C:\SmartTube\.tooling\jdk-17.0.20.1+1

Note: the probes authenticate with guest/empty credentials only. The share requires
stored admin credentials (see GATE0-LIVE-STORAGE-PROOF.md); smbj tree-connect
remains BLOCKED pending authorised credentials or on-device testing.
