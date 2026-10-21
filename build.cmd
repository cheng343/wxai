@echo off
set "ANDROID_HOME=D:\Android\sdk"
set "ANDROID_SDK_ROOT=D:\Android\sdk"
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
call "%~dp0gradlew.bat" assembleDebug --console=plain %*
