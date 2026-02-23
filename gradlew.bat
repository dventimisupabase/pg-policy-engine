@ECHO OFF
where gradle >NUL 2>NUL
IF %ERRORLEVEL% NEQ 0 (
  ECHO ERROR: gradle is not installed and no Gradle wrapper JAR is available.
  EXIT /B 1
)
CALL gradle %*
