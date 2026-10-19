#!/bin/bash
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Resolve script directory
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")/"$link"
    fi
done
SAVED="$(pwd)"
cd "$(dirname "$PRG")/" >/dev/null
APP_HOME="$(pwd -P)"
cd "$SAVED" >/dev/null

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    if [ -n "$JAVACMD" ] && [ -x "$JAVACMD" ]; then
        :
    else
        JAVACMD=java
        if ! command -v java >/dev/null 2>&1; then
            echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
            exit 1
        fi
    fi
fi

exec "$JAVACMD" \
    -Dorg.gradle.appname=$APP_BASE_NAME \
    -cp "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
