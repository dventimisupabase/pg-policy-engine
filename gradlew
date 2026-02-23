#!/usr/bin/env sh
set -eu

if [ -d "$HOME/.local/share/mise/installs/java/21.0.2" ]; then
  JAVA_HOME="$HOME/.local/share/mise/installs/java/21.0.2"
  export JAVA_HOME
  PATH="$JAVA_HOME/bin:$PATH"
  export PATH
fi

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

echo "ERROR: 'gradle' is not installed and no Gradle wrapper JAR is available." >&2
echo "Install Gradle or commit standard wrapper files under gradle/wrapper/." >&2
exit 1
